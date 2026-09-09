package com.spg.storesales;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences(SalesMonitorService.PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(SalesMonitorService.PREF_MONITOR, false) || !prefs.contains(SalesMonitorService.PREF_ENV)) return;
        Intent service = new Intent(context, SalesMonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
        else context.startService(service);
    }
}
