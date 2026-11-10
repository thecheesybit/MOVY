package app.movy.shell;

import android.content.Intent;
import android.util.Log;
import android.webkit.WebView;

/**
 * Debug builds only. {@code --es movy_eval "<js>"} runs JS in the page and logs the result (tag MovyDebug);
 * {@code --ez movy_force_chooser true} shows the chooser; {@code --es movy_source <id>} opens that source's start page;
 * {@code --es movy_voice "<phrase>"} stands in for the speech recogniser (add {@code --ez movy_voice_now true} to answer at once);
 * {@code --ez movy_force_fail true} simulates a failed load; {@code --ez movy_shell_offline true|false} forces the shell offline.
 */
final class DebugIntents {
    private static final String TAG = "MovyDebug";

    private DebugIntents() {}

    static void handle(Intent intent, WebView wv, Failover failover, LaunchController launch, SourceRepository repo, TvSearchController search) {
        if (intent == null || wv == null) return;
        // The emulator has no speech recogniser: this phrase answers the next in-page mic press; movy_voice_now answers at once.
        if (intent.hasExtra("movy_voice") && search != null) {
            search.setDebugVoice(intent.getStringExtra("movy_voice"));
            if (intent.getBooleanExtra("movy_voice_now", false)) wv.postDelayed(() -> search.deliver(intent.getStringExtra("movy_voice")), 800);
        }
        if (intent.getBooleanExtra("movy_force_fail", false) && failover != null) wv.postDelayed(failover::onFailure, 2500);
        if (intent.getBooleanExtra("movy_force_chooser", false) && launch != null) wv.postDelayed(launch::debugShowChooser, 1500);
        if (intent.hasExtra("movy_shell_offline")) ShellFetcher.Http.debugOffline = intent.getBooleanExtra("movy_shell_offline", false);
        String sourceId = intent.getStringExtra("movy_source");
        if (sourceId != null && repo != null && launch != null) {
            wv.postDelayed(() -> { launch.dismissStart(); repo.openStart(sourceId); }, 500);
        }
        String js = intent.getStringExtra("movy_eval");
        if (js == null) return;
        wv.postDelayed(() -> wv.evaluateJavascript(js, v -> Log.i(TAG, "eval result: " + v)), 800);
    }
}
