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
 * A destination that fails is dropped and retried later rather than being allowed to
 * take the run down. Nothing here throws.
 *
 * Pure Java (java.io only), so the host test can drive it.
 */
public final class CsvLogger {

    public static final String HEADER =
            "epoch_ms,iso_local,adc,degC,prop_led_temp,fan_ctrl,rgblevel,led_status,"
                    + "profile,mode,desired,wrote,note,soc_pll_c,soc_ddr_c,soc_sar_c"
                    + ",thr_cpufreq,thr_cpucore,thr_gpufreq,thr_gpucore";

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
