package com.fongmi.android.tv.smb;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.SmbItem;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Extracts a frame from each video and hands back a cached JPEG.
 *
 * <p>Frames are read through {@link SmbHttpProxy}, so {@code MediaMetadataRetriever}
 * issues ranged HTTP requests and pulls only the container index plus the frames
 * around the seek point — a few hundred KB — instead of the whole file. Pointing
 * Glide at the same URL would download everything before decoding.
 */
public final class ThumbLoader {

    private static final String TAG = "smb-thumb";
    private static final long SEEK_US = 3_000_000L;
    private static final long WATCHDOG_MS = 20_000L;
    private static final int TRIP_AFTER = 8;
    private static final long TRIP_MS = 60_000L;

    private static final Map<String, Future<?>> RUNNING = new ConcurrentHashMap<>();
    private static final AtomicInteger FAILURES = new AtomicInteger();
    private static volatile long trippedUntil;

    public interface Callback {

        void onThumb(SmbItem item, String path, boolean ok);
    }

    private ThumbLoader() {
    }

    /**
     * Requests a thumbnail. Cache hits and known failures answer immediately
     * without touching the network.
     */
    public static void request(SmbItem item, int width, int height, Callback callback) {
        if (item == null || item.isDir()) return;
        if (!Setting.getSmbThumbEnabled()) return;
        String key = ThumbCache.key(item);
        File cached = ThumbCache.get(key);
        if (cached != null) {
            callback.onThumb(item, cached.getAbsolutePath(), true);
            return;
        }
        if (ThumbCache.failed(key) || tripped()) {
            callback.onThumb(item, "", false);
            return;
        }
        if (RUNNING.containsKey(key)) return;
        Future<?> future = SmbExecutors.thumb().submit(() -> extract(item, key, width, height, callback));
        RUNNING.put(key, future);
    }

    /** Drops a queued request when its cell scrolls away. */
    public static void cancel(SmbItem item) {
        if (item == null || item.isDir()) return;
        String key = ThumbCache.key(item);
        Future<?> future = RUNNING.get(key);
        // Only cancel work that has not started; a running extraction is allowed
        // to finish because its result still populates the disk cache.
        if (future != null && future.cancel(false)) RUNNING.remove(key);
    }

    public static void reset() {
        RUNNING.clear();
        FAILURES.set(0);
        trippedUntil = 0;
    }

    private static void extract(SmbItem item, String key, int width, int height, Callback callback) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        // A retriever blocked on a stalled socket would pin this thread forever.
        Thread worker = Thread.currentThread();
        Runnable watchdog = () -> {
            if (!worker.isAlive()) return;
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        };
        App.post(watchdog, WATCHDOG_MS);
        String path = "";
        boolean ok = false;
        try {
            String proxyUrl = SmbHttpProxy.shared().proxy(item.getUrl());
            retriever.setDataSource(proxyUrl, new java.util.HashMap<>());
            Bitmap bitmap = frame(retriever, width, height);
            if (bitmap != null) {
                File file = ThumbCache.save(key, bitmap);
                bitmap.recycle();
                if (file != null) {
                    path = file.getAbsolutePath();
                    ok = true;
                }
            }
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "extract failed errorType=%s", e.getClass().getSimpleName());
        } finally {
            App.post(watchdog, -1);
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
            RUNNING.remove(key);
        }
        if (ok) {
            FAILURES.set(0);
        } else {
            ThumbCache.markFailed(key);
            trip();
        }
        final String result = path;
        final boolean success = ok;
        App.post(() -> callback.onThumb(item, result, success));
    }

    /** Seeks past the leader, then falls back before giving up. */
    private static Bitmap frame(MediaMetadataRetriever retriever, int width, int height) {
        Bitmap bitmap = scaled(retriever, SEEK_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height);
        if (bitmap == null) bitmap = scaled(retriever, 0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height);
        if (bitmap == null) bitmap = scaled(retriever, 0, MediaMetadataRetriever.OPTION_CLOSEST, width, height);
        return bitmap;
    }

    private static Bitmap scaled(MediaMetadataRetriever retriever, long timeUs, int option, int width, int height) {
        try {
            if (width > 0 && height > 0) return retriever.getScaledFrameAtTime(timeUs, option, width, height);
            return retriever.getFrameAtTime(timeUs, option);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Stops queueing for a minute once a dead share starts failing everything. */
    private static void trip() {
        if (FAILURES.incrementAndGet() < TRIP_AFTER) return;
        FAILURES.set(0);
        trippedUntil = System.currentTimeMillis() + TRIP_MS;
        SpiderDebug.log(TAG, "circuit tripped for %dms", TRIP_MS);
    }

    private static boolean tripped() {
        return System.currentTimeMillis() < trippedUntil;
    }
}
