package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SplashPolicyTest {
    @Test public void laterLaunchesNeverWaitForTheWarmup() {
        assertFalse(SplashPolicy.mayDismiss(false, false, false));
        assertTrue(SplashPolicy.mayDismiss(false, true, false));
        assertTrue(SplashPolicy.mayDismiss(false, true, true));
    }

    @Test public void firstRunWaitsForPageAndWarmup() {
        assertFalse(SplashPolicy.mayDismiss(true, true, false));
        assertFalse(SplashPolicy.mayDismiss(true, false, true));
        assertTrue(SplashPolicy.mayDismiss(true, true, true));
    }

    @Test public void firstRunNeverHoldsTheSplashLongerThanFiveSeconds() {
        assertEquals(5000, SplashPolicy.capMs(true));
        assertEquals(6000, SplashPolicy.capMs(false));
    }
}
