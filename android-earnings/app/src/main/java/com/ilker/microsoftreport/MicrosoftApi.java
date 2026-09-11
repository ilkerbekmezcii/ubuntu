package com.ilker.microsoftreport;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MicrosoftApi {
    public static final String SCOPE = "https://api.partner.microsoft.com/user_impersonation offline_access openid profile";
    private MicrosoftApi() {}

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    private static JSONObject postForm(String url, Map<String,String> form) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
        StringBuilder b=new StringBuilder();
        for(Map.Entry<String,String> e:form.entrySet()){ if(b.length()>0)b.append('&'); b.append(enc(e.getKey())).append('=').append(enc(e.getValue())); }
        try(OutputStream o=c.getOutputStream()){o.write(b.toString().getBytes(StandardCharsets.UTF_8));}
        InputStream in=(c.getResponseCode()<400)?c.getInputStream():c.getErrorStream();
        String text=read(in); JSONObject j=new JSONObject(text.isEmpty()?"{}":text); j.put("_http",c.getResponseCode()); return j;
    }

    private static String read(InputStream in) throws Exception {
        if(in==null)return ""; ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n;
        while((n=in.read(buf))!=-1)out.write(buf,0,n); return out.toString("UTF-8");
    }

    public static JSONObject startDeviceCode() throws Exception {
        Map<String,String> f=new LinkedHashMap<>(); f.put("client_id",BuildConfig.CLIENT_ID); f.put("scope",SCOPE);
        return postForm("https://login.microsoftonline.com/"+BuildConfig.TENANT_ID+"/oauth2/v2.0/devicecode",f);
    }

    public static JSONObject pollDeviceCode(String deviceCode) throws Exception {
        Map<String,String> f=new LinkedHashMap<>();
        f.put("grant_type","urn:ietf:params:oauth:grant-type:device_code"); f.put("client_id",BuildConfig.CLIENT_ID); f.put("device_code",deviceCode);
        return postForm("https://login.microsoftonline.com/"+BuildConfig.TENANT_ID+"/oauth2/v2.0/token",f);
    }

    public static String refresh(Context ctx) throws Exception {
        String rt=AuthStore.loadRefreshToken(ctx); if(rt==null)throw new IllegalStateException("Oturum yok");
        Map<String,String> f=new LinkedHashMap<>(); f.put("grant_type","refresh_token"); f.put("client_id",BuildConfig.CLIENT_ID); f.put("refresh_token",rt); f.put("scope",SCOPE);
        JSONObject j=postForm("https://login.microsoftonline.com/"+BuildConfig.TENANT_ID+"/oauth2/v2.0/token",f);
        if(!j.has("access_token"))throw new IOException(j.optString("error_description",j.toString()));
        String newRt=j.optString("refresh_token",null); if(newRt!=null && !newRt.isEmpty())AuthStore.saveRefreshToken(ctx,newRt);
        return j.getString("access_token");
    }

    public static String earnings(String accessToken, LocalDate from, LocalDate to) throws Exception {
        String filter="earningForDate ge "+from+" and earningForDate le "+to;
        String url="https://api.partner.microsoft.com/v1.0/payouts/transactionhistory?$filter="+enc(filter)+"&fileformat=csv";
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection(); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setConnectTimeout(15000); c.setReadTimeout(45000); c.setRequestProperty("Authorization","Bearer "+accessToken); c.setRequestProperty("Accept","application/json"); c.setFixedLengthStreamingMode(0);
        try(OutputStream o=c.getOutputStream()){}
        String body=read(c.getResponseCode()<400?c.getInputStream():c.getErrorStream());
        if(c.getResponseCode()>=400)throw new IOException("HTTP "+c.getResponseCode()+" "+body);
        return body;
    }
}
