package app.movy.shell;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SettingsCycleTest {
    @Test public void cyclingChoicesWrapRoundAndRecoverFromBadValues() {
        assertEquals(1, SettingsModel.next(0, 5));
        assertEquals(4, SettingsModel.next(3, 5));
        assertEquals(0, SettingsModel.next(4, 5));
        assertEquals(0, SettingsModel.next(9, 3));
        assertEquals(0, SettingsModel.next(-1, 3));
        assertEquals(5, SettingsModel.SUBTITLE_SIZES.length);
        assertEquals(3, SettingsModel.SUBTITLE_COLOURS.length);
    }
}
