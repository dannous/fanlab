import com.daleygames.fanlab.CsvLogger;
import com.daleygames.fanlab.CurveConfig;
import com.daleygames.fanlab.ExpFit;
import com.daleygames.fanlab.FanCurve;
import com.daleygames.fanlab.FanIo;
import com.daleygames.fanlab.FanLinear;
import com.daleygames.fanlab.HoldSession;
import com.daleygames.fanlab.Json;
import com.daleygames.fanlab.LedDrive;
import com.daleygames.fanlab.LinearConfig;
import com.daleygames.fanlab.Mode;
import com.daleygames.fanlab.PicoReg;
import com.daleygames.fanlab.Provenance;
import com.daleygames.fanlab.Sample;
import com.daleygames.fanlab.SweepEngine;
import com.daleygames.fanlab.SweepPlan;
import com.daleygames.fanlab.SweepReport;
import com.daleygames.fanlab.SweepStep;
import com.daleygames.fanlab.Sysfs;
import com.daleygames.fanlab.Thermistor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Headless tests for everything in FanLab that does not need Android.
 *
 * There is no way to run an armeabi-v7a Android 9 image on this machine, so the strategy
 * is: keep every piece of logic that matters in pure Java, point the sysfs layer at a
 * stub directory tree, and drive the whole thing from a plain main(). What is left
 * untested is the Android glue (service lifecycle, notification, storage discovery, view
 * focus) and, obviously, the real hardware.
 *
 * Run: java -cp classes;. FanLabTest
 */
public final class FanLabTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<String>();

    /**
     * The columns as they shipped before 2026-09-07. Written out in full rather than
     * derived from the current header, so the schema-revision test is driven by the exact
     * bytes sitting in the file on the device rather than by an assumption about them.
     */
    private static final String OLD_HEADER_20 =
            "epoch_ms,iso_local,adc,degC,prop_led_temp,fan_ctrl,rgblevel,led_status,"
                    + "profile,mode,desired,wrote,note,soc_pll_c,soc_ddr_c,soc_sar_c"
                    + ",thr_cpufreq,thr_cpucore,thr_gpufreq,thr_gpucore";

    // ----------------------------------------------------------------- harness

    private static void check(boolean ok, String what) {
        if (ok) {
            passed++;
        } else {
            failed++;
            failures.add(what);
            System.out.println("  FAIL  " + what);
        }
    }

    private static void eq(double a, double b, double tol, String what) {
        check(Math.abs(a - b) <= tol, what + "  (got " + a + ", want " + b + ")");
    }

    private static void eq(int a, int b, String what) {
        check(a == b, what + "  (got " + a + ", want " + b + ")");
    }

    private static void section(String s) {
        System.out.println();
        System.out.println("== " + s + " " + dashes(70 - s.length()));
    }

    private static String dashes(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(0, n); i++) {
            sb.append('-');
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        System.out.println("FanLab headless tests");

        testThermistorAgainstFramework();
        testThermistorParsing();
        testLadderBoundaries();
        testFanIoRange();
        testSysfsStub();
        testCurveShape();
        testCurveVsStockAtRungs();
        testHysteresis();
        testSlew();
        testCatchUpAfterResync();
        testEngineOffAndTierChange();
        testFailSafe();
        testConfigRoundTrip();
        testSocGuard();
        testLinearController();
        testLinearConfigRoundTrip();
        testLinearConvergence();
        testLinearCeilingPromotion();

        // ---- the LED drive override ----
        testLedDriveConfig();
        testLedDriveReadback();
        testLedDriveDecide();
        testCurvePresetsDoNotHunt();
        testOffDuration();
        testExclusiveControl();
        testCsvLogger();
        testProvenanceColumns();
        testClosedLoopVsStock();

        // ---- AUTO and VERIFY ----
        testExpFit();
        testSweepPlanConstants();
        testSweepHappyPath();
        testSweepCeilingAbort();
        testSweepRateGuard();
        testSweepBadReads();
        testSweepUserAbort();
        testSweepTimeCap();
        testHoldSession();
        testHoldSteadyPhase();
        testPicoReg();
        testCaic();
        testImageProcessing();
        testLooks();
        testReportOutput();

        System.out.println();
        System.out.println(dashes(74));
        System.out.println("passed " + passed + "   failed " + failed);
        for (int i = 0; i < failures.size(); i++) {
            System.out.println("  ! " + failures.get(i));
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    // ----------------------------------------------------------------- thermistor

    /**
     * An independent transcription of the framework's arithmetic, written straight from
     * the disassembly rather than by calling the code under test. If Thermistor ever
     * drifts from what BatteryService does, this catches it.
     */
    private static double frameworkCelsius(int adc) {
        double rt = adc * 100000.0 / (4095 - adc);
        double k = 1.0 / (Math.log(rt / 100000) / 4311.0 + 0.0033540164346805303);
        return k - 273.15 + 0.5;
    }

    private static void testThermistorAgainstFramework() {
        section("thermistor: exact agreement with the framework arithmetic");
        int[] spread = {1, 2, 10, 100, 250, 500, 800, 1000, 1200, 1365, 1500, 1638,
                1800, 2000, 2047, 2200, 2400, 2600, 2730, 3000, 3200, 3400, 3600,
                3800, 4000, 4090, 4094};
        int bitExact = 0;
        for (int i = 0; i < spread.length; i++) {
            int adc = spread[i];
            double mine = Thermistor.celsius(adc);
            double theirs = frameworkCelsius(adc);
            if (Double.doubleToLongBits(mine) == Double.doubleToLongBits(theirs)) {
                bitExact++;
            } else {
                check(false, "adc=" + adc + " mine=" + mine + " framework=" + theirs);
            }
        }
        check(bitExact == spread.length,
                "all " + spread.length + " sampled ADC codes are bit-identical to the "
                        + "framework (got " + bitExact + ")");

        // and the whole domain, not just a sample
        int mismatch = 0;
        for (int adc = Thermistor.ADC_MIN; adc <= Thermistor.ADC_MAX; adc++) {
            if (Double.doubleToLongBits(Thermistor.celsius(adc))
                    != Double.doubleToLongBits(frameworkCelsius(adc))) {
                mismatch++;
            }
        }
        eq(mismatch, 0, "every ADC code in 1..4094 is bit-identical");

        // out of domain must be NaN, not an exception and not a number
        check(Double.isNaN(Thermistor.celsius(0)), "adc 0 -> NaN");
        check(Double.isNaN(Thermistor.celsius(4095)), "adc 4095 -> NaN");
        check(Double.isNaN(Thermistor.celsius(-7)), "negative adc -> NaN");
        check(!Thermistor.plausible(Double.NaN), "NaN is not plausible");
        check(!Thermistor.plausible(Thermistor.celsius(1)),
                "adc 1 (" + Thermistor.celsius(1) + " C) is rejected as implausible");

        // monotone: this is an NTC on a divider, so more code means less heat
        boolean monotone = true;
        double prev = Double.MAX_VALUE;
        for (int adc = Thermistor.ADC_MIN; adc <= Thermistor.ADC_MAX; adc++) {
            double c = Thermistor.celsius(adc);
            if (c > prev) {
                monotone = false;
                break;
            }
            prev = c;
        }
        check(monotone, "temperature is monotone decreasing in the ADC code");
    }

    private static void testThermistorParsing() {
        section("thermistor: node parsing");
        // The framework takes the first n-1 characters. For real node content, which
        // always ends in a newline, the tolerant parser must agree with it exactly.
        int disagree = 0;
        for (int adc = 0; adc <= 4095; adc++) {
            String raw = adc + "\n";
            if (Thermistor.parseAdcFrameworkExact(raw) != Thermistor.parseAdc(raw)) {
                disagree++;
            }
        }
        eq(disagree, 0, "tolerant parser agrees with the framework for every 0..4095 + LF");

        eq(Thermistor.parseAdc("1638\n"), 1638, "trailing LF");
        eq(Thermistor.parseAdc("1638"), 1638, "no trailing LF (framework would say 163)");
        eq(Thermistor.parseAdcFrameworkExact("1638"), 163,
                "framework-exact really does lose the last digit without a LF");
        eq(Thermistor.parseAdc("1638\r\n"), 1638, "CRLF");
        eq(Thermistor.parseAdc("1638\n\0\0"), 1638, "NUL padding");
        eq(Thermistor.parseAdc("  1638  \n"), 1638, "surrounding spaces");
        eq(Thermistor.parseAdc(""), Thermistor.BAD_ADC, "empty");
        eq(Thermistor.parseAdc(null), Thermistor.BAD_ADC, "null");
        eq(Thermistor.parseAdc("garbage\n"), Thermistor.BAD_ADC, "non-numeric");
        eq(Thermistor.parseAdc("99999999999999\n"), Thermistor.BAD_ADC, "overlong");
    }

    private static void testLadderBoundaries() {
        section("thermistor: the ladder boundaries land where the research says");
        // The framework compares (int)(T + 0.5), so its "45" boundary is a true 45.5 C.
        // Find the ADC code either side of each transition and confirm the rounding.
        int[] want = {45, 48, 50, 52, 55};
        for (int w = 0; w < want.length; w++) {
            int target = want[w];
            int found = -1;
            for (int adc = Thermistor.ADC_MAX; adc >= Thermistor.ADC_MIN; adc--) {
                double c = Thermistor.celsius(adc);
                if (Thermistor.stockTmp(c) == target) {
                    found = adc;
                    break;
                }
            }
            check(found > 0, "an ADC code exists whose stock tmp is " + target);
            if (found > 0) {
                double c = Thermistor.celsius(found);
                check(c - 0.5 >= target - 0.5 - 0.02 && c - 0.5 < target + 0.5,
                        "stock tmp " + target + " corresponds to a true "
                                + round2(c - 0.5) + " C (adc " + found + ")");
            }
        }
        System.out.println("  reference table (true C = computed - 0.5 bias):");
        int[] codes = {1200, 1400, 1600, 1800, 2000, 2200, 2400, 2600, 2800, 3000};
        for (int i = 0; i < codes.length; i++) {
            double c = Thermistor.celsius(codes[i]);
            System.out.println("    adc " + pad(codes[i]) + "  computed "
                    + round2(c) + " C   true " + round2(c - 0.5)
                    + " C   stock tmp " + Thermistor.stockTmp(c));
        }
    }

    // ----------------------------------------------------------------- fan io

    private static void testFanIoRange() {
        section("fan io: only 1..100 is writable, and fail-safe is high");
        check(!FanIo.valid(0), "0 rejected");
        check(!FanIo.valid(-1), "-1 rejected");
        check(FanIo.valid(1), "1 accepted");
        check(FanIo.valid(100), "100 accepted");
        check(!FanIo.valid(101), "101 rejected");
        check(!FanIo.valid(Integer.MIN_VALUE), "MIN_VALUE rejected");
        eq(FanIo.FAIL_SAFE_DUTY, 83, "fail-safe duty is the stock maximum");
        check(FanIo.FAIL_SAFE_DUTY > FanIo.KERNEL_DEFAULT_DUTY,
                "fail-safe is above the kernel's own default, i.e. it is HIGH");
    }

    // ----------------------------------------------------------------- sysfs stub

    private static File stubRoot() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"),
                "fanlab-stub-" + System.nanoTime());
        mk(root, "sys/class/fan_int");
        mk(root, "sys/class/ledtemp");
        mk(root, "sys/class/dlpc343x");
        put(root, "sys/class/fan_int/fan_ctrl", "55\n");
        put(root, "sys/class/ledtemp/voltage", "2000\n");
        put(root, "sys/class/dlpc343x/rgblevel", "3\n");
        put(root, "sys/class/dlpc343x/led_status", "1\n");
        return root;
    }

    private static void mk(File root, String p) {
        new File(root, p).mkdirs();
    }

    private static void put(File root, String p, String v) throws Exception {
        File f = new File(root, p);
        f.getParentFile().mkdirs();
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        w.write(v);
        w.close();
    }

    private static void testSysfsStub() throws Exception {
        section("sysfs: reads, writes and every failure mode, against a stub tree");
        File root = stubRoot();
        Sysfs.root = root.getAbsolutePath();
        try {
            eq(Sysfs.readInt(Sysfs.LEDTEMP_VOLTAGE, -1), 2000, "reads the ADC node");
            eq(Sysfs.readInt(Sysfs.RGBLEVEL, -1), 3, "reads rgblevel");
            eq(Sysfs.readInt(Sysfs.LED_STATUS, -1), 1, "reads led_status");
            eq(FanIo.readDuty(), 55, "reads fan_ctrl");

            check(FanIo.writeDuty(63), "writes a valid duty");
            eq(FanIo.readDuty(), 63, "the write is visible on read-back");
            check(!FanIo.writeDuty(0), "writeDuty(0) refuses");
            check(!FanIo.writeDuty(101), "writeDuty(101) refuses");
            eq(FanIo.readDuty(), 63, "a refused write changes nothing");

            check(FanIo.writeFailSafe(), "fail-safe write succeeds");
            eq(FanIo.readDuty(), 83, "fail-safe leaves 83 behind");

            // no newline, matching the framework
            String raw = Sysfs.read(Sysfs.FAN_CTRL);
            check("83".equals(raw), "the value is written bare, no trailing newline (got "
                    + quote(raw) + ")");

            // failure modes must be quiet
            check(Sysfs.read("/sys/class/does/not/exist") == null,
                    "a missing file reads null, no exception");
            check(Sysfs.read("/sys/class/fan_int") == null || true,
                    "reading a directory does not throw");
            check(!Sysfs.write("/sys/class/does/not/exist/x", "1"),
                    "a write to a missing directory returns false, no exception");
            eq(Sysfs.readInt("/sys/class/does/not/exist", 42), 42,
                    "readInt falls back when the node is missing");

            // a node containing junk
            put(root, "sys/class/ledtemp/voltage", "banana\n");
            eq(Sysfs.readInt(Sysfs.LEDTEMP_VOLTAGE, Thermistor.BAD_ADC),
                    Thermistor.BAD_ADC, "junk in the ADC node is reported, not guessed");
            // an empty node
            put(root, "sys/class/ledtemp/voltage", "");
            eq(Sysfs.readInt(Sysfs.LEDTEMP_VOLTAGE, Thermistor.BAD_ADC),
                    Thermistor.BAD_ADC, "an empty ADC node is reported, not guessed");
        } finally {
            Sysfs.root = "";
            rmrf(root);
        }
    }

    // ----------------------------------------------------------------- curve

    private static void testCurveShape() {
        section("curve: the map from temperature to duty");
        CurveConfig c = new CurveConfig();
        for (int p = 0; p < CurveConfig.PROFILES; p++) {
            for (int i = 0; i < CurveConfig.POINTS; i++) {
                eq(c.dutyAt(p, c.tempC[i]), c.duty[p][i],
                        CurveConfig.PROFILE_NAMES[p] + ": the curve passes through knee "
                                + i + " (" + c.tempC[i] + " C)");
            }
            // flat outside the ends
            eq(c.dutyAt(p, -50), c.duty[p][0],
                    CurveConfig.PROFILE_NAMES[p] + ": flat below the first knee");
            eq(c.dutyAt(p, 200), c.duty[p][CurveConfig.POINTS - 1],
                    CurveConfig.PROFILE_NAMES[p] + ": flat above the last knee");
            // monotone non-decreasing, in tenths of a degree
            boolean mono = true;
            int prev = -1;
            for (int t = 200; t <= 700; t++) {
                int d = c.dutyAt(p, t / 10.0);
                if (d < prev) {
                    mono = false;
                    break;
                }
                prev = d;
            }
            check(mono, CurveConfig.PROFILE_NAMES[p]
                    + ": duty never falls as temperature rises");
            // every output is writable
            boolean inRange = true;
            for (int t = -100; t <= 200; t++) {
                if (!FanIo.valid(c.dutyAt(p, t))) {
                    inRange = false;
                    break;
                }
            }
            check(inRange, CurveConfig.PROFILE_NAMES[p]
                    + ": every output over -100..200 C is a writable duty");
        }
        // interpolation really is linear: the shipped 47->51 C segment runs 30->38, two
        // duty points per degree, so 48 C is 32 and the midpoint 49 C is 34.
        eq(c.dutyAt(CurveConfig.PROFILE_HIGH, 48), 32,
                "Presentation one degree up the 47 (30) to 51 (38) rise is 32");
        eq(c.dutyAt(CurveConfig.PROFILE_HIGH, 49), 34,
                "and its midpoint is 34");
        // and 51.5 C is on the shelf, so it is 38 exactly rather than interpolated -- which
        // is also, not by coincidence, where the deployed projector settles.
        eq(c.dutyAt(CurveConfig.PROFILE_HIGH, 51.5), 38,
                "Presentation on the 50-55 C shelf is 38");
        // clamps are honoured
        c.maxDuty = 40;
        c.sanitise();
        eq(c.dutyAt(CurveConfig.PROFILE_HIGH, 60), 40, "the hard maximum clamps the curve");
        c = new CurveConfig();
        c.minDuty = 70;
        c.sanitise();
        eq(c.dutyAt(CurveConfig.PROFILE_LOW, 30), 70, "the hard minimum lifts the curve");

        // The presets are offered to a user who cannot inspect them, so every one of them
        // is held to the rules the shipped curve was built on rather than only the default.
        for (int i = 0; i < CurveConfig.PRESETS.length; i++) {
            String name = CurveConfig.PRESET_NAMES[i];
            CurveConfig p = CurveConfig.preset(i);
            int[] high = p.duty[CurveConfig.PROFILE_HIGH];

            for (int k = 1; k < CurveConfig.POINTS; k++) {
                check(p.tempC[k] > p.tempC[k - 1], name + ": knee " + k + " ("
                        + p.tempC[k] + " C) is hotter than knee " + (k - 1));
            }
            eq(high[CurveConfig.POINTS - 1], 83,
                    name + ": the last knee reaches the stock maximum");
            for (int prof = 0; prof < CurveConfig.PROFILES; prof++) {
                for (int k = 1; k < CurveConfig.POINTS; k++) {
                    check(p.duty[prof][k] >= p.duty[prof][k - 1], name + " "
                            + CurveConfig.PROFILE_NAMES[prof] + ": duty does not fall "
                            + "between knee " + (k - 1) + " and " + k);
                }
            }
            // The columns used to be identical in every preset, which made "a brightness
            // change is not a tier change in duty terms" true by construction and stopped
            // FanCurve's immediate-jump exception ever firing. The Bright family breaks that
            // on purpose -- it needs more fan in Presentation and less reason to touch the
            // dim modes -- so the property is asserted here as what it actually has to be:
            // the three columns agree at every temperature at or below 55 C, which is where
            // the dim modes live and therefore where a brightness change is made from. Above
            // 55 C only Presentation is different, and only in the Bright family.
            //
            // Taking the +10 off knee 2 would move this bound to 55 C, and that redraw was
            // built and measured on 2026-09-08. It hunts on the hardware -- nine duty changes
            // in twelve minutes against the shipped rows' zero -- so the bound stays at 51.
            // The reasoning is against the Bright rungs in CurveConfig.PRESETS.
            //
            // What that leaves is stated rather than hidden: on Bright Quiet with the LED
            // drive raised, solved against the MEASURED plant, the step on a Normal ->
            // Presentation switch is 0 duty points out to a 25 C ambient, 1 at 26, 2 at
            // 26.2 -- the warmest this unit has recorded -- then 4 at 28 and 7 at 30. Eco ->
            // Presentation is stepless further still. In the standard family it is 0 always,
            // because the columns are identical.
            String disagreesAt = null;
            for (double t = -100.0; t <= 51.0 && disagreesAt == null; t += 0.25) {
                int low = p.dutyAt(CurveConfig.PROFILE_LOW, t);
                for (int prof = 1; prof < CurveConfig.PROFILES; prof++) {
                    if (p.dutyAt(prof, t) != low) {
                        disagreesAt = CurveConfig.PROFILE_NAMES[prof] + " wants "
                                + p.dutyAt(prof, t) + " where Eco / Super Eco wants " + low
                                + " at " + t + " C";
                        break;
                    }
                }
            }
            check(disagreesAt == null, name + ": all three profiles command the same duty "
                    + "at every temperature up to 51 C, so a brightness change made from "
                    + "where the dim modes live is not a step"
                    + (disagreesAt == null ? "" : " -- " + disagreesAt));
            // A shape check, not a stability proof. It catches a knee typed in wrong.
            //
            // It used to be described here as the stability criterion, and it is not:
            // Cold's rise from the pinned floor was 4 C wide, passed this, and hunted by
            // four duty points at 17 C ambient. Nor is the slope the criterion -- Cool's
            // 47-51 C rise is 4.5 duty/C and steady, while a 2.67 duty/C rise tried during
            // the shelf work hunted. Three static rules have been proposed for this curve
            // and all three passed something that hunts. The check that decides is dynamic:
            // testCurvePresetsDoNotHunt drives the real controller against the plant, and
            // tools/CurveSim.java is the fuller version of it.
            //
            // This bound was relaxed to 3 C while placing the shelf, because Normal's
            // settled reading (47.1 C) and Presentation's (50.8 C) leave only 3.7 C
            // between them and a 3 C rise would have pinned 100 % of Presentation rather
            // than 92 %. It was put straight back, because the relaxed version was built
            // and measured rather than argued about: at 3 C the slope is 2.7 duty/C, the
            // 0.8 C deadband then spans 2.1 duty points, no single duty can rest inside
            // it, and tools/CurveSim.java found it hunting by 2 points at 18 and 21 C
            // ambient where every 4 C version is steady at every pole. The 8 % of
            // Presentation time is the cheaper thing to give up.
            for (int k = 1; k < CurveConfig.POINTS; k++) {
                if (high[k] > high[k - 1]) {
                    check(p.tempC[k] - p.tempC[k - 1] >= 4, name + ": the rising segment "
                            + p.tempC[k - 1] + "-" + p.tempC[k] + " C is at least 4 C wide "
                            + "against a " + p.hysteresisC + " C deadband");
                }
            }
        }

        // What each preset IS, asserted against the published derivation rather than
        // against a second copy of the numbers here: the derivation is what carries the
        // stability argument, so the derivation is what wants pinning.
        //
        // That published derivation used to be int[] PRESET_OFFSETS, and it stopped being
        // able to describe the set the day the first Bright curve arrived. The standard four
        // are Quiet plus a constant at every knee above the floor, leaving the shelf's width
        // and slope equal to Quiet's so the margin against the deadband cannot have moved
        // there; each Bright one draws its Presentation row instead and takes its other two
        // rows from its standard counterpart's shape. Both are a CurveConfig.PresetShape
        // now, which is why this loop has no case in it -- the alternative was an offset
        // array with a sentinel in it and an if in every reader.
        //
        // The floor is deliberately NOT offset in either shape. Below the floor edge the
        // light engine is cool enough that extra fan buys almost nothing -- measured,
        // Cold's +15 bought 3.4 C in Super Eco on a thermistor already at 35 C -- and
        // Normal, Eco and Super Eco spend their whole lives there. So every preset idles at
        // 30, and the offset applies only where the ceiling is actually in question.
        //
        // Driven off the published PRESET_SHAPES rather than a copy of them here, so a
        // ninth curve added without a shape fails rather than going unchecked.
        CurveConfig.PresetShape[] shapes = CurveConfig.PRESET_SHAPES;
        eq(shapes.length, CurveConfig.PRESETS.length,
                "there is a shape here for every preset on offer");
        eq(shapes.length, CurveConfig.PRESET_NAMES.length,
                "and a name for every one of them");
        int[] knees = CurveConfig.preset(0).tempC;
        int[] quiet = CurveConfig.preset(0).duty[CurveConfig.PROFILE_HIGH];
        for (int i = 0; i < CurveConfig.PRESETS.length; i++) {
            String name = CurveConfig.PRESET_NAMES[i];
            CurveConfig p = CurveConfig.preset(i);
            CurveConfig.PresetShape shape = shapes[i];
            // The floor edge is the one knee a preset may move, and only downward: with the
            // floor pinned at 30, a preset whose shelf sits 15 points above Quiet's has to
            // climb 23 points where Quiet climbs 8, and over the same 4 C that is 5.75
            // duty/C -- steep enough to hunt, and Cold did, by four points at 17 C.
            // Starting its rise earlier is the only fix that keeps both the pinned floor
            // and the shelf. It must never move UP: that would narrow the rise and steepen
            // it further.
            eq(p.tempC[0], shape.floorEdgeC, name + ": floor edge is the "
                    + shape.floorEdgeC + " C its shape declares");
            check(p.tempC[0] <= knees[0], name + ": floor edge " + p.tempC[0]
                    + " C is at or before Quiet's " + knees[0]);
            for (int k = 1; k < CurveConfig.POINTS; k++) {
                eq(p.tempC[k], knees[k], name + ": knee " + k + " is at Quiet's "
                        + knees[k] + " C, so the segment widths above the floor are Quiet's");
            }
            for (int prof = 0; prof < CurveConfig.PROFILES; prof++) {
                int[] want = shape.row(prof, quiet);
                eq(want.length, CurveConfig.POINTS, name + " "
                        + CurveConfig.PROFILE_NAMES[prof] + ": its shape derives a row of "
                        + CurveConfig.POINTS + " knees");
                for (int k = 0; k < CurveConfig.POINTS; k++) {
                    eq(p.duty[prof][k], want[k], name + " "
                            + CurveConfig.PROFILE_NAMES[prof] + ": knee " + k + " is the "
                            + want[k] + " its shape derives from Quiet's " + quiet[k]);
                }
            }
            // Quietest rung first, WITHIN a family. The two families are the same four
            // rungs, so the ordering is a property of each family and not of the list: the
            // comparison is against the previous rung in the same family, which for
            // Bright Balanced is Bright Quiet and not Cold. Across the families it would
            // mean nothing -- Bright Quiet is louder than Cold above 51 C and quieter below
            // it, because they are curves for two different machines.
            if (CurveConfig.rungOf(i) > 0) {
                int prevPreset = i - 1;
                int[] prev = CurveConfig.preset(prevPreset).duty[CurveConfig.PROFILE_HIGH];
                int[] mine = p.duty[CurveConfig.PROFILE_HIGH];
                for (int k = 0; k < CurveConfig.POINTS; k++) {
                    check(mine[k] >= prev[k], name + ": knee " + k + " is at least "
                            + CurveConfig.PRESET_NAMES[prevPreset] + "'s, so each family "
                            + "runs quietest first");
                }
                check(mine[1] > prev[1], name + ": and strictly louder than "
                        + CurveConfig.PRESET_NAMES[prevPreset] + " at the top of the rise");
            }
            // Stated as its own assertion rather than left implicit in the loop above,
            // because it is the owner's requirement in his own words: "i want the floor to
            // be 30 for every curve mode and every projector mode". The second half is the
            // per-profile sweep above, and together they mean every preset idles at 30 in
            // every brightness mode -- Bright included, which is the whole reason its dim
            // columns were left as Quiet's.
            for (int prof = 0; prof < CurveConfig.PROFILES; prof++) {
                eq(p.duty[prof][0], 30, name + " " + CurveConfig.PROFILE_NAMES[prof]
                        + ": idles at 30, the floor every preset shares");
                eq(p.dutyAt(prof, 20.0), 30, name + " " + CurveConfig.PROFILE_NAMES[prof]
                        + ": and commands 30 at any temperature below the floor edge");
            }
        }

        // Rule 6: the ceiling has to arrive above anything the plant can produce. Clipping
        // moves that temperature down for the two biggest offsets in each family -- Quiet
        // and Balanced reach 83 at the last knee and Cool and Cold at the one before it,
        // and the Bright rungs land the same way -- so it is worth saying where each one
        // lands rather than trusting that "83 somewhere" is enough. 52.85 C is the hottest
        // degC ever recorded on this unit.
        for (int i = 0; i < CurveConfig.PRESETS.length; i++) {
            CurveConfig p = CurveConfig.preset(i);
            int[] high = p.duty[CurveConfig.PROFILE_HIGH];
            int at = -1;
            for (int k = 0; k < CurveConfig.POINTS && at < 0; k++) {
                if (high[k] >= 83) {
                    at = p.tempC[k];
                }
            }
            check(at >= 60, CurveConfig.PRESET_NAMES[i] + ": reaches 83 by " + at
                    + " C, far above the 52.85 C this unit has ever recorded");
        }

        testBrightPresetFamily();
    }

    /**
     * The two preset families, pinned: the four Bright rows knee by knee, their relationship
     * to their standard counterparts, and the gate that decides which family is on offer.
     *
     * The loops above hold every preset to the rules and to its published shape, which is
     * the right level for a family. The Bright four get their numbers written down as well,
     * because they are the four whose Presentation row was drawn rather than derived: there
     * is no offset to re-derive them from, so a change should be a deliberate edit here and
     * not a diff nobody reads.
     *
     * Each row is its counterpart's Presentation row plus 0, 0, 10, 12, 8, 0 at the six
     * knees, clipped at 83. That uniformity is asserted rather than described, because a
     * comment saying the family is uniform and a table where one rung is not would be worse
     * than no comment.
     *
     * Where they settle is INFERRED for every mode but Presentation -- the drive-90
     * Presentation plant scaling is measured at x1.208, the other three are still the fitted
     * line -- so no equilibrium is asserted here. See CurveConfig.PRESETS and docs/curve.md.
     * What is asserted is the shape and the gate, which are facts about the file.
     */
    private static void testBrightPresetFamily() {
        section("curve: two preset families, and the drive decides which one is on offer");

        eq(CurveConfig.PRESET_NAMES.length, 8, "eight presets on offer");
        eq(CurveConfig.RUNGS, 4, "four rungs, run twice");
        eq(CurveConfig.PRESETS.length, CurveConfig.PRESET_NAMES.length,
                "and a curve for every name");

        // Every one of the eight survives the trip out to a curve and back to an index. The
        // label on the screen is computed this way on every sync, so a preset that did not
        // round trip would show as Custom while running perfectly well.
        for (int i = 0; i < CurveConfig.PRESETS.length; i++) {
            eq(CurveConfig.presetOf(CurveConfig.preset(i).encode()), i,
                    CurveConfig.PRESET_NAMES[i] + ": preset(" + i + ") is recognised back "
                            + "as preset " + i);
        }

        // The families, by name and by index. Positional and RUNGS apart, which is what
        // counterpartOf relies on.
        String[] standard = {"Quiet", "Balanced", "Cool", "Cold"};
        String[] bright = {"Bright Quiet", "Bright Balanced", "Bright Cool", "Bright Cold"};
        int[] std = CurveConfig.standardPresets();
        int[] brt = CurveConfig.brightPresets();
        eq(std.length, CurveConfig.RUNGS, "the standard family is one preset per rung");
        eq(brt.length, CurveConfig.RUNGS, "and so is the Bright one");
        for (int rung = 0; rung < CurveConfig.RUNGS; rung++) {
            check(standard[rung].equals(CurveConfig.PRESET_NAMES[std[rung]]),
                    "rung " + rung + " of the standard family is " + standard[rung]);
            check(bright[rung].equals(CurveConfig.PRESET_NAMES[brt[rung]]),
                    "rung " + rung + " of the Bright family is " + bright[rung]);
            check(!CurveConfig.isBrightPreset(std[rung]),
                    standard[rung] + " is not a Bright preset");
            check(CurveConfig.isBrightPreset(brt[rung]), bright[rung] + " is");
            eq(CurveConfig.rungOf(std[rung]), rung, standard[rung] + " is rung " + rung);
            eq(CurveConfig.rungOf(brt[rung]), rung, bright[rung] + " is the same rung");
        }
        check(!CurveConfig.isBrightPreset(CurveConfig.PRESET_CUSTOM),
                "a hand-edited curve is not a Bright preset");
        eq(CurveConfig.rungOf(CurveConfig.PRESET_CUSTOM), -1, "and has no rung either");
        eq(CurveConfig.rungOf(99), -1, "nor does an index from nowhere");

        // The four Bright Presentation rows, exactly as tabled. Written out here rather than
        // read off PRESET_SHAPES, because this is the copy a human checks the table against.
        int[][] wantHigh = {
                {30, 38, 50, 62, 76, 83},   // Bright Quiet
                {30, 43, 55, 67, 81, 83},   // Bright Balanced
                {30, 48, 60, 72, 83, 83},   // Bright Cool
                {30, 53, 65, 77, 83, 83},   // Bright Cold
        };
        // The one edit that makes a Bright rung out of a standard one, at each knee.
        int[] delta = {0, 0, 10, 12, 8, 0};

        for (int rung = 0; rung < CurveConfig.RUNGS; rung++) {
            CurveConfig s = CurveConfig.preset(std[rung]);
            CurveConfig b = CurveConfig.preset(brt[rung]);
            String name = bright[rung];

            for (int k = 0; k < CurveConfig.POINTS; k++) {
                eq(b.duty[CurveConfig.PROFILE_HIGH][k], wantHigh[rung][k],
                        name + " Presentation: knee " + k + " is " + wantHigh[rung][k]);
                // Knees 1..5 are the counterpart's, always: that is what makes a Bright
                // preset one column different from its standard rung and no more.
                //
                // Knee 0, the floor edge, is the counterpart's for three of the four. The
                // exception is Bright Cool, and it is an exception with a measurement
                // behind it: below 51 C a Bright preset IS its counterpart, so Bright Cool
                // inherits Cool's 4.5 duty/C rise over 47-51 C. Harmless on the plant Cool
                // runs on, because nothing rests there; on the raised plant of drive 90 the
                // operating point lands on it and CurveSim hunts by three at 14 C, over the
                // bound of two. Starting its rise at 45 clears the whole 14-34 C sweep.
                // Cool itself is untouched.
                if (k > 0 || !"Bright Cool".equals(name)) {
                    eq(b.tempC[k], s.tempC[k], name + ": knee " + k + " is at "
                            + standard[rung] + "'s " + s.tempC[k] + " C");
                } else {
                    eq(b.tempC[0], 45, "Bright Cool: floor edge is 45 C, its own, because "
                            + "Cool's 47 puts the raised plant's operating point on a rise "
                            + "steep enough to hunt by three at 14 C");
                    eq(s.tempC[0], 47, "while Cool itself keeps its 47 C");
                }
                // The dim rows ARE the counterpart's. This is the property that makes a
                // Bright preset one column different from its standard rung and no more, and
                // it is why the raised drive does not also make the quiet modes louder.
                eq(b.duty[CurveConfig.PROFILE_NORMAL][k], s.duty[CurveConfig.PROFILE_NORMAL][k],
                        name + " Normal: knee " + k + " is " + standard[rung] + "'s "
                                + s.duty[CurveConfig.PROFILE_NORMAL][k] + ", untouched");
                eq(b.duty[CurveConfig.PROFILE_LOW][k], s.duty[CurveConfig.PROFILE_LOW][k],
                        name + " Eco / Super Eco: knee " + k + " is " + standard[rung] + "'s "
                                + s.duty[CurveConfig.PROFILE_LOW][k] + ", untouched");
                // And the whole family is the same edit, clipped at the shared 83 ceiling.
                int want = Math.min(83, s.duty[CurveConfig.PROFILE_HIGH][k] + delta[k]);
                eq(b.duty[CurveConfig.PROFILE_HIGH][k], want, name + " Presentation: knee "
                        + k + " is " + standard[rung] + "'s "
                        + s.duty[CurveConfig.PROFILE_HIGH][k] + " + " + delta[k]
                        + " clipped at 83, the same edit as every other rung");
            }

            // Everything that is not a duty row is the counterpart's, which is what lets the
            // stability argument above the floor be inherited rather than remade.
            eq(b.hysteresisC, s.hysteresisC, 1e-9, name + ": " + standard[rung] + "'s deadband");
            eq(b.slewUpPerSec, s.slewUpPerSec, 1e-9, name + ": its rising slew");
            eq(b.slewDownPerSec, s.slewDownPerSec, 1e-9, name + ": its falling slew");
            eq(b.minDuty, s.minDuty, name + ": its floor");
            eq(b.maxDuty, s.maxDuty, name + ": its ceiling");
            eq(b.idleDuty, s.idleDuty, name + ": its idle duty");
            check(b.socGuardEnabled == s.socGuardEnabled, name + ": the guard is armed as "
                    + standard[rung] + "'s is");
            eq(b.socGuardStartC, s.socGuardStartC, name + ": its guard knee");
            eq(b.socGuardGainPerC, s.socGuardGainPerC, 1e-9, name + ": its guard gain");
            eq(b.socGuardMaxDuty, s.socGuardMaxDuty, name + ": its guard ceiling");
            eq(b.socGuardHystC, s.socGuardHystC, 1e-9, name + ": its guard deadband");

            // Monotone in temperature and reaching the stock maximum by 70 C, checked on the
            // controller's own output rather than on the table, because dutyAt applies the
            // clamps and the rounding and it is dutyAt the fan sees.
            int prev = -1;
            boolean monotone = true;
            for (double t = -100.0; t <= 200.0; t += 0.1) {
                int d = b.dutyAt(CurveConfig.PROFILE_HIGH, t);
                if (prev >= 0 && d < prev) {
                    monotone = false;
                    break;
                }
                prev = d;
            }
            check(monotone, name + " Presentation: never asks for less fan as it gets hotter");
            eq(b.dutyAt(CurveConfig.PROFILE_HIGH, 70.0), 83,
                    name + " Presentation: 83 by 70 C, the same backstop as every preset");
            eq(b.dutyAt(CurveConfig.PROFILE_HIGH, 55.0), wantHigh[rung][2], name
                    + " Presentation: " + wantHigh[rung][2] + " at 55 C, where "
                    + standard[rung] + " is flat at " + s.duty[CurveConfig.PROFILE_HIGH][2]);
            eq(b.dutyAt(CurveConfig.PROFILE_HIGH, 51.0), s.duty[CurveConfig.PROFILE_HIGH][1],
                    name + " Presentation: " + s.duty[CurveConfig.PROFILE_HIGH][1]
                            + " at 51 C, where " + standard[rung] + "'s shelf starts");
        }

        // ---- counterpartOf: the same rung in the family the drive selects ----
        //
        // An involution across the two families, which is the property the gate needs: a
        // user who switches the override on and off again must get back the preset they
        // started with and not a neighbouring rung.
        for (int i = 0; i < CurveConfig.PRESET_NAMES.length; i++) {
            String name = CurveConfig.PRESET_NAMES[i];
            int rung = CurveConfig.rungOf(i);
            eq(CurveConfig.counterpartOf(i, false), std[rung],
                    name + " with the drive off is " + standard[rung]);
            eq(CurveConfig.counterpartOf(i, true), brt[rung],
                    name + " with the drive on is " + bright[rung]);
            eq(CurveConfig.counterpartOf(CurveConfig.counterpartOf(i, !CurveConfig
                            .isBrightPreset(i)), CurveConfig.isBrightPreset(i)), i,
                    name + ": crossing to the other family and back lands on itself");
            check(CurveConfig.isBrightPreset(CurveConfig.counterpartOf(i, true)),
                    name + "'s drive-on counterpart is a Bright preset");
            check(!CurveConfig.isBrightPreset(CurveConfig.counterpartOf(i, false)),
                    name + "'s drive-off counterpart is a standard one");
        }
        // Custom has no counterpart and must not be given one -- see curveForDrive.
        eq(CurveConfig.counterpartOf(CurveConfig.PRESET_CUSTOM, true),
                CurveConfig.PRESET_CUSTOM, "a hand-edited curve has no Bright counterpart");
        eq(CurveConfig.counterpartOf(CurveConfig.PRESET_CUSTOM, false),
                CurveConfig.PRESET_CUSTOM, "nor a standard one");

        // ---- the gate itself ----
        //
        // Prefs.setLedDriveOn is Android and cannot run here, so what is driven is the pure
        // decision it delegates to. The wiring above it -- all three LED drive writers call
        // it, and they ask Prefs.ledBoostOn rather than the raw flag -- is the part this
        // suite cannot reach.
        for (int rung = 0; rung < CurveConfig.RUNGS; rung++) {
            String off = CurveConfig.PRESETS[std[rung]];
            String on = CurveConfig.PRESETS[brt[rung]];
            check(CurveConfig.curveForDrive(off, true).equals(on),
                    "enabling the drive moves " + standard[rung] + " to " + bright[rung]);
            check(CurveConfig.curveForDrive(on, false).equals(off),
                    "disabling it moves " + bright[rung] + " back to " + standard[rung]);
            check(CurveConfig.curveForDrive(off, false).equals(off),
                    standard[rung] + " with the drive already off is left alone");
            check(CurveConfig.curveForDrive(on, true).equals(on),
                    bright[rung] + " with the drive already on is left alone");
        }
        // Stated on its own for rung 0, because it is the transition the owner described and
        // the one a fresh install makes.
        check(CurveConfig.curveForDrive(CurveConfig.PRESETS[0], true)
                        .equals(CurveConfig.PRESETS[4]),
                "so Quiet becomes Bright Quiet when the LED drive comes on");
        check(CurveConfig.curveForDrive(CurveConfig.PRESETS[4], false)
                        .equals(CurveConfig.PRESETS[0]),
                "and Bright Quiet becomes Quiet again when it goes off");

        // A hand-edited curve survives both, untouched. Silently replacing thirty numbers
        // somebody typed is worse than the pairing the gate exists to prevent, and there is
        // no counterpart to replace them with anyway.
        String custom = CurveConfig.PRESETS[1].replace(",0.8,", ",0.9,");
        eq(CurveConfig.presetOf(custom), CurveConfig.PRESET_CUSTOM,
                "the hand-edited curve really is Custom");
        check(CurveConfig.curveForDrive(custom, true).equals(custom),
                "a Custom curve is untouched by the drive coming on");
        check(CurveConfig.curveForDrive(custom, false).equals(custom),
                "and untouched by it going off");
        check(CurveConfig.curveForDrive(null, true) == null,
                "and so is no curve at all, rather than becoming one");

        // ---- the broadcast refusal ----
        //
        // The words matter as much as the behaviour: this string is the whole of what a
        // caller gets back, and it has to name the preset they probably wanted.
        check(("Quiet is a Curve preset and the LED drive is on; use Bright Quiet or turn "
                        + "the drive off").equals(CurveConfig.wrongFamilyRefusal(0, true)),
                "the refusal names the counterpart and both ways out  (got "
                        + quote(CurveConfig.wrongFamilyRefusal(0, true)) + ")");
        check(("Bright Cold is a Bright Curve preset and the LED drive is off; use Cold or turn "
                        + "the drive on").equals(CurveConfig.wrongFamilyRefusal(7, false)),
                "and reads the same way in the other direction  (got "
                        + quote(CurveConfig.wrongFamilyRefusal(7, false)) + ")");
        for (int rung = 0; rung < CurveConfig.RUNGS; rung++) {
            check(CurveConfig.wrongFamilyRefusal(std[rung], false) == null,
                    standard[rung] + " with the drive off is not refused");
            check(CurveConfig.wrongFamilyRefusal(brt[rung], true) == null,
                    bright[rung] + " with the drive on is not refused");
            check(CurveConfig.wrongFamilyRefusal(std[rung], true) != null,
                    standard[rung] + " with the drive on is refused");
            check(CurveConfig.wrongFamilyRefusal(brt[rung], false) != null,
                    bright[rung] + " with the drive off is refused");
        }
        // Not a preset at all is somebody else's error message -- the receiver has already
        // rejected it by name before this is asked.
        check(CurveConfig.wrongFamilyRefusal(CurveConfig.PRESET_CUSTOM, true) == null,
                "a curve that is no preset is not refused on family grounds");
        check(CurveConfig.wrongFamilyRefusal(99, false) == null, "nor is an index from nowhere");

        // ---- the names a broadcast accepts ----
        //
        // A two-word preset typed into a shell arrives in three spellings and none of them
        // is a mistake worth an error message.
        eq(CurveConfig.presetNamed("Bright Quiet"), 4, "\"Bright Quiet\" is preset 4");
        eq(CurveConfig.presetNamed("bright quiet"), 4, "and so is \"bright quiet\"");
        eq(CurveConfig.presetNamed("brightquiet"), 4, "and \"brightquiet\"");
        eq(CurveConfig.presetNamed("bright-quiet"), 4, "and \"bright-quiet\"");
        eq(CurveConfig.presetNamed("  BRIGHT_QUIET  "), 4, "and \"  BRIGHT_QUIET  \"");
        eq(CurveConfig.presetNamed("quiet"), 0, "\"quiet\" is still preset 0");
        eq(CurveConfig.presetNamed("bright"), CurveConfig.PRESET_CUSTOM,
                "and bare \"bright\" is no longer a preset, because there are four of them");
        eq(CurveConfig.presetNamed("brightest"), CurveConfig.PRESET_CUSTOM,
                "a name close to one is still not one");
        eq(CurveConfig.presetNamed(""), CurveConfig.PRESET_CUSTOM, "nor is an empty name");
        eq(CurveConfig.presetNamed("  "), CurveConfig.PRESET_CUSTOM, "nor is whitespace");
        eq(CurveConfig.presetNamed(null), CurveConfig.PRESET_CUSTOM, "nor is no name");
        for (int i = 0; i < CurveConfig.PRESET_NAMES.length; i++) {
            eq(CurveConfig.presetNamed(CurveConfig.PRESET_NAMES[i]), i,
                    CurveConfig.PRESET_NAMES[i] + " is accepted under the name it is shown "
                            + "under, so the two cannot drift");
        }

        // The words the broadcast reply offers, which are the ones it will accept next.
        check("quiet, balanced, cool or cold".equals(CurveConfig.familyWords(false)),
                "with the drive off the reply offers the standard four  (got "
                        + quote(CurveConfig.familyWords(false)) + ")");
        check("bright quiet, bright balanced, bright cool or bright cold"
                        .equals(CurveConfig.familyWords(true)),
                "and with it on, the Bright four  (got "
                        + quote(CurveConfig.familyWords(true)) + ")");
    }

    private static void testCurveVsStockAtRungs() {
        section("curve: quieter than stock below the ceiling, and firm above it");
        CurveConfig c = new CurveConfig();

        // Running quieter than stock IS the feature, so the invariant is that the curve
        // commands LESS than stock at every rung temperature -- bounded by a measured
        // ceiling rather than by stock's own choices.
        int[] rungs = {48, 50, 52, 55};
        for (int p = 0; p < CurveConfig.PROFILES; p++) {
            int floor = c.duty[p][0];
            for (int i = 0; i < rungs.length; i++) {
                int mine = c.dutyAt(p, rungs[i]);
                int stock = FanCurve.stockLadder(p, rungs[i], floor);
                check(mine < stock, CurveConfig.PROFILE_NAMES[p] + " at " + rungs[i]
                        + " C: " + mine + " is quieter than stock's " + stock);
            }
        }

        // But it must not stay quiet forever: by the top of the ramp it has to be as
        // aggressive as stock's maximum, or the backstop is decorative.
        eq(c.dutyAt(CurveConfig.PROFILE_HIGH, 70), 83,
                "at 70 C the curve reaches the same 83 stock uses for its top rung");

        // The floor is flat to its edge. Normal's settled thermistor median is 46.5 C and
        // Eco's is 38.5 C -- both measured in the field, not taken from the plant table,
        // whose Normal column reads about 2.5 C low. Both sit inside this floor.
        for (int t = 0; t <= 47; t++) {
            eq(c.dutyAt(CurveConfig.PROFILE_HIGH, t), c.minDuty,
                    "flat at the floor at " + t + " C");
        }
        // And the shelf -- the operating point in Presentation -- spans a single duty
        // point right across the band the machine occupies. This is the property the whole
        // curve exists for, so it is asserted rather than left to the equilibrium solver.
        //
        // It tilts rather than being flat deliberately: the owner asked for slightly more
        // fan when the light engine is hotter, and 38->40 across four degrees buys 0.8 C at
        // the top of the band. 40 is the top because that is where he stops calling it
        // silent -- "happy with the fan up until 40". What must not regress is the SPAN: a
        // shelf that moved more than two points would be a ramp again, and the whole curve
        // exists to get the operating point off a ramp.
        for (int h = 1020; h <= 1100; h++) {
            double t = h / 20.0;
            int d = c.dutyAt(CurveConfig.PROFILE_HIGH, t);
            check(d >= 38 && d <= 40, "the shelf at " + t + " C is 38..40, not " + d);
        }
        eq(c.dutyAt(CurveConfig.PROFILE_HIGH, 51), 38, "the shelf starts at 38");
        eq(c.dutyAt(CurveConfig.PROFILE_HIGH, 55), 40, "and ends two points higher, at 40");
        eq(c.dutyAt(CurveConfig.PROFILE_HIGH, 52), 39, "reading 39 from 52 C");
        eq(c.dutyAt(CurveConfig.PROFILE_HIGH, 53), 39, "still 39 at 53 C");
        eq(c.dutyAt(CurveConfig.PROFILE_HIGH, 54), 40, "and 40 from 54 C");
        // Half a duty point per degree. Asserted as arithmetic on the knees so that moving
        // one without the other cannot quietly turn the shelf back into a ramp.
        int lo = c.duty[CurveConfig.PROFILE_HIGH][1];
        int hi = c.duty[CurveConfig.PROFILE_HIGH][2];
        int span = c.tempC[2] - c.tempC[1];
        check(hi - lo <= 2, "the shelf spans at most two duty points, not " + (hi - lo));
        check((hi - lo) * 10 / span <= 5,
                "so its slope is at most 0.5 duty/C, an eighth of the 2.14 it replaced");

        // Identical columns are what removes the mode-change jump (FanCurve bypasses the
        // slew limiter on an upward tier change, so unequal floors step audibly).
        for (int t = 20; t <= 90; t++) {
            eq(c.dutyAt(CurveConfig.PROFILE_LOW, t), c.dutyAt(CurveConfig.PROFILE_HIGH, t),
                    "all profiles agree at " + t + " C, so a mode change is not a step");
        }

        // Monotone: hotter must never mean less fan.
        int prev = -1;
        boolean monotone = true;
        for (int t = 0; t <= 100; t++) {
            int d = c.dutyAt(CurveConfig.PROFILE_HIGH, t);
            if (prev >= 0 && d < prev) {
                monotone = false;
                break;
            }
            prev = d;
        }
        check(monotone, "the curve never commands less fan for a higher temperature");
        // The stock dead zone exists and the curve does not have it. Stock returns -1 at
        // 53 and 54 C, meaning it writes nothing and the fan keeps whatever the last rung
        // left it at; the curve always has a definite answer. Note the curve's answer here
        // is deliberately the SAME at 52, 53 and 54 -- that is the shelf, not a dead zone.
        // The two are opposites: a dead zone is the controller declining to say, and the
        // shelf is it saying the same thing on purpose.
        eq(FanCurve.stockLadder(CurveConfig.PROFILE_HIGH, 53, 63), -1,
                "the stock ladder writes nothing at 53 C");
        eq(FanCurve.stockLadder(CurveConfig.PROFILE_HIGH, 54, 63), -1,
                "the stock ladder writes nothing at 54 C");
        for (int t = 53; t <= 54; t++) {
            check(FanIo.valid(c.dutyAt(CurveConfig.PROFILE_HIGH, t)),
                    "the curve commands a writable duty at " + t + " C, where stock does not");
        }
        check(c.dutyAt(CurveConfig.PROFILE_HIGH, 56) > c.dutyAt(CurveConfig.PROFILE_HIGH, 55),
                "and above the shelf it starts rising again");
    }

    private static void testHysteresis() {
        section("hysteresis: the fix for the actual bug");
        CurveConfig c = new CurveConfig();
        FanCurve f = new FanCurve();

        // Start where the CURVE wants to be at this temperature, not at an arbitrary 63.
        //
        // This mattered and was missed once. The test used to resync(63) because under the
        // old default 45.5 C was duty 63, so the controller began at its own equilibrium
        // and any transition really was a response to the dither. Under the shipped curve
        // 45.5 C is on the flat shelf at duty 30, so resync(63) makes the loop count the
        // 33-step ramp DOWN from 63 to 30 -- a settling transient wearing the costume of a
        // dither failure. It briefly persuaded me to widen the hysteresis band, which
        // would have cost a duty point on every machine to fix nothing.
        f.resync(c.dutyAt(CurveConfig.PROFILE_HIGH, 45.5));

        // Sit on the stock ladder's first boundary and dither by +/- 0.4 C, which is what
        // the real machine does. The curve must not move at all.
        long t = 0;
        int first = -1;
        int changes = 0;
        int prev = -1;
        for (int i = 0; i < 600; i++) {
            double temp = 45.5 + ((i % 2 == 0) ? 0.4 : -0.4);
            int d = f.step(c, CurveConfig.PROFILE_HIGH, temp, true, t);
            if (first < 0) {
                first = d;
            }
            if (prev >= 0 && d != prev) {
                changes++;
            }
            prev = d;
            t += 1000;
        }
        eq(changes, 0, "600 s of +/-0.4 C dither produces no duty change at all");

        // for contrast, the stock ladder on the same input
        int stockChanges = 0;
        int stockPrev = -1;
        for (int i = 0; i < 600; i++) {
            double temp = 45.5 + ((i % 2 == 0) ? 0.4 : -0.4);
            int d = FanCurve.stockLadder(CurveConfig.PROFILE_HIGH,
                    Thermistor.stockTmp(temp + 0.5), 55);
            if (stockPrev >= 0 && d != stockPrev) {
                stockChanges++;
            }
            stockPrev = d;
        }
        check(stockChanges > 0, "the stock ladder does change on the same input ("
                + stockChanges + " transitions), which is the bug");
        System.out.println("  600 s of +/-0.4 C dither around the 45.5 C boundary:");
        System.out.println("    stock ladder : " + stockChanges + " duty transitions");
        System.out.println("    fanlab curve : " + changes + " duty transitions");

        // rising must be immediate, not deadbanded
        f = new FanCurve();
        f.resync(62);
        t = 0;
        f.step(c, CurveConfig.PROFILE_HIGH, 45.0, true, t);
        double heldBefore = f.heldCelsius();
        t += 1000;
        f.step(c, CurveConfig.PROFILE_HIGH, 45.2, true, t);
        check(f.heldCelsius() > heldBefore, "a rising temperature moves the held value at once");

        // falling must wait for the deadband
        t += 1000;
        f.step(c, CurveConfig.PROFILE_HIGH, 44.5, true, t);
        eq((int) Math.round(f.heldCelsius() * 10), 452,
                "a 0.7 C fall is inside the 1.5 C deadband, so the held value is unchanged");
        t += 1000;
        f.step(c, CurveConfig.PROFILE_HIGH, 43.5, true, t);
        eq((int) Math.round(f.heldCelsius() * 10), 435,
                "a 1.7 C fall is outside the deadband, so the held value follows");
    }

    private static void testSlew() {
        section("slew: no change is fast enough to hear");
        CurveConfig c = new CurveConfig();
        c.slewUpPerSec = 5.0;
        c.slewDownPerSec = 1.0;
        FanCurve f = new FanCurve();
        f.resync(55);

        long t = 0;
        f.step(c, CurveConfig.PROFILE_HIGH, 40.0, true, t);       // settle at the bottom
        int prev = 55;
        int worstUp = 0;
        for (int i = 0; i < 40; i++) {
            t += 1000;
            int d = f.step(c, CurveConfig.PROFILE_HIGH, 60.0, true, t);   // slam to the top
            worstUp = Math.max(worstUp, d - prev);
            prev = d;
        }
        check(worstUp <= 5, "a 40 -> 60 C step never raises duty by more than 5 points in "
                + "one second (worst " + worstUp + ")");
        eq(prev, c.dutyAt(CurveConfig.PROFILE_HIGH, 60), "it does eventually get there");

        int worstDown = 0;
        for (int i = 0; i < 200; i++) {
            t += 1000;
            int d = f.step(c, CurveConfig.PROFILE_HIGH, 30.0, true, t);
            worstDown = Math.max(worstDown, prev - d);
            prev = d;
        }
        check(worstDown <= 1, "a 60 -> 30 C step never lowers duty by more than 1 point in "
                + "one second (worst " + worstDown + ")");
        eq(prev, c.dutyAt(CurveConfig.PROFILE_HIGH, 30), "and it does get all the way down");

        // an unknown starting duty must ramp DOWN from the fail-safe, never jump to a
        // lower value the hardware may not actually be at
        FanCurve unknown = new FanCurve();
        unknown.resync(-1);
        int firstUnknown = unknown.step(c, CurveConfig.PROFILE_LOW, 30.0, true, 0);
        eq(firstUnknown, FanIo.FAIL_SAFE_DUTY,
                "resync(-1) starts at the fail-safe duty and slews down from there");

        // a long gap must not be integrated into a huge jump
        f = new FanCurve();
        f.resync(40);
        t = 0;
        f.step(c, CurveConfig.PROFILE_HIGH, 40.0, true, t);
        int d = f.step(c, CurveConfig.PROFILE_HIGH, 60.0, true, t + 3600_000L);
        check(d - 55 <= 25, "an hour-long gap is clamped to 5 s of slew, not integrated "
                + "(got " + d + ")");
    }

    private static void testCatchUpAfterResync() {
        section("catch-up: a duty someone else chose is converged on quickly");
        CurveConfig c = new CurveConfig();

        // Handed the fail-safe 83 with the machine cool: the curve wants the floor. At the
        // comfort slew of 0.12/s that is 53 points and seven minutes of audible fan, which
        // is worse than the change it is trying to hide. The slew limiter exists to mask
        // drift the listener did not cause -- not the consequences of an instruction they
        // just gave.
        FanCurve f = new FanCurve();
        f.resync(FanIo.FAIL_SAFE_DUTY);
        long t = 0;
        int d = FanIo.FAIL_SAFE_DUTY;
        int steps = 0;
        while (steps < 600 && d > c.minDuty) {
            t += 1000;
            d = f.step(c, CurveConfig.PROFILE_HIGH, 40.0, true, t);
            steps++;
        }
        eq(d, c.minDuty, "it does reach the floor from the fail-safe");
        check(steps <= 60, "and gets there in under a minute (" + steps + " s), not seven");

        // Once converged it must be back on the comfort slew, or every later adjustment
        // would be fast too and the whole point is lost.
        int before = d;
        int worst = 0;
        for (int i = 0; i < 30; i++) {
            t += 1000;
            int n = f.step(c, CurveConfig.PROFILE_HIGH, 60.0, true, t);
            worst = Math.max(worst, n - before);
            before = n;
        }
        check(worst <= 1, "after converging, changes are back to 1 point per tick (worst "
                + worst + ")");

        // Catching up must never be slower than the configured slew: a user who sets a
        // brisk rate deliberately should not be throttled by this.
        CurveConfig fast = new CurveConfig();
        fast.slewDownPerSec = 9.0;
        fast.sanitise();
        FanCurve g = new FanCurve();
        g.resync(FanIo.FAIL_SAFE_DUTY);
        long gt = 1000;
        int gd = g.step(fast, CurveConfig.PROFILE_HIGH, 40.0, true, gt);
        gt += 1000;
        gd = g.step(fast, CurveConfig.PROFILE_HIGH, 40.0, true, gt);
        check(FanIo.FAIL_SAFE_DUTY - gd >= 9,
                "a configured slew faster than the catch-up rate still wins (dropped "
                        + (FanIo.FAIL_SAFE_DUTY - gd) + " points)");
    }

    private static void testEngineOffAndTierChange() {
        section("light engine and tier changes");
        CurveConfig c = new CurveConfig();
        FanCurve f = new FanCurve();
        f.resync(70);
        long t = 0;
        f.step(c, CurveConfig.PROFILE_HIGH, 50.0, true, t);
        int d = 0;
        // 900 ticks, not 400. The shipped slew-down is 0.12 points/s -- one point every
        // 8.3 s -- so falling from 70 to idleDuty 10 takes about 500 s. The old default
        // slewed at 1.0/s and got there in a minute. Nothing changed except how long
        // "eventually" is, and the slowness is the point: a fan winding down over minutes
        // is inaudible, and it keeps cooling a machine that has just been switched off.
        for (int i = 0; i < 900; i++) {
            t += 1000;
            d = f.step(c, CurveConfig.PROFILE_HIGH, 50.0, false, t);
        }
        eq(d, c.idleDuty, "with the light engine off the curve settles at the idle duty");

        // engine comes back on: the rise must be immediate, not a 60-second ramp
        t += 1000;
        int back = f.step(c, CurveConfig.PROFILE_HIGH, 50.0, true, t);
        eq(back, c.dutyAt(CurveConfig.PROFILE_HIGH, 50.0),
                "the light engine coming back on jumps straight to the curve value");

        // With the shipped curve a mode change must produce no step at all, because all
        // three profiles are the same column. That is what removes the one audible event
        // the design would otherwise have.
        f = new FanCurve();
        f.resync(c.dutyAt(CurveConfig.PROFILE_LOW, 46.0));
        t = 0;
        int before = f.step(c, CurveConfig.PROFILE_LOW, 46.0, true, t);
        t += 1000;
        int after = f.step(c, CurveConfig.PROFILE_HIGH, 46.0, true, t);
        eq(after, before, "Eco -> Presentation causes NO step with the shipped curve");

        // ...but the immediate-jump mechanism itself must still work, or a user who edits
        // the profiles apart loses the safety behaviour silently. Test it against a
        // config that does differ.
        CurveConfig split = new CurveConfig();
        for (int i = 0; i < CurveConfig.POINTS; i++) {
            split.duty[CurveConfig.PROFILE_LOW][i] = 30;
            split.duty[CurveConfig.PROFILE_HIGH][i] = 60;
        }
        split.sanitise();
        FanCurve g = new FanCurve();
        g.resync(30);
        long gt = 0;
        g.step(split, CurveConfig.PROFILE_LOW, 46.0, true, gt);
        gt += 1000;
        int up = g.step(split, CurveConfig.PROFILE_HIGH, 46.0, true, gt);
        eq(up, split.dutyAt(CurveConfig.PROFILE_HIGH, 46.0),
                "an upward tier change still bypasses the slew limiter when profiles differ");
        gt += 1000;
        int down = g.step(split, CurveConfig.PROFILE_LOW, 46.0, true, gt);
        check(down > split.dutyAt(CurveConfig.PROFILE_LOW, 46.0),
                "a downward tier change does NOT drop immediately, it slews (got " + down + ")");
    }

    private static void testFailSafe() {
        section("fail safe: every bad path goes high");
        CurveConfig c = new CurveConfig();
        FanCurve f = new FanCurve();
        eq(f.step(c, CurveConfig.PROFILE_HIGH, Double.NaN, true, 0),
                FanIo.FAIL_SAFE_DUTY, "NaN temperature -> 83");
        eq(f.step(c, CurveConfig.PROFILE_HIGH, Double.POSITIVE_INFINITY, true, 0),
                FanIo.FAIL_SAFE_DUTY, "infinite temperature -> 83");
        eq(f.step(null, CurveConfig.PROFILE_HIGH, 50.0, true, 0),
                FanIo.FAIL_SAFE_DUTY, "a missing config -> 83");

        // an unknown rgblevel must select the hottest profile, not fall over
        eq(CurveConfig.profileForLevel(-1), CurveConfig.PROFILE_HIGH,
                "an unreadable rgblevel selects Presentation, the hottest column");
        eq(CurveConfig.profileForLevel(9), CurveConfig.PROFILE_HIGH,
                "an out-of-range rgblevel selects Presentation");
        eq(CurveConfig.profileForLevel(4), CurveConfig.PROFILE_LOW,
                "rgblevel 4 (Super Eco) shares the Eco column, as the stock ladder does");
        eq(CurveConfig.profileForLevel(1), CurveConfig.PROFILE_LOW, "rgblevel 1 -> Eco");
        eq(CurveConfig.profileForLevel(2), CurveConfig.PROFILE_NORMAL, "rgblevel 2 -> Normal");
        eq(CurveConfig.profileForLevel(3), CurveConfig.PROFILE_HIGH,
                "rgblevel 3 -> Presentation");

        // a hostile config must not be able to command something unwritable
        CurveConfig bad = new CurveConfig();
        for (int p = 0; p < CurveConfig.PROFILES; p++) {
            for (int i = 0; i < CurveConfig.POINTS; i++) {
                bad.duty[p][i] = i % 2 == 0 ? -1000 : 100000;
            }
        }
        for (int i = 0; i < CurveConfig.POINTS; i++) {
            bad.tempC[i] = 60 - i * 10;         // descending, i.e. nonsense
        }
        bad.hysteresisC = Double.NaN;
        bad.slewUpPerSec = -3;
        bad.slewDownPerSec = 0;
        bad.minDuty = -50;
        bad.maxDuty = 900;
        bad.idleDuty = 0;
        bad.sanitise();
        boolean ok = true;
        for (int t = -100; t <= 200 && ok; t++) {
            for (int p = 0; p < CurveConfig.PROFILES; p++) {
                if (!FanIo.valid(bad.dutyAt(p, t))) {
                    ok = false;
                    break;
                }
            }
        }
        check(ok, "sanitise() makes even a hostile config produce only writable duties");
        boolean ascending = true;
        for (int i = 1; i < CurveConfig.POINTS; i++) {
            if (bad.tempC[i] <= bad.tempC[i - 1]) {
                ascending = false;
            }
        }
        check(ascending, "sanitise() forces the knees back into ascending order");
        check(bad.slewUpPerSec > 0 && bad.slewDownPerSec > 0 && bad.hysteresisC >= 0,
                "sanitise() repairs the response parameters");
    }

    private static void testConfigRoundTrip() {
        section("config: persistence");
        CurveConfig a = new CurveConfig();
        a.duty[CurveConfig.PROFILE_HIGH][1] = 66;
        a.hysteresisC = 2.5;
        a.slewUpPerSec = 3.5;
        a.slewDownPerSec = 0.5;
        a.idleDuty = 12;
        a.minDuty = 15;
        a.maxDuty = 90;
        a.tempC[0] = 39;
        CurveConfig b = CurveConfig.decode(a.encode());
        check(a.encode().equals(b.encode()), "encode/decode round trips");
        eq(b.duty[CurveConfig.PROFILE_HIGH][1], 66, "an edited duty survives");
        eq((int) Math.round(b.slewDownPerSec * 10), 5, "a fractional slew survives");

        check(CurveConfig.decode(null).encode().equals(new CurveConfig().encode()),
                "a missing config falls back to the defaults");
        check(CurveConfig.decode("").encode().equals(new CurveConfig().encode()),
                "an empty config falls back to the defaults");
        check(CurveConfig.decode("v1,not,a,number").encode()
                        .equals(new CurveConfig().encode()),
                "a truncated config falls back to the defaults");
        check(CurveConfig.decode("v9,1,2,3").encode().equals(new CurveConfig().encode()),
                "an unknown version falls back to the defaults");
        String corrupt = a.encode().replace("v1,", "v1,x");
        check(FanIo.valid(CurveConfig.decode(corrupt).dutyAt(CurveConfig.PROFILE_HIGH, 50)),
                "a corrupt config still yields a writable duty");

        // the guard block is appended after the v1 fields and must survive a round trip
        a.socGuardStartC = 66;
        a.socGuardGainPerC = 3.0;
        a.socGuardMaxDuty = 71;
        a.socGuardHystC = 2.0;
        a.socGuardEnabled = false;
        CurveConfig g = CurveConfig.decode(a.encode());
        check(a.encode().equals(g.encode()), "the guard round trips");
        eq(g.socGuardStartC, 66, "an edited guard knee survives");
        eq(g.socGuardMaxDuty, 71, "an edited guard ceiling survives");
        check(!g.socGuardEnabled, "the guard's disabled state survives");

        // a curve saved before the guard existed is short, not corrupt
        CurveConfig fresh = new CurveConfig();
        String[] parts = fresh.encode().split(",");
        StringBuilder legacy = new StringBuilder(parts[0]);
        int v1Fields = CurveConfig.POINTS + CurveConfig.PROFILES * CurveConfig.POINTS + 6;
        for (int i = 1; i <= v1Fields; i++) {
            legacy.append(',').append(parts[i]);
        }
        CurveConfig old1 = CurveConfig.decode(legacy.toString());
        eq(old1.socGuardStartC, fresh.socGuardStartC,
                "a pre-guard curve loads and picks up the default guard");
        eq(old1.duty[CurveConfig.PROFILE_HIGH][3], fresh.duty[CurveConfig.PROFILE_HIGH][3],
                "and its own duties are not disturbed");

        // ---- the presets ----
        // A preset that does not survive the trip through decode/sanitise/encode would
        // have ConfigReceiver answer curve(REPAIRED) for a curve the app itself shipped,
        // and the label would never match the stored line again.
        for (int i = 0; i < CurveConfig.PRESETS.length; i++) {
            String name = CurveConfig.PRESET_NAMES[i];
            String s = CurveConfig.PRESETS[i];
            CurveConfig p = CurveConfig.decode(s);
            check(p.encode().equals(s), name + " round trips byte for byte");
            p.sanitise();
            check(p.encode().equals(s), name + " needs no repair: sanitise() is a no-op");
            eq(CurveConfig.presetOf(s), i, name + " is recognised as preset " + i);
            check(CurveConfig.presetName(i).equals(name), name + " names itself");
        }

        // Quiet is the default curve, not a copy of it. The row and the reset button would
        // otherwise disagree the first time a default moved.
        check(CurveConfig.PRESETS[0].equals(new CurveConfig().encode()),
                "Quiet is byte-identical to what setDefaults() encodes to");

        // Matching the whole line is what makes the label honest: there is no stored index
        // that could go on claiming Balanced after the curve had moved.
        String tweaked = CurveConfig.PRESETS[1].replace(",0.8,", ",0.9,");
        check(!tweaked.equals(CurveConfig.PRESETS[1]), "the mutated line really differs");
        eq(CurveConfig.presetOf(tweaked), CurveConfig.PRESET_CUSTOM,
                "a curve that is none of the presets reads as Custom");
        eq(CurveConfig.presetOf(null), CurveConfig.PRESET_CUSTOM, "and so does no curve");
        check(CurveConfig.presetName(CurveConfig.PRESET_CUSTOM).equals("Custom"),
                "which is the word the row shows");
        check(CurveConfig.preset(-1).encode().equals(CurveConfig.PRESETS[0]),
                "an index from nowhere falls back to Quiet");
        check(CurveConfig.preset(99).encode().equals(CurveConfig.PRESETS[0]),
                "at either end");
    }

    // ----------------------------------------------------------------- soc guard

    private static void testSocGuard() {
        section("soc guard: additive, continuous, and unable to lower the fan");
        CurveConfig c = new CurveConfig();

        // inert everywhere the machine has ever actually been seen
        eq(c.socGuardBoost(64.5), 0, "the hottest SoC yet observed asks for nothing");
        eq(c.socGuardBoost(70.0), 0, "nor does the knee itself");
        eq(c.socGuardBoost(Double.NaN), 0, "an unreadable zone asks for nothing");
        eq(c.socGuardBoost(Double.POSITIVE_INFINITY), 0, "nor does a nonsense one");
        eq(c.socGuardBoost(-40.0), 0, "nor does a wildly cold one");

        // continuous at the knee: no step for the machine to park on, which is the
        // whole failure mode of the stock ladder
        eq(c.socGuardBoost(70.4), 1, "it starts from nothing and rises a point at a time");
        check(c.socGuardBoost(70.0) == 0 && c.socGuardBoost(70.6) <= 2,
                "there is no jump at the knee");

        int prev = -1;
        for (double t = 60.0; t <= 110.0; t += 0.25) {
            int b = c.socGuardBoost(t);
            check(b >= prev, "the boost never sags as the die gets hotter (" + t + ")");
            check(b >= 0, "and is never negative");
            prev = b;
        }

        // disarming it silences it completely
        CurveConfig off = new CurveConfig();
        off.socGuardEnabled = false;
        eq(off.socGuardBoost(95.0), 0, "a disarmed guard asks for nothing at any temperature");

        // --- the property that makes arming it safe ---
        FanCurve plain = new FanCurve();
        FanCurve guarded = new FanCurve();
        long t0 = 0;
        for (double soc = 30.0; soc <= 100.0; soc += 5.0) {
            plain.reset();
            guarded.reset();
            for (double led = 30.0; led <= 70.0; led += 2.0) {
                long now = (t0 += 1000);
                int a1 = plain.step(c, CurveConfig.PROFILE_HIGH, led, Double.NaN, true, now);
                int b1 = guarded.step(c, CurveConfig.PROFILE_HIGH, led, soc, true, now);
                check(b1 >= a1, "the guard can raise the fan and never lower it (LED "
                        + led + ", SoC " + soc + ": " + a1 + " -> " + b1 + ")");
                check(FanIo.valid(b1), "and always asks for a writable duty");
            }
        }

        // the ceiling binds on the result, not on the contribution
        FanCurve cap = new FanCurve();
        int settled = 0;
        for (long t = 1; t <= 4000; t++) {
            settled = cap.step(c, CurveConfig.PROFILE_HIGH, 45.0, 110.0, true, t * 1000L);
        }
        eq(settled, c.socGuardMaxDuty, "a runaway die pins the fan at the guard's ceiling");
        check(c.socGuardMaxDuty < c.maxDuty,
                "which is below the hardware maximum, where the authority has run out");

        // a curve already above the guard's ceiling is left alone -- the curve is the
        // safety case and the guard is not entitled to argue it down
        FanCurve hotLed = new FanCurve();
        int hi = 0;
        for (long t = 1; t <= 4000; t++) {
            hi = hotLed.step(c, CurveConfig.PROFILE_HIGH, 70.0, 110.0, true, t * 1000L);
        }
        check(hi > c.socGuardMaxDuty,
                "a hot LED still commands more than the guard's ceiling (" + hi + ")");

        // it must not fight the light-engine-off idle
        FanCurve idle = new FanCurve();
        eq(idle.step(c, CurveConfig.PROFILE_HIGH, 40.0, 95.0, false, 1000), c.idleDuty,
                "a hot die does not spin the fan up with the engine off");

        // a sensor that stops reporting releases the boost rather than latching it
        FanCurve drop = new FanCurve();
        drop.step(c, CurveConfig.PROFILE_HIGH, 45.0, 95.0, true, 1000);
        check(drop.guardBoost() > 0, "a hot die engages the guard");
        drop.step(c, CurveConfig.PROFILE_HIGH, 45.0, Double.NaN, true, 2000);
        eq(drop.guardBoost(), 0, "and an unreadable zone releases it rather than latching");

        // the guard's input is damped in the same asymmetric way as the curve's
        FanCurve h = new FanCurve();
        h.step(c, CurveConfig.PROFILE_HIGH, 45.0, 80.0, true, 1000);
        int hot = h.guardBoost();
        check(hot > 0, "the guard engages at 80 C");
        h.step(c, CurveConfig.PROFILE_HIGH, 45.0, 79.2, true, 2000);
        eq(h.guardBoost(), hot, "a fall inside the deadband does not move it");
        h.step(c, CurveConfig.PROFILE_HIGH, 45.0, 78.0, true, 3000);
        check(h.guardBoost() < hot, "a fall past it does");
        h.step(c, CurveConfig.PROFILE_HIGH, 45.0, 79.0, true, 4000);
        check(h.guardBoost() > 0, "and a rise is acted on at once");

        // engaging is slew-limited: it is a temperature change like any other, and the
        // whole point of the project is that the listener does not hear those
        FanCurve slew = new FanCurve();
        int before = 0;
        for (long t = 1; t <= 600; t++) {
            before = slew.step(c, CurveConfig.PROFILE_HIGH, 45.0, 60.0, true, t * 1000L);
        }
        int after = slew.step(c, CurveConfig.PROFILE_HIGH, 45.0, 95.0, true, 601000L);
        check(after - before <= 1,
                "the guard cannot step the fan; it slews like everything else (" + before
                        + " -> " + after + ")");

        // repair
        CurveConfig bad = new CurveConfig();
        bad.socGuardStartC = -10;
        bad.socGuardGainPerC = -1;
        bad.socGuardMaxDuty = 99;
        bad.socGuardHystC = -3;
        bad.sanitise();
        check(bad.socGuardStartC >= 0, "a negative guard knee is repaired");
        check(bad.socGuardGainPerC > 0, "a negative gain is restored, not treated as off");
        check(bad.socGuardMaxDuty <= bad.maxDuty, "the guard ceiling cannot exceed the curve's");
        check(bad.socGuardHystC > 0, "a nonsense deadband is replaced");
    }

    // ----------------------------------------------------------------- csv

    private static void testCsvLogger() throws Exception {
        section("csv: mirroring, flushing, and surviving a pulled stick");
        File base = new File(System.getProperty("java.io.tmpdir"),
                "fanlab-csv-" + System.nanoTime());
        File a = new File(base, "internal");
        File b = new File(base, "usb");
        File dead = new File(base, "gone");
        a.mkdirs();
        b.mkdirs();
        dead.mkdirs();
        try {
            CsvLogger log = new CsvLogger("t.csv");
            List<File> dirs = new ArrayList<File>();
            dirs.add(a);
            dirs.add(b);
            log.setDirs(dirs);
            eq(log.append("1,2,3"), 2, "one row reaches both destinations");
            eq(log.append("4,5,6"), 2, "and so does the next");
            eq((int) log.lineCount(), 2, "the row counter counts rows, not writes");

            String ca = read(new File(a, "t.csv"));
            String cb = read(new File(b, "t.csv"));
            check(ca.startsWith(CsvLogger.HEADER + "\n"), "the header is written once");
            check(ca.equals(cb), "both copies are identical");
            check(ca.endsWith("4,5,6\n"), "content is flushed immediately, not buffered");
            eq(countLines(ca), 3, "header plus two rows");

            // reopening must append, not truncate, and must not repeat the header
            log.close();
            CsvLogger log2 = new CsvLogger("t.csv");
            log2.setDirs(dirs);
            log2.append("7,8,9");
            String again = read(new File(a, "t.csv"));
            eq(countLines(again), 4, "reopening appends rather than truncating");
            eq(count(again, CsvLogger.HEADER), 1, "and does not repeat the header");
            log2.close();

            // a destination that disappears must be dropped, not fatal
            CsvLogger log3 = new CsvLogger("t.csv");
            List<File> withDead = new ArrayList<File>();
            withDead.add(a);
            withDead.add(new File(dead, "x y"));   // an impossible path
            log3.setDirs(withDead);
            int reached = log3.append("10,11,12");
            check(reached >= 1, "a broken destination does not stop the good one ("
                    + reached + " reached)");
            check(log3.brokenPaths().size() >= 1, "the broken destination is reported");
            log3.close();

            // an app update that adds columns must not append wide rows under a narrow
            // header -- the old file is rolled aside and a correctly-headed one started
            File c = new File(base, "schema");
            c.mkdirs();
            List<File> one = new ArrayList<File>();
            one.add(c);
            CsvLogger oldSchema = new CsvLogger("t.csv", "epoch_ms,degC,fan_ctrl");
            oldSchema.setDirs(one);
            oldSchema.append("1,2,3");
            oldSchema.close();

            CsvLogger newSchema = new CsvLogger("t.csv");
            newSchema.setDirs(one);
            newSchema.append("1,2,3,4");
            newSchema.close();
            String cur = read(new File(c, "t.csv"));
            check(cur.startsWith(CsvLogger.HEADER + "\n"),
                    "a changed header rolls the file rather than appending under the old one");
            eq(countLines(cur), 2, "the new file holds its header and the new row only");
            File[] kept = c.listFiles();
            eq(kept == null ? 0 : kept.length, 2, "the old data is renamed aside, not deleted");
            for (int i = 0; kept != null && i < kept.length; i++) {
                if (!kept[i].getName().equals("t.csv")) {
                    check(read(kept[i]).startsWith("epoch_ms,degC,fan_ctrl\n"),
                            "and it keeps the header it was actually written under");
                }
            }

            // reopening on the same schema must still append, or every restart would roll
            CsvLogger sameSchema = new CsvLogger("t.csv");
            sameSchema.setDirs(one);
            sameSchema.append("5,6,7,8");
            sameSchema.close();
            eq(countLines(read(new File(c, "t.csv"))), 3,
                    "an unchanged header appends as before");

            check(CsvLogger.HEADER.contains(",soc_pll_c,soc_ddr_c,soc_sar_c"),
                    "the SoC zones are logged");
            check(CsvLogger.HEADER.contains(",thr_cpufreq,thr_cpucore,thr_gpufreq,thr_gpucore"),
                    "and so is what the thermal governor is doing about them");

            // a row must have exactly as many fields as the header promises, or every
            // downstream parser silently reads the wrong column
            Sample blank = new Sample();
            eq(blank.toCsv().split(",", -1).length, CsvLogger.HEADER.split(",", -1).length,
                    "an empty sample still fills every column");
            String[] blankFields = blank.toCsv().split(",", -1);
            for (int i = 0; i < Sysfs.COOLING_NAMES.length; i++) {
                check(blankFields[16 + i].length() == 0,
                        "unread cooling device " + Sysfs.COOLING_NAMES[i]
                                + " is blank, not zero -- 0 means 'not throttling'");
            }
            check(!blank.throttling(), "and an unread device does not read as throttling");

            Sample hot = new Sample();
            hot.throttle = new int[]{2, 0, 1, 0};
            check(hot.throttling(), "any non-zero cooling state is throttling");
            check(hot.throttleNote().equals("cpufreq=2 gpufreq=1"),
                    "and the note names which, got '" + hot.throttleNote() + "'");
            eq(hot.toCsv().split(",", -1).length, CsvLogger.HEADER.split(",", -1).length,
                    "a populated sample fills every column too");

            check(CsvLogger.q("a,b").equals("\"a,b\""), "commas are quoted");
            check(CsvLogger.q("a\"b").equals("\"a\"\"b\""), "quotes are doubled");
            check(CsvLogger.q("plain").equals("plain"), "plain text is untouched");

            testExport(base);
        } finally {
            rmrf(base);
        }
    }

    /**
     * The backlog export: a stick inserted after the fact has to end up with the history,
     * and a stick inserted again tomorrow has to gain the tail rather than a second copy
     * of everything. Pure java.io, so the whole thing runs here.
     */
    private static void testExport(File base) throws Exception {
        section("csv export: additive by watermark, and outside the sink namespace");

        File sink = new File(base, "sink");
        File out = new File(base, "export");
        sink.mkdirs();
        List<File> sinkOnly = new ArrayList<File>();
        sinkOnly.add(sink);

        // a live log, written exactly the way the service writes one
        CsvLogger live = new CsvLogger("fanlab.csv");
        live.setDirs(sinkOnly);
        live.append("1000,a");
        live.append("2000,b");
        live.append("3000,c");

        File src = new File(sink, "fanlab.csv");
        List<File> srcs = new ArrayList<File>();
        srcs.add(src);
        eq((int) CsvLogger.firstEpochMs(src), 1000, "the first row is the file's identity");
        eq((int) CsvLogger.lastEpochMs(src), 3000, "and the last row is the watermark");

        CsvLogger.Export e1 = CsvLogger.exportBacklog(out, srcs);
        eq(e1.filesWritten, 1, "a fresh export writes one file");
        eq((int) e1.rowsCopied, 3, "and copies every row");
        File dst = new File(out, "fanlab-1000.csv");
        check(dst.isFile(), "named after the first row's epoch_ms, not after the source");
        eq(countLines(read(dst)), 4, "header plus three rows");
        check(read(dst).startsWith(CsvLogger.HEADER + "\n"), "the header comes across");

        // the live file keeps growing under the same name, which is exactly why a
        // filename-based dedupe cannot work
        live.append("4000,d");
        live.append("5000,e");
        CsvLogger.Export e2 = CsvLogger.exportBacklog(out, srcs);
        eq(e2.filesWritten, 1, "a grown source lands on the same destination");
        eq((int) e2.rowsCopied, 2, "and only the rows past the watermark are copied");
        eq(countLines(read(dst)), 6, "header plus five rows, each of them once");
        eq(count(read(dst), "3000,c"), 1, "no row is copied twice");
        File[] once = out.listFiles();
        eq(once == null ? 0 : once.length, 1,
                "yesterday's file is added to, not left beside a second copy");

        // nothing new: no read of the destination turns into a write of it
        CsvLogger.Export e3 = CsvLogger.exportBacklog(out, srcs);
        eq(e3.filesWritten, 0, "an unchanged source writes nothing");
        eq((int) e3.rowsCopied, 0, "and copies no rows");
        eq(e3.upToDate, 1, "it reports the destination as already up to date");
        eq(countLines(read(dst)), 6, "the destination is untouched");
        check(!new File(out, "fanlab-1000.csv.part").exists(),
                "and no half-written temporary is left behind");

        // The stick in the field holds 13-column files where this build writes 20. Two
        // schemas in one file is the outcome to refuse.
        File oldSink = new File(base, "old-sink");
        oldSink.mkdirs();
        List<File> oldOnly = new ArrayList<File>();
        oldOnly.add(oldSink);
        CsvLogger old = new CsvLogger("fanlab.csv", "epoch_ms,degC,fan_ctrl");
        old.setDirs(oldOnly);
        old.append("1000,41,30");
        old.close();
        List<File> oldSrc = new ArrayList<File>();
        oldSrc.add(new File(oldSink, "fanlab.csv"));
        CsvLogger.Export e4 = CsvLogger.exportBacklog(out, oldSrc);
        eq(e4.filesWritten, 1, "a differently-headed source still exports");
        File sib = new File(out, "fanlab-1000-v2.csv");
        check(sib.isFile(), "into a sibling, since its first row carries the same epoch");
        check(read(sib).startsWith("epoch_ms,degC,fan_ctrl\n"),
                "the sibling keeps the header it was written under");
        eq(countLines(read(dst)), 6, "and the 20-column file is not appended to");
        eq(count(read(dst), "epoch_ms,degC,fan_ctrl\n"), 0, "no mixed schemas anywhere");

        // A row without its newline is being written right now. append() flushes every
        // complete line, so stopping short of it loses nothing.
        File growing = new File(base, "growing");
        File gout = new File(base, "export-growing");
        write(new File(growing, "fanlab.csv"),
                CsvLogger.HEADER + "\n7000,x\n8000,y\n9000,z");
        List<File> gsrc = new ArrayList<File>();
        gsrc.add(new File(growing, "fanlab.csv"));
        CsvLogger.Export e5 = CsvLogger.exportBacklog(gout, gsrc);
        eq((int) e5.rowsCopied, 2, "the half-written last row is left for next time");
        File gdst = new File(gout, "fanlab-7000.csv");
        check(read(gdst).endsWith("8000,y\n"), "the copy stops one line short");
        eq((int) CsvLogger.lastEpochMs(gdst), 8000, "so the watermark resumes there");
        write(new File(growing, "fanlab.csv"),
                CsvLogger.HEADER + "\n7000,x\n8000,y\n9000,z\n");
        CsvLogger.Export e6 = CsvLogger.exportBacklog(gout, gsrc);
        eq((int) e6.rowsCopied, 1, "and it comes across once the row is finished");
        eq(countLines(read(gdst)), 4, "leaving one header and three rows");

        // The two internal sinks are identical copies that rolled under different
        // timestamps. Keying on the first row collapses them; the watermark makes the
        // second a no-op.
        File collideOut = new File(base, "export-collide");
        String rolled = CsvLogger.HEADER + "\n4100,p\n4200,q\n";
        File r1 = new File(base, "sink-a/fanlab-1111111111111.csv");
        File r2 = new File(base, "sink-b/fanlab-2222222222222.csv");
        write(r1, rolled);
        write(r2, rolled);
        List<File> both = new ArrayList<File>();
        both.add(r1);
        both.add(r2);
        CsvLogger.Export e7 = CsvLogger.exportBacklog(collideOut, both);
        File[] made = collideOut.listFiles();
        eq(made == null ? 0 : made.length, 1,
                "two rolled copies of one log make one destination");
        eq((int) e7.rowsCopied, 2, "whose rows are copied once, not twice");
        eq(e7.upToDate, 1, "the duplicate is recognised as having nothing to add");
        check(new File(collideOut, "fanlab-4100.csv").isFile(),
                "named after the first row, not after either rolled name");

        // ---- the reason the destination is not a sink ----
        // prune() deletes <stem>-*.csv in its target's own directory, so an export named
        // fanlab-<epoch>.csv written into a sink is indistinguishable from a rolled file.
        File hazard = new File(base, "hazard");
        File safe = new File(base, "export-safe");
        hazard.mkdirs();
        safe.mkdirs();
        for (int i = 1; i <= 8; i++) {
            write(new File(hazard, "fanlab-" + i + "000.csv"),
                    CsvLogger.HEADER + "\n" + i + "000,x\n");
            write(new File(safe, "fanlab-" + i + "000.csv"), CsvLogger.HEADER + "\n");
        }
        // an old-schema live file, so opening the sink rolls it and therefore prunes
        write(new File(hazard, "fanlab.csv"), "epoch_ms,degC\n1,2\n");
        CsvLogger pruner = new CsvLogger("fanlab.csv");
        List<File> hazardOnly = new ArrayList<File>();
        hazardOnly.add(hazard);
        pruner.setDirs(hazardOnly);
        pruner.append("1,2,3");
        pruner.close();
        int inSink = 0;
        int inExport = 0;
        for (int i = 1; i <= 8; i++) {
            if (new File(hazard, "fanlab-" + i + "000.csv").exists()) {
                inSink++;
            }
            if (new File(safe, "fanlab-" + i + "000.csv").exists()) {
                inExport++;
            }
        }
        check(inSink < 8, "prune() really does delete fanlab-<n>.csv inside a sink ("
                + inSink + " of 8 left)");
        eq(inExport, 8, "the same names in a directory of their own all survive it");

        CsvLogger.Export e8 = CsvLogger.exportBacklog(safe, srcs);
        eq(e8.filesWritten, 1, "and an export into that directory still works");
        File written = new File(safe, "fanlab-1000.csv");
        check(written.isFile()
                        && written.getName().startsWith("fanlab-")
                        && written.getName().endsWith(".csv"),
                "under exactly the name prune() would have matched in a sink");
        check(!new File(safe, "fanlab.csv").exists(),
                "and never under the live log's own name");
    }

    // ----------------------------------------------------------------- closed loop

    /**
     * A first-order thermal model, calibrated to the two things actually observed on the
     * hardware: with the Presentation floor at 55 the machine cycles (so the steady-state
     * temperature at duty 55 is above the stock ladder's 45.5 C boundary), and with the
     * floor at 69 it is steady (so at 69-70 it is below it).
     *
     * steady(duty) = 53.83 - 0.13333 * duty   =>  steady(55) = 46.5, steady(70) = 44.5
     * dT/dt = (steady(duty) - T) / tau,  tau = 60 s
     */
    private static double steady(int duty) {
        return 53.8333 - 0.133333 * duty;
    }

    private static void testClosedLoopVsStock() {
        section("closed loop: the curve against the stock ladder, same thermal model");
        int seconds = 3600;
        double tau = 60.0;

        // ---- stock: 15 s poll, (int)(T+0.5) ladder, gated on a whole-degree change ----
        double t = 40.0;
        int duty = 55;
        int stockFloor = 55;
        int stockChanges = 0;
        int stockAudible = 0;
        int stockWorst = 0;
        int lastTmp = -999;
        double stockMin = 999;
        double stockMax = -999;
        for (int s = 0; s < seconds; s++) {
            t += (steady(duty) - t) / tau;
            if (s % 15 == 0) {
                int tmp = (int) (t + 0.5);
                if (tmp != lastTmp) {
                    int want = FanCurve.stockLadder(CurveConfig.PROFILE_HIGH, tmp, stockFloor);
                    if (want > 0 && want != duty) {
                        int delta = Math.abs(want - duty);
                        stockChanges++;
                        if (delta >= 5) {
                            stockAudible++;
                        }
                        stockWorst = Math.max(stockWorst, delta);
                        duty = want;
                    }
                    lastTmp = tmp;
                }
            }
            if (s > 600) {
                stockMin = Math.min(stockMin, t);
                stockMax = Math.max(stockMax, t);
            }
        }

        // ---- the curve: 1 Hz, hysteresis, slew ----
        CurveConfig c = new CurveConfig();
        FanCurve f = new FanCurve();
        f.resync(55);
        double t2 = 40.0;
        int duty2 = 55;
        int curveChanges = 0;
        int curveAudible = 0;
        int curveWorst = 0;
        double curveMin = 999;
        double curveMax = -999;
        long ms = 0;
        for (int s = 0; s < seconds; s++) {
            t2 += (steady(duty2) - t2) / tau;
            int want = f.step(c, CurveConfig.PROFILE_HIGH, t2 + 0.5, true, ms);
            ms += 1000;
            if (want != duty2) {
                int delta = Math.abs(want - duty2);
                // The first minute is the controller converging on a duty it was handed
                // rather than chose (resync(55) above), which is deliberately faster than
                // the comfort slew. The invariant being measured here is about RUNNING --
                // that ordinary operation never produces an audible step -- so the
                // convergence is excluded rather than allowed to define the worst case.
                if (s >= 60) {
                    curveChanges++;
                    if (delta >= 5) {
                        curveAudible++;
                    }
                    curveWorst = Math.max(curveWorst, delta);
                }
                duty2 = want;
            }
            if (s > 600) {
                curveMin = Math.min(curveMin, t2);
                curveMax = Math.max(curveMax, t2);
            }
        }

        System.out.println("  one simulated hour, Presentation, floor 55:");
        System.out.println("    stock ladder : " + stockChanges + " duty changes, "
                + stockAudible + " of them >= 5 points, worst jump " + stockWorst
                + " points, settled band " + round2(stockMin) + ".." + round2(stockMax)
                + " C, final duty " + duty);
        System.out.println("    fanlab curve : " + curveChanges + " duty changes, "
                + curveAudible + " of them >= 5 points, worst jump " + curveWorst
                + " points, settled band " + round2(curveMin) + ".." + round2(curveMax)
                + " C, final duty " + duty2);

        check(stockAudible > 0,
                "the model reproduces the reported problem: the stock ladder makes "
                        + stockAudible + " jumps of 5 points or more");
        eq(curveWorst, 1, "the curve never changes duty by more than 1 point at a time");
        eq(curveAudible, 0, "the curve makes no audible step at all");
        check(curveMax - curveMin < stockMax - stockMin,
                "the curve holds a tighter temperature band than the stock ladder ("
                        + round2(curveMax - curveMin) + " C vs "
                        + round2(stockMax - stockMin) + " C)");
        // The trade, asserted rather than assumed. This used to demand the curve never run
        // hotter than stock; that was the conservative position before the plant was
        // measured, and giving it up IS the feature. What replaces it is a bound: hotter,
        // yes, but never past the ceiling, and much quieter for it.
        check(curveMax > stockMax,
                "the curve deliberately runs hotter than stock (" + round2(curveMax)
                        + " vs " + round2(stockMax) + " C) -- that is the trade");
        check(curveMax <= 55.0,
                "but it stays under the 55 C ceiling, the temperature at which the stock "
                        + "ladder itself demands maximum fan (peak " + round2(curveMax) + " C)");
        check(duty2 < duty - 10,
                "and it buys a lot of quiet for it: settles at duty " + duty2
                        + " where stock sits at " + duty);
    }

    // ================================================================== AUTO / VERIFY
    //
    // There is no device and no emulator, so the only way any of this gets exercised is
    // by driving the pure state machines through a thermal model on the host. A whole
    // ninety-minute sweep runs here in a fraction of a second, which means every abort
    // path can be tested, which is the part that actually matters.

    /** First-order thermal model, calibrated the same way as the closed-loop test above. */
    private static double steadyFor(int duty, int rgblevel) {
        double base;
        switch (rgblevel) {
            case 3:
                base = 53.8333;     // Presentation: the hot one, matches SS 8.8
                break;
            case 2:
                base = 49.0;        // Normal
                break;
            default:
                base = 44.0;        // Eco and Super Eco
                break;
        }
        return base - 0.133333 * duty;
    }

    // ----------------------------------------------------------------- the fit

    private static void testExpFit() {
        section("exponential fit: recovering T_inf and tau from a first-order transient");

        double[][] cases = {
                //  T_inf   tau     T_0    seconds of data
                {45.0, 120.0, 38.0, 600},
                {50.0, 60.0, 41.0, 400},
                {36.5, 240.0, 44.0, 900},
                {43.0, 30.0, 42.0, 200},
        };
        for (int k = 0; k < cases.length; k++) {
            double tinf = cases[k][0];
            double tau = cases[k][1];
            double t0 = cases[k][2];
            int n = (int) cases[k][3];
            double[] t = new double[n];
            double[] y = new double[n];
            for (int i = 0; i < n; i++) {
                t[i] = i;
                y[i] = tinf + (t0 - tinf) * Math.exp(-i / tau);
            }
            ExpFit.Fit f = ExpFit.fit(t, y, n);
            check(f.ok, "clean transient (T_inf=" + tinf + ", tau=" + tau + ") fits");
            eq(f.tInf, tinf, 0.02, "  recovers T_inf");
            eq(f.tau, tau, tau * 0.03, "  recovers tau");
            eq(f.t0, t0, 0.05, "  recovers T_0");
            check(f.rms < 0.01, "  residual is far below the "
                    + SweepPlan.SETTLE_MAX_RMS_C + " C settle threshold (" + f.rms + ")");
            check(f.tauIdentifiable, "  and tau is reported as identifiable");
            check(!f.tauPinned, "  tau did not land on a search bound");
        }

        // The whole point of the fit: call a step long before it has settled.
        double tinf = 45.0;
        double tau = 120.0;
        int n = 180;                      // 1.5 time constants, the minimum dwell
        double[] t = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i;
            y[i] = tinf + (38.0 - tinf) * Math.exp(-i / tau);
        }
        ExpFit.Fit early = ExpFit.fit(t, y, n);
        System.out.println("  after only " + n + " s of a " + (int) tau + " s tau: T_inf = "
                + round2(early.tInf) + " C (true " + tinf + "), tau = "
                + round2(early.tau) + " s, still " + round2(y[n - 1]) + " C on the sensor");
        eq(early.tInf, tinf, 0.05,
                "extrapolates T_inf from 1.5 time constants of data - this is what makes a "
                        + "90 minute sweep possible instead of a 3 hour one");
        check(y[n - 1] < tinf - 1.0,
                "  and the raw sensor is still " + round2(tinf - y[n - 1])
                        + " C short of it at that moment");

        // Noise. A deterministic pseudo-random walk so the test cannot flake.
        long seed = 12345L;
        double[] yn = new double[600];
        double[] tn = new double[600];
        for (int i = 0; i < 600; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double noise = ((seed >>> 40) / (double) (1 << 24) - 0.5) * 0.10;
            tn[i] = i;
            yn[i] = 45.0 + (38.0 - 45.0) * Math.exp(-i / 120.0) + noise;
        }
        ExpFit.Fit noisy = ExpFit.fit(tn, yn, 600);
        check(noisy.ok, "fits through +/- 0.05 C of sensor noise");
        eq(noisy.tInf, 45.0, 0.05, "  T_inf survives the noise");
        eq(noisy.tau, 120.0, 12.0, "  tau survives the noise (within 10 %)");
        check(noisy.rms > 0.0 && noisy.rms < 0.06,
                "  and the reported residual reflects the noise (" + round2(noisy.rms) + ")");

        // Flat: T_inf is the mean and is fine, tau is not identifiable and must not be
        // reported as though it had been measured.
        double[] flat = new double[300];
        double[] ft = new double[300];
        for (int i = 0; i < 300; i++) {
            ft[i] = i;
            flat[i] = 44.0;
        }
        ExpFit.Fit level = ExpFit.fit(ft, flat, 300);
        check(level.ok, "a flat trace still yields a usable fit");
        eq(level.tInf, 44.0, 0.001, "  T_inf is the mean");
        check(!level.tauIdentifiable, "  but tau is flagged as not identifiable");
        check(Double.isNaN(level.tau), "  and tau is reported as NaN, not as a number");

        // Refusals, none of which may throw.
        check(!ExpFit.fit(null, null, 10).ok, "null input is refused");
        check(!ExpFit.fit(new double[]{0, 1}, new double[]{1, 2}, 2).ok,
                "two samples is refused (needs " + ExpFit.MIN_SAMPLES + ")");
        double[] bad = new double[20];
        double[] bt = new double[20];
        for (int i = 0; i < 20; i++) {
            bt[i] = i;
            bad[i] = 40.0;
        }
        bad[7] = Double.NaN;
        check(!ExpFit.fit(bt, bad, 20).ok, "a NaN sample is refused rather than propagated");
        bad[7] = Double.POSITIVE_INFINITY;
        check(!ExpFit.fit(bt, bad, 20).ok, "an infinite sample is refused");
        double[] same = new double[20];
        for (int i = 0; i < 20; i++) {
            same[i] = 5.0;
        }
        check(!ExpFit.fit(same, bad, 20).ok, "all-one-timestamp input is refused");
    }

    // ----------------------------------------------------------------- the plan

    private static void testSweepPlanConstants() {
        section("sweep plan: the schedule and the limits that are not negotiable");
        eq(SweepPlan.CEILING_C, 58.0, 0.0001,
                "the hard ceiling is 58 C (revised down from 62 on review)");
        eq(SweepPlan.DUTY_FLOOR, 35, "the duty floor is 35, above the FANSTOP risk");
        eq(FanIo.FAIL_SAFE_DUTY, 83, "fail safe is still 83");
        eq(SweepPlan.RATE_GUARD_C, 0.5, 0.0001, "rate guard is 0.5 C per 10 s");
        eq((int) SweepPlan.RATE_WINDOW_MS, 10000, "  over a 10 s window");
        eq(SweepPlan.MODE_ORDER.length, 4, "all four brightness modes are swept");
        eq(SweepPlan.MODE_ORDER[0], 3, "Presentation first - the problem mode, coldest start");
        eq(SweepPlan.MODE_ORDER[1], 2, "then Normal");
        eq(SweepPlan.MODE_ORDER[2], 1, "then Eco");
        eq(SweepPlan.MODE_ORDER[3], 4, "then Super Eco, to confirm it matches Eco on hardware");
        check(SweepPlan.isFullSchedule(3) && SweepPlan.isFullSchedule(2),
                "Presentation and Normal get the full schedule");
        check(!SweepPlan.isFullSchedule(1) && !SweepPlan.isFullSchedule(4),
                "Eco and Super Eco get the short one");
        eq(SweepPlan.FULL[0], 83, "the full schedule starts at 83 - cool and safe");
        for (int i = 1; i < SweepPlan.FULL.length; i++) {
            check(SweepPlan.FULL[i] < SweepPlan.FULL[i - 1],
                    "the full schedule descends at index " + i);
        }
        for (int i = 1; i < SweepPlan.SHORT.length; i++) {
            check(SweepPlan.SHORT[i] < SweepPlan.SHORT[i - 1],
                    "the short schedule descends at index " + i);
        }
        for (int i = 0; i < SweepPlan.FULL.length; i++) {
            check(SweepPlan.FULL[i] >= SweepPlan.DUTY_FLOOR
                            && FanIo.valid(SweepPlan.FULL[i]),
                    "full schedule entry " + SweepPlan.FULL[i] + " is writable and above the floor");
        }
        for (int i = 0; i < SweepPlan.SHORT.length; i++) {
            check(SweepPlan.SHORT[i] >= SweepPlan.DUTY_FLOOR
                            && FanIo.valid(SweepPlan.SHORT[i]),
                    "short schedule entry " + SweepPlan.SHORT[i] + " is writable and above the floor");
        }
        eq(SweepPlan.totalSteps(), 28, "28 duty steps in the whole run");
        long best = SweepPlan.estimateBestSeconds();
        long typical = SweepPlan.estimateTypicalSeconds();
        System.out.println("  best case " + (best / 60) + " min, typical "
                + (typical / 60) + " min, hard cap " + (SweepPlan.capSeconds() / 60) + " min");
        check(best >= 80 * 60 && best <= 100 * 60,
                "the confirmation screen's \"about 90 minutes\" is what the schedule "
                        + "actually implies (" + (best / 60) + " min)");
        check(typical > best && typical < SweepPlan.capSeconds(),
                "and the slower figure it also quotes sits between the two ("
                        + (typical / 60) + " min)");
        check(SweepPlan.capSeconds() * 1000L == SweepPlan.RUN_CAP_MS,
                "the cap is stated in the same units it is enforced in");
        check(!SweepPlan.validRgbLevel(0) && !SweepPlan.validRgbLevel(5)
                        && SweepPlan.validRgbLevel(1) && SweepPlan.validRgbLevel(4),
                "rgblevel writes are clamped to 1..4, nothing else being exercised by stock");
    }

    // ----------------------------------------------------------------- the sweep

    /** Everything one simulated run produced, so the assertions can be read off it. */
    private static final class RunResult {
        SweepEngine engine;
        int ticks;
        int minDuty = 999;
        int maxDuty = -999;
        double maxSeen = -999;
        long elapsedSec;
        boolean commandedBelowFloor;
        boolean commandedUnwritable;
    }

    /**
     * Drive the engine through the model.
     *
     * @param mode  0 = ordinary model, 1 = runaway (climbs regardless of duty),
     *              2 = a fast climb once the first real step starts,
     *              3 = never settles (a slow drift on top of the transient).
     */
    private static RunResult runSweep(int mode, double tau, int maxTicks, int abortAtTick) {
        RunResult r = new RunResult();
        SweepEngine e = new SweepEngine(0L, 1000000L);
        r.engine = e;
        double t = 30.0;
        int duty = FanIo.FAIL_SAFE_DUTY;
        int rgb = 3;
        long ms = 0L;
        long stepMs = 1000L;
        while (!e.finished() && r.ticks < maxTicks) {
            if (abortAtTick >= 0 && r.ticks == abortAtTick) {
                e.abort("user", ms, 1000000L + ms);
            }
            // Mode 3 puts a slow wobble on the sensor, so no fit ever settles and every
            // step runs to its dwell cap. That is what exercises the overall time cap.
            double reported = t + 0.5
                    + (mode == 3 ? 0.5 * Math.sin(2 * Math.PI * r.ticks / 300.0) : 0.0);
            SweepEngine.Tick tk = e.tick(ms, 1000000L + ms, reported);
            duty = tk.duty;
            if (tk.rgblevel > 0) {
                rgb = tk.rgblevel;
            }
            if (duty < SweepPlan.DUTY_FLOOR) {
                r.commandedBelowFloor = true;
            }
            if (!FanIo.valid(duty)) {
                r.commandedUnwritable = true;
            }
            r.minDuty = Math.min(r.minDuty, duty);
            r.maxDuty = Math.max(r.maxDuty, duty);
            r.maxSeen = Math.max(r.maxSeen, reported);

            if (mode == 1) {
                t += 0.06;                                    // runaway: 0.6 C / 10 s
            } else if (mode == 2 && e.phase() == SweepEngine.PHASE_STEP
                    && e.commandedDuty() < FanIo.FAIL_SAFE_DUTY) {
                t += 0.09;                                    // 0.9 C / 10 s: trips the guard
            } else {
                t += (steadyFor(duty, rgb) - t) / tau;
            }
            ms += stepMs;
            r.ticks++;
        }
        r.elapsedSec = ms / 1000L;
        return r;
    }

    private static void testSweepHappyPath() {
        section("AUTO: a whole unattended run, driven through the thermal model");
        RunResult r = runSweep(0, 60.0, 3 * 3600, -1);
        SweepEngine e = r.engine;
        System.out.println("  finished after " + (r.elapsedSec / 60) + " min "
                + (r.elapsedSec % 60) + " s, end reason \"" + e.endReason() + "\", "
                + e.steps().size() + " records, duty range " + r.minDuty + ".."
                + r.maxDuty + ", hottest sample " + round2(r.maxSeen) + " C");
        check(e.finished(), "the run finishes by itself");
        check("complete".equals(e.endReason()),
                "and it finishes complete, not aborted (was \"" + e.endReason() + "\")");
        check(!r.commandedBelowFloor, "it never commands below the duty floor of "
                + SweepPlan.DUTY_FLOOR);
        check(!r.commandedUnwritable, "every duty it commands is writable (1..100)");
        check(r.maxSeen < SweepPlan.CEILING_C,
                "the ceiling was never reached in this scenario ("
                        + round2(r.maxSeen) + " C)");
        check(r.elapsedSec < SweepPlan.capSeconds(), "it finished inside the run cap");

        // Every scheduled condition was actually held.
        Set<String> held = new HashSet<String>();
        int fits = 0;
        int settled = 0;
        for (int i = 0; i < e.steps().size(); i++) {
            SweepStep s = e.steps().get(i);
            if (SweepStep.PHASE_STEP.equals(s.phase)) {
                held.add(s.rgblevel + "@" + s.commandedDuty);
            }
            if (s.fit != null && s.fit.ok) {
                fits++;
            }
            if (SweepStep.END_SETTLED.equals(s.endReason)) {
                settled++;
            }
        }
        int want = 0;
        boolean allHeld = true;
        for (int m = 0; m < SweepPlan.MODE_ORDER.length; m++) {
            int lvl = SweepPlan.MODE_ORDER[m];
            int[] sch = SweepPlan.scheduleFor(lvl);
            for (int i = 0; i < sch.length; i++) {
                want++;
                if (!held.contains(lvl + "@" + sch[i])) {
                    allHeld = false;
                }
            }
        }
        check(allHeld, "all " + want + " scheduled (mode, duty) conditions were held");
        eq(held.size(), 28, "and no duplicates or extras");
        eq(e.steps().size(), 32, "32 records: 28 duty steps plus 4 mode baselines");
        check(fits == e.steps().size(),
                "every record carries a fit (" + fits + "/" + e.steps().size() + ")");
        check(settled >= 28,
                "at least the 28 duty steps ended early on the fit rather than timing out ("
                        + settled + " settled)");

        // The baselines at 83 are themselves T_eq(83, mode) for each mode - the reference
        // every other point is read against.
        int baselines = 0;
        for (int i = 0; i < e.steps().size(); i++) {
            SweepStep s = e.steps().get(i);
            if (!SweepStep.PHASE_BASELINE.equals(s.phase)) {
                continue;
            }
            baselines++;
            eq(s.commandedDuty, FanIo.FAIL_SAFE_DUTY,
                    "baseline for " + s.modeName() + " is held at 83");
            check(s.fit.ok && !Double.isNaN(s.fit.tInf),
                    "  and yields T_eq(83, " + s.modeName() + ") = "
                            + round2(s.fit.tInf) + " C");
        }
        eq(baselines, 4, "one baseline per brightness mode");

        // T_eq must fall as duty rises. That relation is the whole deliverable.
        double prevTinf = -999;
        int prevDuty = 999;
        boolean monotone = true;
        for (int i = 0; i < e.steps().size(); i++) {
            SweepStep s = e.steps().get(i);
            if (!SweepStep.PHASE_STEP.equals(s.phase) || s.rgblevel != 3) {
                continue;
            }
            if (prevTinf > -900 && s.commandedDuty < prevDuty && s.fit.tInf < prevTinf) {
                monotone = false;
            }
            prevTinf = s.fit.tInf;
            prevDuty = s.commandedDuty;
        }
        check(monotone, "in Presentation, the fitted T_eq rises as the duty falls");

        // The audibility marker.
        SweepEngine e2 = new SweepEngine(0L, 5000L);
        e2.tick(1000L, 6000L, 42.0);
        SweepEngine.Marker mk = e2.mark("audible", 6000L);
        eq(mk.duty, FanIo.FAIL_SAFE_DUTY, "a marker records the duty at that instant");
        eq(mk.rgblevel, 3, "  and the brightness mode");
        eq(mk.degC, 42.0, 0.001, "  and the temperature");
        eq(e2.markers().size(), 1, "  and it lands in the marker list");
        eq(r.engine.markers().size(), 0,
                "a sweep with no keypresses is still a valid sweep (no markers required)");
    }

    private static void testSweepCeilingAbort() {
        section("AUTO abort path: the 58 C hard ceiling");
        RunResult r = runSweep(1, 60.0, 3 * 3600, -1);
        SweepEngine e = r.engine;
        System.out.println("  aborted at " + round2(r.maxSeen) + " C after "
                + r.elapsedSec + " s, end reason \"" + e.endReason() + "\"");
        check(e.finished(), "a runaway aborts the run");
        check("abort:ceiling".equals(e.endReason()),
                "and it says so: \"" + e.endReason() + "\"");
        check(r.maxSeen >= SweepPlan.CEILING_C && r.maxSeen < SweepPlan.CEILING_C + 0.2,
                "it fires on the first sample at or above 58 C, not later ("
                        + round2(r.maxSeen) + ")");
        SweepEngine.Tick after = e.tick(r.elapsedSec * 1000L + 1000L, 0L, 59.0);
        eq(after.duty, FanIo.FAIL_SAFE_DUTY, "every tick after the abort commands 83");
        check(after.finished, "and reports the run as over");
        check(e.steps().size() > 0 && SweepStep.END_ABORT.equals(
                        e.steps().get(e.steps().size() - 1).endReason),
                "the step that was in progress is closed and marked as aborted");
    }

    private static void testSweepRateGuard() {
        section("AUTO abort path: the rate guard, and what it does with the rest of the mode");
        RunResult r = runSweep(2, 60.0, 3 * 3600, -1);
        SweepEngine e = r.engine;
        int guards = 0;
        int guardHolds = 0;
        for (int i = 0; i < e.steps().size(); i++) {
            SweepStep s = e.steps().get(i);
            if (SweepStep.END_RATE_GUARD.equals(s.endReason)) {
                guards++;
            }
            if (SweepStep.PHASE_GUARD.equals(s.phase)) {
                guardHolds++;
                check(s.commandedDuty > SweepPlan.DUTY_FLOOR,
                        "the backed-off hold is above the floor (" + s.commandedDuty + ")");
            }
        }
        System.out.println("  " + guards + " rate-guard trips, " + guardHolds
                + " backed-off holds, end reason \"" + e.endReason() + "\"");
        check(guards > 0, "a 0.9 C per 10 s climb trips the rate guard");
        eq(guardHolds, guards, "every trip is followed by a recorded backed-off hold");
        check(!r.commandedBelowFloor, "and it still never commands below the duty floor");

        // A trip abandons the rest of that mode's descent: everything below the duty that
        // tripped is strictly worse, so there is nothing down there worth holding.
        for (int i = 0; i < e.steps().size(); i++) {
            SweepStep s = e.steps().get(i);
            if (!SweepStep.END_RATE_GUARD.equals(s.endReason)) {
                continue;
            }
            boolean sameModeLater = false;
            for (int j = i + 1; j < e.steps().size(); j++) {
                SweepStep n = e.steps().get(j);
                if (n.rgblevel == s.rgblevel && SweepStep.PHASE_STEP.equals(n.phase)) {
                    sameModeLater = true;
                }
            }
            check(!sameModeLater,
                    "after a trip in " + s.modeName() + " no lower duty is held in that mode");
        }
        boolean guardEvent = false;
        for (int i = 0; i < e.events().size(); i++) {
            if (e.events().get(i).startsWith("rate_guard")) {
                guardEvent = true;
            }
        }
        check(guardEvent, "the trip is written to the event log with the duty and temperature");
    }

    private static void testSweepBadReads() {
        section("AUTO abort path: unusable temperature readings");
        SweepEngine e = new SweepEngine(0L, 0L);
        SweepEngine.Tick t1 = e.tick(1000L, 1000L, 41.0);
        eq(t1.duty, FanIo.FAIL_SAFE_DUTY, "the run opens at 83 in the baseline hold");
        SweepEngine.Tick a = e.tick(2000L, 2000L, Double.NaN);
        check(!a.finished, "one bad read does not abort - a dropped sample at 1 Hz is ordinary");
        eq(a.duty, FanIo.FAIL_SAFE_DUTY, "  and it holds what it was holding");
        SweepEngine.Tick b = e.tick(3000L, 3000L, Double.NaN);
        check(!b.finished, "two bad reads still do not abort");
        SweepEngine.Tick c = e.tick(4000L, 4000L, Double.NaN);
        check(c.finished, "three consecutive bad reads abort the run");
        eq(c.duty, FanIo.FAIL_SAFE_DUTY, "  at the fail-safe duty");
        check("abort:bad_temperature".equals(e.endReason()),
                "  and the reason is recorded (\"" + e.endReason() + "\")");

        // An implausible number is treated exactly like no reading at all.
        SweepEngine e2 = new SweepEngine(0L, 0L);
        e2.tick(1000L, 0L, 41.0);
        e2.tick(2000L, 0L, 500.0);
        e2.tick(3000L, 0L, -200.0);
        SweepEngine.Tick z = e2.tick(4000L, 0L, Double.NEGATIVE_INFINITY);
        check(z.finished, "500 C, -200 C and -infinity all count as unusable readings");
        eq(z.duty, FanIo.FAIL_SAFE_DUTY, "  and the fan ends at 83");

        // A good reading in between clears the counter.
        SweepEngine e3 = new SweepEngine(0L, 0L);
        e3.tick(1000L, 0L, 41.0);
        e3.tick(2000L, 0L, Double.NaN);
        e3.tick(3000L, 0L, Double.NaN);
        e3.tick(4000L, 0L, 41.2);
        e3.tick(5000L, 0L, Double.NaN);
        SweepEngine.Tick ok = e3.tick(6000L, 0L, Double.NaN);
        check(!ok.finished, "a good reading in between resets the bad-read counter");
    }

    private static void testSweepUserAbort() {
        section("AUTO abort path: the user pressing stop");
        RunResult r = runSweep(0, 60.0, 3 * 3600, 900);
        SweepEngine e = r.engine;
        check(e.finished(), "abort() ends the run immediately");
        check("abort:user".equals(e.endReason()),
                "and records who ended it (\"" + e.endReason() + "\")");
        check(r.elapsedSec <= 902, "it stopped on the tick it was asked to, not later ("
                + r.elapsedSec + " s)");
        SweepEngine.Tick after = e.tick(2000000L, 0L, 44.0);
        eq(after.duty, FanIo.FAIL_SAFE_DUTY, "every later tick commands 83");
        check(e.steps().size() > 0, "the partial run still produced records to write out");
        SweepStep last = e.steps().get(e.steps().size() - 1);
        check(SweepStep.END_ABORT.equals(last.endReason),
                "the step in progress is closed as aborted, not left open");
        // Aborting twice must be harmless.
        e.abort("again", 3000000L, 0L);
        check("abort:user".equals(e.endReason()), "a second abort does not overwrite the first");
    }

    private static void testSweepTimeCap() {
        section("AUTO abort path: the overall time cap");
        RunResult r = runSweep(3, 90.0, 4 * 3600, -1);
        SweepEngine e = r.engine;
        System.out.println("  ran " + (r.elapsedSec / 60) + " min, end reason \""
                + e.endReason() + "\", " + e.steps().size() + " records");
        check(e.finished(), "a run whose steps never settle still ends by itself");
        check("time_cap".equals(e.endReason()) || "complete".equals(e.endReason()),
                "and it ends on the cap or on completion, never open-ended (\""
                        + e.endReason() + "\")");
        check(r.elapsedSec <= SweepPlan.capSeconds() + 5,
                "it never runs past the stated cap of " + (SweepPlan.capSeconds() / 60)
                        + " minutes (" + (r.elapsedSec / 60) + " min)");
        check(e.steps().size() > 0, "and everything measured before the cap is kept");
    }

    // ----------------------------------------------------------------- VERIFY

    private static void testHoldSession() {
        section("VERIFY: holding one duty, and logging what a person sees");
        HoldSession h = new HoldSession(62, 3, 0L, 1000L);
        eq(h.duty(), 62, "it holds the duty it was given");
        eq(h.rgblevel(), 3, "in the mode it was given");
        check("Presentation".equals(h.modeName()), "named for the UI");

        eq(new HoldSession(1, 3, 0L, 0L).duty(), SweepPlan.DUTY_FLOOR,
                "a duty below the floor is raised to it, never accepted");
        eq(new HoldSession(100, 3, 0L, 0L).duty(), FanIo.FAIL_SAFE_DUTY,
                "a duty above the stock maximum is lowered to 83");
        eq(new HoldSession(60, 0, 0L, 0L).rgblevel(), 1, "rgblevel 0 is clamped up to 1");
        eq(new HoldSession(60, 9, 0L, 0L).rgblevel(), 4, "rgblevel 9 is clamped down to 4");

        eq(h.tick(1000L, 44.0), 62, "an ordinary reading holds the duty");
        eq(h.tick(2000L, 45.0), 62, "and keeps holding it");
        eq(h.minC(), 44.0, 0.001, "it tracks the range seen: min");
        eq(h.maxC(), 45.0, 0.001, "  and max");

        HoldSession.Check c = h.check(HoldSession.ABOUT_PATTERN, HoldSession.VERDICT_SOFT,
                3000L, 4000L);
        eq(c.degC, 45.0, 0.001, "a check records the temperature at that instant");
        eq(c.duty, 62, "  the duty");
        eq((int) c.elapsedSec, 3, "  and how long it had been held");
        eq(h.checks().size(), 1, "  and it lands in the log");
        h.setPattern("checkerboard-1px");
        HoldSession.Check c2 = h.check(HoldSession.ABOUT_PATTERN, HoldSession.VERDICT_SHARP,
                4000L, 5000L);
        check("checkerboard-1px".equals(c2.pattern),
                "and it records which pattern was on screen when the verdict was given");
        h.check(HoldSession.ABOUT_FAN, HoldSession.VERDICT_AUDIBLE, 5000L, 6000L);
        eq(h.checks().size(), 3, "the audibility verdict is logged the same way");

        // The ceiling.
        eq(h.tick(6000L, SweepPlan.CEILING_C), FanIo.FAIL_SAFE_DUTY,
                "58 C stops it and commands 83");
        check(h.finished(), "  the session is over");
        check("ceiling".equals(h.endReason()), "  and says why (\"" + h.endReason() + "\")");
        eq(h.tick(7000L, 40.0), FanIo.FAIL_SAFE_DUTY,
                "every tick after that still commands 83, even once it has cooled");

        // Bad reads.
        HoldSession h2 = new HoldSession(55, 2, 0L, 0L);
        h2.tick(1000L, 43.0);
        eq(h2.tick(2000L, Double.NaN), 55, "one unusable reading holds the duty");
        eq(h2.tick(3000L, Double.NaN), 55, "two, likewise");
        eq(h2.tick(4000L, Double.NaN), FanIo.FAIL_SAFE_DUTY, "three abort at 83");
        check(h2.finished() && "bad_temperature".equals(h2.endReason()),
                "  and record the reason");

        // Stopping is idempotent.
        HoldSession h3 = new HoldSession(50, 1, 0L, 0L);
        h3.stop("user");
        h3.stop("something else");
        check("user".equals(h3.endReason()), "a second stop does not overwrite the first");
        eq(h3.tick(1000L, 40.0), FanIo.FAIL_SAFE_DUTY, "and a stopped session commands 83");
    }

    // ----------------------------------------------------------------- picoreg

    private static void testPicoReg() throws Exception {
        section("DLPC system temperature (D6h) - and the honest reporting of its absence");

        check("r d6 2".equals(PicoReg.readCommand(PicoReg.OPCODE_SYSTEM_TEMPERATURE,
                        PicoReg.SYSTEM_TEMPERATURE_LEN)),
                "the read command is exactly \"r d6 2\", the format research/05 SS A4 proves");
        check("w 52 1 7".equals(PicoReg.writeCommand(0x52, new int[]{7})),
                "and a write matches the firmware's own \"w 52 1 7\"");

        // Decoding, per DLPU078 SS 3.5.7: 2 bytes, bits 15:12 zero, bit 11 sign.
        eq(PicoReg.decodeSystemTemperature(new int[]{0x2B, 0x00}), 43.0, 0.001,
                "0x002b decodes as 43 C");
        eq(PicoReg.decodeSystemTemperature(new int[]{0x00, 0x00}), 0.0, 0.001,
                "0x0000 decodes as 0 C");
        eq(PicoReg.decodeSystemTemperature(new int[]{0x0A, 0x08}), -10.0, 0.001,
                "bit 11 is the sign: 0x080a is -10 C");
        check(Double.isNaN(PicoReg.decodeSystemTemperatureWord(0x1234)),
                "a non-zero top nibble is refused - that is not a D6h response");
        check(Double.isNaN(PicoReg.decodeSystemTemperature(new int[]{0xFF, 0x03})),
                "1023 C is refused as physically impossible, not reported as a temperature");
        check(Double.isNaN(PicoReg.decodeSystemTemperature(null)),
                "no bytes decodes to NaN, never to zero");
        check(Double.isNaN(PicoReg.decodeSystemTemperature(new int[]{0x2B})),
                "one byte is not enough and is refused");
        eq(PicoReg.word(new int[]{0x2B, 0x00}), 0x2B,
                "the raw word is little endian, and is always kept");

        // Parsing whatever shape the driver's log line turns out to have.
        int[] p = PicoReg.parseHexBytes(" 0x2b 0x00", 2);
        check(p != null && p[0] == 0x2b && p[1] == 0x00, "0x-prefixed bytes parse");
        p = PicoReg.parseHexBytes("2b 00", 2);
        check(p != null && p[0] == 0x2b && p[1] == 0x00, "bare bytes parse");
        p = PicoReg.parseHexBytes("2b00", 2);
        check(p != null && p[0] == 0x2b && p[1] == 0x00, "a packed pair parses");
        check(PicoReg.parseHexBytes("2b", 2) == null,
                "a partial response is refused rather than guessed at");
        check(PicoReg.parseHexBytes("zip zip", 2) == null,
                "text with no hex in it is refused");
        check(PicoReg.parseHexBytes(null, 2) == null, "null is refused");
        // "banana" is, unhelpfully, mostly hex digits. The parser cannot tell that apart
        // from a real payload and does not try to - the decode is what refuses it, which
        // is why decodeSystemTemperature validates the shape rather than trusting bytes.
        check(Double.isNaN(PicoReg.fromResponseText("banana", "test").degC),
                "hex-looking junk is caught by the decode, not silently reported as a "
                        + "temperature");
        p = PicoReg.parseKernelLogLine(
                "<6>[  123.456] lcd extern: read 0xd6 data: 2b 00", 0xD6, 2);
        check(p != null && p[0] == 0x2b, "the driver's \"read 0x%02x data:\" line parses");
        check(PicoReg.parseKernelLogLine("read 0x52 data: 07", 0xD6, 2) == null,
                "a response to a different opcode is not mistaken for ours");
        check(PicoReg.parseKernelLogLine("something else entirely", 0xD6, 2) == null,
                "an unrelated line is ignored");

        // Against a stub tree: the node missing, unwritable, and returning junk. In every
        // case the answer must be "unavailable" with a specific reason, never a number.
        File tmp = File.createTempFile("fanlab-pico", "");
        tmp.delete();
        tmp.mkdirs();
        String oldRoot = Sysfs.root;
        try {
            Sysfs.root = tmp.getAbsolutePath();
            PicoReg.resetRouteLatch();
            PicoReg.Reading r = PicoReg.readSystemTemperature();
            check(PicoReg.STATUS_UNAVAILABLE.equals(r.status),
                    "a missing picoreg node reports unavailable");
            check(Double.isNaN(r.degC), "  with no temperature at all");
            check(r.reason != null && r.reason.indexOf("does not exist") >= 0,
                    "  and says exactly why: " + quote(r.reason));

            File dir = new File(tmp, "sys/class/dlpc343x");
            dir.mkdirs();
            File node = new File(dir, "picoreg");
            write(node, "");
            PicoReg.resetRouteLatch();
            r = PicoReg.readSystemTemperature();
            check(PicoReg.STATUS_UNAVAILABLE.equals(r.status),
                    "a node that accepts the command but answers nothing is unavailable");
            check(Double.isNaN(r.degC), "  still no temperature");
            check(r.reason != null && r.reason.indexOf("show()") >= 0,
                    "  and the reason names the actual obstacle - picoreg has no show() "
                            + "handler on this firmware, so the response goes to the kernel "
                            + "log and not back through sysfs");

            // If the round trip ever does close on real hardware, it must work. Driven
            // through the response handler directly, because a stub file cannot behave
            // like a sysfs node: writing to a file truncates it, writing to the node does
            // not affect what it reads back.
            PicoReg.Reading good = PicoReg.fromResponseText("2b 00\n", "sysfs");
            check(PicoReg.STATUS_OK.equals(good.status),
                    "a real response is read (this is the hoped-for case)");
            eq(good.degC, 43.0, 0.001, "  and decodes");
            check(good.provisional,
                    "  but is flagged provisional - the units are not confirmed");
            check("0x002b".equals(good.rawHex()), "  and carries the raw word");

            PicoReg.Reading bad = PicoReg.fromResponseText("ff 3f\n", "sysfs");
            check(PicoReg.STATUS_UNAVAILABLE.equals(bad.status),
                    "a response with a non-zero top nibble is refused, not decoded");
            check(Double.isNaN(bad.degC), "  and yields no temperature");
            check(bad.rawHex() != null, "  though the raw word is still recorded");
            check(PicoReg.fromResponseText("", "sysfs").status
                            .equals(PicoReg.STATUS_UNAVAILABLE),
                    "an empty response is unavailable");
            check(Double.isNaN(PicoReg.fromResponseText("hello", "sysfs").degC),
                    "an unparseable response yields no temperature");

            // The node echoing the command back must never be mistaken for data. The stub
            // does exactly that, because writing to an ordinary file truncates it.
            PicoReg.resetRouteLatch();
            r = PicoReg.readSystemTemperature();
            check(PicoReg.STATUS_UNAVAILABLE.equals(r.status),
                    "an echo of the command we just wrote is not decoded as a response");
            check(Double.isNaN(r.degC), "  and produces no temperature");

            check(PicoReg.readLedCurrents().length() == 0,
                    "LED currents read as empty when the nodes are absent, not as zeros");
            File cur = new File(dir, "rgbcurrent");
            write(cur, "76\n");
            check(PicoReg.readLedCurrents().indexOf("rgbcurrent=76") >= 0,
                    "and are recorded per step when they are present");

            PicoReg.Reading timed = PicoReg.readSystemTemperature(1500L);
            check(timed != null && timed.status != null,
                    "the bounded call always answers, so the 1 Hz loop cannot be stalled by it");
        } finally {
            Sysfs.root = oldRoot;
            rmrf(tmp);
        }
    }

    // ----------------------------------------------------------------- CAIC

    /**
     * The CAIC toggle is one write with a one-write undo, and the whole of its safety
     * argument is that the bytes on the wire are exactly the two DLPU078 documents and
     * that the read-back never invents a state. So: the strings, byte for byte; the
     * decode against good, wrong and absent bytes; and the write landing on the stub
     * node with nothing added to it.
     */
    private static void testCaic() throws Exception {
        section("CAIC (0x50/0x51) - the command bytes, and never inferring the state");

        // The opcodes are DLPU078's, and the strings are the picoreg format.
        eq(PicoReg.OPCODE_LED_OUTPUT_CONTROL_WRITE, 0x50, "Write LED Output Control Method is 0x50");
        eq(PicoReg.OPCODE_LED_OUTPUT_CONTROL_READ, 0x51, "Read LED Output Control Method is 0x51");
        eq(PicoReg.OPCODE_CAIC_MAX_POWER, 0x57, "Read CAIC LED Max Available Power is 0x57");
        check("w 50 1 1".equals(PicoReg.ledOutputControlCommand(true)),
                "CAIC on is exactly \"w 50 1 1\"");
        check("w 50 1 0".equals(PicoReg.ledOutputControlCommand(false)),
                "CAIC off is exactly \"w 50 1 0\" - the factory value, and the undo");
        check("r 51 1".equals(PicoReg.readCommand(PicoReg.OPCODE_LED_OUTPUT_CONTROL_READ,
                        PicoReg.LED_OUTPUT_CONTROL_LEN)),
                "the read-back command is exactly \"r 51 1\"");
        check("r 57 2".equals(PicoReg.readCommand(PicoReg.OPCODE_CAIC_MAX_POWER,
                        PicoReg.CAIC_MAX_POWER_LEN)),
                "the max-power read is \"r 57 2\"");

        // Decoding: two values are states, everything else is a wrong answer.
        check(PicoReg.CAIC_ON.equals(PicoReg.decodeLedOutputControl(new int[]{0x01})),
                "0x01 decodes as on");
        check(PicoReg.CAIC_OFF.equals(PicoReg.decodeLedOutputControl(new int[]{0x00})),
                "0x00 decodes as off");
        check(PicoReg.CAIC_UNKNOWN.equals(PicoReg.decodeLedOutputControl(new int[]{0x02})),
                "0x02 is neither, and is unknown rather than rounded to a state");
        check(PicoReg.CAIC_UNKNOWN.equals(PicoReg.decodeLedOutputControl(new int[]{0xFF})),
                "0xff is unknown");
        check(PicoReg.CAIC_UNKNOWN.equals(PicoReg.decodeLedOutputControl(null)),
                "no bytes is unknown, never off");
        check(PicoReg.CAIC_UNKNOWN.equals(PicoReg.decodeLedOutputControl(new int[0])),
                "an empty array is unknown");

        // The driver's kernel-log line, for the opcode we asked about and no other.
        int[] p = PicoReg.parseKernelLogLine(
                "<6>[  456.789] lcd extern: read 0x51 data: 01", 0x51, 1);
        check(p != null && p.length == 1 && p[0] == 0x01, "\"read 0x51 data: 01\" parses as on");
        p = PicoReg.parseKernelLogLine("read 0x51 data: 00", 0x51, 1);
        check(p != null && p[0] == 0x00, "\"read 0x51 data: 00\" parses as off");
        check(PicoReg.parseKernelLogLine("read 0x51 data: zz", 0x51, 1) == null,
                "garbage after data: is refused");
        check(PicoReg.parseKernelLogLine("read 0x51 data:", 0x51, 1) == null,
                "a line with no payload is refused");
        check(PicoReg.parseKernelLogLine("read 0xd6 data: 2b 00", 0x51, 1) == null,
                "a D6h response is not mistaken for a 0x51 one");
        check(PicoReg.parseKernelLogLine("read 0x50 data: 01", 0x51, 1) == null,
                "nor is the write opcode's own echo");

        // The response handler: on, off, and every way of not knowing.
        PicoReg.CaicReading r = PicoReg.caicFromResponseText("01\n", "sysfs");
        check(PicoReg.CAIC_ON.equals(r.state) && r.known(), "\"01\" reads as on");
        check("sysfs".equals(r.source) && "0x01".equals(r.rawHex()),
                "  with its source and raw byte");
        r = PicoReg.caicFromResponseText("00", "kernel log");
        check(PicoReg.CAIC_OFF.equals(r.state) && r.known(), "\"00\" reads as off");
        r = PicoReg.caicFromResponseText("07", "sysfs");
        check(PicoReg.CAIC_UNKNOWN.equals(r.state) && !r.known(),
                "\"07\" is unknown - a third value is not a state");
        check(r.rawByte == 0x07 && r.reason.indexOf("0x07") >= 0,
                "  and the raw byte is kept and named in the reason: " + quote(r.reason));
        r = PicoReg.caicFromResponseText("", "sysfs");
        check(PicoReg.CAIC_UNKNOWN.equals(r.state), "an empty response is unknown");
        check(r.rawByte < 0 && r.rawHex() == null, "  with no byte to show");
        r = PicoReg.caicFromResponseText(null, "sysfs");
        check(PicoReg.CAIC_UNKNOWN.equals(r.state), "a null response is unknown");
        r = PicoReg.caicFromResponseText("zip", "sysfs");
        check(PicoReg.CAIC_UNKNOWN.equals(r.state) && r.rawByte < 0,
                "text with no hex in it is unknown and yields no byte");
        // "banana" is mostly hex digits; parseHexBytes hands back 0xba and the decode
        // refuses it. Same shape as the D6h case, and the same reason it matters.
        r = PicoReg.caicFromResponseText("banana", "sysfs");
        check(PicoReg.CAIC_UNKNOWN.equals(r.state),
                "hex-looking junk is caught by the decode, not reported as a state");
        check(new PicoReg.CaicReading().reason != null
                        && !new PicoReg.CaicReading().known(),
                "a fresh reading is unknown with a reason, never a default state");

        // Max power: raw first, the watts interpretation flagged.
        PicoReg.CaicPower pw = PicoReg.caicPowerFromBytes(new int[]{0x34, 0x12}, "kernel log");
        eq(pw.rawWord, 0x1234, "0x57's word is little endian");
        eq(pw.watts, 46.60, 0.001, "  and /100 gives watts if DLPU078's scaling holds");
        check(pw.provisional, "  which is flagged provisional");
        check("0x1234".equals(pw.rawHex()), "  with the raw word beside it");
        check(PicoReg.caicPowerFromBytes(new int[]{0x34}, "x").rawWord < 0
                        && Double.isNaN(PicoReg.caicPowerFromBytes(null, "x").watts),
                "one byte, or none, is no power reading");

        // Against the stub tree: the write lands as the bare command, and the read never
        // manufactures a state out of an echo, an empty node, or a missing one.
        File tmp = File.createTempFile("fanlab-caic", "");
        tmp.delete();
        tmp.mkdirs();
        String oldRoot = Sysfs.root;
        try {
            Sysfs.root = tmp.getAbsolutePath();
            check(!PicoReg.writeLedOutputControl(true),
                    "with no picoreg node the write reports failure rather than pretending");
            r = PicoReg.readLedOutputControl();
            check(PicoReg.CAIC_UNKNOWN.equals(r.state)
                            && r.reason.indexOf("does not exist") >= 0,
                    "and the read is unknown, saying the node is missing: " + quote(r.reason));

            File dir = new File(tmp, "sys/class/dlpc343x");
            dir.mkdirs();
            File node = new File(dir, "picoreg");
            write(node, "");
            check(PicoReg.writeLedOutputControl(true), "the on write succeeds against the node");
            check("w 50 1 1".equals(slurp(node)),
                    "and the node holds exactly \"w 50 1 1\" - no newline, nothing else");
            check(PicoReg.writeLedOutputControl(false), "the off write succeeds");
            check("w 50 1 0".equals(slurp(node)), "and the node holds exactly \"w 50 1 0\"");

            // The stub echoes whatever was last written, which is what a file does and
            // exactly what a node with no show() must not be mistaken for.
            r = PicoReg.readLedOutputControl();
            check(PicoReg.CAIC_UNKNOWN.equals(r.state),
                    "an echo of \"r 51 1\" is not decoded as a state");
            check("r 51 1".equals(slurp(node)), "  (the read did write the read command)");
            check(r.reason.indexOf("show()") >= 0 && r.reason.indexOf("READ_LOGS") >= 0,
                    "  and the reason names the obstacle and the permission: "
                            + quote(r.reason));

            PicoReg.CaicReading timed = PicoReg.readLedOutputControl(1500L);
            check(timed != null && timed.state != null && !timed.known(),
                    "the bounded call always answers, and here answers unknown");
            PicoReg.CaicPower tp = PicoReg.readCaicMaxPower(1500L);
            check(tp != null && tp.rawWord < 0 && Double.isNaN(tp.watts),
                    "the bounded power read answers, with no watts it did not read");
        } finally {
            Sysfs.root = oldRoot;
            rmrf(tmp);
        }
    }

    // ----------------------------------------------------------------- image processing

    /**
     * The two image-processing controls, which are the reason CAIC had nothing to do.
     *
     * The whole case rests on bytes: the fixed-point gain, the packed LABB control byte, and
     * the exact command strings. The projector's own read-backs -- {@code 00 20 60} and
     * {@code 10 80 20 00} -- are in here verbatim, so a decoder that drifts from what the
     * hardware actually said fails here rather than on the machine.
     */
    private static void testImageProcessing() throws Exception {
        section("CAIC image control (0x84/0x85) and LABB (0x80/0x81) - the bytes");

        eq(PicoReg.OPCODE_CAIC_IMAGE_WRITE, 0x84, "Write CAIC Image Processing Control is 0x84");
        eq(PicoReg.OPCODE_CAIC_IMAGE_READ, 0x85, "and the read is 0x85");
        eq(PicoReg.OPCODE_LABB_WRITE, 0x80, "Write Local Area Brightness Boost is 0x80");
        eq(PicoReg.OPCODE_LABB_READ, 0x81, "and the read is 0x81");

        // ---- the fixed-point gain: b7=2^2 down to b0=2^-5, so the byte is gain x 32 ----
        eq(PicoReg.encodeCaicGain(1.0), 0x20, "1.0 encodes as 0x20");
        eq(PicoReg.encodeCaicGain(1.5), 0x30, "1.5 encodes as 0x30");
        eq(PicoReg.encodeCaicGain(2.0), 0x40, "2.0 encodes as 0x40");
        eq(PicoReg.encodeCaicGain(4.0), 0x80, "4.0 encodes as 0x80");
        eq(PicoReg.decodeCaicGain(0x20), 1.0, 1e-9, "and 0x20 decodes back to 1.0");
        eq(PicoReg.decodeCaicGain(0x30), 1.5, 1e-9, "0x30 back to 1.5");
        eq(PicoReg.decodeCaicGain(0x40), 2.0, 1e-9, "0x40 back to 2.0");
        eq(PicoReg.decodeCaicGain(0x80), 4.0, 1e-9, "0x80 back to 4.0");
        // Every representable step round-trips, not just the four named ones.
        for (int b = 0x20; b <= 0x80; b++) {
            if (PicoReg.encodeCaicGain(PicoReg.decodeCaicGain(b)) != b) {
                check(false, "the gain round trip loses byte 0x" + Integer.toHexString(b));
                break;
            }
        }
        check(true, "and every byte from 0x20 to 0x80 survives decode-then-encode");

        // Out of range is refused, not clamped: the controller rejects the whole command on
        // an invalid write parameter, so a clamp would send a gain the caller never asked
        // for while a pass-through would send one that silently does not execute.
        eq(PicoReg.encodeCaicGain(0.9), -1, "0.9 is refused - below the 1.0 the DLPC accepts");
        eq(PicoReg.encodeCaicGain(4.1), -1, "4.1 is refused - above the 4.0 the DLPC accepts");
        eq(PicoReg.encodeCaicGain(0.0), -1, "and so is 0");
        eq(PicoReg.encodeCaicGain(Double.NaN), -1, "and NaN");
        check(PicoReg.caicImageControlCommand(4.1, 0x60) == null,
                "so no command string is produced for one either - nothing is sent at all");
        check(Double.isNaN(PicoReg.decodeCaicGain(-1)), "no byte decodes to NaN, never to 0");

        // ---- what the projector actually answered ----
        PicoReg.CaicImage img = PicoReg.caicImageFromBytes(new int[]{0x00, 0x20, 0x60}, "kernel log");
        check(img.known, "the projector's own \"00 20 60\" is a reading");
        eq(img.gain, 1.0, 1e-9,
                "  and its maximum lumens gain is 1.0 - the bottom of the range, so CAIC was "
                        + "selected with permission to lift the image by nothing");
        eq(img.gainByte, 0x20, "  from byte 0x20");
        eq(img.clipThreshold, 96, "  with the clipping threshold at 96");
        check(!img.gainDisplay,
                "  and the debug overlay off, which is where it must stay - the guide says "
                        + "it must never be used for normal operation");
        check(img.summary().indexOf("gain 1.0") >= 0,
                "  summarised for the log as " + quote(img.summary()));
        check(!PicoReg.caicImageFromBytes(new int[]{0x00, 0x20}, "x").known,
                "two bytes is not a 0x85 reading and is refused");
        check(!PicoReg.caicImageFromBytes(null, "x").known, "nor is none");
        check(!PicoReg.caicImageFromResponseText("", "x").known, "nor an empty response");
        check(PicoReg.caicImageFromResponseText("00 20 60", "sysfs").known
                        && PicoReg.caicImageFromResponseText("002060", "sysfs").known,
                "and it parses whether the bytes arrive spaced or packed");

        // ---- the LABB control byte, and the sharpness it must not drop ----
        eq(PicoReg.labbControlByte(1, true), 0x11,
                "sharpness 1 with LABB enabled is 0x11: b7:4 the sharpness, b1:0 the control "
                        + "- and 0x11 is the byte that was actually written on 2026-09-07, "
                        + "read back as \"11 80\", with a live gain that then moved");
        eq(PicoReg.labbControlByte(1, false), 0x10, "and disabled is 0x10, which is what the "
                + "projector was found holding");
        eq(PicoReg.labbControlByte(0, true), 0x01, "sharpness 0 enabled is 0x01");
        eq(PicoReg.labbControlByte(15, true), 0xF1, "sharpness 15 enabled is 0xf1");
        eq(PicoReg.labbControlByte(15, false), 0xF0, "and disabled 0xf0");
        for (int s = 0; s <= 15; s++) {
            int on = PicoReg.labbControlByte(s, true);
            int off = PicoReg.labbControlByte(s, false);
            if (PicoReg.labbSharpnessOf(on) != s || PicoReg.labbSharpnessOf(off) != s
                    || PicoReg.labbControlOf(on) != PicoReg.LABB_CONTROL_ENABLED
                    || PicoReg.labbControlOf(off) != PicoReg.LABB_CONTROL_DISABLED) {
                check(false, "the LABB control byte loses sharpness " + s);
                break;
            }
        }
        check(true, "and every sharpness 0..15 survives the round trip through either state - "
                + "which is the point, since the two share a byte and DLPU078A says sharpness "
                + "does nothing unless LABB is enabled");
        eq(PicoReg.labbControlByte(99, true), 0xF1, "an over-large sharpness is held to 15");
        eq(PicoReg.labbControlByte(-3, true), 0x01, "and a negative one to 0");
        eq(PicoReg.labbControlByte(1, true) & 0x0C, 0,
                "b3:2 are left clear - which is where an earlier version of this put the "
                        + "control field, on the datasheet alone and before anyone had run it");

        // ---- what the projector actually answered ----
        PicoReg.Labb labb = PicoReg.labbFromBytes(new int[]{0x10, 0x80, 0x20, 0x00}, "kernel log");
        check(labb.known, "the projector's own \"10 80 20 00\" is a reading");
        check(!labb.enabled, "  and LABB is Disabled - control field 0h");
        eq(labb.strength, 128, "  with the strength already preset to 128");
        eq(labb.sharpness, 1, "  and sharpness 1");
        eq(labb.gainRaw, 0x20,
                "  and the read-only current gain kept as the raw 0x20 - Table 3-81 gives "
                        + "the range as 1..8 and 32 is not in it, so converting it would be "
                        + "inventing units");
        eq(labb.status, 0x00, "  with byte 4 recorded as it came");
        PicoReg.Labb on = PicoReg.labbFromBytes(new int[]{0x11, 0x80, 0x27, 0x00}, "kernel log");
        check(on.known && on.enabled && on.sharpness == 1,
                "\"11 80 27 00\" is the same row with LABB enabled, and the gain off idle");
        eq(on.gainRaw, 0x27, "  0x27 being what the gain byte read on real video");
        PicoReg.Labb reserved = PicoReg.labbFromBytes(new int[]{0x12, 0x80, 0x20, 0x00}, "x");
        check(!reserved.known && !reserved.enabled,
                "a 2h control field is reserved, so it is unknown rather than a third state");
        eq(reserved.strength, 128, "  though the bytes around it are still kept");
        check(reserved.reason.indexOf("reserve") >= 0,
                "  and the reason says so: " + quote(reserved.reason));
        check(!PicoReg.labbFromBytes(new int[]{0x10, 0x80, 0x20}, "x").known,
                "three bytes is not a 0x81 reading");
        check(!PicoReg.labbFromResponseText("zip", "x").known,
                "and text with no hex in it is not one either");

        // ---- the command strings, byte for byte, against the stub node ----
        check("w 84 3 0 40 60".equals(PicoReg.caicImageControlCommand(2.0, 0x60)),
                "a 2.0 gain is exactly \"w 84 3 0 40 60\"");
        check("w 84 3 0 20 60".equals(
                        PicoReg.caicImageControlCommand(PicoReg.CAIC_GAIN_STOCK, 0x60)),
                "and the restore is \"w 84 3 0 20 60\" - the bytes the projector was found "
                        + "holding, so switching CAIC off leaves the machine as it was");
        check("w 80 2 11 80".equals(PicoReg.labbCommand(true, 128, 1)),
                "enabling LABB is exactly \"w 80 2 11 80\"");
        check("w 80 2 10 80".equals(PicoReg.labbCommand(false, 128, 1)),
                "and the undo is \"w 80 2 10 80\"");
        check("r 85 3".equals(PicoReg.readCommand(PicoReg.OPCODE_CAIC_IMAGE_READ,
                        PicoReg.CAIC_IMAGE_LEN)),
                "the CAIC image read-back is \"r 85 3\"");
        check("r 81 4".equals(PicoReg.readCommand(PicoReg.OPCODE_LABB_READ,
                        PicoReg.LABB_READ_LEN)),
                "and LABB's is \"r 81 4\"");

        File tmp = File.createTempFile("fanlab-imgproc", "");
        tmp.delete();
        tmp.mkdirs();
        String oldRoot = Sysfs.root;
        try {
            Sysfs.root = tmp.getAbsolutePath();
            check(!PicoReg.writeCaicImageControl(2.0, 0x60),
                    "with no picoreg node the gain write reports failure rather than pretending");
            check(!PicoReg.writeLabb(true, 128, 1), "and so does the LABB write");
            check(!PicoReg.readLabb().known,
                    "and the read is unknown, not a state nobody read");

            File dir = new File(tmp, "sys/class/dlpc343x");
            dir.mkdirs();
            File node = new File(dir, "picoreg");
            write(node, "");
            check(PicoReg.writeCaicImageControl(2.0, PicoReg.CAIC_CLIP_THRESHOLD_STOCK),
                    "the gain write succeeds against the node");
            check("w 84 3 0 40 60".equals(slurp(node)),
                    "and the node holds exactly \"w 84 3 0 40 60\" - no newline, nothing else");
            check(PicoReg.writeLabb(true, PicoReg.LABB_STRENGTH_STOCK,
                            PicoReg.LABB_SHARPNESS_STOCK),
                    "the LABB write succeeds");
            check("w 80 2 11 80".equals(slurp(node)),
                    "and the node holds exactly \"w 80 2 11 80\" - no newline, nothing else");
            check(PicoReg.writeLabb(false, PicoReg.LABB_STRENGTH_STOCK,
                            PicoReg.LABB_SHARPNESS_STOCK),
                    "the off write succeeds");
            check("w 80 2 10 80".equals(slurp(node)), "and holds exactly \"w 80 2 10 80\"");

            // An out-of-range gain must not reach the node at all: the DLPC would reject the
            // whole command, so the byte on the wire would change nothing while the app
            // believed it had set a budget.
            check(!PicoReg.writeCaicImageControl(9.0, 0x60),
                    "a 9.0 gain is refused even with the node right there");
            check("w 80 2 10 80".equals(slurp(node)),
                    "  and nothing was written - the node still holds the previous command");

            // The stub echoes what was last written, which is what a file does and exactly
            // what a node with no show() must not be mistaken for.
            PicoReg.Labb echoed = PicoReg.readLabb();
            check(!echoed.known, "an echo of \"r 81 4\" is not decoded as a state");
            check(echoed.reason.indexOf("show()") >= 0
                            && echoed.reason.indexOf("READ_LOGS") >= 0,
                    "  and the reason names the obstacle and the permission: "
                            + quote(echoed.reason));
            PicoReg.CaicImage echoedImg = PicoReg.readCaicImageControl();
            check(!echoedImg.known, "the same for \"r 85 3\"");

            PicoReg.Labb timed = PicoReg.readLabb(1500L);
            check(timed != null && !timed.known,
                    "the bounded LABB read always answers, so the 1 Hz loop cannot stall on it");
            PicoReg.CaicImage timedImg = PicoReg.readCaicImageControl(1500L);
            check(timedImg != null && !timedImg.known, "and so does the bounded gain read");
        } finally {
            Sysfs.root = oldRoot;
            rmrf(tmp);
        }
    }

    // ----------------------------------------------------------------- the Looks

    /**
     * The colour sequence presets, and the arithmetic that says Look 0 is the end of it.
     *
     * A Look divides the frame's time between the three LEDs. The total is fixed, so every
     * Look is a trade, and the sweep of all 19 on 2026-09-07 says what each one trades:
     * Look 0 is 40/40/20 and reads white on a white field, and every other Look takes red
     * down to 25-33 % and gives the time to green. Looks 1 and 15 read visibly green.
     *
     * Two things are pinned here. The <b>duty encoding</b>, because UQ8.8 over 256 is where
     * TI's own guide and TI's own reference Python disagree, and the guide's worked example
     * settles it -- the three have to sum to 100 and only /256 delivers that. And the
     * <b>sum check</b> itself, which is what makes the decode self-verifying: get the byte
     * order or the scale wrong and the three stop adding up, so a wrong reading is refused
     * rather than reported.
     */
    private static void testLooks() {
        section("the Looks (22h/23h/26h) - the duty split, and why 0 is the only one");

        eq(PicoReg.OPCODE_LOOK_SELECT_WRITE, 0x22, "Write Look Select is 0x22");
        eq(PicoReg.OPCODE_LOOK_SELECT_READ, 0x23, "Read Look Select is 0x23");
        eq(PicoReg.OPCODE_SEQUENCE_HEADER_READ, 0x26,
                "and Read Sequence Header Attributes - the one with the duty cycles - is 0x26");
        eq(PicoReg.SEQUENCE_HEADER_LEN, 30,
                "which answers thirty bytes: the Look's fifteen, then the Sequence's fifteen");

        check("w 22 1 0".equals(PicoReg.lookSelectCommand(0)),
                "selecting Look 0 is exactly \"w 22 1 0\"");
        check("w 22 1 f".equals(PicoReg.lookSelectCommand(15)),
                "and Look 15 is \"w 22 1 f\"");
        check("r 26 1e".equals(PicoReg.readCommand(PicoReg.OPCODE_SEQUENCE_HEADER_READ,
                        PicoReg.SEQUENCE_HEADER_LEN)),
                "the split read is \"r 26 1e\" - thirty in hex, which is what the node wants");

        // ---- UQ8.8: high byte whole percent, low byte 256ths ----
        eq(PicoReg.decodeDuty(0x00, 0x28), 40.0, 1e-9, "00 28 little endian is 40.0 %");
        eq(PicoReg.decodeDuty(0x00, 0x14), 20.0, 1e-9, "00 14 is 20.0 %");
        eq(PicoReg.decodeDuty(0x80, 0x1E), 30.5, 1e-9,
                "and the guide's own 1E80 is 30.5 - exact over 256, which is how the /255 in "
                        + "TI's reference Python is known to be the wrong one of the two");
        eq(PicoReg.decodeDuty(0x00, 0x32), 50.0, 1e-9, "with 3200 alongside it at 50.0");
        eq(PicoReg.decodeDuty(0x80, 0x13), 19.5, 1e-9, "and 1380 at 19.5");
        check(Math.abs((30.5 + 50.0 + 19.5) - PicoReg.DUTY_SUM) < 1e-9,
                "  those three summing to exactly 100, as DLPU078A requires");

        // ---- Look 0, as the projector answered it ----
        PicoReg.Look zero =
                PicoReg.sequenceHeaderFromBytes(look(0x2800, 0x2800, 0x1400), "kernel log");
        check(zero.known, "Look 0's own 40/40/20 is a reading");
        eq(zero.red, 40.0, 1e-9, "  red 40.0 %");
        eq(zero.green, 40.0, 1e-9, "  green 40.0 %");
        eq(zero.blue, 20.0, 1e-9, "  and blue 20.0 %");
        check(zero.blocksAgree, "  with the Sequence block's copy matching the Look's");
        PicoReg.lookSelectIntoBytes(zero, new int[]{0, 0, 0x10, 0x27, 0, 0});
        eq(zero.number, 0, "0x23 puts the Look number on it");
        eq(zero.sequence, 0, "  and the sequence number");
        check(zero.neutral(), "  and Look 0 is the neutral one");
        check(zero.summary().indexOf("40.0/40.0/20.0") >= 0,
                "which the screen prints as the split: " + quote(zero.summary()));

        // ---- and one of the eighteen that are not ----
        PicoReg.Look fifteen =
                PicoReg.sequenceHeaderFromBytes(look(0x1900, 0x3700, 0x1400), "kernel log");
        check(fifteen.known, "a 25/55/20 Look reads too - the fifteen points red lost");
        eq(fifteen.green, 55.0, 1e-9, "  green up to 55 %");
        eq(fifteen.red, 25.0, 1e-9,
                "  with red down to 25 % - and the time it lost went to green, which is what "
                        + "made Looks 1 and 15 read visibly green on a white field");
        PicoReg.lookSelectIntoBytes(fifteen, new int[]{15, 0, 0x10, 0x27, 0, 0});
        check(!fifteen.neutral(), "  and it is not the neutral Look");

        // ---- the sum check is the decode's own proof ----
        PicoReg.Look bad = PicoReg.sequenceHeaderFromBytes(look(0x2800, 0x2800, 0x2800), "x");
        check(!bad.known, "three duty cycles summing to 120 are not a reading");
        check(bad.reason.indexOf("100") >= 0,
                "  and the reason says what they should have summed to: " + quote(bad.reason));
        int[] swapped = look(0x2800, 0x2800, 0x1400);
        int keep = swapped[0];
        swapped[0] = swapped[1];
        swapped[1] = keep;
        check(!PicoReg.sequenceHeaderFromBytes(swapped, "x").known,
                "and getting the byte order wrong stops the three adding up, which is the "
                        + "whole point of checking the sum rather than trusting the layout");

        // ---- blocks that disagree are reported, not averaged ----
        int[] mismatch = look(0x2800, 0x2800, 0x1400);
        mismatch[15] = 0x01;
        PicoReg.Look apart = PicoReg.sequenceHeaderFromBytes(mismatch, "x");
        check(apart.known, "a Look block that disagrees with the Sequence block still decodes");
        check(!apart.blocksAgree, "  but says the two copies differ, which DLPU078A forbids");
        check(apart.summary().indexOf("DISAGREE") >= 0,
                "  and puts it on the screen: " + quote(apart.summary()));

        // ---- nothing short of thirty bytes is a reading ----
        check(!PicoReg.sequenceHeaderFromBytes(new int[]{0x00, 0x28}, "x").known,
                "two bytes is not a 0x26 reading");
        check(!PicoReg.sequenceHeaderFromBytes(null, "x").known, "nor is none");
        check(!PicoReg.sequenceHeaderFromResponseText("", "x").known, "nor an empty response");
        check(!PicoReg.sequenceHeaderFromResponseText("zip", "x").known,
                "nor text with no hex in it");
        check(new PicoReg.Look().summary() == null && !new PicoReg.Look().known,
                "and a reading nobody has taken says nothing at all");
        eq(PicoReg.LOOK_COUNT, 19, "the projector answered for 19 Looks");
        eq(PicoReg.LOOK_NEUTRAL, 0, "and exactly one of them is neutral");
    }

    /**
     * A 0x26 response: three UQ8.8 duty words, little endian, in the Look block, and the
     * same three again in the Sequence block fifteen bytes later. Everything between is the
     * frame counts and the vector count, which this app does not read.
     */
    private static int[] look(int red, int green, int blue) {
        int[] b = new int[PicoReg.SEQUENCE_HEADER_LEN];
        int[] duty = {red, green, blue};
        for (int i = 0; i < 3; i++) {
            b[i * 2] = duty[i] & 0xFF;
            b[i * 2 + 1] = (duty[i] >> 8) & 0xFF;
            b[15 + i * 2] = b[i * 2];
            b[15 + i * 2 + 1] = b[i * 2 + 1];
        }
        return b;
    }

    // ----------------------------------------------------------------- LED drive

    /**
     * The configured levels, and the clamp that is the whole reason this class has an upper
     * bound at all.
     *
     * 97 is not a round number and not a preference. The driver turns a percent into a
     * 7-bit DAC code, {@code code = (30*mA + 40000) / 1968}, and clamps it with
     * {@code if (code > 0x7F) code = 0x3F} -- so an over-request does not saturate the
     * channel, it drops it to about 40 % and the picture goes <i>dim</i>. The code crosses
     * 127 at about 99 % of the measured 7.1 A per-channel maximum, so anything that could
     * store 100 would be storing a dim picture.
     */
    private static void testLedDriveConfig() {
        section("LED drive: the four levels, and the 97 that is not a round number");

        eq(LedDrive.MAX_LEVEL, 97, "the ceiling is 97, three points below the DAC cliff");
        eq(LedDrive.MIN_LEVEL, 20, "and the floor is Super Eco's own stock drive");

        LedDrive.Config stock = new LedDrive.Config();
        check(stock.isStock(), "a fresh config is the kernel's own table");
        check(stock.encode().equals("d1,20,40,55,76"),
                "which encodes as the table itself  (got " + stock.encode() + ")");

        LedDrive.Config bright = LedDrive.Config.bright();
        check(!bright.isStock(), "the Bright preset is not");
        check(bright.encode().equals("d1,35,55,75,90"),
                "and is 35/55/75/90  (got " + bright.encode() + ")");
        eq(bright.levelFor(3), 90, "Presentation reads 90 - 76 stock, so 18 % more drive");
        eq(bright.levelFor(2), 75, "Normal reads 75");
        eq(bright.levelFor(1), 55, "Eco reads 55");
        eq(bright.levelFor(4), 35, "Super Eco reads 35");
        bright.sanitise();
        check(bright.encode().equals("d1,35,55,75,90"),
                "and every one of them is inside MAX_LEVEL, so sanitise leaves it alone");
        eq(bright.levelFor(7), -1, "and a brightness mode this class does not know reads -1");

        // ---- the clamp, which is the point ----
        LedDrive.Config hot = new LedDrive.Config();
        for (int i = 0; i < LedDrive.TIERS; i++) {
            hot.level[i] = 100;
        }
        hot.sanitise();
        eq(hot.level[3], 97,
                "a requested 100 is stored as 97 - at 100 the DAC code passes 127 and the "
                        + "driver answers by dropping the channel to about 40 %");
        check(hot.encode().equals("d1,97,97,97,97"),
                "on every tier  (got " + hot.encode() + ")");
        check(LedDrive.Config.decode("d1,100,100,100,100").encode().equals("d1,97,97,97,97"),
                "and a stored line asking for 100 is repaired on the way in, not obeyed");
        check(LedDrive.Config.decode("d1,5,5,5,5").encode().equals("d1,20,20,20,20"),
                "the floor is enforced the same way");
        check(LedDrive.Config.decode("d1,-40,0,255,9999").encode().equals("d1,20,20,97,97"),
                "including on a line nobody could have typed by accident");

        // ---- channel 1 keeps the stock table's ratio, whatever the level ----
        eq(LedDrive.redFor(3, 95), 89, "Presentation 95 drives the red die at 89");
        eq(LedDrive.redFor(3, 90), 84, "and the older 90 drove it at 84");
        eq(LedDrive.redFor(3, 97), 91, "and the ceiling level at 91");
        eq(LedDrive.redFor(3, 76), 71, "the stock level reproduces the stock red exactly");
        eq(LedDrive.redFor(2, 70), 61, "Normal keeps 48/55");
        eq(LedDrive.redFor(1, 50), 45, "Eco keeps 36/40");
        eq(LedDrive.redFor(4, 30), 30, "and Super Eco is 1:1, as the table has it");
        eq(LedDrive.redFor(0, 50), 50, "an unknown mode gets the level unchanged");
        eq(LedDrive.redFor(9, 50), 50, "in both directions off the end of the table");

        eq(LedDrive.tierOf(4), 0, "Super Eco is the first tier");
        eq(LedDrive.tierOf(3), 3, "Presentation the last");
        eq(LedDrive.tierOf(5), -1, "and an unknown mode has none");

        // ---- a broken setting must never leave the LEDs somewhere nobody chose ----
        check(LedDrive.Config.decode(null).isStock(), "a missing config is stock");
        check(LedDrive.Config.decode("").isStock(), "an empty one is stock");
        check(LedDrive.Config.decode("d1,30,50").isStock(), "a truncated one is stock");
        check(LedDrive.Config.decode("d1,a,b,c,d").isStock(), "an unparseable one is stock");
        check(LedDrive.Config.decode("d9,30,50,70,90").isStock(),
                "and an unknown version is stock, not read as if it were d1");
        check(LedDrive.Config.decode(bright.encode()).encode().equals(bright.encode()),
                "an edited config round trips byte for byte");
    }

    /**
     * The {@code rgbcurrent} show handler, which is racy, off by one, and now explained.
     *
     * A failed SPI read hands the driver {@code 0x8080}; it computes
     * {@code current = -1333 ma, percent = -18}; sysfs prints that percent as an unsigned
     * byte. That is where the 238 and 241 seen in the field come from, and it is why any
     * field above 100 has to read as "could not tell" rather than as the kernel having
     * overwritten the drive -- treating it as a mismatch would rewrite the nodes every
     * five seconds for ever.
     */
    private static void testLedDriveReadback() {
        section("LED drive: reading back, the off-by-one and the 238/241 glitch");

        // Exactly what the projector printed on 2026-09-07 with 90 written to rgbcurrent
        // and 84 to redcurrent. duty_g is the red channel: the kernel prints ch0..ch3
        // under the labels r/g/b/b2 while the map is green/red/b2/blue.
        int[] rb = LedDrive.parseReadback("red_current=13 green_current=13 blue_current=13 "
                + "duty_r=89 duty_g=83 duty_b=89 duty_b2=89");
        check(rb != null && rb[0] == 83 && rb[1] == 89 && rb[2] == 89,
                "duty_g carries red and the other three carry the common level");
        check(LedDrive.parseReadback("duty_r=100 duty_g=100") != null,
                "100 is a legal reading");

        // Three fields carry the common level, so one glitching is not a lost reading.
        rb = LedDrive.parseReadback("duty_r=238 duty_g=83 duty_b=89 duty_b2=89");
        check(rb != null && rb[0] == 83 && rb[1] == 89 && rb[2] == 89,
                "a glitch in one common field is covered by the other two");
        rb = LedDrive.parseReadback("duty_r=241 duty_g=83 duty_b=238 duty_b2=89");
        check(rb != null && rb[1] == 89 && rb[2] == 89,
                "two glitches still leave a usable reading");

        // The half-written table: the kernel writes the four channels one at a time, and
        // this is Eco part way through, read off the projector. Agreeing with it is how a
        // colour cast gets reported as a correct override.
        rb = LedDrive.parseReadback("duty_r=49 duty_g=44 duty_b=39 duty_b2=39");
        check(rb != null && rb[1] == 39 && rb[2] == 49,
                "channels that disagree come back as a range, not as whichever was first");
        check(!LedDrive.readbackAgrees(rb, 45, 50),
                "and a range never agrees, however close one end of it is");
        check(LedDrive.readbackAgrees(
                        LedDrive.parseReadback("duty_r=49 duty_g=44 duty_b=49 duty_b2=49"),
                        45, 50),
                "while the fully applied table does, at the handler's one below");
        check(LedDrive.parseReadback("duty_r=238 duty_g=83 duty_b=241 duty_b2=238") == null,
                "but all three glitching is 'could not tell', not a mismatch");

        check(LedDrive.parseReadback("duty_r=89 duty_g=241") == null,
                "red glitching is a lost reading -- it is printed once and has no stand-in");
        check(LedDrive.parseReadback("duty_r=89 duty_g=101") == null,
                "anything above 100 is the failed-SPI byte, not a drive level");
        check(LedDrive.parseReadback("duty_r=89") == null, "a missing duty_g is no reading");
        check(LedDrive.parseReadback("duty_g=83") == null,
                "and so is a red with nothing to compare the common level against");
        check(LedDrive.parseReadback("duty_r=89 duty_g=") == null, "nor is an empty field");
        check(LedDrive.parseReadback("duty_r=89 duty_g=x") == null, "nor is junk");
        check(LedDrive.parseReadback(null) == null, "nor is nothing at all");
        check(LedDrive.parseReadback("") == null, "nor is an empty node");

        check(LedDrive.matches(75, 76), "the handler reports one below what was written");
        check(LedDrive.matches(76, 76),
                "and the exact value is accepted too, so a firmware that stops doing that "
                        + "does not turn every tick into a rewrite");
        check(!LedDrive.matches(74, 76), "two below is a mismatch");
        check(!LedDrive.matches(55, 76), "and the stock table reappearing certainly is");
    }

    /**
     * The decision, walked through the states it has to get right, with the writes landing
     * on the stub tree.
     *
     * The sequences here are the ones that cost something when they are wrong: an override
     * that never applies, one that rewrites the nodes every second, one that does not come
     * back after the kernel reinstates the stock table, one that does not go away when the
     * app stops being the fan controller, and a ceiling trip that re-arms itself and
     * flickers the picture.
     */
    private static void testLedDriveDecide() throws Exception {
        section("LED drive: apply, hold, rewrite, restore, and the ceiling latch");

        File root = stubRoot();
        String oldRoot = Sysfs.root;
        Sysfs.root = root.getAbsolutePath();
        try {
            File rgbCurrent = new File(root, "sys/class/dlpc343x/rgbcurrent");
            File redCurrent = new File(root, "sys/class/dlpc343x/redcurrent");
            File rgbLevel = new File(root, "sys/class/dlpc343x/rgblevel");

            LedDrive d = new LedDrive();
            // Pinned at the older 90 rather than taken from Config.bright(), on purpose.
            // This test drives the state machine against the read-back string the projector
            // actually printed on 2026-09-07 -- "duty_r=89 duty_g=83", 90 written and one
            // below on the way back -- so it must not move every time the one-press preset
            // does. What bright() currently holds is testLedDriveConfig's business.
            LedDrive.Config raised = new LedDrive.Config();
            raised.level[2] = 70;
            raised.level[3] = 90;
            long t = 1000000L;

            // ---- not allowed: nothing is written, and nothing is believed ----
            LedDrive.Plan p = d.decide(raised, 3, false, 45.0, null, t);
            eq(p.action, LedDrive.Plan.NONE, "not allowed writes nothing at all");
            check(!d.overriding(), "and believes nothing is applied");
            eq(d.appliedLevel(), -1, "which is the blank the CSV column carries");
            check("stock".equals(d.state()), "with the screen saying stock");

            // ---- the enabling edge applies, immediately ----
            p = d.decide(raised, 3, true, 45.0, null, t);
            eq(p.action, LedDrive.Plan.APPLY, "the enabling edge applies");
            eq(p.other, 90, "at Presentation's configured level");
            eq(p.red, 84, "with channel 1 held to the stock table's ratio");
            check(d.perform(p), "and both writes land");
            check("90".equals(slurp(rgbCurrent)),
                    "rgbcurrent holds the level bare  (got " + quote(slurp(rgbCurrent)) + ")");
            check("84".equals(slurp(redCurrent)), "and redcurrent the ratio");
            eq(d.appliedLevel(), 90, "the CSV column carries the level once it is on");
            check(d.state().startsWith("applied 90/84"),
                    "and the screen says so  (got " + quote(d.state()) + ")");

            // ---- steady state: a read-back that agrees writes nothing ----
            t += 1000L;
            p = d.decide(raised, 3, true, 45.0, "duty_r=89 duty_g=83", t);
            eq(p.action, LedDrive.Plan.NONE,
                    "a read-back one below what was written is agreement, not a mismatch");

            // ---- the glitch is not a mismatch ----
            t += 1000L;
            p = d.decide(raised, 3, true, 45.0, "duty_r=238 duty_g=241 duty_b=238", t);
            eq(p.action, LedDrive.Plan.NONE,
                    "and the 238 glitch is 'could not tell', which also writes nothing");

            // ---- a genuine mismatch rewrites, but not before the rate limit ----
            // Anchored to the constant rather than to a wall-clock guess: this assertion
            // was written against a 5 s limit and silently became untrue when the limit
            // was shortened, which is the sort of test that only fails once it matters.
            // Start the limit's clock from a known write rather than from whatever the
            // steps above happened to leave behind.
            p = d.decide(raised, 3, true, 45.0, "duty_r=76 duty_g=71", t, true);
            eq(p.action, LedDrive.Plan.APPLY,
                    "urgent rewrites regardless of the limit -- what a mode change needs");
            check(d.perform(p), "and that write lands");
            long applied = t;

            t = applied + LedDrive.REAPPLY_EVERY_MS / 2;
            p = d.decide(raised, 3, true, 45.0, "duty_r=76 duty_g=71", t);
            eq(p.action, LedDrive.Plan.NONE,
                    "the stock table reappearing inside REAPPLY_EVERY_MS waits its turn");

            t = applied + LedDrive.REAPPLY_EVERY_MS;
            p = d.decide(raised, 3, true, 45.0, "duty_r=76 duty_g=71", t);
            eq(p.action, LedDrive.Plan.APPLY, "and is put back once the limit has passed");
            eq(p.other, 90, "at the same level");
            check(d.perform(p), "and the rewrite lands");

            // ---- a brightness-mode change is an edge, and ignores the limit ----
            put(root, "sys/class/dlpc343x/rgblevel", "2\n");
            t += 1000L;
            p = d.decide(raised, 2, true, 45.0, null, t);
            eq(p.action, LedDrive.Plan.APPLY,
                    "a brightness-mode change re-applies at once, rate limit or not");
            eq(p.other, 70, "at Normal's configured level");
            eq(p.red, 61, "and Normal's own ratio");
            check(d.perform(p), "with both writes landing again");

            // ---- losing the coupling puts the stock table back ----
            t += 1000L;
            p = d.decide(raised, 2, false, 45.0, null, t);
            eq(p.action, LedDrive.Plan.RESTORE,
                    "losing the coupling restores the stock table");
            eq(p.rgblevel, 2, "by rewriting the mode the override was applied under");
            check(!d.overriding(), "it stops believing anything is applied");
            eq(d.appliedLevel(), -1, "and the CSV column goes blank again");
            check(d.perform(p), "the restore write lands");
            check("2".equals(slurp(rgbLevel)),
                    "as a rewrite of rgblevel with the value it already held, which is what "
                            + "makes the kernel reinstate the whole table");
            t += 1000L;
            p = d.decide(raised, 2, false, 45.0, null, t);
            eq(p.action, LedDrive.Plan.NONE,
                    "and it is done once, not on every tick that follows");

            // ---- the ceiling: dropped, latched, and no automatic re-arm ----
            put(root, "sys/class/dlpc343x/rgblevel", "3\n");
            LedDrive e = new LedDrive();
            long u = 2000000L;
            check(e.perform(e.decide(raised, 3, true, 45.0, null, u)),
                    "a fresh override applies while the light engine is cool");
            u += 1000L;
            p = e.decide(raised, 3, true, LedDrive.DEFAULT_TRIP_C + 0.2, null, u);
            eq(p.action, LedDrive.Plan.RESTORE,
                    "above the ceiling the override is dropped");
            check(e.tripped(), "and latched off");
            check(e.state().startsWith("held off"),
                    "which the screen names  (got " + quote(e.state()) + ")");
            check(p.note.indexOf("LEDDRIVE TRIP") >= 0,
                    "and the log gets it as an event  (got " + quote(p.note) + ")");
            e.perform(p);
            u += 30000L;
            p = e.decide(raised, 3, true, 40.0, null, u);
            eq(p.action, LedDrive.Plan.NONE,
                    "cooling down does not re-arm it - brightness cycling on the wall is "
                            + "more objectionable than a fan swing, so it waits to be asked");
            check(e.tripped(), "the latch holds");

            // ---- and releases on the two things that make the trip stale ----
            u += 1000L;
            p = e.decide(raised, 2, true, 40.0, null, u);
            check(!e.tripped(), "a brightness-mode change is a different LED load, so it releases");
            eq(p.action, LedDrive.Plan.APPLY, "and the override goes back on for the new mode");

            LedDrive f = new LedDrive();
            long v = 3000000L;
            f.perform(f.decide(raised, 3, true, 45.0, null, v));
            v += 1000L;
            f.perform(f.decide(raised, 3, true, LedDrive.DEFAULT_TRIP_C + 1.0, null, v));
            check(f.tripped(), "a second override trips the same way");
            v += 1000L;
            LedDrive.Config other = LedDrive.Config.decode("d1,25,45,60,80");
            p = f.decide(other, 3, true, 45.0, null, v);
            check(!f.tripped(), "and changing the configuration releases the latch too");
            eq(p.action, LedDrive.Plan.APPLY, "at the new levels");
            eq(p.other, 80, "which is the new Presentation level");

            // ---- the handback that is not a tick ----
            LedDrive g = new LedDrive();
            long w = 4000000L;
            check(g.forceRestore(w).action == LedDrive.Plan.NONE,
                    "forcing a restore with nothing applied writes nothing, so calling it "
                            + "on a machine this app never boosted is free");
            g.perform(g.decide(raised, 3, true, 45.0, null, w));
            check(g.overriding(), "with an override applied");
            LedDrive.Plan back = g.forceRestore(w);
            eq(back.action, LedDrive.Plan.RESTORE, "forcing a restore asks for the rewrite");
            check(!g.overriding(), "and drops the belief immediately");

            // ---- a stock table is the same as off, whatever the switch says ----
            LedDrive h = new LedDrive();
            p = h.decide(new LedDrive.Config(), 3, true, 45.0, null, w);
            eq(p.action, LedDrive.Plan.NONE,
                    "a stock configuration has nothing to apply, so allowed changes nothing");
            p = h.decide(null, 3, true, 45.0, null, w);
            eq(p.action, LedDrive.Plan.NONE, "and neither does no configuration at all");
        } finally {
            Sysfs.root = oldRoot;
            rmrf(root);
        }
    }

    /**
     * LINEAR's ceiling under the LED drive override: promoted when it is still the untouched
     * default, left exactly alone when somebody has set it.
     *
     * The numbers behind the promotion are inferred from the x1.18 the boost costs, not
     * measured, and they are in {@link LinearConfig#BOOST_CEILING_C}. What is checked here
     * is the rule, which is the part that can be wrong in a way nobody notices: a controller
     * that quietly rewrote a ceiling its owner had chosen, or that stored 54 where 52 was
     * meant, would both look correct on screen.
     */
    private static void testLinearCeilingPromotion() {
        section("linear: the ceiling the LED drive override moves, and the ones it must not");

        eq((int) Math.round(LinearConfig.DEFAULT_CEILING_C * 10), 520,
                "the stock default is 52.0");
        eq((int) Math.round(LinearConfig.BOOST_CEILING_C * 10), 540,
                "and the boosted one is 54.0, where the Bright curve rests");

        LinearConfig off = new LinearConfig();
        check(!LinearConfig.promoteForBoost(off, false),
                "with the override off the default is left where it is");
        eq((int) Math.round(off.ceilingC * 10), 520, "untouched");

        LinearConfig on = new LinearConfig();
        check(LinearConfig.promoteForBoost(on, true),
                "with the override on the untouched default is raised, and says it was");
        eq((int) Math.round(on.ceilingC * 10), 540, "to 54.0");
        check(!LinearConfig.promoteForBoost(on, true),
                "and a second pass over an already-raised config moves nothing and claims "
                        + "nothing, so the status line does not announce it twice");

        // A ceiling somebody chose is a ceiling somebody chose, whatever else is on.
        double[] hand = {35.0, 49.0, 51.9, 52.1, 54.0, 60.0};
        for (int i = 0; i < hand.length; i++) {
            LinearConfig h = new LinearConfig();
            h.ceilingC = hand[i];
            h.sanitise();
            double was = h.ceilingC;
            check(!LinearConfig.promoteForBoost(h, true),
                    "a hand-set " + Sample.fmt1(was) + " C ceiling is left alone");
            eq((int) Math.round(h.ceilingC * 10), (int) Math.round(was * 10), "  exactly");
        }

        // Nothing is stored. The promotion is applied to the copy the loop is about to use,
        // so the encoded line -- which is what reaches SharedPreferences -- still says 52.0,
        // and switching the override off puts the ceiling back without a migration.
        LinearConfig stored = new LinearConfig();
        LinearConfig loaded = LinearConfig.decode(stored.encode());
        LinearConfig.promoteForBoost(loaded, true);
        check(stored.encode().indexOf("52.0") >= 0,
                "the stored line is still the 52.0 default  (got " + stored.encode() + ")");
        check(loaded.encode().indexOf("54.0") >= 0,
                "while the copy the loop holds says 54.0  (got " + loaded.encode() + ")");
        check(LinearConfig.decode(stored.encode()).ceilingC == LinearConfig.DEFAULT_CEILING_C,
                "and re-reading the stored line gives 52.0 again, so the override is "
                        + "reversible by switching it off rather than by editing anything");

        check(!LinearConfig.promoteForBoost(null, true), "a null config is not a crash");
    }

    private static String slurp(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } finally {
            in.close();
        }
    }

    // ----------------------------------------------------------------- output

    private static void testReportOutput() throws Exception {
        section("the output files - which are the actual deliverable");

        // The trace: same first thirteen columns as ordinary telemetry, then the sweep's.
        int headerCols = csvFields(SweepReport.TRACE_HEADER).size();
        check(SweepReport.TRACE_HEADER.startsWith(CsvLogger.HEADER),
                "the trace begins with exactly the ordinary telemetry columns, so anything "
                        + "that reads a FanLab CSV can read a sweep trace");
        Sample s = new Sample();
        s.epochMs = 1700000000000L;
        s.isoLocal = "2026-09-05 21:00:00";
        s.adc = 1200;
        s.degC = 44.84;
        s.fanCtrl = 62;
        s.rgblevel = 3;
        s.ledStatus = 1;
        s.note = "reassert(was 70)";
        String row = SweepReport.traceRow(s, 62, 4, "step", 3, "step_start;audible", null);
        eq(csvFields(row).size(), headerCols,
                "a trace row has exactly as many columns as the header");
        check(row.indexOf("step_start;audible") >= 0, "the event column carries the markers");
        String row2 = SweepReport.traceRow(s, 62, 4, "step", 3, "", null);
        List<String> f2 = csvFields(row2);
        eq(f2.size(), headerCols, "so does a row with no event");
        check(f2.get(headerCols - 1).length() == 0
                        && f2.get(headerCols - 2).length() == 0
                        && f2.get(headerCols - 3).length() == 0,
                "the DLPC columns are blank on rows where no reading was taken - a stale "
                        + "value repeated down a column is indistinguishable from a fresh one");

        // Numbers must never be written as NaN, which is not JSON.
        check("null".equals(Json.num(Double.NaN, 2)), "NaN is written as JSON null");
        check("null".equals(Json.num(Double.POSITIVE_INFINITY, 2)), "so is infinity");
        check("43.00".equals(Json.num(43.0, 2)), "and an ordinary number keeps its decimals");
        check("-0.50".equals(Json.num(-0.5, 2)), "including negatives");
        check("\"a\\\"b\\nc\"".equals(Json.escape("a\"b\nc")), "quotes and newlines escape");

        // A whole report from a real (simulated) run.
        RunResult r = runSweep(0, 60.0, 3 * 3600, -1);
        r.engine.mark("audible", 1234L);
        SweepReport.Meta m = new SweepReport.Meta();
        m.appVersion = "1.0-plain";
        m.packageName = "com.daleygames.fanlab";
        m.uid = 10123;
        m.model = "SCN350";
        m.firmware = "1.7.1";
        m.ambientNote = "room approximately 22 C, entered on the projector";
        m.traceFile = "trace_1700000000.csv";
        String json = SweepReport.sweepJson(r.engine, m, 1700000000000L, 5400L);
        check(jsonBalanced(json), "the sweep report is structurally valid JSON");
        check(!hasBareToken(json, "NaN") && !hasBareToken(json, "Infinity"),
                "and contains no bare NaN or Infinity token anywhere");
        check(json.indexOf("\"format\": \"fanlab-sweep-1\"") >= 0
                        || json.indexOf("\"format\":\"fanlab-sweep-1\"") >= 0,
                "it declares its format version first, so the analysis knows what it has");
        check(json.indexOf("ro.product.model") >= 0 && json.indexOf("SCN350") >= 0,
                "the device is identified");
        check(json.indexOf("room approximately 22 C") >= 0,
                "the ambient note is carried - it is the biggest thing we cannot measure");
        check(json.indexOf("\"ceiling_c\": 58.0") >= 0,
                "the ceiling that was actually in force is recorded with the data");
        check(json.indexOf("\"stock_controller_disabled\": false") >= 0,
                "and it records that the stock controller was never disabled");
        check(json.indexOf("\"t_inf_c\"") >= 0 && json.indexOf("\"tau_s\"") >= 0
                        && json.indexOf("\"residual_rms_c\"") >= 0,
                "every step carries T_inf, tau and the residual, so the analysis can judge "
                        + "how much to trust each point");
        check(json.indexOf("\"kind\": \"audible\"") >= 0,
                "the audibility marker is in the file");

        // The loud part: an unreadable DLPC must be shouted about, not quietly omitted.
        check(json.indexOf("DLPC SYSTEM TEMPERATURE (D6h) UNAVAILABLE") >= 0,
                "an unreadable DLPC temperature produces a loud top-level warning rather "
                        + "than a quietly missing column");
        check(json.indexOf("\"available\": false") >= 0,
                "  and a machine-readable flag beside it");
        check(json.indexOf("cannot honestly be called safe for the DMD") >= 0,
                "  and the warning says why it matters: the 75 C shutdown watches the LED, "
                        + "the DMD's limit is an array temperature nobody can measure, and "
                        + "the relation between them is an unestablished board constant");

        // With a reading attached, the warning must go away and the value appear.
        PicoReg.Reading good = new PicoReg.Reading();
        good.status = PicoReg.STATUS_OK;
        good.degC = 41.0;
        good.rawWord = 0x29;
        good.provisional = true;
        good.source = "sysfs";
        good.reason = "read back from the node";
        for (int i = 0; i < r.engine.steps().size(); i++) {
            r.engine.steps().get(i).dlpc = good;
        }
        String json2 = SweepReport.sweepJson(r.engine, m, 1700000000000L, 5400L);
        check(jsonBalanced(json2), "still valid JSON with readings attached");
        check(json2.indexOf("DLPC SYSTEM TEMPERATURE (D6h) UNAVAILABLE") < 0,
                "the warning disappears once a reading exists");
        check(json2.indexOf("\"available\": true") >= 0, "  and availability flips");
        check(json2.indexOf("\"raw_word\": \"0x0029\"") >= 0,
                "  with the raw word kept so the units can be reinterpreted offline");

        // A run with no markers must say the human half is still missing.
        RunResult bare = runSweep(0, 60.0, 400, 300);
        String json3 = SweepReport.sweepJson(bare.engine, m, 0L, 300L);
        check(json3.indexOf("No audibility marker was recorded") >= 0,
                "a run with no keypresses says so: the thermally-safe floor can be "
                        + "computed, \"quiet enough\" cannot");
        check(json3.indexOf("The run did not complete") >= 0,
                "and an aborted run says that too, together with what survived");

        // VERIFY's report.
        HoldSession h = new HoldSession(62, 3, 0L, 1000L);
        h.tick(1000L, 44.0);
        h.check(HoldSession.ABOUT_PATTERN, HoldSession.VERDICT_SHARP, 2000L, 3000L);
        h.check(HoldSession.ABOUT_FAN, HoldSession.VERDICT_QUIET, 3000L, 4000L);
        h.stop("user");
        String vj = SweepReport.verifyJson(h, m, 5000L, 4L);
        check(jsonBalanced(vj), "the verify report is structurally valid JSON");
        check(!hasBareToken(vj, "NaN"), "with no bare NaN");
        check(vj.indexOf("\"held_duty\": 62") >= 0, "it records the duty that was held");
        check(vj.indexOf("\"verdict\": \"sharp\"") >= 0
                        && vj.indexOf("\"verdict\": \"quiet\"") >= 0,
                "and every verdict the person in the room gave");
        check(vj.indexOf("\"kind\": \"verify\"") >= 0, "and says which kind of run it was");
        check(vj.indexOf("\"steady_run\": false") >= 0,
                "a hold with no closed-loop phase says so outright, so a reader can tell"
                + " \"the fan sat still\" from \"nobody asked\"");
        check(vj.indexOf("steady_verdict") < 0,
                "  and carries none of the steady fields it has no numbers for");

        HoldSession hsx = new HoldSession(40, 3, 0L, 0L);
        hsx.beginSteady(CurveConfig.preset(0), 120, 40, 0L);
        for (int t = 1; t <= 120; t++) {
            hsx.tick(t * 1000L, 52.0);
        }
        String sj = SweepReport.verifyJson(hsx, m, 5000L, 120L);
        check(jsonBalanced(sj), "a report with a steady phase is still valid JSON");
        check(!hasBareToken(sj, "NaN"), "  with no bare NaN");
        check(sj.indexOf("\"steady_run\": true") >= 0, "  and says the phase ran");
        check(sj.indexOf("\"steady_verdict\": \"steady\"") >= 0,
                "  carrying the verdict a person will read first");
        check(sj.indexOf("\"judged_reversals\": 0") >= 0,
                "  and the reversal count, which is what separates settling from hunting");
        check(sj.indexOf("\"judged_span\": 0") >= 0, "  and how far the duty travelled");

        // The names.
        check("trace_1700000000.csv".equals(SweepReport.traceName(1700000000L, false)),
                "trace file name matches the specification");
        check("sweep_1700000000.json".equals(SweepReport.reportName(1700000000L, false)),
                "report file name matches the specification");

        // Whole-file fan-out, which is what puts the report on a USB stick.
        File tmp = File.createTempFile("fanlab-out", "");
        tmp.delete();
        File a = new File(tmp, "a");
        File b = new File(tmp, "b");
        List<File> dirs = new ArrayList<File>();
        dirs.add(a);
        dirs.add(b);
        dirs.add(null);
        try {
            List<String> written = CsvLogger.writeWhole(dirs, "sweep_1.json", json);
            eq(written.size(), 2, "the report is written to every destination");
            check(read(new File(a, "sweep_1.json")).equals(json), "  and lands intact");
            String again = "{\"second\":true}\n";
            CsvLogger.writeWhole(dirs, "sweep_1.json", again);
            check(read(new File(b, "sweep_1.json")).equals(again),
                    "  and a rewrite replaces it rather than appending, so the file on the "
                            + "stick is always a complete document");
            eq(CsvLogger.writeWhole(null, "x", "y").size(), 0, "a null list writes nothing");

            // A trace logger with the sweep header.
            CsvLogger t = new CsvLogger("trace_1.csv", SweepReport.TRACE_HEADER);
            t.setDirs(dirs);
            t.append(row);
            t.close();
            String got = read(new File(a, "trace_1.csv"));
            check(got.startsWith(SweepReport.TRACE_HEADER),
                    "the trace file carries the sweep header, not the telemetry one");
            eq(countLines(got), 2, "  header plus the row");
        } finally {
            rmrf(tmp);
        }
    }

    /** Split a CSV line on top-level commas, respecting the quoting CsvLogger.q produces. */
    private static List<String> csvFields(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuote = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** Braces and brackets balance, and never go negative, outside of string literals. */
    private static boolean jsonBalanced(String s) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inQuote = false;
                }
                continue;
            }
            if (c == '"') {
                inQuote = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0 && !inQuote;
    }

    /** True if {@code token} appears outside any string literal, i.e. as a JSON value. */
    private static boolean hasBareToken(String s, String token) {
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inQuote = false;
                }
                continue;
            }
            if (c == '"') {
                inQuote = true;
            } else if (s.startsWith(token, i)) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------- linear

    /**
     * Measured LED rise above ambient in Presentation, duty by duty -- the same table
     * {@code tools/CurveSim.java} runs on.
     *
     * Interpolated rather than fitted to a line on purpose. The plant is roughly ten times
     * more responsive at duty 35 than at 80 (0.60 against 0.06 C per duty point), and an
     * earlier straight-line model was three times too gentle exactly where the quiet end
     * operates, which made every ramp look stable.
     */
    private static final int[] RISE_DUTY = {30, 35, 40, 45, 50, 55, 60, 70, 83};
    private static final double[] RISE_PRES =
            {33.76, 29.92, 26.91, 24.9, 23.7, 22.3, 21.5, 20.2, 19.4};

    /**
     * Drive a VERIFY steady phase against the same two-pole plant the hunting check uses.
     *
     * The plant starts at the equilibrium for {@code startDuty} unless {@code fromCold},
     * in which case it starts at ambient and warms -- which is what a settling transient
     * looks like and is the thing the judged tail exists to exclude.
     */
    private static HoldSession runSteady(CurveConfig cfg, double ambient, int startDuty,
                                         int seconds, double noise, boolean fromCold) {
        HoldSession h = new HoldSession(startDuty, 3, 0L, 0L);
        h.beginSteady(cfg, seconds, h.duty(), 0L);
        java.util.Random rng = new java.util.Random(7);
        double total0 = fromCold ? 0.0 : presentationRise(h.duty());
        double fast = total0 * 0.70;
        double slow = total0 * 0.30;
        final double tauFast = 230.0;
        final double tauSlow = 1500.0;
        for (int t = 1; t <= seconds; t++) {
            double measured = ambient + fast + slow + rng.nextGaussian() * noise;
            int d = h.tick(t * 1000L, measured);
            double total = presentationRise(d);
            fast += (total * 0.70 - fast) * (1.0 - Math.exp(-1.0 / tauFast));
            slow += (total * 0.30 - slow) * (1.0 - Math.exp(-1.0 / tauSlow));
        }
        return h;
    }

    private static void testHoldSteadyPhase() {
        section("VERIFY steady phase: does the fan sit still once the curve is driving");

        // ---- the gate FanService reads to decide whether the LED drive may stay on ----
        HoldSession g = new HoldSession(50, 3, 0L, 0L);
        eq(g.phase(), HoldSession.PHASE_HOLD, "a session starts in the hold phase");
        check(!g.closedLoop(), "a pinned hold is NOT closed-loop, so the LED drive is dropped");
        check(g.steady() == null, "and there are no steady statistics yet");
        check("too_short".equals(g.verdict()), "nor a verdict");
        g.beginSteady(CurveConfig.preset(0), 600, 50, 0L);
        eq(g.phase(), HoldSession.PHASE_STEADY, "beginSteady moves it on");
        check(g.closedLoop(), "now the curve is driving, so the drive may stay applied -- "
                + "the override's safety case is that the app is cooling the machine, and here it is");
        g.stop("user");
        check(!g.closedLoop(), "a finished session is never closed-loop");

        // ---- guards on beginSteady ----
        HoldSession c1 = new HoldSession(50, 3, 0L, 0L);
        c1.beginSteady(CurveConfig.preset(0), 5, 50, 0L);
        eq(c1.steady().seconds, 60, "a silly short phase is raised to 60 s");
        HoldSession c2 = new HoldSession(50, 3, 0L, 0L);
        c2.beginSteady(CurveConfig.preset(0), 99999, 50, 0L);
        eq(c2.steady().seconds, 3600, "and an endless one is cut to an hour");
        HoldSession c3 = new HoldSession(50, 3, 0L, 0L);
        c3.beginSteady(null, 600, 50, 0L);
        check(c3.steady() == null, "no curve, no phase -- it does not half-start");
        eq(c3.phase(), HoldSession.PHASE_HOLD, "  and it stays in the hold phase");
        HoldSession c4 = new HoldSession(50, 3, 0L, 0L);
        c4.stop("user");
        c4.beginSteady(CurveConfig.preset(0), 600, 50, 0L);
        check(c4.steady() == null, "a finished session cannot be restarted into a steady phase");

        // ---- the target: the fan does not move ----
        CurveConfig quiet = CurveConfig.preset(0);
        HoldSession st = runSteady(quiet, 24.0, 38, 900, 0.03, false);
        check("steady".equals(st.verdict()), "Quiet resting on its shelf: the fan never moves"
                + " (verdict \"" + st.verdict() + "\", " + st.steady().judgedChanges + " judged changes)");
        eq(st.steady().judgedChanges, 0, "  zero changes in the judged half");
        eq(st.steady().judgedReversals, 0, "  and nothing to reverse");
        check(st.finished(), "  the phase ends itself");
        check("steady_complete".equals(st.endReason()),
                "  saying why (\"" + st.endReason() + "\")");

        eq(st.steady().maxTick, 0, "  and no tick moved it at all");

        // ---- settling is not hunting, and a change count alone cannot tell them apart ----
        //
        // Warming from cold is the honest worst case: over half an hour the duty climbs
        // twenty-odd points, and because the slow pole is still arriving at the end, the
        // judged half is NOT quiet. That is correct and is why the verdict leans on
        // direction rather than on the tail alone -- a settle is overwhelmingly
        // one-directional however long it takes.
        HoldSession se = runSteady(quiet, 24.0, 35, 1800, 0.03, true);
        check(se.steady().changes > 0, "warming up from cold, the duty moves ("
                + se.steady().changes + " changes over the whole phase)");
        check(se.steady().reversals * 4 <= se.steady().changes,
                "  but it is overwhelmingly one-directional -- " + se.steady().reversals
                + " reversals against " + se.steady().changes + " changes -- which is what a"
                + " settle looks like and a hunt does not");
        check(!"hunting".equals(se.verdict()),
                "  so it is not called hunting (\"" + se.verdict() + "\")");

        // ---- the judged tail, driven directly so the window is exact ----
        // No plant here on purpose: this is testing the windowing arithmetic, and a plant
        // would make the answer depend on how fast the plant happens to settle.
        HoldSession jt = new HoldSession(40, 3, 0L, 0L);
        jt.beginSteady(quiet, 600, 40, 0L);
        for (int t = 1; t <= 600; t++) {
            // swing it for the first two hundred seconds, then hold it dead flat, so the
            // judged half beginning at 300 s sees a machine that has finished moving
            double c = t <= 200 ? ((t / 20) % 2 == 0 ? 50.0 : 54.0) : 52.0;
            jt.tick(t * 1000L, c);
        }
        check(jt.steady().changes > 0, "a swinging temperature moves the fan ("
                + jt.steady().changes + " changes)");
        check(jt.steady().reversals > 0, "  and turns it round (" + jt.steady().reversals + ")");
        eq(jt.steady().judgedChanges, 0,
                "  yet the judged half, held flat, counts none of it -- the tail is what"
                + " stops an arrival being read as a hunt");
        check("steady".equals(jt.verdict()),
                "  so the verdict is steady (\"" + jt.verdict() + "\")");

        // ---- a machine that is still drifting cannot be reported as well-behaved ----
        //
        // This is the hole the reversal count leaves on its own. A light engine still
        // warming ratchets its duty one way and never turns round, so it scores zero
        // reversals -- identical to a curve that is genuinely sitting still. Reading that
        // as "no hunting" is wrong: the fan has not yet had the chance to hunt. So drift is
        // measured, and while it is above SETTLED_C_PER_HOUR no quiet verdict is offered.
        HoldSession dr = new HoldSession(40, 3, 0L, 0L);
        dr.beginSteady(quiet, 600, 40, 0L);
        for (int t = 1; t <= 600; t++) {
            dr.tick(t * 1000L, 50.0 + 6.0 * t / 600.0);      // a steady climb, no wobble
        }
        check(dr.steady().changes > 0, "a warming machine moves the duty ("
                + dr.steady().changes + " changes)");
        eq(dr.steady().judgedReversals, 0, "  and never turns round, exactly like a good curve");
        check(Math.abs(dr.steady().trendCPerHour()) >= HoldSession.SETTLED_C_PER_HOUR,
                "  but the drift is measured and is well over the threshold ("
                + Sample.fmt1(dr.steady().trendCPerHour()) + " C/h)");
        check("unsettled".equals(dr.verdict()),
                "  so no opinion is offered rather than a reassuring one (\""
                + dr.verdict() + "\")");

        // ...but drift must never HIDE a hunt. A reversal is proof whenever it happens.
        HoldSession dh = new HoldSession(40, 3, 0L, 0L);
        dh.beginSteady(quiet, 600, 40, 0L);
        for (int t = 1; t <= 600; t++) {
            dh.tick(t * 1000L, 50.0 + 6.0 * t / 600.0 + (t % 40 < 20 ? -1.2 : 1.2));
        }
        check(dh.steady().judgedReversals > 0, "the same climb with a wobble turns the fan round ("
                + dh.steady().judgedReversals + " reversals)");
        check(!"unsettled".equals(dh.verdict()),
                "  and that is reported, not suppressed by the drift (\"" + dh.verdict() + "\")");

        // ---- the 2026-09-08 measurement, as a regression test ----
        //
        // This row rested 3.7 duty points quieter than the shipped one, cleared the noise
        // ceiling, cleared the 60 C trip by the same margin and scored 84 of 84 in
        // CurveSim. On the hardware it moved nine times in twelve minutes where the shipped
        // row moved zero, because it steepens the segment the machine rests on from 3.0 to
        // 4.4 duty/C and the light engine wanders 0.6-0.9 C at a fixed duty. Nothing on the
        // device could see that before this phase existed. Now it can, so it is pinned here.
        CurveConfig rejected = CurveConfig.decode(
                "v1,47,51,55,60,66,70,30,38,40,50,68,83,30,38,40,50,68,83,30,38,40,62,76,83,"
                + "0.8,0.25,0.12,10,30,83,1,70,2.0,62,1.5");
        HoldSession hunt = runSteady(rejected, 27.0, 47, 1200, 0.45, false);
        HoldSession keep = runSteady(quiet, 27.0, 40, 1200, 0.45, false);
        check(hunt.steady().reversals > keep.steady().reversals,
                "the rejected row turns the fan round more often than the shipped one ("
                + hunt.steady().reversals + " reversals against " + keep.steady().reversals
                + ") on the same plant, same noise, same seed");
        check(hunt.steady().judgedSpan() >= keep.steady().judgedSpan(),
                "  and travels at least as far (" + hunt.steady().judgedSpan()
                + " duty points against " + keep.steady().judgedSpan() + ")");
        check(!"steady".equals(hunt.verdict()),
                "  so it is not reported as steady (\"" + hunt.verdict() + "\")");

        // ---- the statistics are self-consistent ----
        HoldSession.Steady k = keep.steady();
        check(k.samples > 0, "every tick is counted");
        check(k.judgedSamples > 0 && k.judgedSamples < k.samples,
                "the judged tail is a proper subset of the phase (" + k.judgedSamples
                + " of " + k.samples + ")");
        check(k.judgedChanges <= k.changes, "judged changes cannot exceed total changes");
        check(k.judgedReversals <= k.reversals, "nor judged reversals total reversals");
        check(k.loDuty <= k.hiDuty, "the duty range is the right way round");
        check(k.maxC >= k.minC, "so is the temperature range");
        eq(k.judgedFromSec, k.seconds / 2, "the judged half starts half way through");
    }

    private static double presentationRise(int duty) {
        if (duty <= RISE_DUTY[0]) {
            return RISE_PRES[0];
        }
        if (duty >= RISE_DUTY[RISE_DUTY.length - 1]) {
            return RISE_PRES[RISE_PRES.length - 1];
        }
        for (int i = 1; i < RISE_DUTY.length; i++) {
            if (duty <= RISE_DUTY[i]) {
                double f = (duty - RISE_DUTY[i - 1])
                        / (double) (RISE_DUTY[i] - RISE_DUTY[i - 1]);
                return RISE_PRES[i - 1] + f * (RISE_PRES[i] - RISE_PRES[i - 1]);
            }
        }
        return RISE_PRES[RISE_PRES.length - 1];
    }

    /**
     * The controller is a pure function of temperature, duty and the clock, so it is driven
     * directly here rather than through anything that needs a device.
     *
     * The clock never starts at 0 in these tests. Both controllers use 0 to mean "no
     * previous step" -- the same convention {@link FanCurve} has always used for its slew --
     * and on hardware the argument is {@code elapsedRealtime()}, which is only 0 at the
     * instant of boot, minutes before the service exists.
     */
    private static void testLinearController() {
        section("linear: walks toward the ceiling and never stops walking");
        LinearConfig cfg = new LinearConfig();
        eq((int) Math.round(cfg.ceilingC * 10), 520, "the ceiling defaults to 52.0 C");
        eq((int) cfg.upStepMs, 5000, "rising, a step every 5 s");
        eq((int) cfg.downStepMs, 60000, "falling near the ceiling, every 60 s");
        eq((int) cfg.downFastMs, 10000, "falling with headroom, every 10 s");
        eq((int) Math.round(cfg.nearC * 10), 10, "and near means within 1.0 C");
        eq(cfg.trendWindowS, 90, "and the trend gate fits a slope over 90 s");
        check(cfg.downStepMs > cfg.upStepMs, "the decay is slower than the attack, which "
                + "is the asymmetry the 3-point swing was measured on");
        check(cfg.downFastMs <= cfg.downStepMs, "and the headroom clock is the fast one");
        eq(cfg.minDuty, 30, "the floor is 30");
        eq(cfg.maxDuty, 83, "the ceiling duty is 83");

        // ---- direction ----
        FanLinear f = new FanLinear();
        f.resync(50);
        long ms = 10000L;
        f.step(cfg, 52.0, true, ms);                     // the look-before-moving tick
        ms += cfg.upStepMs;
        eq(f.step(cfg, 52.5, true, ms), 51, "above the ceiling, one point up");
        ms += cfg.downStepMs;
        eq(f.step(cfg, 51.0, true, ms), 50, "below it, one point down");
        ms += cfg.upStepMs;
        // Exactly on the ceiling steps UP. With no resting state there is no "on the
        // boundary and therefore fine", and if a branch has to own the exact value it
        // should be the one that cools.
        eq(f.step(cfg, 52.0, true, ms), 51, "exactly at the ceiling, up");

        // ---- it never holds ----
        // The property the whole design rests on: there is no third branch, so every
        // decision moves the duty unless it is clamped. A hold zone would stop the walk
        // above the quietest duty that holds the ceiling, which is what it exists to find.
        FanLinear nh = new FanLinear();
        nh.resync(55);
        long t = 10000L;
        nh.step(cfg, 52.0, true, t);
        int held = 0;
        int decisions = 0;
        int prev = nh.baseDuty();
        for (int i = 0; i < 200; i++) {
            // The slowest of the three intervals, so a decision is guaranteed to be due
            // whichever branch this temperature selects.
            t += cfg.downStepMs;
            // A temperature that wanders either side of the ceiling without ever settling
            // on a duty: the point is that no input value produces a hold.
            double c = 52.0 + (i % 7 - 3) * 0.3;
            nh.step(cfg, c, true, t);
            int now = nh.baseDuty();
            decisions++;
            if (now == prev && now > cfg.minDuty && now < cfg.maxDuty) {
                held++;
            }
            prev = now;
        }
        eq(decisions, 200, "200 decisions were taken");
        eq(held, 0, "and not one of them held the duty still away from a limit");

        // ---- the step interval is respected ----
        FanLinear r = new FanLinear();
        r.resync(50);
        long base = 10000L;
        r.step(cfg, 60.0, true, base);
        int before = r.baseDuty();
        for (long d = 1; d < cfg.upStepMs; d++) {
            r.step(cfg, 60.0, true, base + d);
        }
        eq(r.baseDuty(), before, "4999 calls inside one step interval move nothing at all");
        eq(r.step(cfg, 60.0, true, base + cfg.upStepMs), before + 1,
                "and the call that crosses it moves exactly one point");

        // ---- and WHICH interval is chosen by where the temperature is ----
        // This is the fix for the one thing about this mode the owner rejected by ear. A
        // single 5 s interval measured a 14-point settled swing and he heard it climbing;
        // 60 s measured 3 and he did not notice it. Descending is therefore slow near the
        // ceiling, where precision is the whole job, and fast below it, where dropping
        // quickly cannot overshoot anything. Asserted directly, because the schedule is
        // invisible in the settled behaviour these tests otherwise check.
        //
        // 51.0 C is exactly the edge with the default 1.0 C band: it is NOT more than
        // nearC below the ceiling, so it takes the slow clock. The boundary belongs to the
        // careful side.
        FanLinear sched = new FanLinear();
        sched.resync(50);
        long sb = 10000L;
        sched.step(cfg, 51.0, true, sb);
        eq(sched.step(cfg, 51.0, true, sb + cfg.downFastMs), 50,
                "one degree below the ceiling, the fast interval is not enough");
        eq(sched.step(cfg, 51.0, true, sb + cfg.downStepMs), 49,
                "it takes the slow one, and then moves a single point");

        FanLinear far = new FanLinear();
        far.resync(50);
        long fb = 10000L;
        far.step(cfg, 45.0, true, fb);
        eq(far.step(cfg, 45.0, true, fb + cfg.downFastMs), 49,
                "seven degrees below it, the fast interval is the one that applies");

        // The interval is re-read every tick rather than latched at the start of a
        // descent, so a temperature drifting back up towards the ceiling slows the walk
        // before it arrives rather than after.
        FanLinear drift = new FanLinear();
        drift.resync(50);
        long db = 10000L;
        drift.step(cfg, 45.0, true, db);
        eq(drift.step(cfg, 45.0, true, db + cfg.downFastMs), 49, "a fast step with headroom");
        db += cfg.downFastMs;
        eq(drift.step(cfg, 51.5, true, db + cfg.downFastMs), 49,
                "and then, close to the ceiling, the same gap buys nothing");

        // A long gap buys one step, not one per interval that elapsed. A catch-up burst
        // after a resume would be the audible jump the walk exists to avoid.
        FanLinear g = new FanLinear();
        g.resync(40);
        g.step(cfg, 60.0, true, 10000L);
        eq(g.step(cfg, 60.0, true, 10000L + 3600000L), 41,
                "an hour-long suspend buys one duty point, not seven hundred");

        // ---- the limits, and saturation on the edge ----
        FanLinear hot = new FanLinear();
        hot.resync(82);
        long h = 10000L;
        hot.step(cfg, 60.0, true, h);
        h += cfg.upStepMs;
        eq(hot.step(cfg, 60.0, true, h), 83, "it reaches the ceiling duty");
        check(!hot.saturated(), "arriving at 83 is not saturation: it had a point to give");
        int edges = 0;
        boolean was = hot.saturated();
        for (int i = 0; i < 50; i++) {
            h += cfg.upStepMs;
            eq(hot.step(cfg, 60.0, true, h), 83, "and stays there");
            if (hot.saturated() != was) {
                edges++;
                was = hot.saturated();
            }
        }
        check(hot.saturated(), "83 and still too hot is out of authority");
        eq(edges, 1, "which becomes true once and stays true, so the log notes it once");
        // ...and clears as soon as it has somewhere to go, so a note is not left standing.
        // 40 C is far below the ceiling, so this is the headroom clock, not the attack one.
        h += cfg.downFastMs;
        eq(hot.step(cfg, 40.0, true, h), 82, "a cool reading walks it back down");
        check(!hot.saturated(), "and clears the saturation");

        FanLinear cold = new FanLinear();
        cold.resync(31);
        long k = 10000L;
        cold.step(cfg, 40.0, true, k);
        k += cfg.downFastMs;
        eq(cold.step(cfg, 40.0, true, k), 30, "it reaches the floor");
        check(!cold.saturated(), "arriving at 30 is not saturation either");
        k += cfg.downFastMs;
        eq(cold.step(cfg, 40.0, true, k), 30, "and stays there");
        check(cold.saturated(), "30 and still too cold is out of authority");

        // ---- engine off ----
        FanLinear idle = new FanLinear();
        idle.resync(45);
        eq(idle.step(cfg, 52.0, false, 10000L), cfg.idleDuty,
                "the light engine off commands idleDuty");
        check(!idle.saturated(), "and is never saturation");
        eq(idle.baseDuty(), 45,
                "the integrator keeps its duty, so coming back on does not re-walk from 10");

        // ---- resync adopts rather than jumping ----
        FanLinear a = new FanLinear();
        a.resync(59);
        eq(a.baseDuty(), 59, "resync adopts the duty actually on the node");
        a.resync(-1);
        eq(a.baseDuty(), FanIo.FAIL_SAFE_DUTY,
                "an unreadable fan_ctrl adopts 83: not knowing where the fan is never "
                        + "lowers it");

        // ---- fail safe ----
        FanLinear bad = new FanLinear();
        bad.resync(40);
        bad.step(cfg, 52.0, true, 10000L);
        eq(bad.step(cfg, Double.NaN, true, 20000L), FanIo.FAIL_SAFE_DUTY,
                "a NaN reading -> 83");
        eq(bad.step(cfg, Double.POSITIVE_INFINITY, true, 30000L), FanIo.FAIL_SAFE_DUTY,
                "an infinite reading -> 83");
        eq(bad.step(null, 52.0, true, 40000L), FanIo.FAIL_SAFE_DUTY,
                "a missing config -> 83");
        // A tick with no usable measurement is not a decision, so it must not consume one
        // and must not move the duty. Otherwise a sensor that failed for a minute would
        // leave the walk twelve points from where it was last justified in being.
        eq(bad.baseDuty(), 40, "and none of them moved the integrator");
        eq(bad.step(cfg, 60.0, true, 45000L), 41,
                "the first good reading afterwards steps once, from where it was");

        // ---- the SoC guard is additive on top, and can only raise ----
        //
        // Passing the curve now also seeds the walk, so the first tick adopts curve(52.0)
        // rather than the 40 resync handed it. That is the point of seeding and it is
        // asserted on its own below; here it only means the expected duties come from the
        // curve, so they are read off it rather than written as literals.
        CurveConfig guard = new CurveConfig();
        int seedAt52 = guard.dutyAt(CurveConfig.PROFILE_HIGH, 52.0);
        FanLinear sg = new FanLinear();
        sg.resync(40);
        sg.step(cfg, guard, CurveConfig.PROFILE_HIGH, 52.0, 60.0, true, 10000L);
        eq(sg.baseDuty(), seedAt52, "the first tick seeds from the curve, not from resync");
        eq(sg.step(cfg, guard, CurveConfig.PROFILE_HIGH, 52.0, 60.0, true, 15000L),
                seedAt52 + 1, "a cool die adds nothing");
        eq(sg.guardBoost(), 0, "and reports no boost");
        int guarded = sg.step(cfg, guard, CurveConfig.PROFILE_HIGH, 52.0, 74.0, true, 20000L);
        check(guarded > sg.baseDuty(), "a hot die raises the duty above the walk's own");
        check(sg.guardBoost() > 0, "and says by how much");
        // Never downward. A monitoring sensor must not be able to argue down the sensor the
        // safety case rests on.
        boolean everLower = false;
        FanLinear mono = new FanLinear();
        mono.resync(50);
        long q = 10000L;
        mono.step(cfg, guard, CurveConfig.PROFILE_HIGH, 52.0, 50.0, true, q);
        for (int soc = 40; soc <= 100; soc++) {
            q += cfg.downStepMs;
            int withGuard = mono.step(cfg, guard, CurveConfig.PROFILE_HIGH, 52.0, soc, true, q);
            if (withGuard < mono.baseDuty()) {
                everLower = true;
            }
        }
        check(!everLower, "at no SoC temperature does the guard lower the commanded duty");

        // ---- seeded from the curve, which is where the walk should start ----
        //
        // Measured justification, not taste: over thirty-six hours of field log the curve
        // predicted the settled operating point to within 0.39 duty points. Starting the
        // integrator there starts it at the answer, which deletes the multi-minute descent
        // that was the worst thing about this mode -- on hardware the accepted 60 s decay
        // took about seven minutes to come down from 48 to 42 and had not finished.
        CurveConfig seedCurve = new CurveConfig();
        for (int seedC = 46; seedC <= 60; seedC++) {
            FanLinear sd = new FanLinear();
            sd.resync(83);                       // handed the fail-safe duty, the worst case
            sd.step(cfg, seedCurve, CurveConfig.PROFILE_HIGH, seedC, Double.NaN, true, 10000L);
            eq(sd.baseDuty(), seedCurve.dutyAt(CurveConfig.PROFILE_HIGH, seedC),
                    "seeded to the curve's answer at " + seedC + " C, not the 83 it was given");
        }

        // It seeds once, not on every tick: after the first, the walk is the walk. If this
        // regressed the mode would silently become the curve with extra steps.
        FanLinear once = new FanLinear();
        once.resync(83);
        long sq = 10000L;
        once.step(cfg, seedCurve, CurveConfig.PROFILE_HIGH, 60.0, Double.NaN, true, sq);
        int afterSeed = once.baseDuty();
        eq(afterSeed, seedCurve.dutyAt(CurveConfig.PROFILE_HIGH, 60.0), "seeded at 60 C");
        sq += cfg.upStepMs;
        once.step(cfg, seedCurve, CurveConfig.PROFILE_HIGH, 60.0, Double.NaN, true, sq);
        eq(once.baseDuty(), afterSeed + 1,
                "and thereafter walks by one, rather than re-seeding to the same value");
        // Walking AWAY from the curve is the whole reason the mode exists: a unit or a room
        // the plant table is wrong about must be able to pull it off the curve's answer.
        sq += cfg.upStepMs;
        once.step(cfg, seedCurve, CurveConfig.PROFILE_HIGH, 60.0, Double.NaN, true, sq);
        check(once.baseDuty() > seedCurve.dutyAt(CurveConfig.PROFILE_HIGH, 60.0),
                "and is free to leave the curve's answer behind");

        // A resync re-arms the seed, because a resync means something else moved the fan.
        once.resync(83);
        once.step(cfg, seedCurve, CurveConfig.PROFILE_HIGH, 52.0, Double.NaN, true, sq + 99999L);
        eq(once.baseDuty(), seedCurve.dutyAt(CurveConfig.PROFILE_HIGH, 52.0),
                "a later resync seeds again, at the temperature current then");

        // With no curve to read there is nothing to seed from, so the adopted duty stands.
        FanLinear noCurve = new FanLinear();
        noCurve.resync(46);
        noCurve.step(cfg, 52.0, true, 10000L);
        eq(noCurve.baseDuty(), 46, "with no curve passed, the resync value is kept");

        // The seed is clamped by the config's own limits, like every other duty here.
        LinearConfig tight = new LinearConfig();
        tight.minDuty = 44;
        tight.maxDuty = 46;
        tight.sanitise();
        FanLinear clamped = new FanLinear();
        clamped.resync(83);
        clamped.step(tight, seedCurve, CurveConfig.PROFILE_HIGH, 30.0, Double.NaN, true, 10000L);
        check(clamped.baseDuty() >= tight.minDuty && clamped.baseDuty() <= tight.maxDuty,
                "a seed below the floor is clamped up to it, not obeyed");

        // ---- the trend gate: do not push while it is already coming down ----
        //
        // This is the fix for windup, and it is the difference between settling and sailing
        // past. Measured on hardware 2026-09-07: seeded to 38 with the ceiling at 52, the
        // ungated walk climbed to 53 while the thermistor had been falling for 36 seconds,
        // overshooting an equilibrium of 45. Swept against the two-pole plant over twelve
        // noise seeds in tools/LinearSim.java, the gate takes the peak of the approach at
        // 30 C ambient from 83 % to 62 % and cuts the settled swing at every ambient.
        LinearConfig gated = new LinearConfig();     // 90 s window by default
        LinearConfig ungated = new LinearConfig();
        ungated.trendWindowS = 0;
        ungated.sanitise();

        // Above the ceiling but falling steadily: the ungated walk keeps adding fan, the
        // gated one waits. Feed a clean ramp down, well clear of the noise floor.
        FanLinear gOn = new FanLinear();
        FanLinear gOff = new FanLinear();
        gOn.resync(50);
        gOff.resync(50);
        long gt = 10000L;
        double falling = 56.0;
        boolean everHeld = false;
        for (int i = 0; i < 200; i++) {
            gOn.step(gated, null, CurveConfig.PROFILE_HIGH, falling, Double.NaN, true, gt);
            gOff.step(ungated, null, CurveConfig.PROFILE_HIGH, falling, Double.NaN, true, gt);
            // trendHeld() reports the most recent DECISION, and decisions are five seconds
            // apart, so it has to be sampled every tick rather than read at the end.
            everHeld = everHeld || gOn.trendHeld();
            gt += 1000L;
            falling -= 0.01;                          // 0.01 C/s, far above the noise floor
        }
        check(gOff.baseDuty() > gOn.baseDuty(),
                "ungated keeps climbing while it cools (" + gOff.baseDuty()
                        + ") where gated holds off (" + gOn.baseDuty() + ")");
        check(everHeld, "and the gated one held steps back along the way");
        check(gOn.trendPerSec() < 0, "because it measured the temperature falling");

        // Symmetrically: below the ceiling but still climbing, do not give fan back.
        // The gate needs history before it can say anything -- fitTrend wants at least
        // half the window -- so the duty does drop for the first ~45 s. What must hold is
        // that it STOPS dropping once the trend is measurable, which is the property under
        // test. Measured from there, not from the cold start.
        // The ramp has to stay below the ceiling for the whole run, or the walk correctly
        // switches to attacking and the test measures the wrong thing. 0.005 C/s over 420 s
        // climbs 2.1 C from 45, so it never reaches 52 -- and it is still five times the
        // gate's own noise floor, so the trend is unambiguous.
        FanLinear rising2 = new FanLinear();
        rising2.resync(50);
        long rt = 10000L;
        double climbing = 45.0;
        for (int i = 0; i < 120; i++) {              // fill the window
            rising2.step(gated, null, CurveConfig.PROFILE_HIGH, climbing, Double.NaN, true, rt);
            rt += 1000L;
            climbing += 0.005;
        }
        int settled2 = rising2.baseDuty();
        for (int i = 0; i < 300; i++) {              // and now it must stop giving fan back
            rising2.step(gated, null, CurveConfig.PROFILE_HIGH, climbing, Double.NaN, true, rt);
            rt += 1000L;
            climbing += 0.005;
        }
        check(climbing < gated.ceilingC, "the ramp stayed below the ceiling throughout");
        eq(rising2.baseDuty(), settled2,
                "below the ceiling but warming, the gate holds the duty rather than dropping it");
        check(settled2 < 50, "having dropped only while it had no trend to go on");

        // The gate delays; it must never cap. A genuine sustained climb has to reach the
        // maximum, or an unreachable ceiling would silently under-cool.
        FanLinear hot2 = new FanLinear();
        hot2.resync(50);
        long ht = 10000L;
        for (int i = 0; i < 4000; i++) {
            hot2.step(gated, null, CurveConfig.PROFILE_HIGH, 60.0, Double.NaN, true, ht);
            ht += 1000L;
        }
        eq(hot2.baseDuty(), gated.maxDuty,
                "a sustained overshoot still reaches full authority: the gate waits, never caps");

        // With the gate off, behaviour is exactly the pre-gate controller.
        FanLinear plain2 = new FanLinear();
        plain2.resync(40);
        long pt = 10000L;
        plain2.step(ungated, null, CurveConfig.PROFILE_HIGH, 60.0, Double.NaN, true, pt);
        pt += ungated.upStepMs;
        eq(plain2.step(ungated, null, CurveConfig.PROFILE_HIGH, 60.0, Double.NaN, true, pt), 41,
                "trendWindowS 0 restores the ungated walk exactly");

        // ---- the mode plumbing ----
        check(Mode.writes(Mode.LINEAR), "LINEAR writes fan_ctrl");
        check(Mode.controls(Mode.LINEAR),
                "and is a temperature controller, so the stock ladder stands down for it");
        check(Mode.controls(Mode.CURVE), "as it does for CURVE");
        check(!Mode.controls(Mode.MANUAL),
                "but not for MANUAL, which has no temperature logic to supervise it");
        check(!Mode.controls(Mode.OFF), "nor for OFF");
        check(Mode.name(Mode.LINEAR).equals("LINEAR"), "and it has a name for the log");
        eq(Provenance.exclusive(Mode.LINEAR, false, "0", 100000L, 0L), 1,
                "a LINEAR row with the ladder down and no foreign write is exclusive");
        eq(Provenance.exclusive(Mode.MANUAL, false, "0", 100000L, 0L), 0,
                "a MANUAL row is not");
    }

    private static void testLinearConfigRoundTrip() {
        section("linear: config survives the trip, and repairs what it cannot accept");
        LinearConfig d = new LinearConfig();
        check(d.encode().equals("l3,52.0,5000,60000,10000,1.0,90,10,30,83"),
                "the defaults encode to the line the reply quotes  (got " + d.encode() + ")");
        check(LinearConfig.decode(d.encode()).encode().equals(d.encode()),
                "encode/decode round trips byte for byte");
        d.sanitise();
        check(d.encode().equals("l3,52.0,5000,60000,10000,1.0,90,10,30,83"),
                "and sanitise() is a no-op on them");

        LinearConfig e = new LinearConfig();
        e.ceilingC = 47.5;
        e.upStepMs = 3000L;
        e.downStepMs = 30000L;
        e.downFastMs = 6000L;
        e.nearC = 2.5;
        e.trendWindowS = 45;
        e.idleDuty = 12;
        e.minDuty = 35;
        e.maxDuty = 80;
        String line = e.encode();
        LinearConfig back = LinearConfig.decode(line);
        check(back.encode().equals(line), "an edited config round trips");
        eq((int) Math.round(back.ceilingC * 10), 475, "a fractional ceiling survives");
        eq((int) back.upStepMs, 3000, "the attack interval survives");
        eq((int) back.downStepMs, 30000, "the decay interval survives");
        eq((int) back.downFastMs, 6000, "the headroom interval survives");
        eq((int) Math.round(back.nearC * 10), 25, "and the band survives");
        eq(back.trendWindowS, 45, "and so does the trend window");
        eq(back.minDuty, 35, "the floor survives");
        eq(back.maxDuty, 80, "the ceiling survives");

        check(LinearConfig.decode(null).encode().equals(new LinearConfig().encode()),
                "a missing config falls back to the defaults");
        check(LinearConfig.decode("").encode().equals(new LinearConfig().encode()),
                "an empty config falls back to the defaults");
        check(LinearConfig.decode("l3,not,a,number,x,y,z,p,q,r").encode()
                        .equals(new LinearConfig().encode()),
                "an unparseable config falls back to the defaults");
        check(LinearConfig.decode("z9,1,2,3,4,5,6,7,8,9").encode()
                        .equals(new LinearConfig().encode()),
                "an unknown version falls back to the defaults");
        check(LinearConfig.decode("l3,52.0,5000").encode()
                        .equals(new LinearConfig().encode()),
                "a truncated line falls back to the defaults");

        // ---- l2 is still read, and does not silently reinstate the ungated walk ----
        // l2 predates the trend gate. The ungated walk was measured winding to 83 % on the
        // approach at 30 C ambient where the answer is 58, so a stored l2 must not quietly
        // turn the gate off -- it takes the default instead.
        LinearConfig l2 = LinearConfig.decode("l2,47.5,3000,30000,6000,2.5,12,35,80");
        eq((int) Math.round(l2.ceilingC * 10), 475, "an l2 ceiling is read");
        eq((int) l2.upStepMs, 3000, "and its three intervals");
        eq((int) l2.downStepMs, 30000, "all");
        eq((int) l2.downFastMs, 6000, "three");
        eq(l2.trendWindowS, 90, "but the trend gate takes the default rather than 0");
        check(l2.encode().startsWith("l3,"), "and it is re-encoded in the new format");

        // ---- the superseded single-interval line is still read, and read SAFELY ----
        // l1 existed while this mode was being tuned, and its one interval applied in both
        // directions -- which is the 14-point-swing configuration the owner rejected. So a
        // stored l1 maps onto the attack interval and lets the two decay intervals default,
        // rather than reinstating a symmetric 5 s walk without saying so.
        LinearConfig legacy = LinearConfig.decode("l1,47.5,3000,12,35,80");
        eq((int) Math.round(legacy.ceilingC * 10), 475, "an l1 ceiling is read");
        eq((int) legacy.upStepMs, 3000, "its single interval becomes the attack interval");
        eq((int) legacy.downStepMs, 60000,
                "and the decay takes the default 60 s rather than that interval");
        eq((int) legacy.downFastMs, 10000, "as does the headroom interval");
        eq(legacy.minDuty, 35, "the rest of the l1 line is read normally");
        eq(legacy.maxDuty, 80, "all of it");
        check(legacy.encode().startsWith("l3,"), "and it is re-encoded in the new format");

        // ---- the step interval is repaired, not accepted ----
        // This is the parameter the plant's measured lag bounds, so a value from outside
        // the range has to be clamped rather than obeyed. One point per second was measured
        // building a growing limit cycle; nothing should be able to store faster.
        LinearConfig fast = new LinearConfig();
        fast.upStepMs = 10L;
        fast.downStepMs = 10L;
        fast.downFastMs = 10L;
        fast.sanitise();
        eq((int) fast.upStepMs, (int) LinearConfig.MIN_STEP_MS,
                "10 ms is repaired up to the minimum");
        eq((int) fast.downStepMs, (int) LinearConfig.MIN_STEP_MS, "on every interval");
        eq((int) fast.downFastMs, (int) LinearConfig.MIN_STEP_MS, "all three of them");
        LinearConfig slow = new LinearConfig();
        slow.upStepMs = 999999999L;
        slow.downStepMs = 999999999L;
        slow.downFastMs = 999999999L;
        slow.sanitise();
        eq((int) slow.upStepMs, (int) LinearConfig.MAX_STEP_MS,
                "and a step interval of eleven days down to the maximum");
        eq((int) slow.downStepMs, (int) LinearConfig.MAX_STEP_MS, "on every interval too");
        LinearConfig neg = new LinearConfig();
        neg.upStepMs = -5000L;
        neg.downStepMs = -5000L;
        neg.downFastMs = -5000L;
        neg.sanitise();
        eq((int) neg.upStepMs, (int) LinearConfig.MIN_STEP_MS,
                "a negative interval is repaired, not treated as fast");
        check(LinearConfig.MIN_STEP_MS == 1000L && LinearConfig.MAX_STEP_MS == 120000L,
                "the range is the specified 1000..120000 ms");

        // ---- an inverted schedule is repaired, not obeyed ----
        // downFastMs is the fast one by definition. A config asking for slow-with-headroom
        // and fast-at-the-ceiling would invert the whole design: it would crawl where
        // dropping is free and sprint where it costs an audible swing.
        LinearConfig inverted = new LinearConfig();
        inverted.downStepMs = 5000L;
        inverted.downFastMs = 90000L;
        inverted.sanitise();
        check(inverted.downFastMs <= inverted.downStepMs,
                "the headroom interval is never slower than the near-ceiling one");
        eq((int) inverted.downFastMs, 5000, "it is pulled back to it rather than rejected");

        // ---- the band is repaired ----
        LinearConfig wide = new LinearConfig();
        wide.nearC = 500.0;
        wide.sanitise();
        eq((int) Math.round(wide.nearC), (int) LinearConfig.MAX_NEAR_C,
                "an absurd band is clamped");
        LinearConfig nb = new LinearConfig();
        nb.nearC = -3.0;
        nb.sanitise();
        eq((int) Math.round(nb.nearC), (int) LinearConfig.MIN_NEAR_C,
                "and a negative one becomes zero, which simply disables the schedule");
        LinearConfig nanb = new LinearConfig();
        nanb.nearC = Double.NaN;
        nanb.sanitise();
        eq((int) Math.round(nanb.nearC * 10), 10, "NaN restores the default band");

        // ---- the trend window is repaired ----
        LinearConfig tw = new LinearConfig();
        tw.trendWindowS = 9999;
        tw.sanitise();
        eq(tw.trendWindowS, LinearConfig.MAX_TREND_S, "an absurd trend window is clamped");
        tw.trendWindowS = -5;
        tw.sanitise();
        eq(tw.trendWindowS, LinearConfig.MIN_TREND_S,
                "and a negative one becomes zero, which simply turns the gate off");

        // ---- the ceiling is repaired ----
        LinearConfig low = new LinearConfig();
        low.ceilingC = 5.0;
        low.sanitise();
        eq((int) Math.round(low.ceilingC), (int) LinearConfig.MIN_CEILING_C,
                "an unreachably low ceiling is clamped, and merely over-cools");
        LinearConfig high = new LinearConfig();
        high.ceilingC = 95.0;
        high.sanitise();
        eq((int) Math.round(high.ceilingC), (int) LinearConfig.MAX_CEILING_C,
                "a ceiling above the 75 C shutdown is clamped well short of it");
        LinearConfig nan = new LinearConfig();
        nan.ceilingC = Double.NaN;
        nan.sanitise();
        eq((int) Math.round(nan.ceilingC * 10), 520, "NaN restores the default");

        // --ef arrives as a float, so 52.3 reaches the receiver as 52.29999923706055. The
        // snap to a tenth is what stops that appearing in the stored line and in every
        // reply that echoes it -- and what lets the round trip be byte-identical at all.
        LinearConfig snap = new LinearConfig();
        snap.ceilingC = (double) 52.3f;
        snap.sanitise();
        check(snap.encode().equals("l3,52.3,5000,60000,10000,1.0,90,10,30,83"),
                "a float ceiling snaps to a tenth  (got " + snap.encode() + ")");
        check(LinearConfig.decode(snap.encode()).encode().equals(snap.encode()),
                "and then round trips");

        // A hostile config must not be able to command something unwritable.
        LinearConfig bad = new LinearConfig();
        bad.ceilingC = -1000.0;
        bad.upStepMs = -1L;
        bad.downStepMs = -1L;
        bad.downFastMs = -1L;
        bad.nearC = Double.NEGATIVE_INFINITY;
        bad.idleDuty = 0;
        bad.minDuty = -50;
        bad.maxDuty = 900;
        bad.sanitise();
        check(FanIo.valid(bad.minDuty) && FanIo.valid(bad.maxDuty)
                        && FanIo.valid(bad.idleDuty),
                "sanitise() leaves only writable duties behind");
        check(bad.maxDuty >= bad.minDuty, "and a ceiling that is not below the floor");
        FanLinear hf = new FanLinear();
        hf.resync(40);
        long hm = 10000L;
        boolean allValid = true;
        for (int i = 0; i < 500; i++) {
            hm += bad.upStepMs;
            if (!FanIo.valid(hf.step(bad, 20.0 + i * 0.2, true, hm))) {
                allValid = false;
            }
        }
        check(allValid, "and the controller running on it only ever commands a legal duty");
    }

    /**
     * Every preset, driven through the real {@link FanCurve} against the plant, at every
     * room temperature from 14 to 34 C. None may hunt.
     *
     * This is the check the static rules above cannot make. A segment can be four degrees
     * wide, monotone, correctly clipped and exactly what its shape derives, and still leave
     * the controller with nowhere to rest: Cold's rise from the pinned floor passed every static
     * assertion in this file and hunted by four duty points at 17 C ambient, because 23 duty
     * points in 4 C is a slope of 5.75 duty/C and the 0.8 C deadband then spans 4.6 duty
     * points. The width test never saw it. This one does.
     *
     * One pole rather than two, for the same reason {@link #testLinearConvergence} uses one:
     * it runs on every build in a few seconds, and {@code tools/CurveSim.java} -- two poles,
     * four slow-pole values, the SoC guard -- is the fuller gate to run before shipping a
     * curve. But the single-pole run reproduces both hunts CurveSim found on the presets
     * that shipped on 2026-09-07, so it is a real regression guard and not a formality.
     *
     * The bound is two duty points. That is not zero, and it is worth saying why: Quiet at
     * 16 C ambient wobbles by two, on a rounding knife-edge where the operating point lands
     * almost exactly between integers on the rise below the shelf. 15, 17 and 18 C are all
     * still. That was accepted as a known corner rather than moved, because moving a flat
     * region off a measured operating point costs more than a two-point wobble six degrees
     * below the coldest room this unit has seen. Two is therefore the accepted state; three
     * is a regression.
     */
    private static void testCurvePresetsDoNotHunt() {
        section("curve: no preset hunts against the plant, 14 to 34 C ambient");
        // The only check that has ever caught a hunt in this curve. Three static rules --
        // segment width, slope, and their product -- were each written down as the criterion
        // and each passed a curve that hunts; the measurements are in docs/curve.md.
        //
        // This mirrors tools/CurveSim.java's pole sweep on purpose, detail for detail,
        // because a first version of this test -- one pole at 120 s, no noise, started at
        // duty 40 -- PASSED the Cold preset that CurveSim had already caught hunting by four
        // points at 17 C. A hunt on a rounding knife-edge is decided by exactly the details
        // a tidy model leaves out: the 70/30 split between the fast pole and the chassis,
        // the slow pole's value, and 0.03 C of seeded sensor noise. So they are all here.
        //
        // It is driven off PRESETS.length rather than a list, so adding a curve puts it
        // under this check without anyone remembering to. That is how all four Bright rungs
        // got here, and it is why the count assertion at the bottom exists.
        //
        // It runs the STOCK plant, and that is deliberate rather than an oversight now that
        // half the presets are drawn for a raised one. Seven of the eight can only ever run
        // at stock drive, and the eighth pairing -- a Bright preset at stock drive -- is a
        // legitimate state: the drive can trip off under a Bright curve and the curve stays.
        // The reverse, a standard curve at raised drive, is the state the family gate makes
        // unreachable, so nothing here needs to model it. tools/CurveSim.java sweeps the
        // raised plant with --scale high=1.208 and its verdicts, including the one place the
        // Bright family is worse than the standard one, are recorded against the preset
        // lines in CurveConfig.
        final double tauFast = 230.0;
        final double[] tauSlow = {0.0, 900.0, 1500.0, 3000.0};
        int checked = 0;
        for (int i = 0; i < CurveConfig.PRESETS.length; i++) {
            checked++;
            String name = CurveConfig.PRESET_NAMES[i];
            CurveConfig cfg = CurveConfig.preset(i);
            for (int ambient = 14; ambient <= 34; ambient++) {
                for (int k = 0; k < tauSlow.length; k++) {
                    java.util.Random rng = new java.util.Random(1);
                    double fast = 0.0, slow = 0.0;
                    FanCurve f = new FanCurve();
                    f.resync(cfg.dutyAt(CurveConfig.PROFILE_HIGH,
                            ambient + presentationRise(40)));
                    int lo = 200, hi = 0, prev = -1, maxTick = 0;
                    for (int t = 0; t <= 12000; t++) {
                        double measured = ambient + fast + slow + rng.nextGaussian() * 0.03;
                        int d = f.step(cfg, CurveConfig.PROFILE_HIGH, measured, true,
                                t * 1000L);
                        double total = presentationRise(d);
                        fast += (total * 0.70 - fast) * (1.0 - Math.exp(-1.0 / tauFast));
                        slow += (total * 0.30 - slow)
                                * (tauSlow[k] == 0.0 ? 1.0 : (1.0 - Math.exp(-1.0 / tauSlow[k])));
                        // The last fifty minutes only, as CurveSim judges it.
                        if (t > 9000) {
                            lo = Math.min(lo, d);
                            hi = Math.max(hi, d);
                            if (prev >= 0) {
                                maxTick = Math.max(maxTick, Math.abs(d - prev));
                            }
                            prev = d;
                        }
                    }
                    String where = name + " at " + ambient + " C, slow pole "
                            + (tauSlow[k] == 0.0 ? "none" : ((int) tauSlow[k] + " s"));
                    check(hi - lo <= 2, where + ": settled within two duty points (was "
                            + lo + ".." + hi + ")");
                    check(maxTick <= 1, where + ": one point per tick once settled (worst "
                            + maxTick + ")");
                }
            }
        }
        eq(checked, CurveConfig.PRESET_NAMES.length,
                "and every curve on offer went through it, not a list of them kept here");
    }

    /**
     * The controller against the measured plant with one thermal pole.
     *
     * What this establishes and what it does not. It establishes that the walk converges
     * from the floor, that it arrives where the plant says it should, and that the residual
     * oscillation is bounded -- a regression guard on the controller's own logic. It is
     * <b>not</b> evidence of field stability: one pole cannot produce the growing limit
     * cycle that duty-per-second was actually measured producing on hardware, which is why
     * {@code tools/CurveSim.java} sweeps two. Anyone tempted to lower {@link
     * LinearConfig#downStepMs} on the strength of this test should read that field's comment
     * and repeat the step test instead.
     */
    private static void testLinearConvergence() {
        section("linear: converges on the ceiling and stays within a bounded swing");
        LinearConfig cfg = new LinearConfig();
        double tau = 120.0;                 // the measured fast pole: 115 s down, 133 s up
        int[] ambients = {22, 24, 26, 30};
        for (int a = 0; a < ambients.length; a++) {
            double ambient = ambients[a];
            FanLinear f = new FanLinear();
            f.resync(cfg.minDuty);
            double temp = ambient + presentationRise(cfg.minDuty);
            int duty = cfg.minDuty;
            long ms = 10000L;
            int lo = 999;
            int hi = -999;
            double tlo = 999;
            double thi = -999;
            int settledAt = -1;
            for (int s = 0; s < 14400; s++) {
                duty = f.step(cfg, temp, true, ms);
                temp += (ambient + presentationRise(duty) - temp) / tau;
                ms += 1000L;
                if (settledAt < 0 && Math.abs(temp - cfg.ceilingC) <= 0.5) {
                    settledAt = s;
                }
                // The second half only: the first is the walk getting there.
                if (s >= 7200) {
                    lo = Math.min(lo, duty);
                    hi = Math.max(hi, duty);
                    tlo = Math.min(tlo, temp);
                    thi = Math.max(thi, temp);
                }
            }
            boolean reachable = ambient + presentationRise(cfg.maxDuty) <= cfg.ceilingC;
            if (reachable) {
                check(settledAt >= 0 && settledAt < 7200, ambient
                        + " C: reaches the ceiling's neighbourhood, in " + settledAt + " s");
                eq(thi - tlo, 0.0, 1.0, ambient
                        + " C: and thereafter holds it to within a degree");
                // The swing is set by decay rate against plant lag, and this is where
                // the split earns its keep. A single 5 s interval swung twelve points here
                // and fourteen on hardware, which the owner heard; a 60 s decay measured
                // three on hardware and the bound below is the simulated equivalent. If
                // this test starts failing upward, the asymmetry has been weakened.
                check(hi - lo <= 4, ambient + " C: the duty swing is " + (hi - lo)
                        + " points, inside the handful a 60 s decay implies");
                check(lo >= cfg.minDuty && hi <= cfg.maxDuty,
                        ambient + " C: and stays between the floor and the ceiling duty");
            } else {
                // At 30 C ambient the plant's minimum rise is 19.4 C, so 52 C is only just
                // reachable and a cooler ceiling would not be. A correct controller pegs
                // the fan and says so rather than pretending.
                eq(hi, cfg.maxDuty, ambient
                        + " C: an unreachable ceiling pegs the duty at the maximum");
                check(f.saturated(), "and reports being out of authority");
            }
        }

        // The ceiling was chosen so that LINEAR and CURVE/Quiet agree at 24 C, which is
        // what makes an A/B of the two a comparison of controllers rather than of targets.
        // Quiet settles at 38.0 % and 52.1 C there; this lands within a couple of points.
        FanLinear m = new FanLinear();
        m.resync(cfg.minDuty);
        double temp = 24.0 + presentationRise(cfg.minDuty);
        int duty = cfg.minDuty;
        long ms = 10000L;
        int lo = 999;
        int hi = -999;
        for (int s = 0; s < 14400; s++) {
            duty = m.step(cfg, temp, true, ms);
            temp += (24.0 + presentationRise(duty) - temp) / tau;
            ms += 1000L;
            if (s >= 7200) {
                lo = Math.min(lo, duty);
                hi = Math.max(hi, duty);
            }
        }
        eq((lo + hi) / 2.0, 38.0, 3.0,
                "at 24 C it settles where CURVE with the Quiet preset does, 38 %");
    }

    // ----------------------------------------------------------------- provenance

    /**
     * The off-duration is the entire basis of the power-on ambient reading, and it has two
     * cases that look nothing like each other. Answering from the wrong one is not a
     * rounding error: it is the difference between a settled 27.9 C and a reading four
     * degrees warm, and the log would carry no sign of which had happened.
     */
    private static void testOffDuration() {
        section("ambient: how long the projector had been off, both cases");

        long hour = 3600000L;
        long boot = 1000000000000L;

        // A true power-down. Android was not running for the thirteen hours, so this boot
        // began after the gap and elapsedRealtime knows nothing whatever about it -- the
        // persisted wall instant is the only witness there is. The previous boot's
        // monotonic reading is deliberately smaller than this gap: a cross-check that
        // failed to test the boot identity first would report eight hours here.
        long onWall = boot + 40000L;
        long stampWall = boot - 13 * hour;
        long stampBoot = stampWall - 2 * hour;
        eq((int) (Provenance.offDurationMs(onWall, 40000L, stampWall, 8 * hour,
                        stampBoot, boot) / 1000L),
                (int) ((13 * hour + 40000L) / 1000L),
                "a power-down is measured from the last engine-on sighting, across the boot");
        check(Provenance.OFF_BOOT.equals(Provenance.offSource(stampWall, stampBoot, boot)),
                "and is reported as the power-down case");

        // Standby: Android stayed up, so no boot intervened and the monotonic clock spans
        // the whole gap. Nothing derived from boot time could answer this -- it would
        // report the twenty hours of uptime instead of the three the engine was off.
        long mono = 20 * hour;
        eq((int) (Provenance.offDurationMs(boot + mono, mono, boot + 17 * hour,
                        17 * hour, boot, boot) / 1000L),
                (int) ((3 * hour) / 1000L),
                "standby is measured inside one boot, with no reboot in the gap");
        check(Provenance.OFF_STANDBY.equals(
                        Provenance.offSource(boot + 17 * hour, boot, boot)),
                "and is reported as the standby case");

        // Same boot, but the wall clock was corrected forward by a year after the stamp
        // was taken. The monotonic witness is the shorter of the two and therefore wins,
        // which is the point of taking the shorter: it can only grade the reading as less
        // settled, never as more.
        long year = 365L * 24 * hour;
        eq((int) (Provenance.offDurationMs(boot + mono + year, mono, boot + 17 * hour,
                        17 * hour, boot, boot) / 1000L),
                (int) ((3 * hour) / 1000L),
                "a clock correction inside one boot cannot inflate the gap");

        // Nothing persisted: a fresh install, or pm clear.
        eq((int) Provenance.offDurationMs(onWall, 40000L, 0L, 0L, 0L, boot), -1,
                "with no stamp the off-duration is unknown, which is not zero");
        check(Provenance.OFF_NONE.equals(Provenance.offSource(0L, 0L, boot)),
                "and it says so rather than naming a case");
        eq((int) Provenance.offDurationMs(onWall, 40000L, onWall + hour, 0L,
                        stampBoot, boot), -1,
                "a clock that moved backwards is unknown, not a negative duration");

        check(Provenance.sameBoot(boot, boot + 30000L),
                "half a minute of clock drift is still the same boot");
        check(!Provenance.sameBoot(boot, boot + 5 * 60000L),
                "five minutes apart is not");
    }

    /**
     * The exclusive-control flag stands in for a filter that was assembled by hand out of
     * three different notes, so the transitions it reports have to be the ones that used
     * to be found by grep -- including the two that are easy to get backwards, MANUAL and
     * a running session.
     */
    private static void testExclusiveControl() {
        section("exclusive control: one column instead of three greps");

        long t = 5000000L;
        eq(Provenance.exclusive(Mode.CURVE, false, "0", t, 0L), 1,
                "the curve driving, the ladder stood down, nothing else on the node");
        eq(Provenance.exclusive(Mode.MANUAL, false, "0", t, 0L), 0,
                "MANUAL is never exclusive -- it leaves the stock ladder armed by design");
        eq(Provenance.exclusive(Mode.OFF, false, "0", t, 0L), 0,
                "nor is OFF, which is not driving at all");
        eq(Provenance.exclusive(Mode.CURVE, true, "0", t, 0L), 0,
                "a running session owns the node, but it has a trace file of its own");
        eq(Provenance.exclusive(Mode.CURVE, false, "1", t, 0L), 0,
                "an armed stock ladder counts whether or not it has written yet");
        eq(Provenance.exclusive(Mode.CURVE, false, "0\n", t, 0L), 1,
                "a property read back with its newline is still a zero");
        eq(Provenance.exclusive(Mode.CURVE, false, null, t, 0L), -1,
                "an unreadable kill switch is 'cannot say', which the CSV writes blank");

        // The quiet window, which is the part with a number in it: a foreign write
        // disqualifies the row and keeps doing so until the window has closed.
        eq(Provenance.exclusive(Mode.CURVE, false, "0", t, t), 0,
                "a foreign write this second is not exclusive");
        eq(Provenance.exclusive(Mode.CURVE, false, "0",
                        t + Provenance.FOREIGN_QUIET_MS - 1, t), 0,
                "and still is not, a millisecond short of the window");
        eq(Provenance.exclusive(Mode.CURVE, false, "0",
                        t + Provenance.FOREIGN_QUIET_MS, t), 1,
                "the window closing restores it");
        check(Provenance.FOREIGN_QUIET_MS >= 4 * 15000L,
                "and the window is at least four of the stock ladder's poll periods wide");
    }

    /**
     * The columns the field questions needed, and the property that matters more than
     * any of them: a field nothing could read comes out blank, never as a zero and never
     * as an exception. Then the revision itself, because a header change has already
     * fired unattended once and is about to again.
     */
    private static void testProvenanceColumns() throws Exception {
        section("csv: the ambient and provenance columns, blank against zero");

        String[] cols = CsvLogger.HEADER.split(",", -1);
        eq(cols.length, 27, "the schema is twenty-seven columns");
        check(CsvLogger.HEADER.startsWith(OLD_HEADER_20),
                "the twenty that were there are unchanged and still in that order");
        check(CsvLogger.HEADER.endsWith(
                        ",session,off_s,room_c,exclusive,catchup,duty_hold_s,led_drive"),
                "and the seven new ones are on the end, so no existing column index moved");
        check(SweepReport.TRACE_HEADER.startsWith(CsvLogger.HEADER + ","),
                "the sweep trace grew with them rather than shifting underneath its reader");

        // Nothing read at all. Every new field has to be empty rather than a 0 or a -1,
        // or a downstream mean is quietly wrong instead of loudly absent.
        Sample blank = new Sample();
        String[] f = blank.toCsv().split(",", -1);
        eq(f.length, cols.length, "an unpopulated sample still fills every column");
        for (int i = 20; i < f.length; i++) {
            check(f[i].length() == 0, "column " + cols[i] + " is blank when nothing was "
                    + "read, got '" + f[i] + "'");
        }

        Sample s = new Sample();
        s.session = 7;
        s.offMs = 47880000L;
        s.exclusive = 0;
        s.catchingUp = 0;
        s.dutyHoldMs = 95500L;
        f = s.toCsv().split(",", -1);
        eq(f.length, cols.length, "and so does a populated one");
        check(f[20].equals("7"), "session is the run number, got '" + f[20] + "'");
        check(f[21].equals("47880"), "off_s is whole seconds, got '" + f[21] + "'");
        check(f[22].length() == 0,
                "a room temperature of 0 means not stated and logs blank, not as a zero");
        check(f[23].equals("0"),
                "where exclusive=0 is a real answer and does log as a zero");
        check(f[24].equals("0"), "and so does catchup=0 -- converged, not unknown");
        check(f[25].equals("95"), "duty_hold_s is whole seconds, got '" + f[25] + "'");
        check(f[26].length() == 0,
                "led_drive is blank under the stock table, so a row with nothing here was "
                + "measured under stock LED drive rather than under an unrecorded one");

        s.ledDrive = 90;
        check(s.toCsv().split(",", -1)[26].equals("90"),
                "and is the level itself once the override is on the hardware");

        s.roomC = 23;
        check(s.toCsv().split(",", -1)[22].equals("23"),
                "a stated room temperature is the number itself");

        // The revision. A file left on the device under the old twenty columns must be
        // rolled aside rather than appended to: this fired for real, unattended, when the
        // header went 16 -> 20, and the same thing has to happen at 20 -> 26.
        File base = new File(System.getProperty("java.io.tmpdir"),
                "fanlab-schema-" + System.nanoTime());
        File dir = new File(base, "sink");
        dir.mkdirs();
        try {
            List<File> one = new ArrayList<File>();
            one.add(dir);
            CsvLogger old = new CsvLogger("fanlab.csv", OLD_HEADER_20);
            old.setDirs(one);
            old.append("1757000000000,2026-09-06 12:00:00,1900,52.20,52,40,3,1,"
                    + "Presentation,CURVE,40,,,64.2,67.3,59.2,0,0,0,0");
            old.close();

            CsvLogger fresh = new CsvLogger("fanlab.csv");
            fresh.setDirs(one);
            eq(fresh.append(new Sample().toCsv()), 1,
                    "a row still reaches the destination across the revision");
            fresh.close();

            String live = read(new File(dir, "fanlab.csv"));
            check(live.startsWith(CsvLogger.HEADER + "\n"),
                    "the live file carries the new header");
            eq(countLines(live), 2, "and holds its header and the new row, nothing else");
            File[] kept = dir.listFiles();
            eq(kept == null ? 0 : kept.length, 2,
                    "the twenty-column data is renamed aside, not thrown away");
            for (int i = 0; kept != null && i < kept.length; i++) {
                if (!kept[i].getName().equals("fanlab.csv")) {
                    check(read(kept[i]).startsWith(OLD_HEADER_20 + "\n"),
                            "and keeps the header it was actually written under");
                }
            }

            // Reopening on the new header has to append, or every service start would
            // roll the file and the cap would stop meaning anything again.
            CsvLogger again = new CsvLogger("fanlab.csv");
            again.setDirs(one);
            again.append(new Sample().toCsv());
            again.close();
            eq(countLines(read(new File(dir, "fanlab.csv"))), 3,
                    "the new header round-trips: reopening appends rather than rolling");
        } finally {
            rmrf(base);
        }
    }

    private static void write(File f, String content) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
        w.write(content);
        w.close();
    }

    // ----------------------------------------------------------------- utils

    private static String read(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        in.close();
        return new String(b, 0, off, "UTF-8");
    }

    private static int countLines(String s) {
        return count(s, "\n");
    }

    private static int count(String hay, String needle) {
        int n = 0;
        int i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static void rmrf(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                rmrf(kids[i]);
            }
        }
        f.delete();
    }

    private static String round2(double v) {
        return Long.toString(Math.round(v * 100)).length() == 0
                ? "" : (Math.round(v * 100) / 100.0) + "";
    }

    private static String pad(int v) {
        String s = Integer.toString(v);
        while (s.length() < 4) {
            s = " " + s;
        }
        return s;
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s.replace("\n", "\\n") + "\"";
    }
}
