package com.fongmi.android.tv.smb;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.SmbDataSource;

import com.fongmi.android.tv.player.PlaybackRouteRegistry;
import com.github.catvod.crawler.SpiderDebug;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * Republishes {@code smb://} sources as range-capable loopback HTTP.
 *
 * <p>Two consumers need this. MPV's native stream layer is built without SMB
 * support (the pinned curl override passes {@code --disable-smb}), so it cannot
 * open an {@code smb://} URL at all. The thumbnail pipeline needs random access
 * from {@code MediaMetadataRetriever}, which speaks HTTP but not SMB. Media3
 * already ships {@link SmbDataSource}, which reads a share at arbitrary offsets,
 * so this bridges the two.
 */
public class SmbHttpProxy extends NanoHTTPD {

    private static final String TAG = "smb-proxy";
    private static final String PATH = "/mpv/smb";
    private static final String MIME_BINARY = "application/octet-stream";
    /** Browsing a large folder registers one session per thumbnail. */
    private static final int MAX_SESSIONS = 256;

    private static SmbHttpProxy shared;

    private final Map<Integer, String> sessions = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
            return size() > MAX_SESSIONS;
        }
    };
    private final AtomicInteger nextId = new AtomicInteger();

    @Nullable
    private PlaybackRouteRegistry.Registration routeRegistration;
    private boolean started;

    public SmbHttpProxy() {
        super("127.0.0.1", 0);
    }

    /** A process-wide instance for callers with no lifecycle of their own. */
    public static synchronized SmbHttpProxy shared() {
        if (shared == null) shared = new SmbHttpProxy();
        return shared;
    }

    /** Returns true when {@code uri} is an SMB source this proxy can serve. */
    public static boolean isSmb(@Nullable String uri) {
        if (TextUtils.isEmpty(uri)) return false;
        return uri.toLowerCase(Locale.US).startsWith("smb://");
    }

    /** Registers {@code url} and returns the loopback HTTP URL to open. */
    public synchronized String proxy(String url) throws IOException {
        ensureStarted();
        int id = nextId.incrementAndGet();
        synchronized (sessions) {
            sessions.put(id, url);
        }
        String proxyUrl = baseUrl() + PATH + "?s=" + id;
        SpiderDebug.log(TAG, "enabled session=%d proxy=%s", id, proxyUrl);
        return proxyUrl;
    }

    /** Drops registered sources; safe to call when no session exists. */
    public synchronized void clear() {
        synchronized (sessions) {
            sessions.clear();
        }
    }

    public synchronized void release() {
        clear();
        try {
            if (started) stop();
        } finally {
            if (routeRegistration != null) routeRegistration.close();
            routeRegistration = null;
            started = false;
        }
    }

    private void ensureStarted() throws IOException {
        if (started) return;
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        routeRegistration = PlaybackRouteRegistry.registerAppService(
                getListeningPort(), PlaybackRouteRegistry.AppOwner.SMB_PROXY);
        started = true;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + getListeningPort();
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String path = session.getUri();
            if (path == null || !path.startsWith(PATH)) return error(Status.NOT_FOUND, "not found");
            String url = resolveUrl(session);
            if (url == null) return error(Status.NOT_FOUND, "expired smb session");
            return serveSmb(session, url);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "serve failed errorType=%s", e.getClass().getSimpleName());
            return error(Status.INTERNAL_ERROR, "proxy failure");
        }
    }

    @Nullable
    private String resolveUrl(IHTTPSession session) {
        Map<String, String> params = session.getParms();
        String id = params == null ? null : params.get("s");
        if (!TextUtils.isEmpty(id)) {
            try {
                synchronized (sessions) {
                    return sessions.get(Integer.parseInt(id.trim()));
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        String raw = params == null ? null : params.get("url");
        if (TextUtils.isEmpty(raw)) return null;
        try {
            String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8.name());
            return isSmb(decoded) ? decoded : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Response serveSmb(IHTTPSession session, String url) throws IOException {
        Map<String, String> headers = session.getHeaders();
        String rangeHeader = headers == null ? null : headers.get("range");
        SmbDataSource source = new SmbDataSource();
        long length;
        try {
            // Probe the total length first; SmbDataSource reports the remaining
            // byte count for the requested position, so opening at 0 yields the
            // full file size needed for Content-Range.
            length = source.open(new DataSpec.Builder().setUri(Uri.parse(url)).build());
        } catch (Throwable e) {
            releaseQuietly(source);
            SpiderDebug.log(TAG, "open failed errorType=%s", e.getClass().getSimpleName());
            return error(Status.NOT_FOUND, "smb open failed");
        }
        if (length == C.LENGTH_UNSET || length < 0) {
            releaseQuietly(source);
            return error(Status.INTERNAL_ERROR, "unknown smb length");
        }
        Range range = parseRange(rangeHeader, length);
        if (rangeHeader != null && range == null) {
            releaseQuietly(source);
            Response response = error(Status.RANGE_NOT_SATISFIABLE, "invalid range");
            response.addHeader("Content-Range", "bytes */" + length);
            return response;
        }
        long start = range == null ? 0 : range.start;
        long end = range == null ? length - 1 : range.end;
        long count = end - start + 1;
        if (range != null) {
            // Reopen at the requested offset. The same host/share/path reuses the
            // established smbj connection, so a seek costs no extra handshake.
            try {
                source.close();
                source.open(new DataSpec.Builder()
                        .setUri(Uri.parse(url))
                        .setPosition(start)
                        .setLength(count)
                        .build());
            } catch (Throwable e) {
                releaseQuietly(source);
                SpiderDebug.log(TAG, "seek failed start=%d errorType=%s", start, e.getClass().getSimpleName());
                return error(Status.INTERNAL_ERROR, "smb seek failed");
            }
        }
        InputStream stream = new SmbInputStream(source, count);
        Response response = newFixedLengthResponse(
                range == null ? Status.OK : Status.PARTIAL_CONTENT, MIME_BINARY, stream, count);
        response.addHeader("Accept-Ranges", "bytes");
        response.addHeader("Cache-Control", "no-cache");
        response.addHeader("Connection", "close");
        if (range != null) {
            response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
        }
        return response;
    }

    private static void releaseQuietly(SmbDataSource source) {
        try {
            source.close();
        } catch (Throwable ignored) {
        }
        try {
            source.release();
        } catch (Throwable ignored) {
        }
    }

    private static Response error(Response.IStatus status, String text) {
        Response response = newFixedLengthResponse(status, MIME_PLAINTEXT, text == null ? "" : text);
        response.addHeader("Cache-Control", "no-cache");
        return response;
    }

    @Nullable
    private static Range parseRange(@Nullable String header, long length) {
        if (TextUtils.isEmpty(header)) return null;
        String value = header.trim().toLowerCase(Locale.US);
        if (!value.startsWith("bytes=") || length <= 0) return null;
        String spec = value.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        try {
            long start;
            long end;
            String left = spec.substring(0, dash).trim();
            String right = spec.substring(dash + 1).trim();
            if (left.isEmpty()) {
                long suffix = Long.parseLong(right);
                if (suffix <= 0) return null;
                start = Math.max(0, length - suffix);
                end = length - 1;
            } else {
                start = Long.parseLong(left);
                end = right.isEmpty() ? length - 1 : Long.parseLong(right);
            }
            if (start < 0 || end < start || start >= length) return null;
            return new Range(start, Math.min(end, length - 1));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private record Range(long start, long end) {
    }

    /**
     * Streams a bounded window of an {@link SmbDataSource} and releases the
     * underlying smbj handles when the response body is closed.
     */
    private static final class SmbInputStream extends InputStream {

        private final SmbDataSource source;
        private long remaining;
        private boolean closed;

        private SmbInputStream(SmbDataSource source, long remaining) {
            this.source = source;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(remaining, length);
            int read = source.read(buffer, offset, toRead);
            if (read == C.RESULT_END_OF_INPUT || read <= 0) return -1;
            remaining -= read;
            return read;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            releaseQuietly(source);
        }
    }
}
