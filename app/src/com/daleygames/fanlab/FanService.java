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
 * Runs in the foreground with a notification (mandatory on API 28) and returns
 * START_STICKY, so the platform brings it back if it is killed. It takes no wakelock, and
 * takes none anywhere: if the projector suspends, the loop stops with it, which is correct
 * - while early suspended the kernel forces every fan_ctrl write to 10 anyway, and it
 * re-imposes 55% on resume regardless of what userspace had asked for. The loop therefore
 * re-reads the node every second and rewrites when reality has drifted from intent, rather
 * than assuming a write stuck. What that costs is one measurement, and it is a cost worth
 * paying: see "the cooldown, and why it is not logged" further down.
 *
 * Safety invariants enforced here:
 * <ul>
 *   <li>the whole tick body is inside try/catch(Throwable); the catch writes
 *       {@link FanIo#FAIL_SAFE_DUTY};</li>
 *   <li>three consecutive implausible temperature readings in CURVE mode write
 *       {@link FanIo#FAIL_SAFE_DUTY} and latch until a good reading returns;</li>
 *   <li>even with re-assertion switched off, a read-back <i>lower</i> than the intended
 *       duty is always corrected - the app will leave the fan higher than it meant to,
 *       never lower;</li>
 *   <li>stopping the service while it was driving the fan writes
 *       {@link FanIo#FAIL_SAFE_DUTY} on the way out, so a crash or a kill can never
 *       leave a low duty behind;</li>
 *   <li>the LED drive override is applied <i>only</i> while this app is the temperature
 *       controller, and the stock table goes back on every path out of that state. See
 *       {@link LedDrive} for why the two are inseparable;</li>
 *   <li>CAIC is armed rather than set: the register is written, a fifteen-second countdown
 *       runs <b>here</b> rather than in the activity, and the preference is persisted only
 *       once someone confirms the picture survived. See {@link CaicArm}.</li>
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

    /**
     * How often the brightness mode alone is re-read, while the LED drive override is on.
     *
     * The kernel applies the stock LED table synchronously inside its own {@code rgblevel}
     * write, so a mode change puts stock brightness on the screen before this app can know
     * it happened. Measured on the projector: at 156 ms after the write the panel was still
     * at stock 75, and the override did not land until 597 ms. That is a visible flick to
     * the old brightness and back, and the owner saw it before any log did.
     *
     * Nothing can remove that window -- the kernel wins the race by construction -- but its
     * width is ours. Re-reading at 50 ms instead of once a tick cuts it to about three
     * frames, which is where it stops being a flick and starts being a step.
     *
     * It cannot be closed entirely from user space, and the docs say so rather than
     * implying the override is seamless.
     *
     * Cheap on purpose: this path reads {@code rgblevel} and nothing else, whose show
     * handler returns a stored int. It must never read {@code rgbcurrent}, which costs four
     * SPI transactions per call.
     */
    private static final long LED_FAST_MS = 50L;

    /**
     * How long after a brightness-mode change the override keeps re-asserting itself.
     *
     * Long enough to outlast the kernel's own four SPI writes, and bounded so a projector
     * that never confirms cannot leave this hammering the bus. It stops the moment a
     * read-back agrees.
     *
     * Measured rather than guessed: with a 600 ms window the projector still showed stock
     * on the common channels five seconds after a mode change, because the kernel's
     * sequence was not finished when the window closed and the ordinary rewrite limit then
     * held the repair off. Three seconds covers the sequence with room to spare, and the
     * loop exits as soon as the hardware agrees, so the full window is only ever spent
     * when something is genuinely wrong.
     */
    private static final long LED_SETTLE_MS = 3000L;

    // There is deliberately no fixed hold-off after a mode change. An earlier version
    // waited 200 ms for the kernel to finish its four SPI writes, which was a guess at a
    // number the hardware will state outright: while the kernel is mid-sequence the
    // channels disagree with each other, and when it has finished they agree. Waiting for
    // agreement is both safer than a guess and faster than the 200 ms it replaced -- the
    // kernel was measured finishing inside 117 ms, and the owner uses the near-instant
    // jump between brightness modes to compare them by eye, so the delay is a feature
    // being taken away rather than an implementation detail.
    private static final int RESCAN_EVERY_TICKS = 30;
    private static final int NOTIF_EVERY_TICKS = 5;
    /** Consecutive bad temperature reads tolerated in CURVE mode before failing high. */
    private static final int FAILSAFE_AFTER_BAD_READS = 3;

    /**
     * How long the last-seen-engine-on stamp may be from the value in {@link Prefs}.
     *
     * The stamp is only ever read after the engine has been off, so a minute of staleness
     * is a minute of error on a quantity measured in hours. Writing it every second would
     * put a preference commit on the control loop's path for no accuracy at all.
     */
    private static final long STAMP_EVERY_MS = 60000L;

    /**
     * How often the CAIC state is read back from the DLPC, at most. Each read-back is a
     * picoreg write plus a {@code logcat} exec on a thread of its own, so one a minute is
     * the budget; the write path is edge-triggered and does not wait for it.
     */
    private static final long CAIC_READ_EVERY_MS = 60000L;
    /** A read-back is brought forward to this long after a write, so the screen answers soon. */
    private static final long CAIC_READ_AFTER_WRITE_MS = 3000L;
    /** The bound on one read-back's round trip. On its own thread, so this stalls nothing. */
    private static final long CAIC_READ_TIMEOUT_MS = 4000L;
    /** After a failed CAIC write, how long before the tick tries again. */
    private static final long CAIC_RETRY_AFTER_FAIL_MS = 60000L;

    // ---- state the UI reads; single process, so plain volatiles are enough ----
    public static volatile FanService instance;
    public static volatile Sample lastSample;
    public static volatile long csvLines;
    public static volatile String[] csvPaths = new String[0];
    public static volatile String[] csvBroken = new String[0];
    public static volatile int writeFailures;
    public static volatile int writesDone;
    public static volatile boolean failSafeLatched;
    public static volatile String statusLine = "not started";

    // ---- AUTO and VERIFY. Non-null means that session is running right now. ----
    // Deliberately NOT persisted in Prefs: a sweep must never resume by itself after a
    // process kill or a reboot, with the white field gone and nobody watching.
    public static volatile SweepEngine sweepEngine;
    public static volatile HoldSession holdSession;
    /** One line describing the session, for the activities. */
    public static volatile String sweepLine = "";
    /** Where the sweep files have been written so far. */
    public static volatile String[] sweepFiles = new String[0];
    /** The most recent DLPC temperature attempt, so the UI can say so loudly. */
    public static volatile PicoReg.Reading lastDlpc;

    // ---- CAIC ----
    /**
     * What this process last wrote to the DLPC's LED output control method (0x50):
     * -1 never, 0 off, 1 on. The hand-back paths write off only when this is 1, so a
     * machine this app never touched is never written to on the way out.
     */
    public static volatile int caicWritten = -1;
    /** The last CAIC write failed, and the tick is backing off before retrying. */
    public static volatile boolean caicWriteFailed;
    /**
     * The most recent 0x51 read-back, or null if none has run. Only the system variant
     * runs one, because only it can read the kernel log the answer lands in; on the plain
     * build this stays null and the screen says "unverified", which is the truth.
     */
    public static volatile PicoReg.CaicReading caicReadback;
    /** rgblevel the previous tick saw, so a brightness-mode change can re-assert the write. */
    private int lastRgbSeen = -1;
    /** Monotonic time of the last read-back attempt; 0 means never. */
    private long caicReadMonoMs;
    /** Monotonic time of the last failed write; 0 means never. */
    private long caicFailMonoMs;
    /** A read-back is in flight, so a slow one is never stacked on another. */
    private volatile boolean caicReading;
    /** The previous read-back's summary, so the log carries the change and not the minute. */
    private volatile String lastCaicReadLine = "";
    /** Which build this is. Read once; it decides whether a read-back can ever succeed. */
    private boolean systemVariant;

    /**
     * The arm-and-confirm countdown for CAIC.
     *
     * Static and final, for two reasons. The activity has to be able to arm it on the press
     * that also starts the service, when {@link #instance} is still null; and the window has
     * to outlive the activity, because a countdown that dies with the screen it was drawn on
     * is not a safety net. It holds no preference, so a process death ends an unconfirmed
     * arm rather than carrying it anywhere.
     */
    public static final CaicArm caicArm = new CaicArm();

    // ---- the LED drive override ----
    /**
     * The override's own state machine: what it believes is on the hardware, its ceiling
     * latch and its rate limits. Not static -- it is the service's, and the two paths that
     * reach it from another thread ({@link #releaseControl} and the session starts) go
     * through {@link #restoreLedDriveNow}, which is synchronized inside {@link LedDrive}.
     */
    private final LedDrive ledDrive = new LedDrive();

    /**
     * The override may only <b>start</b> here: at a service start, a settings change, or the
     * tick the mode becomes one this app controls.
     *
     * <b>Never spontaneously mid-run</b>, and that is a measurement rather than a
     * preference. The owner's ear caught a 14-point cumulative fan sweep, and switching the
     * boost on under LINEAR part way through a session makes it walk about 12 duty points at
     * one per 5 s -- the same event, arriving by a different route. Gating the <i>start</i>
     * rather than the whole thing is what lets the override survive a standby or a
     * light-engine cycle without either re-applying out of nowhere or silently staying off.
     */
    private boolean ledDriveArmed;

    /** Was this app the temperature controller last tick? Edge-triggers the arm above. */
    private boolean lastLedControlling;

    /** Set by the tick: is the fast brightness-mode watch worth running right now? */
    private volatile boolean ledFastArmed;

    /** The brightness mode the fast watch last acted on, or -1 before it has run. */
    private volatile int ledFastLevel = -1;

    /** Last plausible light-engine temperature, so the fast path can honour the trip. */
    private volatile double ledFastC = Double.NaN;

    /** While non-zero, the deadline until which a mode change is still being chased. */
    private volatile long ledFastSettleUntil;

    /** Was LINEAR's ceiling promoted for the boost last tick? Edge-triggers the log note. */
    private boolean linearCeilingWasRaised;

    /**
     * One phrase describing the override, for the screen and the broadcast reply:
     * {@code off}, {@code stock}, {@code applied 90/84}, or a reason it is being held off.
     */
    public static volatile String ledDriveStatus = "off";

    private HandlerThread thread;
    private Handler handler;
    /** Housekeeping, so nothing the control loop does has to wait for a preference. */
    private HandlerThread houseThread;
    private Handler house;
    private CsvLogger csv;
    private final FanCurve curve = new FanCurve();
    private final FanLinear linear = new FanLinear();
    private PowerManager power;
    private NotificationManager notifications;

    private int lastWritten = -1;
    /** Whether the previous tick was driving, so a transition to OFF can be caught. */
    private boolean wasDriving;

    /** Was the SoC guard the binding constraint last tick? Edge-triggers the log note. */
    private boolean guardWasBiting;

    /** Was LINEAR out of authority last tick? Edge-triggers the log note. */
    private boolean linearWasSaturated;

    /** Duty points the SoC guard is currently adding, for the screen. 0 when inert. */
    public static volatile int guardBoost;

    /**
     * LINEAR out of authority: pegged at its ceiling duty and still too hot, or at its
     * floor and still too cold. For the screen; the log gets it as a note on the edge.
     */
    public static volatile boolean linearSaturated;

    /** Is the thermal governor throttling right now? For the screen. */
    public static volatile boolean throttling;

    /**
     * Seconds spent throttled since the service started.
     *
     * The fan cannot prevent throttling outright: it has about 9.8 C of authority over the
     * die from the operating point and the guard deliberately spends only two thirds of
     * that, because the rest costs more noise than it is worth. So the question a log has
     * to be able to answer is not whether throttling could happen but whether it did, and
     * for how long. Nothing else in the system records that.
     */
    public static volatile long throttledSec;

    /** Was it throttling last tick? Edge-triggers the log note. */
    private boolean wasThrottling;

    /** Which run this is. Fixed for the life of the service; on every row. */
    private int session;

    /**
     * Light-engine state the previous tick saw: 1 on, 0 off, -1 not established yet.
     *
     * Tri-state, deliberately unlike the {@code engineOn} the curve is given. There, an
     * unreadable {@code led_status} counts as "on", because assuming the light engine is
     * running is the conservative choice when the question is how hard to cool. Here an
     * unreadable node must not invent a power-on edge and put a fabricated ambient reading
     * in the log, so it holds the previous state instead.
     */
    private int lastEngineState = -1;

    /** Mirror of {@link Prefs#engineOnWallMs} and its two companions; see offDurationMs. */
    private long engineOnWallMs;
    private long engineOnMonoMs;
    private long engineOnBootMs;
    /** Monotonic time the stamp was last persisted, rate-limiting the write. */
    private long stampedMonoMs;

    /** Monotonic time a read-back last disagreed with what we wrote; 0 means never. */
    private long lastForeignMonoMs;

    /** The duty commanded on the previous tick, and when it last moved. */
    private int lastDesired = -1;
    private long dutyChangedMonoMs;

    /**
     * The stock ladder's kill switch as {@link #syncStockLadder} last read it, or null if
     * it has never been readable. That method owns the property and is the only thing that
     * reads it, so caching the value there is what keeps the exclusive-control flag off
     * the control loop's property path entirely.
     */
    private volatile String ladderProp;
    /** Latched once the service is being torn down; stops the tick re-taking the node. */
    private volatile boolean stopped;
    private int badReads;
    private int ticks;
    private boolean lastInteractive = true;
    private volatile boolean resumePending;
    private volatile boolean prefsDirty;
    private volatile boolean running;
    private String pendingNote = "";

    // ---- sweep plumbing ----
    // volatile because the activities call startSweep/startVerify on the UI thread while
    // the control loop reads these on its own thread.
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
                    // Filed, not acted on. The broadcast arrives while the platform still
                    // holds a wakelock of its own, so it catches the edge the 1 Hz poll can
                    // miss when the CPU suspends before the next tick; the note is picked
                    // up by whichever tick runs next, which may not be until resume. That
                    // costs nothing while the loop is stopped, which is the whole reason
                    // this stayed when the cooldown lock went.
                    note("suspend");
                }
            } catch (Throwable t) {
                Log.w(TAG, "powerReceiver", t);
            }
        }
    };

    /**
     * A volume was mounted. Detection was previously the 30 s timer and nothing else, so
     * a stick could sit in the socket for half a minute before anything wrote to it.
     *
     * The timer stays: this only makes detection prompt, and both paths converge on
     * {@link #rescanSinks}, which is idempotent. Posted to the loop thread so the sink
     * list has one writer.
     */
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

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // A fresh instance has written nothing. The previous one, if there was one in this
        // process, handed back in its onDestroy; a leftover from a process that died is
        // what Prefs.caicDriven is for, and the first tick checks it.
        caicWritten = -1;
        caicWriteFailed = false;
        caicReadback = null;
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
            // Read on the main thread, once, before the loop exists. From here the loop
            // owns these three and pushes changes back through the housekeeping thread,
            // so the 1 Hz path never touches a preference for them at all.
            engineOnWallMs = Prefs.engineOnWallMs(this);
            engineOnMonoMs = Prefs.engineOnMonoMs(this);
            engineOnBootMs = Prefs.engineOnBootMs(this);
            session = Prefs.nextSession(this);
        } catch (Throwable t) {
            Log.w(TAG, "onCreate session", t);
        }
        try {
            // One fixed name, deliberately. A timestamp per service start looks tidier,
            // but CsvLogger prunes by file stem, so every start opened a fresh stem that
            // the previous run's cap could not reach -- six capped files per boot, kept
            // for ever. Appending to one name makes the cap mean what it says. Run
            // boundaries stay visible in the data: the first row after a start carries a
            // "resync@...:service_start" note and the session column steps, and the epoch
            // column shows the gap.
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
            // MEDIA_MOUNTED carries the mount point as a file: URI, and a filter without
            // the scheme matches nothing at all.
            m.addDataScheme("file");
            registerReceiver(mediaReceiver, m);
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiver media", t);
        }
        try {
            // A thread of its own for the one thing that must never happen on the control
            // loop: writing a preference. SharedPreferences.apply() looks free, but it
            // takes a lock a disk commit on another thread can already be holding, and the
            // loop's standing rule is that nothing on it can delay a cooling decision.
            // Background priority, and one write a minute at most.
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
            // note() rather than the tick's own builder: the loop has not run yet, so this
            // lands on the first row, which is where a run boundary belongs.
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
        // The launcher removed our task. The loop keeps going (START_STICKY), so there is
        // nothing to hand back here; do not disturb the fan.
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
            // nothing useful to do
        }
        // The LED drive first, and before the fan, for the reason releaseControl() gives:
        // the restore rewrites rgblevel, the stock ladder answers that by writing its own
        // tier floor, and with the loop already stopped the fail-safe write below has to be
        // the last thing that touches the node. Unconditional, unlike the fan handback --
        // forceRestore does nothing at all unless an override is believed to be applied.
        try {
            restoreLedDriveNow("service_stop");
        } catch (Throwable t) {
            Log.e(TAG, "onDestroy leddrive", t);
        }
        // Hand the hardware back in a state that cannot cook it. If we were driving the
        // fan and we are going away, the last thing we wrote may be low and nothing else
        // is guaranteed to write for minutes.
        try {
            // NOT re-derived from Prefs: ACTION_STOP calls releaseControl() first, which
            // sets mode to OFF, so asking the preferences here would answer "was not
            // driving" and skip the handback on precisely the path that needs it.
            boolean wasDriving = this.wasDriving
                    || sweepEngine != null || holdSession != null;
            if (wasDriving) {
                // Give the fan back to the stock controller on the way out. Without this
                // a normal stop leaves the ladder disabled with nobody driving, which
                // persists across reboot.
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
        // The display controller too: if this process turned CAIC on, it turns it off on
        // the way out, and a process that never wrote 0x50 does not start now. The setting
        // survives, so a restart with it on writes on again -- but an unconfirmed arm is
        // not a setting and does not survive anything, which is the whole point of it.
        try {
            caicArm.cancel();
            handBackCaic("service_stop");
        } catch (Throwable t) {
            Log.e(TAG, "onDestroy caic", t);
        }
        // Close out a session that was still running, so the files on the stick describe
        // what happened rather than stopping mid-sentence.
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
            // nothing useful to do
        }
        try {
            unregisterReceiver(powerReceiver);
        } catch (Throwable ignored) {
            // not registered
        }
        try {
            unregisterReceiver(mediaReceiver);
        } catch (Throwable ignored) {
            // not registered
        }
        try {
            if (csv != null) {
                csv.close();
            }
        } catch (Throwable ignored) {
            // nothing useful to do
        }
        try {
            if (thread != null) {
                thread.quit();
            }
        } catch (Throwable ignored) {
            // nothing useful to do
        }
        try {
            if (houseThread != null) {
                // quitSafely: a preference write already queued is worth finishing, and it
                // is the last-seen-engine-on stamp that the next power-on measures from.
                houseThread.quitSafely();
            }
        } catch (Throwable ignored) {
            // nothing useful to do
        }
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ commands

    /**
     * Called by the UI after it changes a preference. Only raises a flag: the actual
     * re-sync happens on the loop thread, so nothing touches sysfs from the UI thread
     * while a D-pad key is being held down on the slider.
     */
    public void onPrefsChanged() {
        prefsDirty = true;
    }

    /**
     * Stop driving the fan and hand it back at the fail-safe duty.
     *
     * 83 is loud. It is also the stock controller's own maximum, and it is the only value
     * that is unconditionally safe to leave behind: the stock ladder will not necessarily
     * write anything for minutes (it only writes when the rounded temperature changes),
     * so releasing at a low duty could leave the machine under-cooled with nothing
     * watching.
     */
    public void releaseControl() {
        try {
            Prefs.setMode(this, Mode.OFF);
            // Re-arm the stock ladder BEFORE dropping the fan, so there is no instant in
            // which nothing at all is responsible for cooling. Mode is already OFF above,
            // so a tick racing us reaches the same conclusion.
            syncStockLadder();
            // And put the stock LED table back before the fan write, not after. Restoring
            // it rewrites rgblevel, which fires the stock ladder's mode-change branch and
            // slams that tier's floor into fan_ctrl; with the mode already OFF no tick will
            // take that back, so the fail-safe below has to be the last thing on the node.
            restoreLedDriveNow("release");
            boolean ok = FanIo.writeFailSafe();
            lastWritten = ok ? FanIo.FAIL_SAFE_DUTY : -1;
            curve.reset();
            linear.reset();
            // RELEASE means everything this app drives, and the CAIC write is a thing it
            // drives. The setting is cleared as well as the register, for the same reason
            // the mode is set OFF above: a tick racing us must reach the same conclusion,
            // not put it back a second later. An arm in flight is dropped rather than
            // expired, so the note says "release" and not "unconfirmed".
            Prefs.setCaic(this, false);
            caicArm.cancel();
            handBackCaic("release");
            note("release->" + FanIo.FAIL_SAFE_DUTY + (ok ? "" : " FAILED"));
            statusLine = ok
                    ? "released: fan handed back at " + FanIo.FAIL_SAFE_DUTY
                    : "release attempted but the write to fan_ctrl FAILED";
        } catch (Throwable t) {
            Log.e(TAG, "releaseControl", t);
        }
    }

    /**
     * Keep the stock controller's kill switch coupled to whether this service is really
     * driving the fan.
     *
     * <h3>Why this has to be automatic</h3>
     * {@code persist.sys.fanctrl.by.temperatue=0} stops the stock ladder writing. It
     * lives in {@code /data}, so it survives a reboot, a force-stop and an uninstall.
     * Setting it by hand and trusting this app to still be running is the one
     * configuration that can leave the projector with <i>no</i> fan controller at all.
     * TARGET.md names that as a thing never to do, and doing it by hand is exactly how
     * it happens anyway. So the switch is owned by the service's own state rather than
     * by whoever last typed a command:
     * <ul>
     *   <li>driving (MANUAL, CURVE, or a session) -&gt; the ladder is off, and
     *       {@link Prefs#autostart} is forced on so a reboot brings the driver back;</li>
     *   <li>not driving -&gt; the ladder is handed back, within one tick.</li>
     * </ul>
     *
     * <h3>What it cannot cover — and this list was wrong once already</h3>
     * <ul>
     *   <li><b>A low-memory kill or a crash</b> is covered: START_STICKY plus a
     *       foreground notification means the platform restarts the service.</li>
     *   <li><b>A reboot</b> is covered by autostart, forced on above.</li>
     *   <li><b>Force-stop, and "Disable" in Settings, are NOT covered.</b> They cancel
     *       the START_STICKY restart <i>and</i> put the package in the stopped state,
     *       which suppresses {@code BOOT_COMPLETED} as well — so neither the restart nor
     *       the reboot path fires. An earlier version of this comment claimed otherwise.</li>
     *   <li><b>{@code pm clear} is NOT covered</b>, and is worse: it also erases
     *       {@code autostart}, so nothing left on the device knows the ladder should come
     *       back.</li>
     *   <li><b>Uninstall is NOT covered.</b> It runs no code and never returns.</li>
     * </ul>
     * In every uncovered case the projector is not in danger — the 75 °C shutdown and the
     * kernel fan-stall watchdog are both outside userspace, and the fan holds the kernel's
     * own default rather than stopping — but nothing is responding to temperature. Hand
     * the fan back first, with the app's RESTORE control or {@code adblab/deploy.sh
     * revert}; or afterwards, with
     * {@code adb shell setprop persist.sys.fanctrl.by.temperatue 1}.
     *
     * <h3>Plain build</h3>
     * The plain build returns immediately without touching the property: it could not
     * write a {@code system_prop} anyway, and both variants can be installed at once, so
     * a second manager of one global switch would only ever fight the real one. It does
     * warn, once, if it is asked to drive while the stock ladder is still armed -- that
     * combination cannot work and the symptom (a duty that will not stay put) looks
     * exactly like the bug this project exists to fix.
     */
    private synchronized void syncStockLadder() {
        try {
            // Decide INSIDE the lock. Taking `driving` as a parameter let a tick that was
            // preempted between evaluating it and acting on it re-disable the ladder after
            // releaseControl() had already re-armed it -- narrow, but it ends with the
            // stock controller off and this service on its way out.
            boolean driving = !stopped && drivesUnattended(Prefs.mode(this));
            // Read once, at the top, and keep it: this method is the only thing that reads
            // the property and it already runs every RESCAN_EVERY_TICKS seconds, so the
            // exclusive-control flag can take the cached value and cost the control loop
            // nothing. Up to thirty seconds stale, which is well inside that flag's own
            // sixty-second window. Read even in the plain build's not-driving case, where
            // nothing used to read it, so the flag can say "no" rather than "cannot tell".
            String have = SysProps.get(SysProps.PROP_FANCTRL_BY_TEMP);
            ladderProp = have;
            // Only the platform-signed build may own this switch. Both variants can be
            // installed at once (that is deliberate -- the plain one is the zero-
            // commitment measuring instrument), and if both tried to manage a single
            // global property they would fight: the idle one re-arming the ladder that
            // the driving one just disabled. Today the plain build's write simply fails,
            // so the fight is invisible; relying on a permission denial to enforce a
            // design invariant is not a plan. Make it explicit instead.
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
                // The stock ladder is still writing and will fight us. Not dangerous --
                // two controllers both cooling -- but the duty will not hold, and the
                // owner should be told why rather than left to hear it.
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
     * **MANUAL is deliberately excluded.** Disabling the ladder for MANUAL would remove
     * the only thing supervising a duty the user typed by hand, and MANUAL has no
     * temperature logic at all -- not even the three-bad-reads fail-safe that CURVE has.
     * With the ladder armed, MANUAL is exactly as safe as it was before any of this:
     * a silly value is corrected within one 15 s poll, and the audition still works
     * because {@link Prefs#reassert} rewrites the node every second and wins in between.
     * That is what re-assertion was built for.
     *
     * **LINEAR is included, on exactly the same footing as CURVE.** It responds to
     * temperature and it fails safe on a bad read, so it earns the ladder standing down and
     * it needs it: the stock ladder writing every 15 s would fight a controller walking a
     * point every 5 s, and two controllers at once is the audible cycling this project
     * exists to remove. {@link Mode#controls} is the one place that judgement is written
     * down, so this asks it rather than listing the modes again.
     *
     * A sweep or a hold session does need the node to itself, and both are transient,
     * in-memory, and impossible to resume across a restart.
     */
    private boolean drivesUnattended(int mode) {
        return Mode.controls(mode) || sweepEngine != null || holdSession != null;
    }

    private void note(String s) {
        synchronized (this) {
            pendingNote = pendingNote.length() == 0 ? s : pendingNote + " " + s;
        }
    }

    /**
     * Adopt the duty the hardware is actually at, and say which path did it.
     *
     * Four paths resync, and they mean four different things to whoever reads the log: a
     * service start is a run boundary, a settings change is deliberate, a resume is the
     * driver having silently reimposed 55 %, and a fail-safe recovery is the machine
     * having been in trouble. Only the resume path used to leave a note, so thirty-six
     * hours of field log contains no {@code resync@} at all -- while every session in it
     * visibly opens with the app taking the fan back off the stock ladder, 55 slewing down
     * to 46. The one the notes claimed to cover, the service start, was the silent one.
     *
     * Returns the note rather than filing it, because three of the four callers are inside
     * a tick and are building that row's note themselves; filing it would put a run
     * boundary on the row after the boundary.
     */
    private String resync(int duty, String path) {
        // Both controllers, always, whichever is driving. Only one of them is being asked
        // for a duty at any moment, but the other has to be holding the truth when the
        // owner flips between them to compare -- which is the entire reason LINEAR exists
        // alongside CURVE. Resyncing only the live one would make the first tick after
        // every switch a step from the idle controller's stale duty.
        curve.resync(duty);
        linear.resync(duty);
        return "resync@" + duty + ":" + path;
    }

    /**
     * Run something off the control loop. Used for preference writes, which the loop's
     * 1 Hz path may not do.
     *
     * Falls back to a one-shot daemon thread if the housekeeping looper never started, the
     * same shape {@link #exportBacklogAsync} uses: worth a thread rather than running it
     * here, because "here" is the thread that writes fan_ctrl.
     */
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

    // ------------------------------------------------------------------ the loop

    /**
     * Watch the brightness mode between ticks and put the override back the moment it
     * changes. See {@link #LED_FAST_MS} for why this exists at all.
     *
     * It re-posts unconditionally so it survives the override being switched off and on,
     * and does nothing but a single small read while disarmed. {@link LedDrive} is
     * synchronized, so racing the 1 Hz tick is safe by construction rather than by timing;
     * the read-back is passed as null because a mode change is an edge, and an edge applies
     * without needing to compare anything.
     */
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
                        // Inside the window the read-back is worth its four SPI reads: it
                        // is the only thing that says whether the kernel has finished.
                        LedDrive.Config cfg = Prefs.ledDrive(FanService.this);
                        String rbText = Sysfs.read(Sysfs.RGBCURRENT);
                        int[] rb = LedDrive.parseReadback(rbText);
                        if (rb != null && rb[1] == rb[2]) {
                            // Coherent: whatever is on the hardware, all of it is on the
                            // hardware. Either it is already ours, or the kernel has
                            // finished and this is the first safe moment to write.
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
                        // Incoherent or unreadable: the kernel is still pushing channels
                        // out, or the SPI read dropped. Writing now is what produced a
                        // colour cast on the projector. Wait for the next 50 ms look.
                    }
                }
            } catch (Throwable t) {
                // The fan is not involved here and the tick will do this again within a
                // second. Never let a brightness cosmetic take the loop down.
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
                // Nothing in tick() is supposed to throw. If it does, the projector must
                // still be cooled: write high, say so, and keep looping.
                Log.e(TAG, "tick", t);
                try {
                    if (Mode.writes(Prefs.mode(FanService.this))) {
                        FanIo.writeFailSafe();
                        failSafeLatched = true;
                    }
                } catch (Throwable ignored) {
                    // nothing left to try
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
            // Plausibility gate, same spirit as the thermistor's: a die below -40 C or
            // above 150 C is a bad read, not a temperature, and must not reach the log
            // looking like data.
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
            // assume awake; the worst case is a wasted write
        }

        boolean resumeEdge = resumePending || (interactive && !lastInteractive);
        boolean suspendEdge = !interactive && lastInteractive;
        resumePending = false;
        lastInteractive = interactive;

        StringBuilder note = new StringBuilder(takeNote());

        s.session = session;
        // Read like mode and the curve are: after the first load a preference read is a
        // lookup in an in-memory map, which is why the loop has always been allowed to do
        // it. Writes are the thing that is not allowed here.
        s.roomC = Prefs.roomC(this);

        boolean settingsChanged = prefsDirty;
        if (prefsDirty) {
            prefsDirty = false;
            badReads = 0;
            failSafeLatched = false;
            append(note, "settings changed " + resync(s.fanCtrl, "settings"));
        }

        // Own the stock ladder's kill switch from here, rather than leaving it to
        // whoever last set it by hand. Re-checked periodically as well as on a settings
        // change, because the property is global: another tool, or an earlier session's
        // leftovers, can put it back underneath us. On the first tick too, so the first
        // row can say whether the ladder is stood down instead of leaving it blank.
        if (settingsChanged || ticks == 1 || ticks % RESCAN_EVERY_TICKS == 0) {
            syncStockLadder();
        }

        if (resumeEdge) {
            // The driver re-applies 55% on resume and after any stall, discarding what we
            // wrote. Adopt reality, then ramp from there instead of stepping.
            lastWritten = -1;
            append(note, resync(s.fanCtrl, "resume"));
        }

        // ---------------- the light engine, and ambient ----------------
        int engineState = s.ledStatus < 0 ? lastEngineState : (s.ledStatus == 0 ? 0 : 1);
        boolean engineOnEdge = engineState == 1 && lastEngineState != 1;
        if (engineOnEdge) {
            // A power-on. Record the reading and the evidence needed to grade it, rather
            // than gating on a threshold: the thermistor is still 4.7 C above its resting
            // value five hours in, so an hour-long gate would pass a reading several
            // degrees too warm with nothing in the log to show it. degC and soc_pll_c on
            // this row are the readings; off_s says whether to believe them.
            //
            // Fired on the first sample of a service start as well as on a real off-on
            // edge, because a service that has only just started cannot know the engine
            // was not switched on a moment before it. That shows up honestly as a
            // three-second off-duration, which is a row to discard, not a threshold.
            long off = offDurationMs(now, mono);
            s.offMs = off;
            append(note, "poweron off=" + (off < 0 ? "?" : (off / 1000L) + "s")
                    + "(" + offSource() + ")"
                    + " degC=" + Sample.fmt1(s.degC)
                    + " pll=" + Sample.fmt1(s.socC[0]));
        } else if (engineState == 0 && lastEngineState != 0) {
            // The edge only. Nothing follows it: the loop suspends with the machine, so the
            // fall towards the room is not sampled -- see "the cooldown, and why it is not
            // logged" below.
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

        // ---------------- CAIC ----------------
        // Edge-triggered, never per tick: the register is written when the setting
        // changes and re-asserted where the DLPC is known or suspected to be re-programmed.
        // The read-back is a minute apart at most and runs on its own thread.
        syncCaic(s, interactive, resumeEdge, engineOnEdge, note, mono);

        // ---------------- the LED drive override, first half ----------------
        // Loaded here because two different parts of the tick need the answer and neither
        // should ask twice: LINEAR's ceiling has to know about the boost before it steps,
        // just below, and the override itself is applied after the fan write, further down,
        // where an extra sysfs read and a couple of writes cannot get in front of a cooling
        // decision. The same question Prefs.ledBoostOn answers, asked from a config this
        // tick has to load anyway.
        LedDrive.Config ledCfg = Prefs.ledDrive(this);
        boolean ledFeatureOn = Prefs.ledDriveOn(this) && !ledCfg.isStock();

        // A read-back that is not what we last wrote is another writer on the node: the
        // stock ladder, or the kernel reimposing 55 % after a stall. It is a free
        // measurement -- fan_ctrl is read back before every write anyway -- and it is one
        // of the three things the exclusive-control flag rests on, so it is now watched in
        // every mode rather than only inside a session. Not while suspended: the kernel
        // forces every write to 10 there, so every read-back would look foreign.
        boolean foreign = interactive && lastWritten > 0 && s.fanCtrl >= 0
                && s.fanCtrl != lastWritten;
        if (foreign) {
            lastForeignMonoMs = mono;
        }

        // ---------------- decide ----------------
        // A running AUTO or VERIFY session overrides the ordinary mode. It is held in a
        // field, never in the preferences, so it can never survive a restart.
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
            // The detection is above, in every mode. A session additionally records it in
            // its own report, because for a measurement run a second writer does not just
            // contaminate a window, it invalidates the step.
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
                // The white field is the test apparatus. If the screen has gone, the run
                // is no longer measuring what it claims to, and the kernel is forcing
                // every write to 10 anyway. Stop, at the fail-safe duty.
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
            tracePhase = "hold";
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
                // No per-tick "engine-off" note. It made every engine-off tick look
                // interesting, which bypasses the decimation and spends a row a second on
                // the fact that nothing is happening. The edge is noted above and the
                // led_status column carries the state on every row, which is the
                // machine-readable form of the same fact.
                //
                // Log the guard only on the edge where it starts or stops being the
                // binding constraint. Logging it every tick while it holds would bypass
                // the heartbeat decimation and fill the file.
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
            // The same fail-safe path as CURVE, deliberately not a parallel one: three bad
            // reads write 83 and latch, and the latch clears through the shared resync so
            // the walk restarts from the duty actually on the node rather than from
            // wherever it had got to before the sensor failed.
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
                // LINEAR needs no new mode for the LED drive override -- it holds a
                // temperature, so it absorbs the extra heat by itself and pays for it in
                // fan. What it needs is a different number: at drive 90 the stock 52.0
                // costs duty 50 in a 24 C room where today it rests at 38, and gives up at
                // 29.1 C ambient instead of 32.6. The default moves to 54.0 while the
                // override is on -- inferred from the x1.18 scaling, never written to the
                // preferences, and never applied to a ceiling the owner set by hand.
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
                // On the edge only. The controller has no hold state, so while the ceiling
                // is unreachable this is true on every one of 720 ticks an hour, and a note
                // per tick bypasses the decimation and fills the file with the same fact.
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
        // Saturation is a statement about a walk that is currently happening. With LINEAR
        // not driving -- another mode, a session, or its own fail-safe latched -- there is
        // no walk, so the flag goes out rather than leaving the screen asserting the last
        // thing that was true. Clearing the edge too means recovering into a still-
        // unreachable ceiling notes it again, which is the honest thing: it is news each
        // time the controller starts and cannot get there.
        if (mode != Mode.LINEAR || sessionRunning || failSafeLatched) {
            linearSaturated = false;
            linearWasSaturated = false;
        }
        // Handing back by any route -- the adb path sets mode=OFF and never calls
        // releaseControl() -- must still leave the fan somewhere safe. Without this the
        // duty simply stops being updated and the fan stays wherever the curve last put
        // it (30, or idleDuty 10), with the stock ladder only writing when the rounded
        // temperature next changes, which at equilibrium is exactly when it does not.
        // Sampled in every mode, including OFF and MANUAL. A record that only exists
        // while the curve is driving cannot answer the one comparison worth making --
        // whether this curve throttles the machine more often than the stock ladder did.
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

        // ---------------- write ----------------
        if (desired > 0) {
            if (!interactive) {
                // Early-suspended: the kernel forces every write to 10, so writing here
                // achieves nothing except log noise. Reality is re-adopted on resume.
                //
                // On the edge only, for the same reason the engine-off note is: whenever
                // the screen goes out without the loop stopping with it, this branch is
                // reached every second, and a note every second is a row every second.
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

        // ---------------- the LED drive override, second half ----------------
        // Deliberately after the write, exactly like the DLPC read below: one sysfs read
        // and up to two writes, none of which may sit in front of a cooling decision.
        //
        // The coupling rule is the entire safety case and it is a conjunction. Raising LED
        // output while this app is not the fan controller runs Presentation-class heat
        // under whatever fan ladder rgblevel happens to select, which is the hazard the
        // notes forbid outright -- so every term below is required, and any doubt about
        // one of them resolves to not-allowed.
        //
        // The one term where that reads oddly is led_status, which is tested for "not 0"
        // and so treats an unreadable node as "on", exactly as the curve does. It is the
        // conservative answer here as well, for a different reason: the hazard is the fan
        // coupling, and every term that establishes the coupling is checked above. Writing
        // LED currents to an engine that turns out to be off is inert -- there is nothing
        // lit to drive -- so guessing wrong in this direction costs one sysfs write.
        boolean ledControls = Mode.controls(mode);
        boolean ledInCharge = ledFeatureOn && !stopped && ledControls
                && !sessionRunning && !failSafeLatched;
        // Arming. The override may only start on one of three events -- see ledDriveArmed
        // for the measurement behind that -- and lastLedControlling starts false, so the
        // service's own first tick in CURVE or LINEAR is an entry edge and needs no case
        // of its own.
        //
        // Losing the display or the light engine is a pause, not a disarm: both come back
        // through a discontinuity the loop already resyncs across, so re-applying there is
        // the service-start case rather than a change nobody asked for. Everything else --
        // the mode leaving, a session taking the node, the fail-safe latching, the feature
        // being switched off -- clears the arm, so coming back takes a deliberate act.
        boolean ledModeEntered = ledControls && !lastLedControlling;
        lastLedControlling = ledControls;
        if (!ledInCharge) {
            ledDriveArmed = false;
        } else if (settingsChanged || ledModeEntered) {
            ledDriveArmed = true;
        }
        boolean ledAllowed = ledInCharge && ledDriveArmed && interactive && s.ledStatus != 0;
        try {
            // One extra read a tick, and only while the feature is asking for something:
            // with the override off there is nothing to compare a read-back against.
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
            // Hand the fast watch its inputs. Arming it only while the override is
            // genuinely in force keeps it a no-op the rest of the time, and re-reading
            // rgblevel here means a mode change the tick saw first is not acted on twice.
            ledFastLevel = s.rgblevel;
            ledFastC = Thermistor.plausible(s.degC) ? s.degC : Double.NaN;
            ledFastArmed = ledAllowed && ledFeatureOn && !ledCfg.isStock();
        } catch (Throwable t) {
            // Sysfs does not throw and LedDrive catches its own arithmetic, so this is the
            // outermost belt: the fan has already been written this tick and nothing about
            // the LEDs is worth losing a tick over.
            Log.w(TAG, "leddrive", t);
        }

        // ---------------- exclusive control ----------------
        // The three conditions and why they are these three are in Provenance. The kill
        // switch comes from the cache syncStockLadder fills, so this costs the loop no
        // property read at all.
        s.exclusive = Provenance.exclusive(mode, sessionRunning, ladderProp, mono,
                lastForeignMonoMs);

        // ---------------- convergence ----------------
        // Converged or still warming up was being read off the trace by eye. The
        // controller knows: it is catching up while the duty it inherited is not yet the
        // duty the curve wants, and the time since the duty last moved says whether what
        // it arrived at has held. Creep is the project's first open question, and this
        // makes it a filter rather than a reconstruction.
        //
        // Blank in every mode but CURVE: with the curve not driving there is nothing to
        // converge on, and a 0 there would read as "converged".
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

        // ---------------- the once-per-step readings ----------------
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

        // ---------------- storage ----------------
        // Outside the logging guard on purpose. Which volumes are mounted is not a
        // logging question, and while the rescan lived inside it a stick inserted with
        // logging off went unnoticed -- and took the backlog export with it, which is the
        // one thing that only happens on the insertion edge.
        try {
            if (ticks % RESCAN_EVERY_TICKS == 1) {
                rescanSinks();
            }
        } catch (Throwable t) {
            Log.w(TAG, "rescan", t);
        }

        // ---------------- log ----------------
        try {
            if (csv != null && Prefs.logging(this)) {
                // Log every event, but only every Nth quiet second. A row a second of
                // "nothing changed" is 0.34 MB/hour that buries the lines that matter.
                // Anything that actually happened -- a write, a note, a session step, a
                // latched fail-safe -- is always recorded, so decimating the heartbeat
                // costs no information, only volume.
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

        // ---------------- session end ----------------
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

    // ------------------------------------------------------------------ ambient

    /**
     * How long the light engine had been off before it came on, milliseconds, or -1 when
     * there is nothing to go on. {@link Provenance#offDurationMs} carries the reasoning
     * and the two cases; this is the three-field mirror it reads from.
     */
    private long offDurationMs(long nowWallMs, long nowMonoMs) {
        return Provenance.offDurationMs(nowWallMs, nowMonoMs, engineOnWallMs,
                engineOnMonoMs, engineOnBootMs, bootWallMs());
    }

    /** Which of the two cases {@link #offDurationMs} answered from, for the note. */
    private String offSource() {
        return Provenance.offSource(engineOnWallMs, engineOnBootMs, bootWallMs());
    }

    /** Persist the stamp, off the control loop. See {@link #runOffLoop}. */
    // ---- CAIC ----

    /**
     * Keep the DLPC's LED output control method coupled to the CAIC setting.
     *
     * <h3>When it writes</h3>
     * <ul>
     *   <li>On the edge where the setting turns on, or on the first tick of a service
     *       that starts with it on: {@code w 50 1 1}.</li>
     *   <li>On the edge where it turns off, if this process ever wrote on: {@code w 50 1 0}.</li>
     *   <li>Re-asserted on -- one write, not a loop -- when {@code rgblevel} changes,
     *       because the kernel re-programs the DLPC LED registers on a brightness-mode
     *       change; after a resume edge and a light-engine power-on, because the DLPC may
     *       be re-initialised across a display-off and a power cycle certainly resets it.
     *       Whether any of those actually clears 0x50 is not known; a write that was not
     *       needed costs one I2C transaction, and a write that was needed and not made
     *       costs the experiment.</li>
     *   <li>Once, on the first tick, if a previous process turned CAIC on and died before
     *       turning it off and the setting is now off: see {@link Prefs#caicDriven}.</li>
     * </ul>
     * Never while the display is off -- the DLPC is not reliably up, and the resume edge
     * covers it -- and never after the stop latch. A failed write backs off for a minute
     * rather than retrying every tick, because a note per tick is a row per tick.
     *
     * <h3>What it does not do</h3>
     * It does not infer the register's state from its own writes. What the DLPC is
     * actually doing is {@link #caicReadback}'s business, and that is null until a read
     * has genuinely returned a byte.
     */
    private void syncCaic(Sample s, boolean interactive, boolean resumeEdge,
                          boolean engineOnEdge, StringBuilder note, long mono) {
        // The countdown is swept first, above every gate below, so an unconfirmed arm
        // expires on the second it is due even with the display off -- an owner who cannot
        // see the picture is precisely who the window is for, and one who has walked away
        // is the case the activity could not have covered.
        //
        // The write that reverts it still obeys the gates: the DLPC is not reliably up
        // while the display is off, so the revert may have to wait for the resume edge.
        // CaicArm.reverting() is what carries the reason across that wait, so the note the
        // log eventually gets says "unconfirmed" rather than "setting".
        if (caicArm.poll(mono)) {
            if (Prefs.caic(this)) {
                // The setting went true while the window was open -- a shell sent
                // `--ez caic true`, which is a deliberate act by someone who has another
                // way in. There is nothing left to revert, so drop the debt rather than
                // leaving it owed against a write that is now wanted.
                caicArm.reverted();
            } else {
                append(note, "caic arm expired unconfirmed -> off");
            }
        }
        // An arm counts as wanting it on. The preference does not move until someone
        // confirms -- see confirmCaic, which is the only thing in the app that writes it
        // true -- so a process death here ends with CAIC off and nothing persisted.
        boolean wanted = Prefs.caic(this) || caicArm.armed(mono);
        boolean rgbEdge = lastRgbSeen >= 0 && s.rgblevel >= 0 && s.rgblevel != lastRgbSeen;
        if (s.rgblevel >= 0) {
            lastRgbSeen = s.rgblevel;
        }
        if (stopped || !interactive) {
            return;
        }
        if (caicFailMonoMs != 0L && mono - caicFailMonoMs < CAIC_RETRY_AFTER_FAIL_MS) {
            return;
        }
        if (wanted) {
            String why = caicWritten != 1 ? (caicWritten < 0 ? "start" : "setting")
                    : rgbEdge ? "rgblevel"
                    : resumeEdge ? "resume"
                    : engineOnEdge ? "poweron"
                    : null;
            if (why != null) {
                writeCaic(true, why, note, mono);
            }
        } else if (caicWritten == 1) {
            boolean unconfirmed = caicArm.reverting();
            if (writeCaic(false, unconfirmed ? "unconfirmed" : "setting", note, mono)
                    && unconfirmed) {
                caicArm.reverted();
                statusLine = "CAIC was not confirmed within " + (CaicArm.WINDOW_MS / 1000)
                        + " s, so it has been turned back off.";
            }
        } else if (caicArm.reverting()) {
            // The window closed but this process never got the on write onto the hardware,
            // so there is nothing to undo. Clear the latch rather than leaving a revert
            // owed for ever.
            caicArm.reverted();
        } else if (ticks == 1 && Prefs.caicDriven(this)) {
            // A previous process wrote on and never wrote off. Leave the machine as the
            // owner now wants it, once, and clear the memory.
            writeCaic(false, "leftover", note, mono);
        }

        // ---- the read-back, system variant only, at most once a minute ----
        // Gated on the experiment being in play: a build that has never written 0x50 and
        // is not asked to has no reason to send the DLPC a command a minute.
        if (systemVariant && (wanted || caicWritten >= 0) && !caicReading
                && (caicReadMonoMs == 0L || mono - caicReadMonoMs >= CAIC_READ_EVERY_MS)) {
            caicReadMonoMs = mono;
            readCaicAsync();
        }
    }

    /** @return true if the DLPC took the write. */
    private boolean writeCaic(boolean on, String why, StringBuilder note, long mono) {
        boolean ok = PicoReg.writeLedOutputControl(on);
        if (ok) {
            caicWritten = on ? 1 : 0;
            caicWriteFailed = false;
            caicFailMonoMs = 0L;
            append(note, "caic<-" + (on ? "on" : "off") + ":" + why);
            // Bring the next read-back forward, so the screen can say what the DLPC did
            // with the write within seconds rather than at the next minute boundary.
            caicReadMonoMs = mono - CAIC_READ_EVERY_MS + CAIC_READ_AFTER_WRITE_MS;
            if (Prefs.caicDriven(this) != on) {
                persistCaicDriven(on);
            }
        } else {
            caicWriteFailed = true;
            caicFailMonoMs = mono;
            append(note, "caic WRITE FAILED:" + why);
            statusLine = "cannot write " + PicoReg.NODE
                    + " - CAIC not applied; check the node exists and is 0777";
        }
        return ok;
    }

    /**
     * Write CAIC off if this process ever wrote it on. Called from the hand-back paths,
     * on whichever thread they run on; the register is the DLPC's, not the fan's, so it
     * is written directly rather than routed through the tick.
     */
    private void handBackCaic(String why) {
        if (caicWritten != 1) {
            return;
        }
        if (PicoReg.writeLedOutputControl(false)) {
            caicWritten = 0;
            note("caic<-off:" + why);
            persistCaicDriven(false);
        } else {
            note("caic hand-back WRITE FAILED:" + why);
            Log.w(TAG, "CAIC hand-back write failed (" + why + "); a power cycle clears it");
        }
    }

    private void persistCaicDriven(final boolean v) {
        runOffLoop(new Runnable() {
            @Override
            public void run() {
                try {
                    Prefs.setCaicDriven(FanService.this, v);
                } catch (Throwable t) {
                    Log.w(TAG, "persistCaicDriven", t);
                }
            }
        });
    }

    /**
     * Ask the DLPC what it is doing, on a thread that is neither the control loop nor
     * the housekeeping looper -- a hung {@code logcat} must be able to take nothing down
     * with it. Publishes to {@link #caicReadback} and files a note only when the answer
     * changes, plus the max-available-power word while CAIC reads on, since that number
     * moving with the content is the one piece of evidence the experiment can produce
     * without a light meter.
     */
    private void readCaicAsync() {
        caicReading = true;
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        PicoReg.CaicReading r = PicoReg.readLedOutputControl(CAIC_READ_TIMEOUT_MS);
                        caicReadback = r;
                        String line = "caic_read=" + r.state
                                + (r.known() ? "(" + r.source + ")" : "");
                        if (!line.equals(lastCaicReadLine)) {
                            lastCaicReadLine = line;
                            // CsvLogger.q quotes the note column, so the reason's commas
                            // and quotes are safe to pass through as they are.
                            note(r.known() ? line : line + " (" + r.reason + ")");
                        }
                        if (PicoReg.CAIC_ON.equals(r.state)) {
                            PicoReg.CaicPower p = PicoReg.readCaicMaxPower(CAIC_READ_TIMEOUT_MS);
                            if (p.rawWord >= 0) {
                                note("caic_maxpower=" + p.rawHex()
                                        + "(" + Sample.fmt1(p.watts) + "W?)");
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "caic read", t);
                    } finally {
                        caicReading = false;
                    }
                }
            }, "fanlab-caic-read");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            caicReading = false;
            Log.w(TAG, "readCaicAsync", t);
        }
    }

    /**
     * One line for the screen and the broadcast reply: the setting, whether this process
     * has written it, and what the DLPC said if anything has asked it.
     *
     * "unverified" is the plain build's permanent answer and the system build's answer
     * until the first read-back lands. It is not "on"; nothing here says on until a byte
     * has said so.
     */
    public static String caicSummary(boolean wanted) {
        PicoReg.CaicReading rb = caicReadback;
        boolean known = rb != null && rb.known();
        // The countdown outranks both: while it is running the setting still reads false,
        // because nothing is persisted until it is confirmed, and reporting that as "off"
        // would be the one moment the screen contradicts the picture.
        int left = caicCountdownSec();
        if (left > 0) {
            return "arming - " + left + " s to confirm, reverts on its own otherwise";
        }
        if (caicArm.reverting()) {
            return "reverting: not confirmed";
        }
        if (!wanted) {
            // Worth saying when the DLPC disagrees with the setting: the setting was turned
            // off but the register still reads on, which means the off write has not
            // happened or did not take.
            return known && PicoReg.CAIC_ON.equals(rb.state) && caicWritten == 1
                    ? "off (read back: still ON)" : "off";
        }
        if (caicWritten == 1) {
            return known ? "on (read back: " + rb.state + ")" : "on (unverified)";
        }
        return caicWriteFailed ? "on (WRITE FAILED)" : "on (not written yet)";
    }

    /**
     * Ask for CAIC and start the countdown. The tick writes {@code w 50 1 1}; nothing is
     * persisted, and in {@link CaicArm#WINDOW_MS} the tick writes it back off unless
     * {@link #confirmCaic} has been called.
     *
     * Static, and it arms before it pokes, because the same press is what starts the
     * service on a cold app: by the time the first tick runs the window is already open.
     */
    public static void armCaic(Context c) {
        caicArm.arm(SystemClock.elapsedRealtime());
        poke(c, ACTION_REFRESH);
    }

    /**
     * The owner confirmed the picture survived. <b>The only place in the app that stores
     * CAIC on</b> -- everything else arms it.
     *
     * @return false if the window had already closed, in which case nothing is stored: a
     *         press that arrives late is a press at a picture that has already come back.
     */
    public static boolean confirmCaic(Context c) {
        if (!caicArm.confirm()) {
            return false;
        }
        Prefs.setCaic(c, true);
        poke(c, ACTION_REFRESH);
        return true;
    }

    /** Drop an arm before its time. The tick writes off; nothing was ever persisted. */
    public static void cancelCaicArm(Context c) {
        caicArm.cancel();
        poke(c, ACTION_REFRESH);
    }

    /** Seconds left on the CAIC countdown, 0 when nothing is armed. For the screen. */
    public static int caicCountdownSec() {
        return caicArm.secondsLeft(SystemClock.elapsedRealtime());
    }

    // ---- the LED drive override ----

    /**
     * One phrase describing the override, for {@link #ledDriveStatus}.
     *
     * The distinction worth drawing is between "stock because nothing is asking" and "stock
     * because something is holding it off", because the second is a state the owner has to
     * be able to see the reason for: the row says Bright, the picture is not, and without
     * this the screen would just look wrong.
     */
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
            // The only state that needs the owner to do something. It is reached by the
            // fail-safe having latched at some point, which is deliberately not
            // self-clearing -- see ledDriveArmed.
            return "stock: held until the mode or the settings change";
        }
        return "stock";
    }

    /**
     * Put the stock LED table back, now, off the tick.
     *
     * The handback for the three paths that are not a tick: RELEASE, a session taking the
     * node, and service stop. Everything a tick can see -- the mode leaving CURVE or
     * LINEAR, the fail-safe latching, the feature being switched off -- is handled by
     * {@link LedDrive#decide} instead, on the tick it happens, because that is the method
     * that knows what is on the hardware and it restores within one second either way.
     *
     * {@link LedDrive#forceRestore} ignores the rewrite rate limit, since a handback is a
     * one-off and a duplicate {@code rgblevel} write is harmless, and it does nothing at
     * all unless an override is believed to be applied -- so calling this on a machine this
     * app never boosted writes nothing.
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

    // ---- the cooldown, and why it is not logged ----
    //
    // The loop suspends with the machine, so when the light engine goes off the LED
    // decaying towards the room goes unsampled. That is why led_status reads 1 in all 3389
    // rows of the first field log, and why the idle-duty-10 path still has no field
    // evidence behind it. Both are permanent limitations, not work outstanding.
    //
    // Holding the CPU up to watch the fall was built and taken back out. Two reasons, and
    // either alone is enough.
    //
    // It would not measure what it claims to. An idle SoC sits 13-15 C above the room and
    // warms the chassis the thermistor is mounted in, so a lock held long enough to see
    // the fall would be heating the thing being measured. The measurement corrupts itself,
    // and it does so plausibly: the curve would come out smooth and wrong.
    //
    // And the owner does not want the CPU held awake.
    //
    // The power-on capture above answers the ambient question instead, and needs no
    // wakelock: one direct reading per power-on, graded by off_s. The cooldown curve was
    // only ever corroboration for it.

    private static void appendEvent(StringBuilder sb, String s) {
        if (s == null || s.length() == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(';');
        }
        sb.append(s);
    }

    // ------------------------------------------------------------------ AUTO

    /**
     * Start the unattended thermal characterisation.
     *
     * @return false if it was refused, with {@link #statusLine} saying why. The two
     *         refusals are "already running something" and "the projector is already too
     *         warm to start" - a run beginning at 54 C would trip the 58 C ceiling within
     *         a step or two and waste ninety minutes.
     */
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
            // Before the session takes the node. A sweep owns rgblevel and drives the fan
            // to a schedule, so it must not start on top of an LED drive the report would
            // not mention -- and its own assertRgbLevel would reinstate the stock table a
            // second later anyway, silently, which is worse than doing it here on purpose.
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

    /**
     * Stop the sweep now. The fail-safe duty is written <b>first and synchronously</b>, so
     * ABORT is instant no matter what the loop thread is doing.
     */
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

    /**
     * Timestamp a human observation - "the fan just became audible". The one thing only a
     * person in the room can measure, and half the objective.
     */
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
            // nothing further to try
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

    // ------------------------------------------------------------------ VERIFY

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

    /** Record what the person in the room just reported about sharpness or noise. */
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

    /** Tell the log which pattern is on screen, so every check records what was looked at. */
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
            // nothing further to try
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

    /** Where the report actually landed, so it can be found without a shell. */
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

    // ------------------------------------------------------------------ session plumbing

    private long lastRgbWriteMs;

    /**
     * Hold the brightness mode the session asked for.
     *
     * Two things worth knowing, both of which show up in the log rather than as surprises:
     * every {@code rgblevel} change fires the stock controller's own mode-change branch,
     * which writes that tier's floor to {@code fan_ctrl} immediately - so the fan gets
     * slammed to 38/42/69-ish within one 15 s poll and the 1 Hz re-assert takes it back.
     * And {@code sc_projectorclient} keeps its own idea of the current brightness mode, so
     * it will not know we moved this underneath it.
     */
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
            // nothing useful to do
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
            // metadata only
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

    // ------------------------------------------------------------------ storage

    /**
     * Every removable volume root, e.g. {@code /storage/1234-5678}.
     *
     * Factored out of {@link #rescanSinks} because the backlog export needs the root
     * itself: its destination deliberately sits outside every logging sink, and the
     * volume path is also the key that stops the same stick being re-scanned every 30 s.
     */
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

    /**
     * Work out where the CSV should go. Every app-external directory the platform offers
     * is used, which on this device means internal shared storage <i>and</i> a mounted USB
     * volume - the app-private directory on a removable volume is writable without any
     * permission at all, which is exactly what is needed on a machine with no adb.
     */
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
        // Belt and braces: enumerate /storage ourselves in case a volume did not show up
        // above. Writing into our own Android/data directory on a volume needs no
        // permission; if the path is wrong the target is simply dropped.
        List<File> roots = usbVolumeRoots();
        for (int i = 0; i < roots.size(); i++) {
            dirs.add(new File(roots.get(i),
                    "Android/data/" + getPackageName() + "/files/fanlab"));
        }
        // A copy somewhere a person can find without knowing about Android/data, but only
        // if the permission was actually granted.
        try {
            if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    == PackageManager.PERMISSION_GRANTED) {
                File pub = Environment.getExternalStorageDirectory();
                if (pub != null) {
                    dirs.add(new File(pub, "FanLab"));
                }
            }
        } catch (Throwable ignored) {
            // optional
        }
        // Guaranteed sink, so the row counter is never zero even if everything else fails.
        try {
            dirs.add(new File(getFilesDir(), "fanlab"));
        } catch (Throwable ignored) {
            // nothing useful to do
        }
        try {
            synchronized (sinkDirs) {
                sinkDirs.clear();
                sinkDirs.addAll(dirs);
                volumeRoots.clear();
                volumeRoots.addAll(roots);
            }
        } catch (Throwable ignored) {
            // the list below is still usable
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
        // The sweep trace and report go to exactly the same places, which is what makes
        // them collectable off a USB stick with no shell.
        try {
            if (trace != null) {
                trace.setDirs(dirs);
            }
        } catch (Throwable t) {
            Log.w(TAG, "setDirs trace", t);
        }
        // A stick that has just appeared is the only chance to collect the backlog, so
        // take it here rather than waiting to be asked. Nothing is copied on this thread.
        try {
            exportBacklogAsync(false);
        } catch (Throwable t) {
            Log.w(TAG, "export edge", t);
        }
    }

    // ------------------------------------------------------------------ backlog export

    /**
     * Where an export puts its files on a volume.
     *
     * Never a {@link CsvLogger} sink. {@code CsvLogger.prune()} deletes everything
     * matching {@code fanlab-*.csv} in a target's own directory, so an export written
     * into a sink would be deleted as if it were a rolled file. This is also the path the
     * manual recipe in the handover notes already uses.
     */
    public static final String EXPORT_DIR = "FanLab-export";

    /** Names the boot an export last ran for, so a stick left in is not re-scanned. */
    private static final String EXPORT_MARK = ".fanlab-volume";

    /** One line describing the last export, for the screen and for adb. */
    public static volatile String exportStatus = "no export yet";
    /** Destinations created or appended to by the last export. */
    public static volatile int exportFiles;
    /** Rows the last export copied. */
    public static volatile long exportRows;
    /** The directories the last export wrote into. */
    public static volatile String[] exportDirs = new String[0];
    /** True while a copy is in progress, so the UI can say so and not start a second. */
    public static volatile boolean exporting;

    /** Volume paths already offered the backlog since this service started. */
    private final Set<String> exportedVolumes = new HashSet<String>();
    private final List<File> volumeRoots = new ArrayList<File>();

    /**
     * Copy the log backlog to every mounted volume, on a thread of its own.
     *
     * <h3>Why it cannot run here</h3>
     * The callers are {@link #rescanSinks} on the {@code fanlab-loop} thread -- the same
     * thread that reads the temperature and writes {@code fan_ctrl} every second -- and
     * the UI thread. Copying up to 28 MB onto FAT32 over USB takes seconds. A cooling
     * decision cannot wait behind a file copy, which is the same reason the DLPC read
     * sits after the write in {@link #tick}. So the only work done on the caller's thread
     * is picking the volumes and starting a thread; every file touched is touched there.
     *
     * @param force copy even to a volume already offered the backlog this boot, and say
     *              something either way. That is what the manual trigger is for; the
     *              automatic path stays quiet when there is nothing to do.
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
                    // With force, picked is every root, so this is only reached when
                    // nothing is mounted at all.
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
        // From here the flag is set, so every exit has to clear it.
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
            // a priority is an optimisation, not a requirement
        }
        int files = 0;
        long rows = 0;
        int failures = 0;
        List<String> where = new ArrayList<String>();
        try {
            // Every mounted root, not just the ones being written to: a stick already
            // done this boot is still no place to read the backlog from.
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

    /**
     * The sink to read the backlog from -- one of them, not both.
     *
     * The two internal sinks are identical copies by design. Keying a destination on the
     * first row's {@code epoch_ms} collapses them onto one file and the watermark makes
     * the second a no-op, but there is no reason to read 28 MB twice to prove it.
     * {@code /sdcard/FanLab} first because it is the copy a person can find, then the
     * app-private one, which survives clearing the other. A sink on a removable volume is
     * never a source: the stick's own live log is already on the stick.
     */
    private List<File> exportSources(List<File> sinks, List<File> roots) {
        List<File> candidates = new ArrayList<File>();
        try {
            File pub = Environment.getExternalStorageDirectory();
            if (pub != null) {
                candidates.add(new File(pub, "FanLab"));
            }
        } catch (Throwable ignored) {
            // the sinks below cover it
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
            // treat an unanswerable path as not removable
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
            // an unlistable directory is simply not a source
        }
        return out;
    }

    /**
     * Has this volume already been offered the backlog on this boot?
     *
     * The marker holds the boot's wall-clock instant rather than a flag, because both
     * halves matter: a stick left plugged in must not be re-scanned every 30 s, and a
     * stick brought back tomorrow must be, or "additive" would mean "once, ever". Service
     * restarts inside one boot land on the same stamp, which is what makes the in-memory
     * key a convenience rather than the thing being relied on.
     */
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
            // Provenance.sameBoot for the same reason the power-on capture uses it: the
            // question and its tolerance for a post-boot clock correction are identical.
            return Provenance.sameBoot(was, bootWallMs());
        } catch (Throwable e) {
            return false;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                    // nothing useful to do
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

    // ------------------------------------------------------------------ notification

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
            // fall through
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
            // a notification without a tap target is still fine
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

    // ------------------------------------------------------------------ helpers

    /** Start the service if it is not running, and tell it to re-read the preferences. */
    public static void poke(Context c, String action) {
        try {
            String a = action == null ? ACTION_REFRESH : action;
            FanService s = instance;
            if (s != null && ACTION_REFRESH.equals(a)) {
                // Already running: no need to go through the activity manager, which the
                // slider would otherwise hit on every D-pad repeat.
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
