package com.daleygames.fanlab;

/**
 * The integral controller: walk the duty until the light engine sits at the ceiling, and
 * keep walking.
 *
 * <pre>
 *   pick the interval for what is happening right now:
 *       degC &gt;= ceilingC                 -> upStepMs     (5 s)
 *       degC &gt;= ceilingC - nearC         -> downStepMs   (60 s)
 *       otherwise                        -> downFastMs   (10 s)
 *
 *   once that interval has elapsed:
 *       if degC &gt;= ceilingC:  duty = min(maxDuty, duty + 1)
 *       else:                 duty = max(minDuty, duty - 1)
 * </pre>
 *
 * That is the whole controller. It has no plant model, which is the point: {@link FanCurve}
 * knows where duty 38 puts the light engine on one unit at 24 C ambient, and this knows
 * only whether the light engine is currently at or above the ceiling. A hotter room, a
 * hotter unit or a brighter picture is not a case it has to have been measured in.
 *
 * <h3>There is no third branch</h3>
 * It never holds. A hold zone would stop the walk at the top of the zone rather than at the
 * quietest duty that actually holds the ceiling, and finding that duty is the requirement:
 * every step down is the controller testing whether one point less would do. So it
 * oscillates by design, and the amplitude is set by the decay interval against the plant's
 * measured lag -- read {@link LinearConfig#downStepMs} before changing it.
 *
 * <b>Three intervals is not three branches.</b> The decision is always the same two-way
 * comparison against the ceiling; what the schedule changes is only how often that decision
 * is taken. That distinction is what keeps this a controller with no resting state rather
 * than a hold zone with a fast lane: at every temperature, including inside
 * {@link LinearConfig#nearC}, the duty is still stepping.
 *
 * Because of that, {@code degC == ceilingC} steps <i>up</i>: the comparison is
 * {@code >=}. With no resting state there is no such thing as "on the boundary and
 * therefore fine", and if one of the two branches has to own the exact value it should be
 * the one that cools.
 *
 * <h3>What it does not do</h3>
 * <ul>
 *   <li><b>No hysteresis.</b> There is no held temperature to apply it to. The deadband in
 *       {@link CurveConfig} exists to stop a mapped output chattering across a boundary;
 *       this controller has no mapping and no boundary, only a direction.</li>
 *   <li><b>No slew limit.</b> One duty point is already below the threshold of a change
 *       anyone hears, so there is nothing left for a slew limiter to hide.</li>
 *   <li><b>No catch-up.</b> {@link #resync} adopts the duty on the node and walks from
 *       there, so there is never a gap to close in a hurry.</li>
 *   <li><b>No reaction to the throttle signal.</b> Throttling cuts SoC power, which cools
 *       the die, which releases the boost, which lets it heat and throttle again. A
 *       discrete trigger on top of a continuous controller is how a limit cycle gets
 *       built, and this controller is already at the edge of one.</li>
 * </ul>
 *
 * Pure Java: no android.* imports, no I/O. Everything here is exercised by the host test.
 */
public final class FanLinear {

    /**
     * Commanded duty before the guard. Negative until a measurement or a {@link #resync}
     * has established one.
     */
    private int duty = -1;

    /**
     * Monotonic time of the last decision. 0 means the clock has not started, the same
     * convention {@link FanCurve} uses for its slew.
     */
    private long lastStepMs;

    /** Has the controller run out of authority in the direction it wants to go? */
    private boolean saturated;

    /** SoC die temperature driving the guard, after its own hysteresis. NaN if never seen. */
    private double heldSocC = Double.NaN;

    /** Duty points the guard actually added on the last tick; 0 when it did nothing. */
    private int guardBoost;

    /**
     * Adopt the duty the hardware is actually at, and start the step clock again.
     *
     * Called at start, on resume, and whenever a read-back shows something else has written
     * the node -- the same places {@link FanCurve#resync} is called from and for the same
     * reason: without it the first commanded value after a resume is a step from the
     * driver's own 55 to wherever the controller had got to.
     *
     * If the current duty is not known -- fan_ctrl unreadable -- the walk starts from
     * {@link FanIo#FAIL_SAFE_DUTY}. Not knowing where the fan is must never be a reason to
     * lower it.
     *
     * @param currentDuty the value read back from fan_ctrl, or -1 if unknown.
     */
    public void resync(int currentDuty) {
        duty = FanIo.valid(currentDuty) ? currentDuty : FanIo.FAIL_SAFE_DUTY;
        lastStepMs = 0L;
    }

    /** Forget everything. */
    public void reset() {
        duty = -1;
        lastStepMs = 0L;
        saturated = false;
        heldSocC = Double.NaN;
        guardBoost = 0;
    }

    /** The duty the integrator holds, before the guard adds anything; -1 before the first. */
    public int baseDuty() {
        return duty;
    }

    /**
     * Is the controller out of authority?
     *
     * True at {@code maxDuty} while still at or above the ceiling, or at {@code minDuty}
     * while still below it: the loop wants to move and cannot. Worth reporting because it
     * is not a fault -- at 30 C ambient the plant's minimum rise in Presentation is 19.4 C,
     * so any ceiling below about 49.5 C is physically unreachable there and pegging the fan
     * is what a correct controller does about it. Without this the log would show a duty
     * stuck at 83 and nothing to say whether that was the answer or the limit.
     */
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

    /** Advance the controller one tick, with no SoC guard. */
    public int step(LinearConfig cfg, double celsius, boolean engineOn, long nowMs) {
        return step(cfg, null, celsius, Double.NaN, engineOn, nowMs);
    }

    /**
     * Advance the controller one tick.
     *
     * Called at 1 Hz like everything else on the control loop, but it only <i>decides</i>
     * once its current interval has elapsed; in between it returns what it already holds.
     * Calling it more often cannot move the duty faster, which is the property the whole
     * stability argument rests on.
     *
     * <h3>The guard is applied to the output, not to the integrator</h3>
     * {@code duty = min(cap, integrator + gain * (pll - knee))}, unchanged from
     * {@link FanCurve}: additive, so it can only ever raise the fan, and a monitoring
     * sensor can never argue down the sensor the safety case rests on. It sits outside the
     * integrator's state so that arming or releasing it does not move the duty the walk has
     * arrived at -- and it is evaluated on every tick rather than every step, because a
     * backstop that waited five seconds would be five seconds late.
     *
     * @param cfg        the ceiling; must already have been {@link LinearConfig#sanitise}d.
     * @param guard      the curve's config, for the SoC guard's five numbers, or null for no
     *                   guard. Deliberately the curve's own: one machine, one SoC backstop,
     *                   one set of numbers to tune. A second copy on the linear line would
     *                   be a second thing to keep in step.
     * @param celsius    the measured LED temperature. Must be plausible; the caller fails
     *                   safe on a bad read rather than passing NaN in here.
     * @param socCelsius SoC die temperature (thermal_zone0 / pll), or NaN if unreadable.
     * @param engineOn   false when led_status reads 0, i.e. the light engine is off.
     * @param nowMs      monotonic milliseconds.
     * @return a duty in 1..100 that is safe to write.
     */
    public int step(LinearConfig cfg, CurveConfig guard, double celsius, double socCelsius,
            boolean engineOn, long nowMs) {
        if (cfg == null || Double.isNaN(celsius) || Double.isInfinite(celsius)) {
            // Should be unreachable: the caller gates on Thermistor.plausible. Fail high,
            // and touch nothing -- neither the duty nor the step clock. A tick with no
            // usable measurement is not a decision, so it must not consume one, and the
            // duty left behind has to stay the last one that was actually justified. The
            // caller's fail-safe latch is what writes 83 for as long as the reads are bad,
            // and its recovery path resyncs, so nothing here resumes from a stale step.
            return FanIo.FAIL_SAFE_DUTY;
        }
        if (duty < 0) {
            // Nothing has told us where the fan is. Same answer as resync(-1).
            duty = FanIo.FAIL_SAFE_DUTY;
        }
        // Re-imposed here rather than only where the duty is set, so that tightening
        // minDuty or maxDuty in the settings takes effect on the next tick.
        if (duty < cfg.minDuty) {
            duty = cfg.minDuty;
        }
        if (duty > cfg.maxDuty) {
            duty = cfg.maxDuty;
        }

        // The guard's own deadband, with the same asymmetry as the curve's: a rising die is
        // acted on at once, a falling one only once it has fallen clear of the band.
        if (!Double.isNaN(socCelsius) && !Double.isInfinite(socCelsius)) {
            double hyst = guard == null ? 0.0 : guard.socGuardHystC;
            if (Double.isNaN(heldSocC) || socCelsius >= heldSocC) {
                heldSocC = socCelsius;
            } else if (socCelsius <= heldSocC - hyst) {
                heldSocC = socCelsius;
            }
        } else {
            // Forget rather than hold: a stale value could keep the fan up indefinitely on
            // a sensor that has stopped reporting.
            heldSocC = Double.NaN;
        }

        if (!engineOn) {
            // Idle, as the curve does. The integrator keeps its duty: with Android up and
            // the light engine off, walking back up from 10 at one point a step would take
            // minutes to re-cool a machine that came back on in a second.
            //
            // The clock restarts too, so the first decision after the engine returns comes
            // a full interval later. Those ticks were not holding a ceiling, so they were
            // not steps.
            lastStepMs = nowMs;
            saturated = false;
            guardBoost = 0;
            return clampWritable(cfg.idleDuty);
        }

        // Which clock is running depends on where the measurement is, and it is re-read
        // every tick rather than latched, so a temperature drifting up towards the ceiling
        // slows the descent before it arrives rather than after.
        boolean rising = celsius >= cfg.ceilingC;
        long interval;
        if (rising) {
            interval = cfg.upStepMs;
        } else if (celsius >= cfg.ceilingC - cfg.nearC) {
            interval = cfg.downStepMs;
        } else {
            interval = cfg.downFastMs;
        }

        if (lastStepMs == 0L) {
            // Look before moving. The controller has just adopted whatever the hardware was
            // at and has one measurement of it; a decision needs an interval.
            lastStepMs = nowMs;
        } else if (nowMs - lastStepMs >= interval) {
            // One step, however long the gap was. A suspend of an hour buys one duty point,
            // not seven hundred: the integrator is meant to walk, and a catch-up burst after
            // a resume would be exactly the audible jump it exists to avoid.
            lastStepMs = nowMs;
            if (rising) {
                saturated = duty >= cfg.maxDuty;
                duty = Math.min(cfg.maxDuty, duty + 1);
            } else {
                saturated = duty <= cfg.minDuty;
                duty = Math.max(cfg.minDuty, duty - 1);
            }
        }

        int out = duty;
        guardBoost = 0;
        if (guard != null) {
            int boost = guard.socGuardBoost(heldSocC);
            if (boost > 0) {
                int raised = out + boost;
                if (raised > guard.socGuardMaxDuty) {
                    raised = guard.socGuardMaxDuty;
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
