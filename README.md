# FanLab

**A quieter fan and a brighter picture for the Philips Screeneo U4 projector (SCN350).**

It's an Android app, installed from a USB stick. Nothing is flashed or rooted, and it
uninstalls from Settings. Run on firmware 1.7.0 and 1.7.1.

## Install

**[`fanlab-system.apk` — latest release →](../../releases/latest)**

1. Put the APK on the root of a USB stick and plug it into the projector.
2. Open **Settings** and press **OK** on the software version five times. The hidden developer
   menu opens.
3. Open **AppInstaller**, near the bottom, and install the APK.
4. Open **FanLab**. It starts in **Smart quiet** and takes over the fan.

## The options

### Fan mode

| Mode | What it does |
|---|---|
| **Smart quiet** (default) | Keeps the fan at or under your **Fan limit**. If the LED gets too warm at the limit, the picture dims slightly instead of the fan getting louder. It learns your projector as it runs, so it settles faster over time |
| **Curve to 55 °C** | A simple fan curve that keeps the LED under 55 °C |
| **Curve to 58 °C** | The same curve, allowed to 58 °C. Offered when Brightness boost is on |

### Fan limit

Smart quiet only. The loudest the fan may go: **40 %** unless you change it, 30–83, or Off.

One exception, for safety: if the LED is 3 °C over its target, or there is nothing left to
dim, the fan goes above the limit until it cools. The picture is never dimmed below Philips'
own brightness.

### Brightness boost

Off is Philips' own brightness. On runs each brightness mode harder:

| Brightness mode | Philips | Boost default | For an even quieter fan |
|---|---:|---:|---:|
| Super Eco | 20 | 45 | 30 |
| Night | 40 | 65 | 50 |
| Normal | 55 | 85 | 65 |
| Presentation | 76 | 95 | 85 |

Each level can be set from 20 to 97 under Advanced. More brightness means more heat, so more
fan. Above 65 °C the boost switches itself off until you change brightness mode or a setting.

### Start on boot

Leave it on, or Philips' controller takes the fan back at the next power-on.

### Advanced

| Setting | What it does |
|---|---|
| **Smart quiet targets** | The LED temperature Smart quiet holds in each brightness mode: Super Eco 50 °C, Night 55 °C, Normal 58 °C, Presentation 60 °C. Each can be set from 45 to 60. Lower is kinder to the LEDs but needs more fan, or a dimmer picture at the limit |
| **Fan floor** | The slowest the fan runs: 30 % unless you change it, 20–40. Below 30 is untested. The curves never go below 25 |
| **Brightness (LED drive)** | The boost level for each brightness mode, and a reset for brightness and targets |
| **Tests** | White screen (full load), colour slides, a focus pattern, a manual fan test (20–100 %, ends by itself after 30 minutes or at 62 °C), and an unattended test |
| **Measure** | Sweep every fan speed, hold one fan speed, and a room temperature for the log |
| **Logs** | Write the CSV log, copy it to shared storage or a USB stick, and raw diagnostics |
| **ADB over USB** | Untested on this projector |
| **Restore stock controller** | Gives the fan back to Philips. Press this before uninstalling |

## Removing it

1. Press **Advanced → Restore stock controller**.
2. Uninstall FanLab from Settings.

Do it in that order. Uninstalling first leaves Philips' controller switched off; if that
happens, reinstall FanLab and press Restore.

## Safety

- The projector's 75 °C shutdown and its fan-stall watchdog are untouched.
- Any failure (bad sensor, crash, force-stop) sends the fan to 83 % and hands it back to
  Philips.
- Smart quiet and Curve to 58 °C run the LEDs warmer than Philips does, and the boost drives
  them harder. Both cost some LED life, by an amount nobody has measured for this projector.

Worked out on one projector. The full account is in [docs/safety.md](docs/safety.md). Not
endorsed by or affiliated with Philips or Screeneo Innovation SA.

## More

How it works, the measurements, and building it from source: [docs/](docs/). Parts of the
docs describe the curve presets from versions before 1.8.0.

MIT — see [LICENSE](LICENSE).
