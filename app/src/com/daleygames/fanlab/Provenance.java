package com.daleygames.fanlab;

/** The parts of a telemetry row that describe the circumstances rather than the measurement. */
public final class Provenance {

    /** Slack when matching a persisted stamp to this boot; a clock correction moves the boot instant. */
    public static final long BOOT_MATCH_MS = 60000L;

    /** Quiet window after a foreign write before exclusive control is claimed again; the stock ladder polls every 15 s. */
    public static final long FOREIGN_QUIET_MS = 60000L;

    /** The row is a power-on and nothing at all is known about the gap before it. */
    public static final String OFF_NONE = "none";
    /** Android stayed up with the light engine off. No boot intervened. */
    public static final String OFF_STANDBY = "standby";
    /** A boot happened between the last engine-on sighting and this one. */
    public static final String OFF_BOOT = "boot";

    private Provenance() {
    }

    public static boolean sameBoot(long stampBootMs, long bootWallMs) {
        return Math.abs(stampBootMs - bootWallMs) < BOOT_MATCH_MS;
    }

    /** Milliseconds the light engine was off before it came on, or -1 when there is nothing to go on; takes the shorter of the monotonic and wall-clock witnesses. */
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

    /** Was this app alone on fan_ctrl? 1 yes, 0 no, -1 not determinable, which the CSV writes as a blank. */
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
