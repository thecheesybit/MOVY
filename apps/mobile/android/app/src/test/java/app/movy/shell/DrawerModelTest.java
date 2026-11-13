package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DrawerModelTest {
    private static final String PROFILE = "{\"drawer\":{\"accent\":\"#3b82f6\",\"items\":["
            + "{\"id\":\"account\",\"label\":\"Account\",\"icon\":\"user\",\"action\":\"account\"},"
            + "{\"id\":\"history\",\"label\":\"History\",\"icon\":\"history\",\"path\":\"/continue-watching\"},"
            + "{\"id\":\"shorts\",\"label\":\"Shorts\",\"icon\":\"clip\",\"path\":\"/shorts\"},"
            + "{\"id\":\"settings\",\"label\":\"Site settings\",\"icon\":\"settings\",\"path\":\"/settings\"},"
            + "{\"id\":\"movies\",\"label\":\"Movies\",\"icon\":\"film\",\"path\":\"/movies\"},"
            + "{\"id\":\"home\",\"label\":\"Home\",\"icon\":\"home\",\"path\":\"/\"},"
            + "{\"id\":\"search\",\"label\":\"Search\",\"icon\":\"search\",\"action\":\"search\"},"
            + "{\"id\":\"mylist\",\"label\":\"My List\",\"icon\":\"bookmark\",\"path\":\"/lists\"},"
            + "{\"id\":\"shows\",\"label\":\"Series\",\"icon\":\"tv\",\"path\":\"/series\"}]}}";

    private static List<String> ids(List<DrawerModel.Item> l) {
        String[] a = new String[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i).id;
        return Arrays.asList(a);
    }

    @Test public void orderIsTheSameForEverySource() {
        List<DrawerModel.Item> l = DrawerModel.build(PROFILE, "Settings");
        assertEquals(Arrays.asList("search", "home", "movies", "shows", "shorts", "mylist", "history", "settings", "account"), ids(l));
    }

    @Test public void siteSettingsItemIsReplacedByTheNativeOne() {
        List<DrawerModel.Item> l = DrawerModel.build(PROFILE, "Settings");
        DrawerModel.Item s = l.get(DrawerModel.indexOf(l, DrawerModel.SETTINGS_ID));
        assertEquals("Settings", s.label);
        assertNull(s.path);
    }

    @Test public void nativeWatchHistoryOnlyWhereTheSiteHasNone() {
        assertEquals(ids(DrawerModel.build(PROFILE, "Settings")), ids(DrawerModel.build(PROFILE, "Settings", "Watch History")));
        List<DrawerModel.Item> l = DrawerModel.build("{\"drawer\":{\"items\":[{\"id\":\"home\",\"label\":\"Home\",\"icon\":\"home\",\"path\":\"/\"}]}}",
                "Settings", "Watch History");
        assertEquals(Arrays.asList("home", DrawerModel.NATIVE_HISTORY_ID, "settings"), ids(l));
        assertNull(l.get(1).path);
    }

    @Test public void missingProfileStillHasSettings() {
        assertEquals(Collections.singletonList("settings"), ids(DrawerModel.build("{}", "Settings")));
        assertEquals(Collections.singletonList("settings"), ids(DrawerModel.build(null, "Settings")));
        assertEquals(Collections.singletonList("settings"), ids(DrawerModel.build("{broken", "Settings")));
    }

    @Test public void currentItemIsTheLongestMatchingPath() {
        List<DrawerModel.Item> l = DrawerModel.build(PROFILE, "Settings");
        assertEquals("home", l.get(DrawerModel.currentIndex(l, "/")).id);
        assertEquals("movies", l.get(DrawerModel.currentIndex(l, "/movies")).id);
        assertEquals("movies", l.get(DrawerModel.currentIndex(l, "/movies/page/2")).id);
        assertEquals(-1, DrawerModel.currentIndex(l, "/movie/123"));
        assertEquals("home is where the focus lands when nothing matches", "home", l.get(DrawerModel.openIndex(l, "/movie/123")).id);
    }

    @Test public void accentComesFromTheProfileOrTheFallback() {
        assertEquals(0xFF3B82F6, DrawerModel.accentOf(PROFILE, 0xFF111111));
        assertEquals(0xFF111111, DrawerModel.accentOf("{}", 0xFF111111));
        assertEquals(0xFF111111, DrawerModel.accentOf("{\"drawer\":{\"accent\":\"red\",\"items\":[]}}", 0xFF111111));
    }

    @Test public void rowsShrinkToFit() {
        assertEquals(40, DrawerModel.cellDp(500, 8));
        assertEquals(31, DrawerModel.cellDp(310, 10));
        assertEquals(30, DrawerModel.cellDp(200, 12));
    }

    @Test public void scrollKeepsTheFocusedRowVisibleAndInsideTheContent() {
        assertEquals(0, DrawerModel.ensureVisible(0, 0, 40, 200));
        assertEquals(40, DrawerModel.ensureVisible(0, 200, 40, 200));
        assertEquals(80, DrawerModel.ensureVisible(120, 80, 40, 200));
        assertEquals(120, DrawerModel.ensureVisible(120, 150, 40, 200));
        assertEquals(0, DrawerModel.clampScroll(-5, 300, 200));
        assertEquals(100, DrawerModel.clampScroll(500, 300, 200));
        assertEquals(0, DrawerModel.clampScroll(50, 100, 200));
    }

    @Test public void listViewportIsTheScreenMinusMarginsAndTheSourcesBlock() {
        assertEquals(540 - 54 - DrawerModel.SOURCE_BLOCK_DP, DrawerModel.listViewportDp(540, 27));
        assertEquals(0, DrawerModel.listViewportDp(100, 60));
    }

    @Test public void theOpenListTightensItsRowsToFit() {
        int vp = 394;
        assertEquals(30, DrawerModel.openCellDp(vp, 13));
        assertEquals(39, DrawerModel.openCellDp(vp, 10));
        assertEquals(40, DrawerModel.openCellDp(vp, 6));
        assertEquals(30, DrawerModel.openCellDp(100, 12));
        assertEquals(40, DrawerModel.openCellDp(vp, 0));
        assertEquals(DrawerModel.MIN_CELL_DP, 30);
    }
}
