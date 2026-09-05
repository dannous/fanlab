package com.daleygames.fanlab;

/**
 * What the control loop is doing. Pure Java so both the service and the host test can use it.
 */
public final class Mode {

    /**
     * Observe only. The app samples and logs but never writes fan_ctrl. This is the
     * state the app starts in, and it is the state that makes the plain variant a pure
     * measuring instrument.
     */
    public static final int OFF = 0;

    /** Hold one duty the user picked, and defend it against whatever else writes the node. */
    public static final int MANUAL = 1;

    /** Run the curve. */
    public static final int CURVE = 2;

    private Mode() {
    }

    public static String name(int mode) {
        switch (mode) {
            case MANUAL:
                return "MANUAL";
            case CURVE:
                return "CURVE";
            case OFF:
            default:
                return "OFF";
        }
    }

    public static boolean writes(int mode) {
        return mode == MANUAL || mode == CURVE;
    }
}
