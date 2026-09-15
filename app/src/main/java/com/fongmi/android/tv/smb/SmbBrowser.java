package com.fongmi.android.tv.smb;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.SmbItem;
import com.fongmi.android.tv.bean.SmbServer;

import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.share.DiskShare;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lists the folders and playable video files inside an SMB share. */
public final class SmbBrowser {

    /**
     * Extensions worth showing in a video grid.
     *
     * <p>{@code Sniffer.isVideoFormat} cannot be reused: its pattern is anchored
     * on {@code https?://}, so every {@code smb://} path fails it. Audio types are
     * deliberately absent — this grid is for video.
     */
    private static final Set<String> VIDEO = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "ts", "m2ts", "mts",
            "mpg", "mpeg", "webm", "rmvb", "rm", "3gp", "vob", "iso", "ogv", "divx", "asf"));

    /** Housekeeping entries NAS devices leave lying around. */
    private static final Set<String> NOISE = new HashSet<>(Arrays.asList(
            "@eadir", "$recycle.bin", "system volume information", "#recycle", "lost+found"));

    private static final long MIN_SIZE = 1024L * 1024L;

    private SmbBrowser() {
    }

    public static List<SmbItem> list(SmbServer server, String relPath) throws Exception {
        try {
            return read(server, relPath);
        } catch (Exception e) {
            // A stale handle surfaces as an IO error; drop it so a retry reconnects.
            SmbClientPool.evict(server);
            throw e;
        }
    }

    private static List<SmbItem> read(SmbServer server, String relPath) throws Exception {
        DiskShare share = SmbClientPool.acquire(server);
        List<SmbItem> dirs = new ArrayList<>();
        List<SmbItem> files = new ArrayList<>();
        for (FileIdBothDirectoryInformation info : share.list(relPath)) {
            String name = info.getFileName();
            if (skip(name)) continue;
            boolean dir = isDir(info);
            if (!dir && !isVideo(name)) continue;
            long size = info.getEndOfFile();
            if (!dir && size < MIN_SIZE) continue;
            String path = SmbServer.join(relPath, name);
            SmbItem item = new SmbItem(server.getId(), name, path, dir, size, time(info));
            item.setUrl(server.urlFor(path));
            (dir ? dirs : files).add(item);
        }
        Comparator<SmbItem> byName = (a, b) -> a.getName().compareToIgnoreCase(b.getName());
        Collections.sort(dirs, byName);
        Collections.sort(files, byName);
        List<SmbItem> items = new ArrayList<>(dirs.size() + files.size());
        items.addAll(dirs);
        items.addAll(files);
        return items;
    }

    public static boolean isVideo(String name) {
        String ext = extension(name);
        return !ext.isEmpty() && VIDEO.contains(ext);
    }

    private static boolean skip(String name) {
        if (TextUtils.isEmpty(name)) return true;
        if (".".equals(name) || "..".equals(name)) return true;
        if (name.startsWith(".")) return true;
        return NOISE.contains(name.toLowerCase(Locale.US));
    }

    private static boolean isDir(FileIdBothDirectoryInformation info) {
        return (info.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
    }

    private static long time(FileIdBothDirectoryInformation info) {
        try {
            return info.getLastWriteTime().toEpochMillis();
        } catch (Throwable e) {
            return 0;
        }
    }

    private static String extension(String name) {
        if (TextUtils.isEmpty(name)) return "";
        int index = name.lastIndexOf('.');
        if (index < 0 || index == name.length() - 1) return "";
        return name.substring(index + 1).toLowerCase(Locale.US);
    }
}
