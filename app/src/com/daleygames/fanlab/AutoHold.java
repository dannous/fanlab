package com.daleygames.fanlab;

/**
 * AUTO: no curve. Steps the fan one point at a time to keep the LED thermistor just under a
 * target, so the fan is as slow as the room allows. Inside the band it does not move at all.
 */
public final class AutoHold {

    /** Stock drive target, degrees C: where the stock ladder itself commands maximum fan. */
    public static final double TARGET_C = 55.0;

    /** Target with the LED drive raised, degrees C. The owner's choice; 4 C under {@link LedDrive#DEFAULT_TRIP_C}. */
    public static final double BOOST_TARGET_C = 58.0;

    /** Width of the band under the target in which the fan is left alone, degrees C. Wider than one duty point is worth anywhere on the measured plant. */
    public static final double BAND_C = 1.5;

    /**
     * Lowest duty in any mode. The owner hears no difference between 20 and 25, so nothing
     * runs below 25. The written record only proves 30 against the stall watchdog.
     */
    public static final int MIN_DUTY = 25;

    /** The lowest floor the owner may set (see Prefs.fanFloor). Below 30 the stall watchdog is the only thing known to watch the fan. */
    public static final int ABS_MIN_DUTY = 20;

    /**
     * After a change of brightness mode, the higher of the old and new targets is used for this
     * long, ms. Each mode has its own target, and a switch down arrives with the LED still warm
     * from the brighter mode: on 15 Nov 2026 a click from Presentation through Night read 58.5 C
     * against Night's 55 and lifted the fan limit on the spot. The LED cools by itself within a
     * minute of the drive coming down; raising the fan for it is noise for nothing.
     */
    static final long SWITCH_GRACE_MS = 90000L;
    private long graceUntilMs;
    private double graceTargetC = Double.NaN;
    private double lastTargetC = Double.NaN;

    private boolean driveSet;
    private boolean driveIs;

    /** Say whether the LED drive is raised. With a target per brightness mode the target no longer tells. */
    public void setDrive(boolean raised) {
        driveSet = true;
        driveIs = raised;
    }

    /** The floor for a brightness mode, by {@code rgblevel}: more heat keeps more fan in reserve. Unknown levels get the highest. */
    public static int minFor(int rgblevel) {
        // 30 in every mode, the owner's choice (14 Nov 2026): it is the lowest duty the written
        // record proves against the stall watchdog, and they hear nothing below 35 anyway.
        return 30;
    }
    /** Below this duty the plant is unmeasured and steeper, so the hold band is widened by {@link #LOW_BAND_EXTRA_C}. */
    public static final int MEASURED_FLOOR = 30;

    public static final double LOW_BAND_EXTRA_C = 1.5;

    /** 83 is the stock controller's maximum, and the fail-safe. */
    public static final int MAX_DUTY = 83;

    /** Smoothing time constant on the input, seconds; sensor noise must not look like a trend. */
    private static final double SMOOTH_S = 20.0;

    /** Second, slower average; with {@link #SMOOTH_S} it gives the slope of the reading. */
    private static final double SLOW_S = 60.0;

    /** How far ahead the catch-up looks, seconds. Longer than the fast thermal time constant, because the chassis keeps warming after it. */
    private static final double LOOKAHEAD_S = 320.0;

    /**
     * The goal is the lowest whole fan speed whose steady LED temperature is at or under the
     * target: 58, not 57 with fan to spare. Inside the band nothing pushes it there, so it probes:
     * when calm, one point down; a few minutes later, a fit of the warming says where that point
     * will settle, and it goes back up a point before the LED gets there if it would end over.
     *
     * The fan only steps up once the reading is this far over the target, degrees C, so the LED
     * can sit right at it without noise nudging the fan.
     */
    static final double OVER_C = 0.05;

    /** Calm before a probe: the fan unchanged this long, ms, and the reading steady. */
    static final long PROBE_CALM_MS = 240000L;

    /** Pace of a probe's ramp, down or back up, ms a point. */
    static final long PROBE_RAMP_MS = 5000L;

    /** Most points one probe takes at once, and the most before a point has been measured. */
    static final int PROBE_MAX_POINTS = 8;
    static final int PROBE_FIRST_MAX = 3;

    /** How far ahead the creep left from earlier steps is projected when judging a probe, seconds. */
    static final double CREEP_LOOK_S = 600.0;

    /** A probe whose points showed less warming than this each, degrees C, is not being read: the sensor is not following the fan. */
    static final double PROBE_MIN_EFFECT_C = 0.03;

    /**
     * No probe this soon after the fan had to step up, ms. While the chassis is still warming
     * (from cold it takes hours) the fan is chasing it, and a probe in a calm moment between
     * those steps only runs warm: in simulation, three in a row from a cold start.
     */
    static final long PROBE_AFTER_UP_MS = 600000L;
    private long lastUpMs;

    /** A probe is only tried if one point less is expected to land this far under the target, degrees C. */
    static final double PROBE_MARGIN_C = 0.05;

    /** How long after a probe its warming is fitted, ms; past two of the 230 s time constants. */
    static final long VERIFY_AFTER_MS = 300000L;

    /**
     * Share of a point's effect still to come at {@link #VERIFY_AFTER_MS} from the chassis, which
     * warms over 15 to 50 minutes and which an eight-minute fit cannot see. Added to the fit.
     */
    static final double SLOW_SHARE = 0.5;

    /** Under the target by this much, calm, for {@link #RECHECK_AFTER_MS}: the room has cooled, probe again. */
    static final double RECHECK_UNDER_C = 0.5;
    static final long RECHECK_AFTER_MS = 1200000L;

    /** Roughly what one point is worth in LED degrees: about 0.2 near 45, about twice that near 30. */
    static double pointWorthC(int d) {
        return d <= MEASURED_FLOOR + 1 ? 0.4 : 0.2;
    }

    /** The worth to plan with: what the last probe in this mode measured, if any (never below the rough figure's half), else the rough figure. */
    private double worthFor(int d) {
        double rough = pointWorthC(d);
        return Double.isNaN(measuredWorthC) ? rough : Math.max(rough * 0.5, measuredWorthC);
    }

    private long probeAtMs;
    private int probeDuty;
    private int probeFrom;
    private int probeTo;
    /** Ramping back up to this after a probe that would end over; 0 when not. */
    private int upToDuty;
    /** A probe just held with room for more: take the next straight away, from {@link #chainBaseC}. */
    private boolean chainReady;
    private double chainBaseC;
    /** The smoothed reading and its slope when the probe began: the baseline and the creep still under way. */
    private double probeStartC;
    private double probeStartSlope;
    /** What a point measured as worth, degrees C, from the last probe in this mode; NaN until one has been fitted. */
    private double measuredWorthC = Double.NaN;
    /** The measured worth of the point that failed, kept to judge when the room has cooled enough to try it again. */
    private double failWorthC = Double.NaN;
    /** The LED, settled one above the bottom, when the bottom was found; a fall from it is the room cooling. */
    private double recheckRefC = Double.NaN;
    /** Lowest duty a probe has found safe for the current mode is one above this; 0 when not known. */
    private int noProbeAtOrBelow;
    private long lowSinceMs;
    private final double[] fitT = new double[256];
    private final double[] fitY = new double[256];
    private int fitN;
    private long fitLastMs;

    /**
     * After a brightness change, how long the speed that mode last settled at guides the fan, ms.
     * Only a guide: the fan goes to it and stops there; after this the ordinary rules take over
     * and correct a memory the room has outgrown.
     */
    static final long GUIDE_MS = 300000L;

    /** Pace toward a learned speed: up a point every 5 s, down a point every 15 s. Ramps, never jumps. */
    static final long GUIDE_UP_MS = 5000L;
    static final long GUIDE_DOWN_MS = 15000L;

    /**
     * While guided, the fan goes above the learned speed only this far over the target. The LED
     * jumps on a switch up faster than any fan can act (54 to 58 C in 3 s on 14 Nov 2026);
     * answering that jump with more fan is what took Presentation from its learned 44 to 56.
     */
    static final double GUIDE_OVER_C = 2.5;

    /** A new brightness mode must hold this long before it counts, ms; modes clicked past are ignored. */
    static final long FLICK_MS = 5000L;

    /** Duty unchanged this long, unguided and inside the band (or resting on the floor), is what the mode needs. */
    static final long SETTLED_AFTER_MS = 600000L;

    /**
     * A learned speed guides only while it is younger than this, ms. The room moves a learned
     * speed by several points over an evening, and inside the band a stale one is not corrected:
     * in simulation a memory 6 points high either came back down audibly or stayed 3 to 6 points
     * loud. Three hours covers a film in one mode and the switch after it; within that the room
     * rarely moves that far, and a steady mode relearns every ten minutes.
     */
    static final long MEMORY_MS = 3L * 3600L * 1000L;

    /** Learned duty by [drive raised ? 1 : 0][rgblevel 1..4]; 0 where nothing is known. */
    private final int[][] settled = new int[2][5];
    private final long[][] settledAtMs = new long[2][5];
    private int level = -1;
    private int pendingLevel = -1;
    private long pendingSinceMs;
    private int guideDuty;
    private long guideUntilMs;
    private long changedMs;
    private String event;

    private int floor = MIN_DUTY;
    private double duty = Double.NaN;
    private double smoothC = Double.NaN;
    private double slowC = Double.NaN;

    /** Set when handed a duty this controller did not choose; cleared once the fan has come down to where the temperature is heading. */
    private boolean catchingUp;
    private long lastMs;
    private long steppedMs;

    public static double targetFor(boolean driveOn) {
        return driveOn ? BOOST_TARGET_C : TARGET_C;
    }

    /** Adopt the duty actually on the fan. An unknown duty starts from the fail-safe, never lower. */
    public void resync(int currentDuty) {
        duty = FanIo.valid(currentDuty) ? clamp(currentDuty) : FanIo.FAIL_SAFE_DUTY;
        steppedMs = 0L;
        catchingUp = true;
    }

    public void reset() {
        duty = Double.NaN;
        smoothC = Double.NaN;
        slowC = Double.NaN;
        lastMs = 0L;
        steppedMs = 0L;
        catchingUp = false;
        // What each mode settled at is kept: it is a fact about the projector, not about this run.
        level = -1;
        pendingLevel = -1;
        guideDuty = 0;
        guideUntilMs = 0L;
        changedMs = 0L;
        probeAtMs = 0L;
        noProbeAtOrBelow = 0;
        lowSinceMs = 0L;
        measuredWorthC = Double.NaN;
        failWorthC = Double.NaN;
        recheckRefC = Double.NaN;
        chainReady = false;
        upToDuty = 0;
    }

    /**
     * Forget every learned speed and what probing found. Called when the LED drive levels change:
     * a speed learned at one drive is wrong at another, and a guide to it would be a guide to the
     * wrong place.
     */
    public void forgetLearned() {
        for (int i = 0; i < 2; i++) {
            for (int l = 0; l < 5; l++) {
                settled[i][l] = 0;
                settledAtMs[i][l] = 0L;
                rel[i][l] = Double.NaN;
                worthBy[i][l] = Double.NaN;
            }
        }
        learnedDirty = true;
        guideDuty = 0;
        guideUntilMs = 0L;
        probeAtMs = 0L;
        noProbeAtOrBelow = 0;
        measuredWorthC = Double.NaN;
        failWorthC = Double.NaN;
        recheckRefC = Double.NaN;
        chainReady = false;
        upToDuty = 0;
    }

    /**
     * What Smart keeps for good, by [drive raised ? 1 : 0][rgblevel 1..4]; NaN where unknown.
     *
     * rel: only the differences mean anything. Two modes that both settled within MEMORY_MS of
     * each other were in the same room, so the difference of their speeds is a property of the
     * projector and the brightness levels, not of the day: Presentation needed 12 points more
     * than Normal on 14 Nov 2026. A speed itself goes stale with the room (about 2 points a
     * degree); the gap does not.
     *
     * worthBy: what a fan point is worth in LED degrees in that mode, as probes measured it.
     */
    private final double[][] rel = new double[2][5];
    private final double[][] worthBy = new double[2][5];
    {
        for (int i = 0; i < 2; i++) {
            for (int l = 0; l < 5; l++) {
                rel[i][l] = Double.NaN;
                worthBy[i][l] = Double.NaN;
            }
        }
    }

    /** How much of a newly seen gap is taken in: a quarter, so one odd evening does not rewrite it. */
    static final double GAP_LEARN = 0.25;

    /** True once after something worth saving changed; the caller then stores {@link #exportLearned()}. */
    private boolean learnedDirty;

    public boolean takeLearnedDirty() {
        boolean d = learnedDirty;
        learnedDirty = false;
        return d;
    }

    private void rememberWorth(boolean driveOn, double per) {
        if (validLevel(level) && per > 0.0) {
            double[] w = worthBy[driveOn ? 1 : 0];
            w[level] = Double.isNaN(w[level]) ? per : w[level] + (per - w[level]) * 0.5;
            learnedDirty = true;
        }
    }

    /** Mode {@code lv} has just settled: fold its gap to every other mode that settled recently into rel. */
    private void learnGaps(boolean driveOn, int lv, long nowMs) {
        double[] r = rel[driveOn ? 1 : 0];
        int mine = settledFor(driveOn, lv);
        if (mine <= 0) {
            return;
        }
        boolean any = false;
        for (int o = 1; o <= 4; o++) {
            int theirs = o == lv ? 0 : freshFor(driveOn, o, nowMs);
            if (theirs <= 0) {
                continue;
            }
            double seen = mine - theirs;
            if (Double.isNaN(r[lv]) && Double.isNaN(r[o])) {
                r[lv] = mine;
                r[o] = theirs;
            } else if (Double.isNaN(r[lv])) {
                r[lv] = r[o] + seen;
            } else if (Double.isNaN(r[o])) {
                r[o] = r[lv] - seen;
            } else {
                double move = (seen - (r[lv] - r[o])) * GAP_LEARN * 0.5;
                r[lv] += move;
                r[o] -= move;
            }
            any = true;
        }
        if (any) {
            learnedDirty = true;
        }
    }

    /** The learned gap, in points, by which {@code a} needs more fan than {@code b}; NaN if not known. */
    public double gap(boolean driveOn, int a, int b) {
        return validLevel(a) && validLevel(b) ? rel[driveOn ? 1 : 0][a] - rel[driveOn ? 1 : 0][b] : Double.NaN;
    }

    /** Everything kept for good, as one line for storage. */
    public String exportLearned() {
        StringBuilder sb = new StringBuilder("s1");
        for (int i = 0; i < 2; i++) {
            for (int l = 1; l <= 4; l++) {
                sb.append(',').append(Double.isNaN(rel[i][l]) ? "x" : Sample.fmt1(rel[i][l]));
                sb.append(',').append(Double.isNaN(worthBy[i][l]) ? "x"
                        : Double.toString(Math.round(worthBy[i][l] * 1000.0) / 1000.0));
            }
        }
        return sb.toString();
    }

    /** Take back a line from {@link #exportLearned()}. Anything unreadable is ignored whole; never throws. */
    public boolean importLearned(String s) {
        try {
            if (s == null) {
                return false;
            }
            String[] p = s.split(",");
            if (p.length != 17 || !"s1".equals(p[0])) {
                return false;
            }
            double[][] nr = new double[2][5], nw = new double[2][5];
            int at = 1;
            for (int i = 0; i < 2; i++) {
                for (int l = 1; l <= 4; l++) {
                    nr[i][l] = "x".equals(p[at]) ? Double.NaN : Double.parseDouble(p[at]);
                    at++;
                    nw[i][l] = "x".equals(p[at]) ? Double.NaN : Double.parseDouble(p[at]);
                    at++;
                    boolean okR = Double.isNaN(nr[i][l]) || (nr[i][l] > -200.0 && nr[i][l] < 200.0);
                    boolean okW = Double.isNaN(nw[i][l]) || (nw[i][l] > 0.0 && nw[i][l] < 5.0);
                    if (!okR || !okW) {
                        return false;
                    }
                }
            }
            for (int i = 0; i < 2; i++) {
                for (int l = 1; l <= 4; l++) {
                    rel[i][l] = nr[i][l];
                    worthBy[i][l] = nw[i][l];
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean validLevel(int rgblevel) {
        return rgblevel >= 1 && rgblevel <= 4;
    }

    /** The duty this brightness mode last settled at, or 0 if none is known. */
    public int settledFor(boolean driveOn, int rgblevel) {
        return validLevel(rgblevel) ? settled[driveOn ? 1 : 0][rgblevel] : 0;
    }

    /** Set a learned duty as learned at {@code nowMs}; 0 forgets it. Other out-of-range values are ignored. */
    public void setSettled(boolean driveOn, int rgblevel, int d, long nowMs) {
        if (validLevel(rgblevel) && (d == 0 || (d >= MIN_DUTY && d <= MAX_DUTY))) {
            settled[driveOn ? 1 : 0][rgblevel] = d;
            settledAtMs[driveOn ? 1 : 0][rgblevel] = nowMs;
        }
    }

    /** The learned duty if it is young enough to guide by, else 0. */
    int freshFor(boolean driveOn, int rgblevel, long nowMs) {
        int d = settledFor(driveOn, rgblevel);
        return d > 0 && nowMs - settledAtMs[driveOn ? 1 : 0][rgblevel] <= MEMORY_MS ? d : 0;
    }

    /** True while a learned speed is guiding the fan after a brightness change. */
    public boolean guided(long nowMs) {
        return guideUntilMs != 0L && nowMs < guideUntilMs;
    }

    /** What happened on the last step worth a log note ("guide ..." or "settled ..."), once; null if nothing. */
    public String takeEvent() {
        String e = event;
        event = null;
        return e;
    }

    private static String levelName(int rgblevel) {
        return rgblevel == 3 ? "Presentation" : rgblevel == 2 ? "Normal" : rgblevel == 1 ? "Night" : "SuperEco";
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
            return 5000L;
        }
        if (errC >= 0.5) {
            return 15000L;
        }
        if (errC >= 0.0) {
            return 45000L;
        }
        if (errC > -BAND_C) {
            return 0L;
        }
        if (errC > -4.0) {
            return 60000L;
        }
        if (errC > -8.0) {
            return 15000L;
        }
        return 3000L;
    }

    /**
     * Rate of change, degrees C per second (0.1 C a minute), beyond which the reading counts as
     * moving. The quicker steps above only stay quiet because of this: the fan moves only while
     * the temperature and its direction agree. On 14 Nov 2026 the fan kept climbing for a minute
     * after the LED had already turned and was cooling, and finished twelve points too high.
     */
    static final double TREND_C_PER_S = 0.1 / 60.0;

    /** Advance one tick and return the duty. {@code celsius} must already be plausible; the caller's fail-safe owns the rest. */
    public int step(double celsius, double targetC, long nowMs) {
        return step(celsius, targetC, MIN_DUTY, nowMs);
    }

    /** As above, with the floor for the current brightness mode; see {@link #minFor}. */
    public int step(double celsius, double targetC, int minDuty, long nowMs) {
        return step(celsius, targetC, minDuty, -1, nowMs);
    }

    /**
     * As above, also told the brightness mode ({@code rgblevel}, -1 if unknown). A change to a
     * mode with a learned speed lets that speed guide the fan for {@link #GUIDE_MS}: up or down to
     * it at a gentle pace, and no further, unless the LED is far over. Everything else is the
     * ordinary rule.
     */
    public int step(double celsius, double targetC, int minDuty, int rgblevel, long nowMs) {
        // Told by the caller when it knows; otherwise read off the target, as before per-mode targets.
        boolean driveOn = driveSet ? driveIs : targetC >= BOOST_TARGET_C;
        final double setTargetC = targetC;
        if (validLevel(rgblevel)) {
            if (rgblevel != pendingLevel) {
                pendingLevel = rgblevel;
                pendingSinceMs = nowMs;
            }
            if (!validLevel(level)) {
                level = rgblevel;
                measuredWorthC = worthBy[driveOn ? 1 : 0][level];
            } else if (rgblevel != level && nowMs - pendingSinceMs >= FLICK_MS) {
                int from = level;
                level = rgblevel;
                // A lower target must not make a warm LED look hot: see SWITCH_GRACE_MS.
                graceTargetC = Double.isNaN(lastTargetC) ? setTargetC : Math.max(lastTargetC, setTargetC);
                graceUntilMs = nowMs + SWITCH_GRACE_MS;
                // Steady time counts from the switch. Without this, the first tick in a new mode
                // still saw ten unchanged minutes and a smoothed reading in the band, both left
                // over from the old one: on 14 Nov 2026 it logged "settled Normal@42" the moment
                // Presentation's 42 was switched to Normal.
                changedMs = nowMs;
                probeAtMs = 0L;
                noProbeAtOrBelow = 0;
                lowSinceMs = 0L;
                // What a point is worth in this mode, if a probe has measured it before.
                measuredWorthC = worthBy[driveOn ? 1 : 0][level];
                failWorthC = Double.NaN;
                recheckRefC = Double.NaN;
                chainReady = false;
                upToDuty = 0;
                // Where to: this mode's own speed if it settled recently; otherwise the speed the
                // mode being left settled at, plus the learned gap between the two. The gap holds
                // whatever the room is doing, so it works on a day this mode has not run yet.
                guideDuty = freshFor(driveOn, level, nowMs);
                String how = "";
                if (guideDuty == 0) {
                    int fromFresh = freshFor(driveOn, from, nowMs);
                    double a = rel[driveOn ? 1 : 0][level], b = rel[driveOn ? 1 : 0][from];
                    if (fromFresh > 0 && !Double.isNaN(a) && !Double.isNaN(b)) {
                        int gap = (int) Math.round(a - b);
                        guideDuty = Math.max(MIN_DUTY, Math.min(MAX_DUTY, fromFresh + gap));
                        how = " (" + levelName(from) + " " + fromFresh + (gap >= 0 ? " +" : " ") + gap + ")";
                    }
                }
                guideUntilMs = guideDuty > 0 ? nowMs + GUIDE_MS : 0L;
                event = "guide " + levelName(from) + "->" + levelName(level)
                        + (guideDuty > 0 ? " to " + guideDuty + how
                        : settledFor(driveOn, level) > 0 ? " (learned " + settledFor(driveOn, level) + " is over 3 h old)"
                        : " (nothing learned yet)");
            }
        }
        if (validLevel(rgblevel) && validLevel(level) && rgblevel != level) {
            // A mode seen but not yet accepted (it may be one clicked past): keep the last target.
            if (!Double.isNaN(lastTargetC)) {
                targetC = Math.max(targetC, lastTargetC);
            }
        } else {
            if (nowMs < graceUntilMs && graceTargetC > targetC) {
                targetC = graceTargetC;
            }
            lastTargetC = setTargetC;
        }
        floor = minDuty < ABS_MIN_DUTY ? ABS_MIN_DUTY : minDuty > MAX_DUTY ? MAX_DUTY : minDuty;
        if (Double.isNaN(duty)) {
            duty = FanIo.FAIL_SAFE_DUTY;
        }
        if (Double.isNaN(celsius) || Double.isInfinite(celsius)) {
            return (int) duty;
        }
        duty = clamp(duty);
        if (Double.isNaN(smoothC) || lastMs == 0L) {
            smoothC = celsius;
            slowC = celsius;
        } else {
            double dt = Math.max(0.0, Math.min(5.0, (nowMs - lastMs) / 1000.0));
            smoothC += (celsius - smoothC) * (1.0 - Math.exp(-dt / SMOOTH_S));
            slowC += (celsius - slowC) * (1.0 - Math.exp(-dt / SLOW_S));
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
        if (err >= 0.0 && err < OVER_C) {
            // Right at the target is where it is meant to sit: noise there is not a reason to move.
            wait = 0L;
        }
        // The two averages lag a ramp by different amounts, and that difference is the slope.
        double slope = (smoothC - slowC) / (SLOW_S - SMOOTH_S);
        // Step only when the error and the trend agree. Over the target but already cooling: the
        // last raise is still arriving, so wait (unless it is far over). Under the band but already
        // warming: the last cut is still arriving, so wait.
        if (err >= 0.0 && err < 3.0 && slope < -TREND_C_PER_S) {
            wait = 0L;
        } else if (err >= 0.0 && err < 3.0 && slope < 0.0
                && smoothC + slope * LOOKAHEAD_S <= targetC + OVER_C) {
            // Cooling more slowly than that, but on course to be under the target within the
            // look-ahead: the points already added are enough. Without this the climb after a
            // switch to Presentation finished two points high in a warm room.
            wait = 0L;
        } else if (err < 0.0 && slope > TREND_C_PER_S && duty > MEASURED_FLOOR) {
            // Not under the measured floor: there the wider band already guards the fall, and
            // holding a point early would only leave it a point louder than it need be.
            wait = 0L;
        }
        if (catchingUp) {
            // Where the reading is heading, not where it is. Coming down on the prediction is what
            // lets this be quick without running past the target; it has its own look-ahead, so the
            // trend rule above does not hold it.
            double ahead = smoothC + Math.max(0.0, slope) * LOOKAHEAD_S - targetC;
            if (err >= 0.0 || ahead >= -BAND_C || duty <= floor) {
                catchingUp = false;
            } else {
                wait = ahead < -6.0 ? 3000L : ahead < -3.0 ? 6000L : 12000L;
            }
        }
        int dir = err >= 0.0 ? 1 : -1;
        boolean guided = guided(nowMs);
        if (!guided) {
            guideDuty = 0;
            guideUntilMs = 0L;
        } else {
            catchingUp = false;
            int at = (int) duty;
            int g = Math.max(floor, guideDuty);
            if (at < g) {
                // Up to the learned speed, whatever the trend: the heat it was learned against is on its way.
                dir = 1;
                wait = err >= GUIDE_OVER_C ? Math.min(intervalFor(err), GUIDE_UP_MS) : GUIDE_UP_MS;
            } else if (at > g) {
                if (err >= GUIDE_OVER_C) {
                    // Far over even here: the ordinary rule, upward.
                    dir = 1;
                } else if (err < 0.0) {
                    // Down to it, gently, and only while under the target.
                    dir = -1;
                    wait = GUIDE_DOWN_MS;
                } else {
                    wait = 0L;
                }
            } else if (!(err >= GUIDE_OVER_C || (err <= -4.0 && slope <= TREND_C_PER_S))) {
                // At the learned speed: stay, unless far over, or far under and not warming.
                wait = 0L;
            }
        }
        if (changedMs == 0L) {
            changedMs = nowMs;
        }

        // Probing for the lowest speed that holds the target. A probe takes as many points as the
        // gap allows at once (all but one, until a point has been measured), checks them with one
        // fit, and if there is still room, goes straight on to the next without waiting for calm.
        boolean probeStep = false;
        if (probeAtMs != 0L && fitN < fitT.length && nowMs - fitLastMs >= 5000L) {
            double ts = (nowMs - probeAtMs) / 1000.0;
            fitT[fitN] = ts;
            // Take out the creep already under way from earlier steps, so the fit sees this probe's
            // own effect; read as the probe's, that creep stopped the descent early in simulation.
            fitY[fitN] = celsius - Math.max(0.0, probeStartSlope) * ts;
            fitN++;
            fitLastMs = nowMs;
        }
        if (guided && (probeAtMs != 0L || chainReady || upToDuty > 0)) {
            probeAtMs = 0L;
            chainReady = false;
            upToDuty = 0;
        }
        if (upToDuty > 0) {
            // Backing out of a probe that would end over: a ramp up, a point every few seconds.
            if ((int) duty < upToDuty) {
                dir = 1;
                wait = PROBE_RAMP_MS;
            } else {
                upToDuty = 0;
            }
        } else if (probeAtMs != 0L) {
            if ((int) duty > probeTo) {
                // Still ramping down to the probe's speed.
                dir = -1;
                wait = PROBE_RAMP_MS;
            } else if (nowMs - probeAtMs >= VERIFY_AFTER_MS) {
                // Where will this speed settle? The fit sees the LED and the first warming; the
                // chassis share still to come is added on top.
                ExpFit.Fit f = ExpFit.fit(fitT, fitY, fitN);
                int k = Math.max(1, probeFrom - (int) duty);
                double fast = (f.ok ? f.tInf : smoothC) - probeStartC;
                double total = Math.max(0.0, fast) / (1.0 - SLOW_SHARE * 0.5);
                double per = total / k;
                double pred = probeStartC + total;
                probeAtMs = 0L;
                if (per < PROBE_MIN_EFFECT_C) {
                    // Fewer points and no warming to show for it: the reading is not following the
                    // fan (a stuck sensor reads like this). Put the points back and stop probing in
                    // this mode; going on would walk the fan down blind.
                    upToDuty = probeFrom;
                    noProbeAtOrBelow = AutoHold.MAX_DUTY;
                    recheckRefC = Double.NaN;
                    chainReady = false;
                    dir = 1;
                    wait = 1L;
                    event = "probe " + (int) duty + " showed no warming; back to " + probeFrom + ", probing off in this mode";
                } else if (pred > targetC) {
                    measuredWorthC = per;
                    rememberWorth(driveOn, per);
                    // Would end over: back up the points it is over by, before the LED gets there.
                    int u = Math.max(1, Math.min(k, (int) Math.ceil((pred - targetC) / Math.max(0.01, per))));
                    upToDuty = (int) duty + u;
                    noProbeAtOrBelow = upToDuty - 1;
                    recheckRefC = Double.NaN;
                    failWorthC = per;
                    chainReady = false;
                    dir = 1;
                    wait = 1L;
                    event = "probe " + (int) duty + " would settle at " + Sample.fmt1(pred) + ", back to " + upToDuty;
                } else {
                    // Holds. If one more point still fits under the target, take it now.
                    measuredWorthC = per;
                    rememberWorth(driveOn, per);
                    chainBaseC = pred;
                    chainReady = (int) duty - 1 >= floor && (int) duty - 1 > noProbeAtOrBelow
                            && pred + per <= targetC - PROBE_MARGIN_C;
                    event = "probe " + (int) duty + " holds, settles about " + Sample.fmt1(pred)
                            + " (" + Sample.fmt1(per) + " C a point)";
                }
            }
        } else if (!guided && !catchingUp && !holdLearning && wait == 0L && (int) duty - 1 >= floor
                && (int) duty - 1 > noProbeAtOrBelow) {
            boolean calm = nowMs - changedMs >= PROBE_CALM_MS
                    && (lastUpMs == 0L || nowMs - lastUpMs >= PROBE_AFTER_UP_MS)
                    && Math.abs(slope) <= TREND_C_PER_S * 0.5;
            if (chainReady || calm) {
                // From where the LED is heading, not where it is: just after the fan has come down
                // it is still creeping up, and a probe judged from the reading took points it did
                // not have (56.9 read, about 57.5 coming) and ran warm in nearly every simulation.
                double base = chainReady ? chainBaseC : smoothC + Math.max(0.0, slope) * CREEP_LOOK_S;
                double w = worthFor((int) duty - 1);
                int room = (int) Math.floor((targetC - PROBE_MARGIN_C - base) / w);
                // Until a point has been measured here, leave one in hand and take at most
                // PROBE_FIRST_MAX: where a point is worth far more than the rough figure, a bigger
                // first jump overshot to 58.4 in simulation before its check could back it out.
                // A follow-up probe takes one point only: on 14 Nov 2026 the first probe held
                // (44 to 43, predicted 57.4, read 57.43) and the follow-up took two, ran warm at
                // 58.13 and came back up both. And the first always leaves one in hand, measured
                // worth or not.
                int k = chainReady ? 1 : Math.min(room - 1, PROBE_FIRST_MAX);
                k = Math.min(k, PROBE_MAX_POINTS);
                k = Math.min(k, (int) duty - Math.max(floor, noProbeAtOrBelow + 1));
                if (room >= 1 && k < 1) {
                    k = 1;
                }
                if (k >= 1 && base + w * k <= targetC - PROBE_MARGIN_C) {
                    probeStep = true;
                    probeTo = (int) duty - k;
                    dir = -1;
                    wait = 1L;
                }
                chainReady = false;
            }
        }        // The room cooled since the bottom was found: look again. Judged at the same speed, so no
        // guess about what a point is worth comes into it (a guess re-probed every twenty minutes
        // in simulation, where a point was worth 1 C rather than 0.2): once settled one above the
        // bottom, note the LED; only a fall of RECHECK_UNDER_C from that means the room has cooled.
        if (noProbeAtOrBelow > 0 && (int) duty == noProbeAtOrBelow + 1 && Double.isNaN(recheckRefC)
                && nowMs - changedMs >= PROBE_CALM_MS && Math.abs(slope) <= TREND_C_PER_S * 0.5) {
            recheckRefC = smoothC;
        }
        if (noProbeAtOrBelow > 0 && !Double.isNaN(recheckRefC) && (int) duty == noProbeAtOrBelow + 1
                && smoothC <= recheckRefC - RECHECK_UNDER_C && Math.abs(slope) <= TREND_C_PER_S) {
            if (lowSinceMs == 0L) {
                lowSinceMs = nowMs;
            } else if (nowMs - lowSinceMs >= RECHECK_AFTER_MS) {
                noProbeAtOrBelow = 0;
                lowSinceMs = 0L;
                recheckRefC = Double.NaN;
            }
        } else {
            lowSinceMs = 0L;
        }

        if ((int) duty > ceiling) {
            // Over the fan limit (it was just set, or just came back): down to it, a point every two seconds.
            dir = -1;
            wait = 2000L;
            probeStep = false;
        } else if ((int) duty == ceiling && dir > 0) {
            wait = 0L;
            upToDuty = 0;
        }
        if (wait > 0L && nowMs - steppedMs >= wait) {
            double before = duty;
            duty = clamp(duty + dir);
            steppedMs = nowMs;
            if (duty != before) {
                changedMs = nowMs;
                if (dir > 0) {
                    lastUpMs = nowMs;
                    if (!guided && (int) before > noProbeAtOrBelow) {
                        // Having to come up from a speed shows that speed is too low here: do not
                        // probe it again until the room has cooled. Without this, a probe accepted
                        // a touch optimistically was tried again hours later and failed.
                        noProbeAtOrBelow = (int) before;
                        recheckRefC = Double.NaN;
                    }
                }
                if (probeStep) {
                    probeAtMs = nowMs;
                    probeFrom = (int) before;
                    probeDuty = probeTo;
                    probeStartC = smoothC;
                    probeStartSlope = slope;
                    event = "probe " + probeFrom + "->" + probeTo + " started at " + Sample.fmt1(smoothC);
                    fitN = 0;
                    fitLastMs = 0L;
                } else if (probeAtMs != 0L && dir > 0) {
                    // The LED went over before the fit was due: that speed was too low.
                    noProbeAtOrBelow = (int) before;
                    recheckRefC = Double.NaN;
                    event = "probe " + (int) before + " ran warm, back to " + (int) duty;
                    probeAtMs = 0L;
                    chainReady = false;
                }
            }
        }
        // Learn: unchanged ten minutes, unguided, and in the band or resting on the floor.
        // err already carries the wider band under the measured floor.
        if (!guided && !holdLearning && validLevel(level) && nowMs - changedMs >= SETTLED_AFTER_MS
                && (err > -BAND_C || (int) duty <= floor) && err < 0.5
                && (settledFor(driveOn, level) != (int) duty
                        || nowMs - settledAtMs[driveOn ? 1 : 0][level] > SETTLED_AFTER_MS)) {
            // Re-stamped every ten steady minutes even when unchanged, so a mode in use stays fresh.
            boolean changed = settledFor(driveOn, level) != (int) duty;
            setSettled(driveOn, level, (int) duty, nowMs);
            learnGaps(driveOn, level, nowMs);
            if (changed) {
                event = "settled " + levelName(level) + "@" + (int) duty;
            }
        }
        return (int) duty;
    }

    /** The most the fan may go (the owner's fan limit, or MAX_DUTY), and whether the picture is being dimmed to stay there. */
    private int ceiling = MAX_DUTY;
    private boolean holdLearning;

    /**
     * Set the fan limit for the coming ticks. Above it the fan comes down a point every two
     * seconds rather than at once. While {@code dimmed}, nothing is learned and no probe starts:
     * a speed held by dimming the picture is not what the mode needs.
     */
    public void setCeiling(int max, boolean dimmed) {
        ceiling = max < MIN_DUTY ? MIN_DUTY : max > MAX_DUTY ? MAX_DUTY : max;
        holdLearning = dimmed || ceiling < MAX_DUTY && !Double.isNaN(duty) && (int) duty >= ceiling;
    }

    /** The duty this controller is asking for, or -1 before its first step. */
    public int duty() {
        return Double.isNaN(duty) ? -1 : (int) duty;
    }

    private double clamp(double d) {
        return d < floor ? floor : d > MAX_DUTY ? MAX_DUTY : d;
    }
}
