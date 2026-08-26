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
 * The account list's raw ciphertext is persisted on disk (AccountListStore) - a
 * deliberate reversal of the original no-persistence decision, see that class's note.
 * fetchAccountList() reads the cached ciphertext when present, decrypting fresh each
 * call rather than caching decrypted profiles in memory; refreshAccountList() re-fetches
 * from the network and updates the cache.
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
    private final AtomicBoolean accountListRefreshedThisLaunch = new AtomicBoolean(false);

    private KVideoSyncEngine() {
    }

    public static KVideoSyncEngine get() {
        return INSTANCE;
    }

    /**
     * Fire-and-forget: refreshes the on-disk account list cache once per process
     * lifetime, so accounts added/removed in the sheet since last launch show up
     * without the user needing to force a refresh. Safe to call unconditionally (unlike
     * pullOncePerLaunch, this doesn't require an active account - the switcher itself
     * needs a current list before one can even be chosen).
     */
    public void refreshAccountListOncePerLaunch() {
        if (!KVideoAccountStore.hasListSource()) return;
        if (!accountListRefreshedThisLaunch.compareAndSet(false, true)) return;
        Task.execute(() -> {
            try {
                refreshAccountList();
            } catch (Exception e) {
                accountListRefreshedThisLaunch.set(false); // allow a retry on next Activity startup
                com.github.catvod.crawler.SpiderDebug.log("kvideo-sync", "startup account list refresh failed error=%s", e.getMessage());
            }
        });
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
     * Returns the account list, decrypting the cached ciphertext when present rather
     * than hitting the network - falls back to refreshAccountList() when nothing is
     * cached yet (e.g. first run on this device).
     */
    public List<AccountProfile> fetchAccountList() throws Exception {
        if (!AccountListStore.hasCiphertext()) return refreshAccountList();
        return decodeAccountList(AccountListStore.getCiphertext());
    }

    /**
     * Fetches, decrypts, returns, and caches the account list from the network.
     * Reuses the VOD source's URL/AES key/IV (Config.vod()) and its combined
     * {sites:[...], accounts:[...]} response - a deliberate single-key simplification,
     * not a separate feed - so this issues its own HTTP call to that same URL rather
     * than piggybacking on VodConfig's own load (which discards the raw JSON).
     */
    public List<AccountProfile> refreshAccountList() throws Exception {
        if (!KVideoAccountStore.hasListSource()) throw new IllegalStateException("Account list source not configured");
        Config config = Config.vod();
        String encrypted = OkHttp.string(config.getUrl());
        if (TextUtils.isEmpty(encrypted)) throw new IllegalStateException("Empty account list response");
        List<AccountProfile> profiles = decodeAccountList(encrypted);
        AccountListStore.saveCiphertext(encrypted);
        return profiles;
    }

    private List<AccountProfile> decodeAccountList(String encrypted) throws Exception {
        Config config = Config.vod();
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
     *  showIdentifier (title match), same semantics as KVideo's own importHistory().
     *  Returns the number of history items applied, so callers can surface a concrete
     *  result (0 usually means either an empty remote payload or a decrypt mismatch -
     *  see the class-level troubleshooting note). */
    public int pull() throws Exception {
        AccountProfile account = requireActiveAccount();
        JsonObject payload = fetchDecryptedPayload(account);
        if (payload == null) return 0;
        List<JsonObject> items = HistorySyncMapper.readHistoryItems(payload);
        for (JsonObject item : items) applyRemoteItem(item);
        return items.size();
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
        int cid = com.fongmi.android.tv.api.config.VodConfig.getCid();
        History existing = findLocalByIdentifier(identifier, cid);
        History updated = HistorySyncMapper.toHistoryUpdate(item, existing);
        updated.save(cid);
    }

    private History findLocalByIdentifier(String identifier, int cid) {
        if (TextUtils.isEmpty(identifier)) return null;
        for (History history : History.get(cid)) {
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
