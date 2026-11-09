package app.movy.shell;

/**
 * Pure rule for the one TV loading indicator: a source switch / start takes the whole screen (opaque, spinner and caption),
 * a slow navigation or player buffering shows only the centred spinner, and there is never more than one spinner.
 */
final class LoadingMode {
    /** A navigation slower than this shows the spinner. */
    static final long NAV_DELAY_MS = 400;
    /** Player buffering shows the spinner after this long. */
    static final long BUFFER_DELAY_MS = 300;

    enum Mode { NONE, SPINNER, SCREEN }

    private LoadingMode() {}

    /** SCREEN wins over SPINNER; a fullscreen player only ever gets the spinner (never an opaque screen over the video). */
    static Mode resolve(boolean screen, boolean navigating, boolean buffering, boolean fullscreen) {
        if (screen && !fullscreen) return Mode.SCREEN;
        return screen || navigating || buffering ? Mode.SPINNER : Mode.NONE;
    }
}
