package app.movy.shell;

/** Pure timing rules of the source warm-up (TV): when to start, when to resume and which sources still need warming. */
final class PreloadPolicy {
    /** Delay after the active source's first page finished before the hidden warm-up starts. */
    static final long START_DELAY_MS = 1500;
    /** First run: the warm-up starts at launch, in parallel with the active source. */
    static final long FIRST_RUN_START_MS = 300;
    /** After a player closes, the user must stay idle on a browse page this long before the warm-up resumes. */
    static final long RESUME_IDLE_MS = 10_000;
    /** A source warmed more recently than this is not warmed again while its assets are still in the cache. */
    static final long WARM_TTL_MS = 12L * 60 * 60 * 1000;
    /** Fewer cached entries than this and a "recent" warm-up cannot be trusted (cache cleared by the user). */
    static final int MIN_CACHE_ENTRIES = 100;

    private PreloadPolicy() {}

    /** @param warmedAt epoch ms of the last completed warm-up of the source, 0 when never. */
    static boolean shouldWarm(long now, long warmedAt, int cacheEntries) {
        if (warmedAt <= 0 || warmedAt > now) return true; // never warmed, or the clock went backwards
        boolean fresh = now - warmedAt < WARM_TTL_MS;
        return !(fresh && cacheEntries >= MIN_CACHE_ENTRIES);
    }
}
