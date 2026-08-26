package com.fongmi.android.tv.sync;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;

/**
 * Persists the account list feed's raw AES-256-CBC ciphertext on disk, keyed the same
 * as Config.vod()'s aesKey/aesIv. This is a deliberate reversal of the original
 * no-persistence decision: since aesKey/aesIv already sit in plaintext in the Config
 * SQLite table, anyone who can read app storage could already derive this ciphertext's
 * plaintext anyway - persisting it adds no new exposure, only removes a network
 * round-trip on every switcher open / sync.
 */
final class AccountListStore {

    private static final String KEY_CIPHERTEXT = "kvideo_account_list_ciphertext";

    private AccountListStore() {
    }

    static String getCiphertext() {
        return Prefers.getString(KEY_CIPHERTEXT);
    }

    static void saveCiphertext(String ciphertext) {
        Prefers.put(KEY_CIPHERTEXT, ciphertext == null ? "" : ciphertext);
    }

    static boolean hasCiphertext() {
        return !TextUtils.isEmpty(getCiphertext());
    }
}
