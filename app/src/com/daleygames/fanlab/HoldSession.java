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
 * Same hard limits as the sweep: ceiling 58 C, duty floor 35, three consecutive unusable
 * readings stop it at {@link FanIo#FAIL_SAFE_DUTY}, and it never touches the stock
 * controller's property.
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
        return duty;
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
