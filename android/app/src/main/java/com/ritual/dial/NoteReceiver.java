package com.ritual.dial;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** An alarm from the plan has gone off: post its notification. */
public class NoteReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent in) {
        Notes.post(c, in);
    }
}
