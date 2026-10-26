package com.ritual.dial;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The notification plan. The page decides what to say and when (two weeks
 * ahead) and hands the list over as JSON; it is kept on the phone and each
 * entry becomes an alarm, so notifications arrive with the app closed.
 */
final class Notes {
    private Notes() { }

    static final String PREFS = "golden-path";
    static final String KEY = "plan";

    /** Replace the whole plan: cancel what was set before, set what is new. */
    static synchronized void setPlan(Context c, String json) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        cancelAll(c, sp.getString(KEY, "[]"));
        sp.edit().putString(KEY, json).apply();
        scheduleAll(c, json);
    }

    /** After a restart or an update, set the stored plan again. */
    static synchronized void restore(Context c) {
        scheduleAll(c, c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"));
    }

    private static Intent intentFor(Context c, JSONObject o) {
        Intent i = new Intent(c, NoteReceiver.class);
        i.setAction("com.ritual.dial.NOTE");
        i.setData(Uri.parse("gp://note/" + Uri.encode(o.optString("id"))));   // one alarm per id
        return i;
    }

    private static int piFlags(int base) {
        return Build.VERSION.SDK_INT >= 23 ? base | PendingIntent.FLAG_IMMUTABLE : base;
    }

    private static void cancelAll(Context c, String json) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        try {
            JSONArray a = new JSONArray(json);
            for (int k = 0; k < a.length(); k++) {
                PendingIntent pi = PendingIntent.getBroadcast(c, 0, intentFor(c, a.getJSONObject(k)),
                        piFlags(PendingIntent.FLAG_NO_CREATE));
                if (pi != null) { am.cancel(pi); pi.cancel(); }
            }
        } catch (Exception ignored) { }
    }

    private static void scheduleAll(Context c, String json) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        long now = System.currentTimeMillis();
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        try {
            JSONArray a = new JSONArray(json);
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.getJSONObject(k);
                long at = o.optLong("at");
                if (at <= now) continue;
                Intent i = intentFor(c, o);
                i.putExtra("title", o.optString("title"));
                i.putExtra("body", o.optString("body"));
                i.putExtra("ch", o.optString("ch", "remind"));
                i.putExtra("color", o.optString("color", "#FDC500"));
                i.putExtra("fg", o.optBoolean("fg", false));
                i.putExtra("id", o.optString("id"));
                PendingIntent pi = PendingIntent.getBroadcast(c, 0, i, piFlags(PendingIntent.FLAG_UPDATE_CURRENT));
                if (Build.VERSION.SDK_INT >= 23) {
                    if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
                }
            }
        } catch (Exception ignored) { }
    }

    /** Three channels: the gods' hours, the reminders, and the night's hours, which come silently. */
    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel hours = new NotificationChannel("hours", "The hours", NotificationManager.IMPORTANCE_DEFAULT);
        hours.setDescription("When a god begins to hold the hour");
        NotificationChannel remind = new NotificationChannel("remind", "Reminders", NotificationManager.IMPORTANCE_HIGH);
        remind.setDescription("Finasteride, bed, English, and finished countdowns");
        NotificationChannel quiet = new NotificationChannel("quiet", "Night hours", NotificationManager.IMPORTANCE_LOW);
        quiet.setDescription("The hours that turn at 00:00, 03:00 and 06:00, without sound");
        nm.createNotificationChannel(hours);
        nm.createNotificationChannel(remind);
        nm.createNotificationChannel(quiet);
    }

    static void post(Context c, Intent in) {
        if (in.getBooleanExtra("fg", false) && MainActivity.foreground) return;   // the app already said it
        channels(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, 0, open, piFlags(PendingIntent.FLAG_UPDATE_CURRENT));
        String ch = in.getStringExtra("ch");
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, ch) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat)
         .setContentTitle(in.getStringExtra("title"))
         .setContentText(in.getStringExtra("body"))
         .setContentIntent(tap)
         .setAutoCancel(true)
         .setShowWhen(true)
         .setWhen(System.currentTimeMillis());
        try { b.setColor(Color.parseColor(in.getStringExtra("color"))); } catch (Exception ignored) { }
        if (Build.VERSION.SDK_INT < 26) {
            if ("quiet".equals(ch)) b.setPriority(Notification.PRIORITY_LOW);
            else { b.setPriority("remind".equals(ch) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT);
                   b.setDefaults(Notification.DEFAULT_ALL); }
        }
        String id = in.getStringExtra("id");
        // the hours replace one another; each reminder stands on its own
        int nid = "remind".equals(ch) ? (id == null ? 0 : id.hashCode()) : 1;
        try { nm.notify(nid, b.build()); } catch (SecurityException ignored) { }
    }
}
