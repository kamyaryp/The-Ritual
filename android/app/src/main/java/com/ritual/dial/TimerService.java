package com.ritual.dial;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The core routine and Arurael's countdowns, run by the phone itself so they keep
 * time - and keep sounding - with the screen locked or the app in the background.
 * Each running timer shows as a media notification, like a song: title, progress,
 * play/pause and reset, on the lock screen too.
 *
 * The page stays in charge of the record. It hands each timer over here as it
 * starts, pauses or resets it, reads the state back while it is open, and writes
 * the result into the day once a timer has run out.
 */
public class TimerService extends Service {

    /* ---------------- the timers themselves ---------------- */

    static final class Slot {
        String kind, id = "", title = "", sub = "", color = "", doneTitle = "", doneBody = "";
        int arm = -1;            // which arm of the cross is lit (0 bottom, 1 left, 2 top, 3 right)
        long deadline;           // wall-clock ms after which an unfinished timer is void; 0 = none
        boolean run, finished;
        long baseEl, startWall, total, endedAt;   // ms
        long lastSec = -1;
        boolean inBrk;

        long el(long now) { return Math.min(total, run ? baseEl + (now - startWall) : baseEl); }

        JSONObject json(long now) throws Exception {
            JSONObject o = new JSONObject();
            o.put("kind", kind); o.put("id", id); o.put("title", title); o.put("sub", sub);
            o.put("color", color); o.put("doneTitle", doneTitle); o.put("doneBody", doneBody);
            o.put("arm", arm); o.put("deadline", deadline);
            o.put("run", run); o.put("finished", finished);
            o.put("baseEl", baseEl); o.put("startWall", startWall); o.put("total", total); o.put("endedAt", endedAt);
            o.put("el", el(now));
            return o;
        }

        static Slot from(JSONObject o) {
            Slot s = new Slot();
            s.kind = o.optString("kind"); s.id = o.optString("id"); s.title = o.optString("title"); s.sub = o.optString("sub");
            s.color = o.optString("color"); s.doneTitle = o.optString("doneTitle"); s.doneBody = o.optString("doneBody");
            s.arm = o.optInt("arm", -1); s.deadline = o.optLong("deadline");
            s.run = o.optBoolean("run"); s.finished = o.optBoolean("finished");
            s.baseEl = o.optLong("baseEl"); s.startWall = o.optLong("startWall"); s.total = o.optLong("total");
            s.endedAt = o.optLong("endedAt");
            s.lastSec = s.el(System.currentTimeMillis()) / 1000;
            return s;
        }
    }

    private static final Object LOCK = new Object();
    private static final Map<String, Slot> SLOTS = new LinkedHashMap<>();
    /** Timers reset from the notification, for the page to take note of. */
    private static final Map<String, String> CLEARED = new HashMap<>();
    private static boolean restored = false;
    private static Context appCtx;
    private static volatile TimerService live;

    private static final String PREFS = "golden-path-timers";

    private static void restore(Context c) {
        if (restored) return;
        restored = true;
        try {
            JSONObject all = new JSONObject(c.getSharedPreferences(PREFS, MODE_PRIVATE).getString("slots", "{}"));
            for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                String k = it.next();
                SLOTS.put(k, Slot.from(all.getJSONObject(k)));
            }
            JSONObject cl = new JSONObject(c.getSharedPreferences(PREFS, MODE_PRIVATE).getString("cleared", "{}"));
            for (java.util.Iterator<String> it = cl.keys(); it.hasNext(); ) {
                String k = it.next();
                CLEARED.put(k, cl.getString(k));
            }
        } catch (Exception ignored) { }
    }

    private static void persist() {
        if (appCtx == null) return;
        try {
            long now = System.currentTimeMillis();
            JSONObject all = new JSONObject();
            for (Slot s : SLOTS.values()) all.put(s.kind, s.json(now));
            JSONObject cl = new JSONObject();
            for (Map.Entry<String, String> e : CLEARED.entrySet()) cl.put(e.getKey(), e.getValue());
            appCtx.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("slots", all.toString()).putString("cleared", cl.toString()).apply();
        } catch (Exception ignored) { }
    }

    /* ---------------- the page's side ---------------- */

    /** set: run or pause a timer at a given point; clear: drop it; ack: forget a reset. */
    static void apply(Context c, String json) {
        appCtx = c.getApplicationContext();
        Sfx.init(appCtx);
        synchronized (LOCK) {
            restore(appCtx);
            try {
                JSONObject o = new JSONObject(json);
                String kind = o.optString("kind");
                String op = o.optString("op", "set");
                long now = System.currentTimeMillis();
                if ("clear".equals(op)) {
                    SLOTS.remove(kind);
                } else if ("ack".equals(op)) {
                    CLEARED.remove(kind);
                } else {
                    Slot s = SLOTS.get(kind);
                    if (s == null) { s = new Slot(); s.kind = kind; SLOTS.put(kind, s); }
                    boolean wasRun = s.run && !s.finished;
                    s.id = o.optString("id", "");
                    s.title = o.optString("title", "");
                    s.sub = o.optString("sub", "");
                    s.color = o.optString("color", "");
                    s.doneTitle = o.optString("doneTitle", "");
                    s.doneBody = o.optString("doneBody", "");
                    s.arm = o.optInt("arm", -1);
                    s.deadline = o.optLong("deadline", 0);
                    s.total = o.optLong("total");
                    s.baseEl = Math.min(s.total, o.optLong("el"));
                    s.startWall = now;
                    s.run = o.optBoolean("run");
                    s.finished = false;
                    CLEARED.remove(kind);
                    if (s.run && !wasRun) started(s, now);
                }
                persist();
            } catch (Exception ignored) { }
        }
        ensure(c);
    }

    /** Every timer as it stands right now, for the page to mirror. */
    static String state(Context c) {
        synchronized (LOCK) {
            restore(c.getApplicationContext());
            try {
                long now = System.currentTimeMillis();
                JSONObject out = new JSONObject();
                for (Slot s : SLOTS.values()) out.put(s.kind, s.json(now));
                JSONObject cl = new JSONObject();
                for (Map.Entry<String, String> e : CLEARED.entrySet()) cl.put(e.getKey(), e.getValue());
                out.put("cleared", cl);
                return out.toString();
            } catch (Exception e) { return "{}"; }
        }
    }

    private static void ensure(Context c) {
        boolean active;
        synchronized (LOCK) { active = hasActive(); }
        TimerService t = live;
        if (t != null) { t.post(); return; }
        if (!active) return;
        Intent i = new Intent(c, TimerService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    private static boolean hasActive() {
        for (Slot s : SLOTS.values()) if (!s.finished) return true;
        return false;
    }

    /* ---------------- the core routine's shape and sounds ----------------
       3 types x 2 rounds x 10 holds x 6 s, with 2 s between types: 364 s. Same as the page. */

    private static final int TOTAL = 364, EX = 360, TYPE = 120, BRK = 2, HOLD = 6;

    /** {brk 0/1, type, ex (seconds held so far), left (seconds of the break left)} */
    private static double[] phase(double w) {
        for (int ty = 0; ty < 3; ty++) {
            double start = ty * (TYPE + BRK);
            if (w < start + TYPE || ty == 2) return new double[]{0, ty, Math.min(EX, ty * TYPE + Math.max(0, w - start)), 0};
            if (w < start + TYPE + BRK) return new double[]{1, ty, (ty + 1) * TYPE, start + TYPE + BRK - w};
        }
        return new double[]{0, 2, EX, 0};
    }

    private static void started(Slot s, long now) {
        double el = s.el(now) / 1000.0;
        s.lastSec = (long) Math.floor(el + 1e-6);
        if ("core".equals(s.kind)) {
            s.inBrk = phase(el)[0] == 1;
            if (s.baseEl == 0) Sfx.play("start");
        }
    }

    private static void coreSound(Slot s, long sec) {
        if (sec >= TOTAL) { Sfx.play("finish"); return; }
        double[] p = phase(sec);
        boolean was = s.inBrk;
        s.inBrk = p[0] == 1;
        if (s.inBrk) {
            if (!was) { Sfx.play("typeEnd"); Sfx.buzz(60, 40, 60); }
            else Sfx.play("beat");
            return;
        }
        if (was) { Sfx.play("go"); return; }
        long e = Math.round(p[2]);
        if (e % 60 == 0) { Sfx.play("round"); Sfx.buzz(45); return; }
        if (e % HOLD == 0) { Sfx.play("hold"); return; }
        Sfx.play("tick");
    }

    /* ---------------- the service ---------------- */

    private static final String CH = "timers";
    private static final String A_PLAY = "com.ritual.dial.T_PLAY", A_PAUSE = "com.ritual.dial.T_PAUSE", A_RESET = "com.ritual.dial.T_RESET";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Map<String, MediaSession> sessions = new HashMap<>();
    private final Map<String, String> shown = new HashMap<>();     // last text shown, per timer
    private PowerManager.WakeLock wl;
    private int fgId = 0;
    private Notification lastFg;

    private final Runnable loop = new Runnable() {
        @Override public void run() { tick(); }
    };
    private final Runnable refreshNow = new Runnable() {
        @Override public void run() { refresh(); }
    };

    void post() { h.post(refreshNow); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        appCtx = getApplicationContext();
        live = this;
        Sfx.init(appCtx);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Timers", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("The core routine and Arurael's countdowns while they run");
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoldenPath:timer");
        wl.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        // a foreground start must show its notification at once, whatever follows
        if (fgId == 0 || lastFg == null) startFg(placeholder()); else startFg(lastFg, fgId);
        synchronized (LOCK) { restore(appCtx); }
        if (in != null && in.getAction() != null) {
            String kind = in.getStringExtra("kind");
            if (kind != null) { op(kind, in.getAction()); return START_STICKY; }
        }
        refresh();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (live == this) live = null;
        h.removeCallbacksAndMessages(null);
        for (MediaSession ms : sessions.values()) { ms.setActive(false); ms.release(); }
        sessions.clear();
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    /** play / pause / reset, from the notification, the lock screen or a headset button */
    private void op(String kind, String what) {
        synchronized (LOCK) {
            Slot s = SLOTS.get(kind);
            if (s == null) return;
            long now = System.currentTimeMillis();
            if (A_PLAY.equals(what) && !s.run && !s.finished) {
                s.baseEl = s.el(now); s.startWall = now; s.run = true;
                started(s, now);
            } else if (A_PAUSE.equals(what) && s.run) {
                s.baseEl = s.el(now); s.startWall = now; s.run = false;
            } else if (A_RESET.equals(what)) {
                SLOTS.remove(kind);
                CLEARED.put(kind, s.id);
            }
            persist();
        }
        refresh();
    }

    private void tick() {
        List<Slot> ended = new ArrayList<>();
        List<Slot> changed = new ArrayList<>();
        boolean running = false, voided = false;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            for (Slot s : new ArrayList<>(SLOTS.values())) {
                if (!s.finished && s.deadline > 0 && now >= s.deadline && s.el(now) < s.total) {
                    SLOTS.remove(s.kind); CLEARED.put(s.kind, s.id); voided = true;   // its window closed: missed
                }
            }
            if (voided) persist();
            for (Slot s : SLOTS.values()) {
                if (!s.run || s.finished) continue;
                long el = s.el(now), sec = el / 1000;
                if (sec != s.lastSec) {
                    s.lastSec = sec;
                    if ("core".equals(s.kind) && sec > 0) coreSound(s, sec);
                    changed.add(s);
                }
                if (el >= s.total) {
                    s.run = false; s.finished = true; s.baseEl = s.total; s.endedAt = now - (el - s.total);
                    if (!"core".equals(s.kind)) { Sfx.play("done"); }
                    Sfx.buzz(70, 50, 70, 50, 140);
                    ended.add(s);
                } else running = true;
            }
            if (!ended.isEmpty()) persist();
        }
        for (Slot s : ended) announce(s);
        if (!ended.isEmpty() || voided) { refresh(); return; }
        for (Slot s : changed) show(s);
        if (running) h.postDelayed(loop, 40);
    }

    /** Bring notifications, sessions, the wake lock and the loop in line with the timers. */
    private void refresh() {
        h.removeCallbacks(loop);
        List<Slot> act = new ArrayList<>();
        boolean running = false;
        synchronized (LOCK) {
            for (Slot s : SLOTS.values()) if (!s.finished) { act.add(s); if (s.run) running = true; }
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        // drop what is no longer there
        for (String k : new ArrayList<>(sessions.keySet())) {
            boolean keep = false;
            for (Slot s : act) if (s.kind.equals(k)) keep = true;
            if (!keep) {
                MediaSession ms = sessions.remove(k);
                ms.setActive(false); ms.release();
                shown.remove(k);
                if (nid(k) != fgId) nm.cancel(nid(k));
            }
        }
        if (act.isEmpty()) {
            if (wl.isHeld()) wl.release();
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
            fgId = 0; lastFg = null;
            live = null;                 // the next timer starts a fresh service
            stopSelf();
            return;
        }
        // the first live timer carries the foreground; any other is posted beside it
        Slot lead = act.get(0);
        for (Slot s : act) { shown.remove(s.kind); }
        int old = fgId;
        startFg(build(lead), nid(lead.kind));
        if (old != 0 && old != fgId) nm.cancel(old);
        for (Slot s : act) show(s);
        if (running) { if (!wl.isHeld()) wl.acquire(8 * 60 * 60 * 1000L); h.post(loop); }
        else {
            if (wl.isHeld()) wl.release();
            long next = Long.MAX_VALUE, now = System.currentTimeMillis();
            for (Slot s : act) if (s.deadline > 0) next = Math.min(next, s.deadline);
            if (next != Long.MAX_VALUE) h.postDelayed(loop, Math.max(0, next - now) + 50);
        }
    }

    private static int nid(String kind) { return "core".equals(kind) ? 2101 : "ar".equals(kind) ? 2102 : 2103; }

    private static int colorOf(Slot s) {
        try { if (!s.color.isEmpty()) return Color.parseColor(s.color); } catch (Exception ignored) { }
        return Color.parseColor("core".equals(s.kind) ? "#FDC500" : "#1D4ED8");
    }

    private static int armOf(Slot s) { return s.arm >= 0 ? s.arm : ("core".equals(s.kind) ? 2 : 1); }

    private void startFg(Notification n) { startFg(n, 2100); }

    private void startFg(Notification n, int id) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(id, n);
            fgId = id; lastFg = n;
        } catch (Exception ignored) { }
    }

    private Notification placeholder() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_stat).setContentTitle("Golden Path").setContentText("Timer").build();
    }

    /* ---------------- what the notification says ---------------- */

    private static String mmss(long s) { return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60); }

    /** {title, line} for a timer at this moment */
    private String[] words(Slot s, long el) {
        long left = Math.max(0, (s.total - el + 999) / 1000);
        if ("core".equals(s.kind)) {
            double w = el / 1000.0;
            double[] p = phase(w);
            if (el == 0) return new String[]{"Core", "Ready · " + mmss(left)};
            if (p[0] == 1) return new String[]{"Core · Change posture",
                    "Type " + ((int) p[1] + 2) + " next · " + (int) Math.max(1, Math.ceil(p[3])) + " s"};
            int i = (int) Math.min(59, Math.floor(p[2] / HOLD));
            return new String[]{"Core · Type " + (i / 20 + 1) + " · Round " + ((i % 20) / 10 + 1),
                    "Hold " + (i % 10 + 1) + "/10 · " + mmss(left) + " left"};
        }
        return new String[]{s.title, (s.sub.isEmpty() ? "" : s.sub + " · ") + mmss(left) + " left"};
    }

    private void show(Slot s) {
        long now = System.currentTimeMillis();
        long el;
        synchronized (LOCK) { el = s.el(now); }
        String[] w = words(s, el);
        String key = w[0] + "|" + w[1] + "|" + s.run;
        if (key.equals(shown.get(s.kind))) return;
        shown.put(s.kind, key);
        MediaSession ms = session(s);
        ms.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, w[0])
                .putString(MediaMetadata.METADATA_KEY_ARTIST, w[1])
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Golden Path")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, s.total)
                .putBitmap(MediaMetadata.METADATA_KEY_ART, art(s))
                .build());
        ms.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                .addCustomAction(new PlaybackState.CustomAction.Builder("reset", "Reset", R.drawable.ic_reset).build())
                .setState(s.run ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, el,
                        s.run ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(nid(s.kind), build(s));
    }

    private Notification build(Slot s) {
        long now = System.currentTimeMillis();
        long el;
        synchronized (LOCK) { el = s.el(now); }
        String[] w = words(s, el);
        MediaSession ms = session(s);
        int color = colorOf(s);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
         .setContentTitle(w[0])
         .setContentText(w[1])
         .setSubText(s.run ? "Running" : "Paused")
         .setLargeIcon(art(s))
         .setColor(color)
         .setOngoing(true)
         .setOnlyAlertOnce(true)
         .setShowWhen(false)
         .setVisibility(Notification.VISIBILITY_PUBLIC)
         .setCategory(Notification.CATEGORY_TRANSPORT)
         .setContentIntent(openApp())
         .setProgress((int) (s.total / 1000), (int) (el / 1000), false);
        if (s.run) b.addAction(new Notification.Action.Builder(R.drawable.ic_pause, "Pause", action(s.kind, A_PAUSE)).build());
        else b.addAction(new Notification.Action.Builder(R.drawable.ic_play, "Play", action(s.kind, A_PLAY)).build());
        b.addAction(new Notification.Action.Builder(R.drawable.ic_reset, "Reset", action(s.kind, A_RESET)).build());
        b.setStyle(new Notification.MediaStyle().setMediaSession(ms.getSessionToken()).setShowActionsInCompactView(0, 1));
        return b.build();
    }

    private MediaSession session(final Slot s) {
        MediaSession ms = sessions.get(s.kind);
        if (ms != null) return ms;
        ms = new MediaSession(this, "golden-path-" + s.kind);
        final String kind = s.kind;
        ms.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { op(kind, A_PLAY); }
            @Override public void onPause() { op(kind, A_PAUSE); }
            @Override public void onStop() { op(kind, A_RESET); }
            @Override public void onCustomAction(String a, android.os.Bundle e) { if ("reset".equals(a)) op(kind, A_RESET); }
        });
        ms.setSessionActivity(openApp());
        ms.setActive(true);
        sessions.put(s.kind, ms);
        return ms;
    }

    private PendingIntent openApp() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent action(String kind, String what) {
        Intent i = new Intent(this, TimerService.class);
        i.setAction(what);
        i.putExtra("kind", kind);
        int code = (kind + what).hashCode();
        if (Build.VERSION.SDK_INT >= 26)
            return PendingIntent.getForegroundService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The app's cross with one god's arm lit: Xenlil's for the core, Arurael's for her countdowns, Xeron's for Aeshma. */
    private final Map<String, Bitmap> arts = new HashMap<>();

    private Bitmap art(Slot s) {
        String key = s.kind + armOf(s) + colorOf(s);
        Bitmap bm = arts.get(key);
        if (bm != null) return bm;
        int n = 512;
        bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.drawColor(Color.parseColor("#0d0d0c"));
        Paint grey = new Paint(Paint.ANTI_ALIAS_FLAG); grey.setColor(Color.parseColor("#3a3a3a"));
        Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
        lit.setColor(colorOf(s));
        int litArm = armOf(s);
        float k = n * 0.56f / 1000f, o = n * 0.22f;              // mark viewBox 500..1500, centred
        float[][] arms = {                                       // x, y, w, h in mark units
                {400, 600, 200, 400},   // bottom - Xeron
                {0, 400, 400, 200},     // left - Arurael
                {400, 0, 200, 400},     // top - Xenlil
                {600, 400, 400, 200}};  // right - Arexia
        for (int i = 0; i < 4; i++) {
            float[] a = arms[i];
            boolean on = i == litArm;
            c.drawRect(new RectF(o + a[0] * k, o + a[1] * k, o + (a[0] + a[2]) * k, o + (a[1] + a[3]) * k), on ? lit : grey);
        }
        arts.put(key, bm);
        return bm;
    }

    /** A timer ran out while the app was away: say so. */
    private void announce(Slot s) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(nid(s.kind));
        if (MainActivity.foreground) return;      // the page says it itself
        Notes.channels(this);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "remind") : new Notification.Builder(this);
        boolean core = "core".equals(s.kind);
        b.setSmallIcon(R.drawable.ic_stat)
         .setContentTitle(!s.doneTitle.isEmpty() ? s.doneTitle
                 : core ? "Core session complete" : s.title + (s.sub.isEmpty() ? "" : " \u00b7 " + s.sub) + " done")
         .setContentText(!s.doneBody.isEmpty() ? s.doneBody
                 : core ? "Six minutes of holds, sealed" : (s.total / 60000) + " minutes of focus, sealed")
         .setColor(core ? Color.parseColor("#84CC16") : colorOf(s))
         .setAutoCancel(true)
         .setContentIntent(openApp());
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
        try { nm.notify(nid(s.kind) + 100, b.build()); } catch (SecurityException ignored) { }
    }
}
