package com.daleygames.fanlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
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
    private TextView hintView;
    private LinearLayout pairView;
    private TextView statusView;

    private StepRow fanRow;
    private StepRow brightRow;
    private StepRow bootRow;
    private StepRow advancedRow;
    private AdvancedPanel advanced;
    private View advancedView;

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
        StepRow.hintSink = new StepRow.HintSink() {
            @Override
            public void onHint(View row, String text) {
                hintView.setText(text == null ? "" : text);
                // Level with the row it describes, wherever the list has scrolled to.
                android.graphics.Rect r = new android.graphics.Rect();
                row.getDrawingRect(r);
                pairView.offsetDescendantRectToMyCoords(row, r);
                hintView.setTranslationY(r.top);
            }
        };
        sync();
        fanRow.clearFocus();
        fanRow.requestFocus();
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        StepRow.hintSink = null;
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
        // A fixed-width column in the middle of the screen, so a value sits beside its label
        // instead of at the far edge of a wide picture, with a note about the focused row
        // to its right when there is room for one.
        android.widget.FrameLayout frame = new android.widget.FrameLayout(c);
        int screen = getResources().getDisplayMetrics().widthPixels;
        int side = Ui.dp(c, 260);
        boolean beside = screen >= Ui.dp(c, 640) + side;
        int wide = Math.min(screen - (beside ? side : 0), Ui.dp(c, 640));
        LinearLayout pair = Ui.row(c);
        pair.setClipChildren(false);
        frame.setClipChildren(false);
        pairView = pair;
        pair.addView(root, new LinearLayout.LayoutParams(wide,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        hintView = Ui.text(c, "", 15f, Ui.DIM);
        hintView.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), 0);
        if (beside) {
            pair.addView(hintView, new LinearLayout.LayoutParams(side,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        frame.addView(pair, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_HORIZONTAL));
        scroll.addView(frame, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(Ui.text(c, "FanLab", 18f, Ui.DIM), Ui.wrap());
        readout = Ui.mono(c, "", 19f, Ui.FG);
        root.addView(readout, Ui.wrap());
        statusView = Ui.mono(c, "", 16f, Ui.DIM);
        LinearLayout.LayoutParams sl = Ui.wrap();
        sl.bottomMargin = Ui.dp(c, 12);
        root.addView(statusView, sl);

        fanRow = addRow(root, new StepRow(c, "Fan").button().tag("fan", 0).hint("How the fan is controlled.\n\nAuto 58 holds the LED just under its target. Auto Step follows a fixed curve. Curve uses the preset chosen under Advanced. Stock hands the fan back to Philips."));
        brightRow = addRow(root, new StepRow(c, "Brightness boost").button().tag("bright", 0).hint("Runs the LEDs harder for a brighter picture.\n\nMore heat, so more fan. Turns itself off above 62 °C."));
        bootRow = addRow(root, new StepRow(c, "Start on boot").button().tag("boot", 0).hint("Start FanLab when the projector powers on.\n\nOff means Philips controls the fan after a restart."));
        addRow(root, new StepRow(c, "White screen test (full load)").button().tag("white", 0).hint("Full white picture, the heaviest load.\n\nUse it to hear the fan at its worst. Any key exits."))
                .display(">");
        addRow(root, new StepRow(c, "Unattended test (" + WhiteActivity.planMinutes() + " min)").button()
                .tag("plan", 0).hint("Runs by itself on a white screen: Presentation, Normal and Eco on Auto 58, then Presentation and Normal on Auto Step.\n\nLeave a USB stick in. Back or OK stops it and puts your settings back."))
                .display(">");
        addRow(root, new StepRow(c, "Restore stock fan (do this before uninstalling)").button()
                .tag("restore", 0).hint("Gives the fan back to Philips for good.\n\nDo this before uninstalling.")).display(">");
        advancedRow = addRow(root, new StepRow(c, "Advanced").button().tag("advanced", 0).hint("Manual fan, measuring, logs and system switches."));
        advancedRow.display("+");
        advanced = new AdvancedPanel(this);
        advancedView = advanced.build();
        advancedView.setVisibility(View.GONE);
        root.addView(advancedView, Ui.wrap());
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
        return kind == Prefs.AUTO_STEP ? "Auto Step"
                : "Curve: " + CurveConfig.presetName(Prefs.preset(this));
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
        advanced.sync();
    }

    private void refresh() {
        if (advancedView.getVisibility() == View.VISIBLE) {
            advanced.refresh();
        }
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
            line = fanChoice().toLowerCase();
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
                chooseFan();
            } else if ("bright".equals(row.tagName)) {
                // Off and on again keeps the four levels set under Advanced; only an unset (stock)
                // table is filled with the defaults.
                if (Prefs.ledBoostOn(this)) {
                    Prefs.setLedDriveOn(this, false);
                } else {
                    if (Prefs.ledDrive(this).isStock()) {
                        Prefs.setLedDrive(this, LedDrive.Config.bright());
                    }
                    Prefs.setLedDriveOn(this, true);
                }
                FanService.poke(this, FanService.ACTION_REFRESH);
            } else if ("boot".equals(row.tagName)) {
                Prefs.setAutostart(this, !Prefs.autostart(this));
            } else if ("white".equals(row.tagName)) {
                startActivity(new android.content.Intent(this, WhiteActivity.class));
            } else if ("plan".equals(row.tagName)) {
                startActivity(new android.content.Intent(this, WhiteActivity.class)
                        .putExtra("plan", true));
            } else if ("advanced".equals(row.tagName)) {
                boolean open = advancedView.getVisibility() != View.VISIBLE;
                advancedView.setVisibility(open ? View.VISIBLE : View.GONE);
                advancedRow.display(open ? "-" : "+");
            } else if ("restore".equals(row.tagName)) {
                restoreStock();
            }
            sync();
        } catch (Throwable t) {
            say("Failed: " + t);
        }
    }

    /** The list behind the Fan row: up and down to move, OK to pick, BACK to leave it as it was. */
    private void chooseFan() {
        final int[] family = CurveConfig.presetsFor(Prefs.ledBoostOn(this));
        // The presets are one entry here; which preset is a choice under Advanced.
        final String[] names = new String[4];
        names[0] = "Stock";
        names[1] = "Auto " + (int) AutoHold.targetFor(Prefs.ledBoostOn(this));
        names[2] = "Auto Step";
        names[3] = "Curve: " + CurveConfig.presetName(Prefs.preset(this));
        int at = -1;
        String now = fanChoice();
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(now)) {
                at = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Fan")
                .setSingleChoiceItems(names, at, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            applyFan(which, family);
                        } catch (Throwable t) {
                            say("Failed: " + t);
                        }
                        dialog.dismiss();
                        sync();
                    }
                })
                .show();
    }

    private void applyFan(int which, int[] family) {
        if (which == 0) {
            release();
            return;
        }
        if (which == 1) {
            Prefs.setAutoKind(this, Prefs.AUTO_HOLD);
        } else if (which == 2) {
            Prefs.setAutoKind(this, Prefs.AUTO_STEP);
        } else {
            Prefs.setAutoKind(this, Prefs.AUTO_NONE);
        }
        Prefs.setMode(this, Mode.CURVE);
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

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        sync();
    }

    private void say(String msg) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show();
    }
}
