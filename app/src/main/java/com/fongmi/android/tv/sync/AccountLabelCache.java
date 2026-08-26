package com.fongmi.android.tv.sync;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Caches only username+label pairs from the account list, so the switcher dialog can
 * render instantly instead of blocking on a network round-trip + AES decrypt every time
 * it opens. Deliberately excludes password/redisUrl/accessToken/userGuid - those never
 * touch disk (see KVideoAccountStore/KVideoSyncEngine), only this label cache does.
 */
final class AccountLabelCache {

    private static final String KEY_CACHE = "kvideo_account_label_cache";
    private static final Type LIST_TYPE = new TypeToken<List<Entry>>() {
    }.getType();

    private AccountLabelCache() {
    }

    static List<Entry> get() {
        try {
            List<Entry> entries = App.gson().fromJson(Prefers.getString(KEY_CACHE), LIST_TYPE);
            return entries == null ? new ArrayList<>() : entries;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    static void save(List<AccountProfile> profiles) {
        List<Entry> entries = new ArrayList<>();
        for (AccountProfile profile : profiles) entries.add(new Entry(profile.getUsername(), profile.getLabel()));
        Prefers.put(KEY_CACHE, App.gson().toJson(entries));
    }

    static final class Entry {
        final String username;
        final String label;

        Entry(String username, String label) {
            this.username = username;
            this.label = label;
        }
    }
}
