# W8 — Phone & TV UI pass, and the fullscreen black-screen fix

User reports:
1. **Phone:** while playing a video in fullscreen, the screen goes black, or
   flickers, while the audio keeps playing.
2. "Optimise the UI for phone and Android TV."

Devices (wireless ADB; adb at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`):

| Device | Serial | Notes |
|---|---|---|
| OnePlus Pad 3 tablet | `192.168.29.236:36091` | API 36, 2400×3392 @420dpi, WebView 157 |
| OnePlus 12 phone (CPH2573) | `192.168.29.199:39545` | API 36, 1440×3168 @640dpi, WebView 156. **Movy is already installed, and the user's data is on it.** |
| Android TV | none | Verify TV with unit tests and a TV-flavor build only. You can also install the tv APK (`com.ayushkumar.movy.tv`, a separate package, so it's safe) on the tablet and drive it with D-pad keyevents, as a rough check. |

The hard rules from W6/W7 still apply: `install -r` only; never uninstall, `pm clear` or clear data, on either device. Don't change device settings
(no `wm size`/`wm density`). Keep source hosts out of committed files. Temp files go in the scratchpad. Don't commit.
Before uninstalling-free installs on the phone, check that its installed signature matches our keystore
(`apksigner verify --print-certs` against the device's base.apk, pulled via `pm path`). If the signature doesn't match, `install -r` fails.
In that case, **stop and report**; do not uninstall.

## Part 1 — Fullscreen black screen / flicker (phone first)

The current implementation is `ShellWebChromeClient.onShowCustomView`:
- it adds the custom view in a black `FrameLayout` to the **decor view**, on top of everything;
- it hides the system bars, sets cutout SHORT_EDGES and `SCREEN_ORIENTATION_SENSOR`;
- the WebView stays VISIBLE underneath.

The manifest handles orientation config changes, so the activity isn't recreated.

Reproduce:
- on the phone (and the tablet, for comparison): play on each source, enter fullscreen, rotate, wait 60 s, tap the controls, then exit;
- capture `screenrecord`, `logcat` (filter the chromium, MovyShell, SurfaceFlinger and BLASTBufferQueue tags), and `dumpsys SurfaceFlinger --list` while black.

Likely causes, to test one at a time:
1. **Surface z-order / overlay conflict.**
   - Chromium's fullscreen video uses a SurfaceView/SurfaceControl (overlay) behind its view. Our views drawn above it make the compositor fall back or punch through. The Island, `LoadingUi` progress bar, spinner overlay and any animators keep invalidating.
   - Fix: while fullscreen, hide or detach the Island, progress bar, spinner and any overlay (`GONE`, stop their animators). Keep only the custom container on top.
   - Also try putting the custom container into `android.R.id.content`, matching Chrome's approach, instead of the decor view.
2. **WebView underneath still rendering, competing for the video surface.** Chrome's own approach is to keep the WebView attached but not
   draw over it. Try `webView.setVisibility(INVISIBLE)` on enter and VISIBLE on exit (not GONE: GONE can pause the media), and compare.
3. **Orientation change mid-fullscreen** (SENSOR) resizing the surface while it's black. Check that the black frames correlate with rotation. Fix by
   locking to `SENSOR_LANDSCAPE` when the video is landscape (the custom view's aspect ratio, or the default) on phones, then restoring.
4. **Display refresh-rate switching** (LTPO 1–120 Hz) during video. Look for frame-rate votes in the SurfaceFlinger logs.
   - If found, set `preferredDisplayModeId`/`setFrameRate` only if it's clearly the cause.
5. **Keep-screen-on / dimming**: confirm the black isn't the screen timing out. FLAG_KEEP_SCREEN_ON should already prevent that.
6. **Injected loading.js** spinner overlay inside fullscreen: confirm it doesn't put an opaque layer over the video when it misdetects
   buffering (`waiting` events without `playing`).

Fix the root cause(s) with minimal code. Add unit tests for any pure logic (e.g. an orientation decision helper).
Done: 3 minutes of fullscreen playback on the phone on 7Movies and Movy, including two rotations and control taps, with **no black frames**
in the screenrecord. Also check entering and exiting 5 times. The tablet must be unaffected.

## Part 2 — Phone UI (compact widths, < 600dp)

Check every native surface on the phone (portrait and landscape) and fix what's off:
- **Island:**
  - capsule and panel size on a 411dp-wide screen;
  - the panel must fit the height with 4 sources plus the gear in landscape (scroll or a compact
    tile size if not);
  - touch targets ≥ 48dp;
  - respects the status/nav bar and cutout insets;
  - drag range clamped inside the safe area;
  - not overlapping the site's own header controls at the default Y.
- **Settings:** a bottom sheet in portrait (grabber, drag-to-dismiss, max height ≈ 90%, scrollable content,
  nav-bar inset padding); the side card in landscape. Text must not clip at the 1.3× font scale (simulate with a layout check only, no setting change).
- **Chooser:** fits without clipping in portrait and landscape (scrollable, the button pinned at the bottom).
- **Splash and error panel:** centred and scaled; no clipped text.
- **Spinner and progress bar:** sizes look right at 640dpi.
- Put sizing in `UiTokens` (compact vs regular, from `smallestScreenWidthDp`), not scattered constants.

## Part 3 — Android TV UI (10-foot)

The TV flavor (`tv`) has no device to test on. Make it right by construction and test what can be tested:
- **Overscan-safe margins:** 48dp horizontal / 27dp vertical for Island, Settings, Chooser, Splash and error panel.
- **Island on TV:**
  - always reachable via MENU, plus D-pad RIGHT at the right edge (that already exists; verify the code paths);
  - larger tiles (×1.5);
  - a clear **focus state**: scale 1.08 plus a white ring;
  - initial focus on the active source;
  - D-pad UP/DOWN moves between tiles and the gear;
  - BACK collapses.
- **Settings on TV:**
  - a right-side panel, never a bottom sheet;
  - every row focusable with a visible focus highlight (the iOS-style `#3A3A3C` row plus a ring);
  - switches toggle with DPAD_CENTER;
  - the sub-page is navigable and BACK returns.
- **Chooser on TV:** focus starts on the recommended or default source; the "Continue" button is reachable; the text size suits 10-foot viewing.
- No touch-only affordances on TV (drag handle, grabber).
- Unit-test the pure focus-order and layout decisions where they're extractable.
- Rough check: install the tv APK on the tablet (separate package `.tv`; `install -r` is fine even when it's new), drive it with `adb shell input keyevent`
  (MENU=82, DPAD_*, CENTER=23, BACK=4) and screenshot.
  - Leave it installed only if the user wants it. **Ask the orchestrator in your report**; don't uninstall it yourself.

## Verification

- Screenshots in the scratchpad (`w8_*.png`):
  - phone portrait and landscape: home with the Island collapsed and expanded, Settings, Chooser (debug extra), error panel;
  - phone fullscreen screenrecord frames;
  - TV-on-tablet: the Island focused, Settings focused rows.
- Play passes on 7Movies and Movy, on both phone and tablet.
- `npm test` and `gradlew testPhoneDebugUnitTest testTvDebugUnitTest` (JDK 17, ANDROID_HOME set).
- Finish by installing the **release** phone APK on both the phone and the tablet (`node scripts/install-android.mjs --app movy --target phone --serial <s>`).
  Leave the active and default sources as you found them.
