# Handover — the Bright build: what is done, what is left, and what will bite you

Written 2026-09-08 after an overnight session. Working notes, not project documentation.
Read [HANDOVER.md](HANDOVER.md) and [NEXT_SESSION.md](NEXT_SESSION.md) too; they carry the
fan-curve background this assumes.

**Your job:** run the plant measurement that failed overnight, redraw the four Bright Curve
rows against it, verify, commit, install, and power the projector off. Everything else is
finished.

---

## 0. Where things are

| | |
|---|---|
| Branch | **`feat/bright`**, currently `9351cd1`, **19 commits ahead of `master`** |
| Worktree to work in | `C:\Users\Gamer\Documents\fanlab-wt-int` |
| Tests | **4537 assertions, 0 failures** — `pwsh -NoProfile -File app/build.ps1 -Variant system` |
| Projector | **powered off**, handed back to Philips (mode OFF, kill switch `1`, fan 83) |
| adb | not on PATH: `C:\Users\Gamer\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| Package | `com.daleygames.fanlab.system` |

**Stale worktrees to delete once you are happy:** `fanlab-wt-{caic,curve,labb,led,p,strip,w}`.
All are merged into `feat/bright`. `git worktree remove <path>` each, then delete the branches.

---

## 1. What is done and needs no further work

**The LED drive override.** Raises the light engine above the factory per-mode table. Levels
are **35 / 55 / 75 / 90** for Super Eco / Eco / Normal / Presentation against a factory
20 / 40 / 55 / 76. **The owner has fixed these — do not change them.** He will decide about
brightness after living with it; if it is too loud the answer is to scale brightness back,
and that is his call, not a tuning knob for you.

Capped at **97, never 100**. The driver converts mA to a 7-bit DAC code and clamps it as
`if (code > 0x7F) code = 0x3F` — an overflow does not saturate, it drops that channel to
about 40 %. Asking for 100 makes the picture *dimmer*.

Applies only while the app is genuinely the fan controller: mode CURVE or LINEAR, no
measurement session, light engine on, fail-safe clear. Trip at **60 °C**, latched until the
brightness mode or the config changes.

**Eight presets in two families**, `Curve` (Quiet / Balanced / Cool / Cold) and `Bright Curve`
(Bright Quiet / Balanced / Cool / Cold). Turning the drive on or off moves the stored curve to
the same rung in the other family. A cross-family preset is refused, verbatim:

```
preset(REFUSED: Quiet is a Curve preset and the LED drive is on; use Bright Quiet or turn the drive off)
```

A hand-edited Custom curve belongs to neither family and is deliberately left alone.

**Do not use the word "stock" for anything of ours.** The owner asked for this explicitly.
"Factory" means Philips' own behaviour; the families are "Curve" and "Bright Curve".

**CAIC, LABB and the Looks are stripped to read-only diagnostics.** All three were tested on
the hardware and none of them helps — section 4 has the evidence. Keep the `PicoReg` encoders
and their tests: they are how the negative was established and how a future firmware could be
re-checked.

---

## 2. What is left — the measurement, and why the last one failed

Everything below the Presentation row in the plant table is still **inferred**. One
measurement fixes that.

### What went wrong overnight, so you do not repeat it

Six holds, 15 minutes each, fan pinned at 45, brackets at both ends to catch ambient drift.
Three holds were valid. Two failures killed the run:

1. **`echo 3 > /sys/class/dlpc343x/rgblevel` did not stick for the end bracket.** The drive
   read back `19` — Super Eco — for 13 of 16 samples. The reference measured the wrong
   machine, so there was no end-of-run ambient and the drift could not be removed.
2. **Normal was still shedding Presentation's heat for its whole hold** — 56.3 to 46.0 °C,
   never settled — because the holds ran hot to cool. The override also took three minutes
   to land on top of that (`54` for five samples, then `74`).

The room fell **28 °C to 22 °C** overnight. With no end bracket that 6 °C landed inside every
rise, and all three valid scalings read 5–12 % low. Do not try to rescue those numbers by
assuming an ambient — the assumptions contradict each other across holds.

### The corrected procedure

`tools/` has nothing for this. Write a fresh script with **all** of these:

- **Order the holds cool to hot**: Super Eco, Eco, Normal, Presentation. Each then settles
  upward instead of decaying out of the previous one's heat.
- **Verify before every hold**, and re-assert until it sticks: read `rgblevel` back, and read
  the drive out of `rgbcurrent` and check it against the expected level. Abort the hold and
  retry rather than logging a hold that was in the wrong mode.
- **20-minute holds**, not 15. The fast pole is about 115 s but a mode change moves the slow
  chassis pole too.
- **Bracket both ends** with the same mode at factory drive, and verify those the same way.
  A stable 22 °C room makes this far easier than the falling 28 of that night.
- **Put the power-off in the script**, on the exit trap. See section 5.

### Then

Redraw the four Bright Curve HIGH rows against the measured columns, target **21–24 °C**
(where the owner says the room settles), verify the backstop to 30 °C, and confirm with
`tools/CurveSim.java` at the measured scale. Then commit, install, power off.

**The bar the owner set:** Presentation at or under 50 % fan, Normal at or under 40 %, Eco and
Super Eco on the silent 30 % floor. He called 39 % "nice volume" and 53 % "a bit loud".

---

## 3. Measured facts — trust these, they are hardware

| fact | value | how |
|---|---|---|
| Per-channel LED maximum | **about 7.1 A** | driver logs `current = 5357 ma, percent = 75` on any `rgbcurrent` read |
| Presentation drive 76 to 90 scaling | **x1.208** | settled hold, ambient from a factory-drive reference |
| Normal drive 55 to 70 | **+3.77 °C** at fan 45 | pinned fan, drift-controlled with a repeat baseline |
| Presentation at 90, fan 45, 28 °C room | **56.3 °C** | overnight hold, drive `89` confirmed every sample |
| CAIC on vs off, pinned fan, 7 min each | **52.33 vs 52.33 °C** | spread 0.04; a true null |
| Room overnight | 28 °C to 22 °C | owner |

**Inferred, and still unmeasured:** the Normal, Eco and Super Eco scalings at their new drive
levels. That is the whole point of the re-run.

---

## 4. The three dead ends, closed with evidence

**CAIC cannot work.** Its engine runs — the debug bars move, `0x5F` produces live per-colour
currents that track content — but every LED-current command is defined by TI as going to a
DLPA200x PMIC, and this board has none. Current comes from the SoC over SPI to two MAX20096
chips the DLPC cannot reach. Raising its gain to 4.0 (accepted, verified) changed nothing. Its
gain had sat at the 1.0 floor because nothing wrote `0x84`; fixing that changed no measurement.

**LABB works and makes the picture worse.** Enable is `w 80 2 11 80` — the control field is
**b(1:0)**, not b(3:2); the datasheet table extracts garbled and reading it wrong wasted an
hour. The owner's verdict at strength 128: "really washed out". That is LABB doing its job.

**Looks work; the brightness is inseparable from a green cast.** All 19 swept. Look 0 (the
only one this projector has ever used — the kernel zeroes its Look table at probe) is
**40/40/20**; every other Look cuts red to 25–33 % and gives the time to green. Rebalancing
Look 15 would need red current up about 2.5x to 175 %, against the 97 ceiling. Look 0 is
optimal because red is the weak primary and has no headroom to give.

---

## 5. Traps that cost time

**`adb push` and Git Bash.** MSYS rewrites the *remote* path: `adb push x /sdcard/Movies/y`
silently became `C:/Program Files/Git/sdcard/...` and reported success while pushing nowhere.
Use `MSYS_NO_PATHCONV=1` **and** a Windows-style source path.

**The `rgbcurrent` field names lie.** The kernel prints channels 0–3 under the labels
`duty_r, duty_g, duty_b, duty_b2` while the map is green / red / b2 / blue. **`duty_g` is
red.** Any field above 100 (238, 241) is a failed SPI read, not a level. And all three
common-level fields must agree — taking the first readable one reports a half-written table
as correct.

**A brightness-mode change races the kernel.** It stores the new `rgblevel` *before* pushing
four SPI writes, so anything reacting instantly writes into the middle of that sequence and
gets the common channels overwritten while red survives — a colour cast, not a flicker. The
service now waits for the four channels to agree.

**Never promise something the script does not do.** The measurement finished at 02:54 and the
projector sat on until 08:34 because the power-off existed only in a message. Anything that
must happen unattended goes in the script's exit trap.

**Clean up backgrounded commands.** A malformed one with a heredoc reading stdin blocked for
eight hours.

**Do not use MANUAL mode to force a fan speed** — it re-arms the factory ladder and the two
writers fight audibly. Stay in CURVE and hand it a flat curve instead.

---

## 6. Standing rules

- **Measure, do not simulate.** Three confident model-backed claims were wrong in one session.
  `tools/CurveSim.java` is the authority on hunting; static slope rules are not — three floor
  edges for Bright Cool gave three, zero and two duty points of hunt in an order no slope
  calculation predicts.
- **The hunting bound is two duty points.** Quiet's 16 °C corner is the accepted state and
  Bright Quiet inherits it. Three is a regression. Do not weaken the test.
- The owner's spec is **quiet *steadily*** — movement is the audible problem, not level.
- He asked that this project not read as AI-generated. Match the existing prose; commits end
  with the `Co-Authored-By` trailer the recent history uses.
