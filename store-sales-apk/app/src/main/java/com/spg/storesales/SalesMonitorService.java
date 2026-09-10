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
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

public final class SalesMonitorService extends Service {
    private static final int ONGOING=7201,SALE_BASE=7202;
    private static final String CH="microsoft_report_monitor",SALES="microsoft_report_sales";
    private static final long SYNC_INTERVAL_MS=2L*60L*1000L;
    private volatile boolean stop;private Thread worker;private ReportStore store;private CredentialStore credentials;private PowerManager.WakeLock wakeLock;

    @Override public void onCreate(){
        super.onCreate();store=new ReportStore(this);credentials=new CredentialStore(this);channels();
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        if(pm!=null){wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MicrosoftRapor:LiveSync");wakeLock.setReferenceCounted(false);wakeLock.acquire();}
        startForeground(ONGOING,note(CH,"Microsoft Rapor canlı senkronizasyon","Store Analytics 2 dakikalık sorgu başlatılıyor",true));
    }

    @Override public int onStartCommand(Intent i,int f,int id){
        if(!credentials.hasCredentials()){stopSelf();return START_NOT_STICKY;}
        if(worker==null||!worker.isAlive()){stop=false;worker=new Thread(this::loop,"microsoft-report-live");worker.start();}
        return START_STICKY;
    }

    private void loop(){
        long backoff=2000L;
        while(!stop&&credentials.hasCredentials()){
            long cycleStarted=System.currentTimeMillis();
            String today=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());
            boolean hadTodayBaseline=today.equals(store.syncDay())&&store.syncDayValues().length()>0;
            JSONObject beforeDay=hadTodayBaseline?store.syncDayValues():new JSONObject();
            try{
                ReportRepository repo=new ReportRepository(credentials,store);
                store.markSyncAttempt("Store Analytics sorgulanıyor");update("Store Analytics sorgulanıyor • "+time());
                repo.refresh(null);
                store.markSyncSuccess("Store Analytics");update("CANLI • Store Analytics • "+time()+" • sonraki 2 dk");
                JSONObject afterDay=today.equals(store.syncDay())?store.syncDayValues():new JSONObject();
                if(store.monitorEnabled()&&hadTodayBaseline)notifyProductDeltas(beforeDay,afterDay);
                backoff=2000L;
                long wait=SYNC_INTERVAL_MS-(System.currentTimeMillis()-cycleStarted);
                if(wait>0L)Thread.sleep(wait);
            }catch(InterruptedException e){break;}
            catch(Exception e){
                store.markSyncError(safe(e));update("Store Analytics yeniden deneniyor • "+shortText(safe(e)));
                try{Thread.sleep(backoff);}catch(InterruptedException x){break;}
                backoff=Math.min(60000L,backoff*2L);
            }
        }
        stopSelf();
    }

    private void notifyProductDeltas(JSONObject before,JSONObject after){
        Iterator<String> keys=after.keys();while(keys.hasNext()){
            String id=keys.next();JSONObject now=after.optJSONObject(id);if(now==null)continue;JSONObject old=before.optJSONObject(id);
            double delta=finite(now.optDouble("sales",0d))-(old==null?0d:finite(old.optDouble("sales",0d)));if(delta<=0.000001d)continue;
            notifySale(id,cleanName(now.optString("name",id),id),delta);
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent){
        if(!stop&&credentials!=null&&credentials.hasCredentials()){
            Intent restart=new Intent(getApplicationContext(),SalesMonitorService.class);
            try{if(Build.VERSION.SDK_INT>=26)getApplicationContext().startForegroundService(restart);else getApplicationContext().startService(restart);}catch(Exception ignored){}
        }
        super.onTaskRemoved(rootIntent);
    }

    private void notifySale(String productId,String name,double delta){String text=String.format(Locale.US,"%s +$%.2f",name,delta);int id=SALE_BASE+Math.abs((productId==null?name:productId).hashCode()%100000);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(id,note(SALES,"Yeni Microsoft Store satışı",text,false));}
    private void channels(){if(Build.VERSION.SDK_INT>=26){NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);n.createNotificationChannel(new NotificationChannel(CH,"Microsoft Rapor canlı senkronizasyon",NotificationManager.IMPORTANCE_LOW));n.createNotificationChannel(new NotificationChannel(SALES,"Yeni satışlar",NotificationManager.IMPORTANCE_HIGH));}}
    private Notification note(String channel,String title,String text,boolean ongoing){Intent open=new Intent(this,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this);return b.setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pi).setOngoing(ongoing).setAutoCancel(!ongoing).build();}
    private void update(String text){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(ONGOING,note(CH,"Microsoft Rapor canlı senkronizasyon",text,true));}
    private static String time(){return new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date());}
    private static String shortText(String s){return s==null?"Hata":(s.length()>72?s.substring(0,72)+"…":s);}
    private static String cleanName(String name,String fallback){return name==null||name.trim().isEmpty()||"—".equals(name)?fallback:name;}
    private static double finite(double v){return Double.isNaN(v)||Double.isInfinite(v)?0d:v;}
    private static String safe(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}
    @Override public void onDestroy(){stop=true;if(worker!=null)worker.interrupt();if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
