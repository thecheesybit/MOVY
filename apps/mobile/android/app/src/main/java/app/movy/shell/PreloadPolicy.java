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

    /** Low-end TVs (32-bit, low RAM or few cores) wait this long after the active page finished. */
    static final long LOW_END_START_DELAY_MS = 8000;
    private static final int LOW_END_MAX_CORES = 4;

    private PreloadPolicy() {}

    /** A TV that cannot afford hidden pages loading in parallel with the visible one. */
    static boolean isLowEnd(boolean lowRamDevice, boolean is64Bit, int cores) {
        return lowRamDevice || !is64Bit || cores <= LOW_END_MAX_CORES;
    }

    /** Delay before the warm-up starts once the active source's first page finished. */
    static long startDelayMs(boolean lowEnd) {
        return lowEnd ? LOW_END_START_DELAY_MS : START_DELAY_MS;
    }

    /** First run warms in parallel with the active source only on capable devices. */
    static boolean warmInParallel(boolean firstRun, boolean lowEnd) {
        return firstRun && !lowEnd;
    }

    /** Only a page of a real source counts as "the active page finished" (not the shell's own local start page). */
    static boolean isSourcePage(String url, boolean belongsToSource) {
        return belongsToSource && url != null && (url.startsWith("https://") || url.startsWith("http://"));
    }

    /** @param warmedAt epoch ms of the last completed warm-up of the source, 0 when never. */
    static boolean shouldWarm(long now, long warmedAt, int cacheEntries) {
        if (warmedAt <= 0 || warmedAt > now) return true; // never warmed, or the clock went backwards
        boolean fresh = now - warmedAt < WARM_TTL_MS;
        return !(fresh && cacheEntries >= MIN_CACHE_ENTRIES);
    }
}
