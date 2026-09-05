package com.daleygames.fanlab;

/**
 * The controller: turns a stream of temperature readings into a stream of duties that
 * are safe, monotone in temperature, and inaudible in their changes.
 *
 * Three stages, in this order.
 * <ol>
 *   <li><b>Hysteresis on the input.</b> The curve is driven by a held temperature, not
 *       the raw one. The held value follows the measurement up immediately, and follows
 *       it down only once the measurement has dropped {@code hysteresisC} below it. This
 *       is the thing the stock controller is missing, and it is what stops the fan
 *       oscillating when the equilibrium parks on a boundary.</li>
 *   <li><b>The curve.</b> {@link CurveConfig#dutyAt}, a pure piecewise-linear map.</li>
 *   <li><b>Slew limit.</b> The commanded duty moves toward the target at no more than
 *       {@code slewUpPerSec} / {@code slewDownPerSec} points per second, so no single
 *       change is large enough to be heard as a change.</li>
 * </ol>
 *
 * Two deliberate exceptions to the slew limit, both in the safe direction:
 * <ul>
 *   <li>a <b>tier change</b> (rgblevel moved, or the light engine came on) jumps
 *       immediately if the new target is higher, and slews normally if it is lower -
 *       matching the stock controller's own mode-change behaviour, which writes the new
 *       tier's floor at once;</li>
 *   <li>{@link #resync} snaps the internal output to whatever the kernel actually holds,
 *       so after a resume or a stall - when the driver has silently reimposed 55% - the
 *       ramp starts from the real value instead of stepping.</li>
 * </ul>
 *
 * Pure Java: no android.* imports, no I/O. Everything here is exercised by the host test.
 */
public final class FanCurve {

    /** Temperature currently driving the curve, after hysteresis. NaN until the first sample. */
    private double heldC = Double.NaN;

    /** SoC die temperature driving the guard, after its own hysteresis. NaN if never seen. */
    private double heldSocC = Double.NaN;

    /** Duty points the guard actually added on the last step; 0 when it did nothing. */
    private int guardBoost;

    /** Commanded duty as an exact value, before rounding. NaN until the first sample. */
    private double output = Double.NaN;

    /** Timestamp of the previous step, for the slew rate. 0 means "no previous step". */
    private long lastMs = 0L;

    /**
     * Set when the controller has just been handed the fan and the duty it inherited is
     * not the one the curve wants. While set, the output converges at
     * {@link #CATCHUP_PER_SEC} instead of the comfort slew.
     *
     * The slew limiter exists to hide changes the listener did not cause. A change that
     * follows immediately from someone pressing a button is attributable, expected, and
     * does not need hiding -- whereas making them wait seven minutes for the fan to crawl
     * down from a fail-safe 83 is exactly the noise the whole design is meant to avoid.
     * So: hide drift, do not hide the consequences of an instruction.
     */
    private boolean catchingUp;

    /** Profile the previous step ran with, to detect a tier change. */
    private int lastProfile = -1;

    /** Light-engine state the previous step ran with. */
    private boolean lastEngineOn = true;

    /** Longest gap the slew limiter will integrate over, seconds. Guards against a stalled loop. */
    private static final double MAX_DT_SEC = 5.0;

    /**
     * Convergence rate, duty points per second, while catching up to the curve after
     * being handed a duty someone else chose. 1.5 covers the worst case -- the fail-safe
     * 83 down to the floor of 30 -- in about 35 seconds rather than seven minutes.
     */
    private static final double CATCHUP_PER_SEC = 1.5;

    /**
     * Adopt the duty the hardware is actually at, and forget the ramp history.
     *
     * Call this at start, on resume, and whenever a read-back shows something else has
     * written the node. Without it, the first commanded value after a resume would be a
     * step from 55 to wherever the curve is.
     *
     * If the current duty is not known - fan_ctrl unreadable - the ramp starts from
     * {@link FanIo#FAIL_SAFE_DUTY} and slews down, rather than jumping to whatever the
     * curve wants. Not knowing where the fan is must never be a reason to lower it.
     *
     * @param currentDuty the value read back from fan_ctrl, or -1 if unknown.
     */
    public void resync(int currentDuty) {
        output = FanIo.valid(currentDuty) ? currentDuty : FanIo.FAIL_SAFE_DUTY;
        lastMs = 0L;
        catchingUp = true;
    }

    /** Forget everything, including the held temperature. */
    public void reset() {
        heldC = Double.NaN;
        heldSocC = Double.NaN;
        guardBoost = 0;
        output = Double.NaN;
        lastMs = 0L;
        lastProfile = -1;
        lastEngineOn = true;
        catchingUp = false;
    }

    /** The temperature currently driving the curve; NaN before the first sample. */
    public double heldCelsius() {
        return heldC;
    }

    /** The un-rounded commanded duty; NaN before the first sample. */
    public double exactOutput() {
        return output;
    }

    /**
     * Duty points the SoC guard added on the most recent step, after its ceiling. Zero
     * means it changed nothing -- either the die is below the knee, the guard is disarmed,
     * or the curve was already asking for more than the guard's ceiling.
     */
    public int guardBoost() {
        return guardBoost;
    }

    /** The SoC temperature driving the guard, after hysteresis; NaN if never sampled. */
    public double heldSocCelsius() {
        return heldSocC;
    }

    /**
     * Advance the controller one step.
     *
     * @param cfg       the curve; must already have been {@link CurveConfig#sanitise}d.
     * @param profile   {@link CurveConfig#PROFILE_LOW} / NORMAL / HIGH.
     * @param celsius   the measured LED temperature. Must be plausible; the caller fails
     *                  safe on a bad read rather than passing NaN in here.
     * @param engineOn  false when led_status reads 0, i.e. the light engine is off.
     * @param nowMs     monotonic milliseconds.
     * @return a duty in 1..100 that is safe to write.
     */
    public int step(CurveConfig cfg, int profile, double celsius, boolean engineOn, long nowMs) {
        return step(cfg, profile, celsius, Double.NaN, engineOn, nowMs);
    }

    /**
     * Advance the controller one step, with the SoC guard.
     *
     * <h3>Why the guard is a floor and not an input</h3>
     * The obvious design is to drive the curve from {@code max(LED, SoC - offset)} and be
     * done. That would be wrong here, because it lets a sensor the safety case does not
     * rest on <i>lower</i> the fan: a cool SoC reading would be able to argue the duty
     * down below what the LED thermistor is asking for. Taking the guard as a floor
     * instead -- {@code max(curve(LED), guard(SoC))} -- makes it strictly additive. It can
     * raise the fan and can never lower it, so arming it cannot make the machine hotter or
     * the controller less safe than it was without it, and the worst a wrong guard number
     * can do is cost noise.
     *
     * The floor is slew-limited like everything else: engaging is not a tier change and
     * does not jump.
     *
     * @param socCelsius SoC die temperature (thermal_zone0), or NaN if unreadable.
     */
    public int step(CurveConfig cfg, int profile, double celsius, double socCelsius,
            boolean engineOn, long nowMs) {
        if (cfg == null || Double.isNaN(celsius) || Double.isInfinite(celsius)) {
            // Should be unreachable: the caller gates on Thermistor.plausible. Fail high.
            return FanIo.FAIL_SAFE_DUTY;
        }

        // ---- stage 1: hysteresis on the input ----
        if (Double.isNaN(heldC) || celsius >= heldC) {
            heldC = celsius;                                  // rise: immediately
        } else if (celsius <= heldC - cfg.hysteresisC) {
            heldC = celsius;                                  // fall: only past the deadband
        }

        // ---- stage 1b: hysteresis on the guard's input ----
        // Same asymmetry as the curve's: a rising die is acted on at once, a falling one
        // only once it has fallen clear of the band. Letting the floor drop as eagerly as
        // it rose is what would turn it into a cycle.
        if (!Double.isNaN(socCelsius) && !Double.isInfinite(socCelsius)) {
            if (Double.isNaN(heldSocC) || socCelsius >= heldSocC) {
                heldSocC = socCelsius;
            } else if (socCelsius <= heldSocC - cfg.socGuardHystC) {
                heldSocC = socCelsius;
            }
        } else {
            // Nothing to go on this tick. Forget rather than hold: a stale value could
            // keep the fan up indefinitely on a sensor that has stopped reporting.
            heldSocC = Double.NaN;
        }

        // ---- stage 2: the curve ----
        int target = engineOn ? cfg.dutyAt(profile, heldC) : cfg.idleDuty;

        // ---- stage 2b: the guard ----
        // Additive, and applied only while the light engine is on: with it off the machine
        // is idling at duty 10 by design, and a warm die on the way down from a session is
        // not a reason to spin the fan back up.
        //
        // The boost is capped at socGuardMaxDuty rather than at maxDuty, and it can only
        // ever raise the result -- a curve already above the guard's ceiling is left where
        // it is, because the curve is the safety case and the guard is not entitled to
        // argue it down.
        guardBoost = 0;
        if (engineOn) {
            int boost = cfg.socGuardBoost(heldSocC);
            if (boost > 0) {
                int raised = target + boost;
                if (raised > cfg.socGuardMaxDuty) {
                    raised = cfg.socGuardMaxDuty;
                }
                if (raised > target) {
                    guardBoost = raised - target;
                    target = raised;
                }
            }
        }

        if (target < FanIo.MIN_DUTY) {
            target = FanIo.MIN_DUTY;
        }
        if (target > FanIo.MAX_DUTY) {
            target = FanIo.MAX_DUTY;
        }

        boolean tierChanged = (profile != lastProfile) || (engineOn != lastEngineOn);
        lastProfile = profile;
        lastEngineOn = engineOn;

        // ---- stage 3: slew limit ----
        if (Double.isNaN(output)) {
            output = target;                                  // first sample: adopt
        } else if (tierChanged && target > output) {
            output = target;                                  // upward tier change: no delay
        } else if (lastMs != 0L) {
            double dt = (nowMs - lastMs) / 1000.0;
            if (!(dt > 0.0)) {
                dt = 0.0;
            }
            if (dt > MAX_DT_SEC) {
                dt = MAX_DT_SEC;
            }
            // Catching up runs faster in BOTH directions, but only until the output first
            // reaches the curve; after that this is ordinary running and the comfort slew
            // applies. Never slower than the configured rate, so a user who deliberately
            // sets a brisk slew is not overridden.
            double up = catchingUp ? Math.max(cfg.slewUpPerSec, CATCHUP_PER_SEC) : cfg.slewUpPerSec;
            double down = catchingUp ? Math.max(cfg.slewDownPerSec, CATCHUP_PER_SEC)
                    : cfg.slewDownPerSec;
            if (target > output) {
                output = Math.min(target, output + up * dt);
            } else if (target < output) {
                output = Math.max(target, output - down * dt);
            }
            if (catchingUp && Math.abs(output - target) < 1e-9) {
                catchingUp = false;
            }
        }
        lastMs = nowMs;

        int duty = (int) Math.round(output);
        if (duty < FanIo.MIN_DUTY) {
            duty = FanIo.MIN_DUTY;
        }
        if (duty > FanIo.MAX_DUTY) {
            duty = FanIo.MAX_DUTY;
        }
        return duty;
    }

    /**
     * The stock ladder, reproduced exactly, for the diagnostics screen and for the
     * "what would stock do right now" comparison. Pure function.
     *
     * @param profile  tier index as per CurveConfig
     * @param tmp      the truncated temperature the stock controller compares, i.e.
     *                 {@code (int)(degC)} where degC already carries the +0.5 bias
     * @param floor    the tier's speed.min (what the stock ladder writes at or below 45)
     * @return the duty stock would command, or -1 for the 53/54 dead zone where the
     *         stock controller writes nothing at all.
     */
    public static int stockLadder(int profile, int tmp, int floor) {
        if (tmp <= 45) {
            return floor;
        }
        if (tmp <= 48) {
            return profile == CurveConfig.PROFILE_HIGH ? 70
                    : profile == CurveConfig.PROFILE_NORMAL ? 60 : 55;
        }
        if (tmp <= 50) {
            return profile == CurveConfig.PROFILE_HIGH ? 75
                    : profile == CurveConfig.PROFILE_NORMAL ? 65 : 60;
        }
        if (tmp <= 52) {
            return profile == CurveConfig.PROFILE_HIGH ? 80
                    : profile == CurveConfig.PROFILE_NORMAL ? 70 : 65;
        }
        if (tmp >= 55) {
            return profile == CurveConfig.PROFILE_HIGH ? 83
                    : profile == CurveConfig.PROFILE_NORMAL ? 80 : 70;
        }
        return -1;      // 53 and 54: the stock dead zone, no write happens
    }
}
