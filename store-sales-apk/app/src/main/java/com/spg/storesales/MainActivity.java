package com.spg.storesales;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQ_ENV=4101,REQ_NOTIF=4102;
    private final ExecutorService exec=Executors.newSingleThreadExecutor();
    private final Handler live=new Handler(Looper.getMainLooper());
    private final Runnable liveTick=new Runnable(){@Override public void run(){if(store!=null)render(store.loadReport());live.postDelayed(this,700L);}};
    private CredentialStore credentials;private ReportStore store;private volatile boolean refreshing;
    private TextView todayGross,todayNet,monthGross,monthNet,todayMeta,monthMeta,dailyLeader,monthlyLeader,status;private Button refresh,monitor;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);credentials=new CredentialStore(this);store=new ReportStore(this);setTitle("Microsoft Rapor");setContentView(ui());render(store.loadReport());syncMonitor();
        if(credentials.hasCredentials()){startSyncService();status.setText(statusText(store.loadReport()));}else status.setText("microsoft.env seçildiğinde canlı senkronizasyon başlar.");
    }
    @Override protected void onResume(){super.onResume();live.removeCallbacks(liveTick);live.post(liveTick);if(store!=null){render(store.loadReport());syncMonitor();}}
    @Override protected void onPause(){live.removeCallbacks(liveTick);super.onPause();}

    private View ui(){
        ScrollView s=new ScrollView(this);LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(dp(16),dp(20),dp(16),dp(28));s.addView(r);
        r.addView(txt("Microsoft Rapor",24,true,"#111827"));TextView sub=txt("Microsoft Partner Center • CANLI",13,true,"#6B7280");sub.setPadding(0,dp(2),0,dp(14));r.addView(sub);

        LinearLayout c=card();c.addView(txt("BUGÜN",11,true,"#6B7280"));
        c.addView(txt("Brüt",12,false,"#6B7280"));todayGross=txt("$0.00",27,true,"#111827");c.addView(todayGross);
        TextView nl=txt("Net",12,false,"#6B7280");nl.setPadding(0,dp(6),0,0);c.addView(nl);todayNet=txt("$0.00",23,true,"#111827");c.addView(todayNet);
        todayMeta=txt("Adet: 0",13,false,"#4B5563");todayMeta.setPadding(0,dp(6),0,0);c.addView(todayMeta);r.addView(c);

        c=card();c.addView(txt("BU AY",11,true,"#6B7280"));
        c.addView(txt("Brüt",12,false,"#6B7280"));monthGross=txt("$0.00",27,true,"#111827");c.addView(monthGross);
        nl=txt("Net",12,false,"#6B7280");nl.setPadding(0,dp(6),0,0);c.addView(nl);monthNet=txt("$0.00",23,true,"#111827");c.addView(monthNet);
        monthMeta=txt("Adet: 0",13,false,"#4B5563");monthMeta.setPadding(0,dp(6),0,0);c.addView(monthMeta);r.addView(c);

        c=card();c.addView(txt("GÜNLÜK LİDER",11,true,"#6B7280"));dailyLeader=txt("—\nBrüt $0.00 • Net $0.00 • 0",15,true,"#111827");c.addView(dailyLeader);r.addView(c);
        c=card();c.addView(txt("AYLIK LİDER",11,true,"#6B7280"));monthlyLeader=txt("—\nBrüt $0.00 • Net $0.00 • 0",15,true,"#111827");c.addView(monthlyLeader);r.addView(c);
        status=txt("Hazır",12,false,"#6B7280");status.setPadding(0,dp(4),0,dp(10));r.addView(status);
        refresh=button("Öncelikli yenile");refresh.setOnClickListener(v->refreshAsync());r.addView(refresh,params());
        monitor=button("Satış bildirimi");monitor.setOnClickListener(v->toggleMonitor());LinearLayout.LayoutParams p=params();p.topMargin=dp(8);r.addView(monitor,p);
        Button env=button("microsoft.env seç / değiştir");env.setOnClickListener(v->chooseEnv());p=params();p.topMargin=dp(8);r.addView(env,p);return s;
    }

    private void render(ReportStore.Report x){
        todayGross.setText(money(x.dailySales));todayNet.setText(money(x.dailyNet));monthGross.setText(money(x.monthlySales));monthNet.setText(money(x.monthlyNet));
        todayMeta.setText("Adet: "+Math.max(0,x.dailyAcq));monthMeta.setText("Adet: "+Math.max(0,x.monthlyAcq));
        dailyLeader.setText(safeName(x.dailyLeader)+String.format(Locale.US,"\nBrüt $%.2f • Net $%.2f • %d",finite(x.dailyLeaderSales),finite(x.dailyLeaderNet),Math.max(0,x.dailyLeaderAcq)));
        monthlyLeader.setText(safeName(x.monthlyLeader)+String.format(Locale.US,"\nBrüt $%.2f • Net $%.2f • %d",finite(x.monthlyLeaderSales),finite(x.monthlyLeaderNet),Math.max(0,x.monthlyLeaderAcq)));
        if(x.updatedAt>0&&!refreshing)status.setText(statusText(x));
    }
    private String statusText(ReportStore.Report x){if(x.updatedAt<=0)return "Canlı senkronizasyon açık • ilk veri hazırlanıyor";return "CANLI • son veri "+DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(x.updatedAt))+" • "+Math.max(0,x.appCount)+" uygulama";}

    private void refreshAsync(){
        if(refreshing||!credentials.hasCredentials())return;refreshing=true;refresh.setEnabled(false);status.setText("Öncelikli yenileme yapılıyor");
        exec.execute(()->{try{ReportRepository repo=new ReportRepository(credentials,store);ReportStore.Report rr=repo.refresh((d,t,phase)->runOnUiThread(()->status.setText(phase+": "+d+"/"+t)));runOnUiThread(()->{render(rr);finishBusy();});}catch(Exception e){runOnUiThread(()->{status.setText("Yenileme bekliyor • "+safe(e));finishBusy();});}});
    }
    private void finishBusy(){refreshing=false;refresh.setEnabled(true);render(store.loadReport());}
    private void chooseEnv(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,REQ_ENV);}
    @Override protected void onActivityResult(int rq,int rc,Intent d){super.onActivityResult(rq,rc,d);if(rq!=REQ_ENV||rc!=RESULT_OK||d==null||d.getData()==null)return;Uri u=d.getData();try(InputStream in=getContentResolver().openInputStream(u)){credentials.saveEnv(read(in));Toast.makeText(this,"Microsoft kimlik bilgileri şifreli kaydedildi",Toast.LENGTH_SHORT).show();startSyncService();refreshAsync();}catch(Exception e){Toast.makeText(this,"Dosya alınamadı: "+safe(e),Toast.LENGTH_LONG).show();}}
    private void toggleMonitor(){boolean next=!store.monitorEnabled();if(next&&!credentials.hasCredentials()){Toast.makeText(this,"Önce microsoft.env seçin",Toast.LENGTH_LONG).show();chooseEnv();return;}if(next&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIF);store.setMonitorEnabled(next);if(credentials.hasCredentials())startSyncService();syncMonitor();}
    private void startSyncService(){Intent i=new Intent(this,SalesMonitorService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private void syncMonitor(){monitor.setText(store.monitorEnabled()?"Satış bildirimi: AÇIK":"Satış bildirimi: KAPALI");}
    private static String safe(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}
    private static double finite(double v){return Double.isNaN(v)||Double.isInfinite(v)?0d:v;}
    private static String safeName(String s){return s==null||s.trim().isEmpty()||"nan".equalsIgnoreCase(s)||"null".equalsIgnoreCase(s)?"—":s;}
    private static String money(double v){return String.format(Locale.US,"$%.2f",finite(v));}
    private static String read(InputStream in)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)o.write(b,0,n);return o.toString("UTF-8");}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(14),dp(12),dp(14),dp(12));GradientDrawable g=new GradientDrawable();g.setColor(Color.WHITE);g.setCornerRadius(dp(14));g.setStroke(1,Color.parseColor("#E5E7EB"));c.setBackground(g);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(10);c.setLayoutParams(p);return c;}
    private TextView txt(String v,int sp,boolean b,String color){TextView t=new TextView(this);t.setText(v);t.setTextSize(sp);t.setTextColor(Color.parseColor(color));if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String v){Button b=new Button(this);b.setText(v);b.setAllCaps(false);b.setGravity(Gravity.CENTER);b.setMinHeight(dp(44));return b;}
    private LinearLayout.LayoutParams params(){return new LinearLayout.LayoutParams(-1,dp(48));}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
