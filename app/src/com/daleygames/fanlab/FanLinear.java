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
 * <h3>The trend gate</h3>
 * The comparison above decides the <i>direction</i>. A second test decides whether to act
 * on it: <b>do not add fan while the temperature is already falling, and do not give fan
 * back while it is still rising.</b> Without it the walk keeps pushing until the absolute
 * reading crosses back, which on a plant with a 120 s lag means it sails past the answer --
 * watched on hardware on 2026-09-07 climbing eight duty points beyond its equilibrium while
 * the thermistor had been falling for half a minute.
 *
 * It delays action; it never caps it. Where the ceiling genuinely needs 83 % the walk still
 * arrives there, because a real climb keeps the slope positive and the gate stays open. See
 * {@link LinearConfig#trendWindowS} for the window and why it is 90 seconds.
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
 * <h3>Where it starts, and why that is the curve's answer</h3>
     * The walk is <b>seeded from {@link FanCurve}'s curve</b> on entry and after every
     * resync, rather than from whatever duty happens to be on the node. That is one line of
     * behaviour with a large effect, and the reason is measured: over thirty-six hours of
     * field log the curve predicted the settled operating point to within <b>0.39 duty
     * points</b>. Starting the integrator there starts it essentially at the answer.
     *
     * Without it the walk begins wherever the hardware was -- the driver's own 55 % after a
     * resume, or the stock ladder's floor -- and has to travel. Measured on hardware, the
     * accepted 60 s decay took about seven minutes to come down from 48 to 42 and had not
     * finished; that descent is the single worst thing about this controller and seeding
     * deletes it, because there is no longer a gap to close.
     *
     * <b>It does not make this a curve.</b> The seed is a starting point, not a target: the
     * integrator is free to walk away from it and will, whenever the curve's plant model is
     * wrong for this room or this unit. That is the whole point of the mode. What seeding
     * changes is that it starts from the best available guess rather than from an arbitrary
     * one, so the first minutes are quiet instead of being a long slide.
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
     * Is the walk waiting to be seeded from the curve?
     *
     * Set by {@link #resync}, cleared by the first tick that has both a usable temperature
     * and a curve to read. See the seeding note on {@link #step}.
     */
    private boolean seedPending;

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
     * Recent temperature history, for the trend gate. A ring, sized for the largest window
     * {@link LinearConfig#MAX_TREND_S} allows plus the current sample.
     *
     * Timestamps are kept alongside the readings rather than assuming one sample a second.
     * The loop is 1 Hz, but a suspend can leave a gap, and a slope fitted across a gap as
     * though it were contiguous would be wrong by however long the machine was away.
     */
    private final double[] histC = new double[LinearConfig.MAX_TREND_S + 1];
    private final long[] histMs = new long[LinearConfig.MAX_TREND_S + 1];
    private int histCount;
    private int histHead;

    /** Slope of the last accepted fit, degrees per second. NaN when there was no fit. */
    private double lastTrend = Double.NaN;

    /** Did the trend gate hold a step back on the most recent decision? */
    private boolean trendHeld;

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
        // The node's value is only a placeholder until the next tick can read the curve.
        // Kept rather than discarded because a tick with no usable temperature must still
        // have somewhere safe to command from, and "where the fan actually is" is that.
        seedPending = true;
    }

    /** Forget everything. */
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

    /**
     * Slope of the temperature over the trend window, degrees per second.
     *
     * NaN until the window has filled. Exposed so the log can show why a step did not
     * happen -- without it, a walk that has gone quiet looks identical to a walk that has
     * stalled.
     */
    public double trendPerSec() {
        return lastTrend;
    }

    /** Did the trend gate hold back the most recent decision? */
    public boolean trendHeld() {
        return trendHeld;
    }

    /**
     * Least-squares slope over the trend window, degrees per second, or NaN.
     *
     * Least squares rather than the difference of the two end samples: the end-point
     * difference has a standard error of sd*sqrt(2)/window, roughly three times worse here,
     * and a gate built on it fires on noise about as often as on signal. That version was
     * built during the search, appeared to work because randomly skipping steps also
     * suppresses windup, and had to be thrown away. Do not go back to it.
     */
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
        // Demand most of the window, so a gap does not produce a confident slope from a
        // handful of samples clustered at one end.
        if (n < windowS / 2) {
            return Double.NaN;
        }
        double den = n * sxx - sx * sx;
        if (den == 0.0) {
            return Double.NaN;
        }
        return (n * sxy - sx * sy) / den;
    }

    /**
     * Three standard errors on that slope. A gate at this level cannot be tripped by
     * sensor noise, which is the whole reason the window is as long as it is.
     */
    private static double trendNoise(int windowS) {
        double t = windowS;
        return 3.0 * LinearConfig.SENSOR_NOISE_SD_C * Math.sqrt(12.0 / ((t + 1.0) * t * t));
    }

    /**
     * Advance the controller one tick, with no SoC guard and no curve to seed from.
     *
     * The walk then starts from whatever {@link #resync} adopted, which is the pre-seeding
     * behaviour and is what the unit tests exercise when they want the integrator on its
     * own.
     */
    public int step(LinearConfig cfg, double celsius, boolean engineOn, long nowMs) {
        return step(cfg, null, CurveConfig.PROFILE_HIGH, celsius, Double.NaN, engineOn, nowMs);
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
     * @param curve      the curve's config, doing two jobs, or null for neither. It carries
     *                   the SoC guard's five numbers -- deliberately the curve's own, since
     *                   one machine wants one SoC backstop and one set of numbers to tune --
     *                   and it is what the walk is seeded from after a resync.
     * @param profile    brightness profile, for reading the seed off the curve. Ignored once
     *                   the walk is running: the integrator is closed on temperature and does
     *                   not care which brightness mode produced it.
     * @param celsius    the measured LED temperature. Must be plausible; the caller fails
     *                   safe on a bad read rather than passing NaN in here.
     * @param socCelsius SoC die temperature (thermal_zone0 / pll), or NaN if unreadable.
     * @param engineOn   false when led_status reads 0, i.e. the light engine is off.
     * @param nowMs      monotonic milliseconds.
     * @return a duty in 1..100 that is safe to write.
     */
    public int step(LinearConfig cfg, CurveConfig curve, int profile, double celsius,
            double socCelsius, boolean engineOn, long nowMs) {
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
            double hyst = curve == null ? 0.0 : curve.socGuardHystC;
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

        // Record the reading before anything decides on it, so the window is the last
        // windowS seconds of what the sensor actually said.
        histC[histHead] = celsius;
        histMs[histHead] = nowMs;
        histHead = (histHead + 1) % histC.length;
        if (histCount < histC.length) {
            histCount++;
        }

        // Seed from the curve, once, on the first tick after a resync that can actually
        // read one. Done here rather than in resync() because resync has no temperature --
        // it is called from paths that run before the first sample.
        //
        // The step clock restarts with it: the controller has just been handed a new
        // starting point and has one measurement of it, so it looks before it moves, the
        // same way it does on a cold start.
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

        trendHeld = false;
        if (lastStepMs == 0L) {
            // Look before moving. The controller has just adopted whatever the hardware was
            // at and has one measurement of it; a decision needs an interval.
            lastStepMs = nowMs;
        } else if (nowMs - lastStepMs >= interval) {
            // One step, however long the gap was. A suspend of an hour buys one duty point,
            // not seven hundred: the integrator is meant to walk, and a catch-up burst after
            // a resume would be exactly the audible jump it exists to avoid.
            lastStepMs = nowMs;

            // The trend gate. The ceiling has already decided the direction; this decides
            // whether to act on it now. Adding fan while the light engine is already coming
            // down is how the walk overshoots -- measured on hardware, it climbed eight duty
            // points past its equilibrium doing exactly that. Symmetrically, giving fan back
            // while the temperature is still climbing would be undoing work that is working.
            //
            // Note what this is NOT: it never caps the duty. At an ambient where the ceiling
            // needs the full 83 % the walk still gets there, because a genuine climb keeps
            // the slope positive and the gate stays open. It only ever says "wait and see".
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
