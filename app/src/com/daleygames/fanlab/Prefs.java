package com.daleygames.fanlab;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The single source of truth for everything the user can change. The activity writes
 * here and pokes the service; the service reads here on every tick. That keeps the two
 * in sync with no binder, and means the settings survive a service restart, a reboot,
 * and being killed by the platform.
 */
public final class Prefs {

    private static final String FILE = "fanlab";

    private static final String K_MODE = "mode";
    private static final String K_MANUAL = "manual_duty";
    private static final String K_REASSERT = "reassert";
    private static final String K_CURVE = "curve";
    private static final String K_LOGGING = "logging";
    private static final String K_LOG_EVERY = "log_every_sec";
    private static final String K_AUTOSTART = "autostart";

    private Prefs() {
    }

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- mode ----

    /** Starts in OFF: the app must not touch the fan until the user asks it to. */
    public static int mode(Context c) {
        int m = get(c).getInt(K_MODE, Mode.OFF);
        return (m == Mode.MANUAL || m == Mode.CURVE) ? m : Mode.OFF;
    }

    public static void setMode(Context c, int mode) {
        get(c).edit().putInt(K_MODE, mode).apply();
    }

    // ---- manual duty ----

    /**
     * Default 55, which is the value the kernel driver itself settles at, so pressing
     * MANUAL without touching the slider changes nothing audible.
     */
    public static int manualDuty(Context c) {
        return FanIo.clampForUi(get(c).getInt(K_MANUAL, FanIo.KERNEL_DEFAULT_DUTY));
    }

    public static void setManualDuty(Context c, int duty) {
        get(c).edit().putInt(K_MANUAL, FanIo.clampForUi(duty)).apply();
    }

    // ---- re-assertion ----

    /**
     * When true the service re-checks fan_ctrl every second and rewrites it whenever
     * something else has changed it. That is what makes the slider win against the stock
     * controller (which rewrites on every whole-degree change, at most once per 15 s) and
     * against the kernel (which slams 55% after every resume and every stall).
     *
     * Default true: an audition where the value silently disappears is worse than useless.
     */
    public static boolean reassert(Context c) {
        return get(c).getBoolean(K_REASSERT, true);
    }

    public static void setReassert(Context c, boolean v) {
        get(c).edit().putBoolean(K_REASSERT, v).apply();
    }

    // ---- logging ----

    public static boolean logging(Context c) {
        return get(c).getBoolean(K_LOGGING, true);
    }

    public static void setLogging(Context c, boolean v) {
        get(c).edit().putBoolean(K_LOGGING, v).apply();
    }

    // ---- start on boot ----

    /**
     * Default false. Coming back after a reboot into a mode that writes the fan, with
     * nobody watching, is not something to do without being asked.
     */
    public static boolean autostart(Context c) {
        return get(c).getBoolean(K_AUTOSTART, false);
    }

    public static void setAutostart(Context c, boolean v) {
        get(c).edit().putBoolean(K_AUTOSTART, v).apply();
    }

    // ---- log interval ----

    /**
     * Seconds between routine telemetry rows. Events are always logged regardless.
     *
     * The loop samples at 1 Hz because the controller needs to, but the *log* does not:
     * a row a second is 0.34 MB an hour of "nothing changed", which fills a projector's
     * storage to no purpose and buries the interesting lines. 10 s keeps a month inside
     * the same budget that 1 Hz burns in three days, and loses nothing, because every
     * row where something actually happens is written anyway.
     *
     * Set to 1 while investigating something; the sweep and hold sessions ignore this
     * entirely and always log at full rate, since that is the measurement.
     */
    public static int logEverySec(Context c) {
        int v = get(c).getInt(K_LOG_EVERY, 10);
        return v < 1 ? 1 : (v > 3600 ? 3600 : v);
    }

    public static void setLogEverySec(Context c, int v) {
        get(c).edit().putInt(K_LOG_EVERY, v < 1 ? 1 : (v > 3600 ? 3600 : v)).apply();
    }

    // ---- the curve ----

    public static CurveConfig curve(Context c) {
        return CurveConfig.decode(get(c).getString(K_CURVE, null));
    }

    public static void setCurve(Context c, CurveConfig cfg) {
        cfg.sanitise();
        get(c).edit().putString(K_CURVE, cfg.encode()).apply();
    }

    public static void resetCurve(Context c) {
        get(c).edit().remove(K_CURVE).apply();
    }
}
