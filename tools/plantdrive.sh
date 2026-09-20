#!/system/bin/sh
#
# plantdrive.sh -- hold each brightness mode at its factory LED drive and again at the
# raised one, pinned to a single fan duty, and log a CSV good enough to divide the two.
#
#   usage: plantdrive.sh <csv-out> <duty> <poweroff:0|1> <step> [<step> ...]
#   step:  LEVEL:DRIVE:SECONDS      DRIVE 0 = leave the factory table alone
#
# It exists because tools/fanlab.sh cannot: fanlab.sh writes rgblevel and trusts it, and
# an overnight run was lost to exactly that. Everything below is the difference.
#
# WHAT THIS ADDS OVER fanlab.sh
#
#   * It verifies the light engine is in the state the hold claims, before the hold
#     starts and on every sample inside it. Both halves are checked -- the mode from
#     rgblevel, and the drive read back out of rgbcurrent -- because rgblevel wrote 3 and
#     read back 19 for thirteen of sixteen samples on the run this replaces, and the
#     reference then measured the wrong machine.
#   * A hold that cannot be put in the right state is retried, not logged. A hold that
#     falls out of it mid-way is re-asserted, and abandoned and retried if it will not
#     come back. A wrong hold in the CSV is worse than a missing one: it looks like data.
#   * It waits for the kernel's own four SPI writes to agree after a mode change before
#     writing a drive on top. The driver stores rgblevel first and pushes the table
#     after, so anything writing into that window gets the common channels overwritten
#     while red survives -- a colour cast, and a drive half factory and half ours.
#   * The power-off is here, on the exit trap, rather than in something somebody says
#     afterwards. Pass 1 as the third argument and the machine goes down when the run
#     ends, however it ends.
#
# WHAT IT KEEPS FROM fanlab.sh, because those were paid for
#
#   * The stock ladder is stood down only for the life of this script, and only if this
#     script was the one that stood it down. Every exit path re-arms it.
#   * Fail safe is HIGH: every abort writes 83 before restoring.
#   * ABORT_ADC stops well below the platform's own 75 C shutdown.
#   * The signal trap exits explicitly. Without that it returns into the sampling loop
#     and carries on driving the fan after reporting a clean shutdown.

OUT="$1"; shift
DUTY="$1"; shift
POWEROFF="$1"; shift

FAN=/sys/class/fan_int/fan_ctrl
VOLT=/sys/class/ledtemp/voltage
LVL=/sys/class/dlpc343x/rgblevel
LST=/sys/class/dlpc343x/led_status
CUR=/sys/class/dlpc343x/rgbcurrent
RED=/sys/class/dlpc343x/redcurrent
Z0=/sys/class/thermal/thermal_zone0/temp
Z1=/sys/class/thermal/thermal_zone1/temp
Z2=/sys/class/thermal/thermal_zone2/temp

ABORT_ADC=701      # adc falls as it heats; 701 ~= 62.0 C. See tools/therm.py.
FAILSAFE=83
KILL=persist.sys.fanctrl.by.temperatue

SETTLE_TRIES=40    # seconds to wait for a mode or a drive to take
HOLD_RETRIES=2     # how many times a hold may be restarted before the run gives up
BAD_BUDGET=30      # samples a hold may spend out of state before it is abandoned

ORIG_LVL=$(cat $LVL 2>/dev/null)
[ -z "$ORIG_LVL" ] && ORIG_LVL=2
DONE=0
TOOK_LADDER=0

# The factory per-channel table, indexed by rgblevel. Channel 1 (redcurrent) is driven
# below the other three in every mode but Super Eco; reproducing that ratio is what keeps
# a raised drive the same colour as the factory one.
stock_other() { case "$1" in 1) echo 40 ;; 2) echo 55 ;; 3) echo 76 ;; 4) echo 20 ;; *) echo 0 ;; esac ; }
stock_red()   { case "$1" in 1) echo 36 ;; 2) echo 48 ;; 3) echo 71 ;; 4) echo 20 ;; *) echo 0 ;; esac ; }

# round(level * stockRed / stockOther) in integer arithmetic. Mirrors LedDrive.redFor,
# and must keep mirroring it: Presentation 90 -> 84, Normal 75 -> 65, Eco 55 -> 50,
# Super Eco 35 -> 35.
red_for() {
    _l=$1; _o=$(stock_other "$2"); _r=$(stock_red "$2")
    [ "$_o" -le 0 ] && { echo "$_l"; return; }
    echo $(( (2 * _l * _r + _o) / (2 * _o) ))
}

restore() {
    [ "$DONE" = "1" ] && return
    DONE=1
    echo "$FAILSAFE" > $FAN 2>/dev/null
    # Rewriting rgblevel with the value it already holds is also the cleanest restore of
    # the LED drive there is: the kernel reinstates the whole factory table on every
    # rgblevel write, so this undoes any rgbcurrent/redcurrent we wrote.
    echo "$ORIG_LVL" > $LVL 2>/dev/null
    if [ "$TOOK_LADDER" = "1" ]; then
        setprop $KILL 1
    fi
    echo "# restored: $KILL=$(getprop $KILL) rgblevel=$ORIG_LVL duty=$FAILSAFE reason=$1" >> "$OUT"
    echo "RESTORED reason=$1"
    if [ "$POWEROFF" = "1" ]; then
        echo "# powering off" >> "$OUT"
        echo "POWERING OFF"
        sync
        # Last thing, and unconditional: an unattended run must not leave the projector
        # sitting on. It went from 02:54 to 08:34 that way once.
        reboot -p 2>/dev/null || svc power shutdown 2>/dev/null
    fi
}
trap 'restore signal; exit 130' INT TERM HUP
trap 'restore exit' EXIT

# --- reading the light engine back ---------------------------------------
#
# rgbcurrent's show text on this firmware is
#   red_current=13,green_current=13,blue_current=13,duty_r=75,duty_g=70,duty_b=238, duty_b2=238
# -- COMMA separated, with one stray space before the last field. Splitting on spaces
# alone returns the whole line as a single token and every field then reads as missing,
# which is a parser that always says "could not tell" and a run that never starts. Split
# on both.
#
# The field names are the array index dressed up as a colour. The channel map is ch0
# green, ch1 red, ch2 b2, ch3 blue, so duty_g IS THE RED CHANNEL and duty_r, duty_b and
# duty_b2 all carry the common level. Reading duty_r as red makes every comparison fail,
# which is how an earlier version of the app logged 285 pointless rewrites in one evening.
# Values read back one below what was written; anything above 100 is a failed SPI read
# (0x8080 in, percent = -18 computed, printed as an unsigned byte) and means "could not
# tell", never "the kernel overwrote us".
field() {
    _v=$(echo "$1" | tr ' ,' '\n\n' | grep "^$2=" | head -1)
    _v=${_v#*=}
    case "$_v" in ''|*[!0-9]*) echo -1 ;; *) echo "$_v" ;; esac
}

# Sets RB_RED, RB_OTHER and RB_N, or -1 for "unreadable".
#
# Three fields carry the common level and the readable ones must agree, but a field above
# 100 is a failed SPI read and says nothing about the others -- so it is skipped, not
# counted as a disagreement. That is LedDrive.parseReadback's rule and it has to be, or
# this harness would refuse to start on a projector the app itself is happy with: the
# live device shows duty_b and duty_b2 stuck at 238 while duty_r reads correctly.
#
# What is NOT tolerated is two readable fields that disagree. The kernel writes the four
# channels one at a time, so mid-sequence they genuinely differ, and answering with
# whichever parsed first reports a half-written table as correct -- observed as Eco
# reading 49/44 on two channels and factory 39 on the other two, a colour cast the
# override then declared correct.
#
# RB_N is how many of the three were readable, and it goes in the CSV. A run where every
# sample rested on one field is a weaker run than one where all three agreed, and that
# should be visible afterwards rather than inferred.
read_drive() {
    _t=$(cat $CUR 2>/dev/null)
    RB_RED=$(field "$_t" duty_g)
    RB_OTHER=-1
    RB_N=0
    for _f in duty_r duty_b duty_b2; do
        _v=$(field "$_t" $_f)
        [ "$_v" -lt 0 ] && continue
        [ "$_v" -gt 100 ] && continue
        if [ "$RB_N" = "0" ]; then
            RB_OTHER=$_v
        elif [ "$_v" != "$RB_OTHER" ]; then
            RB_OTHER=-1                 # half-written table: no comparison is meaningful
            RB_N=0
            break
        fi
        RB_N=$((RB_N + 1))
    done
    [ "$RB_RED" -gt 100 ] && RB_RED=-1
}

# 0 = in state, 1 = wrong, 2 = could not tell. "Could not tell" is not "wrong": a failed
# SPI read must never be counted as the kernel having taken the drive back.
check_state() {
    _want_lvl=$1; _want_red=$2; _want_other=$3
    _l=$(cat $LVL 2>/dev/null)
    [ "$_l" != "$_want_lvl" ] && return 1
    read_drive
    { [ "$RB_RED" -lt 0 ] || [ "$RB_OTHER" -lt 0 ] ; } && return 2
    [ "$RB_RED" != "$_want_red" ] && [ "$RB_RED" != "$((_want_red - 1))" ] && return 1
    [ "$RB_OTHER" != "$_want_other" ] && [ "$RB_OTHER" != "$((_want_other - 1))" ] && return 1
    return 0
}

# Poll check_state up to $1 seconds and succeed on the FIRST confirming read, rather than
# on whatever the last read happened to say.
#
# This distinction is not pedantry, it is most of whether the run starts. duty_g -- the
# red channel -- fails its SPI read on roughly half of all samples on this firmware, and
# a failed read is indistinguishable from silence. Asking "is it in state now" and giving
# up on a no therefore fails about half the time even when the drive is perfectly applied:
# the smoke test spent three attempts and two minutes on Super Eco that way, with the
# drive reading a correct 19 on the common channels throughout. Asking "did it confirm at
# any point in the last forty seconds" answers the question actually being asked, and a
# genuine mismatch still never confirms.
#
# Sets SAW_MISMATCH so the caller can tell a window that was merely quiet from one that
# actively disagreed.
confirm_within() {
    _secs=$1; _l=$2; _r=$3; _o=$4
    SAW_MISMATCH=0
    _i=0
    while [ $_i -lt "$_secs" ]; do
        check_state "$_l" "$_r" "$_o"
        case $? in
            0) return 0 ;;
            1) SAW_MISMATCH=1 ;;
        esac
        _i=$((_i + 1)); sleep 1
    done
    return 1
}

# --- putting the light engine into the state a hold claims ----------------
# Returns 0 once rgblevel AND the drive both read back right, 1 if they will not.
enter_state() {
    _lvl=$1; _drive=$2
    _fo=$(stock_other "$_lvl"); _fr=$(stock_red "$_lvl")
    if [ "$_drive" = "0" ]; then
        WANT_OTHER=$_fo; WANT_RED=$_fr
    else
        WANT_OTHER=$_drive; WANT_RED=$(red_for "$_drive" "$_lvl")
    fi

    _attempt=0
    while [ $_attempt -lt 3 ]; do
        _attempt=$((_attempt + 1))

        # 1. the mode. Re-assert every few seconds until it sticks -- one write is what
        #    the lost run trusted.
        echo "$_lvl" > $LVL 2>/dev/null
        _i=0
        while [ $_i -lt $SETTLE_TRIES ]; do
            [ "$(cat $LVL 2>/dev/null)" = "$_lvl" ] && break
            [ $((_i % 5)) = 4 ] && echo "$_lvl" > $LVL 2>/dev/null
            _i=$((_i + 1)); sleep 1
        done
        if [ "$(cat $LVL 2>/dev/null)" != "$_lvl" ]; then
            echo "  rgblevel will not take $_lvl (reads $(cat $LVL 2>/dev/null)), attempt $_attempt"
            continue
        fi

        # 2. wait for the kernel's own four SPI writes to land before writing over them.
        if ! confirm_within $SETTLE_TRIES "$_lvl" "$_fr" "$_fo"; then
            echo "  factory table never confirmed for level $_lvl (last read $RB_RED/$RB_OTHER, mismatch=$SAW_MISMATCH), attempt $_attempt"
            continue
        fi

        # 3. the drive, if this hold wants one on top. Re-assert while waiting: a write
        #    that lost the race with the kernel's own table push has to be repeated, and
        #    repeating a write that already took is free.
        if [ "$_drive" != "0" ]; then
            _got=0
            _i=0
            while [ $_i -lt $SETTLE_TRIES ]; do
                echo "$WANT_OTHER" > $CUR 2>/dev/null
                echo "$WANT_RED" > $RED 2>/dev/null
                if confirm_within 5 "$_lvl" "$WANT_RED" "$WANT_OTHER"; then
                    _got=1; break
                fi
                _i=$((_i + 5))
            done
            if [ $_got = 0 ]; then
                echo "  drive will not take $WANT_RED/$WANT_OTHER (last read $RB_RED/$RB_OTHER), attempt $_attempt"
                continue
            fi
        fi

        echo "  in state: level=$_lvl red=$RB_RED other=$RB_OTHER (wanted $WANT_RED/$WANT_OTHER)"
        return 0
    done
    return 1
}

# --- take control ---------------------------------------------------------
setprop $KILL 0
sleep 1
GOT=$(getprop $KILL)
if [ "$GOT" != "0" ]; then
    echo "FATAL: could not set $KILL (reads '$GOT'); stock ladder still active"
    exit 1
fi
TOOK_LADDER=1
echo "# $KILL=0, stock ladder disabled" >> "$OUT"
echo "# duty pinned at $DUTY for the whole run" >> "$OUT"
echo "epoch,step,attempt,level_cmd,drive_cmd,duty_cmd,adc,fan_rb,level_rb,red_rb,other_rb,other_n,state,led_status,pll,ddr,sar" >> "$OUT"

STEP=0
for ENTRY in "$@"; do
    STEP=$((STEP + 1))
    LEVEL=${ENTRY%%:*}
    REST=${ENTRY#*:}
    DRIVE=${REST%%:*}
    SECS=${REST#*:}

    ATTEMPT=0
    HOLD_OK=0
    while [ $ATTEMPT -le $HOLD_RETRIES ] && [ $HOLD_OK = 0 ]; do
        ATTEMPT=$((ATTEMPT + 1))
        echo "STEP $STEP level=$LEVEL drive=$DRIVE secs=$SECS attempt=$ATTEMPT"
        echo "$DUTY" > $FAN 2>/dev/null

        if ! enter_state "$LEVEL" "$DRIVE"; then
            echo "# step $STEP attempt $ATTEMPT: could not enter state, retrying" >> "$OUT"
            continue
        fi

        BAD=0
        CONF=0
        LAST_REASSERT=0
        I=0
        while [ $I -lt "$SECS" ]; do
            [ "$DONE" = "1" ] && exit 130
            echo "$DUTY" > $FAN 2>/dev/null

            ADC=$(cat $VOLT 2>/dev/null)
            FANRB=$(cat $FAN 2>/dev/null)
            LVLRB=$(cat $LVL 2>/dev/null)
            LSTRB=$(cat $LST 2>/dev/null)
            P=$(cat $Z0 2>/dev/null); D=$(cat $Z1 2>/dev/null); S=$(cat $Z2 2>/dev/null)

            check_state "$LEVEL" "$WANT_RED" "$WANT_OTHER"; ST=$?

            echo "$(date +%s),$STEP,$ATTEMPT,$LEVEL,$DRIVE,$DUTY,$ADC,$FANRB,$LVLRB,$RB_RED,$RB_OTHER,$RB_N,$ST,$LSTRB,$P,$D,$S" >> "$OUT"

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

            # ST=0 confirms, ST=2 is an unreadable sample and says nothing. Only a real
            # mismatch spends the budget, and a mismatch is re-asserted rather than
            # merely counted. CONF is reported so that a hold nobody could read is
            # distinguishable afterwards from a hold that was checked and was right --
            # duty_g fails its SPI read about half the time on this firmware.
            [ "$ST" = "0" ] && CONF=$((CONF + 1))
            if [ "$ST" = "1" ]; then
                BAD=$((BAD + 1))
                if [ $((I - LAST_REASSERT)) -ge 5 ]; then
                    LAST_REASSERT=$I
                    if [ "$DRIVE" = "0" ]; then
                        echo "$LEVEL" > $LVL 2>/dev/null
                    else
                        echo "$WANT_OTHER" > $CUR 2>/dev/null
                        echo "$WANT_RED" > $RED 2>/dev/null
                    fi
                fi
                if [ $BAD -gt $BAD_BUDGET ]; then
                    echo "  step $STEP out of state for $BAD samples, abandoning attempt $ATTEMPT"
                    echo "# step $STEP attempt $ATTEMPT ABANDONED after $BAD mismatches ($CONF confirmed)" >> "$OUT"
                    break
                fi
            fi

            I=$((I + 1))
            sleep 1
        done

        if [ $I -ge "$SECS" ]; then
            HOLD_OK=1
            echo "# step $STEP attempt $ATTEMPT COMPLETE, $CONF confirmed / $BAD mismatched of $SECS" >> "$OUT"
        fi
    done

    if [ $HOLD_OK = 0 ]; then
        restore "step-$STEP-unholdable"
        exit 5
    fi
done

restore complete
exit 0
