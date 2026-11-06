# Movy — multi-target web shell with Brave adblock

One config → three apps that load a website full-screen, with ads blocked by
Brave's [`adblock-rust`](https://github.com/brave/adblock-rust) engine.

| Target  | Tech                        | Output                       |
|---------|-----------------------------|------------------------------|
| phone   | Capacitor 7 Android (flavor `phone`) | `dist/<app>/<app>-phone.apk` |
| tv      | Capacitor 7 Android (flavor `tv`, Leanback) | `dist/<app>/<app>-tv.apk` |
| desktop | Electron                    | `dist/<app>/desktop/…` (NSIS installer + unpacked) |

Reference device: OnePlus Pad 3 (OPD2415), Android 16 / API 36, arm64-v8a,
Chrome WebView 156. Connected over wireless ADB at `192.168.29.236:36091`.

## Repository layout

```
config/
  apps/example.json        # template; user adds their own apps/<name>.json (git-ignored except example)
  assets/<name>/           # optional icon.png (1024²), tv-banner.png (320×180)
  schema.json              # JSON Schema for editor autocomplete
packages/config/           # Node: load + validate (zod) + merge target overrides
native/adblock/            # Rust crate: adblock-rust wrapper
  src/lib.rs               # core API (pure Rust, unit tested)
  src/jni.rs               # JNI exports for Android  (feature "jni")
  src/bin/build-engine.rs  # host CLI: filter lists -> serialized engine .dat
apps/mobile/               # Capacitor project (android/ with phone + tv flavors)
apps/desktop/              # Electron app
scripts/                   # build orchestration (node)
dist/                      # build outputs (git-ignored)
```

## App config (`config/apps/<name>.json`)

```jsonc
{
  "name": "example",                 // folder/file key, [a-z0-9-]
  "appName": "Movy",                 // launcher label / window title
  "appId": "com.example.movy",       // Android applicationId, Electron appId
  "url": "https://source-1.example",          // start URL
  "version": "1.0.0",
  "versionCode": 1,
  "allowedHosts": ["source-1.example", "*.source-1.example"], // top-level navigation allowed in-app
  "externalLinks": "block",          // "block" | "in-app" (never launches other apps; "open-external" = "block")
  "userAgent": null,                 // null = platform default with WebView markers removed
  "backgroundColor": "#000000",
  "orientation": "any",              // "any" | "portrait" | "landscape"
  "adblock": {
    "enabled": true,
    "lists": "default",              // "default" or array of filter list URLs
    "extraLists": [],                // appended to lists
    "customRules": [],               // inline ABP/uBO rules
    "cosmetic": true,                // element hiding + scriptlets
    "blockPopups": true              // window.open / target=_blank to non-allowed hosts
  },
  "targets": {                       // shallow per-target overrides of any key above
    "phone": {}, "tv": { "orientation": "landscape" }, "desktop": {}
  }
}
```

Default lists: EasyList, EasyPrivacy, uBlock filters, uBlock badware, uBlock
privacy, uBlock unbreak, uBlock quick-fixes, Peter Lowe's. Scriptlet/redirect
resources: `https://raw.githubusercontent.com/brave/adblock-resources/master/dist/resources.json`.

## Build flow

`npm run build -- --app <name> --target phone|tv|desktop|all`

1. `packages/config` loads + validates, merges `targets.<t>` overrides.
2. `scripts/filters.mjs` downloads lists (cache in `.cache/filters`, 24h TTL,
   falls back to cache when offline) and runs `build-engine` →
   `engine.dat` (serialized `adblock::Engine`) + `resources.json`.
3. Target build:
   - Android: generates `capacitor.config.json` + `android/app/movy.gradle.json`
     (appId, label, versionCode…), copies `engine.dat` into assets, icons,
     runs `gradlew assemble<Phone|Tv>Release` (debug-signed unless
     `config/signing.json` exists), copies APK into `dist/`.
   - Desktop: writes `app-config.json` + `engine.dat` into the Electron
     resources, runs electron-builder.

## Adblock engine contract

Rust core (`native/adblock/src/lib.rs`), engine with `Engine::deserialize`
for fast startup; built with the **same crate version** on host and device.

```rust
pub struct Shell { engine: adblock::Engine }          // wrap in Mutex/RwLock if !Sync
impl Shell {
  fn from_serialized(bytes: &[u8]) -> Result<Self>;
  fn from_rules(rules: &str, resources_json: Option<&str>) -> Self;
  fn check(&self, url: &str, source_url: &str, request_type: &str) -> CheckResult; // matched, important, redirect, exception
  fn url_cosmetic_resources(&self, url: &str) -> String;           // JSON
  fn hidden_class_id_selectors(&self, classes: &[String], ids: &[String], exceptions: &[String]) -> String; // JSON array
}
```

Android Kotlin facade `app.movy.adblock.AdblockEngine` (package fixed, independent of appId):

```kotlin
object AdblockEngine {
  fun load(context: Context)                     // reads assets/adblock/engine.dat (+resources) off main thread
  val isReady: Boolean
  fun check(url: String, sourceUrl: String, type: String): BlockResult
  fun cosmeticResources(url: String): String     // JSON
  fun hiddenSelectors(classes: List<String>, ids: List<String>, exceptions: List<String>): String
}
```

JNI symbols: `Java_app_movy_adblock_NativeBridge_*`, handles passed as `long`.
Native lib name: `libmovy_adblock.so`, ABIs arm64-v8a, armeabi-v7a, x86_64.

## Android integration

- `MainActivity` (Capacitor `BridgeActivity`) installs `AdblockWebViewClient
  extends BridgeWebViewClient`:
  - `shouldInterceptRequest`: infer request type (main frame → `document`,
    Accept header / extension → `script|stylesheet|image|media|font|xmlhttprequest|other`),
    `sourceUrl` = current top-level URL; blocked → empty 200/204 response
    (redirect resource → serve its body).
  - `shouldOverrideUrlLoading`: non-allowed host → per `externalLinks`; known ad
    host (check as `document`) → swallow.
- Cosmetic filtering: `WebViewCompat.addDocumentStartJavaScript` injects
  `assets/adblock/cosmetic.js`, which calls a `@JavascriptInterface`
  (`MovyAdblock`) for per-URL resources, injects hide CSS + scriptlets at
  document start, and runs a throttled `MutationObserver` for generic
  class/id hiding.
- Popups: `WebChromeClient.onCreateWindow` → allow only allowed hosts.
- "Like Chrome": JS, DOM storage, third-party cookies, autoplay without
  gesture, file downloads
  (`DownloadManager`), file chooser, back button → `goBack()`, WebView UA with
  `; wv` and `Version/x.y` removed, hardware acceleration, edge-to-edge.
- Loading UX: `getDefaultVideoPoster()` returns a transparent bitmap (no grey
  play-icon flash); injected `loading.js` shows a red spinner over buffering
  `<video>` elements (also inside fullscreen); native 3dp progress bar during
  page loads; WebView background = `backgroundColor`; branded splash until the
  first page loads. Desktop reuses `loading.js` via its preload.
- System bars (phone): status bar + nav bar visible by default, content laid
  out below them via window insets (edge-to-edge is forced on API 35+), bar
  icons light/dark from `backgroundColor`. When the site enters fullscreen
  (Fullscreen API → `onShowCustomView`) the app goes immersive (bars hidden,
  cutout used, screen kept on, landscape allowed); leaving fullscreen
  (`onHideCustomView` or Back) restores everything. Back while fullscreen
  only exits fullscreen.
- `tv` flavor: always immersive, `LEANBACK_LAUNCHER` intent, `android.software.leanback`
  (required=false) and touchscreen not required, banner, landscape, D-pad
  focus visible, back = history back, long-press back = exit.

## Desktop integration

- Electron `session.webRequest.onBeforeRequest` → engine check with Electron
  `resourceType` mapped to adblock types; cancel or redirect (data: URL).
- Preload script performs the same cosmetic filtering via IPC.
- Engine access: Brave's official Node bindings (`adblock-rs`) if they install
  cleanly with prebuilt/locally built binaries on Windows, otherwise a napi-rs
  binding over `native/adblock`. The engine must deserialize the same `engine.dat`
  format or be rebuilt from the cached lists at build time.
- Single window, no menu bar (Alt shows it), remembers size/position,
  external links per `externalLinks`, F11 fullscreen, context menu.

## Work packages (each done by one worker agent, sequentially)

1. **W1 – Rust adblock core**: crate, unit tests (block/allow/cosmetic/
   serialize round-trip), JNI layer, `build-engine` CLI, cargo-ndk build
   script producing `.so` for 3 ABIs.
2. **W2 – Config + build scripts + Capacitor Android (phone + tv)**: config
   package with tests, filters script, Capacitor project, Kotlin integration,
   APK build, install on the tablet.
3. **W3 – Electron desktop**: app, adblock integration, electron-builder.
4. **Orchestrator**: review, device verification, docs.

## Definition of done

- `npm run build -- --app example --target all` produces phone APK, tv APK and
  desktop build without manual steps.
- On the tablet the phone APK opens the configured site; ad requests from the
  default lists are blocked (verified via logcat counter and a test page such
  as `https://d3ward.github.io/toolz/adblock`).
- Rust and config unit tests pass.
