package com.daleygames.fanlab;

/**
 * The one fixed AUTO schedule, and every limit the sweep obeys.
 *
 * There is deliberately no options screen and no configurability in the UI. One button,
 * one schedule. Every knob is a knob that can be set wrong, and a run that cannot be
 * compared with anyone else's. These constants are the whole specification of what AUTO
 * does, so they are gathered here rather than scattered through the engine.
 *
 * <h3>The schedule</h3>
 * Descending duty, starting cool and safe, so temperature rises toward each new
 * equilibrium from below and the run approaches the interesting region gradually.
 * All four brightness modes in the one unattended run:
 * <ol>
 *   <li><b>Presentation</b> (rgblevel 3) - the problem mode, full schedule, first, while
 *       the machine is coldest and the user is most likely still in the room for the
 *       audibility marker;</li>
 *   <li><b>Normal</b> (2) - full schedule;</li>
 *   <li><b>Eco</b> (1) - short schedule; its equilibrium is far below the 45.5 C first
 *       ladder boundary, so only the bottom of the range is interesting;</li>
 *   <li><b>Super Eco</b> (4) - short schedule, included specifically to confirm on
 *       hardware that it behaves like Eco. The disassembly says rgblevel 4 and 1 share the
 *       {@code low} fan tier, and level 4 drives the LEDs lower still, but neither has been
 *       observed directly.</li>
 * </ol>
 *
 * <h3>The hard ceiling is 58 C, and it is not configurable</h3>
 * Above 54.5 C the stock ladder is already commanding 83, so between there and the ceiling
 * a sweep would be overriding a safety ramp for no analytic gain - any duty whose
 * equilibrium sits that high is already disqualified as a candidate floor. 58 still clears
 * every ladder boundary, so no useful data is lost, and it leaves 17 C to the 75 C
 * shutdown instead of 13.
 *
 * Pure Java.
 */
public final class SweepPlan {

    // ------------------------------------------------------------------ the schedule

    /** Presentation and Normal: the full descent. */
    public static final int[] FULL = {83, 75, 70, 65, 60, 55, 50, 45, 40};

    /** Eco and Super Eco: only the bottom of the range is informative. */
    public static final int[] SHORT = {60, 50, 45, 40, 35};

    /** rgblevel values, in the order they are swept. */
    public static final int[] MODE_ORDER = {3, 2, 1, 4};

    /** Which schedule each entry of {@link #MODE_ORDER} uses. */
    public static boolean isFullSchedule(int rgblevel) {
        return rgblevel == 3 || rgblevel == 2;
    }

    public static int[] scheduleFor(int rgblevel) {
        return isFullSchedule(rgblevel) ? FULL : SHORT;
    }

    /** Total number of duty steps in the whole run, across all four modes. */
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

    // ------------------------------------------------------------------ hard limits

    /**
     * Hard ceiling, degrees C. At or above this the sweep writes {@link FanIo#FAIL_SAFE_DUTY}
     * immediately, ends the step and aborts the run. Not configurable, deliberately.
     */
    public static final double CEILING_C = 58.0;

    /**
     * Refuse to start a run that is already this warm. A sweep starting at 54 C would trip
     * the ceiling within a step or two and waste ninety minutes of the user's evening.
     */
    public static final double MAX_START_C = 50.0;

    /**
     * Rate guard: a rise faster than this over {@link #RATE_WINDOW_MS} means the current
     * duty is far below equilibrium and waiting it out is not worth the risk.
     */
    public static final double RATE_GUARD_C = 0.5;
    public static final long RATE_WINDOW_MS = 10000L;

    /**
     * Never command below this. The driver's own default is 50-55, Eco 38 is known good,
     * and going lower risks the kernel FANSTOP stall watchdog, which hard-shuts the
     * projector about a second after two missed tacho windows.
     */
    public static final int DUTY_FLOOR = 35;

    /** Clamp for every rgblevel write: nothing outside this is exercised by the stock app. */
    public static final int RGBLEVEL_MIN = 1;
    public static final int RGBLEVEL_MAX = 4;

    public static boolean validRgbLevel(int v) {
        return v >= RGBLEVEL_MIN && v <= RGBLEVEL_MAX;
    }

    // ------------------------------------------------------------------ dwell

    /** No step is ever called before this. */
    public static final long DWELL_MIN_MS = 180000L;

    /**
     * Backstop for a step whose fit never settles. 480 s is 8 minutes; against a tau of a
     * minute or two that is several time constants, and with 28 steps a larger cap would
     * put the worst case beyond any plausible unattended run.
     */
    public static final long DWELL_MAX_MS = 480000L;

    /** Cooling and re-baselining at duty 83 between modes is capped at this. */
    public static final long BASELINE_MAX_MS = 300000L;
    /** ...and never called before this, so the mode change has time to take effect. */
    public static final long BASELINE_MIN_MS = 60000L;

    /**
     * The whole run stops here regardless. 150 minutes. Everything measured so far is
     * written out with {@code end_reason = time_cap}; nothing is lost.
     */
    public static final long RUN_CAP_MS = 150L * 60000L;

    /** A step is settled when the projected T_inf has held this band for {@link #SETTLE_WINDOW_MS}. */
    public static final double SETTLE_BAND_C = 0.4;      // i.e. +/- 0.2
    public static final long SETTLE_WINDOW_MS = 60000L;
    /** ...and the fit is this good. A worse fit means the projection is not to be trusted yet. */
    public static final double SETTLE_MAX_RMS_C = 0.20;
    /** Refit no more often than this; the fit is cheap but not free. */
    public static final long REFIT_EVERY_MS = 10000L;

    /** Baseline is called when the temperature has moved less than this over 60 s. */
    public static final double BASELINE_STABLE_C = 0.15;

    /** Consecutive implausible temperature readings tolerated before aborting the run. */
    public static final int BAD_READS_ABORT = 3;

    // ------------------------------------------------------------------ estimate

    /**
     * Optimistic duration, seconds: every step called at the minimum dwell and every
     * baseline settling quickly. This is the number the confirmation screen leads with,
     * and it is genuinely achievable - it is what the exponential fit buys.
     */
    public static long estimateBestSeconds() {
        return (totalSteps() * DWELL_MIN_MS
                + MODE_ORDER.length * BASELINE_MIN_MS + 30000L) / 1000L;
    }

    /**
     * The other end of the honest range: every step still called early on the fit, but
     * every mode change taking its full re-cool. That is what a simulated run against a
     * first-order model with tau = 60 s actually costs, so it is what the confirmation
     * screen quotes alongside the optimistic figure.
     */
    public static long estimateTypicalSeconds() {
        return (totalSteps() * DWELL_MIN_MS
                + MODE_ORDER.length * BASELINE_MAX_MS + 30000L) / 1000L;
    }

    /** The cap, seconds. The run cannot exceed this. */
    public static long capSeconds() {
        return RUN_CAP_MS / 1000L;
    }

    private SweepPlan() {
    }
}
