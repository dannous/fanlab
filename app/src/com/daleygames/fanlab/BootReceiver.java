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
 *
 * And for ACTION_MEDIA_MOUNTED, which is the only route by which a stick pushed in while
 * the process is dead gets noticed. A running service registers its own receiver for that
 * and needs no help here. This path is deliberately narrow: it starts the service only if
 * autostart is already on, because starting a fan driver on the strength of somebody
 * plugging in a USB stick is not a decision this receiver gets to make.
 */
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
                // Starting it is enough: onCreate() rescans, and the rescan is what
                // notices the new volume and offers it the backlog.
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
