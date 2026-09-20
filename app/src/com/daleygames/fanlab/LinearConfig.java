package com.daleygames.fanlab;

/** The temperature ceiling {@link FanLinear} holds, and the three intervals that decide how it gets there. */
public final class LinearConfig {

    /** Fields in an encoded {@code l3} line, including the version tag. */
    public static final int FIELDS = 10;

    /** Fields in the superseded single-interval {@code l1} line. */
    public static final int FIELDS_L1 = 6;

    /** Fields in the {@code l2} line, which had the three intervals but no trend gate. */
    public static final int FIELDS_L2 = 9;

    /** Bounds on {@link #ceilingC}, degrees C. The top stops well short of the 75 C shutdown. */
    public static final double MIN_CEILING_C = 35.0;
    public static final double MAX_CEILING_C = 70.0;

    /** Bounds on every step interval, milliseconds. */
    public static final long MIN_STEP_MS = 1000L;
    public static final long MAX_STEP_MS = 120000L;

    /** Bounds on {@link #nearC}, degrees C; zero would disable the schedule. */
    public static final double MIN_NEAR_C = 0.0;
    public static final double MAX_NEAR_C = 10.0;

    /** Bounds on {@link #trendWindowS}, seconds; zero switches the trend gate off entirely. */
    public static final int MIN_TREND_S = 0;
    public static final int MAX_TREND_S = 300;

    /** Sample-to-sample standard deviation of the LED thermistor, degrees C, measured from a flat stretch of the field log. */
    public static final double SENSOR_NOISE_SD_C = 0.078;

    /** The stock default ceiling, degrees C, and the value {@link #promoteForBoost} treats as untouched. */
    public static final double DEFAULT_CEILING_C = 52.0;

    /** The default ceiling while the LED drive override is on, degrees C. Inferred by scaling the measured plant, not measured. */
    public static final double BOOST_CEILING_C = 54.0;

    /** The temperature the light engine is held at, degrees C. */
    public double ceilingC;

    /**
     * Milliseconds between decisions while at or above the ceiling. 5 s is a fifth of the rate
     * measured building a growing limit cycle; do not shorten it without repeating the step test.
     */
    public long upStepMs;

    /**
     * Milliseconds between decisions while falling and within {@link #nearC} of the ceiling.
     * 60 s is the only decay rate measured here that the owner did not hear; do not shorten it
     * without an ear check.
     */
    public long downStepMs;

    /** Milliseconds between decisions while falling with more than {@link #nearC} of headroom. Sanitised to be no slower than {@link #downStepMs}. */
    public long downFastMs;

    /** How close to the ceiling counts as close, degrees C. It sets how often the walk steps; it is not a hold zone. */
    public double nearC;

    /**
     * Seconds of temperature history the trend gate fits a slope over; 0 disables it. The gate
     * withholds a step while the temperature is already moving the right way, and 90 s is long
     * enough to beat the sensor noise floor.
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

    /** Repair anything a user or a corrupt preferences file could have put in here. Called on every load and after every edit. */
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
     * Raise an untouched default ceiling to {@link #BOOST_CEILING_C} while the LED drive override
     * is on. Applied as the config is loaded and never written to preferences, so switching the
     * override off puts the ceiling back by itself.
     */
    public static boolean promoteForBoost(LinearConfig cfg, boolean boostOn) {
        if (cfg == null || !boostOn) {
            return false;
        }
        // Half a tenth: ceilingC is snapped to a tenth on every load, so this is exact, not a tolerance.
        if (Math.abs(cfg.ceilingC - DEFAULT_CEILING_C) > 0.05) {
            return false;
        }
        cfg.ceilingC = BOOST_CEILING_C;
        return true;
    }

    /** To a tenth of a degree: finer is false precision, and an {@code --ef} float would not round-trip. */
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
     * Parse a line produced by {@link #encode()}; anything unparseable falls back to the defaults.
     * An {@code l1} line's single interval maps to {@link #upStepMs} and the decay intervals take theirs.
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
                // l2 predates the trend gate; the default is taken rather than 0.
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
