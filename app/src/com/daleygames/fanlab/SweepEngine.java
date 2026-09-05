package com.daleygames.fanlab;

import java.util.ArrayList;
import java.util.List;

/**
 * The AUTO sweep, as a pure state machine.
 *
 * Everything AUTO decides lives here: which duty to hold, which brightness mode to assert,
 * when a step has told us what it is going to tell us, when to back off, and when to stop
 * the whole thing. It performs no I/O and imports nothing from Android, so the headless
 * test can drive an entire ninety-minute run through a thermal model in a few milliseconds
 * and check every abort path - which is the only way any of this gets tested at all,
 * there being no device and no emulator.
 *
 * The caller ({@link FanService}) feeds it one temperature per second and does what the
 * returned {@link Tick} says. The caller is also the only thing that writes hardware, so
 * the ordinary safety machinery - range checks in {@link FanIo}, the fail-safe on any
 * exception, the 1 Hz read-back and re-assert - is unchanged and still applies underneath.
 *
 * <h3>Safety, restated because this is the most dangerous thing in the app</h3>
 * <ul>
 *   <li><b>Ceiling 58 C, not configurable.</b> At or above it: command
 *       {@link FanIo#FAIL_SAFE_DUTY}, end the step, abort the run.</li>
 *   <li><b>Rate guard.</b> A rise faster than 0.5 C per 10 s means the held duty is far
 *       below its equilibrium. The step ends, the duty goes back up one schedule entry,
 *       and the rest of that mode's descent is abandoned - if duty <i>u</i> is climbing
 *       dangerously then every duty below it is worse, so there is nothing down there
 *       worth measuring.</li>
 *   <li><b>Duty floor 35</b>, below which the kernel FANSTOP stall watchdog becomes a
 *       real risk, and it hard-shuts the projector.</li>
 *   <li><b>Three consecutive unusable temperature readings abort the run</b> at
 *       {@link FanIo#FAIL_SAFE_DUTY}. Not one: at 1 Hz a single dropped read is ordinary,
 *       and against a tau of minutes three seconds of a duty that was safe a second ago is
 *       thermally nothing. Three consecutive is the same rule the CURVE controller already
 *       uses, and it is the point at which the sensor, not the sample, is suspect.</li>
 *   <li><b>The stock controller is never disabled.</b> Not even in the system variant.
 *       {@code persist.sys.fanctrl.by.temperatue} is sticky across reboot <i>and</i>
 *       uninstall, so a crash mid-sweep with it set to 0 would leave the projector with no
 *       thermal regulation at all. The 1 Hz re-assert costs at most a second of wrong duty
 *       per stock write, which against a tau of minutes does not matter.</li>
 * </ul>
 *
 * Pure Java.
 */
public final class SweepEngine {

    // ------------------------------------------------------------------ phases

    public static final int PHASE_BASELINE = 0;
    public static final int PHASE_STEP = 1;
    public static final int PHASE_GUARD = 2;
    public static final int PHASE_DONE = 3;
    public static final int PHASE_ABORTED = 4;

    public static String phaseName(int p) {
        switch (p) {
            case PHASE_BASELINE:
                return SweepStep.PHASE_BASELINE;
            case PHASE_STEP:
                return SweepStep.PHASE_STEP;
            case PHASE_GUARD:
                return SweepStep.PHASE_GUARD;
            case PHASE_DONE:
                return "done";
            default:
                return "aborted";
        }
    }

    /** What the caller must do this second. */
    public static final class Tick {
        /** Duty to command, always 1..100 and never below {@link SweepPlan#DUTY_FLOOR}. */
        public int duty = FanIo.FAIL_SAFE_DUTY;
        /** rgblevel to assert, 1..4, or -1 for "leave it alone". */
        public int rgblevel = -1;
        /** Markers for this second's trace row; empty when nothing happened. */
        public String event = "";
        /** True on the tick a step record was completed. */
        public boolean stepEnded;
        /** True once the run is over, either way. */
        public boolean finished;
    }

    // ------------------------------------------------------------------ state

    private final long runStartMs;
    private final long runStartWallMs;

    private int phase = PHASE_BASELINE;
    private int modeIdx;
    private int stepIdx;
    /** Records written so far, baselines included. Becomes {@link SweepStep#index}. */
    private int recordNo;
    /** Scheduled duty steps completed, for the "step N of M" readout. */
    private int scheduledDone;

    private long phaseStartMs;
    private long phaseStartWallMs;
    private double phaseStartC = Double.NaN;
    private int guardFallbackDuty = FanIo.FAIL_SAFE_DUTY;

    private int badReads;
    private String endReason = "";
    private boolean finished;

    /** Samples of the current phase, for the fit. 1 Hz, bounded by the max dwell. */
    private static final int MAX_SAMPLES = 1100;
    private final double[] sampleT = new double[MAX_SAMPLES];
    private final double[] sampleC = new double[MAX_SAMPLES];
    private int sampleN;

    /** Recent projections of T_inf, for the settle test. */
    private static final int MAX_PROJ = 64;
    private final long[] projMs = new long[MAX_PROJ];
    private final double[] projC = new double[MAX_PROJ];
    private int projN;
    private long lastFitMs;
    private ExpFit.Fit lastFit = new ExpFit.Fit();

    /** Rolling window for the rate guard and the baseline stability test. */
    private static final int RING = 90;
    private final long[] ringMs = new long[RING];
    private final double[] ringC = new double[RING];
    private int ringN;
    private int ringHead;

    private final List<SweepStep> steps = new ArrayList<SweepStep>();
    private SweepStep current;
    private SweepStep lastCompleted;

    private final List<String> events = new ArrayList<String>();
    private final List<Marker> markers = new ArrayList<Marker>();
    private static final int MAX_EVENTS = 2000;

    private long lastStepDwellSum;
    private int lastStepDwellCount;
    private long baselineDwellSum;
    private int baselineDwellCount;

    private double lastC = Double.NaN;

    /** A human observation, timestamped against the machine state at that instant. */
    public static final class Marker {
        public String kind = "";
        public long wallMs;
        public double degC = Double.NaN;
        public int duty;
        public int rgblevel;
    }

    public SweepEngine(long nowMs, long wallMs) {
        this.runStartMs = nowMs;
        this.runStartWallMs = wallMs;
        beginPhase(PHASE_BASELINE, nowMs, wallMs, Double.NaN);
        event("run_start");
    }

    // ------------------------------------------------------------------ accessors

    public int phase() {
        return phase;
    }

    public boolean finished() {
        return finished;
    }

    public String endReason() {
        return endReason;
    }

    public List<SweepStep> steps() {
        return steps;
    }

    public List<String> events() {
        return events;
    }

    public List<Marker> markers() {
        return markers;
    }

    public SweepStep lastCompletedStep() {
        return lastCompleted;
    }

    public int modeRgbLevel() {
        return modeIdx < SweepPlan.MODE_ORDER.length
                ? SweepPlan.MODE_ORDER[modeIdx] : SweepPlan.MODE_ORDER[
                SweepPlan.MODE_ORDER.length - 1];
    }

    public String modeName() {
        return SweepPlan.modeName(modeRgbLevel());
    }

    /** Scheduled step number, from 1, for the UI. */
    public int stepNumber() {
        int n = scheduledDone + 1;
        int total = SweepPlan.totalSteps();
        return n > total ? total : n;
    }

    public int totalSteps() {
        return SweepPlan.totalSteps();
    }

    public int commandedDuty() {
        return dutyForPhase();
    }

    /** The current step's projected equilibrium, or NaN before there is one. */
    public double projectedTInf() {
        return lastFit != null && lastFit.ok ? lastFit.tInf : Double.NaN;
    }

    public double projectedTau() {
        return lastFit != null && lastFit.ok ? lastFit.tau : Double.NaN;
    }

    public long elapsedMs(long nowMs) {
        return nowMs - runStartMs;
    }

    public long phaseElapsedMs(long nowMs) {
        return nowMs - phaseStartMs;
    }

    /** Seconds still to run, on the evidence so far. Rough by nature; labelled as such. */
    public long estimateRemainingSec(long nowMs) {
        if (finished) {
            return 0;
        }
        long typicalStep = lastStepDwellCount > 0
                ? lastStepDwellSum / lastStepDwellCount : SweepPlan.DWELL_MIN_MS;
        long typicalBaseline = baselineDwellCount > 0
                ? baselineDwellSum / baselineDwellCount : SweepPlan.BASELINE_MIN_MS;
        // Count what the plan actually has left, rather than assuming every mode runs to
        // the bottom: a rate guard abandons the rest of its mode's descent.
        int stepsLeft = 0;
        for (int m = modeIdx; m < SweepPlan.MODE_ORDER.length; m++) {
            int[] sch = SweepPlan.scheduleFor(SweepPlan.MODE_ORDER[m]);
            stepsLeft += (m == modeIdx && phase == PHASE_STEP)
                    ? Math.max(0, sch.length - stepIdx) : sch.length;
        }
        int baselinesLeft = Math.max(0, SweepPlan.MODE_ORDER.length - baselineDwellCount
                - (phase == PHASE_BASELINE ? 1 : 0));
        long total = stepsLeft * typicalStep + baselinesLeft * typicalBaseline;
        if (phase == PHASE_BASELINE) {
            total += Math.max(0L, typicalBaseline - phaseElapsedMs(nowMs));
        } else {
            total -= Math.min(typicalStep, phaseElapsedMs(nowMs));
        }
        long capLeft = SweepPlan.RUN_CAP_MS - elapsedMs(nowMs);
        if (total > capLeft) {
            total = capLeft;
        }
        return total < 0 ? 0 : total / 1000L;
    }

    // ------------------------------------------------------------------ the tick

    /**
     * Advance one second.
     *
     * @param nowMs   monotonic milliseconds.
     * @param wallMs  wall clock, for the record.
     * @param degC    the measured LED temperature, or NaN if the read was unusable. The
     *                caller does the plausibility check; NaN here means "no reading".
     */
    public Tick tick(long nowMs, long wallMs, double degC) {
        Tick out = new Tick();
        StringBuilder ev = new StringBuilder();
        if (finished) {
            out.duty = FanIo.FAIL_SAFE_DUTY;
            out.finished = true;
            return out;
        }

        // ---- unusable reading ----
        if (Double.isNaN(degC) || Double.isInfinite(degC) || !Thermistor.plausible(degC)) {
            badReads++;
            append(ev, "bad_read x" + badReads);
            if (badReads >= SweepPlan.BAD_READS_ABORT) {
                finish(PHASE_ABORTED, "abort:bad_temperature", nowMs, wallMs, Double.NaN, ev);
                out.duty = FanIo.FAIL_SAFE_DUTY;
                out.finished = true;
                out.stepEnded = true;
                out.event = ev.toString();
                return out;
            }
            // Hold what we were holding; three seconds of a duty that was safe a second
            // ago cannot move a system whose tau is minutes.
            out.duty = dutyForPhase();
            out.rgblevel = modeRgbLevel();
            out.event = ev.toString();
            return out;
        }
        badReads = 0;
        lastC = degC;
        pushRing(nowMs, degC);

        // ---- hard ceiling: before anything else can decide otherwise ----
        if (degC >= SweepPlan.CEILING_C) {
            append(ev, "ceiling " + Sample.fmt1(degC) + "C");
            finish(PHASE_ABORTED, "abort:ceiling", nowMs, wallMs, degC, ev);
            out.duty = FanIo.FAIL_SAFE_DUTY;
            out.finished = true;
            out.stepEnded = true;
            out.event = ev.toString();
            return out;
        }

        // ---- overall time cap ----
        if (elapsedMs(nowMs) >= SweepPlan.RUN_CAP_MS) {
            append(ev, "run time cap reached");
            finish(PHASE_DONE, "time_cap", nowMs, wallMs, degC, ev);
            out.duty = FanIo.FAIL_SAFE_DUTY;
            out.finished = true;
            out.stepEnded = true;
            out.event = ev.toString();
            return out;
        }

        // ---- record the sample against the current phase ----
        if (Double.isNaN(phaseStartC)) {
            phaseStartC = degC;
            if (current != null) {
                current.tStartC = degC;
            }
        }
        if (sampleN < MAX_SAMPLES) {
            sampleT[sampleN] = (nowMs - phaseStartMs) / 1000.0;
            sampleC[sampleN] = degC;
            sampleN++;
        }
        if (current != null) {
            current.samples = sampleN;
            current.tEndC = degC;
        }

        // ---- refit periodically ----
        if (nowMs - lastFitMs >= SweepPlan.REFIT_EVERY_MS && sampleN >= ExpFit.MIN_SAMPLES) {
            lastFitMs = nowMs;
            lastFit = ExpFit.fit(sampleT, sampleC, sampleN);
            if (lastFit.ok) {
                pushProjection(nowMs, lastFit.tInf);
                if (current != null) {
                    current.fit = lastFit;
                }
            }
        }

        // ---- rate guard, steps only ----
        if (phase == PHASE_STEP && rateRise(nowMs) > SweepPlan.RATE_GUARD_C) {
            append(ev, "rate_guard rise>" + Sample.fmt1(SweepPlan.RATE_GUARD_C)
                    + "C/10s at duty " + dutyForPhase());
            event("rate_guard at duty " + dutyForPhase() + ", "
                    + Sample.fmt1(degC) + "C, mode " + modeName());
            guardFallbackDuty = stepIdx > 0
                    ? SweepPlan.scheduleFor(modeRgbLevel())[stepIdx - 1] : FanIo.FAIL_SAFE_DUTY;
            closeStep(SweepStep.END_RATE_GUARD, nowMs, wallMs, degC);
            out.stepEnded = true;
            // Abandon the rest of this mode's descent: everything below the duty that just
            // tripped the guard is strictly worse, so there is nothing there worth holding.
            beginPhase(PHASE_GUARD, nowMs, wallMs, degC);
            out.duty = dutyForPhase();
            out.rgblevel = modeRgbLevel();
            out.event = ev.toString();
            return out;
        }

        // ---- phase completion ----
        switch (phase) {
            case PHASE_BASELINE:
                if (baselineDone(nowMs)) {
                    String why = phaseElapsedMs(nowMs) >= SweepPlan.BASELINE_MAX_MS
                            ? SweepStep.END_TIMEOUT : SweepStep.END_SETTLED;
                    append(ev, "baseline_end:" + why);
                    closeStep(why, nowMs, wallMs, degC);
                    out.stepEnded = true;
                    startFirstStepOfMode(nowMs, wallMs, degC, ev);
                }
                break;
            case PHASE_STEP: {
                String done = stepDone(nowMs);
                if (done != null) {
                    append(ev, "step_end:" + done);
                    closeStep(done, nowMs, wallMs, degC);
                    out.stepEnded = true;
                    advanceStep(nowMs, wallMs, degC, ev);
                }
                break;
            }
            case PHASE_GUARD:
                if (phaseElapsedMs(nowMs) >= SweepPlan.DWELL_MIN_MS) {
                    append(ev, "guard_end");
                    closeStep("guard_hold", nowMs, wallMs, degC);
                    out.stepEnded = true;
                    nextMode(nowMs, wallMs, degC, ev);
                }
                break;
            default:
                break;
        }

        out.duty = dutyForPhase();
        out.rgblevel = finished ? -1 : modeRgbLevel();
        out.finished = finished;
        out.event = ev.toString();
        return out;
    }

    // ------------------------------------------------------------------ transitions

    private void startFirstStepOfMode(long nowMs, long wallMs, double degC, StringBuilder ev) {
        stepIdx = 0;
        beginPhase(PHASE_STEP, nowMs, wallMs, degC);
        append(ev, "step_start duty=" + dutyForPhase() + " mode=" + modeName());
    }

    private void advanceStep(long nowMs, long wallMs, double degC, StringBuilder ev) {
        int[] schedule = SweepPlan.scheduleFor(modeRgbLevel());
        if (stepIdx + 1 < schedule.length) {
            stepIdx++;
            beginPhase(PHASE_STEP, nowMs, wallMs, degC);
            append(ev, "step_start duty=" + dutyForPhase() + " mode=" + modeName());
        } else {
            nextMode(nowMs, wallMs, degC, ev);
        }
    }

    private void nextMode(long nowMs, long wallMs, double degC, StringBuilder ev) {
        if (modeIdx + 1 < SweepPlan.MODE_ORDER.length) {
            modeIdx++;
            stepIdx = 0;
            beginPhase(PHASE_BASELINE, nowMs, wallMs, degC);
            append(ev, "mode_change to " + modeName() + " (rgblevel " + modeRgbLevel()
                    + "); the stock controller will slam the fan to this tier's floor "
                    + "within one 15 s poll - the 1 Hz re-assert takes it back");
            event("mode_change to " + modeName());
        } else {
            append(ev, "complete");
            finish(PHASE_DONE, "complete", nowMs, wallMs, degC, ev);
        }
    }

    private void beginPhase(int newPhase, long nowMs, long wallMs, double degC) {
        phase = newPhase;
        phaseStartMs = nowMs;
        phaseStartWallMs = wallMs;
        phaseStartC = Double.isNaN(degC) ? Double.NaN : degC;
        sampleN = 0;
        projN = 0;
        lastFitMs = nowMs;
        lastFit = new ExpFit.Fit();
        if (newPhase == PHASE_DONE || newPhase == PHASE_ABORTED) {
            current = null;
            return;
        }
        SweepStep s = new SweepStep();
        s.index = recordNo++;
        s.rgblevel = modeRgbLevel();
        s.phase = phaseName(newPhase);
        s.commandedDuty = dutyForPhase();
        s.startWallMs = wallMs;
        s.tStartC = degC;
        current = s;
    }

    private void closeStep(String reason, long nowMs, long wallMs, double degC) {
        SweepStep s = current;
        if (s == null) {
            return;
        }
        s.endWallMs = wallMs;
        s.dwellMs = nowMs - phaseStartMs;
        s.tEndC = degC;
        s.samples = sampleN;
        s.endReason = reason;
        if (sampleN >= ExpFit.MIN_SAMPLES) {
            ExpFit.Fit f = ExpFit.fit(sampleT, sampleC, sampleN);
            if (f.ok) {
                s.fit = f;
            }
        }
        steps.add(s);
        lastCompleted = s;
        current = null;
        if (SweepStep.PHASE_BASELINE.equals(s.phase)) {
            baselineDwellSum += s.dwellMs;
            baselineDwellCount++;
        } else {
            lastStepDwellSum += s.dwellMs;
            lastStepDwellCount++;
            if (SweepStep.PHASE_STEP.equals(s.phase)) {
                scheduledDone++;
            }
        }
    }

    private void finish(int endPhase, String reason, long nowMs, long wallMs, double degC,
                        StringBuilder ev) {
        if (current != null) {
            String stepReason;
            if (endPhase == PHASE_ABORTED) {
                stepReason = SweepStep.END_ABORT;
            } else if (SweepStep.END_TIME_CAP.equals(reason)) {
                stepReason = SweepStep.END_TIME_CAP;
            } else {
                stepReason = SweepStep.END_SETTLED;
            }
            closeStep(stepReason, nowMs, wallMs, degC);
        }
        phase = endPhase;
        endReason = reason;
        finished = true;
        event(reason);
        if (ev != null) {
            append(ev, reason);
        }
    }

    // ------------------------------------------------------------------ decisions

    private int dutyForPhase() {
        int d;
        switch (phase) {
            case PHASE_BASELINE:
                d = FanIo.FAIL_SAFE_DUTY;
                break;
            case PHASE_STEP: {
                int[] schedule = SweepPlan.scheduleFor(modeRgbLevel());
                int i = stepIdx < 0 ? 0 : stepIdx >= schedule.length ? schedule.length - 1 : stepIdx;
                d = schedule[i];
                break;
            }
            case PHASE_GUARD:
                d = guardFallbackDuty;
                break;
            default:
                d = FanIo.FAIL_SAFE_DUTY;
                break;
        }
        if (d < SweepPlan.DUTY_FLOOR) {
            d = SweepPlan.DUTY_FLOOR;
        }
        return FanIo.clampForUi(d);
    }

    /** A step is over when the projection has stopped moving, or when time is up. */
    private String stepDone(long nowMs) {
        long el = phaseElapsedMs(nowMs);
        if (el >= SweepPlan.DWELL_MAX_MS) {
            return SweepStep.END_TIMEOUT;
        }
        if (el < SweepPlan.DWELL_MIN_MS) {
            return null;
        }
        if (!lastFit.ok || !(lastFit.rms <= SweepPlan.SETTLE_MAX_RMS_C)) {
            return null;
        }
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        int seen = 0;
        for (int i = 0; i < projN; i++) {
            if (nowMs - projMs[i] <= SweepPlan.SETTLE_WINDOW_MS) {
                seen++;
                if (projC[i] < lo) {
                    lo = projC[i];
                }
                if (projC[i] > hi) {
                    hi = projC[i];
                }
            }
        }
        if (seen >= 5 && hi - lo <= SweepPlan.SETTLE_BAND_C) {
            return SweepStep.END_SETTLED;
        }
        return null;
    }

    /** The baseline hold at 83 is over when the temperature has stopped moving. */
    private boolean baselineDone(long nowMs) {
        long el = phaseElapsedMs(nowMs);
        if (el >= SweepPlan.BASELINE_MAX_MS) {
            return true;
        }
        if (el < SweepPlan.BASELINE_MIN_MS) {
            return false;
        }
        double then = ringAt(nowMs - 60000L);
        if (Double.isNaN(then) || Double.isNaN(lastC)) {
            return false;
        }
        return Math.abs(lastC - then) < SweepPlan.BASELINE_STABLE_C;
    }

    /** Rise over the last {@link SweepPlan#RATE_WINDOW_MS}, or 0 if the window is not full. */
    private double rateRise(long nowMs) {
        if (phaseElapsedMs(nowMs) < SweepPlan.RATE_WINDOW_MS) {
            return 0.0;
        }
        double then = ringAt(nowMs - SweepPlan.RATE_WINDOW_MS);
        if (Double.isNaN(then) || Double.isNaN(lastC)) {
            return 0.0;
        }
        return lastC - then;
    }

    // ------------------------------------------------------------------ user input

    /**
     * Abort now. The caller writes {@link FanIo#FAIL_SAFE_DUTY} itself, immediately, before
     * calling this - abort must not wait for the next tick.
     */
    public void abort(String reason, long nowMs, long wallMs) {
        if (finished) {
            return;
        }
        finish(PHASE_ABORTED, "abort:" + (reason == null || reason.length() == 0
                ? "user" : reason), nowMs, wallMs, lastC, null);
    }

    /**
     * The one thing only a human can measure: at what duty the fan stops being noticeable.
     * Optional - a sweep with no keypresses is still a valid sweep.
     */
    public Marker mark(String kind, long wallMs) {
        Marker m = new Marker();
        m.kind = kind == null ? "mark" : kind;
        m.wallMs = wallMs;
        m.degC = lastC;
        m.duty = dutyForPhase();
        m.rgblevel = modeRgbLevel();
        markers.add(m);
        event(m.kind + " at duty " + m.duty + ", " + SweepPlan.modeName(m.rgblevel)
                + ", " + Sample.fmt1(m.degC) + "C");
        return m;
    }

    /**
     * The stock Java controller wrote {@code fan_ctrl} underneath us. Free corroboration,
     * so it is counted per step and logged with the value and the temperature.
     */
    public void noteForeignWrite(int foreignValue, int wanted) {
        if (current != null) {
            current.foreignWrites++;
        }
        event("foreign_write " + foreignValue + " (we hold " + wanted + ") at "
                + Sample.fmt1(lastC) + "C, mode " + modeName());
    }

    /** The user moved the brightness mode with the remote; we put ours back. */
    public void noteForeignMode(int found, int wanted) {
        event("foreign_mode rgblevel=" + found + ", re-asserting " + wanted);
    }

    /** Attach the readings the engine cannot take itself to the step that just ended. */
    public void attachToLastStep(PicoReg.Reading dlpc, String ledCurrents) {
        SweepStep s = lastCompleted;
        if (s == null) {
            return;
        }
        s.dlpc = dlpc;
        s.ledCurrents = ledCurrents == null ? "" : ledCurrents;
    }

    // ------------------------------------------------------------------ plumbing

    private void event(String s) {
        if (events.size() < MAX_EVENTS) {
            events.add(s);
        } else if (events.size() == MAX_EVENTS) {
            events.add("(event log truncated)");
        }
    }

    private static void append(StringBuilder sb, String s) {
        if (sb.length() > 0) {
            sb.append(';');
        }
        sb.append(s);
    }

    private void pushRing(long ms, double c) {
        ringMs[ringHead] = ms;
        ringC[ringHead] = c;
        ringHead = (ringHead + 1) % RING;
        if (ringN < RING) {
            ringN++;
        }
    }

    /** The sample nearest to {@code atMs}, or NaN if the ring does not reach back that far. */
    private double ringAt(long atMs) {
        double best = Double.NaN;
        long bestGap = Long.MAX_VALUE;
        for (int i = 0; i < ringN; i++) {
            long gap = Math.abs(ringMs[i] - atMs);
            if (gap < bestGap) {
                bestGap = gap;
                best = ringC[i];
            }
        }
        return bestGap <= 3000L ? best : Double.NaN;
    }

    private void pushProjection(long ms, double tInf) {
        if (Double.isNaN(tInf) || Double.isInfinite(tInf)) {
            return;
        }
        if (projN < MAX_PROJ) {
            projMs[projN] = ms;
            projC[projN] = tInf;
            projN++;
            return;
        }
        System.arraycopy(projMs, 1, projMs, 0, MAX_PROJ - 1);
        System.arraycopy(projC, 1, projC, 0, MAX_PROJ - 1);
        projMs[MAX_PROJ - 1] = ms;
        projC[MAX_PROJ - 1] = tInf;
    }

    public long startedWallMs() {
        return runStartWallMs;
    }

    public long phaseStartedWallMs() {
        return phaseStartWallMs;
    }
}
