package app.movy.shell;

import android.app.Activity;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.ArrayList;

/**
 * Site search on TV, native side (the page side is tvsearchmic.js). Two jobs:
 * 1. The keyboard proxy: the page asks for the keyboard when OK is pressed on its search box; a hidden native text field takes the
 *    focus and the TV keyboard, every change is mirrored into the site box, Done presses Enter there, BACK closes it and the page
 *    gives the D-pad focus back to the box.
 * 2. The in-page mic: the recogniser activity is started, its phrase goes to the page, which types it into the box.
 * Runs on the main thread; the bridge methods post. No URLs, no source names.
 */
final class TvSearchController implements SearchKeyboard.Listener {
    interface Host {
        /** Runs JS in the page (fire and forget). */
        void eval(String js);

        /** The WebView gets the view focus back (the keyboard proxy had it). */
        void focusPage();
    }

    private static final String TAG = "MovySearch";
    private static final long DEBUG_LISTEN_MS = 900;

    private final Activity activity;
    private final Host host;
    private final boolean debug;
    private final SearchKeyboard keyboard;
    private final SearchText.Mirror mirror = new SearchText.Mirror();
    private final SearchVoice voice;
    private final ListeningPill pill;
    private final ActivityResultLauncher<Intent> launcher;
    private boolean listening;

    /** Builds the controller on the activity's layout and connects it to the page bridge. */
    static TvSearchController install(ComponentActivity activity, SourceRepository repo, TvBridge bridge,
                                      java.util.function.Supplier<android.webkit.WebView> webView, boolean debug) {
        TvSearchController c = new TvSearchController(activity, repo, new Host() {
            @Override public void eval(String js) {
                android.webkit.WebView wv = webView.get();
                if (wv != null) wv.evaluateJavascript(js, null);
            }

            @Override public void focusPage() {
                android.webkit.WebView wv = webView.get();
                if (wv != null) wv.requestFocus();
            }
        }, debug);
        bridge.setSearch(new TvBridge.Search() {
            @Override public void open(String current) { activity.runOnUiThread(() -> c.open(current)); }

            @Override public boolean micAvailable() { return c.micAvailable(); }

            @Override public void micStart() { activity.runOnUiThread(c::micStart); }
        });
        return c;
    }

    private TvSearchController(ComponentActivity activity, SourceRepository repo, Host host, boolean debug) {
        this.activity = activity;
        this.host = host;
        this.debug = debug;
        ViewGroup content = activity.findViewById(android.R.id.content);
        keyboard = new SearchKeyboard(activity, content, this);
        voice = new SearchVoice(activity);
        pill = new ListeningPill(activity, content, activity.getString(R.string.movy_listening));
        launcher = activity.getActivityResultRegistry().register("movy-voice", new ActivityResultContracts.StartActivityForResult(),
                r -> onVoiceResult(r.getResultCode(), r.getData()));
        repo.addListener(new SourceRepository.Listener() {
            @Override public void onConfigChanged(LiveConfig config) {}

            @Override public void onActiveSourceChanged(Source active) { cancel(); }
        });
    }

    boolean isOpen() {
        return keyboard.isOpen();
    }

    /** Leaves everything quietly (source switch, activity pause): no focus hand-back, the page is going away or is not in front. */
    void cancel() {
        if (listening) { listening = false; pill.hide(); }
        keyboard.close();
    }

    // ---------- keys ----------

    /** While the keyboard is up the D-pad belongs to it: arrows never reach the drawer or the page, BACK closes it. */
    boolean handleKey(KeyEvent e) {
        if (!keyboard.isOpen()) return false;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (e.getAction() == KeyEvent.ACTION_UP) keyboard.close();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            default:
                return false;
        }
    }

    // ---------- from the page (UI thread) ----------

    /** OK on the site's search box: the keyboard opens with the box's current text. */
    void open(String current) {
        if (keyboard.isOpen()) return;
        String text = SearchText.clean(current);
        mirror.reset(text);
        keyboard.open(text);
        if (debug) Log.i(TAG, "keyboard open, " + text.length() + " chars");
    }

    boolean micAvailable() {
        return voice.available();
    }

    /** OK on the in-page mic: the recogniser (or, in debug builds, a canned phrase) answers. */
    void micStart() {
        if (listening) return;
        keyboard.close();
        listening = true;
        pill.show();
        String canned = voice.debugResult();
        if (canned != null) {
            activity.getWindow().getDecorView().postDelayed(() -> deliver(canned), DEBUG_LISTEN_MS);
            if (debug) Log.i(TAG, "mic: debug result stands in for the recogniser");
            return;
        }
        try {
            launcher.launch(SearchVoice.intent(activity.getString(R.string.movy_say_title)));
        } catch (RuntimeException e) {
            listening = false;
            pill.hide();
            Log.w(TAG, "no recogniser: " + e.getClass().getSimpleName());
        }
    }

    private void onVoiceResult(int resultCode, Intent data) {
        ArrayList<String> list = resultCode == Activity.RESULT_OK && data != null ? data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
        deliver(SearchVoice.pick(list));
    }

    /** A recognised phrase (also the debug stand-in): the page types it into the search box. */
    void deliver(String phrase) {
        listening = false;
        pill.hide();
        if (debug) Log.i(TAG, "mic phrase " + phrase.length() + " chars");
        if (!phrase.isEmpty()) host.eval(SearchText.call("micResult", phrase));
    }

    /** Debug builds: the next mic press answers with this phrase (the emulator has no recogniser). */
    void setDebugVoice(String phrase) {
        voice.setDebugResult(phrase);
    }

    // ---------- keyboard events ----------

    @Override public void onText(String text) {
        String next = mirror.next(text);
        if (next == null) return;
        host.eval(SearchText.call("searchSet", next));
    }

    @Override public void onSubmit() {
        host.eval(SearchText.callPlain("searchSubmit"));
        keyboard.close();
    }

    @Override public void onClosed() {
        mirror.forget();
        host.focusPage();
        host.eval(SearchText.callPlain("searchClosed"));
        if (debug) Log.i(TAG, "keyboard closed");
    }
}
