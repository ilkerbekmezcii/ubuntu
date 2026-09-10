package com.spg.storesales;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class CredentialStore {
    private static final String PREFS = "microsoft_rapor_secure";
    private static final String KEY_ENV = "encrypted_env_v2";
    private static final String ALIAS = "partner_center_credentials_v2";
    private final SharedPreferences prefs;

    public CredentialStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean hasCredentials() { return prefs.contains(KEY_ENV); }

    public void saveEnv(String env) throws Exception {
        parse(env);
        prefs.edit().putString(KEY_ENV, encrypt(env)).apply();
    }

    public Credentials load() throws Exception {
        return parse(decrypt(prefs.getString(KEY_ENV, "")));
    }

    private static Credentials parse(String env) {
        Map<String, String> map = new HashMap<>();
        for (String line : env.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int p = s.indexOf('=');
            if (p < 1) continue;
            String key = s.substring(0, p).trim();
            String value = s.substring(p + 1).trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            map.put(key, value);
        }
        return new Credentials(req(map, "PARTNER_CENTER_TENANT_ID"), req(map, "PARTNER_CENTER_CLIENT_ID"), req(map, "PARTNER_CENTER_CLIENT_SECRET"));
    }

    private static String req(Map<String, String> map, String key) {
        String value = map.get(key);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(key + " eksik");
        return value.trim();
    }

    private String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private String decrypt(String packed) throws Exception {
        String[] parts = packed.split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("Kimlik bilgisi bozuk");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }

    public static final class Credentials {
        public final String tenantId;
        public final String clientId;
        public final String clientSecret;
        Credentials(String tenantId, String clientId, String clientSecret) {
            this.tenantId = tenantId;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }
    }
}
