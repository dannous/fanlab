package com.daleygames.fanlab;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

/**
 * Read-only diagnostics: every sysfs node the investigation cares about, the property
 * dump, and where the CSV is going.
 *
 * Nothing on this screen writes anything. That is the point: it is the thing to open
 * first when something does not behave as the research predicted.
 */
public class DiagActivity extends Activity {

    private static final int REFRESH_MS = 2000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView dump;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                dump.setText(collect());
            } catch (Throwable t) {
                dump.setText("diagnostics failed: " + t);
            }
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Context c = this;
        ScrollView scroll = new ScrollView(c);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(c);
        int p = Ui.dp(c, 16);
        root.setPadding(p, p, p, p);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.text(c, "Diagnostics — read only", 26f, Ui.FG), Ui.wrap());
        dump = Ui.text(c, "", 13f, Ui.DIM);
        dump.setTypeface(Typeface.MONOSPACE);
        dump.setBackground(Ui.panel(c, Ui.PANEL));
        int q = Ui.dp(c, 8);
        dump.setPadding(q, q, q, q);
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.topMargin = Ui.dp(c, 8);
        root.addView(dump, lp);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    private String collect() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("uid ").append(Process.myUid());
        sb.append(Process.myUid() == Process.SYSTEM_UID
                ? "  (system — platform signed, sharedUserId applied)"
                : "  (ordinary app — untrusted_app domain)");
        sb.append("\npackage ").append(getPackageName());
        sb.append("\ntargetSdk ").append(getApplicationInfo().targetSdkVersion);
        sb.append("   Build.VERSION.SDK_INT ").append(android.os.Build.VERSION.SDK_INT);
        sb.append("\nSystemProperties reflection: ")
                .append(SysProps.reflectionAvailable() ? "available" : "NOT available");
        sb.append("\n\n--- fan ---\n");
        for (int i = 0; i < Sysfs.FAN_NODES.length; i++) {
            node(sb, Sysfs.FAN_NODES[i]);
        }
        sb.append("\n--- thermistor ---\n");
        node(sb, Sysfs.LEDTEMP_VOLTAGE);
        int adc = Sysfs.readInt(Sysfs.LEDTEMP_VOLTAGE, Thermistor.BAD_ADC);
        double t = Thermistor.celsius(adc);
        sb.append("  computed  ").append(Double.isNaN(t) ? "n/a" : Sample.fmt2(t))
                .append(" °C   stock (int) = ")
                .append(Double.isNaN(t) ? "n/a" : Integer.toString(Thermistor.stockTmp(t)))
                .append('\n');

        sb.append("\n--- dlpc343x ---\n");
        for (int i = 0; i < Sysfs.DLPC_NODES.length; i++) {
            node(sb, Sysfs.DLPC_NODES[i]);
        }

        display(sb);

        sb.append("\n--- properties ---\n");
        for (int i = 0; i < SysProps.DIAG_PROPS.length; i++) {
            String name = SysProps.DIAG_PROPS[i];
            String v = SysProps.get(name);
            sb.append("  ").append(name).append(" = ")
                    .append(v == null ? "<unreadable>" : v.length() == 0 ? "<unset>" : v)
                    .append('\n');
        }

        sb.append("\n--- telemetry sinks ---\n");
        String[] paths = FanService.csvPaths;
        for (int i = 0; i < paths.length; i++) {
            long len = -1;
            try {
                len = new File(paths[i]).length();
            } catch (Throwable ignored) {
                // report -1
            }
            sb.append("  ok      ").append(paths[i]).append("   ")
                    .append(len < 0 ? "?" : Long.toString(len)).append(" B\n");
        }
        String[] broken = FanService.csvBroken;
        for (int i = 0; i < broken.length; i++) {
            sb.append("  FAILED  ").append(broken[i]).append('\n');
        }
        sb.append("  rows ").append(FanService.csvLines)
                .append("   fan writes ").append(FanService.writesDone)
                .append("   write failures ").append(FanService.writeFailures)
                .append('\n');
        sb.append("  status: ").append(FanService.statusLine).append('\n');

        // Separate from the sinks above, and it has to be: a sink receives new rows, the
        // export carries the history, and only the second one answers "is the whole log
        // on the stick I am about to walk away with".
        sb.append("\n--- backlog export ---\n");
        sb.append("  ").append(FanService.exporting ? "COPYING NOW  " : "")
                .append(FanService.exportStatus).append('\n');
        sb.append("  files ").append(FanService.exportFiles)
                .append("   rows ").append(FanService.exportRows).append('\n');
        String[] dests = FanService.exportDirs;
        for (int i = 0; i < dests.length; i++) {
            sb.append("  -> ").append(dests[i]).append('\n');
        }
        return sb.toString();
    }

    /**
     * The three display-controller features the app used to offer, as facts rather than
     * controls.
     *
     * Each was a row on the main screen; each was measured on the projector and taken away.
     * They are here because a negative result is worth keeping where it can be re-checked --
     * a firmware that changed one of these answers would show up in this block and nowhere
     * else in the app. The finding sits beside the reading so the two cannot drift apart.
     *
     * <b>Nothing on this screen writes.</b> The readings are sampled by {@link FanService}
     * once a minute at most, on a thread of its own; this only prints what it last saw.
     */
    private void display(StringBuilder sb) {
        sb.append("\n--- display controller (read only, sampled ~1/min) ---\n");
        if (Process.myUid() != Process.SYSTEM_UID) {
            sb.append("  the answers come back in the kernel log, which needs READ_LOGS,\n")
                    .append("  so only the system build can read them. This is the plain build.\n");
            return;
        }

        sb.append("  CAIC: engine runs, output stranded - no DLPA on this board\n");
        sb.append("        off 52.33 C, on 52.33 C; pinned fan, 7 min a hold, Presentation\n");
        PicoReg.CaicReading caic = FanService.caicReadback;
        sb.append("        method (0x51)  ")
                .append(caic == null ? "not read yet"
                        : caic.known() ? caic.state + "  [" + caic.source + "]"
                        : "unknown - " + caic.reason)
                .append('\n');
        PicoReg.CaicImage img = FanService.caicImageReadback;
        sb.append("        gain   (0x85)  ")
                .append(img == null ? "not read yet"
                        : img.known ? img.summary() + "  [" + img.source + "]"
                        : "unknown - " + img.reason)
                .append('\n');

        sb.append("\n  LABB: works, and the work is washing the picture out\n");
        sb.append("        \"really washed out seeming\" - it lifts the black floor by design\n");
        PicoReg.Labb labb = FanService.labbReadback;
        sb.append("        state  (0x81)  ")
                .append(labb == null ? "not read yet"
                        : labb.known ? labb.summary() + "  [" + labb.source + "]"
                        : "unknown - " + labb.reason)
                .append('\n');

        sb.append("\n  Look: only Look 0 is neutral; the other 18 buy green with red\n");
        sb.append("        Look 0 is 40/40/20; the rest cut red to 25-33 % and give it to\n");
        sb.append("        green. Rebalancing Look 15 needs red at 175 %, against a 97 cap.\n");
        PicoReg.Look look = FanService.lookReadback;
        sb.append("        split  (0x26)  ")
                .append(look == null ? "not read yet"
                        : look.known ? look.summary() + "  [" + look.source + "]"
                        : "unknown - " + look.reason)
                .append('\n');
    }

    private static void node(StringBuilder sb, String path) {
        String v = Sysfs.read(path);
        sb.append("  ").append(pad(path, 42)).append(' ');
        if (v == null) {
            sb.append("<unreadable>");
        } else {
            sb.append(v.replace("\n", "\\n").replace("\r", ""));
        }
        boolean canWrite = false;
        try {
            canWrite = new File(path).canWrite();
        } catch (Throwable ignored) {
            // leave false
        }
        sb.append("   [").append(canWrite ? "w" : "-").append("]\n");
    }

    private static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
