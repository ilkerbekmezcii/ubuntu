package com.spg.storesales;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class ReportRepository {
    public interface Progress { void onProgress(int done, int total, ReportStore.Report partial); }
    private final CredentialStore credentials;
    private final ReportStore store;

    public ReportRepository(CredentialStore credentials, ReportStore store) {
        this.credentials = credentials;
        this.store = store;
    }

    public ReportStore.Report refresh(Progress progress) throws Exception {
        PartnerCenterClient api = new PartnerCenterClient(credentials.load());
        JSONArray apps = store.loadApps(12L * 60L * 60L * 1000L);
        if (apps == null) {
            apps = api.applications();
            store.saveApps(apps);
        }
        ReportStore.Report r = new ReportStore.Report();
        r.appCount = apps.length();
        Calendar cal = Calendar.getInstance();
        String start = (cal.get(Calendar.MONTH) + 1) + "/1/" + cal.get(Calendar.YEAR);
        String end = new SimpleDateFormat("M/d/yyyy", Locale.US).format(new Date());
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        double bestMonth = -1d, bestDay = -1d;

        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null) continue;
            String id = first(app.optString("id"), app.optString("applicationId"));
            if (id.isEmpty()) continue;
            String name = first(app.optString("primaryName"), app.optString("name"), app.optString("applicationName"), id);
            PartnerCenterClient.Totals t = api.acquisitions(id, start, end, today);
            r.monthlySales += t.sales;
            r.monthlyAcq += t.acq;
            r.dailySales += t.todaySales;
            r.dailyAcq += t.todayAcq;
            if (t.sales > bestMonth) {
                bestMonth = t.sales;
                r.monthlyLeader = name;
                r.monthlyLeaderSales = t.sales;
                r.monthlyLeaderAcq = t.acq;
            }
            if (t.todaySales > bestDay) {
                bestDay = t.todaySales;
                r.dailyLeader = name;
                r.dailyLeaderSales = t.todaySales;
                r.dailyLeaderAcq = t.todayAcq;
            }
            if (progress != null) progress.onProgress(i + 1, apps.length(), r);
        }
        r.updatedAt = System.currentTimeMillis();
        store.saveReport(r);
        return r;
    }

    private static String first(String... values) {
        for (String s : values) if (s != null && !s.isEmpty()) return s;
        return "";
    }
}
