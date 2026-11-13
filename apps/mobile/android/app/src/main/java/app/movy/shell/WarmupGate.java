package app.movy.shell;

/**
 * Pure rule of the warm-up during playback (TV, plan stage 7b): the other sources' shells may be refreshed only while a
 * fullscreen video has played smoothly for a while with plenty buffered, never right after a stall or a seek, a few at
 * a time with a pause between them. Fed with the page's media status (about once a second) and the buffering spinner.
 */
final class WarmupGate {
    /** Smooth playback needed before the first request. */
    static final long MIN_PLAYED_MS = 60_000;
    /** Seconds buffered ahead of the play head. */
    static final double MIN_AHEAD_S = 30;
    /** No stall or seek in this window. */
    static final long QUIET_MS = 30_000;
    /** Pause between two sources (each refresh fetches one source's shell and its assets in the background). */
    static final long SPACING_MS = 60_000;
    /** Sources refreshed per app session at most. */
    static final int MAX_PER_SESSION = 3;
    /** A status this much later than the previous one is a gap (app in background, frame reloaded), not playback. */
    private static final long MAX_SAMPLE_GAP_MS = 5_000;
    /** The play head moved this much further (or less) than the wall clock: a seek. */
    private static final double SEEK_SLACK_S = 3;

    private long playedMs;
    private long lastSampleAt = -1;
    private double lastTime;
    private boolean playing;
    private double ahead;
    private long lastStallAt = Long.MIN_VALUE / 2;
    private long lastStartAt = Long.MIN_VALUE / 2;
    private int started;

    /** One media status of the page: paused, play head, seconds buffered ahead, fullscreen. */
    void onStatus(long now, boolean paused, double currentTime, double bufferedAhead, boolean fullscreen) {
        boolean wasPlaying = playing;
        playing = !paused && fullscreen;
        ahead = bufferedAhead;
        if (lastSampleAt >= 0 && wasPlaying && playing) {
            long dt = now - lastSampleAt;
            double moved = currentTime - lastTime;
            if (dt > MAX_SAMPLE_GAP_MS || dt <= 0) {
                // not continuous: count nothing
            } else if (Math.abs(moved - dt / 1000.0) > SEEK_SLACK_S && Math.abs(moved - 2 * dt / 1000.0) > SEEK_SLACK_S) {
                onStall(now); // jumped: a seek (1x and 2x speed both count as normal playback)
            } else {
                playedMs += dt;
            }
        }
        if (!fullscreen) playedMs = 0; // the player closed: the next video starts from zero
        lastSampleAt = now;
        lastTime = currentTime;
    }

    /** The buffering spinner came up (a `waiting` stall) or the user seeked. */
    void onStall(long now) {
        lastStallAt = now;
        playedMs = 0;
    }

    /** True when the next source may be refreshed now. */
    boolean open(long now) {
        return playing && started < MAX_PER_SESSION && playedMs >= MIN_PLAYED_MS && ahead >= MIN_AHEAD_S
                && now - lastStallAt >= QUIET_MS && now - lastStartAt >= SPACING_MS;
    }

    /** A refresh was started at {@code now}. */
    void started(long now) {
        started++;
        lastStartAt = now;
    }

    int startedCount() {
        return started;
    }
}
