package com.ilker.microsoftreport;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MicrosoftApi {
    private static final String SCOPE = "https://api.partner.microsoft.com/.default offline_access";
    private MicrosoftApi() {}

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    private static String require(Context ctx, String key, String label) throws Exception {
        String value = AuthStore.load(ctx, key);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(label + " ayarlanmamış");
        return value.trim();
    }

    private static String tokenUrl(Context ctx) throws Exception {
        return "https://login.microsoftonline.com/" + require(ctx, AuthStore.TENANT_ID, "Tenant ID") + "/oauth2/v2.0/token";
    }

    private static JSONObject postForm(String url, Map<String,String> form) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
        StringBuilder b=new StringBuilder();
        for(Map.Entry<String,String> e:form.entrySet()){
            if(b.length()>0)b.append('&');
            b.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        try(OutputStream o=c.getOutputStream()){o.write(b.toString().getBytes(StandardCharsets.UTF_8));}
        int code=c.getResponseCode();
        String text=read(code<400?c.getInputStream():c.getErrorStream());
        JSONObject j=new JSONObject(text.isEmpty()?"{}":text); j.put("_http",code); return j;
    }

    private static String read(InputStream in) throws Exception {
        if(in==null)return "";
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n;
        while((n=in.read(buf))!=-1)out.write(buf,0,n);
        return out.toString("UTF-8");
    }

    private static JSONObject firstValue(JSONObject root) {
        JSONArray value = root.optJSONArray("value");
        if (value != null && value.length() > 0) return value.optJSONObject(0);
        return root;
    }

    private static void saveTokenResponse(Context ctx, JSONObject j) throws Exception {
        String at=j.optString("access_token",null);
        if(at!=null&&!at.isEmpty()) AuthStore.save(ctx,AuthStore.ACCESS_TOKEN,at);
        String rt=j.optString("refresh_token",null);
        if(rt!=null&&!rt.isEmpty()) AuthStore.save(ctx,AuthStore.REFRESH_TOKEN,rt);
        long expiresIn=Math.max(60,j.optLong("expires_in",3600));
        AuthStore.save(ctx,AuthStore.EXPIRES_AT,Long.toString(System.currentTimeMillis()+expiresIn*1000L));
    }

    public static JSONObject startDeviceCode(Context ctx) throws Exception {
        String tenant=require(ctx,AuthStore.TENANT_ID,"Tenant ID");
        String client=require(ctx,AuthStore.CLIENT_ID,"Client ID");
        Map<String,String> f=new LinkedHashMap<>(); f.put("client_id",client); f.put("scope",SCOPE);
        return postForm("https://login.microsoftonline.com/"+tenant+"/oauth2/v2.0/devicecode",f);
    }

    public static JSONObject pollDeviceCode(Context ctx,String deviceCode) throws Exception {
        Map<String,String> f=new LinkedHashMap<>();
        f.put("grant_type","urn:ietf:params:oauth:grant-type:device_code");
        f.put("client_id",require(ctx,AuthStore.CLIENT_ID,"Client ID"));
        f.put("device_code",deviceCode);
        JSONObject j=postForm(tokenUrl(ctx),f);
        if(j.optInt("_http")==200 && j.has("access_token")) saveTokenResponse(ctx,j);
        return j;
    }

    public static String accessToken(Context ctx) throws Exception {
        String at=AuthStore.load(ctx,AuthStore.ACCESS_TOKEN);
        long expiresAt=AuthStore.loadLong(ctx,AuthStore.EXPIRES_AT,0L);
        if(at!=null && System.currentTimeMillis()<expiresAt-120_000L) return at;

        String rt=AuthStore.load(ctx,AuthStore.REFRESH_TOKEN);
        if(rt==null||rt.isEmpty()) throw new IllegalStateException("Oturum yok; Microsoft ile giriş gerekli");
        Map<String,String> f=new LinkedHashMap<>();
        f.put("grant_type","refresh_token");
        f.put("client_id",require(ctx,AuthStore.CLIENT_ID,"Client ID"));
        f.put("refresh_token",rt);
        f.put("scope",SCOPE);
        JSONObject j=postForm(tokenUrl(ctx),f);
        if(!j.has("access_token")){
            if("invalid_grant".equals(j.optString("error"))) AuthStore.clearSession(ctx);
            throw new IOException(j.optString("error_description",j.toString()));
        }
        saveTokenResponse(ctx,j);
        return j.getString("access_token");
    }

    private static JSONObject authorizedJson(Context ctx,String method,String url) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(15000); c.setReadTimeout(45000);
        c.setRequestProperty("Authorization","Bearer "+accessToken(ctx));
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("ms-correlationid",java.util.UUID.randomUUID().toString());
        c.setRequestProperty("ms-requestid",java.util.UUID.randomUUID().toString());
        if("POST".equals(method)){c.setDoOutput(true);c.setFixedLengthStreamingMode(0);try(OutputStream o=c.getOutputStream()) {}}
        int code=c.getResponseCode();
        String body=read(code<400?c.getInputStream():c.getErrorStream());
        if(code>=400) throw new IOException("HTTP "+code+" "+body);
        JSONObject j=new JSONObject(body.isEmpty()?"{}":body); j.put("_http",code); return j;
    }

    private static String downloadBlob(String url) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod("GET"); c.setConnectTimeout(15000); c.setReadTimeout(60000);
        int code=c.getResponseCode();
        String body=read(code<400?c.getInputStream():c.getErrorStream());
        if(code>=400) throw new IOException("Blob HTTP "+code+" "+body);
        return body;
    }

    private static String summarizeCsv(String csv,LocalDate from,LocalDate to) {
        String[] lines=csv.split("\\r?\\n");
        StringBuilder out=new StringBuilder();
        out.append("Earnings export tamamlandı\nDönem: ").append(from).append(" → ").append(to)
                .append("\nSatır: ").append(Math.max(0,lines.length-1)).append("\n\n");
        int max=Math.min(lines.length,15);
        for(int i=0;i<max;i++) out.append(lines[i]).append('\n');
        if(lines.length>max) out.append("… ").append(lines.length-max).append(" satır daha");
        String s=out.toString();
        return s.length()>12000?s.substring(0,12000)+"\n…":s;
    }

    public static String syncEarnings(Context ctx,LocalDate from,LocalDate to) throws Exception {
        String range=from+"|"+to;
        String requestId=AuthStore.load(ctx,AuthStore.EXPORT_REQUEST_ID);
        String previousRange=AuthStore.load(ctx,AuthStore.EXPORT_RANGE);

        if(requestId==null || !range.equals(previousRange)){
            String filter="earningForDate ge "+from+" and earningForDate le "+to;
            String url="https://api.partner.microsoft.com/v1.0/payouts/transactionhistory?$filter="+enc(filter)+"&fileformat=csv";
            JSONObject queued=authorizedJson(ctx,"POST",url);
            JSONObject item=firstValue(queued);
            requestId=item.optString("requestId","");
            if(requestId.isEmpty()) throw new IOException("Export requestId dönmedi: "+queued);
            AuthStore.save(ctx,AuthStore.EXPORT_REQUEST_ID,requestId);
            AuthStore.save(ctx,AuthStore.EXPORT_RANGE,range);
            return "Earnings export kuyruğa alındı\nRequest ID: "+requestId+"\nSonraki kontrolde durum sorgulanacak.";
        }

        JSONObject stateRoot=authorizedJson(ctx,"GET","https://api.partner.microsoft.com/v1.0/payouts/transactionhistory/"+enc(requestId));
        JSONObject state=firstValue(stateRoot);
        String status=state.optString("status","Unknown");
        if("Completed".equalsIgnoreCase(status)){
            String blob=state.optString("blobLocation","");
            if(blob.isEmpty()) return "Export Completed ancak blobLocation henüz boş. Bir sonraki kontrolde yeniden denenecek.";
            String summary=summarizeCsv(downloadBlob(blob),from,to);
            AuthStore.save(ctx,AuthStore.LAST_REPORT,summary);
            AuthStore.remove(ctx,AuthStore.EXPORT_REQUEST_ID);
            AuthStore.remove(ctx,AuthStore.EXPORT_RANGE);
            return summary;
        }
        if("Failed".equalsIgnoreCase(status)){
            AuthStore.remove(ctx,AuthStore.EXPORT_REQUEST_ID);
            AuthStore.remove(ctx,AuthStore.EXPORT_RANGE);
            throw new IOException("Earnings export başarısız oldu; sonraki kontrolde yeni export oluşturulabilir.");
        }
        return "Earnings export durumu: "+status+"\nRequest ID: "+requestId;
    }

    public static String today(Context ctx) throws Exception {
        LocalDate d=LocalDate.now(); return syncEarnings(ctx,d,d);
    }

    public static String month(Context ctx) throws Exception {
        LocalDate d=LocalDate.now(); return syncEarnings(ctx,d.withDayOfMonth(1),d);
    }
}
