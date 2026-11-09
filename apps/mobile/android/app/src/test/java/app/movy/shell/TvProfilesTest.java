package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

public class TvProfilesTest {
    private static final Obfuscator OBF = new Obfuscator("AQIDBAUGBwgJCgsMDQ4PEA==");

    private static String defaults(String extra) {
        return "{\"recommendedSource\":\"a\",\"sources\":[{\"id\":\"a\",\"name\":\"Alpha\",\"urlEnc\":\"" + OBF.encode("https://a.example.com/")
                + "\",\"allowedHosts\":[\"a.example.com\",\"*.a.example.com\"]},{\"id\":\"b\",\"name\":\"Beta\",\"urlEnc\":\""
                + OBF.encode("https://b.example.org/") + "\",\"allowedHosts\":[\"b.example.org\"]}]" + extra + "}";
    }

    private static final String PROFILES = ",\"tvProfiles\":{\"a\":{\"notes\":\"x\",\"rows\":[\".r\"],\"skip\":[\".s\"],"
            + "\"player\":{\"fullscreenButton\":[\"#fs\"],\"depth\":1},\"viewportWidth\":1280}}";

    @Test public void defaultsCarryProfilesAndDropUnknownKeys() throws Exception {
        LiveConfig c = LiveConfig.parseDefaults(defaults(PROFILES), OBF);
        JSONObject a = new JSONObject(c.tvProfileFor("a"));
        assertEquals(".r", a.getJSONArray("rows").getString(0));
        assertEquals("#fs", a.getJSONObject("player").getJSONArray("fullscreenButton").getString(0));
        assertFalse("notes are for humans", a.has("notes"));
        assertEquals("{}", c.tvProfileFor("b"));
        assertEquals("{}", c.tvProfileFor(null));
    }

    @Test public void remoteOverridesPerSourceAndPerKey() throws Exception {
        LiveConfig base = LiveConfig.parseDefaults(defaults(PROFILES), OBF);
        LiveConfig next = LiveConfig.merge(base, "{\"tvProfiles\":{\"a\":{\"rows\":[\".new\"]},\"b\":{\"skip\":[\".b\"]}}}", UrlDecoder.IDENTITY);
        JSONObject a = new JSONObject(next.tvProfileFor("a"));
        assertEquals(".new", a.getJSONArray("rows").getString(0));
        assertEquals("untouched keys stay", ".s", a.getJSONArray("skip").getString(0));
        assertEquals(1280, a.getInt("viewportWidth"));
        assertEquals(".b", new JSONObject(next.tvProfileFor("b")).getJSONArray("skip").getString(0));
        // a remote without tvProfiles keeps the defaults
        LiveConfig same = LiveConfig.merge(base, "{\"tagline\":\"x\"}", UrlDecoder.IDENTITY);
        assertEquals(base.tvProfileFor("a"), same.tvProfileFor("a"));
    }

    private static void rejects(String profileJson) {
        try {
            LiveConfig.merge(LiveConfig.parseDefaults(defaults(""), OBF), "{\"tvProfiles\":" + profileJson + "}", UrlDecoder.IDENTITY);
            fail("expected rejection: " + profileJson);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test public void invalidProfilesAreRejected() {
        rejects("{\"Bad Id\":{}}");
        rejects("{\"a\":{\"rows\":\".r\"}}");
        rejects("{\"a\":{\"rows\":[1]}}");
        rejects("{\"a\":{\"rows\":[\"\"]}}");
        rejects("{\"a\":{\"viewportWidth\":100}}");
        rejects("{\"a\":{\"viewportWidth\":1280.5}}");
        rejects("{\"a\":{\"player\":{\"depth\":9}}}");
        rejects("{\"a\":{\"servers\":{\"toggle\":5}}}");
        StringBuilder big = new StringBuilder("[");
        for (int i = 0; i < 41; i++) big.append(i == 0 ? "" : ",").append("\".s").append(i).append('"');
        rejects("{\"a\":{\"skip\":" + big + "]}}");
    }

    @Test public void playerMenuKeysAndRailInsetSurviveSanitising() throws Exception {
        String extra = ",\"tvProfiles\":{\"a\":{\"railInset\":[{\"selector\":\".wrap\",\"prop\":\"padding-left\"}],\"player\":{"
                + "\"toggles\":[\".t\"],\"menuSelected\":[\".sel\"],\"seekBar\":[\".bar\"],\"menu\":[\".m\"],\"hide\":[\".h\"],\"menuItems\":[\"li\"],\"menuColumns\":[\".col\"],\"never\":[\"input\"],\"subtitles\":[\".cue\"],"
                + "\"menuConfirm\":\"#apply\",\"menuClose\":{\"mode\":\"click\",\"selector\":\"#cancel\"},\"bogus\":[\"x\"]}}}";
        JSONObject a = new JSONObject(LiveConfig.parseDefaults(defaults(extra), OBF).tvProfileFor("a"));
        JSONObject pl = a.getJSONObject("player");
        assertEquals(".t", pl.getJSONArray("toggles").getString(0));
        assertEquals("#apply", pl.getString("menuConfirm"));
        assertEquals(".sel", pl.getJSONArray("menuSelected").getString(0));
        assertEquals(".bar", pl.getJSONArray("seekBar").getString(0));
        assertEquals(".m", pl.getJSONArray("menu").getString(0));
        assertEquals(".h", pl.getJSONArray("hide").getString(0));
        assertEquals("click", pl.getJSONObject("menuClose").getString("mode"));
        assertEquals("#cancel", pl.getJSONObject("menuClose").getString("selector"));
        assertFalse("unknown player keys are dropped", pl.has("bogus"));
        assertEquals("padding-left", a.getJSONArray("railInset").getJSONObject(0).getString("prop"));
    }

    @Test public void cardActivateSurvivesAndIsValidated() throws Exception {
        String extra = ",\"tvProfiles\":{\"a\":{\"cardActivate\":[{\"card\":\".card\",\"target\":\".play\",\"bogus\":1}]}}";
        JSONObject e = new JSONObject(LiveConfig.parseDefaults(defaults(extra), OBF).tvProfileFor("a")).getJSONArray("cardActivate").getJSONObject(0);
        assertEquals(".card", e.getString("card"));
        assertEquals(".play", e.getString("target"));
        assertFalse("unknown pair keys are dropped", e.has("bogus"));
        rejects("{\"a\":{\"cardActivate\":[{\"card\":\".c\"}]}}");
        rejects("{\"a\":{\"cardActivate\":[{\"card\":\".c\",\"target\":5}]}}");
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 9; i++) many.append(i == 0 ? "" : ",").append("{\"card\":\".c\",\"target\":\".t\"}");
        rejects("{\"a\":{\"cardActivate\":" + many + "]}}");
    }

    @Test public void shellBlockSurvivesAndIsValidated() throws Exception {
        String extra = ",\"tvProfiles\":{\"a\":{\"shell\":{\"routes\":[\"/\",\"/movies\"],\"root\":[\"#root\"],\"html\":[\"#root\"],"
                + "\"volatile\":[\"x+\"],\"raceMs\":500,\"maxAgeHours\":6,\"keepVersions\":3,\"bogus\":1}}}}";
        JSONObject sh = new JSONObject(LiveConfig.parseDefaults(defaults(extra), OBF).tvProfileFor("a")).getJSONObject("shell");
        assertEquals("/movies", sh.getJSONArray("routes").getString(1));
        assertEquals(6, sh.getInt("maxAgeHours"));
        assertFalse(sh.has("bogus"));
        ShellSpec spec = ShellSpec.parse("{\"shell\":{\"routes\":[\"/\"],\"html\":[\"#root\"],\"maxAgeHours\":6,\"volatile\":[\"(\",\"ok\"]}}");
        assertEquals(6 * 60 * 60 * 1000L, spec.maxAgeMs);
        assertEquals("a broken regex is skipped, the good one kept", 1, spec.volatilePatterns.size());
        assertEquals(ShellSpec.DEFAULT_RACE_MS, spec.raceMs);
        assertEquals(null, ShellSpec.parse("{}"));
        assertEquals(null, ShellSpec.parse("{\"shell\":{\"routes\":[]}}"));
    }

    @Test public void invalidShellBlocksAreRejected() {
        rejects("{\"a\":{\"shell\":{\"routes\":[\"/x?page=2\"]}}}");
        rejects("{\"a\":{\"shell\":{\"routes\":[\"movies\"]}}}");
        rejects("{\"a\":{\"shell\":{\"routes\":[\"/\"],\"raceMs\":10}}}");
        rejects("{\"a\":{\"shell\":{\"routes\":[\"/\"],\"keepVersions\":9}}}");
        rejects("{\"a\":{\"shell\":{\"routes\":[\"/\"],\"maxAgeHours\":0}}}");
    }

    @Test public void invalidMenuCloseAndRailInsetAreRejected() {
        rejects("{\"a\":{\"player\":{\"menuClose\":{\"mode\":\"explode\"}}}}");
        rejects("{\"a\":{\"player\":{\"menuClose\":{\"mode\":\"click\",\"selector\":7}}}}");
        rejects("{\"a\":{\"railInset\":[{\"selector\":\".w\",\"prop\":\"top\"}]}}");
        rejects("{\"a\":{\"railInset\":[{\"prop\":\"left\"}]}}");
    }

    @Test public void profileFollowsTheTopLevelUrlThenTheActiveSource() {
        LiveConfig c = LiveConfig.parseDefaults(defaults(PROFILES), OBF);
        Source b = c.find("b");
        assertTrue(TvBridge.profileFor(c, b, "https://www.a.example.com/movie/1").contains("\".r\""));
        assertEquals("{}", TvBridge.profileFor(c, b, "https://b.example.org/"));
        assertTrue("unknown url falls back to the active source", TvBridge.profileFor(c, c.find("a"), "https://other.example.net/").contains("\".r\""));
        assertEquals("{}", TvBridge.profileFor(c, null, ""));
    }
}
