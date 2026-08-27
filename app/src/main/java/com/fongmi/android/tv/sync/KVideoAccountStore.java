package com.fongmi.android.tv.sync;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Config;
import com.github.catvod.utils.Prefers;

/**
 * Tracks which KVideo account is currently active - by username only. The account
 * list itself is fetched from the same URL/AES key/IV as the VOD source config
 * (Config.vod().getUrl()/getAesKey()/getAesIv()) via a separate HTTP call, per the
 * decision to combine both feeds into one Apps Script doGet response encrypted with
 * a single key rather than maintaining two. Per an explicit product decision, the
 * decrypted account list (passwords, Redis tokens) is never persisted: every switch/
 * sync fetches and decrypts it fresh, uses the selected entry, and discards it.
 */
public final class KVideoAccountStore {

    private static final String KEY_ACTIVE_USERNAME = "kvideo_active_username";
    private static final String KEY_ACTIVE_LOGO = "kvideo_active_logo";
    private static final String KEY_LAST_UPDATED_AT_PREFIX = "kvideo_last_updated_at_";

    private KVideoAccountStore() {
    }

    public static boolean hasListSource() {
        Config config = Config.vod();
        return !TextUtils.isEmpty(config.getUrl()) && !TextUtils.isEmpty(config.getAesKey()) && !TextUtils.isEmpty(config.getAesIv());
    }

    public static String getActiveUsername() {
        return Prefers.getString(KEY_ACTIVE_USERNAME);
    }

    public static void setActiveUsername(String username) {
        Prefers.put(KEY_ACTIVE_USERNAME, username == null ? "" : username);
    }

    public static void clearActiveAccount() {
        Prefers.put(KEY_ACTIVE_USERNAME, "");
        Prefers.put(KEY_ACTIVE_LOGO, "");
    }

    /** Set alongside the active username on every switch, so the home logo (ImgUtil.logo)
     *  can prefer the active account's own logo over VodConfig's shared one without a
     *  network round-trip on every app launch. Empty when the active account has none. */
    public static String getActiveLogo() {
        return Prefers.getString(KEY_ACTIVE_LOGO);
    }

    public static void setActiveLogo(String logo) {
        Prefers.put(KEY_ACTIVE_LOGO, logo == null ? "" : logo);
    }

    public static boolean hasActiveAccount() {
        return hasListSource() && !TextUtils.isEmpty(getActiveUsername());
    }

    /** Keyed per-username (not a single shared value) since each KVideo account has its
     *  own independent updatedAt in its own user:sync:<GUID> record - switching accounts
     *  must not compare against a stale value left over from a different account. */
    public static long getLastUpdatedAt(String username) {
        return Prefers.getLong(KEY_LAST_UPDATED_AT_PREFIX + username, 0);
    }

    public static void setLastUpdatedAt(String username, long updatedAt) {
        Prefers.put(KEY_LAST_UPDATED_AT_PREFIX + username, updatedAt);
    }
}
