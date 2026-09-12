package com.ilker.microsoftreport;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PollService extends Service {
    public static final String ACTION_STATUS = "com.ilker.microsoftstoresales5m.STATUS";
    public static final String ACTION_POLL_NOW = "com.ilker.microsoftstoresales5m.POLL_NOW";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_ERROR = "error";
    private static final String CHANNEL_MONITOR = "store_sales_5m_monitor";
    private static final String CHANNEL_SALES = "store_sales_5m_alerts";
    private static final int MONITOR_ID = 5001;
    private static final long INTERVAL_SECONDS = 300L;

    private ScheduledExecutorService scheduler;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(MONITOR_ID, monitorNotification("İzleme aktif • 5 dakikada bir kontrol"));
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::pollSafely, 0L, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_POLL_NOW.equals(intent.getAction()) && scheduler != null) scheduler.execute(this::pollSafely);
        return START_STICKY;
    }

    private void pollSafely() {
        if (!polling.compareAndSet(false, true)) return;
        try {
            AuthStore auth = new AuthStore(this);
            if (!auth.hasConfig()) {
                broadcast("ENV yapılandırması bekleniyor", false);
                stopSelf();
                return;
            }
            updateMonitor("Microsoft Store satışları kontrol ediliyor");
            Models.Dashboard dashboard = new MicrosoftApi(auth.load()).fetchTodayAndMonth();
            StateStore state = new StateStore(this);
            List<StateStore.SaleChange> changes = state.detectChanges(dashboard);
            state.saveDashboard(dashboard);
            for (StateStore.SaleChange change : changes) notifySale(change);
            String when = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.forLanguageTag("tr-TR"))
                    .withZone(ZoneId.of("Europe/Istanbul")).format(Instant.now());
            updateMonitor("Son kontrol: " + when + " TR");
            broadcast("Kontrol tamamlandı", false);
        } catch (Throwable e) {
            String msg = concise(e.getMessage());
            updateMonitor("Kontrol hatası");
            broadcast(msg.isEmpty() ? "Kontrol tamamlanamadı" : msg, true);
        } finally {
            polling.set(false);
        }
    }

    private void notifySale(StateStore.SaleChange change) {
        Intent launch = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, change.sale.appId.hashCode(), launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String body = change.delta + " yeni • Bugün " + change.sale.quantity + " adet • Net "
                + String.format(Locale.US, "$%,.2f", change.sale.netUsd());
        Notification n = new Notification.Builder(this, CHANNEL_SALES)
                .setSmallIcon(R.drawable.ic_stat_analytics)
                .setContentTitle(change.sale.appName)
                .setContentText(body)
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(20_000 + Math.abs(change.sale.appId.hashCode() % 10_000), n);
    }

    private Notification monitorNotification(String text) {
        PendingIntent content = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_MONITOR)
                .setSmallIcon(R.drawable.ic_stat_analytics)
                .setContentTitle("Microsoft Store Sales 5M")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateMonitor(String text) {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(MONITOR_ID, monitorNotification(text));
    }

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel monitor = new NotificationChannel(CHANNEL_MONITOR, "5 dk Store izleme", NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("Microsoft Store satışlarını beş dakikada bir kontrol eden servis");
        nm.createNotificationChannel(monitor);
        NotificationChannel sales = new NotificationChannel(CHANNEL_SALES, "Yeni Store satışları", NotificationManager.IMPORTANCE_HIGH);
        sales.setDescription("Yeni Microsoft Store satışı algılandığında bildirim");
        nm.createNotificationChannel(sales);
    }

    private void broadcast(String status, boolean error) {
        Intent i = new Intent(ACTION_STATUS).setPackage(getPackageName());
        i.putExtra(EXTRA_STATUS, status);
        i.putExtra(EXTRA_ERROR, error);
        sendBroadcast(i);
    }

    private static String concise(String s) {
        if (s == null) return "";
        String x = s.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        return x.length() > 240 ? x.substring(0, 240) : x;
    }

    public static void ensureRunning(Context context) {
        if (!new AuthStore(context).hasConfig()) return;
        context.startForegroundService(new Intent(context, PollService.class));
    }

    public static void requestImmediate(Context context) {
        if (!new AuthStore(context).hasConfig()) return;
        context.startForegroundService(new Intent(context, PollService.class).setAction(ACTION_POLL_NOW));
    }

    @Override public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
