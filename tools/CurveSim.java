import com.daleygames.fanlab.CurveConfig;
import com.daleygames.fanlab.FanCurve;

/**
 * Run the REAL {@link FanCurve} against a simulated thermal plant, in the time domain --
 * slew limiter, hysteresis and tier-change exception included, none of which the
 * fixed-point solver in {@code solve_curve.py} models.
 *
 *   javac -cp <app/build/test> -d . CurveSim.java && java -cp <app/build/test>;. CurveSim
 *
 * Arguments: {@code [flags] [encoded-curve] [ambient]}. The flags:
 * <pre>
 *   --profile high|normal|low        which column the settled machine runs; default high
 *   --drive high=90,normal=70,low=50 LED drive per profile, scaling that profile's plant
 *                                    by the ratio in {@link #driveScale}
 *   --scale high=1.18                the scale factor directly
 *   --offset normal=2.5              degrees added to a column before scaling
 *   --sweep 15,35                    run only the hunting check, one line per whole-degree
 *                                    ambient in the range
 * </pre>
 */
public final class CurveSim {

    /** Rise above ambient in degrees C at a given duty, per profile: the MEASURED plant. */
    static final double[] DUTY = {30, 35, 40, 45, 50, 55, 60, 70, 83};
    static final double[] HIGH_RISE = {33.76, 29.92, 26.91, 24.9, 23.7, 22.3, 21.5, 20.2, 19.4};
    static final double[] NORM_RISE = {19.1, 18.6, 18.1, 17.1, 16.1, 15.3, 14.8, 14.1, 13.5};
    static final double[] LOW_RISE  = {15.0, 14.4, 13.4, 12.8, 12.1, 11.4, 11.4, 11.0, 10.8};

    /**
     * The LED drive each column above was measured at, percent of the driver maximum,
     * indexed by profile: LOW is Eco's column, NORMAL is Normal, HIGH is Presentation.
     */
    static final double[] STOCK_DRIVE = {40, 55, 76};

    /** Per-profile plant adjustments, set from the command line: additive, then multiplicative. */
    static final double[] SCALE = {1.0, 1.0, 1.0};
    static final double[] OFFSET = {0.0, 0.0, 0.0};

    /**
     * How much hotter a profile runs, at every duty, with its LED drive raised.
     *
     * A MEASUREMENT for the three levels below, taken 2026-09-08 with
     * {@code tools/plantdrive.sh} at a pinned fan 45. Anything else falls back to the
     * fitted line {@code rise ~= 1.60 + 0.342 x drive} (all four modes at duty 40), which
     * runs -2 to +6 % against the held measurements and is a prediction, not a result.
     */
    static double driveScale(int profile, double drive) {
        int level = (int) Math.round(drive);
        if (profile == CurveConfig.PROFILE_HIGH && level == 90) {
            return 1.2404;
        }
        if (profile == CurveConfig.PROFILE_NORMAL && level == 75) {
            return 1.4032;
        }
        if (profile == CurveConfig.PROFILE_LOW && level == 55) {
            return 1.3866;
        }
        return (1.60 + 0.342 * drive) / (1.60 + 0.342 * STOCK_DRIVE[profile]);
    }

    /** Rise above ambient in degrees C of the SoC pll die, measured directly at each duty. */
    static final double[] SOC_DUTY = {30, 40, 50, 62, 83};
    static final double[] SOC_RISE = {45.0, 40.2, 37.6, 33.8, 30.4};

    /**
     * @param load extra die temperature from SoC work the LED thermistor cannot see, in
     *             degrees C: 0 for the static white field, about 11 for UHD processing on.
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
        return (measuredRise(profile, duty) + OFFSET[profile]) * SCALE[profile];
    }

    static double measuredRise(int profile, double duty) {
        double[] r = profile == CurveConfig.PROFILE_HIGH ? HIGH_RISE
                : profile == CurveConfig.PROFILE_NORMAL ? NORM_RISE : LOW_RISE;
        if (duty <= DUTY[0]) {
            // below the measured floor: extend the lowest measured slope, the steepest one
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

    static int profileNamed(String s) {
        if (s.equalsIgnoreCase("high") || s.equalsIgnoreCase("presentation")) {
            return CurveConfig.PROFILE_HIGH;
        }
        if (s.equalsIgnoreCase("normal")) {
            return CurveConfig.PROFILE_NORMAL;
        }
        if (s.equalsIgnoreCase("low") || s.equalsIgnoreCase("eco")) {
            return CurveConfig.PROFILE_LOW;
        }
        throw new IllegalArgumentException("profile must be high, normal or low: " + s);
    }

    /** Apply one {@code --drive}/{@code --scale}/{@code --offset} value list. */
    static void plantFlag(String flag, String pairs) {
        for (String pair : pairs.split(",")) {
            String[] kv = pair.split("=");
            int p = profileNamed(kv[0].trim());
            double v = Double.parseDouble(kv[1].trim());
            if (flag.equals("--drive")) {
                SCALE[p] = driveScale(p, v);
            } else if (flag.equals("--scale")) {
                SCALE[p] = v;
            } else {
                OFFSET[p] = v;
            }
        }
    }

    /** One line saying how the plant differs from the table, or null if it does not. */
    static String plantNote() {
        StringBuilder sb = new StringBuilder();
        String[] names = {"low", "normal", "high"};
        for (int p = 0; p < 3; p++) {
            if (OFFSET[p] == 0.0 && SCALE[p] == 1.0) {
                continue;
            }
            sb.append(sb.length() == 0 ? "" : "; ").append(names[p]);
            if (OFFSET[p] != 0.0) {
                sb.append(String.format(" %+.1f C", OFFSET[p]));
            }
            if (SCALE[p] != 1.0) {
                sb.append(String.format(" x%.3f", SCALE[p]));
            }
        }
        return sb.length() == 0 ? null
                : "plant adjusted: " + sb;
    }

    public static void main(String[] args) {
        int settled = CurveConfig.PROFILE_HIGH;
        double[] sweep = null;
        java.util.List<String> rest = new java.util.ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--drive") || a.equals("--scale") || a.equals("--offset")) {
                plantFlag(a, args[++i]);
            } else if (a.equals("--profile")) {
                settled = profileNamed(args[++i]);
            } else if (a.equals("--sweep")) {
                String[] lohi = args[++i].split(",");
                sweep = new double[]{Double.parseDouble(lohi[0]), Double.parseDouble(lohi[1])};
            } else {
                rest.add(a);
            }
        }
        String encoded = rest.size() > 0 ? rest.get(0)
                : "v1,42,52,56,59,62,66,30,30,36,48,64,83,30,30,38,50,66,83,40,40,50,62,74,83,"
                  + "0.5,0.25,0.12,10,30,83";
        double ambient = rest.size() > 1 ? Double.parseDouble(rest.get(1)) : 24.0;

        CurveConfig cfg = CurveConfig.decode(encoded);
        System.out.println("curve   : " + cfg.encode());
        if (plantNote() != null) {
            System.out.println(plantNote());
        }
        if (sweep != null) {
            sweepCheck(cfg, settled, sweep[0], sweep[1]);
            return;
        }
        System.out.println("ambient : " + ambient + " C");
        System.out.println();

        // Start in Eco, settled, then switch to the settled profile at t=300 s -- the
        // harshest transition the machine offers.
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
                profile = settled;
            }
            int duty = curve.step(cfg, profile, temp, true, t * 1000L);

            double target = ambient + rise(profile, duty);
            temp += (target - temp) * (1.0 - Math.exp(-1.0 / tau));

            int jump = prevDuty < 0 ? 0 : Math.abs(duty - prevDuty);
            if (t > 0 && jump > maxJump) {
                maxJump = jump;
                jumpAt = t;
            }
            if (t % 120 == 0 || (jump > 0 && t >= 299 && t <= 310)) {
                System.out.printf("%6d %-13s %5d %8.2f %7s%n", t,
                        CurveConfig.PROFILE_NAMES[profile], duty, temp,
                        jump == 0 ? "-" : ("+" + jump));
            }
            prevDuty = duty;
        }

        System.out.println();
        System.out.println("largest single-tick duty change: " + maxJump
                + (jumpAt >= 0 ? " at t=" + jumpAt + "s" : ""));
        System.out.println("(1 point per tick is inaudible; the stock controller steps 15 at once)");

        // Measure stability directly rather than from a static loop gain, which ignores
        // the plant lag, the slew limiter and the 1 Hz sample. Two poles, because one pole
        // cannot oscillate: 70% of the steady rise arrives with the fast tau and 30% with
        // the slow one, plus sensor noise at the ADC quantisation scale.
        System.out.println();
        for (double tauSlow : SLOW_POLES) {
            long[] r = hunt(cfg, profile, ambient, tau, tauSlow);
            System.out.printf("  slow pole tau=%-6s duty %d..%d over the last 50 min, %d changes -> %s%n",
                    tauSlow == 0 ? "none" : ((int) tauSlow) + "s", r[0], r[1], r[2],
                    r[1] - r[0] <= 1 ? "STEADY" : "HUNTING by " + (r[1] - r[0]));
        }

        guardCheck(cfg, profile, ambient, tau);
    }

    /** The slow chassis poles the hunting check sweeps: none, then 15, 25 and 50 minutes. */
    static final double[] SLOW_POLES = {0.0, 900.0, 1500.0, 3000.0};

    /** Measured fast pole, seconds, Presentation at duty 40. */
    static final double FAST_TAU = 230.0;

    /**
     * The two-pole hunting check at one ambient and one slow pole. Returns
     * {@code {lo, hi, changes}} for the duty over the last 50 minutes of a 200-minute run.
     */
    static long[] hunt(CurveConfig cfg, int profile, double ambient, double tau, double tauSlow) {
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
        return new long[]{lo, hi, changes};
    }

    /**
     * The hunting check at every whole-degree ambient from {@code lo} to {@code hi}, all
     * four slow poles each, one line per ambient.
     */
    static void sweepCheck(CurveConfig cfg, int profile, double lo, double hi) {
        System.out.println("profile : " + CurveConfig.PROFILE_NAMES[profile]);
        System.out.println("hunting check, two-pole plant, slow pole none/900/1500/3000 s,"
                + " duty range over the last 50 min:");
        int runs = 0, steady = 0;
        for (double ambient = lo; ambient <= hi + 1e-9; ambient += 1.0) {
            StringBuilder line = new StringBuilder(String.format("  %4.0f C ", ambient));
            int worst = 0;
            for (double tauSlow : SLOW_POLES) {
                long[] r = hunt(cfg, profile, ambient, FAST_TAU, tauSlow);
                int swing = (int) (r[1] - r[0]);
                worst = Math.max(worst, swing);
                runs++;
                if (swing <= 1) {
                    steady++;
                }
                line.append(String.format(" %2d..%-2d", r[0], r[1]));
            }
            line.append(worst <= 1 ? "   STEADY" : "   HUNTING by " + worst);
            System.out.println(line);
        }
        System.out.printf("%d of %d runs steady at one duty point or better%n", steady, runs);
    }

    static int duty0(CurveConfig cfg, int profile, double ambient) {
        return cfg.dutyAt(profile, ambient + rise(profile, 40));
    }

    /**
     * The same two-pole check with the SoC guard as the binding constraint. The die runs
     * on its own measured plant ({@link #socRise}) with its own lag, so a phase difference
     * between the two loops is visible. The swept parameter is extra SoC load the LED
     * thermistor cannot see, walking the guard from inert through part-engaged to saturated.
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

    /** Settle the loop and report {pll, duty, dutyLo, dutyHi, changes, guardBoost}. */
    static double[] settle(CurveConfig cfg, int profile, double ambient, double tau,
            double offset, boolean guarded) {
        java.util.Random rng = new java.util.Random(1);
        double fast = 0.0, slow = 0.0, socFast = 0.0, socSlow = 0.0;
        FanCurve c2 = new FanCurve();
        c2.resync(cfg.dutyAt(profile, ambient + rise(profile, 40)));
        int lo = 200, hi = 0, prev = -1, d = 0;
        long changes = 0;
        double pll = 0;
        // The die's own fitted time constant, seconds, against the LED's ~230.
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
