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
  measured jump on a mode change is 1 duty point, against 10 for a per-mode design. The
  `Bright` preset is the one place the columns differ, and only above 51 °C — an Eco →
  Presentation switch is still stepless there, and a Normal → Presentation one reaches 2
  points at the warmest room this unit has recorded.
- **A 0.8 °C deadband and a 1-point-per-4-seconds rate limit**, so even a real change is
  inaudible as a change. Rate limiting exists to hide drift the listener did not cause, so
  it is deliberately bypassed when the controller is *handed* a duty someone else chose —
  after a reboot, a mode change, or a fail-safe — converging in about 15 seconds instead of
  crawling for seven minutes.
- **Test count: 3292**, run on the host as part of every build.
- **An SoC guard**, because the sensor driving the fan cannot see the processor. It adds
  fan only above 70 °C on the die and can never subtract any. See
  [The SoC guard](#the-soc-guard).
- **Fail-safe high.** Every error path writes 83 %, never a low value.
- **Automatic handback.** The app owns the switch that disables the stock controller and
  re-arms it whenever it stops driving, so the projector cannot be left unmanaged.
- **Optional CSV telemetry** to internal storage and any mounted USB stick at once,
  size-capped and self-pruning. See [Telemetry](#telemetry).
- **Two display-controller switches, as experiments.** One write each asks the DLPC3436 to
  run Content Adaptive Illumination Control or Local Area Brightness Boost; one write, or a
  power cycle, undoes either. Neither adds heat, both change the picture, and both revert
  themselves unless confirmed within fifteen seconds. LABB is the one this board has a
  mechanism for — see
  [The two display-controller experiments](#the-two-display-controller-experiments).

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
| **Curve preset** | `Quiet` / `Balanced` / `Cool` / `Cold` / `Bright`. Quiet is the default and the quietest; each of the next three adds 5 % fan and buys about 1.5 °C. `Bright` is not a fifth step up — it is a separate curve for a raised LED drive, and it is only worth choosing with that override on. See [The five presets](#the-five-presets) |
| **Room temperature** | optional. Tells the log what the room actually is, so later analysis is not guessing |
| **Write CSV telemetry** | logging on/off. On by default, self-pruning, see [Telemetry](#telemetry) |
| **Start automatically after a reboot** | leave this on |
| **Re-assert every second** | leave this on. The projector's own brightness code slams a fan preset on brightness changes; this puts it back. It is not cosmetic — see [Warnings](#warnings) |
| **LED drive** | off by default. Raises the light engine above the brightness mode's stock drive — `Bright` is 30/50/70/90 for Super Eco/Eco/Normal/Presentation against a stock 20/40/55/76. It only applies while the app is actually driving the fan, so light output can never outrun cooling. See [The LED drive override](#the-led-drive-override) |
| **LABB** | off by default, and nothing to do with the fan. Lifts the dark parts of the picture, on the mirrors, without touching the LEDs. Of the two display experiments this is the one to try — read [The LABB experiment](#the-labb-experiment) first. **Strength** 0–255 (128 default) is how hard it pushes; **sharpness** 0–15 (1 default) only does anything while LABB is on |
| **CAIC** | off by default, and nothing to do with the fan. An experiment on the display controller's own LED-power feature; read [The CAIC experiment](#the-caic-experiment) before pressing it. **CAIC brightness budget** 1.0×–4.0× (2.0 default) is how far it may lift the image — the projector was found at 1.0×, which permits nothing, and is why the first attempt did nothing |
| both of those | turning either on starts a **15-second countdown that reverts unless you confirm**, because a bad result can be one you cannot see to undo |
| **RELEASE CONTROL** | hands the fan back for now. The stock controller is re-armed, the app stops driving, and both display experiments are turned off if they were on |
| **RESTORE STOCK FAN CONTROL** | the permanent undo. Clears the setting that disables the stock controller. **Press this before uninstalling** — see below |

### Which preset

Start on **Quiet** and only move up if the light engine runs hotter than you want it to.
The figures are in [The five presets](#the-five-presets); the short version is that Quiet
keeps the light engine under 55 °C up to a 28 °C room, and each step up extends that by
about 2 °C at the cost of 5 % more fan.

**Use `Bright` only with the LED drive override on.** It is drawn for a machine running
Presentation at 90 % drive rather than the stock 76, and on the stock drive it is Quiet to
within half a duty point — harmless, but pointless.

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

The two display-controller experiments are `--ez caic true|false` with
`--ef caicgain <1.0-4.0>`, and `--ez labb true|false` with `--ei labbstrength <0-255>` and
`--ei labbsharpness <0-15>`. Each number is applied before its switch, so one broadcast can
set and enable:

```bash
"$ADB" shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver \
    --ei labbstrength 160 --ez labb true
```

The reply carries `caic=`, `caicgain=`, `labb=`, `labbstrength=` and `labbsharpness=`. The
first and third give the setting and, on the system build a few seconds later, what the
display controller itself said (`on (read back: on)`); `caicgain=` adds `(read back: 2.0x)`
once `0x85` has answered. A gain outside 1.0–4.0 comes back `caicgain(REPAIRED)`, because
the controller rejects the whole command on one rather than clamping it. See the next two
sections before sending any of these.

The LED drive override is `--es leddrive stock|bright|<encoded>` followed by
`--ez leddriveon true|false` — the level is applied before the switch, so both can go in
one broadcast. The reply carries three fields answering three different questions:
`leddrive=` the configured levels, `leddriveon=` whether it is switched on, and
`leddrivestate=` what is actually on the hardware right now, which is the only one of the
three that accounts for the mode, the fail-safe and the temperature trip.

### The LED drive override

**Off by default.** The kernel maps each brightness mode to a fixed LED drive, as a
percentage of the driver's own per-channel maximum — Super Eco 20, Eco 40, Normal 55,
Presentation 76, with the red channel a few points lower at each. So the light engine
spends its life at about three-quarters of what the firmware's own scale permits. This
override writes `rgbcurrent` and `redcurrent` to move those levels up; `Bright` is
30/50/70/90.

**It is capped at 97, not 100, and that is not caution.** Reading `rgbcurrent` makes the
driver log the absolute current: at Presentation it reports `current = 5357 ma, percent = 75`,
so a channel's maximum is about 7.1 A. The driver converts milliamps to a 7-bit DAC code and
clamps it as `if (code > 0x7F) code = 0x3F` — an overflow does **not** saturate, it drops
that channel to code 63, about 2.8 A. The code passes 127 at roughly 99 %. Ask for 100 and
the picture goes *dimmer*, not brighter. The app clamps to 97 and a test holds it there.

**What it costs.** Heat, and it lands on the red die — the lowest-rated part in the light
path, the one that loses output fastest with temperature, and the one seven owners of this
model have reported losing. Presentation at 90 runs the light engine roughly 3.5 °C hotter
at a given fan speed. That is why `Bright` exists: it is the curve that spends fan to put
that back.

**The rule that makes it safe.** The override is on the hardware *only* while the app is
genuinely the fan controller — mode `CURVE` or `LINEAR`, no measurement session running, the
light engine on, and the fail-safe not latched. In every other state the stock table is put
back, within a second, by rewriting `rgblevel`. This is the whole safety case: raising light
output while something else owns the fan is exactly the "Presentation-class heat on the Eco
ladder" failure this project has refused to ship since the reverse-engineering found it. There
is also a temperature trip — above 57 °C the override drops to stock and stays off until the
brightness mode or the configuration changes, because brightness that cycles is worse than
brightness that stops.

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
handler reports. Any field above 100 is a failed SPI read, not a level.

**What is not known.** Whether 90 looks meaningfully brighter, and whether the white point
drifts cool as the red channel droops faster than green and blue. Neither is a temperature
question and neither can be answered from a log — put up a white field and look.

### The two display-controller experiments

CAIC and LABB are the two halves of TI's *IntelliBright*, and the projector ships with both
off. Neither is a fan setting; both change the picture, neither adds heat, and each has a
one-write undo and a fifteen-second confirmation. **Try LABB first.** It is the half this
board has a mechanism for: it works on the mirrors and needs nothing from an LED driver,
where CAIC's whole method is lowering LED current through a driver chip this board does not
have. The reasoning is under *Why LABB and not CAIC* below.

### The LABB experiment

**What it is.** Local Area Brightness Boost. DLPU078A: "The key function of the LABB is to
adaptively gain up darker parts of the image to achieve an overall brighter image." It is
supported in TPG, splash and external input mode, and auto-disabled in curtain mode. It is
pure image processing inside the DLPC3436 — the LED drive is untouched, so the light engine
draws exactly what it drew before.

**What the projector was holding.** Reading `0x81` back answered `10 80 20 00`: sharpness
strength 1, **LABB control `0h` = Disabled**, strength already preset to 128, current gain
`0x20`. So the strength somebody would run it at is already loaded and the feature is simply
switched off. Enabling it while keeping that sharpness is one write, `w 80 2 14 80`.

**The two numbers.** *Strength* is 0–255; DLPU078A says 0 is no boost and 255 "the maximum
boost viable in a product", and warns that "the strength is not a direct indication of the
gain, since the gain varies depending on the image content" — so it is a dial, not a
multiplier, and this app does not pretend otherwise. Default 128, the value already in the
register. *Sharpness* is 0–15 in the top nibble of the same byte, and DLPU078A notes "The
LABB function must be enabled to make use of sharpness" — it does nothing on its own.
Default 1, again what the hardware had; it is preserved through every write rather than
zeroed, because it shares a byte with the enable and dropping it would be changing a
setting nobody asked to change.

**How to try it.** The `LABB` row under *Experiment — display controller*; press it once and
confirm within fifteen seconds. From a PC, `--ez labb true`. Then look at a dark scene:
shadow detail should come up while the bright parts stay where they were. If it looks washed
out, or the dark areas shimmer between frames, turn the strength down or switch it off.

**How to undo it.** Exactly as CAIC below: wait fifteen seconds, power-cycle,
`--ez labb false`, press the row again, RELEASE CONTROL, or `--ez reset`. Off writes back
`w 80 2 10 80` — the bytes the machine was found holding, not a cleared row.

**What is not known.** Whether the boost is visible, whether it introduces banding or
temporal flicker on this engine, and what byte 3 of the read-back actually means: it comes
back as `0x20` = 32 while Table 3-81 gives the LABB gain range as 1–8, so either the units
are not whole gain steps or the table does not describe this firmware. The app records the
raw byte and does not convert it.

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

**Selecting it is not enabling it, and that is why the first attempt did nothing.** `0x50`
picks the method; a second command, *Write CAIC Image Processing Control* (`0x84`), says how
far CAIC is allowed to lift the image. Reading it back with `0x85` on this projector
answered

```
00 20 60
```

— gain display off, **maximum lumens gain `0x20`**, clipping threshold 96. The gain is a
fixed-point byte whose least significant bit is a thirty-second (b7 = 2², b0 = 2⁻⁵), so
`0x20` is **1.0**, the bottom of the legal 1.0–4.0 range. CAIC had been given permission to
lift the image by nothing at all. That is the likeliest reason switching `0x50` on by itself
produced nothing anyone could see: the method was chosen and the budget was zero.

So the app now writes the budget first and selects CAIC second — `w 84 3 0 40 60` then
`w 50 1 1` for the default 2.0× — and hands the budget back to 1.0 (`w 84 3 0 20 60`)
whenever CAIC goes off, so the machine is left as it was found. **A gain outside 1.0–4.0 is
refused locally rather than sent**, because the controller rejects the whole command on an
invalid write parameter: it would change nothing while the app believed it had set a budget.
Encodings, if you are reading a log: 1.0 = `0x20`, 1.5 = `0x30`, 2.0 = `0x40`, 4.0 = `0x80`.

**Why LABB and not CAIC.** The projector ships with CAIC **off** — `caic=0x00` in every
template of the factory settings blob, and nothing in the firmware ever turns it on. And
there is a specific reason to doubt it can do its job on this board: CAIC saves power by
having the controller lower LED current through a TI DLPA LED driver, and **this board has
no TI LED driver**. The LED currents are driven by the SoC, over SPI, to two MAX20096 chips
the controller cannot reach; the controller's own current registers sit at a nominal 13 and
go nowhere. Fixing the gain budget removes one reason CAIC might do nothing here; it does
not remove that one. LABB has no such dependency, which is why it is the one to try first.
So turning CAIC on may

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

**How to try it.** On the screen, the `CAIC` row under *Experiment — display controller*,
with the *CAIC brightness budget* row under it; press CAIC once and confirm within fifteen
seconds. From a PC:

```bash
"$ADB" shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver \
    --ef caicgain 2.0 --ez caic true
# a few seconds later, to see what the controller said
"$ADB" shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver
```

Put up a mostly dark frame with a small bright region and watch it; that is the content
CAIC is designed for. Turn the budget up if nothing changes and down if the picture pumps
between scenes. The CSV note column records every write (`caic<-on:start gain=2.0`,
`caic<-on:rgblevel`, `caic<-off:setting`, `caic gain<-1.0`), each change in the read-back,
and while it reads on, the gain budget the controller says it is actually working within
(`caic_image=gain 2.0 (0x40), clip 96`) plus the *max available power* word about once a
minute (`caic_maxpower=0x...`) — that last number moving with the content is the one piece
of evidence the experiment can produce without a light meter.

**How to undo it — including with no picture.** The first three need nothing on screen,
which is the point: if CAIC blanks or wrecks the image, you cannot read a menu to escape it.

- **do nothing for fifteen seconds.** Turning it on arms a countdown owned by the service,
  not the screen; without a confirmation the service writes `w 50 1 0` and reverts. The
  preference is only saved once you confirm, so an unconfirmed CAIC cannot come back after
  a reboot either;
- **power-cycle the projector** (see below);
- `--ez caic false` from a PC;
- press the `CAIC` row again — one write, `w 50 1 0`, and the budget back to `w 84 3 0 20 60`;
- press **RELEASE CONTROL**, which turns it off along with everything else the app drives;
- `--ez reset`, which turns both experiments off along with the curve;
- **power-cycle the projector.** The controller reloads the factory settings at boot, and
  those have it off. Nothing either switch does is written anywhere that survives a reboot;
  the factory settings partition is deliberately never touched.

The same list is the LABB list, with `--ez labb false` and `w 80 2 10 80` in place of the
CAIC commands, and the same countdown — the two share one confirmation window, so if both
are armed together one press covers both and one silence reverts both.

The app also turns them off itself whenever it stops running, if it was the one that turned
them on, and re-asserts them on brightness-mode changes and after a resume, where the kernel
re-programs the controller's registers.

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

## The five presets

The curve ships as five, selectable on the main screen or by broadcast. Four of them are the
base curve with a constant added to every knee **above the floor**, clipped at 83 %:

| preset | fan at 24 °C | LED at 24 °C | holds ≤54 °C to | holds ≤55 °C to |
|---|---|---|---|---|
| **Quiet** (default) | 38 % | 51.9 °C | 26.7 °C room | 28.0 °C room |
| Balanced | 41 % | 50.4 °C | 28.8 °C room | 30.0 °C room |
| Cool | 43 % | 49.8 °C | 30.1 °C room | 31.2 °C room |
| Cold | 46 % | 48.6 °C | 31.5 °C room | 32.6 °C room |
| **Bright** *(raised LED drive)* | **44 %** | **53.8 °C** | **24.4 °C room** | **26.1 °C room** |

**Bright's row is not comparable with the four above it, and its numbers are inferred.** The
other four are solved against this unit's *measured* thermal plant. Bright is solved against
that plant scaled up for a raised LED drive — Presentation at 90 % of the driver maximum
rather than the stock 76 — and **nothing has ever been held at that drive**, so every figure
in its row is a prediction from a fitted line. One twelve-minute hold at duty 45 confirms or
corrects it; the derivation, the fit, and what happens if it reads high are all in
[docs/curve.md](docs/curve.md#the-bright-preset).

On the stock drive Bright settles at 39 % / 51.5 °C at 24 °C — Quiet, near enough. It is not
a fifth step up the ladder; it is a different curve for a different machine.

**What Bright changes.** Same knees as Quiet, same deadband, slew and SoC guard, and Normal
and Eco / Super Eco keep Quiet's duty row untouched. Only the Presentation row differs, and
it is Quiet's own 2.0 duty/°C rise carried straight through the 51–55 °C shelf instead of
levelling off on it:

```
tempC   =  47   51   55   60   66   70
Quiet   =  30   38   40   50   68   83
Bright  =  30   38   46   56   70   83     Presentation only
```

On the raised drive that settles at 44 % / 53.8 °C in a 24 °C room, against Quiet's
40.6 % / 55.3 °C — already over the ceiling — and it stays under the owner's 50 % line until
about a 29 °C room.

**Raising the LED drive costs the dim modes their silent floor, and that is the drive raise
rather than the preset.** Bright leaves their rows alone precisely so it does not make it
worse, but it is worth knowing before switching either on: with Normal at 70 % drive rather
than 55, **Normal leaves duty 30 at an 18.8 °C room instead of a 24.5 °C one** and sits at
about 37 % / 50.5 °C in a 24 °C room. Eco at 50 % drive reaches the 47 °C floor edge at a
27 °C room rather than a 30.7 °C one. Super Eco is untouched below a 31 °C room. The full
tables are in [docs/curve.md](docs/curve.md#what-it-costs-stated-rather-than-buried).

Two design points worth stating, because both were arrived at the hard way:

**The floor is not offset.** All five presets idle at 30 %. Below the floor edge the light
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
— and that was not assumed safe: all five are driven through `tools/CurveSim.java` across
15–35 °C ambient at four thermal poles, and through the host suite at every ambient from 14
to 34 °C on every build.

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
a raised drive costs about twelve duty points more, and 54 °C is where the `Bright` curve
rests, so the two controllers still meet. A ceiling you have set by hand is left alone.) Presentation, both controllers solved against the measured plant:

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
| `docs/` | curve derivation, findings, safety, deployment, measuring, measurement conditions |
| `release/` | the signed APKs — `fanlab-system.apk` (platform-signed, the one to install) and `fanlab-plain.apk` (debug-signed, observe-only) |
| `final_curve.txt` | the deployed curve, in the app's own encoding |

## Licence

MIT — see [LICENSE](LICENSE).
