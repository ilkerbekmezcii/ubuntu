package com.spg.storesales;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

public class CompactMainActivity extends MainActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        compact(getWindow().getDecorView());
    }

    private void compact(View view) {
        if (view instanceof Button) {
            Button button = (Button) view;
            button.setTextSize(13);
            button.setMinHeight(dpLocal(40));
            ViewGroup.LayoutParams lp = button.getLayoutParams();
            if (lp != null) {
                lp.height = dpLocal(42);
                button.setLayoutParams(lp);
            }
            button.setPadding(dpLocal(12), 0, dpLocal(12), 0);
        } else if (view instanceof TextView) {
            TextView text = (TextView) view;
            if ("Microsoft Store Sales".contentEquals(text.getText())) {
                text.setText("Microsoft Rapor");
                text.setTextSize(24);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) compact(group.getChildAt(i));
        }
    }

    private int dpLocal(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
