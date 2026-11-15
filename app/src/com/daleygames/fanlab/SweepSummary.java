package com.daleygames.fanlab;

import java.util.ArrayList;
import java.util.List;

/**
 * What a sweep found, in words: where the LED settles at each fan speed in each brightness mode,
 * and the slowest fan that keeps it under a temperature. The JSON report holds the same
 * measurements for a program; this is for the person who ran it. Pure: no hardware.
 */
public final class SweepSummary {

    private SweepSummary() {
    }

    /** Where a step settled: the fitted end point when the fit is good, else the last reading. NaN if neither. */
    static double settledC(SweepStep s) {
        if (s == null) {
            return Double.NaN;
        }
        if (s.fit != null && s.fit.ok && !Double.isNaN(s.fit.tInf)) {
            return s.fit.tInf;
        }
        return s.tEndC;
    }

    /**
     * The slowest duty that keeps the LED at or under {@code targetC}, from one mode's steps.
     * Interpolated between the two tested speeds either side and rounded up. Returns 0 when even
     * the fastest tested speed is over the target, and -duty (negative) when the slowest tested
     * speed is already under it, so the caller can say "N % or less".
     */
    static int dutyFor(List<SweepStep> mode, double targetC) {
        SweepStep under = null;     // slowest tested speed that is at or under the target
        SweepStep over = null;      // fastest tested speed that is over it
        int slowest = Integer.MAX_VALUE;
        for (int i = 0; i < mode.size(); i++) {
            SweepStep s = mode.get(i);
            double c = settledC(s);
            if (Double.isNaN(c)) {
                continue;
            }
            slowest = Math.min(slowest, s.commandedDuty);
            if (c <= targetC) {
                if (under == null || s.commandedDuty < under.commandedDuty) {
                    under = s;
                }
            } else if (over == null || s.commandedDuty > over.commandedDuty) {
                over = s;
            }
        }
        if (under == null) {
            return 0;
        }
        if (over == null || over.commandedDuty >= under.commandedDuty) {
            return under.commandedDuty == slowest && over == null ? -under.commandedDuty : under.commandedDuty;
        }
        double cu = settledC(under), co = settledC(over);
        if (co - cu < 0.01) {
            return under.commandedDuty;
        }
        double d = over.commandedDuty + (co - targetC) / (co - cu) * (under.commandedDuty - over.commandedDuty);
        return Math.min(under.commandedDuty, (int) Math.ceil(d));
    }

    /**
     * The whole summary as plain text.
     *
     * @param targetByLevel the temperature to report a fan speed for, indexed by rgblevel 1..4
     */
    public static String text(List<SweepStep> steps, List<SweepEngine.Marker> markers, int[] targetByLevel) {
        StringBuilder sb = new StringBuilder();
        sb.append("SWEEP RESULTS\n");
        sb.append("White screen, Philips' own brightness. Where the LED settles at each fan speed.\n");
        int shown = 0;
        for (int m = 0; m < SweepPlan.MODE_ORDER.length; m++) {
            int lv = SweepPlan.MODE_ORDER[m];
            List<SweepStep> mode = new ArrayList<SweepStep>();
            for (int i = 0; steps != null && i < steps.size(); i++) {
                SweepStep s = steps.get(i);
                if (s != null && s.rgblevel == lv && SweepStep.PHASE_STEP.equals(s.phase)
                        && !Double.isNaN(settledC(s))) {
                    mode.add(s);
                }
            }
            if (mode.isEmpty()) {
                continue;
            }
            shown++;
            sb.append('\n').append(LedDrive.TIER_NAMES[LedDrive.tierOf(lv)]).append('\n');
            for (int i = 0; i < mode.size(); i++) {
                SweepStep s = mode.get(i);
                sb.append("  fan ").append(s.commandedDuty < 10 ? " " : "").append(s.commandedDuty).append(" %   ")
                        .append(Sample.fmt1(settledC(s))).append(" C");
                if (!SweepStep.END_SETTLED.equals(s.endReason)) {
                    sb.append("   (not settled: ").append(s.endReason).append(")");
                }
                sb.append('\n');
            }
            int target = targetByLevel != null && lv < targetByLevel.length ? targetByLevel[lv] : 55;
            int d = dutyFor(mode, target);
            sb.append("  To stay under ").append(target).append(" C: ");
            if (d == 0) {
                sb.append("more fan than the fastest speed tested.");
            } else if (d < 0) {
                sb.append(-d).append(" % is enough, and it may hold with less (not tested lower).");
            } else {
                sb.append("about ").append(d).append(" % fan.");
            }
            sb.append('\n');
        }
        if (shown == 0) {
            sb.append("\nNothing was measured: the sweep ended before its first step settled.\n");
        }
        int audible = -1, loud = -1;
        for (int i = 0; markers != null && i < markers.size(); i++) {
            SweepEngine.Marker k = markers.get(i);
            if ("audible".equals(k.kind)) {
                audible = audible < 0 ? k.duty : Math.min(audible, k.duty);
            } else if ("loud".equals(k.kind)) {
                loud = loud < 0 ? k.duty : Math.min(loud, k.duty);
            }
        }
        if (audible >= 0 || loud >= 0) {
            sb.append("\nYou marked the fan ");
            if (audible >= 0) {
                sb.append("audible at ").append(audible).append(" %");
            }
            if (loud >= 0) {
                sb.append(audible >= 0 ? ", and " : "").append("clearly loud at ").append(loud).append(" %");
            }
            sb.append(".\n");
        }
        sb.append("\nWith Brightness boost on, every mode runs hotter than this and needs more fan.\n");
        return sb.toString();
    }
}
