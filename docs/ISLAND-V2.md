# Island v2 (Apple Dynamic Island style), source fixes & reorder

User feedback: the current Island and Settings look "ugly". They should look and move
like Apple's Dynamic Island and iOS Settings. 7Movies and Movy work;
Cinejoy and Moonflix don't. Fix those two, or remove them.

## Part A — Sources

### A1. New order
1. 7Movies (`recommendedSource` = 7movies)
2. Movy
3. Cinejoy (only if fixed)
4. Moonflix (only if fixed)

Apply the order and the recommended source in:
- `config/remote/movy_config.json`
- `config/remote/template.json`, then deploy Remote Config (`YOUR_FIREBASE_PROJECT`)
- `config/apps/movy.json`, for the baked defaults

Users whose saved default is a removed source get the chooser on the next launch.
That behaviour already exists; verify it.

### A2. Diagnose Cinejoy and Moonflix on the device

Facts the orchestrator has already found:
- Both sites return 200 from the PC.
- Our adblock engine allows every main request of both sites, including:
  - their bundles
  - `check.ddos-guard.net`
  - `/.well-known/ddos-guard/*`
  - Rocket Loader
  - the Moonflix API on `*.up.railway.app`
  - `image.tmdb.org`
  - `player.source-4.example`
- **Cinejoy:** a SvelteKit SPA behind **DDoS-Guard** (`Server: ddos-guard`, `__ddg*` cookies). All of its API calls are relative, to its own host.
- **Moonflix:** a Vite SPA behind **Cloudflare** with **Rocket Loader**. Its search API is on `<api-host>.up.railway.app`, and its code mentions mirrors `source-4-mirror.example` and `source-4-mirror.example`. An earlier device screenshot showed its home page stuck on skeleton placeholders.

How to diagnose:
- Install a **debuggable** build. It's signed with the same key, so `install -r` keeps the user's logins.
- Attach Chrome DevTools Protocol: `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`, then `/json` and a small node CDP script.
- Capture console errors, failed or blocked requests, and main-frame navigations while each site loads and while you open a movie and press Play.
- Baseline: open the same URL in the tablet's Chrome. If it's broken there too, it's the site, not us.

Hypotheses to test, toggling one thing at a time:
1. **UA vs Client Hints mismatch** (bot checks): we strip `; wv` and `Version/x` from the UA, but WebView still sends `Sec-CH-UA` with the "Android WebView" brand.
   - Fix: use `WebSettingsCompat.setUserAgentMetadata` (androidx.webkit, feature `USER_AGENT_METADATA`) so the hints match a normal Chrome brand list.
   - Alternative fix: stop rewriting the UA at all.
   - Re-test all 4 sources after the change.
2. **Our injected JS** (navguard, cosmetic scriptlets, loading.js) breaking the SPA, e.g. a `set-constant` or `abort-on-property` scriptlet from the uBO lists that targets these domains.
   - Test with ad blocking off (Settings toggle) and with navguard disabled (debug flag).
   - Fix narrowly: add `customRules` exceptions such as `@@||site^$ghide` or `#@#+js(...)` per domain, or skip a broken scriptlet.
3. **NavRules** killing a needed main-frame move, e.g. to a mirror domain.
   - Fix: add the mirror to that source's `allowedHosts`.
4. **Cookies or storage**: third-party cookies, or DDoS-Guard cookies not being set.
5. **The site itself** is broken or geo-blocked. Confirm with the Chrome baseline.

Decision:
- If fixed: keep the source, add regression notes, and put any per-site exceptions in config, not in code.
- If not fixable from our side (the site is down or broken in Chrome too, or a challenge can't be passed): remove the source from the remote config and `movy.json`. Report the evidence.

## Part B — Island v2: the Dynamic Island look

Replace the visuals and motion of the Island. Keep its behaviour: home-only
visibility, switching, health dots, failover pulse, TV MENU/D-pad, persisted Y,
and accessibility.

### Shape and material
- **Collapsed:** a floating black **capsule**, fully rounded, about 7×64dp,
  inset 6dp from the right screen edge. It's not fused to the edge, so the
  concave fillets are dropped.
  - It sits on a soft shadow (≈ y 6dp, blur 18dp, 45% black) with a hairline
    1px stroke of `#FFFFFF14` so it's visible on dark pages.
  - Idle opacity is 100%; the capsule is small, so it doesn't need to fade.
- **Expanded:** the capsule **morphs** into a tall rounded rectangle:
  - width ≈ 84dp (TV ×1.5), corners ≈ 30dp, continuous-curvature "squircle"
    corners via cubic approximation
  - pure black `#000`, same hairline and shadow
  - the morph animates width, height, corner radius and position together
- **Settings bubble:** a detached black circle (≈ 44dp) that springs out
  below the main island a beat later (60–80 ms stagger), like the Dynamic
  Island's split second-activity bubble. It holds an SF-style gear glyph
  (vector drawable).

### Content
- Source items are **app-icon-like squircle tiles**, 44dp, with:
  - a vertical gradient from the accent to an accent darkened 25%
  - a white monogram in medium weight, 18sp
  - an inner hairline highlight at the top
- Under each tile: the name, 11sp, `#EBEBF5` at 60% opacity, single line, ellipsized.
- **Active source:** its tile gets a 2dp white ring with a 3dp gap, plus a
  subtle scale of 1.0 against 0.94 for the others. The active name is 100% white.
- **Health:** a 7dp dot at the tile's bottom-right with a 2dp black cutout ring:
  green `#30D158`, yellow `#FFD60A`, red `#FF453A`, grey `#8E8E93`.
- **Font:** use the system sans (`sans-serif` / `sans-serif-medium`),
  letter-spacing −0.01. Don't bundle fonts.

### Motion (this is what makes it feel Apple)
- Use **spring physics**: `androidx.dynamicanimation:dynamicanimation` SpringAnimation,
  which is a small dependency. Don't use duration-based overshoot.
  - Expand: stiffness ≈ 380, damping ratio ≈ 0.78.
  - Collapse: stiffness ≈ 500, damping ratio ≈ 0.9.
- Content fades and scales in from 0.85 *after* the shape has passed about 60%
  of its expansion, and fades out first on collapse.
- Tiles stagger 25 ms apart.
- Tap feedback: pressed tiles scale to 0.92 with a spring, plus a light haptic
  (`HapticFeedbackConstants.CLOCK_TICK` / `CONFIRM` on API 30+).
- Switching source: the white ring travels from the old tile to the new one
  with a spring, rather than jumping.
- Show and hide (home-only rule): the capsule shrinks to a 2dp sliver while
  fading out. It must not slide off-screen.
- Everything runs at 120 Hz on the tablet with no allocations in `onDraw`.
  Report `gfxinfo` again.

## Part C — iOS-style Settings (and matching chooser and error panel)

- **Presentation:** a floating panel.
  - On a tablet or in landscape: a 420dp-wide card on the right side with a
    20dp margin and 28dp corners, springing in from the right.
  - On a phone in portrait: a bottom sheet with 28dp top corners and a grabber.
- **Look:**
  - The panel background is `#1C1C1E`, and a dim scrim (35% black) sits behind it.
  - The large title "Settings" uses 30sp bold.
  - Use **inset grouped** sections: rounded 12dp cards in `#2C2C2E`, with
    0.5dp separators in `#38383A` inset 56dp. Section headers are 13sp
    uppercase `#8E8E93`; footers are 13sp `#8E8E93`.
- **Rows:**
  - 29dp rounded-square **icon badges** (7dp corners) in iOS system colours
    with white glyphs (vector drawables).
  - A 17sp label, a value in `#8E8E93` on the right, and a chevron `›` where a
    row opens a sub-page.
- **Switches:** iOS style. The track is 51×31dp: green `#34C759` when on,
  `#39393D` when off. The white knob has a shadow and moves with a spring.
- **Structure:**
  - **Source:**
    - "Default source" opens a sub-page with a checkmark list (push
      navigation inside the panel, slide transition, "‹ Settings" back).
    - "Ask on every launch" is a switch.
  - **Protection:** "Block ads & trackers" switch, with "Ads blocked" and
    "Redirects stopped" counts.
  - **Sources:** rows with health dots and "Online/Slow/Offline" labels; the
    footer has "Check now".
  - **Storage:** "Used" value, "Clear Cache" (blue text), and "Sign Out of
    All Sites" (red text). Destructive actions confirm with an iOS-style
    action sheet or alert.
  - **About:** the app icon centred, the name, the tagline, "by Ayush Kumar",
    and the version (build) plus cfg.
- **Chooser and error panel:**
  - Reuse the same tokens.
  - Chooser cards become inset-grouped rows with squircle tiles, a checkmark
    for the selection, an iOS switch for "Always open this source", and a
    full-width 14dp-corner primary button in `#0A84FF` or the accent.
- **Strings:** all go in resources.
- **Tokens:** define colours, radii and springs in one place:
  `UiTokens.java` plus `values/movy_colors.xml`.

## Verification (tablet)

- Install with `install -r` only, never uninstall: the user is logged in.
- Before/after screenshots of:
  - the collapsed capsule
  - the Island mid-morph (a burst of screencaps, or `screenrecord`)
  - the Island expanded
  - Settings main, and the Default source sub-page
  - the destructive-action alert
  - the chooser (debug-only extra to force it)
  - the error panel
- All 4 sources: load, open a title, press Play. Make a pass/fail table.
- `gfxinfo` jank; APK delta.
- Unit tests: `testPhoneDebugUnitTest --rerun-tasks` and `npm test`.
- Leave the user's state as found: the active source and default source as
  before. Re-install the **release** build at the end.
