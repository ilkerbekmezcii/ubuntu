package com.spg.storesales;

import android.os.Bundle;
import android.widget.TextView;

/**
 * Clean launcher for the standalone APK. The reporting/network implementation
 * stays entirely on-device and talks directly to Microsoft Partner Center.
 * No Supabase, Vercel, relay, WebView or other backend is used.
 */
public final class MicrosoftRaporActivity extends MainActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Microsoft Rapor");
        rename(getWindow().getDecorView());
    }

    private void rename(android.view.View v) {
        if (v instanceof TextView) {
            TextView t=(TextView)v;
            if ("Microsoft Store Sales".contentEquals(t.getText())) t.setText("Microsoft Rapor");
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g=(android.view.ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) rename(g.getChildAt(i));
        }
    }
}
