package com.daleygames.fanlab;

import java.util.List;

/**
 * Settings worked out from a sweep: for a chosen fan limit, the brightest LED drive each
 * brightness mode can have and still settle under its target.
 *
 * The four brightness modes are four LED drive levels (20, 40, 55 and 76 at Philips' own
 * brightness), so a sweep that holds one fan speed in all four gives four points of settled
 * temperature against drive. A straight line through them says where any other drive would
 * settle at that fan speed. The line is extended above 76, where heat rises a little faster than
 * drive (measured: 25 % more drive made 31 % more heat), so {@link #MARGIN_C} is kept in hand.
 * If the estimate is still optimistic, Smart quiet dims the picture at the fan limit; nothing
 * here can make the projector run hot.
 *
 * Pure: no hardware, no settings. The caller applies the result.
 */
public final class SweepAdvice {

    /** The least fan: limit 35 %, the ordinary targets. */
    public static final int QUIET = 0;
    /** The coolest LEDs at an acceptable fan: limit 40 %, targets five degrees lower. */
    public static final int COOL = 1;

    /** Degrees C kept in hand between the predicted settling point and the target. */
    static final double MARGIN_C = 2.0;

    /** The ordinary Smart quiet targets by rgblevel 1..4 (Night, Normal, Presentation, Super Eco); as Prefs.defaultTarget. */
    private static final int[] DEFAULT_TARGET = {0, 55, 58, 60, 50};

    public static final class Advice {
        public boolean ok;
        /** Why there is no advice, when {@link #ok} is false. */
        public String why = "";
        public int kind;
        public int limit;
        /** By rgblevel 1..4. */
        public final int[] target = new int[5];
        public final int[] level = new int[5];
        /** Where each mode is expected to settle at {@link #limit}, by rgblevel. */
        public final double[] expectC = new double[5];
        /** True for a mode that is over its target at the limit even at Philips' own brightness. */
        public final boolean[] tight = new boolean[5];
    }

    private SweepAdvice() {
    }

    public static int limitFor(int kind) {
        return kind == COOL ? 40 : 35;
    }

    public static int targetFor(int kind, int rgblevel) {
        int t = DEFAULT_TARGET[rgblevel >= 1 && rgblevel <= 4 ? rgblevel : 2] - (kind == COOL ? 5 : 0);
        return Math.max(45, t);
    }

    /** Where mode {@code lv} settled at {@code duty}: the step at that duty, or a straight line between the tested speeds either side. NaN if the sweep did not cover it. */
    static double tempAt(List<SweepStep> steps, int lv, int duty) {
        SweepStep below = null, above = null;
        for (int i = 0; steps != null && i < steps.size(); i++) {
            SweepStep s = steps.get(i);
            if (s == null || s.rgblevel != lv || !SweepStep.PHASE_STEP.equals(s.phase)
                    || Double.isNaN(SweepSummary.settledC(s))) {
                continue;
            }
            if (s.commandedDuty == duty) {
                return SweepSummary.settledC(s);
            }
            if (s.commandedDuty < duty && (below == null || s.commandedDuty > below.commandedDuty)) {
                below = s;
            }
            if (s.commandedDuty > duty && (above == null || s.commandedDuty < above.commandedDuty)) {
                above = s;
            }
        }
        if (below == null || above == null) {
            return Double.NaN;
        }
        double f = (duty - below.commandedDuty) / (double) (above.commandedDuty - below.commandedDuty);
        return SweepSummary.settledC(below) + f * (SweepSummary.settledC(above) - SweepSummary.settledC(below));
    }

    public static Advice advise(List<SweepStep> steps, int kind) {
        Advice a = new Advice();
        a.kind = kind;
        a.limit = limitFor(kind);
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int n = 0;
        for (int lv = 1; lv <= 4; lv++) {
            double t = tempAt(steps, lv, a.limit);
            if (Double.isNaN(t)) {
                continue;
            }
            double x = LedDrive.STOCK_OTHER[lv];
            sx += x;
            sy += t;
            sxx += x * x;
            sxy += x * t;
            n++;
        }
        if (n < 2) {
            a.why = "The sweep did not measure " + a.limit + " % fan in at least two brightness modes.";
            return a;
        }
        double den = n * sxx - sx * sx;
        double b = den == 0 ? 0 : (n * sxy - sx * sy) / den;
        double c0 = (sy - b * sx) / n;
        if (b <= 0.01) {
            a.why = "The temperatures did not rise with brightness, so there is nothing to work a setting out from.";
            return a;
        }
        for (int lv = 1; lv <= 4; lv++) {
            a.target[lv] = targetFor(kind, lv);
            int stock = LedDrive.STOCK_OTHER[lv];
            // The small addition keeps a result that is a whole number in arithmetic from landing just under it.
            int d = (int) Math.floor((a.target[lv] - MARGIN_C - c0) / b + 1e-6);
            a.tight[lv] = d < stock;
            a.level[lv] = Math.max(stock, Math.min(LedDrive.MAX_LEVEL, d));
            a.expectC[lv] = c0 + b * a.level[lv];
        }
        a.ok = true;
        return a;
    }

    /** The advice in words, Super Eco to Presentation. */
    public static String text(Advice a) {
        String name = a.kind == COOL ? "COOL" : "QUIET";
        if (!a.ok) {
            return name + ": no recommendation. " + a.why + "\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(a.kind == COOL ? "  (cooler LEDs, fan up to " : "  (least fan, up to ").append(a.limit).append(" %)\n");
        boolean tight = false;
        for (int i = 0; i < LedDrive.TIERS; i++) {
            int lv = LedDrive.TIER_RGBLEVEL[i];
            String nm = LedDrive.TIER_NAMES[i];
            sb.append("  ").append(nm);
            for (int k = nm.length(); k < 13; k++) {
                sb.append(' ');
            }
            sb.append("brightness ").append(a.level[lv] < 10 ? " " : "").append(a.level[lv])
                    .append("   target ").append(a.target[lv]).append(" C   expect about ")
                    .append(Sample.fmt1(a.expectC[lv])).append(" C").append(a.tight[lv] ? "  *" : "").append('\n');
            tight |= a.tight[lv];
        }
        if (tight) {
            sb.append("  * over its target at this fan even at Philips' own brightness: the picture will\n")
                    .append("    stay at Philips' level and the fan limit may be lifted in that mode.\n");
        }
        return sb.toString();
    }
}
