# Working on FanLab

Notes for anyone changing the code. None of this is needed to use the app.

## Building and testing

```bash
pwsh -NoProfile -File app/build.ps1 -Variant system    # the real build
pwsh -NoProfile -File app/build.ps1 -Variant plain     # observe-only build
```

Both run the host test suite first and refuse to produce an APK if it fails. At the time of
writing that is **4586 assertions, 0 failures**. The suite is plain Java with no framework:
`app/test/FanLabTest.java`, run on the desktop JVM against the same classes the app ships.

The build writes to `app/out/`. **`release/` is a checked-in copy and does not update
itself** — refresh it deliberately when you cut a release. Installing from a stale
`release/` while developing is an easy hour to lose.

## The rule this project runs on

**Measure, don't simulate.** It is written here because it keeps being right, and the
counter-examples are all ours:

- A stability rule based on duty-per-degree passed a curve that hunts.
- A "4 °C wide is enough" rule did the same.
- The fitted rise-vs-drive line was wrong by −2 to +6 %, in *both* directions, on all four
  brightness modes.
- A documented, reproduced, carefully explained hunt at 16 °C ambient turned out to be an
  artefact of an inferred plant. It vanished when the plant was actually measured.
- A redrawn curve that was quieter, cooler-margined and scored 84 of 84 steady in
  `CurveSim` moved the fan nine times in twelve minutes on the hardware, against zero for
  the row it was replacing.

`tools/CurveSim.java` is the authority on hunting, and even it is not sufficient — see
below. Static reasoning about slopes has never once predicted this machine's behaviour.

**The hunting bound is two duty points.** Three is a regression. Do not relax the test to
accommodate a change; change the curve.

**Quiet *steadily* is the specification.** Movement is the audible problem, not level. A fan
sitting at 52 % is easier to live with than one alternating between 46 and 48.

## Things that will cost you a day

**`CurveSim` cannot see hunting caused by sensor noise.** It seeds noise at the ADC
quantisation scale, 0.03 °C. The real light engine wanders **0.6–0.9 °C at a fixed duty**,
about thirty times more. Multiply that wander by the slope of the curve segment the machine
rests on and you get the duty travel: at 3.0 duty/°C the wander stays inside the 0.8 °C
deadband and the fan never moves; at 4.4 it does not. Treat a `CurveSim` "steady" as
necessary and not sufficient, and put anything whose operating point sits on a steeper
segment than 3.0 duty/°C in front of `tools/watch.sh` or the VERIFY steady phase.

**"Ambient" in this repository means intake air, not room temperature.** Measured
2026-09-08: the projector drew 25.3–26.7 °C air while the room thermometer read 23, and the
offset grows with the machine's own power. Every table indexed by ambient is indexed by
intake. Nothing in the control loop cares, because the curve closes on the thermistor and
never reads ambient, but a reader converting to room temperature with the wrong offset will
design for the wrong operating point.

**The projector's own log is worth reading before you measure anything.** The app writes
`/sdcard/Android/data/com.daleygames.fanlab.system/files/fanlab/fanlab.csv` continuously,
with `degC`, `fan_ctrl`, `rgblevel` and `led_drive` columns. A run that produced no usable
scalings still identified the plant's two poles, because the data was all still there.

**`adb push` and `adb pull` under Git Bash.** MSYS rewrites the *remote* path, so
`/sdcard/...` silently becomes `C:/Program Files/Git/sdcard/...` and adb reports success
while doing nothing. Set `MSYS_NO_PATHCONV=1`.

**Do not broadcast mode 0 before measuring, whatever `docs/measuring.md` used to say.**
`FanService` in mode OFF re-arms Philips' ladder every 30 ticks, so it would fight a pinned
fan for the whole run. Force-stop the app instead.

**`rgbcurrent`'s show text is comma separated**, with one stray space:
`duty_r=75,duty_g=70,duty_b=238, duty_b2=238`. A parser that splits on spaces reads every
field as missing.

**The `rgbcurrent` field names lie.** The kernel prints channels 0–3 under `duty_r`,
`duty_g`, `duty_b`, `duty_b2`, but the channel map is green / red / b2 / blue. **`duty_g` is
the red channel.** Any field above 100 (238, 241 are the common ones) is a failed SPI read,
not a level.

**`duty_g` fails its SPI read about half the time.** Ask "did it confirm at any point in the
last forty seconds", not "is it in the right state right now" — the second question fails
about half the time on a perfectly applied drive.

**A brightness-mode change races the kernel.** It stores the new `rgblevel` before pushing
four SPI writes, so anything reacting instantly gets the common channels overwritten while
red survives. That is a colour cast, not a flicker.

**Do not use MANUAL mode to pin a fan speed for a measurement.** It re-arms Philips' ladder
and the two writers fight audibly. Stay in CURVE and hand it a flat curve.

**Anything that must happen unattended goes in the script's exit trap.** A measurement run
once finished at 02:54 and the projector sat on until 08:34, because the power-off existed
only in a message someone wrote afterwards.

## Naming

**Don't call anything of ours "stock".** "Factory" or "Philips'" means Philips' own
behaviour. The two preset families are "Curve" and "Bright Curve".

## What is still open

- **The plant's dynamics, as distinct from its steady state.** `CurveSim` and
  `testCurvePresetsDoNotHunt` model τ_fast at 230 s with a slow pole swept 900–3000 s and
  30 % of the amplitude behind it. The plant identified from real holds is τ_fast ≈ 50 s and
  τ_slow ≈ 400 s, with roughly half the amplitude on each. Both are faster than the model,
  and the sweep does not bracket the fast pole. Every stability verdict rests on those
  modelled dynamics, so this is the obvious next measurement.
- **The intake offset** is one day's observation, in one room, with the projector in one
  position. Do not treat it as a constant.
