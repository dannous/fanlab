package com.daleygames.fanlab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/**
 * The test apparatus: a full-screen field, drawn by the app.
 *
 * <h3>White, for AUTO</h3>
 * 100 % white for the whole run. Two reasons. It is the worst thermal case, so any curve
 * derived from it is conservative. And it is reproducible - every owner who runs AUTO
 * produces comparable data, whereas a varying picture would not, because the DLPC has
 * content-adaptive dimming (CAIC) that lowers LED current on dark content and would make
 * two runs of the same schedule incomparable.
 *
 * <h3>A fine pattern, for VERIFY</h3>
 * Philips' own stated reason for raising the Presentation fan speed was that "the metal
 * inside was shapeshifting by few mm and the image was getting unclear" - differential
 * thermal expansion in an ultra-short-throw optical path. That failure mode is visible,
 * and it is what the whole tuning criterion is actually phrased against. So VERIFY can
 * swap the white field for a <b>one-pixel checkerboard</b> or a <b>one-pixel vertical
 * grating</b>, both of which sit exactly at the display's Nyquist limit: they are crisp
 * when the optics are in focus and turn to flat grey the moment they are not.
 *
 * Both patterns are drawn by tiling a two-pixel bitmap with filtering and anti-aliasing
 * off, so one bitmap pixel is one display pixel with no interpolation anywhere. A scaled
 * or filtered pattern would be a test of the scaler, not of the projector.
 */
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
            // The field is the apparatus; if the pattern cannot be drawn, fall back to
            // white rather than leaving a black screen, which would change the thermal
            // load without saying so.
            try {
                canvas.drawColor(Color.WHITE);
            } catch (Throwable ignored) {
                // nothing left to do
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
