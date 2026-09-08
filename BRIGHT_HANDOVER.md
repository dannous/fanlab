# The Bright build — what was done, and what is still open

Working notes, not project documentation. The load-bearing findings have been moved into
[docs/curve.md](docs/curve.md), [docs/safety.md](docs/safety.md) and
[docs/measurement-conditions.md](docs/measurement-conditions.md); this file keeps the
history and the traps, which do not belong there.

**Status: the plant measurement this file was written to request has been taken.** Written
2026-09-08 after the run.

---

## 0. Where things are

| | |
|---|---|
| Branch | **`feat/bright`** |
| Worktree | `C:\Users\Gamer\Documents\fanlab-wt-int` |
| Tests | **4537 assertions, 0 failures** — `pwsh -NoProfile -File app/build.ps1 -Variant system` |
| adb | not on PATH: `C:\Users\Gamer\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| Package | `com.daleygames.fanlab.system` |
| Built APK | `app/out/fanlab-system.apk` — **not** `release/`, which is stale |

**Stale worktrees to delete once you are happy:** `fanlab-wt-{caic,curve,labb,led,p,strip,w}`.
All are merged into `feat/bright`. `git worktree remove <path>` each, then delete the branches.

---

## 1. What the measurement said

All four LED-drive scalings are now measured. Three of them were the fitted rise-vs-drive
line until 2026-09-08.

| mode | drive | measured | the fit said | error |
|---|---|---:|---:|---:|
| Presentation | 76 → 90 | **×1.2404** | ×1.1735 | +5.7 % |
| Normal | 55 → 75 | **×1.4032** | ×1.3351 | +5.1 % |
| Eco | 40 → 55 | **×1.3866** | ×1.3357 | +3.8 % |
| Super Eco | 20 → 35 | **×1.5763** | ×1.6078 | −2.0 % |

**The fit was wrong by −2 to +6 %, in both directions**, so it was not a bias anyone could
have corrected for. Three of the four read low — the direction that puts the light engine
hotter than the tables promised.

Two things fell out of the run that were not being looked for:

- **"Ambient" in this repository is intake air, not the room.** Five factory holds put it at
  25.3–26.7 °C while the owner reported a 22 °C room, and the offset grows with the machine's
  own power. Written up in `docs/measurement-conditions.md`. Nothing in the control loop cares;
  it matters for reading the tables.
- **The plant has two poles, ≈50 s and ≈400 s**, identified from the failed run's own eight
  40-minute holds (shared-pole fit, rms 0.073 °C). A single exponential — which is what
  `fold_plant.py` uses — cannot fit that and its asymptote was still moving 3 °C at forty
  minutes. With both poles fixed, a ten-minute hold reproduces a forty-minute answer to
  0.02 °C mean / 0.25 °C worst, which is why the new holds are ten minutes.

## 2. What was done with it

**The four Bright HIGH rows were re-examined against the measured plant and left unchanged.**
That is the result, and it cost three hardware runs to establish.

The redraw that was tried was taking the +10 off knee 2, giving `+0,0,0,12,8,0`. It looked
better on every static measure: 3.7 duty points quieter, columns identical to 55 °C instead of
51, identical trip margin, and 84 of 84 steady in `CurveSim`. On the hardware it hunts.

| Presentation row | resting duty | changes in 12 min |
|---|---|---:|
| `+10,12,8` — shipped, kept | **51, flat** | **0** |
| `+7,6,8` | 50 | 1, during settling |
| `+0,12,8` — rejected | 46–48 | **9** |

Taking the bump off steepens the segment the machine rests on from 3.0 to 4.4 duty/°C. The
light engine wanders 0.6–0.9 °C at a fixed duty, and wander times slope is duty travel: at 3.0
it stays inside the 0.8 °C deadband, at 4.4 it does not. `CurveSim` seeds 0.03 °C of noise, so
it cannot see this — **treat its "steady" as necessary, not sufficient.**

The shipped rows now have the closed-loop verification at drive 90 they never had. The tools
changed too, so this cannot quietly regress: `solve_curve.drive_scale` and
`CurveSim.driveScale` return the **measurement** for the four shipped levels and fall back to
the fit only for a level nobody has held.

## 3. What is still open

- **The dynamics, not the scalings.** `CurveSim` and `testCurvePresetsDoNotHunt` model
  τ_fast = 230 s with a slow pole swept 900–3000 s and 30 % of the amplitude behind it. The
  identified plant is τ_fast ≈ 50 s, τ_slow ≈ 400 s, roughly half the amplitude each — both
  faster than the model, and the sweep does not bracket the fast pole. **Every stability
  verdict rests on those modelled dynamics.** This is the obvious next measurement, and the
  hunting bound must not be relaxed to accommodate whatever it finds.
- **The intake offset** is one day's observation in one room with the projector in one
  position. Do not treat +4.7 °C as a constant.
- **`room=28C` is stored in the app's prefs** and is neither the room (22) nor the intake
  (~26.7). It is metadata only — logged and used for sweep planning, never in the control
  loop — but it is wrong and the owner may want to set it.

## 4. Do not re-open

**The LED drive levels 35 / 55 / 75 / 90 are the owner's decision** and he is testing them in
real use. If it is too loud the answer is to scale brightness back, and that is his call.

Capped at **97, never 100**. The driver converts mA to a 7-bit DAC code and clamps it as
`if (code > 0x7F) code = 0x3F` — an overflow does not saturate, it drops that channel to about
40 %. Asking for 100 makes the picture *dimmer*.

**Do not use the word "stock" for anything of ours.** "Factory" means Philips' own behaviour;
the families are "Curve" and "Bright Curve".

**CAIC, LABB and the Looks are closed with evidence.** CAIC cannot work — every LED-current
command is defined by TI as going to a DLPA200x PMIC and this board has none; current comes
from the SoC over SPI to two MAX20096 the DLPC cannot reach. Raising its gain to 4.0 changed
nothing measurable (52.33 vs 52.33 °C, spread 0.04). LABB works and makes the picture worse
("really washed out" at strength 128); its enable is `w 80 2 11 80` and the control field is
**b(1:0)**, not b(3:2). All 19 Looks were swept: Look 0 is 40/40/20 and every other cuts red
to 25–33 %, so Look 0 is optimal because red is the weak primary with no headroom to give.
Keep the `PicoReg` encoders and their tests — they are how the negative was established.

---

## 5. Traps that cost time

**The projector's own logs are worth reading before you measure anything.** The app writes
`/sdcard/Android/data/com.daleygames.fanlab.system/files/fanlab/fanlab.csv` continuously, with
`degC`, `fan_ctrl`, `rgblevel` and `led_drive` columns. The failed overnight run was still in
there in full, and it is what the two-pole identification was done on — the run that produced
no usable scalings still answered "how long must a hold be".

**`adb push`/`pull` and Git Bash.** MSYS rewrites the *remote* path: it silently became
`C:/Program Files/Git/sdcard/...` and reported success. Use `MSYS_NO_PATHCONV=1`.

**`docs/measuring.md` tells you to broadcast mode 0 before measuring. Do not.** `FanService`
in mode OFF re-arms the stock ladder every 30 ticks (`syncStockLadder`, `drivesUnattended`),
so it would fight a pinned fan for the whole run. Force-stop the app instead.

**`rgbcurrent`'s show text is COMMA separated** — `duty_r=75,duty_g=70,duty_b=238, duty_b2=238`
— with one stray space. A parser that splits on spaces reads every field as missing.

**The `rgbcurrent` field names lie.** The kernel prints channels 0–3 under `duty_r, duty_g,
duty_b, duty_b2` while the map is green / red / b2 / blue. **`duty_g` is red.** Any field above
100 (238, 241) is a failed SPI read, not a level.

**`duty_g` fails its SPI read about half the time.** Ask "did it confirm at any point in the
last forty seconds", not "is it in state right now" — the latter fails about half the time on a
perfectly applied drive. That distinction is most of whether a run starts.

**A brightness-mode change races the kernel.** It stores the new `rgblevel` *before* pushing
four SPI writes, so anything reacting instantly gets the common channels overwritten while red
survives — a colour cast, not a flicker.

**Anything that must happen unattended goes in the script's exit trap.** The 2026-09-07 run
finished at 02:54 and the projector sat on until 08:34 because the power-off existed only in a
message. `tools/plantdrive.sh` takes a poweroff flag and does it on the trap.

**`release/*.apk` is stale.** `app/build.ps1` writes `app/out/fanlab-system.apk`. Installing
from `release/` silently deploys September 7.

**Do not use MANUAL mode to force a fan speed** — it re-arms the factory ladder and the two
writers fight audibly. Stay in CURVE and hand it a flat curve instead.

**Clean up backgrounded commands.** A malformed one with a heredoc reading stdin blocked for
eight hours.

## 6. Standing rules

- **Measure, do not simulate.** This round added two more to the tally: the fitted
  rise-vs-drive line was out by up to 6 % in both directions, and Bright Quiet's 16 °C hunt —
  documented, reproduced, explained at length — turned out to be an artefact of the inferred
  plant and vanished the moment the real one was measured.
- **The hunting bound is two duty points.** Three is a regression. Do not weaken the test.
- The owner's spec is **quiet *steadily*** — movement is the audible problem, not level. His
  revised ceiling is **Presentation under 55 %**; he calls 39 % "nice volume" and 53 % "a bit
  loud".
- He asked that this project not read as AI-generated. Match the existing prose; commits end
  with the `Co-Authored-By` trailer the recent history uses.
