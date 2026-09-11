package com.ilker.microsoftreport;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MicrosoftLogin {
    public static final String REDIRECT_URI="com.ilker.microsoftreport://oauth2redirect";
    private static final String SCOPE="https://api.partner.microsoft.com/.default offline_access";
    private static final String PKCE_VERIFIER="oauth_pkce_verifier";
    private static final String OAUTH_STATE="oauth_state";

    private MicrosoftLogin(){}

    private static String require(Context ctx,String key,String label) throws Exception{
        String value=AuthStore.load(ctx,key);
        if(value==null||value.trim().isEmpty())throw new IllegalStateException(label+" ayarlanmamış");
        return value.trim();
    }

    private static String randomUrlSafe(int bytes){
        byte[] data=new byte[bytes];
        new SecureRandom().nextBytes(data);
        return Base64.encodeToString(data,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
    }

    private static String challenge(String verifier) throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.encodeToString(digest,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
    }

    public static Uri begin(Context ctx) throws Exception{
        String tenant=require(ctx,AuthStore.TENANT_ID,"Tenant ID");
        String client=require(ctx,AuthStore.CLIENT_ID,"Client ID");
        String verifier=randomUrlSafe(48);
        String state=randomUrlSafe(24);
        AuthStore.save(ctx,PKCE_VERIFIER,verifier);
        AuthStore.save(ctx,OAUTH_STATE,state);

        return Uri.parse("https://login.microsoftonline.com/"+Uri.encode(tenant)+"/oauth2/v2.0/authorize")
                .buildUpon()
                .appendQueryParameter("client_id",client)
                .appendQueryParameter("response_type","code")
                .appendQueryParameter("redirect_uri",REDIRECT_URI)
                .appendQueryParameter("response_mode","query")
                .appendQueryParameter("scope",SCOPE)
                .appendQueryParameter("code_challenge",challenge(verifier))
                .appendQueryParameter("code_challenge_method","S256")
                .appendQueryParameter("state",state)
                .build();
    }

    public static void finish(Context ctx,Uri callback) throws Exception{
        if(callback==null)throw new IllegalArgumentException("OAuth dönüş adresi boş");
        String error=callback.getQueryParameter("error");
        if(error!=null&&!error.isEmpty()){
            String description=callback.getQueryParameter("error_description");
            throw new IllegalStateException(description==null||description.isEmpty()?error:description);
        }

        String expectedState=AuthStore.load(ctx,OAUTH_STATE);
        String actualState=callback.getQueryParameter("state");
        if(expectedState==null||actualState==null||!expectedState.equals(actualState))
            throw new SecurityException("Microsoft giriş state doğrulaması başarısız");

        String code=callback.getQueryParameter("code");
        if(code==null||code.isEmpty())throw new IllegalStateException("Microsoft authorization code dönmedi");
        String verifier=AuthStore.load(ctx,PKCE_VERIFIER);
        if(verifier==null||verifier.isEmpty())throw new IllegalStateException("PKCE doğrulayıcı bulunamadı");

        String tenant=require(ctx,AuthStore.TENANT_ID,"Tenant ID");
        String client=require(ctx,AuthStore.CLIENT_ID,"Client ID");
        Map<String,String> form=new LinkedHashMap<>();
        form.put("client_id",client);
        form.put("grant_type","authorization_code");
        form.put("code",code);
        form.put("redirect_uri",REDIRECT_URI);
        form.put("code_verifier",verifier);
        form.put("scope",SCOPE);

        JSONObject token=postForm("https://login.microsoftonline.com/"+tenant+"/oauth2/v2.0/token",form);
        if(token.optInt("_http")!=200||!token.has("access_token"))
            throw new IllegalStateException(token.optString("error_description",token.toString()));

        String access=token.optString("access_token","");
        String refresh=token.optString("refresh_token","");
        if(access.isEmpty()||refresh.isEmpty())throw new IllegalStateException("Microsoft token yanıtı eksik");
        long expiresIn=Math.max(60,token.optLong("expires_in",3600));
        AuthStore.save(ctx,AuthStore.ACCESS_TOKEN,access);
        AuthStore.save(ctx,AuthStore.REFRESH_TOKEN,refresh);
        AuthStore.save(ctx,AuthStore.EXPIRES_AT,Long.toString(System.currentTimeMillis()+expiresIn*1000L));
        AuthStore.remove(ctx,PKCE_VERIFIER);
        AuthStore.remove(ctx,OAUTH_STATE);
    }

    public static boolean isCallback(Uri uri){
        return uri!=null&&"com.ilker.microsoftreport".equalsIgnoreCase(uri.getScheme())&&"oauth2redirect".equalsIgnoreCase(uri.getHost());
    }

    private static JSONObject postForm(String url,Map<String,String> form) throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
        StringBuilder body=new StringBuilder();
        for(Map.Entry<String,String> e:form.entrySet()){
            if(body.length()>0)body.append('&');
            body.append(URLEncoder.encode(e.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(e.getValue(),"UTF-8"));
        }
        try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
        int code=c.getResponseCode();
        String text=read(code<400?c.getInputStream():c.getErrorStream());
        JSONObject json=new JSONObject(text.isEmpty()?"{}":text);
        json.put("_http",code);
        return json;
    }

    private static String read(InputStream in) throws Exception{
        if(in==null)return "";
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buf=new byte[8192];
        int n;
        while((n=in.read(buf))!=-1)out.write(buf,0,n);
        return out.toString("UTF-8");
    }
}
