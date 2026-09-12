package com.ilker.microsoftreport;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class Models {
    private Models() {}

    public static final class AppSale {
        public final String appId;
        public final String appName;
        public final int quantity;
        public final double grossUsd;
        public final double taxUsd;

        public AppSale(String appId, String appName, int quantity, double grossUsd, double taxUsd) {
            this.appId = appId;
            this.appName = appName;
            this.quantity = quantity;
            this.grossUsd = grossUsd;
            this.taxUsd = taxUsd;
        }

        public double netUsd() { return grossUsd - taxUsd; }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("appId", appId)
                    .put("appName", appName)
                    .put("quantity", quantity)
                    .put("grossUsd", grossUsd)
                    .put("taxUsd", taxUsd);
        }

        static AppSale fromJson(JSONObject o) {
            return new AppSale(
                    o.optString("appId", ""),
                    o.optString("appName", "Bilinmeyen uygulama"),
                    o.optInt("quantity", 0),
                    o.optDouble("grossUsd", 0),
                    o.optDouble("taxUsd", 0));
        }
    }

    public static final class Dashboard {
        public final String businessDate;
        public final String checkedAt;
        public final String dataFreshness;
        public final double monthGrossUsd;
        public final double monthTaxUsd;
        public final List<AppSale> sales;

        public Dashboard(String businessDate, String checkedAt, String dataFreshness,
                         double monthGrossUsd, double monthTaxUsd, List<AppSale> sales) {
            this.businessDate = businessDate;
            this.checkedAt = checkedAt;
            this.dataFreshness = dataFreshness == null ? "" : dataFreshness;
            this.monthGrossUsd = monthGrossUsd;
            this.monthTaxUsd = monthTaxUsd;
            List<AppSale> copy = new ArrayList<>(sales);
            copy.sort(Comparator.comparingDouble(AppSale::netUsd).reversed().thenComparing(a -> a.appName));
            this.sales = Collections.unmodifiableList(copy);
        }

        public int totalQuantity() { int n = 0; for (AppSale s : sales) n += s.quantity; return n; }
        public double totalGross() { double n = 0; for (AppSale s : sales) n += s.grossUsd; return n; }
        public double totalTax() { double n = 0; for (AppSale s : sales) n += s.taxUsd; return n; }
        public double totalNet() { return totalGross() - totalTax(); }
        public double monthNetUsd() { return monthGrossUsd - monthTaxUsd; }

        public String toJson() throws Exception {
            JSONArray arr = new JSONArray();
            for (AppSale sale : sales) arr.put(sale.toJson());
            return new JSONObject()
                    .put("businessDate", businessDate)
                    .put("checkedAt", checkedAt)
                    .put("dataFreshness", dataFreshness)
                    .put("monthGrossUsd", monthGrossUsd)
                    .put("monthTaxUsd", monthTaxUsd)
                    .put("sales", arr)
                    .toString();
        }

        public static Dashboard fromJson(String json) throws Exception {
            JSONObject o = new JSONObject(json);
            JSONArray arr = o.optJSONArray("sales");
            List<AppSale> sales = new ArrayList<>();
            if (arr != null) for (int i = 0; i < arr.length(); i++) sales.add(AppSale.fromJson(arr.getJSONObject(i)));
            return new Dashboard(
                    o.optString("businessDate"),
                    o.optString("checkedAt"),
                    o.optString("dataFreshness"),
                    o.optDouble("monthGrossUsd", 0),
                    o.optDouble("monthTaxUsd", 0),
                    sales);
        }
    }
}
