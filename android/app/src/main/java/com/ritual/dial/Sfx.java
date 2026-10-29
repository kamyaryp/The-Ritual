package com.ritual.dial;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.HashMap;
import java.util.Map;

/**
 * The timer sounds, rendered from the page's own sound design into WAV files,
 * played through the media stream so they carry on with the screen locked.
 */
final class Sfx {
    private Sfx() { }

    private static SoundPool pool;
    private static final Map<String, Integer> ids = new HashMap<>();
    private static Context app;

    static synchronized void init(Context c) {
        if (pool != null) return;
        app = c.getApplicationContext();
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(aa).build();
        ids.put("tick", pool.load(app, R.raw.sfx_tick, 1));
        ids.put("hold", pool.load(app, R.raw.sfx_hold, 1));
        ids.put("round", pool.load(app, R.raw.sfx_round, 1));
        ids.put("typeEnd", pool.load(app, R.raw.sfx_type_end, 1));
        ids.put("beat", pool.load(app, R.raw.sfx_beat, 1));
        ids.put("go", pool.load(app, R.raw.sfx_go, 1));
        ids.put("start", pool.load(app, R.raw.sfx_start, 1));
        ids.put("finish", pool.load(app, R.raw.sfx_finish, 1));
        ids.put("done", pool.load(app, R.raw.sfx_done, 1));
    }

    static void play(String kind) {
        if (pool == null) return;
        Integer id = ids.get(kind);
        if (id != null) pool.play(id, 1f, 1f, 1, 0, 1f);
    }

    static void buzz(long... on) {
        try {
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            long[] wave = new long[on.length + 1];
            System.arraycopy(on, 0, wave, 1, on.length);
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(wave, -1));
            else v.vibrate(wave, -1);
        } catch (Exception ignored) { }
    }
}
