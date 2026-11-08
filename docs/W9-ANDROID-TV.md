# W9 — Android TV: a polished experience on the remote

User request: "Optimise the Android TV UI. I should be able to use the remote's buttons (MENU, colour keys). The
side bar for changing sites doesn't appear. Go through every screen and test comprehensively. No bugs."

Device: **Sony BRAVIA 4K VH22 (Google TV)**, Android 12 / API 31, `leanback_only`, **armeabi-v7a only (32-bit)**.
It reports 1920x1080 at 320 dpi (≈960×540 dp); the physical panel is 4K. WebView 156.
ADB: `192.168.29.104:5555`.
Package: `com.ayushkumar.movy.tv` (tv flavor). It isn't installed yet, so a fresh install is fine; afterwards only use `install -r`, never
uninstall, never clear data.

The user's remote (Sony voice remote) has:
- power, TV, input, Google Assistant ring, ✎ (tools), **MENU**
- D-pad with centre select; BACK, HOME
- vol ±, mic, mute, CH ±
- ⚙ (settings), **play/pause**, ☰ (guide/text)
- app keys (Netflix, Prime, YouTube, etc.)

It has **no dedicated colour buttons**. On Sony, colour keys may come from an on-screen bar opened by a remote key. Find out what each key actually sends to our
app: log `dispatchKeyEvent` keycodes in a debug build, press each button (ask the orchestrator to ask the user if
needed), or use `adb shell getevent -l` plus logcat.

## 0. State after W8 (read first)

- `com.ayushkumar.movy.tv` (release) is **already installed** on the TV. From now on use `install -r` only.
- Findings on the TV:
  - the chooser has no overscan margins;
  - initial focus lands on the "Always open" row;
  - D-pad RIGHT-at-edge isn't implemented;
  - only MENU (82) opens the Island.
  - See the scratchpad `w8_tv*.png`.
- W8 added `UiTokens.TV_SAFE_H/V`, `TV_SCALE`, and `Ui.isTv`, `safeH`, `safeV`, `textScale`. Use them.
- W8's orientation logic (`OrientationPolicy`) skips TV. Its fullscreen guard in `packages/inject/loading.js` (it keeps
  videos composited so they don't go black when the controls hide) also applies on TV; check fullscreen on TV for black frames as well.
- `docs/PLAN.md` has a "Rotation and fullscreen video (W8)" section.

## 1. Reachability (the current bug: "the side bar doesn't appear")

Today only `KEYCODE_MENU` opens the Island in `MainActivity.dispatchKeyEvent`. The TV probably doesn't send that keycode for
its MENU button, or the WebView consumes the key first. Fix:
- Open/close the Island on **any** of:
  - MENU (82)
  - Sony's MENU/tools/guide keycodes, whatever is measured (e.g. 165 INFO, 172 GUIDE, 226 TV_CONTENTS_MENU, 256 MEDIA_TOP_MENU…)
  - the colour keys PROG_RED/GREEN/YELLOW/BLUE (183–186), if they arrive
  - a **long press of the D-pad centre** (the universal fallback)
- On TV, intercept in `dispatchKeyEvent` **before** the WebView gets the key.
- A first-run TV hint toast/overlay: "Press MENU to switch source" (shown once; string in resources).
- Colour keys, if delivered: RED/GREEN/YELLOW/BLUE jump straight to sources 1–4 (in order). Show them as small
  coloured dots on the tiles in the Island (TV only), so the mapping is discoverable.
- Play/pause, play, pause, FF and RW keys (85, 126, 127, 90, 89): forward them to the page's active `<video>` via injected JS
  (play/pause toggle, ±10 s seek) when the page doesn't handle them itself.

## 2. Navigating websites with a remote

The sites are built for mouse and touch. Make them usable with the D-pad:
- **Spatial navigation:** inject a TV-only script (`packages/inject/tvnav.js`, document-start like the others) that:
  - makes links, buttons, inputs, `[role=button]`, `[tabindex]` and common card elements focusable;
  - moves focus by direction (nearest element in the arrow's cone, scored by distance and alignment);
  - scrolls the focused element into view smoothly;
  - draws a clear focus ring (3px white outline plus a glow, rounded; never hidden by site CSS: use `!important` and
    `outline-offset`);
  - CENTER → click.
  - Inside iframes (players), let the WebView's native focus handle it; enter and leave iframes sensibly.
- **Cursor mode fallback** for elements spatial nav can't reach: toggle with a measured spare key, or a long press of
  BACK/CENTER as you decide. A native on-screen pointer, moved by the D-pad with acceleration, scrolls at the screen edges, and
  CENTER dispatches a tap via `MotionEvent` to the WebView. Show the mode in a small pill.
- **Text input:** focusing a search field opens the TV IME; BACK closes the IME first.
- **BACK:** IME → cursor mode → Island/Settings → fullscreen → web history back → at root: "Press BACK again to exit"
  toast (2 s).
- **Fullscreen video on TV:**
  - CENTER toggles play/pause;
  - LEFT/RIGHT seek ±10 s (show a small native seek bubble);
  - UP/DOWN show the controls;
  - BACK exits fullscreen.
  - Make sure no overlay draws over the video (the W8 learnings apply).

## 3. Every native screen, 10-foot design

Use `UiTokens` (TV variant) and keep the iOS dark look scaled for distance:
- **Overscan-safe margins:** 48dp horizontal, 27dp vertical. Body text ≥ 18sp, titles ≥ 28sp.
- **Focus state** on every focusable: scale 1.06–1.08 with a spring, a white 3dp ring, and elevated brightness. Exactly one element
  is focused, always; never lose focus (set `nextFocus*` explicitly and trap focus inside open panels).
- **Splash:** centred and scaled. **Chooser:**
  - a horizontal row of large source cards (not a phone list);
  - initial focus on the recommended/default source;
  - the "Always open this source" switch and "Continue" reachable with DOWN;
  - CENTER on a card = choose plus continue.
- **Island on TV:** not a thin edge capsule (there's no touch). When opened, it's a right-side vertical panel:
  - big tiles (×1.5) with name and health;
  - focus on the active source;
  - UP/DOWN to move, CENTER to switch, RIGHT/BACK/MENU to close, auto-close after switching.
  - When closed, a subtle hint chip appears for 3 s after each page load on the home page: "MENU · Sources".
- **Settings on TV:**
  - a right-side panel, every row focusable with the focus highlight;
  - switches toggle on CENTER;
  - the Default Source sub-page works with LEFT/BACK to return;
  - destructive alerts have focus on Cancel by default.
- **Error panel:** buttons are focusable, with the default focus on Retry.
- **Google TV integration:**
  - the launcher banner (320×180, from the icon plus appName) shows in the Google TV Apps row;
  - the `LEANBACK_LAUNCHER` intent, `touchscreen required=false`;
  - the app name is correct.
  - Verify the banner on the TV's home/apps screen via screencap.

## 4. Different screens

Layout math must work at 640×360 dp (720p at 2x), 960×540 dp (this TV) and 1280×720 dp (some 1080p TVs at 1.5x).
- Extract the pure sizing/positioning into testable helpers and unit test all three sizes.
- Don't change the TV's display settings to test.

## 5. Testing (comprehensive)

- Native-library check: the tv APK contains `lib/armeabi-v7a/libmovy_adblock.so`; logcat shows the engine loading on the TV.
- A scripted pass with `adb shell input keyevent` (DPAD 19/20/21/22, CENTER 23, BACK 4, MENU 82, long press via
  `input keyevent --longpress 23`) plus `screencap` after each step. Cover:
  1. Cold start → splash → chooser → choose 7Movies.
  2. On the home page, browse with the D-pad: focus moves visibly, a card opens, Play works, fullscreen, seek, BACK out.
  3. Search: focus the search field, type with `input text`, open a result.
  4. Open the Island (every working key), switch to each of the 4 sources, and check health dots.
  5. Settings: every row, the switch toggles, the Default Source sub-page, the Clear Cache alert → Cancel.
  6. The error panel (debug extra to force it), Retry focus.
  7. BACK chain to exit, then relaunch: the default source opens directly, and logins/cookies persist.
  8. The Google TV home/apps row shows the banner.
- Ad blocking works on TV (blocked counter goes up), and the Movy fake-captcha overlay doesn't appear.
- Performance on this 32-bit TV:
  - `gfxinfo` jank while opening the Island;
  - cold-start time (`am start -W`);
  - memory (`dumpsys meminfo`).
- Unit tests: `gradlew testTvDebugUnitTest testPhoneDebugUnitTest`, plus `npm test` (and add tests for tvnav.js logic where
  extractable, e.g. the direction-scoring function).
- Ensure the phone flavor is unaffected: tvnav.js and the TV key handling are only active in the tv flavor. Rebuild the phone APK
  and run its tests.

Hard rules (as before):
- Keep real source hosts out of committed files.
- Temp files go in the scratchpad.
- Don't commit.
- Don't change device settings.
- Never uninstall or clear data.

Finish with the **release** tv APK installed on the TV and the app left on 7Movies home.
