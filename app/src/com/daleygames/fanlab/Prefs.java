package com.daleygames.fanlab;

import android.content.Context;
import android.content.SharedPreferences;

/** The single source of truth for everything the user can change; the activity writes here and the service reads it on every tick. */
public final class Prefs {

    private static final String FILE = "fanlab";

    private static final String K_MODE = "mode";
    private static final String K_MANUAL = "manual_duty";
    private static final String K_REASSERT = "reassert";
    private static final String K_CURVE = "curve";
    private static final String K_LINEAR = "linear";
    private static final String K_LEDDRIVE = "leddrive";
    private static final String K_LEDDRIVE_ON = "leddrive_on";
    private static final String K_LOGGING = "logging";
    private static final String K_LOG_EVERY = "log_every_sec";
    private static final String K_AUTOSTART = "autostart";
    private static final String K_ROOM_C = "room_c";
    private static final String K_SESSION = "session";
    private static final String K_ENGINE_ON_WALL = "engine_on_wall_ms";
    private static final String K_ENGINE_ON_MONO = "engine_on_mono_ms";
    private static final String K_ENGINE_ON_BOOT = "engine_on_boot_ms";

    private Prefs() {
    }

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Starts in OFF: the app must not touch the fan until asked, and a stored mode this build does not know reads as OFF. */
    public static int mode(Context c) {
        int m = get(c).getInt(K_MODE, Mode.OFF);
        return (m == Mode.MANUAL || m == Mode.CURVE || m == Mode.LINEAR) ? m : Mode.OFF;
    }

    public static void setMode(Context c, int mode) {
        get(c).edit().putInt(K_MODE, mode).apply();
    }

    /** Default 55, the value the kernel driver itself settles at, so pressing MANUAL changes nothing audible. */
    public static int manualDuty(Context c) {
        return FanIo.clampForUi(get(c).getInt(K_MANUAL, FanIo.KERNEL_DEFAULT_DUTY));
    }

    public static void setManualDuty(Context c, int duty) {
        get(c).edit().putInt(K_MANUAL, FanIo.clampForUi(duty)).apply();
    }

    /** Re-check fan_ctrl every second and rewrite it whenever something else has changed it. Default true. */
    public static boolean reassert(Context c) {
        return get(c).getBoolean(K_REASSERT, true);
    }

    public static void setReassert(Context c, boolean v) {
        get(c).edit().putBoolean(K_REASSERT, v).apply();
    }

    public static boolean logging(Context c) {
        return get(c).getBoolean(K_LOGGING, true);
    }

    public static void setLogging(Context c, boolean v) {
        get(c).edit().putBoolean(K_LOGGING, v).apply();
    }

    /** Default false: coming back after a reboot into a mode that writes the fan is not done unasked. */
    public static boolean autostart(Context c) {
        return get(c).getBoolean(K_AUTOSTART, false);
    }

    public static void setAutostart(Context c, boolean v) {
        get(c).edit().putBoolean(K_AUTOSTART, v).apply();
    }

    /** Seconds between routine telemetry rows; events are logged regardless, and sweep and hold sessions always log at full rate. */
    public static int logEverySec(Context c) {
        int v = get(c).getInt(K_LOG_EVERY, 10);
        return v < 1 ? 1 : (v > 3600 ? 3600 : v);
    }

    public static void setLogEverySec(Context c, int v) {
        get(c).edit().putInt(K_LOG_EVERY, v < 1 ? 1 : (v > 3600 ? 3600 : v)).apply();
    }

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

    /** LINEAR's own config, stored separately from the curve and deliberately not reset by {@code --ez reset}. */
    public static LinearConfig linear(Context c) {
        return LinearConfig.decode(get(c).getString(K_LINEAR, null));
    }

    public static void setLinear(Context c, LinearConfig cfg) {
        cfg.sanitise();
        get(c).edit().putString(K_LINEAR, cfg.encode()).apply();
    }

    /** The four LED drive levels, Super Eco / Eco / Normal / Presentation. Stock by default, and so is a stored line that does not parse. */
    public static LedDrive.Config ledDrive(Context c) {
        return LedDrive.Config.decode(get(c).getString(K_LEDDRIVE, null));
    }

    public static void setLedDrive(Context c, LedDrive.Config cfg) {
        cfg.sanitise();
        get(c).edit().putString(K_LEDDRIVE, cfg.encode()).apply();
        alignCurveToDrive(c);
    }

    /** Whether the override is switched on at all. Default false; on with a stock table is the same as off. */
    public static boolean ledDriveOn(Context c) {
        return get(c).getBoolean(K_LEDDRIVE_ON, false);
    }

    public static void setLedDriveOn(Context c, boolean v) {
        get(c).edit().putBoolean(K_LEDDRIVE_ON, v).apply();
        alignCurveToDrive(c);
    }

    public static void resetLedDrive(Context c) {
        get(c).edit().remove(K_LEDDRIVE).remove(K_LEDDRIVE_ON).apply();
        alignCurveToDrive(c);
    }

    /**
     * Move the stored curve into the preset family the override now allows, keeping the rung.
     * Called from every writer above, because all three can change {@link #ledBoostOn}.
     */
    private static void alignCurveToDrive(Context c) {
        String now = curve(c).encode();
        String want = CurveConfig.curveForDrive(now, ledBoostOn(c));
        if (!want.equals(now)) {
            get(c).edit().putString(K_CURVE, want).apply();
        }
    }

    /** Is the override asking for anything at all? The switch on, and a table that is not the kernel's own. */
    public static boolean ledBoostOn(Context c) {
        return ledDriveOn(c) && !ledDrive(c).isStock();
    }

    /** The room temperature the owner typed in, degrees C, or 0 for "not stated", which the CSV writes as a blank. */
    public static int roomC(Context c) {
        int v = get(c).getInt(K_ROOM_C, 0);
        return (v < 0 || v > 40) ? 0 : v;
    }

    public static void setRoomC(Context c, int v) {
        get(c).edit().putInt(K_ROOM_C, (v < 0 || v > 40) ? 0 : v).apply();
    }

    /** Which run this is. The counter only ever goes up, so it also orders the runs. */
    public static int session(Context c) {
        return get(c).getInt(K_SESSION, 0);
    }

    /** Claim the next session number, once per service start; commit rather than apply, so two runs cannot share one. */
    public static int nextSession(Context c) {
        int next = session(c) + 1;
        get(c).edit().putInt(K_SESSION, next).commit();
        return next;
    }

    /** The last instant this app saw led_status non-zero: wall clock, the monotonic clock, and the boot instant both were taken in. */
    public static long engineOnWallMs(Context c) {
        return get(c).getLong(K_ENGINE_ON_WALL, 0L);
    }

    public static long engineOnMonoMs(Context c) {
        return get(c).getLong(K_ENGINE_ON_MONO, 0L);
    }

    public static long engineOnBootMs(Context c) {
        return get(c).getLong(K_ENGINE_ON_BOOT, 0L);
    }

    /** Record all three together, so a half-written stamp cannot be read back. */
    public static void setEngineOn(Context c, long wallMs, long monoMs, long bootWallMs) {
        get(c).edit()
                .putLong(K_ENGINE_ON_WALL, wallMs)
                .putLong(K_ENGINE_ON_MONO, monoMs)
                .putLong(K_ENGINE_ON_BOOT, bootWallMs)
                .apply();
    }

    public static void setPreset(Context c, int i) {
        setCurve(c, CurveConfig.preset(i));
    }

    /** Which preset is loaded, or {@link CurveConfig#PRESET_CUSTOM} for a hand-edited curve. */
    public static int preset(Context c) {
        return CurveConfig.presetOf(curve(c).encode());
    }
}
