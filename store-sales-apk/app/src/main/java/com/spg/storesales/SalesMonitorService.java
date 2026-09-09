package com.spg.storesales;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class SalesMonitorService extends Service {
    public static final String PREFS = "store_sales_secure";
    public static final String PREF_ENV = "encrypted_env";
    public static final String PREF_MONITOR = "monitor_enabled";

    private static final String PREF_APPS_JSON = "monitor_apps_json";
    private static final String PREF_APPS_TS = "monitor_apps_ts";
    private static final String PREF_BASELINE_DAY = "monitor_baseline_day";
    private static final String PREF_BASELINE_APPS = "monitor_baseline_apps";
    private static final String KEY_ALIAS = "partner_center_credentials";
    private static final String DEV_CENTER = "https://manage.devcenter.microsoft.com";
    private static final String MY_BASE = DEV_CENTER + "/v1.0/my/";
    private static final long CHECK_INTERVAL_MS = 5L * 60L * 1000L;
    private static final long APP_CACHE_MS = 60L * 60L * 1000L;
    private static final long ACQ_INTERVAL_MS = 3300L;
    private static final int ONGOING_ID = 7201;
    private static final int SALE_ID = 7202;
    private static final String CHANNEL_MONITOR = "sales_monitor";
    private static final String CHANNEL_SALES = "new_sales";

    private SharedPreferences prefs;
    private Thread worker;
    private volatile boolean stopping;
    private volatile long lastAcquisitionRequestAt;
    private String cachedToken;
    private long cachedTokenAt;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        createChannels();
        startForeground(ONGOING_ID, ongoingNotification("5 dakikalık satış takibi başlatıldı"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!prefs.getBoolean(PREF_MONITOR, false) || !prefs.contains(PREF_ENV)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            stopping = false;
            worker = new Thread(this::monitorLoop, "store-sales-monitor");
            worker.start();
        }
        return START_STICKY;
    }

    private void monitorLoop() {
        long next = System.currentTimeMillis();
        while (!stopping && prefs.getBoolean(PREF_MONITOR, false)) {
            try {
                CheckResult result = checkPurchases();
                updateOngoing(String.format(Locale.US, "Son kontrol %s • Bugün $%.2f • %d uygulama", result.checkedAt, result.todaySales, result.appCount));
            } catch (Exception e) {
                updateOngoing("Takip açık • Son kontrol hatası: " + safeMessage(e));
            }

            next += CHECK_INTERVAL_MS;
            long sleep = Math.max(5000L, next - System.currentTimeMillis());
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                break;
            }
            if (System.currentTimeMillis() - next > CHECK_INTERVAL_MS) next = System.currentTimeMillis();
        }
        stopSelf();
    }

    private CheckResult checkPurchases() throws Exception {
        String env = decrypt(prefs.getString(PREF_ENV, ""));
        Map<String, String> creds = parseEnv(env);
        String token = getTokenCached(
                require(creds, "PARTNER_CENTER_TENANT_ID"),
                require(creds, "PARTNER_CENTER_CLIENT_ID"),
                require(creds, "PARTNER_CENTER_CLIENT_SECRET"));

        JSONArray apps = getApplications(token);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String apiDate = new SimpleDateFormat("M/d/yyyy", Locale.US).format(new Date());
        JSONObject current = new JSONObject();
        JSONObject names = new JSONObject();
        double todaySales = 0d;

        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null) continue;
            String id = firstNonEmpty(app.optString("id", ""), app.optString("applicationId", ""));
            if (id.isEmpty()) continue;
            String name = firstNonEmpty(app.optString("primaryName", ""), app.optString("name", ""), app.optString("applicationName", ""), id);
            names.put(id, name);

            double appSales = 0d;
            int skip = 0;
            for (int page = 0; page < 100; page++) {
                String url = MY_BASE + "analytics/appacquisitions" +
                        "?applicationId=" + enc(id) +
                        "&startDate=" + enc(apiDate) +
                        "&endDate=" + enc(apiDate) +
                        "&top=10000&skip=" + skip;
                JSONObject data = getJson(url, token, true);
                JSONArray rows = arrayFrom(data);
                for (int r = 0; r < rows.length(); r++) {
                    JSONObject row = rows.optJSONObject(r);
                    if (row != null) appSales += row.optDouble("purchasePriceUSDAmount", 0d);
                }
                if (rows.length() < 10000) break;
                skip += 10000;
            }
            appSales = round6(appSales);
            current.put(id, appSales);
            todaySales += appSales;
        }
        todaySales = round6(todaySales);

        String previousDay = prefs.getString(PREF_BASELINE_DAY, "");
        JSONObject previous = new JSONObject(prefs.getString(PREF_BASELINE_APPS, "{}"));
        if (today.equals(previousDay)) {
            double deltaTotal = 0d;
            String topName = null;
            double topDelta = 0d;
            int changedApps = 0;
            for (java.util.Iterator<String> it = current.keys(); it.hasNext();) {
                String id = it.next();
                double now = current.optDouble(id, 0d);
                double before = previous.optDouble(id, 0d);
                double delta = round6(now - before);
                if (delta > 0.000001d) {
                    deltaTotal += delta;
                    changedApps++;
                    if (delta > topDelta) {
                        topDelta = delta;
                        topName = names.optString(id, id);
                    }
                }
            }
            deltaTotal = round6(deltaTotal);
            if (deltaTotal > 0.000001d) notifySale(deltaTotal, todaySales, changedApps, topName, topDelta);
        }

        prefs.edit()
                .putString(PREF_BASELINE_DAY, today)
                .putString(PREF_BASELINE_APPS, current.toString())
                .apply();

        CheckResult result = new CheckResult();
        result.todaySales = todaySales;
        result.appCount = apps.length();
        result.checkedAt = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        return result;
    }

    private JSONArray getApplications(String token) throws Exception {
        long ts = prefs.getLong(PREF_APPS_TS, 0L);
        String cached = prefs.getString(PREF_APPS_JSON, "");
        if (!cached.isEmpty() && System.currentTimeMillis() - ts < APP_CACHE_MS) {
            return new JSONArray(cached);
        }

        JSONArray all = new JSONArray();
        String nextUrl = MY_BASE + "applications?top=100";
        int safety = 0;
        while (nextUrl != null && !nextUrl.isEmpty() && safety++ < 1000) {
            JSONObject page = getJson(nextUrl, token, false);
            JSONArray values = arrayFrom(page);
            for (int i = 0; i < values.length(); i++) {
                JSONObject app = values.optJSONObject(i);
                if (app != null) all.put(app);
            }
            String next = firstNonEmpty(page.optString("@nextLink", ""), page.optString("nextLink", ""), page.optString("NextLink", ""));
            if (!next.isEmpty()) nextUrl = resolveMyUrl(next);
            else {
                int total = page.optInt("totalCount", page.optInt("TotalCount", all.length()));
                nextUrl = total > all.length() ? MY_BASE + "applications?skip=" + all.length() + "&top=100" : null;
            }
        }
        prefs.edit().putString(PREF_APPS_JSON, all.toString()).putLong(PREF_APPS_TS, System.currentTimeMillis()).apply();
        return all;
    }

    private String getTokenCached(String tenantId, String clientId, String clientSecret) throws Exception {
        if (cachedToken != null && System.currentTimeMillis() - cachedTokenAt < 50L * 60L * 1000L) return cachedToken;
        HttpURLConnection conn = (HttpURLConnection) new URL("https://login.microsoftonline.com/" + enc(tenantId) + "/oauth2/token").openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");
        String body = "grant_type=client_credentials" +
                "&client_id=" + enc(clientId) +
                "&client_secret=" + enc(clientSecret) +
                "&resource=" + enc(DEV_CENTER);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String response = readResponse(conn, code);
        conn.disconnect();
        if (code < 200 || code >= 300) throw new Exception("OAuth HTTP " + code);
        String token = new JSONObject(response).optString("access_token", "");
        if (token.isEmpty()) throw new Exception("OAuth token alınamadı");
        cachedToken = token;
        cachedTokenAt = System.currentTimeMillis();
        return token;
    }

    private JSONObject getJson(String url, String token, boolean acquisitionRequest) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (acquisitionRequest) waitForRateLimit();
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (acquisitionRequest) lastAcquisitionRequestAt = System.currentTimeMillis();
            String body = readResponse(conn, code);
            String retryAfter = conn.getHeaderField("Retry-After");
            conn.disconnect();
            if (code >= 200 && code < 300) return new JSONObject(body);
            if (code == 429 && attempt < 4) {
                long wait = 3500L;
                try { wait = Math.max(wait, Long.parseLong(retryAfter) * 1000L + 300L); }
                catch (Exception ignored) {}
                Thread.sleep(wait);
                continue;
            }
            throw new Exception("Partner Center HTTP " + code);
        }
        throw new Exception("Partner Center retry limiti aşıldı");
    }

    private void waitForRateLimit() throws InterruptedException {
        long elapsed = System.currentTimeMillis() - lastAcquisitionRequestAt;
        if (lastAcquisitionRequestAt > 0 && elapsed < ACQ_INTERVAL_MS) Thread.sleep(ACQ_INTERVAL_MS - elapsed);
    }

    private void notifySale(double delta, double todayTotal, int changedApps, String topName, double topDelta) {
        String title = "Yeni Microsoft Store satışı";
        String content;
        if (changedApps == 1 && topName != null) {
            content = String.format(Locale.US, "%s +$%.2f • Bugün $%.2f", topName, topDelta, todayTotal);
        } else {
            content = String.format(Locale.US, "%d uygulamada +$%.2f • Bugün $%.2f", changedApps, delta, todayTotal);
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(SALE_ID, buildNotification(CHANNEL_SALES, title, content, false));
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel monitor = new NotificationChannel(CHANNEL_MONITOR, "Satış takibi", NotificationManager.IMPORTANCE_LOW);
            monitor.setDescription("5 dakikalık Microsoft Store satış takibi");
            nm.createNotificationChannel(monitor);
            NotificationChannel sales = new NotificationChannel(CHANNEL_SALES, "Yeni satışlar", NotificationManager.IMPORTANCE_HIGH);
            sales.setDescription("Yeni Microsoft Store satın alma bildirimleri");
            nm.createNotificationChannel(sales);
        }
    }

    private Notification ongoingNotification(String content) {
        return buildNotification(CHANNEL_MONITOR, "Microsoft Store satış takibi açık", content, true);
    }

    private Notification buildNotification(String channel, String title, String content, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setShowWhen(true);
        return b.build();
    }

    private void updateOngoing(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(ONGOING_ID, ongoingNotification(text));
    }

    @Override
    public void onDestroy() {
        stopping = true;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private String resolveMyUrl(String next) {
        String n = next.trim();
        if (n.startsWith("http://") || n.startsWith("https://")) return n;
        while (n.startsWith("/")) n = n.substring(1);
        if (n.startsWith("v1.0/my/")) return DEV_CENTER + "/" + n;
        if (n.startsWith("my/")) return DEV_CENTER + "/v1.0/" + n;
        return MY_BASE + n;
    }

    private JSONArray arrayFrom(JSONObject json) {
        JSONArray arr = json.optJSONArray("value");
        if (arr == null) arr = json.optJSONArray("Value");
        return arr == null ? new JSONArray() : arr;
    }

    private String readResponse(HttpURLConnection conn, int code) throws Exception {
        InputStream in = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        return readAll(in);
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private Map<String, String> parseEnv(String env) {
        Map<String, String> map = new HashMap<>();
        for (String line : env.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            String key = s.substring(0, eq).trim();
            String value = s.substring(eq + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) value = value.substring(1, value.length() - 1);
            map.put(key, value);
        }
        return map;
    }

    private String require(Map<String, String> map, String key) throws Exception {
        String value = map.get(key);
        if (value == null || value.trim().isEmpty()) throw new Exception(key + " eksik");
        return value.trim();
    }

    private String enc(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }

    private String decrypt(String stored) throws Exception {
        String[] parts = stored.split(":", 2);
        if (parts.length != 2) throw new Exception("Kayıtlı kimlik bilgisi bozuk");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
    }

    private String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    private String safeMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.getClass().getSimpleName();
        m = m.replaceAll("(?i)(secret|token|password)=[^&\\s]+", "$1=***");
        return m.length() > 100 ? m.substring(0, 100) : m;
    }

    private double round6(double v) { return Math.round(v * 1000000d) / 1000000d; }

    private static class CheckResult {
        double todaySales;
        int appCount;
        String checkedAt;
    }
}
