package com.daleygames.fanlab;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Access to the Android property service without any SDK dependency, via reflection on
 * {@code android.os.SystemProperties} (light-greylisted on API 28, so it works from an
 * ordinary app and merely logs a warning).
 *
 * A {@code getprop}/{@code setprop} exec is kept as a fallback for the read path in case
 * a future platform hides the class outright.
 *
 * Whether a <i>write</i> succeeds is a policy question, not a code question:
 * {@code persist.sys.*} is {@code system_prop}, which only the {@code system_app} domain
 * can set. In the plain variant every set here returns false, and the UI never offers
 * one. Nothing in this class throws.
 *
 * Pure Java (reflection only), so the host test can link it.
 */
public final class SysProps {

    public static final String PROP_LED_TEMPERATURE = "persist.sys.led.temperature";
    public static final String PROP_FANCTRL_BY_TEMP = "persist.sys.fanctrl.by.temperatue";
    public static final String PROP_ADJFAN_ENABLE = "persist.sys.adjfan.enable";
    public static final String PROP_USB_CONFIG = "sys.usb.config";

    /** The adjfan tier properties, for the diagnostics dump. */
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

    /** True when the reflective get resolved; false means the fallback is in use. */
    public static boolean reflectionAvailable() {
        resolve();
        return getMethod != null;
    }

    /** True when the reflective set resolved. Says nothing about whether it is permitted. */
    public static boolean canAttemptSet() {
        resolve();
        return setMethod != null;
    }

    /**
     * Read a system property.
     *
     * @return its value, "" if unset, or null if it could not be read at all.
     */
    public static String get(String name) {
        resolve();
        Method m = getMethod;
        if (m != null) {
            try {
                Object v = m.invoke(null, name);
                return v == null ? "" : v.toString();
            } catch (Throwable ignored) {
                // fall through to exec
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
                    // nothing useful to do
                }
            }
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    /**
     * Attempt to set a system property.
     *
     * @return true only if the value read back afterwards equals what was requested.
     *         A false return means SELinux or the property service refused, which is the
     *         normal outcome in the plain variant.
     */
    public static boolean set(String name, String value) {
        resolve();
        Method m = setMethod;
        if (m != null) {
            try {
                m.invoke(null, name, value);
            } catch (Throwable ignored) {
                // fall through to verification anyway; the exec path is not useful here
                // because setprop from an app uid is refused by the same policy
            }
        }
        String back = get(name);
        return back != null && back.equals(value);
    }

    /** Parse a property as a double. */
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
