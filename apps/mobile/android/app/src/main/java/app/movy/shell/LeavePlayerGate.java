package app.movy.shell;

/**
 * Pure guard for "one BACK leaves a playing player" (TV). BACK exits fullscreen at once and asks the page to leave the player
 * {@link #DELAY_MS} later. Inside that window a second BACK must not run (it would leave the page entirely: fullscreen is already
 * gone, so it would look like a plain page BACK), and the delayed step only runs if the page is still the one BACK was pressed on.
 */
final class LeavePlayerGate {
    /** Delay before the page is asked to leave the player. */
    static final long DELAY_MS = 250;
    /** A second BACK this soon after the first is ignored (delay plus the time the page needs to act). */
    static final long WINDOW_MS = DELAY_MS + 250;

    private String url;
    private long armedAt = Long.MIN_VALUE / 2;

    /** The first BACK left fullscreen on {@code pageUrl}. */
    void arm(String pageUrl, long now) {
        url = pageUrl;
        armedAt = now;
    }

    boolean swallowsBack(long now) {
        return now - armedAt < WINDOW_MS;
    }

    /** The delayed leave runs only while the page is unchanged. */
    boolean mayRun(String currentUrl) {
        return url != null && url.equals(currentUrl);
    }
}
