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

    public JSONArray applications() throws Exception {
        JSONArray apps=store.loadApps(APP_CACHE_MS);
        if(apps==null){apps=new PartnerCenterClient(credentials.load()).applications();store.saveApps(apps);}
        return apps;
    }

    public boolean needsFullSync(){
        Calendar cal=Calendar.getInstance();
        String monthKey=cal.get(Calendar.YEAR)+"-"+(cal.get(Calendar.MONTH)+1);
        return !monthKey.equals(store.monthKey())||store.appMonthValues().length()==0||System.currentTimeMillis()-store.fullSyncAt()>=FULL_VERIFY_MS;
    }

    public ReportStore.Report refresh(Progress progress) throws Exception {
        PartnerCenterClient api=new PartnerCenterClient(credentials.load());
        JSONArray apps=applications();
        Calendar cal=Calendar.getInstance();
        String monthKey=cal.get(Calendar.YEAR)+"-"+(cal.get(Calendar.MONTH)+1);
        return needsFullSync()?full(api,apps,monthKey,progress):fast(api,apps,progress);
    }

    public ReportStore.Report refreshOne(JSONObject app,int appCount) throws Exception {
        if(app==null)return store.loadReport();
        String id=first(app.optString("id"),app.optString("applicationId"));
        if(id.isEmpty())return store.loadReport();
        String name=first(app.optString("primaryName"),app.optString("name"),app.optString("applicationName"),id);
        String isoToday=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());
        String apiToday=new SimpleDateFormat("M/d/yyyy",Locale.US).format(new Date());
        PartnerCenterClient.Totals t=new PartnerCenterClient(credentials.load()).acquisitions(id,apiToday,apiToday,isoToday);
        double nowSales=finite(t.todaySales);long nowAcq=Math.max(0,t.todayAcq);

        String oldDay=store.syncDay();
        JSONObject dayValues=isoToday.equals(oldDay)?store.syncDayValues():new JSONObject();
        JSONObject monthValues=store.appMonthValues();
        JSONObject old=isoToday.equals(oldDay)?dayValues.optJSONObject(id):null;
        double oldSales=old==null?0d:finite(old.optDouble("sales",0d));
        long oldAcq=old==null?0L:Math.max(0,old.optLong("acq",0L));
        JSONObject m=monthValues.optJSONObject(id);
        if(m==null)m=new JSONObject().put("name",name).put("sales",0d).put("acq",0L);
        double monthSales=Math.max(0d,finite(m.optDouble("sales",0d))+nowSales-oldSales);
        long monthAcq=Math.max(0L,m.optLong("acq",0L)+nowAcq-oldAcq);
        m.put("name",name).put("sales",monthSales).put("acq",monthAcq);
        monthValues.put(id,m);
        dayValues.put(id,new JSONObject().put("name",name).put("sales",nowSales).put("acq",nowAcq));
        store.saveFastSyncState(isoToday,dayValues,monthValues);
        ReportStore.Report r=rebuild(dayValues,monthValues,appCount);
        r.updatedAt=System.currentTimeMillis();store.saveReport(r);return r;
    }

    private ReportStore.Report full(PartnerCenterClient api,JSONArray apps,String monthKey,Progress progress) throws Exception {
        Calendar cal=Calendar.getInstance();
        String start=(cal.get(Calendar.MONTH)+1)+"/1/"+cal.get(Calendar.YEAR);
        String apiToday=new SimpleDateFormat("M/d/yyyy",Locale.US).format(new Date());
        String isoToday=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());
        JSONObject monthValues=new JSONObject(),dayValues=new JSONObject();
        for(int i=0;i<apps.length();i++){
            JSONObject app=apps.optJSONObject(i);if(app==null)continue;
            String id=first(app.optString("id"),app.optString("applicationId"));if(id.isEmpty())continue;
            String name=first(app.optString("primaryName"),app.optString("name"),app.optString("applicationName"),id);
            PartnerCenterClient.Totals t=api.acquisitions(id,start,apiToday,isoToday);
            double ms=finite(t.sales),ds=finite(t.todaySales);long ma=Math.max(0,t.acq),da=Math.max(0,t.todayAcq);
            monthValues.put(id,new JSONObject().put("name",name).put("sales",ms).put("acq",ma));
            dayValues.put(id,new JSONObject().put("name",name).put("sales",ds).put("acq",da));
            ReportStore.Report partial=rebuild(dayValues,monthValues,apps.length());partial.updatedAt=System.currentTimeMillis();store.saveReport(partial);
            if(progress!=null)progress.onProgress(i+1,apps.length(),"Tam doğrulama");
        }
        ReportStore.Report r=rebuild(dayValues,monthValues,apps.length());r.updatedAt=System.currentTimeMillis();store.saveReport(r);
        store.saveFullSyncState(monthKey,isoToday,dayValues,monthValues,r.updatedAt);return r;
    }

    private ReportStore.Report fast(PartnerCenterClient api,JSONArray apps,Progress progress) throws Exception {
        for(int i=0;i<apps.length();i++){
            refreshOne(apps.optJSONObject(i),apps.length());
            if(progress!=null)progress.onProgress(i+1,apps.length(),"Canlı kontrol");
        }
        return store.loadReport();
    }

    private static ReportStore.Report rebuild(JSONObject dayValues,JSONObject monthValues,int appCount){
        ReportStore.Report r=new ReportStore.Report();r.appCount=Math.max(0,appCount);double bestDay=-1,bestMonth=-1;
        java.util.Iterator<String> dk=dayValues.keys();while(dk.hasNext()){
            String id=dk.next();JSONObject d=dayValues.optJSONObject(id);if(d==null)continue;
            double s=finite(d.optDouble("sales",0d));long a=Math.max(0,d.optLong("acq",0));String n=first(d.optString("name"),id);
            r.dailySales+=s;r.dailyAcq+=a;if(s>bestDay){bestDay=s;r.dailyLeader=n;r.dailyLeaderSales=s;r.dailyLeaderAcq=a;}
        }
        java.util.Iterator<String> mk=monthValues.keys();while(mk.hasNext()){
            String id=mk.next();JSONObject m=monthValues.optJSONObject(id);if(m==null)continue;
            double s=finite(m.optDouble("sales",0d));long a=Math.max(0,m.optLong("acq",0));String n=first(m.optString("name"),id);
            r.monthlySales+=s;r.monthlyAcq+=a;if(s>bestMonth){bestMonth=s;r.monthlyLeader=n;r.monthlyLeaderSales=s;r.monthlyLeaderAcq=a;}
        }
        normalize(r);return r;
    }

    private static void normalize(ReportStore.Report r){
        r.dailySales=finite(r.dailySales);r.monthlySales=finite(r.monthlySales);r.dailyLeaderSales=finite(r.dailyLeaderSales);r.monthlyLeaderSales=finite(r.monthlyLeaderSales);
        r.dailyAcq=Math.max(0,r.dailyAcq);r.monthlyAcq=Math.max(0,r.monthlyAcq);r.dailyLeaderAcq=Math.max(0,r.dailyLeaderAcq);r.monthlyLeaderAcq=Math.max(0,r.monthlyLeaderAcq);
    }
    private static double finite(double v){return Double.isNaN(v)||Double.isInfinite(v)?0d:v;}
    private static String first(String...v){for(String s:v)if(s!=null&&!s.isEmpty())return s;return "";}
}
