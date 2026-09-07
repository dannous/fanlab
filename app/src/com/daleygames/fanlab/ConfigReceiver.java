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
 * (the preset, then the curve, then the linear ceiling, then the LED drive table and its
 * switch, then the flags, then the mode), and always answers with the resulting state as
 * the broadcast's result data — so {@code am broadcast} prints it on stdout and no logcat
 * parsing is needed.
 *
 * <table>
 *   <tr><td>{@code --es preset <s>}</td><td>a name from {@link CurveConfig#PRESET_NAMES}
 *       -- {@code quiet}, {@code balanced}, {@code cool}, {@code cold} or {@code bright} --
 *       any case; {@code --ei preset <n>} takes the index into the same list. Applied
 *       <i>before</i> {@code curve}, so sending both lands on the hand-written curve and a
 *       curve that is none of the presets stays possible</td></tr>
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
 *       C, 0..10</td></tr>
 *   <tr><td>{@code --ei lintrend <n>}</td><td>seconds of history LINEAR's trend gate fits a
 *       slope over, 0..300. <b>0 turns the gate off</b>, which restores the walk that was
 *       measured overshooting its equilibrium by eight duty points -- it is there so the two
 *       can be compared by ear, not as a setting to leave. See
 *       {@link LinearConfig#trendWindowS}. {@code --ei linstep} is the superseded spelling
 *       of {@code linup} and is still accepted</td></tr>
 *   <tr><td>{@code --es leddrive <s>}</td><td>the four LED drive levels, Super Eco / Eco /
 *       Normal / Presentation. {@code stock} is the kernel's own 20/40/55/76, {@code bright}
 *       is the one-press preset 30/50/70/90, and a {@link LedDrive.Config#encode()} string
 *       sets them individually. Every level is held to
 *       {@link LedDrive#MIN_LEVEL}..{@link LedDrive#MAX_LEVEL} -- <b>97, not 100</b>,
 *       because the driver's DAC clamp drops an over-request to about 40 % instead of
 *       saturating, so asking for 100 would make the picture go dim. Applied <i>before</i>
 *       {@code leddriveon}, so one command can set the table and switch it on</td></tr>
 *   <tr><td>{@code --ez leddriveon <b>}</td><td>run the LED light engine above what the
 *       brightness mode asks for. Off by default. <b>It only ever applies while this app is
 *       the fan controller</b> -- CURVE or LINEAR, no session running, the light engine on,
 *       the fail-safe clear -- because Presentation-class LED heat under the Eco fan ladder
 *       is the one combination this project must not create; see {@link LedDrive}. Switching
 *       it on while LINEAR's ceiling is still the untouched 52.0 also raises that ceiling to
 *       54.0, which the reply says out loud</td></tr>
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
 *   <tr><td>{@code --ez caic <b>}</td><td>ask the display controller to run Content
 *       Adaptive Illumination Control ({@code w 50 1 1} to picoreg; {@code false} writes
 *       {@code w 50 1 0}). An experiment, not a tuning: this board has no TI LED driver
 *       for CAIC to lower current through, so what it does here is unknown until looked
 *       at. Off by default, which is how stock ships; a power cycle turns it off
 *       regardless. {@code caic=} in the reply gives the setting and, on the system build
 *       once a read-back has landed, what the DLPC actually says. See {@link PicoReg}</td></tr>
 *   <tr><td>{@code --ez reset <b>}</td><td>restore the built-in default curve, put the LED
 *       drive back to the stock table and switch it off, and turn the CAIC experiment
 *       off</td></tr>
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
            // The CAIC experiment goes with it. "Reset" is the word someone reaches for
            // when they want the machine back as the manufacturer left it, and stock has
            // CAIC off; the service sees the setting change and writes the off command.
            Prefs.setCaic(context, false);
            // And the LED drive, for exactly the same reason and by the same mechanism:
            // stock is the kernel's own table with the override off, and the tick that sees
            // the setting change hands the hardware back within a second. LINEAR's own
            // config stays exempt from reset, for the reason Prefs.linear gives -- and the
            // ceiling still comes back to 52.0 here anyway, because the promotion was never
            // stored in it.
            Prefs.resetLedDrive(context);
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
                // Spelled out from the list itself, so the error cannot go on naming four
                // presets after a fifth has been added.
                return "ERROR preset must be " + presetWords() + ", or 0.."
                        + (CurveConfig.PRESET_NAMES.length - 1) + ", got "
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
                || intent.hasExtra("linfast") || intent.hasExtra("linnear")
                || intent.hasExtra("lintrend")) {
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
            if (intent.hasExtra("lintrend")) {
                lin.trendWindowS = intent.getIntExtra("lintrend", lin.trendWindowS);
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
            int askedTrend = lin.trendWindowS;
            lin.sanitise();
            boolean repaired = lin.trendWindowS != askedTrend
                    || lin.upStepMs != askedUp
                    || lin.downStepMs != askedDown
                    || lin.downFastMs != askedFast
                    || Math.abs(lin.nearC - askedNear) > 0.05
                    || Math.abs(lin.ceilingC - askedCeiling) > 0.05;
            Prefs.setLinear(context, lin);
            did.append(repaired ? " linear(REPAIRED)" : " linear");
        }
        // The LED drive table before its switch, so `--es leddrive bright --ez leddriveon
        // true` in one command sets the levels and then turns them on, rather than
        // switching on whatever happened to be stored a moment earlier.
        if (intent.hasExtra("leddrive")) {
            String s = intent.getStringExtra("leddrive");
            String want = s == null ? "" : s.trim();
            LedDrive.Config cfg;
            if ("stock".equalsIgnoreCase(want)) {
                cfg = new LedDrive.Config();
            } else if ("bright".equalsIgnoreCase(want)) {
                cfg = LedDrive.Config.bright();
            } else {
                cfg = LedDrive.Config.decode(want);
            }
            Prefs.setLedDrive(context, cfg);
            // Same discipline as the curve: decode() falls back to stock on anything
            // malformed and sanitise() clamps every level to MAX_LEVEL, so compare what was
            // stored against what was asked for rather than trusting the write. A caller
            // who sent 100 needs to be told it became 97, not left believing the light
            // engine is running at a level the DAC would have turned into 40 %.
            boolean exact = "stock".equalsIgnoreCase(want) || "bright".equalsIgnoreCase(want)
                    || cfg.encode().equals(want);
            did.append(exact ? " leddrive" : " leddrive(REPAIRED)");
        }
        if (intent.hasExtra("leddriveon")) {
            Prefs.setLedDriveOn(context, intent.getBooleanExtra("leddriveon", false));
            did.append(" leddriveon");
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
        if (intent.hasExtra("caic")) {
            Prefs.setCaic(context, intent.getBooleanExtra("caic", false));
            did.append(" caic");
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

    /** "quiet, balanced, cool, cold or bright" -- the accepted words, as the list has them. */
    private static String presetWords() {
        StringBuilder sb = new StringBuilder();
        int n = CurveConfig.PRESET_NAMES.length;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(i == n - 1 ? " or " : ", ");
            }
            sb.append(CurveConfig.PRESET_NAMES[i].toLowerCase());
        }
        return sb.toString();
    }

    /** The state as the app now sees it — the point of the round trip. */
    private String state(Context context) {
        CurveConfig c = Prefs.curve(context);
        LinearConfig l = Prefs.linear(context);
        LedDrive.Config d = Prefs.ledDrive(context);
        boolean boost = Prefs.ledBoostOn(context);
        // Two different questions, so two different answers in the same reply. `ceiling=`
        // is the number the controller will actually hold, with the promotion named rather
        // than left to be inferred from a value that moved on its own; `linear=` at the end
        // is the stored line, which the promotion never touches. Taken before the mutation
        // so the second cannot quietly become the first.
        String storedLinear = l.encode();
        boolean raised = LinearConfig.promoteForBoost(l, boost);
        return "mode=" + Mode.name(Prefs.mode(context))
                + " ceiling=" + Sample.fmt1(l.ceilingC) + "C"
                + (raised ? "(raised from the " + Sample.fmt1(LinearConfig.DEFAULT_CEILING_C)
                        + " default for the LED drive override; inferred, not measured)" : "")
                + " linup=" + l.upStepMs + "ms"
                + " lindown=" + l.downStepMs + "ms"
                + " linfast=" + l.downFastMs + "ms"
                + " linnear=" + Sample.fmt1(l.nearC) + "C"
                + " lintrend=" + (l.trendWindowS == 0 ? "off" : l.trendWindowS + "s")
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
                // The setting, then what the DLPC said if anything has asked it. The
                // read-back lags a write by a few seconds and runs only on the system
                // build, so a second round trip after --ez caic true is how to see it.
                + " caic=" + FanService.caicSummary(Prefs.caic(context))
                // The table, then what is actually on the hardware -- which is not the same
                // question, because the override is held off entirely unless this app is
                // the fan controller, and it drops itself on its own ceiling trip.
                + " leddrive=" + (d.isStock() ? "stock" : d.summary() + " (" + d.encode() + ")")
                + " leddriveon=" + Prefs.ledDriveOn(context)
                + " leddrivestate=" + FanService.ledDriveStatus
                + " session=" + Prefs.session(context)
                + " fan_ctrl=" + FanIo.readDuty()
                + " export=" + (FanService.exporting ? "running" : FanService.exportStatus)
                + " preset=" + CurveConfig.presetName(Prefs.preset(context))
                + " curve=" + c.encode()
                + " linear=" + storedLinear;
    }
}
