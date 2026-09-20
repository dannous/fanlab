package com.daleygames.fanlab;

import java.util.ArrayList;
import java.util.List;

/**
 * VERIFY: hold one duty, in one brightness mode, indefinitely, and record what a person
 * sees and hears while it is held.
 *
 * This is deliberately much simpler than {@link SweepEngine}, and the simplicity is the
 * point. AUTO answers "what temperature does this duty reach"; VERIFY answers "is that
 * actually acceptable", which is the question the target is phrased in:
 *
 * <blockquote>as quiet as possible, subject to: the image stays sharp over a long run in
 * the warmest room you actually use, and the projector never shuts down.</blockquote>
 *
 * Philips' own stated reason for raising the Presentation fan speed was opto-mechanical -
 * the metal in the light engine expanding by a few millimetres and softening the image -
 * and that failure mode is <b>observable</b> and <b>reversible</b>. So the way a candidate
 * floor gets confirmed is not another number: it is running at that duty, in Presentation,
 * in the warmest room, for forty-five minutes, and looking at a fine test pattern a few
 * times. Every look is logged with the time and the temperature at that instant.
 *
 * <h3>The second phase: does the fan then sit still?</h3>
 * A held duty answers "is this acceptable" and cannot answer "will you hear it move",
 * because a pinned duty has nothing to move. Hunting is a CLOSED-loop property: it exists
 * only when the curve is choosing the duty and the plant is answering back. So VERIFY has
 * a second phase, started by {@link #beginSteady}, which hands the fan to the real
 * {@link FanCurve} against the real {@link CurveConfig} and counts what it does.
 *
 * This exists because of a specific failure. On 2026-09-08 a redrawn Presentation row
 * rested 3.7 duty points quieter than the shipped one, cleared the noise ceiling, cleared
 * the 60 C trip by the same margin, and scored 84 of 84 steady in {@code CurveSim}. On the
 * hardware it moved nine times in twelve minutes where the shipped row moved zero. Nothing
 * available on the device could have caught that, and a VERIFY run would have called the
 * redraw better, because at a pinned duty there is nothing to see. This phase is that
 * measurement, on the device, and it is {@code tools/watch.sh} in the app.
 *
 * <b>It counts reversals, not just changes.</b> A curve settling into a new operating point
 * changes duty repeatedly and every change points the same way; a curve hunting changes
 * direction. Counting changes alone rates those two identically, which is what
 * {@code watch.sh} does and why its output needs a human to read the list. The direction
 * count separates them, so {@link #verdict()} can say "settling" and "moving" rather than
 * one number for both.
 *
 * Same hard limits as the sweep: ceiling 58 C, three consecutive unusable readings stop it
 * at {@link FanIo#FAIL_SAFE_DUTY}, and it never touches the stock controller's property.
 * The duty floor is NOT applied in the steady phase: the curve's own floor is the thing
 * under test, and clamping it would measure something else. That is safe because the phase
 * is closed-loop -- the curve is watching the temperature, exactly as it does in ordinary
 * CURVE mode -- and the 58 C ceiling here is tighter than ordinary operation's.
 *
 * Pure Java.
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

    /**
     * What the fan did while the curve was driving it.
     *
     * Every figure is recorded twice: over the whole phase, and over the JUDGED tail, which
     * is the second half. The distinction matters because the phase begins with the fan
     * moving from the pinned hold duty to wherever the curve wants it, and that arrival is
     * not hunting. Judging the tail is the same discipline CurveSim uses when it looks at
     * the last fifty minutes of a run.
     */
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

        /**
         * How fast the light engine was still moving through the judged tail, C per hour.
         *
         * This is the number that decides whether the rest of the block means anything. A
         * machine that is still warming ratchets its duty one way, never turns round, and
         * so scores zero reversals -- which looks exactly like a curve that is behaving
         * itself. Drift has to be excluded before an absence of reversals can be read as
         * an absence of hunting.
         */
        public double trendCPerHour() {
            if (judgedFirstSec < 0 || judgedLastSec <= judgedFirstSec
                    || Double.isNaN(judgedFirstC) || Double.isNaN(judgedLastC)) {
                return 0.0;
            }
            double hours = (judgedLastSec - judgedFirstSec) / 3600.0;
            return (judgedLastC - judgedFirstC) / hours;
        }
    }

    /**
     * Drift in the judged tail, C per hour, above which no opinion is offered.
     *
     * fold_plant.py grades a hold CLEAN below 1 C/h and CONTAMINATED at 4. This sits
     * between, because the question here is easier than estimating an asymptote: it only
     * has to be still enough that a fan which would hunt has had the chance to.
     */
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

    /**
     * @param duty     the candidate floor to hold. Clamped into
     *                 {@link SweepPlan#DUTY_FLOOR}..{@link FanIo#FAIL_SAFE_DUTY}.
     * @param rgblevel the brightness mode to hold, clamped into 1..4.
     */
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

    // ------------------------------------------------------------------ accessors

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

    /**
     * True once the curve is driving.
     *
     * FanService uses this to decide whether the LED drive override may stay applied. It
     * may: the override's safety case is that light output is only raised while this app is
     * the thing cooling the machine, and in this phase it is -- the real curve is closing
     * the loop on the real thermistor. In the hold phase it is not, because a pinned duty
     * is not coupled to temperature, and that is exactly why the override is dropped there.
     * Verifying a Bright preset at the factory drive would measure the wrong machine.
     */
    public boolean closedLoop() {
        return phase == PHASE_STEADY && !finished;
    }

    /** The statistics, or null if the steady phase never started. */
    public Steady steady() {
        return st;
    }

    /**
     * What the numbers say, in one word.
     *
     * <pre>
     *   too_short   fewer than 30 judged samples; no opinion
     *   unsettled   the light engine was still drifting, so the run cannot show a hunt
     *   steady      settled, and the fan did not move at all. The target.
     *   settling    settled temperature, duty still arriving, and it never turned round
     *   moving      it turned round, but inside the accepted bound of two duty points
     *   hunting     outside the bound: three or more points, or more than one per tick
     * </pre>
     *
     * <b>unsettled comes first, and it is the one that stops this being a rubber stamp.</b>
     * Reversals are the hunting signature, and a machine that is still warming or cooling
     * ratchets its duty one way and reverses nothing -- so a drifting run scores exactly
     * like a well-behaved one. Reading that as "no hunting" is the mistake this ordering
     * exists to prevent. The right conditioning is to enter the phase NEAR the operating
     * point, which is what handing over from the held duty does; blasting the fan first
     * makes it worse, not better, because it enlarges the displacement the phase then has
     * to sit through.
     *
     * "moving" is deliberately not called a pass either. Two duty points single-stepping is
     * the accepted state elsewhere in this project and the host test is bounded there, but
     * the owner's spec is quiet <i>steadily</i>, and a fan that turns round every couple of
     * minutes is the thing he can hear. The word is there so a person reads the numbers.
     */
    public String verdict() {
        if (st == null || st.judgedSamples < 30) {
            return "too_short";
        }
        // A reversal is proof of a hunt whenever it happens, drift or no drift, so it is
        // never suppressed. Only the QUIET answers need the machine to have been still.
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

    /**
     * Hand the fan to {@code cfg} and start counting.
     *
     * @param cfg        the curve to put under test -- the one the user will actually run.
     * @param seconds    how long to watch. Clamped to 60..3600.
     * @param startDuty  what the fan is doing now, so the curve's slew limiter picks up
     *                   from there rather than stepping.
     */
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

    // ------------------------------------------------------------------ the tick

    /**
     * Advance one second.
     *
     * @return the duty to command. Always safe to write; {@link FanIo#FAIL_SAFE_DUTY} once
     *         the session has ended for any reason.
     */
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

    /** One second of the closed-loop phase: ask the real curve, then record what it said. */
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

    /** Record what the person in the room just reported. */
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
