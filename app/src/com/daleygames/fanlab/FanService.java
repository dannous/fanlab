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
import java.util.List;
import java.util.Locale;

/**
 * The 1 Hz sampler and the fan controller.
 *
 * Runs in the foreground with a notification (mandatory on API 28) and returns
 * START_STICKY, so the platform brings it back if it is killed. It takes no wakelock: if
 * the projector suspends, the loop stops with it, which is correct - while early
 * suspended the kernel forces every fan_ctrl write to 10 anyway, and it re-imposes 55%
 * on resume regardless of what userspace had asked for. The loop therefore re-reads the
 * node every second and rewrites when reality has drifted from intent, rather than
 * assuming a write stuck.
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
 *       leave a low duty behind.</li>
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
    private static final int RESCAN_EVERY_TICKS = 30;
    private static final int NOTIF_EVERY_TICKS = 5;
    /** Consecutive bad temperature reads tolerated in CURVE mode before failing high. */
    private static final int FAILSAFE_AFTER_BAD_READS = 3;

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

    private HandlerThread thread;
    private Handler handler;
    private CsvLogger csv;
    private final FanCurve curve = new FanCurve();
    private PowerManager power;
    private NotificationManager notifications;

    private int lastWritten = -1;
    /** Whether the previous tick was driving, so a transition to OFF can be caught. */
    private boolean wasDriving;
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
                    note("suspend");
                }
            } catch (Throwable t) {
                Log.w(TAG, "powerReceiver", t);
            }
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
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
            String name = "fanlab-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new Date()) + ".csv";
            csv = new CsvLogger(name);
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
            thread = new HandlerThread("fanlab-loop");
            thread.start();
            handler = new Handler(thread.getLooper());
            running = true;
            curve.resync(FanIo.readDuty());
            handler.post(tickRunnable);
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
            boolean ok = FanIo.writeFailSafe();
            lastWritten = ok ? FanIo.FAIL_SAFE_DUTY : -1;
            curve.reset();
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
     * <h3>What it cannot cover</h3>
     * <ul>
     *   <li><b>A low-memory kill or a crash</b> is covered: START_STICKY plus a
     *       foreground notification means the platform restarts the service.</li>
     *   <li><b>A reboot</b> is covered by autostart, forced on above.</li>
     *   <li><b>Force-stop, and "Disable" in Settings, are NOT covered.</b> They cancel
     *       the START_STICKY restart <i>and</i> put the package in the stopped state,
     *       which suppresses {@code BOOT_COMPLETED} as well — so neither the restart nor
     *       the reboot path fires.</li>
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
            // Only the platform-signed build may own this switch. Both variants can be
            // installed at once (that is deliberate -- the plain one is the zero-
            // commitment measuring instrument), and if both tried to manage a single
            // global property they would fight: the idle one re-arming the ladder that
            // the driving one just disabled. Today the plain build's write simply fails,
            // so the fight is invisible; relying on a permission denial to enforce a
            // design invariant is not a plan. Make it explicit instead.
            if (android.os.Process.myUid() != android.os.Process.SYSTEM_UID) {
                if (driving) {
                    String v = SysProps.get(SysProps.PROP_FANCTRL_BY_TEMP);
                    if (v != null && !"0".equals(v.trim())) {
                        statusLine = "the stock ladder is still armed and this build "
                                + "cannot disable it - it will overwrite the curve every "
                                + "15 s. Deploy the platform-signed build.";
                    }
                }
                return;
            }
            String want = driving ? "0" : "1";
            String have = SysProps.get(SysProps.PROP_FANCTRL_BY_TEMP);
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
     * A sweep or a hold session does need the node to itself, and both are transient,
     * in-memory, and impossible to resume across a restart.
     */
    private boolean drivesUnattended(int mode) {
        return mode == Mode.CURVE || sweepEngine != null || holdSession != null;
    }

    private void note(String s) {
        synchronized (this) {
            pendingNote = pendingNote.length() == 0 ? s : pendingNote + " " + s;
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
        resumePending = false;
        lastInteractive = interactive;

        StringBuilder note = new StringBuilder(takeNote());

        boolean settingsChanged = prefsDirty;
        if (prefsDirty) {
            prefsDirty = false;
            badReads = 0;
            failSafeLatched = false;
            curve.resync(s.fanCtrl);
            append(note, "settings changed");
        }

        // Own the stock ladder's kill switch from here, rather than leaving it to
        // whoever last set it by hand. Re-checked periodically as well as on a settings
        // change, because the property is global: another tool, or an earlier session's
        // leftovers, can put it back underneath us.
        if (settingsChanged || ticks % RESCAN_EVERY_TICKS == 0) {
            syncStockLadder();
        }

        if (resumeEdge) {
            // The driver re-applies 55% on resume and after any stall, discarding what we
            // wrote. Adopt reality, then ramp from there instead of stepping.
            curve.resync(s.fanCtrl);
            lastWritten = -1;
            append(note, "resync@" + s.fanCtrl);
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
            // Free measurement, and it costs nothing: fan_ctrl is read back before every
            // write, so a value that is not what we last wrote is the stock Java
            // controller operating in the wild - its rung values and its 15 s poll,
            // corroboration we otherwise only have from disassembly.
            if (lastWritten > 0 && s.fanCtrl >= 0 && s.fanCtrl != lastWritten) {
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
                    append(note, "failsafe cleared");
                    curve.resync(s.fanCtrl);
                }
                badReads = 0;
                failSafeLatched = false;
                // An unreadable led_status counts as "engine on": assuming the light
                // engine is running is the conservative assumption.
                boolean engineOn = s.ledStatus != 0;
                CurveConfig cfg = Prefs.curve(this);
                desired = curve.step(cfg, s.profile, s.degC, engineOn, mono);
                if (!engineOn) {
                    append(note, "engine-off");
                }
            }
        } else {
            badReads = 0;
            failSafeLatched = false;
        }
        // Handing back by any route -- the adb path sets mode=OFF and never calls
        // releaseControl() -- must still leave the fan somewhere safe. Without this the
        // duty simply stops being updated and the fan stays wherever the curve last put
        // it (30, or idleDuty 10), with the stock ladder only writing when the rounded
        // temperature next changes, which at equilibrium is exactly when it does not.
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
                append(note, "suspended, not writing");
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

        // ---------------- log ----------------
        try {
            if (csv != null && Prefs.logging(this)) {
                if (ticks % RESCAN_EVERY_TICKS == 1) {
                    rescanSinks();
                }
                // Log every event, but only every Nth quiet second. A row a second of
                // "nothing changed" is 0.34 MB/hour that buries the lines that matter.
                // Anything that actually happened -- a write, a note, a session step, a
                // latched fail-safe -- is always recorded, so decimating the heartbeat
                // costs no information, only volume.
                boolean interesting = s.wrote > 0
                        || failSafeLatched
                        || sessionRunning
                        || (s.note != null && s.note.length() > 0);
                if (interesting || ticks % Prefs.logEverySec(this) == 0) {
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
        try {
            File[] vols = new File("/storage").listFiles();
            if (vols != null) {
                for (int i = 0; i < vols.length; i++) {
                    String n = vols[i].getName();
                    if ("emulated".equals(n) || "self".equals(n) || !vols[i].isDirectory()) {
                        continue;
                    }
                    dirs.add(new File(vols[i],
                            "Android/data/" + getPackageName() + "/files/fanlab"));
                }
            }
        } catch (Throwable ignored) {
            // /storage is often not listable; that is fine, the API above is the real path
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
