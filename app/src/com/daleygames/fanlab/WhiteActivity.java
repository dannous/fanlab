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

/** A full white field: the heaviest load the light engine sees, for hearing the fan under it. The fan mode in force keeps running; this changes nothing. */
public class WhiteActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView readout;
    private long startedMs;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            Sample s = FanService.lastSample;
            long min = (System.currentTimeMillis() - startedMs) / 60000L;
            readout.setText(s == null ? "..." : "LED " + (Double.isNaN(s.degC) ? "--"
                    : Sample.fmt1(s.degC)) + " C   FAN " + (s.fanCtrl < 0 ? "--" : s.fanCtrl + "%")
                    + "   " + min + " min   any key to exit");
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFFFFFFF);
        readout = Ui.mono(this, "", 14f, 0xFF808080);
        int p = Ui.dp(this, 12);
        readout.setPadding(p, p, p, p);
        root.addView(readout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END));
        setContentView(root);
        startedMs = System.currentTimeMillis();
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
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // The OK release that opened this screen lands here too; do not let it close it.
        if (System.currentTimeMillis() - startedMs < 800L) {
            return true;
        }
        finish();
        return true;
    }
}
