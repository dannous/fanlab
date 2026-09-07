package com.daleygames.fanlab;

/**
 * The arm-and-confirm window for a change to the picture: apply it, watch the screen, press
 * OK within fifteen seconds or it goes back by itself.
 *
 * <h3>Why the display controller needs one and the fan does not</h3>
 * Every fan control here can be undone by looking at the screen and pressing the control
 * again. CAIC and LABB are changes to the <i>display</i> controller: they re-point the
 * DLPC at lookup tables that may never have been calibrated for this engine, so the
 * plausible failures include a blanked or wrecked picture -- and then the owner cannot see
 * the control that would undo it. That is the same shape as changing a monitor's
 * resolution, and it takes the same answer: apply it, count down, and revert unless a human
 * says the picture is still there.
 *
 * <h3>The rule that makes the countdown worth anything</h3>
 * <b>The preference is persisted only after confirmation.</b> An armed-but-unconfirmed
 * change lives in this object and nowhere else, so it cannot survive a process death, and
 * it certainly cannot survive a reboot. That is load-bearing: a power cycle is the owner's
 * guaranteed escape from a broken picture -- both registers are runtime-only and the
 * factory {@code picosetting} blob is never written -- and a setting that came back on at
 * boot would take that escape away and turn a fifteen-second annoyance into a brick.
 *
 * <h3>One window, and which change it covers</h3>
 * The two display experiments share this rather than each holding their own, because two
 * countdowns running against one screen cannot be told apart by the person looking at it:
 * a picture that had gone wrong would come back in two stages, and neither stage would say
 * which change was responsible. So there is one deadline, and {@link #armedWhat} says what
 * is riding on it. Arming a second change while a window is open adds it and restarts the
 * clock, so the owner always gets the full fifteen seconds against what is on screen now.
 *
 * <h3>Why the service owns it and the activity does not</h3>
 * A countdown running in the activity ends when the activity does. Pressing HOME, the
 * launcher reclaiming memory, or the owner navigating away in a panic would all leave the
 * change applied with nothing left to revert it. So {@link FanService} holds one of these
 * and polls it on the 1 Hz tick; the activity only reads {@link #secondsLeft} to draw the
 * countdown and calls {@link #confirm} when OK is pressed.
 *
 * Pure Java, all state in one object behind one lock: the tick polls it, the UI thread
 * reads and confirms.
 */
public final class PictureArm {

    /**
     * How long the owner has to say the picture survived, milliseconds.
     *
     * Fifteen seconds is the resolution-change convention, and the arithmetic behind it is
     * the same: long enough to look up, register that the image is wrong and find one
     * button, short enough that waiting it out is a tolerable way to undo a mistake.
     */
    public static final long WINDOW_MS = 15000L;

    /** Nothing armed, nothing owed. */
    public static final int NONE = 0;
    /** The LED output control method, {@code 0x50}, and the gain budget behind it. */
    public static final int CAIC = 1;
    /** Local Area Brightness Boost, {@code 0x80}. */
    public static final int LABB = 2;

    /** Monotonic instant the window closes, or 0 when nothing is armed. */
    private long deadlineMs;

    /** Which changes the open window covers. Empty whenever {@link #deadlineMs} is 0. */
    private int armedMask;

    /**
     * Changes whose window closed with no confirmation, and whose off write has not landed.
     *
     * A latch rather than an edge, because the write can be deferred: {@link FanService}
     * does not touch the DLPC while the display is off, so the revert may have to wait for
     * the resume, and the note that eventually lands should still say the reason was a
     * countdown nobody answered.
     */
    private int revertMask;

    /** Start the window for {@code what}, or add to and restart one already running. */
    public synchronized void arm(int what, long nowMs) {
        int add = what & (CAIC | LABB);
        if (add == NONE) {
            return;
        }
        if (deadlineMs == 0L) {
            armedMask = NONE;
        }
        armedMask |= add;
        // A fresh arm supersedes a revert still owed on the same change: it is being asked
        // for again, so undoing it would be carrying out an instruction already withdrawn.
        revertMask &= ~add;
        deadlineMs = nowMs + WINDOW_MS;
    }

    /** Is a window open right now? */
    public synchronized boolean armed(long nowMs) {
        return deadlineMs != 0L && nowMs < deadlineMs;
    }

    /** Is a window open that covers {@code what}? */
    public synchronized boolean armed(int what, long nowMs) {
        return armed(nowMs) && (armedMask & what) != 0;
    }

    /** What the open window covers, or {@link #NONE} when none is open. */
    public synchronized int armedWhat(long nowMs) {
        return armed(nowMs) ? armedMask : NONE;
    }

    /**
     * Whole seconds left on the countdown, rounded up so it reads 15 the instant it starts
     * and 1 for the last second. 0 when nothing is armed.
     */
    public synchronized int secondsLeft(long nowMs) {
        if (deadlineMs == 0L || nowMs >= deadlineMs) {
            return 0;
        }
        return (int) ((deadlineMs - nowMs + 999L) / 1000L);
    }

    /**
     * Advance the clock. Called once per tick, before anything reads {@link #armed}.
     *
     * @return true on the tick where the window closes unconfirmed -- the caller's cue to
     *         write everything in {@link #revertingWhat} back off. Only once;
     *         {@link #reverting} is what stays true until the caller reports each write done.
     */
    public synchronized boolean poll(long nowMs) {
        if (deadlineMs == 0L || nowMs < deadlineMs) {
            return false;
        }
        deadlineMs = 0L;
        revertMask |= armedMask;
        armedMask = NONE;
        return true;
    }

    /**
     * The owner pressed OK.
     *
     * @return what was armed, which is the caller's permission to persist exactly those
     *         settings and nothing else. {@link #NONE} means the countdown had already run
     *         out -- and a press that arrives late must not resurrect a setting that has
     *         just been reverted, because the owner may be pressing OK at a picture that is
     *         already back.
     */
    public synchronized int confirm() {
        if (deadlineMs == 0L) {
            return NONE;
        }
        int was = armedMask;
        deadlineMs = 0L;
        armedMask = NONE;
        // Only what was confirmed. A revert owed on the other change belongs to an earlier
        // window and is still owed.
        revertMask &= ~was;
        return was;
    }

    /** Drop {@code what} from the window, unconfirmed, and without asking for a revert. */
    public synchronized void cancel(int what) {
        armedMask &= ~what;
        revertMask &= ~what;
        if (armedMask == NONE) {
            deadlineMs = 0L;
        }
    }

    /** Drop the whole window. The caller is turning everything off anyway. */
    public synchronized void cancel() {
        cancel(CAIC | LABB);
    }

    /** A revert is owed on {@code what}: its window closed unconfirmed, nothing wrote off. */
    public synchronized boolean reverting(int what) {
        return (revertMask & what) != 0;
    }

    /** A revert is owed on anything at all. */
    public synchronized boolean reverting() {
        return revertMask != NONE;
    }

    /** Everything a revert is owed on, for the log note. */
    public synchronized int revertingWhat() {
        return revertMask;
    }

    /** The off write for {@code what} landed. */
    public synchronized void reverted(int what) {
        revertMask &= ~what;
    }

    /** "CAIC", "LABB", "CAIC and LABB", or "" -- for the screen and the CSV note. */
    public static String name(int what) {
        boolean caic = (what & CAIC) != 0;
        boolean labb = (what & LABB) != 0;
        if (caic && labb) {
            return "CAIC and LABB";
        }
        return caic ? "CAIC" : labb ? "LABB" : "";
    }
}
