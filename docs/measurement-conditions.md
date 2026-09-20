# The configuration the curve was measured in

Everything in `CURVE.md` was measured with the projector in the state below. If the
machine is later run in a materially different configuration, the *curve* still works —
it is a feedback controller and does not depend on these numbers — but the predicted
operating points may shift.

## Measured under

| | |
|---|---|
| Firmware | 1.7.0 |
| Ambient | **24 °C**, owner-reported, at the projector |
| build.prop patch | `minus15pct_rounded_43-43-48-59` (floors 43/43/48/59), not stock |
| **UHD / 4K processing** | **OFF** |
| Content on screen | a static white field for most holds |
| Network | none — no Wi-Fi, no Ethernet, loopback only |
| Brightness modes swept | all four, via `rgblevel` |
| Autofocus | untouched |
| Sound effects | `db_id_sound_effect_bass = 40`, treble 50, EQ bands all 50 |

Display reports **1920x1080 @ 60 Hz** internally regardless of the UHD setting — the panel
is 1080p and UHD is an input/processing option, not an output resolution.

## "Ambient" in this repository means intake air, not the room

Every table in `curve.md` is indexed by an ambient that is **the air the projector draws**,
and on this unit that runs several degrees above the room the owner is sitting in. This was
not known when those tables were written and they read as though the two were the same.

Measured 2026-09-08, from the five factory-drive holds of the plant run (fan pinned at 45,
each mode's settled asymptote minus that mode's measured factory rise):

| hold | LED asymptote | table rise at duty 45 | implied ambient |
|---|---:|---:|---:|
| Super Eco | 32.94 °C | 7.60 | **25.34 °C** |
| Eco | 38.58 °C | 12.80 | **25.78 °C** |
| Normal | 43.32 °C | 17.10 | **26.22 °C** |
| Presentation | 51.61 °C | 24.90 | **26.71 °C** |
| Presentation, 30 min later | 51.65 °C | 24.90 | **26.75 °C** |

**The owner reported the room at 22 °C throughout.** So the offset is roughly **+3.3 °C in
Super Eco rising to +4.7 °C in Presentation** — it is not a constant, it grows with the
machine's own power, which is what recirculating some of your own exhaust looks like.

Two things stop this being written off as a bad plant table. The five readings agree to
1.4 °C across four modes whose rises span 7.6 to 24.9 °C; for the table to be the culprit it
would have to be about 47 % low on Super Eco and 5 % low on Presentation simultaneously. And
the same calculation on the previous night's eight 40-minute holds gives 25.1–26.9 °C — the
same intake, in a room the owner describes as having been warmer. The last two rows above are
the run's closing bracket, thirty minutes and one full Presentation hold apart, agreeing to
**0.04 °C**: the room did not move underneath this run.

**What to do with it.** Nothing in the control loop cares — the curve closes on the LED
thermistor and never reads ambient, and a scaling is a ratio in which ambient cancels. It
matters for *reading* the tables: an equilibrium listed at 27 °C is roughly what you get in a
22 °C room. Do not convert with the 4.7 figure as though it were a constant; it is one day's
observation in one room with the projector in one position, and it should be re-measured
before anything is designed on it.

## What changed afterwards, and what it did

**UHD was switched ON after the measurements.** Observed immediately afterwards, at the
same fan duty and the same brightness mode:

| sensor | UHD off (measured) | UHD on | change |
|---|---:|---:|---:|
| `pll_thermal` | ~52 °C | 64 °C | **+12** |
| `ddr_thermal` | ~56 °C | 67–68 °C | **+11** |
| `sar_thermal` | ~50 °C | 59–60 °C | **+9** |
| **LED thermistor** | 51.9–52.2 °C | 52.6 °C | **+0.5** |
| fan duty | 38–40 | 40 | none |

The SoC got about **11 °C hotter and the fan did not react**, because the fan is driven by
the LED thermistor and UHD loads the video pipeline, not the light engine. LED current is
unchanged, so the sensor that commands the fan barely moves.

Caveat on attribution: content was also playing at the time, where the measurements used a
static white field. Both raise SoC load, and this observation cannot separate them.

## The gap this exposes

**Nothing that controls the fan can see SoC temperature.** With UHD on, all three SoC zones
sit at or above their **first passive trip point (60 °C)**:

```
pll_thermal 64 C   ddr_thermal 68 C   sar_thermal 60 C
trip 0: 60 C passive     trip 1: 75 C passive     trip 2: 85 C hot
```

Only one of the three zones controls anything, and it is not the hottest one:

```
pll_thermal   3 cooling devices bound, all to trip 1 = 75 C   <- the only one that acts
ddr_thermal   0 bindings                                       <- monitor only
sar_thermal   0 bindings                                       <- monitor only
```

The 60 °C trip labelled "passive" on every zone has **no cooling device bound to it**, and
neither does `ddr_thermal` at any temperature. A trip point only acts if something is bound
to it. Throttling therefore begins when `pll_thermal` reaches **75 °C**; with UHD on it sits
at **64 °C**, all four cooling devices are at state 0, and the CPU is at its full
1,908,000 kHz. Nothing is throttled.

Three things keep the rest in proportion:

1. **The stock controller is equally blind.** `adjust_fan_speed_v1` reads the same
   `persist.sys.led.temperature` and nothing else. Not a regression.
2. **Throttling, if it happened, costs frames not hardware** — reduced CPU/GPU frequency,
   with a separate "hot" trip at 85 °C above it.
3. **But a quieter fan does mean a warmer SoC.** Stock would be running 59-70 here instead
   of 40, so the 11 °C of margin to the throttle point is smaller than it would otherwise
   be, and nothing in the control loop is watching it.

## The measurement that settled it

Taken. Six holds with UHD **on**, Presentation, duty 40 → 50 → 62 → 83 → 30 → 40, eight
minutes each. Duty 40 appears first and last so that a change in SoC load over the run
would show up as the two disagreeing; they closed to within 0.5 °C on every channel, so
the load held and the run is usable. Every hold is fitted to an exponential rather than
averaged — an unsettled hold has an end value that is simply wrong, and the trace alone
does not say which it is.

| duty | LED | **pll** | ddr | sar |
|---:|---:|---:|---:|---:|
| 30 | 57.7 | **69.0** | 72.6 | 64.0 |
| 40 | 52.4 | **64.2** | 67.3 | 59.2 |
| 50 | 49.2 | **61.6** | 64.8 | 56.5 |
| 62 | 46.3 | **57.8** | 61.0 | 52.9 |
| 83 | 43.7 | **54.4** | 57.5 | 49.7 |

**The authority is large, not small.** Over duty 30 → 83 the die moves **14.6 °C** against
the LED thermistor's 14.0 — the fan has at least as much grip on the processor as on the
sensor that commands it. The die is not thermally isolated from the airflow; it is simply
not measured. From the operating point at duty 40 the fan can take 6.4 °C off it by duty
62 and 9.8 °C at full speed.

This corrects an earlier estimate of 7.5 °C, which came from transferring the LED plant
across on the strength of a ratio observed in drifting holds taken with UHD off. The ratio
argument turned out to be sound — the two sensors do move together, within a few
hundredths of a degree per duty point — but the magnitudes it was applied to were too
pessimistic at the top of the range.

### What was done about it

Not the `max(LED, ddr − offset)` input suggested above: an input can *lower* the duty as
well as raise it, which would let a monitoring sensor argue down the one the safety case
rests on. Instead an additive guard, described in the README, that can only ever raise the
fan and contributes nothing below 70 °C on the die — 5.8 °C above where the die actually
sits.
