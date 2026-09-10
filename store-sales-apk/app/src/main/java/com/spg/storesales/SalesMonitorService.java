package com.spg.storesales;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class SalesMonitorService extends Service {
    private static final int ONGOING=7201,SALE=7202;
    private static final String CH="microsoft_report_monitor",SALES="microsoft_report_sales";
    private volatile boolean stop;private Thread worker;private ReportStore store;private CredentialStore credentials;private PowerManager.WakeLock wakeLock;

    @Override public void onCreate(){
        super.onCreate();store=new ReportStore(this);credentials=new CredentialStore(this);channels();
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        if(pm!=null){wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MicrosoftRapor:LiveSync");wakeLock.setReferenceCounted(false);wakeLock.acquire();}
        startForeground(ONGOING,note(CH,"Microsoft Rapor canlı senkronizasyon","Earnings sürekli sorgu başlatılıyor",true));
    }

    @Override public int onStartCommand(Intent i,int f,int id){
        if(!credentials.hasCredentials()){stopSelf();return START_NOT_STICKY;}
        if(worker==null||!worker.isAlive()){stop=false;worker=new Thread(this::loop,"microsoft-report-earnings-live");worker.start();}
        return START_STICKY;
    }

    private void loop(){
        long backoff=1000L;
        while(!stop&&credentials.hasCredentials()){
            try{
                ReportStore.Report before=store.loadReport();
                String beforeDay=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date(before.updatedAt>0?before.updatedAt:System.currentTimeMillis()));
                ReportStore.Report after=new ReportRepository(credentials,store).refresh(null);
                String nowDay=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());
                update("CANLI • "+new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date()));
                if(store.monitorEnabled()&&beforeDay.equals(nowDay)){
                    double delta=finite(after.dailySales)-finite(before.dailySales);
                    if(delta>0.000001d)notifySale(after.dailyLeader,delta);
                }
                backoff=1000L;
                // Başarılı sorgudan sonra bekleme yok: API yanıtı tamamlanır tamamlanmaz yeni tur başlar.
            }catch(InterruptedException e){break;}
            catch(Exception e){
                update("Earnings yeniden deneniyor • "+safe(e));
                try{Thread.sleep(backoff);}catch(InterruptedException x){break;}
                backoff=Math.min(15000L,backoff*2L);
            }
        }
        stopSelf();
    }

    @Override public void onTaskRemoved(Intent rootIntent){
        if(!stop&&credentials!=null&&credentials.hasCredentials()){
            Intent restart=new Intent(getApplicationContext(),SalesMonitorService.class);
            try{if(Build.VERSION.SDK_INT>=26)getApplicationContext().startForegroundService(restart);else getApplicationContext().startService(restart);}catch(Exception ignored){}
        }
        super.onTaskRemoved(rootIntent);
    }

    private void notifySale(String name,double delta){String clean=name==null||name.trim().isEmpty()||"—".equals(name)?"Yeni satış":name;String text=String.format(Locale.US,"%s +$%.2f",clean,delta);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(SALE,note(SALES,"Yeni Microsoft Store satışı",text,false));}
    private void channels(){if(Build.VERSION.SDK_INT>=26){NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);n.createNotificationChannel(new NotificationChannel(CH,"Microsoft Rapor canlı senkronizasyon",NotificationManager.IMPORTANCE_LOW));n.createNotificationChannel(new NotificationChannel(SALES,"Yeni satışlar",NotificationManager.IMPORTANCE_HIGH));}}
    private Notification note(String channel,String title,String text,boolean ongoing){Intent open=new Intent(this,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this);return b.setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pi).setOngoing(ongoing).setAutoCancel(!ongoing).build();}
    private void update(String text){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(ONGOING,note(CH,"Microsoft Rapor canlı senkronizasyon",text,true));}
    private static double finite(double v){return Double.isNaN(v)||Double.isInfinite(v)?0d:v;}
    private static String safe(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}
    @Override public void onDestroy(){stop=true;if(worker!=null)worker.interrupt();if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
