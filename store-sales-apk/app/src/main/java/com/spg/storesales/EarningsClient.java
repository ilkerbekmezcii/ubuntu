package com.spg.storesales;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class EarningsClient {
    private static final String API="https://api.partner.microsoft.com/v1.0/payouts/transactionhistory";
    private static String token; private static long tokenAt; private static String tokenKey;
    private final CredentialStore.Credentials c;

    public EarningsClient(CredentialStore.Credentials c){this.c=c;}

    public Result query(String start,String end) throws Exception {
        String t=token();
        String filter="earningForDate ge "+start+" and earningForDate le "+end;
        String url=API+"?$filter="+URLEncoder.encode(filter,"UTF-8")+"&fileformat=csv";
        JSONObject created=request("POST",url,t);
        JSONObject item=first(created);
        String id=item.optString("requestId");
        if(id.isEmpty())throw new Exception("Earnings requestId alınamadı");
        String blob=item.optString("blobLocation");
        String status=item.optString("status");
        long deadline=System.currentTimeMillis()+45000L;
        while((blob==null||blob.isEmpty())&&!"Completed".equalsIgnoreCase(status)){
            if("Failed".equalsIgnoreCase(status))throw new Exception("Earnings raporu oluşturulamadı");
            if(System.currentTimeMillis()>=deadline)throw new Exception("Earnings raporu zaman aşımına uğradı");
            Thread.sleep(500L);
            JSONObject s=request("GET",API+"/"+URLEncoder.encode(id,"UTF-8"),t);
            item=first(s); status=item.optString("status"); blob=item.optString("blobLocation");
        }
        if(blob==null||blob.isEmpty())throw new Exception("Earnings blob alınamadı");
        return parseCsv(download(blob));
    }

    private String token() throws Exception {
        String k=c.tenantId+"|"+c.clientId;
        synchronized(EarningsClient.class){if(token!=null&&k.equals(tokenKey)&&System.currentTimeMillis()-tokenAt<50L*60L*1000L)return token;}
        HttpURLConnection x=(HttpURLConnection)new URL("https://login.microsoftonline.com/"+enc(c.tenantId)+"/oauth2/v2.0/token").openConnection();
        try{
            x.setConnectTimeout(10000);x.setReadTimeout(15000);x.setRequestMethod("POST");x.setDoOutput(true);x.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
            String b="grant_type=client_credentials&client_id="+enc(c.clientId)+"&client_secret="+enc(c.clientSecret)+"&scope="+enc("https://api.partner.microsoft.com/.default");
            try(OutputStream o=x.getOutputStream()){o.write(b.getBytes(StandardCharsets.UTF_8));}
            int code=x.getResponseCode();String body=read(x,code);if(code<200||code>=300)throw new Exception("Earnings oturum açma HTTP "+code);
            String nt=new JSONObject(body).optString("access_token");if(nt.isEmpty())throw new Exception("Earnings token alınamadı");
            synchronized(EarningsClient.class){token=nt;tokenAt=System.currentTimeMillis();tokenKey=k;}return nt;
        }finally{x.disconnect();}
    }

    private JSONObject request(String method,String url,String t) throws Exception {
        HttpURLConnection x=(HttpURLConnection)new URL(url).openConnection();
        try{
            x.setConnectTimeout(10000);x.setReadTimeout(20000);x.setRequestMethod(method);x.setRequestProperty("Authorization","Bearer "+t);x.setRequestProperty("Accept","application/json");
            x.setRequestProperty("ms-correlationid",UUID.randomUUID().toString());x.setRequestProperty("ms-requestid",UUID.randomUUID().toString());
            int code=x.getResponseCode();String body=read(x,code);if(code<200||code>=300)throw new Exception("Earnings HTTP "+code);return body.isEmpty()?new JSONObject():new JSONObject(body);
        }finally{x.disconnect();}
    }

    private static JSONObject first(JSONObject j){JSONArray a=j.optJSONArray("value");if(a==null)a=j.optJSONArray("Value");return a!=null&&a.length()>0&&a.optJSONObject(0)!=null?a.optJSONObject(0):j;}

    private static String download(String url) throws Exception {
        HttpURLConnection x=(HttpURLConnection)new URL(url).openConnection();
        try{x.setConnectTimeout(10000);x.setReadTimeout(30000);int c=x.getResponseCode();if(c<200||c>=300)throw new Exception("Earnings CSV HTTP "+c);return read(x,c);}finally{x.disconnect();}
    }

    private static Result parseCsv(String csv){
        List<List<String>> rows=csv(csv);Result out=new Result();if(rows.isEmpty())return out;
        List<String> h=rows.get(0);Map<String,Integer> ix=new LinkedHashMap<>();for(int i=0;i<h.size();i++)ix.put(norm(h.get(i)),i);
        for(int r=1;r<rows.size();r++){
            List<String> row=rows.get(r);String pid=get(row,ix,"productid");String name=get(row,ix,"productname");String date=get(row,ix,"earningdate");
            if(pid.isEmpty())pid=get(row,ix,"parentproductid");if(pid.isEmpty())pid=name;if(pid.isEmpty())continue;
            double earning=num(get(row,ix,"earningamountusd"));double gross=num(get(row,ix,"transactionamountusd"));long qty=Math.max(0L,Math.round(num(get(row,ix,"quantity"))));
            Product p=out.products.get(pid);if(p==null){p=new Product();p.id=pid;p.name=name.isEmpty()?pid:name;out.products.put(pid,p);}if(!name.isEmpty())p.name=name;
            p.net+=earning;p.gross+=gross;p.quantity+=qty;if(date!=null&&!date.isEmpty())p.lastDate=date;
        }
        return out;
    }

    private static String norm(String s){return s==null?"":s.replace("\uFEFF","").trim().toLowerCase(Locale.US).replace(" ","").replace("_","");}
    private static String get(List<String> r,Map<String,Integer> ix,String k){Integer i=ix.get(k);return i==null||i<0||i>=r.size()?"":r.get(i).trim();}
    private static double num(String s){try{return s==null||s.trim().isEmpty()?0d:Double.parseDouble(s.trim());}catch(Exception e){return 0d;}}

    private static List<List<String>> csv(String s){
        List<List<String>> out=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder f=new StringBuilder();boolean q=false;
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='\"'){if(q&&i+1<s.length()&&s.charAt(i+1)=='\"'){f.append('\"');i++;}else q=!q;}else if(c==','&&!q){row.add(f.toString());f.setLength(0);}else if((c=='\n'||c=='\r')&&!q){if(c=='\r'&&i+1<s.length()&&s.charAt(i+1)=='\n')i++;row.add(f.toString());f.setLength(0);if(!row.isEmpty())out.add(row);row=new ArrayList<>();}else f.append(c);}if(f.length()>0||!row.isEmpty()){row.add(f.toString());out.add(row);}return out;
    }

    private static String enc(String s)throws Exception{return URLEncoder.encode(s,"UTF-8");}
    private static String read(HttpURLConnection x,int code)throws Exception{InputStream in=code>=200&&code<400?x.getInputStream():x.getErrorStream();if(in==null)return "";ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)o.write(b,0,n);return o.toString("UTF-8");}

    public static final class Product{public String id,name,lastDate;public double gross,net;public long quantity;}
    public static final class Result{public final Map<String,Product> products=new LinkedHashMap<>();}
}
