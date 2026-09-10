package com.spg.storesales;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ReportStore {
    private static final String PREFS="microsoft_rapor_cache_v3";
    private static final String REPORT="report", APPS="apps", APPS_TS="apps_ts", MONITOR="monitor";
    private static final String MONITOR_DAY="monitor_day", MONITOR_BASELINE="monitor_baseline";
    private static final String SYNC_DAY="sync_day", SYNC_DAY_VALUES="sync_day_values", APP_MONTH_VALUES="app_month_values";
    private static final String MONTH_KEY="month_key", FULL_SYNC_AT="full_sync_at";
    private final SharedPreferences p;

    public ReportStore(Context c){p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}

    public Report loadReport(){try{return Report.fromJson(new JSONObject(p.getString(REPORT,"{}")));}catch(Exception e){return new Report();}}
    public void saveReport(Report r){p.edit().putString(REPORT,r.toJson().toString()).apply();}

    public JSONArray loadApps(long maxAgeMs){
        try{long ts=p.getLong(APPS_TS,0);if(ts<=0||System.currentTimeMillis()-ts>maxAgeMs)return null;return new JSONArray(p.getString(APPS,"[]"));}
        catch(Exception e){return null;}
    }
    public void saveApps(JSONArray a){p.edit().putString(APPS,a.toString()).putLong(APPS_TS,System.currentTimeMillis()).apply();}

    public boolean monitorEnabled(){return p.getBoolean(MONITOR,false);}
    public void setMonitorEnabled(boolean v){p.edit().putBoolean(MONITOR,v).apply();}

    public String monitorDay(){return p.getString(MONITOR_DAY,"");}
    public JSONObject monitorBaseline(){return json(MONITOR_BASELINE);}
    public void saveMonitorBaseline(String day,JSONObject b){p.edit().putString(MONITOR_DAY,day).putString(MONITOR_BASELINE,b.toString()).apply();}

    public String syncDay(){return p.getString(SYNC_DAY,"");}
    public JSONObject syncDayValues(){return json(SYNC_DAY_VALUES);}
    public JSONObject appMonthValues(){return json(APP_MONTH_VALUES);}
    public String monthKey(){return p.getString(MONTH_KEY,"");}
    public long fullSyncAt(){return p.getLong(FULL_SYNC_AT,0L);}

    public void saveFullSyncState(String monthKey,String day,JSONObject dayValues,JSONObject appMonthValues,long when){
        p.edit().putString(MONTH_KEY,monthKey).putString(SYNC_DAY,day).putString(SYNC_DAY_VALUES,dayValues.toString())
                .putString(APP_MONTH_VALUES,appMonthValues.toString()).putLong(FULL_SYNC_AT,when).apply();
    }
    public void saveFastSyncState(String day,JSONObject dayValues,JSONObject appMonthValues){
        p.edit().putString(SYNC_DAY,day).putString(SYNC_DAY_VALUES,dayValues.toString())
                .putString(APP_MONTH_VALUES,appMonthValues.toString()).apply();
    }

    private JSONObject json(String key){try{return new JSONObject(p.getString(key,"{}"));}catch(Exception e){return new JSONObject();}}

    private static double finite(double v){return Double.isNaN(v)||Double.isInfinite(v)?0d:v;}

    public static final class Report {
        public double dailySales,monthlySales,dailyLeaderSales,monthlyLeaderSales;
        public long dailyAcq,monthlyAcq,dailyLeaderAcq,monthlyLeaderAcq,updatedAt;
        public int appCount;
        public String dailyLeader="—",monthlyLeader="—";

        public JSONObject toJson(){
            JSONObject j=new JSONObject();
            try{
                j.put("dailySales",finite(dailySales));j.put("monthlySales",finite(monthlySales));
                j.put("dailyAcq",Math.max(0,dailyAcq));j.put("monthlyAcq",Math.max(0,monthlyAcq));
                j.put("dailyLeader",safeName(dailyLeader));j.put("monthlyLeader",safeName(monthlyLeader));
                j.put("dailyLeaderSales",finite(dailyLeaderSales));j.put("monthlyLeaderSales",finite(monthlyLeaderSales));
                j.put("dailyLeaderAcq",Math.max(0,dailyLeaderAcq));j.put("monthlyLeaderAcq",Math.max(0,monthlyLeaderAcq));
                j.put("appCount",Math.max(0,appCount));j.put("updatedAt",Math.max(0,updatedAt));
            }catch(Exception ignored){}
            return j;
        }

        static Report fromJson(JSONObject j){
            Report r=new Report();
            r.dailySales=finite(j.optDouble("dailySales",0d));r.monthlySales=finite(j.optDouble("monthlySales",0d));
            r.dailyAcq=Math.max(0,j.optLong("dailyAcq",0));r.monthlyAcq=Math.max(0,j.optLong("monthlyAcq",0));
            r.dailyLeader=safeName(j.optString("dailyLeader","—"));r.monthlyLeader=safeName(j.optString("monthlyLeader","—"));
            r.dailyLeaderSales=finite(j.optDouble("dailyLeaderSales",0d));r.monthlyLeaderSales=finite(j.optDouble("monthlyLeaderSales",0d));
            r.dailyLeaderAcq=Math.max(0,j.optLong("dailyLeaderAcq",0));r.monthlyLeaderAcq=Math.max(0,j.optLong("monthlyLeaderAcq",0));
            r.appCount=Math.max(0,j.optInt("appCount",0));r.updatedAt=Math.max(0,j.optLong("updatedAt",0));
            return r;
        }
        private static String safeName(String s){return s==null||s.trim().isEmpty()||"null".equalsIgnoreCase(s)||"nan".equalsIgnoreCase(s)?"—":s;}
    }
}
