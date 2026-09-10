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
    private static final String CACHE_SCHEMA="store-analytics-v2";
    private static final Object REFRESH_LOCK=new Object();
    private final CredentialStore credentials;
    private final ReportStore store;

    public ReportRepository(CredentialStore credentials,ReportStore store){this.credentials=credentials;this.store=store;}

    private static String currentMonthKey(){Calendar c=Calendar.getInstance();return c.get(Calendar.YEAR)+"-"+(c.get(Calendar.MONTH)+1)+":"+CACHE_SCHEMA;}

    public boolean needsFullSync(){
        return !currentMonthKey().equals(store.monthKey()) || store.appMonthValues().length()==0 || System.currentTimeMillis()-store.fullSyncAt()>=FULL_VERIFY_MS;
    }

    public ReportStore.Report refresh(Progress progress) throws Exception {
        synchronized(REFRESH_LOCK){return needsFullSync()?fullAnalytics(progress):fastAnalytics(progress);}
    }

    public ReportStore.Report refreshLegacyGross(Progress progress) throws Exception { return refresh(progress); }

    private JSONArray apps(PartnerCenterClient api) throws Exception {
        JSONArray a=store.loadApps(APP_CACHE_MS);
        if(a==null){a=api.applications();store.saveApps(a);}
        return a;
    }

    private ReportStore.Report fullAnalytics(Progress progress) throws Exception {
        PartnerCenterClient api=new PartnerCenterClient(credentials.load());JSONArray apps=apps(api);
        Calendar cal=Calendar.getInstance();String isoToday=iso(new Date());String apiToday=apiDate(new Date());
        Calendar first=(Calendar)cal.clone();first.set(Calendar.DAY_OF_MONTH,1);String apiStart=apiDate(first.getTime());
        JSONObject oldMonth=store.appMonthValues(),oldDay=isoToday.equals(store.syncDay())?store.syncDayValues():new JSONObject();
        JSONObject monthValues=new JSONObject(),dayValues=new JSONObject();
        for(int i=0;i<apps.length();i++){
            JSONObject app=apps.optJSONObject(i);if(app==null)continue;String id=appId(app);if(id.isEmpty())continue;String name=appName(app,id);
            PartnerCenterClient.Totals t=api.acquisitions(id,apiStart,apiToday,isoToday);
            JSONObject prevM=oldMonth.optJSONObject(id),prevD=oldDay.optJSONObject(id);
            double monthNet=prevM==null?0d:finite(prevM.optDouble("net",0d));double dayNet=prevD==null?0d:finite(prevD.optDouble("net",0d));
            monthValues.put(id,value(name,t.sales,monthNet,t.acq));dayValues.put(id,value(name,t.todaySales,dayNet,t.todayAcq));
            if(progress!=null)progress.onProgress(i+1,apps.length(),"Store Analytics aylık");
        }
        ReportStore.Report r=rebuild(dayValues,monthValues,apps.length());r.updatedAt=System.currentTimeMillis();
        store.saveReport(r);store.saveFullSyncState(currentMonthKey(),isoToday,dayValues,monthValues,r.updatedAt);return r;
    }

    private ReportStore.Report fastAnalytics(Progress progress) throws Exception {
        PartnerCenterClient api=new PartnerCenterClient(credentials.load());JSONArray apps=apps(api);
        String isoToday=iso(new Date()),apiToday=apiDate(new Date());JSONObject oldDay=isoToday.equals(store.syncDay())?store.syncDayValues():new JSONObject();JSONObject monthValues=store.appMonthValues();JSONObject newDay=new JSONObject();
        for(int i=0;i<apps.length();i++){
            JSONObject app=apps.optJSONObject(i);if(app==null)continue;String id=appId(app);if(id.isEmpty())continue;String name=appName(app,id);
            PartnerCenterClient.Totals t=api.acquisitions(id,apiToday,apiToday,isoToday);double nowGross=finite(t.todaySales);long nowQty=Math.max(0,t.todayAcq);
            JSONObject prev=oldDay.optJSONObject(id);double prevGross=prev==null?0d:finite(prev.optDouble("sales",0d));long prevQty=prev==null?0L:Math.max(0,prev.optLong("acq",0L));double dayNet=prev==null?0d:finite(prev.optDouble("net",0d));
            JSONObject m=monthValues.optJSONObject(id);if(m==null)m=value(name,0d,0d,0L);double monthNet=finite(m.optDouble("net",0d));
            m.put("name",name).put("sales",Math.max(0d,finite(m.optDouble("sales",0d))+nowGross-prevGross)).put("net",monthNet).put("acq",Math.max(0L,m.optLong("acq",0L)+nowQty-prevQty));monthValues.put(id,m);
            newDay.put(id,value(name,nowGross,dayNet,nowQty));if(progress!=null)progress.onProgress(i+1,apps.length(),"Store Analytics canlı");
        }
        ReportStore.Report r=rebuild(newDay,monthValues,apps.length());r.updatedAt=System.currentTimeMillis();store.saveReport(r);store.saveFastSyncState(isoToday,newDay,monthValues);return r;
    }

    private static JSONObject value(String name,double gross,double net,long qty) throws Exception {return new JSONObject().put("name",name).put("sales",finite(gross)).put("net",finite(net)).put("acq",Math.max(0,qty));}
    private static String appId(JSONObject a){return first(a.optString("id"),a.optString("applicationId"));}
    private static String appName(JSONObject a,String id){return first(a.optString("primaryName"),a.optString("name"),a.optString("applicationName"),id);}
    private static String iso(Date d){return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(d);}
    private static String apiDate(Date d){return new SimpleDateFormat("M/d/yyyy",Locale.US).format(d);}

    private static ReportStore.Report rebuild(JSONObject dayValues,JSONObject monthValues,int appCount){
        ReportStore.Report r=new ReportStore.Report();r.appCount=Math.max(0,appCount);double bestDay=-1,bestMonth=-1;
        java.util.Iterator<String> dk=dayValues.keys();while(dk.hasNext()){String id=dk.next();JSONObject d=dayValues.optJSONObject(id);if(d==null)continue;double gross=finite(d.optDouble("sales",0d)),net=finite(d.optDouble("net",0d));long a=Math.max(0,d.optLong("acq",0));String n=safeName(d.optString("name"),id);r.dailySales+=gross;r.dailyNet+=net;r.dailyAcq+=a;if(gross>bestDay){bestDay=gross;r.dailyLeader=n;r.dailyLeaderSales=gross;r.dailyLeaderNet=net;r.dailyLeaderAcq=a;}}
        java.util.Iterator<String> mk=monthValues.keys();while(mk.hasNext()){String id=mk.next();JSONObject m=monthValues.optJSONObject(id);if(m==null)continue;double gross=finite(m.optDouble("sales",0d)),net=finite(m.optDouble("net",0d));long a=Math.max(0,m.optLong("acq",0));String n=safeName(m.optString("name"),id);r.monthlySales+=gross;r.monthlyNet+=net;r.monthlyAcq+=a;if(gross>bestMonth){bestMonth=gross;r.monthlyLeader=n;r.monthlyLeaderSales=gross;r.monthlyLeaderNet=net;r.monthlyLeaderAcq=a;}}
        r.dailySales=finite(r.dailySales);r.dailyNet=finite(r.dailyNet);r.monthlySales=finite(r.monthlySales);r.monthlyNet=finite(r.monthlyNet);return r;
    }

    private static String safeName(String n,String id){return n==null||n.trim().isEmpty()?id:n;}
    private static double finite(double v){return Double.isNaN(v)||Double.isInfinite(v)?0d:v;}
    private static String first(String...v){for(String s:v)if(s!=null&&!s.trim().isEmpty())return s;return "";}
}
