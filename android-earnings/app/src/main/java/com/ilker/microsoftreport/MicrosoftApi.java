package com.ilker.microsoftreport;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

public final class MicrosoftApi {
    private static final String SCOPE="https://api.partner.microsoft.com/.default offline_access";
    private MicrosoftApi(){}

    public static final class DashboardResult{
        public final String displayText;
        public final int newPurchaseCount;
        public final double newPurchaseGrossUsd;
        public final double newPurchaseNetUsd;
        DashboardResult(String text,int count,double gross,double net){displayText=text;newPurchaseCount=count;newPurchaseGrossUsd=gross;newPurchaseNetUsd=net;}
    }

    private static final class Purchase{double gross;double net;}
    private static final class RangeData{
        double grossUsd;
        double netUsd;
        final LinkedHashMap<String,Purchase> purchases=new LinkedHashMap<>();
    }
    private static final class SyncResult{
        final RangeData data;
        final String status;
        SyncResult(RangeData d,String s){data=d;status=s;}
    }
    private static final class PurchaseDelta{int count;double gross;double net;}

    private static String enc(String s) throws Exception{return URLEncoder.encode(s,"UTF-8");}

    private static String require(Context ctx,String key,String label) throws Exception{
        String value=AuthStore.load(ctx,key);
        if(value==null||value.trim().isEmpty())throw new IllegalStateException(label+" ayarlanmamış");
        return value.trim();
    }

    private static String tokenUrl(Context ctx) throws Exception{
        return "https://login.microsoftonline.com/"+require(ctx,AuthStore.TENANT_ID,"Tenant ID")+"/oauth2/v2.0/token";
    }

    private static JSONObject postForm(String url,Map<String,String> form) throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(15000);c.setReadTimeout(30000);
        c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
        StringBuilder b=new StringBuilder();
        for(Map.Entry<String,String> e:form.entrySet()){
            if(b.length()>0)b.append('&');
            b.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        try(OutputStream o=c.getOutputStream()){o.write(b.toString().getBytes(StandardCharsets.UTF_8));}
        int code=c.getResponseCode();
        String text=read(code<400?c.getInputStream():c.getErrorStream());
        JSONObject j=new JSONObject(text.isEmpty()?"{}":text);j.put("_http",code);return j;
    }

    private static String read(InputStream in) throws Exception{
        if(in==null)return "";
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
        while((n=in.read(buf))!=-1)out.write(buf,0,n);
        return out.toString("UTF-8");
    }

    private static void saveTokenResponse(Context ctx,JSONObject j) throws Exception{
        String at=j.optString("access_token",null);if(at!=null&&!at.isEmpty())AuthStore.save(ctx,AuthStore.ACCESS_TOKEN,at);
        String rt=j.optString("refresh_token",null);if(rt!=null&&!rt.isEmpty())AuthStore.save(ctx,AuthStore.REFRESH_TOKEN,rt);
        long expiresIn=Math.max(60,j.optLong("expires_in",3600));
        AuthStore.save(ctx,AuthStore.EXPIRES_AT,Long.toString(System.currentTimeMillis()+expiresIn*1000L));
    }

    public static String accessToken(Context ctx) throws Exception{
        String at=AuthStore.load(ctx,AuthStore.ACCESS_TOKEN);
        long expiresAt=AuthStore.loadLong(ctx,AuthStore.EXPIRES_AT,0L);
        if(at!=null&&System.currentTimeMillis()<expiresAt-120_000L)return at;
        String rt=AuthStore.load(ctx,AuthStore.REFRESH_TOKEN);
        if(rt==null||rt.isEmpty())throw new IllegalStateException("Oturum yok; Microsoft ile giriş gerekli");
        Map<String,String> f=new LinkedHashMap<>();
        f.put("grant_type","refresh_token");
        f.put("client_id",require(ctx,AuthStore.CLIENT_ID,"Client ID"));
        f.put("refresh_token",rt);f.put("scope",SCOPE);
        JSONObject j=postForm(tokenUrl(ctx),f);
        if(!j.has("access_token")){
            if("invalid_grant".equals(j.optString("error")))AuthStore.clearSession(ctx);
            throw new IOException(j.optString("error_description",j.toString()));
        }
        saveTokenResponse(ctx,j);return j.getString("access_token");
    }

    private static JSONObject authorizedJson(Context ctx,String method,String url) throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(45000);
        c.setRequestProperty("Authorization","Bearer "+accessToken(ctx));
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("ms-correlationid",UUID.randomUUID().toString());
        c.setRequestProperty("ms-requestid",UUID.randomUUID().toString());
        if("POST".equals(method)){c.setDoOutput(true);c.setFixedLengthStreamingMode(0);try(OutputStream o=c.getOutputStream()) {}}
        int code=c.getResponseCode();
        String body=read(code<400?c.getInputStream():c.getErrorStream());
        if(code>=400)throw new IOException("HTTP "+code+" "+body);

        String trimmed=body==null?"":body.trim();
        JSONObject j;
        if(trimmed.isEmpty())j=new JSONObject();
        else if(trimmed.startsWith("[")){
            j=new JSONObject();
            j.put("value",new JSONArray(trimmed));
        }else j=new JSONObject(trimmed);
        j.put("_http",code);
        String location=c.getHeaderField("Location");
        if(location!=null&&!location.isEmpty())j.put("_location",location);
        return j;
    }

    private static String normalizedKey(String key){return key==null?"":key.toLowerCase(Locale.US).replaceAll("[^a-z0-9]","");}

    private static String findRequestId(Object value){
        if(value instanceof JSONObject){
            JSONObject o=(JSONObject)value;
            Iterator<String> keys=o.keys();
            while(keys.hasNext()){
                String key=keys.next();
                if("requestid".equals(normalizedKey(key))){
                    String id=o.optString(key,"").trim();
                    if(!id.isEmpty())return id;
                }
            }
            keys=o.keys();
            while(keys.hasNext()){
                String key=keys.next();
                if(key.startsWith("_"))continue;
                String nested=findRequestId(o.opt(key));
                if(nested!=null&&!nested.isEmpty())return nested;
            }
        }else if(value instanceof JSONArray){
            JSONArray a=(JSONArray)value;
            for(int i=0;i<a.length();i++){
                String nested=findRequestId(a.opt(i));
                if(nested!=null&&!nested.isEmpty())return nested;
            }
        }
        return null;
    }

    private static String requestIdFromResponse(JSONObject response){
        String id=findRequestId(response);
        if(id!=null&&!id.isEmpty())return id;
        String location=response.optString("_location","");
        if(!location.isEmpty()){
            try{
                String path=new URL(location).getPath();
                if(path!=null){
                    int slash=path.lastIndexOf('/');
                    String tail=slash>=0?path.substring(slash+1):path;
                    if(tail.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))return tail;
                }
            }catch(Exception ignored){}
        }
        return "";
    }

    private static JSONObject firstValue(JSONObject root){
        Object value=root.opt("value");
        if(value instanceof JSONArray){
            JSONArray a=(JSONArray)value;
            if(a.length()>0&&a.opt(0) instanceof JSONObject)return a.optJSONObject(0);
        }
        if(value instanceof JSONObject)return (JSONObject)value;
        return root;
    }

    private static String downloadBlob(String url) throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod("GET");c.setConnectTimeout(15000);c.setReadTimeout(60000);
        int code=c.getResponseCode();String body=read(code<400?c.getInputStream():c.getErrorStream());
        if(code>=400)throw new IOException("Blob HTTP "+code+" "+body);return body;
    }

    private static String queueExport(Context ctx,LocalDate from,LocalDate to) throws Exception{
        String filter="earningForDate ge "+from+" and earningForDate le "+to;
        String url="https://api.partner.microsoft.com/v1.0/payouts/transactionhistory?$filter="+enc(filter)+"&fileformat=csv";
        JSONObject queued=authorizedJson(ctx,"POST",url);
        String requestId=requestIdFromResponse(queued);
        if(requestId.isEmpty())throw new IOException("Export requestId dönmedi (HTTP "+queued.optInt("_http")+"): "+queued.toString());
        return requestId;
    }

    private static SyncResult syncRange(Context ctx,LocalDate from,LocalDate to,String requestKey,String rangeKey) throws Exception{
        String range=from+"|"+to;
        String requestId=AuthStore.load(ctx,requestKey),previousRange=AuthStore.load(ctx,rangeKey);
        if(requestId==null||!range.equals(previousRange)){
            requestId=queueExport(ctx,from,to);
            AuthStore.save(ctx,requestKey,requestId);AuthStore.save(ctx,rangeKey,range);
            return new SyncResult(null,"Queued");
        }

        JSONObject root=authorizedJson(ctx,"GET","https://api.partner.microsoft.com/v1.0/payouts/transactionhistory/"+enc(requestId));
        JSONObject state=firstValue(root);String status=state.optString("status","Unknown");
        if("Completed".equalsIgnoreCase(status)){
            String blob=state.optString("blobLocation","");
            if(blob.isEmpty())return new SyncResult(null,"Completed-awaiting-blob");
            RangeData data=parseCsv(downloadBlob(blob));
            AuthStore.remove(ctx,requestKey);AuthStore.remove(ctx,rangeKey);
            try{
                String next=queueExport(ctx,from,to);
                AuthStore.save(ctx,requestKey,next);AuthStore.save(ctx,rangeKey,range);
            }catch(Exception ignored){}
            return new SyncResult(data,"Completed");
        }
        if("Failed".equalsIgnoreCase(status)){
            AuthStore.remove(ctx,requestKey);AuthStore.remove(ctx,rangeKey);
            return new SyncResult(null,"Failed");
        }
        return new SyncResult(null,status);
    }

    private static RangeData parseCsv(String csv) throws Exception{
        String[] lines=csv.replace("\uFEFF","").split("\\r?\\n");
        if(lines.length==0||lines[0].trim().isEmpty())return new RangeData();
        List<String> header=parseCsvLine(lines[0]);
        HashMap<String,Integer> cols=new HashMap<>();
        for(int i=0;i<header.size();i++)cols.put(normalize(header.get(i)),i);
        int grossIdx=idx(cols,"transactionAmountUSD");
        int netIdx=idx(cols,"earningAmountUSD");
        if(grossIdx<0||netIdx<0)throw new IOException("Earnings CSV içinde transactionAmountUSD veya earningAmountUSD bulunamadı");
        int typeIdx=idx(cols,"transactionType");
        int txIdIdx=idx(cols,"transactionId");
        int earningIdIdx=idx(cols,"earningId");
        int orderIdIdx=idx(cols,"orderId");
        int txDateIdx=idx(cols,"transactionDate");

        RangeData out=new RangeData();
        HashSet<String> grossSeen=new HashSet<>();
        for(int row=1;row<lines.length;row++){
            if(lines[row].trim().isEmpty())continue;
            List<String> values=parseCsvLine(lines[row]);
            double gross=amount(get(values,grossIdx));
            double net=amount(get(values,netIdx));
            String type=get(values,typeIdx).trim().toLowerCase(Locale.US);
            String txId=get(values,txIdIdx).trim();
            String earningId=get(values,earningIdIdx).trim();
            String orderId=get(values,orderIdIdx).trim();
            String txDate=get(values,txDateIdx).trim();
            String stable=!txId.isEmpty()?txId:(!orderId.isEmpty()?orderId:(!earningId.isEmpty()?earningId:"row:"+Integer.toHexString(lines[row].hashCode())));
            String grossKey=stable+"|"+type+"|"+txDate;
            if(grossSeen.add(grossKey))out.grossUsd+=gross;
            out.netUsd+=net;

            boolean purchase="purchase".equals(type)||(type.isEmpty()&&gross>0);
            if(purchase){
                Purchase p=out.purchases.get(stable);
                if(p==null){p=new Purchase();p.gross=gross;out.purchases.put(stable,p);}
                p.net+=net;
            }
        }
        return out;
    }

    private static List<String> parseCsvLine(String line){
        ArrayList<String> out=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;
        for(int i=0;i<line.length();i++){
            char ch=line.charAt(i);
            if(ch=='\"'){
                if(quoted&&i+1<line.length()&&line.charAt(i+1)=='\"'){cell.append('\"');i++;}
                else quoted=!quoted;
            }else if(ch==','&&!quoted){out.add(cell.toString());cell.setLength(0);}
            else cell.append(ch);
        }
        out.add(cell.toString());return out;
    }

    private static String normalize(String s){return s==null?"":s.trim().toLowerCase(Locale.US).replaceAll("[^a-z0-9]","");}
    private static int idx(Map<String,Integer> cols,String name){Integer i=cols.get(normalize(name));return i==null?-1:i;}
    private static String get(List<String> values,int i){return i>=0&&i<values.size()?values.get(i):"";}

    private static double amount(String raw){
        if(raw==null)return 0;
        String s=raw.trim();if(s.isEmpty())return 0;
        boolean paren=s.startsWith("(")&&s.endsWith(")");
        s=s.replace(",","").replaceAll("[^0-9eE+\\-.]","");
        if(s.isEmpty()||"-".equals(s))return 0;
        try{double v=Double.parseDouble(s);return paren?-Math.abs(v):v;}catch(Exception ignored){return 0;}
    }

    private static void saveDouble(Context ctx,String key,double value) throws Exception{AuthStore.save(ctx,key,Double.toString(value));}
    private static Double loadDouble(Context ctx,String key){
        try{String s=AuthStore.load(ctx,key);return s==null?null:Double.parseDouble(s);}catch(Exception e){return null;}
    }

    private static PurchaseDelta detectNewPurchases(Context ctx,LocalDate date,RangeData data) throws Exception{
        PurchaseDelta delta=new PurchaseDelta();
        String savedDate=AuthStore.load(ctx,AuthStore.SEEN_PURCHASE_DATE);
        String savedIds=AuthStore.load(ctx,AuthStore.SEEN_PURCHASE_IDS);
        boolean firstEver=savedDate==null;
        LinkedHashSet<String> seen=new LinkedHashSet<>();
        if(date.toString().equals(savedDate)&&savedIds!=null){
            for(String id:savedIds.split("\\n"))if(!id.trim().isEmpty())seen.add(id.trim());
        }

        if(!firstEver){
            for(Map.Entry<String,Purchase> e:data.purchases.entrySet()){
                if(!seen.contains(e.getKey())){
                    delta.count++;
                    delta.gross+=e.getValue().gross;
                    delta.net+=e.getValue().net;
                }
            }
        }
        seen.addAll(data.purchases.keySet());
        StringBuilder packed=new StringBuilder();int n=0;
        for(String id:seen){if(n++>=1000)break;if(packed.length()>0)packed.append('\n');packed.append(id);}
        AuthStore.save(ctx,AuthStore.SEEN_PURCHASE_DATE,date.toString());
        AuthStore.save(ctx,AuthStore.SEEN_PURCHASE_IDS,packed.toString());
        return delta;
    }

    private static String money(Double value){return value==null?"--":String.format(Locale.US,"$%,.2f",value);}

    public static DashboardResult refreshDashboard(Context ctx) throws Exception{
        LocalDate now=LocalDate.now();
        SyncResult today=syncRange(ctx,now,now,AuthStore.TODAY_EXPORT_REQUEST,AuthStore.TODAY_EXPORT_RANGE);
        SyncResult month=syncRange(ctx,now.withDayOfMonth(1),now,AuthStore.MONTH_EXPORT_REQUEST,AuthStore.MONTH_EXPORT_RANGE);
        PurchaseDelta delta=new PurchaseDelta();

        if(today.data!=null){
            saveDouble(ctx,AuthStore.TODAY_GROSS,today.data.grossUsd);
            saveDouble(ctx,AuthStore.TODAY_NET,today.data.netUsd);
            delta=detectNewPurchases(ctx,now,today.data);
        }
        if(month.data!=null){
            saveDouble(ctx,AuthStore.MONTH_GROSS,month.data.grossUsd);
            saveDouble(ctx,AuthStore.MONTH_NET,month.data.netUsd);
        }

        String text="Bugün\nBrüt: "+money(loadDouble(ctx,AuthStore.TODAY_GROSS))+"\nNet: "+money(loadDouble(ctx,AuthStore.TODAY_NET))+
                "\n\nBu Ay\nBrüt: "+money(loadDouble(ctx,AuthStore.MONTH_GROSS))+"\nNet: "+money(loadDouble(ctx,AuthStore.MONTH_NET))+
                "\n\nSon kontrol: "+LocalTime.now().withNano(0);
        AuthStore.save(ctx,AuthStore.LAST_REPORT,text);
        return new DashboardResult(text,delta.count,delta.gross,delta.net);
    }
}
