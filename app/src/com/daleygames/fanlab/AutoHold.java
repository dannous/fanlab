package com.daleygames.fanlab;

/**
 * AUTO: no curve. Steps the fan one point at a time to keep the LED thermistor just under a
 * target, so the fan is as slow as the room allows. Inside the band it does not move at all.
 */
public final class AutoHold {

    /** Stock drive target, degrees C: where the stock ladder itself commands maximum fan. */
    public static final double TARGET_C = 55.0;

    /** Target with the LED drive raised, degrees C. The owner's choice; 3 C under {@link LedDrive#DEFAULT_TRIP_C}. */
    public static final double BOOST_TARGET_C = 58.0;

    /** Width of the band under the target in which the fan is left alone, degrees C. Wider than one duty point is worth anywhere on the measured plant. */
    public static final double BAND_C = 1.5;

    /**
     * Lowest duty in any mode. The owner hears no difference between 20 and 25, so nothing
     * runs below 25. The written record only proves 30 against the stall watchdog.
     */
    public static final int MIN_DUTY = 25;

    /** The floor for a brightness mode, by {@code rgblevel}: more heat keeps more fan in reserve. Unknown levels get the highest. */
    public static int minFor(int rgblevel) {
        switch (rgblevel) {
            case 4:     // Super Eco
            case 1:     // Eco
                return 25;
            case 2:     // Normal
                return 27;
            default:    // Presentation, or unreadable
                return 30;
        }
    }

    /** Below this duty the plant is unmeasured and steeper, so the hold band is widened by {@link #LOW_BAND_EXTRA_C}. */
    public static final int MEASURED_FLOOR = 30;

    public static final double LOW_BAND_EXTRA_C = 1.5;

    /** 83 is the stock controller's maximum, and the fail-safe. */
    public static final int MAX_DUTY = 83;

    /** Smoothing time constant on the input, seconds; sensor noise must not look like a trend. */
    private static final double SMOOTH_S = 20.0;

    private int floor = MIN_DUTY;
    private double duty = Double.NaN;
    private double smoothC = Double.NaN;
    private long lastMs;
    private long steppedMs;

    public static double targetFor(boolean driveOn) {
        return driveOn ? BOOST_TARGET_C : TARGET_C;
    }

    /** Adopt the duty actually on the fan. An unknown duty starts from the fail-safe, never lower. */
    public void resync(int currentDuty) {
        duty = FanIo.valid(currentDuty) ? clamp(currentDuty) : FanIo.FAIL_SAFE_DUTY;
        steppedMs = 0L;
    }

    public void reset() {
        duty = Double.NaN;
        smoothC = Double.NaN;
        lastMs = 0L;
        steppedMs = 0L;
    }

    /**
     * Milliseconds to wait before the next one-point step, or 0 for "do not move".
     * Positive {@code errC} is above the target. Rising is quicker than falling, and both
     * speed up with distance so a cold start and a brightness change settle in minutes.
     */
    static long intervalFor(double errC) {
        if (errC >= 3.0) {
            return 2000L;
        }
        if (errC >= 1.5) {
            return 10000L;
        }
        if (errC >= 0.5) {
            return 45000L;
        }
        if (errC >= 0.0) {
            return 120000L;
        }
        if (errC > -BAND_C) {
            return 0L;
        }
        if (errC > -4.0) {
            return 300000L;
        }
        if (errC > -8.0) {
            return 60000L;
        }
        return 3000L;
    }

    /** Advance one tick and return the duty. {@code celsius} must already be plausible; the caller's fail-safe owns the rest. */
    public int step(double celsius, double targetC, long nowMs) {
        return step(celsius, targetC, MIN_DUTY, nowMs);
    }

    /** As above, with the floor for the current brightness mode; see {@link #minFor}. */
    public int step(double celsius, double targetC, int minDuty, long nowMs) {
        floor = minDuty < MIN_DUTY ? MIN_DUTY : minDuty > MAX_DUTY ? MAX_DUTY : minDuty;
        if (Double.isNaN(duty)) {
            duty = FanIo.FAIL_SAFE_DUTY;
        }
        if (Double.isNaN(celsius) || Double.isInfinite(celsius)) {
            return (int) duty;
        }
        duty = clamp(duty);
        if (Double.isNaN(smoothC) || lastMs == 0L) {
            smoothC = celsius;
        } else {
            double dt = Math.max(0.0, Math.min(5.0, (nowMs - lastMs) / 1000.0));
            smoothC += (celsius - smoothC) * (1.0 - Math.exp(-dt / SMOOTH_S));
        }
        lastMs = nowMs;
        if (steppedMs == 0L) {
            steppedMs = nowMs;
        }
        // The raw reading can raise the fan sooner than the smoothed one; only the smoothed one may lower it.
        double err = Math.max(smoothC, celsius >= targetC + 1.5 ? celsius : smoothC) - targetC;
        // Under the measured floor a point is worth more degrees, so falling needs more headroom.
        if (err < 0.0 && duty <= MEASURED_FLOOR) {
            err = Math.min(0.0 - 1e-9, err + LOW_BAND_EXTRA_C);
        }
        long wait = intervalFor(err);
        if (wait > 0L && nowMs - steppedMs >= wait) {
            duty = clamp(duty + (err >= 0.0 ? 1 : -1));
            steppedMs = nowMs;
        }
        return (int) duty;
    }

    private double clamp(double d) {
        return d < floor ? floor : d > MAX_DUTY ? MAX_DUTY : d;
    }
}
