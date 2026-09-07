# Deploying the curve, and undoing it

Everything here is one adb shell away. The old route — sideload, then thirty D-pad entries
on the remote — still works and is written up in `PROCEDURE.md`, but there is no reason to
use it now.

## The short version

```bash
cd tools
bash deploy.sh status                                  # what is driving the fan now
bash deploy.sh apply "$(cat final_curve.txt)"          # take over
bash watch.sh 10                                       # prove it is steady
bash deploy.sh revert                                  # give it back to Philips
```

## Installing without a PC — VERIFIED

The projector ships its own installer, and it works for this APK:

1. copy `fanlab-system.apk` onto a USB stick
2. plug it into the projector
3. open **AppInstaller** from the launcher (`com.droidlogic.appinstall`, already visible)
4. pick the file, confirm
5. open FanLab and press the takeover button

No adb, no PC, no firmware. `install_non_market_apps` is already 1 on this device, and
`sc_filemanager` is launcher-visible as a fallback browser.

Confirmed 2026-09-05: installed via `com.android.packageinstaller`, came back with
`userId=1000 / android.uid.system`, and the privilege was then exercised (toggling the
mode moved `persist.sys.fanctrl.by.temperatue`, which only a system app can write). The
adb route below remains the right one for development and for anything scripted.

## Which build, and why it matters

Deploy **`com.daleygames.fanlab.system`** (the platform-signed build). Not a preference —
a requirement, for one reason:

`persist.sys.fanctrl.by.temperatue=0` is what stops the stock fan ladder writing. It lives
in `/data`, so it survives a reboot, a force-stop and an uninstall. Something has to be
able to set it back, and **only the system build can** — a `persist.sys.*` property is
`system_prop`, which an ordinary app may not write. Deploy the plain build and you can
disable the stock controller by hand from a shell but the app can never re-arm it, which
is precisely the state `TARGET.md` says never to create.

`FanService.syncStockLadder` therefore ties the switch to the service's own state:

| service state | kill switch | autostart |
|---|---|---|
| **CURVE**, or a sweep/hold session | 0 — stock ladder off | forced **on**, so a reboot brings the driver back |
| **MANUAL** | **1 — ladder stays armed** | left alone |
| OFF | 1 — stock ladder armed | left alone |

**MANUAL deliberately does not disable the ladder.** It has no temperature logic at all —
not even the three-bad-reads fail-safe CURVE has — so standing the stock controller down
for a duty someone typed by hand would remove the only thing supervising it. With the
ladder armed, a silly value is corrected within one 15 s poll, and the audition still works
because re-assertion rewrites the node every second and wins in between. That is what
re-assertion was built for.

It re-checks on every settings change and every 30 ticks, so it also notices if something
else flips the property underneath it. `deploy.sh` deliberately does **not** set the
property itself: it waits for the service to do it, because a service that cannot disable
the ladder cannot re-arm it either, and it is better to discover that during deployment
than after the fan has been left unmanaged.

The plain build is left installed on purpose — it is the zero-commitment measuring
instrument — but it will not touch the switch, so the two cannot fight over it.

## Verifying, rather than hoping

`deploy.sh apply` refuses to leave anything half-done. In order it:

1. loads the curve and **diffs what came back against what went in** — a mismatch means
   `sanitise()` repaired it, which is a bug in the curve, not something to shrug at;
2. puts the service into CURVE;
3. **waits for the service to disable the stock ladder**, and aborts back to mode OFF if
   it cannot;
4. checks autostart is on, so a reboot cannot strand the fan;
5. writes a foreign value (77) to `fan_ctrl` and confirms the app **pulls it back** —
   a static reading proves the app *claims* to be driving, this proves it *is*.

`watch.sh 10` then samples for ten minutes and reports the number of duty **changes**,
not a mean and standard deviation. A fan sitting at 47 is inaudible in a way that a fan
alternating 45/49 is not, and a summary statistic rates them the same.

## When the stock controller is *not* handed back

Three of these are covered automatically. Three are not, and they matter because the
setting persists across a reboot.

| how the app stops | ladder handed back? | why |
|---|---|---|
| normal stop, RESTORE, `deploy.sh revert` | **yes** | the service runs its handback |
| crash, low-memory kill | **yes** | START_STICKY + foreground → the platform restarts it |
| reboot | **yes** | autostart, which the service forces on while it is driving |
| **force-stop, or "Disable" in Settings** | **no** | cancels the restart *and* puts the package in the stopped state, which suppresses `BOOT_COMPLETED` too — so neither recovery path fires |
| **`pm clear`** | **no** | as above, and it also erases `autostart`, so nothing on the device remembers |
| **uninstall** | **no** | runs no code, never returns |

Note this only bites in **CURVE** mode. MANUAL deliberately leaves the stock ladder armed
(see `FanService.drivesUnattended`), so a hand-typed duty is always supervised.

So: **revert before uninstalling, force-stopping or clearing data.**

```bash
bash deploy.sh revert
```

If it has already happened, the projector is not in danger — the 75 °C shutdown and the
kernel fan-stall watchdog are both outside userspace, and the fan sits at the kernel's own
default rather than at zero. But nothing is responding to temperature. One line fixes it:

```bash
adb shell setprop persist.sys.fanctrl.by.temperatue 1
```

## Logging

`deploy.sh apply` sets `logging false`. Run 1 wrote about **400 KB/hour** and `CsvLogger`
does not rotate, so leaving telemetry on for a permanently-running controller grows without
bound. Turn it back on only while tuning:

```bash
adb shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver \
    -a com.daleygames.fanlab.CONFIG --ez logging true
```

## The CAIC experiment

Not part of the deployment; off by default, and `deploy.sh` never touches it. Documented
here because it goes over the same broadcast.

```bash
# ask the display controller to run Content Adaptive Illumination Control
adb shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver \
    -a com.daleygames.fanlab.CONFIG --ez caic true

# a few seconds later: the reply's caic= field says what the controller itself reported
adb shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver \
    -a com.daleygames.fanlab.CONFIG

# undo -- or power-cycle the projector, which undoes it regardless
adb shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver \
    -a com.daleygames.fanlab.CONFIG --ez caic false
```

`caic=` reads `off`, `on (not written yet)` for the tick before the service acts,
`on (unverified)` once `w 50 1 1` has gone to `picoreg`, and `on (read back: on)` or
`on (read back: off)` once the controller's own answer has come back through the kernel
log. Only the system build gets a read-back — the plain build has no `READ_LOGS` — so on
the plain build `unverified` is permanent and correct. `--ez reset` turns it off along with
the curve. What CAIC is, and why on this board it is an experiment rather than a feature,
is in the README under *The CAIC experiment* and in [safety.md](safety.md).

**The broadcast is not the on-screen control, and the difference matters.** The row in the
app arms CAIC: it writes the register, counts down for 15 seconds, and reverts unless
someone presses OK — because a display-controller change that goes wrong takes away the
screen you would need to undo it. `--ez caic true` sets the stored preference directly, with
no countdown, on the reasonable assumption that anyone with a shell has another way in.

### If the picture is gone

Three escapes, none of which needs a readable screen:

1. **Wait 15 seconds.** Only if it was armed from the app: the countdown belongs to
   `FanService`, not to the activity, so force-stopping the app or navigating away does not
   stop the revert. An unconfirmed CAIC is never persisted, so it cannot come back after a
   reboot either.
2. **Power-cycle the projector.** The register is runtime-only. The factory `picosetting`
   blob is never written by this app, and it has CAIC off, so the controller reloads it at
   every boot. This is the escape that always works, which is exactly why an unconfirmed
   setting must never survive one.
3. **`--ez caic false`**, the third command in the block above. This is the one for a CAIC
   that was confirmed and stored, and is only now showing a problem — it clears the setting
   as well as the register, so the next start does not put it back.

## The LED drive override

Also not part of the deployment, also off by default, and also over the same broadcast. It
drives the light engine above the per-mode table the kernel installs.

```bash
# the Bright preset -- 30/50/70/90 instead of the stock 20/40/55/76 -- and switch it on
adb shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver \
    -a com.daleygames.fanlab.CONFIG --es leddrive bright --ez leddriveon true

# hand-set levels, Super Eco / Eco / Normal / Presentation
adb shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver \
    -a com.daleygames.fanlab.CONFIG --es leddrive "d1,30,50,70,90"

# off, and back to the kernel's own table
adb shell am broadcast -n com.daleygames.fanlab.system/com.daleygames.fanlab.ConfigReceiver \
    -a com.daleygames.fanlab.CONFIG --es leddrive stock --ez leddriveon false
```

`leddrive` is applied before `leddriveon`, so one command can set the table and switch it
on. The reply carries three fields, and they answer three different questions:

- `leddrive=` the stored table,
- `leddriveon=` the switch,
- `leddrivestate=` **what is actually on the hardware right now** — `applied 90/84`,
  `held off: tripped at 57.2 C`, or `stock:` and the reason.

The third is the one to read. The override applies **only** while the app is the fan
controller — CURVE or LINEAR, no session, light engine on, fail-safe clear, display awake —
so `leddriveon=true leddrivestate=stock: this app is not the fan controller` is a normal and
correct answer, not a bug. [safety.md](safety.md) has why that coupling is not negotiable.

Two clamps worth knowing before typing a number:

- **Levels are held to 97, not 100.** The driver converts a percent to a 7-bit DAC code as
  `code = (30·mA + 40000)/1968` and clamps it with `if (code > 0x7F) code = 0x3F` — an
  over-request does not saturate, it drops that channel to about 2.8 A, roughly 40 %. So
  asking for 100 would make the picture go *dim*. The reply says `leddrive(REPAIRED)` when
  it has clamped something.
- **Any read-back field above 100 is a failed SPI read**, not a drive level: `0x8080` comes
  back, the driver computes `percent = -18`, and sysfs prints it as an unsigned byte, which
  is the 238 and 241 seen in the logs.

Switching the override on while LINEAR's ceiling is still the untouched 52.0 raises it to
54.0 — the reply says so — because holding 52.0 under the extra heat costs duty 50 in a
24 °C room where it rests at 38 today. A hand-set ceiling is left alone, nothing is written
to the stored value, and switching the override off puts it back. Those numbers are
**inferred from the ×1.18 scaling, not measured**.

## Changing the curve later

Edit `final_curve.txt`, check it before you ship it, then re-apply:

```bash
python equilibria.py --curve "$(cat final_curve.txt)"     # one stable point per mode?
bash deploy.sh apply "$(cat final_curve.txt)"
```

`equilibria.py` is the check worth not skipping. `solve_curve.py` iterates to *a* fixed
point and will happily report one while a second exists — and two stable points with
nowhere to rest between them is a fan that hunts, which is the bug this whole project
exists to remove.
