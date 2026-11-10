package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class SearchTextTest {
    @Test public void cleanDropsControlsFlattensBreaksAndCaps() {
        assertEquals("", SearchText.clean(null));
        assertEquals("fight club", SearchText.clean("fight club"));
        assertEquals("a b c d", SearchText.clean("a\nb\tc\r\u2028d").replace("  ", " "));
        assertEquals("ab", SearchText.clean("a\u0000\u0007b"));
        assertEquals(SearchText.MAX_LEN, SearchText.clean(new String(new char[500]).replace('\0', 'x')).length());
    }

    @Test public void diffFindsTheShortestEdit() {
        SearchText.Edit append = SearchText.diff("fight", "fight c");
        assertEquals(5, append.prefix);
        assertEquals("", append.removed);
        assertEquals(" c", append.inserted);

        SearchText.Edit backspace = SearchText.diff("fight", "figh");
        assertEquals("t", backspace.removed);
        assertEquals("", backspace.inserted);

        SearchText.Edit middle = SearchText.diff("abcd", "abXd");
        assertEquals(2, middle.prefix);
        assertEquals("c", middle.removed);
        assertEquals("X", middle.inserted);

        assertTrue(SearchText.diff("same", "same").isNoop());
        SearchText.Edit all = SearchText.diff("abc", "");
        assertEquals("abc", all.removed);
        SearchText.Edit repeat = SearchText.diff("aa", "aaa"); // the suffix never overlaps the prefix
        assertEquals("", repeat.removed);
        assertEquals("a", repeat.inserted);
        assertFalse(repeat.isNoop());
        assertTrue(SearchText.diff(null, null).isNoop());
    }

    @Test public void mirrorSendsOnlyWhatChanged() {
        SearchText.Mirror m = new SearchText.Mirror();
        assertEquals("f", m.next("f"));
        assertNull("a cursor move or repeated commit sends nothing", m.next("f"));
        assertEquals("fi", m.next("fi"));
        assertEquals("f", m.next("f"));
        assertEquals("", m.next(""));
        assertNull(m.next(""));
    }

    @Test public void mirrorStartsFromTheBoxCurrentTextAndCanBeForgotten() {
        SearchText.Mirror m = new SearchText.Mirror();
        m.reset("dune");
        assertNull("opening the keyboard on 'dune' must not resend it", m.next("dune"));
        assertEquals("dune 2", m.next("dune 2"));
        m.forget();
        assertEquals("dune 2", m.next("dune 2"));
    }

    @Test public void jsStringCannotEscapeTheLiteral() {
        String bs = String.valueOf((char) 92);
        String q = "\"";
        assertEquals(q + "plain" + q, SearchText.jsString("plain"));
        assertEquals(q + "a" + bs + q + "b" + q, SearchText.jsString("a" + q + "b"));
        assertEquals(q + "a" + bs + bs + "b" + q, SearchText.jsString("a" + bs + "b"));
        assertEquals(q + bs + "u003c/script" + bs + "u003e" + q, SearchText.jsString("</script>"));
        assertEquals(q + "a" + bs + "nb" + q, SearchText.jsString("a" + (char) 10 + "b"));
        assertEquals(q + bs + "u2028" + q, SearchText.jsString(String.valueOf((char) 0x2028)));
        assertEquals(q + bs + "u0001" + q, SearchText.jsString(String.valueOf((char) 1)));
        assertEquals(q + "it's" + q, SearchText.jsString("it's"));
    }

    @Test public void callBuildsAGuardedPageCall() {
        assertEquals("window.__movyTv&&window.__movyTv.searchSet&&window.__movyTv.searchSet(\"a\\\"b\")", SearchText.call("searchSet", "a\"b"));
        assertEquals("window.__movyTv&&window.__movyTv.searchClosed&&window.__movyTv.searchClosed()", SearchText.callPlain("searchClosed"));
        assertEquals("window.__movyTv&&window.__movyTv.x&&window.__movyTv.x(true,false)", SearchText.callPlain("x", true, false));
    }

    @Test public void voicePickTakesTheFirstUsablePhrase() {
        assertEquals("", SearchVoice.pick(null));
        assertEquals("", SearchVoice.pick(Collections.emptyList()));
        assertEquals("fight club", SearchVoice.pick(Arrays.asList("  fight   club ", "fight clubs")));
        assertEquals("dune", SearchVoice.pick(Arrays.asList("", "   ", "dune")));
        assertEquals("a b", SearchVoice.pick(Collections.singletonList("a\nb")));
    }
}
