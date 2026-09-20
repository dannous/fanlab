package com.daleygames.fanlab;

/** What the control loop is doing. */
public final class Mode {

    /** Observe only: the app samples and logs but never writes fan_ctrl. */
    public static final int OFF = 0;

    /** Hold one duty the user picked, and defend it against whatever else writes the node. */
    public static final int MANUAL = 1;

    /** Run the curve. */
    public static final int CURVE = 2;

    /** Hold a temperature ceiling and let the fan speed float; see {@link FanLinear}. */
    public static final int LINEAR = 3;

    private Mode() {
    }

    public static String name(int mode) {
        switch (mode) {
            case MANUAL:
                return "MANUAL";
            case CURVE:
                return "CURVE";
            case LINEAR:
                return "LINEAR";
            case OFF:
            default:
                return "OFF";
        }
    }

    public static boolean writes(int mode) {
        return mode == MANUAL || mode == CURVE || mode == LINEAR;
    }

    /** Is this app the temperature controller? CURVE and LINEAR stand the stock ladder down and force autostart; MANUAL does not. */
    public static boolean controls(int mode) {
        return mode == CURVE || mode == LINEAR;
    }
}
