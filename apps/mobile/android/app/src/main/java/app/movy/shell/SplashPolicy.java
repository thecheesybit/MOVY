package app.movy.shell;

/** Pure rules for when the TV splash may lift. Later launches never wait for the warm-up; the first run waits at most 5 s. */
final class SplashPolicy {
    static final long NORMAL_CAP_MS = 6000;
    static final long FIRST_RUN_CAP_MS = 5000;

    private SplashPolicy() {}

    static long capMs(boolean firstRunWarmup) {
        return firstRunWarmup ? FIRST_RUN_CAP_MS : NORMAL_CAP_MS;
    }

    /** The page is ready; on the first run the sources also have to be warm (or the cap hits). */
    static boolean mayDismiss(boolean firstRunWarmup, boolean adapterReady, boolean warmupDone) {
        return adapterReady && (!firstRunWarmup || warmupDone);
    }
}
