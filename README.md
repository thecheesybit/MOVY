# Movy

One JSON config per app produces phone and TV Android apps (and later a desktop app) that load a website
full-screen with Brave's `adblock-rust` engine blocking ads. See `docs/PLAN.md` for the design and
`config/README.md` for every config field. **Current status and every open bug: `docs/KNOWN-ISSUES.md`**; Android TV
work log and resume point: `docs/tv-adapter/ROUND5-PROGRESS.md`.

## Prerequisites (Windows)

- Node 20+ and npm; run `npm install` once in this folder.
- Rust (gnu toolchain) with `cargo-ndk` and the Android rust targets (used by `native/adblock`); `%USERPROFILE%\.cargo\bin` is added to PATH by the scripts.
- Android SDK (`ANDROID_HOME`, default `%LOCALAPPDATA%\Android\Sdk`) with platform 36, build-tools and NDK 27.
- JDK 17 or 21 for Gradle (`C:\Program Files\Java\jdk-17` is found automatically, or set `MOVY_JAVA_HOME`).
  Android Studio's JBR 25 is too new for Gradle 8.14 and cannot be used.

## Build

```
npm run build -- --app example --target phone     # dist/example/example-phone.apk
npm run build -- --app example --target tv        # dist/example/example-tv.apk (appId + ".tv")
npm run build -- --app example --target all       # phone + tv (+ desktop once scripts/build-desktop.mjs exists)
npm test                                          # config package tests (coverage report)
npm run schema                                    # regenerate config/schema.json
```

Pipeline: validate config, download filter lists (cached 24h in `.cache/filters`, falls back to cache offline), build
`engine.dat` with the host `build-engine` (cached per list set in `.cache/engine/<hash>`), build `libmovy_adblock.so`
(only when the Rust sources changed), generate Capacitor/Gradle inputs and icons, `cap sync`, Gradle release build.
Release APKs are signed with `config/signing.json` if present, else a generated local keystore (see `config/README.md`).

## Add an app

Copy `config/apps/example.json` to `config/apps/myapp.json`, edit it, optionally add
`config/assets/myapp/icon.png` and `tv-banner.png`, then build with `--app myapp`.
Your configs under `config/apps/` are git-ignored except `example.json`.

## Install over wireless ADB

```
adb pair <ip:pairing-port>          # once
adb connect <ip:port>               # `adb mdns services` shows the _adb-tls-connect address
npm run install:android -- --app example --target phone [--serial <ip:port>]
```

This runs `adb install -r` and launches the app. Logs: `adb logcat -s MovyAdblock MovyShell`.

## Layout

`packages/config` config loader/validator, `scripts/` build orchestration, `apps/mobile` Capacitor project
(`android/app/src/main/java/app/movy/{adblock,shell}`, `adblock/cosmetic.js`), `native/adblock` Rust core.
A desktop target plugs in by adding `scripts/build-desktop.mjs` exporting `buildDesktop(app)`.
