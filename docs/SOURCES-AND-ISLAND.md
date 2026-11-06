# Multi-source app, Island drawer, splash & onboarding

Requested by the user after W2. It applies to phone, tv and desktop. The goal is to
be effortless, seamless, modern, minimal and lightweight.

## 1. Config: sources instead of a single url

```jsonc
{
  "appName": "Movy",
  "tagline": "Ad-free movie streaming",
  "credit": "Ayush Kumar",
  "sources": [                       // order = display order
    { "id": "movy",     "name": "Movy",     "url": "https://source-1.example/",       "allowedHosts": ["source-1.example", "*.source-1.example"],         "accent": "#E50914" },
    { "id": "cinejoy",  "name": "Cinejoy",  "url": "https://source-2.example/",        "allowedHosts": ["source-2.example", "*.source-2.example"],   "accent": "#22C55E" },
    { "id": "moonflix", "name": "Moonflix", "url": "https://source-3.example/",  "allowedHosts": ["source-3.example", "*.source-3.example"], "accent": "#FACC15" },
    { "id": "7movies",  "name": "7Movies",  "url": "https://source-4.example/",        "allowedHosts": ["source-4.example", "*.source-4.example"],   "accent": "#3B82F6" }
  ],
  "recommendedSource": "movy",
  ...everything else unchanged (adblock, targets, backgroundColor, ...)
}
```

- `url` and top-level `allowedHosts` stay accepted for single-site apps. The
  loader normalizes them to one source. With a single source there's no chooser
  and the Island only shows settings.
- Validation: unique `id`s matching `[a-z0-9-]+`, and `recommendedSource` must
  exist. `accent` is optional; if missing, a palette colour is picked by index.
- **No URL leaks in the UI.** Only `name` (and a monogram) is ever shown. No
  URL or host appears in any toast, error page, label, title or settings
  screen. The error page says "Movy is unreachable", not the host. In the
  build output, source URLs are stored obfuscated (XOR + base64 with a
  per-build key, decoded at runtime), never as plain strings in assets or
  resources. Release logcat never prints source URLs or hosts; debug logs may.
  (Note to user: an APK can always be reverse-engineered. This keeps URLs out of
  sight, not cryptographically secret.)
- The real config lives in git-ignored `config/apps/movy.json`. The committed
  `example.json` uses placeholder `example.com`-style sources.
- **Build number:** `versionCode` is auto-incremented on every build. It's stored
  per app in `config/apps/.build-numbers.json`, which is git-ignored, and
  `--build-number N` overrides it. Shown as `v1.0.0 (build 42)`.

## 2. Launch flow

1. **Splash** (native, under ~1 s, never blocks on the network):
   - black background, centered app icon (`config/assets/<app>/icon.png`, the film reel)
   - appName, the tagline "Ad-free movie streaming"
   - bottom: "by Ayush Kumar" · "v1.0.0 (build 42)" in small, muted text
   - a subtle scale/fade-in
   - the red spinner appears only if loading takes over 400 ms
2. **First launch (no default saved)** → the **Chooser** screen:
   - title "Choose your source"
   - 4 large rounded cards with a monogram or glyph in an accent ring, the name, and
     a live status dot (see health checks)
   - the recommended source (Movy) is preselected and has a "Recommended" pill
   - a toggle/checkbox "Always open this source", **checked by default**
   - a primary "Continue" button
   - the chooser is native UI, not the remote site, and is fully D-pad navigable on TV
3. **Later launches**: when a default is saved, the splash goes straight to that
   source. When "Always open" was unchecked, the chooser shows every launch with
   the last choice preselected.
4. Preferences (`SharedPreferences` / Electron `userData/prefs.json`):
   `defaultSourceId`, `askEveryLaunch`, `lastSourceId`, `lastUrlPerSource`
   (in memory only; it isn't persisted, to keep it simple), `islandY`
   (vertical position).

## 3. The Island (right-edge floating drawer)

**Visibility rule (user request):** `island.visibility` is `"home"` by default,
and can also be `"always"`. It can be changed remotely.
- In `"home"` mode, the Island (collapsed tab or expanded) is shown only while
  the top-level page is a source's **home page**: the same host (allowedHosts
  match) and a path equal to the start URL's path, or "/" or empty, ignoring the
  query, the hash and a trailing slash.
- When the user navigates deeper (a movie page, the player, search…), the Island
  animates out (slides into the edge, ~180 ms). It animates back in when the
  user returns to the home page (Back, or the site's own logo/home link).
- The check runs on every main-frame URL change, including SPA route changes via
  `WebViewClient.doUpdateVisitedHistory`, because these sites use
  history.pushState.
- An open Settings sheet stays until closed, even if the page changes.
- Fallbacks so the user is never stuck:
  - On TV the MENU key always opens the Island.
  - The error panel ("X isn't responding") always shows it.

Inspired by `docs/design/island-ref-1.webp` and `island-ref-2.png`: a black
shape fused to the **right screen edge** with concave curved fillets where it
meets the edge, like a "dynamic island" turned sideways.

- **Collapsed:** a slim black tab (about 6dp wide × 56dp tall visible, with fillets)
  hugging the right edge at `islandY`. It's semi-transparent (about 70%) when idle
  and fully opaque on touch. A tap or a swipe left expands it. It can be dragged
  vertically, and the position is remembered.
- **Expanded:** it grows out of the edge with a spring animation (~280 ms,
  slight overshoot) into a vertical pill about 76dp wide, with the same fused
  fillets top and bottom. Contents from top to bottom:
  - one item per source: a 44dp circle with the monogram/glyph, plus a progress
    **ring** in the source accent. The ring is full for the active source and
    a thin track for the others. A small status dot shows health. The short
    name sits underneath in 11sp.
  - a divider, then a ⚙ **Settings** button as a separate circle
    "dripping" below the pill, as in reference 1.
- Tapping a source switches to it. The WebView loads that source's last URL in
  this session, or its start URL. The red spinner overlay and progress bar
  show, allowedHosts switch to the new source, and the Island collapses after
  a short delay.
- Collapse happens on a tap outside, a swipe right, Back, or 4 s idle.
- It's hidden while web content is fullscreen and while the chooser or splash
  is shown.
- **Settings (v1):** a small native sheet sliding from the right with:
  - Default source (radio list)
  - "Ask on every launch" toggle
  - Ad blocking on/off
  - "Check sources now"
  - About: icon, name, tagline, credit, version (build)
  - Keep it extensible: sections are a simple list model.
- **TV:** the Island opens with the MENU key, or with D-pad RIGHT when focus is
  already at the right edge of the page. Items are focusable with a clear focus
  ring; Back collapses it.
- **Desktop:** a frameless transparent overlay on the right edge of the window,
  with the same visuals. It's toggled by clicking the tab or pressing Ctrl+K.

## 4. Health checks & failover

- On app start (after the splash, in the background) and when the Island opens,
  if the last check is over 60 s old, send a `HEAD` request to each source's
  URL (falling back to GET with a range or no body), with a 5 s timeout and no
  cookies. Status is **up** (2xx/3xx under 1.5 s), **slow** (under 5 s) or
  **down** (error, timeout or 5xx). Dot colours are green, yellow and red. 4xx
  counts as up, because sites often 403 HEAD requests.
- If the active source's main-frame load fails (network error, 5xx, or a timeout
  of 15 s without first contentful load) → show the native error panel "Movy
  isn't responding" with buttons [Retry] [Switch to Cinejoy]. The suggested
  source is the first one that is up, in display order. The Island opens
  automatically, pulsing that suggestion. It never auto-switches silently.

## 5. Icon

`config/assets/movy/icon.png` (512² film reel). Use it for:
- the launcher icon: legacy mipmaps + an adaptive icon, with the foreground scaled
  to the safe zone on a matching `#86CBD8`-ish background derived from the image
- the splash
- the About screen
- the TV banner (icon + appName)
- the Electron icon (.ico generated)

## 6. Persistent storage (logins must survive)

Logins, settings and anything a site stores must survive app restarts, a force
stop, a device reboot, switching sources, and app updates. The app must
never start fresh unless the user asks.

- **Android:**
  - Cookies: `CookieManager` accepts cookies and third-party cookies. Call
    `flush()` in `onPause`/`onStop`, after every `onPageFinished`, and when
    switching sources.
  - Never call `removeAllCookies`, `removeSessionCookies`, `clearCache`,
    `clearFormData`, `WebStorage.deleteAllData` or `clearHistory` (except
    history on a source switch, if needed for Back semantics).
  - DOM storage, IndexedDB, Cache Storage and service workers stay enabled
    with the default persistent WebView data dir.
  - Verify whether session cookies (cookies with no expiry) survive a full
    process kill on this WebView version. If they don't, persist them: snapshot
    `getCookie(url)` per source host on pause and restore missing ones at
    startup before the first load.
  - The Android Autofill framework stays enabled on the WebView, so Google
    Password Manager can save and fill logins.
- **Electron:**
  - Use a persistent partition `persist:<appId>`, never an in-memory session.
  - Call `cookies.flushStore()` on navigation finish, blur and before-quit.
  - Keep localStorage, IndexedDB and service workers.
  - Session cookies: verify, and persist the same way as Android if needed.
- **Updates keep data:** the release signing key must be stable forever.
  - Store the generated keystore outside `.cache`, at `%USERPROFILE%\.movy\keystore\<app>.jks`
    with a password file next to it. Override this via `config/signing.json`.
  - The build prints a one-time warning telling the user to back it up. A
    lost key means uninstalling the app, and with it, losing all logins.
  - `adb install -r` must keep data. `install:android` must never uninstall
    first.
- **Settings → Storage:** shows the app's storage used, with explicit
  confirm-guarded actions "Clear cache only" and "Sign out of everything
  (clear all site data)". Nothing is cleared automatically.
- **Verify on the device:** log in or set a cookie plus localStorage on a test
  page (or the source's own settings), then `am force-stop`, relaunch, and
  confirm it's still set. Repeat after `adb install -r` of a rebuilt APK.

## 7. Constraints

- Native views on Android (no extra WebView for the UI; no Compose, to stay
  lightweight). The custom-drawn Island uses `Path` and `ValueAnimator` /
  `SpringAnimation`. Target: APK growth of 300 KB or less, and no measurable
  startup cost.
- Everything respects insets: the Island sits inside the system-bar
  insets, not under the nav bar.
- All strings go in resources, so they can be localised later.
