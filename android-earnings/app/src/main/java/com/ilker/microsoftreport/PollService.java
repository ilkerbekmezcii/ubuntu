package com.ilker.microsoftreport;

import android.app.*;
import android.content.Intent;
import android.os.*;
import java.time.LocalTime;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PollService extends Service {
    public static final String ACTION_STATUS="com.ilker.microsoftreport.STATUS";
    private static final String LIVE_CH="earnings_live";
    private static final String PURCHASE_CH="earnings_purchase";
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private volatile boolean running;

    @Override public void onCreate(){
        super.onCreate();
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(LIVE_CH,"Microsoft Earnings Kontrolü",NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(PURCHASE_CH,"Yeni Satın Alımlar",NotificationManager.IMPORTANCE_HIGH));
        }
        startForeground(1001,liveNotification("2 dakikalık kontrol başlatıldı"));
        running=true;
        io.execute(this::loop);
    }

    private PendingIntent openApp(){
        Intent open=new Intent(this,MainActivity.class);
        return PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification liveNotification(String text){
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,LIVE_CH):new Notification.Builder(this);
        return b.setContentTitle("Microsoft Earnings").setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true).setContentIntent(openApp()).build();
    }

    private Notification purchaseNotification(MicrosoftApi.DashboardResult r){
        String text=r.newPurchaseCount+" yeni satın alım • Brüt "+String.format(Locale.US,"$%,.2f",r.newPurchaseGrossUsd)+
                " • Net "+String.format(Locale.US,"$%,.2f",r.newPurchaseNetUsd);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,PURCHASE_CH):new Notification.Builder(this);
        return b.setContentTitle("Yeni satın alım").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_notify_more).setAutoCancel(true).setContentIntent(openApp()).build();
    }

    private void loop(){
        while(running){
            try{
                MicrosoftApi.DashboardResult result=MicrosoftApi.refreshDashboard(this);
                getSystemService(NotificationManager.class).notify(1001,liveNotification("Son kontrol: "+LocalTime.now().withNano(0)));
                Intent i=new Intent(ACTION_STATUS);i.setPackage(getPackageName());i.putExtra("status",result.displayText);sendBroadcast(i);
                if(result.newPurchaseCount>0)getSystemService(NotificationManager.class).notify(2001,purchaseNotification(result));
            }catch(Exception e){
                String msg="Kontrol hatası: "+e.getMessage();
                getSystemService(NotificationManager.class).notify(1001,liveNotification(msg.length()>120?msg.substring(0,120):msg));
                Intent i=new Intent(ACTION_STATUS);i.setPackage(getPackageName());i.putExtra("status",msg);sendBroadcast(i);
            }
            try{Thread.sleep(120_000L);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
    }

    @Override public int onStartCommand(Intent i,int flags,int id){return START_STICKY;}
    @Override public void onDestroy(){running=false;io.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
