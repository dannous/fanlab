"""Fold a run's holds into the plant table, grading each by end-of-hold drift in C/h:
|drift| < 1 CLEAN, < 4 USABLE, otherwise CONTAMINATED (a bound, not a measurement).

    python fold_plant.py <csv> [--ambient 24]
"""
import sys
from therm import celsius

NAMES = {1: "Eco", 2: "Normal", 3: "Presentation", 4: "SuperEco"}


def load(path):
    rows = []
    for line in open(path):
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("epoch"):
            continue
        f = line.split(",")
        if len(f) < 12:
            continue
        try:
            rows.append({"t": int(f[0]), "step": int(f[1]), "level": int(f[3]),
                         "duty": int(f[4]), "adc": int(f[5]), "ddr": int(f[10]) / 1000.0})
        except ValueError:
            pass
    return rows


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "lowmodes.csv"
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

    print("ambient %.1f C\n" % ambient)
    print("%-13s %5s %6s %9s %8s %11s  %s"
          % ("mode", "duty", "held", "LED end", "rise", "drift C/h", "trust"))
    for k in sorted(steps):
        L = steps[k]
        held = L[-1]["t"] - L[0]["t"]
        temps = [celsius(r["adc"]) for r in L]
        # last three minutes, or as much as there is
        n = min(180, len(L) - 1)
        if n < 30:
            continue
        dt_h = (L[-1]["t"] - L[-n]["t"]) / 3600.0
        drift = (temps[-1] - temps[-n]) / dt_h if dt_h > 0 else 0.0
        ddr_drift = (L[-1]["ddr"] - L[-n]["ddr"]) / dt_h if dt_h > 0 else 0.0
        a = abs(drift)
        trust = "CLEAN" if a < 1 else ("USABLE" if a < 4 else "CONTAMINATED")
        note = ""
        if trust == "CONTAMINATED":
            note = " -- too high" if drift < 0 else " -- too low"
        elif trust == "USABLE" and a > 1:
            note = " (a few tenths %s)" % ("high" if drift < 0 else "low")
        print("%-13s %5d %6d %9.2f %8.2f %11.1f  %s%s"
              % (NAMES.get(L[0]["level"], "?"), L[0]["duty"], held, temps[-1],
                 temps[-1] - ambient, drift, trust, note))
        print("%-13s %5s %6s %9s %8s %11.1f  (chassis)"
              % ("", "", "", "", "", ddr_drift))


if __name__ == "__main__":
    main()
