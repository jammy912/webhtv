package com.fongmi.android.tv.utils;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.SpiderDebug;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-GCM sealing backed by the Android Keystore, for secrets that must be
 * stored but never shown again (currently SMB share passwords).
 *
 * <p>Unlike {@code SyncCrypto} this needs no user passphrase — the point of the
 * SMB feature is that credentials are typed once in the web management page and
 * never re-entered with a remote control. The key lives in the TEE and sealing
 * costs microseconds, so it is cheap enough to call on every share connection.
 *
 * <p>If the Keystore is unavailable the value is stored as-is. Degrading to
 * plaintext keeps the feature working on ROMs with a broken Keystore; losing the
 * server list would be the worse failure.
 */
public final class SecretBox {

    private static final String TAG = "secret-box";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "smb_secret_v1";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private SecretBox() {
    }

    /** Encrypts {@code plain}; returns the input unchanged if the Keystore fails. */
    public static String seal(String plain) {
        if (TextUtils.isEmpty(plain)) return "";
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plain.getBytes("UTF-8"));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "seal failed errorType=%s", e.getClass().getSimpleName());
            return plain;
        }
    }

    /**
     * Decrypts a value produced by {@link #seal}. Values without the version
     * prefix are returned as-is, so plaintext written during a Keystore outage
     * still reads back correctly.
     *
     * @return the plaintext, or an empty string if the key was invalidated
     */
    public static String open(String sealed) {
        if (TextUtils.isEmpty(sealed)) return "";
        if (!sealed.startsWith(PREFIX)) return sealed;
        try {
            byte[] raw = Base64.decode(sealed.substring(PREFIX.length()), Base64.NO_WRAP);
            if (raw.length <= IV_LENGTH) return "";
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH));
            byte[] pt = cipher.doFinal(raw, IV_LENGTH, raw.length - IV_LENGTH);
            return new String(pt, "UTF-8");
        } catch (Throwable e) {
            // Includes KeyPermanentlyInvalidatedException: changing the lock screen
            // can drop the key. The caller sees an empty password and the user is
            // asked to re-enter it in the web page rather than hitting a crash.
            SpiderDebug.log(TAG, "open failed errorType=%s", e.getClass().getSimpleName());
            return "";
        }
    }

    /** True when the value is sealed rather than stored as plaintext. */
    public static boolean isSealed(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry secret) return secret.getSecretKey();
        return generate();
    }

    private static SecretKey generate() throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) throw new IllegalStateException("keystore aes unsupported");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }
}
