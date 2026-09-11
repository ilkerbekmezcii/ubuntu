package com.ilker.microsoftreport;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private TextView status, code;
    private String verificationUrl;

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c, Intent i){ status.setText(i.getStringExtra("status")); }
    };

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(48,72,48,48);
        TextView title=new TextView(this); title.setText("Microsoft Earnings"); title.setTextSize(28); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD); root.addView(title);
        TextView sub=new TextView(this); sub.setText("Partner Center • 60 saniyede otomatik yenileme"); sub.setTextSize(16); sub.setPadding(0,10,0,36); root.addView(sub);
        code=new TextView(this); code.setText("Henüz oturum açılmadı"); code.setTextSize(20); code.setTextIsSelectable(true); root.addView(code);
        Button login=new Button(this); login.setText("Microsoft hesabıyla giriş yap"); root.addView(login);
        Button open=new Button(this); open.setText("Microsoft giriş sayfasını aç"); open.setEnabled(false); root.addView(open);
        Button start=new Button(this); start.setText("60 sn canlı raporu başlat"); root.addView(start);
        Button stop=new Button(this); stop.setText("Canlı raporu durdur"); root.addView(stop);
        status=new TextView(this); status.setText("Durum: hazır"); status.setTextSize(16); status.setPadding(0,32,0,0); status.setTextIsSelectable(true); root.addView(status);
        setContentView(root);

        login.setOnClickListener(v->beginLogin(open));
        open.setOnClickListener(v->{ if(verificationUrl!=null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl))); });
        start.setOnClickListener(v->startPolling());
        stop.setOnClickListener(v->{ stopService(new Intent(this,PollService.class)); status.setText("Canlı rapor durduruldu"); });

        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);
        try { if(AuthStore.loadRefreshToken(this)!=null) code.setText("Oturum hazır ✓"); } catch(Exception ignored){}
    }

    private void beginLogin(Button open){
        status.setText("Microsoft cihaz kodu alınıyor…");
        io.execute(()->{
            try{
                JSONObject j=MicrosoftApi.startDeviceCode();
                if(!j.has("device_code")) throw new Exception(j.optString("error_description",j.toString()));
                String dc=j.getString("device_code"); String uc=j.getString("user_code");
                verificationUrl=j.optString("verification_uri","https://microsoft.com/devicelogin");
                int interval=Math.max(5,j.optInt("interval",5));
                runOnUiThread(()->{ code.setText("Kod: "+uc); open.setEnabled(true); status.setText("Giriş sayfasını açın ve kodu girin. MFA tamamlanınca uygulama otomatik devam eder."); });
                pollLogin(dc,interval);
            }catch(Exception e){runOnUiThread(()->status.setText("Giriş başlatılamadı: "+e.getMessage()));}
        });
    }

    private void pollLogin(String dc,int interval) throws Exception{
        long deadline=System.currentTimeMillis()+15*60_000L;
        while(System.currentTimeMillis()<deadline){
            Thread.sleep(interval*1000L);
            JSONObject j=MicrosoftApi.pollDeviceCode(dc);
            if(j.has("access_token")){
                String rt=j.optString("refresh_token",null); if(rt==null||rt.isEmpty())throw new Exception("Refresh token dönmedi");
                AuthStore.saveRefreshToken(this,rt);
                runOnUiThread(()->{code.setText("Oturum hazır ✓"); status.setText("Microsoft girişi tamamlandı"); startPolling();});
                return;
            }
            String err=j.optString("error","");
            if("authorization_pending".equals(err)||"slow_down".equals(err))continue;
            throw new Exception(j.optString("error_description",j.toString()));
        }
        throw new Exception("Giriş süresi doldu");
    }

    private void startPolling(){
        try{ if(AuthStore.loadRefreshToken(this)==null){status.setText("Önce Microsoft hesabıyla giriş yapın");return;} }catch(Exception e){status.setText(e.getMessage());return;}
        Intent i=new Intent(this,PollService.class);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(i); else startService(i);
        status.setText("60 saniyelik canlı rapor başlatıldı");
    }

    @Override protected void onStart(){super.onStart(); IntentFilter f=new IntentFilter(PollService.ACTION_STATUS); if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver,f);}
    @Override protected void onStop(){super.onStop(); try{unregisterReceiver(receiver);}catch(Exception ignored){}}
}
