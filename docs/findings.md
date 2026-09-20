# Findings

Everything here was produced from an adb shell on a live Screeneo U4 running firmware
1.7.1. Claims are marked **VERIFIED** (with the command and its output) or
**INFERRED**, and the command that produced each is given so it can be re-run.

The tooling behind these results lives in `tools/`:

| file | what it is |
|---|---|
| `therm.py` | ADC ↔ °C, the framework's exact arithmetic |
| `fanlab.sh` | device-side runner: drives `fan_ctrl` through a plan, logs CSV, always restores |
| `fit_run2.py` | live view of a run — segments by hold, fits the steady state |
| `solve_curve.py` | fixed point of a curve against the measured plant, with loop gain |

---

## An undriven temperature device at i2c 0x1c

The board's device tree declares a second temperature device beside the display
controller, and nothing in the system reads it:

```
/sys/bus/i2c/devices/2-001b            the display controller (driver bound)
/sys/bus/i2c/devices/2-001c
  name     = dlp_i2c_tmp
  modalias = i2c:dlp_i2c_tmp
  driver   = (none)
kernel symbols matching dlp_i2c_tmp    0
/dev/i2c-2                             crw------- root root
```

**VERIFIED.** The declaration is real, no driver exists for it anywhere in the kernel, and
the raw i2c bus is root-only — so neither an adb shell (uid 2000) nor a platform-signed
system app (uid 1000) can open it. Note the contrast with the LED thermistor, whose sysfs
node init deliberately chmods to 0777, exactly as it does for `fan_ctrl`.

**What is not known:** which part sits at 0x1c, whether it responds, and what it measures.
A device-tree node is a statement of intent by whoever laid out the board, not proof that a
component was fitted or that it works. Calling this "the DMD temperature" would be an
assumption, and assumptions of that shape are what this section exists to correct.

**What it changes.** The display controller's own `Read System Temperature` register really
does read zero (below), and that remains true. But the broader claim that this hardware has
no DMD-side temperature sensor at all does not follow from it: the sensor inventory below
was built by enumerating `/sys/class`, and **an i2c device with no bound driver never
appears there.** The boundary is a missing driver and a root-only device node, not absent
silicon.

**How someone could pursue it:** an `init.rc` chmod on `/dev/i2c-2`, a small i2c driver, or
root. None of those is needed for the fan curve, which reads only the LED thermistor.

## 1. The display controller's own temperature register reads zero

**VERIFIED. The read path works, and there is nothing behind it.**

`/sys/class/dlpc343x/picoreg` has no sysfs show handler, so a register read returns
nothing through the node itself — but the driver prints the reply to the kernel log, which
makes `r d6 2` followed by `logcat -b kernel` a working read.

Sweeping the command space (`echo "r <op> 4" > /sys/class/dlpc343x/picoreg`, then
`logcat -b kernel`) shows two distinct response shapes:

```
r 0xd3  ->  lcd extern: read 0xd3 data: d3 00 01 02      <- first byte == opcode
r 0xd6  ->  lcd extern: read 0xd6 data: 00 00 01 02      <- first byte != opcode
r 0xd0  ->  lcd extern: read 0xd0 data: 8b 00 00 0d
```

**The discriminator is whether the first data byte echoes the opcode.** Opcodes `10, 11,
13, 14, d3, d5, d7, d8, da, db, dc, dd, de, df` all echo — that is an unsupported command
returning the command byte. Opcodes `15, d0, d1, d2, d4, d6, d9` return something else,
which is a genuine reply.

So **D6h (Read System Temperature) is supported by the controller and reads a hard
`00 00`.** Three consecutive reads gave `00 00` while `d0` read `0x8b` and then `0x83`
between two sweeps — a live, changing register next door proves the bus and the log path
are both fine.

> **Conclusion: the DLPC's own system-temperature input is not populated.** This is a
> statement about that register, not about the board as a whole -- see the i2c 0x1c device
> above.
> This is not a permissions problem, a parse problem, or a `READ_LOGS` problem. There is
> no controller-side temperature to read, so `fanlab-system.apk`'s `READ_LOGS` permission
> — whose stated justification in `AndroidManifest.system.xml` is exactly this — buys
> nothing for the thermal question. TARGET.md §1 calls D6h "a precondition for committing
> to a low floor". That precondition cannot be met and the decision has to be made without
> it.

**Do not read this as licence to probe further by writing.** Only `r` (read) commands were
issued. The `picoreg` node passes arbitrary I2C through to the DLPC and a stray write
could reconfigure the display controller.

## 2. The complete sensor inventory — two signals, and that is all

**VERIFIED** by enumerating `/sys/class`:

| node | what it is | used |
|---|---|---|
| `/sys/class/ledtemp/voltage` | 12-bit ADC on the red-LED thermistor | the control signal |
| `/sys/class/thermal/thermal_zone0/temp` | `pll_thermal`, SoC die | chassis heat-soak proxy |
| `/sys/class/thermal/thermal_zone1/temp` | `ddr_thermal`, SoC die | " |
| `/sys/class/thermal/thermal_zone2/temp` | `sar_thermal`, SoC die | " |

There is **no `hwmon` class** on this device (`/sys/class/hwmon` exists and is empty), and
`power_supply` exposes no `temp`.

**The MAX20096 route is closed too, and it was worth checking** — the LED drivers do have
SPI junction-temperature readback per their datasheet (`research/05` §top), and unlike the
DLPC's D6h nobody had ever tried it. But:

* `/sys/bus/spi/devices/` holds `spi0.0` and `spi0.1` (the two drivers), and
  `/sys/bus/spi/drivers/` holds only `pcm186x` and `spidev` — no MAX20096 driver, so no
  hwmon node;
* **`/dev/spidev*` does not exist**, so `spidev` is bound to nothing and there is no
  userspace path to the bus at all;
* the `dlpc343x` driver talks to the parts directly and logs only current, never
  temperature (`logcat -b kernel` has no temperature-shaped line from it).

> **Conclusion: two temperature signals is the complete set on this machine.** The LED
> thermistor and the three SoC dies. Everything else requires root or a soldering iron.
> That is now three independent sensor routes ruled out — DLPC D6h (unpopulated), hwmon
> (absent), MAX20096 SPI (unreachable) — so it is worth writing down rather than
> re-investigating.

The three SoC zones are new to this run. `RUNS.md` asked for them by name, on the grounds
that they are a decent proxy for internal case temperature and therefore for the slow
chassis pole that a single-exponential fit misses. They are now logged at 1 Hz alongside
the LED thermistor.

## 3. A shell can silence the stock controller — tested discriminatingly

**VERIFIED.** Not "the setprop returned success" — the ladder was watched for three of its
own 15 s poll cycles:

```
before: kill=1 fan=59
set:    kill=0
wrote 33 to fan_ctrl
  t+5s..t+45s   fan=33 fan=33 fan=33 fan=33 fan=33 fan=33 fan=33 fan=33 fan=33
restored kill=1
after handback, fan=70          <- stock re-imposed its own value immediately
```

`persist.sys.fanctrl.by.temperatue=0` is settable from uid 2000 and it really does stop
`adjust_fan_speed_v1` writing. The handback is equally prompt, which is what makes the
restore path in `fanlab.sh` trustworthy.

**Consequence for delivery.** The plain (debug-signed) APK can drive `fan_ctrl` but cannot
set `persist.sys.*`. That was the argument for needing the platform-signed build. It no
longer holds: a shell can set the kill switch once, it persists across reboot, and the
plain app can then own the fan. The system build is still the safer vehicle — it can
*coordinate* the switch, setting 0 when it starts driving and 1 when it stops, which the
plain build cannot — but it is no longer a precondition.

## 4. The app is configurable from a shell

New `ConfigReceiver` (`app/src/com/daleygames/fanlab/ConfigReceiver.java`, registered in
both manifests). The curve is thirty numbers; entering it on a D-pad once per tuning
iteration is how a typo gets into the one table that must not have one.

```bash
adb shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver \
    -a com.daleygames.fanlab.CONFIG \
    --es curve "v1,42,48,52,55,58,62,30,32,42,56,70,83,34,36,44,56,70,83,38,40,46,56,70,83,0.5,0.25,0.12,10,35,83" \
    --ei mode 2
```

It replies with the resulting state as the broadcast result, so `am broadcast` prints it
on stdout and the stored value can be diffed against the intended one. **VERIFIED**
byte-identical round trip for the curve above.

Everything routes through `Prefs.setCurve` → `CurveConfig.sanitise()`, the same path the
on-screen editor uses, so a malformed curve is repaired rather than obeyed. The reply says
`curve(REPAIRED)` when that happens.

Reading state changes nothing, so it is also the quickest way to see what the projector
currently believes:

```bash
adb shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver
```

It is also the quickest way to confirm that what is stored matches what was intended,
which matters because a curve is thirty numbers and a typo in one of them is invisible.

**Use the full component name.** The system build's package is
`com.daleygames.fanlab.system`, not `com.daleygames.fanlab` -- the two builds have to be
installable side by side. `am broadcast` does not validate the component, so a broadcast
addressed to the wrong one reports `Broadcast completed: result=0` and silently does
nothing. If a setting appears not to take, check the component before you check the code:
a successful-looking broadcast that changed nothing is the failure mode.

## 5. Brightness mode can be switched from a shell

**VERIFIED** against `research/02` §8.1, which established that
`dlpc343x_class_rgblevel_write` both stores the level *and* calls
`dlpc343x_set_rgbcurrent_level()` — so the fan ladder's notion of the mode and the actual
LED drive are the same variable.

```bash
echo 3 > /sys/class/dlpc343x/rgblevel     # 1 Eco, 2 Normal, 3 Presentation, 4 Super Eco
```

This is what makes an unattended multi-mode sweep possible at all; previously a mode change
needed the remote. The write is not persisted (`persist.vendor.light_mode` is untouched),
so a reboot or a trip through the on-screen brightness picker undoes it — which is the
right default for an experiment.

**The composition to never ship** is also from `research/02` §8.1: writing `rgblevel=1` and
then `rgbcurrent=76` gives Presentation-class LED heat with the Eco fan ladder, invisibly
to the Java layer. `fanlab.sh` never touches any `*current` node.

## 6. Run 2 — method

`fanlab.sh` holds a plan of `LEVEL:DUTY:SECONDS` steps, re-asserting `fan_ctrl` every
second and logging at 1 Hz. Safety properties, in the order they matter:

* the stock ladder is disabled **only for the life of the script** — `trap` on `EXIT`,
  `INT`, `TERM` and `HUP` restores `persist.sys.fanctrl.by.temperatue=1`, so losing the
  adb connection re-arms stock rather than leaving the fan unmanaged;
* every abort writes duty **83** before restoring — fail safe is high;
* a hard over-temperature stop at **ADC 701 ≈ 62 °C**, far below the framework's 75 °C
  shutdown, so the script gives up long before the platform has to;
* it aborts if the light engine goes off, and restores the brightness level it found.

Ambient **24 °C**, reported by the owner, at the projector.

## 7. The chassis moves as one thermal mass — first direct evidence

**VERIFIED**, and it is new: nothing before this run logged a second temperature.

During a single 12-minute hold at one fixed duty (Presentation, duty 40), comparing the
first-half mean against the last-60-second mean:

| signal | change over the hold |
|---|---|
| LED thermistor | **+1.81 °C** |
| `pll_thermal` (SoC die) | **+2.02 °C** |
| `ddr_thermal` (SoC die) | **+2.11 °C** |
| `sar_thermal` (SoC die) | **+2.02 °C** |

Two things follow.

**The slow pole is real and it is chassis-wide.** The LED thermistor was still climbing
~1.8 °C in the back half of a hold whose fast time constant is ~230 s — that is the second
pole `RUNS.md` predicted, and the SoC dies confirm it is bulk heat soak rather than
anything local to the LED board. It is why a single-exponential fit keeps finding a longer
tau and a higher asymptote the longer you let it run.

**Four sensors on two different boards rise in lockstep, within 0.3 °C of each other.**
That is the signature of a well-mixed internal volume, not of thermally isolated regions.

> This is **not** proof that the DMD tracks the LED thermistor — the DMD has its own heat
> source and its own path, and `TARGET.md` §1 is right that the relationship is a board
> constant nobody has established. But it is the first *evidence* on the question, and it
> points the reassuring way: separated parts of this chassis demonstrably move together,
> which is the opposite of the runaway-in-an-isolated-pocket scenario the DMD worry is
> about. Treat it as weakening that worry, not as retiring it.

Also worth recording: **the SoC dies run hotter than the LED thermistor**, `ddr_thermal`
by about 4–5 °C. The LED thermistor — the sensor the 75 °C shutdown actually watches — is
therefore not the hottest measurable point in the machine.

## 8. Fan authority at the quiet end is about double what CURVE.md states

**VERIFIED** from run 2's long holds. `CURVE.md` says *"Fan authority is weak: about
0.17 °C per duty point, steepening to ~0.34 at the bottom."* The first figure is the
average over the loud half of the range and is fine there. The second is wrong.

Presentation, measured °C of LED temperature per duty point:

| duty band | slope |
|---|---:|
| 70–83 | 0.062 |
| 60–70 | 0.130 |
| 55–60 | 0.160 |
| 50–55 | 0.280 |
| 45–50 | 0.240 |
| **40–45** | **0.402** |
| **35–40** | **0.602** |

So the plant is roughly **ten times more responsive at duty 35 than at duty 80**, and
about **double** what `CURVE.md` assumed at the bottom. Two consequences pull in opposite
directions and both matter:

* **Good:** the fan has far more control authority in the quiet range than anyone thought.
  A few duty points genuinely move the temperature down there.
* **Bad, and this is the one that bites:** loop gain is (curve slope) × (plant slope), so
  the same curve is **twice as close to instability** at the quiet end as the old figure
  implied. A ramp leaving a shelf at duty 30–40 must be shallower than about
  **1.3 duty points per °C** to keep the gain under 0.8. The first shelf candidate used
  2.0 and had to be redesigned.

This also explains why run 1's extrapolations were optimistic and why the error grew the
lower it went: it extended a slope of ~0.34 into a region where the truth is ~0.6.

| duty | run 1 said (extrapolated) | run 2 measured | error |
|---|---:|---:|---:|
| 35 | 28.3 | **29.92** | 1.6 °C |
| 30 | 29.8 | ~32.5 (in progress) | ~2.7 °C |

## 9. Datasheet corrections — three project conclusions downgraded

Full working in `tools/datasheet_limits.md`. These change what other documents rest on.

**9.1 "The DMD is happy to 70 °C long-term" is the best case, not the video case.**
`research/05` §A5 and `TARGET.md` §1 both lean on it. The DLP230NP's long-term T_ARRAY
rating derates from 70 °C **down to 40 °C** with micromirror landed duty cycle, and the
derating is **symmetric** — a black pixel derates exactly as hard as a white one, mid-grey
is the single best point. Gamma-corrected video (the datasheet's own example: 40 % in →
13 % out) sits around 10/90–20/80, firmly on the derated side. The datasheet gives a
*method*, not a number, and Figure 5-1's plotted line did not survive text extraction —
so **any intermediate figure would be invented**.

**9.2 "The 75 °C shutdown lands exactly on the DMD's short-term ceiling" is a coincidence.**
`research/05` hedged it as INFERRED; it should be dropped. The identity only holds if
`D + 9×Q_ARRAY = 0`, where D is the LED-board-to-DMD offset and the second term is
provably non-zero whenever the projector is projecting (TI's own worked example puts it at
**+12.0 °C**). Treat 75 °C as an arbitrary vendor choice on an LED-board sensor — and
leave it alone anyway, because it is untested, not because a datasheet endorses it.

**9.3 DLPC3436 TJ 125 °C is the absolute maximum, not the operating limit.** Recommended
operating is **TJ ≤ 105 °C, TA ≤ 85 °C**. The error was in the generous direction but it
overstated headroom by 20 °C.

**9.4 `research/05`'s suggestion to read DLPC command 57h as thermal data is wrong.** It
returns `Σ duty × current × voltage` and contains no temperature term. It was described here
as still being the right way to measure what CAIC saves; that turned out not to need
measuring. A pinned-fan A/B on 2026-09-07 put CAIC off and CAIC on at the same **52.33 °C**,
because the DLPC has no LED driver it can reach on this board. See the README under *The
three display-controller features, and why none of them is here*.

**9.5 Why D6h is dead, from the other end.** §1 above established it empirically. The
datasheet says why: the DLPC343x has **no on-chip ADC and no on-chip temperature sensor**.
It runs a software SAR against an *external* comparator, and TI's guidance is *"It is
recommended to use the DLPAxxxx to achieve this function."* `research/05` already
established this board has **no DLPA** — it uses MAX20096 LED drivers instead. The part
that would implement the sensor was never fitted. Two independent routes, same answer.

**What this leaves.** The chain from the sensor we have to the limit we care about is

```
T_ARRAY = T_LED + D + (Q_ARRAY × 9.0 °C/W)  ≤  T_max(landed duty cycle)
```

and **D is unmeasured and unbounded in sign** — the red die argues D < 0, the DMD
absorbing 40 % of incident light into 5.2 × 2.9 mm argues the other way, and nothing
decides it. Assume D = 0 with TI's adder and the implied ceiling is 58 °C for symmetric
content and **28 °C** for asymmetric; 28 °C is below what this machine reaches at *any*
fan speed, so at least one assumption is provably wrong and we cannot tell which.

> **Therefore `TARGET.md` §1 needs amending.** It calls a D6h reading "a precondition for
> committing to a low floor". That precondition is unmeetable, and the decision has to be
> made without it. The ceiling actually adopted — **55 °C, because the stock ladder itself
> commands maximum fan at that temperature** — is empirical rather than analytical, and
> that is not a fallback: it is the only kind of answer the hardware can support.
