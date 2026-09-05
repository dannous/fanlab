package com.daleygames.fanlab;

/**
 * The only place in the app that is allowed to command the fan.
 *
 * Two rules are enforced here and nowhere else, so there is exactly one thing to audit:
 * <ol>
 *   <li><b>Range.</b> Only 1..100 is ever written. Anything else is rejected, not clamped
 *       (the framework's own set_fan_speed rejects too, and a silent clamp would hide a
 *       bug in the caller).</li>
 *   <li><b>Fail safe is HIGH.</b> {@link #FAIL_SAFE_DUTY} is 83, the stock maximum. Every
 *       error path in the app writes that, never a low value.</li>
 * </ol>
 *
 * Pure Java.
 */
public final class FanIo {

    public static final int MIN_DUTY = 1;
    public static final int MAX_DUTY = 100;

    /**
     * The value written on any failure: read error, implausible temperature, missing
     * config, or an unexpected exception. 83 is the stock controller's maximum for every
     * tier, so it is a duty the hardware is known to run at indefinitely.
     */
    public static final int FAIL_SAFE_DUTY = 83;

    /** What the kernel driver itself settles at from boot, after a resume, and after a stall. */
    public static final int KERNEL_DEFAULT_DUTY = 55;

    private FanIo() {
    }

    /** True if {@code duty} is a value we are permitted to write. */
    public static boolean valid(int duty) {
        return duty >= MIN_DUTY && duty <= MAX_DUTY;
    }

    /**
     * Write a duty percentage to /sys/class/fan_int/fan_ctrl.
     *
     * @return true only if the value was in range and the write succeeded.
     */
    public static boolean writeDuty(int duty) {
        if (!valid(duty)) {
            return false;
        }
        return Sysfs.write(Sysfs.FAN_CTRL, Integer.toString(duty));
    }

    /**
     * Write {@link #FAIL_SAFE_DUTY}. Used by every error path.
     *
     * @return true if the write succeeded.
     */
    public static boolean writeFailSafe() {
        return Sysfs.write(Sysfs.FAN_CTRL, Integer.toString(FAIL_SAFE_DUTY));
    }

    /**
     * Read back the duty the kernel currently holds.
     *
     * @return 1..100, or -1 if the node could not be read or did not parse.
     */
    public static int readDuty() {
        int v = Sysfs.readInt(Sysfs.FAN_CTRL, -1);
        return valid(v) ? v : -1;
    }

    /** Clamp a candidate into the writable range, for UI use only. */
    public static int clampForUi(int duty) {
        if (duty < MIN_DUTY) {
            return MIN_DUTY;
        }
        if (duty > MAX_DUTY) {
            return MAX_DUTY;
        }
        return duty;
    }
}
