package com.spg.storesales;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class SalesMonitorService extends Service {
    private static final int ONGOING = 7201, SALE = 7202;
    private static final String CH = "microsoft_report_monitor", SALES = "microsoft_report_sales";
    private volatile boolean stop;
    private Thread worker;
    private ReportStore store;
    private CredentialStore credentials;

    @Override public void onCreate() {
        super.onCreate();
        store = new ReportStore(this);
        credentials = new CredentialStore(this);
        channels();
        startForeground(ONGOING, note(CH, "Microsoft Rapor takibi", "5 dakikalık takip açık", true));
    }

    @Override public int onStartCommand(Intent i, int f, int id) {
        if (!store.monitorEnabled() || !credentials.hasCredentials()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            stop = false;
            worker = new Thread(this::loop, "microsoft-report-monitor");
            worker.start();
        }
        return START_STICKY;
    }

    private void loop() {
        while (!stop && store.monitorEnabled()) {
            try {
                check();
                update("Son kontrol başarılı • " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            } catch (Exception e) {
                update("Takip açık • bağlantı hatası: " + safe(e));
            }
            try { Thread.sleep(5L * 60L * 1000L); }
            catch (InterruptedException e) { break; }
        }
        stopSelf();
    }

    private void check() throws Exception {
        PartnerCenterClient api = new PartnerCenterClient(credentials.load());
        JSONArray apps = store.loadApps(12L * 60L * 60L * 1000L);
        if (apps == null) {
            apps = api.applications();
            store.saveApps(apps);
        }
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String apiDay = new SimpleDateFormat("M/d/yyyy", Locale.US).format(new Date());
        JSONObject now = new JSONObject();
        JSONObject old = store.baseline();
        double totalDelta = 0d, best = 0d;
        String bestName = null;
        boolean sameDay = day.equals(store.baselineDay());

        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null) continue;
            String id = app.optString("id", app.optString("applicationId", ""));
            if (id.isEmpty()) continue;
            String name = app.optString("primaryName", app.optString("name", id));
            PartnerCenterClient.Totals t = api.acquisitions(id, apiDay, apiDay, day);
            double current = t.todaySales;
            now.put(id, current);
            if (sameDay) {
                double delta = current - old.optDouble(id, 0d);
                if (delta > 0.000001d) {
                    totalDelta += delta;
                    if (delta > best) { best = delta; bestName = name; }
                }
            }
        }

        if (sameDay && totalDelta > 0.000001d) notifySale(bestName, best, totalDelta);
        store.saveBaseline(day, now);
    }

    private void notifySale(String name, double best, double total) {
        String text = name != null ? String.format(Locale.US, "%s +$%.2f", name, best) : String.format(Locale.US, "Yeni satış +$%.2f", total);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(SALE, note(SALES, "Yeni Microsoft Store satışı", text, false));
    }

    private void channels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager n = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            n.createNotificationChannel(new NotificationChannel(CH, "Microsoft Rapor takibi", NotificationManager.IMPORTANCE_LOW));
            n.createNotificationChannel(new NotificationChannel(SALES, "Yeni satışlar", NotificationManager.IMPORTANCE_HIGH));
        }
    }

    private Notification note(String channel, String title, String text, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
        return b.setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pi).setOngoing(ongoing).setAutoCancel(!ongoing).build();
    }

    private void update(String text) {
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(ONGOING, note(CH, "Microsoft Rapor takibi", text, true));
    }

    private static String safe(Exception e) { String m = e.getMessage(); return m == null ? e.getClass().getSimpleName() : m; }
    @Override public void onDestroy() { stop = true; if (worker != null) worker.interrupt(); super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
