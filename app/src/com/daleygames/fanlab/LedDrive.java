package com.daleygames.fanlab;

/**
 * The LED drive override: run the light engine above its stock per-mode table, and only
 * while this app is the thing cooling it.
 *
 * Levels are percent of the driver's own per-channel maximum. {@code rgbcurrent} sets all four
 * channels, {@code redcurrent} channel 1 alone, and {@link #redFor} keeps the stock table's
 * ratio. Any {@code rgblevel} write reinstates the stock table, which is how handback works.
 *
 * Safety: raising the drive while this app is not the fan controller would leave the stock
 * ladder cooling Presentation-class heat on an Eco fan floor, so the caller passes the whole
 * coupling rule in as {@code allowed} (Mode.controls, no AUTO or VERIFY session, led_status
 * non-zero, fail-safe not latched, machine awake) and the stock table goes back in every other
 * state. Above {@link #DEFAULT_TRIP_C} the override drops and latches off until the brightness
 * mode or the configuration changes.
 */
public final class LedDrive {

    /** Stock drive for channels 0/2/3 by rgblevel, percent of driver max; index 0 unused. */
    public static final int[] STOCK_OTHER = {0, 40, 55, 76, 20};

    /** Stock channel-1 ({@code redcurrent}) drive by rgblevel, percent; index 0 unused. */
    public static final int[] STOCK_RED = {0, 36, 48, 71, 20};

    /**
     * Bounds on a configured level, percent of the driver's per-channel maximum.
     *
     * 97, not 100: the driver's clamp is {@code if (code > 0x7F) code = 0x3F}, so an
     * over-request drops the channel to about 40 % instead of pegging it, and the code
     * crosses 127 at about 99 %.
     */
    public static final int MIN_LEVEL = 20;
    public static final int MAX_LEVEL = 97;

    public static final int TIERS = 4;
    public static final int[] TIER_RGBLEVEL = {4, 1, 2, 3};
    public static final String[] TIER_NAMES = {"Super Eco", "Eco", "Normal", "Presentation"};

    public static final int FIELDS = 5;

    /**
     * LED thermistor temperature above which the override is dropped and latched off, C.
     *
     * 60.0 is five degrees above the 55 C at which the stock controller already commands
     * maximum fan, and well below the 75 C shutdown.
     */
    public static final double DEFAULT_TRIP_C = 60.0;

    /** Minimum gap between {@code rgblevel} rewrites, ms. Matches {@code assertRgbLevel}. */
    public static final long RESTORE_EVERY_MS = 5000L;

    /** Minimum gap between re-applies after a read-back mismatch or a failed write, ms. */
    public static final long REAPPLY_EVERY_MS = 1500L;

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

        /** The one-press preset the main screen offers; Presentation 90 is the drive the Bright curve family was solved at. */
        public static Config bright() {
            Config c = new Config();
            c.level[0] = 35;
            c.level[1] = 55;
            c.level[2] = 75;
            c.level[3] = 90;
            return c;
        }

        public boolean isStock() {
            for (int i = 0; i < TIERS; i++) {
                if (level[i] != STOCK_OTHER[TIER_RGBLEVEL[i]]) {
                    return false;
                }
            }
            return true;
        }

        public int levelFor(int rgblevel) {
            int t = tierOf(rgblevel);
            return t < 0 ? -1 : level[t];
        }

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

        public String encode() {
            StringBuilder sb = new StringBuilder("d1");
            for (int i = 0; i < TIERS; i++) {
                sb.append(',').append(level[i]);
            }
            return sb.toString();
        }

        /** Parse a line from {@link #encode()}. Anything unparseable falls back to the stock table. */
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

    public static int tierOf(int rgblevel) {
        for (int i = 0; i < TIERS; i++) {
            if (TIER_RGBLEVEL[i] == rgblevel) {
                return i;
            }
        }
        return -1;
    }

    /** Channel 1's drive for a given "other" level, at the stock table's ratio: {@code round(level * stockRed / stockOther)}. */
    public static int redFor(int rgblevel, int level) {
        if (rgblevel < 1 || rgblevel >= STOCK_OTHER.length || STOCK_OTHER[rgblevel] <= 0) {
            return level;
        }
        return (int) Math.round(level * (double) STOCK_RED[rgblevel] / STOCK_OTHER[rgblevel]);
    }

    /**
     * Pull the red and the common level out of the {@code rgbcurrent} show text.
     *
     * The printed labels are array indices dressed up as colours: {@code duty_g} carries red,
     * and the other three carry the common level and must all agree (confirmed on hardware
     * 2026-09-07). The show handler reports each value one below what was written.
     *
     * @return {@code {red, otherLow, otherHigh}}, or null if a field is missing, unparseable or
     *         above 100 - above 100 is the handler's failed-SPI glitch and reads as "could not
     *         tell", never as a mismatch.
     */
    public static int[] parseReadback(String text) {
        if (text == null) {
            return null;
        }
        int red = field(text, "duty_g=");
        if (red < 0 || red > 100) {
            return null;
        }
        int low = -1;
        int high = -1;
        String[] commonFields = {"duty_r=", "duty_b=", "duty_b2="};
        for (int i = 0; i < commonFields.length; i++) {
            int v = field(text, commonFields[i]);
            if (v < 0 || v > 100) {
                continue;
            }
            if (low < 0 || v < low) {
                low = v;
            }
            if (v > high) {
                high = v;
            }
        }
        if (low < 0) {
            return null;
        }
        return new int[]{red, low, high};
    }

    public static boolean readbackAgrees(int[] rb, int red, int other) {
        return rb != null && rb.length >= 3 && rb[1] == rb[2]
                && matches(rb[0], red) && matches(rb[1], other);
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

    /** Does a read-back field agree with what was written? The handler reports one below (76 reads 75). */
    public static boolean matches(int read, int written) {
        return read == written || read == written - 1;
    }

    public static final class Plan {
        public static final int NONE = 0;
        /** Write {@link #other} to {@code rgbcurrent}, then {@link #red} to {@code redcurrent}, in that order. */
        public static final int APPLY = 1;
        /** Rewrite {@code rgblevel} with its current value, which reinstates the stock table. */
        public static final int RESTORE = 2;

        public int action = NONE;
        public int rgblevel = -1;
        public int other = -1;
        public int red = -1;
        public String note = "";
        boolean wasOverriding;
        int wasOther;
        int wasRed;
        int wasRgblevel;
    }

    public double tripC = DEFAULT_TRIP_C;

    private boolean overriding;
    private int appliedRgblevel = -1;
    private int appliedOther = -1;
    private int appliedRed = -1;
    private long lastRestoreMs = Long.MIN_VALUE / 2;
    private long lastApplyMs = Long.MIN_VALUE / 2;
    private long retryAfterMs = Long.MIN_VALUE / 2;

    private boolean tripped;
    private int trippedRgblevel = -1;
    private String trippedConfig = "";
    private double trippedAtC = Double.NaN;

    /** Decide what this tick should write. No hardware side effects; {@code allowed} is the caller's coupling rule and {@code ledC} is NaN when unreadable. */
    public synchronized Plan decide(Config cfg, int rgblevel, boolean allowed, double ledC,
                                    String readback, long nowMs) {
        return decide(cfg, rgblevel, allowed, ledC, readback, nowMs, false);
    }

    /** As {@link #decide}, but {@code urgent} drops the {@link #REAPPLY_EVERY_MS} floor on rewrites; restores stay rate-limited. */
    public synchronized Plan decide(Config cfg, int rgblevel, boolean allowed, double ledC,
                                    String readback, long nowMs, boolean urgent) {
        Plan p = new Plan();
        p.wasOverriding = overriding;
        p.wasOther = appliedOther;
        p.wasRed = appliedRed;
        p.wasRgblevel = appliedRgblevel;

        boolean enabled = cfg != null && !cfg.isStock();
        String key = cfg == null ? "" : cfg.encode();

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
            return p;
        }
        boolean edge = !overriding || rgblevel != appliedRgblevel || other != appliedOther
                || red != appliedRed;
        if (edge) {
            apply(p, rgblevel, other, red, nowMs);
            p.note = join(p.note, "leddrive<-" + other + "/" + red + "@" + rgblevel);
            return p;
        }

        int[] rb = parseReadback(readback);
        if (rb != null && !readbackAgrees(rb, red, other)
                && (urgent || nowMs - lastApplyMs >= REAPPLY_EVERY_MS)) {
            apply(p, rgblevel, other, red, nowMs);
            String seen = rb[1] == rb[2] ? Integer.toString(rb[1]) : rb[1] + ".." + rb[2];
            p.note = join(p.note, "leddrive rewrite(read " + seen + "/" + rb[0] + ")");
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

    /** Handback for paths that are not a tick: release, a session starting, service stop. Ignores the rate limit. */
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

    /** Do the writes a plan asks for, through {@link Sysfs}. Never throws; on failure the memory {@link #decide} set is rolled back. */
    public synchronized boolean perform(Plan p) {
        if (p == null || p.action == Plan.NONE) {
            return true;
        }
        try {
            if (p.action == Plan.APPLY) {
                boolean okOther = Sysfs.write(Sysfs.RGBCURRENT, Integer.toString(p.other));
                boolean okRed = okOther && Sysfs.write(Sysfs.REDCURRENT, Integer.toString(p.red));
                if (!okOther) {
                    overriding = p.wasOverriding;
                    appliedRgblevel = p.wasRgblevel;
                    appliedOther = p.wasOther;
                    appliedRed = p.wasRed;
                    retryAfterMs = lastApplyMs + REAPPLY_EVERY_MS;
                } else if (!okRed) {
                    appliedRed = -1;
                    retryAfterMs = lastApplyMs + REAPPLY_EVERY_MS;
                }
                return okRed;
            }
            if (p.action == Plan.RESTORE) {
                int cur = Sysfs.readInt(Sysfs.RGBLEVEL, -1);
                if (cur >= 0 && cur != p.rgblevel) {
                    return true;
                }
                boolean ok = cur >= 0 && Sysfs.write(Sysfs.RGBLEVEL, Integer.toString(cur));
                if (!ok) {
                    overriding = true;
                    appliedRgblevel = p.rgblevel;
                    appliedOther = p.wasOther;
                    appliedRed = p.wasRed;
                }
                return ok;
            }
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    public synchronized boolean confirmed(Config cfg, int rgblevel, String readback) {
        if (cfg == null || !overriding || rgblevel != appliedRgblevel) {
            return false;
        }
        return readbackAgrees(parseReadback(readback), appliedRed, appliedOther);
    }

    public synchronized boolean overriding() {
        return overriding;
    }

    public synchronized int appliedLevel() {
        return overriding ? appliedOther : -1;
    }

    public synchronized boolean tripped() {
        return tripped;
    }

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
