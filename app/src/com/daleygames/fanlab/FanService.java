package com.daleygames.fanlab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The 1 Hz sampler and the fan controller.
 *
 * Foreground service, START_STICKY, and no wakelock anywhere: if the projector suspends the
 * loop stops with it, so every tick re-reads {@code fan_ctrl} and rewrites when reality has
 * drifted from intent rather than assuming a write stuck.
 *
 * Safety invariants enforced here:
 * <ul>
 *   <li>the whole tick body is inside try/catch(Throwable); the catch writes
 *       {@link FanIo#FAIL_SAFE_DUTY};</li>
 *   <li>three consecutive implausible temperature readings in CURVE mode write
 *       {@link FanIo#FAIL_SAFE_DUTY} and latch until a good reading returns;</li>
 *   <li>even with re-assertion switched off, a read-back <i>lower</i> than the intended duty is
 *       always corrected - the app will leave the fan higher than it meant to, never lower;</li>
 *   <li>stopping the service while it was driving writes {@link FanIo#FAIL_SAFE_DUTY} on the way
 *       out, so a crash or a kill can never leave a low duty behind;</li>
 *   <li>the LED drive override is applied only while this app is the temperature controller, and
 *       the stock table goes back on every path out of that state;</li>
 *   <li>nothing here writes the display controller; the DLPC is read only.</li>
 * </ul>
 */
public class FanService extends Service {

    public static final String TAG = "FanLab";

    public static final String ACTION_START = "com.daleygames.fanlab.START";
    public static final String ACTION_REFRESH = "com.daleygames.fanlab.REFRESH";
    public static final String ACTION_RELEASE = "com.daleygames.fanlab.RELEASE";
    public static final String ACTION_STOP = "com.daleygames.fanlab.STOP";

    private static final int NOTIF_ID = 4711;
    private static final String CHANNEL_ID = "fanlab.telemetry";

    private static final long TICK_MS = 1000L;

    /** How often the brightness mode alone is re-read while the override is on, ms. 50 ms cuts the kernel's stock-brightness flick to about three frames. This path reads {@code rgblevel} only - never {@code rgbcurrent}, which costs four SPI transactions. */
    private static final long LED_FAST_MS = 50L;

    /** How long after a brightness-mode change the override keeps re-asserting itself, ms. Long enough to outlast the kernel's own four SPI writes; it stops the moment a read-back agrees. */
    private static final long LED_SETTLE_MS = 3000L;

    private static final int RESCAN_EVERY_TICKS = 30;
    private static final int NOTIF_EVERY_TICKS = 5;
    /** Consecutive bad temperature reads tolerated in CURVE mode before failing high. */
    private static final int FAILSAFE_AFTER_BAD_READS = 3;

    /** How stale the last-seen-engine-on stamp may be, ms. Writing it every second would put a preference commit on the control loop's path. */
    private static final long STAMP_EVERY_MS = 60000L;

    /** How often the display controller is read, at most, ms. One pass is five picoreg round trips on a thread of its own, and nothing acts on the answer. */
    private static final long DLPC_READ_EVERY_MS = 60000L;
    /** The bound on one read's round trip. On its own thread, so this stalls nothing. */
    private static final long DLPC_READ_TIMEOUT_MS = 4000L;

    public static volatile FanService instance;
    public static volatile Sample lastSample;
    public static volatile long csvLines;
    public static volatile String[] csvPaths = new String[0];
    public static volatile String[] csvBroken = new String[0];
    public static volatile int writeFailures;
    public static volatile int writesDone;
    public static volatile boolean failSafeLatched;
    public static volatile String statusLine = "not started";

    // Deliberately NOT persisted in Prefs: a sweep must never resume by itself after a process
    // kill or a reboot, with the white field gone and nobody watching.
    public static volatile SweepEngine sweepEngine;
    public static volatile HoldSession holdSession;
    public static volatile String sweepLine = "";
    public static volatile String[] sweepFiles = new String[0];
    public static volatile PicoReg.Reading lastDlpc;

    public static volatile PicoReg.CaicReading caicReadback;
    public static volatile PicoReg.CaicImage caicImageReadback;
    public static volatile PicoReg.Labb labbReadback;
    public static volatile PicoReg.Look lookReadback;

    /** A read is in flight on the picoreg node, so no second one is started: two interleaving reads can each find the other's command sitting in the node. */
    private volatile boolean dlpcReading;
    private long dlpcReadMonoMs;
    private volatile String lastDisplayReadLine = "";
    private boolean systemVariant;

    /** The override's own state machine. Not static - the two paths that reach it from another thread go through {@link #restoreLedDriveNow}, which is synchronized inside {@link LedDrive}. */
    private final LedDrive ledDrive = new LedDrive();

    /** The override may only start at a service start, a settings change, or the tick the mode becomes one this app controls - never spontaneously mid-run, which the owner's ear caught as a cumulative fan sweep. */
    private boolean ledDriveArmed;

    private boolean lastLedControlling;

    private volatile boolean ledFastArmed;

    private volatile int ledFastLevel = -1;

    private volatile double ledFastC = Double.NaN;

    private volatile long ledFastSettleUntil;

    private boolean linearCeilingWasRaised;

    public static volatile String ledDriveStatus = "off";

    private HandlerThread thread;
    private Handler handler;
    private HandlerThread houseThread;
    private Handler house;
    private CsvLogger csv;
    private final FanCurve curve = new FanCurve();
    private final FanLinear linear = new FanLinear();
    private PowerManager power;
    private NotificationManager notifications;

    private int lastWritten = -1;
    private boolean wasDriving;

    private boolean guardWasBiting;

    private boolean linearWasSaturated;

    public static volatile int guardBoost;

    public static volatile boolean linearSaturated;

    public static volatile boolean throttling;

    /** Seconds spent throttled since the service started. The fan cannot prevent throttling outright, so the log has to be able to say whether it happened and for how long. */
    public static volatile long throttledSec;

    private boolean wasThrottling;

    private int session;

    /** Light-engine state the previous tick saw: 1 on, 0 off, -1 not established yet. Tri-state so an unreadable node holds the previous state instead of inventing a power-on edge. */
    private int lastEngineState = -1;

    private long engineOnWallMs;
    private long engineOnMonoMs;
    private long engineOnBootMs;
    private long stampedMonoMs;

    private long lastForeignMonoMs;

    private int lastDesired = -1;
    private long dutyChangedMonoMs;

    /** The stock ladder's kill switch as {@link #syncStockLadder} last read it, or null if never readable. That method is the only reader, which keeps the property off the control loop's path. */
    private volatile String ladderProp;
    /** Latched once the service is being torn down; stops the tick re-taking the node. */
    private volatile boolean stopped;
    private int badReads;
    private int ticks;
    private boolean lastInteractive = true;
    private boolean lastClosedLoop;
    private volatile boolean resumePending;
    private volatile boolean prefsDirty;
    private volatile boolean running;
    private String pendingNote = "";

    private volatile CsvLogger trace;
    private volatile SweepReport.Meta meta;
    private volatile String traceName = "";
    private volatile String reportName = "";
    private int lastRgbWritten = -1;
    private final List<File> sinkDirs = new ArrayList<File>();

    private final SimpleDateFormat isoFmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String a = intent == null ? null : intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_USER_PRESENT.equals(a)) {
                    resumePending = true;
                    note("resume:" + a);
                } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    note("suspend");
                }
            } catch (Throwable t) {
                Log.w(TAG, "powerReceiver", t);
            }
        }
    };

    /** A volume was mounted. Posted to the loop thread, which is the one writer of the sink list; the 30 s timer covers the same ground and {@link #rescanSinks} is idempotent. */
    private final BroadcastReceiver mediaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                note("media_mounted");
                runOnLoop(new Runnable() {
                    @Override
                    public void run() {
                        rescanSinks();
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "mediaReceiver", t);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        caicReadback = null;
        caicImageReadback = null;
        labbReadback = null;
        lookReadback = null;
        try {
            systemVariant = android.os.Process.myUid() == android.os.Process.SYSTEM_UID;
        } catch (Throwable ignored) {
            systemVariant = false;
        }
        Sysfs.sink = new Sysfs.Sink() {
            @Override
            public void note(String msg, Throwable t) {
                Log.w(TAG, msg, t);
            }
        };
        try {
            power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            notifications = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            createChannel();
            startForeground(NOTIF_ID, buildNotification("starting", ""));
        } catch (Throwable t) {
            Log.e(TAG, "onCreate foreground", t);
        }
        try {
            engineOnWallMs = Prefs.engineOnWallMs(this);
            engineOnMonoMs = Prefs.engineOnMonoMs(this);
            engineOnBootMs = Prefs.engineOnBootMs(this);
            session = Prefs.nextSession(this);
        } catch (Throwable t) {
            Log.w(TAG, "onCreate session", t);
        }
        try {
            csv = new CsvLogger("fanlab.csv");
            rescanSinks();
        } catch (Throwable t) {
            Log.e(TAG, "onCreate csv", t);
        }
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(powerReceiver, f);
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiver", t);
        }
        try {
            IntentFilter m = new IntentFilter();
            m.addAction(Intent.ACTION_MEDIA_MOUNTED);
            m.addDataScheme("file");
            registerReceiver(mediaReceiver, m);
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiver media", t);
        }
        try {
            houseThread = new HandlerThread("fanlab-house",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            houseThread.start();
            house = new Handler(houseThread.getLooper());
        } catch (Throwable t) {
            Log.w(TAG, "onCreate house", t);
        }
        try {
            thread = new HandlerThread("fanlab-loop");
            thread.start();
            handler = new Handler(thread.getLooper());
            running = true;
            note(resync(FanIo.readDuty(), "service_start"));
            handler.post(tickRunnable);
            handler.postDelayed(ledFastRunnable, LED_FAST_MS);
        } catch (Throwable t) {
            Log.e(TAG, "onCreate loop", t);
            statusLine = "loop failed to start: " + t;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            String a = intent == null ? null : intent.getAction();
            if (ACTION_RELEASE.equals(a)) {
                releaseControl();
            } else if (ACTION_STOP.equals(a)) {
                releaseControl();
                stopSelf();
            } else if (ACTION_REFRESH.equals(a)) {
                onPrefsChanged();
            }
        } catch (Throwable t) {
            Log.w(TAG, "onStartCommand", t);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        // Latch before anything else: from here the tick must never re-take the node,
        // whatever it was part-way through deciding.
        stopped = true;
        try {
            if (handler != null) {
                handler.removeCallbacksAndMessages(null);
            }
        } catch (Throwable ignored) {
        }
        // The LED drive first, and before the fan: the restore rewrites rgblevel, the stock ladder
        // answers that by writing its own tier floor, and with the loop already stopped the fail-safe
        // write below has to be the last thing that touches the node.
        try {
            restoreLedDriveNow("service_stop");
        } catch (Throwable t) {
            Log.e(TAG, "onDestroy leddrive", t);
        }
        // Hand the hardware back in a state that cannot cook it. If we were driving the fan and we
        // are going away, the last thing we wrote may be low and nothing else is guaranteed to write.
        try {
            // NOT re-derived from Prefs: ACTION_STOP calls releaseControl() first, which sets mode to OFF,
            // so the preferences would answer "was not driving" and skip the handback that path needs.
            boolean wasDriving = this.wasDriving
                    || sweepEngine != null || holdSession != null;
            if (wasDriving) {
                // Give the fan back to the stock controller on the way out. Without this a normal stop leaves
                // the ladder disabled with nobody driving, which persists across reboot.
                syncStockLadder();
                FanIo.writeFailSafe();
                statusLine = "service stopped: handed back to stock, wrote fail-safe "
                        + FanIo.FAIL_SAFE_DUTY;
            } else {
                statusLine = "service stopped (was observing only)";
            }
        } catch (Throwable t) {
            Log.e(TAG, "onDestroy failsafe", t);
        }
        // Close out a session that was still running, so the files on the stick describe what happened.
        try {
            if (sweepEngine != null) {
                sweepEngine.abort("service stopped", SystemClock.elapsedRealtime(),
                        System.currentTimeMillis());
                writeSweepReport(true);
            }
            if (holdSession != null) {
                holdSession.stop("service stopped");
                writeVerifyReport(true);
            }
        } catch (Throwable t) {
            Log.e(TAG, "onDestroy report", t);
        }
        try {
            if (trace != null) {
                trace.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(powerReceiver);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(mediaReceiver);
        } catch (Throwable ignored) {
        }
        try {
            if (csv != null) {
                csv.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (thread != null) {
                thread.quit();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (houseThread != null) {
                // quitSafely: a preference write already queued is worth finishing, and it is the
                // last-seen-engine-on stamp that the next power-on measures from.
                houseThread.quitSafely();
            }
        } catch (Throwable ignored) {
        }
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    /** Called by the UI after it changes a preference. Only raises a flag: the re-sync happens on the loop thread, so nothing touches sysfs from the UI thread. */
    public void onPrefsChanged() {
        prefsDirty = true;
    }

    /**
     * Stop driving the fan and hand it back at the fail-safe duty.
     *
     * 83 is loud, but it is the stock controller's own maximum and the only value that is
     * unconditionally safe to leave behind: the stock ladder only writes when the rounded
     * temperature changes, so releasing at a low duty could leave the machine under-cooled with
     * nothing watching.
     */
    public void releaseControl() {
        try {
            Prefs.setMode(this, Mode.OFF);
            // Re-arm the stock ladder BEFORE dropping the fan, so there is no instant in
            // which nothing at all is responsible for cooling.
            syncStockLadder();
            // And put the stock LED table back before the fan write, not after. Restoring it rewrites
            // rgblevel, which fires the stock ladder's mode-change branch and slams that tier's floor into
            // fan_ctrl; with the mode already OFF no tick will take that back, so the fail-safe below has
            // to be the last thing on the node.
            restoreLedDriveNow("release");
            boolean ok = FanIo.writeFailSafe();
            lastWritten = ok ? FanIo.FAIL_SAFE_DUTY : -1;
            curve.reset();
            linear.reset();
            note("release->" + FanIo.FAIL_SAFE_DUTY + (ok ? "" : " FAILED"));
            statusLine = ok
                    ? "released: fan handed back at " + FanIo.FAIL_SAFE_DUTY
                    : "release attempted but the write to fan_ctrl FAILED";
        } catch (Throwable t) {
            Log.e(TAG, "releaseControl", t);
        }
    }

    /**
     * Keep the stock controller's kill switch coupled to whether this service is really driving
     * the fan.
     *
     * {@code persist.sys.fanctrl.by.temperatue=0} stops the stock ladder writing, and it lives in
     * {@code /data}: it survives a reboot, a force-stop and an uninstall. So the switch is owned
     * by the service's own state - driving (MANUAL, CURVE or a session) stands the ladder down and
     * forces {@link Prefs#autostart} on; not driving hands it back within one tick.
     *
     * A crash or a low-memory kill is covered by START_STICKY, a reboot by autostart. Force-stop,
     * Disable, {@code pm clear} and uninstall are NOT: the projector is not in danger, since the
     * 75 C shutdown and the kernel fan-stall watchdog are both outside userspace, but nothing is
     * responding to temperature until the fan is handed back - with the app's RESTORE control, or
     * {@code adb shell setprop persist.sys.fanctrl.by.temperatue 1}.
     *
     * The plain build returns immediately without touching the property: only the platform-signed
     * build may own this switch, and both variants can be installed at once.
     */
    private synchronized void syncStockLadder() {
        try {
            boolean driving = !stopped && drivesUnattended(Prefs.mode(this));
            String have = SysProps.get(SysProps.PROP_FANCTRL_BY_TEMP);
            ladderProp = have;
            if (android.os.Process.myUid() != android.os.Process.SYSTEM_UID) {
                if (driving && have != null && !"0".equals(have.trim())) {
                    statusLine = "the stock ladder is still armed and this build "
                            + "cannot disable it - it will overwrite the curve every "
                            + "15 s. Deploy the platform-signed build.";
                }
                return;
            }
            String want = driving ? "0" : "1";
            if (have != null && want.equals(have.trim())) {
                if (driving && !Prefs.autostart(this)) {
                    Prefs.setAutostart(this, true);
                    note("autostart forced on (ladder is disabled)");
                }
                return;
            }
            if (driving) {
                // Order matters: arrange to come back BEFORE disabling the fallback.
                Prefs.setAutostart(this, true);
            }
            boolean ok = SysProps.set(SysProps.PROP_FANCTRL_BY_TEMP, want);
            if (ok) {
                ladderProp = want;
            }
            note("stock_ladder->" + (driving ? "off" : "on") + (ok ? "" : " FAILED"));
            if (!ok && driving) {
                // The stock ladder is still writing and will fight us. Not dangerous - two controllers both
                // cooling - but the duty will not hold, and the owner should be told why.
                statusLine = "cannot disable the stock ladder (plain build?) - it will "
                        + "overwrite the curve every 15 s";
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncStockLadder", t);
        }
    }

    /**
     * Whether the stock ladder should be stood down for us.
     *
     * MANUAL is deliberately excluded: it has no temperature logic at all, not even the
     * three-bad-reads fail-safe that CURVE has, so the ladder stays armed to supervise a duty the
     * user typed by hand. LINEAR is included on exactly the same footing as CURVE, and
     * {@link Mode#controls} is the one place that judgement is written down. A sweep or a hold
     * session needs the node to itself, and both are transient and cannot resume across a restart.
     */
    private boolean drivesUnattended(int mode) {
        return Mode.controls(mode) || sweepEngine != null || holdSession != null;
    }

    private void note(String s) {
        synchronized (this) {
            pendingNote = pendingNote.length() == 0 ? s : pendingNote + " " + s;
        }
    }

    /** Adopt the duty the hardware is actually at, and return the note saying which path did it - the callers are mid-tick and build that row's note themselves. */
    private String resync(int duty, String path) {
        curve.resync(duty);
        linear.resync(duty);
        return "resync@" + duty + ":" + path;
    }

    /** Run something off the control loop. Used for preference writes, which the loop's 1 Hz path may not do. */
    private void runOffLoop(Runnable r) {
        try {
            Handler h = house;
            if (h != null) {
                h.post(r);
                return;
            }
            Thread t = new Thread(r, "fanlab-house-once");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            Log.w(TAG, "runOffLoop", t);
        }
    }

    private String takeNote() {
        synchronized (this) {
            String s = pendingNote;
            pendingNote = "";
            return s;
        }
    }

    /** Watch the brightness mode between ticks and put the override back the moment it changes; see {@link #LED_FAST_MS}. {@link LedDrive} is synchronized, so racing the 1 Hz tick is safe by construction. */
    private final Runnable ledFastRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (ledFastArmed) {
                    long now = SystemClock.elapsedRealtime();
                    int level = Sysfs.readInt(Sysfs.RGBLEVEL, -1);
                    if (level > 0 && level != ledFastLevel) {
                        ledFastLevel = level;
                        ledFastSettleUntil = now + LED_SETTLE_MS;
                    }
                    if (level > 0 && now < ledFastSettleUntil) {
                        LedDrive.Config cfg = Prefs.ledDrive(FanService.this);
                        String rbText = Sysfs.read(Sysfs.RGBCURRENT);
                        int[] rb = LedDrive.parseReadback(rbText);
                        if (rb != null && rb[1] == rb[2]) {
                            if (ledDrive.confirmed(cfg, level, rbText)) {
                                ledFastSettleUntil = 0L;
                            } else {
                                LedDrive.Plan plan = ledDrive.decide(cfg, level, true,
                                        ledFastC, rbText, now, true);
                                if (plan.action != LedDrive.Plan.NONE) {
                                    ledDrive.perform(plan);
                                }
                            }
                        }
                        // Incoherent or unreadable: the kernel is still pushing channels out, or the SPI read
                        // dropped. Writing now is what produced a colour cast. Wait for the next 50 ms look.
                    }
                }
            } catch (Throwable t) {
                // The fan is not involved here and the tick will do this again within a second. Never let a
                // brightness cosmetic take the loop down.
                Log.w(TAG, "ledfast", t);
            }
            handler.postDelayed(this, LED_FAST_MS);
        }
    };

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            long started = SystemClock.elapsedRealtime();
            try {
                tick();
            } catch (Throwable t) {
                // Nothing in tick() is supposed to throw. If it does, the projector must still be cooled:
                // write high, say so, and keep looping.
                Log.e(TAG, "tick", t);
                try {
                    if (Mode.writes(Prefs.mode(FanService.this))) {
                        FanIo.writeFailSafe();
                        failSafeLatched = true;
                    }
                } catch (Throwable ignored) {
                }
                statusLine = "internal error, fail-safe applied: " + t;
            }
            if (running && handler != null) {
                long spent = SystemClock.elapsedRealtime() - started;
                long delay = TICK_MS - spent;
                handler.postDelayed(this, delay < 50 ? 50 : delay);
            }
        }
    };

    private void tick() {
        ticks++;
        long now = System.currentTimeMillis();
        long mono = SystemClock.elapsedRealtime();

        Sample s = new Sample();
        s.epochMs = now;
        s.isoLocal = isoFmt.format(new Date(now));

        s.adcRaw = Sysfs.read(Sysfs.LEDTEMP_VOLTAGE);
        s.adc = Thermistor.parseAdc(s.adcRaw);
        s.degC = Thermistor.celsius(s.adc);
        s.propLedTemp = SysProps.get(SysProps.PROP_LED_TEMPERATURE);
        s.fanCtrl = FanIo.readDuty();
        for (int i = 0; i < Sysfs.SOC_THERMAL.length && i < s.socC.length; i++) {
            int milli = Sysfs.readInt(Sysfs.SOC_THERMAL[i], Integer.MIN_VALUE);
            // Plausibility gate: a die below -40 C or above 150 C is a bad read, not a temperature, and
            // must not reach the log looking like data.
            s.socC[i] = (milli == Integer.MIN_VALUE || milli < -40000 || milli > 150000)
                    ? Double.NaN : milli / 1000.0;
        }
        for (int i = 0; i < Sysfs.COOLING_DEVICES.length && i < s.throttle.length; i++) {
            s.throttle[i] = Sysfs.readInt(Sysfs.COOLING_DEVICES[i], -1);
        }
        s.rgblevel = Sysfs.readInt(Sysfs.RGBLEVEL, -1);
        s.ledStatus = Sysfs.readInt(Sysfs.LED_STATUS, -1);
        s.profile = CurveConfig.profileForLevel(s.rgblevel);

        int mode = Prefs.mode(this);
        s.mode = mode;

        boolean interactive = true;
        try {
            if (power != null) {
                interactive = power.isInteractive();
            }
        } catch (Throwable ignored) {
        }

        boolean resumeEdge = resumePending || (interactive && !lastInteractive);
        boolean suspendEdge = !interactive && lastInteractive;
        resumePending = false;
        lastInteractive = interactive;

        StringBuilder note = new StringBuilder(takeNote());

        s.session = session;
        s.roomC = Prefs.roomC(this);

        boolean settingsChanged = prefsDirty;
        if (prefsDirty) {
            prefsDirty = false;
            badReads = 0;
            failSafeLatched = false;
            append(note, "settings changed " + resync(s.fanCtrl, "settings"));
        }

        // Own the stock ladder's kill switch from here rather than leaving it to whoever last set it
        // by hand. The property is global, so another tool can put it back underneath us.
        if (settingsChanged || ticks == 1 || ticks % RESCAN_EVERY_TICKS == 0) {
            syncStockLadder();
        }

        if (resumeEdge) {
            // The driver re-applies 55% on resume and after any stall, discarding what we wrote. Adopt
            // reality, then ramp from there instead of stepping.
            lastWritten = -1;
            append(note, resync(s.fanCtrl, "resume"));
        }

        int engineState = s.ledStatus < 0 ? lastEngineState : (s.ledStatus == 0 ? 0 : 1);
        boolean engineOnEdge = engineState == 1 && lastEngineState != 1;
        if (engineOnEdge) {
            long off = offDurationMs(now, mono);
            s.offMs = off;
            append(note, "poweron off=" + (off < 0 ? "?" : (off / 1000L) + "s")
                    + "(" + offSource() + ")"
                    + " degC=" + Sample.fmt1(s.degC)
                    + " pll=" + Sample.fmt1(s.socC[0]));
        } else if (engineState == 0 && lastEngineState != 0) {
            append(note, "engine_off");
        }
        lastEngineState = engineState;
        if (engineState == 1) {
            engineOnWallMs = now;
            engineOnMonoMs = mono;
            engineOnBootMs = bootWallMs();
            if (stampedMonoMs == 0L || mono - stampedMonoMs >= STAMP_EVERY_MS) {
                stampedMonoMs = mono;
                stampEngineOn(now, mono, engineOnBootMs);
            }
        }

        sampleDisplay(interactive, mono);

        LedDrive.Config ledCfg = Prefs.ledDrive(this);
        boolean ledFeatureOn = Prefs.ledDriveOn(this) && !ledCfg.isStock();

        // A read-back that is not what we last wrote is another writer on the node: the stock ladder,
        // or the kernel reimposing 55 % after a stall. Not while suspended, where the kernel forces
        // every write to 10 and so every read-back would look foreign.
        boolean foreign = interactive && lastWritten > 0 && s.fanCtrl >= 0
                && s.fanCtrl != lastWritten;
        if (foreign) {
            lastForeignMonoMs = mono;
        }

        // A running AUTO or VERIFY session overrides the ordinary mode. It is held in a field, never
        // in the preferences, so it can never survive a restart.
        SweepEngine sw = sweepEngine;
        HoldSession hs = holdSession;
        int desired = -1;
        int traceStep = -1;
        int traceRgb = -1;
        String tracePhase = "";
        StringBuilder traceEvent = new StringBuilder();
        PicoReg.Reading rowDlpc = null;
        boolean sessionRunning = sw != null || hs != null;
        boolean stepEnded = false;
        boolean sessionFinished = false;

        if (sessionRunning) {
            badReads = 0;
            failSafeLatched = false;
            if (foreign) {
                if (sw != null) {
                    sw.noteForeignWrite(s.fanCtrl, lastWritten);
                } else {
                    hs.noteForeignWrite(s.fanCtrl);
                }
                append(note, "foreign_write " + s.fanCtrl);
                appendEvent(traceEvent, "foreign_write:" + s.fanCtrl);
            }
            if (!interactive) {
                // The white field is the test apparatus. If the screen has gone, the run is no longer
                // measuring what it claims to, and the kernel is forcing every write to 10 anyway. Stop, at
                // the fail-safe duty.
                append(note, "screen off during a session - stopping");
                appendEvent(traceEvent, "abort:screen_off");
                if (sw != null) {
                    abortSweep("screen_off");
                } else {
                    stopVerify("screen_off");
                }
                sw = null;
                hs = null;
                sessionRunning = false;
                desired = FanIo.FAIL_SAFE_DUTY;
            }
        }

        double sweepC = Thermistor.plausible(s.degC) ? s.degC : Double.NaN;

        if (sw != null) {
            SweepEngine.Tick t = sw.tick(mono, now, sweepC);
            desired = t.duty;
            traceRgb = t.rgblevel;
            traceStep = sw.stepNumber();
            tracePhase = SweepEngine.phaseName(sw.phase());
            appendEvent(traceEvent, t.event);
            stepEnded = t.stepEnded;
            sessionFinished = t.finished;
            assertRgbLevel(sw, null, t.rgblevel, s, note, traceEvent);
        } else if (hs != null) {
            desired = hs.tick(mono, sweepC);
            traceRgb = hs.rgblevel();
            tracePhase = hs.closedLoop() ? "steady" : "hold";
            traceStep = 0;
            sessionFinished = hs.finished();
            assertRgbLevel(null, hs, hs.rgblevel(), s, note, traceEvent);
        } else if (mode == Mode.MANUAL) {
            desired = Prefs.manualDuty(this);
            badReads = 0;
            failSafeLatched = false;
        } else if (mode == Mode.CURVE) {
            if (!Thermistor.plausible(s.degC)) {
                badReads++;
                append(note, "badtemp x" + badReads);
                if (badReads >= FAILSAFE_AFTER_BAD_READS) {
                    desired = FanIo.FAIL_SAFE_DUTY;
                    failSafeLatched = true;
                    append(note, "FAILSAFE");
                }
            } else {
                if (failSafeLatched) {
                    append(note, "failsafe cleared " + resync(s.fanCtrl, "failsafe_clear"));
                }
                badReads = 0;
                failSafeLatched = false;
                // An unreadable led_status counts as "engine on": assuming the light
                // engine is running is the conservative assumption.
                boolean engineOn = s.ledStatus != 0;
                CurveConfig cfg = Prefs.curve(this);
                // socC[0] is thermal_zone0 (pll) -- the only SoC zone with cooling devices
                // bound to it, so the only one whose temperature has a consequence.
                desired = curve.step(cfg, s.profile, s.degC, s.socC[0], engineOn, mono);
                guardBoost = curve.guardBoost();
                boolean biting = guardBoost > 0;
                if (biting != guardWasBiting) {
                    append(note, biting
                            ? "socguard +" + curve.guardBoost() + "@" + Sample.fmt1(s.socC[0])
                            : "socguard released");
                    guardWasBiting = biting;
                }
            }
        } else if (mode == Mode.LINEAR) {
            // The same fail-safe path as CURVE, deliberately not a parallel one: three bad reads write 83
            // and latch, and the latch clears through the shared resync so the walk restarts from the duty
            // actually on the node.
            if (!Thermistor.plausible(s.degC)) {
                badReads++;
                append(note, "badtemp x" + badReads);
                if (badReads >= FAILSAFE_AFTER_BAD_READS) {
                    desired = FanIo.FAIL_SAFE_DUTY;
                    failSafeLatched = true;
                    append(note, "FAILSAFE");
                }
            } else {
                if (failSafeLatched) {
                    append(note, "failsafe cleared " + resync(s.fanCtrl, "failsafe_clear"));
                }
                badReads = 0;
                failSafeLatched = false;
                boolean engineOn = s.ledStatus != 0;
                LinearConfig lin = Prefs.linear(this);
                // LINEAR needs no new mode for the LED drive override - it holds a temperature, so it pays for
                // the extra heat in fan. What it needs is a different ceiling: the default moves to 54.0 while
                // the override is on, never written to the preferences and never applied to a ceiling the owner
                // set by hand.
                boolean raised = LinearConfig.promoteForBoost(lin, ledFeatureOn);
                if (raised != linearCeilingWasRaised) {
                    append(note, raised
                            ? "linear ceiling " + Sample.fmt1(LinearConfig.DEFAULT_CEILING_C)
                              + "->" + Sample.fmt1(lin.ceilingC)
                              + " for the LED drive override (inferred)"
                            : "linear ceiling back to " + Sample.fmt1(lin.ceilingC));
                    linearCeilingWasRaised = raised;
                }
                desired = linear.step(lin, Prefs.curve(this), s.profile, s.degC,
                        s.socC[0], engineOn, mono);
                guardBoost = linear.guardBoost();
                boolean biting = guardBoost > 0;
                if (biting != guardWasBiting) {
                    append(note, biting
                            ? "socguard +" + guardBoost + "@" + Sample.fmt1(s.socC[0])
                            : "socguard released");
                    guardWasBiting = biting;
                }
                boolean sat = linear.saturated();
                if (sat != linearWasSaturated) {
                    append(note, sat
                            ? "linear saturated at " + desired + " (ceiling "
                              + Sample.fmt1(lin.ceilingC) + ", degC " + Sample.fmt1(s.degC)
                              + ")"
                            : "linear saturation cleared");
                    linearWasSaturated = sat;
                }
                linearSaturated = sat;
            }
        } else {
            badReads = 0;
            failSafeLatched = false;
        }
        if (mode != Mode.LINEAR || sessionRunning || failSafeLatched) {
            linearSaturated = false;
            linearWasSaturated = false;
        }
        // Handing back by any route -- the adb path sets mode=OFF and never calls releaseControl() --
        // must still leave the fan somewhere safe, since the stock ladder only writes when the rounded
        // temperature next changes.
        throttling = s.throttling();
        if (throttling) {
            throttledSec++;
        }
        if (throttling != wasThrottling) {
            append(note, throttling
                    ? "THROTTLING " + s.throttleNote() + " pll=" + Sample.fmt1(s.socC[0])
                    : "throttling cleared after " + throttledSec + "s");
            wasThrottling = throttling;
        }

        boolean drivingNow = Mode.writes(mode) || sessionRunning;
        if (wasDriving && !drivingNow && desired <= 0) {
            desired = FanIo.FAIL_SAFE_DUTY;
            append(note, "handback->" + FanIo.FAIL_SAFE_DUTY);
        }
        wasDriving = drivingNow;

        s.desired = desired;

        if (desired > 0) {
            if (!interactive) {
                // Early-suspended: the kernel forces every write to 10, so writing here achieves nothing
                // except log noise. Reality is re-adopted on resume.
                if (suspendEdge) {
                    append(note, "suspended, not writing");
                }
            } else {
                // A session depends on winning the node every second, so the user's
                // re-assert toggle does not apply to it.
                boolean reassert = sessionRunning || Prefs.reassert(this);
                boolean changed = desired != lastWritten;
                boolean drifted = s.fanCtrl >= 0 && s.fanCtrl != desired;
                boolean tooLow = s.fanCtrl >= 0 && s.fanCtrl < desired;
                // tooLow is deliberately outside the re-assert switch: the app may leave
                // the fan higher than it intended, never lower.
                if (changed || resumeEdge || (reassert && drifted) || tooLow) {
                    if (FanIo.writeDuty(desired)) {
                        s.wrote = desired;
                        lastWritten = desired;
                        writesDone++;
                        if (drifted && !changed) {
                            append(note, "reassert(was " + s.fanCtrl + ")");
                        }
                    } else {
                        writeFailures++;
                        append(note, "WRITE FAILED");
                        statusLine = "cannot write " + Sysfs.FAN_CTRL
                                + " - check the app is the API-28 build and the node is 0777";
                    }
                }
            }
        }

        // Deliberately after the write, exactly like the DLPC read below: one sysfs read and up to two
        // writes, none of which may sit in front of a cooling decision.
        // The coupling rule is the entire safety case and it is a conjunction. Raising LED output while
        // this app is not the fan controller runs Presentation-class heat under whatever fan ladder
        // rgblevel happens to select, so every term below is required and any doubt resolves to
        // not-allowed. led_status is the one term that treats an unreadable node as "on", because the
        // hazard is the fan coupling and writing LED currents to an engine that is off is inert.
        boolean ledControls = Mode.controls(mode);
        // A session normally forfeits the override. A VERIFY steady phase is the exception, and it
        // satisfies the rule rather than bending it: there the real curve is closing the real loop on
        // the real thermistor, which is precisely the coupling being required.
        boolean sessionOpenLoop = sw != null || (hs != null && !hs.closedLoop());
        boolean ledInCharge = ledFeatureOn && !stopped && ledControls
                && !sessionOpenLoop && !failSafeLatched;
        // Arming. lastLedControlling starts false, so the service's own first tick in CURVE or LINEAR
        // is an entry edge and needs no case of its own.
        // Losing the display or the light engine is a pause, not a disarm. Everything else -- the mode
        // leaving, a session taking the node, the fail-safe latching, the feature being switched off --
        // clears the arm, so coming back takes a deliberate act.
        boolean ledModeEntered = ledControls && !lastLedControlling;
        lastLedControlling = ledControls;
        // Handing the fan to the curve mid-session is an entry edge of its own: without it the override
        // could never come back, since the mode has not changed and startVerify dropped the drive.
        boolean closedLoopNow = hs != null && hs.closedLoop();
        boolean steadyEntered = closedLoopNow && !lastClosedLoop;
        lastClosedLoop = closedLoopNow;
        if (!ledInCharge) {
            ledDriveArmed = false;
        } else if (settingsChanged || ledModeEntered || steadyEntered) {
            ledDriveArmed = true;
        }
        boolean ledAllowed = ledInCharge && ledDriveArmed && interactive && s.ledStatus != 0;
        try {
            String rgbCurrent = ledFeatureOn ? Sysfs.read(Sysfs.RGBCURRENT) : null;
            LedDrive.Plan plan = ledDrive.decide(ledFeatureOn ? ledCfg : null, s.rgblevel,
                    ledAllowed, Thermistor.plausible(s.degC) ? s.degC : Double.NaN,
                    rgbCurrent, mono);
            ledDrive.perform(plan);
            s.ledDrive = ledDrive.appliedLevel();
            if (plan.note != null && plan.note.length() > 0) {
                append(note, plan.note);
            }
            ledDriveStatus = ledDriveLine(ledFeatureOn, ledInCharge, interactive, s.ledStatus);
            // Hand the fast watch its inputs. Re-reading rgblevel here means a mode change the tick saw
            // first is not acted on twice.
            ledFastLevel = s.rgblevel;
            ledFastC = Thermistor.plausible(s.degC) ? s.degC : Double.NaN;
            ledFastArmed = ledAllowed && ledFeatureOn && !ledCfg.isStock();
        } catch (Throwable t) {
            // The outermost belt: the fan has already been written this tick and nothing about the LEDs is
            // worth losing a tick over.
            Log.w(TAG, "leddrive", t);
        }

        // The kill switch comes from the cache syncStockLadder fills, so this costs the loop no
        // property read at all.
        s.exclusive = Provenance.exclusive(mode, sessionRunning, ladderProp, mono,
                lastForeignMonoMs);

        if (mode == Mode.CURVE && !sessionRunning) {
            s.catchingUp = curve.isCatchingUp() ? 1 : 0;
        }
        if (desired > 0) {
            if (desired != lastDesired) {
                lastDesired = desired;
                dutyChangedMonoMs = mono;
            }
            s.dutyHoldMs = mono - dutyChangedMonoMs;
        }

        // Deliberately after the write: the fan is already at the new duty, so the up to
        // 1.2 s this can cost cannot delay a cooling decision.
        if (stepEnded && sw != null) {
            try {
                rowDlpc = PicoReg.readSystemTemperature(1200L);
                lastDlpc = rowDlpc;
                sw.attachToLastStep(rowDlpc, PicoReg.readLedCurrents());
                if (!PicoReg.STATUS_OK.equals(rowDlpc.status)) {
                    append(note, "DLPC temp UNAVAILABLE");
                    appendEvent(traceEvent, "dlpc_unavailable");
                } else {
                    appendEvent(traceEvent, "dlpc_read");
                }
            } catch (Throwable t) {
                Log.w(TAG, "picoreg", t);
            }
            try {
                writeSweepReport(false);
            } catch (Throwable t) {
                Log.w(TAG, "sweep report", t);
            }
        }

        s.note = note.toString();
        lastSample = s;

        try {
            if (ticks % RESCAN_EVERY_TICKS == 1) {
                rescanSinks();
            }
        } catch (Throwable t) {
            Log.w(TAG, "rescan", t);
        }

        try {
            if (csv != null && Prefs.logging(this)) {
                boolean interesting = s.wrote > 0
                        || failSafeLatched
                        || sessionRunning
                        || throttling
                        || (s.note != null && s.note.length() > 0);
                int every = Prefs.logEverySec(this);
                if (interesting || ticks % every == 0) {
                    csv.append(s.toCsv());
                    csvLines = csv.lineCount();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "csv", t);
        }
        try {
            if (trace != null && (sw != null || hs != null || sessionFinished)) {
                trace.append(SweepReport.traceRow(s, desired, traceStep, tracePhase,
                        traceRgb, traceEvent.toString(), rowDlpc));
            }
        } catch (Throwable t) {
            Log.w(TAG, "trace", t);
        }

        if (sessionFinished) {
            try {
                if (sw != null) {
                    finishSweep();
                } else if (hs != null) {
                    finishVerify();
                }
            } catch (Throwable t) {
                Log.e(TAG, "session end", t);
                FanIo.writeFailSafe();
            }
        }

        if (!sessionFinished) {
            if (sw != null) {
                sweepLine = sw.modeName() + "  step " + sw.stepNumber() + "/"
                        + sw.totalSteps() + "  duty " + desired + "%  "
                        + (Double.isNaN(s.degC) ? "--" : Sample.fmt1(s.degC)) + " C";
            } else if (hs != null) {
                sweepLine = "VERIFY  " + hs.modeName() + "  duty " + hs.duty() + "%  "
                        + (Double.isNaN(s.degC) ? "--" : Sample.fmt1(s.degC)) + " C";
            }
        }

        if (ticks % NOTIF_EVERY_TICKS == 0) {
            updateNotification(s);
        }
        if (statusLine == null || statusLine.length() == 0 || "not started".equals(statusLine)) {
            statusLine = "running";
        }
    }

    private static void append(StringBuilder sb, String s) {
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(s);
    }

    /** How long the light engine had been off before it came on, ms, or -1 when there is nothing to go on. {@link Provenance#offDurationMs} carries the two cases. */
    private long offDurationMs(long nowWallMs, long nowMonoMs) {
        return Provenance.offDurationMs(nowWallMs, nowMonoMs, engineOnWallMs,
                engineOnMonoMs, engineOnBootMs, bootWallMs());
    }

    /** Which of the two cases {@link #offDurationMs} answered from, for the note. */
    private String offSource() {
        return Provenance.offSource(engineOnWallMs, engineOnBootMs, bootWallMs());
    }

    /** Ask the DLPC what its three image features are doing, and record the answer. Read only, and off the 1 Hz path: one round trip is an I2C write plus a {@code logcat} scrape, and the loop it would run on is the one holding the fan. Never with the display off. */
    private void sampleDisplay(boolean interactive, long mono) {
        if (stopped || !interactive || !systemVariant || dlpcReading) {
            return;
        }
        if (dlpcReadMonoMs != 0L && mono - dlpcReadMonoMs < DLPC_READ_EVERY_MS) {
            return;
        }
        dlpcReadMonoMs = mono;
        readDisplayAsync();
    }

    /** The four readings, in sequence, on a thread that is neither the control loop nor the housekeeping looper: they share one node, and a hung {@code logcat} must take nothing down with it. A note is filed only when the line changes. */
    private void readDisplayAsync() {
        dlpcReading = true;
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        PicoReg.CaicReading caic =
                                PicoReg.readLedOutputControl(DLPC_READ_TIMEOUT_MS);
                        caicReadback = caic;
                        PicoReg.CaicImage img =
                                PicoReg.readCaicImageControl(DLPC_READ_TIMEOUT_MS);
                        if (img.known) {
                            caicImageReadback = img;
                        }
                        PicoReg.Labb labb = PicoReg.readLabb(DLPC_READ_TIMEOUT_MS);
                        labbReadback = labb;
                        PicoReg.Look look = PicoReg.readLook(DLPC_READ_TIMEOUT_MS);
                        if (look.known) {
                            lookReadback = look;
                        }
                        String line = "dlpc_read=caic:" + caic.state
                                + " gain:" + (img.known ? PicoReg.fmtGain(img.gain) : "?")
                                + " labb:" + (labb.known ? labb.summary() : "?")
                                + " look:" + (look.known ? look.summary() : "?");
                        if (!line.equals(lastDisplayReadLine)) {
                            lastDisplayReadLine = line;
                            note(line);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "dlpc read", t);
                    } finally {
                        dlpcReading = false;
                    }
                }
            }, "fanlab-dlpc-read");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            dlpcReading = false;
            Log.w(TAG, "readDisplayAsync", t);
        }
    }

    /** One phrase describing the override, for {@link #ledDriveStatus}. "Stock because nothing is asking" and "stock because something is holding it off" are different states, and the owner has to be able to see the reason for the second. */
    private String ledDriveLine(boolean featureOn, boolean inCharge, boolean interactive,
                                int ledStatus) {
        if (!featureOn) {
            return "off";
        }
        if (ledDrive.tripped() || ledDrive.overriding()) {
            // "held off: tripped at 57.2 C", or "applied 90/84".
            return ledDrive.state();
        }
        if (!inCharge) {
            return "stock: this app is not the fan controller";
        }
        if (ledStatus == 0) {
            return "stock: the light engine is off";
        }
        if (!interactive) {
            return "stock: the display is asleep";
        }
        if (!ledDriveArmed) {
            // The only state that needs the owner to do something: the fail-safe latched at some point,
            // which is deliberately not self-clearing.
            return "stock: held until the mode or the settings change";
        }
        return "stock";
    }

    /**
     * Put the stock LED table back, now, off the tick.
     *
     * The handback for the three paths that are not a tick: RELEASE, a session taking the node,
     * and service stop. Everything a tick can see is handled by {@link LedDrive#decide} instead,
     * on the tick it happens. Writes nothing at all on a machine this app never boosted.
     */
    private void restoreLedDriveNow(String why) {
        try {
            LedDrive.Plan p = ledDrive.forceRestore(SystemClock.elapsedRealtime());
            if (p.action == LedDrive.Plan.NONE) {
                return;
            }
            boolean ok = ledDrive.perform(p);
            note("leddrive->stock:" + why + (ok ? "" : " FAILED"));
            ledDriveStatus = ok ? "stock" : "stock write FAILED";
        } catch (Throwable t) {
            Log.w(TAG, "restoreLedDriveNow", t);
        }
    }

    private void stampEngineOn(final long wallMs, final long monoMs, final long bootMs) {
        runOffLoop(new Runnable() {
            @Override
            public void run() {
                try {
                    Prefs.setEngineOn(FanService.this, wallMs, monoMs, bootMs);
                } catch (Throwable t) {
                    Log.w(TAG, "stampEngineOn", t);
                }
            }
        });
    }

    private static void appendEvent(StringBuilder sb, String s) {
        if (s == null || s.length() == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(';');
        }
        sb.append(s);
    }

    /** Start the unattended thermal characterisation. Returns false if it was refused - already running, or the projector already too warm to start - with {@link #statusLine} saying why. */
    public synchronized boolean startSweep(String ambientNote) {
        try {
            if (sweepEngine != null || holdSession != null) {
                statusLine = "a session is already running";
                return false;
            }
            Sample s = lastSample;
            double c = s == null ? Double.NaN : s.degC;
            if (Double.isNaN(c) || !Thermistor.plausible(c)) {
                statusLine = "no usable temperature reading yet - wait a moment and retry";
                return false;
            }
            if (c > SweepPlan.MAX_START_C) {
                statusLine = "too warm to start: " + Sample.fmt1(c) + " C, needs to be under "
                        + Sample.fmt1(SweepPlan.MAX_START_C) + " C";
                return false;
            }
            // Before the session takes the node: a sweep owns rgblevel and drives the fan to a schedule, so
            // it must not start on top of an LED drive the report would not mention.
            restoreLedDriveNow("session");
            openSessionFiles(false);
            meta = buildMeta("auto", ambientNote);
            lastRgbWritten = -1;
            lastRgbWriteMs = 0L;
            lastDlpc = null;
            SweepEngine e = new SweepEngine(SystemClock.elapsedRealtime(),
                    System.currentTimeMillis());
            sweepEngine = e;
            statusLine = "AUTO running - do not change anything on the projector";
            sweepLine = "starting";
            note("AUTO start");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "startSweep", t);
            FanIo.writeFailSafe();
            sweepEngine = null;
            statusLine = "could not start AUTO: " + t;
            return false;
        }
    }

    /** Stop the sweep now. The fail-safe duty is written first and synchronously, so ABORT is instant no matter what the loop thread is doing. */
    public void abortSweep(final String reason) {
        try {
            FanIo.writeFailSafe();
            lastWritten = FanIo.FAIL_SAFE_DUTY;
        } catch (Throwable t) {
            Log.e(TAG, "abortSweep write", t);
        }
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    SweepEngine sw = sweepEngine;
                    if (sw == null) {
                        return;
                    }
                    if (!sw.finished()) {
                        sw.abort(reason, SystemClock.elapsedRealtime(),
                                System.currentTimeMillis());
                    }
                    finishSweep();
                } catch (Throwable t) {
                    Log.e(TAG, "abortSweep", t);
                    FanIo.writeFailSafe();
                }
            }
        };
        runOnLoop(r);
    }

    /** Timestamp a human observation - the one thing only a person in the room can measure. */
    public void markSweep(final String kind, final long wallMs) {
        runOnLoop(new Runnable() {
            @Override
            public void run() {
                try {
                    SweepEngine sw = sweepEngine;
                    if (sw != null && !sw.finished()) {
                        sw.mark(kind, wallMs);
                        writeSweepReport(false);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "markSweep", t);
                }
            }
        });
    }

    private void finishSweep() {
        SweepEngine sw = sweepEngine;
        if (sw == null) {
            return;
        }
        try {
            FanIo.writeFailSafe();
            lastWritten = FanIo.FAIL_SAFE_DUTY;
        } catch (Throwable ignored) {
        }
        writeSweepReport(true);
        sweepEngine = null;
        closeTrace();
        statusLine = "AUTO finished (" + sw.endReason() + ") - fan handed back at "
                + FanIo.FAIL_SAFE_DUTY + "%. Set the brightness mode once from the remote "
                + "so the projector's own app agrees with the hardware again."
                + filesLine();
        sweepLine = "finished: " + sw.endReason();
    }

    private void writeSweepReport(boolean last) {
        SweepEngine sw = sweepEngine;
        if (sw == null || meta == null) {
            return;
        }
        long end = System.currentTimeMillis();
        long dur = (end - sw.startedWallMs()) / 1000L;
        String json = SweepReport.sweepJson(sw, meta, last ? end : 0L, dur);
        List<String> paths = CsvLogger.writeWhole(sinkSnapshot(), reportName, json);
        sweepFiles = paths.toArray(new String[0]);
    }

    /** Hold one duty in one mode until the user stops it. */
    public synchronized boolean startVerify(int duty, int rgblevel) {
        try {
            if (sweepEngine != null || holdSession != null) {
                statusLine = "a session is already running";
                return false;
            }
            // For the reason startSweep gives: a hold session owns rgblevel too.
            restoreLedDriveNow("session");
            openSessionFiles(true);
            meta = buildMeta("verify", "");
            lastRgbWritten = -1;
            lastRgbWriteMs = 0L;
            holdSession = new HoldSession(duty, rgblevel, SystemClock.elapsedRealtime(),
                    System.currentTimeMillis());
            statusLine = "VERIFY running";
            note("VERIFY start");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "startVerify", t);
            FanIo.writeFailSafe();
            holdSession = null;
            statusLine = "could not start VERIFY: " + t;
            return false;
        }
    }

    /** Hand the fan from the held duty to the stored curve, and count what it does. The curve handed over is the one actually stored, so what is measured is what will run, and the fan picks up from where it already is. */
    public synchronized boolean beginSteadyPhase(int seconds) {
        try {
            HoldSession hs = holdSession;
            if (hs == null || hs.finished()) {
                statusLine = "no VERIFY session to hand over";
                return false;
            }
            if (hs.closedLoop()) {
                statusLine = "the curve is already driving";
                return false;
            }
            int from = lastWritten > 0 ? lastWritten : hs.duty();
            hs.beginSteady(Prefs.curve(this), seconds, from,
                    SystemClock.elapsedRealtime());
            statusLine = "VERIFY steady phase: the curve is driving, watching for movement";
            note("VERIFY steady phase start from duty " + from);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "beginSteadyPhase", t);
            statusLine = "could not start the steady phase: " + t;
            return false;
        }
    }

    public void stopVerify(final String reason) {
        try {
            FanIo.writeFailSafe();
            lastWritten = FanIo.FAIL_SAFE_DUTY;
        } catch (Throwable t) {
            Log.e(TAG, "stopVerify write", t);
        }
        runOnLoop(new Runnable() {
            @Override
            public void run() {
                try {
                    HoldSession hs = holdSession;
                    if (hs == null) {
                        return;
                    }
                    hs.stop(reason);
                    finishVerify();
                } catch (Throwable t) {
                    Log.e(TAG, "stopVerify", t);
                    FanIo.writeFailSafe();
                }
            }
        });
    }

    public void verifyCheck(final String about, final String verdict, final long wallMs) {
        runOnLoop(new Runnable() {
            @Override
            public void run() {
                try {
                    HoldSession hs = holdSession;
                    if (hs != null && !hs.finished()) {
                        hs.check(about, verdict, SystemClock.elapsedRealtime(), wallMs);
                        writeVerifyReport(false);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "verifyCheck", t);
                }
            }
        });
    }

    public void verifyPattern(final String pattern) {
        runOnLoop(new Runnable() {
            @Override
            public void run() {
                HoldSession hs = holdSession;
                if (hs != null) {
                    hs.setPattern(pattern);
                }
            }
        });
    }

    private void finishVerify() {
        HoldSession hs = holdSession;
        if (hs == null) {
            return;
        }
        try {
            FanIo.writeFailSafe();
            lastWritten = FanIo.FAIL_SAFE_DUTY;
        } catch (Throwable ignored) {
        }
        writeVerifyReport(true);
        holdSession = null;
        closeTrace();
        statusLine = "VERIFY finished (" + hs.endReason() + ") - fan handed back at "
                + FanIo.FAIL_SAFE_DUTY + "%. Set the brightness mode once from the remote "
                + "so the projector's own app agrees with the hardware again."
                + filesLine();
        sweepLine = "finished: " + hs.endReason();
    }

    private String filesLine() {
        try {
            String[] f = sweepFiles;
            if (f == null || f.length == 0) {
                return "\nWARNING: the report could not be written anywhere.";
            }
            StringBuilder sb = new StringBuilder("\nWrote ");
            sb.append(reportName).append(" and ").append(traceName).append(" to:");
            for (int i = 0; i < f.length; i++) {
                File parent = new File(f[i]).getParentFile();
                sb.append("\n   ").append(parent == null ? f[i] : parent.getAbsolutePath());
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private void writeVerifyReport(boolean last) {
        HoldSession hs = holdSession;
        if (hs == null || meta == null) {
            return;
        }
        long end = System.currentTimeMillis();
        long dur = (end - hs.startedWallMs()) / 1000L;
        String json = SweepReport.verifyJson(hs, meta, last ? end : 0L, dur);
        List<String> paths = CsvLogger.writeWhole(sinkSnapshot(), reportName, json);
        sweepFiles = paths.toArray(new String[0]);
    }

    /** A copy, so a rescan on another thread cannot break the write half way through. */
    private List<File> sinkSnapshot() {
        synchronized (sinkDirs) {
            return new ArrayList<File>(sinkDirs);
        }
    }

    private long lastRgbWriteMs;

    /** Hold the brightness mode the session asked for. Every {@code rgblevel} change fires the stock controller's mode-change branch, which writes that tier's floor to {@code fan_ctrl} within one 15 s poll; the 1 Hz re-assert takes it back. */
    private void assertRgbLevel(SweepEngine sw, HoldSession hs, int want, Sample s,
                                StringBuilder note, StringBuilder ev) {
        if (!SweepPlan.validRgbLevel(want)) {
            return;
        }
        int found = s.rgblevel;
        if (found == want) {
            lastRgbWritten = want;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastRgbWriteMs < 5000L) {
            return;         // do not re-drive the LED currents every second
        }
        if (found >= 0 && lastRgbWritten == want) {
            if (sw != null) {
                sw.noteForeignMode(found, want);
            } else if (hs != null) {
                hs.noteForeignMode(found);
            }
            append(note, "foreign_mode " + found);
            appendEvent(ev, "foreign_mode:" + found);
        }
        lastRgbWriteMs = now;
        if (Sysfs.write(Sysfs.RGBLEVEL, Integer.toString(want))) {
            lastRgbWritten = want;
            append(note, "rgblevel<-" + want);
            appendEvent(ev, "rgblevel_set:" + want);
        } else {
            append(note, "rgblevel WRITE FAILED");
            appendEvent(ev, "rgblevel_write_failed");
        }
    }

    private void openSessionFiles(boolean verify) {
        long sessionEpoch = System.currentTimeMillis() / 1000L;
        traceName = SweepReport.traceName(sessionEpoch, verify);
        reportName = SweepReport.reportName(sessionEpoch, verify);
        closeTrace();
        trace = new CsvLogger(traceName, SweepReport.TRACE_HEADER);
        rescanSinks();
    }

    private void closeTrace() {
        try {
            if (trace != null) {
                trace.close();
                trace = null;
            }
        } catch (Throwable ignored) {
        }
    }

    private SweepReport.Meta buildMeta(String kind, String ambientNote) {
        SweepReport.Meta m = new SweepReport.Meta();
        m.kind = kind;
        m.ambientNote = ambientNote == null ? "" : ambientNote;
        m.traceFile = traceName;
        m.reportFile = reportName;
        m.patternLevelPercent = 100;
        m.overlay = "full-screen white field with a small mid-grey readout; no dark panel, "
                + "so the average picture level stays near 100% and content-adaptive "
                + "dimming cannot change the LED drive mid-run";
        try {
            m.packageName = getPackageName();
            m.uid = android.os.Process.myUid();
            m.systemVariant = m.uid == android.os.Process.SYSTEM_UID;
        } catch (Throwable ignored) {
        }
        try {
            m.appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            m.appVersion = "";
        }
        m.model = str(SysProps.get("ro.product.model"));
        m.firmware = str(SysProps.get("ro.product.version"));
        m.androidRelease = str(SysProps.get("ro.build.version.release"));
        m.androidSdk = str(SysProps.get("ro.build.version.sdk"));
        return m;
    }

    private static String str(String s) {
        return s == null ? "" : s.trim();
    }

    /** Run on the control-loop thread, or inline if we are already on it. */
    private void runOnLoop(Runnable r) {
        try {
            Handler h = handler;
            if (h != null && Thread.currentThread() != thread) {
                h.post(r);
            } else {
                r.run();
            }
        } catch (Throwable t) {
            Log.e(TAG, "runOnLoop", t);
        }
    }

    /** Every removable volume root, e.g. {@code /storage/1234-5678}. The backlog export needs the root itself, since its destination sits outside every logging sink. */
    private List<File> usbVolumeRoots() {
        List<File> roots = new ArrayList<File>();
        try {
            File[] vols = new File("/storage").listFiles();
            if (vols != null) {
                for (int i = 0; i < vols.length; i++) {
                    String n = vols[i].getName();
                    if ("emulated".equals(n) || "self".equals(n) || !vols[i].isDirectory()) {
                        continue;
                    }
                    roots.add(vols[i]);
                }
            }
        } catch (Throwable ignored) {
            // /storage is often not listable; getExternalFilesDirs is the real path
        }
        return roots;
    }

    /** Work out where the CSV should go: every app-external directory the platform offers, which on this device means internal shared storage and a mounted USB volume, and needs no permission at all. */
    private void rescanSinks() {
        List<File> dirs = new ArrayList<File>();
        try {
            File[] ext = getExternalFilesDirs(null);
            if (ext != null) {
                for (int i = 0; i < ext.length; i++) {
                    if (ext[i] != null) {
                        dirs.add(new File(ext[i], "fanlab"));
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getExternalFilesDirs", t);
        }
        List<File> roots = usbVolumeRoots();
        for (int i = 0; i < roots.size(); i++) {
            dirs.add(new File(roots.get(i),
                    "Android/data/" + getPackageName() + "/files/fanlab"));
        }
        try {
            if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    == PackageManager.PERMISSION_GRANTED) {
                File pub = Environment.getExternalStorageDirectory();
                if (pub != null) {
                    dirs.add(new File(pub, "FanLab"));
                }
            }
        } catch (Throwable ignored) {
        }
        // Guaranteed sink, so the row counter is never zero even if everything else fails.
        try {
            dirs.add(new File(getFilesDir(), "fanlab"));
        } catch (Throwable ignored) {
        }
        try {
            synchronized (sinkDirs) {
                sinkDirs.clear();
                sinkDirs.addAll(dirs);
                volumeRoots.clear();
                volumeRoots.addAll(roots);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (csv != null) {
                csv.setDirs(dirs);
                csvPaths = csv.activePaths().toArray(new String[0]);
                csvBroken = csv.brokenPaths().toArray(new String[0]);
            }
        } catch (Throwable t) {
            Log.w(TAG, "setDirs", t);
        }
        try {
            if (trace != null) {
                trace.setDirs(dirs);
            }
        } catch (Throwable t) {
            Log.w(TAG, "setDirs trace", t);
        }
        try {
            exportBacklogAsync(false);
        } catch (Throwable t) {
            Log.w(TAG, "export edge", t);
        }
    }

    /** Where an export puts its files on a volume. Never a {@link CsvLogger} sink: {@code prune()} deletes everything matching {@code fanlab-*.csv} in a target's own directory. */
    public static final String EXPORT_DIR = "FanLab-export";

    /** Names the boot an export last ran for, so a stick left in is not re-scanned. */
    private static final String EXPORT_MARK = ".fanlab-volume";

    public static volatile String exportStatus = "no export yet";
    public static volatile int exportFiles;
    public static volatile long exportRows;
    public static volatile String[] exportDirs = new String[0];
    public static volatile boolean exporting;

    private final Set<String> exportedVolumes = new HashSet<String>();
    private final List<File> volumeRoots = new ArrayList<File>();

    /**
     * Copy the log backlog to every mounted volume, on a thread of its own: the callers are the
     * control loop and the UI thread, and a cooling decision cannot wait behind a file copy.
     *
     * @param force copy even to a volume already offered the backlog this boot, and say something
     *              either way.
     */
    public void exportBacklogAsync(final boolean force) {
        List<File> picked = new ArrayList<File>();
        try {
            List<File> all = rootSnapshot();
            synchronized (exportedVolumes) {
                for (int i = 0; i < all.size(); i++) {
                    if (force || !exportedVolumes.contains(all.get(i).getAbsolutePath())) {
                        picked.add(all.get(i));
                    }
                }
                if (picked.isEmpty()) {
                    if (force) {
                        exportStatus = "no USB volume is mounted";
                    }
                    return;
                }
                if (exporting) {
                    if (force) {
                        exportStatus = "an export is already running";
                    }
                    return;
                }
                exporting = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "exportBacklogAsync", t);
            return;
        }
        final List<File> roots = picked;
        try {
            final List<File> sinks = sinkSnapshot();
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    runExport(roots, sinks, force);
                }
            }, "fanlab-export");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable t) {
            exporting = false;
            Log.w(TAG, "export thread", t);
        }
    }

    /** A copy, so a rescan on another thread cannot break an export half way through. */
    private List<File> rootSnapshot() {
        synchronized (sinkDirs) {
            return new ArrayList<File>(volumeRoots);
        }
    }

    /** The whole copy, on the export thread. Nothing here runs on the control loop. */
    private void runExport(List<File> roots, List<File> sinks, boolean force) {
        try {
            // The loop must always win the CPU. This copy is never urgent.
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }
        int files = 0;
        long rows = 0;
        int failures = 0;
        List<String> where = new ArrayList<String>();
        try {
            List<File> sources = exportSources(sinks, rootSnapshot());
            if (sources.isEmpty()) {
                exportStatus = "no logs to copy yet";
                return;
            }
            for (int i = 0; i < roots.size(); i++) {
                File root = roots.get(i);
                synchronized (exportedVolumes) {
                    exportedVolumes.add(root.getAbsolutePath());
                }
                File destDir = new File(root, EXPORT_DIR);
                if (!force && markedThisBoot(destDir)) {
                    continue;
                }
                CsvLogger.Export r = CsvLogger.exportBacklog(destDir, sources);
                files += r.filesWritten;
                rows += r.rowsCopied;
                failures += r.failures;
                if (r.failures == 0) {
                    markThisBoot(destDir);
                }
                if (r.filesWritten > 0 || r.upToDate > 0) {
                    where.add(destDir.getAbsolutePath());
                }
            }
            exportFiles = files;
            exportRows = rows;
            exportDirs = where.toArray(new String[0]);
            if (where.isEmpty()) {
                exportStatus = failures > 0
                        ? "export failed on " + failures + " file(s)"
                        : "nothing to copy";
            } else {
                exportStatus = files + " file" + (files == 1 ? "" : "s") + ", " + rows
                        + " rows -> " + where.get(0)
                        + (where.size() > 1 ? " (+" + (where.size() - 1) + " more)" : "")
                        + (failures > 0 ? "   " + failures + " FAILED" : "");
            }
            note("export->" + files + "f/" + rows + "r");
        } catch (Throwable t) {
            Log.w(TAG, "runExport", t);
            exportStatus = "export failed: " + t;
        } finally {
            exporting = false;
        }
    }

    /** The sink to read the backlog from - one of them, not both, since the two internal sinks are identical copies. {@code /sdcard/FanLab} first, because it is the copy a person can find; a sink on a removable volume is never a source. */
    private List<File> exportSources(List<File> sinks, List<File> roots) {
        List<File> candidates = new ArrayList<File>();
        try {
            File pub = Environment.getExternalStorageDirectory();
            if (pub != null) {
                candidates.add(new File(pub, "FanLab"));
            }
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < sinks.size(); i++) {
            File d = sinks.get(i);
            if (d != null && !under(d, roots)) {
                candidates.add(d);
            }
        }
        for (int i = 0; i < candidates.size(); i++) {
            List<File> logs = logFiles(candidates.get(i));
            if (!logs.isEmpty()) {
                return logs;
            }
        }
        return new ArrayList<File>();
    }

    private static boolean under(File f, List<File> roots) {
        try {
            String p = f.getAbsolutePath();
            for (int i = 0; i < roots.size(); i++) {
                if (p.startsWith(roots.get(i).getAbsolutePath() + "/")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** The telemetry log and its rolled copies, live file included. */
    private static List<File> logFiles(File dir) {
        List<File> out = new ArrayList<File>();
        try {
            File[] all = dir.listFiles();
            if (all == null) {
                return out;
            }
            for (int i = 0; i < all.length; i++) {
                String n = all[i].getName();
                if (n.startsWith("fanlab") && n.endsWith(".csv") && all[i].isFile()) {
                    out.add(all[i]);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Has this volume already been offered the backlog on this boot? The marker holds the boot's wall-clock instant, so a stick left plugged in is not re-scanned every 30 s and one brought back tomorrow is. */
    private boolean markedThisBoot(File destDir) {
        java.io.BufferedReader r = null;
        try {
            File m = new File(destDir, EXPORT_MARK);
            if (!m.isFile()) {
                return false;
            }
            r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(m), "UTF-8"));
            long was = Long.parseLong(r.readLine().trim());
            return Provenance.sameBoot(was, bootWallMs());
        } catch (Throwable e) {
            return false;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void markThisBoot(File destDir) {
        try {
            List<File> one = new ArrayList<File>();
            one.add(destDir);
            // Its own formatter: isoFmt belongs to the control loop and SimpleDateFormat
            // is not safe to share across threads.
            String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(System.currentTimeMillis()));
            CsvLogger.writeWhole(one, EXPORT_MARK, bootWallMs() + "\n"
                    + "FanLab copied the log backlog here at " + when + ".\n"
                    + "Delete this file to have everything copied again on the next "
                    + "insertion.\n");
        } catch (Throwable t) {
            Log.w(TAG, "export mark", t);
        }
    }

    /** When this boot happened, in wall-clock terms. Constant for the life of the boot. */
    private static long bootWallMs() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26 || notifications == null) {
            return;
        }
        try {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Fan telemetry", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shows the live LED temperature and fan duty.");
            ch.setShowBadge(false);
            notifications.createNotificationChannel(ch);
        } catch (Throwable t) {
            Log.w(TAG, "createChannel", t);
        }
    }

    private int smallIcon() {
        try {
            int id = getResources().getIdentifier("ic_stat", "drawable", getPackageName());
            if (id != 0) {
                return id;
            }
        } catch (Throwable ignored) {
        }
        return android.R.drawable.ic_menu_info_details;
    }

    private Notification buildNotification(String title, String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(title);
        b.setContentText(text);
        b.setSmallIcon(smallIcon());
        b.setOngoing(true);
        b.setShowWhen(false);
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            b.setContentIntent(PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT));
        } catch (Throwable ignored) {
        }
        return b.build();
    }

    private void updateNotification(Sample s) {
        try {
            if (notifications == null) {
                return;
            }
            String title = "FanLab " + Mode.name(s.mode)
                    + "  " + (Double.isNaN(s.degC) ? "--" : Sample.fmt1(s.degC)) + " C"
                    + "  fan " + (s.fanCtrl < 0 ? "--" : Integer.toString(s.fanCtrl)) + "%";
            String text = "rows " + csvLines
                    + (failSafeLatched ? "  FAIL-SAFE" : "")
                    + (writeFailures > 0 ? "  write errors " + writeFailures : "");
            notifications.notify(NOTIF_ID, buildNotification(title, text));
        } catch (Throwable t) {
            Log.w(TAG, "updateNotification", t);
        }
    }

    /** Start the service if it is not running, and tell it to re-read the preferences. */
    public static void poke(Context c, String action) {
        try {
            String a = action == null ? ACTION_REFRESH : action;
            FanService s = instance;
            if (s != null && ACTION_REFRESH.equals(a)) {
                s.onPrefsChanged();
                return;
            }
            Intent i = new Intent(c.getApplicationContext(), FanService.class);
            i.setAction(a);
            if (Build.VERSION.SDK_INT >= 26) {
                c.getApplicationContext().startForegroundService(i);
            } else {
                c.getApplicationContext().startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "poke", t);
        }
    }
}
