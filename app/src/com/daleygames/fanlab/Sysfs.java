package com.daleygames.fanlab;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/** Every kernel-node read and write in the app. No method here ever throws: a failed read returns null, a failed write returns false. */
public final class Sysfs {

    /** Prefix applied to every path. "" on device; a temp directory in the host test. */
    public static volatile String root = "";

    /** Optional sink for diagnostics. Set by the service to android.util.Log. */
    public interface Sink {
        void note(String msg, Throwable t);
    }

    public static volatile Sink sink = null;

    public static final String FAN_CTRL = "/sys/class/fan_int/fan_ctrl";
    public static final String LEDTEMP_VOLTAGE = "/sys/class/ledtemp/voltage";
    public static final String RGBLEVEL = "/sys/class/dlpc343x/rgblevel";
    public static final String LED_STATUS = "/sys/class/dlpc343x/led_status";

    /** rgbcurrent sets all four channels, redcurrent channel 1; the kernel overwrites both on the next rgblevel write, which is how the stock table comes back. */
    public static final String RGBCURRENT = "/sys/class/dlpc343x/rgbcurrent";
    public static final String REDCURRENT = "/sys/class/dlpc343x/redcurrent";

    /** The SoC die sensors, in millidegrees. Only thermal_zone0 (pll) has cooling devices bound to it, at 75 C. */
    public static final String[] SOC_THERMAL = {
            "/sys/class/thermal/thermal_zone0/temp",   // pll
            "/sys/class/thermal/thermal_zone1/temp",   // ddr
            "/sys/class/thermal/thermal_zone2/temp",   // sar
    };

    /** The thermal governor's cooling devices, in {@link #COOLING_NAMES} order; cur_state is 0 when idle and counts up to max_state (11, 4, 5 and 2). */
    public static final String[] COOLING_DEVICES = {
            "/sys/class/thermal/cooling_device0/cur_state",   // thermal-cpufreq-0
            "/sys/class/thermal/cooling_device1/cur_state",   // thermal-cpucore-0
            "/sys/class/thermal/cooling_device2/cur_state",   // thermal-gpufreq-0
            "/sys/class/thermal/cooling_device3/cur_state",   // thermal-gpucore-0
    };

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
            }
        }
    }

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
                }
            }
        }
    }

    public static int readInt(String path, int fallback) {
        String s = read(path);
        if (s == null) {
            return fallback;
        }
        int v = Thermistor.parseAdc(s);
        return v == Thermistor.BAD_ADC ? fallback : v;
    }

    /** Write a bare value with no trailing newline, which is what the framework's set_fan_speed does. */
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
                }
            }
        }
    }

    public static boolean exists(String path) {
        try {
            return file(path).exists();
        } catch (Throwable t) {
            return false;
        }
    }
}
