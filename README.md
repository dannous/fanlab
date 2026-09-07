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
- **Two flat regions, placed where the machine actually sits.** A floor at 30 % below
  47 °C, which is where Eco, Super Eco and Normal live, and a shelf spanning two duty
  points — 38 % to 40 % — from 51 to 55 °C, which is where Presentation lives. Nearly flat
  means the fan speed barely depends on temperature, so five degrees of room drift move it
  by two points. An earlier version had one flat region below 48 °C and claimed the same
  thing; thirty-six hours of field log showed the operating point was three to five degrees
  *above* it, on a 2.14 duty/°C ramp, where every one of its duty changes happened. The
  shelf is now under the operating point, at 0.5 duty/°C. Above 55 °C the curve rises at
  2 duty/°C to arrest a runaway — a slope that costs nothing below 55 °C and was validated
  against a deliberately induced blocked-vent fault.
- **One curve shared by all four brightness modes.** The optics care about temperature,
  not about which mode produced it, so changing brightness causes **no fan step** — the
  measured jump on a mode change is 1 duty point, against 10 for a per-mode design.
- **A 0.8 °C deadband and a 1-point-per-4-seconds rate limit**, so even a real change is
  inaudible as a change. Rate limiting exists to hide drift the listener did not cause, so
  it is deliberately bypassed when the controller is *handed* a duty someone else chose —
  after a reboot, a mode change, or a fail-safe — converging in about 15 seconds instead of
  crawling for seven minutes.
- **Test count: 2989**, run on the host as part of every build.
- **An SoC guard**, because the sensor driving the fan cannot see the processor. It adds
  fan only above 70 °C on the die and can never subtract any. See
  [The SoC guard](#the-soc-guard).
- **Fail-safe high.** Every error path writes 83 %, never a low value.
- **Automatic handback.** The app owns the switch that disables the stock controller and
  re-arms it whenever it stops driving, so the projector cannot be left unmanaged.
- **Optional CSV telemetry** to internal storage and any mounted USB stick at once,
  size-capped and self-pruning. See [Telemetry](#telemetry).
- **A CAIC switch, as an experiment.** One write asks the display controller to run
  Content Adaptive Illumination Control; one write, or a power cycle, undoes it. Whether it
  does anything useful on this board is unknown — see [The CAIC experiment](#the-caic-experiment).

## Measured results

Steady state at 24 °C ambient, on the unit this was developed against:

| mode | stock | this curve | LED temp |
|---|---|---|---|
| Presentation | 59, cycling to 70 | **38** | 51.9 °C |
| Normal | 48 | **30** | 44 °C |
| Eco | 43 | **30** | 40 °C |
| Super Eco | 43 | **30** | 35 °C |

**The shelf, measured on hardware.** Twelve minutes in Presentation while the machine
warmed 3.3 °C, logged at 1 Hz:

```
  0m00   fan 38   50.9 C
  4m00   fan 38   53.1 C
  8m00   fan 38   53.8 C
 12m00   fan 38   54.1 C     the LED rose 3.3 C and the fan did not move once
```

On the ramp this replaced, that same 3.3 °C would have dragged the fan through about seven
duty points.

**Against the previous curve, over 13.9 hours of real settled Presentation time**, replayed
closed-loop against ambient inferred per sample from the field log:

| | modal duty held | duty changes | range |
|---|---|---|---|
| previous curve | 32 % of the time | 1.00 / hour | 36–40 |
| **this curve** | **53 %** | **0.79 / hour** | **37–39** |

Every change in either case is a single duty point. What changed is that the duty now lives
in a three-point band instead of a five-point one, and the operating point sits on flat
ground rather than on a 2.14 duty/°C ramp.

**A deliberately induced fault.** Both vents were covered with cloth until the light engine
reached 61.3 °C — about 8 °C hotter than anything this unit had ever recorded — and then
uncovered. Across 955 samples at 1 Hz spanning the whole excursion:

```
largest single-tick change in fan speed : 1 duty point   (never 2, either direction)
duty direction reversals                : 0              (no hunting, under a fault)
throttling events                       : 0
SoC guard engagements                   : 0              (pll peaked at 61.2 C, knee is 70)
```

The fan climbed 38 → 49 and walked back down, one point at a time throughout. No fan curve
can cool a machine whose vents are blocked, and this one cannot either — what the run
establishes is that the controller degrades smoothly rather than oscillating when the plant
is taken away from it.

## Install

**No PC, no cable, no flashing.** The projector has an APK installer built in, and the app
installs like any other. Nothing is written to a system partition and it uninstalls from
Settings like any other app.

**Which file:** `release/fanlab-system.apk`. That is the one to install.

There are two builds in `release/` and only the first is useful on a projector:

| file | what it is |
|---|---|
| **`fanlab-system.apk`** | **the one you want.** Platform-signed, so it can write the fan node and disable the stock controller |
| `fanlab-plain.apk` | debug-signed, observe-only. It can read and log but cannot drive the fan. For development on a device without the platform key |

### Steps

1. Copy `release/fanlab-system.apk` onto a USB stick
2. Plug the stick into the projector
3. From the launcher open **AppInstaller** and pick the file
4. Open **FanLab** from the launcher
5. Set **Mode** to `CURVE`

That is the whole install. Choosing `CURVE` disables the stock fan controller for you —
there is a separate **Take over** button but you do not need to press it. The fan takes
about fifteen seconds to come down from wherever the stock controller had it, and then it
should not move again.

**Leave `Start automatically after a reboot` on** (it is on by default). Without it the
stock controller takes back over on the next power cycle.

## Using it

The main screen is a list you move through with the remote's up/down, changing a value with
left/right and pressing a button row with OK.

### The controls that matter

| control | what it does |
|---|---|
| **Mode** | `OFF` observes only. `CURVE` is the fan curve — **this is the one to use**. `MANUAL` holds one fixed speed. `LINEAR` holds a temperature instead of a speed (see below) |
| **Curve preset** | `Quiet` / `Balanced` / `Cool` / `Cold`. Quiet is the default and the quietest; each step up adds 5 % fan and buys about 1.5 °C. See [The four presets](#the-four-presets) |
| **Room temperature** | optional. Tells the log what the room actually is, so later analysis is not guessing |
| **Write CSV telemetry** | logging on/off. On by default, self-pruning, see [Telemetry](#telemetry) |
| **Start automatically after a reboot** | leave this on |
| **Re-assert every second** | leave this on. The projector's own brightness code slams a fan preset on brightness changes; this puts it back. It is not cosmetic — see [Warnings](#warnings) |
| **CAIC** | off by default, and nothing to do with the fan. An experiment on the display controller's own LED-power feature; read [The CAIC experiment](#the-caic-experiment) before pressing it. Press again to turn it off; a power cycle turns it off regardless |
| **RELEASE CONTROL** | hands the fan back for now. The stock controller is re-armed, the app stops driving, and CAIC is turned off if it was on |
| **RESTORE STOCK FAN CONTROL** | the permanent undo. Clears the setting that disables the stock controller. **Press this before uninstalling** — see below |

### Which preset

Start on **Quiet** and only move up if the light engine runs hotter than you want it to.
The figures are in [The four presets](#the-four-presets); the short version is that Quiet
keeps the light engine under 55 °C up to a 28 °C room, and each step up extends that by
about 2 °C at the cost of 5 % more fan.

### CURVE or LINEAR

**Use CURVE.** It is the default, it has thirty-six hours of field logging behind it, and
it is the quieter of the two in any room you are likely to be sitting in.

LINEAR holds a *temperature* rather than a fan speed. It is the right choice only if you
care more about a temperature ceiling than about noise — it is quieter than CURVE below
about 24 °C and considerably louder above it. Read
[Read this before choosing it](#read-this-before-choosing-it) first.

### Undoing it

There are two different controls and they do different things. Confusing them is the one
way to leave the projector worse off than you found it.

**To stop the app driving, for now:** press **RELEASE CONTROL**. The stock controller is
re-armed immediately and the app goes back to observing. Expect a few seconds of loud fan —
it hands back at 83 % on purpose, so the machine is never unmanaged in between. Setting
**Mode** to `OFF` does the same thing.

**To uninstall:** press **RESTORE STOCK FAN CONTROL** *first*, then uninstall from Settings.

That second control matters because taking over sets a property,
`persist.sys.fanctrl.by.temperatue = 0`, which lives in `/data` and **survives a reboot and
survives uninstalling the app**. The app re-arms the stock controller on every path where it
gets to run code — a normal stop, a mode change, a force-stop, a crash — but an uninstall
runs no code at all and never returns. So:

| how the app stops | stock controller handed back? |
|---|---|
| Mode → OFF, RELEASE CONTROL, mode change, force-stop, crash | **yes** |
| **uninstall** | **no** |

If it has already happened, the projector is not in danger — the 75 °C over-temperature
shutdown and the kernel's fan-stall watchdog are both untouched, and the fan holds the
kernel's own default rather than stopping. But nothing is responding to temperature. One
line fixes it:

```bash
adb shell setprop persist.sys.fanctrl.by.temperatue 1
```

[docs/safety.md](docs/safety.md) has the full account.

### Driving it from a PC

Everything above is also settable over adb, which is how it is developed and how the curve
is deployed. The full reference is in [docs/deploy.md](docs/deploy.md). The two commands
worth knowing:

```bash
ADB="C:/Users/Gamer/AppData/Local/Android/Sdk/platform-tools/adb.exe"

# read the current state, changes nothing
"$ADB" shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver

# switch preset
"$ADB" shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver \
    --es preset quiet
```

**Use the full component name.** The system build's package is
`com.daleygames.fanlab.system`, and `am broadcast` does not validate components — a
broadcast to the wrong one reports `result=0` and silently does nothing.

The reply is the resulting state, so diff it against what you sent. It says
`curve(REPAIRED)` or `linear(REPAIRED)` when a value was clamped rather than accepted.

The CAIC experiment is `--ez caic true` and `--ez caic false`; the reply's `caic=` field
gives the setting and, on the system build a few seconds later, what the display
controller itself said (`on (read back: on)`). See the next section before sending it.

### The CAIC experiment

**This is not a fan setting and it is off by default.** It is here because the display
controller has a feature that, on paper, cuts LED power without dimming the picture, and
one write turns it on and one write turns it off. It is an experiment with a cheap undo,
not a recommendation.

**What it is.** CAIC — Content Adaptive Illumination Control, TI's *IntelliBright* — runs
in the DLPC3436 display controller. Frame by frame, on content that does not need full
output, it lowers the LED current and raises the mirror duty cycle by the same factor, so
the white point stays where it was while the LEDs draw less. TI's own example is 27 % LED
power saved at constant brightness. Less LED power is less heat at the thermistor this
whole project is built around, which is why it is worth a look.

**Why it is only an experiment here.** The projector ships with it **off** — `caic=0x00`
in every template of the factory settings blob, and nothing in the firmware ever turns it
on. And there is a specific reason to doubt it can do its job on this board: CAIC saves
power by having the controller lower LED current through a TI LED driver, and **this board
has no TI LED driver**. The LED currents are set by the kernel, over SPI, to two MAX20096
drivers the controller does not talk to; the controller's own current registers hold a
placeholder. So turning CAIC on may

- save LED power as designed, if the controller has some path to the drivers nobody found;
- do only the duty-cycle half — a **brighter** image at the same LED power, no saving;
- do nothing visible at all; or
- show artefacts (banding, flicker, a shifted white point) if its lookup tables were never
  calibrated for this engine.

Nothing in the app claims a power saving. The screen says `on (unverified)` until the
controller itself has been asked, and `on (read back: on)` only once it has answered. Only
the system build can hear the answer — it arrives in the kernel log, which needs a
permission the plain build cannot hold — so on the plain build it stays `unverified`
for ever, which is the truth.

**How to try it.** On the screen, the `CAIC` row under *Experiment — display controller*;
press it once for on, again for off. From a PC:

```bash
"$ADB" shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver \
    --ez caic true
# a few seconds later, to see what the controller said
"$ADB" shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver
```

Put up a mostly dark frame with a small bright region and watch it; that is the content
CAIC is designed for. The CSV note column records every write (`caic<-on:start`,
`caic<-on:rgblevel`, `caic<-off:setting`), each change in the read-back, and while it
reads on, the controller's *max available power* word about once a minute
(`caic_maxpower=0x...`) — that number moving with the content is the one piece of evidence
the experiment can produce without a light meter.

**How to undo it.** Any of:

- press the `CAIC` row again, or `--ez caic false` — one write, `w 50 1 0`;
- press **RELEASE CONTROL**, which turns it off along with everything else the app drives;
- `--ez reset`, which turns it off along with the curve;
- **power-cycle the projector.** The controller reloads the factory settings at boot, and
  those have it off. Nothing this switch does is written anywhere that survives a reboot;
  the factory settings partition is deliberately never touched.

The app also turns it off itself whenever it stops running, if it was the one that turned
it on, and re-asserts it on brightness-mode changes and after a resume, where the kernel
re-programs the controller's LED registers.

### If something looks wrong

| symptom | what it is |
|---|---|
| fan cycles slowly between two speeds | something else is writing the fan node. Check `Mode` is `CURVE` and `Re-assert` is on. MANUAL leaves the stock controller armed by design, so it is not usable for a quiet run |
| on Cold, Normal is louder than 30 % | expected above a 23 °C room. Cold starts its rise at 43 °C rather than 47 so that it does not hunt, which puts Normal's operating point on the rise. Quiet, Balanced and Cool all keep Normal at 30 % |
| fan jumps to 83 % and stays | a fail-safe. Every error path writes 83 rather than a low value. Check the Diagnostics screen |
| fan loud for ~15 s after changing mode | expected. Changing away from CURVE hands back at 83 %, and coming back is a slew-limited ramp down |
| a setting did not take, over adb | wrong component name — see above |
| "CSV rows" resets to zero | it counts rows written by the current service instance, not the length of the file. A service restart resets it. The file is fine |

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

### Knowing when it happens anyway

Since the fan cannot rule throttling out, the app records it rather than guessing. All four
cooling devices are read every second and logged, the screen shows `● THROTTLING cpufreq=2`
while it is happening and a running total afterwards, and the CSV carries a note on each
edge:

```
THROTTLING cpufreq=2 gpufreq=1 pll=76.3
throttling cleared after 41s
```

So the question the logs can answer is not "could the machine throttle" but "did it, when,
for how long, and what was the fan doing at the time". Nothing in the stock firmware
records that.

Disarm the guard with `--ez socguard false`, or tune it with `--ei socstart`, `--ef
socgain`, `--ei socmax`, `--ef sochyst`.

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
profile,mode,desired,wrote,note,soc_pll_c,soc_ddr_c,soc_sar_c,
thr_cpufreq,thr_cpucore,thr_gpufreq,thr_gpucore,
session,off_s,room_c,exclusive,catchup,duty_hold_s
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

The four `thr_*` columns are the thermal governor's cooling devices, read straight from
sysfs: 0 means idle, anything higher means CPU or GPU frequency is being reduced **right
now**. That is a fact rather than an inference from temperature, and it is the only way to
tell a machine that is merely warm from one that is dropping frames. Blank means the device
could not be read, which is not the same as zero. They are sampled in every mode, including
with the app not driving, so the curve can be compared against the stock controller on this
too, and a throttled row is never decimated away by the heartbeat.

The last six columns describe the circumstances rather than the measurement, and each
exists because the first thirty-six hours of field log could not answer a question that
was asked of it.

`session` is the run. It steps once per service start and only ever goes up, so
segmentation is exact rather than a guess about which `epoch_ms` gaps were long enough to
count.

`off_s` is non-blank on exactly one row per power-on: how long the light engine had been
off before it came on. **The `degC` and `soc_pll_c` on that row are a direct measurement of
the room** — the one quantity the log cannot otherwise derive, because inferring it from
temperature needs the plant table and the plant table is what field data exists to check.
Whether to believe the reading depends entirely on `off_s`: the thermistor is still 4.7 °C
above its resting value five hours after the engine goes off, so this is recorded and
graded afterwards rather than gated on a threshold that would silently pass a warm one.
`room_c` is the same quantity stated by hand, as the independent check on it, and is blank
until somebody states it.

`exclusive` is 1 only when the curve is driving, the stock ladder is stood down, and
nothing else has written `fan_ctrl` in the last minute. It replaces a filter that had to be
assembled by grepping MANUAL rows, `stock_ladder->*` toggles and `reassert(was N)` events
and then subtracting time ranges by hand. Blank means the kill switch could not be read,
which is not the same as 0.

`catchup` is 1 while the controller is still converging on the curve and 0 once it has
arrived, and `duty_hold_s` is how long the commanded duty has sat still. A duty of 44 on
its way down from a fail-safe 83 and a duty of 44 the curve settled on are the same number
describing opposite situations; these two tell them apart, which is what makes creep
answerable rather than reconstructed.

If a future version adds a column, the existing file is rolled aside rather than appended
to, so no file ever contains rows of two different widths. That has now fired twice, at
16 → 20 and at 20 → 26.

## How the curve works

Every temperature has a fan speed. That is the whole controller.

```
  fan %
   83 |                                                            #########
   68 |                                                     #######
   50 |                                        #############
   40 |                          ##############
   38 |                    ######
   30 |####################
      +--------------------------------------------------------------------
       38       43      47    51    55        60         66        70
                            LED thermistor, C
       |____ floor ____|    |shelf|
            duty 30          38-40
```

It is a **loop**: the fan changes the temperature, and the temperature changes the fan. The
machine slides along that line until it reaches the one point where both are true at once.
Nobody chose 38 % — 38 is where the projector's thermal response crosses the curve.

The shape is **two flat regions joined by a rise**, and both flats are placed on measured
operating points rather than round numbers:

- **Normal, Eco and Super Eco land on the floor.** Their settled thermistor readings are
  46.5, 38.5 and 34.4 °C, all inside it, so all three sit at 30 % and never move.
- **Presentation lands on the shelf**, which spans a single 2-point band across the whole
  51–55 °C range the machine occupies. Five degrees of room drift move the fan two points.
- **Above 55 °C the curve rises at 2 duty/°C** to arrest a runaway. That segment is never
  reached below about a 28 °C room, so it costs nothing in normal use.

The floor edge is at 47 °C rather than 46 because Normal's settled reading reaches 47.1 °C
at its 95th percentile — a floor ending at 46 lifts Normal off 30 % for 96 % of its running
time. That edge comes from the field log, not from the plant table, whose Normal column
reads about 2.5 °C low.

Full derivation, the measured thermal plant, and the stability analysis:
**[docs/curve.md](docs/curve.md)**.

## The four presets

The curve ships as four, selectable on the main screen or by broadcast. Each is the base
curve with a constant added to every knee **above the floor**, clipped at 83 %:

| preset | fan at 24 °C | LED at 24 °C | holds ≤54 °C to | holds ≤55 °C to |
|---|---|---|---|---|
| **Quiet** (default) | 38 % | 51.9 °C | 26.7 °C room | 28.0 °C room |
| Balanced | 41 % | 50.4 °C | 28.8 °C room | 30.0 °C room |
| Cool | 43 % | 49.8 °C | 30.1 °C room | 31.2 °C room |
| Cold | 46 % | 48.6 °C | 31.5 °C room | 32.6 °C room |

Two design points worth stating, because both were arrived at the hard way:

**The floor is not offset.** All four presets idle at 30 %. Below the floor edge the light
engine is cool enough that extra fan buys almost nothing — measured, Cold's +15 bought 3.4 °C
in Super Eco on a thermistor already sitting at 35 °C. Since Normal, Eco and Super Eco spend
their whole lives on the floor, offsetting it would make them louder for no useful cooling.
The offset applies only where the ceiling is actually in question.

**Cold's floor edge is 43 °C, where the other three share 47 °C**, and that is the one place
the presets are not congruent. With the floor pinned at 30 and Cold's shelf at 53, a rise
over 47–51 °C would be **5.75 duty/°C** — steep enough that the 0.8 °C deadband spans 4.6 duty
points, so no fan speed can rest inside it. It hunted by four points at a 17 °C room. Starting
Cold's rise at 43 halves the slope to 2.87 duty/°C and removes it.

The cost falls on Normal, and only on Cold: its settled 46.5 °C reading now sits on the rise,
so above about a 23 °C room Normal runs 32–39 % on this preset rather than 30. Eco and Super
Eco are untouched to 26 °C. Anyone choosing the coldest preset is not asking for the quietest
fan, so the trade was taken — and it let the host test be bounded at the accepted two duty
points rather than carry an exception for a known four.

**A uniform offset above the floor keeps the geometry.** Adding a constant leaves every
segment's width and slope untouched, so the shelf and everything above it inherit the base
curve's stability rather than needing a fresh argument. The one exception is the rise from
the pinned floor to the shelf, which climbs further in the same 4 °C — 5.75 duty/°C on Cold
— and that was not assumed safe: all four were driven through `tools/CurveSim.java` across
15–35 °C ambient at four thermal poles.

**A curve cannot hold a hard temperature ceiling**, and it is worth being explicit about
why. To pin the LED at exactly 55 °C the curve would have to command 38.2 % in a 27 °C room
and 44.8 % in a 30 °C one — two different duties at the same input. A curve is a function of
temperature, so no shape does that. Above the shelf it climbs and the light engine settles a
little over instead. Holding a *temperature* rather than a fan speed is what LINEAR is for.

## LINEAR mode

An alternative controller, installed but **not the default**. Where the curve holds a fan
speed and lets the temperature float, LINEAR holds a temperature and lets the fan float: it
steps the duty one point at a time until the light engine sits at a ceiling, and never stops
stepping. It has no plant model, so a hotter room, a hotter unit or a brighter picture is not
a case it has to have been measured in.

### Read this before choosing it

**LINEAR is not simply louder — it trades noise against temperature in opposite directions
either side of about 24 °C**, which is where its default 52 °C ceiling was chosen to meet the
curve. Presentation, both controllers solved against the measured plant:

| room | CURVE Quiet | LINEAR @ 52 °C | LINEAR costs | and buys |
|---|---|---|---|---|
| 22 °C | 37.2 % / 50.6 °C | 34.9 % / 52.0 °C | **−2.3 % fan** | −1.4 °C |
| 24 °C | 38.4 % / 51.9 °C | 38.2 % / 52.0 °C | ±0 | ±0 |
| 26 °C | 39.2 % / 53.4 °C | 42.3 % / 52.0 °C | +3.1 % fan | +1.4 °C |
| 28 °C | 40.0 % / 54.9 °C | 48.8 % / 52.0 °C | +8.8 % fan | +2.9 °C |
| 30 °C | 42.1 % / 56.1 °C | 56.9 % / 52.0 °C | **+14.8 % fan** | +4.1 °C |
| 32 °C | 44.3 % / 57.2 °C | 73.3 % / 52.0 °C | **+29.0 % fan** | +5.2 °C |

In a **cool** room LINEAR is quieter, because it only cools to its ceiling where the curve's
fixed shelf overcools past it. In a **warm** room it is much louder, because holding a
temperature against a plant whose authority collapses above duty 45 costs whatever it costs.
Above a **32.5 °C** room it cannot hold 52 °C at all, and says so rather than pretending.

**So: choose the curve for quiet, and LINEAR when the ceiling matters more than the noise.**
The curve cannot hold a ceiling at all — it is a function of temperature, so it cannot
command two different duties at the same reading — and that is the one thing LINEAR is for.

### How it steps

The step rate is measured, not chosen. Settled swing scales as step rate times plant lag:

| decay interval | measured swing | verdict |
|---|---|---|
| 1 s | 39 points, growing | limit cycle |
| 5 s | 14 points | audible |
| 60 s | **3 points** | not noticed by ear |

A 3-point swing needs a 60 s decay; giving back a 6-point overshoot at that rate takes six
minutes. No single rate satisfies both, so there are three: **attack 5 s** while above the
ceiling, **decay 60 s** within 1 °C of it, and **decay 10 s** with more headroom — fast where
dropping is free, slow where it costs an audible swing.

### The trend gate, and why it exists

Direction alone is blind to whether the fan it already has is working. Watched on hardware on
2026-09-07: the walk kept adding fan for 36 seconds *after* the light engine had started
falling, purely because the absolute reading had not crossed back below the ceiling yet — and
sailed eight duty points past its equilibrium. On a plant with a 120 s lag that is textbook
integral windup.

So a second test gates the first: **do not add fan while the temperature is already coming
down, and do not give it back while it is still climbing.** It fits a least-squares slope over
the last 90 seconds and acts only when the slope is at least three standard errors clear of
sensor noise. It delays action; it never caps it — where the ceiling genuinely needs 83 %, the
walk still gets there.

Measured against the ungated walk:

| | ungated | gated |
|---|---|---|
| hardware, overshoot past equilibrium | 5+ duty points | **2 duty points** |
| simulated, peak of the approach at 30 °C | 83 % | **62 %** |
| simulated, settled swing at 24 °C | 3.8 points | **2.7 points** |
| time to reach the ceiling from 83 %, 24 °C | 66 minutes | **2 minutes** |

That last row is the seed: the walk starts from `curve(degC)` rather than from whatever duty
happened to be on the node. Without it LINEAR can spend an hour failing to reach the ceiling
it exists to hold.

The window is settable with `--ei lintrend`; **0 turns the gate off** and restores the
pre-2026-09-07 behaviour, which is there so the two can be compared by ear.

Designs that were built and **rejected on evidence**, so nobody rebuilds them:

- **Curve feedforward plus a clamped integral trim.** At 33 °C the ±20 clamp stalls it at
  60 % and lets the light engine reach 54.5 °C, where the plain walk goes to 83 % and holds
  52.4. A clamp that blocks authority in a hot room is the worst available failure. It also
  steps more than one duty point per tick, because the feedforward term moves with
  temperature.
- **A trend gate built on the difference of the two end samples.** Its noise is three times
  worse than a least-squares slope, so the gate fires at random — and random skipping also
  suppresses windup, which made the broken version look like it worked.
- **A rule that steps the fan on temperature *rate* alone**, with no ceiling. It has no set
  point, so it holds whatever temperature it happens to arrive at; and at the thresholds that
  beat sensor noise it never fires anyway. Measured: the most violent event ever induced on
  this unit — both vents covered, then uncovered — peaked at 1.7 °C/minute, where a 1 °C-in-5 s
  trigger needs 12 °C/minute.

**LINEAR has one settled hardware run behind it**, on 2026-09-07: seeded from 30 %, peaked at
44, settled at 42 holding 51.6 °C in a 25.5 °C room, every change a single duty point. The
curve has thirty-six hours. Treat the two accordingly.

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
| `release/` | the signed APKs — `fanlab-system.apk` (platform-signed, the one to install) and `fanlab-plain.apk` (debug-signed, observe-only) |
| `final_curve.txt` | the deployed curve, in the app's own encoding |

## Licence

MIT — see [LICENSE](LICENSE).
