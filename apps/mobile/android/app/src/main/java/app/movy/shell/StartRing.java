package app.movy.shell;

/**
 * Pure timing of the start-screen progress ring (TV). The ring fills once in {@link #RING_MS}; when the page is ready (or OK is
 * pressed) the rest is fast-forwarded over at most {@link #FAST_MS}. The site opens when the ring is full AND the page has painted;
 * a full ring that is still waiting shows the spinner. No Android state: unit tested.
 */
final class StartRing {
    /** One full circle. */
    static final long RING_MS = 3000;
    /** The remaining arc is drawn over at most this long once the page is ready or OK was pressed. */
    static final long FAST_MS = 300;

    enum Phase { RING, SPINNER, OPEN }

    private long start;
    private long end;
    private long fastFrom;
    private float fastFromProgress;
    private boolean fast;
    private boolean ready;

    StartRing(long now) {
        restart(now);
    }

    /** New source focused: the ring and the readiness start over. */
    void restart(long now) {
        start = now;
        end = now + RING_MS;
        fast = false;
        ready = false;
    }

    /** The focused source has painted. */
    void pageReady(long now) {
        ready = true;
        accelerate(now);
    }

    /** OK pressed: finish the ring now (the page may still have to paint). */
    void skip(long now) {
        accelerate(now);
    }

    private void accelerate(long now) {
        if (fast || now >= end) return;
        fastFromProgress = progress(now);
        fastFrom = now;
        end = Math.min(end, now + FAST_MS);
        fast = true;
    }

    boolean isReady() {
        return ready;
    }

    /** 0..1 fraction of the circle drawn. */
    float progress(long now) {
        if (now >= end) return 1f;
        if (fast) {
            long span = Math.max(1, end - fastFrom);
            return Math.min(1f, fastFromProgress + (1f - fastFromProgress) * (now - fastFrom) / span);
        }
        return Math.max(0f, Math.min(1f, (now - start) / (float) RING_MS));
    }

    Phase phase(long now) {
        if (now < end) return Phase.RING;
        return ready ? Phase.OPEN : Phase.SPINNER;
    }
}
