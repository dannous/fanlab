package com.daleygames.fanlab;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One full-width, D-pad-driven control: LEFT and RIGHT change the value, UP and DOWN move between rows, OK activates. */
public class StepRow extends LinearLayout {

    public interface Listener {
        void onStepRow(StepRow row);
    }

    private final TextView labelView;
    private final TextView valueView;

    private int min;
    private int max;
    private int step = 1;
    private int fastStep = 5;
    private int scale = 1;
    private int value;
    private String suffix = "";
    private boolean stepper = true;
    private Listener listener;
    private int baseColour = Ui.PANEL;

    public String tagName = "";
    public int tagIndex = -1;

    public StepRow(Context c, String label) {
        super(c);
        setOrientation(HORIZONTAL);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClickable(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        int p = Ui.dp(c, 10);
        setPadding(p, Ui.dp(c, 9), p, Ui.dp(c, 9));
        setBackground(Ui.panel(c, Ui.PANEL));
        setGravity(Gravity.CENTER_VERTICAL);

        labelView = Ui.text(c, label, 18f, Ui.FG);
        valueView = Ui.text(c, "", 20f, Ui.ACCENT);
        valueView.setGravity(Gravity.END);
        addView(labelView, Ui.weighted(1f));
        addView(valueView, new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT));

        setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                v.setBackground(Ui.panel(v.getContext(), has ? Ui.FOCUS : baseColour));
            }
        });
    }

    public StepRow tint(int colour) {
        baseColour = colour;
        if (!hasFocus()) {
            setBackground(Ui.panel(getContext(), colour));
        }
        return this;
    }

    public StepRow range(int lo, int hi) {
        min = lo;
        max = hi;
        return this;
    }

    public StepRow steps(int normal, int fast) {
        step = normal;
        fastStep = fast;
        return this;
    }

    public StepRow scaled(int s) {
        scale = s < 1 ? 1 : s;
        return this;
    }

    public StepRow suffix(String s) {
        suffix = s == null ? "" : s;
        return this;
    }

    /** A row that only activates on OK and ignores LEFT/RIGHT: a plain button. */
    public StepRow button() {
        stepper = false;
        return this;
    }

    public StepRow listen(Listener l) {
        listener = l;
        return this;
    }

    public StepRow tag(String name, int index) {
        tagName = name;
        tagIndex = index;
        return this;
    }

    public StepRow label(String s) {
        labelView.setText(s);
        return this;
    }

    public StepRow valueColour(int colour) {
        valueView.setTextColor(colour);
        return this;
    }

    /** Set the raw stored integer (tenths if scaled). Does not notify. */
    public StepRow set(int v) {
        value = clamp(v);
        render();
        return this;
    }

    /** Set from a real number, honouring the scale. Does not notify. */
    public StepRow setReal(double v) {
        return set((int) Math.round(v * scale));
    }

    public StepRow display(String s) {
        valueView.setText(s);
        return this;
    }

    public int get() {
        return value;
    }

    public double getReal() {
        return value / (double) scale;
    }

    private int clamp(int v) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }

    private void render() {
        if (!stepper) {
            return;
        }
        String s;
        if (scale == 1) {
            s = Integer.toString(value);
        } else {
            s = Sample.fmt1(value / (double) scale);
        }
        valueView.setText(s + suffix);
    }

    private void bump(int delta) {
        int old = value;
        value = clamp(value + delta);
        render();
        if (value != old && listener != null) {
            listener.onStepRow(this);
        }
    }

    private void activate() {
        if (listener != null) {
            listener.onStepRow(this);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int amount = event != null && event.getRepeatCount() > 6 ? fastStep : step;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (stepper) {
                    bump(-amount);
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (stepper) {
                    bump(amount);
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                activate();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Consume the matching UP so View's own confirm-key handling cannot fire a second activation.
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        activate();
        return true;
    }
}
