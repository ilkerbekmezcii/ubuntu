package com.spg.storesales;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class ReportRepository {
    public interface Progress { void onProgress(int done,int total,String phase); }
    private static final long APP_CACHE_MS=12L*60L*60L*1000L;
    private static final long FULL_VERIFY_MS=24L*60L*60L*1000L;
    private final CredentialStore credentials;
    private final ReportStore store;

    public ReportRepository(CredentialStore credentials,ReportStore store){this.credentials=credentials;this.store=store;}

    public ReportStore.Report refresh(Progress progress) throws Exception {
        PartnerCenterClient api=new PartnerCenterClient(credentials.load());
        JSONArray apps=store.loadApps(APP_CACHE_MS);
        if(apps==null){apps=api.applications();store.saveApps(apps);}

        Calendar cal=Calendar.getInstance();
        String monthKey=cal.get(Calendar.YEAR)+"-"+(cal.get(Calendar.MONTH)+1);
        boolean needFull=!monthKey.equals(store.monthKey()) || store.appMonthValues().length()==0 ||
                System.currentTimeMillis()-store.fullSyncAt()>=FULL_VERIFY_MS;
        return needFull?full(api,apps,monthKey,progress):fast(api,apps,progress);
    }

    private ReportStore.Report full(PartnerCenterClient api,JSONArray apps,String monthKey,Progress progress) throws Exception {
        Calendar cal=Calendar.getInstance();
        String start=(cal.get(Calendar.MONTH)+1)+"/1/"+cal.get(Calendar.YEAR);
        String apiToday=new SimpleDateFormat("M/d/yyyy",Locale.US).format(new Date());
        String isoToday=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());
        ReportStore.Report r=new ReportStore.Report();
        r.appCount=apps.length();
        JSONObject monthValues=new JSONObject(), dayValues=new JSONObject();
        double bestMonth=-1,bestDay=-1;

        for(int i=0;i<apps.length();i++){
            JSONObject app=apps.optJSONObject(i);if(app==null)continue;
            String id=first(app.optString("id"),app.optString("applicationId"));if(id.isEmpty())continue;
            String name=first(app.optString("primaryName"),app.optString("name"),app.optString("applicationName"),id);
            PartnerCenterClient.Totals t=api.acquisitions(id,start,apiToday,isoToday);
            double ms=finite(t.sales),ds=finite(t.todaySales);long ma=Math.max(0,t.acq),da=Math.max(0,t.todayAcq);
            r.monthlySales+=ms;r.monthlyAcq+=ma;r.dailySales+=ds;r.dailyAcq+=da;
            monthValues.put(id,new JSONObject().put("name",name).put("sales",ms).put("acq",ma));
            dayValues.put(id,new JSONObject().put("sales",ds).put("acq",da));
            if(ms>bestMonth){bestMonth=ms;r.monthlyLeader=name;r.monthlyLeaderSales=ms;r.monthlyLeaderAcq=ma;}
            if(ds>bestDay){bestDay=ds;r.dailyLeader=name;r.dailyLeaderSales=ds;r.dailyLeaderAcq=da;}
            if(progress!=null)progress.onProgress(i+1,apps.length(),"Tam doğrulama");
        }
        normalize(r);r.updatedAt=System.currentTimeMillis();store.saveReport(r);
        store.saveFullSyncState(monthKey,isoToday,dayValues,monthValues,r.updatedAt);
        return r;
    }

    private ReportStore.Report fast(PartnerCenterClient api,JSONArray apps,Progress progress) throws Exception {
        String isoToday=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());
        String apiToday=new SimpleDateFormat("M/d/yyyy",Locale.US).format(new Date());
        String oldDay=store.syncDay();
        JSONObject oldDayValues=store.syncDayValues();
        JSONObject monthValues=store.appMonthValues();
        JSONObject newDayValues=new JSONObject();
        ReportStore.Report r=store.loadReport();
        r.dailySales=0;r.dailyAcq=0;r.dailyLeader="—";r.dailyLeaderSales=0;r.dailyLeaderAcq=0;r.appCount=apps.length();
        double bestDay=-1;

        for(int i=0;i<apps.length();i++){
            JSONObject app=apps.optJSONObject(i);if(app==null)continue;
            String id=first(app.optString("id"),app.optString("applicationId"));if(id.isEmpty())continue;
            String name=first(app.optString("primaryName"),app.optString("name"),app.optString("applicationName"),id);
            PartnerCenterClient.Totals t=api.acquisitions(id,apiToday,apiToday,isoToday);
            double nowSales=finite(t.todaySales);long nowAcq=Math.max(0,t.todayAcq);
            newDayValues.put(id,new JSONObject().put("sales",nowSales).put("acq",nowAcq));
            r.dailySales+=nowSales;r.dailyAcq+=nowAcq;
            if(nowSales>bestDay){bestDay=nowSales;r.dailyLeader=name;r.dailyLeaderSales=nowSales;r.dailyLeaderAcq=nowAcq;}

            JSONObject old=isoToday.equals(oldDay)?oldDayValues.optJSONObject(id):null;
            double oldSales=old==null?0d:finite(old.optDouble("sales",0d));long oldAcq=old==null?0:Math.max(0,old.optLong("acq",0));
            JSONObject m=monthValues.optJSONObject(id);
            if(m==null)m=new JSONObject().put("name",name).put("sales",0d).put("acq",0L);
            double newMonthSales=Math.max(0d,finite(m.optDouble("sales",0d))+nowSales-oldSales);
            long newMonthAcq=Math.max(0,m.optLong("acq",0)+nowAcq-oldAcq);
            m.put("name",name).put("sales",newMonthSales).put("acq",newMonthAcq);monthValues.put(id,m);
            if(progress!=null)progress.onProgress(i+1,apps.length(),"Hızlı günlük kontrol");
        }

        r.monthlySales=0;r.monthlyAcq=0;r.monthlyLeader="—";r.monthlyLeaderSales=0;r.monthlyLeaderAcq=0;
        double bestMonth=-1;
        java.util.Iterator<String> keys=monthValues.keys();
        while(keys.hasNext()){
            String id=keys.next();JSONObject m=monthValues.optJSONObject(id);if(m==null)continue;
            double s=finite(m.optDouble("sales",0d));long a=Math.max(0,m.optLong("acq",0));String n=first(m.optString("name"),id);
            r.monthlySales+=s;r.monthlyAcq+=a;
            if(s>bestMonth){bestMonth=s;r.monthlyLeader=n;r.monthlyLeaderSales=s;r.monthlyLeaderAcq=a;}
        }
        normalize(r);r.updatedAt=System.currentTimeMillis();store.saveReport(r);store.saveFastSyncState(isoToday,newDayValues,monthValues);return r;
    }

    private static void normalize(ReportStore.Report r){
        r.dailySales=finite(r.dailySales);r.monthlySales=finite(r.monthlySales);r.dailyLeaderSales=finite(r.dailyLeaderSales);r.monthlyLeaderSales=finite(r.monthlyLeaderSales);
        r.dailyAcq=Math.max(0,r.dailyAcq);r.monthlyAcq=Math.max(0,r.monthlyAcq);r.dailyLeaderAcq=Math.max(0,r.dailyLeaderAcq);r.monthlyLeaderAcq=Math.max(0,r.monthlyLeaderAcq);
    }
    private static double finite(double v){return Double.isNaN(v)||Double.isInfinite(v)?0d:v;}
    private static String first(String...v){for(String s:v)if(s!=null&&!s.isEmpty())return s;return "";}
}
