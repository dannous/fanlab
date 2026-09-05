package com.daleygames.fanlab;

/**
 * One held condition in a sweep, and everything measured while it was held.
 *
 * A step is either a scheduled duty ({@code "step"}), the re-cool and re-baseline at duty
 * 83 that separates two brightness modes ({@code "baseline"}), or the backed-off hold that
 * follows a rate-guard trip ({@code "guard"}). All three are recorded the same way,
 * because the baseline holds are themselves a measurement: they are {@code T_eq(83, mode)}
 * for each of the four modes, which is the reference every other point is read against.
 *
 * Pure Java. The engine fills in the timing and the fit; the service fills in the two
 * fields that need I/O, {@link #dlpc} and {@link #ledCurrents}.
 */
public final class SweepStep {

    public static final String PHASE_BASELINE = "baseline";
    public static final String PHASE_STEP = "step";
    public static final String PHASE_GUARD = "guard";

    /** End reasons, exactly as they appear in the report. */
    public static final String END_SETTLED = "settled";
    public static final String END_TIMEOUT = "timeout";
    public static final String END_RATE_GUARD = "rate_guard";
    public static final String END_ABORT = "abort";
    public static final String END_TIME_CAP = "time_cap";

    /** Global step number across the whole run, from 0. */
    public int index;
    /** The brightness mode this step ran in, as an rgblevel (1..4). */
    public int rgblevel;
    public String phase = PHASE_STEP;
    /** The duty commanded throughout. */
    public int commandedDuty;

    public long startWallMs;
    public long endWallMs;
    public long dwellMs;

    public double tStartC = Double.NaN;
    public double tEndC = Double.NaN;
    public int samples;

    /** The extrapolated equilibrium. This is what the whole sweep exists to produce. */
    public ExpFit.Fit fit = new ExpFit.Fit();

    public String endReason = "";

    /**
     * How many times the stock Java controller wrote {@code fan_ctrl} underneath us during
     * this step. Free corroboration of the stock ladder operating in the wild - its rung
     * values and its 15 s poll - which until now we only had from disassembly.
     */
    public int foreignWrites;

    /** The DLPC's own temperature at the end of the step, or the reason there is none. */
    public PicoReg.Reading dlpc;

    /** What the DLPC says the LED drive was, so the log records the actual power. */
    public String ledCurrents = "";

    public String modeName() {
        return SweepPlan.modeName(rgblevel);
    }

    /** Serialise this step into an open {@link Json} array. */
    public void writeJson(Json j) {
        j.beginObject();
        j.put("index", index);
        j.put("phase", phase);
        j.put("mode_rgblevel", rgblevel);
        j.put("mode_name", modeName());
        j.put("commanded_duty", commandedDuty);
        j.put("start_epoch_ms", startWallMs);
        j.put("end_epoch_ms", endWallMs);
        j.put("dwell_s", dwellMs / 1000L);
        j.put("t_start_c", tStartC, 3);
        j.put("t_end_c", tEndC, 3);
        j.put("samples", samples);
        j.put("end_reason", endReason);
        j.put("foreign_writes", foreignWrites);
        j.obj("fit");
        ExpFit.Fit f = fit == null ? new ExpFit.Fit() : fit;
        j.put("ok", f.ok);
        j.put("t_inf_c", f.tInf, 3);
        j.put("tau_s", f.tau, 2);
        j.put("t0_c", f.t0, 3);
        j.put("amplitude_c", f.amp, 3);
        j.put("residual_rms_c", f.rms, 4);
        j.put("samples", f.n);
        j.put("tau_identifiable", f.tauIdentifiable);
        j.put("tau_pinned", f.tauPinned);
        j.put("note", f.why);
        j.endObject();
        j.obj("dlpc_temperature");
        if (dlpc == null) {
            j.put("status", PicoReg.STATUS_UNAVAILABLE);
            j.put("temp_c", Double.NaN, 1);
            j.put("raw_word", (String) null);
            j.put("source", (String) null);
            j.put("provisional", false);
            j.put("reason", "not attempted for this step");
        } else {
            j.put("status", dlpc.status);
            j.put("temp_c", dlpc.degC, 1);
            j.put("raw_word", dlpc.rawHex());
            j.put("source", dlpc.source);
            j.put("provisional", dlpc.provisional);
            j.put("reason", dlpc.reason);
        }
        j.endObject();
        j.put("led_currents", ledCurrents);
        j.endObject();
    }
}
