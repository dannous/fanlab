package com.daleygames.fanlab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Brings the service back after a reboot, but only if the user asked for that.
 *
 * Also used for ACTION_MY_PACKAGE_REPLACED, so an update does not silently leave the fan
 * unmanaged after the app that was managing it was swapped out underneath it.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (context == null || intent == null) {
                return;
            }
            String a = intent.getAction();
            if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                    && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                    && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) {
                return;
            }
            if (!Prefs.autostart(context)) {
                return;
            }
            FanService.poke(context, FanService.ACTION_START);
        } catch (Throwable t) {
            Log.w(FanService.TAG, "BootReceiver", t);
        }
    }
}
