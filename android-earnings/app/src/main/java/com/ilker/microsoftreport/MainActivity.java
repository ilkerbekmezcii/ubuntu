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
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_ENV_FILE=1201;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private TextView status,report,setupHint;
    private LinearLayout setupBox;

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            String value=i.getStringExtra("status");
            if(value!=null){report.setText(value);status.setText("2 dakikalık kontrol aktif");}
        }
    };

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        buildUi();
        loadLocalState();
        handleOAuthIntent(getIntent());
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);
    }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(42,54,42,48);
        TextView title=new TextView(this); title.setText("Microsoft Earnings"); title.setTextSize(28); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD); root.addView(title);
        TextView sub=new TextView(this); sub.setText("Bugün ve bu ay • USD"); sub.setTextSize(15); sub.setPadding(0,8,0,24); root.addView(sub);

        report=new TextView(this);
        report.setText("Bugün\nBrüt: --\nNet: --\n\nBu Ay\nBrüt: --\nNet: --");
        report.setTextSize(22); report.setTextIsSelectable(true); report.setPadding(0,8,0,24); root.addView(report);

        setupBox=new LinearLayout(this); setupBox.setOrientation(LinearLayout.VERTICAL);
        Button pickEnv=new Button(this); pickEnv.setText(".env dosyası seç"); setupBox.addView(pickEnv);
        setupHint=new TextView(this); setupHint.setText("Kurulum gerekli"); setupHint.setTextSize(15); setupHint.setPadding(0,12,0,10); setupBox.addView(setupHint);
        Button login=new Button(this); login.setText("Microsoft ile giriş"); setupBox.addView(login);
        root.addView(setupBox);

        status=new TextView(this); status.setText("Durum: hazır"); status.setTextSize(13); status.setPadding(0,18,0,0); root.addView(status);
        ScrollView scroll=new ScrollView(this); scroll.addView(root); setContentView(scroll);

        pickEnv.setOnClickListener(v->pickEnvFile());
        login.setOnClickListener(v->beginLogin());
    }

    private void loadLocalState(){
        try{
            String last=AuthStore.load(this,AuthStore.LAST_REPORT); if(last!=null)report.setText(last);
            boolean configured=notEmpty(AuthStore.load(this,AuthStore.TENANT_ID))&&notEmpty(AuthStore.load(this,AuthStore.CLIENT_ID));
            boolean loggedIn=notEmpty(AuthStore.load(this,AuthStore.REFRESH_TOKEN));
            if(configured&&loggedIn){
                setupBox.setVisibility(View.GONE);
                ensurePolling();
            }else{
                setupBox.setVisibility(View.VISIBLE);
                setupHint.setText(configured?"OAuth ayarları hazır. Microsoft ile giriş yapın.":"Önce .env dosyasını seçin.");
            }
        }catch(Exception e){status.setText("Yerel durum okunamadı: "+e.getMessage());}
    }

    private boolean notEmpty(String s){return s!=null&&!s.trim().isEmpty();}

    private void pickEnvFile(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try{startActivityForResult(i,PICK_ENV_FILE);}
        catch(Exception e){status.setText("Dosya seçici açılamadı: "+e.getMessage());}
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=PICK_ENV_FILE || resultCode!=RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        status.setText(".env okunuyor…");
        io.execute(()->{
            try{
                EnvValues values=readEnv(uri);
                if(values.tenantId==null||values.clientId==null)throw new Exception("TENANT_ID ve CLIENT_ID bulunamadı");
                String oldT=AuthStore.load(this,AuthStore.TENANT_ID),oldC=AuthStore.load(this,AuthStore.CLIENT_ID);
                if((oldT!=null&&!oldT.equals(values.tenantId))||(oldC!=null&&!oldC.equals(values.clientId)))AuthStore.clearSession(this);
                AuthStore.save(this,AuthStore.TENANT_ID,values.tenantId);
                AuthStore.save(this,AuthStore.CLIENT_ID,values.clientId);
                runOnUiThread(()->{
                    setupHint.setText(".env yüklendi ✓");
                    status.setText("Microsoft giriş ekranı açılıyor…");
                    beginLogin();
                });
            }catch(Exception e){runOnUiThread(()->status.setText(".env okunamadı: "+e.getMessage()));}
        });
    }

    private EnvValues readEnv(Uri uri) throws Exception{
        EnvValues out=new EnvValues();
        InputStream input=getContentResolver().openInputStream(uri);
        if(input==null)throw new Exception("Dosya açılamadı");
        try(BufferedReader r=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){
            String line;
            while((line=r.readLine())!=null){
                line=line.replace("\uFEFF","").trim();
                if(line.isEmpty()||line.startsWith("#"))continue;
                if(line.startsWith("export "))line=line.substring(7).trim();
                int eq=line.indexOf('='); if(eq<=0)continue;
                String key=line.substring(0,eq).trim();
                String value=cleanEnvValue(line.substring(eq+1).trim());
                if(value.isEmpty())continue;
                if("TENANT_ID".equals(key)||"PARTNER_CENTER_TENANT_ID".equals(key)||"MICROSOFT_TENANT_ID".equals(key))out.tenantId=value;
                if("CLIENT_ID".equals(key)||"PARTNER_CENTER_CLIENT_ID".equals(key)||"MICROSOFT_CLIENT_ID".equals(key))out.clientId=value;
            }
        }
        return out;
    }

    private String cleanEnvValue(String value){
        if(value.length()>=2){
            char first=value.charAt(0),last=value.charAt(value.length()-1);
            if((first=='\"'&&last=='\"')||(first=='\''&&last=='\''))value=value.substring(1,value.length()-1);
        }
        return value.trim();
    }

    private static class EnvValues{String tenantId;String clientId;}

    private void beginLogin(){
        try{
            if(!notEmpty(AuthStore.load(this,AuthStore.TENANT_ID))||!notEmpty(AuthStore.load(this,AuthStore.CLIENT_ID))){
                status.setText("Önce .env dosyasını seçin");
                return;
            }
        }catch(Exception e){status.setText(e.getMessage());return;}
        status.setText("Microsoft giriş ekranı hazırlanıyor…");
        io.execute(()->{
            try{
                Uri authorization=MicrosoftLogin.begin(this);
                runOnUiThread(()->{
                    status.setText("Microsoft hesabınızla giriş yapın ve MFA'yı tamamlayın");
                    startActivity(new Intent(Intent.ACTION_VIEW,authorization));
                });
            }catch(Exception e){runOnUiThread(()->status.setText("Giriş başlatılamadı: "+e.getMessage()));}
        });
    }

    private void handleOAuthIntent(Intent intent){
        if(intent==null||!MicrosoftLogin.isCallback(intent.getData()))return;
        Uri callback=intent.getData();
        status.setText("Microsoft girişi tamamlanıyor…");
        io.execute(()->{
            try{
                MicrosoftLogin.finish(this,callback);
                runOnUiThread(()->{
                    setupBox.setVisibility(View.GONE);
                    status.setText("Giriş tamamlandı • 2 dakikalık kontrol başlatıldı");
                    ensurePolling();
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    setupBox.setVisibility(View.VISIBLE);
                    status.setText("Microsoft giriş hatası: "+e.getMessage());
                });
            }
        });
    }

    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        handleOAuthIntent(intent);
    }

    private void ensurePolling(){
        Intent i=new Intent(this,PollService.class);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(i); else startService(i);
    }

    @Override protected void onStart(){
        super.onStart(); IntentFilter f=new IntentFilter(PollService.ACTION_STATUS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver,f);
    }
    @Override protected void onStop(){super.onStop();try{unregisterReceiver(receiver);}catch(Exception ignored){}}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}
