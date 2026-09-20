package com.daleygames.fanlab;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The whole look of the app: plain framework views, sized for a projector and reachable by D-pad. */
public final class Ui {

    public static final int BG = 0xFF101014;
    public static final int PANEL = 0xFF1B1B22;
    public static final int FG = 0xFFF2F2F5;
    public static final int DIM = 0xFF9A9AA6;
    public static final int ACCENT = 0xFF4FC3F7;
    public static final int WARN = 0xFFFFB300;
    public static final int DANGER = 0xFFEF5350;
    public static final int GOOD = 0xFF81C784;
    public static final int FOCUS = 0xFF2F5D82;

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static TextView text(Context c, String s, float sp, int colour) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        return t;
    }

    public static TextView heading(Context c, String s) {
        TextView t = text(c, s, 15f, DIM);
        t.setAllCaps(true);
        t.setPadding(dp(c, 4), dp(c, 14), dp(c, 4), dp(c, 4));
        return t;
    }

    public static TextView body(Context c, String s) {
        TextView t = text(c, s, 14f, DIM);
        t.setPadding(dp(c, 4), dp(c, 2), dp(c, 4), dp(c, 2));
        return t;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weighted(float w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, w);
        return p;
    }

    public static GradientDrawable panel(Context c, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, 6));
        return g;
    }

    public static final class Tile extends LinearLayout {
        private final TextView value;
        private final TextView caption;

        public Tile(Context c, String cap) {
            super(c);
            setOrientation(VERTICAL);
            setBackground(panel(c, PANEL));
            int p = dp(c, 8);
            setPadding(p, p, p, p);
            value = text(c, "--", 34f, FG);
            value.setGravity(Gravity.CENTER_HORIZONTAL);
            caption = text(c, cap, 12f, DIM);
            caption.setAllCaps(true);
            caption.setGravity(Gravity.CENTER_HORIZONTAL);
            addView(value, wrap());
            addView(caption, wrap());
        }

        public void set(String v) {
            value.setText(v);
        }

        public void set(String v, int colour) {
            value.setText(v);
            value.setTextColor(colour);
        }

        public void setCaption(String s) {
            caption.setText(s);
        }
    }

    public static void grid(Context c, LinearLayout parent, View[] tiles, int perRow) {
        LinearLayout current = null;
        for (int i = 0; i < tiles.length; i++) {
            if (i % perRow == 0) {
                current = row(c);
                LinearLayout.LayoutParams lp = wrap();
                lp.topMargin = dp(c, 6);
                parent.addView(current, lp);
            }
            LinearLayout.LayoutParams lp = weighted(1f);
            lp.leftMargin = (i % perRow == 0) ? 0 : dp(c, 6);
            current.addView(tiles[i], lp);
        }
    }
}
