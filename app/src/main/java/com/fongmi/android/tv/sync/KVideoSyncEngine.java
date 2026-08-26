package com.fongmi.android.tv.sync;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.History;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import com.fongmi.android.tv.utils.Task;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.SecretKey;

/**
 * Ties SyncCrypto + UpstashSyncClient + HistorySyncMapper + KVideoAccountStore together.
 * Every call re-fetches and decrypts the account list fresh (nothing is cached to disk,
 * per an explicit no-persistence decision) and discards credentials once the call returns.
 *
 * Sync policy mirrors KVideo's own AutoSync.tsx on purpose (see design discussion):
 * whole-object overwrite, no timestamp-based merge protection. KVideo's own push already
 * clobbers the key this way, so adding client-side merge logic on webhtv's end would only
 * be silently discarded by KVideo's next write - not worth the complexity.
 */
public final class KVideoSyncEngine {

    private static final Type PROFILE_LIST_TYPE = new TypeToken<List<AccountProfile>>() {
    }.getType();

    private static final KVideoSyncEngine INSTANCE = new KVideoSyncEngine();

    private final AtomicBoolean pulledThisLaunch = new AtomicBoolean(false);

    private KVideoSyncEngine() {
    }

    public static KVideoSyncEngine get() {
        return INSTANCE;
    }

    /**
     * Fire-and-forget: pulls once per process lifetime (mirrors KVideo's own AutoSync.tsx
     * pullFromCloud-on-mount, which also only runs once per page load, not periodically).
     * Safe to call from every Activity's startup path - the AtomicBoolean guard means
     * only the first caller after process start actually triggers a network call.
     */
    public void pullOncePerLaunch() {
        if (!KVideoAccountStore.hasActiveAccount()) return;
        if (!pulledThisLaunch.compareAndSet(false, true)) return;
        Task.execute(() -> {
            try {
                pull();
            } catch (Exception e) {
                pulledThisLaunch.set(false); // allow a retry on next Activity startup
                com.github.catvod.crawler.SpiderDebug.log("kvideo-sync", "startup pull failed error=%s", e.getMessage());
            }
        });
    }

    /**
     * Fetches, decrypts, and returns the account list. Never persisted by the caller.
     * Reuses the VOD source's URL/AES key/IV (Config.vod()) and its combined
     * {sites:[...], accounts:[...]} response - a deliberate single-key simplification,
     * not a separate feed - so this issues its own HTTP call to that same URL rather
     * than piggybacking on VodConfig's own load (which discards the raw JSON).
     */
    public List<AccountProfile> fetchAccountList() throws Exception {
        if (!KVideoAccountStore.hasListSource()) throw new IllegalStateException("Account list source not configured");
        Config config = Config.vod();
        String encrypted = OkHttp.string(config.getUrl());
        if (TextUtils.isEmpty(encrypted)) throw new IllegalStateException("Empty account list response");
        String json = AccountListCrypto.decrypt(encrypted, config.getAesKey(), config.getAesIv());
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonElement accountsEl = root.get("accounts");
        if (accountsEl == null || !accountsEl.isJsonArray()) return new ArrayList<>();
        List<AccountProfile> profiles = App.gson().fromJson(accountsEl, PROFILE_LIST_TYPE);
        return profiles == null ? new ArrayList<>() : profiles;
    }

    public AccountProfile findActiveAccount() throws Exception {
        String username = KVideoAccountStore.getActiveUsername();
        if (TextUtils.isEmpty(username)) return null;
        for (AccountProfile profile : fetchAccountList()) {
            if (TextUtils.equals(profile.getUsername(), username) && profile.isUsable()) return profile;
        }
        return null;
    }

    /** Pulls KVideo's history/favorites and overwrites matching local History rows by
     *  showIdentifier (title match), same semantics as KVideo's own importHistory(). */
    public void pull() throws Exception {
        AccountProfile account = requireActiveAccount();
        JsonObject payload = fetchDecryptedPayload(account);
        if (payload == null) return;
        for (JsonObject item : HistorySyncMapper.readHistoryItems(payload)) {
            applyRemoteItem(item);
        }
    }

    /** Pushes a single History row (whole-object overwrite of the "encrypted" field, per
     *  KVideo's own read-modify-write pattern in UpstashSyncClient). episodeNames is the
     *  current Flag's episode name list, or empty when not available at push time. */
    public void pushSingle(History history, List<String> episodeNames) {
        try {
            AccountProfile account = requireActiveAccount();
            UpstashSyncClient client = new UpstashSyncClient(account.getRedisUrl(), account.getAccessToken());
            SecretKey key = SyncCrypto.deriveKey(account.getPassword());
            JsonObject existingPayload = decryptExistingPayload(client, account.getUserGuid(), key);
            JsonArray mergedHistory = mergeHistoryItem(existingPayload, history, episodeNames);
            JsonObject payload = new JsonObject();
            payload.add("history", mergedHistory);
            payload.add("favorites", existingFavorites(existingPayload));
            String ciphertext = SyncCrypto.encrypt(key, payload.toString());
            client.putEncryptedPayload(account.getUserGuid(), ciphertext);
        } catch (Exception e) {
            com.github.catvod.crawler.SpiderDebug.log("kvideo-sync", "push failed error=%s", e.getMessage());
        }
    }

    private JsonObject fetchDecryptedPayload(AccountProfile account) throws Exception {
        UpstashSyncClient client = new UpstashSyncClient(account.getRedisUrl(), account.getAccessToken());
        SecretKey key = SyncCrypto.deriveKey(account.getPassword());
        return decryptExistingPayload(client, account.getUserGuid(), key);
    }

    private JsonObject decryptExistingPayload(UpstashSyncClient client, String userGuid, SecretKey key) throws Exception {
        String encrypted = client.getEncryptedPayload(userGuid);
        if (TextUtils.isEmpty(encrypted)) return null;
        String plaintext = SyncCrypto.decrypt(key, encrypted);
        return JsonParser.parseString(plaintext).getAsJsonObject();
    }

    private JsonArray existingFavorites(JsonObject existingPayload) {
        if (existingPayload == null || !existingPayload.has("favorites")) return new JsonArray();
        return existingPayload.getAsJsonArray("favorites");
    }

    /** Replaces (by showIdentifier) or appends this History's entry within the existing
     *  remote history array, rather than uploading only this one item and losing the
     *  rest - KVideo's SET overwrites the whole key, so a partial push would erase other
     *  titles' remote history entirely. */
    private JsonArray mergeHistoryItem(JsonObject existingPayload, History history, List<String> episodeNames) {
        JsonArray items = new JsonArray();
        String identifier = HistorySyncMapper.identifierFor(history.getVodName());
        boolean replaced = false;
        if (existingPayload != null && existingPayload.has("history") && existingPayload.get("history").isJsonArray()) {
            for (com.google.gson.JsonElement element : existingPayload.getAsJsonArray("history")) {
                if (!element.isJsonObject()) continue;
                JsonObject existingItem = element.getAsJsonObject();
                boolean isSameShow = existingItem.has("showIdentifier")
                        && TextUtils.equals(existingItem.get("showIdentifier").getAsString(), identifier);
                if (isSameShow) {
                    items.add(HistorySyncMapper.toKVideoItem(history, episodeNames));
                    replaced = true;
                } else {
                    items.add(existingItem);
                }
            }
        }
        if (!replaced) items.add(HistorySyncMapper.toKVideoItem(history, episodeNames));
        return items;
    }

    /** No timestamp guard here by design: KVideo's own pull/push is whole-object
     *  overwrite with no version protection (see AutoSync.tsx), and this mirrors that
     *  on purpose - see the class-level note on sync policy. */
    private void applyRemoteItem(JsonObject item) {
        String identifier = item.has("showIdentifier") ? item.get("showIdentifier").getAsString() : null;
        History existing = findLocalByIdentifier(identifier);
        History updated = HistorySyncMapper.toHistoryUpdate(item, existing);
        updated.save();
    }

    private History findLocalByIdentifier(String identifier) {
        if (TextUtils.isEmpty(identifier)) return null;
        for (History history : History.get()) {
            if (TextUtils.equals(HistorySyncMapper.identifierFor(history.getVodName()), identifier)) return history;
        }
        return null;
    }

    private AccountProfile requireActiveAccount() throws Exception {
        AccountProfile account = findActiveAccount();
        if (account == null) throw new IllegalStateException("No active KVideo account selected");
        return account;
    }
}
