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
    private StepRow presetRow;
    private StepRow ceilingRow;
    private StepRow ledDriveRow;
    private StepRow reassertRow;
    private StepRow caicRow;
    private StepRow caicGainRow;
    private StepRow labbRow;
    private StepRow labbStrengthRow;
    private StepRow labbSharpnessRow;
    private StepRow loggingRow;
    private StepRow autostartRow;
    private StepRow takeoverRow;
    private StepRow restoreRow;
    private StepRow storageRow;
    private StepRow exportRow;
    private StepRow roomRow;
    private SeekBar slider;
    private TextView sliderValue;

    /**
     * The picture-change confirmation dialog, while one is up.
     *
     * Held so the 250 ms poll can redraw the countdown in it and take it away when the
     * window closes. It is a <i>view</i> of a countdown the service owns, never the
     * countdown itself -- dismissing it, or this activity going away under it, changes
     * nothing about whether the change reverts.
     */
    private AlertDialog pictureDialog;

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
        // The dialog goes; the countdown does not. It lives in the service precisely so
        // that walking away from this screen is one of the ways a display change gets
        // reverted rather than one of the ways it gets stuck on.
        dismissPictureDialog();
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
        presetRow = addRow(root, new StepRow(c, "Curve preset — CURVE mode").button()
                .tag("preset", 0));
        root.addView(Ui.body(c,
                "Fan speed in Presentation, and where the light engine settles at "
                        + "24 °C:"
                        + "\n    Quiet       38–40 %    51.9 °C"
                        + "\n    Balanced    43–45 %    50.3 °C"
                        + "\n    Cool        48–50 %    49.2 °C"
                        + "\n    Cold        53–55 %    48.3 °C"
                        + "\n    Bright      ~44 %      53.8 °C   use with LED drive"
                        + "\nChoose Bright only with the LED drive on. On stock drive "
                        + "it behaves like Quiet, so it buys nothing."
                        + "\nAll five idle at 30 % in Normal, Eco and Super Eco at stock "
                        + "drive."),
                Ui.wrap());
        ceilingRow = addRow(root, new StepRow(c, "Temperature ceiling — LINEAR mode")
                .range((int) LinearConfig.MIN_CEILING_C, (int) LinearConfig.MAX_CEILING_C)
                .steps(1, 5).tag("ceiling", 0));
        root.addView(Ui.body(c,
                "CURVE holds a fan speed and lets the temperature float; LINEAR holds the "
                        + "temperature and lets the fan float. They meet at 24 °C and "
                        + "diverge either side of it — LINEAR is quieter in a cool room and "
                        + "louder in a warm one:"
                        + "\n    room   CURVE        LINEAR @ 52 °C"
                        + "\n    22 °C   37 % / 50.6   35 % / 52.0"
                        + "\n    26 °C   39 % / 53.4   42 % / 52.0"
                        + "\n    30 °C   42 % / 56.1   57 % / 52.0"
                        + "\nAbove a 32.5 °C room LINEAR cannot hold 52 °C and says so. "
                        + "Choose CURVE for quiet, LINEAR when the ceiling matters more."),
                Ui.wrap());
        ledDriveRow = addRow(root, new StepRow(c,
                "LED drive (BETA) — a brighter picture").button()
                .tag("leddrive", 0));
        root.addView(Ui.body(c,
                "Runs the LEDs harder than the projector normally does. Presentation "
                        + "goes from 76 % to 90 % of maximum — roughly 18 % more "
                        + "light. Press to cycle Stock → Bright → Stock.\n"
                        + "\nSwitch the curve preset to Bright as well. The light "
                        + "engine runs about 4 °C hotter on this setting, and Bright "
                        + "is the curve that spends a little more fan to cover it.\n"
                        + "\nThen put up a white image and check two things: it should "
                        + "look brighter, and white should still look white. If it looks "
                        + "dimmer instead, switch it off — that means the drive was set "
                        + "too high and the hardware cut it back.\n"
                        + "\nIt switches itself off above "
                        + Sample.fmt1(LedDrive.DEFAULT_TRIP_C) + " °C, and whenever this "
                        + "app is not the one driving the fan. To turn it off yourself, "
                        + "press this row again or set Mode to OFF.\n"
                        + "\nBETA — the temperatures quoted for it are calculated, "
                        + "not yet measured on this projector."), Ui.wrap());
        reassertRow = addRow(root, new StepRow(c,
                "Re-assert every second (beat the stock controller)").button()
                .tag("reassert", 0));
        addRow(root, new StepRow(c, "Curve settings…").button().tag("curve", 0));

        root.addView(Ui.heading(c, "Experiment — display controller"), Ui.wrap());
        root.addView(Ui.body(c,
                "The two settings below change the picture, not the fan. Neither raises how "
                        + "hard the LEDs are driven, so neither makes the projector hotter. "
                        + "Both ask you to confirm within "
                        + (PictureArm.WINDOW_MS / 1000) + " seconds, because a display "
                        + "setting that goes wrong can take away the screen you would need "
                        + "to undo it — do nothing and it puts itself back.\n"
                        + "\nIf the picture goes and you cannot read this: wait "
                        + (PictureArm.WINDOW_MS / 1000) + " seconds, or pull the power. "
                        + "Neither setting is saved until you confirm it, and neither is "
                        + "written anywhere that survives a reboot."), Ui.wrap());

        labbRow = addRow(root, new StepRow(c,
                "LABB (BETA) — lift the dark parts of the picture").button()
                .tag("labb", 0));
        labbStrengthRow = addRow(root, new StepRow(c, "    LABB strength")
                .range(0, PicoReg.LABB_STRENGTH_MAX).steps(8, 32).tag("labbstrength", 0));
        labbSharpnessRow = addRow(root, new StepRow(c, "    LABB sharpness")
                .range(0, PicoReg.LABB_SHARPNESS_MAX).steps(1, 4).tag("labbsharp", 0));
        root.addView(Ui.body(c,
                "Brightens dark areas of the image without touching the LEDs, frame by "
                        + "frame. Press to turn it on, then look at a dark scene — shadow "
                        + "detail should come up while the bright parts stay where they "
                        + "were. If it looks washed out or the dark areas shimmer, turn the "
                        + "strength down or switch it off.\n"
                        + "\nStrength is 0–255 and starts at 128, which is the value the "
                        + "projector already had loaded. It is a dial, not a multiplier: "
                        + "how much lift you get depends on the picture. Sharpness 0–15 "
                        + "only does anything while LABB is on.\n"
                        + "\nThis is the one of the two that has a mechanism this "
                        + "projector can actually run — it works on the mirrors, and needs "
                        + "nothing from the LED driver. Try it before CAIC.\n"
                        + "\nBETA — nobody has run it on this model."), Ui.wrap());

        caicRow = addRow(root, new StepRow(c,
                "CAIC (BETA) — content-adaptive LED power").button().tag("caic", 0));
        caicGainRow = addRow(root, new StepRow(c, "    CAIC brightness budget")
                .range(Prefs.CAIC_GAIN_MIN_TENTHS, Prefs.CAIC_GAIN_MAX_TENTHS)
                .steps(1, 5).scaled(10).suffix("×").tag("caicgain", 0));
        root.addView(Ui.body(c,
                "Asks the display controller to dim the LEDs on frames that do not need "
                        + "full output and open the mirrors to compensate. If it works, the "
                        + "picture looks the same and the projector draws less power.\n"
                        + "\nThe budget above is how far it is allowed to lift the image, "
                        + "1.0× to 4.0×. The projector was found set to 1.0×, which is no "
                        + "lift at all — that is why switching CAIC on by itself did "
                        + "nothing you could see. 2.0× is the starting point; turn it up if "
                        + "nothing changes, down if the picture pumps or flickers between "
                        + "scenes.\n"
                        + "\nTo judge it, put up a mostly dark frame with one small bright "
                        + "region and watch it for a few seconds — that is the content it "
                        + "is designed for. Then check a normal scene still looks right.\n"
                        + "\nBETA — and this one may do nothing whatever the budget "
                        + "says. It saves power by lowering LED current through a driver "
                        + "chip this board does not have.\n"
                        + (systemVariant
                        ? "\n\"read back\" is what the controller itself reported, "
                          + "checked about once a minute."
                        : "\nOnly the system build can read the controller's answer "
                          + "back, so this build says \"unverified\".")), Ui.wrap());

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

        // Here rather than only on the AUTO screen. It was on the sweep's confirmation
        // page, where it became a line in the sweep report and never reached fanlab.csv --
        // so a month of ordinary use carried no ambient at all and the first field log had
        // to have it supplied by word of mouth.
        root.addView(Ui.heading(c, "Room temperature"), Ui.wrap());
        roomRow = addRow(root, new StepRow(c, "Room temperature").range(0, 40)
                .steps(1, 5).tag("room", 0));
        root.addView(Ui.body(c,
                "Goes on every CSV row, blank until you set it — and blank means not "
                        + "stated, not zero. This is the one number the log cannot work "
                        + "out for itself: inferring the room from the LED temperature "
                        + "needs the plant table, which is the thing field data exists to "
                        + "check. The app also takes its own reading at every power-on and "
                        + "records how long the projector had been off beforehand; this is "
                        + "the independent cross-check on that."), Ui.wrap());

        root.addView(Ui.heading(c, "Logging"), Ui.wrap());
        loggingRow = addRow(root, new StepRow(c, "Write CSV telemetry").button()
                .tag("logging", 0));
        autostartRow = addRow(root, new StepRow(c, "Start automatically after a reboot")
                .button().tag("autostart", 0));
        storageRow = addRow(root, new StepRow(c,
                "Also copy to /storage/emulated/0/FanLab (asks for permission)")
                .button().tag("storage", 0));
        exportRow = addRow(root, new StepRow(c,
                "COPY ALL LOGS to a USB stick now").button().tag("export", 0));
        exportRow.tint(0xFF14313F).valueColour(Ui.ACCENT).display("▶");
        addRow(root, new StepRow(c, "Diagnostics…").button().tag("diag", 0));
        root.addView(Ui.body(c,
                "New rows go to this app's own folder on internal storage and to any USB "
                        + "stick that is plugged in at the time — no permission needed. A "
                        + "stick only receives what is written while it is in, so use COPY "
                        + "ALL LOGS to collect the history: it writes " + FanService.EXPORT_DIR
                        + "/ at the top level of the stick, adds to what is already there, "
                        + "and copies nothing twice. Live rows land in Android/data/"
                        + getPackageName() + "/files/fanlab/."), Ui.wrap());

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
                        + "55 % by itself; this app notices within a second, adopts that "
                        + "value and moves off it gradually rather than stepping."),
                Ui.wrap());

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
                    : mode == Mode.LINEAR
                    ? "Mode — LINEAR: holding the temperature ceiling, fan free to move"
                    : "Mode — CURVE: following the temperature curve");
        }
        if (presetRow != null) {
            // The colour tracks the noise, not the state: green for the quietest, amber
            // for the ones whose operating point reaches the owner's "just acceptable" 50
            // in a warm room -- Cool is at 45.8 % at 26 C and Cold at 49.1 %, and Bright is
            // predicted at 45.9 % in a 26 C room with the LED drive raised -- and dim for a
            // curve that is none of them and therefore has nothing to say about how loud it
            // is.
            //
            // Dimmed outside CURVE, because a value the loop is not currently using should
            // not look like one it is.
            int preset = Prefs.preset(this);
            presetRow.display(CurveConfig.presetName(preset));
            presetRow.valueColour(mode != Mode.CURVE ? Ui.DIM
                    : preset == 0 ? Ui.GOOD
                    : preset == 1 ? Ui.ACCENT
                    : (preset >= 2 && preset < CurveConfig.PRESET_NAMES.length) ? Ui.WARN
                    : Ui.DIM);
        }
        if (ceilingRow != null) {
            LinearConfig lin = Prefs.linear(this);
            // The ceiling the controller will actually hold, promotion included. Showing
            // the stored 52 while the loop held 54 would make the one row on this screen
            // whose whole job is to state a temperature the one that does not.
            boolean raised = LinearConfig.promoteForBoost(lin, Prefs.ledBoostOn(this));
            ceilingRow.set((int) Math.round(lin.ceilingC));
            ceilingRow.display(Sample.fmt1(lin.ceilingC) + " °C"
                    + (raised ? " (LED drive)" : ""));
            // Dim unless LINEAR is the thing running, for the same reason the preset row
            // dims outside CURVE: a value the loop is not currently using should not look
            // like one it is.
            ceilingRow.valueColour(mode == Mode.LINEAR ? Ui.ACCENT : Ui.DIM);
        }
        syncLedDriveRow();
        if (reassertRow != null) {
            boolean r = Prefs.reassert(this);
            reassertRow.display(r ? "ON" : "OFF");
            reassertRow.valueColour(r ? Ui.GOOD : Ui.DIM);
        }
        syncCaicRow();
        syncLabbRow();
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
        if (roomRow != null) {
            int r = Prefs.roomC(this);
            roomRow.set(r);
            roomRow.display(r == 0 ? "not stated" : r + " °C");
            roomRow.valueColour(r == 0 ? Ui.DIM : Ui.ACCENT);
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

    /**
     * The LED drive row. Like the CAIC row it is driven from the poll as well as from the
     * preference sync, because what it reports is not the setting: the service holds the
     * override off in half a dozen states the setting knows nothing about, and the trip
     * latch drops it without anyone pressing anything.
     *
     * Dimmed outside CURVE and LINEAR for the same reason the preset and ceiling rows are
     * dimmed outside their own modes -- and here it is more than a convention, because
     * outside those two modes the override genuinely is not applied.
     */
    private void syncLedDriveRow() {
        if (ledDriveRow == null) {
            return;
        }
        boolean on = Prefs.ledBoostOn(this);
        boolean controls = Mode.controls(Prefs.mode(this));
        // The service's live word for it while there is a service; the setting otherwise,
        // which is all a cold app can honestly say.
        String state = FanService.instance == null
                ? (on ? "on (service not running)" : "off")
                : FanService.ledDriveStatus;
        ledDriveRow.display(state);
        int colour;
        if (!on) {
            colour = Ui.DIM;
        } else if (!controls) {
            colour = Ui.DIM;
        } else if (state.startsWith("held off")) {
            colour = Ui.DANGER;
        } else if (state.startsWith("applied")) {
            colour = Ui.WARN;
        } else {
            colour = Ui.ACCENT;
        }
        ledDriveRow.valueColour(colour);
        ledDriveRow.label(on
                ? "LED drive (BETA) — Bright (" + Prefs.ledDrive(this).summary() + ")"
                : "LED drive (BETA) — a brighter picture");
    }

    /**
     * The CAIC row. Called from the preference sync and from the 1 s poll, because two of
     * its states arrive asynchronously: the service's write happens on the next tick, and
     * the read-back lands seconds later on a thread of its own.
     *
     * Green only when the DLPC itself has said on. Amber for on-but-unverified, which is
     * what the plain build shows for ever and the system build shows for a few seconds;
     * red when the register disagrees with the setting or the write failed.
     */
    private void syncCaicRow() {
        if (caicRow == null) {
            return;
        }
        boolean want = Prefs.caic(this);
        String text = FanService.caicSummary(want);
        caicRow.display(text);
        PicoReg.CaicReading rb = FanService.caicReadback;
        boolean saysOn = rb != null && PicoReg.CAIC_ON.equals(rb.state);
        boolean saysOff = rb != null && PicoReg.CAIC_OFF.equals(rb.state);
        int colour;
        if (FanService.pictureArmed(PictureArm.CAIC)) {
            // Amber, and not green: an armed CAIC is a thing that is about to be undone
            // unless someone acts, which is the opposite of a settled state.
            colour = Ui.WARN;
        } else if (!want) {
            colour = text.indexOf("still ON") >= 0 ? Ui.DANGER : Ui.DIM;
        } else if (FanService.caicWriteFailed) {
            colour = Ui.DANGER;
        } else if (FanService.caicWritten == 1 && saysOn) {
            colour = Ui.GOOD;
        } else if (FanService.caicWritten == 1 && saysOff) {
            colour = Ui.DANGER;
        } else {
            colour = Ui.WARN;
        }
        caicRow.valueColour(colour);
        if (caicGainRow != null) {
            // The setting, and beside it what the controller said its budget actually is --
            // which is the number that was wrong, so it is worth showing rather than
            // trusting the write. Dim while CAIC is off: a budget nothing is spending is
            // not a value the machine is using.
            int tenths = Prefs.caicGainTenths(this);
            caicGainRow.set(tenths);
            PicoReg.CaicImage img = FanService.caicImageReadback;
            if (want && img != null && img.known) {
                caicGainRow.display(Sample.fmt1(tenths / 10.0) + "× (read back: "
                        + PicoReg.fmtGain(img.gain) + "×)");
            } else {
                caicGainRow.display(Sample.fmt1(tenths / 10.0) + "×");
            }
            caicGainRow.valueColour(!want ? Ui.DIM
                    : (img != null && img.known
                            && Math.abs(img.gain - tenths / 10.0) > 0.05) ? Ui.DANGER
                    : Ui.ACCENT);
        }
    }

    /**
     * The LABB row and its two numbers. Same shape as the CAIC row and for the same reason:
     * what it reports is not the setting but what the service has managed to put on the
     * hardware, and on the system build what the hardware said back.
     */
    private void syncLabbRow() {
        if (labbRow == null) {
            return;
        }
        boolean want = Prefs.labb(this);
        String text = FanService.labbSummary(want);
        labbRow.display(text);
        PicoReg.Labb rb = FanService.labbReadback;
        int colour;
        if (FanService.pictureArmed(PictureArm.LABB)) {
            colour = Ui.WARN;
        } else if (!want) {
            colour = text.indexOf("still ON") >= 0 ? Ui.DANGER : Ui.DIM;
        } else if (FanService.labbWriteFailed) {
            colour = Ui.DANGER;
        } else if (FanService.labbWritten == 1 && rb != null && rb.known) {
            colour = rb.enabled ? Ui.GOOD : Ui.DANGER;
        } else {
            colour = Ui.WARN;
        }
        labbRow.valueColour(colour);
        if (labbStrengthRow != null) {
            int v = Prefs.labbStrength(this);
            labbStrengthRow.set(v);
            labbStrengthRow.display(Integer.toString(v));
            labbStrengthRow.valueColour(want ? Ui.ACCENT : Ui.DIM);
        }
        if (labbSharpnessRow != null) {
            int v = Prefs.labbSharpness(this);
            labbSharpnessRow.set(v);
            labbSharpnessRow.display(v == 0 ? "off" : Integer.toString(v));
            labbSharpnessRow.valueColour(want ? Ui.ACCENT : Ui.DIM);
        }
    }

    private void refresh() {
        syncCaicRow();
        syncLabbRow();
        syncLedDriveRow();
        syncPictureDialog();
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
        // The SoC zones are not on a tile because they do not drive the fan; they are here
        // because they are the one thing the LED thermistor cannot tell you, and without
        // them a warm-looking machine and a busy one look identical.
        if (!Double.isNaN(s.socC[0])) {
            sb.append("\nSoC  pll ").append(Sample.fmt1(s.socC[0])).append(" C");
            if (!Double.isNaN(s.socC[1])) {
                sb.append("   ddr ").append(Sample.fmt1(s.socC[1])).append(" C");
            }
            if (!Double.isNaN(s.socC[2])) {
                sb.append("   sar ").append(Sample.fmt1(s.socC[2])).append(" C");
            }
            sb.append("   (throttles at 75)");
            if (FanService.guardBoost > 0) {
                sb.append("   ● SoC GUARD +").append(FanService.guardBoost);
            }
        }
        if (FanService.throttling) {
            sb.append("   ● THROTTLING ").append(s.throttleNote());
        } else if (FanService.throttledSec > 0) {
            sb.append("   throttled ").append(FanService.throttledSec).append("s so far");
        }
        // The override's own line, because the row alone cannot say why it is not applied
        // and "the LED drive says Bright but the picture is not" is a question the screen
        // has to answer without a shell.
        if (Prefs.ledBoostOn(this)) {
            sb.append("\nLED drive  ").append(FanService.ledDriveStatus);
        }
        if (Prefs.mode(this) == Mode.LINEAR) {
            LinearConfig lin = Prefs.linear(this);
            boolean raised = LinearConfig.promoteForBoost(lin, Prefs.ledBoostOn(this));
            sb.append("\nLINEAR  ceiling ").append(Sample.fmt1(lin.ceilingC))
                    .append(raised ? " C (raised for the LED drive; predicted)   " : " C   ")
                    .append(lin.upStepMs / 1000).append(" s up, ")
                    .append(lin.downStepMs / 1000).append(" s down within ")
                    .append(Sample.fmt1(lin.nearC)).append(" C, ")
                    .append(lin.downFastMs / 1000).append(" s below that");
            // Named rather than implied. A duty pinned at 83 with nothing to explain it
            // looks like a fault; saying the ceiling is out of reach says it is not.
            if (FanService.linearSaturated) {
                sb.append("   ● OUT OF AUTHORITY — the ceiling cannot be reached "
                        + "at this room temperature");
            }
        }
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
        ps.append("\nExport → ").append(FanService.exportStatus);
        pathsView.setText(ps.toString());

        if (exportRow != null) {
            boolean busy = FanService.exporting;
            exportRow.display(busy ? "copying…"
                    : FanService.exportFiles > 0
                    ? FanService.exportFiles + " file"
                      + (FanService.exportFiles == 1 ? "" : "s") : "▶");
            exportRow.valueColour(busy ? Ui.WARN
                    : FanService.exportFiles > 0 ? Ui.GOOD : Ui.ACCENT);
        }
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
                // OFF -> CURVE -> LINEAR -> MANUAL -> CURVE -> ... The driving modes cycle
                // among themselves, because going from one driver to another has no
                // business routing through a handback: OFF writes the fail-safe 83 and
                // re-arms the stock controller, which then has to be undone. Reaching OFF
                // is a deliberate act with its own control (RESTORE STOCK), not something
                // to stumble into while auditioning a duty.
                //
                // CURVE and LINEAR are adjacent on purpose. Comparing them by ear is what
                // LINEAR is for, and the two are one button press apart with no fail-safe
                // burst in between, so the comparison is of the controllers rather than of
                // how each of them recovers from 83.
                int next = mode == Mode.CURVE ? Mode.LINEAR
                        : mode == Mode.LINEAR ? Mode.MANUAL : Mode.CURVE;
                Prefs.setMode(this, next);
                FanService.poke(this, FanService.ACTION_REFRESH);
                syncControlsFromPrefs();
            } else if ("preset".equals(row.tagName)) {
                // Quiet -> Balanced -> Cool -> Cold -> Bright -> Quiet, in PRESET_NAMES
                // order. Custom is a state to arrive in, not one to cycle to: it has no
                // curve of its own, so PRESET_CUSTOM being -1 lands the next press on
                // Quiet, which is the only sensible place to go from a curve none of the
                // names describe.
                int next = Prefs.preset(this) + 1;
                if (next >= CurveConfig.PRESET_NAMES.length) {
                    next = 0;
                }
                Prefs.setPreset(this, next);
                FanService.poke(this, FanService.ACTION_REFRESH);
                syncControlsFromPrefs();
            } else if ("ceiling".equals(row.tagName)) {
                // Poked, unlike the room temperature: this one is an input to the
                // controller, so the loop should pick it up as a settings change and resync
                // rather than discovering it a tick later mid-walk.
                LinearConfig lin = Prefs.linear(this);
                lin.ceilingC = row.get();
                Prefs.setLinear(this, lin);
                FanService.poke(this, FanService.ACTION_REFRESH);
                syncControlsFromPrefs();
            } else if ("leddrive".equals(row.tagName)) {
                // Stock -> Bright -> Stock. Poked, like the ceiling and unlike the room
                // temperature, because it is an input to the controller: the loop has to
                // see it as a settings change, which is also the edge that lets the
                // override start at all.
                if (Prefs.ledBoostOn(this)) {
                    Prefs.resetLedDrive(this);
                } else {
                    Prefs.setLedDrive(this, LedDrive.Config.bright());
                    Prefs.setLedDriveOn(this, true);
                }
                FanService.poke(this, FanService.ACTION_REFRESH);
                syncControlsFromPrefs();
            } else if ("reassert".equals(row.tagName)) {
                Prefs.setReassert(this, !Prefs.reassert(this));
                syncControlsFromPrefs();
            } else if ("caic".equals(row.tagName)) {
                // Three states, not two: off, armed-and-counting, and confirmed on.
                //
                // Nothing here writes the preference. Arming asks the service to write the
                // register and start its own countdown; only FanService.confirmPicture
                // stores anything, and only after someone has said the picture survived.
                // That is what stops an unconfirmed CAIC coming back after a reboot, which
                // would take away the power cycle that is the owner's guaranteed escape.
                pressPicture(PictureArm.CAIC, Prefs.caic(this));
            } else if ("labb".equals(row.tagName)) {
                pressPicture(PictureArm.LABB, Prefs.labb(this));
            } else if ("caicgain".equals(row.tagName)) {
                // Poked: with CAIC already on, the tick has to see the new budget as an edge
                // and re-send 0x84. Nothing on the hardware changes on its own to tell it.
                Prefs.setCaicGainTenths(this, row.get());
                FanService.poke(this, FanService.ACTION_REFRESH);
                syncCaicRow();
            } else if ("labbstrength".equals(row.tagName)) {
                Prefs.setLabbStrength(this, row.get());
                FanService.poke(this, FanService.ACTION_REFRESH);
                syncLabbRow();
            } else if ("labbsharp".equals(row.tagName)) {
                Prefs.setLabbSharpness(this, row.get());
                FanService.poke(this, FanService.ACTION_REFRESH);
                syncLabbRow();
            } else if ("logging".equals(row.tagName)) {
                Prefs.setLogging(this, !Prefs.logging(this));
                syncControlsFromPrefs();
            } else if ("autostart".equals(row.tagName)) {
                Prefs.setAutostart(this, !Prefs.autostart(this));
                syncControlsFromPrefs();
            } else if ("room".equals(row.tagName)) {
                // No poke: the loop picks this up on its next tick, and re-syncing the
                // controller because the room was typed in would be a resync for nothing.
                int v = row.get();
                Prefs.setRoomC(this, v);
                row.display(v == 0 ? "not stated" : v + " °C");
                row.valueColour(v == 0 ? Ui.DIM : Ui.ACCENT);
            } else if ("storage".equals(row.tagName)) {
                requestStoragePermission();
            } else if ("export".equals(row.tagName)) {
                doExport();
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


    /**
     * One press on a display-experiment row: cancel a countdown, switch a confirmed one
     * off, or arm it and put the dialog up. Shared by both rows, because the three states
     * and the reasoning behind them are identical -- only the register differs.
     */
    private void pressPicture(int what, boolean alreadyOn) {
        if (FanService.pictureArmed(what)) {
            FanService.cancelPictureArm(this, what);
            syncControlsFromPrefs();
        } else if (alreadyOn) {
            if (what == PictureArm.CAIC) {
                Prefs.setCaic(this, false);
            } else {
                Prefs.setLabb(this, false);
            }
            FanService.poke(this, FanService.ACTION_REFRESH);
            syncControlsFromPrefs();
        } else {
            FanService.armPicture(this, what);
            syncControlsFromPrefs();
            showPictureConfirm();
        }
    }

    /**
     * The confirmation, the same shape as a monitor asking whether a new resolution
     * worked — and for the same reason: the change can take away the screen you would need
     * in order to undo it.
     *
     * <b>This dialog is not the countdown.</b> {@link FanService} owns that, and it reverts
     * whether or not anything is on screen; killing the app, pressing HOME or the launcher
     * reclaiming this activity all leave the revert running. All this does is draw the
     * remaining seconds and offer the one button that stops it.
     */
    private void showPictureConfirm() {
        dismissPictureDialog();
        pictureDialog = new AlertDialog.Builder(this)
                .setTitle("Keep " + PictureArm.name(FanService.pictureArmedWhat()) + " on?")
                .setMessage(pictureConfirmText())
                .setCancelable(false)
                .setPositiveButton("Keep it", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (!FanService.confirmPicture(MainActivity.this)) {
                            // The window closed between the press and the dispatch. Say so
                            // rather than storing it late: the picture the owner is looking
                            // at is already the reverted one.
                            toastLike("Too late — the countdown had already run out and "
                                    + "the setting has been turned back off. Press the row "
                                    + "again to retry.");
                        }
                        syncControlsFromPrefs();
                    }
                })
                .setNegativeButton("Revert now", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        FanService.cancelPictureArm(MainActivity.this,
                                PictureArm.CAIC | PictureArm.LABB);
                        syncControlsFromPrefs();
                    }
                })
                .show();
    }

    private String pictureConfirmText() {
        int left = FanService.pictureCountdownSec();
        String what = PictureArm.name(FanService.pictureArmedWhat());
        return what + " is on. Look at the picture.\n\n"
                + "If it is still readable, press Keep it.\n"
                + "If it is blank, wrong or full of artefacts, press nothing: it turns "
                + "itself back off in " + left + " s.\n\n"
                + "Nothing has been saved yet, so a power cycle also clears it — the "
                + "factory display settings are never written.";
    }

    /** Redraw the countdown, and take the dialog away when the service's window closes. */
    private void syncPictureDialog() {
        if (pictureDialog == null) {
            return;
        }
        if (FanService.pictureCountdownSec() <= 0) {
            dismissPictureDialog();
            return;
        }
        try {
            pictureDialog.setMessage(pictureConfirmText());
        } catch (Throwable ignored) {
            // a dialog that will not redraw is still a dialog with a working button
        }
    }

    private void dismissPictureDialog() {
        try {
            if (pictureDialog != null) {
                pictureDialog.dismiss();
            }
        } catch (Throwable ignored) {
            // already gone
        }
        pictureDialog = null;
    }

    /**
     * Copy the backlog now, whether or not this boot already did.
     *
     * The automatic export fires once per volume per boot, which is right for a stick
     * left in the socket and wrong for someone standing in front of the projector holding
     * one. This forces it. The result cannot be reported here -- the copy runs on its own
     * thread precisely so that it is not waited on -- so the row and the line under the
     * CSV paths report it as it happens.
     */
    private void doExport() {
        FanService s = FanService.instance;
        if (s == null) {
            toastLike("The service is not running, so nothing knows which volumes are "
                    + "mounted. Set Mode to CURVE or MANUAL and try again.");
            return;
        }
        s.exportBacklogAsync(true);
        toastLike("Copying every log to " + FanService.EXPORT_DIR + "/ on each USB stick. "
                + "It runs in the background — the row shows the result when it finishes, "
                + "and Diagnostics lists the destinations.\n\n"
                + "Already-copied rows are skipped, so doing this twice costs nothing.");
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
