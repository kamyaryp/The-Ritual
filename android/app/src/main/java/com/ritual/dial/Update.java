package com.ritual.dial;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * "Check for update": the repo's "app" release holds the latest build (version.json, index.html,
 * the-ritual.apk). When only the page changed, the new page is kept in the app's files and shown in
 * place of the bundled one — same address, so the records in localStorage stay where they are.
 * When the native side changed, the new app is downloaded and handed to Android, which asks the
 * owner to install it. A page downloaded for an older install of the app is dropped.
 */
final class Update {
    static final String BASE = "https://github.com/kamyaryp/The-Ritual/releases/latest/download/";
    static final String PAGE_URL = "file:///android_asset/index.html";

    interface Say { void say(String state, String info); }

    private Update() { }

    static long installedAt(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime; }
        catch (Exception e) { return 0; }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("update", Context.MODE_PRIVATE);
    }

    /** The downloaded page, when there is one for this install of the app. */
    static File page(Context c) {
        File f = new File(c.getFilesDir(), "page.html");
        if (!f.exists()) return null;
        if (prefs(c).getLong("pageFor", -1) != installedAt(c) || f.length() < 100000) {
            f.delete();
            return null;
        }
        return f;
    }

    static void check(final Activity a, final String running, final Say say) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject v = new JSONObject(new String(get(BASE + "version.json", null, null), "UTF-8"));
                    String nat = v.optString("native", ""), build = v.optString("build", "");
                    if (nat.length() > 0 && !nat.equals(BuildConfig.NATIVE_ID)) { appOrLink(a, say); return; }
                    if (build.length() == 0 || build.equals(running)) { say.say("none", ""); return; }
                    byte[] html = get(BASE + "index.html", null, null);
                    String text = new String(html, "UTF-8");
                    if (html.length < 100000 || !text.contains("APP_BUILD=\"" + build + "\"") || !text.trim().endsWith("</html>")) {
                        say.say("error", "bad page");
                        return;
                    }
                    File tmp = new File(a.getFilesDir(), "page.tmp"), f = new File(a.getFilesDir(), "page.html");
                    FileOutputStream out = new FileOutputStream(tmp);
                    try { out.write(html); out.getFD().sync(); } finally { out.close(); }
                    if (!tmp.renameTo(f)) { say.say("error", "write"); return; }
                    prefs(a).edit().putLong("pageFor", installedAt(a)).putString("pageBuild", build).apply();
                    say.say("page", build);
                } catch (Exception e) {
                    say.say("error", String.valueOf(e.getMessage()));
                }
            }
        }).start();
    }

    /** The whole app, now (when a page update didn't take). */
    static void apk(final Activity a, final Say say) {
        new Thread(new Runnable() {
            @Override
            public void run() { appOrLink(a, say); }
        }).start();
    }

    /** Install the app; if that can't be done here, open its download so the owner installs it in one tap. */
    private static void appOrLink(Activity a, Say say) {
        try { app(a, say); }
        catch (Exception e) { open(a, BASE + "the-ritual.apk"); say.say("link", String.valueOf(e.getMessage())); }
    }

    static void open(final Activity a, final String url) {
        if (url == null || !url.startsWith("https://github.com/kamyaryp/The-Ritual/")) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    a.startActivity(i);
                } catch (Exception ignored) { }
            }
        });
    }

    static String read(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally {
            in.close();
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    /** A new app: Android must allow this app to install, then the download goes to the installer. */
    private static void app(final Activity a, Say say) throws Exception {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            a.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + a.getPackageName())));
                    } catch (Exception ignored) { }
                }
            });
            say.say("allow", "");
            return;
        }
        File apk = new File(a.getCacheDir(), "update.apk");
        get(BASE + "the-ritual.apk", apk, say);
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(a.getPackageName());
        int id = pi.createSession(p);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            OutputStream out = s.openWrite("app.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            try {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            } finally {
                in.close();
                out.close();
            }
            Intent i = new Intent(a, InstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(a, id, i, flags).getIntentSender());
        } catch (Exception e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
        }
        say.say("install", "");
    }

    /** One download, following GitHub's redirect; to a file (with progress) or into memory. */
    private static byte[] get(String url, File to, Say say) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "TheRitual");
        int code = c.getResponseCode();
        if (code != 200) { c.disconnect(); throw new Exception("http " + code); }
        long len = c.getContentLengthLong();
        InputStream in = c.getInputStream();
        OutputStream out = to != null ? new FileOutputStream(to) : new ByteArrayOutputStream();
        try {
            byte[] b = new byte[65536];
            int n, last = -1;
            long got = 0;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                got += n;
                if (say != null && len > 0) {
                    int pc = (int) (got * 100 / len);
                    if (pc / 10 != last / 10) { last = pc; say.say("progress", String.valueOf(pc)); }
                }
            }
        } finally {
            in.close();
            out.close();
            c.disconnect();
        }
        return to != null ? null : ((ByteArrayOutputStream) out).toByteArray();
    }
}
