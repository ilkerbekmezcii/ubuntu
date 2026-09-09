package com.spg.storesales;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
    private static final int REQ_ENV = 4101;
    private static final int REQ_NOTIFICATIONS = 4102;
    private static final String PREFS = SalesMonitorService.PREFS;
    private static final String PREF_ENV = SalesMonitorService.PREF_ENV;
    private static final String PREF_MONITOR = SalesMonitorService.PREF_MONITOR;
    private static final String KEY_ALIAS = "partner_center_credentials";
    private static final long ACQ_INTERVAL_MS = 3300L;
    private static final String DEV_CENTER = "https://manage.devcenter.microsoft.com";
    private static final String MY_BASE = DEV_CENTER + "/v1.0/my/";

    private TextView dailyValue;
    private TextView monthlyValue;
    private TextView dailyMeta;
    private TextView monthlyMeta;
    private TextView leaderValue;
    private TextView leaderMeta;
    private TextView status;
    private Button importButton;
    private Button refreshButton;
    private Button monitorButton;
    private SharedPreferences prefs;
    private volatile long lastAcquisitionRequestAt = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        syncMonitorButton();

        if (prefs.getBoolean(PREF_MONITOR, false) && prefs.contains(PREF_ENV)) startMonitorService();

        if (prefs.contains(PREF_ENV)) refreshReport();
        else {
            status.setText("İlk kullanım: Drive’daki microsoft.env dosyasını seçin.");
            refreshButton.setEnabled(false);
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor("#F5F7FA"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        root.addView(text("Microsoft Store Sales", 26, true, "#111827"));
        TextView subtitle = text("Partner Center • Gross sales USD", 14, false, "#6B7280");
        subtitle.setPadding(0, dp(4), 0, dp(20));
        root.addView(subtitle);

        LinearLayout dailyCard = card();
        dailyCard.addView(text("BUGÜN", 12, true, "#6B7280"));
        dailyValue = text("—", 34, true, "#111827");
        dailyValue.setPadding(0, dp(6), 0, dp(4));
        dailyCard.addView(dailyValue);
        dailyMeta = text("Günlük acquisition: —", 14, false, "#4B5563");
        dailyCard.addView(dailyMeta);
        root.addView(dailyCard);

        LinearLayout monthlyCard = card();
        monthlyCard.addView(text("BU AY", 12, true, "#6B7280"));
        monthlyValue = text("—", 34, true, "#111827");
        monthlyValue.setPadding(0, dp(6), 0, dp(4));
        monthlyCard.addView(monthlyValue);
        monthlyMeta = text("Aylık acquisition: —", 14, false, "#4B5563");
        monthlyCard.addView(monthlyMeta);
        root.addView(monthlyCard);

        LinearLayout leaderCard = card();
        leaderCard.addView(text("LİDER UYGULAMA", 12, true, "#6B7280"));
        leaderValue = text("—", 20, true, "#111827");
        leaderValue.setPadding(0, dp(7), 0, dp(4));
        leaderCard.addView(leaderValue);
        leaderMeta = text("ID: —", 13, false, "#4B5563");
        leaderCard.addView(leaderMeta);
        root.addView(leaderCard);

        status = text("Hazır", 13, false, "#6B7280");
        status.setPadding(dp(4), dp(8), dp(4), dp(14));
        root.addView(status);

        refreshButton = button("Raporu Yenile");
        refreshButton.setOnClickListener(v -> refreshReport());
        root.addView(refreshButton, buttonParams());

        monitorButton = button("5 dk satış bildirimi");
        monitorButton.setOnClickListener(v -> toggleMonitor());
        LinearLayout.LayoutParams monitorParams = buttonParams();
        monitorParams.topMargin = dp(10);
        root.addView(monitorButton, monitorParams);

        importButton = button("microsoft.env Seç / Değiştir");
        importButton.setOnClickListener(v -> chooseEnvFile());
        LinearLayout.LayoutParams importParams = buttonParams();
        importParams.topMargin = dp(10);
        root.addView(importButton, importParams);

        TextView note = text("Kimlik bilgileri APK içinde bulunmaz. Seçtiğiniz env dosyası Android Keystore ile bu cihazda şifrelenir. Tüm Partner Center uygulama sayfaları taranır. 5 dakikalık takip açıkken kalıcı bir sistem bildirimi görünür; yeni gross satış geldiğinde ayrıca bildirim gönderilir.", 12, false, "#6B7280");
        note.setPadding(dp(2), dp(18), dp(2), 0);
        root.addView(note);
        return scroll;
    }

    private void toggleMonitor() {
        boolean enabled = prefs.getBoolean(PREF_MONITOR, false);
        if (enabled) {
            prefs.edit().putBoolean(PREF_MONITOR, false).apply();
            stopService(new Intent(this, SalesMonitorService.class));
            syncMonitorButton();
            Toast.makeText(this, "5 dakikalık satış takibi kapatıldı.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!prefs.contains(PREF_ENV)) {
            Toast.makeText(this, "Önce microsoft.env dosyasını seçin.", Toast.LENGTH_LONG).show();
            chooseEnvFile();
            return;
        }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
        prefs.edit().putBoolean(PREF_MONITOR, true).apply();
        startMonitorService();
        syncMonitorButton();
        Toast.makeText(this, "Takip açıldı. İlk kontrol başlangıç değeri olarak kaydedilir.", Toast.LENGTH_LONG).show();
    }

    private void startMonitorService() {
        Intent service = new Intent(this, SalesMonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
    }

    private void syncMonitorButton() {
        if (monitorButton == null) return;
        boolean enabled = prefs.getBoolean(PREF_MONITOR, false);
        monitorButton.setText(enabled ? "5 dk satış bildirimi: AÇIK" : "5 dk satış bildirimi: KAPALI");
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(17), dp(18), dp(17));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.parseColor("#E5E7EB"));
        card.setBackground(bg);
        card.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView text(String value, int sp, boolean bold, String color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(Color.parseColor(color));
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#2563EB"));
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(52));
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() { return new LinearLayout.LayoutParams(-1, dp(54)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void chooseEnvFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_ENV);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ENV || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) {}

        try (InputStream in = getContentResolver().openInputStream(uri)) {
            String env = readAll(in);
            Map<String, String> parsed = parseEnv(env);
            if (!parsed.containsKey("PARTNER_CENTER_CLIENT_ID") ||
                    !parsed.containsKey("PARTNER_CENTER_CLIENT_SECRET") ||
                    !parsed.containsKey("PARTNER_CENTER_TENANT_ID")) {
                throw new IllegalArgumentException("Gerekli Partner Center alanları bulunamadı.");
            }
            prefs.edit().putString(PREF_ENV, encrypt(env)).apply();
            refreshButton.setEnabled(true);
            Toast.makeText(this, "Kimlik bilgileri güvenli şekilde kaydedildi.", Toast.LENGTH_SHORT).show();
            if (prefs.getBoolean(PREF_MONITOR, false)) startMonitorService();
            refreshReport();
        } catch (Exception e) {
            Toast.makeText(this, "Dosya okunamadı: " + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshReport() {
        if (!prefs.contains(PREF_ENV)) {
            chooseEnvFile();
            return;
        }
        setBusy(true, "Partner Center’a bağlanılıyor…");
        new Thread(() -> {
            try {
                String env = decrypt(prefs.getString(PREF_ENV, ""));
                Report report = fetchReport(parseEnv(env));
                runOnUiThread(() -> showReport(report));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false, "Hata: " + safeMessage(e));
                    Toast.makeText(this, "Rapor alınamadı.", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private Report fetchReport(Map<String, String> creds) throws Exception {
        String clientId = require(creds, "PARTNER_CENTER_CLIENT_ID");
        String clientSecret = require(creds, "PARTNER_CENTER_CLIENT_SECRET");
        String tenantId = require(creds, "PARTNER_CENTER_TENANT_ID");
        String token = getToken(tenantId, clientId, clientSecret);

        JSONArray apps = fetchAllApplications(token);
        final int appCount = apps.length();
        runOnUiThread(() -> status.setText(appCount + " uygulama bulundu. Satışlar taranıyor…"));

        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        String startDate = month + "/1/" + year;
        String endDate = month + "/" + day + "/" + year;
        String isoToday = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

        double monthlySales = 0d, dailySales = 0d;
        long monthlyAcq = 0L, dailyAcq = 0L;
        String monthlyLeaderName = "—", monthlyLeaderId = "—";
        String dailyLeaderName = "—", dailyLeaderId = "—";
        long monthlyLeaderAcq = -1L, dailyLeaderAcq = -1L;

        for (int i = 0; i < appCount; i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null) continue;
            String appId = firstNonEmpty(app.optString("id", ""), app.optString("applicationId", ""));
            if (appId.isEmpty()) continue;
            String appName = firstNonEmpty(app.optString("primaryName", ""), app.optString("name", ""), app.optString("applicationName", ""), appId);

            final int index = i + 1;
            runOnUiThread(() -> status.setText("Uygulamalar taranıyor: " + index + "/" + appCount));

            double appSales = 0d, appDailySales = 0d;
            long appAcq = 0L, appDailyAcq = 0L;
            int skip = 0;

            for (int page = 0; page < 1000; page++) {
                String url = MY_BASE + "analytics/appacquisitions" +
                        "?applicationId=" + enc(appId) +
                        "&startDate=" + enc(startDate) +
                        "&endDate=" + enc(endDate) +
                        "&top=10000&skip=" + skip;
                JSONObject salesJson = getJson(url, token, true);
                JSONArray values = arrayFrom(salesJson);

                for (int j = 0; j < values.length(); j++) {
                    JSONObject row = values.optJSONObject(j);
                    if (row == null) continue;
                    double price = row.optDouble("purchasePriceUSDAmount", 0d);
                    long qty = row.optLong("acquisitionQuantity", 0L);
                    appSales += price;
                    appAcq += qty;
                    if (isoToday.equals(row.optString("date", ""))) {
                        appDailySales += price;
                        appDailyAcq += qty;
                    }
                }
                if (values.length() < 10000) break;
                skip += 10000;
            }

            monthlySales += appSales;
            dailySales += appDailySales;
            monthlyAcq += appAcq;
            dailyAcq += appDailyAcq;
            if (appAcq > monthlyLeaderAcq) {
                monthlyLeaderAcq = appAcq;
                monthlyLeaderId = appId;
                monthlyLeaderName = appName;
            }
            if (appDailyAcq > dailyLeaderAcq) {
                dailyLeaderAcq = appDailyAcq;
                dailyLeaderId = appId;
                dailyLeaderName = appName;
            }
        }

        Report r = new Report();
        r.monthlySales = monthlySales;
        r.dailySales = dailySales;
        r.monthlyAcquisitions = monthlyAcq;
        r.dailyAcquisitions = dailyAcq;
        r.monthlyLeaderName = monthlyLeaderName;
        r.monthlyLeaderId = monthlyLeaderId;
        r.monthlyLeaderAcquisitions = Math.max(0L, monthlyLeaderAcq);
        r.dailyLeaderName = dailyLeaderName;
        r.dailyLeaderId = dailyLeaderId;
        r.dailyLeaderAcquisitions = Math.max(0L, dailyLeaderAcq);
        r.appCount = appCount;
        r.generatedAt = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date());
        return r;
    }

    private JSONArray fetchAllApplications(String token) throws Exception {
        JSONArray all = new JSONArray();
        Set<String> seenIds = new HashSet<>();
        String nextUrl = MY_BASE + "applications?top=100";
        int safety = 0;

        while (nextUrl != null && !nextUrl.isEmpty() && safety++ < 1000) {
            JSONObject page = getJson(nextUrl, token, false);
            JSONArray values = arrayFrom(page);
            for (int i = 0; i < values.length(); i++) {
                JSONObject app = values.optJSONObject(i);
                if (app == null) continue;
                String id = firstNonEmpty(app.optString("id", ""), app.optString("applicationId", ""));
                if (id.isEmpty() || seenIds.add(id)) all.put(app);
            }

            String next = firstNonEmpty(page.optString("@nextLink", ""), page.optString("nextLink", ""), page.optString("NextLink", ""));
            if (!next.isEmpty()) {
                nextUrl = resolveMyUrl(next);
                continue;
            }

            int total = page.optInt("totalCount", page.optInt("TotalCount", all.length()));
            if (total > all.length()) nextUrl = MY_BASE + "applications?skip=" + all.length() + "&top=100";
            else nextUrl = null;
        }

        if (safety >= 1000) throw new Exception("Uygulama sayfalama güvenlik sınırı aşıldı");
        return all;
    }

    private JSONArray arrayFrom(JSONObject json) {
        JSONArray arr = json.optJSONArray("value");
        if (arr == null) arr = json.optJSONArray("Value");
        return arr == null ? new JSONArray() : arr;
    }

    private String resolveMyUrl(String next) {
        String n = next.trim();
        if (n.startsWith("http://") || n.startsWith("https://")) return n;
        while (n.startsWith("/")) n = n.substring(1);
        if (n.startsWith("v1.0/my/")) return DEV_CENTER + "/" + n;
        if (n.startsWith("my/")) return DEV_CENTER + "/v1.0/" + n;
        return MY_BASE + n;
    }

    private String getToken(String tenantId, String clientId, String clientSecret) throws Exception {
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
        try (OutputStream out = conn.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        int code = conn.getResponseCode();
        String response = readResponse(conn, code);
        conn.disconnect();
        if (code < 200 || code >= 300) throw new Exception("OAuth HTTP " + code);
        String token = new JSONObject(response).optString("access_token", "");
        if (token.isEmpty()) throw new Exception("OAuth token alınamadı");
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

    private String encrypt(String plaintext) throws Exception {
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

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

    private void showReport(Report r) {
        dailyValue.setText(String.format(Locale.US, "$%.2f", r.dailySales));
        monthlyValue.setText(String.format(Locale.US, "$%.2f", r.monthlySales));
        dailyMeta.setText("Günlük acquisition: " + r.dailyAcquisitions + " • Lider: " + r.dailyLeaderName + " (" + r.dailyLeaderAcquisitions + ")");
        monthlyMeta.setText("Aylık acquisition: " + r.monthlyAcquisitions + " • " + r.appCount + " uygulama");
        leaderValue.setText(r.monthlyLeaderName + " • " + r.monthlyLeaderAcquisitions + " acquisition");
        leaderMeta.setText("ID: " + r.monthlyLeaderId + "\nBugünün lideri: " + r.dailyLeaderName + " • " + r.dailyLeaderId);
        setBusy(false, "Son güncelleme: " + r.generatedAt + " • " + r.appCount + " uygulama tarandı");
    }

    private void setBusy(boolean busy, String message) {
        refreshButton.setEnabled(!busy && prefs.contains(PREF_ENV));
        importButton.setEnabled(!busy);
        monitorButton.setEnabled(!busy || prefs.getBoolean(PREF_MONITOR, false));
        status.setText(message);
    }

    private String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    private String safeMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.getClass().getSimpleName();
        m = m.replaceAll("(?i)(secret|token|password)=[^&\\s]+", "$1=***");
        return m.length() > 180 ? m.substring(0, 180) : m;
    }

    private static class Report {
        double monthlySales, dailySales;
        long monthlyAcquisitions, dailyAcquisitions;
        String monthlyLeaderName, monthlyLeaderId, dailyLeaderName, dailyLeaderId;
        long monthlyLeaderAcquisitions, dailyLeaderAcquisitions;
        int appCount;
        String generatedAt;
    }
}
