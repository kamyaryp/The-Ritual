package com.ritual.dial;

import android.app.Activity;
import android.content.Intent;
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

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;

import org.json.JSONObject;

import java.util.Collections;

/**
 * The whole app: one WebView showing the bundled page from assets.
 * Records live in the WebView's localStorage, on the device, with no network.
 */
public class MainActivity extends Activity {

    private WebView web;
    private static final int PICK_FOLDER = 7;
    private static final int DRIVE_AUTH = 8;

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

        /** Hand a timer (the core, or an Arurael countdown) to the phone, so it runs locked. */
        @JavascriptInterface
        public void timer(String json) {
            TimerService.apply(getApplicationContext(), json);
        }

        /** Every timer as the phone has it, for the page to mirror. */
        @JavascriptInterface
        public String timerState() {
            return TimerService.state(getApplicationContext());
        }

        @JavascriptInterface
        public void setPlan(String json) {
            Notes.setPlan(getApplicationContext(), json);
        }

        /** The backup folder: its name ("" when none is chosen), the file's text, writing it, choosing it. */
        @JavascriptInterface
        public String vaultFolder() {
            return Vault.folderName(getApplicationContext());
        }

        @JavascriptInterface
        public String vaultRead(String name) {
            return Vault.read(getApplicationContext(), name);
        }

        @JavascriptInterface
        public boolean vaultWrite(String name, String text) {
            return Vault.write(getApplicationContext(), name, text);
        }

        @JavascriptInterface
        public void vaultPick() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    try { startActivityForResult(i, PICK_FOLDER); }
                    catch (Exception e) { tellPage(false); }
                }
            });
        }

        /** Google Drive: sign in for the app's hidden folder (quietly when already allowed),
            and make one Drive request; both answer the page through a callback. */
        @JavascriptInterface
        public void driveAuth(final boolean interactive) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { driveAuthorize(interactive); }
            });
        }

        @JavascriptInterface
        public void driveFetch(final String id, final String method, final String url,
                               final String type, final String body, final String token) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String[] r = Drive.fetch(method, url, type, body, token);
                    tellJs("window.driveFetched&&window.driveFetched(" + JSONObject.quote(id) + "," + r[0] + "," + JSONObject.quote(r[1]) + ")");
                }
            }).start();
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
        Sfx.init(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    /** The folder picker came back: keep the folder and let the page sync with it. */
    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == DRIVE_AUTH) {
            try {
                AuthorizationResult r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data);
                driveAuthed(r.getAccessToken() != null, r.getAccessToken());
            } catch (Exception e) {
                driveAuthed(false, "cancelled");
            }
            return;
        }
        if (request != PICK_FOLDER) return;
        boolean ok = false;
        if (result == RESULT_OK && data != null && data.getData() != null) {
            try { Vault.choose(this, data.getData(), data.getFlags()); ok = true; }
            catch (Exception ignored) { }
        }
        tellPage(ok);
    }

    /** Ask Google for the Drive folder. Already allowed: the token comes straight back.
        Not yet: the consent screen is shown only when the owner pressed the button. */
    private void driveAuthorize(final boolean interactive) {
        try {
            AuthorizationRequest req = AuthorizationRequest.builder()
                    .setRequestedScopes(Collections.singletonList(new Scope(Drive.SCOPE)))
                    .build();
            Identity.getAuthorizationClient(this).authorize(req)
                    .addOnSuccessListener(r -> {
                        if (r.hasResolution()) {
                            if (!interactive || r.getPendingIntent() == null) { driveAuthed(false, "consent"); return; }
                            try {
                                startIntentSenderForResult(r.getPendingIntent().getIntentSender(), DRIVE_AUTH, null, 0, 0, 0);
                            } catch (Exception e) {
                                driveAuthed(false, "ui");
                            }
                        } else {
                            driveAuthed(r.getAccessToken() != null, r.getAccessToken());
                        }
                    })
                    .addOnFailureListener(e -> driveAuthed(false, String.valueOf(e.getMessage())));
        } catch (Exception e) {
            driveAuthed(false, String.valueOf(e.getMessage()));
        }
    }

    private void driveAuthed(boolean ok, String token) {
        tellJs("window.driveAuthed&&window.driveAuthed(" + ok + "," + JSONObject.quote(token == null ? "" : token) + ")");
    }

    private void tellJs(final String js) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() { if (web != null) web.evaluateJavascript(js, null); }
        });
    }

    private void tellPage(final boolean ok) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript("window.vaultPicked&&window.vaultPicked(" + ok + ")", null);
            }
        });
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
