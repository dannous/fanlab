package com.daleygames.fanlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Quarantined: switch the USB gadget configuration so adbd runs. Nothing here is verified on hardware. */
public class AdbActivity extends Activity implements StepRow.Listener {

    // "ptp,adb" rather than "adb": it starts adbd while leaving the USB socket in host mode, so a stick keeps working.
    private static final String CONFIG_ADB = "ptp,adb";
    private static final String CONFIG_STOCK = "mtp";

    private TextView state;
    private StepRow enableRow;
    private StepRow revertRow;

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

        root.addView(Ui.text(c, "ADB over the network", 26f, Ui.FG), Ui.wrap());
        root.addView(Ui.body(c,
                "This has nothing to do with the fan. It is here only because this build "
                        + "happens to be able to set sys.usb.config, and a shell is "
                        + "otherwise unreachable on this projector.\n\n"
                        + "It sets sys.usb.config to \"" + CONFIG_ADB + "\", which starts "
                        + "adbd while leaving the USB socket in host mode, so a USB stick "
                        + "still works. You then need an ASIX USB-Ethernet adapter (not "
                        + "Realtek — that driver is not built into this kernel) and "
                        + "\"adb connect\" from a PC.\n\n"
                        + "sys.usb.config is NOT persistent — a reboot puts it back. The "
                        + "revert below does it immediately."), Ui.wrap());

        state = Ui.text(c, "", 18f, Ui.ACCENT);
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.topMargin = Ui.dp(c, 10);
        root.addView(state, lp);

        enableRow = add(root, new StepRow(c, "Enable adbd (sys.usb.config = " + CONFIG_ADB + ")")
                .button().tag("enable", 0));
        enableRow.tint(0xFF4A3A12);
        revertRow = add(root, new StepRow(c, "REVERT to stock (sys.usb.config = "
                + CONFIG_STOCK + ")").button().tag("revert", 0));
        revertRow.tint(0xFF1F4A2A).valueColour(Ui.GOOD);

        if (Process.myUid() != Process.SYSTEM_UID) {
            root.addView(Ui.body(c,
                    "This build is not running as the system user, so both buttons will "
                            + "fail. Only the platform-signed build can set this."),
                    Ui.wrap());
        }
        setContentView(scroll);
        refresh();
    }

    private StepRow add(LinearLayout parent, StepRow row) {
        row.listen(this);
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.topMargin = Ui.dp(this, 8);
        parent.addView(row, lp);
        return row;
    }

    private void refresh() {
        String v = SysProps.get(SysProps.PROP_USB_CONFIG);
        state.setText("sys.usb.config = " + (v == null ? "<unreadable>" : v));
        boolean on = v != null && v.contains("adb");
        enableRow.display(on ? "already on" : "▶");
        revertRow.display(on ? "▶" : "already stock");
    }

    @Override
    public void onStepRow(StepRow row) {
        if ("enable".equals(row.tagName)) {
            new AlertDialog.Builder(this)
                    .setTitle("Start adbd?")
                    .setMessage("This opens a debugging service on the projector. Anyone on "
                            + "the same network who can reach it, and who accepts the "
                            + "on-screen RSA prompt, gets a shell.\n\nA reboot undoes it, "
                            + "and so does the revert button.")
                    .setPositiveButton("Enable", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            apply(CONFIG_ADB);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else if ("revert".equals(row.tagName)) {
            apply(CONFIG_STOCK);
        }
    }

    private void apply(String value) {
        boolean ok = SysProps.set(SysProps.PROP_USB_CONFIG, value);
        refresh();
        new AlertDialog.Builder(this)
                .setMessage(ok ? "sys.usb.config is now " + value
                        : "FAILED — the property service refused. That is expected unless "
                          + "this is the platform-signed build with "
                          + "sharedUserId=android.uid.system.")
                .setPositiveButton("OK", null)
                .show();
    }
}
