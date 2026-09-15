package com.fongmi.android.tv.smb;

import android.graphics.Bitmap;
import android.text.TextUtils;

import com.fongmi.android.tv.bean.SmbItem;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * Stores extracted video frames on disk so a folder is only ever paid for once.
 *
 * <p>Lives under {@code getCacheDir()}: thumbnails are regenerable, so letting
 * Android reclaim them under storage pressure is the right trade.
 */
public final class ThumbCache {

    private static final String TAG = "smb-thumb-cache";
    private static final String SUFFIX = ".jpg";
    private static final String FAIL_SUFFIX = ".fail";
    private static final int QUALITY = 80;
    private static final long FAIL_TTL = TimeUnit.HOURS.toMillis(24);
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_FILES = 2000;

    private ThumbCache() {
    }

    /**
     * Identity of a thumbnail.
     *
     * <p>Credentials are excluded so changing a password does not throw the cache
     * away; size and mtime are included so a replaced file re-extracts.
     */
    public static String key(SmbItem item) {
        String url = item.getUrl();
        String bare = stripCredentials(url);
        return Util.md5(bare + "|" + item.getSize() + "|" + item.getTime());
    }

    public static File file(String key) {
        return new File(Path.thumb(), key + SUFFIX);
    }

    /** The cached frame, or null when absent. */
    public static File get(String key) {
        File file = file(key);
        return file.exists() && file.length() > 0 ? file : null;
    }

    public static File save(String key, Bitmap bitmap) {
        File file = file(key);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, fos);
            return file;
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "save failed errorType=%s", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * True when extraction failed recently enough that retrying is not worth the
     * network cost. Unlike an in-memory blacklist this forgets, so a transient
     * outage does not blank a thumbnail for the life of the process.
     */
    public static boolean failed(String key) {
        File marker = new File(Path.thumb(), key + FAIL_SUFFIX);
        if (!marker.exists()) return false;
        if (System.currentTimeMillis() - marker.lastModified() < FAIL_TTL) return true;
        marker.delete();
        return false;
    }

    public static void markFailed(String key) {
        File marker = new File(Path.thumb(), key + FAIL_SUFFIX);
        try {
            if (marker.exists()) marker.setLastModified(System.currentTimeMillis());
            else marker.createNewFile();
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "mark failed errorType=%s", e.getClass().getSimpleName());
        }
    }

    /** Deletes the oldest entries once the cache exceeds its budget. */
    public static void trim() {
        File[] files = Path.thumb().listFiles();
        if (files == null || files.length == 0) return;
        long total = 0;
        for (File file : files) total += file.length();
        if (total <= MAX_BYTES && files.length <= MAX_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (total <= MAX_BYTES && files.length <= MAX_FILES) break;
            long size = file.length();
            if (file.delete()) total -= size;
        }
    }

    private static String stripCredentials(String url) {
        if (TextUtils.isEmpty(url)) return "";
        int scheme = url.indexOf("://");
        if (scheme < 0) return url;
        int at = url.indexOf('@', scheme);
        if (at < 0) return url;
        return url.substring(0, scheme + 3) + url.substring(at + 1);
    }
}
