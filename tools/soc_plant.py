"""Measure the SoC thermal plant directly: what does duty do to the die?

The guard's gain and ceiling were first set from a *ratio* -- the die moved about as many
degrees as the LED thermistor for a given duty change -- taken across holds that were
themselves still drifting, in runs where UHD processing was off. That is inference, not
measurement, and authority in C per duty point scales with dissipated power, so a low-load
run can understate it. This reads a sweep taken under the load that actually matters.

Every hold is fitted, not averaged. A hold that has not settled has an end value that is
simply wrong, and the trace alone will not tell you -- so each channel gets

    T(t) = Tinf + (T0 - Tinf) * exp(-t / tau)

with tau found by grid search and (Tinf, T0) by least squares at each tau. The reported
asymptote is Tinf. Two numbers say whether to believe it: the residual RMS, and how far
the fit had to extrapolate beyond the last sample (`reach`). A fit that extrapolates
several degrees past where the data stopped is an opinion.

    python soc_plant.py <csv> [--ambient 24]
"""
import io
import math
import sys

from therm import celsius

CHANNELS = ("LED", "pll", "ddr", "sar")


def load(path):
    rows = []
    for line in io.open(path, encoding="utf-8", errors="replace"):
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("epoch"):
            continue
        f = line.split(",")
        if len(f) < 12:
            continue
        try:
            rows.append(dict(t=int(f[0]), step=int(f[1]), duty=int(f[4]),
                             LED=celsius(int(f[5])), fan=int(f[6]), level=int(f[7]),
                             pll=int(f[9]) / 1000.0, ddr=int(f[10]) / 1000.0,
                             sar=int(f[11]) / 1000.0))
        except (ValueError, IndexError):
            pass
    return rows


def fit(ts, ys):
    """Return (Tinf, tau, rms). Falls back to a flat mean when no lag fits better."""
    n = len(ts)
    if n < 20:
        return ys[-1], 0.0, 0.0
    t0 = ts[0]
    x = [t - t0 for t in ts]
    best = None
    mean = sum(ys) / n
    flat_rss = sum((y - mean) ** 2 for y in ys)
    best = (flat_rss, mean, 0.0)
    for tau in [30 * (1.15 ** k) for k in range(40)]:
        e = [math.exp(-xi / tau) for xi in x]
        se = sum(e)
        see = sum(v * v for v in e)
        sy = sum(ys)
        sey = sum(e[i] * ys[i] for i in range(n))
        det = n * see - se * se
        if abs(det) < 1e-12:
            continue
        b = (n * sey - se * sy) / det
        a = (sy - b * se) / n
        rss = sum((ys[i] - (a + b * e[i])) ** 2 for i in range(n))
        if rss < best[0]:
            best = (rss, a, tau)
    rss, tinf, tau = best
    return tinf, tau, math.sqrt(rss / n)


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "socsweep.csv"
    ambient = 24.0
    if "--ambient" in sys.argv:
        ambient = float(sys.argv[sys.argv.index("--ambient") + 1])

    rows = load(path)
    if not rows:
        print("no data in", path)
        return

    steps = {}
    for r in rows:
        steps.setdefault(r["step"], []).append(r)

    print("ambient %.1f C, %d samples over %d holds\n"
          % (ambient, len(rows), len(steps)))

    table = []
    for k in sorted(steps):
        L = steps[k]
        held = L[-1]["t"] - L[0]["t"]
        if held < 120:
            continue
        # drop the first 45 s: the fan takes a moment to reach the commanded duty and
        # those samples belong to the previous hold's plant, not this one's
        L = [r for r in L if r["t"] - L[0]["t"] >= 45]
        if len(L) < 30:
            continue
        ts = [r["t"] for r in L]
        row = {"step": k, "duty": L[0]["duty"], "held": held, "level": L[0]["level"]}
        for ch in CHANNELS:
            ys = [r[ch] for r in L]
            tinf, tau, rms = fit(ts, ys)
            row[ch] = dict(inf=tinf, tau=tau, rms=rms, end=ys[-1], reach=tinf - ys[-1])
        table.append(row)

    print("%-5s %5s %6s | %s" % ("step", "duty", "held", "  ".join(
        "%-26s" % (c + ": asym  end  reach") for c in CHANNELS)))
    for row in table:
        cells = []
        for ch in CHANNELS:
            d = row[ch]
            cells.append("%6.1f %5.1f %+6.1f     " % (d["inf"], d["end"], d["reach"]))
        print("%-5d %5d %6d | %s" % (row["step"], row["duty"], row["held"], " ".join(cells)))

    print("\nfit quality (rms C, tau s):")
    for row in table:
        print("  duty %-3d  %s" % (row["duty"], "  ".join(
            "%s rms %.2f tau %4.0f" % (c, row[c]["rms"], row[c]["tau"]) for c in CHANNELS)))

    # --- closure: the same duty measured twice, at the start and at the end ---
    byduty = {}
    for row in table:
        byduty.setdefault(row["duty"], []).append(row)
    print("\nclosure check (same duty, start vs end of run):")
    closed = False
    for duty, L in sorted(byduty.items()):
        if len(L) < 2:
            continue
        closed = True
        for ch in CHANNELS:
            drift = L[-1][ch]["inf"] - L[0][ch]["inf"]
            flag = "ok" if abs(drift) < 1.0 else ("SUSPECT" if abs(drift) < 2.5 else "BAD")
            print("  duty %-3d %-4s %+6.2f C  %s" % (duty, ch, drift, flag))
    if not closed:
        print("  none -- no duty was held twice, so load drift is unmeasured")

    # --- authority: what a duty point is worth, per channel ---
    print("\nauthority, C per duty point (first visit to each duty):")
    seen = {}
    for row in table:
        seen.setdefault(row["duty"], row)
    duties = sorted(seen)
    print("  %-12s %s" % ("segment", "  ".join("%-8s" % c for c in CHANNELS)))
    for i in range(1, len(duties)):
        d0, d1 = duties[i - 1], duties[i]
        cells = []
        for ch in CHANNELS:
            slope = (seen[d0][ch]["inf"] - seen[d1][ch]["inf"]) / (d1 - d0)
            cells.append("%-8.3f" % slope)
        print("  %-12s %s" % ("%d->%d" % (d0, d1), "  ".join(cells)))

    lo, hi = duties[0], duties[-1]
    print("\ntotal authority %d -> %d:" % (lo, hi))
    for ch in CHANNELS:
        print("  %-4s %5.1f C" % (ch, seen[lo][ch]["inf"] - seen[hi][ch]["inf"]))

    # --- the number the guard is actually set from ---
    base = 40
    if base in seen:
        print("\nfrom duty %d (the operating point), what the guard could buy:" % base)
        print("  %-6s %s" % ("duty", "  ".join("%-8s" % c for c in CHANNELS)))
        for d in duties:
            if d <= base:
                continue
            cells = ["%-8.1f" % (seen[base][ch]["inf"] - seen[d][ch]["inf"]) for ch in CHANNELS]
            print("  %-6d %s" % (d, "  ".join(cells)))


if __name__ == "__main__":
    main()
