package com.daleygames.fanlab;

/**
 * A plain-text guide written beside the logs, so that someone (or an AI) handed a log with no
 * other context can read it: what the app is doing, what each column means, and what each note
 * the app writes means. Kept here, next to nothing else, so it is updated when the notes are.
 */
public final class LogGuide {

    public static final String NAME = "READ-ME-fanlab-logs.txt";

    private LogGuide() {
    }

    public static String text(String appVersion) {
        StringBuilder s = new StringBuilder();
        s.append("FANLAB LOG GUIDE (written by FanLab ").append(appVersion == null ? "?" : appVersion).append(")\n");
        s.append("\n");
        s.append("WHAT THIS IS\n");
        s.append("FanLab is an Android app on a Philips Screeneo U4 projector (SCN350). It replaces the\n");
        s.append("projector's own fan controller, and can drive the LEDs harder for a brighter picture.\n");
        s.append("fanlab.csv is its telemetry: about one row every 10 seconds, and an extra row whenever\n");
        s.append("something happens. fanlab-<number>.csv files are older rows of the same log.\n");
        s.append("sweep_*.json/.txt and trace_*.csv come from the Sweep measurement; verify_* from Hold.\n");
        s.append("\n");
        s.append("THE GOAL THE APP IS PURSUING\n");
        s.append("Keep the LED thermistor at or under a target temperature with the slowest (quietest) fan.\n");
        s.append("The owner notices the fan at about 35 %, accepts under 40 %, and tolerates 45 %.\n");
        s.append("\n");
        s.append("FAN MODES (the 'mode' column plus the notes say which is running)\n");
        s.append("  Smart quiet   mode=CURVE, notes start 'auto'. Holds a target per brightness mode\n");
        s.append("                (defaults: Super Eco 50, Night 55, Normal 58, Presentation 60 C; at\n");
        s.append("                Philips' brightness never above 55). Steps the fan one point at a time.\n");
        s.append("                Has a fan limit (default 40 %): at the limit it dims the picture instead.\n");
        s.append("  Curve to 55/58  mode=CURVE, notes 'autotrim'. A fixed curve of fan against temperature.\n");
        s.append("  Manual        mode=MANUAL. One fixed speed. Ends itself after 30 min or at 62 C.\n");
        s.append("  Stock         mode=OFF. Philips' controller has the fan; the app only watches.\n");
        s.append("\n");
        s.append("COLUMNS\n");
        s.append("  epoch_ms, iso_local   time of the row (Unix ms, and local time)\n");
        s.append("  adc                   raw LED thermistor reading\n");
        s.append("  degC                  LED temperature in C, computed from adc. THE controlled value.\n");
        s.append("  prop_led_temp         the projector firmware's own idea of the LED temperature\n");
        s.append("  fan_ctrl              fan duty in percent, read back from the hardware\n");
        s.append("  rgblevel              brightness mode: 3 Presentation, 2 Normal, 1 Night (Eco), 4 Super Eco\n");
        s.append("  led_status            1 light engine on, 0 off (standby)\n");
        s.append("  profile               curve group for the mode; 'Eco/Super Eco' covers rgblevel 1 and 4\n");
        s.append("  mode                  OFF, MANUAL, CURVE or LINEAR (see FAN MODES)\n");
        s.append("  desired               fan duty the controller wants this row\n");
        s.append("  wrote                 fan duty written this row; blank if nothing was written\n");
        s.append("  note                  events, space-separated; see NOTES below\n");
        s.append("  soc_pll_c, soc_ddr_c, soc_sar_c   chip temperatures in C; the chip throttles at 75\n");
        s.append("  thr_*                 chip throttling state; anything above 0 means throttling now\n");
        s.append("  session               increases each time the app's service starts\n");
        s.append("  off_s                 seconds the light engine was off before this power-on; blank otherwise\n");
        s.append("  room_c                room temperature the owner typed in; blank if not stated\n");
        s.append("  exclusive             1 if only this app wrote the fan this row, 0 if something else did\n");
        s.append("  catchup               1 while the controller is still converging, 0 once settled\n");
        s.append("  duty_hold_s           seconds since the commanded fan duty last changed\n");
        s.append("  led_drive             LED drive percent the app applied for the CURRENT mode only; blank\n");
        s.append("                        means Philips' own level (Super Eco 20, Night 40, Normal 55,\n");
        s.append("                        Presentation 76). Lower than the owner's setting = dimmed by the limit.\n");
        s.append("\n");
        s.append("NOTES (the 'note' column)\n");
        s.append("  fanlab <version> ...             first row of a session: app version and fan mode\n");
        s.append("  brightness A/B/C/D (...), boost on|off, targets ..., limit N%, floor N%\n");
        s.append("                                   the owner's settings: LED drive per mode (Super Eco/Night/\n");
        s.append("                                   Normal/Presentation), Smart quiet targets, fan limit, floor.\n");
        s.append("                                   'brightness set ...' = they were just changed.\n");
        s.append("  resync@N:<why>                   controller adopted the fan speed N (service start, settings)\n");
        s.append("  settings changed                 a setting was changed in the app\n");
        s.append("  poweron off=Ns(standby)          light engine came on after N seconds off\n");
        s.append("  leddrive<-A/B@L                  app set LED drive A (red channel B) in rgblevel L\n");
        s.append("  leddrive->stock                  app put Philips' own LED drive back\n");
        s.append("  leddrive rewrite(...)            the projector changed the drive; the app put it back\n");
        s.append("  LEDDRIVE TRIP ...                LED passed the 65 C cut-out; brighter picture switched off\n");
        s.append("  auto guide X->Y to N (...)       brightness mode changed; fan is ramping to N, the speed\n");
        s.append("                                   learned for Y, or X's speed plus a learned gap\n");
        s.append("  auto guide X->Y (nothing learned yet)   mode changed, no learned speed to ramp to\n");
        s.append("  auto settled Mode@N              fan unchanged 10 min in that mode: N is learned for it\n");
        s.append("  auto probe A->B started at T     trying a lower fan speed to find the slowest that holds\n");
        s.append("  auto probe B holds, settles about T (X C a point)   it holds; X = degrees per fan point\n");
        s.append("  auto probe B would settle at T, back to C   predicted too warm; fan raised again\n");
        s.append("  auto probe B ran warm, back to C LED passed the target before the check; fan raised again\n");
        s.append("  auto forgot learned speeds       brightness or targets changed, so learning restarted\n");
        s.append("  smart saved s1,...               what Smart quiet has learned, saved to the app's settings\n");
        s.append("  smart restored s1,...            that learning loaded at start\n");
        s.append("  limit N: brightness -K           at the fan limit: picture dimmed K points below the setting\n");
        s.append("  limit N: brightness back to full dimming undone\n");
        s.append("  limit N lifted at T C            LED too warm for the limit; fan allowed above it (safety)\n");
        s.append("  limit N back                     the limit applies again\n");
        s.append("  autotrim +K                      Curve mode added K points to stay under its target\n");
        s.append("  socguard +K@T / socguard released   extra fan because the chip is hot / no longer needed\n");
        s.append("  reassert(was N)                  something else changed the fan to N; the app put it back\n");
        s.append("  manual test ended: ...           Manual ended itself (30 min, or LED at 62 C)\n");
        s.append("  plan start / plan step K ... / plan finished / plan closed ...   the unattended test\n");
        s.append("  plan ignored key=...             a stray key press during the unattended test, ignored\n");
        s.append("  release->83 / handback->83       fan handed back to Philips via 83 %\n");
        s.append("  FAILSAFE / badtemp xN            unreadable temperature; fan forced to 83 %\n");
        s.append("  WRITE FAILED                     the fan could not be written\n");
        s.append("  media_mounted, export->Nf/Mr     USB stick inserted; N files / M rows copied to it\n");
        s.append("  logs on the projector wiped      the owner deleted the logs stored on the projector\n");
        s.append("  applied sweep advice QUIET|COOL  the owner applied settings recommended by a sweep; the next\n");
        s.append("                                   'brightness set ...' note shows what they were\n");
        s.append("  dlpc_read=...                    periodic read of the display chip's settings; ignore\n");
        s.append("\n");
        s.append("HOW TO READ IT\n");
        s.append("  - Steady running: fan_ctrl flat, degC within about 1.5 C under the mode's target.\n");
        s.append("  - After a switch to a brighter mode degC jumps about 4 C within seconds and falls back\n");
        s.append("    over a few minutes. That is expected and the fan cannot prevent it.\n");
        s.append("  - A fan speed means nothing without the brightness it was measured at: check led_drive\n");
        s.append("    and the latest 'brightness ...' note before comparing runs.\n");
        s.append("  - Room temperature moves the fan needed by roughly 2 points per degree C.\n");
        s.append("  - Limits: brighter picture cuts out at 65 C; the projector itself shuts down at 75 C.\n");
        s.append("  - Problems to look for: FAILSAFE, WRITE FAILED, LEDDRIVE TRIP, thr_* above 0, the fan\n");
        s.append("    stepping up and down repeatedly, or degC staying over its target for minutes.\n");
        return s.toString();
    }
}
