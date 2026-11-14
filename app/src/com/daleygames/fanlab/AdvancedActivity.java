package com.daleygames.fanlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Everything the main screen leaves out: the other modes, measurement, logging and the system switches. */
public class AdvancedActivity extends Activity implements StepRow.Listener {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private StepRow modeRow;
    private TextView sliderLabel;
    private SeekBar slider;
    private StepRow ceilingRow;
    private StepRow reassertRow;
    private StepRow roomRow;
    private StepRow loggingRow;
    private StepRow storageRow;
    private StepRow exportRow;
    private StepRow takeoverRow;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        sync();
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

        root.addView(Ui.text(c, "FanLab / Advanced", 18f, Ui.DIM), Ui.wrap());
        statusView = Ui.mono(c, "", 16f, Ui.DIM);
        root.addView(statusView, Ui.wrap());

        root.addView(Ui.heading(c, "Control"), Ui.wrap());
        modeRow = add(root, new StepRow(c, "Mode").button().tag("mode", 0));

        LinearLayout box = Ui.column(c);
        box.setBackground(Ui.panel(c, Ui.PANEL));
        int bp = Ui.dp(c, 10);
        box.setPadding(bp, bp, bp, bp);
        sliderLabel = Ui.text(c, "Manual fan", 18f, Ui.FG);
        box.addView(sliderLabel, Ui.wrap());
        slider = new SeekBar(c);
        slider.setMax(FanIo.MAX_DUTY - FanIo.MIN_DUTY);
        slider.setKeyProgressIncrement(1);
        slider.setFocusable(true);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int duty = progress + FanIo.MIN_DUTY;
                sliderLabel.setText("Manual fan  " + duty + "%");
                if (fromUser) {
                    Prefs.setManualDuty(AdvancedActivity.this, duty);
                    Prefs.setMode(AdvancedActivity.this, Mode.MANUAL);
                    FanService.poke(AdvancedActivity.this, FanService.ACTION_REFRESH);
                    sync();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        box.addView(slider, Ui.wrap());
        LinearLayout.LayoutParams blp = Ui.wrap();
        blp.topMargin = Ui.dp(c, 1);
        root.addView(box, blp);

        ceilingRow = add(root, new StepRow(c, "Linear target °C")
                .range((int) LinearConfig.MIN_CEILING_C, (int) LinearConfig.MAX_CEILING_C)
                .steps(1, 5).tag("ceiling", 0));
        reassertRow = add(root, new StepRow(c, "Re-assert every second").button()
                .tag("reassert", 0));
        add(root, new StepRow(c, "Edit curve").button().tag("curve", 0)).display(">");

        root.addView(Ui.heading(c, "Measure"), Ui.wrap());
        add(root, new StepRow(c, "Sweep every fan speed").button().tag("sweep", 0))
                .display(">");
        add(root, new StepRow(c, "Hold one fan speed").button().tag("verify", 0)).display(">");
        roomRow = add(root, new StepRow(c, "Room °C (for the log)").range(0, 40)
                .steps(1, 5).tag("room", 0));

        root.addView(Ui.heading(c, "Logs"), Ui.wrap());
        loggingRow = add(root, new StepRow(c, "Write CSV log").button().tag("logging", 0));
        storageRow = add(root, new StepRow(c, "Also copy to shared storage").button()
                .tag("storage", 0));
        exportRow = add(root, new StepRow(c, "Copy all logs to USB").button()
                .tag("export", 0));
        add(root, new StepRow(c, "Diagnostics").button().tag("diag", 0)).display(">");

        if (Process.myUid() == Process.SYSTEM_UID) {
            root.addView(Ui.heading(c, "System"), Ui.wrap());
            takeoverRow = add(root, new StepRow(c, "Stock fan controller").button()
                    .tag("takeover", 0));
            add(root, new StepRow(c, "ADB over network").button().tag("adb", 0)).display(">");
        }
        return scroll;
    }

    private StepRow add(LinearLayout parent, StepRow row) {
        row.listen(this);
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.topMargin = Ui.dp(this, 1);
        parent.addView(row, lp);
        return row;
    }

    private static void onOff(StepRow row, boolean on) {
        row.display(on ? "On" : "Off");
        row.valueColour(on ? Ui.GOOD : Ui.DIM);
    }

    private void sync() {
        int mode = Prefs.mode(this);
        modeRow.display(mode == Mode.OFF ? "Off" : mode == Mode.CURVE ? "Curve"
                : mode == Mode.LINEAR ? "Linear" : "Manual");
        modeRow.valueColour(mode == Mode.OFF ? Ui.DIM : mode == Mode.MANUAL ? Ui.WARN : Ui.GOOD);
        int duty = Prefs.manualDuty(this);
        slider.setProgress(duty - FanIo.MIN_DUTY);
        sliderLabel.setText("Manual fan  " + duty + "%");
        LinearConfig lin = Prefs.linear(this);
        LinearConfig.promoteForBoost(lin, Prefs.ledBoostOn(this));
        ceilingRow.set((int) Math.round(lin.ceilingC));
        ceilingRow.display(Sample.fmt1(lin.ceilingC));
        ceilingRow.valueColour(mode == Mode.LINEAR ? Ui.ACCENT : Ui.DIM);
        onOff(reassertRow, Prefs.reassert(this));
        onOff(loggingRow, Prefs.logging(this));
        int r = Prefs.roomC(this);
        roomRow.set(r);
        roomRow.display(r == 0 ? "Not set" : Integer.toString(r));
        roomRow.valueColour(r == 0 ? Ui.DIM : Ui.ACCENT);
        onOff(storageRow, storageGranted());
        if (takeoverRow != null) {
            String v = SysProps.get(SysProps.PROP_FANCTRL_BY_TEMP);
            boolean stockOff = v != null && "0".equals(v.trim());
            takeoverRow.display(stockOff ? "Stopped" : "Running");
            takeoverRow.valueColour(stockOff ? Ui.WARN : Ui.DIM);
        }
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder(FanService.statusLine);
        if (Prefs.mode(this) == Mode.LINEAR && FanService.linearSaturated) {
            sb.append(" · cannot reach the target in this room");
        }
        if (FanService.guardBoost > 0) {
            sb.append(" · chip guard +").append(FanService.guardBoost);
        }
        statusView.setText(sb.toString());
        boolean busy = FanService.exporting;
        exportRow.display(busy ? "Copying…"
                : FanService.exportFiles > 0 ? FanService.exportFiles + " copied" : ">");
        exportRow.valueColour(busy ? Ui.WARN : Ui.ACCENT);
    }

    @Override
    public void onStepRow(StepRow row) {
        try {
            String t = row.tagName;
            if ("mode".equals(t)) {
                int mode = Prefs.mode(this);
                Prefs.setMode(this, mode == Mode.CURVE ? Mode.LINEAR
                        : mode == Mode.LINEAR ? Mode.MANUAL : Mode.CURVE);
                FanService.poke(this, FanService.ACTION_REFRESH);
            } else if ("ceiling".equals(t)) {
                LinearConfig lin = Prefs.linear(this);
                lin.ceilingC = row.get();
                Prefs.setLinear(this, lin);
                FanService.poke(this, FanService.ACTION_REFRESH);
            } else if ("reassert".equals(t)) {
                Prefs.setReassert(this, !Prefs.reassert(this));
            } else if ("logging".equals(t)) {
                Prefs.setLogging(this, !Prefs.logging(this));
            } else if ("room".equals(t)) {
                Prefs.setRoomC(this, row.get());
            } else if ("storage".equals(t)) {
                askStorage();
            } else if ("export".equals(t)) {
                FanService s = FanService.instance;
                if (s == null) {
                    say("Turn the fan control on first.");
                } else {
                    s.exportBacklogAsync(true);
                }
            } else if ("curve".equals(t)) {
                startActivity(new Intent(this, CurveActivity.class));
            } else if ("sweep".equals(t)) {
                startActivity(new Intent(this, AutoActivity.class));
            } else if ("verify".equals(t)) {
                startActivity(new Intent(this, VerifyActivity.class));
            } else if ("diag".equals(t)) {
                startActivity(new Intent(this, DiagActivity.class));
            } else if ("adb".equals(t)) {
                startActivity(new Intent(this, AdbActivity.class));
            } else if ("takeover".equals(t)) {
                confirmTakeover();
            }
            sync();
        } catch (Throwable th) {
            say("Failed: " + th);
        }
    }

    private void confirmTakeover() {
        new AlertDialog.Builder(this)
                .setTitle("Stop the stock fan controller?")
                .setMessage("FanLab becomes the only thing controlling the fan. This survives "
                        + "a reboot and an uninstall. Use Restore stock fan to undo it.\n\n"
                        + "The 75 °C shutdown is not affected.")
                .setPositiveButton("Stop it", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        boolean ok = SysProps.set(SysProps.PROP_FANCTRL_BY_TEMP, "0");
                        if (!ok) {
                            say("Failed: could not set the property.");
                        } else if (Prefs.mode(AdvancedActivity.this) == Mode.OFF) {
                            Prefs.setMode(AdvancedActivity.this, Mode.CURVE);
                            FanService.poke(AdvancedActivity.this, FanService.ACTION_REFRESH);
                        }
                        sync();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void say(String msg) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private boolean storageGranted() {
        try {
            return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void askStorage() {
        if (storageGranted() || Build.VERSION.SDK_INT < 23) {
            return;
        }
        try {
            requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 1);
        } catch (Throwable t) {
            say("Could not ask: " + t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        sync();
    }
}
