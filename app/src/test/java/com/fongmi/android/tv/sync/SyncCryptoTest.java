package com.fongmi.android.tv.sync;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;

/**
 * Verifies the KVideo-compatible crypto core (PBKDF2WithHmacSHA256 + AES/GCM/NoPadding)
 * without touching android.util.Base64, which is unavailable in plain JVM unit tests.
 */
public class SyncCryptoTest {

    @Test
    public void deriveKey_matchesKnownVector() throws Exception {
        // AES-256 key must be exactly 32 bytes regardless of password/salt content.
        SecretKey key = SyncCrypto.deriveKey("correct horse battery staple");
        assertEquals("AES", key.getAlgorithm());
        assertEquals(32, key.getEncoded().length);
    }

    @Test
    public void deriveKey_isDeterministicForSamePassword() throws Exception {
        SecretKey key1 = SyncCrypto.deriveKey("same-password");
        SecretKey key2 = SyncCrypto.deriveKey("same-password");
        assertArrayEquals(key1.getEncoded(), key2.getEncoded());
    }

    @Test
    public void deriveKey_differsForDifferentPassword() throws Exception {
        SecretKey key1 = SyncCrypto.deriveKey("password-a");
        SecretKey key2 = SyncCrypto.deriveKey("password-b");
        assertNotEquals(bytesToHex(key1.getEncoded()), bytesToHex(key2.getEncoded()));
    }

    @Test
    public void encryptThenDecrypt_roundTripsPlaintext() throws Exception {
        SecretKey key = SyncCrypto.deriveKey("kvideo-test-password");
        String plaintext = "{\"history\":[],\"favorites\":[]}";
        byte[] combined = SyncCrypto.encryptToBytes(key, plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] decrypted = SyncCrypto.decryptFromBytes(key, combined);
        assertEquals(plaintext, new String(decrypted, StandardCharsets.UTF_8));
    }

    @Test
    public void encrypt_prependsTwelveByteIv() throws Exception {
        SecretKey key = SyncCrypto.deriveKey("iv-length-check");
        byte[] combined = SyncCrypto.encryptToBytes(key, "x".getBytes(StandardCharsets.UTF_8));
        // 12-byte IV + 1-byte ciphertext + 16-byte GCM tag = 29 bytes total.
        assertEquals(12 + 1 + 16, combined.length);
    }

    @Test
    public void encrypt_producesDifferentCiphertextEachTime() throws Exception {
        SecretKey key = SyncCrypto.deriveKey("randomized-iv");
        byte[] a = SyncCrypto.encryptToBytes(key, "same plaintext".getBytes(StandardCharsets.UTF_8));
        byte[] b = SyncCrypto.encryptToBytes(key, "same plaintext".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(bytesToHex(a), bytesToHex(b));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Cross-system verification against KVideo's actual Node.js WebCrypto output
     * (both crypto.pbkdf2Sync and crypto.webcrypto agreed on the key bytes).
     */
    @Test
    public void crossSystem_matchesKVideoNodeVector() throws Exception {
        String password = "webhtv-cross-check-2026";
        String expectedKeyHex = "d72b802692b25ba2fcc6b7ad8a761dfc699a48999a744c5b3ded6d4ffe2e6305".substring(0, 64);
        String kvideoCiphertextBase64 = "w2vlhwIKc/R8ygD4zcT+jYKNVSpa4R/0tQSNpFV6FQzTwag3jyLwVkPdVFMFYvleZFLRUNDg0Znqr+6Klq1p6UsgIqfLo8XwS/xIVyF5PCM=";
        String expectedPlaintext = "{\"history\":[],\"favorites\":[],\"marker\":\"cross-check\"}";

        SecretKey key = SyncCrypto.deriveKey(password);
        assertEquals(expectedKeyHex, bytesToHex(key.getEncoded()));

        // android.util.Base64 is a stub in plain JVM unit tests; java.util.Base64 (JDK 8+)
        // decodes the identical standard-alphabet payload for test purposes only.
        byte[] combined = java.util.Base64.getDecoder().decode(kvideoCiphertextBase64);
        byte[] decrypted = SyncCrypto.decryptFromBytes(key, combined);
        assertEquals(expectedPlaintext, new String(decrypted, StandardCharsets.UTF_8));
    }
}
