"""Where does a given fan curve actually settle, on the measured plant?

A fan curve and a thermal plant are a feedback loop: the curve maps temperature to duty,
the plant maps duty to temperature. The operating point is the fixed point of the round
trip, and it is the only number that matters -- the curve's shape away from it is a
contingency plan, not a description of what you will hear.

This solves that fixed point per brightness mode per ambient, and reports the loop gain,
which is what says whether the point is stable and whether the fan will hunt.

    python solve_curve.py                       # the curve in CURVE.md
    python solve_curve.py --curve "v1,42,..."   # any encoded CurveConfig
    python solve_curve.py --curve ... --drive Presentation=90,Normal=70,Eco=50,SuperEco=30
                                                # the same on a machine with the LED drive
                                                # raised: an inferred plant, see drive_scale
"""
import sys

# Rise above ambient, degrees C, from the fitted steady state at each duty.
#
# Sources, and it matters which: run 1 (ambient 23 C, ~170 s holds) and run 2 (ambient
# 24 C, ~730 s holds, marked R2 below). Where the two overlap they agree to 0.3 C.
#
# Run 1's Presentation rows at duty 35 and 30 were EXTRAPOLATIONS below its swept range,
# and run 2 has now shown them to be optimistic by 1.6 C at duty 35 -- the real plant
# steepens faster than the extension assumed. Any remaining None is a pair nobody has
# held long enough to fit; the solver reports when a fixed point lands on one rather than
# quietly interpolating across it.
PLANT = {
    # duty:  Presentation, Normal, Eco,  SuperEco
    83:     (19.4, 13.5, None, None),
    70:     (20.2, 14.1, None, None),
    60:     (21.5, 14.8, 11.4, 6.1),
    55:     (22.3, 15.3, None, None),
    50:     (23.7, 16.1, 12.1, 7.3),
    45:     (24.9, 17.1, 12.8, 7.6),
    40:     (26.91, 18.1, 13.51, 8.1),
    35:     (29.92, None, 14.4, 8.7),
    30:     (33.76, None, 16.41, 11.00),
}

# Provenance for the run-2 cells, because "measured" is not one thing. Each hold was
# graded by its own end-of-hold drift (see fold_plant.py): a hold taken while the chassis
# is still moving yields a confident-looking asymptote that is simply wrong, and the LED
# trace alone will not tell you.
#
#   Presentation 40   26.91  12 min, chassis warming monotonically      trustworthy
#   Presentation 35   29.92  12 min                                     trustworthy
#   Presentation 30   33.76  12 min                                     trustworthy
#   Eco          40   13.51  drift -0.8 C/h   CLEAN -- and it lands within 0.1 C of
#                            run 1's 13.4, the best cross-run agreement in the project
#   Eco          30   16.41  drift +4.5 C/h   still warming, so this is a LOWER bound;
#                            the true value is somewhat above it
#   SuperEco     30   11.00  drift +2.5 C/h   a few tenths low, usable
#   SuperEco     40   --     drift -15.9 C/h  DISCARDED. Measured first, straight off the
#                            Presentation block, so it absorbed the whole cool-down.
#                            Run 1's 8.1 is retained above; the truth is between 8.1 and
#                            the 9.97 that contaminated reading gave.
#   Normal 40/35      --     both upper bounds for the same reason; run 1's values kept.
#
# The pattern that now holds across THREE independent modes: run 1's extrapolations below
# duty 40 all read low, by 1-4 C, growing as the duty falls.


MODES = ["Presentation", "Normal", "Eco", "SuperEco"]
# Curve profile index for each mode: LOW=0 (Eco and Super Eco), NORMAL=1, HIGH=2.
PROFILE_OF = {"Presentation": 2, "Normal": 1, "Eco": 0, "SuperEco": 0}

# The LED drive each column of PLANT was measured at, percent of the driver maximum.
STOCK_DRIVE = {"Presentation": 76, "Normal": 55, "Eco": 40, "SuperEco": 20}

# Per-mode adjustments to the measured plant, for asking what a curve does on a machine
# that is not the one measured. Both default to "the table as it stands". Set them with
# --drive, --scale or --rise-offset (see plant_args), never by editing here.
#
#   OFFSET  additive, degrees C, applied first. Its one real use is Normal, whose table
#           column is a lower bound: the field log has Normal settling at 46.5 C on duty
#           30 in a 24 C room, about 2.5 C above the table (docs/curve.md, "The floor
#           stops at 47 C").
#   SCALE   multiplicative, applied second, for a different LED drive level.
SCALE = {m: 1.0 for m in MODES}
OFFSET = {m: 0.0 for m in MODES}


def drive_scale(mode, drive):
    """How much hotter a mode runs, at every duty, when its LED drive is raised.

    Fitted across all four modes at duty 40: rise ~= 1.60 + 0.342 x drive, which
    reproduces Normal and Eco to about 1 K. The duty dependence is a single
    multiplicative shape -- the four sensors agree on it within 3 % -- so a column for a
    new drive level is the measured column times the ratio of the two fitted rises.
    Presentation 76 -> 90 is x1.180, Normal 55 -> 70 x1.250, Eco 40 -> 50 x1.220,
    Super Eco 20 -> 30 x1.378.

    This is an INFERENCE from the fit, not a measurement: nothing has yet been held at a
    raised drive. Anything solved with a scale other than 1.0 needs confirming with a
    measured hold before it is relied on.
    """
    return (1.60 + 0.342 * drive) / (1.60 + 0.342 * STOCK_DRIVE[mode])


def _mode_of(key):
    for m in MODES:
        if m.lower() == key.strip().lower():
            return m
    raise SystemExit("unknown mode %r; one of %s" % (key, ", ".join(MODES)))


def plant_args(args):
    """Consume the plant-adjustment flags shared by every solver here; return the rest.

        --drive Presentation=90,Normal=70      LED drive per mode -> SCALE via drive_scale
        --scale Presentation=1.18              a scale factor directly
        --rise-offset Normal=2.5               degrees added to a column before scaling

    Modes not named are left alone, so a flag can raise one mode and no other.
    """
    rest = []
    i = 0
    while i < len(args):
        a = args[i]
        if a in ("--drive", "--scale", "--rise-offset"):
            for pair in args[i + 1].split(","):
                key, val = pair.split("=")
                mode = _mode_of(key)
                if a == "--drive":
                    SCALE[mode] = drive_scale(mode, float(val))
                elif a == "--scale":
                    SCALE[mode] = float(val)
                else:
                    OFFSET[mode] = float(val)
            i += 2
        else:
            rest.append(a)
            i += 1
    return rest


def plant_note():
    """One line saying how the plant differs from the table, or '' if it does not."""
    parts = []
    for m in MODES:
        bits = []
        if OFFSET[m]:
            bits.append("%+.1f C" % OFFSET[m])
        if SCALE[m] != 1.0:
            bits.append("x%.3f" % SCALE[m])
        if bits:
            parts.append("%s %s" % (m, " then ".join(bits)))
    if not parts:
        return ""
    return ("plant adjusted (INFERRED from the drive fit, not measured): "
            + "; ".join(parts))

CURVE_MD = "v1,42,48,52,55,58,62,30,32,42,56,70,83,34,36,44,56,70,83,38,40,46,56,70,83,0.5,0.25,0.12,10,35,83"


def parse_curve(s):
    f = s.split(",")
    assert f[0] == "v1", "not a v1 curve string"
    k = 1
    temps = [int(f[k + i]) for i in range(6)]; k += 6
    duty = []
    for _ in range(3):
        duty.append([int(f[k + i]) for i in range(6)]); k += 6
    hyst = float(f[k]); slew_up = float(f[k + 1]); slew_dn = float(f[k + 2])
    idle = int(f[k + 3]); dmin = int(f[k + 4]); dmax = int(f[k + 5])
    return {"temps": temps, "duty": duty, "hyst": hyst, "slew_up": slew_up,
            "slew_dn": slew_dn, "idle": idle, "min": dmin, "max": dmax}


def duty_at(cfg, profile, degc):
    """CurveConfig.dutyAt, reproduced: piecewise linear, flat outside the end knees."""
    t = cfg["temps"]; col = cfg["duty"][profile]
    if degc <= t[0]:
        v = col[0]
    elif degc >= t[-1]:
        v = col[-1]
    else:
        v = col[-1]
        for i in range(1, len(t)):
            if degc <= t[i]:
                span = t[i] - t[i - 1]
                frac = 1.0 if span <= 0 else (degc - t[i - 1]) / span
                v = col[i - 1] + frac * (col[i] - col[i - 1])
                break
    return max(cfg["min"], min(cfg["max"], v))


def rise_at(mode, duty):
    """Rise above ambient at an arbitrary duty, linear between measured points.

    Extrapolates below the lowest measured duty using the local slope there, which is
    the steepest part of the plant -- flagged by the caller, because the quiet end of
    every curve rests on exactly this extrapolation. OFFSET and SCALE are applied to
    every point first, so a raised drive steepens the extrapolation along with the rest.
    """
    col = MODES.index(mode)
    pts = sorted((d, (v[col] + OFFSET[mode]) * SCALE[mode])
                 for d, v in PLANT.items() if v[col] is not None)
    if not pts:
        return None, True
    if duty >= pts[-1][0]:
        return pts[-1][1], False
    if duty <= pts[0][0]:
        # below the measured range: extend the slope of the lowest measured segment
        (d0, r0), (d1, r1) = pts[0], pts[1]
        slope = (r1 - r0) / (d1 - d0)
        return r0 + slope * (duty - d0), True
    for i in range(1, len(pts)):
        if duty <= pts[i][0]:
            (d0, r0), (d1, r1) = pts[i - 1], pts[i]
            f = (duty - d0) / (d1 - d0)
            return r0 + f * (r1 - r0), False
    return pts[-1][1], False


def solve(cfg, mode, ambient):
    """Iterate duty <- curve(ambient + rise(duty)) to the fixed point."""
    profile = PROFILE_OF[mode]
    duty = 50.0
    extrapolated = False
    for _ in range(400):
        rise, ex = rise_at(mode, duty)
        if rise is None:
            return None
        extrapolated = ex
        temp = ambient + rise
        nxt = duty_at(cfg, profile, temp)
        if abs(nxt - duty) < 1e-4:
            duty = nxt
            break
        duty += 0.5 * (nxt - duty)          # damped, so a steep curve still converges
    rise, extrapolated = rise_at(mode, duty)
    temp = ambient + rise

    # Loop gain = (duty per degree on the curve) x (degrees per duty on the plant).
    # |gain| < 1 is the condition for the fixed point to be stable under iteration.
    eps = 0.25
    d_curve = (duty_at(cfg, profile, temp + eps) - duty_at(cfg, profile, temp - eps)) / (2 * eps)
    r_hi, _ = rise_at(mode, duty + 1)
    r_lo, _ = rise_at(mode, duty - 1)
    d_plant = (r_hi - r_lo) / 2.0
    return {"duty": duty, "temp": temp, "gain": abs(d_curve * d_plant),
            "extrapolated": extrapolated}


AUDIBILITY = [(30, "inaudible"), (35, "barely audible"), (40, "quiet"),
              (45, "acceptable"), (50, "just acceptable"), (55, "too loud"),
              (60, "way too loud"), (101, "way too loud")]


def audible(duty):
    for cap, word in AUDIBILITY:
        if duty <= cap + 0.5:
            return word
    return "?"


def main():
    curve = CURVE_MD
    ambients = [21, 24, 27, 30, 33]
    args = plant_args(sys.argv[1:])
    i = 0
    while i < len(args):
        if args[i] == "--curve":
            curve = args[i + 1]; i += 2
        elif args[i] == "--ambient":
            ambients = [float(x) for x in args[i + 1].split(",")]; i += 2
        else:
            i += 1

    cfg = parse_curve(curve)
    print("curve: %s" % curve)
    if plant_note():
        print(plant_note())
    print()
    print("%-7s %-13s %6s %8s %6s %6s  %s"
          % ("ambient", "mode", "duty", "LED C", "gain", "extrap", "sounds"))
    for amb in ambients:
        for mode in MODES:
            r = solve(cfg, mode, amb)
            if r is None:
                print("%-7.0f %-13s %6s %8s %6s %6s  no plant data" % (amb, mode, "-", "-", "-", "-"))
                continue
            print("%-7.0f %-13s %6.1f %8.1f %6.2f %6s  %s"
                  % (amb, mode, r["duty"], r["temp"], r["gain"],
                     "YES" if r["extrapolated"] else "no", audible(r["duty"])))
        print()
    print("gain < 1 means the operating point is stable and the fan will not hunt.")
    print("extrap=YES means the duty landed below the lowest measured point for that")
    print("mode, so the temperature is an extension of the plant, not a measurement.")


if __name__ == "__main__":
    main()
