package com.daleygames.fanlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
 * VERIFY - hold one duty, in one mode, and look at the picture.
 *
 * AUTO answers "what temperature does this duty reach". VERIFY answers "is that actually
 * acceptable", and that is the question the target is phrased in:
 *
 * <blockquote>as quiet as possible, subject to: the image stays sharp over a long run in
 * the warmest room you actually use, and the projector never shuts down.</blockquote>
 *
 * The binding constraint on this projector is not a component limit - nothing in the DMD,
 * DLPC3436 or LED datasheets constrains anything near the 46 C the stock controller
 * triggers at. It is focus stability. Philips said so in writing: the Presentation fan
 * speed was raised because "the heat was too high for the optical engine and the metal
 * inside was shapeshifting by few mm and the image was getting unclear". That failure mode
 * has two properties that make it tractable - it is <b>observable</b>, and it is
 * <b>reversible</b> - so the honest way to confirm a candidate floor is to run at it, in
 * Presentation, in the warmest room, for forty-five minutes, and look at a fine pattern
 * every so often.
 *
 * Deliberately much simpler than AUTO: one duty, one mode, one pattern toggle, and a log.
 * Every look is recorded with the time, the temperature and the verdict.
 */
public class VerifyActivity extends Activity implements StepRow.Listener {

    private static final int POLL_MS = 500;
    private static final int INK = 0xFF6E6E6E;
    private static final int INK_STRONG = 0xFF303030;

    private static final int[] MODES = {3, 2, 1, 4};

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private FieldView field;
    private ScrollView setupView;
    private LinearLayout overlay;

    private TextView headline;
    private TextView detail;
    private TextView keys;
    private TextView lastVerdict;

    private StepRow dutyRow;
    private StepRow modeRow;
    private StepRow startRow;
    private TextView setupTemp;

    private int modeIdx;
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
        if (running) {
            stopHold("screen left");
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (running) {
            stopHold("activity destroyed");
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

        setupView = buildSetup();
        root.addView(setupView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = buildOverlay();
        overlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.gravity = Gravity.BOTTOM;
        root.addView(overlay, olp);
    }

    private ScrollView buildSetup() {
        Context c = this;
        ScrollView scroll = new ScrollView(c);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout col = Ui.column(c);
        int p = Ui.dp(c, 16);
        col.setPadding(p, p, p, p);
        scroll.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(Ui.text(c, "VERIFY — hold one duty and watch the picture", 26f, Ui.FG),
                Ui.wrap());
        col.addView(Ui.body(c,
                "AUTO tells you what temperature a duty reaches. This tells you whether "
                        + "that is acceptable, which is the question that actually matters. "
                        + "Run it at the floor you are thinking of, in Presentation, in the "
                        + "warmest room you use, for forty-five minutes or more, and check "
                        + "the picture a few times."), Ui.wrap());

        col.addView(Ui.heading(c, "What to hold"), Ui.wrap());
        dutyRow = new StepRow(c, "Fan duty").range(SweepPlan.DUTY_FLOOR,
                FanIo.FAIL_SAFE_DUTY).steps(1, 5).suffix(" %").tag("duty", 0);
        dutyRow.set(62);
        addRow(col, dutyRow);
        modeRow = new StepRow(c, "Brightness mode").button().tag("mode", 0);
        modeRow.display(SweepPlan.modeName(MODES[modeIdx]));
        addRow(col, modeRow);
        col.addView(Ui.body(c,
                "62 % in Presentation is the default because that is what the curve "
                        + "commands at the temperature this machine actually parks at, and "
                        + "it sits inside the 56–69 band the firmware bisection had already "
                        + "narrowed the equilibrium to. Changing the brightness mode here "
                        + "writes rgblevel directly, which also changes the LED current — "
                        + "set the mode once from the remote afterwards so the projector's "
                        + "own app agrees with the hardware again."), Ui.wrap());

        col.addView(Ui.heading(c, "While it runs"), Ui.wrap());
        col.addView(bullet(c, "OK cycles the picture: white field → one-pixel "
                + "checkerboard → one-pixel grating → white. The two patterns sit right at "
                + "the display's resolution limit, so they look crisp in focus and turn to "
                + "flat grey the moment the optics drift."), Ui.wrap());
        col.addView(bullet(c, "When you are satisfied with the held duty, MENU hands the fan "
                + "to your curve and watches it for twelve minutes. A held duty cannot hunt "
                + "- only a curve choosing its own speed can - so that second phase is the "
                + "one that tells you whether you will hear the fan move."), Ui.wrap());
        col.addView(bullet(c, "UP says the picture is sharp, DOWN says it has gone soft. "
                + "RIGHT says the fan is audible, LEFT says it is quiet. Each one is "
                + "logged with the time and the temperature at that instant."), Ui.wrap());
        col.addView(bullet(c, "BACK stops and writes " + FanIo.FAIL_SAFE_DUTY + " %. It "
                + "also stops on its own at " + Sample.fmt1(SweepPlan.CEILING_C)
                + " °C, and the 75 °C shutdown and the fan-stall watchdog are untouched "
                + "underneath."), Ui.wrap());

        setupTemp = Ui.body(c, "");
        setupTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        col.addView(setupTemp, Ui.wrap());

        startRow = new StepRow(c, "START HOLDING").button().tag("start", 0);
        startRow.tint(0xFF1F4A2A).valueColour(Ui.GOOD).display("▶");
        addRow(col, startRow);
        addRow(col, new StepRow(c, "Go back — change nothing").button().tag("back", 0));
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

        lastVerdict = Ui.text(c, "", 20f, INK_STRONG);
        lastVerdict.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(lastVerdict, Ui.wrap());

        keys = Ui.text(c,
                "OK next pattern    ▲ sharp    ▼ soft    ▶ fan audible    ◀ fan quiet"
                        + "    MENU hand it to the curve"
                        + "    BACK stop", 20f, INK);
        keys.setGravity(Gravity.CENTER);
        int q = Ui.dp(c, 8);
        keys.setPadding(q * 2, q, q * 2, q);
        keys.setBackground(border(c, INK));
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.topMargin = Ui.dp(c, 10);
        klp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(keys, klp);
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
                start();
            } else if ("back".equals(row.tagName)) {
                finish();
            } else if ("mode".equals(row.tagName)) {
                modeIdx = (modeIdx + 1) % MODES.length;
                modeRow.display(SweepPlan.modeName(MODES[modeIdx]));
            }
        } catch (Throwable t) {
            say("Failed: " + t);
        }
    }

    private void start() {
        FanService s = FanService.instance;
        if (s == null) {
            FanService.poke(this, FanService.ACTION_START);
            say(serviceWaits++ < 5
                    ? "The telemetry service is still starting. Press START again in a "
                      + "moment."
                    : "The telemetry service will not start. Go back to the main screen "
                      + "first, check the readouts are moving, then try again.");
            return;
        }
        if (!s.startVerify(dutyRow.get(), MODES[modeIdx])) {
            say(FanService.statusLine);
            return;
        }
        running = true;
        setupView.setVisibility(View.GONE);
        field.setPattern(FieldView.PATTERN_WHITE);
        field.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.VISIBLE);
        s.verifyPattern(field.patternName());
        lastVerdict.setText("");
    }

    private void stopHold(String reason) {
        running = false;
        FanService s = FanService.instance;
        if (s != null) {
            s.stopVerify(reason);
        } else {
            FanIo.writeFailSafe();
        }
    }

    private void stopAndShow() {
        stopHold("user");
        field.setVisibility(View.GONE);
        overlay.setVisibility(View.GONE);
        setupView.setVisibility(View.VISIBLE);
        if (startRow != null) {
            startRow.requestFocus();
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                say("Stopped. The fan has been written to " + FanIo.FAIL_SAFE_DUTY
                        + " %. Every check you made is in the files.\n\n"
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
            case KeyEvent.KEYCODE_BACK:
                stopAndShow();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                field.nextPattern();
                FanService s = FanService.instance;
                if (s != null) {
                    s.verifyPattern(field.patternName());
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                check(HoldSession.ABOUT_PATTERN, HoldSession.VERDICT_SHARP);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                check(HoldSession.ABOUT_PATTERN, HoldSession.VERDICT_SOFT);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                check(HoldSession.ABOUT_FAN, HoldSession.VERDICT_AUDIBLE);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                check(HoldSession.ABOUT_FAN, HoldSession.VERDICT_QUIET);
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_BUTTON_Y:
                handToCurve();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /**
     * Stop pinning the duty and let the stored curve drive, while the app counts whether
     * the fan then sits still.
     *
     * The held duty answered "is this acceptable". This answers "will you hear it move",
     * which a pinned duty cannot: a fan only hunts when something is choosing its speed
     * from a temperature that its own speed is changing.
     */
    private void handToCurve() {
        FanService s = FanService.instance;
        if (s == null) {
            say("The telemetry service has gone; cannot hand over.");
            return;
        }
        if (!s.beginSteadyPhase(HoldSession.STEADY_SECONDS)) {
            lastVerdict.setText(FanService.statusLine);
            return;
        }
        lastVerdict.setText("the curve is driving now - watching for "
                + (HoldSession.STEADY_SECONDS / 60) + " minutes. Leave it alone and listen.");
    }

    private void check(String about, String verdict) {
        long wall = System.currentTimeMillis();
        FanService s = FanService.instance;
        if (s != null) {
            s.verifyCheck(about, verdict, wall);
        }
        Sample sm = FanService.lastSample;
        lastVerdict.setText("logged: " + about + " = " + verdict + "  at "
                + (sm == null || Double.isNaN(sm.degC) ? "--" : Sample.fmt1(sm.degC))
                + " °C, pattern " + field.patternName());
    }

    // ------------------------------------------------------------------ refresh

    private void refresh() {
        if (!running) {
            Sample s = FanService.lastSample;
            double c = s == null ? Double.NaN : s.degC;
            setupTemp.setText(Double.isNaN(c)
                    ? "Waiting for the first temperature reading…"
                    : "LED is " + Sample.fmt1(c) + " °C, fan is at "
                      + (s.fanCtrl < 0 ? "--" : Integer.toString(s.fanCtrl)) + " %, "
                      + "brightness mode " + SweepPlan.modeName(s.rgblevel) + ".");
            return;
        }
        HoldSession h = FanService.holdSession;
        Sample s = FanService.lastSample;
        if (h == null) {
            running = false;
            field.setVisibility(View.GONE);
            overlay.setVisibility(View.GONE);
            setupView.setVisibility(View.VISIBLE);
            if (startRow != null) {
                startRow.requestFocus();
            }
            say(FanService.statusLine);
            return;
        }
        double c = s == null ? Double.NaN : s.degC;
        headline.setText(h.modeName() + "   duty " + h.duty() + " %   "
                + (Double.isNaN(c) ? "--" : Sample.fmt1(c)) + " °C");
        long el = h.elapsedSec(SystemClock.elapsedRealtime());
        StringBuilder sb = new StringBuilder();
        sb.append(field.patternName()).append("  ·  ").append(mmss(el)).append(" held");
        if (!Double.isNaN(h.minC())) {
            sb.append("  ·  range ").append(Sample.fmt1(h.minC())).append('–')
                    .append(Sample.fmt1(h.maxC())).append(" °C");
        }
        sb.append("  ·  ").append(h.checks().size()).append(" checks logged");
        if (h.foreignWrites() > 0) {
            sb.append("  ·  stock controller wrote ").append(h.foreignWrites())
                    .append(" time(s)");
        }
        detail.setText(sb.toString());
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
