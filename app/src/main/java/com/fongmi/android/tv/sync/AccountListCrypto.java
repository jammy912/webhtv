package com.fongmi.android.tv.sync;

import android.util.Base64;

import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-CBC decryption for the Google Apps Script account-list feed, using a KEY/IV
 * pair independent from Config.aesKey/aesIv (VOD/live sources). Same algorithm as
 * Decoder's private aes256cbc() but kept separate: this list carries KVideo login
 * passwords and Redis tokens, a different trust boundary from source configs.
 */
final class AccountListCrypto {

    private AccountListCrypto() {
    }

    static String decrypt(String base64Data, String key, String iv) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        byte[] decrypted = cipher.doFinal(Base64.decode(base64Data.trim(), Base64.DEFAULT));
        return new String(decrypted, StandardCharsets.UTF_8);
    }
}
