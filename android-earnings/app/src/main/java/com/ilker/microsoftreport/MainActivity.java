package com.ilker.microsoftreport;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private TextView status,report,deviceCode;
    private EditText clientId,tenantId;
    private String verificationUrl;

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            String value=i.getStringExtra("status");
            if(value!=null){status.setText("Canlı mod aktif");report.setText(value);}
        }
    };

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        buildUi();
        loadLocalState();
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);
    }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(42,54,42,48);
        TextView title=new TextView(this); title.setText("Microsoft Earnings"); title.setTextSize(28); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD); root.addView(title);
        TextView sub=new TextView(this); sub.setText("Partner Center • OAuth/MFA • cihaz içi token yenileme"); sub.setTextSize(15); sub.setPadding(0,8,0,28); root.addView(sub);

        tenantId=new EditText(this); tenantId.setHint("Tenant ID"); tenantId.setSingleLine(true); tenantId.setInputType(InputType.TYPE_CLASS_TEXT); root.addView(tenantId);
        clientId=new EditText(this); clientId.setHint("Client ID"); clientId.setSingleLine(true); clientId.setInputType(InputType.TYPE_CLASS_TEXT); root.addView(clientId);
        Button save=new Button(this); save.setText("OAuth ayarlarını cihazda kaydet"); root.addView(save);

        deviceCode=new TextView(this); deviceCode.setText("Henüz oturum açılmadı"); deviceCode.setTextSize(18); deviceCode.setTextIsSelectable(true); deviceCode.setPadding(0,18,0,12); root.addView(deviceCode);
        Button login=new Button(this); login.setText("Microsoft ile giriş / MFA"); root.addView(login);
        Button open=new Button(this); open.setText("Microsoft giriş sayfasını aç"); open.setEnabled(false); root.addView(open);

        Button today=new Button(this); today.setText("Bugün earnings durumunu yenile"); root.addView(today);
        Button month=new Button(this); month.setText("Bu ay earnings durumunu yenile"); root.addView(month);
        Button start=new Button(this); start.setText("60 sn canlı modu başlat"); root.addView(start);
        Button stop=new Button(this); stop.setText("Canlı modu durdur"); root.addView(stop);
        Button logout=new Button(this); logout.setText("Oturumu temizle"); root.addView(logout);

        status=new TextView(this); status.setText("Durum: hazır"); status.setTextSize(15); status.setPadding(0,24,0,10); root.addView(status);
        report=new TextView(this); report.setText("Henüz veri yok."); report.setTextSize(14); report.setTextIsSelectable(true); root.addView(report);
        ScrollView scroll=new ScrollView(this); scroll.addView(root); setContentView(scroll);

        save.setOnClickListener(v->saveConfig());
        login.setOnClickListener(v->{ if(saveConfig()) beginLogin(open); });
        open.setOnClickListener(v->{ if(verificationUrl!=null) startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(verificationUrl))); });
        today.setOnClickListener(v->{ if(saveConfig()) loadReport(false); });
        month.setOnClickListener(v->{ if(saveConfig()) loadReport(true); });
        start.setOnClickListener(v->{ if(saveConfig()) startPolling(); });
        stop.setOnClickListener(v->{ stopService(new Intent(this,PollService.class)); status.setText("Canlı mod durduruldu"); });
        logout.setOnClickListener(v->{ AuthStore.clearSession(this); deviceCode.setText("Oturum temizlendi"); status.setText("Microsoft oturumu temizlendi"); });
    }

    private void loadLocalState(){
        try{
            String t=AuthStore.load(this,AuthStore.TENANT_ID); if(t!=null)tenantId.setText(t);
            String c=AuthStore.load(this,AuthStore.CLIENT_ID); if(c!=null)clientId.setText(c);
            if(AuthStore.load(this,AuthStore.REFRESH_TOKEN)!=null) deviceCode.setText("Oturum hazır ✓");
            String last=AuthStore.load(this,AuthStore.LAST_REPORT); if(last!=null)report.setText(last);
        }catch(Exception e){status.setText("Yerel durum okunamadı: "+e.getMessage());}
    }

    private boolean saveConfig(){
        String t=tenantId.getText().toString().trim(); String c=clientId.getText().toString().trim();
        if(t.isEmpty()||c.isEmpty()){status.setText("Tenant ID ve Client ID gerekli");return false;}
        try{
            String oldT=AuthStore.load(this,AuthStore.TENANT_ID); String oldC=AuthStore.load(this,AuthStore.CLIENT_ID);
            if((oldT!=null&&!oldT.equals(t))||(oldC!=null&&!oldC.equals(c))) AuthStore.clearSession(this);
            AuthStore.save(this,AuthStore.TENANT_ID,t); AuthStore.save(this,AuthStore.CLIENT_ID,c);
            status.setText("OAuth ayarları cihazda şifreli kaydedildi"); return true;
        }catch(Exception e){status.setText("Ayar kaydı başarısız: "+e.getMessage());return false;}
    }

    private void beginLogin(Button open){
        status.setText("Microsoft cihaz kodu alınıyor…");
        io.execute(()->{
            try{
                JSONObject j=MicrosoftApi.startDeviceCode(this);
                if(j.optInt("_http")!=200 || !j.has("device_code")) throw new Exception(j.optString("error_description",j.toString()));
                String dc=j.getString("device_code"); String uc=j.getString("user_code");
                verificationUrl=j.optString("verification_uri","https://microsoft.com/devicelogin");
                int interval=Math.max(5,j.optInt("interval",5));
                String message=j.optString("message","Microsoft giriş sayfasında şu kodu girin: "+uc);
                runOnUiThread(()->{deviceCode.setText("Kod: "+uc);open.setEnabled(true);status.setText(message);startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(verificationUrl)));});
                pollLogin(dc,interval);
            }catch(Exception e){runOnUiThread(()->status.setText("Giriş başlatılamadı: "+e.getMessage()));}
        });
    }

    private void pollLogin(String dc,int initialInterval) throws Exception{
        int interval=initialInterval;
        long deadline=System.currentTimeMillis()+15*60_000L;
        while(System.currentTimeMillis()<deadline){
            Thread.sleep(interval*1000L);
            JSONObject j=MicrosoftApi.pollDeviceCode(this,dc);
            if(j.optInt("_http")==200 && j.has("access_token")){
                runOnUiThread(()->{deviceCode.setText("Oturum hazır ✓");status.setText("Microsoft girişi ve MFA tamamlandı");});
                return;
            }
            String err=j.optString("error","");
            if("authorization_pending".equals(err))continue;
            if("slow_down".equals(err)){interval+=5;continue;}
            throw new Exception(j.optString("error_description",j.toString()));
        }
        throw new Exception("Giriş süresi doldu");
    }

    private void loadReport(boolean month){
        status.setText("Earnings API çağrılıyor…");
        io.execute(()->{
            try{
                String text=month?MicrosoftApi.month(this):MicrosoftApi.today(this);
                runOnUiThread(()->{report.setText(text);status.setText("Earnings durumu güncellendi");});
            }catch(Exception e){runOnUiThread(()->status.setText("Earnings hatası: "+e.getMessage()));}
        });
    }

    private void startPolling(){
        try{ if(AuthStore.load(this,AuthStore.REFRESH_TOKEN)==null){status.setText("Önce Microsoft ile giriş / MFA tamamlanmalı");return;} }
        catch(Exception e){status.setText(e.getMessage());return;}
        Intent i=new Intent(this,PollService.class);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(i); else startService(i);
        status.setText("60 saniyelik canlı mod başlatıldı");
    }

    @Override protected void onStart(){
        super.onStart(); IntentFilter f=new IntentFilter(PollService.ACTION_STATUS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver,f);
    }
    @Override protected void onStop(){super.onStop();try{unregisterReceiver(receiver);}catch(Exception ignored){}}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}
