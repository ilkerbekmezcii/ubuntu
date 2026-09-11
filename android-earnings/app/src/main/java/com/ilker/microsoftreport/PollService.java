package com.ilker.microsoftreport;

import android.app.*;
import android.content.Intent;
import android.os.*;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PollService extends Service {
    public static final String ACTION_STATUS="com.ilker.microsoftreport.STATUS";
    private static final String CH="earnings_live";
    private final Handler h=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private boolean running;

    private final Runnable tick=new Runnable(){@Override public void run(){ if(!running)return; fetch(); h.postDelayed(this,60_000L); }};

    @Override public void onCreate(){
        super.onCreate();
        if(Build.VERSION.SDK_INT>=26){ NotificationChannel c=new NotificationChannel(CH,"Microsoft Earnings",NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager.class).createNotificationChannel(c); }
        startForeground(1001,notification("Canlı rapor başlatıldı"));
        running=true; h.post(tick);
    }

    private Notification notification(String text){
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CH):new Notification.Builder(this);
        return b.setContentTitle("Microsoft Earnings").setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build();
    }

    private void fetch(){
        io.execute(()->{
            String msg;
            try{
                String token=MicrosoftApi.refresh(this);
                LocalDate today=LocalDate.now(); LocalDate first=today.withDayOfMonth(1);
                String body=MicrosoftApi.earnings(token,first,today);
                msg="Son güncelleme: "+java.time.LocalTime.now().withNano(0)+"\n"+body;
            }catch(Exception e){msg="Hata: "+e.getMessage();}
            getSystemService(NotificationManager.class).notify(1001,notification(msg.length()>120?msg.substring(0,120):msg));
            Intent i=new Intent(ACTION_STATUS); i.setPackage(getPackageName()); i.putExtra("status",msg); sendBroadcast(i);
        });
    }

    @Override public int onStartCommand(Intent i,int flags,int id){return START_STICKY;}
    @Override public void onDestroy(){running=false;h.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
