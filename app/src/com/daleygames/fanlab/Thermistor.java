package com.daleygames.fanlab;

/** SCN350 LED thermistor conversion, reproducing the platform framework's arithmetic exactly (BatteryService, services.vdex 1.7.0). */
public final class Thermistor {

    /** The node is a 12-bit ADC code, not millivolts. */
    public static final double FULL_SCALE = 4095.0;
    /** Nominal thermistor resistance at T0, ohms. */
    public static final double R0 = 100000.0;
    public static final double BETA = 4311.0;
    /** 1/T0 in kelvin^-1 (T0 = 298.15 K); the exact const-wide literal in the framework. */
    public static final double INV_T0 = 0.0033540164346805303;

    /** Lowest ADC code the conversion is defined for (0 gives Rt = 0 -> ln(0)). */
    public static final int ADC_MIN = 1;
    /** Highest ADC code the conversion is defined for (4095 divides by zero). */
    public static final int ADC_MAX = 4094;

    public static final int BAD_ADC = Integer.MIN_VALUE;

    private Thermistor() {
    }

    /** The framework's own parse: the first n-1 characters, Integer.parseInt. {@link #BAD_ADC} on failure. */
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

    /** Tolerant parse: the last run of digits, so a missing newline, CRLF or NUL padding still reads. */
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

    public static double celsius(int adc) {
        if (adc < ADC_MIN || adc > ADC_MAX) {
            return Double.NaN;
        }
        double rt = adc * R0 / (FULL_SCALE - adc);
        double kelvin = 1.0 / (Math.log(rt / R0) / BETA + INV_T0);
        double c = kelvin - 273.15 + 0.5;      // the +0.5 bias is the framework's own
        if (Double.isNaN(c) || Double.isInfinite(c)) {
            return Double.NaN;
        }
        return c;
    }

    /** What the stock ladder compares: truncation of the already +0.5-biased value. */
    public static int stockTmp(double celsius) {
        return (int) celsius;
    }

    /** Sanity gate: outside this range the caller must write {@link FanIo#FAIL_SAFE_DUTY}. */
    public static boolean plausible(double celsius) {
        return !Double.isNaN(celsius) && !Double.isInfinite(celsius)
                && celsius > -20.0 && celsius < 120.0;
    }
}
