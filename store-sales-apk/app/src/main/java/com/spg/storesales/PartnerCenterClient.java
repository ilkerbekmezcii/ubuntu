package com.spg.storesales;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

public final class PartnerCenterClient {
    private static final String DEV = "https://manage.devcenter.microsoft.com";
    private static final String BASE = DEV + "/v1.0/my/";
    private static final Object ACQ_LOCK = new Object();
    private static long lastAcq;
    private static String token;
    private static long tokenAt;
    private static String tokenKey;
    private final CredentialStore.Credentials c;

    public PartnerCenterClient(CredentialStore.Credentials c) { this.c = c; }

    public JSONArray applications() throws Exception {
        String t = token();
        JSONArray all = new JSONArray();
        String next = BASE + "applications?top=100";
        int guard = 0;
        while (next != null && guard++ < 100) {
            JSONObject j = get(next, t, false);
            JSONArray a = array(j);
            for (int i = 0; i < a.length(); i++) all.put(a.optJSONObject(i));
            String n = first(j.optString("@nextLink"), j.optString("nextLink"), j.optString("NextLink"));
            if (!n.isEmpty()) next = resolve(n);
            else {
                int total = j.optInt("totalCount", j.optInt("TotalCount", all.length()));
                next = total > all.length() ? BASE + "applications?skip=" + all.length() + "&top=100" : null;
            }
        }
        return all;
    }

    public Totals acquisitions(String appId, String start, String end, String isoToday) throws Exception {
        synchronized (ACQ_LOCK) {
            Totals z = new Totals();
            int skip = 0;
            String t = token();
            for (int page = 0; page < 100; page++) {
                String u = BASE + "analytics/appacquisitions?applicationId=" + enc(appId)
                        + "&startDate=" + enc(start) + "&endDate=" + enc(end)
                        + "&top=10000&skip=" + skip;
                JSONObject j = get(u, t, true);
                JSONArray a = array(j);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject r = a.optJSONObject(i);
                    if (r == null) continue;
                    double s = r.optDouble("purchasePriceUSDAmount", 0d);
                    long q = r.optLong("acquisitionQuantity", 0L);
                    z.sales += s;
                    z.acq += q;
                    if (isoToday.equals(r.optString("date"))) {
                        z.todaySales += s;
                        z.todayAcq += q;
                    }
                }
                if (a.length() < 10000) break;
                skip += 10000;
            }
            return z;
        }
    }

    private String token() throws Exception {
        String k = c.tenantId + "|" + c.clientId;
        synchronized (PartnerCenterClient.class) {
            if (token != null && k.equals(tokenKey) && System.currentTimeMillis() - tokenAt < 50L * 60L * 1000L) return token;
        }
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection x = null;
            try {
                x = (HttpURLConnection) new URL("https://login.microsoftonline.com/" + enc(c.tenantId) + "/oauth2/token").openConnection();
                x.setConnectTimeout(10000);
                x.setReadTimeout(15000);
                x.setRequestMethod("POST");
                x.setDoOutput(true);
                x.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                String b = "grant_type=client_credentials&client_id=" + enc(c.clientId)
                        + "&client_secret=" + enc(c.clientSecret)
                        + "&resource=" + enc(DEV);
                try (OutputStream o = x.getOutputStream()) { o.write(b.getBytes(StandardCharsets.UTF_8)); }
                int code = x.getResponseCode();
                String body = read(x, code);
                if (code < 200 || code >= 300) throw new ApiException(code, "Microsoft oturum açma reddedildi");
                String nt = new JSONObject(body).optString("access_token");
                if (nt.isEmpty()) throw new ApiException(code, "Microsoft token alınamadı");
                synchronized (PartnerCenterClient.class) {
                    token = nt;
                    tokenAt = System.currentTimeMillis();
                    tokenKey = k;
                }
                return nt;
            } catch (UnknownHostException | SocketTimeoutException e) {
                last = e;
                if (attempt < 2) Thread.sleep(1000L * (attempt + 1));
            } finally {
                if (x != null) x.disconnect();
            }
        }
        if (last instanceof UnknownHostException) throw new HostException("Microsoft adresi çözümlenemedi; DNS/bağlantı geçici olarak kullanılamıyor", last);
        throw new HostException("Microsoft bağlantısı zaman aşımına uğradı", last);
    }

    private JSONObject get(String url, String t, boolean acq) throws Exception {
        Exception lastNetwork = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            if (acq) waitAcq();
            HttpURLConnection x = null;
            try {
                x = (HttpURLConnection) new URL(url).openConnection();
                x.setConnectTimeout(10000);
                x.setReadTimeout(20000);
                x.setRequestProperty("Authorization", "Bearer " + t);
                x.setRequestProperty("Accept", "application/json");
                int code = x.getResponseCode();
                if (acq) lastAcq = System.currentTimeMillis();
                String body = read(x, code);
                if (code >= 200 && code < 300) return new JSONObject(body);
                if (code == 429 && attempt < 4) {
                    long w = 3500L;
                    try { w = Math.max(w, Long.parseLong(x.getHeaderField("Retry-After")) * 1000L + 250L); }
                    catch (Exception ignored) {}
                    Thread.sleep(w);
                    continue;
                }
                if (code == 401 || code == 403) throw new ApiException(code, "Partner Center yetkisi reddedildi");
                if (code >= 500 && attempt < 2) {
                    Thread.sleep(1500L * (attempt + 1));
                    continue;
                }
                throw new ApiException(code, "Partner Center HTTP " + code);
            } catch (UnknownHostException | SocketTimeoutException e) {
                lastNetwork = e;
                if (attempt < 2) {
                    Thread.sleep(1000L * (attempt + 1));
                    continue;
                }
                break;
            } finally {
                if (x != null) x.disconnect();
            }
        }
        if (lastNetwork instanceof UnknownHostException) throw new HostException("Microsoft host bağlantısı kurulamadı; ağ/DNS kontrol edilecek", lastNetwork);
        if (lastNetwork != null) throw new HostException("Microsoft bağlantısı zaman aşımına uğradı", lastNetwork);
        throw new ApiException(429, "Microsoft istek limiti aşıldı");
    }

    private static void waitAcq() throws InterruptedException {
        long elapsed = System.currentTimeMillis() - lastAcq;
        if (lastAcq > 0 && elapsed < 3300L) Thread.sleep(3300L - elapsed);
    }

    private static JSONArray array(JSONObject j) { JSONArray a = j.optJSONArray("value"); if (a == null) a = j.optJSONArray("Value"); return a == null ? new JSONArray() : a; }
    private static String resolve(String n) { if (n.startsWith("http")) return n; while (n.startsWith("/")) n = n.substring(1); if (n.startsWith("v1.0/my/")) return DEV + "/" + n; return BASE + n; }
    private static String first(String... values) { for (String s : values) if (s != null && !s.isEmpty()) return s; return ""; }
    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }
    private static String read(HttpURLConnection x, int code) throws Exception { InputStream in = code >= 200 && code < 400 ? x.getInputStream() : x.getErrorStream(); if (in == null) return ""; ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1) o.write(b, 0, n); return o.toString("UTF-8"); }

    public static final class Totals { public double sales, todaySales; public long acq, todayAcq; }
    public static final class ApiException extends Exception { public final int code; ApiException(int c, String m) { super(m); code = c; } }
    public static final class HostException extends Exception { HostException(String m, Throwable c) { super(m, c); } }
}
