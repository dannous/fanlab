package com.daleygames.fanlab;

import java.util.List;

/** The output files: a 1 Hz trace CSV and a JSON summary, written to app storage and mirrored to any mounted USB volume. */
public final class SweepReport {

    /** Format version. Bump it if a column moves; the analysis reads this first. */
    public static final String FORMAT = "fanlab-sweep-1";

    /** Trace columns: the ordinary telemetry columns first, then the sweep's own. The dlpc_* columns are filled only on the row a reading was taken. */
    public static final String TRACE_HEADER = CsvLogger.HEADER
            + ",commanded_duty,step_index,phase,mode_rgblevel,mode_name,event"
            + ",dlpc_temp_c,dlpc_raw,dlpc_status";

    /** Everything about the run that is not a measurement. */
    public static final class Meta {
        public String kind = "auto";
        public String appVersion = "";
        public String packageName = "";
        public int uid = -1;
        public boolean systemVariant;
        public String model = "";
        public String firmware = "";
        public String androidRelease = "";
        public String androidSdk = "";
        public String ambientNote = "";
        public int patternLevelPercent = 100;
        public String overlay = "";
        public String traceFile = "";
        public String reportFile = "";
    }

    private SweepReport() {
    }

    /** One 1 Hz trace row; {@code dlpc} is null on the many rows where no reading was taken. */
    public static String traceRow(Sample s, int commandedDuty, int stepIndex, String phase,
                                  int rgblevel, String event, PicoReg.Reading dlpc) {
        StringBuilder sb = new StringBuilder(220);
        sb.append(s == null ? "" : s.toCsv());
        sb.append(',').append(commandedDuty < 0 ? "" : Integer.toString(commandedDuty));
        sb.append(',').append(stepIndex < 0 ? "" : Integer.toString(stepIndex));
        sb.append(',').append(CsvLogger.q(phase == null ? "" : phase));
        sb.append(',').append(rgblevel < 0 ? "" : Integer.toString(rgblevel));
        sb.append(',').append(rgblevel < 0 ? "" : CsvLogger.q(SweepPlan.modeName(rgblevel)));
        sb.append(',').append(CsvLogger.q(event == null ? "" : event));
        if (dlpc == null) {
            sb.append(",,,");
        } else {
            sb.append(',').append(Double.isNaN(dlpc.degC) ? "" : Sample.fmt1(dlpc.degC));
            sb.append(',').append(dlpc.rawHex() == null ? "" : dlpc.rawHex());
            sb.append(',').append(CsvLogger.q(dlpc.status));
        }
        return sb.toString();
    }

    public static String sweepJson(SweepEngine e, Meta m, long endWallMs, long durationSec) {
        Json j = new Json();
        j.beginObject();
        header(j, m, "auto");
        j.put("started_epoch_ms", e == null ? 0L : e.startedWallMs());
        j.put("ended_epoch_ms", endWallMs);
        j.put("duration_s", durationSec);
        j.put("end_reason", e == null ? "not started" : e.endReason());
        j.put("steps_completed", e == null ? 0 : e.steps().size());

        j.obj("schedule");
        j.intArray("full", SweepPlan.FULL);
        j.intArray("short", SweepPlan.SHORT);
        j.intArray("mode_order_rgblevel", SweepPlan.MODE_ORDER);
        j.put("dwell_min_s", SweepPlan.DWELL_MIN_MS / 1000L);
        j.put("dwell_max_s", SweepPlan.DWELL_MAX_MS / 1000L);
        j.put("baseline_min_s", SweepPlan.BASELINE_MIN_MS / 1000L);
        j.put("baseline_max_s", SweepPlan.BASELINE_MAX_MS / 1000L);
        j.put("run_cap_s", SweepPlan.capSeconds());
        j.put("ceiling_c", SweepPlan.CEILING_C, 1);
        j.put("ceiling_configurable", false);
        j.put("rate_guard_c_per_10s", SweepPlan.RATE_GUARD_C, 2);
        j.put("duty_floor", SweepPlan.DUTY_FLOOR);
        j.put("fail_safe_duty", FanIo.FAIL_SAFE_DUTY);
        j.put("settle_band_c", SweepPlan.SETTLE_BAND_C, 2);
        j.put("settle_window_s", SweepPlan.SETTLE_WINDOW_MS / 1000L);
        j.put("settle_max_residual_c", SweepPlan.SETTLE_MAX_RMS_C, 3);
        j.put("stock_controller_disabled", false);
        j.endObject();

        dlpcSummary(j, e);

        j.arr("markers");
        if (e != null) {
            List<SweepEngine.Marker> ms = e.markers();
            for (int i = 0; i < ms.size(); i++) {
                SweepEngine.Marker k = ms.get(i);
                j.beginObject();
                j.put("kind", k.kind);
                j.put("epoch_ms", k.wallMs);
                j.put("duty", k.duty);
                j.put("mode_rgblevel", k.rgblevel);
                j.put("mode_name", SweepPlan.modeName(k.rgblevel));
                j.put("degC", k.degC, 2);
                j.endObject();
            }
        }
        j.endArray();

        j.arr("steps");
        if (e != null) {
            List<SweepStep> ss = e.steps();
            for (int i = 0; i < ss.size(); i++) {
                ss.get(i).writeJson(j);
            }
        }
        j.endArray();

        j.arr("events");
        if (e != null) {
            List<String> ev = e.events();
            for (int i = 0; i < ev.size(); i++) {
                j.value(ev.get(i));
            }
        }
        j.endArray();

        warnings(j, e);
        j.endObject();
        return j.finish();
    }

    public static String verifyJson(HoldSession h, Meta m, long endWallMs, long durationSec) {
        Json j = new Json();
        j.beginObject();
        header(j, m, "verify");
        j.put("started_epoch_ms", h == null ? 0L : h.startedWallMs());
        j.put("ended_epoch_ms", endWallMs);
        j.put("duration_s", durationSec);
        j.put("end_reason", h == null ? "not started" : h.endReason());
        j.put("held_duty", h == null ? -1 : h.duty());
        j.put("mode_rgblevel", h == null ? -1 : h.rgblevel());
        j.put("mode_name", h == null ? "" : h.modeName());
        j.put("min_c", h == null ? Double.NaN : h.minC(), 2);
        j.put("max_c", h == null ? Double.NaN : h.maxC(), 2);
        j.put("last_c", h == null ? Double.NaN : h.lastC(), 2);
        j.put("foreign_writes", h == null ? 0 : h.foreignWrites());
        j.put("ceiling_c", SweepPlan.CEILING_C, 1);
        j.put("duty_floor", SweepPlan.DUTY_FLOOR);
        j.put("stock_controller_disabled", false);

        HoldSession.Steady st = h == null ? null : h.steady();
        j.put("steady_run", st != null);
        if (st != null) {
            j.put("steady_verdict", h.verdict());
            j.put("steady_seconds", st.seconds);
            j.put("steady_judged_from_s", st.judgedFromSec);
            j.put("steady_samples", st.samples);
            j.put("steady_changes", st.changes);
            j.put("steady_reversals", st.reversals);
            j.put("steady_duty_lo", st.loDuty);
            j.put("steady_duty_hi", st.hiDuty);
            j.put("steady_max_tick", st.maxTick);
            j.put("steady_min_c", st.minC, 2);
            j.put("steady_max_c", st.maxC, 2);
            j.put("judged_samples", st.judgedSamples);
            j.put("judged_changes", st.judgedChanges);
            j.put("judged_reversals", st.judgedReversals);
            j.put("judged_duty_lo", st.judgedLo);
            j.put("judged_duty_hi", st.judgedHi);
            j.put("judged_span", st.judgedSpan());
            j.put("judged_max_tick", st.judgedMaxTick);
            j.put("judged_trend_c_per_h", st.trendCPerHour(), 2);
            j.put("settled_threshold_c_per_h", HoldSession.SETTLED_C_PER_HOUR, 1);
        }

        j.arr("checks");
        if (h != null) {
            List<HoldSession.Check> cs = h.checks();
            for (int i = 0; i < cs.size(); i++) {
                HoldSession.Check c = cs.get(i);
                j.beginObject();
                j.put("epoch_ms", c.wallMs);
                j.put("elapsed_s", c.elapsedSec);
                j.put("about", c.about);
                j.put("verdict", c.verdict);
                j.put("pattern", c.pattern);
                j.put("duty", c.duty);
                j.put("mode_rgblevel", c.rgblevel);
                j.put("degC", c.degC, 2);
                j.endObject();
            }
        }
        j.endArray();

        j.arr("events");
        if (h != null) {
            List<String> ev = h.events();
            for (int i = 0; i < ev.size(); i++) {
                j.value(ev.get(i));
            }
        }
        j.endArray();
        j.endObject();
        return j.finish();
    }

    private static void header(Json j, Meta m, String kind) {
        Meta meta = m == null ? new Meta() : m;
        j.put("format", FORMAT);
        j.put("kind", kind);
        j.put("app_version", meta.appVersion);
        j.put("package", meta.packageName);
        j.putOpt("uid", meta.uid, -1);
        j.put("system_variant", meta.systemVariant);
        j.obj("device");
        j.put("ro.product.model", meta.model);
        j.put("ro.product.version", meta.firmware);
        j.put("ro.build.version.release", meta.androidRelease);
        j.put("ro.build.version.sdk", meta.androidSdk);
        j.endObject();
        j.obj("pattern");
        j.put("level_percent", meta.patternLevelPercent);
        j.put("overlay", meta.overlay);
        j.endObject();
        j.put("ambient_note", meta.ambientNote);
        j.put("trace_csv", meta.traceFile);
        j.put("report_json", meta.reportFile);
    }

    /** The DLPC temperature block; says in words when it could not be read, rather than going quietly absent. */
    private static void dlpcSummary(Json j, SweepEngine e) {
        int attempts = 0;
        int successes = 0;
        String reason = "not attempted";
        if (e != null) {
            List<SweepStep> ss = e.steps();
            for (int i = 0; i < ss.size(); i++) {
                PicoReg.Reading r = ss.get(i).dlpc;
                if (r == null) {
                    continue;
                }
                attempts++;
                if (PicoReg.STATUS_OK.equals(r.status)) {
                    successes++;
                }
                reason = r.reason;
            }
        }
        j.obj("dlpc_temperature");
        j.put("available", successes > 0);
        j.put("command", PicoReg.readCommand(PicoReg.OPCODE_SYSTEM_TEMPERATURE,
                PicoReg.SYSTEM_TEMPERATURE_LEN));
        j.put("node", PicoReg.NODE);
        j.put("attempts", attempts);
        j.put("successes", successes);
        j.put("reason", reason);
        j.put("units_confirmed", false);
        j.put("note", "Decoded as signed magnitude in whole degrees C: bit 11 sign, bits "
                + "10:0 magnitude, bits 15:12 must be zero (DLPU078 3.5.7). The raw 16-bit "
                + "word is recorded on every reading so the units can be reinterpreted "
                + "offline without re-running the sweep.");
        j.endObject();
    }

    private static void warnings(Json j, SweepEngine e) {
        j.arr("warnings");
        int successes = 0;
        int attempts = 0;
        if (e != null) {
            List<SweepStep> ss = e.steps();
            for (int i = 0; i < ss.size(); i++) {
                PicoReg.Reading r = ss.get(i).dlpc;
                if (r != null) {
                    attempts++;
                    if (PicoReg.STATUS_OK.equals(r.status)) {
                        successes++;
                    }
                }
            }
        }
        if (successes == 0) {
            j.value("DLPC SYSTEM TEMPERATURE (D6h) UNAVAILABLE on " + attempts
                    + " attempt(s). This is a precondition, not a bonus column: the 75 C "
                    + "shutdown watches the LED thermistor, but the DMD's 70 C limit is an "
                    + "array temperature that cannot be measured directly, and the "
                    + "relationship between the two is a board constant nobody has "
                    + "established. Without a controller-side temperature, a low floor "
                    + "cannot honestly be called safe for the DMD - only for the LED. See "
                    + "dlpc_temperature.reason for exactly which door was shut.");
        }
        if (e != null && e.markers().isEmpty()) {
            j.value("No audibility marker was recorded. The thermally-safe floor can be "
                    + "computed from this data; \"quiet enough\" cannot. That half of the "
                    + "objective still needs a person in the room pressing OK when the fan "
                    + "first becomes noticeable.");
        }
        if (e != null && e.endReason() != null && e.endReason().startsWith("abort")) {
            j.value("The run did not complete: " + e.endReason() + ". Everything measured "
                    + "before that point is in this file and is valid.");
        }
        j.endArray();
    }

    public static String traceName(long epoch, boolean verify) {
        return (verify ? "verify_trace_" : "trace_") + epoch + ".csv";
    }

    public static String reportName(long epoch, boolean verify) {
        return (verify ? "verify_" : "sweep_") + epoch + ".json";
    }
}
