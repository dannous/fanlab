package com.daleygames.fanlab;

/** The one fixed AUTO schedule, and every limit the sweep obeys. */
public final class SweepPlan {

    /** Presentation and Normal: the full descent. */
    public static final int[] FULL = {83, 75, 70, 65, 60, 55, 50, 45, 40};

    /** Eco and Super Eco: only the bottom of the range is informative. */
    public static final int[] SHORT = {60, 50, 45, 40, 35};

    /** rgblevel values, in the order they are swept. */
    public static final int[] MODE_ORDER = {3, 2, 1, 4};

    public static boolean isFullSchedule(int rgblevel) {
        return rgblevel == 3 || rgblevel == 2;
    }

    public static int[] scheduleFor(int rgblevel) {
        return isFullSchedule(rgblevel) ? FULL : SHORT;
    }

    public static int totalSteps() {
        int n = 0;
        for (int i = 0; i < MODE_ORDER.length; i++) {
            n += scheduleFor(MODE_ORDER[i]).length;
        }
        return n;
    }

    public static String modeName(int rgblevel) {
        switch (rgblevel) {
            case 1:
                return "Eco";
            case 2:
                return "Normal";
            case 3:
                return "Presentation";
            case 4:
                return "Super Eco";
            default:
                return "rgblevel " + rgblevel;
        }
    }

    /** Hard ceiling, degrees C: at or above this the sweep writes FAIL_SAFE_DUTY, ends the step and aborts. Not configurable. */
    public static final double CEILING_C = 58.0;

    /** Refuse to start a run that is already this warm, degrees C. */
    public static final double MAX_START_C = 50.0;

    /** Rate guard: a rise faster than this, degrees C over {@link #RATE_WINDOW_MS}, backs the step off. */
    public static final double RATE_GUARD_C = 0.5;
    public static final long RATE_WINDOW_MS = 10000L;

    /** Never command below this: lower risks the kernel FANSTOP stall watchdog, which hard-shuts the projector. */
    public static final int DUTY_FLOOR = 35;

    public static final int RGBLEVEL_MIN = 1;
    public static final int RGBLEVEL_MAX = 4;

    public static boolean validRgbLevel(int v) {
        return v >= RGBLEVEL_MIN && v <= RGBLEVEL_MAX;
    }

    public static final long DWELL_MIN_MS = 180000L;

    /** Backstop for a step whose fit never settles; 8 minutes is several time constants. */
    public static final long DWELL_MAX_MS = 480000L;

    /** Cooling and re-baselining at duty 83 between modes is capped at this. */
    public static final long BASELINE_MAX_MS = 300000L;
    /** ...and never called before this, so the mode change has time to take effect. */
    public static final long BASELINE_MIN_MS = 60000L;

    /** The whole run stops here regardless; everything measured is written with end_reason = time_cap. */
    public static final long RUN_CAP_MS = 150L * 60000L;

    /** A step is settled when the projected T_inf has held this band for {@link #SETTLE_WINDOW_MS}. */
    public static final double SETTLE_BAND_C = 0.4;      // i.e. +/- 0.2
    public static final long SETTLE_WINDOW_MS = 60000L;
    /** ...and the fit is this good. A worse fit means the projection is not to be trusted yet. */
    public static final double SETTLE_MAX_RMS_C = 0.20;
    public static final long REFIT_EVERY_MS = 10000L;

    /** Baseline is called when the temperature has moved less than this over 60 s. */
    public static final double BASELINE_STABLE_C = 0.15;

    public static final int BAD_READS_ABORT = 3;

    /** Optimistic duration, seconds: every step called at the minimum dwell. */
    public static long estimateBestSeconds() {
        return (totalSteps() * DWELL_MIN_MS
                + MODE_ORDER.length * BASELINE_MIN_MS + 30000L) / 1000L;
    }

    /** The other end of the honest range, seconds: steps still called early, every mode change taking its full re-cool. */
    public static long estimateTypicalSeconds() {
        return (totalSteps() * DWELL_MIN_MS
                + MODE_ORDER.length * BASELINE_MAX_MS + 30000L) / 1000L;
    }

    public static long capSeconds() {
        return RUN_CAP_MS / 1000L;
    }

    private SweepPlan() {
    }
}
