package com.ilker.microsoftreport;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_ENV = 5201;
    private static final int REQUEST_NOTIFY = 5202;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView statusText, configText, todayQty, todayGross, todayTax, todayNet, monthGross, monthNet, lastCheck;
    private LinearLayout salesList;
    private ProgressBar progress;
    private Button selectEnv, refresh;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean error = intent.getBooleanExtra(PollService.EXTRA_ERROR, false);
            String status = intent.getStringExtra(PollService.EXTRA_STATUS);
            setStatus(status == null ? "" : status, error);
            renderDashboard();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestNotificationsIfNeeded();
        renderConfigState();
        renderDashboard();
        if (new AuthStore(this).hasConfig()) PollService.ensureRunning(this);
    }

    @Override protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(PollService.ACTION_STATUS);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(receiver, filter);
            receiverRegistered = true;
        }
        renderDashboard();
    }

    @Override protected void onStop() {
        if (receiverRegistered) { unregisterReceiver(receiver); receiverRegistered = false; }
        super.onStop();
    }

    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 247, 250));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("Microsoft Store Sales 5M", 26, Color.rgb(25, 33, 43), Typeface.BOLD));
        TextView subtitle = text("Türkiye saati • USD • 5 dakikada bir otomatik kontrol", 13, Color.rgb(88, 98, 112), Typeface.NORMAL);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        LinearLayout configBox = box();
        configBox.addView(text("MICROSOFT BAĞLANTISI", 12, Color.rgb(73, 83, 98), Typeface.BOLD));
        configText = text("ENV bekleniyor", 14, Color.rgb(25, 33, 43), Typeface.NORMAL);
        configText.setPadding(0, dp(8), 0, dp(10));
        configBox.addView(configText);
        selectEnv = button("ENV / TXT dosyası seç");
        selectEnv.setOnClickListener(v -> selectEnvFile());
        configBox.addView(selectEnv);
        root.addView(configBox);

        LinearLayout statusBox = box();
        statusText = text("Durum: hazırlanıyor", 14, Color.rgb(25, 33, 43), Typeface.NORMAL);
        statusBox.addView(statusText);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        pp.topMargin = dp(10);
        statusBox.addView(progress, pp);
        root.addView(statusBox);

        LinearLayout today = box();
        today.addView(text("BUGÜN", 12, Color.rgb(73, 83, 98), Typeface.BOLD));
        todayQty = metric(today, "Satılan", "0");
        todayGross = metric(today, "Brüt", "$0.00");
        todayTax = metric(today, "Vergi", "$0.00");
        todayNet = metric(today, "Net", "$0.00");
        lastCheck = metric(today, "Son kontrol", "--");
        root.addView(today);

        LinearLayout month = box();
        month.addView(text("BU AY", 12, Color.rgb(73, 83, 98), Typeface.BOLD));
        monthGross = metric(month, "Toplam brüt", "$0.00");
        monthNet = metric(month, "Toplam net", "$0.00");
        root.addView(month);

        refresh = button("Şimdi kontrol et");
        refresh.setOnClickListener(v -> {
            if (new AuthStore(this).hasConfig()) {
                setBusy(true, "Microsoft Store kontrol ediliyor");
                PollService.requestImmediate(this);
            } else setStatus("Önce ENV dosyasını seçin", true);
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMargins(0, dp(2), 0, dp(14));
        root.addView(refresh, rp);

        TextView listTitle = text("BUGÜN SATILAN UYGULAMALAR", 12, Color.rgb(73, 83, 98), Typeface.BOLD);
        listTitle.setPadding(dp(2), dp(6), 0, dp(8));
        root.addView(listTitle);
        salesList = new LinearLayout(this);
        salesList.setOrientation(LinearLayout.VERTICAL);
        root.addView(salesList);

        TextView note = text("Net = Brüt − Microsoft Store acquisition verisindeki vergi.", 12, Color.rgb(99, 108, 120), Typeface.NORMAL);
        note.setPadding(dp(4), dp(12), dp(4), 0);
        root.addView(note);
        setContentView(scroll);
    }

    private TextView metric(LinearLayout parent, String label, String initial) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        TextView l = text(label, 14, Color.rgb(75, 84, 96), Typeface.NORMAL);
        TextView v = text(initial, 16, Color.rgb(20, 28, 38), Typeface.BOLD);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(v);
        parent.addView(row);
        return v;
    }

    private void renderConfigState() {
        boolean ready = new AuthStore(this).hasConfig();
        configText.setText(ready ? "ENV yüklü • Microsoft erişimi hazır"
                : "PARTNER_CENTER_TENANT_ID / CLIENT_ID / CLIENT_SECRET içeren .env seçin");
        refresh.setEnabled(ready);
        if (!ready) setStatus("Microsoft erişim dosyası bekleniyor", false);
    }

    private void renderDashboard() {
        Models.Dashboard d = new StateStore(this).loadDashboard();
        salesList.removeAllViews();
        if (d == null) {
            todayQty.setText("0"); todayGross.setText("$0.00"); todayTax.setText("$0.00"); todayNet.setText("$0.00");
            monthGross.setText("$0.00"); monthNet.setText("$0.00"); lastCheck.setText("--");
            TextView empty = text("Henüz veri yok", 14, Color.rgb(105, 113, 123), Typeface.NORMAL);
            empty.setPadding(dp(4), dp(12), 0, dp(12)); salesList.addView(empty); return;
        }
        todayQty.setText(String.valueOf(d.totalQuantity()));
        todayGross.setText(money(d.totalGross()));
        todayTax.setText(money(d.totalTax()));
        todayNet.setText(money(d.totalNet()));
        monthGross.setText(money(d.monthGrossUsd));
        monthNet.setText(money(d.monthNetUsd()));
        lastCheck.setText(formatTurkeyTime(d.checkedAt));

        if (d.sales.isEmpty()) {
            TextView empty = text("Bugün ücretli uygulama satışı yok", 14, Color.rgb(105, 113, 123), Typeface.NORMAL);
            empty.setPadding(dp(4), dp(12), 0, dp(12)); salesList.addView(empty);
        } else {
            for (Models.AppSale sale : d.sales) addSaleRow(sale);
        }
    }

    private void addSaleRow(Models.AppSale sale) {
        LinearLayout card = box();
        card.addView(text(sale.appName, 17, Color.rgb(20, 28, 38), Typeface.BOLD));
        TextView details = text(sale.quantity + " adet\nBrüt " + money(sale.grossUsd)
                        + "   Vergi " + money(sale.taxUsd) + "   Net " + money(sale.netUsd()),
                14, Color.rgb(55, 65, 78), Typeface.NORMAL);
        details.setPadding(0, dp(7), 0, 0);
        card.addView(details);
        salesList.addView(card);
    }

    private void selectEnvFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQUEST_ENV);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ENV || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = displayName(uri).toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".env") || name.endsWith(".txt"))) { setStatus("Yalnızca .env veya .txt seçin", true); return; }
        setBusy(true, "ENV okunuyor ve Microsoft ile doğrulanıyor");
        executor.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("Dosya açılamadı");
                EnvConfig cfg = EnvConfig.parse(in);
                int count = new MicrosoftApi(cfg).validate();
                new AuthStore(this).save(cfg);
                runOnUiThread(() -> {
                    setBusy(false, "Microsoft bağlantısı doğrulandı • " + count + " uygulama");
                    renderConfigState();
                    PollService.ensureRunning(this);
                    PollService.requestImmediate(this);
                });
            } catch (Throwable e) { runOnUiThread(() -> setBusy(false, concise(e.getMessage()))); }
        });
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (idx >= 0) return c.getString(idx); }
        } catch (Exception ignored) {}
        String p = uri.getLastPathSegment(); return p == null ? "config.env" : p;
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFY);
    }

    private void setBusy(boolean busy, String status) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        selectEnv.setEnabled(!busy);
        refresh.setEnabled(!busy && new AuthStore(this).hasConfig());
        setStatus(status, false);
    }

    private void setStatus(String status, boolean error) {
        progress.setVisibility(View.GONE);
        statusText.setText((error ? "Hata: " : "Durum: ") + (status == null || status.isEmpty() ? "--" : status));
        statusText.setTextColor(error ? Color.rgb(180, 40, 40) : Color.rgb(25, 33, 43));
        selectEnv.setEnabled(true);
        refresh.setEnabled(new AuthStore(this).hasConfig());
    }

    private LinearLayout box() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE); bg.setCornerRadius(dp(12)); bg.setStroke(dp(1), Color.rgb(226, 230, 235));
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12)); box.setLayoutParams(lp); return box;
    }

    private Button button(String label) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(15, 108, 189)); bg.setCornerRadius(dp(9));
        b.setBackground(bg); b.setPadding(dp(14), dp(10), dp(14), dp(10)); return b;
    }

    private TextView text(String value, float sp, int color, int style) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color); t.setTypeface(Typeface.create("sans", style)); return t;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String money(double value) { return String.format(Locale.US, "$%,.2f", value); }
    private static String formatTurkeyTime(String instant) {
        try { return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.forLanguageTag("tr-TR"))
                .withZone(ZoneId.of("Europe/Istanbul")).format(Instant.parse(instant)) + " TR"; }
        catch (Exception e) { return instant == null || instant.isEmpty() ? "--" : instant; }
    }
    private static String concise(String s) {
        if (s == null || s.trim().isEmpty()) return "İşlem tamamlanamadı";
        String x = s.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        return x.length() > 240 ? x.substring(0, 240) : x;
    }
}
