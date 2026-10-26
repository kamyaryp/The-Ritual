package com.ritual.dial;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * The whole app: one WebView showing the bundled page from assets.
 * Records live in the WebView's localStorage, on the device, with no network.
 */
public class MainActivity extends Activity {

    private WebView web;

    /** Whether the app is on screen, so a countdown it already announced isn't announced twice. */
    static volatile boolean foreground = false;

    /** What the page may ask of the phone: keep the screen on, buzz, and plan notifications. */
    public class Host {
        @JavascriptInterface
        public void vibrate(String pattern) {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) return;
                String[] parts = pattern.split(",");
                long[] wave = new long[parts.length + 1];        // [delay, on, off, on, ...]
                wave[0] = 0;
                for (int i = 0; i < parts.length; i++) wave[i + 1] = Long.parseLong(parts[i].trim());
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(wave, -1));
                else v.vibrate(wave, -1);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void setPlan(String json) {
            Notes.setPlan(getApplicationContext(), json);
        }

        @JavascriptInterface
        public void keepAwake(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.parseColor("#0d0d0c"));
        getWindow().setNavigationBarColor(Color.parseColor("#0d0d0c"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0d0d0c"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // localStorage: where the chronicle lives
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true);      // nothing may be fetched, ever
        s.setTextZoom(100);                // ignore the system font scale
        s.setMediaPlaybackRequiresUserGesture(false);  // the timer's ticks

        web.addJavascriptInterface(new Host(), "RitualHost");
        web.setWebViewClient(new WebViewClient());
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        Notes.channels(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
        web.onPause();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        web.onResume();
    }
}
