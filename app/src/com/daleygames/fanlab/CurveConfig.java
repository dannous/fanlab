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
            c.maxDuty = Integer.parseInt(f[k].trim());
        } catch (RuntimeException e) {
            c.setDefaults();
        }
        c.sanitise();
        return c;
    }
}
