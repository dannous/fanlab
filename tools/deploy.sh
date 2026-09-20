#!/usr/bin/env bash
#
# deploy.sh -- hand the projector's fan to the FanLab curve, or hand it back to stock.
#
#   ./deploy.sh status                 what is driving the fan right now
#   ./deploy.sh apply  <curve> [pkg]   load the curve and take over
#   ./deploy.sh revert [pkg]           give the fan back to the stock controller
#
# Order is safety-critical in both directions. Taking over: load the curve -> start the app
# driving -> only then disable the stock ladder. Handing back: re-enable stock -> then stop
# the app. The reverse order leaves the fan unmanaged, and that state survives a reboot.

set -u
export MSYS_NO_PATHCONV=1

ADB="${ADB:-adb}"
RECV="com/daleygames/fanlab/ConfigReceiver"
KILL="persist.sys.fanctrl.by.temperatue"
DEFAULT_PKG="com.daleygames.fanlab.system"

MODE_OFF=0
MODE_CURVE=2

die() { echo "FATAL: $*" >&2; exit 1; }

cfg() {   # cfg <pkg> [extra am args...]
    local pkg="$1"; shift
    "$ADB" shell "am broadcast -n $pkg/${RECV//\//.} -a com.daleygames.fanlab.CONFIG $*" 2>&1 \
        | sed -n 's/.*data="\(.*\)".*/\1/p' | tr -d '\r'
}

field() { echo "$1" | sed -n "s/.*$2=\([^ ]*\).*/\1/p"; }

installed() { "$ADB" shell "pm list packages" 2>/dev/null | tr -d '\r' | grep -qx "package:$1"; }

status() {
    echo "kill switch ($KILL) = $("$ADB" shell "getprop $KILL" | tr -d '\r')"
    echo "  0 = stock ladder disabled (something else must be driving the fan)"
    echo "  1 = stock ladder in charge"
    echo
    echo "fan_ctrl  = $("$ADB" shell "cat /sys/class/fan_int/fan_ctrl" | tr -d '\r')"
    echo "rgblevel  = $("$ADB" shell "cat /sys/class/dlpc343x/rgblevel" | tr -d '\r')  (1 Eco 2 Normal 3 Presentation 4 SuperEco)"
    echo "led_status= $("$ADB" shell "cat /sys/class/dlpc343x/led_status" | tr -d '\r')"
    echo
    for p in com.daleygames.fanlab com.daleygames.fanlab.system; do
        if installed "$p"; then
            echo "== $p"
            echo "   $(cfg "$p")"
        else
            echo "== $p  (not installed)"
        fi
    done
    echo
    local driving
    driving=$(for p in com.daleygames.fanlab com.daleygames.fanlab.system; do
        installed "$p" && cfg "$p" | grep -q "mode=CURVE\|mode=MANUAL" && echo "$p"
    done)
    if [ -n "$driving" ]; then
        echo "DRIVING: $driving"
        [ "$(echo "$driving" | wc -l)" -gt 1 ] && echo "  WARNING: more than one app is driving the fan. Stop one."
    elif [ "$("$ADB" shell "getprop $KILL" | tr -d '\r')" = "0" ]; then
        echo "DRIVING: no app -- and the stock ladder is disabled."
        echo "  Either something outside the apps is driving the node (an adblab run"
        echo "  holds it this way on purpose), or the fan is unmanaged. If no experiment"
        echo "  is running, this is the state to get out of:  ./deploy.sh revert"
    else
        echo "DRIVING: the stock ladder"
    fi
}

apply() {
    local curve="$1"; local pkg="${2:-$DEFAULT_PKG}"
    installed "$pkg" || die "$pkg is not installed"

    # Refuse to start a second driver: two controllers fighting over one node is worse
    # than either alone.
    local other
    for other in com.daleygames.fanlab com.daleygames.fanlab.system; do
        [ "$other" = "$pkg" ] && continue
        installed "$other" || continue
        if cfg "$other" | grep -q "mode=CURVE\|mode=MANUAL"; then
            die "$other is already driving the fan. Stop it first: ./deploy.sh revert $other"
        fi
    done

    echo "1/4 loading curve into $pkg"
    local out; out=$(cfg "$pkg" "--es curve '$curve' --ez logging false --ez autostart true")
    echo "    $out"
    local back; back=$(field "$out" curve)
    [ "$back" = "$curve" ] || die "curve was altered on the way in.
      sent:   $curve
      stored: $back
    A mismatch means sanitise() repaired it -- fix the curve, do not proceed."

    echo "2/4 starting the app driving (stock ladder still armed underneath)"
    out=$(cfg "$pkg" "--ei mode $MODE_CURVE")
    [ "$(field "$out" mode)" = "CURVE" ] || die "mode did not stick: $out"

    echo "3/4 waiting for the service to disable the stock ladder itself"
    # Deliberately NOT setprop'd from here. FanService owns this switch, and proving it
    # can disable the ladder is what proves it can re-arm it on revert.
    local k=""
    local i=0
    while [ "$i" -lt 40 ]; do
        k=$("$ADB" shell "getprop $KILL" | tr -d '\r')
        [ "$k" = "0" ] && break
        sleep 1
        i=$((i + 1))
    done
    if [ "$k" != "0" ]; then
        cfg "$pkg" "--ei mode $MODE_OFF --ez autostart false" >/dev/null
        die "the service did not disable the stock ladder within ${i}s (reads '$k').
    That means it cannot manage the switch -- almost certainly the plain build, which
    has no permission to. Deploy com.daleygames.fanlab.system instead. The app has been
    put back to mode OFF, so nothing is left half-applied."
    fi
    echo "    done by the service after ${i}s (so it can undo it too)"
    local auto; auto=$(cfg "$pkg" | sed -n 's/.*autostart=\([a-z]*\).*/\1/p')
    [ "$auto" = "true" ] || die "autostart is '$auto'; a reboot would leave the fan unmanaged"

    echo "4/4 verifying the app is really driving the node"
    # Write a value the app did not choose and watch it be corrected: that proves the loop
    # is live. The probe must be far BELOW target -- the app takes fail-safe 83 on startup
    # then slews down 1 point per 8 s, so a value near 80 is indistinguishable from the ramp.
    local before; before=$("$ADB" shell "cat /sys/class/fan_int/fan_ctrl" | tr -d '\r')
    local probe=20
    "$ADB" shell "echo $probe > /sys/class/fan_int/fan_ctrl" >/dev/null
    sleep 4
    local after; after=$("$ADB" shell "cat /sys/class/fan_int/fan_ctrl" | tr -d '\r')
    if [ "$after" = "$probe" ]; then
        echo "    WARNING: fan_ctrl still reads $probe four seconds after a foreign write."
        echo "    The app is NOT re-asserting. Do not leave it like this:"
        echo "        ./deploy.sh revert $pkg"
        exit 1
    fi
    echo "    fan_ctrl was $before, forced to $probe, and the app pulled it back to $after -- driving confirmed."
    echo
    echo "Done. The curve is live and will come back after a reboot (autostart=true)."
    echo "To undo:  ./deploy.sh revert $pkg"
}

revert() {
    local pkg="${1:-$DEFAULT_PKG}"

    # Order matters: FanService re-asserts the kill switch on a timer while in a driving
    # mode, so setting the property first and stopping the app second would race and
    # silently do nothing. Stop the driving first and the service hands the ladder back.
    echo "1/3 stopping the app driving, and clearing autostart"
    for p in com.daleygames.fanlab com.daleygames.fanlab.system; do
        installed "$p" && cfg "$p" "--ei mode $MODE_OFF --ez autostart false" >/dev/null
    done

    echo "2/3 waiting for the service to re-arm the stock ladder"
    local k=""
    local i=0
    while [ "$i" -lt 40 ]; do
        k=$("$ADB" shell "getprop $KILL" | tr -d '\r')
        [ "$k" = "1" ] && break
        sleep 1
        i=$((i + 1))
    done
    if [ "$k" != "1" ]; then
        # An unmanaged fan is worth being blunt about: force it, but say so -- it means
        # the coupling is broken.
        echo "    the service did not re-arm it in ${i}s; forcing (its coupling may be broken)"
        "$ADB" shell "setprop $KILL 1" >/dev/null
        sleep 2
        k=$("$ADB" shell "getprop $KILL" | tr -d '\r')
        [ "$k" = "1" ] || die "could not re-arm the stock ladder (reads '$k').
    The projector is running with NO fan controller. Fix this before walking away:
        adb shell setprop $KILL 1"
    else
        echo "    the service re-armed it after ${i}s"
    fi

    echo "3/3 waiting for stock to re-impose its own duty"
    sleep 17
    echo "    fan_ctrl = $("$ADB" shell "cat /sys/class/fan_int/fan_ctrl" | tr -d '\r')  (stock's choice)"
    echo
    echo "Reverted. The projector is back on its original fan controller."
}

case "${1:-status}" in
    status) status ;;
    apply)  [ $# -ge 2 ] || die "usage: $0 apply <encoded-curve> [package]"; apply "$2" "${3:-}" ;;
    revert) revert "${2:-}" ;;
    *)      die "usage: $0 {status|apply <curve> [pkg]|revert [pkg]}" ;;
esac
