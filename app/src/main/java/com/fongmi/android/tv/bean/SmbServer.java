package com.fongmi.android.tv.bean;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.impl.Diffable;
import com.fongmi.android.tv.utils.SecretBox;
import com.google.gson.annotations.SerializedName;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.UUID;

/**
 * A user-configured SMB share. Entered by hand in the web management page —
 * smbj 0.14.0 cannot enumerate a host's shares (that needs NetShareEnum over
 * IPC$), so the share name must be supplied rather than discovered.
 */
public class SmbServer implements Diffable<SmbServer> {

    public static final int DEFAULT_PORT = 445;

    @SerializedName("id")
    private String id;
    @SerializedName("name")
    private String name;
    @SerializedName("host")
    private String host;
    @SerializedName("port")
    private int port;
    @SerializedName("share")
    private String share;
    @SerializedName("user")
    private String user;
    /** Sealed by {@link SecretBox}; never returned to the browser. */
    @SerializedName("pass")
    private String pass;
    @SerializedName("path")
    private String path;
    @SerializedName("time")
    private long time;

    public static SmbServer create() {
        SmbServer server = new SmbServer();
        server.setId(UUID.randomUUID().toString());
        server.setPort(DEFAULT_PORT);
        server.setTime(System.currentTimeMillis());
        return server;
    }

    public String getId() {
        return TextUtils.isEmpty(id) ? "" : id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /** Display label; falls back to {@code host/share} when the user left it blank. */
    public String getName() {
        if (!TextUtils.isEmpty(name)) return name;
        return getHost() + (TextUtils.isEmpty(getShare()) ? "" : "/" + getShare());
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return TextUtils.isEmpty(host) ? "" : host.trim();
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port <= 0 || port > 65535 ? DEFAULT_PORT : port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getShare() {
        return TextUtils.isEmpty(share) ? "" : trim(share);
    }

    public void setShare(String share) {
        this.share = share;
    }

    public String getUser() {
        return TextUtils.isEmpty(user) ? "" : user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    /** The decrypted password. Use {@link #setPass} to store a new one. */
    public String getPass() {
        return SecretBox.open(pass);
    }

    public void setPass(String plain) {
        this.pass = TextUtils.isEmpty(plain) ? "" : SecretBox.seal(plain);
    }

    /** Raw stored form, for persistence round-trips that must not re-seal. */
    public String getSealedPass() {
        return TextUtils.isEmpty(pass) ? "" : pass;
    }

    public void setSealedPass(String sealed) {
        this.pass = sealed;
    }

    public boolean hasPass() {
        return !TextUtils.isEmpty(pass);
    }

    /** Optional sub-path inside the share to open first, e.g. {@code Video}. */
    public String getPath() {
        return TextUtils.isEmpty(path) ? "" : trim(path);
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public boolean isValid() {
        return !TextUtils.isEmpty(getHost()) && !TextUtils.isEmpty(getShare());
    }

    /** The share root, honouring {@link #getPath()}. */
    public String rootUrl() {
        return urlFor(getPath());
    }

    /**
     * Builds the playable {@code smb://} URL for a path inside the share.
     *
     * <p>Credentials are percent-encoded: {@code SmbDataSource} parses them via
     * {@code Uri.getUserInfo()} split on {@code :}, so a password containing
     * {@code @}, {@code :} or {@code /} would otherwise corrupt the whole URI.
     */
    public String urlFor(String relPath) {
        StringBuilder sb = new StringBuilder("smb://");
        String account = getUser();
        if (!TextUtils.isEmpty(account)) {
            sb.append(encode(account));
            String secret = getPass();
            if (!TextUtils.isEmpty(secret)) sb.append(':').append(encode(secret));
            sb.append('@');
        }
        sb.append(getHost());
        if (getPort() != DEFAULT_PORT) sb.append(':').append(getPort());
        sb.append('/').append(getShare());
        String child = trim(relPath);
        if (!TextUtils.isEmpty(child)) sb.append('/').append(child);
        return sb.toString();
    }

    /** Credential-free form, safe for logs and for the web UI. */
    public String displayUrl() {
        StringBuilder sb = new StringBuilder("smb://").append(getHost());
        if (getPort() != DEFAULT_PORT) sb.append(':').append(getPort());
        sb.append('/').append(getShare());
        if (!TextUtils.isEmpty(getPath())) sb.append('/').append(getPath());
        return sb.toString();
    }

    /** Joins a parent path and a child name using the SMB separator. */
    public static String join(String parent, String child) {
        String head = trim(parent);
        String tail = trim(child);
        if (TextUtils.isEmpty(head)) return tail;
        if (TextUtils.isEmpty(tail)) return head;
        return head + "/" + tail;
    }

    /** The parent of {@code relPath}, or an empty string at the share root. */
    public static String parent(String relPath) {
        String value = trim(relPath);
        int index = value.lastIndexOf('/');
        return index <= 0 ? "" : value.substring(0, index);
    }

    private static String trim(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String result = value.replace('\\', '/').trim();
        while (result.startsWith("/")) result = result.substring(1);
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String encode(String value) {
        try {
            // URLEncoder is form-encoding, so spaces become '+' and must be fixed up.
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return Uri.encode(value);
        }
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SmbServer it)) return false;
        return getId().equals(it.getId());
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }

    @Override
    public boolean isSameItem(SmbServer other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(SmbServer other) {
        if (other == null) return false;
        return getName().equals(other.getName())
                && getHost().equals(other.getHost())
                && getPort() == other.getPort()
                && getShare().equals(other.getShare())
                && getUser().equals(other.getUser())
                && getPath().equals(other.getPath())
                && hasPass() == other.hasPass();
    }
}
