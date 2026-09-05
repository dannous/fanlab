import com.daleygames.fanlab.CsvLogger;
import com.daleygames.fanlab.CurveConfig;
import com.daleygames.fanlab.ExpFit;
import com.daleygames.fanlab.FanCurve;
import com.daleygames.fanlab.FanIo;
import com.daleygames.fanlab.HoldSession;
import com.daleygames.fanlab.Json;
import com.daleygames.fanlab.PicoReg;
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
        testCsvLogger();
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
        testPicoReg();
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
        // interpolation really is linear: the shipped 48->55 C segment runs 30->45, so
        // its midpoint 51.5 C must be 37.5, rounding to 38 -- which is also, not by
        // coincidence, where the deployed projector settles.
        eq(c.dutyAt(CurveConfig.PROFILE_HIGH, 51.5), 38,
                "Presentation halfway between 48 (30) and 55 (45) is 38");
        // clamps are honoured
        c.maxDuty = 40;
        c.sanitise();
        eq(c.dutyAt(CurveConfig.PROFILE_HIGH, 60), 40, "the hard maximum clamps the curve");
        c = new CurveConfig();
        c.minDuty = 70;
        c.sanitise();
        eq(c.dutyAt(CurveConfig.PROFILE_LOW, 30), 70, "the hard minimum lifts the curve");
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

        // The shelf is the whole point: nothing moves at all below its edge.
        for (int t = 0; t <= 48; t++) {
            eq(c.dutyAt(CurveConfig.PROFILE_HIGH, t), c.minDuty,
                    "flat at the floor at " + t + " C");
        }

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
        // the stock dead zone exists and the curve does not have it
        eq(FanCurve.stockLadder(CurveConfig.PROFILE_HIGH, 53, 63), -1,
                "the stock ladder writes nothing at 53 C");
        eq(FanCurve.stockLadder(CurveConfig.PROFILE_HIGH, 54, 63), -1,
                "the stock ladder writes nothing at 54 C");
        check(c.dutyAt(CurveConfig.PROFILE_HIGH, 53) > c.dutyAt(CurveConfig.PROFILE_HIGH, 52),
                "the curve keeps rising through 53 C");
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

            check(CsvLogger.q("a,b").equals("\"a,b\""), "commas are quoted");
            check(CsvLogger.q("a\"b").equals("\"a\"\"b\""), "quotes are doubled");
            check(CsvLogger.q("plain").equals("plain"), "plain text is untouched");
        } finally {
            rmrf(base);
        }
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
