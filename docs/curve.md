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
tempC   =  42   48   55   60   65   70
duty    =  30   30   45   60   74   83        <- identical for all three profiles

hysteresisC     = 0.8
slewUpPerSec    = 0.25      (1 duty point per 4 s)
slewDownPerSec  = 0.12      (1 duty point per ~8 s)
minDuty         = 30
maxDuty         = 83
idleDuty        = 10        (light engine off)
```

Encoded, for `tools/deploy.sh` and `ConfigReceiver`:

```
v1,42,48,55,60,65,70,30,30,45,60,74,83,30,30,45,60,74,83,30,30,45,60,74,83,0.8,0.25,0.12,10,30,83
```

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

* **Flat at duty 30 up to 48 °C.** 30 is "inaudible even up close" and it is the floor for
  every mode that can hold it. Nothing moves in this region at all.
* **48 → 55 °C ramps to duty 45**, a slope of **2.14 duty points per °C**. That number is
  measured, not chosen: above about **2.4** the controller hunts (below).
* **55 → 70 °C** steepens to 83. This is the backstop. The plant cannot reach it at any
  plausible ambient — at 33 °C ambient with the fan at its cap, Presentation is 56.7 °C.
* **Hysteresis 0.8 rather than 0.5.** At 0.5 the duty dithered by one point at 24 °C
  ambient (4 changes in 50 minutes). 0.8 removes it entirely.

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

| room | Presentation | Normal | Eco | Super Eco |
|---|---|---|---|---|
| 21 °C | 36 · 50.6 °C | **30 · inaudible** | **30 · inaudible** | **30 · inaudible** |
| **24 °C** | **38 · 51.9 °C** | **30 · inaudible** | **30 · inaudible** | **30 · inaudible** |
| 27 °C | 41 · 53.3 °C | **30 · inaudible** | **30 · inaudible** | **30 · inaudible** |
| 30 °C | 45 · 54.9 °C | 33 · barely audible | **30 · inaudible** | **30 · inaudible** |
| 33 °C | 50 · 56.7 °C | 38 · quiet | 31 · barely audible | **30 · inaudible** |

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
ambient 21C   0 changes in the last 50 min -> STEADY
ambient 24C   0 changes                    -> STEADY
ambient 27C   0 changes                    -> STEADY
ambient 30C   0 changes                    -> STEADY
```

Not "moves smoothly" — does not move.

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

`equilibria.py` reports it. Every segment of this curve is **6.2× the hysteresis band**,
which is ample; its steepest ramp is 3.0 duty/°C.

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
