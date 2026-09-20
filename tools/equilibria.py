"""Find ALL the equilibria of a curve against the plant, not just one.

Scans duty across the range, evaluates  g(d) = curve(ambient + rise(d)) - d,  and reports
every sign change. One crossing is what a well-behaved curve looks like; zero or two-plus
is a design error, and the fan hunts.

    python equilibria.py --curve "v1,..." [--ambient 24,27,30]
                         [--drive Presentation=90,Normal=70,Eco=50,SuperEco=30]
                         [--rise-offset Normal=2.5]

The plant flags are solve_curve.plant_args: --drive scales a mode's column for a raised
LED drive, and --rise-offset adds to a column first.
"""
import sys
from solve_curve import (PLANT, MODES, PROFILE_OF, parse_curve, duty_at, rise_at, audible,
                         plant_args, plant_note)


def crossings(cfg, mode, ambient, lo=25.0, hi=83.0, step=0.05):
    """Every duty where the curve and the plant agree, with each one's stability."""
    profile = PROFILE_OF[mode]
    out = []
    d = lo
    rise, _ = rise_at(mode, d)
    if rise is None:
        return None
    prev_d = d
    prev_g = duty_at(cfg, profile, ambient + rise) - d
    d += step
    while d <= hi:
        rise, _ = rise_at(mode, d)
        g = duty_at(cfg, profile, ambient + rise) - d
        if (prev_g <= 0.0 < g) or (prev_g >= 0.0 > g) or g == 0.0:
            span = g - prev_g
            root = prev_d if abs(span) < 1e-12 else prev_d + (0.0 - prev_g) * (d - prev_d) / span
            r, extrap = rise_at(mode, root)
            out.append({"duty": root, "temp": ambient + r,
                        "stable": span < 0.0, "extrapolated": extrap})
        prev_d, prev_g = d, g
        d += step
    # A curve pinned at its floor for the whole range never crosses.
    if not out:
        r, extrap = rise_at(mode, cfg["min"])
        if r is not None and duty_at(cfg, profile, ambient + r) <= cfg["min"]:
            out.append({"duty": float(cfg["min"]), "temp": ambient + r,
                        "stable": True, "extrapolated": extrap, "floor": True})
    return out


def ramp_check(cfg):
    """Ramp width vs hysteresis -- the constraint that actually bounds curve steepness.

    The hysteresis band is applied to the *input temperature*, so a rising segment
    narrower than the deadband has no resting state that fits inside it and the output
    swings between its endpoints. Ratio (segment width in C / hysteresisC) below about 2
    is dangerous; below 1 it is guaranteed to misbehave.
    """
    out = []
    t, h = cfg["temps"], cfg["hyst"]
    for p in range(3):
        col = cfg["duty"][p]
        for i in range(1, len(t)):
            rise = col[i] - col[i - 1]
            if rise <= 0:
                continue
            width = t[i] - t[i - 1]
            if width <= 0:
                # A curve being SEARCHED has not been sanitised yet, so a zero-width
                # segment is an infinitely steep ramp rather than a division to crash on.
                out.append({"profile": p, "lo": t[i - 1], "hi": t[i],
                            "slope": float("inf"), "ratio": 0.0})
                continue
            out.append({"profile": p, "lo": t[i - 1], "hi": t[i],
                        "slope": rise / width, "ratio": width / h if h > 0 else 999.0})
    return out


def main():
    curve = None
    ambients = [21, 24, 27, 30, 33]
    ceiling = 55.0
    duty_cap = 50.0
    args = plant_args(sys.argv[1:])
    i = 0
    while i < len(args):
        if args[i] == "--curve":
            curve = args[i + 1]; i += 2
        elif args[i] == "--ambient":
            ambients = [float(x) for x in args[i + 1].split(",")]; i += 2
        elif args[i] == "--ceiling":
            ceiling = float(args[i + 1]); i += 2
        else:
            i += 1
    if curve is None:
        print(__doc__)
        return

    cfg = parse_curve(curve)
    print("curve: %s" % curve)
    if plant_note():
        print(plant_note())
    print("checked against: LED <= %.0f C, operating duty <= %.0f, exactly one equilibrium\n"
          % (ceiling, duty_cap))
    worst = []
    for amb in ambients:
        for mode in MODES:
            cs = crossings(cfg, mode, amb)
            if cs is None:
                continue
            stable = [c for c in cs if c["stable"]]
            tag = ""
            if len(stable) == 0:
                tag = "  *** NO STABLE POINT -- WILL HUNT ***"
            elif len(stable) > 1:
                tag = "  *** %d STABLE POINTS -- CAN HUNT BETWEEN THEM ***" % len(stable)
            for c in stable:
                flags = []
                if c["temp"] > ceiling:
                    flags.append("over %.0fC by %.1f" % (ceiling, c["temp"] - ceiling))
                if c["duty"] > duty_cap:
                    flags.append("over duty %.0f" % duty_cap)
                if flags:
                    worst.append((amb, mode, "; ".join(flags)))
                print("%-5.0fC %-13s duty %5.1f  %5.1f C  %-18s %s%s"
                      % (amb, mode, c["duty"], c["temp"], audible(c["duty"]),
                         "(extrapolated plant)" if c["extrapolated"] else "", tag))
            if not stable:
                print("%-5.0fC %-13s %s" % (amb, mode, tag))
        print()

    print("ramp geometry (width vs the %.1f C hysteresis band):" % cfg["hyst"])
    rc = ramp_check(cfg)
    tight = sorted(rc, key=lambda r: r["ratio"])[:3]
    for r in tight:
        verdict = ("OK" if r["ratio"] >= 3 else
                   "TIGHT" if r["ratio"] >= 2 else
                   "*** TOO NARROW - no resting state fits in this segment ***")
        print("  %2.0f-%2.0f C  %.2f duty/C  width = %.1fx hysteresis   %s"
              % (r["lo"], r["hi"], r["slope"], r["ratio"], verdict))
    print()

    if worst:
        print("outside the design envelope:")
        for amb, mode, why in worst:
            print("  %.0f C ambient, %s: %s" % (amb, mode, why))
    else:
        print("every mode at every ambient is inside the envelope.")


if __name__ == "__main__":
    main()
