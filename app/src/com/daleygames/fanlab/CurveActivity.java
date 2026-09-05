package com.daleygames.fanlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The curve editor. Every control is a full-width row: LEFT/RIGHT changes the value,
 * UP/DOWN moves between rows, OK activates. Changes are saved as soon as they are made
 * and take effect on the service's next tick.
 *
 * The preview at the bottom prints the resulting duty for every degree from 38 to 58
 * next to what the stock ladder would have done, because a table of numbers is far more
 * useful than a graph when the reader is deciding whether a change is safe.
 */
public class CurveActivity extends Activity implements StepRow.Listener {

    private CurveConfig cfg;
    private int profile = CurveConfig.PROFILE_HIGH;

    private StepRow profileRow;
    private final StepRow[] dutyRows = new StepRow[CurveConfig.POINTS];
    private final StepRow[] tempRows = new StepRow[CurveConfig.POINTS];
    private StepRow hystRow;
    private StepRow slewUpRow;
    private StepRow slewDownRow;
    private StepRow idleRow;
    private StepRow minRow;
    private StepRow maxRow;
    private TextView preview;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        cfg = Prefs.curve(this);
        Sample s = FanService.lastSample;
        if (s != null && s.rgblevel > 0) {
            profile = CurveConfig.profileForLevel(s.rgblevel);
        }
        setContentView(build());
        render();
    }

    private View build() {
        Context c = this;
        ScrollView scroll = new ScrollView(c);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(c);
        int p = Ui.dp(c, 16);
        root.setPadding(p, p, p, p);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(Ui.text(c, "Fan curve", 26f, Ui.FG), Ui.wrap());
        root.addView(Ui.body(c,
                "Duty is interpolated between the knee points, so there is never a step to "
                        + "hear. Hysteresis stops the curve chasing noise; the slew limit "
                        + "stops any change being fast enough to notice."), Ui.wrap());

        root.addView(Ui.heading(c, "Brightness tier being edited"), Ui.wrap());
        profileRow = add(root, new StepRow(c, "Tier").button().tag("profile", 0));

        root.addView(Ui.heading(c, "Duty at each knee"), Ui.wrap());
        for (int i = 0; i < CurveConfig.POINTS; i++) {
            dutyRows[i] = add(root, new StepRow(c, "duty")
                    .range(FanIo.MIN_DUTY, FanIo.MAX_DUTY).steps(1, 5).suffix(" %")
                    .tag("duty", i));
        }

        root.addView(Ui.heading(c, "Response"), Ui.wrap());
        hystRow = add(root, new StepRow(c, "Hysteresis — follow a falling temperature only "
                + "after it has dropped this far")
                .range(0, 100).steps(1, 5).scaled(10).suffix(" °C").tag("hyst", 0));
        slewUpRow = add(root, new StepRow(c, "Slew up — fastest rise")
                .range(1, 500).steps(1, 10).scaled(10).suffix(" %/s").tag("slewup", 0));
        slewDownRow = add(root, new StepRow(c, "Slew down — fastest fall")
                .range(1, 500).steps(1, 10).scaled(10).suffix(" %/s").tag("slewdown", 0));
        idleRow = add(root, new StepRow(c, "Idle duty while the light engine is off")
                .range(FanIo.MIN_DUTY, FanIo.MAX_DUTY).steps(1, 5).suffix(" %")
                .tag("idle", 0));
        minRow = add(root, new StepRow(c, "Hard minimum")
                .range(FanIo.MIN_DUTY, FanIo.MAX_DUTY).steps(1, 5).suffix(" %")
                .tag("min", 0));
        maxRow = add(root, new StepRow(c, "Hard maximum")
                .range(FanIo.MIN_DUTY, FanIo.MAX_DUTY).steps(1, 5).suffix(" %")
                .tag("max", 0));

        root.addView(Ui.heading(c, "Knee temperatures — advanced"), Ui.wrap());
        for (int i = 0; i < CurveConfig.POINTS; i++) {
            tempRows[i] = add(root, new StepRow(c, "Knee " + (i + 1))
                    .range(0, 100).steps(1, 5).suffix(" °C").tag("temp", i));
        }

        add(root, new StepRow(c, "Reset the whole curve to the defaults").button()
                .tag("reset", 0));

        root.addView(Ui.heading(c, "Preview — this tier"), Ui.wrap());
        preview = Ui.text(c, "", 13f, Ui.DIM);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setBackground(Ui.panel(c, Ui.PANEL));
        int q = Ui.dp(c, 8);
        preview.setPadding(q, q, q, q);
        root.addView(preview, Ui.wrap());

        root.addView(Ui.body(c,
                "The \"stock\" column is the projector's own ladder, reproduced exactly, "
                        + "using this tier's speed.min for the bottom rung. It writes "
                        + "nothing at all at 53 and 54 °C — that gap is a bug in the "
                        + "original, shown here as \"--\"."), Ui.wrap());
        return scroll;
    }

    private StepRow add(LinearLayout parent, StepRow row) {
        row.listen(this);
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.topMargin = Ui.dp(this, 6);
        parent.addView(row, lp);
        return row;
    }

    // ------------------------------------------------------------------ state

    private void render() {
        profileRow.display(CurveConfig.PROFILE_NAMES[profile]);
        profileRow.label("Tier — press OK to switch (rgblevel "
                + (profile == CurveConfig.PROFILE_LOW ? "1 and 4"
                : profile == CurveConfig.PROFILE_NORMAL ? "2" : "3") + ")");
        for (int i = 0; i < CurveConfig.POINTS; i++) {
            dutyRows[i].label("Duty at " + cfg.tempC[i] + " °C");
            dutyRows[i].set(cfg.duty[profile][i]);
            tempRows[i].set(cfg.tempC[i]);
        }
        hystRow.setReal(cfg.hysteresisC);
        slewUpRow.setReal(cfg.slewUpPerSec);
        slewDownRow.setReal(cfg.slewDownPerSec);
        idleRow.set(cfg.idleDuty);
        minRow.set(cfg.minDuty);
        maxRow.set(cfg.maxDuty);
        renderPreview();
    }

    private void renderPreview() {
        int floor = cfg.duty[profile][0];
        StringBuilder sb = new StringBuilder();
        sb.append(" °C   curve   stock\n");
        for (int t = 38; t <= 58; t++) {
            int mine = cfg.dutyAt(profile, t);
            int stock = FanCurve.stockLadder(profile, t, floor);
            sb.append(pad(Integer.toString(t), 3));
            sb.append(pad(mine + "%", 8));
            sb.append(stock < 0 ? "  --" : "  " + stock + "%");
            sb.append('\n');
        }
        preview.setText(sb.toString());
    }

    private static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < w; i++) {
            sb.append(' ');
        }
        return sb.append(s).toString();
    }

    private void save() {
        Prefs.setCurve(this, cfg);
        FanService.poke(this, FanService.ACTION_REFRESH);
    }

    @Override
    public void onStepRow(StepRow row) {
        try {
            String n = row.tagName;
            if ("profile".equals(n)) {
                profile = (profile + 1) % CurveConfig.PROFILES;
                render();
                return;
            }
            if ("reset".equals(n)) {
                new AlertDialog.Builder(this)
                        .setTitle("Reset the curve?")
                        .setMessage("Every tier goes back to the shipped defaults.")
                        .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                cfg = new CurveConfig();
                                Prefs.resetCurve(CurveActivity.this);
                                FanService.poke(CurveActivity.this,
                                        FanService.ACTION_REFRESH);
                                render();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
            if ("duty".equals(n)) {
                cfg.duty[profile][row.tagIndex] = row.get();
            } else if ("temp".equals(n)) {
                cfg.tempC[row.tagIndex] = row.get();
            } else if ("hyst".equals(n)) {
                cfg.hysteresisC = row.getReal();
            } else if ("slewup".equals(n)) {
                cfg.slewUpPerSec = row.getReal();
            } else if ("slewdown".equals(n)) {
                cfg.slewDownPerSec = row.getReal();
            } else if ("idle".equals(n)) {
                cfg.idleDuty = row.get();
            } else if ("min".equals(n)) {
                cfg.minDuty = row.get();
            } else if ("max".equals(n)) {
                cfg.maxDuty = row.get();
            } else {
                return;
            }
            cfg.sanitise();
            save();
            // sanitise() can move later knees to keep them ascending, so show what was
            // actually stored rather than what was typed.
            for (int i = 0; i < CurveConfig.POINTS; i++) {
                dutyRows[i].label("Duty at " + cfg.tempC[i] + " °C");
                if ("temp".equals(n)) {
                    tempRows[i].set(cfg.tempC[i]);
                }
            }
            renderPreview();
        } catch (Throwable t) {
            new AlertDialog.Builder(this).setMessage("Failed: " + t)
                    .setPositiveButton("OK", null).show();
        }
    }
}
