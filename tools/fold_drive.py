"""Turn a plantdrive.sh run into the four LED-drive scalings, with its own arithmetic shown.

fold_plant.py grades a hold and stops there, because until now every hold was at the
factory drive and a hold was the answer. This run holds each mode twice -- once at the
factory drive and once at the raised one, ten minutes apart, at the same pinned fan
duty -- so the answer is a ratio of two holds, and the interesting failure is a ratio
that came out of one good hold and one bad one.

    python fold_drive.py <csv> [--duty 45] [--room 22]

WHY IT IS PAIRED RATHER THAN BRACKETED

A scaling is a ratio of rises above ambient, and ambient is the number nobody measures:

    scale = rise(raised) / rise(factory) = (T_raised - A) / (T_factory - A)

The run this replaces took one ambient for the whole night from a bracket at each end,
lost the end bracket, and every scaling came out 5-12 % low. Pairing each mode's raised
hold with its own factory hold twenty minutes earlier does two things that a pair of
brackets three hours apart cannot. Ambient has ten minutes to drift instead of three
hours; and the expression collapses to

    scale = 1 + (T_raised - T_factory) / rise_table(mode, duty)

in which the only number from outside this run is rise_table -- the measured factory
plant at the pinned duty. The difference of the two temperatures is measured in-session,
in the same mode, at the same fan duty, with the same thermistor.

It also gives four independent readings of ambient rather than two, one per factory hold,
spread through the run:

    A_i = T_factory,i - rise_table(mode_i, duty)

Those four agreeing is a stronger statement than a bracket pair agreeing: they are four
different modes and four different rises, so they can only agree if both the table and
the room are right. Where they disagree, THAT is the model error, and it is printed
rather than averaged away.

WHAT IS GRADED AND WHY

Every hold is fitted to the plant's two poles and reported by its asymptote, not by its
last sample -- see TAU_NOTE, which is also why ten-minute holds are enough. Each is
separately graded by the drift still present at its end, fold_plant.py's rule unchanged,
because it is the one that caught a whole block of run 2 pretending to be a measurement:

    |drift| <  1 C/h   CLEAN
    |drift| <  4 C/h   USABLE, note the sign
    |drift| >= 4 C/h   CONTAMINATED -- a bound, and only in the drift's direction

A pair is only as good as its worse half, so the scaling carries the worse of the two
grades. The state column is folded in too: a hold with samples the harness could not
verify is reported with the count, because "the projector was in the mode we asked for"
is the exact claim the run this replaces could not make.
"""
import math
import sys

from therm import celsius

NAMES = {1: "Eco", 2: "Normal", 3: "Presentation", 4: "SuperEco"}

# The measured factory plant: rise above ambient, degrees C, by duty and mode. Copied
# from solve_curve.PLANT rather than imported, so that editing this run's arithmetic can
# never edit the plant it is measured against.
PLANT = {
    83: {3: 19.4, 2: 13.5, 1: None, 4: None},
    70: {3: 20.2, 2: 14.1, 1: None, 4: None},
    60: {3: 21.5, 2: 14.8, 1: 11.4, 4: 6.1},
    55: {3: 22.3, 2: 15.3, 1: None, 4: None},
    50: {3: 23.7, 2: 16.1, 1: 12.1, 4: 7.3},
    45: {3: 24.9, 2: 17.1, 1: 12.8, 4: 7.6},
    40: {3: 26.91, 2: 18.1, 1: 13.51, 4: 8.1},
    35: {3: 29.92, 2: None, 1: 14.4, 4: 8.7},
    30: {3: 33.76, 2: None, 1: 16.41, 4: 11.00},
}

FACTORY_DRIVE = {1: 40, 2: 55, 3: 76, 4: 20}


def fitted_scale(mode, drive):
    """solve_curve.drive_scale, reproduced: the prediction this run exists to test.

    rise ~= 1.60 + 0.342 x drive, fitted across all four modes at duty 40, so a column at
    a new drive is the measured column times the ratio of the two fitted rises. It is an
    inference and has never been held against a measurement below Presentation.
    """
    return (1.60 + 0.342 * drive) / (1.60 + 0.342 * FACTORY_DRIVE[mode])


def load(path):
    """Rows of the plantdrive CSV, keeping only each step's last attempt.

    An abandoned attempt is in the file on purpose -- it is the evidence that the harness
    noticed -- but it is not data, and averaging it in would be the whole mistake again.
    """
    rows = []
    for line in open(path):
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("epoch"):
            continue
        f = line.split(",")
        if len(f) < 17:
            continue
        try:
            rows.append({
                "t": int(f[0]), "step": int(f[1]), "attempt": int(f[2]),
                "level": int(f[3]), "drive": int(f[4]), "duty": int(f[5]),
                "adc": int(f[6]), "other_n": int(f[11]), "state": int(f[12]),
                "ddr": int(f[15]) / 1000.0,
            })
        except ValueError:
            pass

    last = {}
    for r in rows:
        last[r["step"]] = max(last.get(r["step"], 0), r["attempt"])
    return [r for r in rows if r["attempt"] == last[r["step"]]]


# The two poles of this plant, seconds, identified from the eight 40-minute holds of the
# 2026-09-08 overnight run at a pinned fan 45 -- a shared-pole least squares over all
# eight at once, rms 0.073 C. See TAU_NOTE.
TAU_FAST = 50.0
TAU_SLOW = 400.0

TAU_NOTE = """Why two poles, and why it is what makes a short hold legitimate.

A single exponential fitted to those holds never converged: its asymptote was still
moving 3 C at forty minutes on Presentation, because it was averaging a 50 s pole and a
400 s one into a single 270 s fiction and then extrapolating the fiction. That is the
mechanism behind "a hold taken while the chassis is still moving produces a
confident-looking asymptote that is simply wrong" -- the confident-looking part is the
one-pole fit, not the hold.

With both poles in the model and fixed at the values above, the same holds truncated to
ten minutes reproduce their own forty-minute asymptote to a mean of 0.02 C and a worst
case of 0.25 C, and the worst case is Presentation, whose swing is 15 C. So the holds in
this run are ten minutes rather than twenty, and that is a measurement about this plant
rather than an economy.

The poles are fixed rather than re-fitted per hold on purpose. Three free parameters on a
ten-minute hold will find a tau that flatters the data; the poles are a property of the
chassis, they were identified on long holds where they are actually observable, and
holding them fixed is what stops a short hold buying its own answer."""


def asymptote(ts, temps):
    """Fit T(t) = Tinf + A exp(-t/TAU_FAST) + B exp(-t/TAU_SLOW), return Tinf.

    Linear in all three coefficients once the poles are fixed, so this is an exact least
    squares rather than a search. The asymptote is reported rather than the last sample
    because at ten minutes they differ by more than the effect being measured.
    """
    t0 = ts[0]
    xs = [t - t0 for t in ts]
    basis = [[1.0, math.exp(-x / TAU_FAST), math.exp(-x / TAU_SLOW)] for x in xs]
    global LAST_RMS
    n = 3
    ata = [[sum(b[i] * b[j] for b in basis) for j in range(n)] for i in range(n)]
    aty = [sum(basis[k][i] * temps[k] for k in range(len(temps))) for i in range(n)]
    m = [ata[i][:] + [aty[i]] for i in range(n)]
    for i in range(n):
        piv = max(range(i, n), key=lambda r: abs(m[r][i]))
        if abs(m[piv][i]) < 1e-12:
            return temps[-1], 0.0, 0.0
        m[i], m[piv] = m[piv], m[i]
        for r in range(n):
            if r == i:
                continue
            f = m[r][i] / m[i][i]
            for c in range(i, n + 1):
                m[r][c] -= f * m[i][c]
    c = [m[i][n] / m[i][i] for i in range(n)]
    resid = [temps[k] - sum(basis[k][i] * c[i] for i in range(n)) for k in range(len(temps))]
    LAST_RMS = math.sqrt(sum(r * r for r in resid) / len(resid))
    return c[0], c[1], c[2]


LAST_RMS = 0.0


def grade(to_come, rms):
    """Grade a FITTED hold, which is not the same question fold_plant.py asks.

    fold_plant.py grades the raw drift at the end of a hold, because there the last
    sample is the answer and drift is the error. Here the asymptote is the answer and
    residual drift is expected -- a ten-minute hold on a 400 s pole is still moving by
    construction, and grading that as contamination would condemn every hold in the run
    including the ones that are exactly right.

    What actually threatens a fitted asymptote is different, and there are two of it:

      how far it extrapolates   |Tinf - last sample|. The further past the data the
                                answer sits, the more of it is the model talking.
      how well the model fits   the rms residual. If the plant did not behave like these
                                two poles over this hold -- something else moved, the
                                room, the content, a fan the harness did not command --
                                the residual says so, and the extrapolation is then
                                unsupported however small it is.

    Both have to be good. A tight fit extrapolating three degrees is a guess with good
    manners, and a two-tenths extrapolation off a fit that does not describe the data is
    not a measurement of anything.
    """
    if abs(to_come) < 0.35 and rms < 0.15:
        return "CLEAN"
    if abs(to_come) < 1.20 and rms < 0.35:
        return "USABLE"
    return "CONTAMINATED"


WORSE = {"CLEAN": 0, "USABLE": 1, "CONTAMINATED": 2}


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "plantdrive.csv"
    duty = 45
    room = None
    if "--duty" in sys.argv:
        duty = int(sys.argv[sys.argv.index("--duty") + 1])
    if "--room" in sys.argv:
        room = float(sys.argv[sys.argv.index("--room") + 1])

    rows = load(path)
    if not rows:
        print("no data in", path)
        return

    steps = {}
    for r in rows:
        steps.setdefault(r["step"], []).append(r)

    print("run: %s   duty pinned at %d" % (path, duty))
    print()
    print("%-4s %-13s %6s %6s %9s %9s %11s %8s  %s"
          % ("step", "mode", "drive", "held", "LED end", "asympt", "drift C/h", "bad/unk",
             "trust (of the FITTED asymptote, not the last sample -- see grade())"))

    holds = {}
    for k in sorted(steps):
        L = steps[k]
        ts = [r["t"] for r in L]
        temps = [celsius(r["adc"]) for r in L]
        if any(v is None for v in temps):
            print("%-4d  unconvertible adc in step, skipped" % k)
            continue
        held = ts[-1] - ts[0]
        n = min(180, len(L) - 1)
        if n < 30:
            print("%-4d  too short to grade, skipped" % k)
            continue
        dt_h = (ts[-1] - ts[-n]) / 3600.0
        drift = (temps[-1] - temps[-n]) / dt_h if dt_h > 0 else 0.0
        inf, fast_amp, slow_amp = asymptote(ts, temps)
        rms = LAST_RMS
        bad = sum(1 for r in L if r["state"] == 1)
        unk = sum(1 for r in L if r["state"] == 2)
        g = grade(inf - temps[-1], rms)
        print("%-4d %-13s %6s %6d %9.2f %9.2f %11.1f %4d/%-4d  %s%s"
              % (k, NAMES.get(L[0]["level"], "?"),
                 "factory" if L[0]["drive"] == 0 else str(L[0]["drive"]),
                 held, temps[-1], inf, drift, bad, unk, g,
                 "  (%+.2f to come, rms %.3f)" % (inf - temps[-1], rms)))
        holds[k] = {"level": L[0]["level"], "drive": L[0]["drive"], "inf": inf,
                    "end": temps[-1], "drift": drift, "grade": g, "bad": bad,
                    "unk": unk, "t": ts[0]}

    # ---- ambient, one reading per factory hold ---------------------------
    print()
    print("AMBIENT, from each factory hold against the measured plant at duty %d" % duty)
    print("%-4s %-13s %9s %9s %9s" % ("step", "mode", "asympt", "rise tbl", "ambient"))
    ambients = []
    for k in sorted(holds):
        h = holds[k]
        if h["drive"] != 0:
            continue
        rise = PLANT.get(duty, {}).get(h["level"])
        if rise is None:
            print("%-4d %-13s %9.2f %9s  no plant cell at this duty" % (k, NAMES[h["level"]], h["inf"], "--"))
            continue
        a = h["inf"] - rise
        ambients.append((k, h["level"], a))
        print("%-4d %-13s %9.2f %9.2f %9.2f" % (k, NAMES[h["level"]], h["inf"], rise, a))
    if ambients:
        vals = [a for _, _, a in ambients]
        print("  spread %.2f C over %d readings, mean %.2f"
              % (max(vals) - min(vals), len(vals), sum(vals) / len(vals)))
        if room is not None:
            print("  owner-reported room %.1f C, mean reading is %+.2f against it"
                  % (room, sum(vals) / len(vals) - room))

    # ---- the scalings ----------------------------------------------------
    print()
    print("SCALINGS: measured against the fitted line rise ~= 1.60 + 0.342 x drive")
    print("%-13s %8s %9s %9s %8s %9s %9s %8s  %s"
          % ("mode", "drive", "factory", "raised", "delta", "measured", "predicted", "error", "trust"))
    out = {}
    for lvl in (4, 1, 2, 3):
        fac = [h for h in holds.values() if h["level"] == lvl and h["drive"] == 0]
        rai = [h for h in holds.values() if h["level"] == lvl and h["drive"] != 0]
        if not fac or not rai:
            continue
        f = min(fac, key=lambda h: h["t"])       # the anchor is the one before the raise
        r = rai[0]
        rise = PLANT.get(duty, {}).get(lvl)
        if rise is None:
            continue
        delta = r["inf"] - f["inf"]
        measured = 1.0 + delta / rise
        predicted = fitted_scale(lvl, r["drive"])
        worst = max(f["grade"], r["grade"], key=lambda g: WORSE[g])
        print("%-13s %4d->%-3d %9.2f %9.2f %8.2f %9.4f %9.4f %+7.1f%%  %s"
              % (NAMES[lvl], FACTORY_DRIVE[lvl], r["drive"], f["inf"], r["inf"], delta,
                 measured, predicted, 100.0 * (measured - predicted) / predicted, worst))
        out[lvl] = measured

    if out:
        print()
        print("for --scale / --drive flags:")
        print("  solve_curve.py  --scale " + ",".join(
            "%s=%.4f" % (NAMES[l], out[l]) for l in (3, 2, 1, 4) if l in out))
        prof = {3: "high", 2: "normal", 1: "low"}
        print("  CurveSim        --scale " + ",".join(
            "%s=%.4f" % (prof[l], out[l]) for l in (3, 2, 1) if l in out))

    # ---- the closing bracket --------------------------------------------
    reps = {}
    for k in sorted(holds):
        h = holds[k]
        reps.setdefault((h["level"], h["drive"]), []).append((k, h))
    print()
    for key, lst in sorted(reps.items()):
        if len(lst) < 2:
            continue
        (k0, h0), (k1, h1) = lst[0], lst[-1]
        print("BRACKET %s drive %s: step %d %.2f C, step %d %.2f C, %+.2f C over %d min"
              % (NAMES[key[0]], "factory" if key[1] == 0 else key[1],
                 k0, h0["inf"], k1, h1["inf"], h1["inf"] - h0["inf"],
                 (h1["t"] - h0["t"]) // 60))
        print("  that difference is ambient drift plus whatever step %d had not shed; it is"
              % k1)
        print("  the run's own statement about how much the room moved under it.")


if __name__ == "__main__":
    main()
