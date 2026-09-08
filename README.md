# FanLab

**A quieter fan for the Philips Screeneo U4 projector.**

If your Screeneo U4 sounds like it can't make its mind up, this fixes it. The fan stops
surging up and down and settles at one speed, and that speed is a lot lower than the one it
keeps jumping to.

It's an ordinary app. You install it from a USB stick, the same way you'd install anything
else on the projector. Nothing is flashed, no firmware is replaced, and you can remove it
again from Settings.

For the Screeneo U4 (model SCN350) on firmware 1.7.1.

---

## What's actually wrong with the fan

Philips' fan control is a set of five fixed speeds, and it checks the temperature every
fifteen seconds to decide which one to use. The problem is that it uses the same
temperature to step up as it does to step down, with no gap between them.

In Presentation mode the projector naturally settles at almost exactly the temperature where
one of those steps sits. So it gets a little too warm, jumps to a much higher fan speed,
cools down, drops back to a low speed, warms up again, and repeats. Forever.

That's the noise you're hearing. It isn't that the fan is too fast. It's that it keeps
changing, and a fan that changes is far more noticeable than a fan that's simply on.

FanLab replaces the five fixed speeds with a smooth curve. There are no steps left to trip
over, so there's nothing to surge between.

## What you'll notice

In Presentation mode, in a normal room, at the projector's standard brightness:

| | Philips' controller | FanLab |
|---|---|---|
| Fan speed | jumps between 59 % and 70 % | steady at 38–40 % |
| How often it changes | constantly | typically not at all |
| Other brightness modes | varies | 30 %, which you can't hear from a sofa |

Measured over an hour against the projector's real thermal behaviour, Philips' controller
made 232 speed changes and FanLab made 25 — and none of FanLab's were bigger than a single
percent, where 232 of Philips' were five percent or more.

The projector will run slightly warmer than it did. That's the trade: a slower fan moves
less air. The section on safety below explains why that's fine, and what protects you if it
ever isn't.

## Is it safe?

Short answer: yes, and here's exactly why rather than just a reassurance.

**The projector's own protections are untouched.** There's a temperature cut-out at 75 °C
built into the projector, and a separate watchdog that reacts if the fan ever stalls.
FanLab doesn't and can't disable either. They sit underneath everything it does. In normal
use with FanLab the light engine runs around 52–55 °C, so there's a wide margin.

**If anything goes wrong, the fan goes to maximum, not to minimum.** Every failure path
was written that way deliberately. If the temperature sensor stops responding, if the app
crashes, if the projector's screen turns off mid-measurement, if you force-stop it — the
fan goes to 83 % and Philips' controller is handed back. A quiet fan is never the fallback.

**Nothing permanent is changed.** No firmware, no system partition, no bootloader. It's an
app in the ordinary sense and it uninstalls like one.

**One thing genuinely can catch you out**, and it's worth knowing before you start rather
than after. Taking over the fan sets a hidden system setting that tells Philips' controller
to stand down, and that setting survives uninstalling the app. If you just delete FanLab
without pressing **RESTORE STOCK FAN CONTROL** first, nothing is left watching the
temperature. The projector still won't cook itself, because the 75 °C cut-out is still
there, but you'd be running with no thermostat. See [Removing it](#removing-it) — it's one
button, and it's easy as long as you know.

This was all worked out on one projector by measuring it, not by reading a manual. If yours
behaves differently, [docs/safety.md](docs/safety.md) is the honest, complete version of
this section.

## What you need

- A Philips Screeneo U4 (SCN350)
- A USB stick
- Nothing else. No PC, no cable, no adb, no unlocking anything.

## Installing it

1. Copy **`release/fanlab-system.apk`** onto a USB stick.
2. Plug the stick into the projector.
3. On the projector, open **AppInstaller** from the launcher and choose that file.
4. Open **FanLab** from the launcher.
5. Change **Mode** from `OFF` to `CURVE`.

That's it. Choosing `CURVE` hands the fan over automatically — there's a separate **Take
over** button but you don't need it.

The fan takes about fifteen seconds to come down from wherever Philips had it, and then it
should stay put.

Leave **Start automatically after a reboot** switched on. It's on by default. If you turn
it off, Philips' controller takes back over the next time you switch the projector on.

> **Two files, and you want the first one.** `fanlab-system.apk` is the real one.
> `fanlab-plain.apk` can only watch and log — it can't actually control the fan. It exists
> for development. If you install the wrong one, nothing bad happens, it just won't do
> anything.

## Using it

The main screen is a list. Move up and down with the remote, change a setting with left and
right, and press OK on a button.

Most of it you can ignore. Three settings matter:

**Mode.** Set this to `CURVE` and leave it there. `OFF` just watches without doing
anything. The other two, `MANUAL` and `LINEAR`, are for experimenting and are described
further down.

**Curve preset.** Four choices, quietest first: `Quiet`, `Balanced`, `Cool`, `Cold`. Start
on `Quiet`, which is the default. Each step up settles about 3 % higher on the fan and takes
roughly 1 °C off the light engine. Move up only if you'd rather have it cooler than
quieter.

**Start automatically after a reboot.** Leave it on.

Everything else has a sensible default. The full list is in
[All the settings](#all-the-settings) further down if you're curious.

### Your settings are remembered

Switch the projector off and on and it comes back exactly as you left it — same mode, same
preset, same everything. You don't need to set it up again.

One thing that isn't FanLab's to remember is the **brightness mode** — Presentation, Normal,
Eco, Super Eco. That belongs to the projector, not to this app, and it behaves the same
whether FanLab is installed or not. Whatever mode you come back in, FanLab notices within a
second and uses the right part of the curve for it.

## Making the picture brighter (optional)

There's a setting called **LED drive**, and it's off by default. Turning it on runs the
light engine harder than Philips does, so the picture is noticeably brighter.

It costs you the quiet. More light means more heat, which means more fan. With it on,
Presentation settles around 51–52 % instead of 38–40 %, and the dimmer modes stop being
silent — Normal and Eco both come off their 30 % floor in any normal room.

If you turn it on, the preset list changes to `Bright Quiet`, `Bright Balanced`,
`Bright Cool` and `Bright Cold`. These are the same four steps, redrawn for the extra heat.
You don't choose between the two sets; whichever is right for your brightness setting is the
one you're offered, and switching the LED drive moves you across automatically. That's
deliberate — running the ordinary curve at the higher brightness gets hot enough that the
projector quietly drops back to standard brightness on its own, with nothing on screen to
explain why.

It's brighter, and it's louder. Try it and see which you prefer. Turning it off puts
everything back.

## Removing it

There are two different buttons here and they do different things. This is the one part of
the app worth reading carefully.

**To stop it driving the fan for now:** press **RELEASE CONTROL**, or set Mode to `OFF`.
Philips' controller takes over again straight away. Expect a few seconds of loud fan while
it hands back — it deliberately goes to 83 % during the handover so that nothing is ever
left unmanaged in between.

**To uninstall it properly:**

1. Press **RESTORE STOCK FAN CONTROL** in the app.
2. Then uninstall FanLab from Settings, like any other app.

Do those in that order. The reason is the hidden setting mentioned earlier: taking over the
fan tells Philips' controller to stand down, and that instruction lives outside the app. The
app puts it back whenever it gets the chance — when you stop it, change mode, force-stop it,
even if it crashes. But uninstalling doesn't run any of the app's code, so it can't put it
back on the way out.

| how you stop it | is Philips' controller handed back? |
|---|---|
| Mode → OFF, RELEASE CONTROL, force-stop, crash, reboot | yes |
| uninstalling without pressing RESTORE first | **no** |

If you've already uninstalled without pressing it, don't panic. The 75 °C cut-out and the
fan-stall watchdog are both still there, and the fan sits at the projector's own default
speed rather than stopping. But nothing is responding to temperature any more. Reinstall
FanLab and press **RESTORE STOCK FAN CONTROL**, and you're back to normal.

## If something looks wrong

**The fan is loud and won't come down.** Check Mode is `CURVE` and not `OFF`. If you've just
switched it on, give it fifteen seconds.

**The fan jumps every time I change brightness.** That's the projector's own brightness code
slamming a fan speed in, and FanLab puts it back within a second. Make sure **Re-assert
every second** is on; it's on by default.

**The picture suddenly went back to normal brightness on its own.** The LED drive has a
safety cut-out at 60 °C, and it's latched off until you change brightness mode or a setting.
Your room is probably warmer than usual. Either accept it, pick a cooler preset, or turn the
LED drive off.

**It looks like nothing happened after installing.** Check you installed
`fanlab-system.apk` and not `fanlab-plain.apk`. The plain one can't control the fan.

**The projector shut down.** That's the 75 °C cut-out, and it means something is physically
wrong — a blocked vent, a failing fan, a very hot room. FanLab doesn't disable that cut-out
and can't. Check the vents are clear before anything else.

---

# How it works

Everything above is what you need to use it. The rest of this file is the engineering: what
was measured, why the curve is the shape it is, and what was tried and rejected. None of it
is required reading.

The deeper material lives in `docs/`:

| document | what's in it |
|---|---|
| [docs/safety.md](docs/safety.md) | the complete safety argument, and every failure path |
| [docs/curve.md](docs/curve.md) | how the curve was derived, and the measurements behind it |
| [docs/findings.md](docs/findings.md) | what taking the projector's firmware apart established |
| [docs/measuring.md](docs/measuring.md) | how to measure your own projector and re-derive the curve |
| [docs/measurement-conditions.md](docs/measurement-conditions.md) | the exact conditions everything was measured in |
| [docs/deploy.md](docs/deploy.md) | driving the app over adb, for development |
| [docs/hacking.md](docs/hacking.md) | notes for anyone working on the code, and the traps in it |

## All the settings

Everything on the main screen, in the order it appears. Most of it can be left alone.

| control | what it does |
|---|---|
| **Mode** | `OFF` watches without driving. `CURVE` is the fan curve, and the one to use. `MANUAL` holds one fixed speed. `LINEAR` holds a temperature instead of a speed, described under [LINEAR mode](#linear-mode) |
| **Curve preset** | four steps, quietest first: `Quiet`, `Balanced`, `Cool`, `Cold`. Each step settles about 3 % higher on the fan and buys about 1 °C.  Measured at 24 °C: 38.4 %, 41.6 %, 44.3 %, 47.6 %. With the LED drive on you get `Bright Quiet` through `Bright Cold` instead, and the plain four are not offered. See [The two preset families](#the-two-preset-families) |
| **Room temperature** | optional, and only recorded in the log. Nothing in the fan control reads it. Leave it at "not stated" unless you know the figure, because a stale number is worse than none |
| **Write CSV telemetry** | logging on or off. On by default and self-pruning. See [Telemetry](#telemetry) |
| **Start automatically after a reboot** | leave this on, or Philips' controller takes over at the next power-on |
| **Re-assert every second** | leave this on. The projector's own brightness code slams a fan speed in whenever you change brightness, and this puts it back within a second |
| **LED drive** | off by default. Runs the light engine brighter than Philips does, at 35/55/75/90 for Super Eco/Eco/Normal/Presentation against Philips' 20/40/55/76. It only applies while the app is actually driving the fan, so brightness can never outrun cooling. See [The LED drive override](#the-led-drive-override) |
| **RELEASE CONTROL** | hands the fan back for now, and the app goes back to watching |
| **RESTORE STOCK FAN CONTROL** | the permanent undo. Press this before uninstalling. See [Removing it](#removing-it) |

## Driving it from a PC

Everything above is also settable over adb, which is how it is developed and how the curve
is deployed. The full reference is in [docs/deploy.md](docs/deploy.md). The two commands
worth knowing:

```bash
# read the current state, changes nothing
adb shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver

# switch preset -- "bright quiet", "brightquiet" and "bright-quiet" all work too
adb shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver \
    --es preset quiet
```

**Use the full component name.** The system build's package is
`com.daleygames.fanlab.system`, and `am broadcast` does not validate components — a
broadcast to the wrong one reports `result=0` and silently does nothing.

The reply is the resulting state, so diff it against what you sent. It says
`curve(REPAIRED)` or `linear(REPAIRED)` when a value was clamped rather than accepted, and
`preset(REFUSED: ...)` when a preset is asked for from the wrong family:

```
preset(REFUSED: Quiet is a Curve preset and the LED drive is on; use Bright Quiet or
turn the drive off)
```

Refused rather than substituted, because naming a preset is a request for that exact curve
and answering it with a different one while saying `preset` would be a lie. The family is
judged against the drive setting the *whole command* ends in, so
`--es preset "bright quiet" --ez leddriveon true` in one line is accepted. Two more reply
fields say where you stand: `presetfamily=` and `presetsallowed=`.

The LED drive override is `--es leddrive stock|bright|<encoded>` followed by
`--ez leddriveon true|false` — the level is applied before the switch, so both can go in
one broadcast. The reply carries three fields answering three different questions:
`leddrive=` the configured levels, `leddriveon=` whether it is switched on, and
`leddrivestate=` what is actually on the hardware right now, which is the only one of the
three that accounts for the mode, the fail-safe and the temperature trip.

## The LED drive override

**Off by default.** The kernel maps each brightness mode to a fixed LED drive, as a
percentage of the driver's own per-channel maximum — Super Eco 20, Eco 40, Normal 55,
Presentation 76, with the red channel a few points lower at each. So the light engine
spends its life at about three-quarters of what the firmware's own scale permits. This
override writes `rgbcurrent` and `redcurrent` to move those levels up; `Bright` is
35/55/75/90 — Presentation from 76 to 90, which is 18 % more drive. 90 rather than higher
because 90 is the drive the Bright curve family was drawn against and the drive its plant
scaling was measured at; a brightness preset that outran its curve would be a number nobody
had solved a fan speed for.

**It is capped at 97, not 100, and that is not caution.** Reading `rgbcurrent` makes the
driver log the absolute current: at Presentation it reports `current = 5357 ma, percent = 75`,
so a channel's maximum is about 7.1 A. The driver converts milliamps to a 7-bit DAC code and
clamps it as `if (code > 0x7F) code = 0x3F` — an overflow does **not** saturate, it drops
that channel to code 63, about 2.8 A. The code passes 127 at roughly 99 %. Ask for 100 and
the picture goes *dimmer*, not brighter. The app clamps to 97 and a test holds it there.

**What it costs.** Heat, and it lands on the red die — the lowest-rated part in the light
path, the one that loses output fastest with temperature, and the one seven owners of this
model have reported losing. From the measured plant each +10 on the Presentation level is
a measured +4.3 °C at a fixed fan speed, so 76 → 90 is **6.0 °C**. That is why the Bright
curve family exists: it spends fan to put some of that back. **Switching this row on switches
your curve preset with it** — Quiet becomes Bright Quiet, Cool becomes Bright Cool — and
switching it off switches them back, so the two can never be out of step. See
[The two preset families](#the-two-preset-families).

**The rule that makes it safe.** The override is on the hardware *only* while the app is
genuinely the fan controller — mode `CURVE` or `LINEAR`, no measurement session running, the
light engine on, and the fail-safe not latched. In every other state the stock table is put
back, within a second, by rewriting `rgblevel`. This is the whole safety case: raising light
output while something else owns the fan is exactly the "Presentation-class heat on the Eco
ladder" failure this project has refused to ship since the reverse-engineering found it. There
is also a temperature trip — above 60 °C the override drops to stock and stays off until the
brightness mode or the configuration changes, because brightness that cycles is worse than
brightness that stops. The preset gate is what keeps that trip a backstop rather than a
routine event: on a Bright preset the drive-90 equilibrium is 56 °C in a 28 °C room, four
degrees clear, where the same drive on a Curve preset settles at 58.

**How to undo it.** Turn the row off, `--ez leddriveon false`, `--ez reset`, press
**RELEASE CONTROL**, or switch to `OFF` or `MANUAL` — any of them restore the stock table.
Nothing it writes survives a reboot, and the kernel restores the stock levels itself on the
next brightness-mode change.

**Reading it back.** `cat /sys/class/dlpc343x/rgbcurrent` shows the levels the hardware
actually has — but its field names lie. The kernel prints the four SPI channels in array
order under the labels `duty_r, duty_g, duty_b, duty_b2`, while the real map is
ch0 green, ch1 red, ch2 b2, ch3 blue. So **`duty_g` is the red channel** and the other
three all carry the common level. With Bright on in Presentation it reads
`duty_r=89 duty_g=83 duty_b=89 duty_b2=89` — 90 and 84, each one low, which is how the
handler reports, and that is the reading the projector actually printed rather than a
prediction. Any field above 100 is a failed SPI read, not a level.

**What is not known.** Whether 90 looks meaningfully brighter, and whether the white point
drifts cool as the red channel droops faster than green and blue. Neither is a temperature
question and neither can be answered from a log — put up a white field and look.

### If the LED drive looks wrong

| symptom | what it is |
|---|---|
| fan cycles slowly between two speeds | something else is writing the fan node. Check `Mode` is `CURVE` and `Re-assert` is on. MANUAL leaves the stock controller armed by design, so it is not usable for a quiet run |
| on Cold, Normal is louder than 30 % | expected above a 23 °C room. Cold starts its rise at 43 °C rather than 47 so that it does not hunt, which puts Normal's operating point on the rise. The other three steps all keep Normal at 30 %, and Bright Cold inherits Cold's behaviour here along with its floor edge |
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

## The two preset families

Four steps, run twice. The **standard** four are for the stock LED drive. The **Bright**
four are the same four steps with Presentation's fan row redrawn for the drive override.
You pick the step; **the override picks the family**, and it is not negotiable — turning it
on moves you to the Bright version of the step you are on, turning it off moves you back,
and the preset button only ever cycles inside the family you are currently in. There is no
sequence of presses that pairs a standard curve with the raised drive.

**Why that is a gate rather than a warning.** Nothing used to stop Quiet running with the
drive on. At drive 90 in a 28 °C room that pairing settles at **58 °C**, against 56 °C on
Bright Quiet. 58 °C is inside the margin the drive's own 60 °C cut-out leaves itself, so a
warm afternoon reaches the trip — and the trip drops the drive without announcing it, so
what you actually see is the picture going back to stock brightness on its own with nothing
on screen saying why. A pairing that fails that way is worth making unreachable; merely
discouraging it leaves the report to be filed and diagnosed.

### The standard four — stock LED drive

| preset | fan at 24 °C | LED at 24 °C | holds ≤54 °C to | holds ≤55 °C to |
|---|---|---|---|---|
| **Quiet** (default) | 38 % | 51.9 °C | 26.7 °C room | 28.0 °C room |
| Balanced | 41 % | 50.4 °C | 28.8 °C room | 30.0 °C room |
| Cool | 43 % | 49.8 °C | 30.1 °C room | 31.2 °C room |
| Cold | 46 % | 48.6 °C | 31.5 °C room | 32.6 °C room |

Solved against this unit's *measured* thermal plant.

### The Bright four — LED drive override on

Same steps, same names with `Bright` in front. Their equilibria are in
[docs/curve.md](docs/curve.md#the-bright-preset-family) rather than here, because they are
solved against a *different plant* and putting the two tables side by side invites a
comparison that means nothing: a Bright preset never runs at stock drive by choice, and a
standard one can no longer run at raised drive at all.

### All eight, knee by knee

```
tempC            =  47   51   55   60   66   70      (knee 0 is 43 on Cold and Bright Cold)

Quiet            =  30   38   40   50   68   83
Balanced         =  30   43   45   55   73   83
Cool             =  30   48   50   60   78   83
Cold             =  30   53   55   65   83   83

Bright Quiet     =  30   38   50   62   76   83      Presentation only
Bright Balanced  =  30   43   55   67   81   83      Presentation only
Bright Cool      =  30   48   60   72   83   83      Presentation only
Bright Cold      =  30   53   65   77   83   83      Presentation only
```

**A Bright step differs from its standard step in exactly one column and one way.** Its
Presentation row is the standard row **plus 0, 0, 10, 12, 8, 0 at the six knees, clipped at
83** — the same edit in all four. Normal and Eco / Super Eco keep the standard step's row
untouched, the knees are the standard step's including the floor edge, and so are the
deadband, the slew limits and the SoC guard. Knee 1 is deliberately unchanged, which is what
keeps all three brightness columns identical at and below 51 °C.

What the edit does is **spend the shelf**. The standard curve levels off across 51–55 °C, the
band this machine occupies at factory drive; the Bright one climbs through it at 3.0 duty/°C
instead. A shelf is deliberately indifferent to temperature, which is the right instinct when
the operating point sits in the middle of the band and the wrong one once the raised drive
has pushed it up against the ceiling.

These rows were re-examined against the measured plant on 2026-09-08 and left unchanged. The
redraw that was tried — taking the +10 off knee 2, which rests 3.7 duty points quieter and
looked better on every static measure including `CurveSim` — hunts on the hardware, nine duty
changes in twelve minutes against the shipped rows' zero. See
[docs/curve.md](docs/curve.md#the-redraw-that-was-tried-against-the-measured-plant-and-rejected).

**Raising the LED drive costs the dim modes their silent floor, and that is the drive raise
rather than the preset.** The Bright steps leave Normal, Eco and Super Eco alone precisely so
they do not make it worse, but it is worth knowing before switching the override on. All three
scalings below were **measured** on 2026-09-08, and all three came out worse than the fitted
line they replace: with Normal at 75 % drive rather than 55, **Normal leaves duty 30 at a
19.2 °C ambient instead of a 27.3 °C one** and sits at about 37 % / 50.3 °C at 24 °C. Eco at
55 % leaves the floor at 24.8 °C rather than 31.1. Super Eco at 35 % holds 30 until 30.3 °C.

Set against the +2 to +4 °C the intake runs above the room, **Normal and Eco are off the
silent floor in any room this machine will realistically be used in** once the drive is
raised. The full tables are in
[docs/curve.md](docs/curve.md#what-it-costs-stated-rather-than-buried).

Three design points worth stating, because all three were arrived at the hard way:

**The floor is not offset.** All eight presets idle at 30 %. Below the floor edge the light
engine is cool enough that extra fan buys almost nothing — measured, Cold's +15 bought 3.4 °C
in Super Eco on a thermistor already sitting at 35 °C. Since Normal, Eco and Super Eco spend
their whole lives on the floor, offsetting it would make them louder for no useful cooling.
The offset applies only where the ceiling is actually in question.

**Cold's floor edge is 43 °C, where the other three steps share 47 °C**, and that is the one
place the steps are not congruent. With the floor pinned at 30 and Cold's shelf at 53, a rise
over 47–51 °C would be **5.75 duty/°C** — steep enough that the 0.8 °C deadband spans 4.6 duty
points, so no fan speed can rest inside it. It hunted by four points at a 17 °C room. Starting
Cold's rise at 43 halves the slope to 2.87 duty/°C and removes it. Bright Cold inherits that
edge along with everything else it takes from Cold.

The cost falls on Normal, and only on the Cold step: its settled 46.5 °C reading now sits on
the rise, so above about a 23 °C room Normal runs 32–39 % on this preset rather than 30. Eco
and Super Eco are untouched to 26 °C. Anyone choosing the coldest step is not asking for the
quietest fan, so the trade was taken — and it let the host test be bounded at the accepted two
duty points rather than carry an exception for a known four.

**A uniform offset above the floor keeps the geometry.** Adding a constant leaves every
segment's width and slope untouched, so the shelf and everything above it inherit the base
curve's stability rather than needing a fresh argument. The one exception is the rise from
the pinned floor to the shelf, which climbs further in the same 4 °C — 5.75 duty/°C on Cold
— and that was not assumed safe: all eight are driven through `tools/CurveSim.java` across
15–35 °C ambient at four thermal poles (**666 of 672 runs steady**), and through the host
suite at every ambient from 14 to 34 °C on every build. The six that are not steady are
Quiet's and Bright Quiet's shared 16 °C corner; they are the same corner, because below 51 °C
Bright Quiet *is* Quiet. **On the raised plant there is one more, and it is on Bright Cool** —
see [docs/curve.md](docs/curve.md#stability-of-the-bright-family).

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
curve. (With the LED drive override on, that default is promoted to 54 °C — holding 52 °C at
a raised drive costs about twelve duty points more, and 54 °C is roughly where the Bright
curve family rests, so the two controllers still meet. A ceiling you have set by hand is left alone.) Presentation, both controllers solved against the measured plant:

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

### The three display-controller features, and why none of them is here

Each of these was a control on the main screen. All three were tried on the projector on
2026-09-07, all three were withdrawn, and this is the record of what each one did. The
command encoders and decoders are still in `PicoReg`, with tests, and the app still reads
all three once a minute and prints them under **Diagnostics** — a firmware that changed one
of these answers would show up there. Nothing writes them any more.

**CAIC does nothing here, and it cannot.** Content Adaptive Illumination Control lowers LED
current on frames that do not need full output and opens the mirrors to compensate. A/B with
the fan pinned, seven minutes a hold, Presentation, video playing:

| | LED temperature |
|---|---|
| CAIC off | **52.33 °C** |
| CAIC on | **52.33 °C** |

Identical, with a within-hold spread of ±0.04 °C. Raising the gain budget from the 1.0 the
projector was found holding to the maximum 4.0 changed nothing either — the write was
accepted, `0x85` read back `00 80 60`, status bit clear.

The engine is demonstrably running. The debug gain bars move with the content, and `0x5F`
(*Read CAIC RGB LED Current*) gives live per-colour values that vary with the image:
`13 00 14 00 12 00` on one frame, `11 00 17 00 11 00` later. But the LED current never
leaves the kernel's stock per-mode table, because **TI defines every LED-current command as
going to a DLPA200x PMIC and this board has none** — the currents are driven by the SoC over
SPI to two MAX20096 chips the display controller cannot reach. CAIC computes correctly and
its output is stranded. That is a wiring fact, not a setting, so no firmware update fixes it.

**LABB works, and what it does is make the picture worse.** Local Area Brightness Boost is
pure image processing inside the DLPC3436 — it needs no LED driver, which is exactly why it
runs here where CAIC cannot. Enabling it (`w 80 2 11 80`) is accepted, the register reads
back `11 80`, and the live gain byte tracks the content: `0x20` idle, moving to `0x27` and
`0x24` on real video. The verdict from watching it was *"really washed out seeming"*. That
is not a fault — it is LABB doing its documented job. Adaptively gaining up the darker parts
of a frame raises the black floor, and a raised black floor is flattened contrast.

**The Looks trade red for green, and red has nothing to trade.** A Look is a colour-sequence
preset: how the frame's time is divided between the three LEDs. All 19 were swept and their
duty splits read via `26h`:

| Look | red | green | blue | on a white field |
|---|---|---|---|---|
| **0** | 40 % | 40 % | 20 % | white |
| 1–18 | 25–33 % | the rest | 20 % | 1 and 15 visibly green |

Look 0 is the only one this projector has ever used — the kernel zeroes its Look table at
probe — and it is also optimal by construction. The gain the others offer cannot be
rebalanced back to neutral: restoring white on Look 15 needs red flux up **1.97×**, which is
red current up about **2.5× to 175 %**, against a hard ceiling of 97. Red is the weak
primary on this engine and has no headroom to give, so the extra brightness is inseparable
from the green cast that pays for it.

## How the tests work

`app/test/FanLabTest.java` is a plain Java program — no JUnit, no Android — that runs on
the host as part of every build. **3292 assertions**, and the build refuses to produce an
APK if any fail.

It covers five things:

1. **The pure logic**, exhaustively: the thermistor conversion against the framework's own
   arithmetic, the curve's interpolation and clamps, monotonicity, and that every output
   over −100 to 200 °C is a legal duty.
2. **Every failure path goes high.** Bad reads, missing config, unparseable preferences,
   an unreadable node — each is asserted to produce 83, never a low value.
3. **The bug itself, reproduced.** It models the stock ladder and this curve against the
   same dithering input and asserts stock changes speed while the curve does not.
4. **A simulated hour** of both controllers, asserting the curve never moves more than one
   point at a time and that it is measurably quieter than stock.
5. **No preset hunts.** Every curve on offer is driven through the real `FanCurve` against
   the two-pole plant at every ambient from 14 to 34 °C, at four thermal poles, and the run
   fails if any of them settles outside two duty points or moves more than one point per
   tick. This is the only check that has ever caught a hunt here — three static rules were
   each written down as the criterion and each passed a curve that hunts. It is driven off
   the preset list rather than a copy of it, so a new curve is covered without anyone
   remembering to add it.

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
| `docs/` | safety, curve derivation, findings, measuring, deployment, and notes for working on the code |
| `release/` | the signed APKs — `fanlab-system.apk` (platform-signed, the one to install) and `fanlab-plain.apk` (debug-signed, observe-only) |
| `final_curve.txt` | the deployed curve, in the app's own encoding |

## Licence

MIT — see [LICENSE](LICENSE).
