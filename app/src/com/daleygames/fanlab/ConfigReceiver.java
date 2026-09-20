package com.daleygames.fanlab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Configure the app from an adb shell, and read back what it now believes as the broadcast's
 * result data. Nothing here commands a duty: every setting routes through the same sanitise()
 * the on-screen editor uses, so a malformed or hostile value is repaired rather than obeyed.
 */
public class ConfigReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String reply;
        try {
            reply = apply(context, intent);
        } catch (Throwable t) {
            Log.w(FanService.TAG, "ConfigReceiver", t);
            reply = "ERROR " + t;
        }
        try {
            setResultCode(0);
            setResultData(reply);
        } catch (Throwable ignored) {
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
            // And the LED drive: stock is the kernel's own table with the override off. LINEAR's
            // own config stays exempt from reset, and its ceiling comes back to 52.0 anyway.
            Prefs.resetLedDrive(context);
            did.append(" reset");
        }
        // Before "curve" on purpose: sending both is how a preset gets used as a starting
        // point for a hand-edited line, and the later write has to be the one that sticks.
        if (intent.hasExtra("preset")) {
            String name = intent.getStringExtra("preset");
            int given = intent.getIntExtra("preset", CurveConfig.PRESET_CUSTOM);
            int i = name == null ? given : CurveConfig.presetNamed(name);
            if (i < 0 || i >= CurveConfig.PRESET_NAMES.length) {
                return "ERROR preset must be " + presetWords() + ", or 0.."
                        + (CurveConfig.PRESET_NAMES.length - 1) + ", got "
                        + (name == null ? Integer.toString(given) : name.trim());
            }
            // Judged against the drive state this command ENDS in, not the one it starts in, so a
            // preset and the switch can be sent in one line. Refused rather than substituted:
            // naming a preset is an explicit request for that exact curve.
            String refusal = CurveConfig.wrongFamilyRefusal(i, endingBoostOn(context, intent));
            if (refusal != null) {
                did.append(" preset(REFUSED: ").append(refusal).append(")");
            } else {
                Prefs.setPreset(context, i);
                did.append(" preset");
            }
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
        // LINEAR's numbers, one at a time. linstep is the superseded spelling of linup and is
        // still accepted.
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
            // Say so when a value was clamped. Compared field by field rather than by re-encoding,
            // because --ef arrives as a float: half a tenth of slack separates the snap back to a
            // tenth from a real clamp, which moves a value by whole degrees.
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
        // The LED drive table before its switch, so one command can set the levels and then turn
        // them on, rather than switching on whatever happened to be stored a moment earlier.
        if (intent.hasExtra("leddrive")) {
            String s = intent.getStringExtra("leddrive");
            String want = s == null ? "" : s.trim();
            LedDrive.Config cfg = ledDriveNamed(want);
            Prefs.setLedDrive(context, cfg);
            // Same discipline as the curve: decode() falls back to stock and sanitise() clamps every
            // level to MAX_LEVEL, so a caller who sent 100 is told it became 97.
            boolean exact = "stock".equalsIgnoreCase(want) || "bright".equalsIgnoreCase(want)
                    || cfg.encode().equals(want);
            did.append(exact ? " leddrive" : " leddrive(REPAIRED)");
        }
        if (intent.hasExtra("leddriveon")) {
            Prefs.setLedDriveOn(context, intent.getBooleanExtra("leddriveon", false));
            did.append(" leddriveon");
        }
        // Start VERIFY from a script: the screen is exported="false", so otherwise a session could
        // only be reached by someone holding the remote. The spec is duty:rgblevel.
        if (intent.hasExtra("verify")) {
            FanService fs = FanService.instance;
            String spec = intent.getStringExtra("verify");
            int colon = spec == null ? -1 : spec.indexOf(':');
            if (fs == null) {
                did.append(" verify(NO SERVICE)");
            } else if (colon <= 0) {
                did.append(" verify(BAD SPEC, want duty:rgblevel)");
            } else {
                try {
                    int d = Integer.parseInt(spec.substring(0, colon).trim());
                    int lv = Integer.parseInt(spec.substring(colon + 1).trim());
                    did.append(fs.startVerify(d, lv) ? " verify" : " verify(REFUSED)");
                } catch (NumberFormatException e) {
                    did.append(" verify(BAD SPEC, want duty:rgblevel)");
                }
            }
        }
        // Hand a running VERIFY over to the curve, so the closed-loop half can be driven
        // from a script as well as from the remote. Seconds, clamped in HoldSession.
        if (intent.hasExtra("steady")) {
            FanService fs = FanService.instance;
            if (fs == null) {
                did.append(" steady(NO SERVICE)");
            } else {
                did.append(fs.beginSteadyPhase(intent.getIntExtra("steady",
                        HoldSession.STEADY_SECONDS)) ? " steady" : " steady(REFUSED)");
            }
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

        // Whatever changed, the running loop has to be told; if it is not running, this starts it.
        // Once at the end, so a curve+mode change lands as a single transition rather than two.
        FanService.poke(context, FanService.ACTION_REFRESH);

        // After the poke, because the export needs the service: only it knows which volumes are
        // mounted.
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

    /** The table a {@code --es leddrive} value asks for: a keyword, or an encoded line. */
    private static LedDrive.Config ledDriveNamed(String want) {
        if ("stock".equalsIgnoreCase(want)) {
            return new LedDrive.Config();
        }
        if ("bright".equalsIgnoreCase(want)) {
            return LedDrive.Config.bright();
        }
        return LedDrive.Config.decode(want);
    }

    /** Will the override be asking for anything once this whole command has been applied? */
    private static boolean endingBoostOn(Context context, Intent intent) {
        boolean on = intent.hasExtra("leddriveon")
                ? intent.getBooleanExtra("leddriveon", false)
                : Prefs.ledDriveOn(context);
        LedDrive.Config table;
        if (intent.hasExtra("leddrive")) {
            String s = intent.getStringExtra("leddrive");
            table = ledDriveNamed(s == null ? "" : s.trim());
            table.sanitise();
        } else {
            table = Prefs.ledDrive(context);
        }
        return on && !table.isStock();
    }

    /** Every accepted word, as the list has them -- both families, in {@code --ei} order. */
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
        // Two questions, two answers: ceiling= is the number the controller will actually hold,
        // with the promotion named, while linear= at the end is the stored line the promotion
        // never touches. Taken before the mutation so the second cannot become the first.
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
                // The table, then what is actually on the hardware -- not the same question, since
                // the override is held off unless this app is the fan controller.
                + " leddrive=" + (d.isStock() ? "stock" : d.summary() + " (" + d.encode() + ")")
                + " leddriveon=" + Prefs.ledDriveOn(context)
                + " leddrivestate=" + FanService.ledDriveStatus
                + " session=" + Prefs.session(context)
                + " fan_ctrl=" + FanIo.readDuty()
                + " export=" + (FanService.exporting ? "running" : FanService.exportStatus)
                + " preset=" + CurveConfig.presetName(Prefs.preset(context))
                + " presetfamily=" + (boost ? "Bright Curve" : "Curve")
                + " presetsallowed=" + CurveConfig.familyWords(boost)
                + " curve=" + c.encode()
                + " linear=" + storedLinear;
    }
}
