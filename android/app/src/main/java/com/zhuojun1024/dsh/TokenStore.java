package com.zhuojun1024.dsh;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Persists the server URL and an AES/GCM-encrypted token in app-private preferences. */
final class TokenStore {
    private static final String PREFS = "dsh_prefs";
    private static final String KEY_HOST = "host";
    private static final String KEY_TOKEN = "token";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "dsh_token_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private final SharedPreferences prefs;

    TokenStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean hasCredentials() {
        String host = getHost();
        String token = getToken();
        return host != null && !host.isEmpty() && token != null && !token.isEmpty();
    }

    String getHost() {
        return prefs.getString(KEY_HOST, null);
    }

    String getToken() {
        String stored = prefs.getString(KEY_TOKEN, null);
        if (stored == null) return null;
        try {
            return decrypt(stored);
        } catch (Exception e) {
            clear();
            return null;
        }
    }

    /** Saves credentials durably; throws when the token cannot be encrypted or persisted. */
    void save(String host, String token) {
        try {
            String encrypted = encrypt(token);
            boolean committed = prefs.edit()
                    .putString(KEY_HOST, host)
                    .putString(KEY_TOKEN, encrypted)
                    .commit();
            if (!committed) throw new IllegalStateException("SharedPreferences commit failed");
        } catch (Exception e) {
            throw new IllegalStateException("无法安全保存 token", e);
        }
    }

    void clear() {
        prefs.edit().remove(KEY_HOST).remove(KEY_TOKEN).apply();
    }

    private static SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (!keyStore.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) keyStore.getKey(ALIAS, null);
    }

    private static String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        ByteBuffer payload = ByteBuffer.allocate(1 + iv.length + ciphertext.length);
        payload.put((byte) iv.length).put(iv).put(ciphertext);
        return Base64.encodeToString(payload.array(), Base64.NO_WRAP);
    }

    private static String decrypt(String stored) throws Exception {
        byte[] payload = Base64.decode(stored, Base64.NO_WRAP);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int ivLength = buffer.get() & 0xFF;
        if (ivLength < 12 || ivLength > 16 || payload.length <= 1 + ivLength) {
            throw new IllegalArgumentException("bad token payload");
        }
        byte[] iv = new byte[ivLength];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
