package com.daleygames.fanlab;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/**
 * Every filesystem touch the app makes on a kernel node goes through here.
 *
 * Contract, and it is absolute: <b>no method in this class ever throws.</b> A read that
 * fails returns null; a write that fails returns false. A projector must not be taken
 * out by an uncaught exception, and the stock framework controller already demonstrates
 * what happens when one escapes (its LED thread dies and fan regulation stops until
 * reboot).
 *
 * Pure Java, no android.* imports, so the host test can point {@link #root} at a stub
 * directory tree and exercise the same code that runs on the device.
 */
public final class Sysfs {

    /** Prefix applied to every path. "" on device; a temp directory in the host test. */
    public static volatile String root = "";

    /** Optional sink for diagnostics. Set by the service to android.util.Log. */
    public interface Sink {
        void note(String msg, Throwable t);
    }

    public static volatile Sink sink = null;

    // ---- the nodes, all verified 0777 at runtime on this firmware ----
    public static final String FAN_CTRL = "/sys/class/fan_int/fan_ctrl";
    public static final String LEDTEMP_VOLTAGE = "/sys/class/ledtemp/voltage";
    public static final String RGBLEVEL = "/sys/class/dlpc343x/rgblevel";
    public static final String LED_STATUS = "/sys/class/dlpc343x/led_status";

    /**
     * The two LED drive nodes an app may write, both 0777. {@code rgbcurrent} sets all four
     * channels to one value and has a show handler; {@code redcurrent} sets channel 1
     * alone. Both are overwritten by the kernel on the next {@code rgblevel} write, which
     * is what {@link LedDrive} relies on to restore the stock table. {@code b2current} is
     * not writable by an app and is not here.
     */
    public static final String RGBCURRENT = "/sys/class/dlpc343x/rgbcurrent";
    public static final String REDCURRENT = "/sys/class/dlpc343x/redcurrent";

    /**
     * The SoC die sensors, in millidegrees. Only {@code thermal_zone0} (pll) has cooling
     * devices bound to it -- at 75 C -- so it is the one that can actually throttle; the
     * other two are monitoring only. They are logged because the fan is driven solely by
     * the LED thermistor and is blind to SoC load: switching UHD processing on moves these
     * by about 11 C while moving the LED thermistor by half a degree, so without them the
     * telemetry cannot show that happening at all.
     */
    public static final String[] SOC_THERMAL = {
            "/sys/class/thermal/thermal_zone0/temp",   // pll  -- throttles at 75 C
            "/sys/class/thermal/thermal_zone1/temp",   // ddr  -- monitor only
            "/sys/class/thermal/thermal_zone2/temp",   // sar  -- monitor only
    };

    /**
     * The thermal governor's cooling devices, in the order of {@link #COOLING_NAMES}.
     * {@code cur_state} is 0 when the device is doing nothing and counts up towards its
     * {@code max_state} (11, 4, 5 and 2 respectively) as the governor throttles harder.
     *
     * All four are bound to {@code pll_thermal} trip 1, at 75 C, and to nothing else. So a
     * non-zero reading here is not an inference from temperature -- it is the governor
     * saying it is actively reducing CPU or GPU frequency right now. That is worth reading
     * directly, because the fan's own sensors cannot distinguish a machine that is merely
     * warm from one that is dropping frames.
     */
    public static final String[] COOLING_DEVICES = {
            "/sys/class/thermal/cooling_device0/cur_state",   // thermal-cpufreq-0
            "/sys/class/thermal/cooling_device1/cur_state",   // thermal-cpucore-0
            "/sys/class/thermal/cooling_device2/cur_state",   // thermal-gpufreq-0
            "/sys/class/thermal/cooling_device3/cur_state",   // thermal-gpucore-0
    };

    /** Short names for {@link #COOLING_DEVICES}, for log notes and the screen. */
    public static final String[] COOLING_NAMES = {"cpufreq", "cpucore", "gpufreq", "gpucore"};

    /** Read-only diagnostics targets. Not all of these are guaranteed to exist. */
    public static final String[] DLPC_NODES = {
            "/sys/class/dlpc343x/rgblevel",
            "/sys/class/dlpc343x/led_status",
            "/sys/class/dlpc343x/rgbcurrent",
            "/sys/class/dlpc343x/redcurrent",
            "/sys/class/dlpc343x/b2current",
            "/sys/class/dlpc343x/usb_sw",
            "/sys/class/dlpc343x/usb_power",
            "/sys/class/dlpc343x/pattern",
            "/sys/class/dlpc343x/looks",
    };

    public static final String[] FAN_NODES = {
            "/sys/class/fan_int/fan_ctrl",
            "/sys/class/fan_int/fan_enable_debug",
    };

    private Sysfs() {
    }

    private static File file(String path) {
        String r = root;
        return (r == null || r.length() == 0) ? new File(path) : new File(r + path);
    }

    private static void note(String msg, Throwable t) {
        Sink s = sink;
        if (s != null) {
            try {
                s.note(msg, t);
            } catch (Throwable ignored) {
                // a logging failure must never propagate
            }
        }
    }

    /**
     * Read a small kernel node whole.
     *
     * @return its contents, or null on any failure whatsoever.
     */
    public static String read(String path) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(file(path));
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) {
                return "";
            }
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            note("read failed: " + path, t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    /** Read a node and parse it as an integer. */
    public static int readInt(String path, int fallback) {
        String s = read(path);
        if (s == null) {
            return fallback;
        }
        int v = Thermistor.parseAdc(s);
        return v == Thermistor.BAD_ADC ? fallback : v;
    }

    /**
     * Write a bare value to a kernel node, with no trailing newline (which is what the
     * framework's set_fan_speed does).
     *
     * @return true if the bytes were written and the stream closed cleanly.
     */
    public static boolean write(String path, String value) {
        OutputStreamWriter w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(file(path)), "UTF-8");
            w.write(value);
            w.flush();
            return true;
        } catch (Throwable t) {
            note("write failed: " + path + " <- " + value, t);
            return false;
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    /** True if the path exists and is readable. Never throws. */
    public static boolean exists(String path) {
        try {
            return file(path).exists();
        } catch (Throwable t) {
            return false;
        }
    }
}
