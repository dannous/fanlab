package com.daleygames.fanlab;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/** The Android property service by reflection on android.os.SystemProperties, with a getprop fallback for reads. Nothing here throws. */
public final class SysProps {

    public static final String PROP_LED_TEMPERATURE = "persist.sys.led.temperature";
    public static final String PROP_FANCTRL_BY_TEMP = "persist.sys.fanctrl.by.temperatue";
    public static final String PROP_ADJFAN_ENABLE = "persist.sys.adjfan.enable";
    public static final String PROP_USB_CONFIG = "sys.usb.config";

    public static final String[] DIAG_PROPS = {
            "persist.sys.led.temperature",
            "persist.sys.fanctrl.by.temperatue",
            "persist.sys.adjfan.enable",
            "persist.sys.adjfan.low.temperatue",
            "persist.sys.adjfan.low.speed.min",
            "persist.sys.adjfan.low.speed.max",
            "persist.sys.adjfan.normal.temperatue",
            "persist.sys.adjfan.normal.speed.min",
            "persist.sys.adjfan.normal.speed.max",
            "persist.sys.adjfan.high.temperatue",
            "persist.sys.adjfan.high.speed.min",
            "persist.sys.adjfan.high.speed.max",
            "persist.sys.adjfan.superlow.speed.min",
            "persist.sys.adjfan.superhigh.speed.min",
            "persist.sys.adjfan.superhigh.speed.max",
            "persist.sys.rled.otp.enable",
            "sys.usb.config",
            "ro.build.version.release",
            "ro.build.version.sdk",
            "ro.product.version",
            "ro.product.model",
    };

    private static volatile Method getMethod;
    private static volatile Method setMethod;
    private static volatile boolean resolved;

    private SysProps() {
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        synchronized (SysProps.class) {
            if (resolved) {
                return;
            }
            try {
                Class<?> c = Class.forName("android.os.SystemProperties");
                try {
                    getMethod = c.getMethod("get", String.class);
                } catch (Throwable ignored) {
                    getMethod = null;
                }
                try {
                    setMethod = c.getMethod("set", String.class, String.class);
                } catch (Throwable ignored) {
                    setMethod = null;
                }
            } catch (Throwable ignored) {
                getMethod = null;
                setMethod = null;
            }
            resolved = true;
        }
    }

    public static boolean reflectionAvailable() {
        resolve();
        return getMethod != null;
    }

    public static boolean canAttemptSet() {
        resolve();
        return setMethod != null;
    }

    /** Read a property: its value, "" if unset, or null if it could not be read at all. */
    public static String get(String name) {
        resolve();
        Method m = getMethod;
        if (m != null) {
            try {
                Object v = m.invoke(null, name);
                return v == null ? "" : v.toString();
            } catch (Throwable ignored) {
            }
        }
        return getViaExec(name);
    }

    private static String getViaExec(String name) {
        Process p = null;
        BufferedReader r = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"getprop", name});
            r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line = r.readLine();
            return line == null ? "" : line.trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Attempt to set a property; true only if the value read back afterwards equals what was requested. */
    public static boolean set(String name, String value) {
        resolve();
        Method m = setMethod;
        if (m != null) {
            try {
                m.invoke(null, name, value);
            } catch (Throwable ignored) {
            }
        }
        String back = get(name);
        return back != null && back.equals(value);
    }

    public static double getDouble(String name, double fallback) {
        String s = get(name);
        if (s == null) {
            return fallback;
        }
        s = s.trim();
        if (s.length() == 0) {
            return fallback;
        }
        try {
            return Double.parseDouble(s);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
