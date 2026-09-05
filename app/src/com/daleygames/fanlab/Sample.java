package com.daleygames.fanlab;

/**
 * One second of telemetry. Immutable-ish value object shared by the CSV writer and the UI.
 * Pure Java.
 */
public final class Sample {

    public long epochMs;
    public String isoLocal = "";

    /** Raw contents of /sys/class/ledtemp/voltage, or null if unreadable. */
    public String adcRaw;
    /** Parsed ADC code, or {@link Thermistor#BAD_ADC}. */
    public int adc = Thermistor.BAD_ADC;
    /** Computed temperature, or NaN. */
    public double degC = Double.NaN;

    /** persist.sys.led.temperature as the framework last wrote it, or null. */
    public String propLedTemp;

    /** Read-back of fan_ctrl, or -1. */
    public int fanCtrl = -1;
    /** /sys/class/dlpc343x/rgblevel, or -1. */
    public int rgblevel = -1;
    /** /sys/class/dlpc343x/led_status, or -1. */
    public int ledStatus = -1;

    /** Profile the controller selected for this sample. */
    public int profile = CurveConfig.PROFILE_HIGH;
    /** OFF / MANUAL / CURVE. */
    public int mode = Mode.OFF;
    /** What the controller wanted, or -1 if it wanted nothing. */
    public int desired = -1;
    /** What was actually written this tick, or -1 if nothing was. */
    public int wrote = -1;
    /** Free text: failsafe reasons, resume events, mode changes. */
    public String note = "";

    /**
     * SoC die temperatures in degrees C -- pll, ddr, sar -- or NaN if unreadable.
     * NaN rather than a sentinel so a missing reading is blank in the CSV instead of
     * being mistaken for a real value.
     */
    public double[] socC = {Double.NaN, Double.NaN, Double.NaN};

    public String toCsv() {
        StringBuilder sb = new StringBuilder(140);
        sb.append(epochMs).append(',');
        sb.append(isoLocal).append(',');
        sb.append(adc == Thermistor.BAD_ADC ? "" : Integer.toString(adc)).append(',');
        sb.append(Double.isNaN(degC) ? "" : fmt2(degC)).append(',');
        sb.append(propLedTemp == null ? "" : CsvLogger.q(propLedTemp)).append(',');
        sb.append(fanCtrl < 0 ? "" : Integer.toString(fanCtrl)).append(',');
        sb.append(rgblevel < 0 ? "" : Integer.toString(rgblevel)).append(',');
        sb.append(ledStatus < 0 ? "" : Integer.toString(ledStatus)).append(',');
        sb.append(CurveConfig.PROFILE_NAMES[
                profile < 0 || profile >= CurveConfig.PROFILES ? CurveConfig.PROFILE_HIGH : profile]
                .replace(" / ", "/")).append(',');
        sb.append(Mode.name(mode)).append(',');
        sb.append(desired < 0 ? "" : Integer.toString(desired)).append(',');
        sb.append(wrote < 0 ? "" : Integer.toString(wrote)).append(',');
        sb.append(CsvLogger.q(note));
        for (int i = 0; i < socC.length; i++) {
            sb.append(',').append(Double.isNaN(socC[i]) ? "" : fmt1(socC[i]));
        }
        return sb.toString();
    }

    /** Two decimal places without pulling in a Formatter or a Locale. */
    public static String fmt2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "";
        }
        long scaled = Math.round(v * 100.0);
        boolean neg = scaled < 0;
        if (neg) {
            scaled = -scaled;
        }
        long whole = scaled / 100;
        long frac = scaled % 100;
        StringBuilder sb = new StringBuilder();
        if (neg) {
            sb.append('-');
        }
        sb.append(whole).append('.');
        if (frac < 10) {
            sb.append('0');
        }
        sb.append(frac);
        return sb.toString();
    }

    /** One decimal place. */
    public static String fmt1(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "--";
        }
        long scaled = Math.round(v * 10.0);
        boolean neg = scaled < 0;
        if (neg) {
            scaled = -scaled;
        }
        StringBuilder sb = new StringBuilder();
        if (neg) {
            sb.append('-');
        }
        sb.append(scaled / 10).append('.').append(scaled % 10);
        return sb.toString();
    }
}
