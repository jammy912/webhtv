package com.fongmi.android.tv.sync;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Talks to the same Upstash Redis REST endpoint KVideo's Next.js backend uses,
 * reading/writing the "user:sync:<GUID>" key directly (no KVideo backend involved).
 * Mirrors KVideo's own read-modify-write pattern (GET, merge fields, SET back the
 * whole object) so neither side clobbers fields the other wrote.
 */
public class UpstashSyncClient {

    private static final MediaType TEXT = MediaType.parse("text/plain; charset=utf-8");
    private static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15);

    private final String baseUrl;
    private final String accessToken;

    public UpstashSyncClient(String baseUrl, String accessToken) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.accessToken = accessToken;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String syncKey(String userGuid) {
        return "user:sync:" + userGuid;
    }

    /** Returns the raw JSON object stored at user:sync:<GUID>, or an empty object if unset. */
    public JsonObject getSyncObject(String userGuid) throws Exception {
        String url = baseUrl + "/get/" + syncKey(userGuid);
        Map<String, String> headers = authHeaders();
        try (Response res = OkHttp.newCall(OkHttp.client(TIMEOUT_MS), url, headers).execute()) {
            if (!res.isSuccessful()) throw new Exception("Upstash GET failed: HTTP " + res.code());
            JsonObject envelope = JsonParser.parseString(res.body().string()).getAsJsonObject();
            if (envelope.has("error")) throw new Exception("Upstash GET error: " + envelope.get("error").getAsString());
            JsonElement result = envelope.get("result");
            if (result == null || result.isJsonNull()) return new JsonObject();
            return JsonParser.parseString(result.getAsString()).getAsJsonObject();
        }
    }

    /** Returns just the base64 "encrypted" payload, or null if the key is unset or has no such field. */
    public String getEncryptedPayload(String userGuid) throws Exception {
        JsonObject object = getSyncObject(userGuid);
        JsonElement encrypted = object.get("encrypted");
        return encrypted == null || encrypted.isJsonNull() ? null : encrypted.getAsString();
    }

    /**
     * Read-modify-write: fetches the existing object, overwrites only "encrypted" and
     * "updatedAt", and writes the merged object back, matching KVideo's own route.ts logic.
     */
    public void putEncryptedPayload(String userGuid, String base64Ciphertext) throws Exception {
        JsonObject merged = getSyncObject(userGuid);
        merged.addProperty("encrypted", base64Ciphertext);
        merged.addProperty("updatedAt", System.currentTimeMillis());
        String url = baseUrl + "/set/" + syncKey(userGuid);
        Map<String, String> headers = authHeaders();
        RequestBody body = RequestBody.create(merged.toString(), TEXT);
        try (Response res = OkHttp.newCall(url, headers, body).execute()) {
            if (!res.isSuccessful()) throw new Exception("Upstash SET failed: HTTP " + res.code());
            JsonObject envelope = JsonParser.parseString(res.body().string()).getAsJsonObject();
            if (envelope.has("error")) throw new Exception("Upstash SET error: " + envelope.get("error").getAsString());
        }
    }

    private Map<String, String> authHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        return headers;
    }
}
