# Measuring your own projector

The curve in this repository was derived from one unit. It should work on yours unchanged —
it is a feedback controller closed on temperature, so a projector that runs hotter simply
gets more fan — but if you want to verify or re-derive it, this is how.

Everything here needs adb. See [deploy.md](deploy.md) for how to get a shell.

## What the tools do

| tool | what it is for |
|---|---|
| `therm.py` | ADC ↔ °C, reproducing the platform's own arithmetic exactly |
| `fanlab.sh` | device-side harness: holds a plan of fan duties, logs at 1 Hz, always restores |
| `fit_run2.py` | segments a run into holds and estimates each steady state |
| `fold_plant.py` | grades each hold by its end-of-hold drift — the important one |
| `solve_curve.py` | the measured thermal plant, and a fixed-point solver |
| `equilibria.py` | finds **all** equilibria of a curve, plus the ramp-width check |
| `CurveSim.java` | drives the real controller class against the plant, in the time domain |
| `watch.sh` | acceptance test: counts fan-speed **changes** over a period |

## Running a measurement

Stop the app driving first, or two controllers will fight:

```bash
adb shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver \
    -a com.daleygames.fanlab.CONFIG --ei mode 0
adb push tools/fanlab.sh /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/fanlab.sh
adb shell "sh /data/local/tmp/fanlab.sh /data/local/tmp/run.csv 3:40:600 3:35:600 3:30:600"
```

Each step is `LEVEL:DUTY:SECONDS`, where LEVEL is the brightness mode (1 Eco, 2 Normal,
3 Presentation, 4 Super Eco) or 0 to leave it alone. The harness disables the stock
controller for its own lifetime only, aborts on over-temperature, and restores on **every**
exit path including a lost adb connection.

Then:

```bash
adb pull /data/local/tmp/run.csv
python tools/fold_plant.py run.csv --ambient 24
```

## The mistake to avoid

**Every mode change and every large duty change starts a thermal transient**, and a hold
taken while the chassis is still moving produces a confident-looking asymptote that is
simply wrong. The LED trace alone will not reveal it — it looks like a clean settling curve
with a respectable residual.

`fold_plant.py` grades each hold by the drift still present at its end:

```
mode           duty   held   LED end     rise   drift C/h  trust
SuperEco         40    775     33.97     9.97       -15.9  CONTAMINATED -- too high
Eco              40    735     37.51    13.51        -0.8  CLEAN
```

Anything above about 4 °C/hour is a bound, not a measurement, and only in the direction the
drift points. Order your holds so the machine warms monotonically, and give it a settling
step after any mode change.

This is not hypothetical: it is how one run's entire Normal block became an upper bound
while looking like data, and the second temperature sensor is what exposed it.

## Checking a curve before deploying it

```bash
python tools/equilibria.py --curve "$(cat final_curve.txt)"
```

It reports, per mode and per ambient, every **stable** operating point, and flags:

- **more than one stable point** — the fan will hunt between them, which is the original bug
  in a new costume;
- **no stable point** — the same problem;
- **a rising segment narrower than the hysteresis band** — the held temperature cannot
  settle inside it, so the output oscillates between the segment's endpoints.

Use this rather than `solve_curve.py` alone: that one iterates to *a* fixed point and will
happily report it while a second exists.

## Verifying on the hardware

```bash
bash tools/watch.sh 10
```

Ten minutes of sampling, reporting the number of fan-speed **changes** rather than a mean
and standard deviation. A fan sitting at 47 is inaudible in a way that a fan alternating
45/49 is not, and a summary statistic rates them identically.
