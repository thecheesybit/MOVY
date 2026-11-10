package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DrawerNavTest {
    private static DrawerNav nav() {
        DrawerNav n = new DrawerNav();
        n.setCounts(6, 4);
        return n;
    }

    @Test public void closedDrawerIgnoresKeys() {
        DrawerNav n = nav();
        assertFalse(n.isOpen());
        assertEquals(DrawerNav.Act.NONE, n.press(DrawerNav.Key.LEFT));
    }

    @Test public void edgeLeftOpensTheListOnTheCurrentSection() {
        DrawerNav n = nav();
        n.openList(2, 0);
        assertEquals(DrawerNav.Zone.LIST, n.zone());
        assertEquals(2, n.listIndex());
    }

    @Test public void secondLeftGoesToTheSourcesRowAndDownComesBack() {
        DrawerNav n = nav();
        n.openList(2, 0);
        assertEquals(DrawerNav.Act.MOVED, n.press(DrawerNav.Key.LEFT));
        assertEquals(DrawerNav.Zone.SOURCES, n.zone());
        assertEquals(DrawerNav.Act.MOVED, n.press(DrawerNav.Key.DOWN));
        assertEquals(DrawerNav.Zone.LIST, n.zone());
        assertEquals("the list keeps its row", 2, n.listIndex());
    }

    @Test public void theSourcesRowStartsOnTheCurrentSource() {
        DrawerNav n = nav();
        n.openList(2, 3);
        n.press(DrawerNav.Key.LEFT);
        assertEquals(3, n.sourceIndex());
    }

    @Test public void upFromTheTopRowGoesToSources() {
        DrawerNav n = nav();
        n.openList(0, 0);
        assertEquals(DrawerNav.Act.MOVED, n.press(DrawerNav.Key.UP));
        assertEquals(DrawerNav.Zone.SOURCES, n.zone());
    }

    @Test public void rightAndBackCloseAndRestoreFocus() {
        DrawerNav n = nav();
        n.openList(1, 0);
        assertEquals(DrawerNav.Act.CLOSE_RESTORE, n.press(DrawerNav.Key.RIGHT));
        assertFalse(n.isOpen());
        n.openList(1, 0);
        assertEquals(DrawerNav.Act.CLOSE_RESTORE, n.press(DrawerNav.Key.BACK));
        n.openSources(1);
        assertEquals(DrawerNav.Act.CLOSE_RESTORE, n.press(DrawerNav.Key.BACK));
        assertFalse(n.isOpen());
    }

    @Test public void rightInTheSourcesRowPicksTheNextSourceNeverClosesAndStopsAtTheEnd() {
        DrawerNav n = nav();
        n.openSources(1);
        assertEquals(DrawerNav.Act.MOVED, n.press(DrawerNav.Key.RIGHT));
        assertEquals(2, n.sourceIndex());
        assertEquals(DrawerNav.Act.MOVED, n.press(DrawerNav.Key.RIGHT));
        assertEquals(DrawerNav.Act.STAY, n.press(DrawerNav.Key.RIGHT));
        assertTrue(n.isOpen());
        assertEquals(3, n.sourceIndex());
        assertEquals(DrawerNav.Act.MOVED, n.press(DrawerNav.Key.LEFT));
        assertEquals(2, n.sourceIndex());
    }

    @Test public void okInTheSourcesRowSwitchesOkInTheListActivates() {
        DrawerNav n = nav();
        n.openSources(2);
        assertEquals(DrawerNav.Act.ACTIVATE_SOURCE, n.press(DrawerNav.Key.OK));
        assertEquals(2, n.sourceIndex());
        n.openList(3, 0);
        assertEquals(DrawerNav.Act.ACTIVATE_ITEM, n.press(DrawerNav.Key.OK));
        assertEquals(3, n.listIndex());
    }

    @Test public void menuTogglesBetweenListAndSourcesRowAndCloses() {
        DrawerNav n = nav();
        n.openList(1, 0);
        assertEquals(DrawerNav.Act.MOVED, n.press(DrawerNav.Key.MENU));
        assertEquals(DrawerNav.Zone.SOURCES, n.zone());
        assertEquals(DrawerNav.Act.CLOSE_RESTORE, n.press(DrawerNav.Key.MENU));
    }

    @Test public void listMovesAreClampedAndCountsShrinkSafely() {
        DrawerNav n = nav();
        n.openList(5, 0);
        assertEquals(DrawerNav.Act.STAY, n.press(DrawerNav.Key.DOWN));
        assertEquals(5, n.listIndex());
        n.setCounts(3, 4); // the recent titles went away
        assertEquals(2, n.listIndex());
    }

    @Test public void noSourcesMeansLeftStaysInTheList() {
        DrawerNav n = new DrawerNav();
        n.setCounts(4, 0);
        n.openList(1, 0);
        assertEquals(DrawerNav.Act.STAY, n.press(DrawerNav.Key.LEFT));
        assertEquals(DrawerNav.Zone.LIST, n.zone());
    }
}
