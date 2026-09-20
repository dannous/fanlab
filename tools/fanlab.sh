#!/system/bin/sh
#
# fanlab.sh -- drive /sys/class/fan_int/fan_ctrl through a plan of holds and log a CSV.
#
#   usage:  fanlab.sh <csv-out> <plan-step> [<plan-step> ...]
#   step:   LEVEL:DUTY:SECONDS      LEVEL 0 = leave the brightness mode alone
#
# Safety: the stock fan ladder is disabled only for the life of this script -- traps on
# EXIT, INT, TERM and HUP restore persist.sys.fanctrl.by.temperatue=1 and the original
# brightness level on every exit path. Fail safe is HIGH: every abort writes duty 83.
# ABORT_ADC is a hard over-temperature stop below the framework's own 75 C shutdown.

OUT="$1"; shift

FAN=/sys/class/fan_int/fan_ctrl
VOLT=/sys/class/ledtemp/voltage
LVL=/sys/class/dlpc343x/rgblevel
LST=/sys/class/dlpc343x/led_status
Z0=/sys/class/thermal/thermal_zone0/temp
Z1=/sys/class/thermal/thermal_zone1/temp
Z2=/sys/class/thermal/thermal_zone2/temp

ABORT_ADC=701      # adc falls as it heats; 701 ~= 62.0 C. See adblab/therm.py.
FAILSAFE=83
KILL=persist.sys.fanctrl.by.temperatue

ORIG_LVL=$(cat $LVL 2>/dev/null)
[ -z "$ORIG_LVL" ] && ORIG_LVL=2
DONE=0
TOOK_LADDER=0      # only re-arm the stock ladder if we were the one who stood it down

restore() {
    [ "$DONE" = "1" ] && return
    DONE=1
    echo "$FAILSAFE" > $FAN 2>/dev/null
    echo "$ORIG_LVL" > $LVL 2>/dev/null
    if [ "$TOOK_LADDER" = "1" ]; then
        setprop $KILL 1
    fi
    echo "# restored: $KILL=$(getprop $KILL) rgblevel=$ORIG_LVL duty=$FAILSAFE reason=$1" >> "$OUT"
    echo "RESTORED reason=$1"
}
# The explicit exit is load-bearing: without it a trap handler returns into the sampling
# loop and keeps driving the fan after restore() has reported a clean shutdown.
trap 'restore signal; exit 130' INT TERM HUP
trap 'restore exit' EXIT

setprop $KILL 0
sleep 1
GOT=$(getprop $KILL)
if [ "$GOT" != "0" ]; then
    echo "FATAL: could not set $KILL (reads '$GOT'); stock ladder still active"
    exit 1
fi
TOOK_LADDER=1
echo "# $KILL=0, stock ladder disabled" >> "$OUT"

echo "epoch,step,phase,level_cmd,duty_cmd,adc,fan_rb,level_rb,led_status,pll,ddr,sar" >> "$OUT"

STEP=0
for ENTRY in "$@"; do
    STEP=$((STEP + 1))
    LEVEL=${ENTRY%%:*}
    REST=${ENTRY#*:}
    DUTY=${REST%%:*}
    SECS=${REST#*:}

    if [ "$LEVEL" != "0" ]; then
        echo "$LEVEL" > $LVL 2>/dev/null
    fi
    echo "STEP $STEP level=$LEVEL duty=$DUTY secs=$SECS"

    I=0
    while [ $I -lt "$SECS" ]; do
        # Second line of defence: never write after a restore, no matter how we got here.
        [ "$DONE" = "1" ] && exit 130
        echo "$DUTY" > $FAN 2>/dev/null

        ADC=$(cat $VOLT 2>/dev/null)
        FANRB=$(cat $FAN 2>/dev/null)
        LVLRB=$(cat $LVL 2>/dev/null)
        LSTRB=$(cat $LST 2>/dev/null)
        P=$(cat $Z0 2>/dev/null); D=$(cat $Z1 2>/dev/null); S=$(cat $Z2 2>/dev/null)

        echo "$(date +%s),$STEP,$LEVEL:$DUTY,$LEVEL,$DUTY,$ADC,$FANRB,$LVLRB,$LSTRB,$P,$D,$S" >> "$OUT"

        case "$ADC" in
            ''|*[!0-9]*) restore "bad-adc:$ADC"; exit 2 ;;
        esac
        if [ "$ADC" -lt "$ABORT_ADC" ]; then
            restore "over-temp:adc=$ADC"
            exit 3
        fi
        if [ "$LSTRB" = "0" ]; then
            restore "light-engine-off"
            exit 4
        fi

        I=$((I + 1))
        sleep 1
    done
done

restore complete
exit 0
