package com.daleygames.fanlab;

/** The integral controller: walk the duty until the light engine sits at the ceiling, and keep walking. */
public final class FanLinear {

    /** Commanded duty before the guard. Negative until a measurement or a {@link #resync} has established one. */
    private int duty = -1;

    /** Waiting to be seeded from the curve; set by {@link #resync}, cleared by the first tick with both a temperature and a curve. */
    private boolean seedPending;

    /** Monotonic time of the last decision. 0 means the clock has not started. */
    private long lastStepMs;

    /** Has the controller run out of authority in the direction it wants to go? */
    private boolean saturated;

    /** SoC die temperature driving the guard, after its own hysteresis. NaN if never seen. */
    private double heldSocC = Double.NaN;

    /** Duty points the guard actually added on the last tick; 0 when it did nothing. */
    private int guardBoost;

    /** Recent temperature history for the trend gate. Timestamps are kept alongside the readings, because a suspend can leave a gap. */
    private final double[] histC = new double[LinearConfig.MAX_TREND_S + 1];
    private final long[] histMs = new long[LinearConfig.MAX_TREND_S + 1];
    private int histCount;
    private int histHead;

    /** Slope of the last accepted fit, degrees per second. NaN when there was no fit. */
    private double lastTrend = Double.NaN;

    /** Did the trend gate hold a step back on the most recent decision? */
    private boolean trendHeld;

    /**
     * Adopt the duty the hardware is actually at, and start the step clock again. Called at start,
     * on resume, and whenever a read-back shows something else wrote the node; an unknown duty
     * starts the walk from {@link FanIo#FAIL_SAFE_DUTY} rather than lower.
     */
    public void resync(int currentDuty) {
        duty = FanIo.valid(currentDuty) ? currentDuty : FanIo.FAIL_SAFE_DUTY;
        lastStepMs = 0L;
        seedPending = true;
    }

    public void reset() {
        duty = -1;
        lastStepMs = 0L;
        saturated = false;
        heldSocC = Double.NaN;
        guardBoost = 0;
        seedPending = false;
        histCount = 0;
        histHead = 0;
        lastTrend = Double.NaN;
        trendHeld = false;
    }

    /** The duty the integrator holds, before the guard adds anything; -1 before the first. */
    public int baseDuty() {
        return duty;
    }

    /** Is the controller out of authority? At maxDuty while still at or above the ceiling, or at minDuty while still below it. */
    public boolean saturated() {
        return saturated;
    }

    /** Duty points the SoC guard added on the most recent tick, after its ceiling. */
    public int guardBoost() {
        return guardBoost;
    }

    /** The SoC temperature driving the guard, after hysteresis; NaN if never sampled. */
    public double heldSocCelsius() {
        return heldSocC;
    }

    /** Slope of the temperature over the trend window, degrees per second; NaN until the window has filled. */
    public double trendPerSec() {
        return lastTrend;
    }

    /** Did the trend gate hold back the most recent decision? */
    public boolean trendHeld() {
        return trendHeld;
    }

    /** Least-squares slope over the trend window, degrees per second, or NaN. */
    private double fitTrend(int windowS) {
        if (windowS <= 0 || histCount < 3) {
            return Double.NaN;
        }
        long newest = histMs[(histHead - 1 + histC.length) % histC.length];
        long oldestWanted = newest - windowS * 1000L;
        int n = 0;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < histCount; i++) {
            int idx = (histHead - 1 - i + histC.length * 2) % histC.length;
            long t = histMs[idx];
            if (t < oldestWanted) {
                break;
            }
            double x = (t - newest) / 1000.0;      // seconds, negative going back
            double y = histC[idx];
            sx += x; sy += y; sxx += x * x; sxy += x * y;
            n++;
        }
        // Demand most of the window, so a gap cannot produce a confident slope.
        if (n < windowS / 2) {
            return Double.NaN;
        }
        double den = n * sxx - sx * sx;
        if (den == 0.0) {
            return Double.NaN;
        }
        return (n * sxy - sx * sy) / den;
    }

    /** Three standard errors on that slope: a gate at this level cannot be tripped by sensor noise. */
    private static double trendNoise(int windowS) {
        double t = windowS;
        return 3.0 * LinearConfig.SENSOR_NOISE_SD_C * Math.sqrt(12.0 / ((t + 1.0) * t * t));
    }

    /** Advance one tick with no SoC guard and no curve to seed from: the walk starts from whatever {@link #resync} adopted. */
    public int step(LinearConfig cfg, double celsius, boolean engineOn, long nowMs) {
        return step(cfg, null, CurveConfig.PROFILE_HIGH, celsius, Double.NaN, engineOn, nowMs);
    }

    /**
     * Advance the controller one tick. Called at 1 Hz, but it only decides once the current
     * interval has elapsed. {@code celsius} must already be plausible, {@code socCelsius} is
     * thermal_zone0 or NaN, and {@code nowMs} is monotonic. The SoC guard is applied to the
     * output and is strictly additive: it can raise the duty and never lower it.
     */
    public int step(LinearConfig cfg, CurveConfig curve, int profile, double celsius,
            double socCelsius, boolean engineOn, long nowMs) {
        if (cfg == null || Double.isNaN(celsius) || Double.isInfinite(celsius)) {
            // Should be unreachable: the caller gates on Thermistor.plausible. Fail high and
            // touch nothing -- a tick with no usable measurement is not a decision.
            return FanIo.FAIL_SAFE_DUTY;
        }
        if (duty < 0) {
            // Nothing has told us where the fan is. Same answer as resync(-1).
            duty = FanIo.FAIL_SAFE_DUTY;
        }
        if (duty < cfg.minDuty) {
            duty = cfg.minDuty;
        }
        if (duty > cfg.maxDuty) {
            duty = cfg.maxDuty;
        }

        if (!Double.isNaN(socCelsius) && !Double.isInfinite(socCelsius)) {
            double hyst = curve == null ? 0.0 : curve.socGuardHystC;
            if (Double.isNaN(heldSocC) || socCelsius >= heldSocC) {
                heldSocC = socCelsius;
            } else if (socCelsius <= heldSocC - hyst) {
                heldSocC = socCelsius;
            }
        } else {
            heldSocC = Double.NaN;
        }

        if (!engineOn) {
            // Idle, as the curve does. The integrator keeps its duty and the step clock restarts,
            // so the first decision after the engine returns comes a full interval later.
            lastStepMs = nowMs;
            saturated = false;
            guardBoost = 0;
            return clampWritable(cfg.idleDuty);
        }

        histC[histHead] = celsius;
        histMs[histHead] = nowMs;
        histHead = (histHead + 1) % histC.length;
        if (histCount < histC.length) {
            histCount++;
        }

        // Seed from the curve, once, on the first tick after a resync that can read one. The
        // step clock restarts with it, so the controller looks before it moves.
        if (seedPending && curve != null) {
            seedPending = false;
            int seeded = curve.dutyAt(profile, celsius);
            if (seeded < cfg.minDuty) {
                seeded = cfg.minDuty;
            }
            if (seeded > cfg.maxDuty) {
                seeded = cfg.maxDuty;
            }
            duty = seeded;
            lastStepMs = 0L;
        }

        boolean rising = celsius >= cfg.ceilingC;
        long interval;
        if (rising) {
            interval = cfg.upStepMs;
        } else if (celsius >= cfg.ceilingC - cfg.nearC) {
            interval = cfg.downStepMs;
        } else {
            interval = cfg.downFastMs;
        }

        trendHeld = false;
        if (lastStepMs == 0L) {
            // Look before moving: there is one measurement so far, and a decision needs an interval.
            lastStepMs = nowMs;
        } else if (nowMs - lastStepMs >= interval) {
            // One step, however long the gap was: a suspend must not buy a catch-up burst.
            lastStepMs = nowMs;

            // The trend gate. The ceiling has already decided the direction; this decides whether
            // to act on it now. It only delays a step -- it never caps the duty.
            lastTrend = fitTrend(cfg.trendWindowS);
            boolean act = true;
            if (!Double.isNaN(lastTrend)) {
                double floor = trendNoise(cfg.trendWindowS);
                if (rising && lastTrend < -floor) {
                    act = false;
                } else if (!rising && lastTrend > floor) {
                    act = false;
                }
            }
            if (act) {
                if (rising) {
                    saturated = duty >= cfg.maxDuty;
                    duty = Math.min(cfg.maxDuty, duty + 1);
                } else {
                    saturated = duty <= cfg.minDuty;
                    duty = Math.max(cfg.minDuty, duty - 1);
                }
            } else {
                trendHeld = true;
            }
        }

        int out = duty;
        guardBoost = 0;
        if (curve != null) {
            int boost = curve.socGuardBoost(heldSocC);
            if (boost > 0) {
                int raised = out + boost;
                if (raised > curve.socGuardMaxDuty) {
                    raised = curve.socGuardMaxDuty;
                }
                if (raised > out) {
                    guardBoost = raised - out;
                    out = raised;
                }
            }
        }
        return clampWritable(out);
    }

    private static int clampWritable(int d) {
        if (d < FanIo.MIN_DUTY) {
            return FanIo.MIN_DUTY;
        }
        if (d > FanIo.MAX_DUTY) {
            return FanIo.MAX_DUTY;
        }
        return d;
    }
}
