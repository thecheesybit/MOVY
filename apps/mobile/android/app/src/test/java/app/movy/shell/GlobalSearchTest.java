package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GlobalSearchTest {
    private static final String ORIGIN = "https://site.example";

    /** TMDB-style answer: movies, shows and people mixed. */
    private static final String MULTI = "{\"results\":["
            + "{\"id\":550,\"media_type\":\"movie\",\"title\":\"Fight Club\",\"release_date\":\"1999-10-15\",\"poster_path\":\"/p1.jpg\"},"
            + "{\"id\":1399,\"media_type\":\"tv\",\"name\":\"Game of Thrones\",\"first_air_date\":\"2011-04-17\",\"poster_path\":null},"
            + "{\"id\":287,\"media_type\":\"person\",\"name\":\"Brad Pitt\"},"
            + "{\"id\":550,\"media_type\":\"movie\",\"title\":\"Fight Club\",\"release_date\":\"1999-10-15\"},"
            + "{\"media_type\":\"movie\",\"title\":\"No id\"}]}";

    private static JSONObject tmdbApi(String openMovie) throws Exception {
        return new JSONObject("{\"url\":\"https://api.example/3/search/multi?query={q}&page=1\",\"items\":\"results\",\"id\":\"id\","
                + "\"title\":[\"title\",\"name\"],\"year\":[\"release_date\",\"first_air_date\"],\"poster\":\"poster_path\","
                + "\"posterBase\":\"https://img.example/w342\",\"type\":\"media_type\","
                + "\"open\":{\"movie\":\"" + openMovie + "\",\"tv\":\"/tv/{id}\"}}");
    }

    @Test public void requestUrlEncodesTheQueryAndKeepsHostsInTheProfile() throws Exception {
        assertEquals("https://api.example/3/search/multi?query=fight%20club%20%26%20co&page=1",
                GlobalSearch.requestUrl(tmdbApi("/movie/{id}"), ORIGIN, "  fight   club & co "));
        JSONObject same = new JSONObject("{\"url\":\"/api/search?v=5&q={q}\",\"cookies\":true}");
        assertEquals(ORIGIN + "/api/search?v=5&q=dune", GlobalSearch.requestUrl(same, ORIGIN, "dune"));
        assertTrue(GlobalSearch.sendsCookies(same));
        assertFalse(GlobalSearch.sendsCookies(tmdbApi("/m")));
    }

    @Test public void requestUrlRefusesEmptyQueriesPlainHttpAndTemplatesWithoutQ() throws Exception {
        assertNull(GlobalSearch.requestUrl(tmdbApi("/m"), ORIGIN, "   "));
        assertNull(GlobalSearch.requestUrl(new JSONObject("{\"url\":\"http://x.example/s?q={q}\"}"), ORIGIN, "a"));
        assertNull(GlobalSearch.requestUrl(new JSONObject("{\"url\":\"/s\"}"), ORIGIN, "a"));
        assertNull(GlobalSearch.requestUrl(new JSONObject("{\"url\":\"/s?q={q}\"}"), "http://site.example", "a"));
        assertNull(GlobalSearch.requestUrl(null, ORIGIN, "a"));
    }

    @Test public void parseKeepsMoviesAndShowsDropsPeopleDuplicatesAndBadRows() throws Exception {
        List<GlobalSearch.Hit> hits = GlobalSearch.parse(tmdbApi("/movie/{id}"), MULTI, 12);
        assertEquals(2, hits.size());
        GlobalSearch.Hit m = hits.get(0);
        assertEquals("Fight Club", m.title);
        assertEquals("1999", m.year);
        assertEquals("/movie/550", m.path);
        assertEquals("https://img.example/w342/p1.jpg", m.poster);
        GlobalSearch.Hit t = hits.get(1);
        assertEquals("Game of Thrones", t.title);
        assertEquals("2011", t.year);
        assertEquals("/tv/1399", t.path);
        assertEquals("", t.poster);
    }

    @Test public void parseFillsSlugAndYearRoutes() throws Exception {
        List<GlobalSearch.Hit> hits = GlobalSearch.parse(tmdbApi("/movie/{id}-{slug}-{year}"), MULTI, 12);
        assertEquals("/movie/550-fight-club-1999", hits.get(0).path);
    }

    @Test public void parseHonoursMaxNestedItemsAndTypelessApis() throws Exception {
        JSONObject api = new JSONObject("{\"url\":\"/s?q={q}\",\"items\":\"data.list\",\"title\":[\"t\"],\"poster\":\"img\","
                + "\"open\":{\"*\":\"/watch/{id}\"}}");
        String body = "{\"data\":{\"list\":[{\"id\":\"a b\",\"t\":\"One\",\"img\":\"https://i.example/1.jpg\"},{\"id\":\"2\",\"t\":\"Two\"},"
                + "{\"id\":\"3\",\"t\":\"Three\"}]}}";
        List<GlobalSearch.Hit> hits = GlobalSearch.parse(api, body, 2);
        assertEquals(2, hits.size());
        assertEquals("/watch/a%20b", hits.get(0).path);
        assertEquals("https://i.example/1.jpg", hits.get(0).poster);
    }

    @Test public void parseSurvivesJunk() throws Exception {
        assertTrue(GlobalSearch.parse(tmdbApi("/m/{id}"), "not json", 12).isEmpty());
        assertTrue(GlobalSearch.parse(tmdbApi("/m/{id}"), "{\"results\":5}", 12).isEmpty());
        assertTrue(GlobalSearch.parse(new JSONObject("{\"url\":\"/s?q={q}\"}"), MULTI, 12).isEmpty()); // no open map
        // a route template that is not a site path is never opened
        assertTrue(GlobalSearch.parse(tmdbApi("https://elsewhere.example/{id}"), MULTI, 12).stream().noneMatch(h -> h.type.equals("movie")));
    }

    @Test public void rowsPutTheCurrentSourceFirstThenMostResults() {
        GlobalSearch.Hit h = new GlobalSearch.Hit("1", "A", "", "", "movie", "/m/1");
        List<GlobalSearch.Row> rows = Arrays.asList(
                new GlobalSearch.Row("a", GlobalSearch.Status.FAILED, null),
                new GlobalSearch.Row("b", GlobalSearch.Status.OK, Arrays.asList(h)),
                new GlobalSearch.Row("c", GlobalSearch.Status.OK, Arrays.asList(h, h, h)),
                new GlobalSearch.Row("d", GlobalSearch.Status.NO_API, null),
                new GlobalSearch.Row("e", GlobalSearch.Status.LOADING, null),
                new GlobalSearch.Row("f", GlobalSearch.Status.OK, Collections.<GlobalSearch.Hit>emptyList()));
        StringBuilder order = new StringBuilder();
        for (GlobalSearch.Row r : GlobalSearch.order(rows, "b")) order.append(r.sourceId);
        assertEquals("bcefad", order.toString());
        order.setLength(0);
        for (GlobalSearch.Row r : GlobalSearch.order(rows, "a")) order.append(r.sourceId); // current failed: no head start
        assertEquals("cbefad", order.toString());
    }

    @Test public void recentsAreNewestFirstWithoutDuplicatesAndCapped() {
        List<String> r = GlobalSearch.addRecent(Arrays.asList("dune", "Up", "alien"), "  UP ");
        assertEquals(Arrays.asList("UP", "dune", "alien"), r);
        List<String> many = Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertEquals(GlobalSearch.MAX_RECENTS, GlobalSearch.addRecent(many, "new").size());
        assertEquals("new", GlobalSearch.addRecent(many, "new").get(0));
        assertEquals(Arrays.asList("a", "b"), GlobalSearch.recentsFromJson(GlobalSearch.recentsToJson(Arrays.asList("a", "b", "A"))));
        assertTrue(GlobalSearch.recentsFromJson("{broken").isEmpty());
    }

    @Test public void queryCleaningAndHelpers() {
        assertEquals("a b", GlobalSearch.cleanQuery(" a\n\tb "));
        assertEquals(GlobalSearch.MAX_QUERY, GlobalSearch.cleanQuery(new String(new char[300]).replace('\0', 'x')).length());
        assertEquals("spider-man-no-way-home", GlobalSearch.slug("Spider-Man: No Way Home!"));
        assertEquals("2021", GlobalSearch.yearOf("2021-12-15"));
        assertEquals("", GlobalSearch.yearOf("soon"));
    }
}
