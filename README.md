# FanLab

A replacement fan controller for the **Philips Screeneo U4** (model SCN350, firmware 1.7.1).

The stock projector runs its fan at 59–70 % in Presentation mode and audibly cycles between
those two speeds. This replaces that with a curve that holds a **steady 38–40 %** in the
same conditions, and **30 %** — inaudible at arm's length — in every other brightness mode.

It installs as an ordinary app from a USB stick. Nothing is flashed, no partition is
written, and it is removable from Settings.

---

## The problem

The Screeneo U4's fan controller is a five-rung staircase, polled every 15 seconds, with
**no hysteresis** — it uses the same thresholds going up as coming down. Its first rung
fires at 46 °C and jumps Presentation straight from its floor to 70 %.

The machine's natural equilibrium in a normal room lands almost exactly on that 46 °C
boundary. So it crosses it, jumps to 70, cools, drops back, warms, and crosses again —
indefinitely. Measured over a simulated hour against the real thermal response:

```
stock ladder : 232 duty changes, 232 of them >= 5 points, worst jump 15 points
this curve   :  25 duty changes,   0 of them >= 5 points, worst jump  1 point
```

**The complaint is not that the fan is too fast. It is that the fan is indecisive.** A
continuous curve removes that failure structurally: there is no boundary left to cross.

## What it does

- **A continuous temperature → fan-speed curve**, evaluated every second against the LED
  thermistor, with no thresholds and no steps.
- **A flat shelf at 30 %** below 48 °C, so in normal use the fan does not move at all.
- **One curve shared by all four brightness modes.** The optics care about temperature,
  not about which mode produced it, so changing brightness causes **no fan step** — the
  measured jump on a mode change is 1 duty point, against 10 for a per-mode design.
- **A 0.8 °C deadband and a 1-point-per-4-seconds rate limit**, so even a real change is
  inaudible as a change. Rate limiting exists to hide drift the listener did not cause, so
  it is deliberately bypassed when the controller is *handed* a duty someone else chose —
  after a reboot, a mode change, or a fail-safe — converging in about 15 seconds instead of
  crawling for seven minutes.
- **Test count: 1613**, run on the host as part of every build.
- **An SoC guard**, because the sensor driving the fan cannot see the processor. It adds
  fan only above 70 °C on the die and can never subtract any. See
  [The SoC guard](#the-soc-guard).
- **Fail-safe high.** Every error path writes 83 %, never a low value.
- **Automatic handback.** The app owns the switch that disables the stock controller and
  re-arms it whenever it stops driving, so the projector cannot be left unmanaged.
- **Optional CSV telemetry** to internal storage and any mounted USB stick at once,
  size-capped and self-pruning. See [Telemetry](#telemetry).

## Measured results

Steady state at 24 °C ambient, on the unit this was developed against:

| mode | stock | this curve | LED temp |
|---|---|---|---|
| Presentation | 59, cycling to 70 | **38–40** | 52 °C |
| Normal | 48 | **30** | 44 °C |
| Eco | 43 | **30** | 40 °C |
| Super Eco | 43 | **30** | 35 °C |

An 18-minute settle, logged on hardware, walking up as the machine warmed:

```
  0m   fan 35   50.1 C
  3m   fan 38   51.3 C
  8m   fan 39   52.0 C
 18m   fan 40   52.1 C     5 changes, every one a single point
```

## Install

**No PC, no cable, no flashing.** The projector has an APK installer built in.

1. Copy `release/fanlab-system.apk` onto a USB stick
2. Plug it into the projector
3. Open **AppInstaller** from the launcher and select the file
4. Open **FanLab** and press the takeover control

To undo it: open FanLab and press **RESTORE STOCK FAN CONTROL**, or uninstall from
Settings — but read [docs/safety.md](docs/safety.md) first, because the order matters.

There is also an adb route for development, in [docs/deploy.md](docs/deploy.md).

## The SoC guard

The fan is driven by one sensor: a thermistor on the LED assembly. That is what the stock
firmware uses and it is what this curve uses, and it has a blind spot. Switching UHD
processing on moves the SoC die about **11 °C** and moves the LED thermistor **half a
degree** — the video pipeline and the light engine are different heat sources, and only
one of them is measured. A load that heats the processor is, to the fan, invisible.

`thermal_zone0` (pll) is the only SoC zone with cooling devices bound to it. They are
bound at **75 °C**, and what they do there is drop CPU and GPU frequency. So the cost of
that blind spot is dropped frames, not damaged hardware — the SoC protects itself
regardless of what the fan does.

### What the fan can actually do about it

Worth establishing before designing anything, and worth measuring rather than inferring.
A 48-minute sweep with UHD processing **on**, Presentation, six holds at duty
40 → 50 → 62 → 83 → 30 → 40. Duty 40 is held first *and* last, so if the SoC load had
drifted during the run the two would disagree; they closed to within 0.5 °C on every
channel. Each hold is fitted to an exponential rather than averaged, because a hold that
has not settled has an end value that is simply wrong.

| duty | LED | **pll** | ddr | sar |
|---:|---:|---:|---:|---:|
| 30 | 57.7 | **69.0** | 72.6 | 64.0 |
| 40 | 52.4 | **64.2** | 67.3 | 59.2 |
| 50 | 49.2 | **61.6** | 64.8 | 56.5 |
| 62 | 46.3 | **57.8** | 61.0 | 52.9 |
| 83 | 43.7 | **54.4** | 57.5 | 49.7 |

Across duty 30 → 83 the die swings **14.6 °C** against the LED thermistor's 14.0. The fan
has at least as much grip on the processor as on the sensor that commands it — the die is
not thermally isolated from the airflow, it simply is not measured.

From duty 40, where the machine actually sits at 24 °C:

| to duty | 50 | 62 | 83 |
|---|---:|---:|---:|
| °C off the die | 2.6 | **6.4** | 9.8 |

Where it spends that authority is lopsided. Per duty point the die moves 0.48 °C at the
bottom of the range, 0.26–0.32 through the middle, and 0.16 above duty 62. That last
figure is why the guard stops at 62: the remaining 21 points to full speed buy 3.4 °C, and
62 is already "way too loud".

### The design

```
duty = min(socGuardMaxDuty, curve(LED) + gain × (pll − 70))      when pll > 70
duty = curve(LED)                                                otherwise
```

Three properties, each load-bearing:

- **Additive, not a floor.** An absolute floor has to climb from the minimum duty back up
  to whatever the curve already wanted before it achieves anything, and it arrives at its
  knee as a step — the same discontinuity the stock ladder parks on. Adding to the curve's
  own output contributes nothing at the knee, rises continuously from there, and spends
  every point it asks for.
- **It can only raise the fan.** The obvious design drives the curve from
  `max(LED, SoC − offset)`, which lets a cool die argue the duty *down* below what the LED
  thermistor is asking for. Making the guard strictly additive means arming it cannot make
  the machine hotter or the controller less safe, and the worst a wrong guard number can do
  is cost noise.
- **It is inert in normal use.** At the operating point the die sits at 64.2 °C — 10.8 °C
  under its trip — and the knee is at 70. Working the measured plant forward, the die does
  not reach the knee until roughly a **30–33 °C room**, and at 33 °C the guard adds about
  one duty point. A guard that fires in ordinary conditions is not a guard, it is a second
  curve, and a noisy one.

Defaults: knee **70 °C**, gain **2.0** duty points per degree, ceiling **62**, deadband
**1.5 °C** (wider than the curve's 0.8, because a die sensor is noisier and faster-moving
than a thermistor bolted to the chassis). Engaging is slew-limited like everything else.

### Does it hunt?

Adding a second sensor to a loop tuned never to move is the obvious way to reintroduce the
oscillation this project exists to remove, so it gets a test rather than an argument.
`tools/CurveSim.java` runs the shipping controller against the measured two-pole plant.
The die has its own measured rise table and its own lag rather than being modelled as the
LED trace plus a constant — the two sensors have similar gain but not identical dynamics,
and a stability test that cannot see a phase difference between the two loops is not
testing the thing that would oscillate. Sweeping the unseen SoC load from inert through
part-engaged to saturated, at 24 °C and 30 °C ambient: **zero duty changes in the last 50
minutes of every run.** The model also reproduces the hardware, predicting 64.4 °C on the
die at duty 39 where the sweep measured 64.2.

Loop gain, with the measured authority: 2.0 duty/°C × 0.26–0.32 °C/duty = **0.52–0.64**.

Measured on hardware by moving the knee below the current die temperature: the guard
engaged at `+10@65.0`, duty went 40 → 47, the die fell to 63.0, the boost shrank as it
cooled, duty eased back to 46, and moving the knee back released it cleanly.

### What it does not do

It buys 0.5–5 °C, depending on how far past the knee the die has gone. In simulation
against the measured plant that moves the onset of throttling by about 2 °C of unseen SoC
load at 24 °C ambient, and rather more at 30 °C where the curve has already raised the fan
on its own. It does **not** prevent throttling on a machine that is genuinely overloaded:
the fan has 9.8 °C of total authority from the operating point and the guard deliberately
spends only two-thirds of it. It shaves the peak. The SoC's own throttling remains the
actual protection, exactly as it was before this existed — and throttling costs frames,
not hardware.

Disarm it with `--ez socguard false`, or tune it with `--ei socstart`, `--ef socgain`,
`--ei socmax`, `--ef sochyst`.

## Telemetry

Logging is on by default; the FanLab screen turns it off. One row per sample is written
to **every** working destination at once — `/sdcard/FanLab/fanlab.csv` and the same path on any USB volume
mounted at the time. Every line is flushed, so a stick can be pulled without losing the
row before it.

| | |
|---|---|
| Rate | one row per second while anything is happening; otherwise one every 10 s |
| Size cap | 4 MB per file, then it rolls to `fanlab-<epoch>.csv` |
| History | the newest 6 rolled files are kept, so 28 MB per destination at most |

Routine rows are decimated but events never are: a write, a fail-safe, a mode change, a
foreign write to `fan_ctrl` or a resync is always logged, at full rate, whatever the
heartbeat interval is.

### Columns

```
epoch_ms,iso_local,adc,degC,prop_led_temp,fan_ctrl,rgblevel,led_status,
profile,mode,desired,wrote,note,soc_pll_c,soc_ddr_c,soc_sar_c
```

`adc` is the raw 12-bit thermistor count and `degC` is that count through the
Steinhart-Hart fit; `prop_led_temp` is what the stock firmware believes, for comparison.
`desired` is what the controller asked for and `wrote` is what it actually wrote — they
differ when a write is refused or suppressed, and that difference is the point of having
both. Empty means "not read this tick", never zero.

The three `soc_*` columns are the SoC die sensors. They are logged because **the fan
cannot see them**: it is driven by the LED thermistor alone, so a load that heats the SoC
and not the light engine — switching UHD processing on moves the SoC about 11 °C and the
LED thermistor half a degree — is invisible to the controller. Only `soc_pll_c` has
cooling devices bound to it, at 75 °C; the other two are monitoring.

If a future version adds a column, the existing file is rolled aside rather than appended
to, so no file ever contains rows of two different widths.

## How the curve works

Every temperature has a fan speed. That is the whole controller.

```
  fan
  speed
   83 |                                                                #####
   74 |                                                     ###########
   60 |                                           ###########
   45 |                                ###########
   30 |################################
      +---------------------------------------------------------------------
       38        43        48        53        58        63        68
                          LED temperature, C
```

It is a **loop**: the fan changes the temperature, and the temperature changes the fan. The
machine slides along that line until it reaches the one point where both are true at once.
Nobody chose 38 % — 38 is where the projector's thermal response crosses the curve.

Three modes land on the flat shelf, so their fan speed is pinned and their temperature
floats. Presentation lands on the slope, so it trims itself a little as the room changes.

Full derivation, the measured thermal plant, and the stability analysis:
**[docs/curve.md](docs/curve.md)**.

## What the reverse-engineering established

Summarised; the detail is in [docs/findings.md](docs/findings.md).

- **46 °C is not a component limit.** Nothing in the DMD, display-controller or LED
  datasheets constrains anything near it. Philips stated publicly in 2022 that the
  Presentation fan speed was raised because heat was distorting the optical engine and
  softening the image — a focus-stability concern, which is observable and reversible.
  Tested on this curve, cold against heat-soaked: **no observable change in focus.** One
  observation by eye at 24 °C, so it shows no drift rather than bounding a slow one —
  but it is the failure mode the whole safety argument turns on, and it was looked for.
- **No DMD temperature is readable from userspace.** The display controller's own
  `Read System Temperature` register responds but returns a hard zero. Separately, the
  board's device tree declares a temperature device at i2c `0x1c` named `dlp_i2c_tmp` —
  with **no driver bound anywhere in the kernel**, and on a bus node that is root-only. So
  the limit is a missing driver and a permission, not absent silicon. What that part is,
  and whether it responds, is unknown.
- **What the fan controller can read is the LED-board thermistor** (SAR ADC channel 2) and
  three SoC die sensors. That is the whole set available to an app.
- **Fan authority is strongly non-linear**: 0.06 °C per duty point at 80 %, rising to
  0.60 at 35 %. A curve designed against the average figure will be wrong at the quiet end.
- **Philips shipped this same trade themselves** on the sibling PicoPix Max in 2020 —
  raising the target temperature in exchange for 7 dB less fan noise, landing at 50 °C
  steady and 53 °C worst case, and never rolling it back.

## How the tests work

`app/test/FanLabTest.java` is a plain Java program — no JUnit, no Android — that runs on
the host as part of every build. **546 assertions**, and the build refuses to produce an
APK if any fail.

It covers four things:

1. **The pure logic**, exhaustively: the thermistor conversion against the framework's own
   arithmetic, the curve's interpolation and clamps, monotonicity, and that every output
   over −100 to 200 °C is a legal duty.
2. **Every failure path goes high.** Bad reads, missing config, unparseable preferences,
   an unreadable node — each is asserted to produce 83, never a low value.
3. **The bug itself, reproduced.** It models the stock ladder and this curve against the
   same dithering input and asserts stock changes speed while the curve does not.
4. **A simulated hour** of both controllers, asserting the curve never moves more than one
   point at a time and that it is measurably quieter than stock.

There is also `tools/CurveSim.java`, which drives the **real** controller class against the
measured thermal response in the time domain, and `tools/equilibria.py`, which checks a
candidate curve has exactly one stable operating point per mode and that no ramp is
narrower than the hysteresis band.

## Warnings

**Read [docs/safety.md](docs/safety.md) before installing.** In brief:

- This runs your projector **hotter than the manufacturer does** — about 52 °C where stock
  holds 44–46 °C. That is the trade being made, deliberately, and it is not reversible by
  wishing.
- **The DMD's temperature cannot be measured on this hardware.** The ceiling used here is
  55 °C, chosen because that is the temperature at which the stock controller itself
  demands maximum fan. It is an empirical line, not one a datasheet endorses.
- The cost you can expect is a small, reversible loss of red LED output and a slightly
  cooler white point.
- **Revert before uninstalling.** Uninstalling while the app is driving leaves the stock
  controller disabled, and that setting survives a reboot.
- Everything here is specific to **firmware 1.7.1**. Property names, sysfs paths and the
  offsets behind the analysis are all version-dependent.
- This is not endorsed by or affiliated with Philips or Screeneo Innovation SA.

## Building from source

```powershell
cd app
.\build.ps1 -Clean
```

`aapt2 → javac → d8 → apksigner`, driven by one PowerShell script. No Gradle. Set
`ANDROID_SDK_ROOT` and `JAVA_HOME` if they are not in the default Windows locations.

**Signing keys are not included.** The system build must be signed with the AOSP platform
key, because that is the key this firmware happens to use — see
[docs/findings.md](docs/findings.md). Supply your own copy as `app/keys/platform.pk8` and
`app/keys/platform.x509.pem`; the build generates its own debug keystore for the plain
variant on first run.

## Repository layout

| | |
|---|---|
| `app/` | the application: source, manifests, resources, host tests, build script |
| `tools/` | measurement and deployment tooling — see [docs/measuring.md](docs/measuring.md) |
| `docs/` | curve derivation, findings, safety, deployment, measuring, measurement conditions |
| `release/` | the signed APK |
| `final_curve.txt` | the deployed curve, in the app's own encoding |

## Licence

MIT — see [LICENSE](LICENSE).
