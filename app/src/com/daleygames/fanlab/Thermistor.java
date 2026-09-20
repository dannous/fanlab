package com.daleygames.fanlab;

/**
 * The SCN350 LED thermistor conversion, reproducing the platform framework's arithmetic
 * exactly (BatteryService.shutdownIfOverTemperature in services.vdex, 1.7.0).
 *
 * <pre>
 *   adc  = int(first n-1 chars of /sys/class/ledtemp/voltage)
 *   Rt   = adc * 100000.0 / (4095 - adc)
 *   T_K  = 1.0 / ( ln(Rt/100000)/4311.0 + 0.0033540164346805303 )
 *   degC = T_K - 273.15 + 0.5              // note the +0.5 rounding bias, it is in the framework
 * </pre>
 *
 * Pure Java: no android.* imports, so it is compiled into both the APK and the host test.
 */
public final class Thermistor {

    /** The node is a 12-bit ADC code, not millivolts. */
    public static final double FULL_SCALE = 4095.0;
    /** Nominal thermistor resistance at T0. */
    public static final double R0 = 100000.0;
    /** Beta. */
    public static final double BETA = 4311.0;
    /** 1/T0 in kelvin^-1 (T0 = 298.15 K); the exact const-wide literal in the framework. */
    public static final double INV_T0 = 0.0033540164346805303;

    /** Lowest ADC code the conversion is defined for (0 gives Rt = 0 -> ln(0)). */
    public static final int ADC_MIN = 1;
    /** Highest ADC code the conversion is defined for (4095 divides by zero). */
    public static final int ADC_MAX = 4094;

    /** Sentinel returned by the parsers when nothing usable was found. */
    public static final int BAD_ADC = Integer.MIN_VALUE;

    private Thermistor() {
    }

    /**
     * A literal reproduction of the framework's parse: take the first n-1 characters and
     * Integer.parseInt them. Only correct for content that ends in exactly one non-digit
     * (the real node always ends in '\n'). Kept so the unit test can prove we agree with
     * the framework byte for byte.
     *
     * @return the ADC code, or {@link #BAD_ADC}. Never throws.
     */
    public static int parseAdcFrameworkExact(String raw) {
        if (raw == null || raw.length() < 2) {
            return BAD_ADC;
        }
        try {
            return Integer.parseInt(raw.substring(0, raw.length() - 1));
        } catch (RuntimeException e) {
            return BAD_ADC;
        }
    }

    /**
     * Tolerant parse: strips trailing whitespace/NUL and takes the last run of digits.
     * Agrees with {@link #parseAdcFrameworkExact} for every well-formed node read, and
     * additionally survives a missing newline, CRLF, or trailing NUL padding.
     *
     * @return the ADC code, or {@link #BAD_ADC}. Never throws.
     */
    public static int parseAdc(String raw) {
        if (raw == null) {
            return BAD_ADC;
        }
        int end = raw.length();
        while (end > 0 && !isDigit(raw.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && isDigit(raw.charAt(start - 1))) {
            start--;
        }
        if (start == end || end - start > 9) {
            return BAD_ADC;
        }
        try {
            return Integer.parseInt(raw.substring(start, end));
        } catch (RuntimeException e) {
            return BAD_ADC;
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * ADC code to degrees Celsius, using the framework's exact arithmetic including the
     * +0.5 bias.
     *
     * @return degrees C, or NaN if the code is outside the domain of the conversion.
     */
    public static double celsius(int adc) {
        if (adc < ADC_MIN || adc > ADC_MAX) {
            return Double.NaN;
        }
        double rt = adc * R0 / (FULL_SCALE - adc);
        double kelvin = 1.0 / (Math.log(rt / R0) / BETA + INV_T0);
        double c = kelvin - 273.15 + 0.5;
        if (Double.isNaN(c) || Double.isInfinite(c)) {
            return Double.NaN;
        }
        return c;
    }

    /**
     * The value the stock ladder compares against: {@code new Double(t).intValue()},
     * i.e. truncation toward zero of the already +0.5-biased value.
     */
    public static int stockTmp(double celsius) {
        return (int) celsius;
    }

    /**
     * Sanity gate. A reading outside this range means the sensor or the parse is wrong,
     * and the caller must fail safe (write {@link FanIo#FAIL_SAFE_DUTY}) rather than trust it.
     */
    public static boolean plausible(double celsius) {
        return !Double.isNaN(celsius) && !Double.isInfinite(celsius)
                && celsius > -20.0 && celsius < 120.0;
    }
}
