package com.spg.storesales;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class SalesMonitorService extends Service {
    private static final int ONGOING=7201,SALE=7202;
    private static final long SYNC_INTERVAL_MS=5L*60L*1000L;
    private static final String CH="microsoft_report_monitor",SALES="microsoft_report_sales";
    private volatile boolean stop;
    private Thread worker;
    private ReportStore store;
    private CredentialStore credentials;

    @Override public void onCreate(){
        super.onCreate();
        store=new ReportStore(this);
        credentials=new CredentialStore(this);
        channels();
        startForeground(ONGOING,note(CH,"Microsoft Rapor senkronizasyonu","Arka plan senkronizasyonu açık",true));
    }

    @Override public int onStartCommand(Intent i,int f,int id){
        if(!credentials.hasCredentials()){
            stopSelf();
            return START_NOT_STICKY;
        }
        if(worker==null||!worker.isAlive()){
            stop=false;
            worker=new Thread(this::loop,"microsoft-report-sync");
            worker.start();
        }
        return START_STICKY;
    }

    private void loop(){
        while(!stop&&credentials.hasCredentials()){
            try{
                syncOnce();
                update("Son senkronizasyon • "+new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()));
            }catch(Exception e){
                update("Senkronizasyon bekliyor • "+safe(e));
            }
            try{Thread.sleep(SYNC_INTERVAL_MS);}catch(InterruptedException e){break;}
        }
        stopSelf();
    }

    private void syncOnce() throws Exception {
        ReportStore.Report before=store.loadReport();
        long beforeDay=dayKey(before.updatedAt);
        ReportRepository repo=new ReportRepository(credentials,store);
        ReportStore.Report after=repo.refresh(null);

        if(store.monitorEnabled()&&dayKey(System.currentTimeMillis())==beforeDay){
            double delta=finite(after.dailySales)-finite(before.dailySales);
            if(delta>0.000001d)notifySale(after.dailyLeader,delta);
        }
    }

    private void notifySale(String name,double delta){
        String clean=(name==null||name.trim().isEmpty()||"—".equals(name))?"Yeni satış":name;
        String text=String.format(Locale.US,"%s +$%.2f",clean,delta);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(SALE,note(SALES,"Yeni Microsoft Store satışı",text,false));
    }

    private static long dayKey(long ts){
        if(ts<=0)return -1;
        java.util.Calendar c=java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        return c.get(java.util.Calendar.YEAR)*1000L+c.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private void channels(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            n.createNotificationChannel(new NotificationChannel(CH,"Microsoft Rapor senkronizasyonu",NotificationManager.IMPORTANCE_LOW));
            n.createNotificationChannel(new NotificationChannel(SALES,"Yeni satışlar",NotificationManager.IMPORTANCE_HIGH));
        }
    }

    private Notification note(String channel,String title,String text,boolean ongoing){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this);
        return b.setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pi).setOngoing(ongoing).setAutoCancel(!ongoing).build();
    }

    private void update(String text){
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(ONGOING,note(CH,"Microsoft Rapor senkronizasyonu",text,true));
    }

    private static double finite(double v){return Double.isNaN(v)||Double.isInfinite(v)?0d:v;}
    private static String safe(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}
    @Override public void onDestroy(){stop=true;if(worker!=null)worker.interrupt();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
