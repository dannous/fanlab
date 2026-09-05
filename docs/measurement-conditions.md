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

Three things keep this in proportion:

1. **The stock controller is equally blind.** `adjust_fan_speed_v1` reads the same
   `persist.sys.led.temperature` and nothing else. This is not a regression introduced by
   the new curve — it is how the machine has always worked.
2. **The SoC protects itself.** Those trip points drive passive throttling of CPU and GPU
   frequency, independent of any fan control. 68 °C is between trip 0 and trip 1, which is
   ordinary operating territory for a die, not a fault.
3. **But a quieter fan does mean a warmer SoC.** Stock would be running 59–70 here instead
   of 40, and that extra airflow cools the whole chassis. The trade bought with fan noise
   is paid partly by the SoC, and no sensor in the control loop is watching that.

**The measurement that would settle it** — untaken — is to hold the fan at, say, 40 and
then 70 with UHD on and the same content, and see how far the SoC zones move. That gives
the fan's actual authority over SoC temperature. If it is small, this is a non-issue; if it
is large, a future curve could take `max(LED, ddr − offset)` as its input instead, which
`FanService` already reads and logs.
