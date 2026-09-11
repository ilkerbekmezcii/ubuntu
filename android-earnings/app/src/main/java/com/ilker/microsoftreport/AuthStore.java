package com.ilker.microsoftreport;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AuthStore {
    private static final String ALIAS = "partner_refresh_key";
    private static final String PREF = "auth_store";

    public static final String CLIENT_ID = "client_id";
    public static final String TENANT_ID = "tenant_id";
    public static final String ACCESS_TOKEN = "access_token";
    public static final String REFRESH_TOKEN = "refresh_token";
    public static final String EXPIRES_AT = "expires_at";
    public static final String EXPORT_REQUEST_ID = "export_request_id";
    public static final String EXPORT_RANGE = "export_range";
    public static final String LAST_REPORT = "last_report";

    private AuthStore() {}

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
    }

    public static void save(Context c, String name, String value) throws Exception {
        if (value == null) {
            remove(c, name);
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        String packed = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(name, packed).apply();
    }

    public static String load(Context c, String name) throws Exception {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String packed = p.getString(name, null);
        if (packed == null) return null;
        String[] parts = packed.split(":", 2);
        if (parts.length != 2) return null;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                Base64.decode(parts[0], Base64.NO_WRAP)));
        byte[] pt = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
        return new String(pt, StandardCharsets.UTF_8);
    }

    public static long loadLong(Context c, String name, long defaultValue) {
        try {
            String value = load(c, name);
            return value == null ? defaultValue : Long.parseLong(value);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    public static void remove(Context c, String name) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(name).apply();
    }

    public static void clearSession(Context c) {
        remove(c, ACCESS_TOKEN);
        remove(c, REFRESH_TOKEN);
        remove(c, EXPIRES_AT);
        remove(c, EXPORT_REQUEST_ID);
        remove(c, EXPORT_RANGE);
    }

    public static void clearAll(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
