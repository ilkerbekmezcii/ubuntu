package com.spg.storesales;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ReportStore {
    private static final String PREFS="microsoft_rapor_cache_v2", REPORT="report", APPS="apps", APPS_TS="apps_ts", MONITOR="monitor", BASELINE_DAY="baseline_day", BASELINE="baseline";
    private final SharedPreferences p;
    public ReportStore(Context c){p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public Report loadReport(){try{return Report.fromJson(new JSONObject(p.getString(REPORT,"{}")));}catch(Exception e){return new Report();}}
    public void saveReport(Report r){p.edit().putString(REPORT,r.toJson().toString()).apply();}
    public JSONArray loadApps(long maxAgeMs){try{long ts=p.getLong(APPS_TS,0);if(System.currentTimeMillis()-ts>maxAgeMs)return null;return new JSONArray(p.getString(APPS,"[]"));}catch(Exception e){return null;}}
    public void saveApps(JSONArray a){p.edit().putString(APPS,a.toString()).putLong(APPS_TS,System.currentTimeMillis()).apply();}
    public boolean monitorEnabled(){return p.getBoolean(MONITOR,false);} public void setMonitorEnabled(boolean v){p.edit().putBoolean(MONITOR,v).apply();}
    public String baselineDay(){return p.getString(BASELINE_DAY,"");} public JSONObject baseline(){try{return new JSONObject(p.getString(BASELINE,"{}"));}catch(Exception e){return new JSONObject();}}
    public void saveBaseline(String day,JSONObject b){p.edit().putString(BASELINE_DAY,day).putString(BASELINE,b.toString()).apply();}

    public static final class Report {
        public double dailySales,monthlySales,dailyLeaderSales,monthlyLeaderSales; public long dailyAcq,monthlyAcq,dailyLeaderAcq,monthlyLeaderAcq,updatedAt; public int appCount; public String dailyLeader="—",monthlyLeader="—";
        public JSONObject toJson(){JSONObject j=new JSONObject();try{j.put("dailySales",dailySales);j.put("monthlySales",monthlySales);j.put("dailyAcq",dailyAcq);j.put("monthlyAcq",monthlyAcq);j.put("dailyLeader",dailyLeader);j.put("monthlyLeader",monthlyLeader);j.put("dailyLeaderSales",dailyLeaderSales);j.put("monthlyLeaderSales",monthlyLeaderSales);j.put("dailyLeaderAcq",dailyLeaderAcq);j.put("monthlyLeaderAcq",monthlyLeaderAcq);j.put("appCount",appCount);j.put("updatedAt",updatedAt);}catch(Exception ignored){}return j;}
        static Report fromJson(JSONObject j){Report r=new Report();r.dailySales=j.optDouble("dailySales");r.monthlySales=j.optDouble("monthlySales");r.dailyAcq=j.optLong("dailyAcq");r.monthlyAcq=j.optLong("monthlyAcq");r.dailyLeader=j.optString("dailyLeader","—");r.monthlyLeader=j.optString("monthlyLeader","—");r.dailyLeaderSales=j.optDouble("dailyLeaderSales");r.monthlyLeaderSales=j.optDouble("monthlyLeaderSales");r.dailyLeaderAcq=j.optLong("dailyLeaderAcq");r.monthlyLeaderAcq=j.optLong("monthlyLeaderAcq");r.appCount=j.optInt("appCount");r.updatedAt=j.optLong("updatedAt");return r;}
    }
}
