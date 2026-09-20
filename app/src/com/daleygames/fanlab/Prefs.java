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

    // ---- mode ----

    /**
     * Starts in OFF: the app must not touch the fan until the user asks it to.
     *
     * Anything that is not a mode this build knows reads as OFF rather than as itself, so a
     * preferences file written by a newer build cannot leave the loop dispatching on a
     * number none of its branches match -- which would be a mode that writes nothing while
     * {@link Mode#writes} said it did.
     */
    public static int mode(Context c) {
        int m = get(c).getInt(K_MODE, Mode.OFF);
        return (m == Mode.MANUAL || m == Mode.CURVE || m == Mode.LINEAR) ? m : Mode.OFF;
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

    // ---- the linear ceiling ----

    /**
     * LINEAR's own config. Stored separately from the curve, and deliberately not reset by
     * {@code --ez reset}: the two modes are auditioned against each other, so resetting the
     * curve to compare it against a ceiling that has just moved as well would answer nothing.
     */
    public static LinearConfig linear(Context c) {
        return LinearConfig.decode(get(c).getString(K_LINEAR, null));
    }

    public static void setLinear(Context c, LinearConfig cfg) {
        cfg.sanitise();
        get(c).edit().putString(K_LINEAR, cfg.encode()).apply();
    }

    // ---- the LED drive override ----

    /**
     * The four LED drive levels, Super Eco / Eco / Normal / Presentation. Stock by default,
     * and a stored line that does not parse is stock too, for the reason
     * {@link LedDrive.Config#decode} gives.
     */
    public static LedDrive.Config ledDrive(Context c) {
        return LedDrive.Config.decode(get(c).getString(K_LEDDRIVE, null));
    }

    public static void setLedDrive(Context c, LedDrive.Config cfg) {
        cfg.sanitise();
        get(c).edit().putString(K_LEDDRIVE, cfg.encode()).apply();
        alignCurveToDrive(c);
    }

    /**
     * Whether the override is switched on at all. Default false: driving the LEDs above
     * what the brightness mode asks for is something to do deliberately, not something an
     * install does. On with a stock table is the same as off -- there is nothing to apply
     * -- and the loop treats it that way.
     */
    public static boolean ledDriveOn(Context c) {
        return get(c).getBoolean(K_LEDDRIVE_ON, false);
    }

    public static void setLedDriveOn(Context c, boolean v) {
        get(c).edit().putBoolean(K_LEDDRIVE_ON, v).apply();
        alignCurveToDrive(c);
    }

    /** Back to stock and off, as {@code --ez reset} does alongside the curve. */
    public static void resetLedDrive(Context c) {
        get(c).edit().remove(K_LEDDRIVE).remove(K_LEDDRIVE_ON).apply();
        alignCurveToDrive(c);
    }

    /**
     * Move the stored curve into the preset family the override now allows, keeping the
     * rung. <b>The gate that stops a curve running at a drive level it was not drawn for.</b>
     *
     * Called from all three writers above rather than from {@link #setLedDriveOn} alone,
     * because all three can change the answer to {@link #ledBoostOn} -- switching the
     * override off, and putting the stock table back under an override that is still on, are
     * the same thing to the loop and must be the same thing here. Asking
     * {@link #ledBoostOn} rather than the raw flag is what makes them agree: "on with a stock
     * table" is off everywhere else in this app, so a curve must not be moved to the Bright
     * family for it.
     *
     * The reason it is a move and not a warning is measured. Quiet with the drive at 90 in a
     * 28 C room settles at 58 C where Bright Quiet settles at 56 -- past the margin the
     * {@link LedDrive#DEFAULT_TRIP_C} override trip leaves itself, and the trip drops the
     * drive without saying so, so the symptom is the picture reverting to stock brightness on
     * its own. A pairing that fails that way is worth making unreachable rather than
     * discouraging.
     *
     * A hand-edited curve is in neither family and is left exactly as it is -- see
     * {@link CurveConfig#curveForDrive}. Nothing here can tell the owner that happened, so
     * the screen and the broadcast reply both report the loaded curve as Custom, which is
     * what it is.
     */
    private static void alignCurveToDrive(Context c) {
        String now = curve(c).encode();
        String want = CurveConfig.curveForDrive(now, ledBoostOn(c));
        if (!want.equals(now)) {
            get(c).edit().putString(K_CURVE, want).apply();
        }
    }

    /**
     * Is the override asking for anything at all? The switch on <i>and</i> a table that is
     * not the kernel's own.
     *
     * The one question every caller actually has, in one place: the service's coupling rule,
     * the LINEAR ceiling promotion, the screen and the broadcast reply all turn on it, and
     * "on with a stock table" is the state where they would otherwise disagree -- the loop
     * treats it as off, so everything else must too.
     *
     * Deliberately the <i>setting</i>, not what is on the hardware. {@link LedDrive} drops
     * the override on its own ceiling trip and puts it back when the brightness mode
     * changes, and a ceiling that also moved the LINEAR ceiling twice in a minute would be
     * a controller chasing itself.
     */
    public static boolean ledBoostOn(Context c) {
        return ledDriveOn(c) && !ledDrive(c).isStock();
    }

    // ---- stated room temperature ----

    /**
     * The room temperature the owner typed in, degrees C, or 0 for "not stated".
     *
     * Ambient is the one quantity in the log that cannot be derived from the log.
     * {@code ambient = degC - rise(duty)} needs the plant table, and the plant table is
     * exactly what field data is collected to check, so the inference argues in a circle.
     * The app takes its own reading at every power-on; this is the independent number to
     * check that against, and the first field log had to have it supplied verbally.
     *
     * <b>0 means not stated, and is written to the CSV as a blank rather than a zero.</b>
     * A 0 C living room is not a reading anyone will take, and the alternative -- a
     * separate "is it set" flag -- is a second value to fall out of step with the first.
     */
    public static int roomC(Context c) {
        int v = get(c).getInt(K_ROOM_C, 0);
        return (v < 0 || v > 40) ? 0 : v;
    }

    public static void setRoomC(Context c, int v) {
        get(c).edit().putInt(K_ROOM_C, (v < 0 || v > 40) ? 0 : v).apply();
    }

    // ---- session identity ----

    /**
     * Which run this is. Sessions previously had to be reconstructed from
     * {@code epoch_ms} gaps longer than two minutes, which is a heuristic wearing a
     * measurement's clothes: a pause in the middle of a session and a real restart look
     * the same, and neither is distinguishable from a stick pulled for a minute.
     *
     * The counter only ever goes up, so it also orders the runs. It is not a boot id:
     * two service starts inside one boot are two sessions, which is the boundary that
     * matters here, because it is the point at which the controller adopts the duty again
     * from whatever it finds. The {@code resync@...:service_start} note on the first row
     * says <i>why</i> the boundary is there; this says <i>which</i> run, so the two
     * corroborate rather than duplicate.
     *
     * {@code pm clear} resets it to 1. {@code epoch_ms} tells the two apart.
     */
    public static int session(Context c) {
        return get(c).getInt(K_SESSION, 0);
    }

    /**
     * Claim the next session number. Called once per service start.
     *
     * {@code commit} rather than {@code apply}: two runs sharing one number would defeat
     * the whole point, and a process killed between the increment and the asynchronous
     * write is exactly how that happens. It is one blocking write at service start,
     * alongside the storage scan that already happens there.
     */
    public static int nextSession(Context c) {
        int next = session(c) + 1;
        get(c).edit().putInt(K_SESSION, next).commit();
        return next;
    }

    // ---- when the light engine was last seen on ----

    /**
     * The last instant this app saw {@code led_status} non-zero: wall clock, the
     * monotonic clock, and the wall-clock instant of the boot both were taken in.
     *
     * All three, because the pair alone cannot tell a power-down from standby.
     * {@code elapsedRealtime} restarts at every boot, so it is only a witness within the
     * boot it was recorded in -- and inside that boot it is the better witness, since no
     * clock correction can move it. The boot stamp is what says which case applies.
     * {@link #roomC} explains why the resulting off-duration is worth this much trouble.
     */
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

    // ---- presets ----

    /**
     * A preset is stored as the curve itself and nothing else, so there is no second value
     * to fall out of step with the first. Choosing one is an ordinary curve write.
     */
    public static void setPreset(Context c, int i) {
        setCurve(c, CurveConfig.preset(i));
    }

    /**
     * Which preset is loaded, or {@link CurveConfig#PRESET_CUSTOM} for a hand-edited
     * curve. Read back through {@link #curve} rather than off the raw string, so a fresh
     * install -- which has no curve stored at all -- reports the default as what it is.
     */
    public static int preset(Context c) {
        return CurveConfig.presetOf(curve(c).encode());
    }
}
