# The configuration the curve was measured in

Everything in `CURVE.md` was measured with the projector in the state below. If the
machine is later run in a materially different configuration, the *curve* still works —
it is a feedback controller and does not depend on these numbers — but the predicted
operating points may shift.

## Measured under

| | |
|---|---|
| Firmware | 1.7.1 |
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
