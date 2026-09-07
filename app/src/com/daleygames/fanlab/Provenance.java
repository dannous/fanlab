package com.daleygames.fanlab;

/**
 * The parts of a telemetry row that describe the circumstances rather than the
 * measurement: how long the projector had been off before a reading was taken, and
 * whether this app was alone on {@code fan_ctrl} while it was.
 *
 * Both exist because the first thirty-six hours of field log could not answer a question
 * anyone asked of it. Ambient had to be supplied by word of mouth, and excluding the
 * windows where something else was writing the fan meant grepping three different notes
 * and then subtracting time ranges by hand. Neither is a measurement the hardware can be
 * asked for; both are judgements about what a row means.
 *
 * Here rather than inside {@link FanService} for the reason the whole seam exists: there
 * is no way to run the service on the build machine, so a judgement left in it is a
 * judgement nothing checks. Pure Java, no state, no I/O.
 */
public final class Provenance {

    /**
     * Slack when deciding whether a persisted stamp belongs to this boot.
     *
     * The boot instant is a difference of two clocks, so a clock correction after boot
     * moves it. A minute of slack costs at worst one power-on reading filed under the
     * wrong case; no slack costs the same reading being filed under the wrong case every
     * time the machine corrects its clock, which on this device is every boot.
     */
    public static final long BOOT_MATCH_MS = 60000L;

    /**
     * How long after a foreign write the app stops claiming exclusive control.
     *
     * The stock ladder polls every 15 s, so a quiet minute is four of its poll periods
     * and a ladder that is still armed cannot slip through one. Short enough that a
     * single stray write does not blank out a whole session.
     */
    public static final long FOREIGN_QUIET_MS = 60000L;

    /** The row is a power-on and nothing at all is known about the gap before it. */
    public static final String OFF_NONE = "none";
    /** Android stayed up with the light engine off. No boot intervened. */
    public static final String OFF_STANDBY = "standby";
    /** A boot happened between the last engine-on sighting and this one. */
    public static final String OFF_BOOT = "boot";

    private Provenance() {
    }

    /** Was a stamp carrying {@code stampBootMs} taken during the boot at {@code bootWallMs}? */
    public static boolean sameBoot(long stampBootMs, long bootWallMs) {
        return Math.abs(stampBootMs - bootWallMs) < BOOT_MATCH_MS;
    }

    /**
     * How long the light engine had been off before it came on, milliseconds, or -1 when
     * there is nothing to go on.
     *
     * <h3>Why this is recorded rather than thresholded</h3>
     * Ambient is the one quantity in the log that cannot be derived from the log:
     * {@code ambient = degC - rise(duty)} needs the plant table, and the plant table is
     * what field data exists to check, so the inference argues in a circle. A power-on
     * reading is a direct measurement instead -- but only once it has settled, and the
     * LED thermistor is still 4.7 C above its resting value five hours after the engine
     * went off (32.65 at 5.0 h, 28.75 at 8.6 h, 27.91 at 13.3 h). An hour-long gate would
     * therefore pass a reading several degrees too warm with nothing in the row to reveal
     * it. So the off-duration goes in the log beside the reading and the sample is graded
     * afterwards, which is the discipline {@code fold_plant.py} already applies to a hold.
     *
     * <h3>Two cases, and neither covers the other</h3>
     * <ul>
     *   <li><b>A true power-down.</b> Android was not running, so nothing sampled the
     *       gap. The only evidence is the persisted instant at which the app last saw the
     *       engine on, which survives the power cycle. The boot instant says when the
     *       machine came back, never how long it had been away, so on its own it cannot
     *       answer this at all -- what it contributes is the identity of the boot, which
     *       is how the two cases are told apart.</li>
     *   <li><b>Standby, with Android up and the light engine off</b>, which the field log
     *       suggests is the common case. No boot happens, so anything derived from boot
     *       time would report the whole uptime.</li>
     * </ul>
     *
     * <h3>Where the two witnesses disagree, the shorter wins</h3>
     * A shorter off-duration grades the reading as less settled, so it is the answer that
     * cannot overstate what was measured. Inside one boot the monotonic clock is the
     * second witness and the one that can be shorter: nothing can move it, where a
     * wall-clock gap spanning a clock correction is inflated by exactly that correction.
     * Across a boot there is no monotonic witness -- {@code elapsedRealtime} restarted --
     * so the wall clock stands alone, and a gap that comes out negative is reported as
     * unknown rather than as a number.
     *
     * @param stampWallMs wall clock at the last engine-on sighting, 0 if there is none.
     * @param stampMonoMs {@code elapsedRealtime} at the same sighting.
     * @param stampBootMs the boot instant that sighting was taken in.
     * @param bootWallMs  this boot's instant, i.e. {@code currentTimeMillis - elapsedRealtime}.
     */
    public static long offDurationMs(long nowWallMs, long nowMonoMs, long stampWallMs,
            long stampMonoMs, long stampBootMs, long bootWallMs) {
        if (stampWallMs <= 0L) {
            return -1L;
        }
        long byWall = nowWallMs - stampWallMs;
        if (byWall < 0L) {
            return -1L;
        }
        if (stampMonoMs > 0L && sameBoot(stampBootMs, bootWallMs)) {
            long byMono = nowMonoMs - stampMonoMs;
            if (byMono >= 0L && byMono < byWall) {
                return byMono;
            }
        }
        return byWall;
    }

    /** Which case {@link #offDurationMs} answered from, for the log note. */
    public static String offSource(long stampWallMs, long stampBootMs, long bootWallMs) {
        if (stampWallMs <= 0L) {
            return OFF_NONE;
        }
        return sameBoot(stampBootMs, bootWallMs) ? OFF_STANDBY : OFF_BOOT;
    }

    /**
     * Was this app alone on {@code fan_ctrl}? 1 yes, 0 no, -1 not determinable.
     *
     * Three conditions, and all three have been the culprit at least once: one of this
     * app's temperature controllers has to be the thing driving, the stock ladder has to be
     * stood down, and nothing else can have written the node recently. Together they are
     * the filter that had to be assembled by hand from 134 MANUAL rows, 11
     * {@code stock_ladder->*} toggles and 19 {@code reassert(was N)} events before any of
     * the field data could be trusted.
     *
     * "Driving" is {@link Mode#controls}, so CURVE and LINEAR both qualify and MANUAL does
     * not. That is the same test {@code syncStockLadder} uses to decide whether to stand
     * the ladder down, which is what keeps the column's second condition from contradicting
     * its first: a mode the ladder is disabled for and that this column called
     * non-exclusive would be a window nothing could account for.
     *
     * A running session is deliberately not exclusive. AUTO and VERIFY do own the node,
     * but they are measurements with a trace file of their own, and a row claiming
     * exclusive control ought to mean ordinary use of the curve.
     *
     * -1, which the CSV writes as a blank, when the kill switch has never been readable.
     * "Cannot say" is not "no" here any more than it is in the {@code thr_*} columns, and
     * the only value the column has is that a filter on it can be trusted.
     *
     * @param ladderProp {@code persist.sys.fanctrl.by.temperatue} as last read, or null.
     * @param lastForeignMonoMs when a read-back last disagreed with what was written; 0
     *                          means it never has.
     */
    public static int exclusive(int mode, boolean sessionRunning, String ladderProp,
            long nowMonoMs, long lastForeignMonoMs) {
        if (ladderProp == null) {
            return -1;
        }
        boolean quiet = lastForeignMonoMs == 0L
                || nowMonoMs - lastForeignMonoMs >= FOREIGN_QUIET_MS;
        return (Mode.controls(mode) && !sessionRunning && quiet
                && "0".equals(ladderProp.trim())) ? 1 : 0;
    }
}
