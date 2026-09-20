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

    /**
     * Cooling-device states, in {@link Sysfs#COOLING_DEVICES} order; -1 if unreadable.
     * Any value above zero means the governor is throttling, now.
     */
    public int[] throttle = {-1, -1, -1, -1};

    /**
     * Which run this row belongs to, from {@link Prefs#session}; -1 before it is known.
     * Segmentation stops being a guess about {@code epoch_ms} gaps.
     */
    public int session = -1;

    /**
     * How long the light engine had been off before it came on, milliseconds, or -1 on
     * every row that is not the first sample of a power-on.
     *
     * Non-blank marks the row as an ambient measurement: {@code degC} and
     * {@code soc_pll_c} on this same row <i>are</i> the power-on readings, so they are
     * not repeated here -- the marker is a column on the row precisely so that they do
     * not have to be. The note says the same thing in words, for grepping.
     */
    public long offMs = -1L;

    /** The stated room temperature, or 0 for "not stated", which logs as a blank. */
    public int roomC;

    /**
     * Was this app alone on {@code fan_ctrl} for this row? 1 yes, 0 no, -1 not
     * determinable, which logs as a blank rather than as a 0.
     */
    public int exclusive = -1;

    /** Is the controller still converging on the curve? 1, 0, or -1 for "not driving". */
    public int catchingUp = -1;

    /** Milliseconds since the commanded duty last moved, or -1 if nothing is commanded. */
    public long dutyHoldMs = -1L;

    /**
     * The LED drive level this app has put on the hardware, percent of the driver's
     * maximum for channels 0/2/3, or -1 when the stock table is in force -- which logs as
     * a blank, so a row with nothing here was measured under stock LED drive. Channel 1
     * follows from it through {@link LedDrive#redFor}. A column rather than a note
     * because the thermal cost of the override is the thing the log has to be able to
     * separate from everything else, on every row and not only on the edges.
     */
    public int ledDrive = -1;

    /** Is the thermal governor actively throttling anything? */
    public boolean throttling() {
        for (int i = 0; i < throttle.length; i++) {
            if (throttle[i] > 0) {
                return true;
            }
        }
        return false;
    }

    /** The states as a log note, e.g. {@code cpufreq=2 gpufreq=1}; empty when idle. */
    public String throttleNote() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < throttle.length && i < Sysfs.COOLING_NAMES.length; i++) {
            if (throttle[i] > 0) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(Sysfs.COOLING_NAMES[i]).append('=').append(throttle[i]);
            }
        }
        return sb.toString();
    }

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
        for (int i = 0; i < throttle.length; i++) {
            sb.append(',').append(throttle[i] < 0 ? "" : Integer.toString(throttle[i]));
        }
        sb.append(',').append(session < 0 ? "" : Integer.toString(session));
        sb.append(',').append(offMs < 0 ? "" : Long.toString(offMs / 1000L));
        sb.append(',').append(roomC <= 0 ? "" : Integer.toString(roomC));
        sb.append(',').append(exclusive < 0 ? "" : Integer.toString(exclusive));
        sb.append(',').append(catchingUp < 0 ? "" : Integer.toString(catchingUp));
        sb.append(',').append(dutyHoldMs < 0 ? "" : Long.toString(dutyHoldMs / 1000L));
        sb.append(',').append(ledDrive < 0 ? "" : Integer.toString(ledDrive));
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
