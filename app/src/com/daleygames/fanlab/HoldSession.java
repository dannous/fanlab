package com.daleygames.fanlab;

import java.util.ArrayList;
import java.util.List;

/**
 * VERIFY: hold one duty in one brightness mode while a person reports what they see and hear,
 * then hand the fan to the real curve and count whether it sits still.
 */
public final class HoldSession {

    /** One thing the person in the room reported. */
    public static final class Check {
        public long wallMs;
        public long elapsedSec;
        public double degC = Double.NaN;
        public int duty;
        public int rgblevel;
        /** "sharp" / "soft" for the pattern, "audible" / "quiet" for the fan. */
        public String verdict = "";
        /** "pattern" or "fan". */
        public String about = "";
        /** Which pattern was on screen when the verdict was given. */
        public String pattern = "";
    }

    /** Verdict tokens, so the report and the UI cannot drift apart. */
    public static final String VERDICT_SHARP = "sharp";
    public static final String VERDICT_SOFT = "soft";
    public static final String VERDICT_AUDIBLE = "audible";
    public static final String VERDICT_QUIET = "quiet";

    public static final String ABOUT_PATTERN = "pattern";
    public static final String ABOUT_FAN = "fan";

    /** Holding one duty and asking a person to look at the screen. */
    public static final int PHASE_HOLD = 0;
    /** The curve is driving; counting whether the fan sits still. */
    public static final int PHASE_STEADY = 1;

    /** Default length of the steady phase, seconds. Twelve minutes, as watch.sh uses. */
    public static final int STEADY_SECONDS = 720;

    /** What the fan did while the curve was driving; every figure over the whole phase, and over the judged second half. */
    public static final class Steady {
        /** Planned length, seconds. */
        public int seconds;
        /** Elapsed second from which the judged figures are counted. */
        public int judgedFromSec;
        public int samples;

        public int changes;
        /** Changes that reversed the previous change's direction. The hunting signature. */
        public int reversals;
        public int loDuty = -1;
        public int hiDuty = -1;
        /** Largest single-tick duty change. The slew limiter should hold this at 1. */
        public int maxTick;
        public double minC = Double.NaN;
        public double maxC = Double.NaN;

        /** First and last temperature of the judged tail, and how far apart in time. */
        public double judgedFirstC = Double.NaN;
        public double judgedLastC = Double.NaN;
        public int judgedFirstSec = -1;
        public int judgedLastSec = -1;

        public int judgedSamples;
        public int judgedChanges;
        public int judgedReversals;
        public int judgedLo = -1;
        public int judgedHi = -1;
        public int judgedMaxTick;

        /** Duty span over the judged tail, or 0 if nothing was judged. */
        public int judgedSpan() {
            return judgedLo < 0 ? 0 : judgedHi - judgedLo;
        }

        /** How fast the light engine was still moving through the judged tail, C per hour; drift has to be excluded before an absence of reversals means anything. */
        public double trendCPerHour() {
            if (judgedFirstSec < 0 || judgedLastSec <= judgedFirstSec
                    || Double.isNaN(judgedFirstC) || Double.isNaN(judgedLastC)) {
                return 0.0;
            }
            double hours = (judgedLastSec - judgedFirstSec) / 3600.0;
            return (judgedLastC - judgedFirstC) / hours;
        }
    }

    /** Drift in the judged tail, C per hour, above which no opinion is offered. */
    public static final double SETTLED_C_PER_HOUR = 2.0;

    private final int duty;
    private final int rgblevel;
    private final long startMs;
    private final long startWallMs;

    private final List<Check> checks = new ArrayList<Check>();
    private final List<String> events = new ArrayList<String>();

    private double lastC = Double.NaN;
    private double minC = Double.NaN;
    private double maxC = Double.NaN;
    private int badReads;
    private boolean finished;
    private String endReason = "";
    private String pattern = "white";
    private int foreignWrites;

    private int phase = PHASE_HOLD;
    private CurveConfig steadyCfg;
    private FanCurve steadyCurve;
    private int steadyProfile;
    private long steadyStartMs;
    private Steady st;
    private int prevDuty = -1;
    private int prevDir;

    /** {@code duty} is clamped into {@link SweepPlan#DUTY_FLOOR}..{@link FanIo#FAIL_SAFE_DUTY}, {@code rgblevel} into 1..4. */
    public HoldSession(int duty, int rgblevel, long nowMs, long wallMs) {
        int d = duty;
        if (d < SweepPlan.DUTY_FLOOR) {
            d = SweepPlan.DUTY_FLOOR;
        }
        if (d > FanIo.FAIL_SAFE_DUTY) {
            d = FanIo.FAIL_SAFE_DUTY;
        }
        this.duty = FanIo.clampForUi(d);
        int r = rgblevel;
        if (r < SweepPlan.RGBLEVEL_MIN) {
            r = SweepPlan.RGBLEVEL_MIN;
        }
        if (r > SweepPlan.RGBLEVEL_MAX) {
            r = SweepPlan.RGBLEVEL_MAX;
        }
        this.rgblevel = r;
        this.startMs = nowMs;
        this.startWallMs = wallMs;
        events.add("verify_start duty=" + this.duty + " mode=" + SweepPlan.modeName(this.rgblevel));
    }

    public int duty() {
        return duty;
    }

    public int rgblevel() {
        return rgblevel;
    }

    public String modeName() {
        return SweepPlan.modeName(rgblevel);
    }

    public boolean finished() {
        return finished;
    }

    public String endReason() {
        return endReason;
    }

    public List<Check> checks() {
        return checks;
    }

    public List<String> events() {
        return events;
    }

    public long startedWallMs() {
        return startWallMs;
    }

    public long elapsedSec(long nowMs) {
        return (nowMs - startMs) / 1000L;
    }

    public double lastC() {
        return lastC;
    }

    public double minC() {
        return minC;
    }

    public double maxC() {
        return maxC;
    }

    public String pattern() {
        return pattern;
    }

    public void setPattern(String p) {
        pattern = p == null ? "white" : p;
    }

    public int foreignWrites() {
        return foreignWrites;
    }

    public int phase() {
        return phase;
    }

    /** True once the curve is driving. FanService allows the LED drive override to stay applied only in this phase. */
    public boolean closedLoop() {
        return phase == PHASE_STEADY && !finished;
    }

    /** The statistics, or null if the steady phase never started. */
    public Steady steady() {
        return st;
    }

    /** What the numbers say, in one word: too_short, unsettled, steady, settling, moving or hunting. */
    public String verdict() {
        if (st == null || st.judgedSamples < 30) {
            return "too_short";
        }
        // A reversal proves a hunt regardless of drift; only the quiet answers need stillness.
        if (st.judgedReversals == 0
                && Math.abs(st.trendCPerHour()) >= SETTLED_C_PER_HOUR) {
            return "unsettled";
        }
        if (st.judgedChanges == 0) {
            return "steady";
        }
        if (st.judgedReversals == 0) {
            return "settling";
        }
        return st.judgedSpan() <= 2 && st.judgedMaxTick <= 1 ? "moving" : "hunting";
    }

    /** Hand the fan to {@code cfg} and start counting; {@code seconds} is clamped to 60..3600. */
    public void beginSteady(CurveConfig cfg, int seconds, int startDuty, long nowMs) {
        if (finished || phase == PHASE_STEADY || cfg == null) {
            return;
        }
        int secs = seconds < 60 ? 60 : (seconds > 3600 ? 3600 : seconds);
        phase = PHASE_STEADY;
        steadyCfg = cfg;
        steadyProfile = CurveConfig.profileForLevel(rgblevel);
        steadyCurve = new FanCurve();
        steadyCurve.resync(startDuty);
        steadyStartMs = nowMs;
        st = new Steady();
        st.seconds = secs;
        st.judgedFromSec = secs / 2;
        prevDuty = -1;
        prevDir = 0;
        events.add("steady_start " + secs + "s, curve driving, profile "
                + CurveConfig.PROFILE_NAMES[steadyProfile] + ", from duty " + startDuty);
    }

    /** Advance one second; the returned duty is always safe to write, and is {@link FanIo#FAIL_SAFE_DUTY} once the session has ended. */
    public int tick(long nowMs, double degC) {
        if (finished) {
            return FanIo.FAIL_SAFE_DUTY;
        }
        if (Double.isNaN(degC) || Double.isInfinite(degC) || !Thermistor.plausible(degC)) {
            badReads++;
            if (badReads >= SweepPlan.BAD_READS_ABORT) {
                stop("bad_temperature");
                return FanIo.FAIL_SAFE_DUTY;
            }
            return duty;
        }
        badReads = 0;
        lastC = degC;
        if (Double.isNaN(minC) || degC < minC) {
            minC = degC;
        }
        if (Double.isNaN(maxC) || degC > maxC) {
            maxC = degC;
        }
        if (degC >= SweepPlan.CEILING_C) {
            events.add("ceiling " + Sample.fmt1(degC) + "C - holding "
                    + FanIo.FAIL_SAFE_DUTY + " and stopping");
            stop("ceiling");
            return FanIo.FAIL_SAFE_DUTY;
        }
        if (phase == PHASE_STEADY) {
            return steadyTick(nowMs, degC);
        }
        return duty;
    }

    private int steadyTick(long nowMs, double degC) {
        int d = steadyCurve.step(steadyCfg, steadyProfile, degC, true, nowMs);
        long el = (nowMs - steadyStartMs) / 1000L;
        boolean judged = el >= st.judgedFromSec;

        st.samples++;
        if (st.loDuty < 0 || d < st.loDuty) {
            st.loDuty = d;
        }
        if (d > st.hiDuty) {
            st.hiDuty = d;
        }
        if (Double.isNaN(st.minC) || degC < st.minC) {
            st.minC = degC;
        }
        if (Double.isNaN(st.maxC) || degC > st.maxC) {
            st.maxC = degC;
        }
        if (judged) {
            st.judgedSamples++;
            if (st.judgedLo < 0 || d < st.judgedLo) {
                st.judgedLo = d;
            }
            if (d > st.judgedHi) {
                st.judgedHi = d;
            }
            if (st.judgedFirstSec < 0) {
                st.judgedFirstSec = (int) el;
                st.judgedFirstC = degC;
            }
            st.judgedLastSec = (int) el;
            st.judgedLastC = degC;
        }

        if (prevDuty >= 0 && d != prevDuty) {
            int tick = Math.abs(d - prevDuty);
            int dir = d > prevDuty ? 1 : -1;
            boolean reversed = prevDir != 0 && dir != prevDir;
            st.changes++;
            if (tick > st.maxTick) {
                st.maxTick = tick;
            }
            if (reversed) {
                st.reversals++;
            }
            if (judged) {
                st.judgedChanges++;
                if (tick > st.judgedMaxTick) {
                    st.judgedMaxTick = tick;
                }
                if (reversed) {
                    st.judgedReversals++;
                }
            }
            events.add("steady_change " + prevDuty + "->" + d + " at " + el + "s, "
                    + Sample.fmt1(degC) + "C" + (reversed ? " (reversed)" : ""));
            prevDir = dir;
        }
        prevDuty = d;

        if (el >= st.seconds) {
            events.add("steady_done " + verdict() + ": " + st.judgedChanges
                    + " changes and " + st.judgedReversals + " reversals in the judged half, duty "
                    + st.judgedLo + ".." + st.judgedHi + ", drift "
                    + Sample.fmt1(st.trendCPerHour()) + "C/h");
            stop("steady_complete");
        }
        return d;
    }

    /** End the session. The caller writes the fail-safe duty itself, immediately. */
    public void stop(String reason) {
        if (finished) {
            return;
        }
        finished = true;
        endReason = reason == null || reason.length() == 0 ? "user" : reason;
        events.add("verify_end:" + endReason);
    }

    public Check check(String about, String verdict, long nowMs, long wallMs) {
        Check c = new Check();
        c.wallMs = wallMs;
        c.elapsedSec = elapsedSec(nowMs);
        c.degC = lastC;
        c.duty = duty;
        c.rgblevel = rgblevel;
        c.about = about == null ? "" : about;
        c.verdict = verdict == null ? "" : verdict;
        c.pattern = pattern;
        checks.add(c);
        events.add(c.about + ":" + c.verdict + " at " + c.elapsedSec + "s, "
                + Sample.fmt1(c.degC) + "C, pattern " + c.pattern);
        return c;
    }

    public void noteForeignWrite(int foreignValue) {
        foreignWrites++;
        events.add("foreign_write " + foreignValue + " (we hold " + duty + ") at "
                + Sample.fmt1(lastC) + "C");
    }

    public void noteForeignMode(int found) {
        events.add("foreign_mode rgblevel=" + found + ", re-asserting " + rgblevel);
    }
}
