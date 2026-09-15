package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.SmbServer;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * Web-management endpoints for the SMB browser.
 *
 * <p>Server entries are configured here rather than in a native settings screen:
 * typing a password with a TV remote is painful, and one web implementation
 * serves both the mobile and leanback flavors.
 *
 * <p>The path prefix must not begin with {@code /file} — {@link Local} claims
 * that prefix and the handler list is first-match-wins.
 */
public class Smb implements Process {

    private static final String TAG = "smb-server";
    private static final int TEST_TIMEOUT = 15;
    private static final int TEST_LIMIT = 5000;

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/smb/");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            Map<String, String> params = session.getParms();
            return switch (url) {
                case "/smb/servers" -> servers();
                case "/smb/server" -> save(params);
                case "/smb/server/delete" -> delete(params);
                case "/smb/server/test" -> test(params);
                case "/smb/settings" -> settings(params);
                case "/smb/thumb/clear" -> clearThumb();
                default -> Nano.error(Status.NOT_FOUND, "Not found");
            };
        } catch (Exception e) {
            SpiderDebug.log(TAG, "request failed url=%s errorType=%s", url, e.getClass().getSimpleName());
            return Nano.error(e.getMessage());
        }
    }

    private Response servers() {
        JsonArray array = new JsonArray();
        for (SmbServer server : Setting.getSmbServers()) array.add(toJson(server));
        JsonObject object = new JsonObject();
        object.add("servers", array);
        object.addProperty("thumbEnabled", Setting.getSmbThumbEnabled());
        object.addProperty("concurrency", Setting.getSmbThumbConcurrency());
        return json(object);
    }

    private Response save(Map<String, String> params) {
        String id = value(params, "id");
        SmbServer server = TextUtils.isEmpty(id) ? null : Setting.getSmbServer(id);
        boolean creating = server == null;
        if (creating) {
            server = SmbServer.create();
            if (!TextUtils.isEmpty(id)) server.setId(id);
        }
        server.setName(value(params, "name"));
        server.setHost(value(params, "host"));
        server.setShare(value(params, "share"));
        server.setPath(value(params, "path"));
        server.setUser(value(params, "user"));
        server.setPort(number(params.get("port"), SmbServer.DEFAULT_PORT));
        // A blank password on edit means "unchanged" — the browser is never sent
        // the stored value, so it cannot echo it back.
        String pass = params.get("pass");
        if (creating || !TextUtils.isEmpty(pass)) server.setPass(pass == null ? "" : pass);
        server.setTime(System.currentTimeMillis());
        if (!server.isValid()) return Nano.error(Status.BAD_REQUEST, "host and share are required");
        Setting.putSmbServer(server);
        return json(toJson(server));
    }

    private Response delete(Map<String, String> params) {
        Setting.removeSmbServer(value(params, "id"));
        return servers();
    }

    /**
     * Connects and lists the configured root. This is what makes hand-entering a
     * server tolerable: the user gets an immediate yes/no instead of discovering
     * a typo later inside the grid.
     */
    private Response test(Map<String, String> params) {
        SmbServer server = resolve(params);
        JsonObject object = new JsonObject();
        if (server == null || !server.isValid()) {
            object.addProperty("ok", false);
            object.addProperty("message", "host and share are required");
            return json(object);
        }
        SMBClient client = null;
        Connection connection = null;
        try {
            SmbConfig config = SmbConfig.builder()
                    .withTimeout(TEST_TIMEOUT, TimeUnit.SECONDS)
                    .withSoTimeout(TEST_TIMEOUT, TimeUnit.SECONDS)
                    .build();
            client = new SMBClient(config);
            connection = client.connect(server.getHost(), server.getPort());
            Session session = connection.authenticate(auth(server));
            try (DiskShare share = (DiskShare) session.connectShare(server.getShare())) {
                int dirs = 0;
                int files = 0;
                List<FileIdBothDirectoryInformation> items = share.list(server.getPath());
                for (FileIdBothDirectoryInformation info : items) {
                    String name = info.getFileName();
                    if (".".equals(name) || "..".equals(name)) continue;
                    if (isDir(info)) dirs++;
                    else files++;
                    if (dirs + files >= TEST_LIMIT) break;
                }
                object.addProperty("ok", true);
                object.addProperty("dirs", dirs);
                object.addProperty("files", files);
                object.addProperty("count", dirs + files);
                object.addProperty("message", "OK");
            }
        } catch (Throwable e) {
            // The NT status is what makes this diagnosable; it carries the share
            // path but never the password, which lives only in the URI we build.
            SpiderDebug.log(TAG, "test failed errorType=%s status=%s", e.getClass().getSimpleName(), e.getMessage());
            object.addProperty("ok", false);
            object.addProperty("message", describe(e));
        } finally {
            close(connection);
            close(client);
        }
        return json(object);
    }

    private Response settings(Map<String, String> params) {
        if (params.containsKey("thumbEnabled")) Setting.putSmbThumbEnabled(bool(params.get("thumbEnabled")));
        if (params.containsKey("concurrency")) Setting.putSmbThumbConcurrency(number(params.get("concurrency"), 2));
        return servers();
    }

    private Response clearThumb() {
        long freed = 0;
        File dir = Path.thumb();
        File[] items = dir.listFiles();
        if (items != null) {
            for (File item : items) {
                long size = item.length();
                if (item.delete()) freed += size;
            }
        }
        JsonObject object = new JsonObject();
        object.addProperty("ok", true);
        object.addProperty("freed", freed);
        return json(object);
    }

    /** Builds a server from either a stored id or the raw form fields. */
    private SmbServer resolve(Map<String, String> params) {
        String id = value(params, "id");
        SmbServer stored = TextUtils.isEmpty(id) ? null : Setting.getSmbServer(id);
        String host = value(params, "host");
        if (stored != null && TextUtils.isEmpty(host)) return stored;
        SmbServer server = SmbServer.create();
        if (stored != null) server.setId(stored.getId());
        server.setName(value(params, "name"));
        server.setHost(host);
        server.setShare(value(params, "share"));
        server.setPath(value(params, "path"));
        server.setUser(value(params, "user"));
        server.setPort(number(params.get("port"), SmbServer.DEFAULT_PORT));
        String pass = params.get("pass");
        if (TextUtils.isEmpty(pass) && stored != null) server.setSealedPass(stored.getSealedPass());
        else server.setPass(pass == null ? "" : pass);
        return server;
    }

    private static AuthenticationContext auth(SmbServer server) {
        if (TextUtils.isEmpty(server.getUser())) return AuthenticationContext.guest();
        return new AuthenticationContext(server.getUser(), server.getPass().toCharArray(), null);
    }

    private static boolean isDir(FileIdBothDirectoryInformation info) {
        return (info.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
    }

    private static String describe(Throwable e) {
        String name = e.getClass().getSimpleName();
        String message = e.getMessage();
        if (TextUtils.isEmpty(message)) return name;
        if (message.contains("STATUS_LOGON_FAILURE")) return "帳號或密碼錯誤";
        if (message.contains("STATUS_BAD_NETWORK_NAME")) return "找不到分享名稱";
        if (message.contains("STATUS_ACCESS_DENIED")) return "沒有存取權限";
        if (message.contains("STATUS_OBJECT_NAME_NOT_FOUND")) return "找不到起始子路徑";
        if (message.contains("STATUS_OBJECT_PATH_NOT_FOUND")) return "找不到起始子路徑";
        if (message.contains("STATUS_NOT_A_DIRECTORY")) return "起始子路徑不是資料夾";
        if (message.contains("STATUS_ACCOUNT_DISABLED")) return "帳號已停用";
        if (message.contains("STATUS_PASSWORD_EXPIRED")) return "密碼已過期";
        if (message.contains("STATUS_SHARING_VIOLATION")) return "檔案被其他程式鎖定";
        // Anything else: show the raw status so a failure is diagnosable rather
        // than collapsing into a bare class name.
        return name + ": " + message;
    }

    private JsonObject toJson(SmbServer server) {
        JsonObject object = new JsonObject();
        object.addProperty("id", server.getId());
        object.addProperty("name", server.getName());
        object.addProperty("host", server.getHost());
        object.addProperty("port", server.getPort());
        object.addProperty("share", server.getShare());
        object.addProperty("path", server.getPath());
        object.addProperty("user", server.getUser());
        // Never expose the password, not even sealed.
        object.addProperty("hasPass", server.hasPass());
        object.addProperty("url", server.displayUrl());
        object.addProperty("time", server.getTime());
        return object;
    }

    private static Response json(JsonObject object) {
        return Nano.newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", object.toString());
    }

    private static String value(Map<String, String> params, String key) {
        String value = params.get(key);
        return value == null ? "" : value.trim();
    }

    private static int number(String value, int fallback) {
        try {
            return TextUtils.isEmpty(value) ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean bool(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static void close(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception ignored) {
        }
    }
}
