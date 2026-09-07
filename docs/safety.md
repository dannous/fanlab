# Safety

Read this before installing. It is short, and none of it is boilerplate.

## What this actually does to your projector

It runs it **hotter than Philips does**. That is the trade, stated plainly: quieter fan,
warmer machine. On the unit this was developed against, Presentation settles at about
**52 °C** where the stock controller holds **44–46 °C**.

Everything below is about whether that is a sensible trade and where its limits are.

## The two protections you keep

Neither of these is touched, and neither can be disabled from an app:

- **A 75 °C over-temperature shutdown** in the platform, watching the LED thermistor.
- **A kernel fan-stall watchdog** — two missed tacho windows and the projector powers off
  in about a second.

So the failure mode of too little airflow on this hardware is a **shutdown, not silent
cooking** — for the LED path. Read on for the caveat.

## The limit that cannot be measured

The DMD (the imaging chip) has its own temperature rating. **It cannot be measured on this
hardware.**

- The display controller's `Read System Temperature` command responds, but returns a hard
  zero.
- The board *does* declare a second temperature device at i2c `0x1c` (`dlp_i2c_tmp`), but
  no driver in the kernel binds it and the bus node is root-only, so no application can
  read it. What it measures is unknown.
- The DMD datasheet states the array temperature "cannot be measured directly and must be
  computed analytically" from a test point on the package — a test point nothing on this
  board reads.
- The offset between the LED-board thermistor we *can* read and the DMD is a board constant
  that is **unmeasured, and unbounded in sign**.

So: **this project cannot tell you the DMD is fine.** It can tell you it is operating
inside the envelope the manufacturer's own software treats as normal, which is a different
and weaker claim, and it is the strongest one available.

## Why 55 °C

The ceiling used here is **55 °C on the LED thermistor**, because that is the temperature
at which **the stock controller itself commands maximum fan.** It is the manufacturer's own
software declaring the machine out of its comfort zone, on the same sensor.

It is an empirical line, not one any datasheet endorses. The datasheets cannot supply a
better one — see [findings.md](findings.md) for why the analytical chain fails.

Supporting evidence, gathered separately and agreeing:

- Every hard limit is far away — red projector LEDs are rated to 100–105 °C solder point,
  and the only published trip point for an engine thermistor of this class is 70 °C.
- **Philips shipped this same trade themselves.** PicoPix Max firmware 1.0.32 raised the
  target temperature in exchange for a measured 7 dB less fan noise; testers logged 50 °C
  steady and 53 °C worst case, Philips called it working as expected, and it was never
  rolled back.
- Four years of the Screeneo U4 owner forum contains no report of an over-temperature
  shutdown, a thermal warning, dimming, or colour degradation.

**The counterweight, not buried:** one engine vendor recommends keeping the LED board at
**≤ 50 °C to maximise LED lifetime**. At 52 °C this is about two degrees over that
guideline. It is a lifetime-optimisation figure, not a rating — it sits 50 °C below the
actual limit — but it is the honest cost and it is the best explanation anyone has produced
for why Philips picked 46 °C.

## What it costs you

- **A small, reversible loss of red output** and a slightly cooler white point. Red LED
  output falls with junction temperature; the effect is an operating point, not damage, and
  it reverses on cooling. Estimates for the magnitude vary by about 3× between sources, so
  treat any specific percentage with suspicion.
- **Possibly faster long-term red fade**, on the order of 1.2–1.7× against a 30,000-hour
  rating. Against a few hours a day this is the difference between "still fine" and "still
  fine".
- **Focus drift is the failure Philips actually named** — heat distorting the optical
  engine and softening the image. It is observable and reversible, and the remote has a
  refocus button.

  **It was looked for on the deployed curve, and not observed.** From cold, the top of the
  projected image was already slightly soft; after heat-soaking on this curve, it was
  *equally* soft — no change between the two states. A constant blur that does not vary
  with temperature is not thermal drift; it is something else, probably geometry or an
  optical tolerance.

  That result is stronger than it first appears, because the autofocus cannot be masking
  it: the autofocus stack is pure time-of-flight and open-loop, with **no temperature input
  anywhere** in the native library or any of its classes. It cannot silently refocus in
  response to heat.

  **Caveats, and they matter.** This is a single observation, by eye, at about 24 °C
  ambient — the condition in which the curve sits *furthest* from its ceiling. It shows no
  *observable* drift, which is not the same as bounding a slow one. It is worth repeating
  in a warm room, where the curve runs closest to 55 °C.
- Not affected: the power supply is an external brick, so its capacitors are not in the
  airflow at all. And the fan itself lasts *longer* run slower.

## The one way to leave it in a bad state

The app disables the stock controller through a system property that lives in `/data`.
**That setting survives a reboot, a force-stop and an uninstall.**

The app manages this automatically — it re-arms the stock controller whenever it stops
driving, and re-checks periodically in case something else changes it. But it can only do
that if it is running:

| how the app stops | stock controller handed back? |
|---|---|
| normal stop, RESTORE control, `deploy.sh revert` | **yes** |
| crash, low-memory kill | **yes** — the platform restarts it |
| reboot | **yes** — autostart is forced on while it is driving |
| **force-stop, or Disable in Settings** | **no** — cancels the restart *and* suppresses boot recovery |
| **clearing app data** | **no** — also erases the autostart flag |
| **uninstall** | **no** — runs no code, never returns |

**So: press RESTORE before uninstalling, force-stopping or clearing data.**

If it has already happened, the projector is not in danger — the 75 °C shutdown and the
stall watchdog are both untouched, and the fan holds the kernel's own default rather than
stopping. But nothing is responding to temperature. One line fixes it:

```
adb shell setprop persist.sys.fanctrl.by.temperatue 1
```

Or reinstall the app, press the takeover control, then press RESTORE.

## The CAIC switch adds no heat, and may add nothing

The `CAIC` control is the one thing in the app that writes to the display controller rather
than the fan. It asks the DLPC3436 to run Content Adaptive Illumination Control: lower the
LED current and raise the mirror duty cycle together, per frame, on content that does not
need full output. Done as designed, that is **less** LED power and **less** heat at the
thermistor — it cannot raise LED current above what the brightness mode already commands.
The 75 °C shutdown and the fan-stall watchdog are as untouched by it as by everything else.

What it is **not** is a known quantity on this board. CAIC lowers LED current through a TI
LED driver, and this board has none — the LED currents are set by the kernel, over SPI, to
two MAX20096 drivers the display controller does not talk to. So the honest range of
outcomes runs from "saves power as designed" through "brightens the image at the same
power" to "does nothing" to "visible artefacts from lookup tables never calibrated for this
engine". Nothing in the app or these documents claims a saving; the screen reports the
setting, and separately what the controller itself said when asked, and says `unverified`
when it has not been asked or could not answer.

It is runtime only. The write is `w 50 1 1` to `/sys/class/dlpc343x/picoreg`, the undo is
`w 50 1 0`, and a **power cycle undoes it regardless**, because the controller reloads the
factory settings — which have it off — at every boot. The factory settings partition is
deliberately never written. The app turns it off itself when it stops, if it was the one
that turned it on, and RELEASE CONTROL turns it off along with the fan. The one way to be
left with it on is a process killed with no chance to run code, and then the next power
cycle clears it.

## The controller cannot see SoC temperature

The fan is driven by the LED thermistor, and nothing in the loop reads the SoC. Switching
UHD processing on raises the three SoC die sensors by about **11 °C** while moving the LED
thermistor by **0.5 °C**, so the fan does not respond to it at all.

**What that does and does not mean.** Only one of the three zones controls anything:

```
pll_thermal   3 cooling devices bound, all to trip 1 = 75 C   <- the only one that acts
ddr_thermal   0 bindings                                       <- monitor only
sar_thermal   0 bindings                                       <- monitor only
```

`ddr_thermal`, the hottest reading, has nothing bound to it — it is a thermometer with no
wire attached. And the 60 °C trip labelled "passive" on every zone has no cooling device
bound either. A trip point only does something if something is bound to it; the label alone
means nothing.

So throttling begins when **`pll_thermal` reaches 75 °C**, and observed with UHD on it sits
at **64 °C**, with all four cooling devices at state 0 and the CPU at its full
1,908,000 kHz. Nothing is being throttled.

If it ever did throttle, the consequence is reduced CPU and GPU frequency — stutter or
dropped frames — not damage. A separate "hot" trip sits at 85 °C above it.

**The honest limitation.** A quieter fan leaves the SoC warmer than stock would, so that
11 °C of margin is smaller than it was. Nothing in the fan's control loop is watching it,
and the **stock controller is equally blind** — it reads the same single sensor, so this is
not something the new curve introduced. The case worth watching is a warm room with UHD on
and heavy content, which stacks every load at once.

See [measurement-conditions.md](measurement-conditions.md) for the numbers, and for the
measurement that would establish how much authority the fan actually has over SoC
temperature — which has not been taken.

## Scope

- **Firmware 1.7.1 only.** Property names, sysfs paths and every offset behind the analysis
  are version-specific. Other versions have not been examined.
- **The curve was measured at 24 °C ambient.** It is designed to hold its ceiling to about
  30 °C and has not been validated in a genuinely hot room.
- **One unit.** Unit-to-unit variation is absorbed by the controller — it is closed on
  temperature, so a projector that runs hotter simply gets more fan — but the specific
  numbers quoted here are from one machine.
- Not endorsed by or affiliated with Philips or Screeneo Innovation SA. If your projector
  is under warranty, installing a platform-signed system app is unlikely to be viewed
  kindly.
