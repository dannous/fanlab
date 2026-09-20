package com.daleygames.fanlab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/** The test apparatus: a full-screen white field, or a one-pixel checkerboard or grating drawn at the display's Nyquist limit. */
public class FieldView extends View {

    public static final int PATTERN_WHITE = 0;
    public static final int PATTERN_CHECKER = 1;
    public static final int PATTERN_GRATING = 2;

    private static final String[] NAMES = {"white", "checkerboard-1px", "grating-1px"};

    private int pattern = PATTERN_WHITE;
    private final Paint paint = new Paint();
    private Bitmap checker;
    private Bitmap grating;

    public FieldView(Context c) {
        super(c);
        paint.setAntiAlias(false);
        paint.setFilterBitmap(false);
        paint.setDither(false);
        setFocusable(false);
    }

    public int pattern() {
        return pattern;
    }

    public String patternName() {
        return NAMES[pattern < 0 || pattern >= NAMES.length ? 0 : pattern];
    }

    public void setPattern(int p) {
        pattern = p < 0 || p > PATTERN_GRATING ? PATTERN_WHITE : p;
        invalidate();
    }

    /** white -> checkerboard -> grating -> white. */
    public int nextPattern() {
        setPattern(pattern + 1 > PATTERN_GRATING ? PATTERN_WHITE : pattern + 1);
        return pattern;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            if (pattern == PATTERN_WHITE) {
                canvas.drawColor(Color.WHITE);
                return;
            }
            Bitmap tile = pattern == PATTERN_CHECKER ? checkerTile() : gratingTile();
            if (tile == null) {
                canvas.drawColor(Color.WHITE);
                return;
            }
            paint.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT,
                    Shader.TileMode.REPEAT));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
        } catch (Throwable t) {
            try {
                canvas.drawColor(Color.WHITE);
            } catch (Throwable ignored) {
            }
        }
    }

    private Bitmap checkerTile() {
        if (checker == null) {
            checker = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            checker.setPixel(0, 0, Color.WHITE);
            checker.setPixel(1, 0, Color.BLACK);
            checker.setPixel(0, 1, Color.BLACK);
            checker.setPixel(1, 1, Color.WHITE);
        }
        return checker;
    }

    private Bitmap gratingTile() {
        if (grating == null) {
            grating = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888);
            grating.setPixel(0, 0, Color.WHITE);
            grating.setPixel(1, 0, Color.BLACK);
        }
        return grating;
    }
}
