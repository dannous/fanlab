import com.daleygames.fanlab.CurveConfig;
import com.daleygames.fanlab.FanLinear;
import com.daleygames.fanlab.LinearConfig;

/**
 * Search for the best form of LINEAR, against the measured two-pole plant.
 *
 * <h3>Why this exists and what it is allowed to conclude</h3>
 * Hardware settles in ten to thirty minutes per variant, so ranking a dozen designs on the
 * machine is a day's work. This ranks them in seconds and hands the winner to hardware for
 * confirmation. That is only legitimate if the simulator is honest, and this project has
 * been burned once already: a controller simulator built on a time constant fitted from
 * hold data said 491 s where a bracketed step test measured 115 s, and a day of conclusions
 * went with it.
 *
 * So there are two guard rails:
 * <ol>
 *   <li><b>The baseline variant calls the real {@link FanLinear}</b>, not a paraphrase of
 *       it, and every other variant is checked to agree with it tick-for-tick where their
 *       logic is supposed to be identical. A paraphrase that has drifted from the shipping
 *       controller cannot rank anything.</li>
 *   <li><b>The plant is validated against a real trace before any ranking is believed.</b>
 *       On 2026-09-07 the seeded controller was watched on hardware climbing from duty 38
 *       to 53 in about 80 seconds and overshooting its equilibrium; the model has to
 *       reproduce that before its opinion about anything else is worth having.</li>
 * </ol>
 *
 * <h3>The plant</h3>
 * Two poles, per the bracketed step test of 2026-09-07: a fast one near 120 s carrying most
 * of the amplitude and a slow chassis pole near 500 s carrying the rest. Sensor noise is the
 * measured 0.078 C sample-to-sample standard deviation, which matters because one candidate
 * decides on a temperature trend and a trend detector has to beat its own noise.
 *
 *   javac -cp <app/build/test> -d <out> LinearSim.java
 *   java  -cp "<app/build/test>;<out>" LinearSim
 */
public final class LinearSim {

    // Measured rise above ambient, Presentation, from tools/solve_curve.py's plant table.
    static final double[] DUTY = {30, 35, 40, 45, 50, 55, 60, 70, 83};
    static final double[] RISE = {33.76, 29.92, 26.91, 24.9, 23.7, 22.3, 21.5, 20.2, 19.4};

    static final double TAU_FAST = 120.0;   // bracketed step test: 115 s cooling, 133 warming
    static final double TAU_SLOW = 500.0;   // the chassis pole the two duty-35 holds exposed
    static final double FAST_SHARE = 0.80;  // most of the amplitude sits on the fast pole
    static final double NOISE_SD = 0.078;   // measured, sample to sample, from the field log

    static double rise(double duty) {
        if (duty <= DUTY[0]) {
            double slope = (RISE[1] - RISE[0]) / (DUTY[1] - DUTY[0]);
            return RISE[0] + slope * (duty - DUTY[0]);
        }
        for (int i = 1; i < DUTY.length; i++) {
            if (duty <= DUTY[i]) {
                double f = (duty - DUTY[i - 1]) / (DUTY[i] - DUTY[i - 1]);
                return RISE[i - 1] + f * (RISE[i] - RISE[i - 1]);
            }
        }
        return RISE[RISE.length - 1];
    }

    /** The duty that holds a given temperature at a given ambient -- the true answer. */
    static double dutyHolding(double ambient, double target) {
        double lo = 30, hi = 83;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (ambient + rise(mid) > target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    // ------------------------------------------------------------------ the variants

    interface Ctl {
        String name();
        void start(LinearConfig cfg, CurveConfig curve, int seedDuty, double celsius);
        int step(LinearConfig cfg, double celsius, long nowMs);
    }

    /** The shipping controller, driven directly. No paraphrase. */
    static final class Real implements Ctl {
        final FanLinear f = new FanLinear();
        CurveConfig curve;
        final boolean seed;
        final String label;
        Real(boolean seed) { this(seed, seed ? "real: seed" : "real: no seed"); }
        Real(boolean seed, String label) { this.seed = seed; this.label = label; }
        public String name() { return label; }
        public void start(LinearConfig cfg, CurveConfig c, int seedDuty, double celsius) {
            curve = seed ? c : null;
            f.reset();
            f.resync(seedDuty);
        }
        public int step(LinearConfig cfg, double celsius, long nowMs) {
            return f.step(cfg, curve, CurveConfig.PROFILE_HIGH, celsius, Double.NaN, true, nowMs);
        }
    }

    /** A copy of a config with the trend gate forced to a given window. */
    static LinearConfig withGate(LinearConfig base, int windowS) {
        LinearConfig c = LinearConfig.decode(base.encode());
        c.trendWindowS = windowS;
        c.sanitise();
        return c;
    }

    /**
     * A reimplementation of the shipping walk, so variants can be built on it. Checked
     * against {@link Real} tick-for-tick before anything is ranked.
     */
    static class Walk implements Ctl {
        int duty = -1;
        long lastStep;
        boolean seedPending;
        CurveConfig curve;
        final boolean seed;
        // variant knobs
        long attackMs = 5000, decayNearMs = 60000, decayFarMs = 10000;
        double nearC = 1.0;
        boolean antiWindup = false;
        int trendWindow = 30;                  // seconds
        double[] hist = new double[4096];
        int histN = 0;
        String label;

        Walk(String label, boolean seed) { this.label = label; this.seed = seed; }
        public String name() { return label; }

        public void start(LinearConfig cfg, CurveConfig c, int seedDuty, double celsius) {
            curve = seed ? c : null;
            duty = seedDuty;
            lastStep = 0;
            seedPending = true;
            histN = 0;
        }

        /**
         * Least-squares slope over the trend window, degrees per second; NaN until full.
         *
         * A two-point difference was tried first and is wrong: its noise is
         * NOISE_SD*sqrt(2)/window, which for a 60 s window is 0.0018 C/s, and the gate
         * below then fires on noise as often as on signal. Randomly skipping steps also
         * suppresses windup, so that version flattered itself. A least-squares slope over
         * N points spanning T seconds has noise NOISE_SD*sqrt(12/(N*T*T)) -- 0.00058 C/s
         * here, three times smaller -- and is what the gate is now built on.
         */
        double trend() {
            if (histN < trendWindow + 1) return Double.NaN;
            int n = trendWindow + 1;
            int from = histN - n;
            double sx = 0, sy = 0, sxx = 0, sxy = 0;
            for (int i = 0; i < n; i++) {
                double x = i, y = hist[from + i];
                sx += x; sy += y; sxx += x * x; sxy += x * y;
            }
            double den = n * sxx - sx * sx;
            return den == 0 ? Double.NaN : (n * sxy - sx * sy) / den;
        }

        /** Three sigma on that slope, so the gate cannot be tripped by sensor noise. */
        double trendNoise() {
            double t = trendWindow;
            return 3.0 * NOISE_SD * Math.sqrt(12.0 / ((t + 1) * t * t));
        }

        public int step(LinearConfig cfg, double celsius, long nowMs) {
            if (histN < hist.length) hist[histN++] = celsius;
            if (duty < cfg.minDuty) duty = cfg.minDuty;
            if (duty > cfg.maxDuty) duty = cfg.maxDuty;
            if (seedPending && curve != null) {
                seedPending = false;
                int s = curve.dutyAt(CurveConfig.PROFILE_HIGH, celsius);
                duty = Math.max(cfg.minDuty, Math.min(cfg.maxDuty, s));
                lastStep = 0;
            }
            boolean rising = celsius >= cfg.ceilingC;
            long interval = rising ? attackMs
                    : (celsius >= cfg.ceilingC - nearC ? decayNearMs : decayFarMs);
            if (lastStep == 0L) {
                lastStep = nowMs;
            } else if (nowMs - lastStep >= interval) {
                lastStep = nowMs;
                boolean act = true;
                if (antiWindup) {
                    double d = trend();
                    // Do not add fan while the light engine is already coming down, and do
                    // not remove it while it is still climbing. The threshold is set well
                    // above the trend window's own noise floor.
                    if (!Double.isNaN(d)) {
                        double fl = trendNoise();
                        if (rising && d < -fl) act = false;
                        if (!rising && d > fl) act = false;
                    }
                }
                if (act) {
                    duty = rising ? Math.min(cfg.maxDuty, duty + 1)
                                  : Math.max(cfg.minDuty, duty - 1);
                }
            }
            return duty;
        }
    }

    /** Curve feedforward plus a clamped integral trim -- section 5a's structure. */
    static final class Trim extends Walk {
        int trim = 0;
        final int clamp;
        Trim(String label, int clamp, boolean aw) {
            super(label, true);
            this.clamp = clamp;
            this.antiWindup = aw;
        }
        public void start(LinearConfig cfg, CurveConfig c, int seedDuty, double celsius) {
            super.start(cfg, c, seedDuty, celsius);
            trim = 0;
        }
        public int step(LinearConfig cfg, double celsius, long nowMs) {
            if (histN < hist.length) hist[histN++] = celsius;
            int ff = curve.dutyAt(CurveConfig.PROFILE_HIGH, celsius);
            boolean rising = celsius >= cfg.ceilingC;
            long interval = rising ? attackMs
                    : (celsius >= cfg.ceilingC - nearC ? decayNearMs : decayFarMs);
            if (lastStep == 0L) {
                lastStep = nowMs;
            } else if (nowMs - lastStep >= interval) {
                lastStep = nowMs;
                boolean act = true;
                if (antiWindup) {
                    double d = trend();
                    if (!Double.isNaN(d)) {
                        double fl = trendNoise();
                        if (rising && d < -fl) act = false;
                        if (!rising && d > fl) act = false;
                    }
                }
                if (act) trim += rising ? 1 : -1;
                if (trim > clamp) trim = clamp;
                if (trim < -clamp) trim = -clamp;
            }
            duty = Math.max(cfg.minDuty, Math.min(cfg.maxDuty, ff + trim));
            return duty;
        }
    }

    // ------------------------------------------------------------------ the run

    static final class Result {
        int peak, settledLo, settledHi, maxTick;
        double settledMeanDuty, settledMeanTemp, reachSec, peakTemp, secsOver50;
        boolean reached;
    }

    static Result run(Ctl c, LinearConfig cfg, CurveConfig curve, double ambient,
                      int startDuty, int seconds, long seed) {
        java.util.Random rng = new java.util.Random(seed);
        double fast = ambient + rise(startDuty), slow = fast;
        c.start(cfg, curve, startDuty, fast);
        Result r = new Result();
        r.peak = startDuty;
        r.peakTemp = -999;
        r.settledLo = 999; r.settledHi = -999;
        int prev = -1;
        double sumD = 0, sumT = 0;
        int n = 0;
        int settleFrom = seconds - 1200;      // judge on the last 20 minutes
        for (int t = 0; t <= seconds; t++) {
            double temp = FAST_SHARE * fast + (1 - FAST_SHARE) * slow;
            double measured = temp + rng.nextGaussian() * NOISE_SD;
            int duty = c.step(cfg, measured, t * 1000L);
            if (prev >= 0) r.maxTick = Math.max(r.maxTick, Math.abs(duty - prev));
            prev = duty;
            r.peak = Math.max(r.peak, duty);
            r.peakTemp = Math.max(r.peakTemp, temp);
            if (duty > 50) r.secsOver50++;
            if (!r.reached && Math.abs(temp - cfg.ceilingC) <= 0.5) {
                r.reached = true;
                r.reachSec = t;
            }
            if (t >= settleFrom) {
                r.settledLo = Math.min(r.settledLo, duty);
                r.settledHi = Math.max(r.settledHi, duty);
                sumD += duty; sumT += temp; n++;
            }
            double target = ambient + rise(duty);
            fast += (target - fast) * (1 - Math.exp(-1.0 / TAU_FAST));
            slow += (target - slow) * (1 - Math.exp(-1.0 / TAU_SLOW));
        }
        r.settledMeanDuty = sumD / n;
        r.settledMeanTemp = sumT / n;
        return r;
    }

    public static void main(String[] args) {
        CurveConfig curve = new CurveConfig();
        LinearConfig cfg = new LinearConfig();
        cfg.sanitise();

        // ---- guard rail 1: the paraphrase must match the shipping controller -----------
        System.out.println("== guard rail 1: does the paraphrase match the real FanLinear?");
        int mismatch = 0;
        for (double amb : new double[]{22, 25, 27, 30}) {
            Real real = new Real(true);
            Walk para = new Walk("paraphrase", true);
            double f1 = amb + rise(46), s1 = f1, f2 = f1, s2 = f1;
            LinearConfig off = withGate(cfg, 0);
            real.start(off, curve, 46, f1);
            para.start(off, curve, 46, f1);
            java.util.Random r1 = new java.util.Random(7), r2 = new java.util.Random(7);
            for (int t = 0; t <= 3600; t++) {
                double t1 = FAST_SHARE * f1 + (1 - FAST_SHARE) * s1 + r1.nextGaussian() * NOISE_SD;
                double t2 = FAST_SHARE * f2 + (1 - FAST_SHARE) * s2 + r2.nextGaussian() * NOISE_SD;
                int d1 = real.step(withGate(cfg, 0), t1, t * 1000L);
                int d2 = para.step(withGate(cfg, 0), t2, t * 1000L);
                if (d1 != d2) mismatch++;
                double g1 = amb + rise(d1), g2 = amb + rise(d2);
                f1 += (g1 - f1) * (1 - Math.exp(-1.0 / TAU_FAST));
                s1 += (g1 - s1) * (1 - Math.exp(-1.0 / TAU_SLOW));
                f2 += (g2 - f2) * (1 - Math.exp(-1.0 / TAU_FAST));
                s2 += (g2 - s2) * (1 - Math.exp(-1.0 / TAU_SLOW));
            }
        }
        System.out.printf("   ticks where paraphrase and shipping controller disagree: %d%n%n",
                mismatch);

        // ---- guard rail 2: reproduce the hardware overshoot of 2026-09-07 -------------
        System.out.println("== guard rail 2: reproduce the observed hardware overshoot");
        System.out.println("   hardware, 17:33-17:35: seeded to 38, climbed to 53 in ~80 s,");
        System.out.println("   true equilibrium about 45.");
        double ambObs = 52.0 - rise(45);
        Result obs = run(new Real(true), cfg, curve, ambObs, 46, 3600, 11);
        System.out.printf("   model: peak duty %d, settles %d-%d, holds %.2f C  (ambient %.1f C)%n",
                obs.peak, obs.settledLo, obs.settledHi, obs.settledMeanTemp, ambObs);
        System.out.printf("   true duty holding %.1f C at this ambient: %.1f%n%n",
                cfg.ceilingC, dutyHolding(ambObs, cfg.ceilingC));

        // ---- the sweep ---------------------------------------------------------------
        Walk aw = new Walk("C  seed + anti-windup (30 s trend)", true);
        aw.antiWindup = true;
        Walk awNo = new Walk("D  anti-windup, no seed", false);
        awNo.antiWindup = true;
        Walk slow = new Walk("E  seed + 15 s attack", true);
        slow.attackMs = 15000;
        Walk slow2 = new Walk("F  seed + 20 s attack + anti-windup", true);
        slow2.attackMs = 20000; slow2.antiWindup = true;
        Ctl[] all = {
            new Real(true), new Real(false),
            aw, awNo, slow, slow2,
            new Trim("G  feedforward + trim +-15", 15, false),
            new Trim("H  feedforward + trim +-15 + anti-windup", 15, true),
        };

        System.out.println("== sweep: 3600 s from a cold-ish start, judged on the last 20 min");
        System.out.printf("%-40s %6s %7s %8s %8s %7s %6s%n",
                "variant", "peak", "swing", "settled%", "holds C", "reach", "tick");
        for (double amb : new double[]{24, 27, 30}) {
            double ideal = dutyHolding(amb, cfg.ceilingC);
            System.out.printf("%n   ambient %.0f C   (the duty that truly holds %.1f C is %.1f)%n",
                    amb, cfg.ceilingC, ideal);
            for (Ctl c : all) {
                Result r = run(c, cfg, curve, amb, 46, 3600, 11);
                System.out.printf("   %-38s %5d %6d %8.1f %8.2f %6s %5d%n",
                        c.name(), r.peak, r.settledHi - r.settledLo, r.settledMeanDuty,
                        r.settledMeanTemp,
                        r.reached ? String.format("%.0fs", r.reachSec) : "never", r.maxTick);
            }
        }

        // ---- parameter sweep: what attack rate and trend window are actually best? ----
        System.out.println();
        System.out.println("== parameter sweep, judged at 30 C where the windup shows");
        System.out.println("   'over50' is seconds spent above duty 50 -- audible time.");
        System.out.printf("%n   %-34s %5s %7s %6s %8s %8s %7s%n",
                "attack / anti-windup / window", "peak", "peakC", "swing", "settled%", "over50", "reach");
        double amb = 30.0;
        java.util.List<String> ranked = new java.util.ArrayList<String>();
        for (long atk : new long[]{5000, 10000, 15000, 20000, 30000}) {
            for (int win : new int[]{0, 15, 30, 60}) {
                Walk w = new Walk("", true);
                w.attackMs = atk;
                w.antiWindup = win > 0;
                w.trendWindow = win == 0 ? 30 : win;
                String nm = String.format("%2ds  %-12s %s", atk / 1000,
                        win > 0 ? "anti-windup" : "plain", win > 0 ? win + " s" : "-");
                Result r = run(w, cfg, curve, amb, 46, 3600, 11);
                System.out.printf("   %-34s %5d %7.2f %6d %8.1f %8.0f %7s%n", nm, r.peak,
                        r.peakTemp, r.settledHi - r.settledLo, r.settledMeanDuty, r.secsOver50,
                        r.reached ? String.format("%.0fs", r.reachSec) : "never");
                if (r.settledHi - r.settledLo <= 3 && r.reached && r.maxTick <= 1) {
                    ranked.add(String.format("%5d  %s", r.peak, nm));
                }
            }
        }
        java.util.Collections.sort(ranked);
        System.out.println();
        System.out.println("   acceptable (swing <= 3, reaches the ceiling, 1 point per tick),");
        System.out.println("   quietest peak first:");
        for (int i = 0; i < Math.min(6, ranked.size()); i++) {
            System.out.println("     " + ranked.get(i));
        }

        // ---- robustness: the clamp is tuned to one condition, so sweep the room -----
        System.out.println();
        System.out.println("== robustness across the room, which is where a tuned clamp breaks");
        System.out.printf("   %-32s %6s %6s %6s %8s %9s %s%n",
                "variant", "room", "peak", "swing", "settled%", "holds C", "verdict");
        for (double a2 : new double[]{24, 27, 30, 33, 35}) {
            double ideal2 = dutyHolding(a2, cfg.ceilingC);
            Walk best = new Walk("5 s attack + anti-windup 60 s", true);
            best.antiWindup = true; best.trendWindow = 60;
            Ctl[] cand = {
                new Real(true),
                best,
                new Trim("feedforward + trim +-20 + AW", 20, true),
                new Trim("feedforward + trim +-25 + AW", 25, true),
            };
            String[] nm = {"shipped (5 s, no anti-windup)", "5 s attack + anti-windup 60 s",
                           "feedforward + trim +-20 + AW", "feedforward + trim +-25 + AW"};
            for (int i = 0; i < cand.length; i++) {
                if (cand[i] instanceof Walk && !(cand[i] instanceof Trim)) {
                    Walk w = (Walk) cand[i];
                    w.antiWindup = true; w.trendWindow = 60; w.attackMs = 5000;
                }
                Result r = run(cand[i], cfg, curve, a2, 46, 5400, 11);
                String v = !r.reached ? "CANNOT REACH the ceiling"
                        : (r.settledHi - r.settledLo) > 3 ? "swing too big"
                        : r.maxTick > 1 ? "steps more than 1" : "ok";
                System.out.printf("   %-32s %6.0f %6d %6d %8.1f %9.2f %s%n",
                        nm[i], a2, r.peak, r.settledHi - r.settledLo, r.settledMeanDuty,
                        r.settledMeanTemp, v);
            }
            System.out.printf("   %-32s        (true answer at this room: %.1f %%)%n", "", ideal2);
        }

        // ---- the settled swing is the decay interval's job, so sweep that -----------
        System.out.println();
        System.out.println("== settled swing vs decay interval, with anti-windup 60 s on");
        System.out.printf("   %-12s %6s %6s %8s %9s %8s%n",
                "decay", "room", "swing", "settled%", "holds C", "reach");
        for (long dec : new long[]{60000, 90000, 120000}) {
            for (double a3 : new double[]{24, 27, 30}) {
                Walk w = new Walk("", true);
                w.antiWindup = true; w.trendWindow = 60; w.attackMs = 5000;
                w.decayNearMs = dec;
                Result r = run(w, cfg, curve, a3, 46, 5400, 11);
                System.out.printf("   %-12s %6.0f %6d %8.1f %9.2f %8s%n",
                        (dec / 1000) + " s", a3, r.settledHi - r.settledLo,
                        r.settledMeanDuty, r.settledMeanTemp,
                        r.reached ? String.format("%.0fs", r.reachSec) : "never");
            }
        }

        // ---- the 2x2 that actually matters, every arm the shipping class --------------
        System.out.println();
        System.out.println("== seed x trend-gate, 12 noise seeds each, all arms the real FanLinear");
        System.out.printf("   %-26s %6s %8s %8s %10s %9s %8s%n",
                "variant", "room", "peakAvg", "peakMax", "swingAvg", "settled%", "holdsC");
        String[] nm = {"seed, gate off", "seed, gate 90 s",
                       "no seed, gate off", "no seed, gate 90 s"};
        for (double a4 : new double[]{24, 27, 30, 33}) {
            for (int v = 0; v < 4; v++) {
                boolean seed = v < 2;
                int gate = (v % 2 == 0) ? 0 : 90;
                LinearConfig cc = withGate(cfg, gate);
                double pSum = 0, sSum = 0, dSum = 0, tSum = 0;
                int pMax = 0, seeds = 12;
                for (int k = 0; k < seeds; k++) {
                    Result r = run(new Real(seed), cc, curve, a4, 46, 5400, 100 + k);
                    pSum += r.peak; pMax = Math.max(pMax, r.peak);
                    sSum += r.settledHi - r.settledLo;
                    dSum += r.settledMeanDuty; tSum += r.settledMeanTemp;
                }
                System.out.printf("   %-26s %6.0f %8.1f %8d %10.2f %9.1f %8.2f%n",
                        nm[v], a4, pSum / seeds, pMax, sSum / seeds, dSum / seeds, tSum / seeds);
            }
            System.out.println();
        }

        // ---- seeding only earns its keep when the start is far from the answer -------
        System.out.println();
        System.out.println("== does the seed help? swept over where the walk starts from");
        System.out.println("   (46 flatters no-seed at 27 C, since the answer there is 44.8)");
        System.out.printf("   %-18s %6s %7s %9s %9s %9s%n",
                "start duty", "room", "seed?", "peakAvg", "reachAvg", "swingAvg");
        for (int start : new int[]{30, 46, 55, 83}) {
            for (double a5 : new double[]{24, 27, 30}) {
                for (int sd = 0; sd < 2; sd++) {
                    double pSum = 0, rSum = 0, sSum = 0;
                    int seeds = 8, got = 0;
                    for (int k = 0; k < seeds; k++) {
                        Result r = run(new Real(sd == 1), withGate(cfg, 90), curve,
                                a5, start, 5400, 200 + k);
                        pSum += r.peak; sSum += r.settledHi - r.settledLo;
                        if (r.reached) { rSum += r.reachSec; got++; }
                    }
                    System.out.printf("   %-18d %6.0f %7s %9.1f %9s %9.2f%n",
                            start, a5, sd == 1 ? "seed" : "no", pSum / seeds,
                            got > 0 ? String.format("%.0fs", rSum / got) : "never",
                            sSum / seeds);
                }
            }
            System.out.println();
        }

        // ---- and does a bigger clamp rescue feedforward + trim? ----
        System.out.println();
        System.out.println("== feedforward + trim, clamp swept (it could not reach at +-15)");
        for (int clamp : new int[]{15, 20, 25, 30}) {
            Result r = run(new Trim("", clamp, true), cfg, curve, amb, 46, 3600, 11);
            System.out.printf("   clamp +-%-3d peak %3d  swing %d  settled %.1f%%  holds %.2f C  %s%n",
                    clamp, r.peak, r.settledHi - r.settledLo, r.settledMeanDuty,
                    r.settledMeanTemp, r.reached ? "reaches" : "NEVER REACHES THE CEILING");
        }
    }
}
