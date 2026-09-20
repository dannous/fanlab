package com.daleygames.fanlab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Restarts the service after a reboot, a package replace, or a media mount, but only when autostart is already on. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (context == null || intent == null) {
                return;
            }
            String a = intent.getAction();
            if (Intent.ACTION_MEDIA_MOUNTED.equals(a)) {
                if (FanService.instance != null || !Prefs.autostart(context)) {
                    return;
                }
                FanService.poke(context, FanService.ACTION_START);
                return;
            }
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
