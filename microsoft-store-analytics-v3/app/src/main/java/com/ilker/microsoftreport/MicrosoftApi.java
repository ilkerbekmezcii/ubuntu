package com.ilker.microsoftreport;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MicrosoftApi {
    public static final ZoneId REPORT_ZONE = ZoneId.of("Europe/Istanbul");
    private static final String DEV_CENTER = "https://manage.devcenter.microsoft.com";
    private static final String RESOURCE = DEV_CENTER;
    private static final DateTimeFormatter API_DATE = DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US);

    private final EnvConfig config;
    private String accessToken;
    private long accessExpiresAt;

    public MicrosoftApi(EnvConfig config) { this.config = config; }

    public int validate() throws Exception {
        token();
        return fetchApplications().size();
    }

    public Models.Dashboard fetchTodayAndMonth() throws Exception {
        String bearer = token();
        Map<String, String> apps = fetchApplications();
        LocalDate today = LocalDate.now(REPORT_ZONE);
        LocalDate monthStart = today.withDayOfMonth(1);
        List<Models.AppSale> todaySales = new ArrayList<>();
        double monthGross = 0.0;
        double monthTax = 0.0;
        String freshest = "";

        for (Map.Entry<String, String> app : apps.entrySet()) {
            try {
                AcquisitionResult r = fetchAppMonth(bearer, app.getKey(), app.getValue(), monthStart, today);
                monthGross += r.monthGross;
                monthTax += r.monthTax;
                if (r.todaySale != null && r.todaySale.quantity > 0) todaySales.add(r.todaySale);
                if (!r.dataFreshness.isEmpty() && r.dataFreshness.compareTo(freshest) > 0) freshest = r.dataFreshness;
            } catch (HttpException ex) {
                if (ex.status == 404 || ex.status == 409) continue;
                throw ex;
            }
        }

        return new Models.Dashboard(today.toString(), Instant.now().toString(), freshest,
                monthGross, monthTax, todaySales);
    }

    private synchronized String token() throws Exception {
        long now = System.currentTimeMillis();
        if (accessToken != null && now + 120_000L < accessExpiresAt) return accessToken;
        String endpoint = "https://login.microsoftonline.com/" + encPath(config.tenantId) + "/oauth2/token";
        String body = "grant_type=client_credentials"
                + "&client_id=" + enc(config.clientId)
                + "&client_secret=" + enc(config.clientSecret)
                + "&resource=" + enc(RESOURCE);
        JSONObject o = new JSONObject(request("POST", endpoint, null,
                "application/x-www-form-urlencoded", body.getBytes(StandardCharsets.UTF_8)));
        String t = o.optString("access_token", "");
        if (t.isEmpty()) throw new IllegalStateException(cleanError(o));
        accessToken = t;
        accessExpiresAt = now + Math.max(300L, o.optLong("expires_in", 3600L)) * 1000L;
        return t;
    }

    private Map<String, String> fetchApplications() throws Exception {
        String bearer = token();
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        String url = DEV_CENTER + "/v1.0/my/applications?top=100";
        int guard = 0;
        while (url != null && guard++ < 100) {
            JSONObject root = new JSONObject(request("GET", url, bearer, null, null));
            JSONArray arr = root.optJSONArray("value");
            if (arr == null) arr = root.optJSONArray("Value");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject app = arr.getJSONObject(i);
                    String id = app.optString("id", app.optString("applicationId", "")).trim();
                    String name = app.optString("primaryName", app.optString("applicationName", "")).trim();
                    if (!id.isEmpty()) result.put(id, name.isEmpty() ? id : name);
                }
            }
            String next = root.optString("@nextLink", "").trim();
            url = next.isEmpty() ? null : (next.startsWith("http") ? next : DEV_CENTER + "/" + next.replaceFirst("^/+", ""));
        }
        return result;
    }

    private AcquisitionResult fetchAppMonth(String bearer, String appId, String primaryName,
                                             LocalDate start, LocalDate today) throws Exception {
        String url = DEV_CENTER + "/v1.0/my/analytics/appacquisitions"
                + "?applicationId=" + enc(appId)
                + "&startDate=" + enc(API_DATE.format(start))
                + "&endDate=" + enc(API_DATE.format(today))
                + "&aggregationLevel=day"
                + "&groupby=" + enc("date,applicationName,acquisitionType")
                + "&top=10000&skip=0";

        int todayQty = 0;
        double todayGross = 0.0, todayTax = 0.0, monthGross = 0.0, monthTax = 0.0;
        String apiName = "";
        String freshest = "";
        int guard = 0;

        while (url != null && guard++ < 20) {
            JSONObject root = new JSONObject(request("GET", url, bearer, null, null));
            String f = root.optString("DataFreshnessTimestamp", root.optString("dataFreshnessTimestamp", ""));
            if (!f.isEmpty() && f.compareTo(freshest) > 0) freshest = f;
            JSONArray arr = root.optJSONArray("Value");
            if (arr == null) arr = root.optJSONArray("value");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject row = arr.getJSONObject(i);
                    String type = row.optString("acquisitionType", "").trim();
                    double gross = finite(row.optDouble("purchasePriceUSDAmount", 0.0));
                    double tax = finite(row.optDouble("purchaseTaxUSDAmount", 0.0));
                    int qty = Math.max(0, row.optInt("acquisitionQuantity", 0));
                    boolean appSale = type.equalsIgnoreCase("Paid") || type.equalsIgnoreCase("Pre Order") || gross > 0.0;
                    if (!appSale) continue;
                    if (type.equalsIgnoreCase("Iap") || type.equalsIgnoreCase("Subscription Iap")) continue;

                    monthGross += gross;
                    monthTax += tax;
                    String rowDate = row.optString("date", "");
                    if (rowDate.startsWith(today.toString())) {
                        todayQty += qty;
                        todayGross += gross;
                        todayTax += tax;
                    }
                    String n = row.optString("applicationName", "").trim();
                    if (!n.isEmpty()) apiName = n;
                }
            }
            String next = root.optString("@nextLink", "").trim();
            url = next.isEmpty() ? null : (next.startsWith("http") ? next : DEV_CENTER + "/" + next.replaceFirst("^/+", ""));
        }

        String name = apiName.isEmpty() ? primaryName : apiName;
        if (name == null || name.trim().isEmpty()) name = appId;
        Models.AppSale todaySale = todayQty > 0
                ? new Models.AppSale(appId, name.trim(), todayQty, todayGross, todayTax) : null;
        return new AcquisitionResult(todaySale, monthGross, monthTax, freshest);
    }

    private static double finite(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? 0.0 : v; }

    private static String request(String method, String url, String bearer, String contentType, byte[] body) throws Exception {
        long fallback = 5L;
        for (int attempt = 0; attempt < 4; attempt++) {
            Response r = requestOnce(method, url, bearer, contentType, body);
            if (r.code >= 200 && r.code < 300) return r.body;
            if ((r.code == 429 || r.code >= 500) && attempt < 3) {
                long wait = r.retryAfterSeconds > 0 ? r.retryAfterSeconds : fallback;
                Thread.sleep(Math.min(60L, Math.max(1L, wait)) * 1000L);
                fallback = Math.min(60L, fallback * 2L);
                continue;
            }
            throw new HttpException(r.code, "HTTP " + r.code + " " + concise(r.body));
        }
        throw new HttpException(429, "Microsoft istek sınırı aşıldı");
    }

    private static Response requestOnce(String method, String urlText, String bearer,
                                        String contentType, byte[] body) throws Exception {
        URL url = new URL(urlText);
        requireMicrosoft(url);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "MicrosoftStoreSales5M-Android/1.0");
        if (bearer != null && !bearer.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (contentType != null) c.setRequestProperty("Content-Type", contentType);
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
        }
        int code = c.getResponseCode();
        if (code >= 300 && code < 400) {
            String location = c.getHeaderField("Location");
            c.disconnect();
            if (location == null) return new Response(code, "Microsoft yönlendirmesi boş", 0);
            URL redirected = new URL(location);
            requireMicrosoft(redirected);
            return requestOnce(method, redirected.toString(), bearer, contentType, body);
        }
        long retryAfter = parseRetryAfter(c.getHeaderField("Retry-After"));
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = read(in);
        c.disconnect();
        return new Response(code, text, retryAfter);
    }

    private static long parseRetryAfter(String value) {
        if (value == null) return 0;
        try { return Long.parseLong(value.trim()); } catch (Exception ignored) { return 0; }
    }

    private static void requireMicrosoft(URL url) {
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new SecurityException("Yalnızca HTTPS desteklenir");
        String h = url.getHost().toLowerCase(Locale.ROOT);
        boolean ok = h.equals("login.microsoftonline.com")
                || h.equals("manage.devcenter.microsoft.com")
                || h.endsWith(".microsoft.com")
                || h.endsWith(".microsoftonline.com");
        if (!ok) throw new SecurityException("Microsoft dışı adres engellendi: " + h);
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = input.read(buf)) >= 0;) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String cleanError(JSONObject o) {
        String d = o.optString("error_description", "").trim();
        if (!d.isEmpty()) return d.replaceAll("[\\r\\n]+", " ");
        String e = o.optString("error", "Microsoft token alınamadı");
        return e.isEmpty() ? "Microsoft token alınamadı" : e;
    }

    private static String concise(String s) {
        if (s == null) return "";
        String x = s.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        return x.length() > 300 ? x.substring(0, 300) : x;
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, StandardCharsets.UTF_8.name()); }
    private static String encPath(String s) throws Exception {
        String x = enc(s).replace("+", "%20");
        if (x.contains("%2F") || x.contains("%5C")) throw new IllegalArgumentException("Tenant ID geçersiz");
        return x;
    }

    private static final class AcquisitionResult {
        final Models.AppSale todaySale;
        final double monthGross, monthTax;
        final String dataFreshness;
        AcquisitionResult(Models.AppSale todaySale, double monthGross, double monthTax, String dataFreshness) {
            this.todaySale = todaySale;
            this.monthGross = monthGross;
            this.monthTax = monthTax;
            this.dataFreshness = dataFreshness == null ? "" : dataFreshness;
        }
    }

    private static final class Response {
        final int code;
        final String body;
        final long retryAfterSeconds;
        Response(int code, String body, long retryAfterSeconds) {
            this.code = code; this.body = body == null ? "" : body; this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    public static final class HttpException extends Exception {
        public final int status;
        HttpException(int status, String message) { super(message); this.status = status; }
    }
}
