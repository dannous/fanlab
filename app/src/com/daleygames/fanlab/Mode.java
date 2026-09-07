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

    /**
     * Hold a temperature ceiling and let the fan speed float, one duty point at a time.
     *
     * A separate mode rather than a replacement for {@link #CURVE}: the curve is proven
     * over thirty-six hours in the field and the safety case rests on it, and this has no
     * field evidence at all yet. Both are installed so they can be compared by ear.
     *
     * The two are opposites in what they let move, which is the whole reason to have both.
     * CURVE holds a fan speed and lets the temperature float, so the light engine tracks
     * the room: across the shelf the duty is pinned at 38 % while the light engine runs
     * 50.1 C at 22 C ambient, 52.1 C at 24 and 54.1 C at 26, reaching 56.6 C at 30 once it
     * has left the shelf. LINEAR holds the temperature and lets the fan float, so it keeps
     * 52 C in a hot room and pays for it in duty: 42 % at 26 C and 57 % at 30 C. See
     * {@link FanLinear}.
     */
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

    /**
     * Is this app the machine's temperature controller in this mode?
     *
     * The distinction {@link #writes} cannot make. MANUAL writes the node but has no
     * temperature logic at all, so the stock ladder has to stay armed to supervise it;
     * CURVE and LINEAR both respond to temperature, so the ladder stands down for them and
     * autostart is forced on. Everything that turns on "are we the controller" -- the kill
     * switch, the exclusive-control column -- asks this rather than listing the two modes
     * again in its own words.
     */
    public static boolean controls(int mode) {
        return mode == CURVE || mode == LINEAR;
    }
}
