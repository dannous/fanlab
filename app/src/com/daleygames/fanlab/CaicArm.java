package com.daleygames.fanlab;

/**
 * The arm-and-confirm window for CAIC: turn it on, watch the picture, press OK within
 * fifteen seconds or it goes back by itself.
 *
 * <h3>Why CAIC needs one and nothing else in the app does</h3>
 * Every other control here moves the fan. If a fan setting is wrong the owner can see the
 * screen, read the status line and press the control again. CAIC is a change to the
 * <i>display</i> controller: it re-points the DLPC at lookup tables that may never have
 * been calibrated for this engine, so the plausible failures include a blanked or wrecked
 * picture -- and then the owner cannot see the control that would undo it. That is the
 * same shape as changing a monitor's resolution, and it takes the same answer: apply it,
 * count down, and revert unless a human says the picture is still there.
 *
 * <h3>The rule that makes the countdown worth anything</h3>
 * <b>The preference is persisted only after confirmation.</b> An armed-but-unconfirmed
 * CAIC lives in this object and nowhere else, so it cannot survive a process death, and it
 * certainly cannot survive a reboot. That is load-bearing: a power cycle is the owner's
 * guaranteed escape from a broken picture -- the register is runtime-only and the factory
 * {@code picosetting} blob is never written -- and a setting that came back on at boot
 * would take that escape away and turn a fifteen-second annoyance into a brick.
 *
 * <h3>Why the service owns it and the activity does not</h3>
 * A countdown running in the activity ends when the activity does. Pressing HOME, the
 * launcher reclaiming memory, or the owner navigating away in a panic would all leave CAIC
 * on with nothing left to revert it. So {@link FanService} holds one of these and polls it
 * on the 1 Hz tick; the activity only reads {@link #secondsLeft} to draw the countdown and
 * calls {@link #confirm} when OK is pressed.
 *
 * Pure Java, all state in one object behind one lock: the tick polls it, the UI thread
 * reads and confirms.
 */
public final class CaicArm {

    /**
     * How long the owner has to say the picture survived, milliseconds.
     *
     * Fifteen seconds is the resolution-change convention, and the arithmetic behind it is
     * the same: long enough to look up, register that the image is wrong and find one
     * button, short enough that waiting it out is a tolerable way to undo a mistake.
     */
    public static final long WINDOW_MS = 15000L;

    /** Monotonic instant the window closes, or 0 when nothing is armed. */
    private long deadlineMs;

    /**
     * The window closed with no confirmation and the off write has not landed yet.
     *
     * A latch rather than an edge, because the write can be deferred: {@link FanService}
     * does not touch the DLPC while the display is off, so the revert may have to wait for
     * the resume, and the note that eventually lands should still say the reason was a
     * countdown nobody answered.
     */
    private boolean reverting;

    /** Start the window, or restart it if one is already running. */
    public synchronized void arm(long nowMs) {
        deadlineMs = nowMs + WINDOW_MS;
        reverting = false;
    }

    /** Is a window open right now? */
    public synchronized boolean armed(long nowMs) {
        return deadlineMs != 0L && nowMs < deadlineMs;
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
     *         write CAIC off. Only once; {@link #reverting()} is what stays true until the
     *         caller reports the write done.
     */
    public synchronized boolean poll(long nowMs) {
        if (deadlineMs == 0L || nowMs < deadlineMs) {
            return false;
        }
        deadlineMs = 0L;
        reverting = true;
        return true;
    }

    /**
     * The owner pressed OK.
     *
     * @return true if there was actually a window open, which is the caller's permission to
     *         persist the preference. False means the countdown had already run out -- and
     *         a press that arrives late must not resurrect a setting that has just been
     *         reverted, because the owner may be pressing OK at a picture that is already
     *         back.
     */
    public synchronized boolean confirm() {
        if (deadlineMs == 0L) {
            return false;
        }
        deadlineMs = 0L;
        reverting = false;
        return true;
    }

    /** Drop the window without confirming it, and without asking for a revert. */
    public synchronized void cancel() {
        deadlineMs = 0L;
        reverting = false;
    }

    /** A revert is owed: the window closed unconfirmed and nothing has written off yet. */
    public synchronized boolean reverting() {
        return reverting;
    }

    /** The off write landed. */
    public synchronized void reverted() {
        reverting = false;
    }
}
