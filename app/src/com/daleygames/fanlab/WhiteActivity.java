package com.daleygames.fanlab;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * A full white field: the heaviest load the light engine sees, for hearing the fan under it.
 *
 * Opened plainly it changes nothing. Opened with the {@code plan} extra it walks through
 * {@link #PLAN} unattended, setting the brightness mode and the Auto in force for each step,
 * and puts everything back when it ends or any key is pressed.
 */
public class WhiteActivity extends Activity {

    /** Steps of the unattended run: rgblevel, {@link Prefs} auto kind, minutes. Presentation first, while the light engine is coolest. */
    static final int[][] PLAN = {
            {3, Prefs.AUTO_HOLD, 40},
            {2, Prefs.AUTO_HOLD, 20},
            {1, Prefs.AUTO_HOLD, 10},
            {3, Prefs.AUTO_STEP, 25},
            {2, Prefs.AUTO_STEP, 15},
    };

    static int planMinutes() {
        int m = 0;
        for (int i = 0; i < PLAN.length; i++) {
            m += PLAN[i][2];
        }
        return m;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextView readout;
    private long startedMs;

    private boolean plan;
    private boolean finished;
    private int step = -1;
    private long stepStartedMs;

    private int savedMode;
    private int savedAuto;
    private int savedLevel = -1;
    private boolean savedBoost;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Throwable t) {
                readout.setText("error: " + t);
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFFFFFFFF);
        readout = Ui.mono(this, "", 14f, 0xFF808080);
        int p = Ui.dp(this, 12);
        readout.setPadding(p, p, p, p);
        root.addView(readout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END));
        setContentView(root);
        startedMs = System.currentTimeMillis();
        plan = getIntent() != null && getIntent().getBooleanExtra("plan", false);
        if (plan) {
            savedMode = Prefs.mode(this);
            savedAuto = Prefs.autoKind(this);
            savedBoost = Prefs.ledBoostOn(this);
            Sample s = FanService.lastSample;
            savedLevel = s == null ? -1 : s.rgblevel;
            if (!savedBoost) {
                Prefs.setLedDrive(this, LedDrive.Config.bright());
                Prefs.setLedDriveOn(this, true);
            }
            FanService.mark("plan start");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        restore("closed");
        super.onDestroy();
    }

    private static String levelName(int level) {
        return level == 3 ? "Presentation" : level == 2 ? "Normal" : level == 1 ? "Eco" : "Super Eco";
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Sample s = FanService.lastSample;
        String live = s == null ? "..." : "LED " + (Double.isNaN(s.degC) ? "--"
                : Sample.fmt1(s.degC)) + " C   FAN " + (s.fanCtrl < 0 ? "--" : s.fanCtrl + "%");
        long totalMin = (now - startedMs) / 60000L;
        if (finished) {
            return;
        }
        if (!plan) {
            readout.setText(live + "   " + totalMin + " min   any key to exit");
            return;
        }
        if (step < 0 || now - stepStartedMs >= PLAN[step][2] * 60000L) {
            step++;
            if (step >= PLAN.length) {
                finished = true;
                restore("finished");
                root.setBackgroundColor(0xFF000000);
                readout.setText("Test finished after " + totalMin + " min. Any key to exit.");
                return;
            }
            stepStartedMs = now;
            begin(PLAN[step][0], PLAN[step][1]);
        }
        // The projector's own menu can change the mode under us; put it back.
        if (s != null && s.rgblevel >= 0 && s.rgblevel != PLAN[step][0]
                && now - stepStartedMs > 8000L && (now / 1000L) % 10L == 0L) {
            writeLevel(PLAN[step][0]);
        }
        readout.setText("STEP " + (step + 1) + "/" + PLAN.length + "  " + levelName(PLAN[step][0])
                + ", " + (PLAN[step][1] == Prefs.AUTO_HOLD ? "Auto 58" : "Auto Step") + "  "
                + (now - stepStartedMs) / 60000L + "/" + PLAN[step][2] + " min   " + live
                + "   total " + totalMin + "/" + planMinutes() + " min   any key stops");
    }

    private void begin(int level, int autoKind) {
        Prefs.setAutoKind(this, autoKind);
        Prefs.setMode(this, Mode.CURVE);
        FanService.poke(this, FanService.ACTION_REFRESH);
        writeLevel(level);
        FanService.mark("plan step " + (step + 1) + " " + levelName(level) + " "
                + (autoKind == Prefs.AUTO_HOLD ? "hold" : "step"));
    }

    private static void writeLevel(final int level) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Sysfs.write(Sysfs.RGBLEVEL, Integer.toString(level));
            }
        }, "fanlab-plan-level").start();
    }

    /** Put back whatever the run changed. Safe to call twice. */
    private void restore(String why) {
        if (!plan) {
            return;
        }
        plan = false;
        Prefs.setAutoKind(this, savedAuto);
        if (!savedBoost) {
            Prefs.resetLedDrive(this);
        }
        FanService svc = FanService.instance;
        if (savedMode == Mode.OFF && svc != null) {
            // Stock was in charge before the run: hand back through the path that writes the fail-safe first.
            svc.releaseControl();
        } else {
            Prefs.setMode(this, savedMode);
            FanService.poke(this, FanService.ACTION_REFRESH);
        }
        if (savedLevel >= 1 && savedLevel <= 4) {
            writeLevel(savedLevel);
        }
        FanService.mark("plan " + why);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // The OK release that opened this screen lands here too; do not let it close it.
        if (System.currentTimeMillis() - startedMs < 800L) {
            return true;
        }
        finish();
        return true;
    }
}
