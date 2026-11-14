package com.daleygames.fanlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** The rows the main screen keeps folded away: the other modes, measurement, logging and the system switches. */
public final class AdvancedPanel implements StepRow.Listener {

    private final Activity a;

    private TextView statusView;
    private StepRow floorRow;
    private StepRow manualRow;
    private final StepRow[] targetRows = new StepRow[LedDrive.TIERS];
    private StepRow roomRow;
    private StepRow loggingRow;
    private StepRow storageRow;
    private StepRow exportRow;
    private StepRow takeoverRow;
    /** One row per brightness mode, in {@link LedDrive#TIER_NAMES} order: Super Eco, Eco, Normal, Presentation. */
    private final StepRow[] driveRows = new StepRow[LedDrive.TIERS];

    public AdvancedPanel(Activity activity) {
        a = activity;
    }

    public View build() {
        Context c = a;
        LinearLayout root = Ui.column(c);

        statusView = Ui.mono(c, "", 16f, Ui.DIM);
        root.addView(statusView, Ui.wrap());

        root.addView(Ui.heading(c, "Smart quiet"), Ui.wrap());
        for (int i = 0; i < LedDrive.TIERS; i++) {
            int lv = LedDrive.TIER_RGBLEVEL[i];
            targetRows[i] = add(root, new StepRow(c, LedDrive.TIER_NAMES[i] + " target °C")
                    .range(Prefs.MIN_TARGET_C, Prefs.MAX_TARGET_C).steps(1, 5).tag("target", i)
                    .hint("The LED temperature Smart quiet holds in " + LedDrive.TIER_NAMES[i] + ". Normally "
                            + Prefs.defaultTarget(lv) + ".\n\nLower is kinder to the LEDs but needs more fan, or a dimmer picture at the limit. "
                            + "Above " + (int) LedDrive.DEFAULT_TRIP_C + " °C the brighter picture switches itself off."));
        }
        floorRow = add(root, new StepRow(c, "Fan floor %").range(Prefs.MIN_FAN_FLOOR, Prefs.MAX_FAN_FLOOR)
                .steps(1, 5).tag("floor", 0)
                .hint("The slowest the fan runs in Smart quiet and the curves. Normally " + Prefs.DEFAULT_FAN_FLOOR
                        + ".\n\nBelow 30 is untested: if the fan stalls, the projector's own watchdog steps in. "
                        + "The curves never go below 25, and the fan limit wins if it is set lower than this."));
        root.addView(Ui.heading(c, "Brightness (LED drive)"), Ui.wrap());
        for (int i = 0; i < LedDrive.TIERS; i++) {
            int stock = LedDrive.STOCK_OTHER[LedDrive.TIER_RGBLEVEL[i]];
            driveRows[i] = add(root, new StepRow(c, LedDrive.TIER_NAMES[i] + " %")
                    .range(LedDrive.MIN_LEVEL, LedDrive.MAX_LEVEL).steps(1, 5).tag("drive", i)
                    .hint("How hard the LEDs are driven in " + LedDrive.TIER_NAMES[i] + ". Philips uses " + stock
                            + ".\n\nHigher is brighter, but needs more fan and wears the LEDs faster. Used when Brightness boost is on; over "
                            + (int) LedDrive.DEFAULT_TRIP_C + " °C the brighter picture switches itself off."));
        }
        add(root, new StepRow(c, "Reset brightness and targets to the defaults").button().tag("drivereset", 0)
                .hint("Puts the four modes back to " + LedDrive.Config.bright().encode().substring(3).replace(",", " / ")
                        + " (Super Eco to Presentation)."));

        root.addView(Ui.heading(c, "Tests"), Ui.wrap());
        add(root, new StepRow(c, "White screen (full load)").button().tag("white", 0).hint("Full white picture, the heaviest load.\n\nUse it to hear the fan at its worst. Any key exits.")).display(">");
        add(root, new StepRow(c, "Colour slides").button().tag("slides", 0).hint("Full-screen white, red, green, blue and black, one at a time, to check each LED and look for dust or dead pixels.\n\nLeft and right change the slide. Back or OK exits.")).display(">");
        add(root, new StepRow(c, "Focus pattern").button().tag("focus", 0).hint("A fine grid with markers in the centre and corners, for setting focus and checking it is even across the picture.\n\nAny key exits.")).display(">");
        manualRow = add(root, new StepRow(c, "Manual fan test %").range(Prefs.MIN_FAN_FLOOR, FanIo.MAX_DUTY).steps(1, 5).tag("manual", 0)
                .hint("Left and right hold the fan at one speed, from 20 to 100 %, to hear it or to cool the projector quickly. Philips itself never goes above 83. Brightness boost keeps working.\n\n"
                        + "Nothing watches the temperature during the test, so it ends by itself after 30 minutes, or at "
                        + (int) FanService.MANUAL_END_C + " °C, and your fan mode takes over. Pick a Fan mode to end it sooner."));
        add(root, new StepRow(c, "Unattended test (" + WhiteActivity.planMinutes() + " min)").button().tag("plan", 0)
                .hint("Runs by itself on a white screen: Presentation, Normal and Night on Smart quiet, then Presentation and Normal on the curve.\n\nLeave a USB stick in. Back or OK stops it and puts your settings back.")).display(">");

        root.addView(Ui.heading(c, "Measure"), Ui.wrap());
        add(root, new StepRow(c, "Sweep every fan speed").button().tag("sweep", 0).hint("Measures the LED temperature at every fan speed.\n\nTakes over an hour."))
                .display(">");
        add(root, new StepRow(c, "Hold one fan speed").button().tag("verify", 0).hint("Holds one fan speed so you can watch the picture and the temperature.")).display(">");        roomRow = add(root, new StepRow(c, "Room °C (for the log)").range(0, 40)
                .steps(1, 5).tag("room", 0).hint("Your room temperature, saved with the log.\n\nNot used to control anything."));

        root.addView(Ui.heading(c, "Logs"), Ui.wrap());
        loggingRow = add(root, new StepRow(c, "Write CSV log").button().tag("logging", 0).hint("Record temperature and fan speed every few seconds."));
        storageRow = add(root, new StepRow(c, "Also copy to shared storage").button()
                .tag("storage", 0).hint("Also save the log to shared storage.\n\nAsks for permission."));
        exportRow = add(root, new StepRow(c, "Copy all logs to USB").button()
                .tag("export", 0).hint("Copy the whole log history to a plugged-in USB stick."));
        add(root, new StepRow(c, "Diagnostics").button().tag("diag", 0).hint("Raw sensor and system readings.")).display(">");

        root.addView(Ui.heading(c, "System"), Ui.wrap());
        if (Process.myUid() == Process.SYSTEM_UID) {
            takeoverRow = add(root, new StepRow(c, "Stock fan controller").button()
                    .tag("takeover", 0).hint("Stops Philips' own fan control.\n\nStays stopped after a restart until you restore it."));
            add(root, new StepRow(c, "ADB over USB (untested)").button().tag("adb", 0).hint("Switches the USB port so a PC can connect with adb.\n\nNever verified on this projector.")).display(">");
        }
        add(root, new StepRow(c, "Restore stock controller (do this before uninstalling)").button()
                .tag("restore", 0).hint("Gives the fan and the brightness back to Philips for good.\n\nDo this before uninstalling.")).display(">");        return root;
    }

    private StepRow add(LinearLayout parent, StepRow row) {
        row.listen(this);
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.topMargin = Ui.dp(a, 1);
        parent.addView(row, lp);
        return row;
    }

    private static void onOff(StepRow row, boolean on) {
        row.display(on ? "On" : "Off");
        row.valueColour(on ? Ui.GOOD : Ui.DIM);
    }

    public void sync() {
        if (floorRow == null) {
            return;
        }
        boolean smart = Prefs.mode(a) == Mode.CURVE && Prefs.autoKind(a) == Prefs.AUTO_HOLD;
        for (int i = 0; i < LedDrive.TIERS; i++) {
            int tc = Prefs.smartTarget(a, LedDrive.TIER_RGBLEVEL[i]);
            targetRows[i].set(tc);
            targetRows[i].display(tc + " °C");
            targetRows[i].valueColour(smart ? Ui.ACCENT : Ui.DIM);
        }
        int fl = Prefs.fanFloor(a);
        floorRow.set(fl);
        floorRow.display(fl + "%");
        floorRow.valueColour(fl < Prefs.DEFAULT_FAN_FLOOR ? Ui.WARN : Ui.ACCENT);
        boolean testing = Prefs.mode(a) == Mode.MANUAL;
        manualRow.set(Prefs.manualDuty(a));
        manualRow.display(testing ? Prefs.manualDuty(a) + "% (running)" : "Off");
        manualRow.valueColour(testing ? Ui.WARN : Ui.DIM);        onOff(loggingRow, Prefs.logging(a));
        int r = Prefs.roomC(a);
        roomRow.set(r);
        roomRow.display(r == 0 ? "Not set" : Integer.toString(r));
        roomRow.valueColour(r == 0 ? Ui.DIM : Ui.ACCENT);
        onOff(storageRow, storageGranted());
        boolean driveOn = Prefs.ledBoostOn(a);
        LedDrive.Config cfg = Prefs.ledDrive(a);
        if (cfg.isStock()) {
            cfg = LedDrive.Config.bright();
        }
        for (int i = 0; i < LedDrive.TIERS; i++) {
            driveRows[i].set(cfg.level[i]);
            driveRows[i].display(driveOn ? cfg.level[i] + "%" : cfg.level[i] + "% (boost off)");
            driveRows[i].valueColour(driveOn ? Ui.ACCENT : Ui.DIM);
        }
        if (takeoverRow != null) {
            String v = SysProps.get(SysProps.PROP_FANCTRL_BY_TEMP);
            boolean stockOff = v != null && "0".equals(v.trim());
            takeoverRow.display(stockOff ? "Stopped" : "Running");
            takeoverRow.valueColour(stockOff ? Ui.WARN : Ui.DIM);
        }
    }

    public void refresh() {
        if (statusView == null) {
            return;
        }
        StringBuilder sb = new StringBuilder(FanService.statusLine);
        if (Prefs.mode(a) == Mode.LINEAR && FanService.linearSaturated) {
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
            if ("target".equals(t)) {
                Prefs.setSmartTarget(a, LedDrive.TIER_RGBLEVEL[row.tagIndex], row.get());
                FanService.poke(a, FanService.ACTION_REFRESH);
            } else if ("manual".equals(t)) {
                Prefs.setManualDuty(a, row.get());
                Prefs.setMode(a, Mode.MANUAL);
                FanService.poke(a, FanService.ACTION_REFRESH);
            } else if ("floor".equals(t)) {
                Prefs.setFanFloor(a, row.get());
                FanService.poke(a, FanService.ACTION_REFRESH);
            } else if ("white".equals(t)) {
                a.startActivity(new Intent(a, WhiteActivity.class));
            } else if ("slides".equals(t)) {
                a.startActivity(new Intent(a, WhiteActivity.class).putExtra("slides", true));
            } else if ("focus".equals(t)) {
                a.startActivity(new Intent(a, WhiteActivity.class).putExtra("focus", true));
            } else if ("plan".equals(t)) {
                a.startActivity(new Intent(a, WhiteActivity.class).putExtra("plan", true));
            } else if ("restore".equals(t)) {
                // Stop driving FIRST: the service re-asserts this property every 30 ticks, so setting it alone is undone within half a minute.
                Prefs.setMode(a, Mode.OFF);
                FanService.poke(a, FanService.ACTION_RELEASE);
                boolean ok = SysProps.set(SysProps.PROP_FANCTRL_BY_TEMP, "1");
                say(ok ? "Stock fan restored. Safe to uninstall." : "Failed: could not set the property.");            } else if ("drive".equals(t)) {
                // Changing a level is asking for it: keep the other three, and turn the boost on.
                LedDrive.Config cfg = Prefs.ledDrive(a);
                if (cfg.isStock()) {
                    cfg = LedDrive.Config.bright();
                }
                cfg.level[row.tagIndex] = row.get();
                Prefs.setLedDrive(a, cfg);
                Prefs.setLedDriveOn(a, true);
                FanService.poke(a, FanService.ACTION_REFRESH);
            } else if ("drivereset".equals(t)) {
                Prefs.setLedDrive(a, LedDrive.Config.bright());
                for (int lv = 1; lv <= 4; lv++) {
                    Prefs.setSmartTarget(a, lv, Prefs.defaultTarget(lv));
                }
                FanService.poke(a, FanService.ACTION_REFRESH);
            } else if ("logging".equals(t)) {
                Prefs.setLogging(a, !Prefs.logging(a));
            } else if ("room".equals(t)) {
                Prefs.setRoomC(a, row.get());
            } else if ("storage".equals(t)) {
                askStorage();
            } else if ("export".equals(t)) {
                FanService s = FanService.instance;
                if (s == null) {
                    say("Turn the fan control on first.");
                } else {
                    s.exportBacklogAsync(true);
                }
            } else if ("sweep".equals(t)) {
                a.startActivity(new Intent(a, AutoActivity.class));
            } else if ("verify".equals(t)) {
                a.startActivity(new Intent(a, VerifyActivity.class));
            } else if ("diag".equals(t)) {
                a.startActivity(new Intent(a, DiagActivity.class));
            } else if ("adb".equals(t)) {
                a.startActivity(new Intent(a, AdbActivity.class));
            } else if ("takeover".equals(t)) {
                confirmTakeover();
            }
            sync();
        } catch (Throwable th) {
            say("Failed: " + th);
        }
    }

    private void confirmTakeover() {
        new AlertDialog.Builder(a)
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
                        } else if (Prefs.mode(a) == Mode.OFF) {
                            Prefs.setMode(a, Mode.CURVE);
                            FanService.poke(a, FanService.ACTION_REFRESH);
                        }
                        sync();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void say(String msg) {
        new AlertDialog.Builder(a).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private boolean storageGranted() {
        try {
            return a.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
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
            a.requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 1);
        } catch (Throwable t) {
            say("Could not ask: " + t);
        }
    }
}
