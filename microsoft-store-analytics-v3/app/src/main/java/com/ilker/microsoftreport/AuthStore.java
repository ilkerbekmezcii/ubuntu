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
    private static final String PREF="ms_store_auth_v3", ALIAS="ms_store_analytics_config_v3";
    private final SharedPreferences prefs;
    public AuthStore(Context c){prefs=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    public synchronized void save(EnvConfig cfg)throws Exception{prefs.edit().putString("tenant_id",cfg.tenantId).putString("client_id",cfg.clientId).putString("client_secret_enc",encrypt(cfg.clientSecret)).apply();}
    public synchronized EnvConfig load()throws Exception{String t=prefs.getString("tenant_id",""),c=prefs.getString("client_id",""),e=prefs.getString("client_secret_enc",""); if(t.isEmpty()||c.isEmpty()||e.isEmpty()) throw new IllegalStateException("Microsoft ENV yapılandırması bulunamadı"); return new EnvConfig(t,c,decrypt(e));}
    public boolean hasConfig(){return !prefs.getString("tenant_id","").isEmpty()&&!prefs.getString("client_id","").isEmpty()&&!prefs.getString("client_secret_enc","").isEmpty();}
    private SecretKey key()throws Exception{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(!ks.containsAlias(ALIAS)){KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());g.generateKey();}return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey();}
    private String encrypt(String p)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());return Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+"."+Base64.encodeToString(c.doFinal(p.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);}
    private String decrypt(String p)throws Exception{String[]x=p.split("\\.",2);if(x.length!=2)throw new IllegalStateException("Şifreli secret verisi geçersiz");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(x[0],Base64.NO_WRAP)));return new String(c.doFinal(Base64.decode(x[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);}
}
