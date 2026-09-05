"""Live view of an adblab run: segment by step, estimate the steady state of each hold.

Deliberately simple. This is the instrument you watch a run through, not the one you
draw conclusions with -- it fits a single exponential, which is known to under-read the
asymptote on this machine because a slow chassis pole sits underneath the fast one (see
RUNS.md, and adblab/refit_run1.md for the size of that bias). It prints the fitted tau
next to the hold duration so you can see for yourself whether a given number is a
measurement or an extrapolation.

    python fit_run2.py [run2.csv] [--ambient 24]
"""
import sys
import math
from therm import celsius

TAU_GRID = [20 + 5 * i for i in range(120)]      # 20 s .. 615 s


def load(path):
    rows = []
    with open(path) as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("epoch"):
                continue
            f = line.split(",")
            if len(f) < 12:
                continue
            try:
                rows.append({
                    "t": int(f[0]), "step": int(f[1]), "phase": f[2],
                    "level": int(f[3]), "duty": int(f[4]), "adc": int(f[5]),
                    "fan_rb": int(f[6]), "level_rb": int(f[7]), "engine": int(f[8]),
                    "pll": int(f[9]) / 1000.0, "ddr": int(f[10]) / 1000.0,
                    "sar": int(f[11]) / 1000.0,
                })
            except ValueError:
                continue
    return rows


def fit_exp(ts, ys):
    """T(t) = Tinf - (Tinf - T0) exp(-t/tau). Grid over tau, least squares on the rest.

    Returns (Tinf, tau, rmse) or None. For each candidate tau the model is linear in
    (Tinf, A) with basis [1, exp(-t/tau)], so the inner solve is exact and only tau
    needs searching -- which keeps this dependency-free and robust on short segments.
    """
    n = len(ts)
    if n < 30:
        return None
    t0 = ts[0]
    best = None
    for tau in TAU_GRID:
        s11 = s12 = s22 = sy1 = sy2 = 0.0
        for i in range(n):
            e = math.exp(-(ts[i] - t0) / tau)
            s11 += 1.0
            s12 += e
            s22 += e * e
            sy1 += ys[i]
            sy2 += ys[i] * e
        det = s11 * s22 - s12 * s12
        if abs(det) < 1e-12:
            continue
        tinf = (sy1 * s22 - sy2 * s12) / det
        amp = (s11 * sy2 - s12 * sy1) / det
        sse = 0.0
        for i in range(n):
            e = math.exp(-(ts[i] - t0) / tau)
            r = ys[i] - (tinf + amp * e)
            sse += r * r
        rmse = math.sqrt(sse / n)
        if best is None or rmse < best[2]:
            best = (tinf, tau, rmse)
    return best


def main():
    path = "run2.csv"
    ambient = 24.0
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        if args[i] == "--ambient":
            ambient = float(args[i + 1]); i += 2
        else:
            path = args[i]; i += 1

    rows = load(path)
    if not rows:
        print("no data in", path)
        return

    steps = []
    for r in rows:
        if not steps or steps[-1][0]["step"] != r["step"]:
            steps.append([])
        steps[-1].append(r)

    names = {1: "Eco", 2: "Normal", 3: "Presentation", 4: "SuperEco"}
    print("ambient %.1f C, %d samples, %d steps\n" % (ambient, len(rows), len(steps)))
    print("%-13s %4s %6s %6s %7s %7s %6s %6s   %s"
          % ("mode", "duty", "held_s", "T_end", "T_inf", "rise", "tau", "rmse", "verdict"))

    for seg in steps:
        mode = names.get(seg[0]["level"], "?")
        duty = seg[0]["duty"]
        held = seg[-1]["t"] - seg[0]["t"]
        ts = [r["t"] for r in seg]
        ys = [celsius(r["adc"]) for r in seg]
        ys = [y for y in ys if y is not None]
        if len(ys) != len(ts):
            ts = ts[:len(ys)]
        t_end = ys[-1]
        fit = fit_exp(ts, ys)
        if fit is None:
            print("%-13s %4d %6d %7.2f %7s %7s %6s %6s   too short to fit"
                  % (mode, duty, held, t_end, "-", "-", "-", "-"))
            continue
        tinf, tau, rmse = fit
        # A hold shorter than ~2 tau has not shown the asymptote; the number is then an
        # extrapolation off the early curve and should be treated as such.
        verdict = "measured" if held >= 2 * tau else "EXTRAPOLATED (held < 2 tau)"
        if seg is steps[-1]:
            verdict += " [in progress]"
        print("%-13s %4d %6d %7.2f %7.2f %7.2f %6d %6.3f   %s"
              % (mode, duty, held, t_end, tinf, tinf - ambient, tau, rmse, verdict))

    last = rows[-1]
    print("\nlast sample: %s duty=%d  LED %.2f C  SoC pll/ddr/sar %.1f/%.1f/%.1f  engine=%d"
          % (names.get(last["level_rb"], "?"), last["fan_rb"], celsius(last["adc"]),
             last["pll"], last["ddr"], last["sar"], last["engine"]))


if __name__ == "__main__":
    main()
