package com.daleygames.fanlab;

/**
 * Just enough JSON to write a report, and nothing more.
 *
 * {@code org.json} is in {@code android.jar}, but using it would put the report builder on
 * the impure side of the seam and out of reach of the headless test - and the whole point
 * of the sweep is that its output is the deliverable, so its output is exactly the thing
 * that must be tested. This is a few dozen lines instead.
 *
 * Numbers are written with a fixed number of decimals rather than through
 * {@code Double.toString}, so the file never contains {@code 4.9E-324} or a locale comma,
 * and non-finite values are written as JSON {@code null} rather than as the token
 * {@code NaN}, which is not valid JSON and which every parser on the analysis side would
 * reject.
 *
 * Pure Java.
 */
public final class Json {

    private final StringBuilder sb = new StringBuilder(4096);
    private boolean needComma;
    private int depth;

    public Json() {
    }

    // ------------------------------------------------------------------ structure

    public Json beginObject() {
        sep();
        sb.append('{');
        depth++;
        needComma = false;
        return this;
    }

    public Json endObject() {
        depth--;
        sb.append('}');
        needComma = true;
        return this;
    }

    public Json beginArray() {
        sep();
        sb.append('[');
        depth++;
        needComma = false;
        return this;
    }

    public Json endArray() {
        depth--;
        sb.append(']');
        needComma = true;
        return this;
    }

    /** Open a named object. */
    public Json obj(String key) {
        key(key);
        sb.append('{');
        depth++;
        needComma = false;
        return this;
    }

    /** Open a named array. */
    public Json arr(String key) {
        key(key);
        sb.append('[');
        depth++;
        needComma = false;
        return this;
    }

    private void key(String k) {
        sep();
        quote(k);
        // A space after the colon: these files are read by a person as often as by a
        // script, and the run that produced them cannot be repeated cheaply.
        sb.append(": ");
        needComma = false;
    }

    private void sep() {
        if (needComma) {
            sb.append(',');
            sb.append('\n');
            for (int i = 0; i < depth; i++) {
                sb.append(' ');
            }
        } else if (depth > 0 && sb.length() > 0) {
            sb.append('\n');
            for (int i = 0; i < depth; i++) {
                sb.append(' ');
            }
        }
    }

    // ------------------------------------------------------------------ values

    public Json put(String key, String value) {
        key(key);
        if (value == null) {
            sb.append("null");
        } else {
            quote(value);
        }
        needComma = true;
        return this;
    }

    public Json put(String key, long value) {
        key(key);
        sb.append(value);
        needComma = true;
        return this;
    }

    public Json put(String key, boolean value) {
        key(key);
        sb.append(value ? "true" : "false");
        needComma = true;
        return this;
    }

    /** A real number to {@code decimals} places; NaN and infinity are written as null. */
    public Json put(String key, double value, int decimals) {
        key(key);
        sb.append(num(value, decimals));
        needComma = true;
        return this;
    }

    /** An integer that may be absent; {@code missing} is written as null. */
    public Json putOpt(String key, int value, int missing) {
        key(key);
        if (value == missing) {
            sb.append("null");
        } else {
            sb.append(value);
        }
        needComma = true;
        return this;
    }

    public Json value(String v) {
        sep();
        if (v == null) {
            sb.append("null");
        } else {
            quote(v);
        }
        needComma = true;
        return this;
    }

    public Json value(long v) {
        sep();
        sb.append(v);
        needComma = true;
        return this;
    }

    public Json intArray(String key, int[] v) {
        arr(key);
        if (v != null) {
            for (int i = 0; i < v.length; i++) {
                value(v[i]);
            }
        }
        endArray();
        return this;
    }

    // ------------------------------------------------------------------ output

    @Override
    public String toString() {
        return sb.toString();
    }

    /** The finished document, newline terminated. */
    public String finish() {
        return sb.toString() + "\n";
    }

    // ------------------------------------------------------------------ primitives

    /**
     * Format a double with a fixed number of decimals, no locale, no exponent.
     *
     * @return the number, or {@code null} (the JSON literal) if it is not finite.
     */
    public static String num(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "null";
        }
        if (decimals < 0) {
            decimals = 0;
        }
        if (decimals > 6) {
            decimals = 6;
        }
        long scale = 1;
        for (int i = 0; i < decimals; i++) {
            scale *= 10;
        }
        // Guard the rounding itself: a value beyond long range would wrap silently.
        double scaled = v * scale;
        if (scaled > 9.0e18 || scaled < -9.0e18) {
            return "null";
        }
        long r = Math.round(scaled);
        boolean neg = r < 0;
        if (neg) {
            r = -r;
        }
        StringBuilder out = new StringBuilder();
        if (neg) {
            out.append('-');
        }
        out.append(r / scale);
        if (decimals > 0) {
            out.append('.');
            String frac = Long.toString(r % scale);
            for (int i = frac.length(); i < decimals; i++) {
                out.append('0');
            }
            out.append(frac);
        }
        return out.toString();
    }

    private void quote(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        sb.append("\\u");
                        String h = Integer.toHexString(c);
                        for (int k = h.length(); k < 4; k++) {
                            sb.append('0');
                        }
                        sb.append(h);
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
    }

    /** Escape a bare string for embedding, exposed for the tests. */
    public static String escape(String s) {
        Json j = new Json();
        j.quote(s == null ? "" : s);
        return j.sb.toString();
    }
}
