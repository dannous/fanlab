# The curve: derivation and verification

Two measurement campaigns sit behind this curve. The first swept the fan in short holds
and characterised duty 40 and above. The second used twelve-minute holds to measure the
quiet end directly, where the thermal response steepens sharply and a short hold cannot
resolve it.

Measured at ambient **24 °C** with twelve-minute holds. Tooling in `tools/`. The full
configuration these numbers were taken in — including that UHD processing was off — is in
[measurement-conditions.md](measurement-conditions.md).

---

## The curve

```
tempC   =  47   51   55   60   66   70
duty    =  30   38   40   50   68   83        <- identical for all three profiles
             |    |____|
             |    shelf: 38->40 across the band Presentation occupies
             floor: flat 30 below here, where Normal, Eco and Super Eco all sit

hysteresisC     = 0.8
slewUpPerSec    = 0.25      (1 duty point per 4 s)
slewDownPerSec  = 0.12      (1 duty point per ~8 s)
minDuty         = 30
maxDuty         = 83
idleDuty        = 10        (light engine off)
```

Encoded, for `tools/deploy.sh` and `ConfigReceiver`:

```
v1,47,51,55,60,66,70,30,38,40,50,68,83,30,38,40,50,68,83,30,38,40,50,68,83,0.8,0.25,0.12,10,30,83
```

The four presets are this curve with 5, 10 and 15 added to every knee duty, clipped at 83.
A uniform offset leaves every segment's width and slope untouched, so the stability
argument is made once and inherited by all four.

### Why one curve for all three modes

This is the change that mattered most, and it was not the plan going in.

The stock controller has a floor per brightness tier, and `CURVE.md` inherited that shape
— a separate duty column for Eco/Super Eco, Normal and Presentation. But the constraint
being tuned against is **optical-engine temperature**, and the optics do not care which
mode produced the heat. Meanwhile the feedback loop already finds whatever duty holds a
given temperature, so the curve does not need to be told the mode: the per-mode
differences fall out of the plant by themselves. Presentation ends up at duty 38 and Super
Eco at 30 with the *same* curve, because Presentation is 24 °C hotter at the same duty.

The payoff is a property that per-mode columns cannot have. `FanCurve` deliberately
bypasses the slew limiter on an upward tier change, so with per-mode floors, switching Eco
→ Presentation stepped the duty **10 points in one tick** — the single audible event in an
otherwise silent design. With one shared curve there is nothing to step between:

```
per-mode columns:  largest single-tick duty change: 10
one shared curve:  largest single-tick duty change: 1
```

That removes the jump **without touching `FanCurve`'s tier-change rule**, which is tested
safety behaviour and was correct for the reason it was written.

### Why these knees

The shape is **two flat regions joined by a rise**, and both flats are placed on measured
operating points rather than on round numbers. A flat region has no boundary to park on,
which is the whole fix for the original bug; a slope is better than the stock controller's
step, but only a flat is genuinely indifferent to small temperature movement.

* **Flat at duty 30 up to 47 °C — the floor.** 30 is "inaudible even up close". Normal,
  Eco and Super Eco all live here: their settled thermistor readings are 46.5, 38.5 and
  34.4 °C, so all three sit on the flat and never move the fan. The edge is at 47 and not
  46 because Normal's reading reaches 47.1 °C at its 95th percentile — a floor ending at
  46 lifts Normal off duty 30 for 96 % of its running time. Note this edge is set from
  **field measurement, not from the plant table**, whose Normal column under-states the
  rise by about 2.5 °C.
* **47 → 51 °C rises to duty 38**, two duty points per °C.
* **Duty 38 rising to 40 from 51 to 55 °C — the shelf.** This is where Presentation
  sits. Its rise above ambient at duty 38 is 28.1 °C, so a room from 22.9 to 28.0 °C puts
  the thermistor inside this band and the fan moves by at most two duty points across the
  whole of it. In integers it reads 38 % at 51 °C, 39 % from 52 °C and 40 % from 54 °C.

  Duty 38 is the quietest value that still holds the LED under 55 °C in the warmest room
  this unit has been measured in: 37 breaches it in a 26.3 °C room and 36 in a 25.7 °C
  room, and this unit's room has been 26.2 °C.

  **Why it tilts rather than being flat, and why by two points.** A flat 38 was built
  first and is quieter — 0.14 duty changes an hour against 0.79, and only two duty values
  in play. It was changed on the owner's instruction, and the instruction was right: a flat
  shelf serves its quietest duty at the *top* of the band as well as the bottom, and he does
  not want the light engine above 54–55 °C for lamp life. He set the top of the tilt himself
  — "happy with the fan up until 40" — so the shelf spans 38 to 40 and stops there.

  Two duty points across four degrees is a slope of **0.5 duty/°C**, a quarter of the ramp
  this curve was built to get off and a twentieth of the stock ladder's step. It buys 0.8 °C
  at the top of the band, which carries the 55 °C line out to a 28.0 °C room instead of 26.8
  and the 54 °C line to 26.7 instead of 25.8. As a side effect worth having, it doubles the
  unit-to-unit tolerance from +2 °C to +4 °C, because the extra authority is available to a
  hotter unit as well as to a hotter room.

  The cost is measured, not waved away: 0.79 duty changes an hour over the field log against
  the flat shelf's 0.14 — still one point at a time, and still below the 1.00 of the curve
  this replaces. It was verified on hardware in a 26.1 °C room: the flat shelf had let the
  thermistor drift to 54.24 °C at duty 38, and the tilted curve asked for more fan on its
  first tick.
* **55 → 60 °C** rises at **2 duty points per °C**, then **60 → 70 °C** steepens to 83.
  This is the backstop. The 2 %/°C slope is the owner's and its purpose is *transient*, not
  steady-state: it exists to arrest a runaway. Against the 1.4 %/°C it replaced it is worth
  +2 duty points at 58 °C, +3 at 60 and +4 at 61 — measured against the blocked-vent fault
  run of 2026-09-07 — while changing **nothing at all below 55 °C** and moving the settled
  point by only 0.15 °C at a 30 °C room. Free where the machine lives, useful where it does
  not. Above 60 °C is territory the plant cannot reach at any plausible ambient; the hottest
  reading ever recorded on this unit is 52.85 °C, and the hottest ever *induced*, by covering
  both vents with cloth, is 61.3 °C.
* **Hysteresis 0.8 rather than 0.5.** At 0.5 the duty dithered by one point at 24 °C
  ambient (4 changes in 50 minutes). 0.8 removes it entirely.

### Why the shelf starts at 51 and not 50

Three constraints, and they do not quite fit. Normal's settled reading reaches 47.1 °C, so
the floor cannot end below 47. Presentation's settled reading starts at 50.8 °C, so a shelf
covering all of it cannot start above 50.8. That leaves 3.7 °C for the rise between them —
and **a rising segment needs 4 °C.**

That last figure is not a rule of thumb, and it is not derived either — the 3 °C version was
built and run through `tools/CurveSim.java` rather than argued about. It hunted by two duty
points at 18 and 21 °C ambient, where every 4 °C version tried was steady at every thermal
pole. So the 4 °C rise is kept and the shelf starts at 51.

Do not read 4 °C as a threshold with a mechanism behind it. Cool's 47–51 °C rise is steeper
still, at 4.5 duty/°C, and is perfectly steady; the discussion under
[Stability](#stability--measured-not-argued) has the full set of measurements and why no
static rule survives them.

The cost is the coldest degree of the room's measured range: below about 23 °C the
thermistor drops off the bottom of the shelf onto the rise and the duty comes down a point.
Against the field log that is 92 % of settled Presentation time pinned at duty 38, at 0.14
duty changes an hour, with the duty confined to 37-38.

The alternative — floor at 46, shelf at 50 — pins 100 % of Presentation but lifts Normal
off duty 30 for 96 % of its running time, and Normal is inaudible today. That trade was
measured, not guessed, and refused.

### What this replaced, and why

The previous curve was flat at duty 30 below 48 °C and ramped 48 → 55 °C at 2.14 duty
points per °C. Its documentation claimed the operating point sat mid-shelf with the fan
never moving. **Thirty-six hours of field log refuted that:** the settled `degC` median was
51.85, three to five degrees up the ramp, and **every one of the thirty settled duty changes
in that log happened on the ramp while the shelf produced none.** The shelf was real and the
machine simply was not on it. Closed-loop against the same room, the old curve holds its
modal duty for 32 % of settled time and changes once an hour; this one holds 92 % and
changes once every seven.

One retraction to note, because this document previously asserted it: the claim that "above
about 2.4 duty points per °C the controller hunts" was withdrawn as an artefact of a bad
simulator. The geometric constraint above — rise width against the deadband — is the one
that has survived being tested, and it is what the 3 °C attempt ran into.

## The measured plant

Rise above ambient at steady state, °C. **Bold = measured in run 2 with a 12-minute hold.**

| duty | Presentation | Normal | Eco | Super Eco |
|---:|---:|---:|---:|---:|
| 83 | 19.4 | 13.5 | — | — |
| 70 | 20.2 | 14.1 | — | — |
| 60 | 21.5 | 14.8 | 11.4 | 6.1 |
| 55 | 22.3 | 15.3 | — | — |
| 50 | 23.7 | 16.1 | 12.1 | 7.3 |
| 45 | 24.9 | 17.1 | 12.8 | 7.6 |
| 40 | **26.91** | 18.1 … 20.0 | 13.4 | 8.1 |
| 35 | **29.92** | 18.6 … 20.6 | 14.4 | 8.7 |
| 30 | **33.76** | *~19.1 …* | *~15.0* | *~9.2* |

The Normal column is a **range, not a measurement**, and deliberately so. Run 1 measured it
from a cold chassis and reads low; run 2 measured it immediately after the Presentation
holds, from a chassis at 57.8 °C, and reads high — over the duty-40 hold the LED fell
5.40 °C and `ddr_thermal` fell 6.41 °C, **both still falling at twelve minutes**. The truth
is between the two bounds. Nothing in the design turns on where: Normal would need a
duty-30 rise above ~25 °C to breach the ceiling in a 30 °C room, and both bounds are far
below that.

Worth noting how that would have gone undetected. With only the LED sensor the duty-40
trace looks like a clean settling curve with a plausible asymptote and a respectable
residual. It is the *chassis* sensor that shows the whole machine still shedding heat
underneath it. That is precisely why `RUNS.md` asked for the SoC zones.

**What run 1 got wrong.** Its Presentation rows below duty 40 were extrapolations, and it
flagged them as its least trustworthy numbers. It was right to:

| duty | run 1 (extrapolated) | run 2 (measured) | error |
|---:|---:|---:|---:|
| 40 | 26.60 | 26.91 | −0.31 |
| 35 | 28.30 | 29.92 | −1.62 |
| 30 | 29.80 | **33.76** | **−3.96** |

The error grows as the duty falls, because run 1 extended a slope of ~0.34 °C per duty
point into a region where the truth is ~0.6.

**Fan authority is not 0.17 °C per duty point** — that is the average across the loud half
of the range. Measured, Presentation:

| duty band | 70–83 | 60–70 | 50–55 | 45–50 | 40–45 | 35–40 |
|---|---:|---:|---:|---:|---:|---:|
| °C per duty point | 0.062 | 0.130 | 0.280 | 0.240 | **0.402** | **0.602** |

The plant is about **ten times more responsive at duty 35 than at duty 80**. Good news for
control authority; bad news for stability margin, since loop gain is (curve slope) ×
(plant slope) and the quiet end is where both are largest.

## Where it settles

Driven through the shipping controller by `CurveSim`, so these are what the node is
actually written, not solver output:

| room | Presentation | Normal | Eco | Super Eco |
|---|---|---|---|---|
| 21 °C | 36 · 50.1 °C | **30 · inaudible** | **30 · inaudible** | **30 · inaudible** |
| 23 °C | **38 · 51.1 °C** | **30 · inaudible** | **30 · inaudible** | **30 · inaudible** |
| **24 °C** | **38 · 51.9 °C** | **30 · inaudible** | **30 · inaudible** | **30 · inaudible** |
| 26 °C | **39 · 53.4 °C** | **30 · inaudible** | **30 · inaudible** | **30 · inaudible** |
| 27 °C | **40 · 54.2 °C** | **30 · inaudible** | **30 · inaudible** | **30 · inaudible** |
| 28 °C | 40 · 54.9 °C | 32 · barely audible | **30 · inaudible** | **30 · inaudible** |
| 30 °C | 42 · 56.1 °C | 34 · barely audible | **30 · inaudible** | **30 · inaudible** |
| 33 °C | 46 · 57.8 °C | 38 · quiet | 33 · barely audible | **30 · inaudible** |

Presentation moves by two duty points across 23 to 28 °C — five degrees of room drift for
two points of fan — and the light engine, not the fan, takes up the rest. The three dimmer
modes are on the floor and read 30 throughout the room's real range.

**How far each preset holds a ceiling**, which is the number to choose a preset by:

| preset | fan at 24 °C | holds LED ≤ 54 °C to | holds LED ≤ 55 °C to |
|---|---|---|---|
| Quiet | 38 % | 26.7 °C room | 28.0 °C room |
| Balanced | 41 % | 28.8 °C room | 30.0 °C room |
| Cool | 43 % | 30.1 °C room | 31.2 °C room |
| Cold | 46 % | 31.5 °C room | 32.6 °C room |

Those are `equilibria.py` fixed points, solved in floating point. The shipping controller
writes whole duty points and therefore does slightly better — `CurveSim` has Quiet holding
54.9 °C in a 28 °C room, because rounding 39.4 up to 40 buys a few tenths. The table above
is the conservative reading, which is the one to design against.

**A curve cannot hold a hard ceiling, and it is worth being explicit about why.** To pin
the LED at exactly 55 °C the curve would have to command 38.2 % in a 27 °C room and 44.8 %
in a 30 °C room — two different duties at the same 55 °C input. A curve is a function of
temperature, so no shape does that. Above the shelf it rises at 1.7 duty/°C and the
light engine settles a little over 55 instead: 0.3 °C over at a 28 °C room, 1.4 °C over at
30 °C. Holding a temperature rather than a fan speed is what `LINEAR` mode is for.

The presets add 5, 10 and 15 to every duty. In Presentation at 24 °C they settle at
38 % / 51.9 °C, 42 % / 50.3 °C, 44 % / 49.2 °C and 48 % / 48.3 °C, so Balanced, Cool and
Cold buy 1.6, 2.7 and 3.6 °C. In the dimmer modes the offset transfers almost 1:1 into fan
speed but buys only about 3 °C, because the light engine is already close to the room and
there is little left for the fan to take — so the presets are worth much more in
Presentation than below it.

Stock, for comparison, runs Presentation at a floor of 59 and oscillates to 70 — because
its first rung fires at 46 °C with **no hysteresis**, and the equilibrium parks on that
boundary. That oscillation is the original complaint and a continuous curve removes it
structurally.

## Stability — measured, not argued

Three separate checks, because the first two are not trustworthy on their own.

**1. Static loop gain — the wrong tool, kept only as a screen.** `solve_curve.py` computes
(curve slope) × (plant slope) and calls < 1 stable. That models the loop as an instantly
iterated map, which ignores the plant lag, the slew limiter and the 1 Hz sample. It is
useful for locating the resting point and it **should not be used to judge stability** — it
rejects curves that are perfectly well behaved.

**2. Multiple equilibria — `equilibria.py`.** A steep curve can leave the system with two
stable points, or none, and hunt between them. Iterating to a fixed point cannot see this;
scanning duty across the range and counting sign changes can. The curve above has **exactly
one** equilibrium for every mode from 21 to 33 °C ambient.

**3. Time domain against the shipping code — `CurveSim.java`.** Calls the real `FanCurve`
on a two-pole plant with sensor noise, sweeping the slow pole from none to 3000 s (one pole
cannot oscillate, so a single-pole test would always pass and prove nothing).

```
ambient 22C   0 changes in the last 50 min -> STEADY   (all four slow poles)
ambient 24C   0 changes                    -> STEADY
ambient 26C   0 changes                    -> STEADY
ambient 28C   0 changes                    -> STEADY
ambient 30C   0 changes                    -> STEADY
ambient 33C   0 changes                    -> STEADY
```

Not "moves smoothly" — does not move. The largest single-tick duty change anywhere in those
runs is **1 point**, against the stock controller's 15. All four presets pass the same sweep.

**One known exception, and it is narrow.** Quiet at 16 °C ambient hunts by two duty points
on three of the four slow poles. 14, 15, 17 and 18 °C are all steady, so this is a rounding
knife-edge at one ambient rather than a band of instability — at 16 °C the operating point
lands almost exactly between two integers on the rise below the shelf. It is six degrees
below the coldest room this unit has been measured in (21.9 °C), and the swing is two points
at duty 32, where three points went unnoticed by ear at the louder duty of 42-45. Recorded
rather than fixed, because fixing it means moving a flat region off a measured operating
point.

**4. Closed-loop replay against the real room.** The strongest of the four, and the one that
is not a model of the room. Ambient is inferred per-sample from the field log (a property of
the room, not of the curve), smoothed to remove sensor noise, and the loop solved at each
point — so a different curve is allowed to produce a different temperature, which an
open-loop replay over logged temperatures is not. Over 13.9 hours of settled Presentation
time:

| | modal duty held | duty changes | range |
|---|---|---|---|
| previous curve | 32 % of the time | 1.00 / hour | 36–40 |
| **this curve (shelf 38–40)** | **53 %** | **0.79 / hour** | **37–39** |
| shelf 38–39, considered | 69 % | 0.22 / hour | 37–39 |
| flat shelf at 38, considered | 92 % | 0.14 / hour | 37–38 |

The two rejected variants are on that table because they are the honest comparison: both
are quieter, and the tilt was bought deliberately for 0.8 °C of light-engine temperature at
the top of the band, on the owner's instruction and to his stated limit of 40 %. Every
change in every variant is a single duty point; what the tilt costs is how often one
happens, and even at 0.79/hour that is below the curve this replaces.

**What bounds curve steepness.** Not loop gain — a slew-limited controller on a lagged
plant does not oscillate merely because the gain exceeds one, and driving the real
controller against the measured plant produces no hunting below about **19 duty points
per °C**, with transport delay to 180 s, sampling to 60 s, plant poles from 5 to 800 s, or
ADC quantisation added.

The constraint is **geometric**. Hysteresis is applied to the *input temperature*, so a
rising segment narrower than the deadband has no resting state that fits inside it: the
held temperature can never settle within that segment, and the output swings between its
endpoints. So the test is

```
width of every rising segment (°C)  >>  hysteresisC
```

`equilibria.py` reports it. Every rising segment of this curve is **at least 5× the
hysteresis band**:

| segment | slope | width vs deadband |
|---|---:|---:|
| 47–51 °C | 2.00 duty/°C | 5.00× |
| 51–55 °C | 0.50 duty/°C | 5.00× |
| 55–60 °C | 2.00 duty/°C | 6.25× |
| 60–66 °C | 3.00 duty/°C | 7.50× |
| 66–70 °C | 3.75 duty/°C | 5.00× |

Nothing the machine reaches in normal use is steeper than 2.00 duty/°C, and the operating
point itself sits on the 0.50 duty/°C shelf. The steeper segments above 60 °C are the
backstop.

**A correction to what this section used to say.** It claimed the real controller "produces
no hunting below about 19 duty points per °C". That is wrong, and the way it was found to be
wrong is worth recording: a 3 °C-wide rise at **2.7 duty/°C** — nowhere near 19 — hunted by
two duty points at 18 and 21 °C ambient when run through `CurveSim.java`.

**And a correction to the correction.** "Every rising segment at least 4 °C wide" was then
adopted as the design rule. It is not sufficient either: Cold's rise from the pinned floor was
exactly 4 °C wide, passed every static check in the suite, and hunted by four duty points at a
17 °C room.

The obvious next move was to bound the product, `slope × hysteresisC`, on the grounds that it
is the width of the deadband measured in duty points. **That is also wrong**, and the shipping
curves are the counter-example. Every measured case, on segments the machine can actually rest
on:

| slope | where | result |
|---:|---|---|
| 2.00 duty/°C | Quiet, 47–51 °C | steady |
| **2.67 duty/°C** | a 3 °C rise, built and rejected | **hunts at 18 and 21 °C** |
| 2.88 duty/°C | Cold, 43–51 °C | steady |
| **4.50 duty/°C** | Cool, 47–51 °C | **steady** |
| 5.75 duty/°C | Cold's old 47–51 °C | hunts at 17 °C |

2.67 hunts and 4.50 does not. No monotone function of slope can produce that ordering, so
**slope is not the determinant** — and neither is width, nor their product. Whether a curve
hunts depends on where its equilibrium happens to fall relative to the integer duty boundaries
and the deadband, which is a property of the whole closed loop at one ambient, not of a segment
in isolation. Quiet's surviving wobble at 16 °C is the same phenomenon: 2.00 duty/°C is the
gentlest reachable slope in the whole preset set and it is the one that wobbles, purely because
that ambient puts the operating point between two integers.

**So there is no static rule, and this document should stop proposing one.** Three have now been
written down here and all three passed a curve that hunts. What is left is the dynamic check,
and the host suite now runs it on every build: all four presets through the real `FanCurve`
against the plant at every ambient from 14 to 34 °C, failing if any settles outside two duty
points or moves more than one point per tick.

Getting that test to mean anything took two attempts, which is worth recording because the
first one looked perfectly reasonable. One pole at 120 s, no sensor noise, started at duty 40 —
and it **passed the Cold curve `CurveSim` had already caught**. A hunt on a knife-edge is
decided by exactly the details a tidy model leaves out, so the test now mirrors this file's own
pole sweep in full: the 230 s fast pole, all four slow-pole values, the 70/30 amplitude split
and 0.03 °C of seeded noise. It reproduces both known hunts, which is the only reason it can be
trusted to catch a third.

The `≥ 4 °C wide` assertion stays in the suite as a cheap sanity check on curve *shape* — it
catches a knee typed in wrong — but it is documented there as necessary, not sufficient.

The tempting mechanism is that hysteresis applies to the input temperature, so a segment's
deadband spans `slope × hysteresisC` duty points and above one point no single duty can rest
inside it. It is a good story and the measurements do not support it — see the table above,
where 2.67 duty/°C hunts and 4.50 does not. Treat `width ≥ 4 °C` as a shape check and
`CurveSim` as the
authority, which is what caught the 3 °C attempt.

The curve's own operating points sidestep the question by being flat or nearly so, which is
the real reason the shelf exists.

**One note on modelling the plant.** The thermal response is strongly non-linear — 0.06 °C
per duty point at 80 %, 0.60 at 35 %. A simulation that uses the whole-range average of
0.174 is roughly three times too gentle exactly where the quiet end of any curve operates,
and will make unstable designs look safe. `CurveSim.java` interpolates the measured table
for this reason.

## Safety

**The ceiling is 55 °C**, and it is chosen on the only non-arbitrary ground available:
**the stock ladder itself commands maximum fan at ≥ 55 °C.** That is the manufacturer's own
software declaring the machine out of its comfort zone, on the same sensor. Below it we are
inside what Philips validated; above it, nothing is.

The datasheets cannot supply a better number, and it is worth knowing *why* rather than
just that they don't — see `tools/datasheet_limits.md`. The chain is

```
T_ARRAY = T_LED + D + (Q_ARRAY × 9.0 °C/W)  ≤  T_max(landed duty cycle)
```

and **D, the LED-board-to-DMD offset, is unmeasured and unbounded in sign.** Nor is
`T_max` a single number: the DMD's long-term rating derates from 70 °C down to 40 °C with
micromirror landed duty cycle, the derating is symmetric (black derates as hard as white),
and gamma-corrected video sits firmly on the derated side. There is no DMD-side sensor to
close the loop with — the DLPC's temperature register reads a hard zero because the
external comparator TI's design requires was never fitted to this board.

**What the curve is measured to do:** hold ≤ 55 °C in every mode up to about 30 °C ambient,
and keep the operating duty at or under 50 everywhere below 33 °C.

**What bounds a failure:** the fail-safe is duty **83**, not a low value, so every error
path in the app cools harder rather than less. The 75 °C shutdown and the kernel fan-stall
watchdog sit outside anything userspace can change.

### The safety case, from four independent directions

Working in `tools/datasheet_limits.md`, `field_evidence.md`, `lifetime_at_temperature.md`.
They were researched separately and they agree, which is the reason to believe them.

**1. Philips shipped this exact trade themselves, on the sibling model.** PicoPix Max
firmware 1.0.32 (2020), their own release notes: *"Many people asked us to slightly
increase the core temp in exchange for lower fan noise, well your wish is granted"* —
measured at **−7 dB**, mechanism stated as raising the target temperature and letting the
fan adapt to hold it. Testers logged the light-engine sensor at **50 °C steady, 53 °C worst
case at 23 °C ambient**; Philips called it *"working as expected"*; it was never rolled
back. Same `adjfan` property namespace as the SCN350. **This curve sits at 51.9 °C at
24 °C ambient** — inside a setpoint the manufacturer chose, shipped and defended.

**2. Every hard limit is far away, and one soft guideline is close.**

| reference | value | source |
|---|---|---|
| Red projector LED, max solder point | 100–105 °C | ams-OSRAM datasheets |
| OSRAM's own lifetime qualification | 85 °C board | OSRAM AN084 |
| Only published engine-thermistor trip point | 70 °C | TI LightCrafter EVM, DLPU006E |
| TI's design target, red LED junction | 60–70 °C | TI patent US7866852B2 |
| **"LED copper PCB ≤ 50 °C to maximise LED lifetime"** | **50 °C** | engine vendor + TI FAE |

The last row is the only one we exceed, by about **2 °C**. It is a lifetime-*optimisation*
guideline from a single vendor, sitting 50 °C below the actual rating — not a limit. It is
also the honest cost, and it is consistent with the red-fade estimate.

**3. No field evidence of thermal failure exists.** Across four years and the complete
53-topic Screeneo U4 forum category: no over-temperature shutdown, no thermal warning, no
dimming, no colour degradation, no fan failure. Eleven separate scoped searches, nothing.
Nor is there evidence the 46 °C trigger was set in response to failures — no owner ever
reported the symptom Philips named, before or after the change.

**4. The machine's real failure mode is not thermal.** Eleven owners lost an LED colour
channel **outright and abruptly**, onset from one hour to a few months. That is infant
mortality in the drive path — and it happened on the *loud* firmware. Nothing this curve
changes touches it.

**Peer context:** comparable 400–800 lm LED DLP portables measure 32.5–46.1 °C on their
*external* exhaust. An internal LED board 5–15 °C above that lands at 51–56 °C. (That delta
is an inference — nobody publishes internal board temperatures for any retail projector.)

**The counterweights, stated rather than buried.** When cooling genuinely fails on these
machines the damage is optical, permanent, and the thermal shutdown does *not* prevent it
— a PicoPix Max left with its fan off for ~5 hours by a CEC bug was ruined. So the ramp and
the watchdog matter more than the floor does. And a hotter steady state does enlarge the
power-cycle ΔT, which is the real driver of solder and wirebond fatigue.

### Two known-unresolved things

1. **The measured asymptotes are lower bounds, by 1-3 °C.**

   A thermal component slower than the fitted 172 s pole exists, and carries real DC gain.
   The evidence is the drift still present at the end of each twelve-minute hold, compared
   against what a 172 s pole permits (98.6 % settled by 730 s):

   | hold | drift at end | a 172 s pole permits | ratio |
   |---|---:|---:|---:|
   | Presentation 40 | **+7.89 °C/h** | 1.33 | 5.9x |
   | Presentation 35 | +3.43 | 0.86 | 4.0x |
   | Presentation 30 | +7.67 | 1.15 | 6.7x |
   | Normal 40 | -13.37 | 4.09 | 3.3x |

   Every hold is still moving **3-7x faster than a 172 s pole allows**. Its time constant
   cannot be pinned down by holds of this length — an estimate always lands near half the
   hold duration, which is the signature of something longer. One confound cannot be
   separated from this data: the room was also warming, partly because the projector was
   heating it.

   **Observed in service:** deployed and left alone, Presentation settles at duty **40 at
   52.2 °C**, against 38.4 at 51.9 predicted from the twelve-minute holds. Two duty points
   and 0.3 °C — the bottom of the estimated range.

   **This costs noise, not safety.** The controller closes the loop on temperature, so an
   underestimate of the plant is spent on duty:

   | if every rise is understated by | duty settles | LED | vs 55 °C |
   |---|---:|---:|---|
   | 0 °C | 38 | 51.9 | OK |
   | +3 °C | 41 | 53.3 | OK |
   | +5 °C | 44 | 54.4 | OK |

   Five degrees of error buys five duty points of fan noise and still does not reach the
   ceiling. That is structural: any feedback controller closed on temperature converts a
   plant error into an actuator offset rather than a temperature offset.

2. **Normal, Eco and Super Eco rows below duty 40 are still extrapolated** from run 1, and
   run 1's extrapolations are now known to run optimistic. This does not threaten the
   ceiling — Eco and Super Eco have 10–20 °C of margin, so only Normal's number can move
   anything, and it would have to be wrong by ~6 °C (half again the worst error yet seen)
   to matter.

---

## Deployed and verified — 2026-09-05

Live on the projector, `com.daleygames.fanlab.system`, `mode=CURVE`, `autostart=true`.

**Predicted duty 38.4 at 51.9 °C. Measured duty 38 at 51.4 °C.** Within half a degree and
half a duty point of a plant model built the same afternoon.

Settling from the fail-safe:

| | duty | LED |
|---|---:|---:|
| t+0 | 47 | 46.8 °C — slewing down from 83 |
| t+120 | 33 | 49.2 °C — reached the floor |
| t+200 | 35 | 50.5 °C — machine warming, curve answering |
| t+360 | 37 | 51.3 °C |
| **t+580 →** | **38** | **51.4 °C, and it stops** |

Acceptance test, six minutes at equilibrium:

```
duty         : 38..38 (mean 38.0)
LED temp     : 51.42..51.91 C
duty changes : 0
VERDICT: the fan did not move once.
```

**Reboot test passed.** Kill switch, mode and autostart all survived, the service came
back by itself, and it re-converged to **duty 38** — the same value, from a cold start.

**Safety coupling: 7 of 7.** Including the case that matters — the kill switch forced to 0
behind the app's back while it was idle, and the app re-armed it unprompted.

Against the starting point: Presentation ran at **59, oscillating to 70**. It now sits
motionless at **38**.

### Two things deliberately left undone

* **The sub-30 stall probe.** Duty 30 is proven — held twelve minutes under the hottest
  load the machine can produce. Probing 25/22/20/15 would only measure margin we chose not
  to spend, at the cost of a real power-off risk.
* **The long hold that pins the slow pole.** It is now running for free: this deployment
  *is* the multi-hour hold. Re-check the equilibrium after a few hours of ordinary use — if
  it has crept above 38, the plant reads low by the amount the creep implies.
