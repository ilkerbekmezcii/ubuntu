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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class SalesMonitorService extends Service {
    private static final int ONGOING=7201,SALE=7202;
    private static final String CH="microsoft_report_monitor",SALES="microsoft_report_sales";
    private volatile boolean stop;
    private Thread worker;
    private ReportStore store;
    private CredentialStore credentials;

    @Override public void onCreate(){
        super.onCreate();store=new ReportStore(this);credentials=new CredentialStore(this);channels();
        startForeground(ONGOING,note(CH,"Microsoft Rapor canlı senkronizasyon","Canlı tarama başlatılıyor",true));
    }

    @Override public int onStartCommand(Intent i,int f,int id){
        if(!credentials.hasCredentials()){stopSelf();return START_NOT_STICKY;}
        if(worker==null||!worker.isAlive()){stop=false;worker=new Thread(this::loop,"microsoft-report-live-sync");worker.start();}
        return START_STICKY;
    }

    private void loop(){
        while(!stop&&credentials.hasCredentials()){
            try{
                ReportRepository repo=new ReportRepository(credentials,store);
                if(repo.needsFullSync()){
                    update("Tam doğrulama yapılıyor");repo.refresh(null);continue;
                }
                JSONArray apps=repo.applications();
                List<JSONObject> ranked=rank(apps);
                if(ranked.isEmpty()){Thread.sleep(3000L);continue;}
                int hotCount=Math.min(3,ranked.size());
                List<JSONObject> hot=new ArrayList<>(ranked.subList(0,hotCount));
                List<JSONObject> cold=new ArrayList<>(ranked.subList(hotCount,ranked.size()));
                if(cold.isEmpty()){
                    for(JSONObject app:hot){if(stop)break;refreshOne(repo,app,apps.length());}
                }else{
                    int h=0;
                    for(JSONObject app:cold){
                        if(stop)break;
                        refreshOne(repo,hot.get(h++%hot.size()),apps.length());
                        if(stop)break;
                        refreshOne(repo,app,apps.length());
                    }
                }
            }catch(InterruptedException e){break;}
            catch(Exception e){update("Canlı senkronizasyon bekliyor • "+safe(e));try{Thread.sleep(3000L);}catch(InterruptedException x){break;}}
        }
        stopSelf();
    }

    private void refreshOne(ReportRepository repo,JSONObject app,int appCount) throws Exception {
        String day=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());
        boolean sameDay=day.equals(store.syncDay());
        ReportStore.Report before=store.loadReport();
        ReportStore.Report after=repo.refreshOne(app,appCount);
        String name=first(app.optString("primaryName"),app.optString("name"),app.optString("applicationName"),app.optString("id"),"Uygulama");
        update("Canlı • "+name+" • "+new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date()));
        if(store.monitorEnabled()&&sameDay){double delta=finite(after.dailySales)-finite(before.dailySales);if(delta>0.000001d)notifySale(name,delta);}
    }

    private List<JSONObject> rank(JSONArray apps){
        List<JSONObject> out=new ArrayList<>();for(int i=0;i<apps.length();i++){JSONObject a=apps.optJSONObject(i);if(a!=null)out.add(a);}
        final JSONObject day=store.syncDayValues(),month=store.appMonthValues();
        Collections.sort(out,new Comparator<JSONObject>(){
            @Override public int compare(JSONObject a,JSONObject b){return Double.compare(score(b,day,month),score(a,day,month));}
        });
        return out;
    }

    private static double score(JSONObject app,JSONObject day,JSONObject month){
        String id=first(app.optString("id"),app.optString("applicationId"));
        JSONObject d=day.optJSONObject(id),m=month.optJSONObject(id);
        double ds=d==null?0d:finite(d.optDouble("sales",0d));long da=d==null?0L:Math.max(0,d.optLong("acq",0L));
        double ms=m==null?0d:finite(m.optDouble("sales",0d));long ma=m==null?0L:Math.max(0,m.optLong("acq",0L));
        return ds*1000000d+da*10000d+ms*10d+ma;
    }

    private void notifySale(String name,double delta){
        String text=String.format(Locale.US,"%s +$%.2f",name,delta);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(SALE,note(SALES,"Yeni Microsoft Store satışı",text,false));
    }

    private void channels(){if(Build.VERSION.SDK_INT>=26){NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);n.createNotificationChannel(new NotificationChannel(CH,"Microsoft Rapor canlı senkronizasyon",NotificationManager.IMPORTANCE_LOW));n.createNotificationChannel(new NotificationChannel(SALES,"Yeni satışlar",NotificationManager.IMPORTANCE_HIGH));}}
    private Notification note(String channel,String title,String text,boolean ongoing){Intent open=new Intent(this,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this);return b.setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pi).setOngoing(ongoing).setAutoCancel(!ongoing).build();}
    private void update(String text){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(ONGOING,note(CH,"Microsoft Rapor canlı senkronizasyon",text,true));}
    private static double finite(double v){return Double.isNaN(v)||Double.isInfinite(v)?0d:v;}
    private static String first(String...v){for(String s:v)if(s!=null&&!s.isEmpty())return s;return "";}
    private static String safe(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}
    @Override public void onDestroy(){stop=true;if(worker!=null)worker.interrupt();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
