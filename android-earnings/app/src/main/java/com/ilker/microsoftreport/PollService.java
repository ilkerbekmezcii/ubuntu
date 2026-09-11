package com.ilker.microsoftreport;

import android.app.*;
import android.content.Intent;
import android.os.*;
import java.time.LocalTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PollService extends Service {
    public static final String ACTION_STATUS="com.ilker.microsoftreport.STATUS";
    private static final String CH="earnings_live";
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private volatile boolean running;

    @Override public void onCreate(){
        super.onCreate();
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(CH,"Microsoft Earnings",NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
        startForeground(1001,notification("Canlı rapor başlatıldı"));
        running=true;
        io.execute(this::loop);
    }

    private Notification notification(String text){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CH):new Notification.Builder(this);
        return b.setContentTitle("Microsoft Earnings").setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true).setContentIntent(pi).build();
    }

    private void loop(){
        while(running){
            String msg;
            try{
                msg=MicrosoftApi.today(this);
                String stamp="Son kontrol: "+ LocalTime.now().withNano(0)+"\n";
                msg=stamp+msg;
                try{AuthStore.save(this,AuthStore.LAST_REPORT,msg);}catch(Exception ignored){}
            }catch(Exception e){
                msg="Hata: "+e.getMessage();
            }
            final String out=msg;
            String note=out.replace('\n',' '); if(note.length()>120)note=note.substring(0,120);
            getSystemService(NotificationManager.class).notify(1001,notification(note));
            Intent i=new Intent(ACTION_STATUS); i.setPackage(getPackageName()); i.putExtra("status",out); sendBroadcast(i);
            try{Thread.sleep(60_000L);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
    }

    @Override public int onStartCommand(Intent i,int flags,int id){return START_STICKY;}
    @Override public void onDestroy(){running=false;io.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
