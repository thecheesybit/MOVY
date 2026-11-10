package app.movy.shell;

import android.content.Context;
import android.content.Intent;
import android.speech.RecognizerIntent;

import java.util.List;

/**
 * The in-page mic's recogniser: the system speech activity ({@link RecognizerIntent}). The mic button only exists when something
 * resolves the intent. Debug builds can stand in a canned result (the emulator has no recogniser).
 */
final class SearchVoice {
    private final Context context;
    private String debugResult;

    SearchVoice(Context context) {
        this.context = context;
    }

    static Intent intent(String prompt) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
        return i;
    }

    boolean systemRecognizerResolves() {
        try {
            return intent("").resolveActivity(context.getPackageManager()) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True when the mic button should show: a recogniser exists, or a debug result is standing in for one. */
    boolean available() {
        return debugResult != null || systemRecognizerResolves();
    }

    void setDebugResult(String text) {
        debugResult = text == null || text.isEmpty() ? null : text;
    }

    String debugResult() {
        return debugResult;
    }

    /** The first usable phrase of the recogniser's candidates (best first), cleaned for the search box; "" when there is none. */
    static String pick(List<String> candidates) {
        if (candidates == null) return "";
        for (String c : candidates) {
            String t = SearchText.clean(c).trim().replaceAll(" {2,}", " ");
            if (!t.isEmpty()) return t;
        }
        return "";
    }
}
