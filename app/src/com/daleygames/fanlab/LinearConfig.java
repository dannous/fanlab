package com.daleygames.fanlab;

/**
 * The temperature ceiling {@link FanLinear} holds, and the two numbers that decide how it
 * gets there.
 *
 * <h3>Why a ceiling and not a curve</h3>
 * {@link CurveConfig} predicts. It maps temperature to duty, and its shape comes from a
 * plant table measured on one unit at 24 C ambient, so where it settles follows from that
 * table and every prediction written down about it inherits the caveat. A ceiling predicts
 * nothing. It states the temperature the light engine must not exceed and lets the loop
 * find whatever duty holds it, which needs no plant model and is indifferent to ambient,
 * to the brightness tier and to unit-to-unit variation. The cost is fan speed, and in a
 * hot room it is a real cost -- that is the trade, stated rather than hidden.
 *
 * <h3>There is no hold zone, and that is the point</h3>
 * A hold zone -- a band the measurement may sit inside with the duty frozen -- would stop
 * the walk at the top of the band rather than at the quietest duty that actually holds the
 * ceiling. The owner's requirement is that it "always find the quietest low temperature it
 * can", so the controller never stops moving: every step down is it testing whether one
 * point less would do. The price is a permanent small oscillation, which is why the three
 * step intervals below are the parameters that matter here.
 *
 * <h3>Three intervals, because one cannot work -- and this is measured</h3>
 * Settled swing scales as step rate times plant lag. Two points were measured on hardware
 * at a 52 C ceiling: a 5 s decay swung <b>14 duty points</b> and the owner heard it
 * climbing; a 60 s decay swung <b>3</b> and he did not notice it. Fitting those gives
 * {@code swing ~ 60 / decay_seconds + 2}, which is interpolation between two points rather
 * than a law, but it is the best rule available.
 *
 * That creates a conflict with no single-rate answer. A 3-point swing needs a decay of
 * 60 s or slower; giving back a 6-point overshoot at one point per 60 s takes six minutes,
 * and a sub-minute descent needs 10 s, which swings 8 and is audible. <b>Every variant
 * tested on hardware was therefore either audible or slow.</b>
 *
 * The way out is that the two requirements apply in different places. With more than
 * {@link #nearC} of headroom below the ceiling you cannot overshoot it by dropping fast, so
 * drop fast ({@link #downFastMs}); inside that band precision is the whole job, so drop
 * slowly ({@link #downStepMs}). Fast where it is free, slow where it counts. Rising is
 * always {@link #upStepMs}, because being late upwards is the one direction that matters
 * thermally.
 *
 * Pure Java.
 */
public final class LinearConfig {

    /**
     * Fields in an encoded {@code l2} line, including the version tag.
     *
     * {@code l1} lines carried a single step interval and are still parsed -- see
     * {@link #decode} -- because that format existed while this mode was being built.
     */
    public static final int FIELDS = 10;

    /** Fields in the superseded single-interval {@code l1} line. */
    public static final int FIELDS_L1 = 6;

    /** Fields in the {@code l2} line, which had the three intervals but no trend gate. */
    public static final int FIELDS_L2 = 9;

    /**
     * Bounds on {@link #ceilingC}, degrees C.
     *
     * Asymmetric because the two ends fail differently. Too low is merely unachievable:
     * the fan pegs at {@link #maxDuty}, says so through {@link FanLinear#saturated()}, and
     * the machine is over-cooled rather than under-cooled. Too high is a safety question --
     * a ceiling near the 75 C shutdown would let the loop walk the fan <i>down</i> while
     * the light engine climbed towards it -- so the top of the range stops well short.
     */
    public static final double MIN_CEILING_C = 35.0;
    public static final double MAX_CEILING_C = 70.0;

    /** Bounds on every step interval. One outside this is a typo, not a setting. */
    public static final long MIN_STEP_MS = 1000L;
    public static final long MAX_STEP_MS = 120000L;

    /** Bounds on {@link #nearC}. Zero would disable the schedule; 10 C is the whole range. */
    public static final double MIN_NEAR_C = 0.0;
    public static final double MAX_NEAR_C = 10.0;

    /**
     * Bounds on {@link #trendWindowS}. Zero switches the trend gate off entirely, which is
     * the pre-2026-09-07 behaviour and is kept so the two can be compared by ear.
     */
    public static final int MIN_TREND_S = 0;
    public static final int MAX_TREND_S = 300;

    /**
     * Sample-to-sample standard deviation of the LED thermistor, degrees C.
     *
     * Measured from a flat stretch of the field log, not assumed. It is here because the
     * trend gate has to know its own noise floor: a slope threshold set below it makes the
     * gate fire at random, which suppresses windup by accident and flatters itself in
     * simulation. That happened during the search and is the reason this number is a named
     * constant rather than a literal.
     */
    public static final double SENSOR_NOISE_SD_C = 0.078;

    /**
     * The stock default ceiling, degrees C, and the value {@link #promoteForBoost} treats as
     * "the owner has not touched this".
     */
    public static final double DEFAULT_CEILING_C = 52.0;

    /**
     * The default ceiling while the LED drive override is on. <b>Inferred, not measured.</b>
     *
     * Every number here comes from scaling the measured plant by the x1.18 the override
     * costs at drive 90, so all of it is arithmetic on a table measured at stock drive
     * rather than anything anyone has listened to.
     *
     * Holding the stock 52.0 under the boost costs duty <b>50 in a 24 C room</b> where
     * today it rests at 38, reaches 60 by 26.6 C, and runs out of authority at about
     * 29.1 C ambient instead of 32.6. At 54.0 it rests at about <b>44</b> at 24 C, reaches
     * 50 at 26.0 C, and saturates around 31.1 C -- most of the headroom back, for two
     * degrees on a sensor that is already two degrees above the stock operating point.
     *
     * 54 is also where the Bright CURVE preset rests, which is the reason to pick it over
     * any other number: {@link Mode} records that LINEAR's ceiling matches where the curve
     * settles precisely so the two can be A/B'd by ear, and a boost that moved one and not
     * the other would confound "which controller" with "which temperature".
     */
    public static final double BOOST_CEILING_C = 54.0;

    /**
     * The temperature the light engine is held at, degrees C.
     *
     * 52.0 by default, so that at 24 C ambient in Presentation this mode lands on the same
     * operating point as CURVE with the Quiet preset -- 38 % and about 52 C -- and diverges
     * either side of it, holding the temperature where CURVE would hold the duty. Starting
     * the comparison from a shared point is what makes it a comparison; the owner wants to
     * A/B the two by ear, and a ceiling picked anywhere else would confound "which
     * controller" with "which temperature".
     *
     * A single global field, deliberately: the brightness profile only seeds the resync, and
     * that is fine here, because Normal at drive 70 sits about 48 C at duty 30 and never
     * reaches either ceiling. See {@link #promoteForBoost} for what the LED drive override
     * does to it.
     */
    public double ceilingC;

    /**
     * Milliseconds between decisions while at or above the ceiling. <b>Measured, not
     * guessed.</b>
     *
     * The plant's fast pole was measured on this unit by a bracketed step test -- duty 35,
     * then 65, then 35 again, six minutes each: <b>115 s cooling and 133 s warming</b>,
     * with the exponential fits extrapolating only 0.10 C past the last sample, so these
     * are measurements rather than opinions. Raw traces in
     * {@code runs/field-20260907/steptest.csv}.
     *
     * Oscillation amplitude scales as step rate times that lag, and the top of the range
     * has been measured too: <b>one duty point per second produced a growing limit
     * cycle</b> -- duty walking 30 to 69 and back, peaks climbing 62, 67, 69, each swing
     * taking longer than the last (86 s, 98 s, 106 s). That is the audible failure this
     * whole project exists to remove, reproduced deliberately.
     * {@code runs/field-20260907/selfbal-1s.csv} is the trace.
     *
     * 5 s is a fifth of that rate. <b>Nobody should "optimise" this downward without
     * repeating the step test.</b> Settable with {@code --ei linup}.
     */
    public long upStepMs;

    /**
     * Milliseconds between decisions while falling and within {@link #nearC} of the
     * ceiling. <b>This is the number that decides whether anyone hears it.</b>
     *
     * 60 s, because 60 s is the only decay rate measured on this machine that the owner did
     * not notice: a 3-point settled swing at duty 42-45, against 14 points at 5 s which he
     * heard climbing. Traces {@code selfbal-asym.csv} and {@code selfbal-sym5.csv}.
     *
     * <b>Do not shorten this without an ear check.</b> Asymmetry ratio is what works, and
     * it has to be strong: 12:1 (5 s up, 60 s down) gave the 3-point swing, while 1.67:1
     * (3 s up, 5 s down) gave 14 -- indistinguishable from symmetric.
     * {@code selfbal-3s5s-twospeed.csv} is that trace.
     *
     * The one open experiment is 30 s, where the swing law predicts 4 points and nobody has
     * listened. The audibility boundary is somewhere between the 3 he did not notice and
     * the 14 he did; ten minutes on the machine would locate it, and it is the difference
     * between a three-minute and a six-minute descent. Settable with {@code --ei lindown}
     * so that experiment is a broadcast rather than a build.
     */
    public long downStepMs;

    /**
     * Milliseconds between decisions while falling with more than {@link #nearC} of
     * headroom below the ceiling.
     *
     * 10 s. Six times faster than {@link #downStepMs} and it costs nothing, because the
     * swing law only governs behaviour <i>at</i> the ceiling: an overshoot cannot be caused
     * by descending quickly through temperatures that are already comfortably under it. It
     * exists to fix the measured cost of the quiet decay -- the accepted variant took about
     * seven minutes to walk 48 down to 42 and had not finished. Settable with
     * {@code --ei linfast}.
     *
     * Sanitised to be no slower than {@link #downStepMs}: this interval is the fast one by
     * definition, and swapping them would invert the whole schedule.
     */
    public long downFastMs;

    /**
     * How close to the ceiling counts as close, degrees C.
     *
     * 1.0. Above this much headroom the controller drops at {@link #downFastMs}, inside it
     * at {@link #downStepMs}. It is not a hold zone and must not become one -- the duty
     * keeps stepping either way, because finding the quietest duty that holds the ceiling
     * requires continually testing whether one point less would do. This only sets how fast
     * it tests. Settable with {@code --ef linnear}.
     */
    public double nearC;

    /**
     * Seconds of temperature history the trend gate fits a slope over. 0 disables it.
     *
     * <h3>What the gate does</h3>
     * The walk steps on whether the light engine is above or below the ceiling. That alone
     * is blind to whether the fan it already has is working: it keeps adding fan while the
     * temperature is falling, purely because the absolute reading has not crossed back yet.
     * On a plant with a 120 s lag that is textbook integral windup, and it was watched
     * happening on hardware on 2026-09-07 -- seeded to 38, the walk climbed to 53 while the
     * thermistor had been falling for 36 seconds, overshooting an equilibrium of 45.
     *
     * So: <b>do not add fan while the temperature is already coming down, and do not remove
     * it while the temperature is still climbing.</b> The ceiling still decides direction;
     * the trend only decides whether to act now or wait and see.
     *
     * <h3>Why 90 seconds</h3>
     * The window has to beat its own noise. A least-squares slope over N points spanning T
     * seconds has a standard error of {@link #SENSOR_NOISE_SD_C}*sqrt(12/(N*T*T)); the gate
     * uses three of those, so it cannot be tripped by sensor noise. A shorter window is
     * noisier and a longer one is slower to notice a real turn.
     *
     * 90 s was chosen by sweeping 15, 30, 60 and 90 against the measured two-pole plant over
     * twelve noise seeds each, in {@code tools/LinearSim.java}. Against the ungated walk it
     * takes the settled swing at 24 C ambient from 3.83 duty points to <b>0.25</b>, at 27 C
     * from 4.08 to 2.08, and at 30 C it cuts the peak of the approach from <b>83 % to
     * 62 %</b> while also reducing the swing. At 33 C, where the ceiling needs the full 83 %,
     * it still goes there -- the gate delays action, it never caps authority.
     */
    public int trendWindowS;

    /** Duty commanded while the light engine is off (led_status == 0). Stock uses 10. */
    public int idleDuty;

    /** Hard floor. 30 is "inaudible even up close" on the owner's own scale. */
    public int minDuty;

    /** Hard ceiling. 83 is the stock controller's maximum for every tier. */
    public int maxDuty;

    public LinearConfig() {
        setDefaults();
    }

    /** 52 C; up every 5 s, down every 60 s near the ceiling and every 10 s below it. */
    public void setDefaults() {
        ceilingC = DEFAULT_CEILING_C;
        upStepMs = 5000L;
        downStepMs = 60000L;
        downFastMs = 10000L;
        nearC = 1.0;
        trendWindowS = 90;
        idleDuty = 10;
        minDuty = 30;
        maxDuty = 83;
    }

    /**
     * Repair anything a user or a corrupt preferences file could have put in here.
     * Called on every load and after every edit, so the control loop can assume sanity.
     */
    public void sanitise() {
        if (Double.isNaN(ceilingC) || Double.isInfinite(ceilingC)) {
            ceilingC = DEFAULT_CEILING_C;
        }
        ceilingC = snap(ceilingC);
        if (ceilingC < MIN_CEILING_C) {
            ceilingC = MIN_CEILING_C;
        }
        if (ceilingC > MAX_CEILING_C) {
            ceilingC = MAX_CEILING_C;
        }
        upStepMs = clampStep(upStepMs);
        downStepMs = clampStep(downStepMs);
        downFastMs = clampStep(downFastMs);
        // The fast decay is the fast one by definition. Swapping the two would invert the
        // schedule -- slow where dropping is free, fast where it costs an audible swing --
        // so a config that asks for that is repaired rather than obeyed.
        if (downFastMs > downStepMs) {
            downFastMs = downStepMs;
        }
        if (Double.isNaN(nearC) || Double.isInfinite(nearC)) {
            nearC = 1.0;
        }
        nearC = snap(nearC);
        if (nearC < MIN_NEAR_C) {
            nearC = MIN_NEAR_C;
        }
        if (nearC > MAX_NEAR_C) {
            nearC = MAX_NEAR_C;
        }
        if (trendWindowS < MIN_TREND_S) {
            trendWindowS = MIN_TREND_S;
        }
        if (trendWindowS > MAX_TREND_S) {
            trendWindowS = MAX_TREND_S;
        }
        if (minDuty < FanIo.MIN_DUTY) {
            minDuty = FanIo.MIN_DUTY;
        }
        if (maxDuty > FanIo.MAX_DUTY) {
            maxDuty = FanIo.MAX_DUTY;
        }
        if (maxDuty < minDuty) {
            maxDuty = minDuty;
        }
        if (idleDuty < FanIo.MIN_DUTY) {
            idleDuty = FanIo.MIN_DUTY;
        }
        if (idleDuty > FanIo.MAX_DUTY) {
            idleDuty = FanIo.MAX_DUTY;
        }
    }

    /**
     * Raise an untouched default ceiling to {@link #BOOST_CEILING_C} while the LED drive
     * override is on, and leave a hand-set one exactly where its owner put it.
     *
     * <h3>Why LINEAR needs no new mode for the boost, only a different number</h3>
     * LINEAR holds a temperature and lets the fan float, so it self-corrects for the extra
     * LED heat with no help at all -- it simply pays for it in duty. The problem is only how
     * <i>much</i> duty: at drive 90 the stock 52.0 costs 50 points in a 24 C room where
     * today it rests at 38, and gives up at 29.1 C ambient. {@link #BOOST_CEILING_C} has the
     * inferred numbers for both ceilings.
     *
     * <h3>Why this is not a change to the stored setting</h3>
     * <b>Nothing here writes a preference.</b> The promotion is applied to the config as it
     * is loaded, every time, so switching the override off puts the ceiling back to 52.0 by
     * itself and an existing user's stored default is never quietly rewritten underneath
     * them. It also means the promotion can be reported honestly wherever the ceiling is --
     * the status line, the tick note, the broadcast reply -- rather than looking like a
     * number the owner chose.
     *
     * A ceiling that is anything other than the default is left alone whether or not the
     * boost is on: someone who typed 49 meant 49, and a controller that moved it because a
     * different feature was switched on would be exactly the kind of surprise this file's
     * other comments spend their length avoiding.
     *
     * @return true if the ceiling was moved, which the caller should say out loud.
     */
    public static boolean promoteForBoost(LinearConfig cfg, boolean boostOn) {
        if (cfg == null || !boostOn) {
            return false;
        }
        // Half a tenth: ceilingC is snapped to a tenth on every load, so this is an exact
        // comparison with room for the double, not a tolerance for a value near 52.
        if (Math.abs(cfg.ceilingC - DEFAULT_CEILING_C) > 0.05) {
            return false;
        }
        cfg.ceilingC = BOOST_CEILING_C;
        return true;
    }

    /**
     * To a tenth of a degree.
     *
     * The thermistor's noise is 0.166 C sd, so a ceiling specified finer than a tenth is
     * false precision -- and {@code --ef ceiling} arrives as a float, which without this
     * turns 52.3 into 52.29999923706055 in the encoded line and in every reply that echoes
     * it, which would then never round-trip.
     */
    private static double snap(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static long clampStep(long v) {
        if (v < MIN_STEP_MS) {
            return MIN_STEP_MS;
        }
        if (v > MAX_STEP_MS) {
            return MAX_STEP_MS;
        }
        return v;
    }

    /** Serialise to a single line, for SharedPreferences. */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append("l3");
        sb.append(',').append(ceilingC);
        sb.append(',').append(upStepMs);
        sb.append(',').append(downStepMs);
        sb.append(',').append(downFastMs);
        sb.append(',').append(nearC);
        sb.append(',').append(trendWindowS);
        sb.append(',').append(idleDuty);
        sb.append(',').append(minDuty);
        sb.append(',').append(maxDuty);
        return sb.toString();
    }

    /**
     * Parse a line produced by {@link #encode()}. Anything unparseable falls back to the
     * defaults; a missing or broken config must never leave the fan uncontrolled.
     *
     * <b>{@code l1} lines are still read.</b> That format had one step interval, and it is
     * the version whose symmetric 5 s decay measured a 14-point swing -- so a stored
     * {@code l1} is mapped onto {@link #upStepMs} and the two decay intervals take their
     * defaults, which is the configuration that was actually accepted by ear. Reading it as
     * a symmetric line would reinstate the rejected behaviour silently.
     */
    public static LinearConfig decode(String s) {
        LinearConfig c = new LinearConfig();
        if (s == null) {
            return c;
        }
        try {
            String[] f = s.split(",");
            if (f.length >= FIELDS && "l3".equals(f[0])) {
                int k = 1;
                c.ceilingC = Double.parseDouble(f[k++].trim());
                c.upStepMs = Long.parseLong(f[k++].trim());
                c.downStepMs = Long.parseLong(f[k++].trim());
                c.downFastMs = Long.parseLong(f[k++].trim());
                c.nearC = Double.parseDouble(f[k++].trim());
                c.trendWindowS = Integer.parseInt(f[k++].trim());
                c.idleDuty = Integer.parseInt(f[k++].trim());
                c.minDuty = Integer.parseInt(f[k++].trim());
                c.maxDuty = Integer.parseInt(f[k].trim());
            } else if (f.length >= FIELDS_L2 && "l2".equals(f[0])) {
                int k = 1;
                c.ceilingC = Double.parseDouble(f[k++].trim());
                c.upStepMs = Long.parseLong(f[k++].trim());
                c.downStepMs = Long.parseLong(f[k++].trim());
                c.downFastMs = Long.parseLong(f[k++].trim());
                c.nearC = Double.parseDouble(f[k++].trim());
                c.idleDuty = Integer.parseInt(f[k++].trim());
                c.minDuty = Integer.parseInt(f[k++].trim());
                c.maxDuty = Integer.parseInt(f[k].trim());
                // l2 predates the trend gate. Take the default rather than 0: the ungated
                // walk is the version measured winding to 83 % on the approach, and a
                // stored config should not quietly reinstate that.
            } else if (f.length >= FIELDS_L1 && "l1".equals(f[0])) {
                int k = 1;
                c.ceilingC = Double.parseDouble(f[k++].trim());
                c.upStepMs = Long.parseLong(f[k++].trim());
                c.idleDuty = Integer.parseInt(f[k++].trim());
                c.minDuty = Integer.parseInt(f[k++].trim());
                c.maxDuty = Integer.parseInt(f[k].trim());
            } else {
                return c;
            }
        } catch (RuntimeException e) {
            c.setDefaults();
        }
        c.sanitise();
        return c;
    }
}
