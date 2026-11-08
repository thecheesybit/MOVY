# Anti-Gravity orchestration prompt: YouTube Freemium

Copy everything below the line into Anti-Gravity. The workspace must be opened at `D:\Movy`.

---

## ROLE

You are the orchestrator for a multi-step engineering job. Plan first, then split the work across sub-agents where tasks are independent (see the work packages). Verify each package before moving on. Do not stop until a signed, installable **phone APK** exists and has been checked. Report honestly: if something fails or is unverified, say so.

## GOAL

Create a **second, separate app** from the existing Movy project (`D:\Movy`): **YouTube Freemium** (spelled exactly `YouTube Freemium`, F-R-E-E-M-I-U-M). It is an ad-free native Android shell around two websites, with **Picture-in-Picture** and **background/screen-off audio**.

The original Movy project must stay untouched and keep working.

## HARD RULES

1. **Do not modify `D:\Movy`.** Create a new sibling project at `D:\YouTube-Freemium` by copying only what the Android phone/tablet build needs.
2. Copy the essentials only. Skip: `node_modules`, `dist`, `build/`, `.gradle/`, `*.log`, `.git`, the Electron desktop app (`apps/desktop`), the TV flavor and its sources, Movy's four streaming source configs, Movy docs and tests that don't apply. Run `git init` fresh in the new folder.
3. Android **phone + tablet only**. Drop the `tv` product flavor, `src/tv`, and TV D-pad navigation code (`tvnav`, `tvProfiles`) unless removing them breaks the build; if so, leave them inert.
4. New identity: **applicationId `com.ayushkumar.youtubefreemium`**, Java namespace may stay `app.movy.shell` (rename only if it is low-risk and the build stays green). Config key `name`: `youtube-freemium`. `appName`: `YouTube Freemium`. Keep the credit `by Ayush Kumar`. Version `1.0.0`, `versionCode` 1.
5. Keep what is already good in Movy: Brave adblock-rust (JNI) + cosmetic filtering + scriptlets, the red-spinner loading screen, native modern/minimal look, status bar visible except in web fullscreen, right-edge "Island" drawer for switching sources, error panel + failover, obfuscated start URLs in the APK, per-app release signing under `%USERPROFILE%\.movy\keystore\`.
6. Never log or display a full URL in the UI. Show source **names** only ("YouTube", "YouTube Music").
7. Use the existing build pipeline (`npm run build -- --app <name> --target phone`, `scripts/build-android.mjs`, `config/schema.json`, `packages/config`). Host quirks: Gradle must run on **JDK 17** (the JBR 25 bundled with Android Studio is too new); Rust uses the **gnu** toolchain (MSVC is incomplete). Read `config/README.md`, `docs/PLAN.md`, `docs/SOURCES-AND-ISLAND.md` before editing anything.

## WORK PACKAGES

### WP1: Scaffold the new project (sequential, do first)

- Copy the essentials from `D:\Movy` to `D:\YouTube-Freemium`: root `package.json`/workspaces, `packages/config`, `packages/inject` (keep the adblock/cosmetic/scamguard parts), `scripts/` (Android build only), `apps/mobile` (Capacitor + `android/` project with the `phone` flavor), `native/` (adblock-rust JNI sources and prebuilt `jniLibs` so no Rust rebuild is required if already compiled), `config/schema.json`, `config/README.md`.
- Install dependencies and confirm `npm test` for the kept packages passes before changing anything. This is the baseline.

### WP2: Config — replace all sources with exactly two

Create `config/apps/youtube-freemium.json` (git-ignored by default like Movy's; also add a tracked `config/apps/example.json` with placeholder values). Sources, in this order:

| id | name | url | allowedHosts | accent |
|---|---|---|---|---|
| `youtube` | `YouTube` | `https://www.youtube.com/` | `youtube.com`, `*.youtube.com`, `youtu.be`, `*.googlevideo.com`, `*.ytimg.com`, `*.ggpht.com`, `*.gstatic.com` | `#FF0000` |
| `youtube-music` | `YouTube Music` | `https://music.youtube.com/` | `music.youtube.com`, `*.youtube.com`, `*.googlevideo.com`, `*.ytimg.com`, `*.ggpht.com`, `*.gstatic.com`, `*.googleusercontent.com` | `#FF0033` |

- `recommendedSource`: `youtube`. `appName`: `YouTube Freemium`. `tagline`: `Ad-free YouTube and YouTube Music`. `credit`: `by Ayush Kumar`. `island.visibility`: `always` (so the user can hop between YouTube and YouTube Music from the player too). `backgroundColor`: `#0F0F0F`. `orientation`: `any`. `externalLinks`: `block`. `adblock.enabled`: true, lists `default`, cosmetic true, `blockPopups` true.
- `authHosts`: keep the built-ins (accounts.google.com etc.) and add `accounts.youtube.com`, `myaccount.google.com`, `consent.youtube.com`, `consent.google.com` so Google sign-in and the consent screen work on a user tap.
- Remove every Movy source (movy, cinejoy, moonflix, 7movies) and any `tvProfiles`, `remote`/Firebase block and their `google-services` plumbing. Remote config is not needed; the app must build without `google-services.json`.
- Make sure the user-agent setting yields a normal Chrome Android UA with **no WebView markers** (`; wv`, `Version/x.y`). Google blocks sign-in from embedded WebViews, so verify sign-in at least reaches the password step.
- Regenerate the schema (`npm run schema`) and make the config validator accept the file.

### WP3: Branding and icon

- The icon is already saved at `D:\Movy\config\assets\youtube-freemium\icon.png` (red rounded square with a white play button). Copy it to `D:\YouTube-Freemium\config\assets\youtube-freemium\icon.png`. If it is not 1024x1024, upscale it cleanly without distortion or add padding on a transparent background.
- Use it for the launcher icon: generate **adaptive icon** layers (foreground = the artwork scaled to the 66% safe zone, background = solid `#E60000`-matching red or the artwork's own red), plus legacy mipmaps (mdpi to xxxhdpi, round variants), and a monochrome layer for Android 13 themed icons. Use the same artwork on the splash/loading screen above the red spinner, with `YouTube Freemium` and `by Ayush Kumar` text.
- Launcher label, window title, splash text, notification text and the About/credits row must all read `YouTube Freemium`. Search the whole new project for the strings "Movy"/"movy" and replace every user-visible one (strings, resValue, splash, error panel, notification channels). Internal package names (`app.movy.*`) can stay if renaming is risky; user-visible text may not.

### WP4: Ad skipping (must work as in Movy, tuned for YouTube)

- Network + cosmetic blocking through the existing Brave adblock-rust engine with the default lists (EasyList, EasyPrivacy, uBO filters/quick-fixes etc.). Make sure uBlock Origin's YouTube scriptlets are active (`json-prune` / `set-constant` of ad fields like `playerResponse.adPlacements`, `adSlots`, `playerAds`, and the `abort-on-property-read` family), since they are what stop pre-roll and mid-roll ads on youtube.com and music.youtube.com.
- Add a small injected fallback script (document-start, main-frame, only on the allowed YouTube hosts) that: clicks `.ytp-skip-ad-button` / `.ytp-ad-skip-button-modern` the moment it appears; if an ad is detected (`.ad-showing` on `#movie_player`, or `.video-ads .ytp-ad-player-overlay`), mutes it and seeks the ad video to its end; hides ad containers (`#masthead-ad`, `ytd-ad-slot-renderer`, `ytd-promoted-sparkles-*`, `ytd-display-ad-renderer`, `.ytp-ad-overlay-container`, `ytm-promoted-*`); and dismisses the "ad blockers violate YouTube's terms" enforcement popup (`ytd-enforcement-message-view-model`, `tp-yt-paper-dialog` with that text) and resumes playback.
- Wrap all of this in try/catch, throttle with `MutationObserver` + a 300 ms interval only while an ad is showing, and keep it from touching normal playback. YouTube changes its markup often, so put all selectors in one `youtube-selectors.json`/module that is easy to update, and also make the selector list refreshable through the existing `LiveConfig` mechanism if one exists (optional).
- Music: on music.youtube.com block the video-ad slots the same way, and the "Are you still listening?" pause (click the confirm button automatically).

### WP5: Picture-in-Picture and background audio (the main new feature)

Implement in `MainActivity` and a new `PlaybackService`:

**Picture-in-Picture**
- Manifest: `android:supportsPictureInPicture="true"` on the activity, and make sure `configChanges` includes `screenSize|smallestScreenSize|screenLayout|orientation` so PiP does not recreate the activity.
- Enter PiP automatically when the user leaves the app (Home, recents, gesture nav) **while a video is playing**: use `setAutoEnterEnabled(true)` on Android 12+ (API 31) via `PictureInPictureParams.Builder`, and `onUserLeaveHint()` -> `enterPictureInPictureMode(params)` as the fallback for API 26-30. Set aspect ratio from the real video size (`16:9` default), updated through a JS-to-native bridge when the `<video>` metadata is known. Set `setSeamlessResizeEnabled(false)` is not needed; do set `setSourceRectHint` to the video bounds on API 26+.
- Do **not** enter PiP when nothing is playing, or when the current source is YouTube Music **audio-only** playback with no visible video (use background audio instead, below).
- In PiP, show only the video: via JS inject a style that makes the player fill the viewport and hides everything else (use the player's `fullscreen`-like class or `position:fixed` on `#movie_player`), and hide native chrome (Island drawer, status bar, loading UI). On leaving PiP (`onPictureInPictureModeChanged(false)`), restore the page layout exactly.
- Add PiP remote actions (Play/Pause, optional +10 s / -10 s) through `RemoteAction` + a `BroadcastReceiver`, wired to JS (`video.pause()/play()`).
- Add a user setting "Picture-in-Picture" (default **on**) in the existing settings model (`SettingsModel`/`Prefs`) so it can be turned off. Graceful fallback when `PackageManager.FEATURE_PICTURE_IN_PICTURE` is missing.

**Background / screen-off audio**
- When the app is backgrounded or the screen is turned off with the power button, playback must **continue**. Android's WebView pauses media with the page, and YouTube pauses on `visibilitychange`, so all of the following are needed:
  1. Do not call `WebView.onPause()` / `pauseTimers()` while media is playing; call `onResume()` / `resumeTimers()` when the app returns. (`MainActivity.onPause/onStop` must be media-aware.)
  2. Inject at document-start a script that overrides the Page Visibility API: `document.hidden` -> `false`, `document.visibilityState` -> `"visible"`, and swallow `visibilitychange`, `webkitvisibilitychange`, and `blur`/`pagehide` events in the capture phase; also set `document.hasFocus = () => true`. Only apply it on the YouTube hosts.
  3. Start a **foreground service** (`PlaybackService`, `foregroundServiceType="mediaPlayback"`, permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `WAKE_LOCK`) as soon as playback starts, and stop it when playback ends or the app is closed. Hold a `PARTIAL_WAKE_LOCK` and a `WifiLock` (only while playing, released on stop) so the CPU and Wi-Fi stay on with the screen off.
  4. Create a `MediaSession` and a `MediaStyle` notification (title, channel/artist, artwork, play/pause, previous/next if available). Feed metadata from JS using `navigator.mediaSession.metadata` or the page's player state through a small `@JavascriptInterface` bridge; route notification and lock-screen buttons and Bluetooth headset buttons back to the `<video>`/YouTube Music player (`play`, `pause`, `nexttrack`, `previoustrack`, `seekto`).
  5. Request and handle audio focus properly (`AudioFocusRequest`, `USAGE_MEDIA`, `CONTENT_TYPE_MOVIE`/`MUSIC`): pause on a call or another app, duck on notification sounds, resume afterwards.
  6. Request `POST_NOTIFICATIONS` (Android 13+) at the first playback with a friendly one-line rationale; if denied, still play audio and keep the foreground service (the notification is just hidden).
  7. If the screen turns off and the WebView still stalls on some devices, add a safe fallback: when `ACTION_SCREEN_OFF` fires during playback, call `video.play()` after 300 ms, and again on `ACTION_SCREEN_ON`/`ACTION_USER_PRESENT` (re-sync the visible state).
- YouTube Music must behave like a music player: audio continues when the app is minimized, the screen is off, or the user switches to YouTube; the notification shows the song, artist and art.
- Battery: release the service, wake lock and notification when playback stops for more than ~2 minutes or the user swipes the notification away.
- Add a "Background play" toggle (default **on**) next to the PiP toggle in settings.

### WP6: Polish and keep the established UX

- Modern, minimal, native feel: no web "shell" look. Red spinner loading screen (no skeleton text clutter), smooth fade into the page, status bar visible except while the web player is fullscreen (fullscreen = immersive, rotates to landscape for landscape video, returns correctly).
- Keep the Island drawer, but show just two entries: **YouTube** and **YouTube Music**, with the current one highlighted. Switching must keep the previous source's playback state predictable: switching away from a playing video continues audio in the background unless the user pauses it (document this behavior in the settings description).
- Handle: hardware Back (history back, exit fullscreen, minimize on root), deep links (`youtube.com/watch`, `youtu.be/*`, `music.youtube.com/*`) opened from other apps load inside the app and are never opened in a browser, offline state with the existing error panel, orientation changes without restarting playback, and the first-run Google consent page.
- Share-sheet "Share" from YouTube works; downloads and file chooser behave as in Movy.

### WP7: Build, sign, install, verify

- Build: `npm run build -- --app youtube-freemium --target phone` (release APK, signed with a **new** per-app key stored at `%USERPROFILE%\.movy\keystore\youtube-freemium.jks`; do not reuse Movy's key). Use JDK 17.
- Output: copy the final APK to `D:\YouTube-Freemium\dist\YouTube-Freemium-1.0.0.apk` and print its size, applicationId, versionName/versionCode, signing certificate SHA-256 (`apksigner verify --print-certs`), and `aapt dump badging` summary (label must be `YouTube Freemium`).
- Install on a device if one is reachable over adb (the OnePlus Pad 3 over wireless adb; its connect port changes, so discover it with `adb mdns services`; `adb install -r` only, **never uninstall**). If no device is connected, say so clearly and stop short of claiming runtime verification.
- Run the kept test suites (`npm test`, Android unit tests) and a debug/lint build; fix failures introduced by your changes.

## ACCEPTANCE CHECKLIST (report each as PASS / FAIL / NOT VERIFIED with evidence)

1. New project at `D:\YouTube-Freemium`; `D:\Movy` has **no** modifications (`git status` clean for files you didn't intend to change).
2. Launcher label is exactly `YouTube Freemium`; icon is the supplied red play-button art; adaptive and legacy icons render correctly.
3. Config lists only the two sources (YouTube, YouTube Music); no Movy sources, no TV profiles, no Firebase.
4. Island drawer shows exactly those two names and switches between them; no URLs visible anywhere in the UI or logs.
5. YouTube pre-roll/mid-roll/overlay ads do not play or are skipped instantly; no enforcement popup blocks playback; ads on YouTube Music are not served.
6. Starting a video and pressing Home enters PiP with the correct aspect ratio and working play/pause; returning restores the page.
7. Pressing the power button with a video or song playing keeps audio going; the lock screen/notification shows media controls that work; Bluetooth headset buttons work.
8. Playback stops or pauses cleanly on incoming calls and resumes afterward; the foreground service and wake lock are released when playback ends.
9. Google sign-in reaches the password step and the account stays signed in after restart.
10. Signed release APK exists, installs over a previous build with `adb install -r`, and reports `YouTube Freemium` as its label.

## OUTPUT

Finish with a short report: what was built, the APK path and size, the checklist results, any known limitations (YouTube markup changes can break ad skipping, so list the file that holds the selectors and the steps to update them), and any step you could not verify.
