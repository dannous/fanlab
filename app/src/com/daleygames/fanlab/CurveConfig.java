package com.daleygames.fanlab;

/**
 * The fan curve: a piecewise-linear map from LED temperature to duty, one duty column
 * per brightness tier, plus the hysteresis and slew-rate parameters that make the result
 * inaudible.
 *
 * <h3>Why these defaults</h3>
 * The stock controller is a bare if/else ladder with no hysteresis, sampled every 15 s.
 * Its tier-3 (Presentation) rungs are, using the true boundaries:
 * <pre>
 *   t &lt; 45.5  -&gt; high.speed.min      45.5..48.5 -&gt; 70
 *   48.5..50.5 -&gt; 75                  50.5..52.5 -&gt; 80      t &ge; 54.5 -&gt; 83
 * </pre>
 * The equilibrium parks at about 45.5 C, right on the first boundary, so the fan
 * alternates between the floor and 70 forever. With the floor at 69 the step is one
 * point and inaudible ("steady"); with the floor at 55 the step is fifteen points and
 * obvious ("cycling"). That is the entire bug: not the floor value, the missing
 * hysteresis at the boundary the machine happens to sit on.
 *
 * The shipped table is the one measured on hardware and written up in CURVE.md, and its
 * shape follows from that diagnosis:
 * <ul>
 *   <li><b>a flat shelf at duty 30 up to 48 C</b>, because the fix for a boundary the
 *       machine parks on is not a better boundary but no boundary -- in a normal room the
 *       operating point sits in the middle of the shelf and the fan never moves at all;</li>
 *   <li><b>a gentle ramp above it</b>, about two duty points per degree, so that when the
 *       machine does leave the shelf it leaves it continuously;</li>
 *   <li><b>a backstop</b> reaching 83 by 70 C, at temperatures the plant cannot reach at
 *       any plausible ambient;</li>
 *   <li><b>identical columns for all three profiles</b>, so a brightness change is not a
 *       tier change in duty terms and FanCurve's immediate-jump exception never fires.</li>
 * </ul>
 * The response is monotone and continuous, so it is self-correcting: if the temperature
 * rises, duty rises with it immediately, instead of waiting for a whole-degree tick.
 *
 * Pure Java.
 */
public final class CurveConfig {

    /** rgblevel 1 (Eco) and 4 (Super Eco) share this column, exactly as the stock ladder does. */
    public static final int PROFILE_LOW = 0;
    /** rgblevel 2 (Normal). */
    public static final int PROFILE_NORMAL = 1;
    /** rgblevel 3 (Presentation). */
    public static final int PROFILE_HIGH = 2;
    public static final int PROFILES = 3;

    public static final String[] PROFILE_NAMES = {"Eco / Super Eco", "Normal", "Presentation"};

    /** Fields the optional guard block adds to the encoded line. */
    public static final int GUARD_FIELDS = 5;

    /** Number of knee points. Fixed so the D-pad editor has a fixed shape. */
    public static final int POINTS = 6;

    /** Knee temperatures, degrees C, ascending. Editable. */
    public final int[] tempC = new int[POINTS];

    /** Duty at each knee, per profile. Editable. */
    public final int[][] duty = new int[PROFILES][POINTS];

    /**
     * Deadband, degrees C. The curve input rises with the measured temperature
     * immediately, but only follows it down after it has fallen this far. Asymmetric on
     * purpose: rising is a safety event, falling is a comfort event.
     */
    public double hysteresisC;

    /** Maximum rise, duty points per second. */
    public double slewUpPerSec;

    /** Maximum fall, duty points per second. */
    public double slewDownPerSec;

    /** Duty commanded while the light engine is off (led_status == 0). Stock uses 10. */
    public int idleDuty;

    /** Hard floor applied after the curve, never below {@link FanIo#MIN_DUTY}. */
    public int minDuty;

    /** Hard ceiling applied after the curve, never above {@link FanIo#MAX_DUTY}. */
    public int maxDuty;

    /**
     * Is the SoC guard armed? On by default, because with the shipped numbers it cannot
     * fire in any condition yet measured -- it is a backstop, not a second curve.
     */
    public boolean socGuardEnabled;

    /**
     * SoC die temperature at which the guard starts adding fan, degrees C, read from
     * thermal_zone0.
     */
    public int socGuardStartC;

    /**
     * Extra duty points per degree above {@link #socGuardStartC}.
     *
     * The guard is <b>additive</b>, not an absolute floor, and that is the whole design.
     * An absolute floor has to climb from the minimum duty back up to whatever the curve
     * already wanted before it can achieve anything -- a third of its useful range spent
     * catching up -- and it arrives at the knee as a step, which is exactly the
     * discontinuity the stock controller parks on. Adding to the curve's own output
     * instead means the guard contributes nothing at the knee, rises continuously from
     * there, and spends every point it asks for.
     */
    public double socGuardGainPerC;

    /**
     * Ceiling on the guarded duty. Not the hardware maximum: from duty 40 the fan has
     * about 7.5 C of total authority over the die and 5.4 C of that is bought by duty 60,
     * so the last twenty-odd points buy 2 C. Past here it is paying a lot of noise for
     * very little heat, and the SoC's own throttling is the better tool.
     */
    public int socGuardMaxDuty;

    /**
     * Deadband for the guard's input, degrees C. Wider than {@link #hysteresisC} because
     * a die sensor is noisier and faster-moving than a thermistor bolted to the chassis;
     * the same asymmetry applies -- up at once, down only past the band.
     */
    public double socGuardHystC;

    public CurveConfig() {
        setDefaults();
    }

    /**
     * The measured curve, as shipped. A fresh install is correct without configuring
     * anything -- install, press the button, done.
     *
     * <h3>Why a preset is safe on a projector nobody has measured</h3>
     * This is a feedback controller closed on temperature, not a lookup table. It does not
     * need to know a unit's thermal plant: one that runs hotter simply gets more fan. The
     * measurements behind these numbers were needed to *predict* where a given unit lands,
     * not for the controller to work. Solved against the measured plant with a synthetic
     * penalty applied to every duty, Presentation at 24 C ambient settles at:
     * <pre>
     *   unit +0 C -> duty 38, 51.9 C     unit +6 C  -> duty 45, 54.9 C  (still inside)
     *   unit +4 C -> duty 43, 53.9 C     unit +15 C -> duty 61, 60.4 C  (loud; 15 C under
     *                                                                    the shutdown)
     * </pre>
     * So it absorbs about 6 C of unit-to-unit variation while holding the 55 C ceiling, and
     * past that it fails by getting louder rather than by running hot.
     *
     * <h3>Why one column for all three profiles</h3>
     * The optics care about temperature, not about which brightness mode produced it, and
     * the loop already finds whatever duty holds a given temperature. Identical columns
     * also mean a brightness change is not a tier change in duty terms, so
     * {@link FanCurve}'s deliberate "jump immediately on an upward tier change" never
     * fires: the largest single-tick change on an Eco -> Presentation switch drops from
     * 10 duty points to 1.
     *
     * <h3>The numbers</h3>
     * Flat at 30 (inaudible) to 48 C; ~2 duty points per C through the working range;
     * steepening to a backstop that reaches 83 by 70 C. Measured on the deployed unit:
     * settles at duty 38 / 51.4 C, zero duty changes in six minutes. See CURVE.md.
     */
    public void setDefaults() {
        int[] t = {42, 48, 55, 60, 65, 70};
        System.arraycopy(t, 0, tempC, 0, POINTS);

        // 42   48   55   60   65   70      <- degrees C
        int[] all = {30, 30, 45, 60, 74, 83};
        System.arraycopy(all, 0, duty[PROFILE_LOW], 0, POINTS);
        System.arraycopy(all, 0, duty[PROFILE_NORMAL], 0, POINTS);
        System.arraycopy(all, 0, duty[PROFILE_HIGH], 0, POINTS);

        // 0.8. Measured, not guessed: at 0.5 the duty dithered by a point at 24 C
        // ambient; at 0.8 the six-minute acceptance test on hardware saw the LED range
        // 51.42..51.91 C (+/-0.25 C) with ZERO duty changes.
        //
        // Briefly raised to 1.2 on the strength of a failing test, and put back. That
        // test starts the controller at duty 62 and counts transitions while it slews to
        // the curve's target; under the old curve 45.5 C WAS duty 62 so nothing moved,
        // and under this one it ramps 62 -> 30 and logs ~32 changes. It was measuring
        // settling, not dither sensitivity. Widening the band would not have helped, and
        // because the band is asymmetric it would have cost about a duty point of upward
        // bias on every machine, forever, to fix nothing.
        hysteresisC = 0.8;
        // Slow on purpose. One point every 4 s rising, every 8 s falling, is inaudible as
        // a change; the stock controller's 10-15 point step is not.
        slewUpPerSec = 0.25;
        slewDownPerSec = 0.12;
        idleDuty = 10;
        // 30 is "inaudible even up close" and is proven: held 12 minutes under the hottest
        // load the machine can produce, with no fan-stall shutdown.
        minDuty = 30;
        maxDuty = 83;

        // --- the SoC guard ---
        //
        // The fan is driven by the LED thermistor and cannot see the SoC at all. Switching
        // UHD processing on moves the SoC die about 11 C while moving the LED thermistor
        // half a degree, so a load that heats the video pipeline and not the light engine
        // is invisible to the curve. thermal_zone0 (pll) is the only zone with cooling
        // devices bound to it, at 75 C; that is where CPU and GPU frequency get throttled.
        //
        // Where these numbers come from, measured rather than argued. A 48-minute sweep
        // with UHD on -- six holds, duty 40 held first and last so load drift would show
        // as a failure to close, and it closed to within 0.5 C -- gives the die's plant
        // directly:
        //
        //     duty   30     40     50     62     83
        //     pll    69.0   64.2   61.6   57.8   54.4   C at 24 C ambient
        //
        // So from duty 40 the fan can take 6.4 C off the die by duty 62 and 9.8 C by full
        // speed. The last 21 points buy 3.4 C of that, which is why the ceiling is 62 and
        // not 83: 62 is already "way too loud" and the authority has largely run out.
        //
        // 2.0 duty points per degree. Against the measured authority of 0.26-0.32 C per
        // duty point through this range that is a loop gain of 0.52-0.64 -- under one, and
        // confirmed steady in simulation against the measured plant at 24 C and 30 C
        // ambient across every SoC load, inert to saturated, with zero duty changes.
        //
        // 70 C as the knee. The die sits at 64.2 C at the operating point, 10.8 C under
        // the 75 C trip, and does not reach 70 until roughly a 30-33 C room -- where it
        // then adds about one duty point. It must stay a backstop; a guard that fires in
        // ordinary use is just a second curve, and a noisy one.
        socGuardEnabled = true;
        socGuardStartC = 70;
        socGuardGainPerC = 2.0;
        socGuardMaxDuty = 62;
        socGuardHystC = 1.5;
    }

    /** Map an rgblevel reading onto a profile index, mirroring the stock packed-switch. */
    public static int profileForLevel(int rgblevel) {
        switch (rgblevel) {
            case 1:
            case 4:
                return PROFILE_LOW;
            case 2:
                return PROFILE_NORMAL;
            case 3:
                return PROFILE_HIGH;
            default:
                // Unknown or unreadable brightness tier. Use the hottest profile: fail safe
                // is high, and tier 3 is the one whose duties are highest at every knee.
                return PROFILE_HIGH;
        }
    }

    /**
     * Repair anything a user or a corrupt preferences file could have put in here.
     * Called on every load and after every edit, so the control loop can assume sanity.
     */
    public void sanitise() {
        for (int i = 0; i < POINTS; i++) {
            if (tempC[i] < 0) {
                tempC[i] = 0;
            }
            if (tempC[i] > 100) {
                tempC[i] = 100;
            }
        }
        // keep the knees strictly ascending, so interpolation can never divide by zero
        for (int i = 1; i < POINTS; i++) {
            if (tempC[i] <= tempC[i - 1]) {
                tempC[i] = tempC[i - 1] + 1;
            }
        }
        if (tempC[POINTS - 1] > 100) {
            for (int i = POINTS - 1; i >= 0; i--) {
                if (tempC[i] > 100 - (POINTS - 1 - i)) {
                    tempC[i] = 100 - (POINTS - 1 - i);
                }
            }
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
        for (int p = 0; p < PROFILES; p++) {
            for (int i = 0; i < POINTS; i++) {
                if (duty[p][i] < FanIo.MIN_DUTY) {
                    duty[p][i] = FanIo.MIN_DUTY;
                }
                if (duty[p][i] > FanIo.MAX_DUTY) {
                    duty[p][i] = FanIo.MAX_DUTY;
                }
            }
        }
        if (!(hysteresisC >= 0.0) || hysteresisC > 20.0) {
            hysteresisC = 1.5;
        }
        if (!(slewUpPerSec > 0.0) || slewUpPerSec > 100.0) {
            slewUpPerSec = 5.0;
        }
        if (!(slewDownPerSec > 0.0) || slewDownPerSec > 100.0) {
            slewDownPerSec = 1.0;
        }
        sanitiseGuard();
    }

    /**
     * Repair the SoC guard. Held to the same rule as the curve -- ascending knees, legal
     * duties -- plus one the curve does not need: the guard's duties must be
     * non-decreasing. A floor that sagged as the die got hotter would be worse than no
     * floor at all, because it would look like protection while removing it.
     */
    private void sanitiseGuard() {
        if (socGuardStartC < 0) {
            socGuardStartC = 0;
        }
        if (socGuardStartC > 120) {
            socGuardStartC = 120;
        }
        // A negative or absent gain is not "off" -- socGuardEnabled is off. Treat it as a
        // corrupt value and restore the default rather than silently disarming.
        if (!(socGuardGainPerC > 0.0) || socGuardGainPerC > 20.0) {
            socGuardGainPerC = 2.0;
        }
        if (socGuardMaxDuty < FanIo.MIN_DUTY) {
            socGuardMaxDuty = FanIo.MIN_DUTY;
        }
        if (socGuardMaxDuty > maxDuty) {
            socGuardMaxDuty = maxDuty;
        }
        if (!(socGuardHystC >= 0.0) || socGuardHystC > 20.0) {
            socGuardHystC = 1.5;
        }
    }

    /**
     * Extra duty points the guard asks for at a given SoC die temperature, on top of
     * whatever the curve already wants. Zero below the knee, rising linearly above it.
     * The caller applies {@link #socGuardMaxDuty}, because the cap is on the resulting
     * duty and not on the contribution.
     *
     * An unreadable sensor returns 0, not a high value. The guard is advisory: it exists
     * to shave a peak the LED thermistor cannot see, and the machine is no worse off
     * without it than it was before it existed. Failing a fan to 62 % because a monitoring
     * zone stopped responding would spend a lot of silence on nothing. The LED-driven
     * curve, which is the actual safety case, is unaffected either way.
     */
    public int socGuardBoost(double socCelsius) {
        if (!socGuardEnabled || Double.isNaN(socCelsius) || Double.isInfinite(socCelsius)) {
            return 0;
        }
        double over = socCelsius - socGuardStartC;
        if (over <= 0.0) {
            return 0;
        }
        int boost = (int) Math.round(over * socGuardGainPerC);
        return boost < 0 ? 0 : boost;
    }

    /**
     * The curve itself: piecewise-linear interpolation, flat outside the end knees.
     * Pure function of the config; no state, no I/O.
     */
    public int dutyAt(int profile, double celsius) {
        if (profile < 0 || profile >= PROFILES) {
            profile = PROFILE_HIGH;
        }
        int[] col = duty[profile];
        double v;
        if (celsius <= tempC[0]) {
            v = col[0];
        } else if (celsius >= tempC[POINTS - 1]) {
            v = col[POINTS - 1];
        } else {
            v = col[POINTS - 1];
            for (int i = 1; i < POINTS; i++) {
                if (celsius <= tempC[i]) {
                    double span = tempC[i] - tempC[i - 1];
                    double f = span <= 0 ? 1.0 : (celsius - tempC[i - 1]) / span;
                    v = col[i - 1] + f * (col[i] - col[i - 1]);
                    break;
                }
            }
        }
        int d = (int) Math.round(v);
        if (d < minDuty) {
            d = minDuty;
        }
        if (d > maxDuty) {
            d = maxDuty;
        }
        return FanIo.clampForUi(d);
    }

    /** Serialise to a single line, for SharedPreferences. */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append("v1");
        for (int i = 0; i < POINTS; i++) {
            sb.append(',').append(tempC[i]);
        }
        for (int p = 0; p < PROFILES; p++) {
            for (int i = 0; i < POINTS; i++) {
                sb.append(',').append(duty[p][i]);
            }
        }
        sb.append(',').append(hysteresisC);
        sb.append(',').append(slewUpPerSec);
        sb.append(',').append(slewDownPerSec);
        sb.append(',').append(idleDuty);
        sb.append(',').append(minDuty);
        sb.append(',').append(maxDuty);
        // Appended after the v1 fields on purpose: decode() accepts a line that is longer
        // than it needs, so a curve saved before the guard existed still loads, and picks
        // up the default guard rather than none.
        sb.append(',').append(socGuardEnabled ? 1 : 0);
        sb.append(',').append(socGuardStartC);
        sb.append(',').append(socGuardGainPerC);
        sb.append(',').append(socGuardMaxDuty);
        sb.append(',').append(socGuardHystC);
        return sb.toString();
    }

    /**
     * Parse a line produced by {@link #encode()}. Anything unparseable falls back to the
     * defaults; a missing or broken config must never leave the fan uncontrolled.
     */
    public static CurveConfig decode(String s) {
        CurveConfig c = new CurveConfig();
        if (s == null) {
            return c;
        }
        try {
            String[] f = s.split(",");
            int need = 1 + POINTS + PROFILES * POINTS + 6;
            if (f.length < need || !"v1".equals(f[0])) {
                return c;
            }
            int k = 1;
            for (int i = 0; i < POINTS; i++) {
                c.tempC[i] = Integer.parseInt(f[k++].trim());
            }
            for (int p = 0; p < PROFILES; p++) {
                for (int i = 0; i < POINTS; i++) {
                    c.duty[p][i] = Integer.parseInt(f[k++].trim());
                }
            }
            c.hysteresisC = Double.parseDouble(f[k++].trim());
            c.slewUpPerSec = Double.parseDouble(f[k++].trim());
            c.slewDownPerSec = Double.parseDouble(f[k++].trim());
            c.idleDuty = Integer.parseInt(f[k++].trim());
            c.minDuty = Integer.parseInt(f[k++].trim());
            c.maxDuty = Integer.parseInt(f[k++].trim());
            // The guard block is optional. A short line is a curve from before it existed,
            // not a corrupt one, and it keeps the defaults set in the constructor.
            if (f.length >= k + GUARD_FIELDS) {
                c.socGuardEnabled = Integer.parseInt(f[k++].trim()) != 0;
                c.socGuardStartC = Integer.parseInt(f[k++].trim());
                c.socGuardGainPerC = Double.parseDouble(f[k++].trim());
                c.socGuardMaxDuty = Integer.parseInt(f[k++].trim());
                c.socGuardHystC = Double.parseDouble(f[k].trim());
            }
        } catch (RuntimeException e) {
            c.setDefaults();
        }
        c.sanitise();
        return c;
    }
}
