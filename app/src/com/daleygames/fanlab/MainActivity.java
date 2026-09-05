package com.daleygames.fanlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * The main screen: the live telemetry, the fan slider, and the switches.
 *
 * The readouts are the point of the whole app. On a machine with no adb and no shell,
 * this is the only way to see what the LED temperature is actually doing, which is the
 * measurement the entire fan investigation has been guessing at.
 */
public class MainActivity extends Activity implements StepRow.Listener {

    private static final int POLL_MS = 250;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Ui.Tile tempTile;
    private Ui.Tile adcTile;
    private Ui.Tile propTile;
    private Ui.Tile fanTile;
    private Ui.Tile levelTile;
    private Ui.Tile engineTile;
    private Ui.Tile targetTile;
    private Ui.Tile rowsTile;

    private TextView statusView;
    private TextView pathsView;
    private TextView bannerView;

    private StepRow modeRow;
    private StepRow reassertRow;
    private StepRow loggingRow;
    private StepRow autostartRow;
    private StepRow takeoverRow;
    private StepRow restoreRow;
    private StepRow storageRow;
    private SeekBar slider;
    private TextView sliderValue;

    private boolean systemVariant;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                refresh();
            } catch (Throwable t) {
                // the UI must never take the app down
                statusView.setText("UI error: " + t);
            }
            ui.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        systemVariant = Process.myUid() == Process.SYSTEM_UID;
        setContentView(buildUi());
        // Deliberately no permission request here. The CSV goes to app-private external
        // directories, which need no permission at all, and a system permission dialog
        // that a user with only an IR remote could not dismiss would make the app
        // unusable. The extra copy in shared storage is opt-in, on its own row.
        FanService.poke(this, FanService.ACTION_START);
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncControlsFromPrefs();
        // Land the focus on the mode row, not on the slider. The slider takes manual
        // control the moment it is nudged, and the first thing a remote does on an
        // unfamiliar screen is nudge something.
        if (modeRow != null) {
            modeRow.requestFocus();
        }
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(poll);
        super.onPause();
    }

    // ------------------------------------------------------------------ layout

    private View buildUi() {
        Context c = this;
        ScrollView scroll = new ScrollView(c);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(c);
        int p = Ui.dp(c, 16);
        root.setPadding(p, p, p, p);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = Ui.text(c, "FanLab — SCN350", 26f, Ui.FG);
        root.addView(title, Ui.wrap());

        bannerView = Ui.text(c,
                systemVariant
                        ? "SYSTEM build — running as uid " + Process.myUid()
                          + ", system properties available"
                        : "PLAIN build — uid " + Process.myUid()
                          + ", sysfs only, no system properties",
                13f, systemVariant ? Ui.GOOD : Ui.DIM);
        root.addView(bannerView, Ui.wrap());

        // ---- telemetry tiles ----
        root.addView(Ui.heading(c, "Live — sampled every second"), Ui.wrap());
        tempTile = new Ui.Tile(c, "LED °C (computed)");
        adcTile = new Ui.Tile(c, "ADC raw");
        propTile = new Ui.Tile(c, "persist.sys.led.temperature");
        fanTile = new Ui.Tile(c, "fan_ctrl read back");
        levelTile = new Ui.Tile(c, "rgblevel");
        engineTile = new Ui.Tile(c, "led_status");
        targetTile = new Ui.Tile(c, "target duty");
        rowsTile = new Ui.Tile(c, "CSV rows");
        Ui.grid(c, root, new View[]{tempTile, fanTile, targetTile, propTile}, 4);
        Ui.grid(c, root, new View[]{adcTile, levelTile, engineTile, rowsTile}, 4);

        statusView = Ui.body(c, "");
        statusView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
        LinearLayout.LayoutParams slp = Ui.wrap();
        slp.topMargin = Ui.dp(c, 8);
        root.addView(statusView, slp);

        pathsView = Ui.body(c, "");
        root.addView(pathsView, Ui.wrap());

        // ---- the slider ----
        root.addView(Ui.heading(c, "Fan duty — left / right on the remote"), Ui.wrap());
        LinearLayout sliderBox = Ui.column(c);
        sliderBox.setBackground(Ui.panel(c, Ui.PANEL));
        int sp = Ui.dp(c, 10);
        sliderBox.setPadding(sp, sp, sp, sp);
        sliderValue = Ui.text(c, "55 %", 44f, Ui.ACCENT);
        sliderValue.setGravity(Gravity.CENTER_HORIZONTAL);
        sliderBox.addView(sliderValue, Ui.wrap());
        slider = new SeekBar(c);
        slider.setMax(FanIo.MAX_DUTY - FanIo.MIN_DUTY);
        slider.setKeyProgressIncrement(1);
        slider.setFocusable(true);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int duty = progress + FanIo.MIN_DUTY;
                sliderValue.setText(duty + " %");
                if (fromUser) {
                    Prefs.setManualDuty(MainActivity.this, duty);
                    // Moving the slider is the request. Take manual control so the value
                    // actually reaches the hardware; the mode row shows what happened.
                    if (Prefs.mode(MainActivity.this) != Mode.MANUAL) {
                        Prefs.setMode(MainActivity.this, Mode.MANUAL);
                        syncControlsFromPrefs();
                    }
                    FanService.poke(MainActivity.this, FanService.ACTION_REFRESH);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        LinearLayout.LayoutParams blp = Ui.wrap();
        blp.topMargin = Ui.dp(c, 6);
        sliderBox.addView(slider, blp);
        sliderBox.addView(Ui.body(c,
                "Moving this takes MANUAL control and writes /sys/class/fan_int/fan_ctrl "
                        + "immediately."), Ui.wrap());
        LinearLayout.LayoutParams sbl = Ui.wrap();
        sbl.topMargin = Ui.dp(c, 6);
        root.addView(sliderBox, sbl);

        // ---- controls ----
        root.addView(Ui.heading(c, "Control"), Ui.wrap());
        modeRow = addRow(root, new StepRow(c, "Mode").button().tag("mode", 0));
        reassertRow = addRow(root, new StepRow(c,
                "Re-assert every second (beat the stock controller)").button()
                .tag("reassert", 0));
        addRow(root, new StepRow(c, "Curve settings…").button().tag("curve", 0));

        root.addView(Ui.heading(c, "Measure"), Ui.wrap());
        StepRow auto = new StepRow(c,
                "AUTO — measure the steady-state temperature at every duty…").button()
                .tag("auto", 0);
        auto.tint(0xFF14313F).valueColour(Ui.ACCENT).display("▶");
        addRow(root, auto);
        StepRow verify = new StepRow(c,
                "VERIFY — hold one duty and check the picture stays sharp…").button()
                .tag("verify", 0);
        verify.tint(0xFF14313F).valueColour(Ui.ACCENT).display("▶");
        addRow(root, verify);
        root.addView(Ui.body(c,
                "AUTO runs unattended for about "
                        + (SweepPlan.estimateBestSeconds() / 60) + "–"
                        + (SweepPlan.estimateTypicalSeconds() / 60) + " minutes with a "
                        + "white screen and writes sweep_<time>.json and trace_<time>.csv. It is "
                        + "the measurement the fan floor is currently a guess in place of. "
                        + "VERIFY holds one duty for as long as you like so you can watch "
                        + "for the image going soft, which is the thing the fan speed is "
                        + "really protecting."), Ui.wrap());

        StepRow release = new StepRow(c, "RELEASE CONTROL — hand the fan back at "
                + FanIo.FAIL_SAFE_DUTY + " %").button().tag("release", 0);
        release.tint(0xFF5A1F1F).valueColour(Ui.DANGER).display("▶");
        addRow(root, release);

        root.addView(Ui.heading(c, "Logging"), Ui.wrap());
        loggingRow = addRow(root, new StepRow(c, "Write CSV telemetry").button()
                .tag("logging", 0));
        autostartRow = addRow(root, new StepRow(c, "Start automatically after a reboot")
                .button().tag("autostart", 0));
        storageRow = addRow(root, new StepRow(c,
                "Also copy to /storage/emulated/0/FanLab (asks for permission)")
                .button().tag("storage", 0));
        addRow(root, new StepRow(c, "Diagnostics…").button().tag("diag", 0));
        root.addView(Ui.body(c,
                "The CSV always goes to this app's own folder on internal storage and on "
                        + "any USB stick that is plugged in — no permission needed. Look "
                        + "for Android/data/" + getPackageName() + "/files/fanlab/ on the "
                        + "stick."), Ui.wrap());

        if (systemVariant) {
            root.addView(Ui.heading(c, "System build only — permanent changes"), Ui.wrap());
            takeoverRow = addRow(root, new StepRow(c,
                    "Take over: stop the stock fan controller").button().tag("takeover", 0));
            takeoverRow.tint(0xFF4A3A12);
            restoreRow = addRow(root, new StepRow(c,
                    "RESTORE STOCK FAN CONTROL").button().tag("restore", 0));
            restoreRow.tint(0xFF1F4A2A).valueColour(Ui.GOOD);
            addRow(root, new StepRow(c, "ADB over network — advanced…").button()
                    .tag("adb", 0));
            root.addView(Ui.body(c,
                    "persist.sys.fanctrl.by.temperatue lives in /data. Setting it to 0 "
                            + "survives a reboot AND an uninstall of this app. Nothing else "
                            + "on the projector will turn it back on. Use RESTORE STOCK FAN "
                            + "CONTROL above to undo it."), Ui.wrap());
        }

        root.addView(Ui.heading(c, "What to expect"), Ui.wrap());
        root.addView(Ui.body(c, systemVariant
                ? "Once you press \"Take over\" the projector's own fan ladder stops "
                  + "writing and this app is alone on the node."
                : "The projector's own fan controller is still running. It writes "
                  + "fan_ctrl whenever the rounded temperature changes — at most once "
                  + "every 15 seconds, often much less. With re-assertion ON this app "
                  + "puts its own value back within a second, so you may hear one brief "
                  + "excursion when the temperature crosses a whole degree. Only the "
                  + "system build can silence the stock controller for good."), Ui.wrap());

        root.addView(Ui.heading(c, "Safety"), Ui.wrap());
        root.addView(Ui.body(c,
                "Only values 1–100 are ever written. Any read error, implausible "
                        + "temperature or internal fault writes " + FanIo.FAIL_SAFE_DUTY
                        + " %, never a low value. Stopping the service writes "
                        + FanIo.FAIL_SAFE_DUTY + " % on the way out. The 75 °C shutdown "
                        + "and the kernel fan-stall watchdog are untouched. After the "
                        + "projector wakes from standby the driver puts the fan back to "
                        + "55 % by itself; this app notices within a second and ramps "
                        + "back to where the curve wants it."), Ui.wrap());

        return scroll;
    }

    private StepRow addRow(LinearLayout parent, StepRow row) {
        row.listen(this);
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.topMargin = Ui.dp(this, 6);
        parent.addView(row, lp);
        return row;
    }

    // ------------------------------------------------------------------ state

    private void syncControlsFromPrefs() {
        int mode = Prefs.mode(this);
        if (modeRow != null) {
            modeRow.display(Mode.name(mode));
            modeRow.valueColour(mode == Mode.OFF ? Ui.DIM
                    : mode == Mode.MANUAL ? Ui.WARN : Ui.GOOD);
            modeRow.label(mode == Mode.OFF
                    ? "Mode — OFF: observing only, the fan is not touched"
                    : mode == Mode.MANUAL
                    ? "Mode — MANUAL: holding the slider value"
                    : "Mode — CURVE: following the temperature curve");
        }
        if (reassertRow != null) {
            boolean r = Prefs.reassert(this);
            reassertRow.display(r ? "ON" : "OFF");
            reassertRow.valueColour(r ? Ui.GOOD : Ui.DIM);
        }
        if (loggingRow != null) {
            boolean l = Prefs.logging(this);
            loggingRow.display(l ? "ON" : "OFF");
            loggingRow.valueColour(l ? Ui.GOOD : Ui.DIM);
        }
        if (autostartRow != null) {
            boolean a = Prefs.autostart(this);
            autostartRow.display(a ? "ON" : "OFF");
            autostartRow.valueColour(a ? Ui.WARN : Ui.DIM);
        }
        if (storageRow != null) {
            boolean g = storageGranted();
            storageRow.display(g ? "GRANTED" : "not granted");
            storageRow.valueColour(g ? Ui.GOOD : Ui.DIM);
        }
        if (slider != null) {
            int duty = Prefs.manualDuty(this);
            slider.setProgress(duty - FanIo.MIN_DUTY);
            sliderValue.setText(duty + " %");
        }
        if (systemVariant && takeoverRow != null) {
            String v = SysProps.get(SysProps.PROP_FANCTRL_BY_TEMP);
            boolean stockOff = v != null && "0".equals(v.trim());
            takeoverRow.display(stockOff ? "ALREADY OFF" : "stock is ON");
            takeoverRow.valueColour(stockOff ? Ui.WARN : Ui.DIM);
            restoreRow.display(stockOff ? "▶" : "already stock");
        }
    }

    private void refresh() {
        Sample s = FanService.lastSample;
        if (s == null) {
            statusView.setText("waiting for the first sample…  " + FanService.statusLine);
            return;
        }
        tempTile.set(Double.isNaN(s.degC) ? "--" : Sample.fmt1(s.degC),
                Double.isNaN(s.degC) ? Ui.DANGER
                        : s.degC >= 70 ? Ui.DANGER : s.degC >= 55 ? Ui.WARN : Ui.FG);
        adcTile.set(s.adc == Thermistor.BAD_ADC ? "--" : Integer.toString(s.adc));
        propTile.set(s.propLedTemp == null || s.propLedTemp.length() == 0
                ? "--" : s.propLedTemp.trim());
        fanTile.set(s.fanCtrl < 0 ? "--" : s.fanCtrl + "%");
        levelTile.set(s.rgblevel < 0 ? "--" : Integer.toString(s.rgblevel));
        levelTile.setCaption("rgblevel — " + levelName(s.rgblevel));
        engineTile.set(s.ledStatus < 0 ? "--" : Integer.toString(s.ledStatus));
        engineTile.setCaption(s.ledStatus == 0 ? "light engine OFF" : "led_status");
        targetTile.set(s.desired < 0 ? "--" : s.desired + "%",
                s.desired < 0 ? Ui.DIM : Ui.ACCENT);
        rowsTile.set(Long.toString(FanService.csvLines));

        StringBuilder sb = new StringBuilder();
        if (FanService.sweepEngine != null) {
            sb.append("● AUTO IS RUNNING — ").append(FanService.sweepLine).append("\n");
        } else if (FanService.holdSession != null) {
            sb.append("● VERIFY IS RUNNING — ").append(FanService.sweepLine).append("\n");
        }
        sb.append(FanService.statusLine);
        if (FanService.failSafeLatched) {
            sb.append("   ● FAIL-SAFE ACTIVE (").append(FanIo.FAIL_SAFE_DUTY).append("%)");
        }
        if (FanService.writeFailures > 0) {
            sb.append("   ● fan_ctrl write failures: ").append(FanService.writeFailures);
        }
        sb.append("   writes: ").append(FanService.writesDone);
        if (s.note != null && s.note.length() > 0) {
            sb.append("\n").append(s.note);
        }
        statusView.setText(sb.toString());
        statusView.setTextColor(FanService.failSafeLatched || FanService.writeFailures > 0
                ? Ui.WARN : Ui.DIM);

        StringBuilder ps = new StringBuilder("CSV → ");
        String[] paths = FanService.csvPaths;
        if (paths.length == 0) {
            ps.append("(none yet)");
        } else {
            for (int i = 0; i < paths.length; i++) {
                if (i > 0) {
                    ps.append("\n        ");
                }
                ps.append(paths[i]);
            }
        }
        pathsView.setText(ps.toString());
    }

    private static String levelName(int level) {
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
                return "unknown";
        }
    }

    // ------------------------------------------------------------------ actions

    @Override
    public void onStepRow(StepRow row) {
        try {
            if ("mode".equals(row.tagName)) {
                int mode = Prefs.mode(this);
                int next = mode == Mode.OFF ? Mode.CURVE
                        : mode == Mode.CURVE ? Mode.MANUAL : Mode.OFF;
                if (next == Mode.OFF) {
                    confirmOff();
                } else {
                    Prefs.setMode(this, next);
                    FanService.poke(this, FanService.ACTION_REFRESH);
                    syncControlsFromPrefs();
                }
            } else if ("reassert".equals(row.tagName)) {
                Prefs.setReassert(this, !Prefs.reassert(this));
                syncControlsFromPrefs();
            } else if ("logging".equals(row.tagName)) {
                Prefs.setLogging(this, !Prefs.logging(this));
                syncControlsFromPrefs();
            } else if ("autostart".equals(row.tagName)) {
                Prefs.setAutostart(this, !Prefs.autostart(this));
                syncControlsFromPrefs();
            } else if ("storage".equals(row.tagName)) {
                requestStoragePermission();
            } else if ("curve".equals(row.tagName)) {
                startActivity(new Intent(this, CurveActivity.class));
            } else if ("auto".equals(row.tagName)) {
                startActivity(new Intent(this, AutoActivity.class));
            } else if ("verify".equals(row.tagName)) {
                startActivity(new Intent(this, VerifyActivity.class));
            } else if ("diag".equals(row.tagName)) {
                startActivity(new Intent(this, DiagActivity.class));
            } else if ("adb".equals(row.tagName)) {
                startActivity(new Intent(this, AdbActivity.class));
            } else if ("release".equals(row.tagName)) {
                doRelease();
            } else if ("takeover".equals(row.tagName)) {
                confirmTakeover();
            } else if ("restore".equals(row.tagName)) {
                doRestoreStock();
            }
        } catch (Throwable t) {
            toastLike("Failed: " + t);
        }
    }

    private void confirmOff() {
        new AlertDialog.Builder(this)
                .setTitle("Stop controlling the fan?")
                .setMessage("OFF stops this app writing fan_ctrl and leaves the fan exactly "
                        + "where it is. The stock controller only writes when the rounded "
                        + "temperature changes, which can be minutes away.\n\n"
                        + "If you want the fan handed back in a state that is definitely "
                        + "safe, use RELEASE CONTROL instead — it writes "
                        + FanIo.FAIL_SAFE_DUTY + " %.")
                .setPositiveButton("Just stop writing", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setMode(MainActivity.this, Mode.OFF);
                        FanService.poke(MainActivity.this, FanService.ACTION_REFRESH);
                        syncControlsFromPrefs();
                    }
                })
                .setNeutralButton("Release at " + FanIo.FAIL_SAFE_DUTY + " %",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                doRelease();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doRelease() {
        FanService s = FanService.instance;
        if (s != null) {
            // A running session owns the fan; releasing has to stop it too, or the loop
            // would put its own duty back a second later.
            if (FanService.sweepEngine != null) {
                s.abortSweep("release control");
            }
            if (FanService.holdSession != null) {
                s.stopVerify("release control");
            }
            s.releaseControl();
        } else {
            // No service to ask, so do it here: setting the mode first means a later
            // start cannot immediately drive the fan back down.
            Prefs.setMode(this, Mode.OFF);
            FanIo.writeFailSafe();
            FanService.poke(this, FanService.ACTION_RELEASE);
        }
        syncControlsFromPrefs();
    }

    private void confirmTakeover() {
        new AlertDialog.Builder(this)
                .setTitle("Stop the stock fan controller?")
                .setMessage("This sets persist.sys.fanctrl.by.temperatue = 0.\n\n"
                        + "• The projector's own fan ladder stops writing fan_ctrl "
                        + "completely, so this app's curve is no longer fighting it.\n"
                        + "• The 75 °C over-temperature shutdown is NOT affected — "
                        + "it lives in the caller and still runs.\n"
                        + "• The kernel fan-stall watchdog is NOT affected.\n\n"
                        + "This is a persistent property in /data. It survives a reboot and "
                        + "it survives uninstalling this app. Until you press RESTORE STOCK "
                        + "FAN CONTROL, nothing but this app will regulate the fan.")
                .setPositiveButton("Take over", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        boolean ok = SysProps.set(SysProps.PROP_FANCTRL_BY_TEMP, "0");
                        toastLike(ok
                                ? "Stock fan controller disabled. This app is now the only "
                                  + "thing regulating the fan."
                                : "FAILED — the property was refused. This build is not "
                                  + "running as the system user.");
                        if (ok && Prefs.mode(MainActivity.this) == Mode.OFF) {
                            Prefs.setMode(MainActivity.this, Mode.CURVE);
                            FanService.poke(MainActivity.this, FanService.ACTION_REFRESH);
                        }
                        syncControlsFromPrefs();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doRestoreStock() {
        // Stop driving FIRST. The service now owns this property and re-asserts it every
        // 30 ticks while it is in CURVE, so setting it alone was undone within half a
        // minute -- after this dialog had already said it worked. DEPLOY.md tells the
        // owner to press this before uninstalling, which made it the worst place in the
        // app to have a control that silently does nothing.
        Prefs.setMode(this, Mode.OFF);
        FanService.poke(this, FanService.ACTION_RELEASE);
        boolean ok = SysProps.set(SysProps.PROP_FANCTRL_BY_TEMP, "1");
        toastLike(ok
                ? "Stock fan control restored. It takes effect on the controller's next "
                  + "15-second poll."
                : "FAILED — the property was refused.");
        syncControlsFromPrefs();
    }

    private void toastLike(String msg) {
        new AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    private boolean storageGranted() {
        try {
            return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestStoragePermission() {
        if (storageGranted()) {
            toastLike("Already granted. The extra copy appears at "
                    + "/storage/emulated/0/FanLab within 30 seconds.");
            return;
        }
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Ask for storage permission?")
                .setMessage("The projector will show its own permission dialog. Use the "
                        + "D-pad to choose, or press BACK to dismiss it.\n\n"
                        + "This is optional: the CSV is already being written to this "
                        + "app's own folders, including on a USB stick.")
                .setPositiveButton("Ask", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            requestPermissions(new String[]{
                                    "android.permission.WRITE_EXTERNAL_STORAGE"}, 1);
                        } catch (Throwable t) {
                            toastLike("Could not ask: " + t);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        // Nothing to do: the app-private directories work either way, and the extra copy
        // in /storage/emulated/0/FanLab is picked up on the service's next rescan.
        super.onRequestPermissionsResult(code, perms, results);
        syncControlsFromPrefs();
    }
}
