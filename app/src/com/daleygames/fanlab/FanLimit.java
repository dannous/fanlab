package com.daleygames.fanlab;

/**
 * The owner's fan limit: the loudest the fan may go. Up to the limit the fan does the work; at
 * the limit the picture is dimmed a point at a time instead, and brought back when there is room.
 *
 * The owner notices the fan at about 35 %, accepts under 40 for almost everything and takes 45
 * for brightness; a few percent of light is not seen, a few points of fan are heard. Dimming
 * also acts within seconds where a fan change takes minutes.
 *
 * Safety beats quiet: far over the target, or over it with nothing left to dim, the limit is
 * lifted until the LED is back near the target. The 62 C trip and the fail-safe are untouched.
 *
 * Pure: no hardware, no clock of its own. The caller applies {@link #trim()} to the LED drive and
 * {@link #ceilingFor} / {@link #floorFor} to the fan.
 */
public final class FanLimit {

    /** No dimming this long after a brightness change, ms: the LED jumps about 4 C on a switch up and falls back by itself. */
    static final long GRACE_MS = 90000L;

    /** Over the target by this much, degrees C, the limit is lifted whatever else is true. */
    static final double LIFT_OVER_C = 3.0;

    /** Over by this much with nothing left to dim, the limit is lifted too. */
    static final double LIFT_SPENT_OVER_C = 1.0;

    /** Lifted, the limit comes back once the LED is this close over the target or under it. */
    static final double RELEASE_OVER_C = 1.0;

    /** Brightness comes back a point a minute once the LED is this far under the target. */
    static final double RESTORE_UNDER_C = 0.6;
    static final long RESTORE_EVERY_MS = 60000L;

    private static final double SMOOTH_S = 8.0;

    private int trim;
    private boolean lifted;
    private int level = -1;
    private long graceUntilMs;
    private long changedMs;
    private long lastMs;
    private double smoothC = Double.NaN;
    private double lastTargetC = Double.NaN;
    private double graceTargetC = Double.NaN;

    /** Points currently taken off the LED drive for the mode on screen. */
    public int trim() {
        return trim;
    }

    /** True while the limit is lifted for temperature. */
    public boolean lifted() {
        return lifted;
    }

    public void reset() {
        trim = 0;
        lifted = false;
        level = -1;
        graceUntilMs = 0L;
        changedMs = 0L;
        lastMs = 0L;
        smoothC = Double.NaN;
        lastTargetC = Double.NaN;
        graceTargetC = Double.NaN;
    }

    /** How often to dim one point, ms, for a reading {@code over} the target; 0 for not at all. */
    static long dimEveryMs(double over) {
        if (over >= 2.0) {
            return 3000L;
        }
        if (over >= 0.5) {
            return 15000L;
        }
        if (over >= 0.05) {
            return 60000L;
        }
        return 0L;
    }

    /**
     * Advance one tick.
     *
     * @param limit    the fan limit, percent; 0 or less for none
     * @param fanDuty  the duty the fan controller is asking for
     * @param maxTrim  how many points the drive in this mode can come down (to the stock level)
     * @return a note for the log when the brightness or the lift changed, else null
     */
    public String step(int limit, int fanDuty, double degC, double targetC, int rgblevel, int maxTrim,
                       long nowMs) {
        if (limit <= 0 || Double.isNaN(degC)) {
            boolean was = trim > 0 || lifted;
            int old = trim;
            reset();
            return was ? "limit off: brightness back " + old + " points" : null;
        }
        String note = null;
        if (rgblevel != level) {
            // A new mode starts at its full brightness, with the grace a switch needs.
            if (trim > 0) {
                note = "limit: brightness back " + trim + " points (mode changed)";
            }
            trim = 0;
            lifted = false;
            level = rgblevel;
            graceUntilMs = nowMs + GRACE_MS;
            changedMs = nowMs;
            // A switch down arrives warm from the brighter mode: judge it against the higher target
            // until the grace is over, or the limit lifts the moment a lower target appears.
            graceTargetC = Double.isNaN(lastTargetC) ? targetC : Math.max(lastTargetC, targetC);
        }
        lastTargetC = targetC;
        if (nowMs < graceUntilMs && graceTargetC > targetC) {
            targetC = graceTargetC;
        }
        if (Double.isNaN(smoothC) || lastMs == 0L) {
            smoothC = degC;
        } else {
            double dt = Math.max(0.0, Math.min(5.0, (nowMs - lastMs) / 1000.0));
            smoothC += (degC - smoothC) * (1.0 - Math.exp(-dt / SMOOTH_S));
        }
        lastMs = nowMs;
        double over = smoothC - targetC;
        if (maxTrim < 0) {
            maxTrim = 0;
        }
        if (trim > maxTrim) {
            trim = maxTrim;
        }

        boolean wasLifted = lifted;
        if (degC - targetC >= LIFT_OVER_C || (trim >= maxTrim && over >= LIFT_SPENT_OVER_C)) {
            lifted = true;
        } else if (lifted && over < RELEASE_OVER_C && (trim < maxTrim || over < 0.0)) {
            lifted = false;
        }
        if (lifted != wasLifted) {
            note = join(note, lifted ? "limit " + limit + " lifted at " + Sample.fmt1(degC) + " C"
                    : "limit " + limit + " back");
        }

        boolean atLimit = fanDuty >= limit;
        long every = dimEveryMs(over);
        if (atLimit && nowMs >= graceUntilMs && trim < maxTrim && every > 0L && nowMs - changedMs >= every) {
            trim++;
            changedMs = nowMs;
            note = join(note, "limit " + limit + ": brightness -" + trim);
        } else if (trim > 0 && over < -RESTORE_UNDER_C && nowMs - changedMs >= RESTORE_EVERY_MS) {
            trim--;
            changedMs = nowMs;
            note = join(note, "limit " + limit + ": brightness " + (trim == 0 ? "back to full" : "-" + trim));
        }
        return note;
    }

    /** The most the fan may go right now. */
    public int ceilingFor(int limit) {
        return limit <= 0 || lifted ? AutoHold.MAX_DUTY : Math.max(AutoHold.MIN_DUTY, limit);
    }

    /** The least the fan may go: while the picture is dimmed the fan stays at the limit, so brightness comes back before the fan comes down. */
    public int floorFor(int normalFloor, int limit) {
        return trim > 0 && limit > normalFloor ? Math.min(limit, AutoHold.MAX_DUTY) : normalFloor;
    }

    private static String join(String a, String b) {
        return a == null ? b : a + " " + b;
    }
}
