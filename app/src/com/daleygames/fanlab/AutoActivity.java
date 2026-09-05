package com.daleygames.fanlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * AUTO - the unattended thermal characterisation.
 *
 * One button, one fixed schedule, no options. The user presses AUTO, reads what it is
 * about to do, presses START, and walks away for about ninety minutes. What comes back is
 * a machine-readable file: {@code T_eq(duty, mode)} for twenty-eight held conditions across
 * all four brightness modes, which is the single measurement the whole fan project is
 * blocked on.
 *
 * <h3>The screen is the apparatus</h3>
 * A full-screen white field runs for the whole sweep, with a small mid-grey readout on top
 * of it. Grey on white rather than a dark panel, deliberately: the average picture level
 * has to stay near 100 % so the DLPC's content-adaptive dimming cannot quietly change the
 * LED drive part way through and make the second half of the run incomparable with the
 * first. {@code FLAG_KEEP_SCREEN_ON} is held for the duration, which is legitimate here
 * because the white field genuinely is the test apparatus.
 *
 * <h3>Keys, and why OK is the stop</h3>
 * The specification asked for two things that cannot share one key: a large, always-focused
 * ABORT, and "press OK when the fan first becomes audible". Safety takes OK - a focused
 * control that does not act on OK would be a trap, and an emergency stop must be the most
 * obvious key on the remote. So:
 * <ul>
 *   <li><b>OK, or BACK</b> - abort. Writes {@link FanIo#FAIL_SAFE_DUTY} first, instantly,
 *       before anything else happens.</li>
 *   <li><b>Up</b> - "the fan just became audible". <b>Down</b> - "it is clearly loud".
 *       Both timestamp the current duty, mode and temperature. Entirely optional: a sweep
 *       with no keypresses is still a valid sweep.</li>
 * </ul>
 * Both bindings are on screen the whole time, in letters big enough to read from a sofa.
 */
public class AutoActivity extends Activity implements StepRow.Listener {

    private static final int POLL_MS = 500;
    private static final int INK = 0xFF6E6E6E;
    private static final int INK_STRONG = 0xFF303030;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private FieldView field;
    private ScrollView confirmView;
    private LinearLayout overlay;

    private TextView headline;
    private TextView detail;
    private TextView markLine;
    private TextView abortBox;

    private TextView confirmTemp;
    private StepRow ambientRow;
    private StepRow startRow;

    private boolean running;
    private int serviceWaits;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                refresh();
            } catch (Throwable ignored) {
                // the UI must never take a thermal run down
            }
            ui.postDelayed(this, POLL_MS);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        setContentView(root);
        FanService.poke(this, FanService.ACTION_START);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(poll);
        ui.post(poll);
        if (!running && startRow != null) {
            startRow.requestFocus();
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(poll);
        // The white field has gone, so the run is no longer measuring what it claims to.
        // Stop it, at the fail-safe duty. This also covers the projector being switched
        // off part way through.
        if (running) {
            stopSweep("screen left");
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (running) {
            stopSweep("activity destroyed");
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        Context c = this;
        root = new FrameLayout(c);
        root.setBackgroundColor(Ui.BG);

        field = new FieldView(c);
        field.setVisibility(View.GONE);
        root.addView(field, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        confirmView = buildConfirm();
        root.addView(confirmView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = buildOverlay();
        overlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.gravity = Gravity.BOTTOM;
        root.addView(overlay, olp);
    }

    private ScrollView buildConfirm() {
        Context c = this;
        ScrollView scroll = new ScrollView(c);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout col = Ui.column(c);
        int p = Ui.dp(c, 16);
        col.setPadding(p, p, p, p);
        scroll.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(Ui.text(c, "AUTO — measure the steady-state temperature", 26f, Ui.FG),
                Ui.wrap());
        col.addView(Ui.body(c,
                "This is the measurement the whole fan project is waiting on: the "
                        + "temperature the LED actually settles at, at each fan duty, in "
                        + "each brightness mode. Until it exists the fan floor is a guess "
                        + "between fourteen candidates."), Ui.wrap());

        col.addView(Ui.heading(c, "Before you press start, read this"), Ui.wrap());
        long best = SweepPlan.estimateBestSeconds();
        long typical = SweepPlan.estimateTypicalSeconds();
        col.addView(bullet(c, "It takes about " + (best / 60) + " minutes if the "
                + "temperature settles quickly at every step, and up to about "
                + (typical / 60) + " if it does not. It stops itself after "
                + (SweepPlan.capSeconds() / 60) + " minutes at the very latest, whatever "
                + "happens, and writes out everything it measured up to that point."),
                Ui.wrap());
        col.addView(bullet(c, "The screen shows a plain white field for the whole run. "
                + "That is the test: it is the worst thermal case, and it is the same "
                + "picture every time, so two runs can be compared."), Ui.wrap());
        col.addView(bullet(c, "The projector will run warmer than usual and the fan will "
                + "get quieter than usual — that is the point. It will never be allowed "
                + "past " + Sample.fmt1(SweepPlan.CEILING_C) + " °C: at that temperature "
                + "the sweep writes " + FanIo.FAIL_SAFE_DUTY + " % and stops itself. The "
                + "projector's own 75 °C shutdown and its fan-stall watchdog are untouched "
                + "and are still watching underneath."), Ui.wrap());
        col.addView(bullet(c, "It changes the brightness mode four times by itself "
                + "(Presentation, then Normal, then Eco, then Super Eco). When it has "
                + "finished, set the brightness mode once from the remote — the "
                + "projector's own app will not know it moved."), Ui.wrap());
        col.addView(bullet(c, "You can stop it at any moment: press OK or BACK. The fan "
                + "goes straight to " + FanIo.FAIL_SAFE_DUTY + " %."), Ui.wrap());
        col.addView(bullet(c, "While it runs, press UP the moment the fan becomes "
                + "audible, and DOWN when it is clearly loud. That is the one thing the "
                + "projector cannot measure for itself, and it is half the answer. It is "
                + "optional — a run with no keypresses is still a good run."), Ui.wrap());

        col.addView(Ui.heading(c, "What it writes"), Ui.wrap());
        col.addView(Ui.body(c,
                "Two files, to this app's folder on internal storage and on any USB stick "
                        + "that is plugged in: sweep_<time>.json and trace_<time>.csv. "
                        + "Pull the stick out when it has finished; the files are complete "
                        + "to the second before. They are meant to be read on a computer, "
                        + "not here."), Ui.wrap());

        col.addView(Ui.heading(c, "Room temperature — optional, but it is the biggest "
                + "thing we cannot measure"), Ui.wrap());
        ambientRow = new StepRow(c, "Room temperature").range(0, 40).steps(1, 5)
                .tag("ambient", 0);
        ambientRow.set(0);
        ambientRow.display("not stated");
        addRow(col, ambientRow);

        confirmTemp = Ui.body(c, "");
        confirmTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        col.addView(confirmTemp, Ui.wrap());

        startRow = new StepRow(c, "START THE SWEEP").button().tag("start", 0);
        startRow.tint(0xFF1F4A2A).valueColour(Ui.GOOD).display("▶");
        addRow(col, startRow);

        StepRow back = new StepRow(c, "Go back — change nothing").button().tag("back", 0);
        addRow(col, back);

        return scroll;
    }

    private LinearLayout buildOverlay() {
        Context c = this;
        LinearLayout col = Ui.column(c);
        int p = Ui.dp(c, 18);
        col.setPadding(p, p, p, p);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        headline = Ui.text(c, "", 30f, INK_STRONG);
        headline.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(headline, Ui.wrap());

        detail = Ui.text(c, "", 20f, INK);
        detail.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(detail, Ui.wrap());

        markLine = Ui.text(c,
                "▲ the fan is now audible      ▼ it is clearly loud", 20f, INK);
        markLine.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams mlp = Ui.wrap();
        mlp.topMargin = Ui.dp(c, 6);
        col.addView(markLine, mlp);

        abortBox = Ui.text(c, "ABORT — press OK or BACK", 26f, INK_STRONG);
        abortBox.setGravity(Gravity.CENTER);
        int q = Ui.dp(c, 10);
        abortBox.setPadding(q * 2, q, q * 2, q);
        abortBox.setBackground(border(c, INK));
        // Focusable so it is unmistakably the thing OK acts on, but not clickable: every
        // key is handled by the activity, so UP and DOWN reach the audibility markers
        // instead of being swallowed by a focus search that has nowhere to go.
        abortBox.setFocusable(true);
        abortBox.setFocusableInTouchMode(false);
        abortBox.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                v.setBackground(border(v.getContext(), has ? INK_STRONG : INK));
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(c, 10);
        alp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(abortBox, alp);
        return col;
    }

    private static GradientDrawable border(Context c, int colour) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setStroke(Ui.dp(c, 3), colour);
        g.setCornerRadius(Ui.dp(c, 6));
        return g;
    }

    private TextView bullet(Context c, String s) {
        TextView t = Ui.body(c, "•  " + s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        t.setTextColor(Ui.FG);
        return t;
    }

    private StepRow addRow(LinearLayout parent, StepRow row) {
        row.listen(this);
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.topMargin = Ui.dp(this, 6);
        parent.addView(row, lp);
        return row;
    }

    // ------------------------------------------------------------------ actions

    @Override
    public void onStepRow(StepRow row) {
        try {
            if ("start".equals(row.tagName)) {
                startSweep();
            } else if ("back".equals(row.tagName)) {
                finish();
            } else if ("ambient".equals(row.tagName)) {
                int v = row.get();
                row.display(v == 0 ? "not stated" : v + " °C");
            }
        } catch (Throwable t) {
            say("Failed: " + t);
        }
    }

    private void startSweep() {
        FanService s = FanService.instance;
        if (s == null) {
            FanService.poke(this, FanService.ACTION_START);
            if (serviceWaits++ < 5) {
                say("The telemetry service is still starting. Press START again in a "
                        + "moment.");
            } else {
                say("The telemetry service will not start. Go back to the main screen "
                        + "first, check the readouts are moving, then try again.");
            }
            return;
        }
        int amb = ambientRow == null ? 0 : ambientRow.get();
        String note = amb == 0 ? ""
                : "room approximately " + amb + " C, entered on the projector";
        if (!s.startSweep(note)) {
            say(FanService.statusLine);
            return;
        }
        running = true;
        confirmView.setVisibility(View.GONE);
        field.setPattern(FieldView.PATTERN_WHITE);
        field.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.VISIBLE);
        abortBox.requestFocus();
    }

    private void stopSweep(String reason) {
        running = false;
        FanService s = FanService.instance;
        if (s != null) {
            s.abortSweep(reason);
        } else {
            FanIo.writeFailSafe();
        }
    }

    private void abortAndLeave() {
        stopSweep("user");
        // Show what happened rather than dropping straight back to the menu, so it is
        // obvious the fan was handed back.
        field.setVisibility(View.GONE);
        overlay.setVisibility(View.GONE);
        confirmView.setVisibility(View.VISIBLE);
        if (startRow != null) {
            startRow.requestFocus();
        }
        // The service closes the files on its own thread; give it a moment so the message
        // can name where they actually landed rather than saying "somewhere".
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                say("Stopped. The fan has been written to " + FanIo.FAIL_SAFE_DUTY + " %.\n\n"
                        + FanService.statusLine);
            }
        }, 900);
    }

    private void say(String msg) {
        try {
            new AlertDialog.Builder(this)
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Throwable ignored) {
            // a dialog failing must not matter
        }
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!running) {
            return super.onKeyDown(keyCode, event);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
                abortAndLeave();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                mark("audible");
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                mark("loud");
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void mark(String kind) {
        long wall = System.currentTimeMillis();
        FanService s = FanService.instance;
        if (s != null) {
            s.markSweep(kind, wall);
        }
        SweepEngine e = FanService.sweepEngine;
        markLine.setText("recorded: \"" + kind + "\" at duty "
                + (e == null ? "--" : Integer.toString(e.commandedDuty())) + " %"
                + (e == null ? "" : " in " + e.modeName())
                + "      ▲ audible   ▼ clearly loud");
    }

    // ------------------------------------------------------------------ refresh

    private void refresh() {
        if (!running) {
            Sample s = FanService.lastSample;
            double c = s == null ? Double.NaN : s.degC;
            String txt;
            if (Double.isNaN(c)) {
                txt = "Waiting for the first temperature reading…";
            } else if (c > SweepPlan.MAX_START_C) {
                txt = "Too warm to start: " + Sample.fmt1(c) + " °C. Let it cool below "
                        + Sample.fmt1(SweepPlan.MAX_START_C) + " °C first — a run that "
                        + "starts this warm would hit the ceiling and stop within minutes.";
            } else {
                txt = "Ready. LED is " + Sample.fmt1(c) + " °C, fan is at "
                        + (s.fanCtrl < 0 ? "--" : Integer.toString(s.fanCtrl)) + " %, "
                        + "brightness mode " + SweepPlan.modeName(s.rgblevel) + ".";
            }
            confirmTemp.setText(txt);
            return;
        }

        SweepEngine e = FanService.sweepEngine;
        Sample s = FanService.lastSample;
        if (e == null) {
            // The service finished or aborted the run underneath us.
            running = false;
            field.setVisibility(View.GONE);
            overlay.setVisibility(View.GONE);
            confirmView.setVisibility(View.VISIBLE);
            if (startRow != null) {
                startRow.requestFocus();
            }
            say(FanService.statusLine);
            return;
        }
        double c = s == null ? Double.NaN : s.degC;
        long now = android.os.SystemClock.elapsedRealtime();
        long elapsed = e.elapsedMs(now) / 1000L;
        long left = e.estimateRemainingSec(now);
        headline.setText(e.modeName() + "   duty " + e.commandedDuty() + " %   "
                + (Double.isNaN(c) ? "--" : Sample.fmt1(c)) + " °C");
        double tinf = e.projectedTInf();
        StringBuilder sb = new StringBuilder();
        sb.append("step ").append(e.stepNumber()).append(" of ").append(e.totalSteps());
        sb.append("  ·  ").append(SweepEngine.phaseName(e.phase()));
        sb.append("  ·  settling towards ");
        sb.append(Double.isNaN(tinf) ? "…" : Sample.fmt1(tinf) + " °C");
        sb.append("  ·  ").append(mmss(elapsed)).append(" elapsed, about ");
        sb.append(mmss(left)).append(" left");
        PicoReg.Reading d = FanService.lastDlpc;
        if (d != null && !PicoReg.STATUS_OK.equals(d.status)) {
            sb.append("\nDLPC temperature UNAVAILABLE — the sweep continues, but the "
                    + "controller-side reading is missing from this run");
        } else if (d != null) {
            sb.append("\nDLPC ").append(Sample.fmt1(d.degC)).append(" °C (provisional units)");
        }
        detail.setText(sb.toString());
        if (!abortBox.hasFocus()) {
            abortBox.requestFocus();
        }
    }

    private static String mmss(long sec) {
        if (sec < 0) {
            sec = 0;
        }
        long m = sec / 60;
        long ss = sec % 60;
        return m + ":" + (ss < 10 ? "0" : "") + ss;
    }
}
