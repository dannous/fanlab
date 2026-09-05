package com.daleygames.fanlab;

/**
 * Least-squares fit of a first-order thermal transient.
 *
 * <pre>
 *   dT/dt = (T_inf - T) / tau        =&gt;      T(t) = T_inf + (T_0 - T_inf) * exp(-t/tau)
 * </pre>
 *
 * This is the thing that makes an unattended sweep finish in ninety minutes instead of
 * three hours: you do not have to wait for equilibrium, you fit the transient and
 * extrapolate to it. A step that would take twenty minutes to settle can be called in
 * three or four with a good fit.
 *
 * <h3>How it is solved</h3>
 * The model is linear in {@code (T_inf, A)} once {@code tau} is fixed, and non-linear only
 * in {@code tau}. So: search {@code tau} over a logarithmic grid, and at each candidate
 * solve the two-parameter linear least squares in closed form. Four refinement rounds,
 * each shrinking the bracket twentyfold, land {@code tau} to well under a percent. No
 * matrices, no iteration that can fail to converge, and no library.
 *
 * The fit is a convenience, not the deliverable: the caller logs the full 1 Hz trace
 * regardless, so a better fit can always be done offline. What the fit is <i>used</i> for
 * on the device is deciding when a step has told us what it is going to tell us.
 *
 * Pure Java: no android.* imports, no I/O.
 */
public final class ExpFit {

    /** Fewer samples than this and no fit is attempted at all. */
    public static final int MIN_SAMPLES = 8;

    /** Search bounds for tau, seconds. The LED heatsink is minutes, not hours. */
    public static final double TAU_MIN = 5.0;
    public static final double TAU_MAX = 2000.0;

    /**
     * Below this transient amplitude tau is not identifiable - the data is a flat line
     * plus noise, and any tau fits it equally well. T_inf is still perfectly good (it is
     * the mean), so the fit is reported as usable with {@link Fit#tauIdentifiable} false.
     */
    public static final double MIN_AMPLITUDE_C = 0.15;

    /** The result. Every field is finite or NaN; nothing here is ever infinite. */
    public static final class Fit {
        /** True when a fit was produced. False means every other field is NaN. */
        public boolean ok;
        /** Extrapolated steady-state temperature. This is the number the sweep exists for. */
        public double tInf = Double.NaN;
        /** Fitted amplitude, {@code T_0 - T_inf}. Negative while heating toward equilibrium. */
        public double amp = Double.NaN;
        /** Fitted temperature at t = 0. */
        public double t0 = Double.NaN;
        /** Fitted time constant, seconds. NaN when it could not be identified. */
        public double tau = Double.NaN;
        /** Root-mean-square residual, degrees C. How much to trust the two above. */
        public double rms = Double.NaN;
        /** Samples used. */
        public int n;
        /** False when the transient was too small to pin tau down; tInf is still valid. */
        public boolean tauIdentifiable;
        /** True when the best tau sat on a search bound, i.e. the bracket was too narrow. */
        public boolean tauPinned;
        /** Plain-language reason a fit was refused, or "ok". */
        public String why = "no data";
    }

    private ExpFit() {
    }

    /**
     * Fit {@code y} against {@code t}.
     *
     * @param t seconds, relative to anything; only differences matter.
     * @param y temperature, degrees C.
     * @param n number of leading entries of both arrays to use.
     * @return a {@link Fit}; never null, never throws.
     */
    public static Fit fit(double[] t, double[] y, int n) {
        Fit f = new Fit();
        try {
            if (t == null || y == null) {
                f.why = "no data";
                return f;
            }
            if (n > t.length) {
                n = t.length;
            }
            if (n > y.length) {
                n = y.length;
            }
            if (n < MIN_SAMPLES) {
                f.why = "only " + n + " samples, need " + MIN_SAMPLES;
                return f;
            }
            // Reject anything non-finite rather than letting it poison the sums.
            double span = 0.0;
            double t0ref = t[0];
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(t[i]) || Double.isInfinite(t[i])
                        || Double.isNaN(y[i]) || Double.isInfinite(y[i])) {
                    f.why = "sample " + i + " is not finite";
                    return f;
                }
                double dt = t[i] - t0ref;
                if (dt > span) {
                    span = dt;
                }
            }
            if (!(span > 0.0)) {
                f.why = "all samples share one timestamp";
                return f;
            }

            double loLog = Math.log(TAU_MIN);
            double hiLog = Math.log(TAU_MAX);
            double bestLog = Double.NaN;
            double bestRss = Double.POSITIVE_INFINITY;
            double bestA = Double.NaN;
            double bestB = Double.NaN;

            // One scratch array for the whole search: the basis is recomputed per
            // candidate tau, but keeping it lets the residual be summed exactly rather
            // than through Sum(y^2) - a*Sum(y) - b*Sum(fy), which cancels catastrophically
            // once the fit is good - and a good fit is precisely the case that matters.
            double[] basis = new double[n];
            final int steps = 40;
            double lo = loLog;
            double hi = hiLog;
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i <= steps; i++) {
                    double lt = lo + (hi - lo) * i / steps;
                    double tau = Math.exp(lt);
                    double[] fit = solveAt(t, y, n, t0ref, tau, basis);
                    if (fit == null) {
                        continue;
                    }
                    if (fit[2] < bestRss) {
                        bestRss = fit[2];
                        bestA = fit[0];
                        bestB = fit[1];
                        bestLog = lt;
                    }
                }
                if (Double.isNaN(bestLog)) {
                    f.why = "no usable tau in the search range";
                    return f;
                }
                double w = (hi - lo) / steps;
                lo = Math.max(loLog, bestLog - w);
                hi = Math.min(hiLog, bestLog + w);
                if (!(hi > lo)) {
                    break;
                }
            }

            double tau = Math.exp(bestLog);
            f.ok = true;
            f.n = n;
            f.tInf = bestA;
            f.amp = bestB;
            f.t0 = bestA + bestB;
            f.tau = tau;
            f.rms = Math.sqrt(bestRss / n);
            f.tauIdentifiable = Math.abs(bestB) >= MIN_AMPLITUDE_C;
            f.tauPinned = tau <= TAU_MIN * 1.001 || tau >= TAU_MAX * 0.999;
            if (!f.tauIdentifiable) {
                // The transient is inside the noise. T_inf is the mean and is fine; tau is
                // meaningless and must not be reported as if it were measured.
                f.tau = Double.NaN;
                f.why = "ok (flat: tau not identifiable)";
            } else if (f.tauPinned) {
                f.why = "ok (tau pinned at a search bound)";
            } else {
                f.why = "ok";
            }
            if (Double.isNaN(f.tInf) || Double.isInfinite(f.tInf)) {
                return new Fit();
            }
            return f;
        } catch (Throwable e) {
            // A fit failing must never be able to take a thermal run down.
            Fit bad = new Fit();
            bad.why = "exception: " + e;
            return bad;
        }
    }

    /**
     * Closed-form least squares for {@code y = a + b*exp(-(t-t0)/tau)} at a fixed tau.
     *
     * @return {a, b, rss}, or null if the normal equations are singular at this tau.
     */
    private static double[] solveAt(double[] t, double[] y, int n, double t0ref, double tau,
                                    double[] basis) {
        double sf = 0.0;
        double sff = 0.0;
        double sy = 0.0;
        double sfy = 0.0;
        for (int i = 0; i < n; i++) {
            double e = Math.exp(-(t[i] - t0ref) / tau);
            basis[i] = e;
            sf += e;
            sff += e * e;
            sy += y[i];
            sfy += e * y[i];
        }
        double det = n * sff - sf * sf;
        // A tiny determinant means every exp() collapsed to the same value at this tau,
        // so a and b are not separable. Skip rather than divide by ~0.
        if (!(Math.abs(det) > 1e-12)) {
            return null;
        }
        double a = (sy * sff - sf * sfy) / det;
        double b = (n * sfy - sf * sy) / det;
        if (Double.isNaN(a) || Double.isInfinite(a) || Double.isNaN(b) || Double.isInfinite(b)) {
            return null;
        }
        double rss = 0.0;
        for (int i = 0; i < n; i++) {
            double r = y[i] - (a + b * basis[i]);
            rss += r * r;
        }
        if (Double.isNaN(rss) || Double.isInfinite(rss)) {
            return null;
        }
        return new double[]{a, b, rss};
    }
}
