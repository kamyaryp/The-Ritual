package com.ritual.dial;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The phone restarted, the app was updated, or the time zone moved: set the plan again. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent in) {
        String a = in.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(a)) {
            Notes.restore(c);
        }
    }
}
