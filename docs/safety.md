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

## The three display-controller features, and why the app no longer offers any

`CAIC`, `LABB` and the Looks were all controls in this app. All three were measured on the
projector on 2026-09-07 and all three were taken off the screen. This section is here
because a safety document that only lists live hazards is a document that keeps re-inviting
the question; the answers are recorded so nobody has to run the experiment again.

**None of them was ever a thermal hazard, and none is now.** LABB and the Looks are pure
image processing inside the DLPC3436 — the LED drive is untouched, so the light engine draws
what it drew before. CAIC's method is the opposite direction, less LED power rather than
more. None of the three touches `rgbcurrent`, `redcurrent` or anything else the LED
thermistor responds to, and the 75 °C shutdown and the fan-stall watchdog were never in
play. The risk they carried was a bad *picture*, which is why they were behind a
confirmation window; withdrawing them retires that risk along with the feature.

**CAIC: the engine runs, and its output goes nowhere.** A/B with the fan pinned, seven
minutes a hold, Presentation, video playing — CAIC off **52.33 °C**, CAIC on **52.33 °C**,
within-hold spread ±0.04 °C. Identical. Raising the gain budget to the maximum 4.0 changed
nothing either; the write was accepted and `0x85` read back `00 80 60` with the status bit
clear. The engine is demonstrably alive — the debug gain bars move with the content and
`0x5F` gives live per-colour currents that vary with the image — but the LED current never
leaves the kernel's stock per-mode table, because TI routes every LED-current command to a
DLPA200x PMIC and **this board has none**: the currents are driven by the SoC over SPI to
two MAX20096 chips the DLPC cannot reach. Structural, not a setting.

**LABB: it works, and the work is unwanted.** `w 80 2 11 80` is accepted, the register reads
back `11 80`, and the live gain byte tracks the content — `0x20` idle, `0x27` and `0x24` on
real video. Watched: *"really washed out seeming"*. That is LABB's documented job. Gaining
up the darker parts of a frame raises the black floor, and a raised black floor is flattened
contrast.

**The Looks: brightness and a green cast are the same purchase.** All 19 were swept and read
via `26h`. Look 0 is 40/40/20 R/G/B and reads white on a white field; every other Look cuts
red to 25–33 % and gives the time to green, and Looks 1 and 15 both read visibly green.
It cannot be rebalanced: neutral white on Look 15 needs red flux up 1.97×, so red current up
about 2.5× to 175 %, against a hard ceiling of 97. Red is the weak primary here and has
nothing to give. Look 0 — the only one the kernel ever selects, because it zeroes the Look
table at probe — is optimal by construction.

**What is left in the app.** The command encoders and decoders in `PicoReg`, their tests,
and a read of all three once a minute whose result is printed under **Diagnostics**,
read-only, beside the finding it supports. That is deliberate: a negative result is only
worth anything if it can be re-checked, and a firmware that changed one of these answers
would show up on that screen and nowhere else. **Nothing in the app writes `0x50`, `0x80`,
`0x84` or `0x22`.**

**If a previous build left one on.** Every one of these registers is runtime-only and the
factory `picosetting` blob is never written, so a power cycle clears all of them. This build
also stores no preference that could turn one back on at boot.

## The LED drive override runs the light engine harder

Separate from everything above, and off by default. It writes `rgbcurrent` and `redcurrent`
to drive the four brightness modes at **35/55/75/90 %** instead of the kernel's own
20/40/55/76 — 18 % more drive in Presentation, and about **+3.4 °C** on the LED thermistor
for every 10 points at a fixed fan duty, so roughly **+4.8 °C** for the 76 → 90 step. 90 is
the drive the Bright curve family was drawn against and the drive its plant scaling was
measured at, so the brightness preset and the curve now agree on one number.

The safety case is one rule, and it is a conjunction: **the override applies only while
this app is the thing cooling the machine.** CURVE or LINEAR, no AUTO or VERIFY session
running, the light engine on, the fail-safe clear, the display awake. Anywhere else the
kernel's own table goes straight back, within a second, because the alternative is
Presentation-class LED heat under whatever fan ladder `rgblevel` happens to select — which
is precisely the hazard the rest of this document exists to avoid. Any doubt, including an
unreadable `led_status`, resolves to *not* applied.

On top of that it has a ceiling of its own: above **60 °C** on the LED thermistor the
override is dropped and **latched off** until the brightness mode or the setting changes.
60 is five degrees above the 55 at which the stock controller commands maximum fan. There is
no automatic re-arm, because brightness cycling on the wall is more objectionable than a
fan swing.

**The curve preset families exist to keep that trip a backstop.** Switching the override on
moves the stored curve to the Bright version of the step it is on, and switching it off moves
it back; the screen only offers the family the drive allows and a broadcast naming one from
the wrong family is refused. The reason is measured: at drive 90 in a 28 °C room a Bright step
settles at 56 °C, four degrees under the trip, where the same drive on a standard step settles
at **58 °C** — 2 °C under it. A pairing that close reaches the trip on a warm afternoon, and
the trip drops the brightness with nothing on screen to say why, so the symptom is a picture
that dims itself for no visible reason. A hand-edited curve has no counterpart and is left as
it is, which is the one case where the pairing is still the owner's to get right.

It also starts only at a service start, a settings change, or a mode change — never part
way through a run. That is not tidiness: switching it on under LINEAR mid-session makes the
fan walk about 12 duty points at one per five seconds, and a 14-point cumulative walk is the
one thing on this machine the owner has actually heard and objected to.

**What it costs.** Under CURVE the extra heat is paid in temperature: Bright Quiet rests
around 53.8 °C rather than Quiet's 51.9 in a 24 °C room. Under LINEAR it is paid in fan, so
the default ceiling moves from 52.0 to **54.0 °C** while the override is on — roughly where
the Bright Curve family rests, so the two controllers can still be compared by ear. A ceiling you
set by hand is never moved, and nothing is written to the stored setting: switching the
override off puts the ceiling back. **The Presentation plant scaling behind these numbers is
measured — ×1.208 at drive 90 — but the equilibria themselves were solved at the earlier
fitted ×1.1735 and read about half a degree low.** The dim-mode scalings are still inferred
entirely. The two-degrees-over-50 caveat above applies with two more degrees on top.

The 75 °C shutdown and the fan-stall watchdog are untouched by it, like everything else here.

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
