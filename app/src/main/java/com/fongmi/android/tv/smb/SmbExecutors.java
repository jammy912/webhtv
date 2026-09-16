package com.fongmi.android.tv.smb;

import com.fongmi.android.tv.setting.Setting;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Thread pools for SMB work.
 *
 * <p>Directory listing is serialised on a single thread: two listings never race,
 * and a stale one is discarded by request id rather than by cancellation.
 *
 * <p>Thumbnail extraction runs on a small pool fed by a LIFO queue. On a
 * high-latency share each frame costs several round trips, so raising the
 * parallelism mostly buys TCP contention; taking the newest request first means
 * whatever the user is actually looking at gets decoded before older,
 * scrolled-past entries.
 */
public final class SmbExecutors {

    private static ExecutorService io;
    private static ExecutorService search;
    private static ThreadPoolExecutor thumb;
    private static int thumbSize;

    private SmbExecutors() {
    }

    public static synchronized ExecutorService io() {
        if (io == null || io.isShutdown()) io = Executors.newSingleThreadExecutor(runnable -> thread(runnable, "smb-io"));
        return io;
    }

    /**
     * Recursive search runs apart from listing: a deep walk can take a while and
     * must not hold up the navigation the user is still doing.
     */
    public static synchronized ExecutorService search() {
        if (search == null || search.isShutdown()) search = Executors.newSingleThreadExecutor(runnable -> thread(runnable, "smb-search"));
        return search;
    }

    /** Recreated when the user changes the concurrency setting. */
    public static synchronized ThreadPoolExecutor thumb() {
        int size = Setting.getSmbThumbConcurrency();
        if (thumb != null && !thumb.isShutdown() && size == thumbSize) return thumb;
        if (thumb != null) thumb.shutdownNow();
        thumbSize = size;
        thumb = new ThreadPoolExecutor(size, size, 30, TimeUnit.SECONDS, new LifoQueue<>(), runnable -> thread(runnable, "smb-thumb"));
        thumb.allowCoreThreadTimeOut(true);
        return thumb;
    }

    public static synchronized void stopSearch() {
        if (search == null) return;
        search.shutdownNow();
        search = null;
    }

    public static synchronized void stopThumb() {
        if (thumb == null) return;
        thumb.shutdownNow();
        thumb = null;
    }

    private static Thread thread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.setDaemon(true);
        return thread;
    }

    /** A blocking deque that hands out the most recently submitted task first. */
    private static class LifoQueue<E> extends LinkedBlockingDeque<E> {

        @Override
        public boolean offer(E e) {
            return super.offerFirst(e);
        }

        @Override
        public boolean add(E e) {
            return super.offerFirst(e);
        }

        @Override
        public void put(E e) throws InterruptedException {
            super.putFirst(e);
        }
    }
}
