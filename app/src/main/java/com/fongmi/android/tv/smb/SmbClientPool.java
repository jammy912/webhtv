package com.fongmi.android.tv.smb;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.SmbServer;
import com.github.catvod.crawler.SpiderDebug;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Keeps authenticated {@link DiskShare} handles alive between calls.
 *
 * <p>Connecting costs a TCP handshake plus SMB negotiate/session-setup/tree-connect
 * — several round trips. On a share with ~100ms latency that is most of a second
 * per folder, so without caching every navigation step would visibly stall.
 */
public final class SmbClientPool {

    private static final String TAG = "smb-pool";
    private static final int TIMEOUT = 15;
    private static final long IDLE_MS = TimeUnit.MINUTES.toMillis(2);

    private static final Map<String, Entry> POOL = new HashMap<>();

    private SmbClientPool() {
    }

    /** Returns a connected share, reusing the cached one when it is still alive. */
    public static synchronized DiskShare acquire(SmbServer server) throws Exception {
        String key = key(server);
        Entry entry = POOL.get(key);
        if (entry != null && entry.alive()) {
            entry.used = System.currentTimeMillis();
            return entry.share;
        }
        if (entry != null) close(key);
        Entry created = connect(server);
        POOL.put(key, created);
        return created.share;
    }

    /**
     * Drops a share after a failure so the next call reconnects instead of
     * reusing a half-dead socket.
     */
    public static synchronized void evict(SmbServer server) {
        close(key(server));
    }

    public static synchronized void closeAll() {
        for (String key : POOL.keySet().toArray(new String[0])) close(key);
    }

    /** Closes shares untouched for longer than the idle window. */
    public static synchronized void trim() {
        long now = System.currentTimeMillis();
        for (String key : POOL.keySet().toArray(new String[0])) {
            Entry entry = POOL.get(key);
            if (entry != null && now - entry.used > IDLE_MS) close(key);
        }
    }

    private static Entry connect(SmbServer server) throws Exception {
        SmbConfig config = SmbConfig.builder()
                .withTimeout(TIMEOUT, TimeUnit.SECONDS)
                .withSoTimeout(TIMEOUT, TimeUnit.SECONDS)
                .build();
        SMBClient client = new SMBClient(config);
        Connection connection = client.connect(server.getHost(), server.getPort());
        Session session = connection.authenticate(auth(server));
        DiskShare share = (DiskShare) session.connectShare(server.getShare());
        Entry entry = new Entry();
        entry.client = client;
        entry.connection = connection;
        entry.share = share;
        entry.used = System.currentTimeMillis();
        return entry;
    }

    private static AuthenticationContext auth(SmbServer server) {
        if (TextUtils.isEmpty(server.getUser())) return AuthenticationContext.guest();
        return new AuthenticationContext(server.getUser(), server.getPass().toCharArray(), null);
    }

    private static void close(String key) {
        Entry entry = POOL.remove(key);
        if (entry == null) return;
        quietly(entry.share);
        quietly(entry.connection);
        quietly(entry.client);
    }

    private static void quietly(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "close failed errorType=%s", e.getClass().getSimpleName());
        }
    }

    private static String key(SmbServer server) {
        return server.getHost() + ":" + server.getPort() + "/" + server.getShare() + "#" + server.getUser();
    }

    private static class Entry {

        private SMBClient client;
        private Connection connection;
        private DiskShare share;
        private long used;

        private boolean alive() {
            try {
                return share != null && share.isConnected() && connection != null && connection.isConnected();
            } catch (Throwable e) {
                return false;
            }
        }
    }
}
