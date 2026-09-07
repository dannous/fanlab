package com.daleygames.fanlab;

/**
 * The LED drive override: run the light engine harder than the brightness mode asks for,
 * but only while this app is the thing cooling it.
 *
 * <h3>What the kernel does, and what this rides on</h3>
 * The {@code dlpc343x} driver maps the brightness mode ({@code rgblevel}) to a static
 * per-channel LED drive table and applies that table on every {@code rgblevel} write.
 * The values are percent of the driver's own per-channel maximum, and channel 1 -- the
 * node called {@code redcurrent} -- is driven lower than the other three:
 * <pre>
 *   rgblevel  mode           ch1 (redcurrent)   ch0 / ch2 / ch3
 *   4         Super Eco      20                 20
 *   1         Eco            36                 40
 *   2         Normal         48                 55
 *   3         Presentation   71                 76
 * </pre>
 * Two nodes are writable by an app: {@code rgbcurrent} sets all four channels to one
 * value, and {@code redcurrent} sets channel 1 alone. {@code b2current} is not writable.
 * So the table's shape is reproduced at a new level {@code L} by writing
 * {@code rgbcurrent = L} and then {@code redcurrent = round(L * stockRed / stockOther)}
 * for the mode in force -- 71/76 in Presentation, 48/55 in Normal, 36/40 in Eco and 1:1
 * in Super Eco. {@link #redFor} is that rule.
 *
 * <h3>Restoring is free, and the kernel does it for us anyway</h3>
 * Those writes last only until the next {@code rgblevel} write, which reinstates the
 * stock table exactly: a mode change from the remote, a boot, or the sweep's own
 * {@code assertRgbLevel}. That is also the cleanest restore this class has -- rewrite
 * {@code rgblevel} with the value it already holds -- and it is used for every handback.
 * It has one side effect worth knowing: the stock ladder's mode-change branch fires and
 * slams that tier's fan floor into {@code fan_ctrl} within one 15 s poll. The loop
 * re-asserts within a second, and the same thing already happens on every mode change,
 * so it is tolerated; but it is why {@code rgblevel} rewrites are rate-limited to one
 * per {@link #RESTORE_EVERY_MS}, the same interval {@code assertRgbLevel} uses.
 *
 * <h3>The hazard, and the rule that prevents it</h3>
 * Raising the LED drive while this app is not the fan controller decouples light output
 * from cooling: the stock ladder picks its fan floor from {@code rgblevel}, so it would
 * be running the Eco fan ladder under Presentation-class LED heat, with nothing watching
 * the difference. The override is therefore applied only while every one of these holds,
 * and the caller passes the conjunction in as {@code allowed}:
 * <ul>
 *   <li>{@link Mode#controls} is true -- CURVE or LINEAR, the two modes where the app is
 *       the temperature controller and the stock ladder is stood down;</li>
 *   <li>no AUTO or VERIFY session is running (they own {@code rgblevel} themselves);</li>
 *   <li>{@code led_status} is not 0;</li>
 *   <li>the fail-safe is not latched;</li>
 *   <li>the machine is awake.</li>
 * </ul>
 * In every other state the stock table is put back. On top of that there is a ceiling of
 * its own: above {@link #DEFAULT_TRIP_C} on the LED thermistor the override is dropped
 * and <b>latched off until the brightness mode or the configuration changes</b>. No
 * automatic re-enable, because brightness cycling is visible on the wall in a way a fan
 * swing is not, and a controller that trips and re-arms every few minutes would flicker.
 *
 * <h3>What it costs thermally</h3>
 * From the measured plant, each +10 on the Presentation level adds roughly +3.4 C on the
 * LED thermistor at a fixed fan duty. Under CURVE that is paid in temperature, under
 * LINEAR in fan. The 75 C shutdown and the fan-stall watchdog are untouched either way.
 *
 * <h3>Why the ceiling is 97 and not 100 -- and why overflowing goes <i>dim</i></h3>
 * The percent this class writes is not the hardware's unit. The driver converts it to a
 * 7-bit DAC code, and the conversion and its clamp were read off the live device:
 * <pre>
 *   code = (30 * mA + 40000) / 1968          integer division
 *   if (code &gt; 0x7F) code = 0x3F;            NOT a saturation -- a drop to 63
 * </pre>
 * The kernel logs the absolute current on every {@code rgbcurrent} read, which is what
 * makes the scale measurable rather than assumed:
 * <pre>
 *   dlp_spi_get_current reg(0x4) = 0x8066, current = 5357 ma, percent = 75
 *   dlp_spi_get_current reg(0x3) = 0x8060, current = 4964 ma, percent = 70
 * </pre>
 * So 100 % is about <b>7.1 A</b> per channel and Presentation's 76/71 runs at about
 * 5.4 A / 5.0 A. Putting 7.1 A through the formula gives a code of 129, and the clamp then
 * writes <b>0x3F -- about 2.8 A, roughly 40 %</b>. An overflow does not peg the channel at
 * maximum; it makes the picture suddenly go <i>dim</i>, which is the failure that looks
 * like a fault rather than like an over-request. The code crosses 127 at about 99 %, so
 * {@link #MAX_LEVEL} is <b>97</b>: three points of margin below a cliff, at a level whose
 * own code is 125.
 *
 * <h3>Reading back</h3>
 * {@code rgbcurrent} has a show handler whose text looks like
 * {@code red_current=13 green_current=13 blue_current=13 duty_r=75 duty_g=70 duty_b=75
 * duty_b2=75}. {@code duty_b} (channel 3) reflects the "other" value and {@code duty_r}
 * (channel 1) the red one, each <b>one below what was written</b> -- the table's 76 reads
 * 75. The handler is also racy, and the glitch is now explained rather than merely
 * tolerated: a failed SPI read hands the driver {@code 0x8080}, from which it computes
 * {@code current = -1333 ma, percent = -18}, and sysfs prints that percent as an unsigned
 * byte -- which is exactly where the observed 238 and 241 come from. <b>Any field above
 * 100 is a failed read</b>, so it makes the whole reading unreadable, never a mismatch.
 * {@link #parseReadback} is that rule, and {@link #matches} is the off-by-one.
 *
 * Pure Java. The decision ({@link #decide}) has no side effects beyond its own memory,
 * and the writes ({@link #perform}) go through {@link Sysfs}, so the host test drives
 * both against a stub tree.
 */
public final class LedDrive {

    /** Stock "other" (channels 0/2/3) drive by rgblevel; index 0 is unused. */
    public static final int[] STOCK_OTHER = {0, 40, 55, 76, 20};

    /** Stock channel-1 ({@code redcurrent}) drive by rgblevel; index 0 is unused. */
    public static final int[] STOCK_RED = {0, 36, 48, 71, 20};

    /**
     * Bounds on a configured level, percent.
     *
     * 20 is Super Eco's stock drive and there is no reason to go below it: dimmer than the
     * dimmest mode is a fan setting looking for a purpose.
     *
     * <b>97, not 100.</b> The driver's clamp is {@code if (code > 0x7F) code = 0x3F} -- an
     * over-request drops the channel to about 40 % instead of pegging it at maximum -- and
     * the code crosses 127 at about 99 %. The class comment has the arithmetic and the
     * measured currents it was derived from. 97 is the highest level with margin below
     * that cliff, and {@link Config#sanitise()} holds every stored and broadcast level to
     * it, so a request for 100 becomes 97 rather than a dim picture.
     */
    public static final int MIN_LEVEL = 20;
    public static final int MAX_LEVEL = 97;

    /** The four tiers in the order the config stores them. */
    public static final int TIERS = 4;
    public static final int[] TIER_RGBLEVEL = {4, 1, 2, 3};
    public static final String[] TIER_NAMES = {"Super Eco", "Eco", "Normal", "Presentation"};

    /** Fields in an encoded {@code d1} line, including the version tag. */
    public static final int FIELDS = 5;

    /**
     * LED thermistor temperature above which the override is dropped and latched off.
     *
     * 57.0: two degrees above the 55 C at which the stock controller itself commands
     * maximum fan, so the override cannot hold the light engine in a region the
     * manufacturer's own software treats as out of its comfort zone, and well below the
     * 75 C shutdown.
     */
    public static final double DEFAULT_TRIP_C = 57.0;

    /** Minimum gap between {@code rgblevel} rewrites. Matches {@code assertRgbLevel}. */
    public static final long RESTORE_EVERY_MS = 5000L;

    /**
     * Minimum gap between re-applies driven by a read-back mismatch, or after a failed
     * write. An {@code rgblevel} edge or the first apply is never held back by this; it
     * only stops a node that keeps disagreeing from being rewritten every second.
     */
    public static final long REAPPLY_EVERY_MS = 5000L;

    // ------------------------------------------------------------------ config

    /** The four target levels, Super Eco / Eco / Normal / Presentation. */
    public static final class Config {

        /** Percent of the driver's per-channel maximum for the "other" channels, by tier. */
        public final int[] level = new int[TIERS];

        public Config() {
            setStock();
        }

        /** The kernel's own table: 20, 40, 55, 76. */
        public void setStock() {
            for (int i = 0; i < TIERS; i++) {
                level[i] = STOCK_OTHER[TIER_RGBLEVEL[i]];
            }
        }

        /** The one-press preset the main screen offers: +10, +10, +15, +14 over stock. */
        public static Config bright() {
            Config c = new Config();
            c.level[0] = 30;
            c.level[1] = 50;
            c.level[2] = 70;
            c.level[3] = 90;
            return c;
        }

        /** Equal to the kernel's table, in which case there is nothing to apply. */
        public boolean isStock() {
            for (int i = 0; i < TIERS; i++) {
                if (level[i] != STOCK_OTHER[TIER_RGBLEVEL[i]]) {
                    return false;
                }
            }
            return true;
        }

        /** The configured level for a brightness mode, or -1 for one this class does not know. */
        public int levelFor(int rgblevel) {
            int t = tierOf(rgblevel);
            return t < 0 ? -1 : level[t];
        }

        /**
         * Repair anything a user or a corrupt preferences file could have put in here.
         * Called on every load and after every edit.
         */
        public void sanitise() {
            for (int i = 0; i < TIERS; i++) {
                if (level[i] < MIN_LEVEL) {
                    level[i] = MIN_LEVEL;
                }
                if (level[i] > MAX_LEVEL) {
                    level[i] = MAX_LEVEL;
                }
            }
        }

        /** Serialise to a single line, for SharedPreferences: {@code d1,20,40,55,76}. */
        public String encode() {
            StringBuilder sb = new StringBuilder("d1");
            for (int i = 0; i < TIERS; i++) {
                sb.append(',').append(level[i]);
            }
            return sb.toString();
        }

        /**
         * Parse a line produced by {@link #encode()}. Anything unparseable falls back to
         * stock: a broken setting must never leave the LEDs driven at a level nobody chose.
         */
        public static Config decode(String s) {
            Config c = new Config();
            if (s == null) {
                return c;
            }
            try {
                String[] f = s.split(",");
                if (f.length < FIELDS || !"d1".equals(f[0].trim())) {
                    return c;
                }
                for (int i = 0; i < TIERS; i++) {
                    c.level[i] = Integer.parseInt(f[i + 1].trim());
                }
            } catch (RuntimeException e) {
                c.setStock();
            }
            c.sanitise();
            return c;
        }

        /** {@code 30·50·70·90}, for the screen. */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < TIERS; i++) {
                if (i > 0) {
                    sb.append('·');
                }
                sb.append(level[i]);
            }
            return sb.toString();
        }
    }

    /** Index into a {@link Config} for a brightness mode, or -1 for one that is not in the table. */
    public static int tierOf(int rgblevel) {
        for (int i = 0; i < TIERS; i++) {
            if (TIER_RGBLEVEL[i] == rgblevel) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Channel 1's drive for a given "other" level, keeping the stock table's ratio for
     * that mode: {@code round(level * stockRed / stockOther)}. Presentation 90 gives 84;
     * Super Eco is 1:1. An unknown mode gets the level unchanged, which is the only ratio
     * there is any evidence for.
     */
    public static int redFor(int rgblevel, int level) {
        if (rgblevel < 1 || rgblevel >= STOCK_OTHER.length || STOCK_OTHER[rgblevel] <= 0) {
            return level;
        }
        return (int) Math.round(level * (double) STOCK_RED[rgblevel] / STOCK_OTHER[rgblevel]);
    }

    // ------------------------------------------------------------------ read-back

    /**
     * Pull {@code duty_r} and {@code duty_b} out of the {@code rgbcurrent} show text.
     *
     * @return {@code {red, other}}, or null if either field is missing, unparseable, or
     *         above 100. Above 100 is the handler's failed-SPI glitch -- {@code 0x8080}
     *         read back, {@code percent = -18} computed, printed as an unsigned byte, which
     *         is the 238 and 241 seen in the field -- and it must read as "could not tell"
     *         rather than as the kernel having overwritten the drive.
     */
    public static int[] parseReadback(String text) {
        if (text == null) {
            return null;
        }
        int r = field(text, "duty_r=");
        int b = field(text, "duty_b=");
        if (r < 0 || b < 0 || r > 100 || b > 100) {
            return null;
        }
        return new int[]{r, b};
    }

    private static int field(String text, String key) {
        int at = text.indexOf(key);
        if (at < 0) {
            return -1;
        }
        int i = at + key.length();
        int end = i;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == i) {
            return -1;
        }
        try {
            return Integer.parseInt(text.substring(i, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Does a read-back field agree with what was written? The show handler reports one
     * below the written value (76 reads 75). The exact value is accepted too, so a
     * firmware that stops doing that does not turn every tick into a rewrite; a genuine
     * overwrite by the kernel differs by ten or more.
     */
    public static boolean matches(int read, int written) {
        return read == written || read == written - 1;
    }

    // ------------------------------------------------------------------ decision

    /** What a tick should write, if anything. */
    public static final class Plan {
        public static final int NONE = 0;
        /** Write {@link #other} to {@code rgbcurrent}, then {@link #red} to {@code redcurrent}. */
        public static final int APPLY = 1;
        /**
         * Rewrite {@code rgblevel} with its current value, which reinstates the stock
         * table. {@link #rgblevel} is the mode the override was applied under; if the mode
         * has moved since, the kernel has already done this and nothing is written.
         */
        public static final int RESTORE = 2;

        public int action = NONE;
        public int rgblevel = -1;
        public int other = -1;
        public int red = -1;
        /** For the tick's note; empty when nothing worth recording happened. */
        public String note = "";
        /** Whether an override was believed to be on the hardware before this plan. */
        boolean wasOverriding;
        int wasOther;
        int wasRed;
        int wasRgblevel;
    }

    /** The trip temperature in force. A field so the test can move it; the app uses the default. */
    public double tripC = DEFAULT_TRIP_C;

    /** True while this class believes its own values are on the hardware. */
    private boolean overriding;
    private int appliedRgblevel = -1;
    private int appliedOther = -1;
    private int appliedRed = -1;
    private long lastRestoreMs = Long.MIN_VALUE / 2;
    private long lastApplyMs = Long.MIN_VALUE / 2;
    /** No apply before this instant; set by {@link #perform} after a failed write. */
    private long retryAfterMs = Long.MIN_VALUE / 2;

    private boolean tripped;
    private int trippedRgblevel = -1;
    private String trippedConfig = "";
    private double trippedAtC = Double.NaN;

    /**
     * Decide what this tick should write. No side effects on the hardware; the memory of
     * what was last written is updated here on the assumption that {@link #perform} will
     * be called with the result, and {@link #perform} undoes that assumption if a write
     * fails.
     *
     * @param cfg      the configured levels, or null when the feature is off. A stock
     *                 config is treated exactly as off.
     * @param rgblevel the brightness mode as read this tick, -1 if unreadable.
     * @param allowed  the coupling rule from the class comment, evaluated by the caller.
     * @param ledC     the LED thermistor, or NaN when there is no plausible reading.
     * @param readback the {@code rgbcurrent} show text this tick, or null if not read.
     * @param nowMs    a monotonic clock.
     */
    public synchronized Plan decide(Config cfg, int rgblevel, boolean allowed, double ledC,
                                    String readback, long nowMs) {
        Plan p = new Plan();
        p.wasOverriding = overriding;
        p.wasOther = appliedOther;
        p.wasRed = appliedRed;
        p.wasRgblevel = appliedRgblevel;

        boolean enabled = cfg != null && !cfg.isStock();
        String key = cfg == null ? "" : cfg.encode();

        // The latch releases on the two events that make the trip stale: a different
        // brightness mode means a different LED load, and a different config means the
        // owner has changed their mind. Nothing else releases it -- see the class comment.
        if (tripped && (rgblevel != trippedRgblevel || !key.equals(trippedConfig))) {
            tripped = false;
            p.note = "leddrive trip released";
        }

        int tier = tierOf(rgblevel);
        boolean want = enabled && allowed && tier >= 0 && !tripped;

        if (want && !Double.isNaN(ledC) && ledC > tripC) {
            tripped = true;
            trippedRgblevel = rgblevel;
            trippedConfig = key;
            trippedAtC = ledC;
            want = false;
            p.note = "LEDDRIVE TRIP " + Sample.fmt1(ledC) + "C>" + Sample.fmt1(tripC)
                    + " -> stock until rgblevel or config changes";
        }

        if (!want) {
            if (overriding) {
                if (rgblevel != appliedRgblevel) {
                    // A mode change already made the kernel reapply the stock table for
                    // the new level, so there is nothing left to restore.
                    overriding = false;
                    appliedOther = -1;
                    appliedRed = -1;
                    p.note = join(p.note, "leddrive->stock(rgblevel " + rgblevel + ")");
                } else if (nowMs - lastRestoreMs >= RESTORE_EVERY_MS) {
                    p.action = Plan.RESTORE;
                    p.rgblevel = rgblevel;
                    lastRestoreMs = nowMs;
                    overriding = false;
                    appliedOther = -1;
                    appliedRed = -1;
                    p.note = join(p.note, "leddrive->stock");
                }
            }
            return p;
        }

        int other = cfg.levelFor(rgblevel);
        int red = redFor(rgblevel, other);
        if (nowMs < retryAfterMs) {
            // A write failed a moment ago. Sysfs already logged why; do not add a failed
            // write a second to it.
            return p;
        }
        boolean edge = !overriding || rgblevel != appliedRgblevel || other != appliedOther
                || red != appliedRed;
        if (edge) {
            apply(p, rgblevel, other, red, nowMs);
            p.note = join(p.note, "leddrive<-" + other + "/" + red + "@" + rgblevel);
            return p;
        }

        // Steady state: trust the hardware only as far as it can be read. A read that
        // disagrees by more than the handler's off-by-one is the kernel having reapplied
        // the stock table underneath us -- something wrote rgblevel with the same value --
        // and the answer is to put the override back, at most once per REAPPLY_EVERY_MS.
        int[] rb = parseReadback(readback);
        if (rb != null && !(matches(rb[0], red) && matches(rb[1], other))
                && nowMs - lastApplyMs >= REAPPLY_EVERY_MS) {
            apply(p, rgblevel, other, red, nowMs);
            p.note = join(p.note, "leddrive rewrite(read " + rb[1] + "/" + rb[0] + ")");
        }
        return p;
    }

    private void apply(Plan p, int rgblevel, int other, int red, long nowMs) {
        p.action = Plan.APPLY;
        p.rgblevel = rgblevel;
        p.other = other;
        p.red = red;
        overriding = true;
        appliedRgblevel = rgblevel;
        appliedOther = other;
        appliedRed = red;
        lastApplyMs = nowMs;
    }

    /**
     * The handback for paths that are not a tick: release, a session starting, and
     * service stop. Ignores the rate limit, because a handback is a one-off and a
     * duplicate {@code rgblevel} write is harmless; does nothing at all unless an override
     * is believed to be on the hardware. {@link #perform} checks the mode is still the one
     * the override was applied under before it writes anything.
     */
    public synchronized Plan forceRestore(long nowMs) {
        Plan p = new Plan();
        p.wasOverriding = overriding;
        p.wasOther = appliedOther;
        p.wasRed = appliedRed;
        p.wasRgblevel = appliedRgblevel;
        if (!overriding) {
            return p;
        }
        overriding = false;
        appliedOther = -1;
        appliedRed = -1;
        p.action = Plan.RESTORE;
        p.rgblevel = appliedRgblevel;
        lastRestoreMs = nowMs;
        p.note = "leddrive->stock";
        return p;
    }

    /**
     * Do the writes a plan asks for, through {@link Sysfs}. Never throws.
     *
     * @return true if every write succeeded. On failure the memory {@link #decide} set is
     *         rolled back so the next tick tries again -- after {@link #REAPPLY_EVERY_MS}
     *         for an apply, so an unwritable node is not hammered every second, and after
     *         {@link #RESTORE_EVERY_MS} for a restore.
     */
    public synchronized boolean perform(Plan p) {
        if (p == null || p.action == Plan.NONE) {
            return true;
        }
        try {
            if (p.action == Plan.APPLY) {
                boolean okOther = Sysfs.write(Sysfs.RGBCURRENT, Integer.toString(p.other));
                boolean okRed = okOther && Sysfs.write(Sysfs.REDCURRENT, Integer.toString(p.red));
                if (!okOther) {
                    // Nothing reached the hardware. Forget the attempt entirely and try
                    // again after the backoff.
                    overriding = p.wasOverriding;
                    appliedRgblevel = p.wasRgblevel;
                    appliedOther = p.wasOther;
                    appliedRed = p.wasRed;
                    retryAfterMs = lastApplyMs + REAPPLY_EVERY_MS;
                } else if (!okRed) {
                    // All four channels took the new level but channel 1 did not get its
                    // ratio, which is a state nobody asked for -- brighter red than the
                    // table's shape. It is an override, so a handback restores it; and the
                    // missing red is an edge, so the next tick after the backoff retries.
                    appliedRed = -1;
                    retryAfterMs = lastApplyMs + REAPPLY_EVERY_MS;
                }
                return okRed;
            }
            if (p.action == Plan.RESTORE) {
                // Re-read immediately before writing, rather than trusting the value the
                // tick started with. A session's assertRgbLevel runs earlier in the same
                // tick and may have moved the mode; rewriting the old value here would move
                // it back. And if the mode has moved, the kernel has already reinstated the
                // stock table, so there is nothing left to write.
                int cur = Sysfs.readInt(Sysfs.RGBLEVEL, -1);
                if (cur >= 0 && cur != p.rgblevel) {
                    return true;
                }
                boolean ok = cur >= 0 && Sysfs.write(Sysfs.RGBLEVEL, Integer.toString(cur));
                if (!ok) {
                    // Still overridden, or cannot tell. lastRestoreMs already holds this
                    // attempt, so the next one is RESTORE_EVERY_MS away.
                    overriding = true;
                    appliedRgblevel = p.rgblevel;
                    appliedOther = p.wasOther;
                    appliedRed = p.wasRed;
                }
                return ok;
            }
        } catch (Throwable t) {
            // Sysfs does not throw; this is belt and braces around the arithmetic.
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ for the screen

    /** True while this class believes its values are on the hardware. */
    public synchronized boolean overriding() {
        return overriding;
    }

    /** The "other" level believed to be on the hardware, or -1 when stock. For the CSV. */
    public synchronized int appliedLevel() {
        return overriding ? appliedOther : -1;
    }

    /** Is the ceiling latch holding the override off? */
    public synchronized boolean tripped() {
        return tripped;
    }

    /**
     * One phrase for the status area: {@code stock}, {@code applied 90/84},
     * or {@code held off: tripped at 57.2 C}.
     */
    public synchronized String state() {
        if (tripped) {
            return "held off: tripped at " + Sample.fmt1(trippedAtC) + " C";
        }
        if (overriding) {
            return "applied " + appliedOther + "/" + appliedRed;
        }
        return "stock";
    }

    private static String join(String a, String b) {
        if (a == null || a.length() == 0) {
            return b;
        }
        return a + " " + b;
    }
}
