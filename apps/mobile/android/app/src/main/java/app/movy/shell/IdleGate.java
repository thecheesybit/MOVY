package app.movy.shell;

/**
 * Pure state machine for the TV idle mode: after {@link #IDLE_MS} without a key everything hides; the first key after
 * that wakes the screen AND acts (nothing is swallowed). What OK does on a faded player (play/pause at once) is decided by
 * the page (tvfs.js): the native side cannot see web focus. Time is passed in so it can be unit tested.
 */
final class IdleGate {
    static final long IDLE_MS = 5000;

    enum Decision { PASS }

    private final long idleMs;
    private boolean idle;
    private long lastKeyAt;

    IdleGate(long idleMs, long now) {
        this.idleMs = idleMs;
        this.lastKeyAt = now;
    }

    boolean isIdle() {
        return idle;
    }

    /** Time at which the next idle check is due (only meaningful while awake). */
    long dueAt() {
        return lastKeyAt + idleMs;
    }

    /** Feeds one key event. {@code woke} (out, index 0) is set when this event ended the idle state. */
    Decision onKey(boolean down, int keyCode, long now, boolean[] woke) {
        if (woke != null && woke.length > 0) woke[0] = false;
        if (!down) return Decision.PASS;
        if (idle) {
            idle = false;
            lastKeyAt = now;
            if (woke != null && woke.length > 0) woke[0] = true;
            return Decision.PASS;
        }
        lastKeyAt = now;
        return Decision.PASS;
    }

    /** Checks the clock; true when this call switched to idle. */
    boolean tick(long now) {
        if (!idle && now - lastKeyAt >= idleMs) {
            idle = true;
            return true;
        }
        return false;
    }

    /** Resets the timer without a key (native UI changes, page loads). */
    void touch(long now) {
        if (!idle) lastKeyAt = now;
    }
}
