#!/usr/bin/env bash
#
# test_coupling.sh -- prove that the kill switch is owned by the service, not by whoever
# last typed a command.
#
# The defect this guards against: persist.sys.fanctrl.by.temperatue=0 disables the stock
# fan ladder and lives in /data, so it survives reboot, force-stop and uninstall. Set by
# hand, it can leave the projector with no fan controller at all -- permanently, silently,
# and across power cycles. FanService.syncStockLadder is supposed to make that
# unreachable by tying the switch to whether the service is actually driving.
#
# "Supposed to" is the problem. This checks it on the hardware, including the case that
# actually bites: the switch left at 0 by something else while the app is NOT driving.
#
#   ./test_coupling.sh [package]        default: the system build

set -u
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"
PKG="${1:-com.daleygames.fanlab.system}"
KILL="persist.sys.fanctrl.by.temperatue"
RECV="$PKG/com.daleygames.fanlab.ConfigReceiver"

pass=0; fail=0
kill_is() { "$ADB" shell "getprop $KILL" | tr -d '\r'; }
cfg() { "$ADB" shell "am broadcast -n $RECV -a com.daleygames.fanlab.CONFIG $*" 2>&1 \
        | sed -n 's/.*data="\(.*\)".*/\1/p' | tr -d '\r'; }

check() {  # check <what> <expected> <actual>
    if [ "$2" = "$3" ]; then
        echo "  PASS  $1 = $3"; pass=$((pass+1))
    else
        echo "  FAIL  $1: expected '$2', got '$3'"; fail=$((fail+1))
    fi
}

echo "testing $PKG"
echo

echo "1. not driving -> the stock ladder must be armed"
cfg "--ei mode 0" >/dev/null
sleep 35          # the periodic re-check runs every 30 ticks
check "kill switch" "1" "$(kill_is)"

echo
echo "2. THE DANGEROUS CASE: switch forced to 0 behind the app's back while it is idle."
echo "   The app must notice and hand the fan back on its own."
"$ADB" shell "setprop $KILL 0" >/dev/null
check "forced to 0" "0" "$(kill_is)"
sleep 35
check "app re-armed it unprompted" "1" "$(kill_is)"

echo
echo "3. driving -> the ladder must be disabled, and autostart forced on so a reboot"
echo "   brings the driver back rather than leaving the fan unmanaged"
out=$(cfg "--ei mode 2")
sleep 3
check "mode" "CURVE" "$(echo "$out" | sed -n 's/.*mode=\([A-Z]*\).*/\1/p')"
check "kill switch" "0" "$(kill_is)"
check "autostart" "true" "$(cfg | sed -n 's/.*autostart=\([a-z]*\).*/\1/p')"

echo
echo "4. back to not driving -> the ladder must come back"
cfg "--ei mode 0" >/dev/null
sleep 3
check "kill switch" "1" "$(kill_is)"

echo
echo "$pass passed, $fail failed"
if [ "$fail" -ne 0 ]; then
    echo
    echo "A failure here means the projector can be left with no fan controller."
    echo "Re-arm it by hand before walking away:  adb shell setprop $KILL 1"
    exit 1
fi
