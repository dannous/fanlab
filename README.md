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
flashed, nothing rooted, uninstalls from Settings. For the Screeneo U4 (SCN350), run on
firmware 1.7.0 and 1.7.1.

## Download

**[`fanlab-system.apk` — latest release →](../../releases/latest)**

One file. You also need a USB stick. No PC, no cable, no adb, no unlocking anything.

## Installing it

AppInstaller isn't on the launcher. It's in the hidden developer menu, which you open like
this:

1. Put `fanlab-system.apk` on the **root** of a USB stick, and plug the stick into the
   projector.
2. On the projector, open **Settings**.
3. Press **OK** on the software version **five times**. The hidden developer menu opens.
4. Scroll to **AppInstaller**, near the bottom.
5. Find the APK on the USB stick, and install it.
6. Open **FanLab**, press OK on **Fan mode**, and pick one of the options below.

That's it. Picking a mode hands the fan over for you.

## Using it

The main screen is a short list. Move up and down with the remote, change a number with left
and right, press OK on a button. A note about the row you are on appears beside it.

Two choices matter. **Fan mode:**

| Fan mode | What the fan does |
|---|---|
| **Curve to 55 °C** | A simple fan curve that keeps the LED under 55 °C |
| **Curve to 58 °C** | The same curve, allowed to 58 °C. Offered when Brightness boost is on |
| **Smart quiet** | Stays at or under your **Fan limit** (40 % unless you change it). If the LED gets too warm at the limit, the picture dims slightly instead of the fan getting louder |
| **Manual** | One fixed speed that you choose |

And **Brightness boost**, which is its own switch. Off is Philips' own brightness. On runs each
brightness mode at the level you set for it under Advanced (see
[A brighter picture](#a-brighter-picture-optional)). It works with either curve and with Smart
quiet, not with Manual.
**Smart quiet** is the one to use if noise matters most. It finds the slowest fan that holds
the LED at its target for the brightness mode you are in, and it learns your projector as it
runs: how much more fan one mode needs than another, and what a point of fan is worth. That
is kept in the app's own settings, with or without the log, so after a change of brightness
mode it goes straight to about the right speed.

Its targets go up with the brightness mode, so the dimmer modes run cooler:

| Brightness mode | Target | Default brightness (Philips' own) |
|---|---:|---:|
| Super Eco | 50 °C | 65 (20) |
| Night | 55 °C | 75 (40) |
| Normal | 58 °C | 85 (55) |
| Presentation | 60 °C | 90 (76) |

**The fan limit has one exception, for safety.** If the LED is 3 °C over its target, or it is
over and there is nothing left to dim, the fan goes above the limit until it cools. The
picture is never dimmed below Philips' own brightness.

Everything comes back as you left it after a power cycle. The brightness mode belongs to the
projector, and FanLab notices whichever one you are in within a few seconds.

<details>
<summary><b>Every setting</b></summary>

**Main screen**

| Control | What it does |
|---|---|
| **Fan mode** | The four choices above |
| **Brightness boost** | On or Off. On uses your per-mode brightness levels |
| **Fan limit %** | Shown in Smart quiet. The loudest the fan may go, 30–83, or Off |
| **Manual fan %** | Shown in Manual. The fixed speed |
| **Start on boot** | Leave this on, or Philips' controller takes back over at the next power-on |

**Advanced**

| Control | What it does |
|---|---|
| **Smart quiet → target °C**, one per brightness mode | The LED temperature Smart quiet holds, 45–60 °C. Lower is kinder to the LEDs but needs more fan, or a dimmer picture at the limit |
| **Smart quiet → Fan floor %** | The slowest the fan runs, 20–40, normally 30. Below 30 is untested. The curves never go below 25 |
| **Brightness (LED drive)**, one per brightness mode | How hard the LEDs are driven, 20–97. Used when Brightness boost is on |
| **Reset brightness and targets** | Puts both back to the tables above |
| **Tests** | White screen (full load), colour slides (white, red, green, blue, black), a focus pattern, and an unattended test that walks through the modes by itself |
| **Measure** | Sweep every fan speed, hold one fan speed, and a room temperature that is only written to the log |
| **Logs** | Write the CSV log, copy it to shared storage, copy everything to a USB stick, and raw diagnostics |
| **System → ADB over USB** | Switches the USB port so a PC can connect. Untested on this projector |
| **System → Restore stock controller** | The permanent undo. Press this before uninstalling |

The fan speed is put back every second if the projector changes it. That used to be a switch;
it is now always on.

</details>

> **Coming from a version before 1.8.0?** Linear mode, the curve presets and the curve editor
> are no longer on the screen. A setup that was using one of them moves to the built-in curve.
> "Auto 58" is now Smart quiet, and "Eco" is called Night, as the projector's own menu does.

## Removing it

**To give the fan back to Philips** — press **Advanced → Restore stock controller**. Expect a
few seconds of loud fan; the handover goes via 83 % on purpose, so nothing is ever left
unmanaged in between. Pick a Fan mode again whenever you want FanLab back.

**To uninstall it,** in this order:

1. Press **Advanced → Restore stock controller** in the app.
2. Then uninstall FanLab from Settings, like any other app.

| How you stop it | Philips' controller handed back? |
|---|---|
| Restore stock controller, force-stop, crash, reboot | yes |
| Uninstalling without pressing Restore first | **no** |

Taking over the fan sets a system property telling Philips' controller to stand down, and it
lives outside the app. FanLab puts it back on every path where it gets to run code — but an
uninstall runs none of them.

**Already uninstalled without pressing it?** The 75 °C cut-out and the fan-stall watchdog are
both still there and the fan runs at the projector's default speed, but nothing is responding
to temperature. Reinstall FanLab, press **Advanced → Restore stock controller**, done.

## Safety

| | |
|---|---|
| The 75 °C cut-out and the fan-stall watchdog | untouched, and the app cannot disable them |
| Every failure path — bad sensor, crash, force-stop | fan to **83 %**, stock controller handed back. A quiet fan is never the fallback |
| Firmware, system partition, bootloader | unchanged |
| The cost | light engine at about **52 °C** where stock holds 44–46 °C; expect a small, reversible loss of red LED output and a slightly cooler white point |
| The 55 °C ceiling | a judgement call — the DMD's temperature can't be measured on this hardware, so the line was drawn where the stock controller itself demands maximum fan |
| Curve to 58 °C and Smart quiet | run the LEDs harder and warmer than Philips does, up to 60 °C in Presentation. That is past Philips' 55 °C comfort point by choice, and it costs LED life by an amount nobody has measured for this projector. Above **65 °C** the brighter picture switches itself off and stays off until you change brightness mode or a setting |

Worked out on one projector, by measuring it. [docs/safety.md](docs/safety.md) is the
complete and unflattering version. Not endorsed by or affiliated with Philips or Screeneo
Innovation SA.

## What it does on a real projector

One evening's log (14 November 2026, one projector, one room), with what was Auto 58 and is
now Smart quiet holding 58 °C in every mode:

| Brightness mode | Brightness | Fan it settled at | LED |
|---|---:|---:|---:|
| Presentation | 95 | **43 %** | 57.8 °C |
| Normal | 80 | **31 %** | 56.9 °C, still rising slowly after 20 minutes |
| Night | 60 | on the floor | 52.6 °C |

Those were measured before the fan limit and the per-mode targets existed. With a 40 % limit,
Presentation at 95 would sit at 40 % with the picture dimmed by an estimated two or three
points; that estimate has not been checked on the projector.

## The fan curves (versions before 1.8.0)

This section and the next describe the curve presets that versions before 1.8.0 let you
choose. From 1.8.0 the two Curve modes use one built-in curve (your fan floor up to 50 °C, 52 % at
60 °C, 83 % by 70 °C, plus up to ten points if the LED passes its target), and the presets
are no longer on the screen. The headline table at the top of this page was measured with
the Quiet preset.

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

Philips runs the LEDs at about three-quarters of what the hardware allows. **Brightness boost**
turns all four modes up:

| Brightness mode | Philips | FanLab | More LED drive |
|---|---:|---:|---:|
| Super Eco | 20 | **65** | +225 % |
| Night | 40 | **75** | +88 % |
| Normal | 55 | **85** | +55 % |
| Presentation | 76 | **90** | +18 % |

Percentages of the LED driver's own maximum. These are the defaults; each mode can be set from
20 to 97 under **Advanced → Brightness (LED drive)**. The brighter picture only applies while
FanLab is driving the fan, so brightness can never outrun cooling.

The rest of this section was measured on versions before 1.8.0, with the Bright presets, Normal
at 75 and Presentation at 90 — **turned-up Normal at 75 sat level with Philips' Presentation
at 76:**

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
| Fan is loud and won't come down | No Fan mode is chosen (the row says Stock), or it's been less than a minute. In Smart quiet, check the status line: "limit lifted" means the LED was too warm for your fan limit |
| Fan jumps every time I change brightness | The projector slams a fan speed in; FanLab puts it back within a second. Check **Re-assert every second** is on |
| Picture went back to normal brightness on its own | The brighter picture's 65 °C cut-out latched, and stays off until you change brightness mode or a setting. Lower that mode's brightness under Advanced, or raise the fan limit ||
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
