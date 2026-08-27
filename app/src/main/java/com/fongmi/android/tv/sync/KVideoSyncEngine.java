package com.fongmi.android.tv.sync;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
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
    private final AtomicBoolean pollingStarted = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong lastPullAtMs = new java.util.concurrent.atomic.AtomicLong(0);

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
        startPollingIfNeeded();
        if (!pulledThisLaunch.compareAndSet(false, true)) return;
        Task.execute(() -> {
            try {
                pull();
                lastPullAtMs.set(System.currentTimeMillis());
            } catch (Exception e) {
                pulledThisLaunch.set(false); // allow a retry on next Activity startup
                com.github.catvod.crawler.SpiderDebug.log("kvideo-sync", "startup pull failed error=%s", e.getMessage());
            }
        });
    }

    /**
     * Starts a background 1-minute poll, once per process lifetime, so history stays
     * current even while the app sits idle on the home screen with no onResume() firing
     * to trigger pullIfStale(). Runs for the life of the process; there's no explicit
     * stop since the scheduler thread is a daemon-backed pool shared with the rest of
     * the app (Task.scheduler()), not something that needs its own lifecycle.
     */
    private void startPollingIfNeeded() {
        if (!pollingStarted.compareAndSet(false, true)) return;
        long intervalMs = java.util.concurrent.TimeUnit.MINUTES.toMillis(1);
        Task.scheduler().scheduleWithFixedDelay(() -> {
            if (!KVideoAccountStore.hasActiveAccount()) return;
            try {
                pull();
                lastPullAtMs.set(System.currentTimeMillis());
            } catch (Exception e) {
                com.github.catvod.crawler.SpiderDebug.log("kvideo-sync", "background poll failed error=%s", e.getMessage());
            }
        }, intervalMs, intervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Fire-and-forget: pulls when the app returns to the foreground (e.g. onResume()),
     * but only if at least minIntervalMs has passed since the last successful pull -
     * covers the gap pullOncePerLaunch leaves (the process can sit backgrounded for
     * a day without being killed, during which KVideo-side watches never show up
     * until the process is actually restarted) without hammering Upstash on every
     * quick app-switch.
     */
    public void pullIfStale(long minIntervalMs) {
        if (!KVideoAccountStore.hasActiveAccount()) return;
        long now = System.currentTimeMillis();
        long last = lastPullAtMs.get();
        if (now - last < minIntervalMs) return;
        if (!lastPullAtMs.compareAndSet(last, now)) return; // another caller just claimed this pull
        Task.execute(() -> {
            try {
                pull();
            } catch (Exception e) {
                lastPullAtMs.set(last); // allow a retry on the next resume
                com.github.catvod.crawler.SpiderDebug.log("kvideo-sync", "foreground pull failed error=%s", e.getMessage());
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

    /**
     * Switches the active account and replaces local history with that account's
     * remote data. webhtv lets a single device swap between multiple KVideo accounts -
     * KVideo itself has no such concept (one browser session, one account) - so without
     * this, a prior account's synced-in rows would just sit there forever, mixed in
     * with whatever the newly selected account pulls in. Wipes local history for the
     * current VOD config (cid) before pulling, rather than trying to reconcile/tag rows
     * by account, since History has no per-account column and adding one is a bigger
     * schema change than this feature currently warrants.
     */
    public int switchAccount(String username) throws Exception {
        KVideoAccountStore.setActiveUsername(username);
        History.delete(com.fongmi.android.tv.api.config.VodConfig.getCid());
        Keep.delete(com.fongmi.android.tv.api.config.VodConfig.getCid());
        // Force a real pull even if this account's updatedAt hasn't changed since last
        // seen (e.g. re-selecting the same account as a manual refresh) - local history/
        // favorites were just wiped above, so skipping here would leave them empty
        // instead of repopulated.
        KVideoAccountStore.setLastUpdatedAt(username, 0);
        return pull();
    }

    /** Pulls KVideo's history/favorites and overwrites matching local History rows by
     *  showIdentifier (title match), same semantics as KVideo's own importHistory().
     *  Returns the number of history items applied, so callers can surface a concrete
     *  result (0 usually means either an empty remote payload or a decrypt mismatch -
     *  see the class-level troubleshooting note). */
    public int pull() throws Exception {
        AccountProfile account = requireActiveAccount();
        UpstashSyncClient client = new UpstashSyncClient(account.getRedisUrl(), account.getAccessToken());
        JsonObject syncObject = client.getSyncObject(account.getUserGuid());
        // Skip decrypt + per-row diffing entirely when nothing changed remotely since
        // the last successful pull for this account - avoids unnecessary Upstash
        // bandwidth and local DB writes on every 60s poll when the user hasn't watched
        // anything new on KVideo's side.
        long remoteUpdatedAt = syncObject.has("updatedAt") ? syncObject.get("updatedAt").getAsLong() : -1;
        long lastSeenUpdatedAt = KVideoAccountStore.getLastUpdatedAt(account.getUsername());
        if (remoteUpdatedAt > 0 && remoteUpdatedAt == lastSeenUpdatedAt) return 0;

        JsonElement encryptedEl = syncObject.get("encrypted");
        if (encryptedEl == null || encryptedEl.isJsonNull()) return 0;
        SecretKey key = SyncCrypto.deriveKey(account.getPassword());
        String plaintext = SyncCrypto.decrypt(key, encryptedEl.getAsString());
        JsonObject payload = JsonParser.parseString(plaintext).getAsJsonObject();

        List<JsonObject> items = HistorySyncMapper.readHistoryItems(payload);
        for (JsonObject item : items) applyRemoteItem(item);
        pruneLocalRowsNotInRemote(items);
        if (!items.isEmpty()) com.fongmi.android.tv.event.RefreshEvent.history();

        List<JsonObject> favoriteItems = FavoriteSyncMapper.readFavoriteItems(payload);
        boolean favoritesChanged = applyRemoteFavorites(favoriteItems);
        // Without this, a background pull (pullIfStale) that writes new History/Keep
        // rows would leave an already-rendered "recent"/"favorites" list stale until the
        // user manually leaves and re-enters that screen - the write itself doesn't
        // trigger any UI refresh on its own.
        if (favoritesChanged) com.fongmi.android.tv.event.RefreshEvent.keep();

        if (remoteUpdatedAt > 0) KVideoAccountStore.setLastUpdatedAt(account.getUsername(), remoteUpdatedAt);
        return items.size();
    }

    /**
     * Deletes local History rows (for the current VOD config) whose showIdentifier
     * isn't present in this pull's remote response, so a deletion made on KVideo's side
     * (or by another device) is reflected here too, not just adds/updates. Accepted
     * tradeoff: a row webhtv itself just pushed can be pruned here if this pull raced
     * ahead of that push actually landing in Upstash (pushSingle() and pull() aren't
     * mutually exclusive) - the user explicitly chose full alignment over this edge
     * case's risk.
     */
    private void pruneLocalRowsNotInRemote(List<JsonObject> remoteItems) {
        java.util.Set<String> remoteIdentifiers = new java.util.HashSet<>();
        for (JsonObject item : remoteItems) {
            if (item.has("showIdentifier")) remoteIdentifiers.add(item.get("showIdentifier").getAsString());
        }
        int cid = com.fongmi.android.tv.api.config.VodConfig.getCid();
        for (History history : History.get(cid)) {
            String identifier = HistorySyncMapper.identifierFor(history.getVodName());
            if (!remoteIdentifiers.contains(identifier)) history.delete();
        }
    }

    /**
     * Applies KVideo's favorites array to local Keep rows: adds/updates rows matching a
     * remote item (keyed by source:videoId, not a title-based identifier - see
     * FavoriteSyncMapper's class note), then deletes local Keep rows (for the current
     * VOD config) whose source:videoId isn't present in the remote list, mirroring the
     * same full-alignment tradeoff pull() already applies to history. Returns whether
     * anything actually changed, so the caller can skip an unnecessary RefreshEvent.
     */
    private boolean applyRemoteFavorites(List<JsonObject> remoteItems) {
        int cid = com.fongmi.android.tv.api.config.VodConfig.getCid();
        java.util.Set<String> remoteIdentifiers = new java.util.HashSet<>();
        boolean changed = false;
        for (JsonObject item : remoteItems) {
            String identifier = FavoriteSyncMapper.identifierFor(item);
            remoteIdentifiers.add(identifier);
            Keep existing = findLocalKeepByIdentifier(identifier, cid);
            Keep updated = FavoriteSyncMapper.toKeep(item, existing);
            if (updated == null) continue; // source doesn't name a configured Site
            updated.save(cid);
            changed = true;
        }
        for (Keep keep : Keep.getVod()) {
            if (keep.getCid() != cid) continue;
            if (!remoteIdentifiers.contains(FavoriteSyncMapper.identifierFor(keep))) {
                keep.delete();
                changed = true;
            }
        }
        return changed;
    }

    private Keep findLocalKeepByIdentifier(String identifier, int cid) {
        for (Keep keep : Keep.getVod()) {
            if (keep.getCid() == cid && TextUtils.equals(FavoriteSyncMapper.identifierFor(keep), identifier)) return keep;
        }
        return null;
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

    /** Pushes a single History removal: read-modify-write, dropping the matching history
     *  entry (by showIdentifier) while leaving favorites and every other history row
     *  untouched. Without this, deleting a row from the local history list (not via
     *  playback quiescence) never reached Upstash, so the next pull()'s full-alignment
     *  pass would see the row still present remotely and resurrect it locally -
     *  confirmed as the cause of "deleted but comes back a minute later". */
    public void pushHistoryRemove(History history) {
        try {
            AccountProfile account = requireActiveAccount();
            UpstashSyncClient client = new UpstashSyncClient(account.getRedisUrl(), account.getAccessToken());
            SecretKey key = SyncCrypto.deriveKey(account.getPassword());
            JsonObject existingPayload = decryptExistingPayload(client, account.getUserGuid(), key);
            String identifier = HistorySyncMapper.identifierFor(history.getVodName());
            JsonArray remaining = new JsonArray();
            for (JsonElement element : existingHistory(existingPayload)) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                boolean isSameShow = item.has("showIdentifier") && TextUtils.equals(item.get("showIdentifier").getAsString(), identifier);
                if (!isSameShow) remaining.add(item);
            }
            JsonObject payload = new JsonObject();
            payload.add("history", remaining);
            payload.add("favorites", existingFavorites(existingPayload));
            String ciphertext = SyncCrypto.encrypt(key, payload.toString());
            client.putEncryptedPayload(account.getUserGuid(), ciphertext);
        } catch (Exception e) {
            com.github.catvod.crawler.SpiderDebug.log("kvideo-sync", "push history remove failed error=%s", e.getMessage());
        }
    }

    /** Pushes an empty history array (favorites untouched), for "clear all history" -
     *  an explicit product decision that clearing local history also clears KVideo's
     *  cloud copy, not just this device's local rows. */
    public void pushHistoryClear() {
        try {
            AccountProfile account = requireActiveAccount();
            UpstashSyncClient client = new UpstashSyncClient(account.getRedisUrl(), account.getAccessToken());
            SecretKey key = SyncCrypto.deriveKey(account.getPassword());
            JsonObject existingPayload = decryptExistingPayload(client, account.getUserGuid(), key);
            JsonObject payload = new JsonObject();
            payload.add("history", new JsonArray());
            payload.add("favorites", existingFavorites(existingPayload));
            String ciphertext = SyncCrypto.encrypt(key, payload.toString());
            client.putEncryptedPayload(account.getUserGuid(), ciphertext);
        } catch (Exception e) {
            com.github.catvod.crawler.SpiderDebug.log("kvideo-sync", "push history clear failed error=%s", e.getMessage());
        }
    }

    /** Pushes an empty favorites array (history untouched), for "clear all favorites" -
     *  symmetric to pushHistoryClear(). */
    public void pushFavoritesClear() {
        try {
            AccountProfile account = requireActiveAccount();
            UpstashSyncClient client = new UpstashSyncClient(account.getRedisUrl(), account.getAccessToken());
            SecretKey key = SyncCrypto.deriveKey(account.getPassword());
            JsonObject existingPayload = decryptExistingPayload(client, account.getUserGuid(), key);
            JsonObject payload = new JsonObject();
            payload.add("history", existingHistory(existingPayload));
            payload.add("favorites", new JsonArray());
            String ciphertext = SyncCrypto.encrypt(key, payload.toString());
            client.putEncryptedPayload(account.getUserGuid(), ciphertext);
        } catch (Exception e) {
            com.github.catvod.crawler.SpiderDebug.log("kvideo-sync", "push favorites clear failed error=%s", e.getMessage());
        }
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

    private JsonArray existingHistory(JsonObject existingPayload) {
        if (existingPayload == null || !existingPayload.has("history")) return new JsonArray();
        return existingPayload.getAsJsonArray("history");
    }

    /** Pushes a newly-added Keep row: read-modify-write, touching only the favorites
     *  array (history is carried over untouched, symmetric to how pushSingle() carries
     *  favorites over untouched). Matches/replaces by source:videoId per
     *  FavoriteSyncMapper's identifierFor(), same replace-or-append shape as
     *  mergeHistoryItem() - though KVideo's own addFavorite() never actually overwrites
     *  an existing entry (favorites-store.ts:44-71's `exists` check is a no-op on
     *  duplicates), this mirrors that by just re-adding since Keep.save() is itself an
     *  upsert on the webhtv side. */
    public void pushFavoriteAdd(Keep keep) {
        try {
            AccountProfile account = requireActiveAccount();
            UpstashSyncClient client = new UpstashSyncClient(account.getRedisUrl(), account.getAccessToken());
            SecretKey key = SyncCrypto.deriveKey(account.getPassword());
            JsonObject existingPayload = decryptExistingPayload(client, account.getUserGuid(), key);
            JsonArray mergedFavorites = mergeFavoriteItem(existingPayload, keep);
            JsonObject payload = new JsonObject();
            payload.add("history", existingHistory(existingPayload));
            payload.add("favorites", mergedFavorites);
            String ciphertext = SyncCrypto.encrypt(key, payload.toString());
            client.putEncryptedPayload(account.getUserGuid(), ciphertext);
        } catch (Exception e) {
            com.github.catvod.crawler.SpiderDebug.log("kvideo-sync", "push favorite add failed error=%s", e.getMessage());
        }
    }

    /** Pushes a Keep removal: read-modify-write, dropping the matching favorites entry
     *  (by source:videoId) while leaving history and every other favorite untouched. */
    public void pushFavoriteRemove(Keep keep) {
        try {
            AccountProfile account = requireActiveAccount();
            UpstashSyncClient client = new UpstashSyncClient(account.getRedisUrl(), account.getAccessToken());
            SecretKey key = SyncCrypto.deriveKey(account.getPassword());
            JsonObject existingPayload = decryptExistingPayload(client, account.getUserGuid(), key);
            String identifier = FavoriteSyncMapper.identifierFor(keep);
            JsonArray remaining = new JsonArray();
            for (JsonElement element : existingFavorites(existingPayload)) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                if (!TextUtils.equals(FavoriteSyncMapper.identifierFor(item), identifier)) remaining.add(item);
            }
            JsonObject payload = new JsonObject();
            payload.add("history", existingHistory(existingPayload));
            payload.add("favorites", remaining);
            String ciphertext = SyncCrypto.encrypt(key, payload.toString());
            client.putEncryptedPayload(account.getUserGuid(), ciphertext);
        } catch (Exception e) {
            com.github.catvod.crawler.SpiderDebug.log("kvideo-sync", "push favorite remove failed error=%s", e.getMessage());
        }
    }

    /** Replaces (by source:videoId) or appends this Keep's entry within the existing
     *  remote favorites array, rather than uploading only this one item and losing the
     *  rest - KVideo's SET overwrites the whole key, so a partial push would erase other
     *  favorites entirely. */
    private JsonArray mergeFavoriteItem(JsonObject existingPayload, Keep keep) {
        JsonArray items = new JsonArray();
        String identifier = FavoriteSyncMapper.identifierFor(keep);
        boolean replaced = false;
        for (JsonElement element : existingFavorites(existingPayload)) {
            if (!element.isJsonObject()) continue;
            JsonObject existingItem = element.getAsJsonObject();
            if (TextUtils.equals(FavoriteSyncMapper.identifierFor(existingItem), identifier)) {
                items.add(FavoriteSyncMapper.toKVideoItem(keep));
                replaced = true;
            } else {
                items.add(existingItem);
            }
        }
        if (!replaced) items.add(FavoriteSyncMapper.toKVideoItem(keep));
        return items;
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
