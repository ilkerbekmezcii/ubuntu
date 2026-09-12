package com.ilker.microsoftreport;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class EnvConfig {
    public static final int MAX_BYTES = 64 * 1024;
    public final String tenantId;
    public final String clientId;
    public final String clientSecret;
    public EnvConfig(String tenantId, String clientId, String clientSecret) {
        this.tenantId = required("TENANT_ID", tenantId);
        this.clientId = required("CLIENT_ID", clientId);
        this.clientSecret = required("CLIENT_SECRET", clientSecret);
    }
    public static EnvConfig parse(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int total = 0;
        for (int n; (n = in.read(buf)) >= 0;) { total += n; if (total > MAX_BYTES) throw new IllegalArgumentException("ENV dosyası 64 KB sınırını aşıyor"); out.write(buf, 0, n); }
        String text = out.toString(StandardCharsets.UTF_8.name());
        Map<String,String> values = new LinkedHashMap<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim(); if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring(7).trim();
            int eq = line.indexOf('='); if (eq <= 0) continue;
            String key = line.substring(0,eq).trim().toUpperCase(Locale.ROOT);
            values.put(key, unquote(line.substring(eq+1).trim()));
        }
        return new EnvConfig(first(values,"TENANT_ID","AZURE_TENANT_ID","MICROSOFT_TENANT_ID"), first(values,"CLIENT_ID","AZURE_CLIENT_ID","MICROSOFT_CLIENT_ID"), first(values,"CLIENT_SECRET","AZURE_CLIENT_SECRET","MICROSOFT_CLIENT_SECRET","MS_CLIENT_SECRET","PARTNER_CENTER_CLIENT_SECRET"));
    }
    private static String first(Map<String,String> map,String...keys){ for(String key:keys){String v=map.get(key); if(v!=null&&!v.trim().isEmpty()) return v.trim();} return ""; }
    private static String unquote(String v){ if(v.length()>=2){char a=v.charAt(0),b=v.charAt(v.length()-1); if((a=='"'&&b=='"')||(a=='\''&&b=='\'')) return v.substring(1,v.length()-1);} return v; }
    private static String required(String n,String v){String x=v==null?"":v.trim(); if(x.isEmpty()) throw new IllegalArgumentException(n+" eksik"); return x;}
}
