#!/usr/bin/env bash
#
# watch.sh -- observe whatever is currently driving the fan, and judge whether it is
# actually steady. This is the acceptance test for "quiet, steadily".
#
#   ./watch.sh [minutes]        default 10
#
# Simulation said the duty would not move. This says whether it moved on the hardware,
# which is a different claim and the only one that counts. It reports the number of duty
# CHANGES rather than a plot, because a change is the thing you hear -- a fan sitting at
# 47 is inaudible in a way that a fan alternating 45/49 is not, even though the average
# is the same and a mean-and-stddev summary would rate them equally.

set -u
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"

MINS="${1:-10}"
SECS=$(( MINS * 60 ))
OUT="watch_$(date +%s).csv"

echo "watching for $MINS minutes -> $OUT"
echo "(change the brightness mode during this if you want to see the transition)"
echo

"$ADB" shell "
  end=\$(( \$(date +%s) + $SECS ))
  while [ \$(date +%s) -lt \$end ]; do
    echo \"\$(date +%s),\$(cat /sys/class/fan_int/fan_ctrl),\$(cat /sys/class/ledtemp/voltage),\$(cat /sys/class/dlpc343x/rgblevel),\$(cat /sys/class/dlpc343x/led_status)\"
    sleep 2
  done
" | tr -d '\r' > "$OUT"

python - "$OUT" <<'PYEOF'
import sys
from therm import celsius

rows = []
for line in open(sys.argv[1]):
    f = line.strip().split(",")
    if len(f) != 5:
        continue
    try:
        rows.append((int(f[0]), int(f[1]), int(f[2]), int(f[3]), int(f[4])))
    except ValueError:
        pass

if not rows:
    print("no samples -- is the device still connected?")
    sys.exit(1)

names = {1: "Eco", 2: "Normal", 3: "Presentation", 4: "SuperEco"}
duties = [r[1] for r in rows]
temps = [celsius(r[2]) for r in rows]
span = rows[-1][0] - rows[0][0]

changes = []
for i in range(1, len(rows)):
    if rows[i][1] != rows[i - 1][1]:
        changes.append((rows[i][0] - rows[0][0], rows[i - 1][1], rows[i][1],
                        names.get(rows[i][3], "?")))

print("samples      : %d over %d s" % (len(rows), span))
print("mode(s) seen : %s" % ", ".join(sorted({names.get(r[3], "?") for r in rows})))
print("duty         : %d..%d (mean %.1f)" % (min(duties), max(duties), sum(duties) / len(duties)))
print("LED temp     : %.2f..%.2f C" % (min(temps), max(temps)))
print("duty changes : %d" % len(changes))
for t, a, b, m in changes[:40]:
    print("    t+%-5ds  %2d -> %-2d  (%s)  step %+d" % (t, a, b, m, b - a))
if len(changes) > 40:
    print("    ... and %d more" % (len(changes) - 40))

print()
biggest = max((abs(b - a) for _, a, b, _ in changes), default=0)
if not changes:
    print("VERDICT: the fan did not move once. That is the target.")
elif biggest <= 1:
    print("VERDICT: %d changes, all single points. Single-point moves are inaudible." % len(changes))
else:
    print("VERDICT: largest single step was %d points." % biggest)
    print("  A step >1 point means the slew limiter was bypassed -- expected only on a")
    print("  brightness-mode change (FanCurve jumps immediately when a tier change asks")
    print("  for more cooling). If no mode change happened here, that needs explaining.")
PYEOF
