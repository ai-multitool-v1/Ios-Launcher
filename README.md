# SETBD Cloner

**Parallel apps. One device.** A production-oriented Android application (Kotlin, Jetpack Compose, Material You) that works as a virtual application container / multi-instance environment: import an app, and run several isolated instances of it side by side from one launcher.

> `org.setbd.cloner` · minSdk 26 (Android 8.0) · targetSdk 35 · Google Material Design, Material icons, bundled Roboto · APK releases built & signed by GitHub Actions, distributed via GitHub Releases with an in-app updater.

This is **not** a simple APK repackager and **not** a root tool. It is an in-process container engine: guest apps are loaded into the host's own process through a class-loading + component-mapping + context-redirect pipeline, with a hard honesty policy about what a normal Android app can and cannot do (see [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)).

---

## Features

| Area | What it does |
|---|---|
| **Engine** | Redundant launch pipeline: guest manifests are resolved from **three independent sources** (binary XML manifest, framework archive parser, import-time captured launcher component) so a single parse failure can never kill a launch. Guest activities run with a **guest-bound LayoutInflater** (custom views inflate through the guest class loader), the guest Application instance, per-clone storage redirection, and explicit (never silent) failure reporting. |
| **Import** | Import any installed app — including **App Bundle / split-APK packages** (base + every `split_config.*` APK is copied) — or standalone files: monolithic APKs and `.xapk` / `.apks` / `.apkm` bundles (auto-unpacked). Label, icon, version are parsed from the base APK; the full APK set lives in the clone's private namespace (splits are always copied, never moved). |
| **Clones** | Every clone gets a unique numeric id (1, 2, 3 …), its own storage namespace, its own `DexClassLoader`, and its own static-state space. Rename, change icon, duplicate (data copied), enable/disable, delete. |
| **Isolation** | Per-clone directories: `files/`, `cache/`, `shared_prefs/`, `databases/`, `code_cache/`. `getFilesDir()`, `getCacheDir()`, `openFileInput/Output()`, `openOrCreateDatabase()` and friends are redirected per clone; preferences are strictly namespaced per clone id. |
| **Launcher UI** | Mini-launcher home: grid of clone icons with numbered badge, live lifecycle dot, search, long-press → options (Launch / Rename / Change icon / Duplicate / Clone info / Add to home / Enable-Disable / Delete), Material You theming. |
| **Persistence** | Clone registry in Room (v1→v2: split paths, v2→v3: launcher capture); settings (theme, fallback behavior, update prefs, Telegram contact) in DataStore. Everything restores after restart: records, icons, names, order, enabled state — and running clones recover after process death (Recents relaunch rebuilds the runtime). |
| **Shortcuts** | One distinct launcher shortcut per clone (`clone_<id>`), clone-specific label + badged icon, intent routed through the host to launch the requested instance. Pin-to-home supported. |
| **Lifecycle** | `CREATED → STARTING → RUNNING → STOPPING → STOPPED` (+ `ERROR`), tracked in memory and persisted, surfaced in the UI. |
| **Permissions** | Full host permission battery: camera, microphone, location, storage/media, contacts, calendar, telephony, SMS, Bluetooth, sensors, notifications — declared in the manifest, batch-requested on first run and grantable anytime from Settings → App permissions. Guests inherit the host's grants (they physically run in the host process) — standard system dialogs only, no permission bypass. |
| **Updates** | In-app updater checks GitHub Releases (`releases/latest`), shows a Material dialog, downloads via system `DownloadManager`, and hands the APK to the standard installer flow. |

## Architecture

```
org.setbd.cloner
├── ClonerApp / MainActivity        single-activity host, shortcut router
├── di/AppContainer                 manual DI (Google-recommended pattern)
├── data
│   ├── db/CloneEntity·CloneDao·ClonerDatabase   Room registry
│   ├── CloneRepository             CRUD, duplicate, wipe, reorder
│   ├── SettingsStore               DataStore (theme, fallback, updates)
│   └── model/CloneInfo             domain model + CloneLifecycle states
├── core
│   ├── ApkImporter                 installed-app & SAF-APK import pipeline
│   ├── VirtualStorageManager       per-clone namespaces, usage, wipe/copy
│   ├── CloneLifecycleManager       state machine per clone
│   ├── ShortcutManagerImpl         distinct shortcut per clone (badged icon)
│   ├── PermissionMediator          guest needs → host grants
│   ├── LaunchCoordinator           engine launch + honest fallback policy
│   └── LaunchResult                Started / FallbackStarted / Failed
├── engine                          the container engine (POC scope)
│   ├── VirtualEngine               orchestrator, runtime cache, activity tracking
│   ├── HiddenApiUnlock             VMRuntime.setHiddenApiExemptions via HiddenApiBypass
│   ├── hook/ClonerInstrumentation  stub→guest activity swap + env attach
│   ├── hook/ActivityManagerHook    client-side startActivity rewrite
│   ├── guest/GuestRuntime          per-clone loader/resources/manifest/app
│   ├── guest/GuestClassLoader      child-first class loading
│   ├── guest/VirtualContext        redirected context (identity + storage)
│   ├── guest/VirtualPackageManager guest-space metadata, host delegation
│   ├── guest/ManifestParser        binary manifest reader (launcher/theme/launchMode)
│   ├── guest/GuestResourcesLoader  AssetManager.addAssetPath technique
│   └── stub/StubActivity*          declared proxy components
├── update/UpdateManager            GitHub Releases check + DownloadManager install
└── ui                              Compose Material 3 (splash/home/import/info/settings)
```

## Build

Requirements: JDK 17, Android SDK 35.

```bash
./gradlew :app:assembleRelease
```

Release signing is provided by CI through GitHub Secrets:

| Secret | Content |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | base64 of `setbd-cloner.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | `setbd-cloner` |
| `ANDROID_KEY_PASSWORD` | key password |

Every push to `main` publishes a signed APK to the **latest** GitHub release; pushing a tag `vX.Y.Z` publishes a versioned release. The in-app updater compares its tag against `versionName` in `app/build.gradle.kts` — bump the version, push the tag, done.

> **Keep the keystore safe.** It is never committed (`.gitignore` blocks `*.jks`). Losing it means losing update compatibility.

## Release flow

1. Edit `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Commit, tag `v1.0.1`, push.
3. Actions builds the signed APK → attaches it to the versioned release **and** refreshes the `latest` release.
4. Installed clients see "Update available" on next launch / manual check.

## Security & honesty commitments

- No signature-verification bypass, no Play Integrity / DRM circumvention, no sandbox escape, no SELinux interaction, no privileged APIs. Hooks are **process-local** to this app.
- Unsupported features fail loudly (logged + surfaced in Clone Info), never fake success.
- Guest data lives only inside this app's private storage and is excluded from backups.

See [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) for the full technical limitation analysis, per-version notes, and the list of app categories that cannot be virtualized.

## License

MIT © 2026 SETBD. Bundled fonts: Roboto (Apache 2.0).
