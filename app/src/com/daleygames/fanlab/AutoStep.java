package com.daleygames.fanlab;

/**
 * AUTO STEP: one fixed curve drawn from two months of field log, plus a slow trim that adds
 * fan only while the light engine sits at its ceiling. The fan follows temperature, as the
 * presets do, so it never needs to search for a level.
 */
public final class AutoStep {

    /**
     * The curve. Floor 25 to 50 C, then 38 at 56, 52 at 60, 68 at 64, 83 from 68. The first rise is six
     * degrees wide because a four-degree one hunted by three points against the plant.
     *
     * The floor edge is 50 C because the field log puts Normal at drive 75 at 50.6 C, on the
     * old curve's 47-51 C rise, where it paid eight duty points for about one degree.
     */
    public static final String CURVE =
            "v1,50,56,60,64,68,70,25,38,52,68,83,83,25,38,52,68,83,83,25,38,52,68,83,83,"
                    + "0.8,0.25,0.12,10,25,83,1,70,2.0,62,1.5";

    /** Most the trim may add, duty points. */
    public static final int MAX_TRIM = 10;

    /** Degrees C below the ceiling before the trim comes back off. */
    public static final double RELEASE_BELOW_C = 2.0;

    /** One point up per fast thermal time constant (measured 115-133 s). */
    public static final long UP_MS = 120000L;

    /** One point down per ten minutes. */
    public static final long DOWN_MS = 600000L;

    private static final double SMOOTH_S = 30.0;

    private int trim;
    private double smoothC = Double.NaN;
    private long lastMs;
    private long sinceMs;
    private int zone;

    public static CurveConfig curve() {
        return CurveConfig.decode(CURVE);
    }

    public int trim() {
        return trim;
    }

    public void reset() {
        trim = 0;
        smoothC = Double.NaN;
        lastMs = 0L;
        sinceMs = 0L;
        zone = 0;
    }

    /** Advance one tick and return the trim. An implausible reading changes nothing. */
    public int step(double celsius, double ceilingC, long nowMs) {
        if (Double.isNaN(celsius) || Double.isInfinite(celsius)) {
            return trim;
        }
        if (Double.isNaN(smoothC) || lastMs == 0L) {
            smoothC = celsius;
        } else {
            double dt = Math.max(0.0, Math.min(5.0, (nowMs - lastMs) / 1000.0));
            smoothC += (celsius - smoothC) * (1.0 - Math.exp(-dt / SMOOTH_S));
        }
        lastMs = nowMs;

        int z = smoothC >= ceilingC ? 1 : smoothC <= ceilingC - RELEASE_BELOW_C ? -1 : 0;
        if (z != zone) {
            zone = z;
            sinceMs = nowMs;
        }
        if (zone == 1 && trim < MAX_TRIM && nowMs - sinceMs >= UP_MS) {
            trim++;
            sinceMs = nowMs;
        } else if (zone == -1 && trim > 0 && nowMs - sinceMs >= DOWN_MS) {
            trim--;
            sinceMs = nowMs;
        }
        return trim;
    }

    /** The curve with the trim added at every knee above the floor, clipped at 83. The floor is never raised. */
    public static CurveConfig apply(CurveConfig base, int trim) {
        if (trim <= 0) {
            return base;
        }
        CurveConfig c = CurveConfig.decode(base.encode());
        for (int p = 0; p < CurveConfig.PROFILES; p++) {
            for (int k = 1; k < CurveConfig.POINTS; k++) {
                c.duty[p][k] = Math.min(83, c.duty[p][k] + trim);
            }
        }
        return c;
    }
}
