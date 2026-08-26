package com.fongmi.android.tv.sync;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Byte-for-byte compatible with KVideo's lib/utils/sync-crypto.ts (Web Crypto API).
 * PBKDF2WithHmacSHA256, fixed salt "kvideo-sync-v1_!", 250000 iterations, 256-bit key.
 * AES/GCM/NoPadding, 12-byte random IV prefixed to ciphertext+tag, no AAD, base64 envelope.
 */
public class SyncCrypto {

    private static final byte[] SALT = "kvideo-sync-v1_!".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 250_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private SyncCrypto() {
    }

    public static SecretKey deriveKey(String password) throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), SALT, ITERATIONS, KEY_LENGTH_BITS);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    public static String encrypt(SecretKey key, String plaintext) throws GeneralSecurityException {
        byte[] combined = encryptToBytes(key, plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    public static String decrypt(SecretKey key, String base64) throws GeneralSecurityException {
        byte[] combined = Base64.decode(base64, Base64.DEFAULT);
        return new String(decryptFromBytes(key, combined), StandardCharsets.UTF_8);
    }

    /** Base64-free core, kept separate so PBKDF2/AES-GCM correctness can be unit tested off-device. */
    static byte[] encryptToBytes(SecretKey key, byte[] plaintext) throws GeneralSecurityException {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        return combined;
    }

    static byte[] decryptFromBytes(SecretKey key, byte[] combined) throws GeneralSecurityException {
        if (combined.length < IV_LENGTH_BYTES) throw new GeneralSecurityException("Payload too short");
        byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH_BYTES, combined.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        return cipher.doFinal(ciphertext);
    }
}
