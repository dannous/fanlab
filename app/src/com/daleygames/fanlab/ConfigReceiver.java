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
 * (the preset, then the curve, then the linear ceiling, then the flags, then the mode), and
 * always answers with the resulting state as the broadcast's result data — so
 * {@code am broadcast} prints it on stdout and no logcat parsing is needed.
 *
 * <table>
 *   <tr><td>{@code --es preset <s>}</td><td>{@code quiet}, {@code balanced}, {@code cool}
 *       or {@code cold}, any case; {@code --ei preset 0|1|2|3} does the same. Applied
 *       <i>before</i> {@code curve}, so sending both lands on the hand-written curve and a
 *       curve that is none of the four stays possible</td></tr>
 *   <tr><td>{@code --es curve <s>}</td><td>a {@link CurveConfig#encode()} string</td></tr>
 *   <tr><td>{@code --ei mode <n>}</td><td>0 OFF, 1 MANUAL, 2 CURVE, 3 LINEAR</td></tr>
 *   <tr><td>{@code --ef ceiling <f>}</td><td>LINEAR's temperature ceiling in C, held to
 *       {@link LinearConfig#MIN_CEILING_C}..{@link LinearConfig#MAX_CEILING_C} and snapped
 *       to a tenth</td></tr>
 *   <tr><td>{@code --ei linup <n>}</td><td>milliseconds between LINEAR's decisions while
 *       at or above the ceiling, 1000..120000. The plant's lag is measured and one point
 *       per second was measured building a growing limit cycle</td></tr>
 *   <tr><td>{@code --ei lindown <n>}</td><td>the same while falling and within
 *       {@code linnear} of the ceiling, 1000..120000. <b>This is the one that decides
 *       whether anyone hears it</b> -- 60 s measured a 3-point swing the owner did not
 *       notice, 5 s measured 14 that he did -- so read {@link LinearConfig#downStepMs}
 *       before lowering it</td></tr>
 *   <tr><td>{@code --ei linfast <n>}</td><td>the same while falling with more than
 *       {@code linnear} of headroom, 1000..120000. Sanitised to be no slower than
 *       {@code lindown}</td></tr>
 *   <tr><td>{@code --ef linnear <f>}</td><td>how close to the ceiling counts as close, in
 *       C, 0..10. {@code --ei linstep} is the superseded spelling of {@code linup} and is
 *       still accepted</td></tr>
 *   <tr><td>{@code --ei manual <n>}</td><td>MANUAL duty, 1..100</td></tr>
 *   <tr><td>{@code --ez autostart <b>}</td><td>come back after a reboot</td></tr>
 *   <tr><td>{@code --ez reassert <b>}</td><td>defend the node against other writers</td></tr>
 *   <tr><td>{@code --ez logging <b>}</td><td>CSV telemetry on/off</td></tr>
 *   <tr><td>{@code --ei logevery <n>}</td><td>seconds between routine rows (events are
 *       always logged); 10 by default, 1 while investigating</td></tr>
 *   <tr><td>{@code --ei room <n>}</td><td>room temperature in C, 0..40, written to every
 *       CSV row. 0 means "not stated" and logs as a blank, never as a zero. The one
 *       quantity in the log that cannot be derived from the log, so it is worth being able
 *       to state without a D-pad</td></tr>
 *   <tr><td>{@code --ez reset <b>}</td><td>restore the built-in default curve</td></tr>
 *   <tr><td>{@code --ez export <b>}</td><td>copy every existing log to
 *       {@code FanLab-export/} on each mounted USB volume, now, whether or not this boot
 *       has already done it. Runs on its own thread; the reply says it started, and
 *       {@code export=} on the next round trip says what it did</td></tr>
 *   <tr><td>{@code --ez socguard <b>}</td><td>arm or disarm the SoC guard</td></tr>
 *   <tr><td>{@code --ei socstart <n>}</td><td>die temperature it starts adding fan at</td></tr>
 *   <tr><td>{@code --ef socgain <f>}</td><td>extra duty points per degree above that</td></tr>
 *   <tr><td>{@code --ei socmax <n>}</td><td>ceiling on the guarded duty</td></tr>
 *   <tr><td>{@code --ef sochyst <f>}</td><td>deadband on the guard's input</td></tr>
 * </table>
 *
 * <h3>Safety</h3>
 * Nothing here can command a duty directly. Everything routes through
 * {@link Prefs#setCurve}, which calls {@link CurveConfig#sanitise()}, or through
 * {@link Prefs#setLinear}, which calls {@link LinearConfig#sanitise()}, so a malformed or
 * hostile setting is repaired into a legal one rather than obeyed — the same path the
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
        // Before "curve" on purpose: sending both is how a preset gets used as a starting
        // point for a hand-edited line, and the later write has to be the one that sticks.
        if (intent.hasExtra("preset")) {
            // A name if one was sent, an index otherwise: --es is what a person types and
            // --ei is what a script does, and neither should have to know about the other.
            String name = intent.getStringExtra("preset");
            int given = intent.getIntExtra("preset", CurveConfig.PRESET_CUSTOM);
            int i = name == null ? given : presetIndexFor(name);
            if (i < 0 || i >= CurveConfig.PRESET_NAMES.length) {
                return "ERROR preset must be quiet, balanced, cool or cold, or 0..3, got "
                        + (name == null ? Integer.toString(given) : name.trim());
            }
            Prefs.setPreset(context, i);
            did.append(" preset");
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
        // The guard's four numbers, individually settable, because tuning it means moving
        // one of them at a time and re-encoding the whole curve to change a knee is how
        // typos get into a safety table.
        if (intent.hasExtra("socguard")) {
            CurveConfig cfg = Prefs.curve(context);
            cfg.socGuardEnabled = intent.getBooleanExtra("socguard", true);
            Prefs.setCurve(context, cfg);
            did.append(" socguard");
        }
        if (intent.hasExtra("socstart") || intent.hasExtra("socgain")
                || intent.hasExtra("socmax") || intent.hasExtra("sochyst")) {
            CurveConfig cfg = Prefs.curve(context);
            if (intent.hasExtra("socstart")) {
                cfg.socGuardStartC = intent.getIntExtra("socstart", cfg.socGuardStartC);
            }
            if (intent.hasExtra("socgain")) {
                cfg.socGuardGainPerC = intent.getFloatExtra("socgain",
                        (float) cfg.socGuardGainPerC);
            }
            if (intent.hasExtra("socmax")) {
                cfg.socGuardMaxDuty = intent.getIntExtra("socmax", cfg.socGuardMaxDuty);
            }
            if (intent.hasExtra("sochyst")) {
                cfg.socGuardHystC = intent.getFloatExtra("sochyst", (float) cfg.socGuardHystC);
            }
            Prefs.setCurve(context, cfg);
            did.append(" socguard-tune");
        }
        // LINEAR's numbers, on the same principle as the guard's: one at a time, so
        // moving a step interval does not mean re-typing the ceiling beside it.
        //
        // linstep is the superseded name for linup and is still accepted, because the
        // single-interval version of this mode was on the machine while it was being tuned
        // and that spelling is in the notes.
        if (intent.hasExtra("ceiling") || intent.hasExtra("linstep")
                || intent.hasExtra("linup") || intent.hasExtra("lindown")
                || intent.hasExtra("linfast") || intent.hasExtra("linnear")) {
            LinearConfig lin = Prefs.linear(context);
            if (intent.hasExtra("ceiling")) {
                lin.ceilingC = intent.getFloatExtra("ceiling", (float) lin.ceilingC);
            }
            if (intent.hasExtra("linstep")) {
                lin.upStepMs = intent.getIntExtra("linstep", (int) lin.upStepMs);
            }
            if (intent.hasExtra("linup")) {
                lin.upStepMs = intent.getIntExtra("linup", (int) lin.upStepMs);
            }
            if (intent.hasExtra("lindown")) {
                lin.downStepMs = intent.getIntExtra("lindown", (int) lin.downStepMs);
            }
            if (intent.hasExtra("linfast")) {
                lin.downFastMs = intent.getIntExtra("linfast", (int) lin.downFastMs);
            }
            if (intent.hasExtra("linnear")) {
                lin.nearC = intent.getFloatExtra("linnear", (float) lin.nearC);
            }
            // Say so when a value was clamped, the same way the curve does: a caller who
            // sent 300 ms needs to be told it became 1000 rather than left to assume the
            // fan is now walking three times a second.
            //
            // Compared field by field rather than by re-encoding, because --ef arrives as
            // a float: 52.3 reaches us as 52.29999923706055, and sanitise() snapping that
            // back to a tenth is the transport being undone, not a repair. Comparing
            // encoded lines would call every fractional ceiling REPAIRED and teach the
            // reader to ignore the word. The tolerance is half a tenth, which is the most
            // the snap can move a value, while a real clamp moves it by whole degrees.
            double askedCeiling = lin.ceilingC;
            double askedNear = lin.nearC;
            long askedUp = lin.upStepMs;
            long askedDown = lin.downStepMs;
            long askedFast = lin.downFastMs;
            lin.sanitise();
            boolean repaired = lin.upStepMs != askedUp
                    || lin.downStepMs != askedDown
                    || lin.downFastMs != askedFast
                    || Math.abs(lin.nearC - askedNear) > 0.05
                    || Math.abs(lin.ceilingC - askedCeiling) > 0.05;
            Prefs.setLinear(context, lin);
            did.append(repaired ? " linear(REPAIRED)" : " linear");
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
        if (intent.hasExtra("room")) {
            Prefs.setRoomC(context, intent.getIntExtra("room", 0));
            did.append(" room");
        }
        if (intent.hasExtra("mode")) {
            int m = intent.getIntExtra("mode", Mode.OFF);
            if (m != Mode.OFF && m != Mode.MANUAL && m != Mode.CURVE && m != Mode.LINEAR) {
                return "ERROR mode must be 0 OFF, 1 MANUAL, 2 CURVE or 3 LINEAR, got " + m;
            }
            Prefs.setMode(context, m);
            did.append(" mode");
        }

        // Whatever changed, the running loop has to be told; if it is not running, this
        // starts it. Doing it once at the end means a curve+mode change is applied as a
        // single transition rather than as two.
        FanService.poke(context, FanService.ACTION_REFRESH);

        // After the poke, because the export needs the service: only it knows which
        // volumes are mounted. A start requested above is asynchronous, so a cold app
        // cannot be forced from here -- but it does not need to be, since the service
        // offers a mounted stick the backlog on its first rescan anyway.
        if (intent.getBooleanExtra("export", false)) {
            FanService s = FanService.instance;
            if (s == null) {
                did.append(" export(service was not running)");
            } else {
                s.exportBacklogAsync(true);
                did.append(" export(started)");
            }
        }

        return "OK applied:" + (did.length() == 0 ? " (nothing)" : did) + " | " + state(context);
    }

    /**
     * A preset by the name it is shown under, so the words this accepts cannot drift from
     * the words on screen. Anything else is not a preset.
     */
    private static int presetIndexFor(String name) {
        for (int i = 0; i < CurveConfig.PRESET_NAMES.length; i++) {
            if (CurveConfig.PRESET_NAMES[i].equalsIgnoreCase(name.trim())) {
                return i;
            }
        }
        return CurveConfig.PRESET_CUSTOM;
    }

    /** The state as the app now sees it — the point of the round trip. */
    private String state(Context context) {
        CurveConfig c = Prefs.curve(context);
        LinearConfig l = Prefs.linear(context);
        return "mode=" + Mode.name(Prefs.mode(context))
                + " ceiling=" + Sample.fmt1(l.ceilingC) + "C"
                + " linup=" + l.upStepMs + "ms"
                + " lindown=" + l.downStepMs + "ms"
                + " linfast=" + l.downFastMs + "ms"
                + " linnear=" + Sample.fmt1(l.nearC) + "C"
                + " socguard=" + (c.socGuardEnabled
                        ? c.socGuardStartC + "C+" + c.socGuardGainPerC + "/C<=" + c.socGuardMaxDuty
                        : "off")
                + " manual=" + Prefs.manualDuty(context)
                + " autostart=" + Prefs.autostart(context)
                + " reassert=" + Prefs.reassert(context)
                + " logging=" + Prefs.logging(context)
                + " logevery=" + Prefs.logEverySec(context) + "s"
                + " room=" + (Prefs.roomC(context) == 0
                        ? "not stated" : Prefs.roomC(context) + "C")
                + " session=" + Prefs.session(context)
                + " fan_ctrl=" + FanIo.readDuty()
                + " export=" + (FanService.exporting ? "running" : FanService.exportStatus)
                + " preset=" + CurveConfig.presetName(Prefs.preset(context))
                + " curve=" + c.encode()
                + " linear=" + l.encode();
    }
}
