package app.movy.shell;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LoadingModeTest {
    @Test public void nothingWantedShowsNothing() {
        assertEquals(LoadingMode.Mode.NONE, LoadingMode.resolve(false, false, false, false));
    }

    @Test public void switchTakesTheWholeScreen() {
        assertEquals(LoadingMode.Mode.SCREEN, LoadingMode.resolve(true, true, true, false));
    }

    @Test public void navigationAndBufferingShareOneSpinner() {
        assertEquals(LoadingMode.Mode.SPINNER, LoadingMode.resolve(false, true, true, false));
        assertEquals(LoadingMode.Mode.SPINNER, LoadingMode.resolve(false, false, true, true));
    }

    @Test public void fullscreenNeverGetsAnOpaqueScreen() {
        assertEquals(LoadingMode.Mode.SPINNER, LoadingMode.resolve(true, false, false, true));
    }
}
