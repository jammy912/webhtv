package com.fongmi.android.tv.sync;

import com.google.gson.annotations.SerializedName;

/**
 * One row of the account list Google Apps Script serves, AES-256-CBC encrypted with a
 * key/IV independent from the VOD/live source AES config (see Config.aesKey/aesIv).
 * The "password" field doubles as the KVideo login password AND, per KVideo's own
 * sync-crypto.ts, the PBKDF2 material for the Upstash payload's AES-GCM key - there is
 * no separate "sync password" on KVideo's side.
 */
public class AccountProfile {

    @SerializedName("label")
    private String label;
    @SerializedName("username")
    private String username;
    @SerializedName("password")
    private String password;
    @SerializedName("redisUrl")
    private String redisUrl;
    @SerializedName("accessToken")
    private String accessToken;
    @SerializedName("userGuid")
    private String userGuid;
    @SerializedName("logo")
    private String logo;

    public String getLabel() {
        return label == null || label.isEmpty() ? username : label;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getRedisUrl() {
        return redisUrl;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getUserGuid() {
        return userGuid;
    }

    public String getLogo() {
        return logo;
    }

    public boolean isUsable() {
        return notEmpty(password) && notEmpty(redisUrl) && notEmpty(accessToken) && notEmpty(userGuid);
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.isEmpty();
    }
}
