package com.daleygames.fanlab;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only CSV writer that fans one line out to several destinations at once, so the
 * same log lands in app-external storage <i>and</i> on any USB volume that happens to be
 * mounted. The USB copy is the whole point: it is how the telemetry gets to a PC on a
 * machine with no adb.
 *
 * Every line is flushed. A USB stick can be pulled at any moment and the file on it must
 * be complete up to the second before.
 *
 * A stick inserted after the fact only receives what is written from then on, so
 * {@link #exportBacklog} copies the history across as well. It is a separate mechanism on
 * purpose: it writes outside every sink, and it appends by watermark rather than fanning
 * out.
 *
 * A destination that fails is dropped and retried later rather than being allowed to
 * take the run down. Nothing here throws.
 *
 * Pure Java (java.io only), so the host test can drive it.
 */
public final class CsvLogger {

    /**
     * The columns, and their order is a compatibility surface.
     *
     * New columns go on the end, never in the middle: every analysis script in
     * {@code tools/} addresses fields by position, and a column inserted at 14 would move
     * the SoC block underneath them and be read as data rather than as an error. The six
     * added on 2026-09-07 answer the questions the first field log could not -- ambient,
     * run boundaries, whether the app was alone on the node, and whether the controller
     * had converged.
     *
     * A file whose first line is not this string is rolled aside rather than appended to,
     * so changing it is safe and is meant to be done in one revision rather than seven.
     */
    public static final String HEADER =
            "epoch_ms,iso_local,adc,degC,prop_led_temp,fan_ctrl,rgblevel,led_status,"
                    + "profile,mode,desired,wrote,note,soc_pll_c,soc_ddr_c,soc_sar_c"
                    + ",thr_cpufreq,thr_cpucore,thr_gpufreq,thr_gpucore"
                    + ",session,off_s,room_c,exclusive,catchup,duty_hold_s";

    private static final class Target {
        final File file;
        OutputStreamWriter writer;
        boolean broken;
        long bytes;

        Target(File f) {
            this.file = f;
        }
    }

    /**
     * Cap on one log file, bytes. Telemetry runs at 1 Hz and about 400 kB an hour, and
     * nothing else on this device is going to notice a full /sdcard until something
     * important fails to write. 4 MB is roughly ten hours; past that the file rolls.
     */
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    /**
     * How many rolled files to keep per directory, newest first. Six of them is about
     * sixty hours of continuous logging -- far more than any question we ask of it needs
     * -- and it bounds each destination at 28 MB -- six rolled files plus
     * the live one -- instead of at infinity.
     *
     * The old behaviour was: a new timestamped file per service start, never capped,
     * never pruned. Fine for an afternoon's measurement, wrong for something that ships
     * and runs for years.
     */
    private static final int KEEP_FILES = 6;

    private final List<Target> targets = new ArrayList<Target>();
    private final String fileName;
    private final String header;
    private long lines;

    public CsvLogger(String fileName) {
        this(fileName, HEADER);
    }

    /**
     * A logger with its own column set. The sweep trace carries the ordinary telemetry
     * columns plus the commanded duty, step, phase and event, so it needs a different
     * header while reusing the same fan-out and flush-every-line behaviour.
     */
    public CsvLogger(String fileName, String header) {
        this.fileName = fileName;
        this.header = header == null || header.length() == 0 ? HEADER : header;
    }

    /** Number of CSV rows written to at least one destination. */
    public long lineCount() {
        return lines;
    }

    /** Human-readable list of the destinations currently working. */
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

    /** Human-readable list of the destinations that failed. */
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

    /**
     * Set the destination directories. Existing targets are kept (so their file handles
     * and byte offsets survive), new ones are opened, and ones that have gone away are
     * closed. Safe to call repeatedly; the service calls it every 30 s so a stick
     * inserted mid-run starts receiving data.
     */
    public synchronized void setDirs(List<File> dirs) {
        if (dirs == null) {
            return;
        }
        // add anything new
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
                    // a previously broken target gets one more chance on every rescan
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
                // mkdirs can fail benignly if another thread won the race
                parent.mkdirs();
            }
            if (t.file.exists() && t.file.length() > 0 && !headerMatches(t.file)) {
                // An app update that adds a column would otherwise append wide rows under
                // a narrow header, and the mismatch is invisible until someone tries to
                // parse the file months later. Roll instead: the old data keeps the header
                // it was written under, and the new file starts with the current one.
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

    /**
     * Does the file on disk already carry the columns we are about to write?
     *
     * An unreadable first line counts as a mismatch. Rolling a file we cannot read is the
     * conservative answer: it costs one rename, where guessing "close enough" costs the
     * integrity of the log.
     */
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
                    // nothing useful to do
                }
            }
        }
    }

    /**
     * Rename the full file out of the way and start a new one, then prune the oldest.
     *
     * Renaming rather than truncating means the data already collected survives -- the
     * whole point of logging is that somebody wants to read it later, and rolling a log
     * by throwing away the interesting half is a classic way to discover that only after
     * the event you needed it for.
     */
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
                // Could not rename (read-only volume, vanished stick). Leaving the file
                // in place and continuing to append would defeat the cap, so stop writing
                // to this target rather than grow without bound.
                t.broken = true;
                return;
            }
            prune(dir, stem, ext);
            t.bytes = 0;
        } catch (Throwable e) {
            t.broken = true;
        }
    }

    /** Keep only the newest {@link #KEEP_FILES} rolled files for this stem. */
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
                    return;        // do not spin if the delete is refused
                }
                mine.remove(oldest);
            }
        } catch (Throwable ignored) {
            // pruning is housekeeping; never let it break logging
        }
    }

    private void closeQuietly(Target t) {
        if (t.writer != null) {
            try {
                t.writer.close();
            } catch (Throwable ignored) {
                // nothing useful to do
            }
            t.writer = null;
        }
    }

    /**
     * Append one row to every working destination.
     *
     * @return the number of destinations the row reached.
     */
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
                // most likely the stick was pulled; drop it, a later setDirs will retry
                t.broken = true;
                closeQuietly(t);
            }
        }
        if (ok > 0) {
            lines++;
        }
        return ok;
    }

    /** Close everything. Safe to call more than once. */
    public synchronized void close() {
        for (int i = 0; i < targets.size(); i++) {
            closeQuietly(targets.get(i));
        }
    }

    /**
     * Write one whole file to every directory given, replacing anything already there.
     *
     * The JSON report is rewritten in full after every step rather than at the end, so an
     * abort, a pulled stick, or a process kill still leaves a complete and parseable file
     * describing everything measured up to that point. Each destination is independent: a
     * failure on one is reported and does not stop the others.
     *
     * @return the paths that were written.
     */
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
                // a destination that cannot take it is simply skipped
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
        return written;
    }

    // ------------------------------------------------------------------ export

    /**
     * How many differently-headed files may share one first-row timestamp. It takes a
     * schema change to make even the second, so this only exists to bound the loop.
     */
    private static final int MAX_SIBLINGS = 20;

    /** What one {@link #exportBacklog} run managed to do, for the screen and the log. */
    public static final class Export {
        /** Source files that held at least one complete row. */
        public int sources;
        /** Destinations created or appended to. */
        public int filesWritten;
        /** Rows appended, across every destination. */
        public long rowsCopied;
        /** Destinations that already held everything their source had. */
        public int upToDate;
        /** Sources that could not be read, or destinations that could not be written. */
        public int failures;
    }

    /**
     * Copy the log backlog into {@code destDir}, adding to what is already there rather
     * than replacing it.
     *
     * <h3>The destination must not be a sink</h3>
     * {@link #prune} deletes everything matching {@code <stem>-*<ext>} in a target's own
     * directory, so an export named {@code fanlab-1757.csv} written into a sink is
     * indistinguishable from a rolled file and becomes prune fodder. {@link
     * #openIfNeeded} would also roll a historic file aside for carrying the header it was
     * written under. Both are avoided by exporting somewhere no logger writes.
     *
     * <h3>How "additive" is made to hold</h3>
     * A destination is named after its source's <i>first</i> row, whose {@code epoch_ms}
     * never changes for the life of that file. The live log therefore maps to the same
     * destination however much it has grown since, and the two internal sinks -- which
     * are identical copies of the same data under different rolled names -- collapse onto
     * one destination too. Before appending, the destination's last complete row gives a
     * watermark and only strictly newer source rows are copied, so the duplicate is a
     * no-op and a stick brought back tomorrow gains today's tail instead of a second copy
     * of everything. Filename-based dedupe cannot do this: the live file keeps its name
     * and grows.
     *
     * A destination whose header differs from the source's gets a sibling rather than two
     * schemas in one file. The sticks in the field already hold 13-column files where
     * this build writes 20.
     *
     * Never throws.
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
                    // A header and nothing else yet, or a single row still being written.
                    // Nothing is lost by waiting: the name comes from a row that is
                    // already complete, so the next insertion picks the same destination.
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

    /**
     * The {@code epoch_ms} on the first data row of a log file. Fixed for the life of
     * that file, which is what makes it usable as an identity.
     *
     * Only a row terminated by a newline counts. A half-written first row would otherwise
     * name the destination after a truncated number, and that name has to come out the
     * same on every insertion or nothing is additive.
     *
     * @return -1 if the file has no complete data row yet.
     */
    public static long firstEpochMs(File f) {
        Rows rows = null;
        try {
            if (f == null || !f.isFile() || f.length() == 0) {
                return -1L;
            }
            rows = new Rows(f);
            rows.next();                       // the header
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

    /**
     * The {@code epoch_ms} on the last complete row of a file -- the watermark an append
     * starts from.
     *
     * Read from the tail rather than by scanning: a destination on the stick is already
     * megabytes, and re-reading all of it to find one number would cost as much as the
     * copy the watermark exists to avoid.
     *
     * @return -1 if the file holds no complete data row, in which case everything copies.
     */
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
                // The last newline ends the last complete row; anything after it is a
                // fragment and is deliberately ignored.
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
                    // nothing useful to do
                }
            }
        }
    }

    /**
     * Copy every row of {@code src} newer than {@code watermark} onto the end of
     * {@code dest}, giving {@code dest} the source's header if it has none yet.
     *
     * The new destination is built in {@code <dest>.part} and renamed into place, so a
     * stick pulled part way through leaves the file that was already there exactly as it
     * was. Appending to the real file instead would strand a half row in the middle of
     * the log, and the next export would write the following row straight onto it.
     *
     * A source row without a terminating newline is being written right now. It is
     * dropped: {@link #append} flushes every complete line, so a missing newline is the
     * only signature a partial row has and nothing complete is lost by stopping short.
     *
     * @return rows copied, 0 if the destination was already up to date and therefore not
     *         touched at all, or -1 if the copy failed and the destination was left alone.
     */
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
                // Different columns. The caller picks the destination and is the only one
                // that can pick another; mixing them here is the thing to refuse.
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
                    // Opened lazily, so a source with nothing new costs no writing at
                    // all -- the common case once a stick has been exported to once.
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
            // Swap in only now. If this is interrupted the .part holds everything the
            // destination did plus the new rows, and the next export rebuilds the
            // destination from the source anyway, so the data is not stranded.
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
                    // nothing useful to do
                }
            }
        }
    }

    /**
     * The file in {@code destDir} this source belongs in: named after its first row so a
     * grown source lands on the same one, stepped to a sibling if what is already there
     * was written under different columns.
     */
    private static File destFor(File destDir, String srcName, long first, String header) {
        int dot = srcName.lastIndexOf('.');
        String stem = dot > 0 ? srcName.substring(0, dot) : srcName;
        String ext = dot > 0 ? srcName.substring(dot) : "";
        // Drop a rolled file's own timestamp. fanlab.csv and fanlab-<millis>.csv are the
        // same log at different ages and each keys on its own first row instead.
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

    /**
     * Start a replacement for {@code dest}, carrying its complete rows across first.
     *
     * Copying the rows rather than the bytes drops any fragment a previous interrupted
     * copy left at the end, which is what stops the fragment being buried mid-file.
     */
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
                    // nothing useful to do
                }
            }
            return null;
        } finally {
            closeQuietly(rows);
        }
    }

    /** The first complete line of a file, or null if it has none. */
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

    /** The {@code epoch_ms} a row starts with, or -1 if it does not start with one. */
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

    /**
     * A line reader that also says whether the line it just returned was terminated.
     *
     * That distinction is the whole reason it exists: the file being read is being
     * appended to by the control loop at the same time, and taking {@link #append}'s
     * lock to read it would stall the loop behind a USB copy. Reading a row short of the
     * newline is the one hazard that leaves, and a reader that reports termination turns
     * it into a row to skip.
     */
    private static final class Rows {
        private final java.io.Reader r;
        private final char[] buf = new char[8192];
        private final StringBuilder line = new StringBuilder(256);
        private int len;
        private int pos;

        /** Did the line just returned end in a newline? */
        boolean terminated;

        Rows(File f) throws java.io.IOException {
            r = new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8");
        }

        /** The next line, or null at end of file. */
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
                // nothing useful to do
            }
        }
    }

    /** Escape a field for CSV. Only used for the free-text note column. */
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
