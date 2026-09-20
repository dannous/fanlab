# FanLab

**A quieter fan for the Philips Screeneo U4 projector.**

If your Screeneo U4 sounds like a jet engine, this fixes it. FanLab gives the fan a proper
curve, so it settles on one speed and stays there — at roughly two thirds of Philips' speed,
and in Presentation closer to half.

The projector runs Android, so this is just an app. You install it from a USB stick like
anything else. Nothing is flashed, nothing is rooted, and it uninstalls from Settings.

For the Screeneo U4 (model SCN350) on firmware 1.7.0.

**[Download the latest release →](../../releases/latest)**

---

## What's wrong with the fan

Philips' fan control has five fixed speeds. Every fifteen seconds it checks the temperature
and picks one.

The trouble is it uses the same temperature to step up as it does to step down. There's no
gap between them. The first step sits at 46 °C — and in a normal room, the projector settles
at almost exactly 46 °C.

So it drifts a fraction too warm, jumps to a much higher speed, cools down, drops back to a
lower (still high) speed, warms up, and does it again. Forever.

That's what you're hearing. The fan isn't too fast. It's that it never stops changing, and a
fan that changes is far more noticeable than one that's simply on.

FanLab throws away the five steps and uses a smooth curve instead. There's nothing left to
trip over, so there's nothing to surge between.

Over an hour, measured against the projector's real thermal behaviour: Philips' controller
made **232 speed changes**. FanLab made **25**, and not one of them was bigger than a single
percent.

## What changes

Same picture, same brightness, same everything — just the fan. Measured at 24 °C:

| Brightness mode | Philips' fan | FanLab's fan | Light engine |
|---|---:|---:|---:|
| **Presentation** | **59 %, surging to 70 %** | **38 %, steady** | 51.9 °C |
| Normal | 48 % | **30 %** | 44 °C |
| Eco | 43 % | **30 %** | 40 °C |
| Super Eco | 43 % | **30 %** | 35 °C |

30 % is inaudible from a sofa. So three of the four modes stop being something you can hear
at all, and Presentation roughly halves and then holds still.

The projector runs warmer than it used to. That's the trade — a slower fan moves less air.
[Is it safe?](#is-it-safe) covers what that costs and what still protects you.

## Want it brighter too?

Two things pull against each other here: how loud the fan is, and how much light the
projector puts out. Philips fixes both. FanLab lets you move both.

And this part surprised me. Philips runs the LEDs at about three-quarters of what the
hardware actually allows, in every mode. There's an optional **LED drive** setting that turns
all four up:

| Brightness mode | Philips | FanLab | More light |
|---|---:|---:|---:|
| Super Eco | 20 | **35** | +75 % |
| Eco | 40 | **55** | +38 % |
| Normal | 55 | **75** | +36 % |
| Presentation | 76 | **90** | +18 % |

Those are percentages of the LED driver's own maximum. Which leads to the combination worth
knowing about — **turned-up Normal sits at 75, and Philips' Presentation sits at 76.** Near
enough the same light on the wall, cooled as Normal rather than as Presentation:

| What you're running | Light | Fan |
|---|---:|---:|
| Philips, Presentation | 76 | **59–70 %, surging** |
| FanLab, Presentation, drive off | 76 | **38 %, steady** |
| **FanLab, Normal, drive on** | **75** | **37–39 %, steady** |
| FanLab, Presentation, drive on | **90** | 47–51 %, steady |

Read the bottom two rows together. You can have Philips' Presentation brightness for roughly
half the fan. Or you can have a picture **brighter than the projector will otherwise give
you** — drive 90 is 18 % past Philips' maximum — and still run quieter than Philips does at
its dimmest Presentation setting.

**What it costs.** More light is more heat, and the quiet modes pay for it. The number that
matters is the room temperature at which each mode stops sitting at a silent 30 %:

| Brightness mode | Drive off | Drive on |
|---|---:|---:|
| **Normal** | 27.3 °C | **19.2 °C** |
| Eco | 31.1 °C | 24.8 °C |
| Super Eco | 36.5 °C | 30.3 °C |

So with the drive turned up, Normal and Eco are off the silent floor in any room you'd
actually sit in. Normal goes from 30 % to about 37 %. Super Eco stays silent.

It's brighter and it's louder. Try it both ways and keep whichever you prefer — turning it
off puts everything back.

> **About the temperatures in these tables.** They're the air the projector draws in, which
> measured about 4.7 °C warmer than the room. So 24 °C here is roughly a 19–20 °C room. That's
> one day's measurement, so treat the conversion as rough.

## The presets

Four of them, quietest first. Each step up costs about 3 % more fan and buys about 1 °C.

| Preset | Presentation | Normal | Eco | Super Eco | Stays under 55 °C up to |
|---|---:|---:|---:|---:|---|
| *Philips* | *59 → 70 %* | *48 %* | *43 %* | *43 %* | — |
| **Quiet** (default) | **38 %** | 30 % | 30 % | 30 % | 28.0 °C |
| Balanced | 41 % | 30 % | 30 % | 30 % | 30.0 °C |
| Cool | 43 % | 30 % | 30 % | 30 % | 31.2 °C |
| Cold | 46 % | 30 %* | 30 % | 30 % | 32.6 °C |

*Cold runs Normal at 32–39 % in a room above about 23 °C. It's the one preset that does.

Only Presentation really changes between presets. The other three modes sit at 30 % whatever
you pick, because down there the light engine is already cool enough that more fan buys
almost nothing — it'd just be noise for nothing.

**Start on Quiet.** Move up only if your room is warm enough that you'd rather have it cooler
than quieter. Turn the LED drive on and you get `Bright Quiet` through `Bright Cold` instead,
which are the same four steps redrawn for the extra heat.

## Is it safe?

Short answer: yes. Here's why, rather than just an assurance.

**The projector's own protections still work.** There's a 75 °C cut-out and a separate
watchdog that reacts if the fan ever stalls. FanLab doesn't touch either and couldn't disable
them if it tried. With FanLab running, the light engine sits around 52–55 °C, so there's a
wide margin.

**If anything breaks, the fan goes to maximum.** Every failure path was written that way on
purpose. Sensor stops responding, app crashes, you force-stop it — the fan goes to 83 % and
Philips' controller gets handed back. A quiet fan is never the fallback.

**Nothing permanent changes.** No firmware, no system partition, no bootloader.

**The honest cost.** It runs the light engine warmer than Philips does — about 52 °C where
stock holds 44–46 °C. What you can expect from that is a small, reversible loss of red LED
output and a slightly cooler white point. The 55 °C ceiling is a judgement call: the DMD's
temperature genuinely can't be measured on this hardware, so the line was drawn where the
stock controller itself demands maximum fan.

**One thing can catch you out.** Taking over the fan sets a hidden system setting telling
Philips' controller to stand down, and that setting survives uninstalling the app. Delete
FanLab without pressing **RESTORE STOCK FAN CONTROL** first and nothing is left watching the
temperature. See [Removing it](#removing-it) — it's one button, and it's only a problem if
you don't know about it.

This was all worked out on one projector, by measuring it. If yours behaves differently,
[docs/safety.md](docs/safety.md) is the complete and unflattering version of this section.
Not endorsed by or affiliated with Philips or Screeneo Innovation SA.

## Installing it

You need a U4 on firmware 1.7.0 and a USB stick. No PC, no cable, no adb, no unlocking
anything.

1. Download **`fanlab-system.apk`** from [the latest release](../../releases/latest).
2. Copy it onto a USB stick and plug the stick into the projector.
3. On the projector, open **AppInstaller** from the launcher and pick that file.
4. Open **FanLab** from the launcher.
5. Change **Mode** from `OFF` to `CURVE`.

That's it. Choosing `CURVE` hands the fan over for you — there's a **Take over** button, but
you don't need it.

The fan takes about fifteen seconds to come down from wherever Philips had it. Then it should
stay put. Leave it in Presentation for ten minutes and watch the number on screen — if it's
working, it won't move.

> **There are two files and you want the first.** `fanlab-system.apk` is the real one.
> `fanlab-plain.apk` can only watch and log — it can't control the fan, and exists for
> development. Install the wrong one and nothing bad happens, it just won't do anything.

## Using it

The main screen is a list. Move up and down with the remote, change a setting with left and
right, press OK on a button.

Most of it you can ignore. Three things matter:

- **Mode** — set it to `CURVE` and leave it.
- **Curve preset** — start on `Quiet`.
- **Start automatically after a reboot** — leave it on, or Philips' controller takes back
  over next time you switch on.

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
| **LED drive** | Off by default. See [Want it brighter too?](#want-it-brighter-too) It only applies while FanLab is actually driving the fan, so brightness can never outrun cooling |
| **RELEASE CONTROL** | Hands the fan back for now |
| **RESTORE STOCK FAN CONTROL** | The permanent undo. Press this before uninstalling |

</details>

## Removing it

Two buttons, and they do different things. This is the one part worth reading carefully.

**To stop it driving the fan for now** — press **RELEASE CONTROL**, or set Mode to `OFF`.
Philips' controller takes over again straight away. Expect a few seconds of loud fan; it goes
to 83 % during the handover on purpose, so nothing is ever left unmanaged in between.

**To uninstall it properly:**

1. Press **RESTORE STOCK FAN CONTROL** in the app.
2. Then uninstall FanLab from Settings, like any other app.

In that order. Taking over the fan tells Philips' controller to stand down, and that
instruction lives outside the app. FanLab puts it back whenever it gets the chance — when you
stop it, change mode, force-stop it, even if it crashes. But uninstalling doesn't run any of
the app's code, so it can't put it back on the way out.

| How you stop it | Philips' controller handed back? |
|---|---|
| Mode → OFF, RELEASE CONTROL, force-stop, crash, reboot | yes |
| Uninstalling without pressing RESTORE first | **no** |

**Already uninstalled without pressing it?** Don't panic. The 75 °C cut-out and the fan-stall
watchdog are both still there, and the fan sits at the projector's own default speed rather
than stopping — but nothing is responding to temperature. Reinstall FanLab, press **RESTORE
STOCK FAN CONTROL**, and you're back to normal.

## When something looks wrong

**The fan is loud and won't come down.** Check Mode is `CURVE`, not `OFF`. If you've just
switched it on, give it fifteen seconds.

**The fan jumps every time I change brightness.** That's the projector slamming a fan speed
in, and FanLab puts it back within a second. Check **Re-assert every second** is on.

**The picture went back to normal brightness on its own.** The LED drive has a cut-out at
60 °C and stays off until you change brightness mode or a setting. Your room is probably
warmer than usual. Accept it, pick a cooler preset, or turn the drive off.

**Nothing seems to have happened.** Check you installed `fanlab-system.apk` and not
`fanlab-plain.apk`.

**The projector shut down.** That's the 75 °C cut-out, and it means something is physically
wrong — blocked vent, failing fan, very hot room. FanLab can't disable that cut-out. Check
the vents are clear before anything else.

## How it works

Every temperature has a fan speed. That's the whole controller.

```
  fan %
   83 |                                                            #########
   68 |                                                     #######
   50 |                                        #############
   40 |                          ##############
   38 |                    ######
   30 |####################
      +--------------------------------------------------------------------
       38       43      47    51    55        60         66        70
                            LED thermistor, C
       |____ floor ____|    |shelf|
```

It's a loop — the fan changes the temperature, and the temperature changes the fan. The
machine slides along that line until it finds the one point where both are true at once.
Nobody picked 38 %. That's just where the projector's thermal response crosses the curve.

The two flat parts are the trick. Flat at 30 % below 47 °C, which is where Normal, Eco and
Super Eco all live. Nearly flat from 38 % to 40 % across 51–55 °C, which is where
Presentation lives. A flat region has no boundary to park on, so five degrees of room drift
move the fan by two points instead of slamming it between two speeds.

**Want the rest?** [docs/internals.md](docs/internals.md) is the engineering — the LED drive
override, the SoC guard, telemetry, LINEAR mode, how the presets are built, what the
reverse-engineering turned up and how the tests work.

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

## Building it

```powershell
cd app
.\build.ps1 -Clean
```

`aapt2 → javac → d8 → apksigner`, driven by one PowerShell script. No Gradle. Set
`ANDROID_SDK_ROOT` and `JAVA_HOME` if they aren't in the default Windows locations. The host
test suite runs as part of every build — 2989 assertions, and the build refuses to produce an
APK if any of them fail.

**Signing keys aren't included.** The system build needs the AOSP platform key, because
that's what this firmware happens to be signed with. Supply your own as `app/keys/platform.pk8`
and `app/keys/platform.x509.pem`. The build makes its own debug keystore for the plain
variant.

## What's in here

| | |
|---|---|
| `app/` | the application — source, manifests, resources, host tests, build script |
| `tools/` | measurement and deployment tooling |
| `docs/` | how it works, safety, curve derivation, findings, and notes for working on the code |
| `release/` | the signed APKs |
| `final_curve.txt` | the deployed curve, in the app's own encoding |

## Licence

MIT — see [LICENSE](LICENSE).
