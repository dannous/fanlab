package com.daleygames.fanlab;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * The DLPC3436's own registers, reached through {@code /sys/class/dlpc343x/picoreg}: the
 * system temperature (D6h) plus the CAIC, LABB and Look registers.
 *
 * The node is write-only on this firmware - its {@code show()} handler is NULL - so a reply
 * lands only in the kernel log, behind {@code READ_LOGS}. Every read therefore yields bytes or
 * a specific reason, never a guess; a decoded temperature is flagged provisional and its raw
 * 16-bit word recorded, because the unit interpretation is not confirmed. Nothing in the app
 * writes CAIC, LABB or the Looks any more; the decoders remain so the findings can be re-checked.
 */
public final class PicoReg {

    public static final String NODE = "/sys/class/dlpc343x/picoreg";

    /** DLPU078 SS 3.5.7, Read System Temperature. */
    public static final int OPCODE_SYSTEM_TEMPERATURE = 0xD6;
    public static final int SYSTEM_TEMPERATURE_LEN = 2;

    /** DLPU078 Write / Read LED Output Control Method: one byte, {@code 0x00} manual RGB LED currents (CAIC off, as the factory templates ship), {@code 0x01} CAIC on. */
    public static final int OPCODE_LED_OUTPUT_CONTROL_WRITE = 0x50;
    public static final int OPCODE_LED_OUTPUT_CONTROL_READ = 0x51;
    public static final int LED_OUTPUT_CONTROL_LEN = 1;

    /** DLPU078 Read CAIC LED Max Available Power: two bytes, little endian, watts x 100. Recorded, never acted on. */
    public static final int OPCODE_CAIC_MAX_POWER = 0x57;
    public static final int CAIC_MAX_POWER_LEN = 2;

    /** Nodes that do have a show() handler, so the LED drive can be recorded per step. */
    public static final String[] CURRENT_NODES = {
            "/sys/class/dlpc343x/rgbcurrent",
            "/sys/class/dlpc343x/redcurrent",
            "/sys/class/dlpc343x/greencurrent",
            "/sys/class/dlpc343x/bluecurrent",
            "/sys/class/dlpc343x/maxcurrent",
    };

    public static final String STATUS_OK = "ok";
    public static final String STATUS_UNAVAILABLE = "unavailable";

    public static final class Reading {
        public String status = STATUS_UNAVAILABLE;
        public String source;
        /** The raw 16-bit word as returned, or -1 if none was obtained. */
        public int rawWord = -1;
        /** Decoded degrees C, or NaN. Never a number unless {@link #status} is ok. */
        public double degC = Double.NaN;
        /** True whenever degC is present: the unit interpretation is not yet confirmed. */
        public boolean provisional;
        public String reason = "not attempted";

        public String rawHex() {
            return rawWord < 0 ? null : "0x" + pad4(Integer.toHexString(rawWord));
        }
    }

    private static String pad4(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < 4; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }

    private PicoReg() {
    }

    /** The command string for a read, e.g. {@code r d6 2}. */
    public static String readCommand(int opcode, int len) {
        return "r " + Integer.toHexString(opcode & 0xFF) + " " + Integer.toHexString(len & 0xFF);
    }

    /** The command string for a write, e.g. {@code w 52 1 7}. */
    public static String writeCommand(int opcode, int[] payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("w ").append(Integer.toHexString(opcode & 0xFF)).append(' ');
        sb.append(Integer.toHexString(payload == null ? 0 : payload.length));
        if (payload != null) {
            for (int i = 0; i < payload.length; i++) {
                sb.append(' ').append(Integer.toHexString(payload[i] & 0xFF));
            }
        }
        return sb.toString();
    }

    /** Pull a run of hex byte tokens out of arbitrary text: exactly {@code want} bytes, or null. A partial or over-long match returns null rather than a guess. */
    public static int[] parseHexBytes(String text, int want) {
        if (text == null || want <= 0) {
            return null;
        }
        int[] out = new int[want];
        int n = 0;
        int i = 0;
        int len = text.length();
        while (i < len && n < want) {
            char c = text.charAt(i);
            if (isHex(c)) {
                int start = i;
                while (i < len && isHex(text.charAt(i))) {
                    i++;
                }
                String tok = text.substring(start, i);
                if (i < len && (text.charAt(i) == 'x' || text.charAt(i) == 'X')) {
                    i++;
                    continue;
                }
                if (tok.length() > 2) {
                    if ((tok.length() & 1) != 0) {
                        return null;
                    }
                    for (int k = 0; k + 1 < tok.length() && n < want; k += 2) {
                        try {
                            out[n++] = Integer.parseInt(tok.substring(k, k + 2), 16) & 0xFF;
                        } catch (RuntimeException e) {
                            return null;
                        }
                    }
                    continue;
                }
                try {
                    out[n++] = Integer.parseInt(tok, 16) & 0xFF;
                } catch (RuntimeException e) {
                    return null;
                }
            } else {
                i++;
            }
        }
        return n == want ? out : null;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Pull the payload out of the driver's kernel-log line, printed as {@code "read 0x%02x data:"} followed by the bytes. Null if the line is not that response. */
    public static int[] parseKernelLogLine(String line, int opcode, int want) {
        if (line == null) {
            return null;
        }
        String lower = line.toLowerCase(java.util.Locale.US);
        String tag = "read 0x" + pad2(Integer.toHexString(opcode & 0xFF));
        int at = lower.indexOf(tag);
        if (at < 0) {
            return null;
        }
        int colon = lower.indexOf("data:", at);
        if (colon < 0) {
            return null;
        }
        return parseHexBytes(line.substring(colon + 5), want);
    }

    private static String pad2(String s) {
        return s.length() < 2 ? "0" + s : s;
    }

    /** Decode a D6h response: two bytes, little endian, bits 15:12 zero, bit 11 the sign, bits 10:0 the magnitude in degrees C. NaN means unavailable, never a guess. */
    public static double decodeSystemTemperature(int[] bytes) {
        if (bytes == null || bytes.length < 2) {
            return Double.NaN;
        }
        int word = (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8);
        return decodeSystemTemperatureWord(word);
    }

    /** As {@link #decodeSystemTemperature}, from an already-assembled 16-bit word. */
    public static double decodeSystemTemperatureWord(int word) {
        if (word < 0 || word > 0xFFFF) {
            return Double.NaN;
        }
        if ((word & 0xF000) != 0) {
            return Double.NaN;
        }
        int magnitude = word & 0x07FF;
        boolean negative = (word & 0x0800) != 0;
        double c = negative ? -magnitude : magnitude;
        if (c < -40.0 || c > 150.0) {
            return Double.NaN;
        }
        return c;
    }

    /** Assemble the little-endian word without decoding it, for the raw log column. */
    public static int word(int[] bytes) {
        if (bytes == null || bytes.length < 2) {
            return -1;
        }
        return (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8);
    }

    /** Turn a raw response into a {@link Reading}. */
    public static Reading fromResponseText(String text, String source) {
        Reading r = new Reading();
        if (text == null || text.trim().length() == 0) {
            r.reason = "no response bytes";
            return r;
        }
        int[] bytes = parseHexBytes(text, SYSTEM_TEMPERATURE_LEN);
        if (bytes == null) {
            r.reason = "the response did not contain " + SYSTEM_TEMPERATURE_LEN
                    + " hex bytes; treating it as unavailable rather than guessing";
            return r;
        }
        r.rawWord = word(bytes);
        double c = decodeSystemTemperature(bytes);
        if (Double.isNaN(c)) {
            r.reason = "the response was " + r.rawHex() + ", which is not a valid D6h "
                    + "reading (bits 15:12 must be zero and the result must be a possible "
                    + "temperature). Recorded as unavailable, not as a temperature.";
            return r;
        }
        r.status = STATUS_OK;
        r.source = source;
        r.degC = c;
        r.provisional = true;
        r.reason = "read from " + source + "; the unit interpretation is provisional, so "
                + "the raw word is logged beside it";
        return r;
    }

    /** Set false once the kernel-log route has proved unreachable, so it is not retried. */
    private static volatile boolean logRouteWorthTrying = true;

    public static void resetRouteLatch() {
        logRouteWorthTrying = true;
    }

    /** Try to read the DLPC system temperature. Never throws, never blocks on anything unbounded, and never returns a temperature it is not sure of. */
    public static Reading readSystemTemperature() {
        Reading r = new Reading();
        try {
            String cmd = readCommand(OPCODE_SYSTEM_TEMPERATURE, SYSTEM_TEMPERATURE_LEN);
            if (!Sysfs.exists(PicoReg.NODE)) {
                r.reason = PicoReg.NODE + " does not exist on this firmware";
                return r;
            }
            if (!Sysfs.write(PicoReg.NODE, cmd)) {
                r.reason = "cannot write \"" + cmd + "\" to " + PicoReg.NODE
                        + " (the node should be 0777; check the app is the API-28 build)";
                return r;
            }
            String back = Sysfs.read(PicoReg.NODE);
            if (back != null && !back.trim().startsWith(cmd)) {
                Reading fromNode = fromResponseText(back, "sysfs");
                if (STATUS_OK.equals(fromNode.status) || fromNode.rawWord >= 0) {
                    return fromNode;
                }
            }
            if (logRouteWorthTrying) {
                int[] bytes = fromKernelLog(OPCODE_SYSTEM_TEMPERATURE, SYSTEM_TEMPERATURE_LEN);
                if (bytes != null) {
                    double c = decodeSystemTemperature(bytes);
                    r.rawWord = word(bytes);
                    if (!Double.isNaN(c)) {
                        r.status = STATUS_OK;
                        r.source = "kernel log";
                        r.degC = c;
                        r.provisional = true;
                        r.reason = "recovered from the kernel log line the dlpc343x driver "
                                + "prints; unit interpretation is provisional, raw word logged";
                        return r;
                    }
                    r.reason = "the kernel log carried " + r.rawHex()
                            + ", which is not a valid D6h response";
                    return r;
                }
                logRouteWorthTrying = false;
            }
            r.reason = "the command was accepted but no response came back: "
                    + PicoReg.NODE + " has no show() handler on this firmware (it is one of "
                    + "the write-only dlpc343x attributes), and the driver's "
                    + "\"read 0x%02x data:\" line goes to the kernel log, which needs "
                    + "READ_LOGS. DLPC system temperature is UNAVAILABLE from user space "
                    + "on this build.";
            return r;
        } catch (Throwable t) {
            Reading bad = new Reading();
            bad.reason = "exception while reading: " + t;
            return bad;
        }
    }

    /** Run the attempt on a throwaway thread and give up after {@code timeoutMs}: a hung exec must not stall the 1 Hz loop holding the fan. */
    public static Reading readSystemTemperature(long timeoutMs) {
        final Reading[] slot = new Reading[1];
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    slot[0] = readSystemTemperature();
                }
            }, "fanlab-picoreg");
            t.setDaemon(true);
            t.start();
            t.join(timeoutMs);
            if (slot[0] != null) {
                return slot[0];
            }
            Reading r = new Reading();
            r.reason = "the picoreg round trip did not answer within " + timeoutMs + " ms";
            return r;
        } catch (Throwable e) {
            Reading r = new Reading();
            r.reason = "could not run the picoreg read: " + e;
            return r;
        }
    }

    /** Scan the tail of the kernel log for the driver's newest response to a read of {@code opcode}. */
    private static int[] fromKernelLog(int opcode, int want) {
        String[][] attempts = {
                {"logcat", "-d", "-b", "kernel", "-t", "300"},
                {"dmesg"},
        };
        for (int a = 0; a < attempts.length; a++) {
            Process p = null;
            BufferedReader in = null;
            try {
                p = Runtime.getRuntime().exec(attempts[a]);
                in = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                String line;
                int[] newest = null;
                int guard = 0;
                while ((line = in.readLine()) != null && guard++ < 2000) {
                    int[] bytes = parseKernelLogLine(line, opcode, want);
                    if (bytes != null) {
                        newest = bytes;
                    }
                }
                if (newest != null) {
                    return newest;
                }
            } catch (Throwable ignored) {
            } finally {
                if (in != null) {
                    try {
                        in.close();
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
        return null;
    }

    public static final String CAIC_ON = "on";
    public static final String CAIC_OFF = "off";
    public static final String CAIC_UNKNOWN = "unknown";

    public static final class CaicReading {
        public String state = CAIC_UNKNOWN;
        public String source;
        public int rawByte = -1;
        public String reason = "not attempted";

        public boolean known() {
            return CAIC_ON.equals(state) || CAIC_OFF.equals(state);
        }

        public String rawHex() {
            return rawByte < 0 ? null : "0x" + pad2(Integer.toHexString(rawByte & 0xFF));
        }
    }

    /** The command string for 0x50: {@code w 50 1 1} to turn CAIC on, {@code w 50 1 0} off. */
    public static String ledOutputControlCommand(boolean caic) {
        return writeCommand(OPCODE_LED_OUTPUT_CONTROL_WRITE, new int[]{caic ? 1 : 0});
    }

    /** Write the LED output control method: CAIC on or off. True means the bytes reached the node, not that the DLPC took the value. */
    public static boolean writeLedOutputControl(boolean caic) {
        if (!Sysfs.exists(NODE)) {
            return false;
        }
        return Sysfs.write(NODE, ledOutputControlCommand(caic));
    }

    /** Decode a 0x51 response byte: {@code 0x00} off, {@code 0x01} on, anything else unknown. */
    public static String decodeLedOutputControl(int[] bytes) {
        if (bytes == null || bytes.length < 1) {
            return CAIC_UNKNOWN;
        }
        int b = bytes[0] & 0xFF;
        return b == 0x00 ? CAIC_OFF : b == 0x01 ? CAIC_ON : CAIC_UNKNOWN;
    }

    public static CaicReading caicFromResponseText(String text, String source) {
        CaicReading r = new CaicReading();
        if (text == null || text.trim().length() == 0) {
            r.reason = "no response bytes";
            return r;
        }
        int[] bytes = parseHexBytes(text, LED_OUTPUT_CONTROL_LEN);
        if (bytes == null) {
            r.reason = "the response did not contain " + LED_OUTPUT_CONTROL_LEN
                    + " hex byte; treating it as unknown rather than guessing";
            return r;
        }
        return caicFromBytes(bytes, source);
    }

    private static CaicReading caicFromBytes(int[] bytes, String source) {
        CaicReading r = new CaicReading();
        r.rawByte = bytes[0] & 0xFF;
        String state = decodeLedOutputControl(bytes);
        if (CAIC_UNKNOWN.equals(state)) {
            r.reason = "the response was " + r.rawHex() + ", which is neither 0x00 (manual) "
                    + "nor 0x01 (CAIC), so it is not a valid 0x51 answer. Recorded as unknown.";
            return r;
        }
        r.state = state;
        r.source = source;
        r.reason = "read from " + source;
        return r;
    }

    /** Ask the DLPC which LED output control method it is using right now. Never answers on or off without the byte that says so. */
    public static CaicReading readLedOutputControl() {
        CaicReading r = new CaicReading();
        try {
            String cmd = readCommand(OPCODE_LED_OUTPUT_CONTROL_READ, LED_OUTPUT_CONTROL_LEN);
            if (!Sysfs.exists(NODE)) {
                r.reason = NODE + " does not exist on this firmware";
                return r;
            }
            if (!Sysfs.write(NODE, cmd)) {
                r.reason = "cannot write \"" + cmd + "\" to " + NODE
                        + " (the node should be 0777; check the app is the API-28 build)";
                return r;
            }
            String back = Sysfs.read(NODE);
            if (back != null && !back.trim().startsWith(cmd)) {
                CaicReading fromNode = caicFromResponseText(back, "sysfs");
                if (fromNode.known() || fromNode.rawByte >= 0) {
                    return fromNode;
                }
            }
            int[] bytes = fromKernelLog(OPCODE_LED_OUTPUT_CONTROL_READ, LED_OUTPUT_CONTROL_LEN);
            if (bytes != null) {
                CaicReading fromLog = caicFromBytes(bytes, "kernel log");
                if (fromLog.known()) {
                    fromLog.reason = "recovered from the newest \"read 0x51 data:\" line the "
                            + "dlpc343x driver printed to the kernel log";
                }
                return fromLog;
            }
            r.reason = "the command was accepted but no response came back: " + NODE
                    + " has no show() handler on this firmware, and the driver's "
                    + "\"read 0x%02x data:\" line goes to the kernel log, which needs "
                    + "READ_LOGS. CAIC state is UNKNOWN from this build.";
            return r;
        } catch (Throwable t) {
            CaicReading bad = new CaicReading();
            bad.reason = "exception while reading: " + t;
            return bad;
        }
    }

    /** As {@link #readLedOutputControl()}, on a throwaway thread, giving up after {@code timeoutMs}. */
    public static CaicReading readLedOutputControl(long timeoutMs) {
        final CaicReading[] slot = new CaicReading[1];
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    slot[0] = readLedOutputControl();
                }
            }, "fanlab-picoreg-caic");
            t.setDaemon(true);
            t.start();
            t.join(timeoutMs);
            if (slot[0] != null) {
                return slot[0];
            }
            CaicReading r = new CaicReading();
            r.reason = "the picoreg round trip did not answer within " + timeoutMs + " ms";
            return r;
        } catch (Throwable e) {
            CaicReading r = new CaicReading();
            r.reason = "could not run the picoreg read: " + e;
            return r;
        }
    }

    /** One attempt at 0x57, Read CAIC LED Max Available Power. Raw first, watts second. */
    public static final class CaicPower {
        public int rawWord = -1;
        /** {@code rawWord / 100} if DLPU078's "watts x 100" holds here; NaN otherwise. */
        public double watts = Double.NaN;
        /** True whenever watts is present: the unit interpretation is not confirmed on this board. */
        public boolean provisional;
        public String source;
        public String reason = "not attempted";

        public String rawHex() {
            return rawWord < 0 ? null : "0x" + pad4(Integer.toHexString(rawWord));
        }
    }

    /** Decode a 0x57 response. Recorded, never acted on; see {@link CaicPower}. */
    public static CaicPower caicPowerFromBytes(int[] bytes, String source) {
        CaicPower p = new CaicPower();
        if (bytes == null || bytes.length < CAIC_MAX_POWER_LEN) {
            p.reason = "no response bytes";
            return p;
        }
        p.rawWord = word(bytes);
        p.watts = p.rawWord / 100.0;
        p.provisional = true;
        p.source = source;
        p.reason = "read from " + source + "; watts assumes DLPU078's x100 scaling, which "
                + "has not been checked against this board, so the raw word is kept";
        return p;
    }

    /** Read the CAIC max available power (0x57). Only meaningful while CAIC is on, and only reachable through the kernel log. */
    public static CaicPower readCaicMaxPower() {
        CaicPower p = new CaicPower();
        try {
            String cmd = readCommand(OPCODE_CAIC_MAX_POWER, CAIC_MAX_POWER_LEN);
            if (!Sysfs.exists(NODE) || !Sysfs.write(NODE, cmd)) {
                p.reason = "cannot write \"" + cmd + "\" to " + NODE;
                return p;
            }
            String back = Sysfs.read(NODE);
            if (back != null && !back.trim().startsWith(cmd)) {
                int[] bytes = parseHexBytes(back, CAIC_MAX_POWER_LEN);
                if (bytes != null) {
                    return caicPowerFromBytes(bytes, "sysfs");
                }
            }
            int[] bytes = fromKernelLog(OPCODE_CAIC_MAX_POWER, CAIC_MAX_POWER_LEN);
            if (bytes != null) {
                return caicPowerFromBytes(bytes, "kernel log");
            }
            p.reason = "no 0x57 response in the kernel log";
            return p;
        } catch (Throwable t) {
            CaicPower bad = new CaicPower();
            bad.reason = "exception while reading: " + t;
            return bad;
        }
    }

    /** As {@link #readCaicMaxPower()}, bounded. */
    public static CaicPower readCaicMaxPower(long timeoutMs) {
        final CaicPower[] slot = new CaicPower[1];
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    slot[0] = readCaicMaxPower();
                }
            }, "fanlab-picoreg-caicpower");
            t.setDaemon(true);
            t.start();
            t.join(timeoutMs);
            if (slot[0] != null) {
                return slot[0];
            }
            CaicPower p = new CaicPower();
            p.reason = "the picoreg round trip did not answer within " + timeoutMs + " ms";
            return p;
        } catch (Throwable e) {
            CaicPower p = new CaicPower();
            p.reason = "could not run the picoreg read: " + e;
            return p;
        }
    }

    /** Read back what the DLPC says the LED drive is. These nodes do have show() handlers, unlike picoreg. Returns {@code name=value} pairs, semicolon separated; empty if nothing could be read. */
    public static String readLedCurrents() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CURRENT_NODES.length; i++) {
            String v = Sysfs.read(CURRENT_NODES[i]);
            if (v == null) {
                continue;
            }
            v = v.trim().replace('\n', ' ').replace(';', ' ').replace(',', ' ');
            if (v.length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            String name = CURRENT_NODES[i];
            sb.append(name.substring(name.lastIndexOf('/') + 1)).append('=').append(v);
        }
        return sb.toString();
    }

    /** One read on this channel: write the {@code r} command, then look for the answer in the node and then in the kernel log. Bytes or a reason, never a guess. */
    private static final class RoundTrip {
        int[] bytes;
        String source;
        String reason;
    }

    private static RoundTrip roundTrip(int opcode, int want) {
        RoundTrip t = new RoundTrip();
        String cmd = readCommand(opcode, want);
        if (!Sysfs.exists(NODE)) {
            t.reason = NODE + " does not exist on this firmware";
            return t;
        }
        if (!Sysfs.write(NODE, cmd)) {
            t.reason = "cannot write \"" + cmd + "\" to " + NODE
                    + " (the node should be 0777; check the app is the API-28 build)";
            return t;
        }
        String back = Sysfs.read(NODE);
        if (back != null && !back.trim().startsWith(cmd)) {
            int[] bytes = parseHexBytes(back, want);
            if (bytes != null) {
                t.bytes = bytes;
                t.source = "sysfs";
                return t;
            }
        }
        int[] bytes = fromKernelLog(opcode, want);
        if (bytes != null) {
            t.bytes = bytes;
            t.source = "kernel log";
            return t;
        }
        t.reason = "the command was accepted but no response came back: " + NODE
                + " has no show() handler on this firmware, and the driver's "
                + "\"read 0x%02x data:\" line goes to the kernel log, which needs "
                + "READ_LOGS. This build cannot see the answer.";
        return t;
    }

    /** DLPU078A Write / Read Local Area Brightness Boost Control. */
    public static final int OPCODE_LABB_WRITE = 0x80;
    public static final int OPCODE_LABB_READ = 0x81;
    /** The write takes two bytes: the control byte, then the strength. */
    public static final int LABB_WRITE_LEN = 2;
    /** The read answers four: control, strength, current gain, status. */
    public static final int LABB_READ_LEN = 4;

    /** DLPU078A Write / Read CAIC Image Processing Control. */
    public static final int OPCODE_CAIC_IMAGE_WRITE = 0x84;
    public static final int OPCODE_CAIC_IMAGE_READ = 0x85;
    public static final int CAIC_IMAGE_LEN = 3;

    /** The legal range for the maximum lumens gain, DLPU078A. A value outside it makes the controller reject the whole command, so {@link #encodeCaicGain} refuses rather than clamps. */
    public static final double CAIC_GAIN_MIN = 1.0;
    public static final double CAIC_GAIN_MAX = 4.0;

    /** Byte weights for the gain, DLPU078A: the byte is the gain times 32, so 1.0=0x20, 2.0=0x40, 4.0=0x80. */
    public static final int CAIC_GAIN_SCALE = 32;

    /** What this projector was found holding: 0x20, which is 1.0 -- no boost permitted. */
    public static final double CAIC_GAIN_STOCK = 1.0;
    public static final int CAIC_GAIN_STOCK_BYTE = 0x20;

    /** The clipping threshold the projector was found holding, byte 3 of 0x84. Carried through every write rather than zeroed, since the command sets all three bytes at once. */
    public static final int CAIC_CLIP_THRESHOLD_STOCK = 0x60;

    /** LABB control field values, DLPU078A: 0h Disabled, 1h Enabled; 2h and 3h reserved. */
    public static final int LABB_CONTROL_DISABLED = 0x0;
    public static final int LABB_CONTROL_ENABLED = 0x1;

    /** What the projector was found holding: strength 128, sharpness 1, control disabled. */
    public static final int LABB_STRENGTH_STOCK = 0x80;
    public static final int LABB_SHARPNESS_STOCK = 1;
    public static final int LABB_STRENGTH_MAX = 255;
    public static final int LABB_SHARPNESS_MAX = 15;

    /** The CAIC maximum lumens gain as its fixed-point byte, or -1 for a gain outside {@link #CAIC_GAIN_MIN}..{@link #CAIC_GAIN_MAX}; the controller rejects the whole command on an out-of-range parameter. */
    public static int encodeCaicGain(double gain) {
        if (Double.isNaN(gain) || gain < CAIC_GAIN_MIN || gain > CAIC_GAIN_MAX) {
            return -1;
        }
        return (int) Math.round(gain * CAIC_GAIN_SCALE) & 0xFF;
    }

    /** The inverse: the byte as a gain. NaN when there is no byte, never 0. */
    public static double decodeCaicGain(int b) {
        if (b < 0 || b > 0xFF) {
            return Double.NaN;
        }
        return (b & 0xFF) / (double) CAIC_GAIN_SCALE;
    }

    /** The 0x84 command string, e.g. {@code w 84 3 0 40 60} for a 2.0 gain. Byte 1 is held at 0 because its b7 enables a debug overlay. Null if the gain is out of range. */
    public static String caicImageControlCommand(double gain, int clipThreshold) {
        int g = encodeCaicGain(gain);
        if (g < 0) {
            return null;
        }
        return writeCommand(OPCODE_CAIC_IMAGE_WRITE,
                new int[]{0x00, g, clipThreshold & 0xFF});
    }

    /** The LABB control byte: sharpness strength in b7:4, the control field in b1:0 - measured on hardware, not the b3:2 the first version assumed. Enabling what the projector was found holding is {@code 0x11}. */
    public static int labbControlByte(int sharpness, boolean enabled) {
        int s = sharpness < 0 ? 0 : (sharpness > LABB_SHARPNESS_MAX ? LABB_SHARPNESS_MAX : sharpness);
        return (s << 4) | (enabled ? LABB_CONTROL_ENABLED : LABB_CONTROL_DISABLED);
    }

    /** The sharpness strength out of a control byte, 0..15. */
    public static int labbSharpnessOf(int controlByte) {
        return (controlByte >> 4) & 0x0F;
    }

    /** The raw control field out of a control byte: 0 disabled, 1 enabled, 2 and 3 reserved. */
    public static int labbControlOf(int controlByte) {
        return controlByte & 0x03;
    }

    /** The 0x80 command string, e.g. {@code w 80 2 11 80}. Strength is 0..255, a dial rather than a multiplier: DLPU078A says the gain varies with image content. */
    public static String labbCommand(boolean enabled, int strength, int sharpness) {
        return writeCommand(OPCODE_LABB_WRITE,
                new int[]{labbControlByte(sharpness, enabled), strength & 0xFF});
    }

    /** One attempt at reading the CAIC image processing control back (0x85). */
    public static final class CaicImage {
        /** True only when three bytes actually arrived. Never inferred from a write. */
        public boolean known;
        /** b7 of byte 1: the CAIC gain debug overlay. This app never sets it. */
        public boolean gainDisplay;
        public int gainByte = -1;
        public double gain = Double.NaN;
        public int clipThreshold = -1;
        public String source;
        public String reason = "not attempted";

        public String summary() {
            return known ? "gain " + fmtGain(gain) + " (0x" + pad2(Integer.toHexString(gainByte))
                    + "), clip " + clipThreshold : null;
        }
    }

    /** Decode a 0x85 response. Three bytes or nothing; a short reply is not a reading. */
    public static CaicImage caicImageFromBytes(int[] bytes, String source) {
        CaicImage r = new CaicImage();
        if (bytes == null || bytes.length < CAIC_IMAGE_LEN) {
            r.reason = "no response bytes";
            return r;
        }
        r.gainDisplay = (bytes[0] & 0x80) != 0;
        r.gainByte = bytes[1] & 0xFF;
        r.gain = decodeCaicGain(r.gainByte);
        r.clipThreshold = bytes[2] & 0xFF;
        r.known = true;
        r.source = source;
        r.reason = "read from " + source;
        return r;
    }

    public static CaicImage caicImageFromResponseText(String text, String source) {
        CaicImage r = new CaicImage();
        if (text == null || text.trim().length() == 0) {
            r.reason = "no response bytes";
            return r;
        }
        int[] bytes = parseHexBytes(text, CAIC_IMAGE_LEN);
        if (bytes == null) {
            r.reason = "the response did not contain " + CAIC_IMAGE_LEN
                    + " hex bytes; treating it as unread rather than guessing";
            return r;
        }
        return caicImageFromBytes(bytes, source);
    }

    public static final class Labb {
        /** True only when four bytes arrived and the control field was one of the two DLPU078A defines; a reserved 2h or 3h is a wrong answer, not a third state. */
        public boolean known;
        public boolean enabled;
        public int control = -1;
        /** Byte 2, the strength, 0..255, or -1. */
        public int strength = -1;
        /** The sharpness strength out of byte 1, 0..15, or -1. */
        public int sharpness = -1;
        /** Byte 3, the current LABB gain, read-only. Kept raw: this projector answers 0x20, outside Table 3-81's stated 1..8, so the units are unsettled. */
        public int gainRaw = -1;
        /** Byte 4, further status. Recorded, not interpreted. */
        public int status = -1;
        public String source;
        public String reason = "not attempted";

        public String summary() {
            return known ? (enabled ? "on" : "off") + ", strength " + strength
                    + ", sharpness " + sharpness
                    + ", gain 0x" + pad2(Integer.toHexString(gainRaw)) : null;
        }
    }

    /** Decode a 0x81 response: four bytes, control field 0h or 1h, everything else unknown. */
    public static Labb labbFromBytes(int[] bytes, String source) {
        Labb r = new Labb();
        if (bytes == null || bytes.length < LABB_READ_LEN) {
            r.reason = "no response bytes";
            return r;
        }
        r.control = labbControlOf(bytes[0]);
        r.sharpness = labbSharpnessOf(bytes[0]);
        r.strength = bytes[1] & 0xFF;
        r.gainRaw = bytes[2] & 0xFF;
        r.status = bytes[3] & 0xFF;
        if (r.control != LABB_CONTROL_DISABLED && r.control != LABB_CONTROL_ENABLED) {
            r.reason = "the control field read " + r.control + "h, which DLPU078A reserves. "
                    + "Recorded as unknown rather than rounded to a state.";
            return r;
        }
        r.enabled = r.control == LABB_CONTROL_ENABLED;
        r.known = true;
        r.source = source;
        r.reason = "read from " + source;
        return r;
    }

    public static Labb labbFromResponseText(String text, String source) {
        Labb r = new Labb();
        if (text == null || text.trim().length() == 0) {
            r.reason = "no response bytes";
            return r;
        }
        int[] bytes = parseHexBytes(text, LABB_READ_LEN);
        if (bytes == null) {
            r.reason = "the response did not contain " + LABB_READ_LEN
                    + " hex bytes; treating it as unread rather than guessing";
            return r;
        }
        return labbFromBytes(bytes, source);
    }

    /** Write the CAIC image processing control (0x84): the gain budget, and the clipping threshold restated as found. False means the gain was out of range - nothing was written - or the node write failed. */
    public static boolean writeCaicImageControl(double gain, int clipThreshold) {
        String cmd = caicImageControlCommand(gain, clipThreshold);
        if (cmd == null || !Sysfs.exists(NODE)) {
            return false;
        }
        return Sysfs.write(NODE, cmd);
    }

    /** Ask the DLPC what gain budget CAIC has (0x85). Never invents one. */
    public static CaicImage readCaicImageControl() {
        try {
            RoundTrip t = roundTrip(OPCODE_CAIC_IMAGE_READ, CAIC_IMAGE_LEN);
            if (t.bytes == null) {
                CaicImage r = new CaicImage();
                r.reason = t.reason;
                return r;
            }
            return caicImageFromBytes(t.bytes, t.source);
        } catch (Throwable e) {
            CaicImage r = new CaicImage();
            r.reason = "exception while reading: " + e;
            return r;
        }
    }

    /** As {@link #readCaicImageControl()}, bounded, on a thread of its own. */
    public static CaicImage readCaicImageControl(long timeoutMs) {
        final CaicImage[] slot = new CaicImage[1];
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    slot[0] = readCaicImageControl();
                }
            }, "fanlab-picoreg-caicimage");
            t.setDaemon(true);
            t.start();
            t.join(timeoutMs);
            if (slot[0] != null) {
                return slot[0];
            }
            CaicImage r = new CaicImage();
            r.reason = "the picoreg round trip did not answer within " + timeoutMs + " ms";
            return r;
        } catch (Throwable e) {
            CaicImage r = new CaicImage();
            r.reason = "could not run the picoreg read: " + e;
            return r;
        }
    }

    /** Write the LABB control (0x80). True means the bytes reached the node, not that the DLPC took them. */
    public static boolean writeLabb(boolean enabled, int strength, int sharpness) {
        if (!Sysfs.exists(NODE)) {
            return false;
        }
        return Sysfs.write(NODE, labbCommand(enabled, strength, sharpness));
    }

    /** Ask the DLPC what LABB is doing (0x81). Never answers on or off without the bytes. */
    public static Labb readLabb() {
        try {
            RoundTrip t = roundTrip(OPCODE_LABB_READ, LABB_READ_LEN);
            if (t.bytes == null) {
                Labb r = new Labb();
                r.reason = t.reason;
                return r;
            }
            return labbFromBytes(t.bytes, t.source);
        } catch (Throwable e) {
            Labb r = new Labb();
            r.reason = "exception while reading: " + e;
            return r;
        }
    }

    /** As {@link #readLabb()}, bounded, on a thread of its own. */
    public static Labb readLabb(long timeoutMs) {
        final Labb[] slot = new Labb[1];
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    slot[0] = readLabb();
                }
            }, "fanlab-picoreg-labb");
            t.setDaemon(true);
            t.start();
            t.join(timeoutMs);
            if (slot[0] != null) {
                return slot[0];
            }
            Labb r = new Labb();
            r.reason = "the picoreg round trip did not answer within " + timeoutMs + " ms";
            return r;
        } catch (Throwable e) {
            Labb r = new Labb();
            r.reason = "could not run the picoreg read: " + e;
            return r;
        }
    }

    /** DLPU078A SS 3.1.16 Write Look Select: one byte, the Look number. */
    public static final int OPCODE_LOOK_SELECT_WRITE = 0x22;
    /** DLPU078A SS 3.1.17 Read Look Select: Look number, sequence number, frame rate. */
    public static final int OPCODE_LOOK_SELECT_READ = 0x23;
    public static final int LOOK_SELECT_LEN = 6;

    /** DLPU078A SS 3.1.18 Read Sequence Header Attributes: thirty bytes, two identical fifteen-byte blocks - the Look's copy first, then the Sequence's. Only the six duty bytes at the head of each are read. */
    public static final int OPCODE_SEQUENCE_HEADER_READ = 0x26;
    public static final int SEQUENCE_HEADER_LEN = 30;
    /** Where the Sequence block's copy of the same three duty cycles starts. */
    private static final int SEQUENCE_BLOCK_AT = 15;

    /** Duty cycle is UQ8.8: a little-endian sixteen-bit word, high byte whole percent, low byte 256ths (not 255ths). 40 % is {@code 00 28}. */
    public static final int DUTY_SCALE = 256;

    /** DLPU078A: "The sum of the three duty cycles must add up to 100." */
    public static final double DUTY_SUM = 100.0;

    /** How far the three duty cycles may miss 100 before the reading is refused, percent. One UQ8.8 LSB is 1/256 of a percent, so this is above any rounding. */
    public static final double DUTY_SUM_TOLERANCE = 0.5;

    /** How many Looks this projector answered for, 0..18. */
    public static final int LOOK_COUNT = 19;

    /** The one Look with a neutral split, and the only one the kernel ever selects. */
    public static final int LOOK_NEUTRAL = 0;

    /** The command string for 0x22, e.g. {@code w 22 1 f} to select Look 15. */
    public static String lookSelectCommand(int number) {
        return writeCommand(OPCODE_LOOK_SELECT_WRITE, new int[]{number & 0xFF});
    }

    /** One UQ8.8 duty word as a percentage. NaN for a word that is not there. */
    public static double decodeDuty(int lo, int hi) {
        if (lo < 0 || hi < 0) {
            return Double.NaN;
        }
        return (((hi & 0xFF) << 8) | (lo & 0xFF)) / (double) DUTY_SCALE;
    }

    public static final class Look {

        /** True only when the three duty cycles arrived and summed to 100. */
        public boolean known;

        /** The Look number from 0x23, or -1 if that read did not land. */
        public int number = -1;
        /** The sequence number from 0x23, or -1. */
        public int sequence = -1;

        /** The three duty cycles as percentages of frame time, or NaN. */
        public double red = Double.NaN;
        public double green = Double.NaN;
        public double blue = Double.NaN;

        public boolean blocksAgree;

        public String source;
        public String reason = "not attempted";

        public boolean neutral() {
            return number == LOOK_NEUTRAL;
        }

        public String summary() {
            if (!known) {
                return null;
            }
            return "Look " + (number < 0 ? "?" : Integer.toString(number))
                    + (sequence < 0 ? "" : " (seq " + sequence + ")")
                    + ", " + fmtGain(red) + "/" + fmtGain(green) + "/" + fmtGain(blue)
                    + " R/G/B" + (blocksAgree ? "" : " [blocks DISAGREE]");
        }
    }

    /** Decode a 0x23 response into an existing reading: byte 0 the Look, byte 1 the sequence. */
    public static void lookSelectIntoBytes(Look r, int[] bytes) {
        if (r == null || bytes == null || bytes.length < LOOK_SELECT_LEN) {
            return;
        }
        r.number = bytes[0] & 0xFF;
        r.sequence = bytes[1] & 0xFF;
    }

    /** Decode a 0x26 response: the Look block's three duty cycles, checked against the Sequence block's copy and against the sum TI requires. */
    public static Look sequenceHeaderFromBytes(int[] bytes, String source) {
        Look r = new Look();
        if (bytes == null || bytes.length < SEQUENCE_HEADER_LEN) {
            r.reason = "no response bytes";
            return r;
        }
        r.red = decodeDuty(bytes[0], bytes[1]);
        r.green = decodeDuty(bytes[2], bytes[3]);
        r.blue = decodeDuty(bytes[4], bytes[5]);
        r.blocksAgree = bytes[0] == bytes[SEQUENCE_BLOCK_AT]
                && bytes[1] == bytes[SEQUENCE_BLOCK_AT + 1]
                && bytes[2] == bytes[SEQUENCE_BLOCK_AT + 2]
                && bytes[3] == bytes[SEQUENCE_BLOCK_AT + 3]
                && bytes[4] == bytes[SEQUENCE_BLOCK_AT + 4]
                && bytes[5] == bytes[SEQUENCE_BLOCK_AT + 5];
        double sum = r.red + r.green + r.blue;
        if (Math.abs(sum - DUTY_SUM) > DUTY_SUM_TOLERANCE) {
            r.reason = "the three duty cycles summed to " + fmtGain(sum)
                    + ", and DLPU078A requires 100. Treating the reading as unread rather "
                    + "than reporting a split the controller did not give.";
            return r;
        }
        r.known = true;
        r.source = source;
        r.reason = "read from " + source;
        return r;
    }

    public static Look sequenceHeaderFromResponseText(String text, String source) {
        Look r = new Look();
        if (text == null || text.trim().length() == 0) {
            r.reason = "no response bytes";
            return r;
        }
        int[] bytes = parseHexBytes(text, SEQUENCE_HEADER_LEN);
        if (bytes == null) {
            r.reason = "the response did not contain " + SEQUENCE_HEADER_LEN
                    + " hex bytes; treating it as unread rather than guessing";
            return r;
        }
        return sequenceHeaderFromBytes(bytes, source);
    }

    /** Ask the DLPC which Look is selected and how it splits the frame: 0x26 for the split, then 0x23 for the number. */
    public static Look readLook() {
        try {
            RoundTrip split = roundTrip(OPCODE_SEQUENCE_HEADER_READ, SEQUENCE_HEADER_LEN);
            if (split.bytes == null) {
                Look r = new Look();
                r.reason = split.reason;
                return r;
            }
            Look r = sequenceHeaderFromBytes(split.bytes, split.source);
            RoundTrip which = roundTrip(OPCODE_LOOK_SELECT_READ, LOOK_SELECT_LEN);
            if (which.bytes != null) {
                lookSelectIntoBytes(r, which.bytes);
            }
            return r;
        } catch (Throwable e) {
            Look r = new Look();
            r.reason = "exception while reading: " + e;
            return r;
        }
    }

    /** As {@link #readLook()}, bounded, on a thread of its own. */
    public static Look readLook(long timeoutMs) {
        final Look[] slot = new Look[1];
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    slot[0] = readLook();
                }
            }, "fanlab-picoreg-look");
            t.setDaemon(true);
            t.start();
            t.join(timeoutMs);
            if (slot[0] != null) {
                return slot[0];
            }
            Look r = new Look();
            r.reason = "the picoreg round trip did not answer within " + timeoutMs + " ms";
            return r;
        } catch (Throwable e) {
            Look r = new Look();
            r.reason = "could not run the picoreg read: " + e;
            return r;
        }
    }

    static String fmtGain(double v) {
        if (Double.isNaN(v)) {
            return "?";
        }
        long tenths = Math.round(v * 10.0);
        return (tenths / 10) + "." + Math.abs(tenths % 10);
    }
}
