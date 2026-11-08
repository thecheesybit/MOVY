# Movy desktop (Electron, Windows)

Parity port of the Android phone/tablet app. Code: `apps/desktop` (Electron 45), `native/adblock-node` (napi binding of
`adblock-rust`), generator in `scripts/desktop/`. No real source host or URL appears in this repo; the real config is the
git-ignored `config/apps/<app>.json`.

## Gap list (desktop vs Android phone) and status

| Area | Before | Now |
|---|---|---|
| Multi-source | single `url` | `sources[]` (order, accent, name only), `recommendedSource`, `defaultSource`, `askEveryLaunch`; chooser with "Always open this source"; no URL in any UI string |
| URL hygiene | plain in `app-config.json` | source URLs, allowed hosts and DoH hosts are XOR+base64 with a per-build key (same scheme as Android); last-good remote config is stored obfuscated too |
| Splash | grey spinner | icon, app name, tagline, "by <credit>", `vX (build N) · cfg C`, spinner only after 400 ms, never waits on the network |
| Island | none | right-edge floating switcher, Island-v2 look (red capsule that wiggles every 10 s, morphs to a black squircle with gradient tiles, white travelling ring, health dots, detached gear bubble), spring physics, home-page-only visibility with per-source landing-page learning, Ctrl+K, **hover to expand** (120 ms intent, 600 ms leave grace), vertical drag (position remembered) |
| Settings | none | iOS-style card (bottom sheet when the window is narrow): Default source sub-page, Ask on every launch, Block ads & trackers (+ counts), source health + Check Now, Storage (used, Clear Cache, Sign Out of All Sites, both confirm-guarded), About |
| Navigation | allow-list, "open in browser" | `core/nav-rules.js` mirrors Android `NavRules`: allowed hosts + learned redirect chains from a start page, sign-in hosts only with a gesture, popups killed except a gesture to an allowed host (loaded in the main view), subframes judged only by the engine, **never opens a browser or another app** |
| Protection | engine + cosmetic | + `scamguard.js` (top frame), `navguard.js` (main world, click-jack overlays), config `customRules` (baked into `engine.dat`), DoH rescue |
| Persistence | partition + flush | unchanged contract: `persist:<appId>`, flush on load/blur/quit, session-cookie snapshot, nothing cleared automatically |
| Live config | none | Firebase Remote Config `movy_config` over the REST fetch (like Android `RestRemoteFallback`), polled every 60 s; sources, texts, island visibility, hosts, contentVersion applied live; cached last-good config applied synchronously at start; invalid JSON ignored |
| Fullscreen / loading | F11 | F11 + site fullscreen, red buffering spinner (`loading.js`), 3 px progress bar, black backgrounds everywhere (no white flash) |
| Window | size/pos | + Alt shows the menu, context menu (no "open in browser"), downloads via save dialog |
| Scrollbars | visible | never drawn (also in our own UI); pages still scroll |
| Failover | offline page | "X isn't responding" panel with Retry and "Switch to Y" (first healthy source), Island opens and pulses the suggestion; never auto-switches |

## Architecture

```
src/core/      pure logic, no electron import, unit tested
  nav-rules, home-page, island-visibility, live-config, repository, health-rules,
  obfuscator, prefs, remote-rest, doh, ui-state, url-parts
src/main/      electron wiring: main (bootstrap), shell (window + launch flow + actions), views (layers),
               ui-bridge (validated IPC), navigation, adblock, cosmetic-ipc, health, remote, storage, ...
src/preload/   site.js (injected into every frame of the sources), ui.js (tiny fixed API for our own views)
src/ui/        island.*, panel.* (splash, chooser, settings, error), spring.js, dom.js, base.css
```

Window layers (bottom to top): site `WebContentsView`, panel (only while a splash/chooser/Settings/error is shown),
island, progress bar. The island is its own small transparent view whose bounds follow its state (28x88 collapsed, 150 wide
expanded), so every click and scroll elsewhere still reaches the page; no click-through trickery is needed and the view
receives native mouse-enter/leave. The views render a state snapshot (`ui-state.js`, never a URL) and send validated actions.

Springs: `ui/spring.js` samples a damped spring (same stiffness/damping as `UiTokens.java`) into a CSS `linear()` easing,
played through the Web Animations API.

## Behaviour notes

- **DoH rescue.** `dohHosts` non-empty turns on Chromium's secure DNS for the whole app:
  `app.configureHostResolver({ secureDnsMode: 'automatic', secureDnsServers: [cloudflare, dns.google] })`. `automatic` falls
  back to the system resolver if neither server is reachable, so a network that blocks DoH cannot break the app. Unlike
  Android this is not per host (Chromium has no per-host switch); it applies to all requests.
- **Remote config.** The REST fetch uses the Android client of `google-services.json` whose package equals `appId`; creds go
  into `app-config.json` at build time (the file is git-ignored with the rest of `gen/`). There is no realtime listener on
  desktop, so changes arrive within ~60 s instead of seconds.
- **Not implemented on desktop:** announcement banner, `minBuild` update sheet, TV profiles (all remote fields are parsed
  or ignored safely).
- **Developer flags:** `--remote-debugging-port=N` (drive and screenshot via CDP), `--inactive` (show without stealing
  focus), `--hold-splash=ms`, `--screenshot <png>`, `--storage-check set|get`.

## Build

```
node scripts/build.mjs --app movy --target desktop
```
Produces `dist/<app>/desktop/<App>-Setup-<version>.exe` (NSIS, per-user, uninstall keeps user data) plus `win-unpacked/`,
and copies the installer to `dist/<app>/release/<App>-<version>-desktop-setup.exe`. **No code signing** (no certificate
configured), so Windows SmartScreen shows "unknown publisher". Dev run without packaging:
`node scripts/desktop/gen-only.mjs --app movy && node_modules/.bin/electron apps/desktop`.
User data lives in `%APPDATA%\movy-<app>` and survives updates and reinstalls.

## Tests

`npm test --workspace @movy/desktop` (41 tests): nav rules, home-page rule, island visibility incl. landing-page learning,
live-config merge, obfuscation (and a no-plain-URL check on the generated config), remote REST parsing/poller, DoH options,
UI state, health rules, prefs, repository, spring easing, engine binding.
