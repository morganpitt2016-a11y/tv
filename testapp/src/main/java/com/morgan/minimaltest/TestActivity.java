package com.morgan.minimaltest;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.TextView;

public class TestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("It installed. Minimal test app works.");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(32f);
        tv.setBackgroundColor(Color.BLACK);
        setContentView(tv);
    }
}
