import com.daleygames.fanlab.CurveConfig;
import com.daleygames.fanlab.FanCurve;

/**
 * Run the REAL {@link FanCurve} against a simulated thermal plant, in the time domain.
 *
 * The fixed-point solver in {@code solve_curve.py} says where the fan comes to rest. It
 * cannot say whether you would hear it get there, because it models neither the slew
 * limiter nor the hysteresis nor the tier-change exception -- and those three are the
 * entire difference between "smooth" and "the thing it does now". This does, and it does
 * it by calling the shipping controller rather than a paraphrase of it, so a divergence
 * between the analysis and the APK is impossible by construction.
 *
 * The trajectory print uses one pole; the hunting check below it uses two, and sweeps the
 * slow one, because a single-pole plant cannot oscillate and a stability test it always
 * passes is not a test. Both use the measured plant table, not a linear fit to it -- see
 * the note on {@link #rise}, which is where an earlier version of this file went wrong.
 *
 *   javac -cp <app/build/test> -d . CurveSim.java && java -cp <app/build/test>;. CurveSim
 */
public final class CurveSim {

    /**
     * Rise above ambient at a given duty, per mode: the MEASURED plant, interpolated.
     *
     * An earlier version of this file used a single straight line of slope 0.174 C per
     * duty point, taken from the average across the whole range. That was wrong in the
     * way that mattered: the plant is roughly ten times more responsive at duty 35 than
     * at duty 80 (0.60 vs 0.06), so a linear model is 3x too gentle exactly where the
     * quiet end of the curve operates -- which is precisely where a steep ramp would
     * misbehave if it were going to. The stability results from the linear version were
     * therefore not worth much. Use the table.
     */
    static final double[] DUTY = {30, 35, 40, 45, 50, 55, 60, 70, 83};
    static final double[] HIGH_RISE = {33.76, 29.92, 26.91, 24.9, 23.7, 22.3, 21.5, 20.2, 19.4};
    static final double[] NORM_RISE = {19.1, 18.6, 18.1, 17.1, 16.1, 15.3, 14.8, 14.1, 13.5};
    static final double[] LOW_RISE  = {15.0, 14.4, 13.4, 12.8, 12.1, 11.4, 11.4, 11.0, 10.8};

    /**
     * Rise above ambient of the SoC pll die, measured directly rather than transferred.
     *
     * The guard's numbers were first set by arguing that the die moves about as many
     * degrees as the LED thermistor for a given duty change, taken across holds that were
     * still drifting in runs with UHD processing off. A 48-minute sweep under UHD-on load
     * -- Presentation, six holds, duty 40 held first and last so load drift would show as
     * a failure to close, and it closed to within 0.5 C -- says the argument was right and
     * the transferred magnitudes were not. Total swing over duty 30..83 is 14.6 C on the
     * die against 14.0 C on the LED, where the transferred plant predicted rather less at
     * the top of the range.
     */
    static final double[] SOC_DUTY = {30, 40, 50, 62, 83};
    static final double[] SOC_RISE = {45.0, 40.2, 37.6, 33.8, 30.4};

    /**
     * @param load extra die temperature from SoC work the LED thermistor cannot see:
     *             0 for the static white field the LED plant was measured on, about 11 for
     *             UHD processing on. The sweep above already carries the UHD-on load, so
     *             this is measured relative to it.
     */
    static double socRise(double duty, double load) {
        double r;
        if (duty <= SOC_DUTY[0]) {
            double slope = (SOC_RISE[1] - SOC_RISE[0]) / (SOC_DUTY[1] - SOC_DUTY[0]);
            r = SOC_RISE[0] + slope * (duty - SOC_DUTY[0]);
        } else if (duty >= SOC_DUTY[SOC_DUTY.length - 1]) {
            r = SOC_RISE[SOC_RISE.length - 1];
        } else {
            r = SOC_RISE[SOC_RISE.length - 1];
            for (int i = 1; i < SOC_DUTY.length; i++) {
                if (duty <= SOC_DUTY[i]) {
                    double f = (duty - SOC_DUTY[i - 1]) / (SOC_DUTY[i] - SOC_DUTY[i - 1]);
                    r = SOC_RISE[i - 1] + f * (SOC_RISE[i] - SOC_RISE[i - 1]);
                    break;
                }
            }
        }
        return r + load;
    }

    static double rise(int profile, double duty) {
        double[] r = profile == CurveConfig.PROFILE_HIGH ? HIGH_RISE
                : profile == CurveConfig.PROFILE_NORMAL ? NORM_RISE : LOW_RISE;
        if (duty <= DUTY[0]) {
            // below the measured floor: extend the lowest measured slope, which is the
            // steepest one, rather than flattening off and flattering the design
            double slope = (r[1] - r[0]) / (DUTY[1] - DUTY[0]);
            return r[0] + slope * (duty - DUTY[0]);
        }
        for (int i = 1; i < DUTY.length; i++) {
            if (duty <= DUTY[i]) {
                double f = (duty - DUTY[i - 1]) / (DUTY[i] - DUTY[i - 1]);
                return r[i - 1] + f * (r[i] - r[i - 1]);
            }
        }
        return r[r.length - 1];
    }

    public static void main(String[] args) {
        String encoded = args.length > 0 ? args[0]
                : "v1,42,52,56,59,62,66,30,30,36,48,64,83,30,30,38,50,66,83,40,40,50,62,74,83,"
                  + "0.5,0.25,0.12,10,30,83";
        double ambient = args.length > 1 ? Double.parseDouble(args[1]) : 24.0;

        CurveConfig cfg = CurveConfig.decode(encoded);
        System.out.println("curve   : " + cfg.encode());
        System.out.println("ambient : " + ambient + " C");
        System.out.println();

        // Start in Eco, settled. Then switch to Presentation at t=300 s -- the harshest
        // transition the machine offers, and the one the stock controller does as a
        // 15-point step.
        int profile = CurveConfig.PROFILE_LOW;
        double temp = ambient + rise(profile, 30);
        FanCurve curve = new FanCurve();
        curve.resync(30);

        final double tau = 230.0;      // measured fast pole, Presentation duty 40
        int prevDuty = -1;
        int maxJump = 0;
        long jumpAt = -1;

        System.out.printf("%6s %-13s %5s %8s %7s%n", "t(s)", "mode", "duty", "LED C", "step");
        for (long t = 0; t <= 2400; t++) {
            if (t == 300) {
                profile = CurveConfig.PROFILE_HIGH;
            }
            int duty = curve.step(cfg, profile, temp, true, t * 1000L);

            // plant: first-order lag toward the steady state for this duty
            double target = ambient + rise(profile, duty);
            temp += (target - temp) * (1.0 - Math.exp(-1.0 / tau));

            int jump = prevDuty < 0 ? 0 : Math.abs(duty - prevDuty);
            if (t > 0 && jump > maxJump) {
                maxJump = jump;
                jumpAt = t;
            }
            if (t % 120 == 0 || (jump > 0 && t >= 299 && t <= 310)) {
                System.out.printf("%6d %-13s %5d %8.2f %7s%n", t,
                        profile == CurveConfig.PROFILE_HIGH ? "Presentation" : "Eco",
                        duty, temp, jump == 0 ? "-" : ("+" + jump));
            }
            prevDuty = duty;
        }

        System.out.println();
        System.out.println("largest single-tick duty change: " + maxJump
                + (jumpAt >= 0 ? " at t=" + jumpAt + "s" : ""));
        System.out.println("(1 point per tick is inaudible; the stock controller steps 15 at once)");

        // ---- hunting check ----
        // The static loop-gain criterion in solve_curve.py models the loop as an
        // instantaneously iterated map. That is the right model for locating the fixed
        // point and the WRONG one for stability, because it ignores the plant lag, the
        // slew limiter and the 1 Hz sample. So measure the thing directly.
        //
        // Two poles, because one pole cannot oscillate and would make this test
        // vacuous. Today's run showed the LED thermistor and three SoC dies all still
        // climbing ~2 C in the back half of a 12-minute hold, so a slow chassis pole is
        // real; it is modelled here as 30% of the steady rise arriving with tau 1500 s
        // behind the 70% that arrives with the measured fast tau. Sensor noise is
        // included at the ADC quantisation scale.
        System.out.println();
        for (double tauSlow : new double[]{0.0, 900.0, 1500.0, 3000.0}) {
            java.util.Random rng = new java.util.Random(1);
            double fast = 0.0, slow = 0.0;
            FanCurve c2 = new FanCurve();
            int seed = cfg.dutyAt(profile, ambient + rise(profile, 40));
            c2.resync(seed);
            int lo = 200, hi = 0;
            long changes = 0;
            int prev = -1;
            for (long t = 0; t <= 12000; t++) {
                double measured = ambient + fast + slow + rng.nextGaussian() * 0.03;
                int d = c2.step(cfg, profile, measured, true, t * 1000L);
                double total = rise(profile, d);
                double split = tauSlow > 0 ? 0.70 : 1.0;
                fast += (total * split - fast) * (1.0 - Math.exp(-1.0 / tau));
                if (tauSlow > 0) {
                    slow += (total * 0.30 - slow) * (1.0 - Math.exp(-1.0 / tauSlow));
                }
                if (t > 9000) {
                    lo = Math.min(lo, d);
                    hi = Math.max(hi, d);
                    if (prev >= 0 && d != prev) {
                        changes++;
                    }
                    prev = d;
                }
            }
            System.out.printf("  slow pole tau=%-6s duty %d..%d over the last 50 min, %d changes -> %s%n",
                    tauSlow == 0 ? "none" : ((int) tauSlow) + "s", lo, hi, changes,
                    hi - lo <= 1 ? "STEADY" : "HUNTING by " + (hi - lo));
        }

        guardCheck(cfg, profile, ambient, tau);
    }

    static int duty0(CurveConfig cfg, int profile, double ambient) {
        return cfg.dutyAt(profile, ambient + rise(profile, 40));
    }

    /**
     * The same two-pole check, run with the SoC guard as the binding constraint.
     *
     * Adding a second sensor to a loop tuned never to move is the obvious way to
     * reintroduce the oscillation the whole project exists to remove, so it gets its own
     * stability test rather than an argument.
     *
     * The die runs on its own measured plant ({@link #socRise}) with its own lag, not on
     * the LED trace plus a constant. An earlier version did the latter, which quietly
     * assumes the two sensors move together instantly; they have similar gain but not
     * identical dynamics, and a stability test that cannot see a phase difference between
     * the two loops is not testing the thing that would actually oscillate.
     *
     * The swept parameter is extra SoC load the LED thermistor cannot see -- 0 is the
     * sweep's own conditions, and positive values are work the video pipeline does on top.
     * It walks the guard from inert, through part-engaged, to saturated; part-engaged is
     * the interesting one, because that is where the guard rather than the curve sets the
     * duty and can therefore chase its own tail.
     */
    static void guardCheck(CurveConfig cfg, int profile, double ambient, double tau) {
        System.out.println();
        System.out.println("SoC guard, measured die plant, extra SoC load swept:");
        System.out.printf("  %7s | %7s %6s | %7s %6s %8s | %7s  %s%n",
                "load", "pll", "duty", "pll", "duty", "changes", "saved", "verdict");
        System.out.printf("  %7s | %14s | %23s |%n", "", "unguarded", "guarded");
        for (double offset : new double[]{0, 4, 6, 8, 10, 12, 14, 16, 20}) {
            double[] off = settle(cfg, profile, ambient, tau, offset, false);
            double[] on = settle(cfg, profile, ambient, tau, offset, true);
            int lo = (int) on[2], hi = (int) on[3];
            String verdict = hi - lo <= 1 ? "STEADY" : "HUNTING by " + (hi - lo);
            if (on[1] <= off[1] + 0.05) {
                verdict += ", guard inert";
            } else if (on[1] >= cfg.socGuardMaxDuty) {
                verdict += ", guard saturated";
            } else {
                verdict += ", guard binding";
            }
            if (on[0] >= 75.0) {
                verdict += " -- STILL OVER 75";
            }
            System.out.printf("  %7.0f | %7.1f %6.0f | %7.1f %6.0f %8.0f | %7.1f  %s%n",
                    offset, off[0], off[1], on[0], on[1], on[4], off[0] - on[0], verdict);
        }
        System.out.println("  (75 C is the pll trip where CPU/GPU throttling starts)");
    }

    /**
     * Settle the loop and report {pll, duty, dutyLo, dutyHi, changes, guardBoost}.
     * Run twice per row -- guard armed and disarmed -- so the saving is measured against
     * the same plant rather than asserted.
     */
    static double[] settle(CurveConfig cfg, int profile, double ambient, double tau,
            double offset, boolean guarded) {
        java.util.Random rng = new java.util.Random(1);
        double fast = 0.0, slow = 0.0, socFast = 0.0, socSlow = 0.0;
        FanCurve c2 = new FanCurve();
        c2.resync(cfg.dutyAt(profile, ambient + rise(profile, 40)));
        int lo = 200, hi = 0, prev = -1, d = 0;
        long changes = 0;
        double pll = 0;
        // The die's own fitted time constant, ~210 s, against the LED's ~230. Close, but
        // modelled separately so the two loops are not forced into lockstep.
        final double socTau = 210.0;
        for (long t = 0; t <= 12000; t++) {
            double measured = ambient + fast + slow + rng.nextGaussian() * 0.03;
            pll = ambient + socFast + socSlow + rng.nextGaussian() * 0.15;
            d = c2.step(cfg, profile, measured, guarded ? pll : Double.NaN, true, t * 1000L);
            double total = rise(profile, d);
            fast += (total * 0.70 - fast) * (1.0 - Math.exp(-1.0 / tau));
            slow += (total * 0.30 - slow) * (1.0 - Math.exp(-1.0 / 1500.0));
            double socTotal = socRise(d, offset);
            socFast += (socTotal * 0.70 - socFast) * (1.0 - Math.exp(-1.0 / socTau));
            socSlow += (socTotal * 0.30 - socSlow) * (1.0 - Math.exp(-1.0 / 1500.0));
            if (t > 9000) {
                lo = Math.min(lo, d);
                hi = Math.max(hi, d);
                if (prev >= 0 && d != prev) {
                    changes++;
                }
                prev = d;
            }
        }
        return new double[]{pll, d, lo, hi, changes, c2.guardBoost()};
    }
}
