package com.daleygames.fanlab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Configure the app from an adb shell, and read back what it now believes.
 *
 * <h3>Why this exists</h3>
 * The curve is thirty numbers — six knee temperatures, eighteen duties, and six
 * parameters. Entering that on a projector remote, with a D-pad, once per tuning
 * iteration, is slow and it is the kind of slow that produces typos in a table whose
 * whole job is to be safe. Now that adb works, a shell can do it in one line and read
 * the result back, so the value that ends up in {@link Prefs} can be diffed against the
 * value that was intended.
 *
 * <h3>Contract</h3>
 * <pre>
 * adb shell am broadcast -n com.daleygames.fanlab/.ConfigReceiver \
 *      --es curve "v1,42,48,52,55,58,62,30,32,42,56,70,83,..." \
 *      --ei mode 2 --ez autostart true
 * </pre>
 * Every extra is optional; the receiver applies whichever are present, in a fixed order
 * (curve, then the flags, then the mode), and always answers with the resulting state as
 * the broadcast's result data — so {@code am broadcast} prints it on stdout and no
 * logcat parsing is needed.
 *
 * <table>
 *   <tr><td>{@code --es curve <s>}</td><td>a {@link CurveConfig#encode()} string</td></tr>
 *   <tr><td>{@code --ei mode <n>}</td><td>0 OFF, 1 MANUAL, 2 CURVE</td></tr>
 *   <tr><td>{@code --ei manual <n>}</td><td>MANUAL duty, 1..100</td></tr>
 *   <tr><td>{@code --ez autostart <b>}</td><td>come back after a reboot</td></tr>
 *   <tr><td>{@code --ez reassert <b>}</td><td>defend the node against other writers</td></tr>
 *   <tr><td>{@code --ez logging <b>}</td><td>CSV telemetry on/off</td></tr>
 *   <tr><td>{@code --ei logevery <n>}</td><td>seconds between routine rows (events are
 *       always logged); 10 by default, 1 while investigating</td></tr>
 *   <tr><td>{@code --ez reset <b>}</td><td>restore the built-in default curve</td></tr>
 * </table>
 *
 * <h3>Safety</h3>
 * Nothing here can command a duty directly. Everything routes through
 * {@link Prefs#setCurve}, which calls {@link CurveConfig#sanitise()}, so a malformed or
 * hostile curve is repaired into a legal one rather than obeyed — the same path the
 * on-screen editor uses. An unparseable curve string falls back to the defaults inside
 * {@link CurveConfig#decode}, which is a working curve, not an absent one. The receiver
 * is exported because an adb shell is a different uid and could not reach it otherwise;
 * that is the same exposure the already-exported {@link BootReceiver} carries, and the
 * worst an attacker with shell on this projector could do through it is pick a fan
 * curve, having already been able to write {@code fan_ctrl} directly.
 */
public class ConfigReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String reply;
        try {
            reply = apply(context, intent);
        } catch (Throwable t) {
            // Never let a bad extra kill the receiver silently; the caller needs to know.
            Log.w(FanService.TAG, "ConfigReceiver", t);
            reply = "ERROR " + t;
        }
        try {
            setResultCode(0);
            setResultData(reply);
        } catch (Throwable ignored) {
            // Not an ordered broadcast. The log line below is then the only record.
        }
        Log.i(FanService.TAG, "ConfigReceiver: " + reply);
    }

    private String apply(Context context, Intent intent) {
        if (context == null || intent == null) {
            return "ERROR no context";
        }
        StringBuilder did = new StringBuilder();

        if (intent.getBooleanExtra("reset", false)) {
            Prefs.resetCurve(context);
            did.append(" reset");
        }
        if (intent.hasExtra("curve")) {
            String s = intent.getStringExtra("curve");
            CurveConfig cfg = CurveConfig.decode(s);
            Prefs.setCurve(context, cfg);
            // decode() silently falls back to defaults on anything malformed, so compare
            // what was stored against what was asked for rather than trusting the write.
            boolean exact = cfg.encode().equals(s);
            did.append(exact ? " curve" : " curve(REPAIRED)");
        }
        if (intent.hasExtra("manual")) {
            Prefs.setManualDuty(context, intent.getIntExtra("manual", FanIo.KERNEL_DEFAULT_DUTY));
            did.append(" manual");
        }
        if (intent.hasExtra("autostart")) {
            Prefs.setAutostart(context, intent.getBooleanExtra("autostart", false));
            did.append(" autostart");
        }
        if (intent.hasExtra("reassert")) {
            Prefs.setReassert(context, intent.getBooleanExtra("reassert", true));
            did.append(" reassert");
        }
        if (intent.hasExtra("logevery")) {
            Prefs.setLogEverySec(context, intent.getIntExtra("logevery", 10));
            did.append(" logevery");
        }
        if (intent.hasExtra("logging")) {
            Prefs.setLogging(context, intent.getBooleanExtra("logging", true));
            did.append(" logging");
        }
        if (intent.hasExtra("mode")) {
            int m = intent.getIntExtra("mode", Mode.OFF);
            if (m != Mode.OFF && m != Mode.MANUAL && m != Mode.CURVE) {
                return "ERROR mode must be 0 OFF, 1 MANUAL or 2 CURVE, got " + m;
            }
            Prefs.setMode(context, m);
            did.append(" mode");
        }

        // Whatever changed, the running loop has to be told; if it is not running, this
        // starts it. Doing it once at the end means a curve+mode change is applied as a
        // single transition rather than as two.
        FanService.poke(context, FanService.ACTION_REFRESH);

        return "OK applied:" + (did.length() == 0 ? " (nothing)" : did) + " | " + state(context);
    }

    /** The state as the app now sees it — the point of the round trip. */
    private String state(Context context) {
        CurveConfig c = Prefs.curve(context);
        return "mode=" + Mode.name(Prefs.mode(context))
                + " manual=" + Prefs.manualDuty(context)
                + " autostart=" + Prefs.autostart(context)
                + " reassert=" + Prefs.reassert(context)
                + " logging=" + Prefs.logging(context)
                + " logevery=" + Prefs.logEverySec(context) + "s"
                + " fan_ctrl=" + FanIo.readDuty()
                + " curve=" + c.encode();
    }
}
