package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.impl.Diffable;

/** One row in an SMB directory listing: either a folder or a video file. */
public class SmbItem implements Diffable<SmbItem> {

    public static final int THUMB_NONE = 0;
    public static final int THUMB_LOADING = 1;
    public static final int THUMB_READY = 2;
    public static final int THUMB_FAILED = 3;

    private final String serverId;
    private final String name;
    private final String relPath;
    private final boolean dir;
    private final long size;
    private final long time;

    private String url;
    private String thumb;
    private int thumbState;
    private boolean serverEntry;

    /**
     * A tile standing for a whole server, shown at the top level when more than
     * one share is configured. Marked as a directory so navigation treats it the
     * same way, but carries no path of its own.
     */
    public static SmbItem server(SmbServer server) {
        SmbItem item = new SmbItem(server.getId(), server.getName(), "", true, 0, server.getTime());
        item.serverEntry = true;
        return item;
    }

    public SmbItem(String serverId, String name, String relPath, boolean dir, long size, long time) {
        this.serverId = serverId;
        this.name = name;
        this.relPath = relPath;
        this.dir = dir;
        this.size = size;
        this.time = time;
    }

    public String getServerId() {
        return TextUtils.isEmpty(serverId) ? "" : serverId;
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? "" : name;
    }

    public String getRelPath() {
        return TextUtils.isEmpty(relPath) ? "" : relPath;
    }

    public boolean isDir() {
        return dir;
    }

    /** True when this tile selects a server rather than a folder. */
    public boolean isServerEntry() {
        return serverEntry;
    }

    public long getSize() {
        return size;
    }

    public long getTime() {
        return time;
    }

    /** The full {@code smb://} URL, cached after the first build. */
    public String getUrl() {
        return TextUtils.isEmpty(url) ? "" : url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    /** Absolute path of the cached JPEG, or empty while none exists. */
    public String getThumb() {
        return TextUtils.isEmpty(thumb) ? "" : thumb;
    }

    public void setThumb(String thumb) {
        this.thumb = thumb;
    }

    public int getThumbState() {
        return thumbState;
    }

    public void setThumbState(int thumbState) {
        this.thumbState = thumbState;
    }

    /** A copy carrying a new thumbnail state, so the differ sees a distinct object. */
    public SmbItem withThumb(String thumb, int state) {
        SmbItem copy = new SmbItem(serverId, name, relPath, dir, size, time);
        copy.setUrl(url);
        copy.setThumb(thumb);
        copy.setThumbState(state);
        copy.serverEntry = serverEntry;
        return copy;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SmbItem it)) return false;
        return isDir() == it.isDir() && isServerEntry() == it.isServerEntry()
                && getServerId().equals(it.getServerId()) && getRelPath().equals(it.getRelPath());
    }

    @Override
    public int hashCode() {
        return (getServerId() + "|" + getRelPath() + "|" + isDir()).hashCode();
    }

    @Override
    public boolean isSameItem(SmbItem other) {
        return equals(other);
    }

    /**
     * Compares the thumbnail too, so a frame arriving later rebinds exactly one
     * cell through {@code AsyncListDiffer} instead of refreshing the whole grid.
     */
    @Override
    public boolean isSameContent(SmbItem other) {
        if (other == null) return false;
        return getSize() == other.getSize()
                && getTime() == other.getTime()
                && getThumbState() == other.getThumbState()
                && getThumb().equals(other.getThumb());
    }
}
