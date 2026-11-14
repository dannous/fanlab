package com.daleygames.fanlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The main screen: four numbers and four choices. Everything else is under Advanced. */
public class MainActivity extends Activity implements StepRow.Listener {

    private static final int POLL_MS = 500;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView readout;
    private TextView statusView;

    private StepRow fanRow;
    private StepRow brightRow;
    private StepRow bootRow;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                refresh();
            } catch (Throwable t) {
                statusView.setText("UI error: " + t);
            }
            ui.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildUi());
        FanService.poke(this, FanService.ACTION_START);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sync();
        fanRow.requestFocus();
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(poll);
        super.onPause();
    }

    private View buildUi() {
        Context c = this;
        ScrollView scroll = new ScrollView(c);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(c);
        int p = Ui.dp(c, 32);
        root.setPadding(p, p, p, p);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(Ui.text(c, "FanLab", 18f, Ui.DIM), Ui.wrap());
        readout = Ui.mono(c, "", 26f, Ui.FG);
        root.addView(readout, Ui.wrap());
        statusView = Ui.mono(c, "", 16f, Ui.DIM);
        LinearLayout.LayoutParams sl = Ui.wrap();
        sl.bottomMargin = Ui.dp(c, 12);
        root.addView(statusView, sl);

        fanRow = addRow(root, new StepRow(c, "Fan").button().tag("fan", 0));
        brightRow = addRow(root, new StepRow(c, "Brightness boost").button().tag("bright", 0));
        bootRow = addRow(root, new StepRow(c, "Start on boot").button().tag("boot", 0));
        addRow(root, new StepRow(c, "Advanced").button().tag("advanced", 0)).display(">");
        addRow(root, new StepRow(c, "Restore stock fan (do this before uninstalling)").button()
                .tag("restore", 0)).display(">");
        return scroll;
    }

    private StepRow addRow(LinearLayout parent, StepRow row) {
        row.listen(this);
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.topMargin = Ui.dp(this, 1);
        parent.addView(row, lp);
        return row;
    }

    /** What the Fan row shows: Stock, Auto, a preset name, or the advanced mode in force. */
    private String fanChoice() {
        int mode = Prefs.mode(this);
        if (mode == Mode.OFF) {
            return "Stock";
        }
        if (mode != Mode.CURVE) {
            return mode == Mode.MANUAL ? "Manual" : "Linear";
        }
        int kind = Prefs.autoKind(this);
        if (kind == Prefs.AUTO_HOLD) {
            // Named for the temperature it holds, which follows the brightness boost.
            return "Auto " + (int) AutoHold.targetFor(Prefs.ledBoostOn(this));
        }
        return kind == Prefs.AUTO_STEP ? "Auto Step" : CurveConfig.presetName(Prefs.preset(this));
    }

    private void sync() {
        int mode = Prefs.mode(this);
        fanRow.display(fanChoice());
        fanRow.valueColour(mode == Mode.OFF ? Ui.DIM
                : mode == Mode.CURVE ? Ui.GOOD : Ui.WARN);
        boolean bright = Prefs.ledBoostOn(this);
        brightRow.display(bright ? "On" : "Off");
        brightRow.valueColour(bright ? Ui.WARN : Ui.DIM);
        boolean boot = Prefs.autostart(this);
        bootRow.display(boot ? "On" : "Off");
        bootRow.valueColour(boot ? Ui.GOOD : Ui.DIM);
    }

    private void refresh() {
        Sample s = FanService.lastSample;
        if (s == null) {
            statusView.setText("Starting…");
            return;
        }
        readout.setText("LED " + (Double.isNaN(s.degC) ? "--" : Sample.fmt1(s.degC)) + " C   FAN "
                + (s.fanCtrl < 0 ? "--" : s.fanCtrl + "%") + "   "
                + (s.ledStatus == 0 ? "LIGHT OFF" : levelName(s.rgblevel).toUpperCase())
                + "   CHIP " + (Double.isNaN(s.socC[0]) ? "--" : Sample.fmt1(s.socC[0])) + " C");
        readout.setTextColor(Double.isNaN(s.degC) || s.degC >= 70 ? Ui.DANGER
                : s.degC >= 61 ? Ui.WARN : Ui.FG);

        String line;
        int colour = Ui.DIM;
        if (FanService.failSafeLatched) {
            line = "FAIL-SAFE, fan " + FanIo.FAIL_SAFE_DUTY + "%";
            colour = Ui.DANGER;
        } else if (FanService.writeFailures > 0) {
            line = "cannot write fan";
            colour = Ui.DANGER;
        } else if (FanService.sweepEngine != null || FanService.holdSession != null) {
            line = "measuring: " + FanService.sweepLine;
            colour = Ui.WARN;
        } else if (FanService.throttling) {
            line = "chip throttling";
            colour = Ui.WARN;
        } else if (Prefs.ledBoostOn(this) && FanService.ledDriveStatus.startsWith("held off")) {
            line = "brightness boost off: over " + (int) LedDrive.DEFAULT_TRIP_C + " C";
            colour = Ui.WARN;
        } else if (Prefs.mode(this) == Mode.OFF) {
            line = "stock fan control";
        } else if (Prefs.mode(this) == Mode.CURVE && Prefs.autoKind(this) == Prefs.AUTO_HOLD) {
            line = "auto, target " + (int) FanService.autoTargetNow + " C";
        } else if (Prefs.mode(this) == Mode.CURVE && Prefs.autoKind(this) == Prefs.AUTO_STEP) {
            line = "auto step" + (FanService.autoTrimNow > 0
                    ? ", +" + FanService.autoTrimNow + " to stay under "
                      + (int) FanService.autoTargetNow + " C" : "");
        } else {
            line = fanChoice().toLowerCase() + " curve";
        }
        statusView.setText(line);
        statusView.setTextColor(colour);
    }

    static String levelName(int level) {
        switch (level) {
            case 1:
                return "Eco";
            case 2:
                return "Normal";
            case 3:
                return "Presentation";
            case 4:
                return "Super Eco";
            default:
                return "--";
        }
    }

    @Override
    public void onStepRow(StepRow row) {
        try {
            if ("fan".equals(row.tagName)) {
                cycleFan();
            } else if ("bright".equals(row.tagName)) {
                if (Prefs.ledBoostOn(this)) {
                    Prefs.resetLedDrive(this);
                } else {
                    Prefs.setLedDrive(this, LedDrive.Config.bright());
                    Prefs.setLedDriveOn(this, true);
                }
                FanService.poke(this, FanService.ACTION_REFRESH);
            } else if ("boot".equals(row.tagName)) {
                Prefs.setAutostart(this, !Prefs.autostart(this));
            } else if ("advanced".equals(row.tagName)) {
                startActivity(new Intent(this, AdvancedActivity.class));
            } else if ("restore".equals(row.tagName)) {
                restoreStock();
            }
            sync();
        } catch (Throwable t) {
            say("Failed: " + t);
        }
    }

    /** Stock, Auto 58, Auto Step, then the four presets of the family the LED drive allows, and round again. */
    private void cycleFan() {
        int[] family = CurveConfig.presetsFor(Prefs.ledBoostOn(this));
        int mode = Prefs.mode(this);
        int kind = Prefs.autoKind(this);
        if (mode != Mode.CURVE) {
            Prefs.setAutoKind(this, Prefs.AUTO_HOLD);
            Prefs.setMode(this, Mode.CURVE);
        } else if (kind == Prefs.AUTO_HOLD) {
            Prefs.setAutoKind(this, Prefs.AUTO_STEP);
        } else if (kind == Prefs.AUTO_STEP) {
            Prefs.setAutoKind(this, Prefs.AUTO_NONE);
            Prefs.setPreset(this, family[0]);
        } else {
            int rung = CurveConfig.rungOf(Prefs.preset(this));
            if (rung < 0 || rung >= family.length - 1) {
                release();
                return;
            }
            Prefs.setPreset(this, family[rung + 1]);
        }
        FanService.poke(this, FanService.ACTION_REFRESH);
    }

    private void release() {
        FanService s = FanService.instance;
        if (s != null) {
            // A running session owns the fan; releasing has to stop it too.
            if (FanService.sweepEngine != null) {
                s.abortSweep("release control");
            }
            if (FanService.holdSession != null) {
                s.stopVerify("release control");
            }
            s.releaseControl();
        } else {
            // No service to ask: set the mode first, so a later start cannot drive the fan back down.
            Prefs.setMode(this, Mode.OFF);
            FanIo.writeFailSafe();
            FanService.poke(this, FanService.ACTION_RELEASE);
        }
    }

    private void restoreStock() {
        // Stop driving FIRST: the service re-asserts this property every 30 ticks in CURVE, so setting it alone is undone within half a minute.
        Prefs.setMode(this, Mode.OFF);
        FanService.poke(this, FanService.ACTION_RELEASE);
        boolean ok = SysProps.set(SysProps.PROP_FANCTRL_BY_TEMP, "1");
        say(ok ? "Stock fan restored. Safe to uninstall." : "Failed: could not set the property.");
    }

    private void say(String msg) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show();
    }
}
