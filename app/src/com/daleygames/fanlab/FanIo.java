package com.daleygames.fanlab;

/** The only place in the app that commands the fan. Only 1..100 is ever written. */
public final class FanIo {

    public static final int MIN_DUTY = 1;
    public static final int MAX_DUTY = 100;

    /** Written by every error path. 83 is the stock controller's maximum, safe to hold indefinitely. */
    public static final int FAIL_SAFE_DUTY = 83;

    /** Duty the kernel driver settles at from boot, after a resume, and after a stall. */
    public static final int KERNEL_DEFAULT_DUTY = 55;

    private FanIo() {
    }

    public static boolean valid(int duty) {
        return duty >= MIN_DUTY && duty <= MAX_DUTY;
    }

    public static boolean writeDuty(int duty) {
        if (!valid(duty)) {
            return false;
        }
        return Sysfs.write(Sysfs.FAN_CTRL, Integer.toString(duty));
    }

    public static boolean writeFailSafe() {
        return Sysfs.write(Sysfs.FAN_CTRL, Integer.toString(FAIL_SAFE_DUTY));
    }

    public static int readDuty() {
        int v = Sysfs.readInt(Sysfs.FAN_CTRL, -1);
        return valid(v) ? v : -1;
    }

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
