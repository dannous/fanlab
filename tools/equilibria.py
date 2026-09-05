"""Find ALL the equilibria of a curve against the plant, not just one.

`solve_curve.py` iterates duty <- curve(plant(duty)) to a fixed point. That is fine for a
gentle curve and quietly wrong for a steep one: iteration converges to whichever
equilibrium it happens to fall into, reports it as "the" answer, and cannot see a second
one or notice that there is none.

That matters here because the failure this whole project exists to fix is a limit cycle.
A curve steep enough to overshoot its own shelf can leave the system with two stable
points and nowhere to rest between them, and the fan will hunt between them forever --
the stock bug wearing different clothes.

So: scan duty across the whole range, evaluate  g(d) = curve(ambient + rise(d)) - d,  and
report every sign change. One crossing is what a well-behaved curve looks like. Zero or
two-plus is a design error.

    python equilibria.py --curve "v1,..." [--ambient 24,27,30]
"""
import sys
from solve_curve import PLANT, MODES, PROFILE_OF, parse_curve, duty_at, rise_at, audible


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
            # linear interpolation onto the crossing
            span = g - prev_g
            root = prev_d if abs(span) < 1e-12 else prev_d + (0.0 - prev_g) * (d - prev_d) / span
            r, extrap = rise_at(mode, root)
            # g decreasing through zero => a perturbation is pushed back => stable
            out.append({"duty": root, "temp": ambient + r,
                        "stable": span < 0.0, "extrapolated": extrap})
        prev_d, prev_g = d, g
        d += step
    # A curve pinned at its floor for the whole range never crosses; the floor is then
    # the operating point and the plant simply sits wherever it lands.
    if not out:
        r, extrap = rise_at(mode, cfg["min"])
        if r is not None and duty_at(cfg, profile, ambient + r) <= cfg["min"]:
            out.append({"duty": float(cfg["min"]), "temp": ambient + r,
                        "stable": True, "extrapolated": extrap, "floor": True})
    return out


def ramp_check(cfg):
    """The constraint that actually bounds curve steepness -- ramp width vs hysteresis.

    Curve steepness is not bounded by loop gain. A slew-limited controller on a lagged
    plant does not oscillate merely because the gain exceeds one, and driving the real
    controller against the measured plant produces no hunting below about 19 duty points
    per C -- with transport delay to 180 s, sampling to 60 s, plant poles from 5 to 800 s,
    or ADC quantisation added.

    What does break is geometric, and nothing else checks it: the hysteresis band is
    applied to the *input temperature*, so a rising segment narrower than the deadband has
    no resting state that fits inside it. The held temperature can never settle within the
    segment, and the output swings between its endpoints. The constraint is therefore

        (width of any rising segment, in C)  >>  hysteresisC

    A ratio below about 2 is dangerous; below 1 it is guaranteed to misbehave.
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
                # CurveConfig.sanitise() forces the knees strictly ascending, so a real
                # config cannot get here -- but a curve being SEARCHED has not been
                # sanitised yet, and a zero-width segment is an infinitely steep ramp
                # rather than a division to crash on. Report it as unusable.
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
    args = sys.argv[1:]
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
