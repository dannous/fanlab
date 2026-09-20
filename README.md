# FanLab

**A quieter fan for the Philips Screeneo U4 projector.**

Philips' fan controller is five fixed speeds, re-checked every fifteen seconds, using the
same temperature to step up as to step down. In Presentation the projector settles right on
one of the steps — so it surges between 59 % and 70 %, forever. FanLab replaces it with a
continuous curve, and the fan settles on one speed and stays there.

| Brightness mode | Philips' fan | FanLab's fan | Light engine |
|---|---:|---:|---:|
| **Presentation** | **59 %, surging to 70 %** | **38 %, steady** | 51.9 °C |
| Normal | 48 % | **30 %** | 44 °C |
| Eco | 43 % | **30 %** | 40 °C |
| Super Eco | 43 % | **30 %** | 35 °C |

30 % is inaudible from a sofa. Over an hour against the projector's real thermal behaviour,
Philips' controller made **232** speed changes and FanLab made **25**, none bigger than a
single percent.

The projector runs Android, so this is just an app — installed from a USB stick, nothing
flashed, nothing rooted, uninstalls from Settings. For the Screeneo U4 (SCN350) on firmware
1.7.0.

## Download

**[`fanlab-system.apk` — latest release →](../../releases/latest)**

One file. You also need a USB stick. No PC, no cable, no adb, no unlocking anything.

## Installing it

1. Copy `fanlab-system.apk` onto a USB stick and plug the stick into the projector.
2. On the projector, open **AppInstaller** from the launcher and pick that file.
3. Open **FanLab** from the launcher.
4. Change **Mode** from `OFF` to `CURVE`.

That's it. Choosing `CURVE` hands the fan over for you — there's a **Take over** button, but
you don't need it.

The fan takes about fifteen seconds to come down from wherever Philips had it, then it should
stay put. Leave it in Presentation for ten minutes and watch the number on screen — if it's
working, it won't move.

## Using it

The main screen is a list. Move up and down with the remote, change a setting with left and
right, press OK on a button. Three things matter:

| Setting | Do this |
|---|---|
| **Mode** | Set to `CURVE` and leave it |
| **Curve preset** | Start on `Quiet` |
| **Start automatically after a reboot** | Leave it on, or Philips' controller takes back over at the next power-on |

Everything comes back as you left it after a power cycle. The one thing FanLab doesn't
remember is the brightness mode, because that belongs to the projector — but it notices
whichever mode you come back in within a second.

<details>
<summary><b>Every setting on the screen</b></summary>

| Control | What it does |
|---|---|
| **Mode** | `OFF` watches without driving. `CURVE` is the fan curve, and the one to use. `MANUAL` holds one fixed speed. `LINEAR` holds a temperature instead of a speed — see [docs/internals.md](docs/internals.md#linear-mode) |
| **Curve preset** | Four steps, quietest first. With the LED drive on you get the `Bright` four instead |
| **Room temperature** | Optional, and only written to the log — nothing in the fan control reads it. Leave it at "not stated" unless you know the figure, because a stale number is worse than none |
| **Write CSV telemetry** | Logging on or off. On by default, and it prunes itself |
| **Start automatically after a reboot** | Leave this on |
| **Re-assert every second** | Leave this on. The projector slams a fan speed in whenever you change brightness, and this puts it back |
| **LED drive** | Off by default. See [A brighter picture](#a-brighter-picture-optional). It only applies while FanLab is actually driving the fan, so brightness can never outrun cooling |
| **RELEASE CONTROL** | Hands the fan back for now |
| **RESTORE STOCK FAN CONTROL** | The permanent undo. Press this before uninstalling |

</details>

## Removing it

**To stop it driving the fan for now** — press **RELEASE CONTROL**, or set Mode to `OFF`.
Expect a few seconds of loud fan; the handover goes via 83 % on purpose, so nothing is ever
left unmanaged in between.

**To uninstall it,** in this order:

1. Press **RESTORE STOCK FAN CONTROL** in the app.
2. Then uninstall FanLab from Settings, like any other app.

| How you stop it | Philips' controller handed back? |
|---|---|
| Mode → OFF, RELEASE CONTROL, force-stop, crash, reboot | yes |
| Uninstalling without pressing RESTORE first | **no** |

Taking over the fan sets a system property telling Philips' controller to stand down, and it
lives outside the app. FanLab puts it back on every path where it gets to run code — but an
uninstall runs none of them.

**Already uninstalled without pressing it?** The 75 °C cut-out and the fan-stall watchdog are
both still there and the fan runs at the projector's default speed, but nothing is responding
to temperature. Reinstall FanLab, press **RESTORE STOCK FAN CONTROL**, done.

## Safety

| | |
|---|---|
| The 75 °C cut-out and the fan-stall watchdog | untouched, and the app cannot disable them |
| Every failure path — bad sensor, crash, force-stop | fan to **83 %**, stock controller handed back. A quiet fan is never the fallback |
| Firmware, system partition, bootloader | unchanged |
| The cost | light engine at about **52 °C** where stock holds 44–46 °C; expect a small, reversible loss of red LED output and a slightly cooler white point |
| The 55 °C ceiling | a judgement call — the DMD's temperature can't be measured on this hardware, so the line was drawn where the stock controller itself demands maximum fan |

Worked out on one projector, by measuring it. [docs/safety.md](docs/safety.md) is the
complete and unflattering version. Not endorsed by or affiliated with Philips or Screeneo
Innovation SA.

## The fan curves

Percent duty against the LED thermistor. Philips has five rungs and no gap between stepping
up and stepping down; FanLab has six knees with a straight line between them, flat below the
first and flat above the last.

**Philips**

| LED thermistor | Presentation | Normal | Eco / Super Eco |
|---|---:|---:|---:|
| ≤ 45 °C | 59 % | 48 % | 43 % |
| 46 – 48 °C | 70 % | 60 % | 55 % |
| 49 – 50 °C | 75 % | 65 % | 60 % |
| 51 – 52 °C | 80 % | 70 % | 65 % |
| 53 – 54 °C | *nothing written* | *nothing written* | *nothing written* |
| ≥ 55 °C | 83 % | 80 % | 70 % |

**FanLab** — the same row is used in all four brightness modes.

| °C | Quiet | Balanced | Cool | Cold |
|---|---:|---:|---:|---:|
| 47 — floor (**43** on Cold) | 30 % | 30 % | 30 % | 30 % |
| 51 | 38 % | 43 % | 48 % | 53 % |
| 55 | 40 % | 45 % | 50 % | 55 % |
| 60 | 50 % | 55 % | 60 % | 65 % |
| 66 | 68 % | 73 % | 78 % | 83 % |
| 70 | 83 % | 83 % | 83 % | 83 % |

| Preset | Presentation settles at | Stays under 55 °C up to |
|---|---:|---|
| **Quiet** (default) | 38 % | 28.0 °C |
| Balanced | 41 % | 30.0 °C |
| Cool | 43 % | 31.2 °C |
| Cold | 46 % | 32.6 °C |

Below the floor the fan sits at 30 % and never moves — that is Normal, Eco and Super Eco on
every preset. Cold is the exception: its floor starts at 43 °C, so it runs Normal at 32–39 %
in a room above about 23 °C.

<details>
<summary><b>Bright Curve — the four presets used when the LED drive is on</b></summary>

| °C | Bright Quiet | Bright Balanced | Bright Cool | Bright Cold |
|---|---:|---:|---:|---:|
| 47 — floor (**45** on Bright Cool, **43** on Bright Cold) | 30 % | 30 % | 30 % | 30 % |
| 51 | 38 % | 43 % | 48 % | 53 % |
| 55 | 50 % | 55 % | 60 % | 65 % |
| 60 | 62 % | 67 % | 72 % | 77 % |
| 66 | 76 % | 81 % | 83 % | 83 % |
| 70 | 83 % | 83 % | 83 % | 83 % |

These rows are used in **Presentation only**. In Normal, Eco and Super Eco a Bright preset
uses its ordinary counterpart's row unchanged, and the 51 °C knee is unchanged too — so all
four brightness modes are identical at and below 51 °C.

You don't pick the family. Turning the LED drive on moves you to the Bright version of the
step you're on, turning it off moves you back, and the preset button only cycles inside the
family you're in. An ordinary curve can't be paired with the raised drive.

</details>

<details>
<summary><b>How the curve works</b></summary>

It's a loop — the fan changes the temperature, and the temperature changes the fan. The
machine slides along the curve until it finds the one point where both are true at once.
Nobody picked 38 %; that's where the projector's thermal response crosses the curve.

The two flat regions are the point. Flat at 30 % below 47 °C, where Normal, Eco and Super Eco
live. Nearly flat from 38 % to 40 % across 51–55 °C, where Presentation lives. A flat region
has no boundary to park on, so five degrees of room drift move the fan by two points instead
of slamming it between two speeds.

Derivation, the measured thermal plant and the stability analysis:
[docs/curve.md](docs/curve.md).

</details>

## A brighter picture (optional)

Philips runs the LEDs at about three-quarters of what the hardware allows. The optional
**LED drive** setting turns all four modes up:

| Brightness mode | Philips | FanLab | More light |
|---|---:|---:|---:|
| Super Eco | 20 | **35** | +75 % |
| Eco | 40 | **55** | +38 % |
| Normal | 55 | **75** | +36 % |
| Presentation | 76 | **90** | +18 % |

Percentages of the LED driver's own maximum. Which gives you this — **turned-up Normal sits
at 75, and Philips' Presentation sits at 76:**

| What you're running | Light | Fan |
|---|---:|---:|
| Philips, Presentation | 76 | **59–70 %, surging** |
| FanLab, Presentation, drive off | 76 | **38 %, steady** |
| **FanLab, Normal, drive on** | **75** | **37–39 %, steady** |
| FanLab, Presentation, drive on | **90** | 47–51 %, steady |

Philips' Presentation brightness for roughly half the fan — or a picture brighter than the
projector will otherwise give you, still quieter than stock.

**What it costs:** the room temperature at which each mode stops sitting at a silent 30 %.

| Brightness mode | Drive off | Drive on |
|---|---:|---:|
| **Normal** | 27.3 °C | **19.2 °C** |
| Eco | 31.1 °C | 24.8 °C |
| Super Eco | 36.5 °C | 30.3 °C |

So with the drive up, Normal and Eco are off the silent floor in any room you'd sit in.
Normal goes from 30 % to about 37 %. Super Eco stays silent. Turning it off puts everything
back.

> Temperatures in these tables are the air the projector draws in, which measured about
> 4.7 °C warmer than the room — so 24 °C here is roughly a 19–20 °C room. One day's
> measurement, so treat the conversion as rough.

<details>
<summary><b>When something looks wrong</b></summary>

| Symptom | Cause |
|---|---|
| Fan is loud and won't come down | Mode isn't `CURVE`, or it's been less than fifteen seconds |
| Fan jumps every time I change brightness | The projector slams a fan speed in; FanLab puts it back within a second. Check **Re-assert every second** is on |
| Picture went back to normal brightness on its own | The LED drive's 60 °C cut-out latched, and stays off until you change brightness mode or a setting. Pick a cooler preset, or turn the drive off |
| Projector shut down | The 75 °C cut-out — something is physically wrong. Check the vents are clear |

</details>

<details>
<summary><b>Building it, and what's in the repo</b></summary>

```powershell
cd app
.\build.ps1 -Clean
```

`aapt2 → javac → d8 → apksigner`, driven by one PowerShell script. No Gradle. Set
`ANDROID_SDK_ROOT` and `JAVA_HOME` if they aren't in the default Windows locations. The host
test suite runs as part of every build — 4586 assertions, and the build refuses to produce an
APK if any of them fail.

**Signing keys aren't included.** The build needs the AOSP platform key, because that's what
this firmware happens to be signed with. Supply your own as `app/keys/platform.pk8` and
`app/keys/platform.x509.pem`.

| | |
|---|---|
| `app/` | the application — source, manifests, resources, host tests, build script |
| `tools/` | measurement and deployment tooling |
| `docs/` | how it works, safety, curve derivation, findings, and notes for working on the code |
| `release/` | the signed APK |
| `final_curve.txt` | the deployed curve, in the app's own encoding |

</details>

## The engineering

[docs/internals.md](docs/internals.md) is the rest of it — the LED drive override, the SoC
guard, telemetry, LINEAR mode, how the presets are built, what the reverse-engineering turned
up and how the tests work.

| Document | What's in it |
|---|---|
| [docs/internals.md](docs/internals.md) | how everything works, in detail |
| [docs/safety.md](docs/safety.md) | the complete safety argument, and every failure path |
| [docs/curve.md](docs/curve.md) | how the curve was derived, and the measurements behind it |
| [docs/findings.md](docs/findings.md) | what taking the firmware apart established |
| [docs/measuring.md](docs/measuring.md) | measuring your own projector and re-deriving the curve |
| [docs/measurement-conditions.md](docs/measurement-conditions.md) | the exact conditions everything was measured in |
| [docs/deploy.md](docs/deploy.md) | driving the app over adb |
| [docs/hacking.md](docs/hacking.md) | notes for working on the code, and the traps in it |

## Licence

MIT — see [LICENSE](LICENSE).
