package com.spg.storesales;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

public final class ReportRepository {
    public interface Progress { void onProgress(int done,int total,String phase); }
    private static final long APP_CACHE_MS=12L*60L*60L*1000L;
    private static final long FULL_VERIFY_MS=6L*60L*60L*1000L;
    private static final String CACHE_SCHEMA="txn-v1";
    private static final Object REFRESH_LOCK=new Object();
    private final CredentialStore credentials;
    private final ReportStore store;

    public ReportRepository(CredentialStore credentials,ReportStore store){this.credentials=credentials;this.store=store;}

    private static String currentMonthKey(){Calendar c=Calendar.getInstance();return c.get(Calendar.YEAR)+"-"+(c.get(Calendar.MONTH)+1)+":"+CACHE_SCHEMA;}

    public boolean needsFullSync(){
        String monthKey=currentMonthKey();
        return !monthKey.equals(store.monthKey())||store.appMonthValues().length()==0||System.currentTimeMillis()-store.fullSyncAt()>=FULL_VERIFY_MS;
    }

    public ReportStore.Report refresh(Progress progress) throws Exception {
        synchronized(REFRESH_LOCK){String monthKey=currentMonthKey();return needsFullSync()?fullEarnings(monthKey,progress):fastEarnings(progress);}
    }

    public ReportStore.Report refreshLegacyGross(Progress progress) throws Exception {
        synchronized(REFRESH_LOCK){return legacy(progress);}
    }

    private ReportStore.Report fullEarnings(String monthKey,Progress progress) throws Exception {
        Calendar c=Calendar.getInstance();String today=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());String start=String.format(Locale.US,"%04d-%02d-01",c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1);
        if(progress!=null)progress.onProgress(0,1,"Earnings aylık sorgu");EarningsClient.Result result=new EarningsClient(credentials.load()).query(start,today);
        JSONObject monthValues=new JSONObject(),dayValues=new JSONObject();
        for(Map.Entry<String,EarningsClient.Product> e:result.products.entrySet()){
            EarningsClient.Product p=e.getValue();String name=safeName(p.name,p.id);
            monthValues.put(p.id,new JSONObject().put("name",name).put("sales",finite(p.gross)).put("net",finite(p.net)).put("acq",Math.max(0,p.quantity)));
            dayValues.put(p.id,new JSONObject().put("name",name).put("sales",finite(p.todayGross)).put("net",finite(p.todayNet)).put("acq",Math.max(0,p.todayQuantity)));
        }
        ReportStore.Report r=rebuild(dayValues,monthValues,result.products.size());r.updatedAt=System.currentTimeMillis();store.saveReport(r);store.saveFullSyncState(monthKey,today,dayValues,monthValues,r.updatedAt);
        if(progress!=null)progress.onProgress(1,1,"Earnings aylık sorgu");return r;
    }

    private ReportStore.Report fastEarnings(Progress progress) throws Exception {
        String today=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());if(progress!=null)progress.onProgress(0,1,"Earnings canlı sorgu");
        EarningsClient.Result result=new EarningsClient(credentials.load()).query(today,today);JSONObject oldDay=today.equals(store.syncDay())?store.syncDayValues():new JSONObject();JSONObject monthValues=store.appMonthValues();JSONObject newDay=new JSONObject();
        for(Map.Entry<String,EarningsClient.Product> e:result.products.entrySet()){
            EarningsClient.Product p=e.getValue();String id=p.id;String name=safeName(p.name,id);double nowGross=finite(p.gross),nowNet=finite(p.net);long nowQty=Math.max(0,p.quantity);
            JSONObject prev=oldDay.optJSONObject(id);double prevGross=prev==null?0d:finite(prev.optDouble("sales",0d));double prevNet=prev==null?0d:finite(prev.optDouble("net",0d));long prevQty=prev==null?0L:Math.max(0,prev.optLong("acq",0L));
            JSONObject month=monthValues.optJSONObject(id);if(month==null)month=new JSONObject().put("name",name).put("sales",0d).put("net",0d).put("acq",0L);
            month.put("name",name).put("sales",Math.max(0d,finite(month.optDouble("sales",0d))+nowGross-prevGross)).put("net",Math.max(0d,finite(month.optDouble("net",0d))+nowNet-prevNet)).put("acq",Math.max(0L,month.optLong("acq",0L)+nowQty-prevQty));
            monthValues.put(id,month);newDay.put(id,new JSONObject().put("name",name).put("sales",nowGross).put("net",nowNet).put("acq",nowQty));
        }
        java.util.Iterator<String> oldKeys=oldDay.keys();while(oldKeys.hasNext()){
            String id=oldKeys.next();if(newDay.has(id))continue;JSONObject prev=oldDay.optJSONObject(id),month=monthValues.optJSONObject(id);if(prev==null||month==null)continue;
            month.put("sales",Math.max(0d,finite(month.optDouble("sales",0d))-finite(prev.optDouble("sales",0d)))).put("net",Math.max(0d,finite(month.optDouble("net",0d))-finite(prev.optDouble("net",0d)))).put("acq",Math.max(0L,month.optLong("acq",0L)-Math.max(0L,prev.optLong("acq",0L))));monthValues.put(id,month);
        }
        ReportStore.Report r=rebuild(newDay,monthValues,monthValues.length());r.updatedAt=System.currentTimeMillis();store.saveReport(r);store.saveFastSyncState(today,newDay,monthValues);if(progress!=null)progress.onProgress(1,1,"Earnings canlı sorgu");return r;
    }

    private ReportStore.Report legacy(Progress progress) throws Exception {
        PartnerCenterClient api=new PartnerCenterClient(credentials.load());JSONArray apps=store.loadApps(APP_CACHE_MS);if(apps==null){apps=api.applications();store.saveApps(apps);}
        Calendar cal=Calendar.getInstance();String monthKey=cal.get(Calendar.YEAR)+"-"+(cal.get(Calendar.MONTH)+1);String isoToday=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());String apiToday=new SimpleDateFormat("M/d/yyyy",Locale.US).format(new Date());
        boolean sameMonth=monthKey.equals(store.monthKey());JSONObject monthValues=sameMonth?store.appMonthValues():new JSONObject();JSONObject oldDay=isoToday.equals(store.syncDay())?store.syncDayValues():new JSONObject();JSONObject newDay=new JSONObject();
        for(int i=0;i<apps.length();i++){
            JSONObject app=apps.optJSONObject(i);if(app==null)continue;String id=first(app.optString("id"),app.optString("applicationId"));if(id.isEmpty())continue;String name=first(app.optString("primaryName"),app.optString("name"),app.optString("applicationName"),id);
            PartnerCenterClient.Totals t=api.acquisitions(id,apiToday,apiToday,isoToday);double nowGross=finite(t.todaySales);long nowQty=Math.max(0,t.todayAcq);JSONObject prev=oldDay.optJSONObject(id);double oldGross=prev==null?0d:finite(prev.optDouble("sales",0d));long oldQty=prev==null?0L:Math.max(0,prev.optLong("acq",0L));double preservedDayNet=prev==null?0d:finite(prev.optDouble("net",0d));
            JSONObject m=monthValues.optJSONObject(id);if(m==null)m=new JSONObject().put("name",name).put("sales",0d).put("net",0d).put("acq",0L);m.put("name",name).put("sales",Math.max(0d,finite(m.optDouble("sales",0d))+nowGross-oldGross)).put("acq",Math.max(0L,m.optLong("acq",0L)+nowQty-oldQty));monthValues.put(id,m);
            newDay.put(id,new JSONObject().put("name",name).put("sales",nowGross).put("net",preservedDayNet).put("acq",nowQty));if(progress!=null)progress.onProgress(i+1,apps.length(),"Acquisition fallback");
        }
        ReportStore.Report r=rebuild(newDay,monthValues,apps.length());r.updatedAt=System.currentTimeMillis();store.saveReport(r);store.saveFastSyncState(isoToday,newDay,monthValues);return r;
    }

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
