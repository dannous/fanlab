package com.daleygames.fanlab;

/** The controller: hysteresis on the input, the curve, then a slew limit. */
public final class FanCurve {

    /** Temperature currently driving the curve, after hysteresis. NaN until the first sample. */
    private double heldC = Double.NaN;

    /** SoC die temperature driving the guard, after its own hysteresis. NaN if never seen. */
    private double heldSocC = Double.NaN;

    private int guardBoost;

    /** Commanded duty as an exact value, before rounding. NaN until the first sample. */
    private double output = Double.NaN;

    /** Timestamp of the previous step, for the slew rate. 0 means "no previous step". */
    private long lastMs = 0L;

    /** Set when the fan was handed over at a duty the curve did not choose; the output then converges at {@link #CATCHUP_PER_SEC}. */
    private boolean catchingUp;

    private int lastProfile = -1;

    private boolean lastEngineOn = true;

    /** Longest gap the slew limiter will integrate over, seconds. Guards against a stalled loop. */
    private static final double MAX_DT_SEC = 5.0;

    /** Convergence rate, duty points per second, while catching up to the curve after being handed a duty someone else chose. */
    private static final double CATCHUP_PER_SEC = 1.5;

    /**
     * Adopt the duty the hardware is actually at. Call at start, on resume, and whenever a
     * read-back shows something else wrote the node; an unknown duty starts the ramp from
     * {@link FanIo#FAIL_SAFE_DUTY} rather than from wherever the curve wants to be.
     */
    public void resync(int currentDuty) {
        output = FanIo.valid(currentDuty) ? currentDuty : FanIo.FAIL_SAFE_DUTY;
        lastMs = 0L;
        catchingUp = true;
    }

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

    public double heldCelsius() {
        return heldC;
    }

    public double exactOutput() {
        return output;
    }

    /** Duty points the SoC guard added on the most recent step, after its ceiling; 0 when it changed nothing. */
    public int guardBoost() {
        return guardBoost;
    }

    public double heldSocCelsius() {
        return heldSocC;
    }

    public boolean isCatchingUp() {
        return catchingUp;
    }

    /** Advance the controller one step; {@code celsius} must already be plausible and {@code nowMs} is monotonic milliseconds. */
    public int step(CurveConfig cfg, int profile, double celsius, boolean engineOn, long nowMs) {
        return step(cfg, profile, celsius, Double.NaN, engineOn, nowMs);
    }

    /**
     * Advance one step with the SoC guard, which is a floor and strictly additive: it can raise
     * the duty and never lower it. {@code socCelsius} is thermal_zone0, or NaN if unreadable.
     */
    public int step(CurveConfig cfg, int profile, double celsius, double socCelsius,
            boolean engineOn, long nowMs) {
        if (cfg == null || Double.isNaN(celsius) || Double.isInfinite(celsius)) {
            // Should be unreachable: the caller gates on Thermistor.plausible. Fail high.
            return FanIo.FAIL_SAFE_DUTY;
        }

        if (Double.isNaN(heldC) || celsius >= heldC) {
            heldC = celsius;
        } else if (celsius <= heldC - cfg.hysteresisC) {
            heldC = celsius;
        }

        if (!Double.isNaN(socCelsius) && !Double.isInfinite(socCelsius)) {
            if (Double.isNaN(heldSocC) || socCelsius >= heldSocC) {
                heldSocC = socCelsius;
            } else if (socCelsius <= heldSocC - cfg.socGuardHystC) {
                heldSocC = socCelsius;
            }
        } else {
            heldSocC = Double.NaN;
        }

        int target = engineOn ? cfg.dutyAt(profile, heldC) : cfg.idleDuty;

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

        if (Double.isNaN(output)) {
            output = target;
        } else if (tierChanged && target > output) {
            output = target;
        } else if (lastMs != 0L) {
            double dt = (nowMs - lastMs) / 1000.0;
            if (!(dt > 0.0)) {
                dt = 0.0;
            }
            if (dt > MAX_DT_SEC) {
                dt = MAX_DT_SEC;
            }
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
     * The stock ladder, reproduced exactly. {@code tmp} is the truncated, already +0.5-biased
     * temperature the stock controller compares; -1 is its 53/54 dead zone, where it writes nothing.
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
