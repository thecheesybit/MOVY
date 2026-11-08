# Live config (OTA) via Firebase Remote Config

Goal: change what users see (the source list, splash and intro texts, an
announcement, the "content version" label) from the Firebase console, **with
no APK update**. Running apps pick changes up within seconds.

Firebase project: **`YOUR_FIREBASE_PROJECT`** (CLI account your-firebase-account).

**Already set up by the orchestrator:**
- Android apps are registered: `com.ayushkumar.movy` (1:YOUR_PROJECT_NUMBER:android:YOUR_APP_HASH)
  and `com.ayushkumar.movy.tv` (…:YOUR_TV_APP_HASH).
- `config/assets/movy/google-services.json` (git-ignored) holds both clients.
- The Remote Config template is published with parameter `movy_config` (JSON).
  Its source is `config/remote/movy_config.json`, wrapped into
  `config/remote/template.json`; `firebase.json` points at the template. Both
  files are git-ignored.
- To redeploy: `npx firebase-tools deploy --only remoteconfig --project YOUR_FIREBASE_PROJECT`.

`config/apps/movy.json` → `remote` describes the wiring. The build copies
`google-services.json` into `apps/mobile/android/app/` and applies the
google-services Gradle plugin **only when `remote` is configured**, so apps
without Firebase still build.

## What is remote vs. baked into the APK

| Remote (Remote Config `movy_config` JSON) | Baked (APK, needs a rebuild) |
|---|---|
| `sources[]` (id, name, url, allowedHosts, accent, enabled) and their order | appId, appName, launcher icon |
| `recommendedSource` | adblock engine + filter lists (later: remote lists) |
| splash: tagline, credit, subtitle, accent colour, minimum display ms | native code: Island, players, navigation policy |
| chooser (intro) screen: title, subtitle, button label, "Recommended" label | real versionName / versionCode |
| `contentVersion` label shown next to the build ("v1.0.0 (build 42) · cfg 7") | |
| `announcement` {id, text, level, url?} → one-time banner in the Island | |
| `minBuild` + `updateMessage` → soft "please update" sheet | |
| `extraAllowedHosts`, `authHosts` (global add-ons) | |

Rule: **every remote field is optional**. The APK ships the full default (the
build writes the merged `movy.json` values as `assets/movy-defaults.json`), and
remote values override per field. A broken or invalid remote JSON is ignored
entirely (log one line, keep the last good config).

## Remote Config template

One parameter, `movy_config` (type JSON), so it's easy to edit as a whole in the console.
Optional conditions: `platform == android` vs tv uses a separate parameter
`movy_config_tv` falling back to `movy_config`.

```jsonc
{
  "schema": 1,
  "contentVersion": 1,
  "sources": [ { "id": "movy", "name": "Movy", "url": "https://source-1.example/", "allowedHosts": ["source-1.example","*.source-1.example"], "accent": "#E50914", "enabled": true }, ... ],
  "recommendedSource": "movy",
  "splash": { "tagline": "Ad-free movie streaming", "credit": "Ayush Kumar", "minMs": 600 },
  "chooser": { "title": "Choose your source", "subtitle": "You can switch anytime from the side bar", "cta": "Continue", "recommendedLabel": "Recommended" },
  "announcement": null,
  "minBuild": 0,
  "updateMessage": ""
}
```

Repo source of truth: `config/remote/movy_config.json` (git-ignored, because it
holds URLs) plus `npm run remote:push -- --app movy` / `remote:pull`, which use the
Firebase CLI. Console editing works too; `remote:pull` syncs it back.

## Client behaviour (Android, phone + tv)

- Firebase Android SDK `firebase-config` (BoM), initialised from
  `google-services.json`. No Analytics (keeps it light and private):
  `firebase-analytics` is not added.
- Startup:
  1. Apply defaults merged with the **cached last-good remote** synchronously
     (`activate()` of the previous fetch). Startup never waits on the network.
  2. `fetchAndActivate()` in the background (`minimumFetchInterval`: 1 h release,
     0 debug).
  3. `addOnConfigUpdateListener` (real-time). On update: `activate()`, then
     validate (`RemoteAppConfig.parse` → immutable record, unit tested), then post
     to the UI thread.
- Live effects without a restart:
  - Island and chooser re-render the source list immediately.
  - If the active source was removed or disabled, keep the current page,
    but don't offer it again. On next launch, fall back to the default →
    recommended → first enabled.
  - If the user's saved default disappeared, show the chooser on next launch.
  - Navigation policy picks up the new allowedHosts at once.
  - Splash and intro texts apply on the next show.
- `minBuild` > installed versionCode → a dismissible sheet with `updateMessage`,
  shown once per contentVersion.
- No URL leaks: the UI still shows only names, and logs redact hosts in release.
  Note: Remote Config values are readable by anyone who extracts the API key
  from the app. They're hidden from users, not secret.

## Later (not now)

- Remote filter-list refresh: download a new engine.dat hosted on Firebase
  Storage and hot-swap it.
- Remotely updatable splash/intro *design*: make them a small local HTML bundle
  that's live-updated (Capacitor live updates hosted on Firebase Hosting).
- Desktop: the same JSON via the Firebase JS SDK.

## `dohHosts` (per-site DNS workaround)

Some ISP resolvers poison a name a site depends on (for example its metadata API), so the page hangs on skeleton
placeholders in the WebView, which always uses the system resolver, while Chrome works because it uses its own secure
DNS. List such hosts (exact or `*.suffix`) in `dohHosts` (app config `config/apps/<app>.json`, overridable via the
remote config). Requests to them (GET/HEAD/OPTIONS, https only) are answered in `AdblockWebViewClient` by `DohRescue`,
which resolves the name over DNS-over-HTTPS (IPv4 only) and connects to that address with normal TLS host-name
verification. Everything else is untouched. Regression check: open a title and press Play on every source after any
change to request interception, DNS or the User-Agent.

## Per-site ad rules (`adblock.customRules`)

These rules are baked into `engine.dat` at build time.
- Block an injected ad chain by **specific hosts** (`||ad-host.example^`) plus a scoped cosmetic backup
  (`site.example##iframe[srcdoc][sandbox*="allow-modals"]` for fake-captcha overlays).
- Never use broad rules like `*$script,third-party,domain=site.example`. The WebView can't tell fetch from script
  requests, so the type is guessed, and such a rule breaks the site's API calls.
