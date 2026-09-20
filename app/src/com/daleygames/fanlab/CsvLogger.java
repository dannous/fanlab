package com.daleygames.fanlab;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only CSV writer that fans one line out to several destinations at once, so the same
 * log lands in app-external storage and on any mounted USB volume. Every line is flushed, and
 * a destination that fails is dropped and retried later. Nothing here throws.
 */
public final class CsvLogger {

    /** The columns. Their order is a compatibility surface: new columns go on the end, never in the middle, because the scripts in {@code tools/} address fields by position. */
    public static final String HEADER =
            "epoch_ms,iso_local,adc,degC,prop_led_temp,fan_ctrl,rgblevel,led_status,"
                    + "profile,mode,desired,wrote,note,soc_pll_c,soc_ddr_c,soc_sar_c"
                    + ",thr_cpufreq,thr_cpucore,thr_gpufreq,thr_gpucore"
                    + ",session,off_s,room_c,exclusive,catchup,duty_hold_s"
                    + ",led_drive";

    private static final class Target {
        final File file;
        OutputStreamWriter writer;
        boolean broken;
        long bytes;

        Target(File f) {
            this.file = f;
        }
    }

    /** Cap on one log file, bytes. Telemetry is about 400 kB an hour, so 4 MB is roughly ten hours before the file rolls. */
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    /** Rolled files kept per directory, newest first; bounds each destination at about 28 MB. */
    private static final int KEEP_FILES = 6;

    private final List<Target> targets = new ArrayList<Target>();
    private final String fileName;
    private final String header;
    private long lines;

    public CsvLogger(String fileName) {
        this(fileName, HEADER);
    }

    /** A logger with its own column set, for the sweep trace. */
    public CsvLogger(String fileName, String header) {
        this.fileName = fileName;
        this.header = header == null || header.length() == 0 ? HEADER : header;
    }

    public long lineCount() {
        return lines;
    }

    public List<String> activePaths() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (!t.broken) {
                out.add(t.file.getAbsolutePath());
            }
        }
        return out;
    }

    public List<String> brokenPaths() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (t.broken) {
                out.add(t.file.getAbsolutePath());
            }
        }
        return out;
    }

    /** Set the destination directories. Existing targets keep their handles and offsets; safe to call repeatedly. */
    public synchronized void setDirs(List<File> dirs) {
        if (dirs == null) {
            return;
        }
        for (int i = 0; i < dirs.size(); i++) {
            File dir = dirs.get(i);
            if (dir == null) {
                continue;
            }
            File f = new File(dir, fileName);
            boolean known = false;
            for (int j = 0; j < targets.size(); j++) {
                if (targets.get(j).file.getAbsolutePath().equals(f.getAbsolutePath())) {
                    known = true;
                    targets.get(j).broken = false;
                    break;
                }
            }
            if (!known) {
                targets.add(new Target(f));
            }
        }
    }

    private void openIfNeeded(Target t) {
        if (t.writer != null || t.broken) {
            return;
        }
        try {
            File parent = t.file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            if (t.file.exists() && t.file.length() > 0 && !headerMatches(t.file)) {
                roll(t);
                if (t.broken) {
                    return;
                }
            }
            boolean fresh = !t.file.exists() || t.file.length() == 0;
            t.writer = new OutputStreamWriter(new FileOutputStream(t.file, true), "UTF-8");
            if (fresh) {
                t.writer.write(header);
                t.writer.write('\n');
                t.writer.flush();
                t.bytes = header.length() + 1;
            } else {
                t.bytes = t.file.length();
            }
        } catch (Throwable e) {
            t.broken = true;
            closeQuietly(t);
        }
    }

    private boolean headerMatches(File f) {
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            String first = r.readLine();
            return header.equals(first);
        } catch (Throwable e) {
            return false;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void roll(Target t) {
        try {
            closeQuietly(t);
            File dir = t.file.getParentFile();
            String base = t.file.getName();
            int dot = base.lastIndexOf('.');
            String stem = dot > 0 ? base.substring(0, dot) : base;
            String ext = dot > 0 ? base.substring(dot) : "";
            File rolled = new File(dir, stem + "-" + System.currentTimeMillis() + ext);
            if (!t.file.renameTo(rolled)) {
                t.broken = true;
                return;
            }
            prune(dir, stem, ext);
            t.bytes = 0;
        } catch (Throwable e) {
            t.broken = true;
        }
    }

    private void prune(File dir, String stem, String ext) {
        try {
            File[] all = dir.listFiles();
            if (all == null) {
                return;
            }
            List<File> mine = new ArrayList<File>();
            for (int i = 0; i < all.length; i++) {
                String n = all[i].getName();
                if (n.startsWith(stem + "-") && n.endsWith(ext) && all[i].isFile()) {
                    mine.add(all[i]);
                }
            }
            while (mine.size() > KEEP_FILES) {
                File oldest = null;
                for (int i = 0; i < mine.size(); i++) {
                    if (oldest == null || mine.get(i).lastModified() < oldest.lastModified()) {
                        oldest = mine.get(i);
                    }
                }
                if (oldest == null || !oldest.delete()) {
                    return;
                }
                mine.remove(oldest);
            }
        } catch (Throwable ignored) {
        }
    }

    private void closeQuietly(Target t) {
        if (t.writer != null) {
            try {
                t.writer.close();
            } catch (Throwable ignored) {
            }
            t.writer = null;
        }
    }

    /** Append one row to every working destination; returns how many destinations it reached. */
    public synchronized int append(String csvLine) {
        int ok = 0;
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (t.broken) {
                continue;
            }
            openIfNeeded(t);
            if (t.broken || t.writer == null) {
                continue;
            }
            try {
                t.writer.write(csvLine);
                t.writer.write('\n');
                t.writer.flush();
                t.bytes += csvLine.length() + 1;
                if (t.bytes >= MAX_BYTES) {
                    roll(t);
                }
                ok++;
            } catch (Throwable e) {
                t.broken = true;
                closeQuietly(t);
            }
        }
        if (ok > 0) {
            lines++;
        }
        return ok;
    }

    public synchronized void close() {
        for (int i = 0; i < targets.size(); i++) {
            closeQuietly(targets.get(i));
        }
    }

    /** Write one whole file to every directory given, replacing anything already there; returns the paths written. */
    public static List<String> writeWhole(List<File> dirs, String fileName, String content) {
        List<String> written = new ArrayList<String>();
        if (dirs == null || fileName == null || content == null) {
            return written;
        }
        for (int i = 0; i < dirs.size(); i++) {
            File dir = dirs.get(i);
            if (dir == null) {
                continue;
            }
            OutputStreamWriter w = null;
            try {
                if (!dir.isDirectory()) {
                    dir.mkdirs();
                }
                File f = new File(dir, fileName);
                w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
                w.write(content);
                w.flush();
                written.add(f.getAbsolutePath());
            } catch (Throwable ignored) {
            } finally {
                if (w != null) {
                    try {
                        w.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return written;
    }

    /** How many differently-headed files may share one first-row timestamp; exists only to bound the loop. */
    private static final int MAX_SIBLINGS = 20;

    public static final class Export {
        public int sources;
        public int filesWritten;
        public long rowsCopied;
        public int upToDate;
        public int failures;
    }

    /**
     * Copy the log backlog into {@code destDir}, adding to what is already there.
     *
     * {@code destDir} must not be a directory any logger writes to: {@link #prune} would treat
     * the export as a rolled file. A destination is named after its source's first row and
     * appends only rows newer than the destination's last complete row, which is what makes
     * repeat exports additive; a differing header gets a sibling file rather than two schemas
     * in one. Never throws.
     */
    public static Export exportBacklog(File destDir, List<File> sources) {
        Export r = new Export();
        if (destDir == null || sources == null) {
            return r;
        }
        try {
            if (!destDir.isDirectory()) {
                destDir.mkdirs();
            }
            if (!destDir.isDirectory()) {
                r.failures++;
                return r;
            }
        } catch (Throwable e) {
            r.failures++;
            return r;
        }
        for (int i = 0; i < sources.size(); i++) {
            File src = sources.get(i);
            try {
                if (src == null || !src.isFile() || src.length() == 0) {
                    continue;
                }
                String header = headerOf(src);
                long first = firstEpochMs(src);
                if (header == null || first < 0) {
                    continue;
                }
                r.sources++;
                File dest = destFor(destDir, src.getName(), first, header);
                if (dest == null) {
                    r.failures++;
                    continue;
                }
                long n = appendSince(src, dest, lastEpochMs(dest));
                if (n < 0) {
                    r.failures++;
                } else if (n == 0) {
                    r.upToDate++;
                } else {
                    r.filesWritten++;
                    r.rowsCopied += n;
                }
            } catch (Throwable e) {
                r.failures++;
            }
        }
        return r;
    }

    /** The {@code epoch_ms} on the first complete data row, which is fixed for the life of the file. Returns -1 if there is none yet. */
    public static long firstEpochMs(File f) {
        Rows rows = null;
        try {
            if (f == null || !f.isFile() || f.length() == 0) {
                return -1L;
            }
            rows = new Rows(f);
            rows.next();
            if (!rows.terminated) {
                return -1L;
            }
            String row = rows.next();
            return rows.terminated ? epochOf(row) : -1L;
        } catch (Throwable e) {
            return -1L;
        } finally {
            closeQuietly(rows);
        }
    }

    /** The {@code epoch_ms} on the last complete row - the watermark an append starts from - read from the tail. Returns -1 if there is none. */
    public static long lastEpochMs(File f) {
        java.io.RandomAccessFile raf = null;
        try {
            if (f == null || !f.isFile() || f.length() == 0) {
                return -1L;
            }
            raf = new java.io.RandomAccessFile(f, "r");
            long size = raf.length();
            int window = 8192;
            while (true) {
                long from = size > window ? size - window : 0L;
                byte[] b = new byte[(int) (size - from)];
                raf.seek(from);
                raf.readFully(b);
                int end = lastIndexOfNl(b, b.length - 1);
                int start = end < 0 ? -1 : lastIndexOfNl(b, end - 1);
                if (end >= 0 && (start >= 0 || from == 0L)) {
                    return epochOf(new String(b, start + 1, end - start - 1, "UTF-8"));
                }
                if (from == 0L) {
                    return -1L;
                }
                window *= 2;
            }
        } catch (Throwable e) {
            return -1L;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Copy rows of {@code src} newer than {@code watermark} onto {@code dest}, built in {@code <dest>.part} and renamed into place. Returns rows copied, 0 if already up to date, -1 on failure. */
    public static long appendSince(File src, File dest, long watermark) {
        Rows rows = null;
        OutputStreamWriter w = null;
        File part = null;
        long copied = 0;
        try {
            if (src == null || dest == null) {
                return -1L;
            }
            rows = new Rows(src);
            String header = rows.next();
            if (header == null || !rows.terminated) {
                return -1L;
            }
            String have = headerOf(dest);
            if (have != null && !have.equals(header)) {
                return -1L;
            }
            String row;
            while ((row = rows.next()) != null) {
                if (!rows.terminated) {
                    break;
                }
                long e = epochOf(row);
                if (e < 0 || e <= watermark) {
                    continue;
                }
                if (w == null) {
                    part = new File(dest.getParentFile(), dest.getName() + ".part");
                    w = openPart(part, dest, header);
                    if (w == null) {
                        return -1L;
                    }
                }
                w.write(row);
                w.write('\n');
                copied++;
            }
            if (w == null) {
                return 0L;
            }
            w.flush();
            w.close();
            w = null;
            if (dest.exists() && !dest.delete()) {
                return -1L;
            }
            return part.renameTo(dest) ? copied : -1L;
        } catch (Throwable e) {
            return -1L;
        } finally {
            closeQuietly(rows);
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static File destFor(File destDir, String srcName, long first, String header) {
        int dot = srcName.lastIndexOf('.');
        String stem = dot > 0 ? srcName.substring(0, dot) : srcName;
        String ext = dot > 0 ? srcName.substring(dot) : "";
        int dash = stem.lastIndexOf('-');
        if (dash > 0 && allDigits(stem.substring(dash + 1))) {
            stem = stem.substring(0, dash);
        }
        String base = stem + "-" + first;
        for (int v = 1; v <= MAX_SIBLINGS; v++) {
            File f = new File(destDir, v == 1 ? base + ext : base + "-v" + v + ext);
            String have = headerOf(f);
            if (have == null || have.equals(header)) {
                return f;
            }
        }
        return null;
    }

    private static OutputStreamWriter openPart(File part, File dest, String header) {
        OutputStreamWriter w = null;
        Rows rows = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(part, false), "UTF-8");
            long kept = 0;
            if (dest.isFile() && dest.length() > 0) {
                rows = new Rows(dest);
                String row;
                while ((row = rows.next()) != null) {
                    if (!rows.terminated) {
                        break;
                    }
                    w.write(row);
                    w.write('\n');
                    kept++;
                }
            }
            if (kept == 0) {
                w.write(header);
                w.write('\n');
            }
            return w;
        } catch (Throwable e) {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
            return null;
        } finally {
            closeQuietly(rows);
        }
    }

    private static String headerOf(File f) {
        Rows rows = null;
        try {
            if (f == null || !f.isFile() || f.length() == 0) {
                return null;
            }
            rows = new Rows(f);
            String first = rows.next();
            return rows.terminated ? first : null;
        } catch (Throwable e) {
            return null;
        } finally {
            closeQuietly(rows);
        }
    }

    private static long epochOf(String row) {
        if (row == null) {
            return -1L;
        }
        int end = row.indexOf(',');
        if (end < 0) {
            end = row.length();
        }
        if (end == 0 || end > 18) {
            return -1L;
        }
        long v = 0;
        for (int i = 0; i < end; i++) {
            char c = row.charAt(i);
            if (c < '0' || c > '9') {
                return -1L;
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    private static boolean allDigits(String s) {
        if (s.length() == 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static int lastIndexOfNl(byte[] b, int from) {
        for (int i = from; i >= 0; i--) {
            if (b[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static void closeQuietly(Rows r) {
        if (r != null) {
            r.close();
        }
    }

    /** A line reader that also reports whether the line it just returned was newline-terminated. */
    private static final class Rows {
        private final java.io.Reader r;
        private final char[] buf = new char[8192];
        private final StringBuilder line = new StringBuilder(256);
        private int len;
        private int pos;

        boolean terminated;

        Rows(File f) throws java.io.IOException {
            r = new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8");
        }

        String next() throws java.io.IOException {
            line.setLength(0);
            terminated = false;
            while (true) {
                if (pos >= len) {
                    len = r.read(buf, 0, buf.length);
                    pos = 0;
                    if (len <= 0) {
                        return line.length() == 0 ? null : line.toString();
                    }
                }
                char c = buf[pos++];
                if (c == '\n') {
                    terminated = true;
                    return line.toString();
                }
                if (c != '\r') {
                    line.append(c);
                }
            }
        }

        void close() {
            try {
                r.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static String q(String s) {
        if (s == null) {
            return "";
        }
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"").replace("\n", " ") + '"';
    }
}
