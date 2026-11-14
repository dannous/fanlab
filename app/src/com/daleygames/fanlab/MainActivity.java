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

/** The main screen: four numbers, the fan mode and the one setting that mode has. Everything else is under Advanced. */
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
    private StepRow limitRow;
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
        Prefs.firstRun(this);
        Prefs.migrate(this);
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

        fanRow = addRow(root, new StepRow(c, "Fan mode").button().tag("fan", 0).hint("How the fan is controlled.\n\n"
                + "Curve to 55 °C: a simple fan curve that keeps the LED under 55 °C.\n\n"
                + "Curve to 58 °C: the same curve, allowed to 58 °C. Offered when Brightness boost is on.\n\n"
                + "Smart quiet: keeps the fan under your limit and dims the picture slightly when it must. It learns your projector as it runs. This is the one to use."));
        brightRow = addRow(root, new StepRow(c, "Brightness boost").button().tag("bright", 0)
                .hint("Runs the LEDs harder for a brighter picture, at the level you set for each mode under Advanced.\n\n"
                        + "More heat, so more fan. Off is Philips' own brightness. Above "
                        + (int) LedDrive.DEFAULT_TRIP_C + " °C it switches itself off."));
        limitRow = addRow(root, new StepRow(c, "Fan limit %").range(30, AutoHold.MAX_DUTY + 1).steps(1, 5).tag("limit", 0)
                .hint("Smart quiet keeps the fan at or under this. At the limit the picture dims slightly instead, never below Philips' own brightness.\n\n"
                        + "One exception, for safety: if the LED is 3 °C over its target, or there is nothing left to dim, the fan goes above the limit until it cools.\n\n"
                        + "All the way right is Off."));
        bootRow = addRow(root, new StepRow(c, "Start on boot").button().tag("boot", 0).hint("Start FanLab when the projector powers on.\n\nOff means Philips controls the fan after a restart."));
        advancedRow = addRow(root, new StepRow(c, "Advanced").button().tag("advanced", 0).hint("Temperatures and fan floor, brightness of each mode, tests, measuring, logs, and giving the fan back to Philips."));
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

    static final int FAN_CURVE55 = 0, FAN_CURVE58 = 1, FAN_SMART = 2, FAN_MANUAL = 3;

    /** Which of the four choices is in force, or -1 for Stock or a setup from before 1.8.0. */
    private int fanIndex() {
        int mode = Prefs.mode(this);
        if (mode == Mode.MANUAL) {
            return FAN_MANUAL;
        }
        if (mode != Mode.CURVE) {
            return -1;
        }
        int kind = Prefs.autoKind(this);
        if (kind == Prefs.AUTO_HOLD) {
            return FAN_SMART;
        }
        return kind == Prefs.AUTO_STEP
                ? (Prefs.ledBoostOn(this) && Prefs.curveTarget(this) == 58 ? FAN_CURVE58 : FAN_CURVE55) : -1;
    }

    /** What the Fan mode row shows. */
    private String fanChoice() {
        switch (fanIndex()) {
            case FAN_CURVE55:
                return "Curve to 55 °C";
            case FAN_CURVE58:
                return "Curve to 58 °C";
            case FAN_SMART:
                return "Smart quiet";
            case FAN_MANUAL:
                return "Manual fan test · " + Prefs.manualDuty(this) + "%";
            default:
                return Prefs.mode(this) == Mode.OFF ? "Stock (Philips)" : "Other";
        }
    }
    private void sync() {
        int mode = Prefs.mode(this);
        fanRow.display(fanChoice());
        fanRow.valueColour(mode == Mode.OFF ? Ui.DIM
                : mode == Mode.CURVE ? Ui.GOOD : Ui.WARN);
        // Picking a fan mode is also how a manual fan test is ended.
        boolean bright = Prefs.ledBoostOn(this);
        brightRow.display(bright ? "On" : "Off");
        brightRow.valueColour(bright ? Ui.WARN : Ui.DIM);
        int lim = Prefs.fanLimit(this);
        limitRow.set(lim == 0 ? AutoHold.MAX_DUTY + 1 : lim);
        limitRow.display(lim == 0 ? "Off" : lim + "%");
        limitRow.valueColour(lim == 0 ? Ui.DIM : Ui.ACCENT);
        // Only the rows that mean something in the mode in force are shown.
        limitRow.setVisibility(fanIndex() == FAN_SMART ? View.VISIBLE : View.GONE);
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
            String lim = FanService.limitLine;
            line = "smart quiet, target " + (int) FanService.autoTargetNow + " C" + (lim.length() > 0 ? " · " + lim : "");
            colour = lim.length() > 0 ? Ui.WARN : colour;
        } else if (Prefs.mode(this) == Mode.CURVE && Prefs.autoKind(this) == Prefs.AUTO_STEP) {
            line = "curve to " + (int) FanService.autoTargetNow + " C" + (FanService.autoTrimNow > 0
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
                return "Night";
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
                // Off and on again keeps the levels set under Advanced; only an unset table is filled with the defaults.
                if (Prefs.ledBoostOn(this)) {
                    Prefs.setLedDriveOn(this, false);
                } else {
                    if (Prefs.ledDrive(this).isStock()) {
                        Prefs.setLedDrive(this, LedDrive.Config.bright());
                    }
                    Prefs.setLedDriveOn(this, true);
                }
                FanService.poke(this, FanService.ACTION_REFRESH);
            } else if ("limit".equals(row.tagName)) {
                int v = row.get();
                Prefs.setFanLimit(this, v > AutoHold.MAX_DUTY ? 0 : v);
                FanService.poke(this, FanService.ACTION_REFRESH);
            } else if ("boot".equals(row.tagName)) {
                Prefs.setAutostart(this, !Prefs.autostart(this));
            } else if ("advanced".equals(row.tagName)) {
                boolean open = advancedView.getVisibility() != View.VISIBLE;
                advancedView.setVisibility(open ? View.VISIBLE : View.GONE);
                advancedRow.display(open ? "-" : "+");
            }
            sync();
        } catch (Throwable t) {
            say("Failed: " + t);
        }
    }

    /** The list behind the Fan mode row: up and down to move, OK to pick, BACK to leave it as it was. */
    private void chooseFan() {
        // Curve to 58 is only offered with the brightness boost on: at Philips' brightness the curve keeps to Philips' 55.
        final boolean boost = Prefs.ledBoostOn(this);
        final int[] ids = boost ? new int[] {FAN_SMART, FAN_CURVE55, FAN_CURVE58}
                : new int[] {FAN_SMART, FAN_CURVE55};
        final String[] names = new String[ids.length];
        int at = -1;
        for (int i = 0; i < ids.length; i++) {
            names[i] = ids[i] == FAN_CURVE55 ? "Curve to 55 °C"
                    : ids[i] == FAN_CURVE58 ? "Curve to 58 °C"
                    : "Smart quiet  ·  fan under the limit";
            if (ids[i] == fanIndex()) {
                at = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Fan mode")
                .setSingleChoiceItems(names, at, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            applyFan(ids[which]);
                        } catch (Throwable t) {
                            say("Failed: " + t);
                        }
                        dialog.dismiss();
                        sync();
                    }
                })
                .show();
    }

    /** The fan mode leaves the brightness boost alone; that is its own switch. */
    private void applyFan(int which) {
        if (which == FAN_MANUAL) {
            Prefs.setMode(this, Mode.MANUAL);
        } else {
            if (which != FAN_SMART) {
                Prefs.setCurveTarget(this, which == FAN_CURVE58 ? 58 : 55);
            }
            Prefs.setAutoKind(this, which == FAN_SMART ? Prefs.AUTO_HOLD : Prefs.AUTO_STEP);
            Prefs.setMode(this, Mode.CURVE);
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

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        sync();
    }

    private void say(String msg) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show();
    }
}
