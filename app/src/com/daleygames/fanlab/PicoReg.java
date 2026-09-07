package com.daleygames.fanlab;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * The DLPC3436's own temperature sensor, reached through {@code /sys/class/dlpc343x/picoreg}.
 *
 * <h3>Why this is a precondition and not a bonus column</h3>
 * The 75 C over-temperature shutdown reads the <b>red-LED thermistor</b>. The DMD's own
 * long-term limit is 70 C <b>array</b> temperature, which the DLP230NP datasheet says
 * "cannot be measured directly and must be computed analytically" from test point TP1 via
 * a 9.0 C/W package thermal resistance. The relationship between the LED thermistor and
 * the DMD array temperature is a board constant nobody has established. Until we know how
 * a controller-side temperature tracks the LED-side one, a low fan floor cannot honestly
 * be called safe for the DMD - only for the LED. {@code Read System Temperature (D6h)} is
 * a second, independent sensor, plausibly much closer to the optical engine.
 *
 * So: if it cannot be read, this class says so loudly and specifically. It never guesses,
 * and a parse failure is reported as <b>unavailable</b>, never as a temperature.
 *
 * <h3>What is actually known about the channel</h3>
 * <ul>
 *   <li>{@code picoreg} is raw DLPC3436 command access and is {@code chmod 777} by
 *       {@code init.amlogic.board.rc}. Format {@code w <op-hex> <len-hex> <byte-hex>...}
 *       and {@code r <op-hex> <len-hex>} (research/05 SS A4, corroborated three ways:
 *       kernel format strings, {@code /system/bin/2D_command.sh}, and
 *       {@code sc_projectorclient}'s own {@code "w 52 1 0"}).</li>
 *   <li>{@code D6h} returns the system temperature from an external thermistor as two
 *       bytes, signed-magnitude degrees C, with bits 15:12 zero (DLPU078 SS 3.5.7).</li>
 *   <li><b>But the attribute's {@code show} handler is NULL on this firmware</b>
 *       (research/02 SS 8: {@code picoreg} is listed write-only, alongside {@code vmirror},
 *       {@code curtain}, {@code pattern}, {@code splash}, {@code flip} and {@code threeD}).
 *       The driver prints the response with {@code "read 0x%02x data:"} - to the kernel
 *       log, not back through sysfs. So the obvious round trip almost certainly does not
 *       close from user space on a stock device.</li>
 * </ul>
 * That is a finding, not a reason to skip the attempt: the disassembly is a strong
 * expectation, not an observation of this running unit, and the cost of trying is one
 * write and one read. This class therefore tries, in order: read the node back, then the
 * kernel log (which needs {@code READ_LOGS}, so realistically only the system variant),
 * and if neither yields bytes it reports exactly which door was shut.
 *
 * <h3>Units are recorded raw</h3>
 * The decode below reads bit 11 as the sign and bits 10:0 as whole degrees. The raw
 * 16-bit word is <b>always</b> recorded alongside it, so if the units turn out to be
 * tenths, or the sign bit sits elsewhere, the trace can be reinterpreted offline without
 * re-running the sweep. Every decoded value is flagged {@code provisional} for that reason.
 *
 * <h3>Also here: the CAIC toggle</h3>
 * The same node carries the one write this app makes to the display controller, the LED
 * output control method (0x50), and its read-back (0x51). It is the same channel with
 * the same write-only problem, so it lives in the same class; see the CAIC section.
 *
 * Pure Java (Sysfs + java.lang only), so the headless test drives it against a stub tree.
 */
public final class PicoReg {

    public static final String NODE = "/sys/class/dlpc343x/picoreg";

    /** DLPU078 SS 3.5.7, Read System Temperature. */
    public static final int OPCODE_SYSTEM_TEMPERATURE = 0xD6;
    public static final int SYSTEM_TEMPERATURE_LEN = 2;

    /**
     * DLPU078 Write / Read LED Output Control Method: one byte, {@code 0x00} manual RGB LED
     * currents (CAIC off, which is how the factory {@code picosetting} templates ship,
     * every one of them {@code caic=0x00}) and {@code 0x01} CAIC on. See
     * {@link #writeLedOutputControl} for what CAIC is and what is not known about it here.
     */
    public static final int OPCODE_LED_OUTPUT_CONTROL_WRITE = 0x50;
    public static final int OPCODE_LED_OUTPUT_CONTROL_READ = 0x51;
    public static final int LED_OUTPUT_CONTROL_LEN = 1;

    /**
     * DLPU078 Read CAIC LED Max Available Power: two bytes, little endian, watts x 100.
     * Only meaningful while CAIC is on; recorded raw, for the log, and never acted on.
     */
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

    /** One attempt at the D6h round trip. */
    public static final class Reading {
        /** {@link #STATUS_OK} or {@link #STATUS_UNAVAILABLE}. Never anything else. */
        public String status = STATUS_UNAVAILABLE;
        /** Where the bytes came from: "sysfs", "kernel log", or null. */
        public String source;
        /** The raw 16-bit word as returned, or -1 if none was obtained. */
        public int rawWord = -1;
        /** Decoded degrees C, or NaN. Never a number unless {@link #status} is ok. */
        public double degC = Double.NaN;
        /** True whenever degC is present: the unit interpretation is not yet confirmed. */
        public boolean provisional;
        /** Specific, quotable reason. Always set, including on success. */
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

    // ------------------------------------------------------------------ formatting

    /** The command string for a read, e.g. {@code r d6 2}. */
    public static String readCommand(int opcode, int len) {
        return "r " + Integer.toHexString(opcode & 0xFF) + " " + Integer.toHexString(len & 0xFF);
    }

    /** The command string for a write, e.g. {@code w 52 1 7}. The CAIC toggle is the one writer. */
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

    // ------------------------------------------------------------------ parsing

    /**
     * Pull a run of hex byte tokens out of arbitrary text.
     *
     * @param want how many bytes are expected.
     * @return exactly {@code want} bytes, or null if the text did not contain them. A
     *         partial or over-long match returns null rather than a guess.
     */
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
                // "0x" prefixes arrive as a separate token pair; skip the bare "0" that
                // precedes an 'x', and skip anything wider than a byte.
                if (i < len && (text.charAt(i) == 'x' || text.charAt(i) == 'X')) {
                    i++;
                    continue;
                }
                if (tok.length() > 2) {
                    // A packed run such as "2b00": take it two characters at a time.
                    // An odd length is not a byte string, so refuse it outright.
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

    /**
     * Pull the payload out of the driver's own kernel-log line, which is printed as
     * {@code "read 0x%02x data:"} followed by the bytes.
     *
     * @return the bytes, or null if this line is not a D6h read response.
     */
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

    /**
     * Decode a D6h response: two bytes, little endian, bits 15:12 zero, bit 11 the sign,
     * bits 10:0 the magnitude in degrees C.
     *
     * @return degrees C, or NaN if the word does not have the documented shape or the
     *         result is not a physically possible temperature. NaN means <i>unavailable</i>,
     *         never zero and never a guess.
     */
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
            // DLPU078 says the top nibble reads zero. A non-zero nibble means this is not
            // a D6h response, or the byte order is not what we assumed. Refuse it.
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

    // ------------------------------------------------------------------ the attempt

    /**
     * Turn a raw response into a {@link Reading}.
     *
     * Split out from the I/O so the host test can drive every outcome - a good response,
     * a malformed one, an impossible temperature, and nothing at all - without needing a
     * kernel node that behaves like the real one.
     *
     * @param text   whatever came back, in whatever shape.
     * @param source where it came from, for the record.
     */
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

    /** Reset the "stop retrying the log" latch. For the tests. */
    public static void resetRouteLatch() {
        logRouteWorthTrying = true;
    }

    /**
     * Try to read the DLPC system temperature. Never throws, never blocks on anything
     * unbounded, and never returns a temperature it is not sure of.
     */
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
            // 1. the node itself. The disassembly says its show() is NULL, so this is
            //    expected to fail - but it costs one read to find out on real hardware.
            String back = Sysfs.read(PicoReg.NODE);
            // An echo of the command is not a response. Without this guard a node that
            // simply plays back what was written would be decoded as if it were data,
            // which is exactly the kind of quiet fiction this class must not produce.
            if (back != null && !back.trim().startsWith(cmd)) {
                Reading fromNode = fromResponseText(back, "sysfs");
                if (STATUS_OK.equals(fromNode.status) || fromNode.rawWord >= 0) {
                    return fromNode;
                }
            }
            // 2. the kernel log. The driver prints "read 0x%02x data:" there. Reading it
            //    needs READ_LOGS, which an ordinary app does not have.
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

    /**
     * Run the attempt on a throwaway thread and give up after {@code timeoutMs}.
     *
     * The 1 Hz control loop calls this. An {@code exec} that hangs must not be able to
     * stall the loop that is holding the fan at a low duty, so the wait is bounded and a
     * timeout is reported as unavailable like any other failure.
     */
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

    /**
     * Scan the tail of the kernel log for the driver's response to a read of
     * {@code opcode}, and return the newest one.
     *
     * Newest, because the log is a history: a response to the same opcode from a minute
     * ago is still in it, and this cannot tell that line from the one the read just made.
     * Callers that poll therefore learn what the DLPC last answered, which may lag one
     * poll behind a change; they do not learn nothing, and they never learn a fiction,
     * because the line is the driver's own and carries the opcode.
     */
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
                // no READ_LOGS, no dmesg, or exec refused: try the next, then give up
            } finally {
                if (in != null) {
                    try {
                        in.close();
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
        return null;
    }

    // ------------------------------------------------------------------ CAIC

    /*
     * CAIC is Content Adaptive Illumination Control, the DLPC3436's IntelliBright feature.
     * Per frame it lowers the LED current and raises the DMD duty cycle by the same factor
     * on content that does not need full output, so the displayed white point holds while
     * the LEDs draw less. TI's datasheet example is 27 % LED power saved at constant
     * brightness. Stock has it OFF: caic=0x00 in every template of the factory picosetting
     * blob, and no Java caller of Pico.setProjectorCaic anywhere in the firmware.
     *
     * What is NOT known, and why this is an experiment rather than a feature:
     *
     *   This board has no TI DLPA LED driver. The LED currents are set by the kernel over
     *   SPI to two MAX20096 drivers, and the DLPC's own RGB current registers hold a
     *   nominal 13. CAIC's power saving comes from the DLPC lowering LED current itself,
     *   through a DLPA it does not have here. So it may be unable to lower real LED current
     *   at all, in which case it does only the duty-cycle half -- a brighter image at the
     *   same LED power -- or nothing, or shows artefacts if its LUTs were never calibrated
     *   for this engine. Nothing in this class claims a power saving, and nothing in the
     *   documentation should either.
     *
     * The write is runtime only: "w 50 1 1" turns it on, "w 50 1 0" turns it off, and a
     * power cycle turns it off regardless because the DLPC re-loads picosetting at boot.
     * The picosetting partition (/dev/block/mmcblk2p21) is deliberately never touched --
     * that would persist across reboots, and the point of a one-write undo is that it is
     * one write.
     *
     * Reading it back has the same problem D6h has: picoreg's show() is NULL on this
     * firmware, so the response only appears in the kernel log, which needs READ_LOGS,
     * which only the system variant holds. The reading below is therefore tri-state --
     * on, off, or unknown with a reason -- and "unknown" is the answer whenever the bytes
     * did not actually arrive. It is never inferred from what was written.
     */

    public static final String CAIC_ON = "on";
    public static final String CAIC_OFF = "off";
    public static final String CAIC_UNKNOWN = "unknown";

    /** One attempt at reading the LED output control method back (0x51). */
    public static final class CaicReading {
        /** {@link #CAIC_ON}, {@link #CAIC_OFF} or {@link #CAIC_UNKNOWN}. Never anything else. */
        public String state = CAIC_UNKNOWN;
        /** Where the byte came from: "sysfs", "kernel log", or null. */
        public String source;
        /** The raw byte as returned, or -1 if none was obtained. */
        public int rawByte = -1;
        /** Specific, quotable reason. Always set, including on success. */
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

    /**
     * Write the LED output control method: CAIC on or off.
     *
     * @return whatever {@link Sysfs#write} said. True means the bytes reached the node
     *         and the stream closed cleanly. It does <b>not</b> mean the DLPC took the
     *         value; only {@link #readLedOutputControl} can say that, and only where the
     *         kernel log is readable.
     */
    public static boolean writeLedOutputControl(boolean caic) {
        if (!Sysfs.exists(NODE)) {
            return false;
        }
        return Sysfs.write(NODE, ledOutputControlCommand(caic));
    }

    /**
     * Decode a 0x51 response byte. {@code 0x00} is off, {@code 0x01} is on, and anything
     * else -- including no byte at all -- is unknown, because DLPU078 defines only those two
     * values and a third is not a state, it is a wrong answer.
     */
    public static String decodeLedOutputControl(int[] bytes) {
        if (bytes == null || bytes.length < 1) {
            return CAIC_UNKNOWN;
        }
        int b = bytes[0] & 0xFF;
        return b == 0x00 ? CAIC_OFF : b == 0x01 ? CAIC_ON : CAIC_UNKNOWN;
    }

    /**
     * Turn a raw 0x51 response into a {@link CaicReading}. Split out from the I/O for the
     * same reason {@link #fromResponseText} is: so the host test can drive every outcome.
     */
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

    /**
     * Ask the DLPC which LED output control method it is using right now. Never throws,
     * never blocks on anything unbounded, and never answers on or off without the byte
     * that says so.
     *
     * The kernel-log route is tried regardless of the D6h latch. That latch records that
     * the log yielded nothing for a temperature read; whether it yields anything for this
     * opcode is a separate question, and the caller ({@code FanService}) already restricts
     * this to the variant that can read the log at all.
     */
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
            // 1. the node itself; expected to fail, see readSystemTemperature. The same
            //    echo guard: a node that plays back the command is not answering it.
            String back = Sysfs.read(NODE);
            if (back != null && !back.trim().startsWith(cmd)) {
                CaicReading fromNode = caicFromResponseText(back, "sysfs");
                if (fromNode.known() || fromNode.rawByte >= 0) {
                    return fromNode;
                }
            }
            // 2. the kernel log.
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
        /** The raw 16-bit little-endian word, or -1 if none was obtained. */
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

    /**
     * Read the CAIC max available power. Only meaningful while CAIC is on, and only
     * reachable through the kernel log, like everything else on this node. For the log.
     */
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

    // ------------------------------------------------------------------ LED currents

    /**
     * Read back what the DLPC says the LED drive is, so the log records what power the
     * light engine was actually drawing at each step. These nodes do have show() handlers
     * ({@code rgbcurrent} reads command 0x55 plus the four SPI channels), unlike picoreg.
     *
     * @return {@code name=value} pairs, semicolon separated; empty if nothing could be read.
     */
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

    // ------------------------------------------------------------------ the shared read

    /**
     * One read on this channel: write the {@code r} command, then look for the answer in
     * the node and then in the kernel log.
     *
     * The route is the same for every opcode and the reason it is this shape is above:
     * {@code picoreg}'s {@code show()} is NULL on this firmware, so the reply lands in the
     * kernel log behind {@code READ_LOGS}. Bytes or a reason, never a guess.
     */
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
        // The node itself first. An echo of the command is not a response: without this
        // guard a node that plays back what was written would be decoded as if it were data.
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

    // ------------------------------------------------------------------ image processing

    /*
     * The two IntelliBright image-processing controls, and which half of it this board can
     * actually run.
     *
     * CAIC (above, 0x50) selects the method. What it is *allowed to do* once selected lives
     * in a second command, Write CAIC Image Processing Control (0x84), and reading it back
     * (0x85) on this projector answered
     *
     *     00 20 60
     *
     * -- gain display off, maximum lumens gain 0x20, clipping threshold 96. And 0x20 in
     * that fixed-point byte is 1.0, which is the bottom of the legal range: CAIC was being
     * selected with permission to raise the image by nothing at all. That is the most
     * likely reason switching 0x50 on alone produced nothing measurable on the hardware --
     * the method was chosen and the budget was zero.
     *
     * LABB (0x80/0x81) is the other half of IntelliBright, and DLPU078A describes it as
     * adaptively gaining up darker parts of the image to achieve an overall brighter one.
     * It is supported in TPG, splash and external input mode, and auto-disabled in curtain
     * mode. The projector reads back
     *
     *     10 80 20 00
     *
     * -- sharpness strength 1, LABB control 0h = Disabled, strength already preset to 128,
     * current gain 0x20. So the strength this board would run at has been chosen by
     * somebody; the feature is simply switched off.
     *
     * Why LABB is the one that can work here. CAIC's mechanism is the controller lowering
     * LED current, and this board has no TI DLPA LED driver for it to lower current
     * through: the LED currents are driven by the SoC over SPI to two MAX20096 chips the
     * DLPC cannot reach, and the DLPC's own current registers sit at a nominal 13 and go
     * nowhere. So even with the gain budget fixed, CAIC may still achieve nothing here.
     * LABB is pure DMD-side image processing and needs no LED control at all. Neither is
     * claimed to work; only one of them has a mechanism that does not depend on hardware
     * this board does not have.
     */

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

    /**
     * The legal range for the maximum lumens gain, DLPU078A.
     *
     * <b>A value outside it is rejected as an invalid write parameter and the command does
     * not execute</b> -- the whole command, not just the offending byte. So this is not a
     * clamp for tidiness: sending 0.9 would silently leave the gain, the gain-display bit
     * and the clipping threshold all at whatever they were, and the app would have no way
     * to know. {@link #encodeCaicGain} refuses instead.
     */
    public static final double CAIC_GAIN_MIN = 1.0;
    public static final double CAIC_GAIN_MAX = 4.0;

    /**
     * Byte weights for the gain, DLPU078A: b7=2^2, b6=2^1, b5=2^0, b4=2^-1, b3=2^-2,
     * b2=2^-3, b1=2^-4, b0=2^-5. The least significant bit is a thirty-second, so the byte
     * is simply the gain times 32: 1.0=0x20, 1.5=0x30, 2.0=0x40, 4.0=0x80.
     */
    public static final int CAIC_GAIN_SCALE = 32;

    /** What this projector was found holding: 0x20, which is 1.0 -- no boost permitted. */
    public static final double CAIC_GAIN_STOCK = 1.0;
    public static final int CAIC_GAIN_STOCK_BYTE = 0x20;

    /**
     * The clipping threshold the projector was found holding, byte 3 of 0x84.
     *
     * Carried through every write rather than zeroed. The command sets all three bytes at
     * once, so writing the gain means restating this, and restating it as anything other
     * than what the machine had would be changing a setting nobody asked to change.
     */
    public static final int CAIC_CLIP_THRESHOLD_STOCK = 0x60;

    /** LABB control field values, DLPU078A: 0h Disabled, 1h Enabled; 2h and 3h reserved. */
    public static final int LABB_CONTROL_DISABLED = 0x0;
    public static final int LABB_CONTROL_ENABLED = 0x1;

    /** What the projector was found holding: strength 128, sharpness 1, control disabled. */
    public static final int LABB_STRENGTH_STOCK = 0x80;
    public static final int LABB_SHARPNESS_STOCK = 1;
    public static final int LABB_STRENGTH_MAX = 255;
    public static final int LABB_SHARPNESS_MAX = 15;

    /**
     * The CAIC maximum lumens gain as its fixed-point byte.
     *
     * @return the byte, or <b>-1 for a gain outside {@link #CAIC_GAIN_MIN}..
     *         {@link #CAIC_GAIN_MAX}</b>. Refusing rather than clamping is deliberate: the
     *         controller rejects the whole command on an out-of-range parameter, so an app
     *         that sent one would believe it had set a gain it had not, and the caller has
     *         to be able to tell that apart from a write that failed.
     */
    public static int encodeCaicGain(double gain) {
        if (Double.isNaN(gain) || gain < CAIC_GAIN_MIN || gain > CAIC_GAIN_MAX) {
            return -1;
        }
        // Rounding to the nearest thirty-second can move the value by at most 1/64, and the
        // two ends of the range are exactly representable, so this can never round out of
        // range: 1.0 -> 0x20 and 4.0 -> 0x80 are both exact.
        return (int) Math.round(gain * CAIC_GAIN_SCALE) & 0xFF;
    }

    /** The inverse: the byte as a gain. NaN when there is no byte, never 0. */
    public static double decodeCaicGain(int b) {
        if (b < 0 || b > 0xFF) {
            return Double.NaN;
        }
        return (b & 0xFF) / (double) CAIC_GAIN_SCALE;
    }

    /**
     * The 0x84 command string, e.g. {@code w 84 3 0 40 60} for a 2.0 gain.
     *
     * Byte 1 is held at 0. Its b7 is the CAIC gain <i>display</i> enable -- a five-bar debug
     * overlay that DLPU078A says "must never be used for normal operation" -- and b6 scales
     * that overlay. Nothing here has any reason to switch a debug overlay on, so the bit is
     * a constant rather than a setting.
     *
     * @return the command, or null if the gain is out of range; see {@link #encodeCaicGain}.
     */
    public static String caicImageControlCommand(double gain, int clipThreshold) {
        int g = encodeCaicGain(gain);
        if (g < 0) {
            return null;
        }
        return writeCommand(OPCODE_CAIC_IMAGE_WRITE,
                new int[]{0x00, g, clipThreshold & 0xFF});
    }

    /**
     * The LABB control byte: sharpness strength in b7:4, the control field in b3:2, b1:0
     * reserved and left clear.
     *
     * Sharpness is carried through rather than owned by the enable, because DLPU078A ties
     * the two together -- "The LABB function must be enabled to make use of sharpness" --
     * so turning LABB on with sharpness zeroed would quietly drop a setting the machine
     * already had. Enabling what the projector was found holding is {@code 0x14}.
     */
    public static int labbControlByte(int sharpness, boolean enabled) {
        int s = sharpness < 0 ? 0 : (sharpness > LABB_SHARPNESS_MAX ? LABB_SHARPNESS_MAX : sharpness);
        return (s << 4) | ((enabled ? LABB_CONTROL_ENABLED : LABB_CONTROL_DISABLED) << 2);
    }

    /** The sharpness strength out of a control byte, 0..15. */
    public static int labbSharpnessOf(int controlByte) {
        return (controlByte >> 4) & 0x0F;
    }

    /** The raw control field out of a control byte: 0 disabled, 1 enabled, 2 and 3 reserved. */
    public static int labbControlOf(int controlByte) {
        return (controlByte >> 2) & 0x03;
    }

    /**
     * The 0x80 command string, e.g. {@code w 80 2 14 80} to enable LABB at the strength and
     * sharpness the projector was already holding.
     *
     * Strength is 0..255 where DLPU078A says 0 is no boost and 255 "the maximum boost
     * viable in a product" -- and that "the strength is not a direct indication of the
     * gain, since the gain varies depending on the image content". So it is a dial, not a
     * multiplier, and nothing here converts it into one.
     */
    public static String labbCommand(boolean enabled, int strength, int sharpness) {
        return writeCommand(OPCODE_LABB_WRITE,
                new int[]{labbControlByte(sharpness, enabled), strength & 0xFF});
    }

    /** One attempt at reading the CAIC image processing control back (0x85). */
    public static final class CaicImage {
        /** True only when three bytes actually arrived. Never inferred from a write. */
        public boolean known;
        /** b7 of byte 1: the debug overlay. This app never sets it; a true here is someone else's. */
        public boolean gainDisplay;
        /** The raw fixed-point gain byte, or -1. */
        public int gainByte = -1;
        /** The gain it decodes to, or NaN. */
        public double gain = Double.NaN;
        /** Byte 3, the clipping threshold, or -1. */
        public int clipThreshold = -1;
        /** Where the bytes came from: "sysfs", "kernel log", or null. */
        public String source;
        /** Specific, quotable reason. Always set, including on success. */
        public String reason = "not attempted";

        /** "gain 1.0 (0x20), clip 96", or null when nothing was read. */
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

    /** As {@link #caicImageFromBytes}, from arbitrary response text. For the host test. */
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

    /** One attempt at reading the LABB control back (0x81). */
    public static final class Labb {
        /**
         * True only when four bytes arrived <i>and</i> the control field was one of the two
         * DLPU078A defines. A reserved 2h or 3h is a wrong answer, not a third state.
         */
        public boolean known;
        /** Whether LABB is running. Meaningless unless {@link #known}. */
        public boolean enabled;
        /** The raw control field, 0..3, or -1. */
        public int control = -1;
        /** Byte 2, the strength, 0..255, or -1. */
        public int strength = -1;
        /** The sharpness strength out of byte 1, 0..15, or -1. */
        public int sharpness = -1;
        /**
         * Byte 3, the current LABB gain, read-only.
         *
         * Kept raw and deliberately not converted. Table 3-81 gives the range as 1..8, and
         * this projector answers 0x20 = 32, which is not in that range -- so either the
         * units are not whole gain steps or the table does not describe this firmware.
         * Recording the byte lets that be settled later; inventing a gain from it would not.
         */
        public int gainRaw = -1;
        /** Byte 4, further status. Recorded, not interpreted. */
        public int status = -1;
        /** Where the bytes came from: "sysfs", "kernel log", or null. */
        public String source;
        /** Specific, quotable reason. Always set, including on success. */
        public String reason = "not attempted";

        /** "on, strength 128, sharpness 1, gain 0x20", or null when nothing was read. */
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

    /** As {@link #labbFromBytes}, from arbitrary response text. For the host test. */
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

    /**
     * Write the CAIC image processing control: the gain budget CAIC is allowed to work
     * within, and the clipping threshold restated as found.
     *
     * @return false either because the gain is out of range -- in which case
     *         <b>nothing was written</b>, deliberately, since the controller would have
     *         rejected the command anyway -- or because the node write failed. Callers hold
     *         the gain to {@link #CAIC_GAIN_MIN}..{@link #CAIC_GAIN_MAX} before getting
     *         here, so in practice a false is a node problem.
     */
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

    /**
     * Write the LABB control: enabled or not, at this strength, keeping this sharpness.
     *
     * @return whatever {@link Sysfs#write} said. True means the bytes reached the node, not
     *         that the DLPC took them; only {@link #readLabb} can say that, and only where
     *         the kernel log is readable.
     */
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

    /**
     * A gain to one decimal, without {@link Sample} -- which is on the pure side too, but
     * this class is used by the sweep report and should not grow a dependency for one
     * number.
     */
    static String fmtGain(double v) {
        if (Double.isNaN(v)) {
            return "?";
        }
        long tenths = Math.round(v * 10.0);
        return (tenths / 10) + "." + Math.abs(tenths % 10);
    }
}
