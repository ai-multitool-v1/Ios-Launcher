# Android Compatibility & Limitation Analysis

This document is the engineering ground truth for SETBD Cloner. It exists because the project explicitly refuses to fake APIs: everything below describes what a **normal, non-privileged, non-root Android application** can actually do when running third-party apps inside its own process — and where the hard platform boundaries are.

---

## 0.0 v1.2.1 core-engine update (Android 10+ dex policy)

- **Writable-dex enforcement.** Android 10 (API 29) hardened ART: apps targeting API 29+ may no longer execute DEX from a **writable** file — `PathClassLoader` fails with *"Writable dex file '…' is not allowed"*. Every guest APK (base + all splits) is now flipped to read-only (mode 0444) at import time **and** re-enforced at every runtime build. The launch-time enforcement is idempotent and heals clones imported by older versions **in place** — existing clones launch again without re-importing the app. The check ART performs is on the file's permission bits, not its location, so a read-only APK inside the app's private storage is fully compliant.

## 0. v1.2 core-engine update (launch reliability)

- **Three independent manifest sources.** A launch no longer depends on a single parse: the binary XML manifest, the framework's own `getPackageArchiveInfo` metadata (unaffected by hidden-API enforcement) and the launcher component captured at import time are merged (`ManifestMerger`). Launches die only when NO source yields a runnable entry — previously one failed parse produced "container cannot run this apk".
- **Import-time launcher capture.** Installed-app imports store the real launcher component (`getLaunchIntentForPackage`) in the registry (DB v3); file imports resolve it from the manifest. Even with both manifest parsers dead, a stored launcher still starts the guest with sensible defaults.
- **Guest-bound LayoutInflater.** Guest activities now run with a `PhoneLayoutInflater` built on the virtual context (window + `LAYOUT_INFLATER_SERVICE` paths). Custom view classes declared in guest layouts resolve through the guest class loader — previously every non-framework view crashed inflation; the activity is also wired as the private Factory2 so fragment view creation keeps routing.
- **No silent original-app fallback.** Failed launches show an explicit, actionable error by default. Opening the original app is now opt-in ("Compatibility fallback" in Settings, default OFF) — the app never quietly swaps itself for the original.
- **Diagnostics.** When a stub mapping fails the stub explains itself via toast instead of a silent black flash; launch errors carry user-readable reasons.

## 0.1 v1.1 core-engine update

- **Split-APK (App Bundle) support.** Installed App Bundle packages are imported as base + **all** split APKs (`split_config.*.abi/density/language`); splits are copied into the clone namespace and loaded together with the base (joined dex path, joined asset paths, zip-embedded native-lib lookup). `.xapk` / `.apks` / `.apkm` bundles picked from storage are unpacked and imported the same way.
- **Permission battery.** The host declares and batch-requests every grantable runtime permission; guests run inside the host process and inherit the grants (camera, microphone, location, storage/media, contacts, telephony, SMS, Bluetooth, sensors, notifications…).
- **Robustness.** Archive-metadata parse failures no longer abort a launch (manifest-only mode), missing MAIN/LAUNCHER filters fall back to the first declared activity, guest activities without an explicit theme inherit the guest application theme, and runtimes are rebuilt from the registry after process death (Recents keeps working).
- **Storage redirection hardening.** Guest `openFileInput/openFileOutput/deleteFile/fileList/getDataDir` now target the clone namespace — previously file-stream writes leaked to the host directory.

## 1. What Android allows a normal app to do

| Capability | Mechanism used | Status |
|---|---|---|
| Enumerate installed apps | `PackageManager.getInstalledPackages` + `QUERY_ALL_PACKAGES` | ✅ documented API |
| Read app label / icon / version / permissions | `getPackageArchiveInfo`, `ApplicationInfo` | ✅ documented API |
| Read & copy base APK + ALL split APKs of an installed app | `ApplicationInfo.sourceDir` / `splitSourceDirs` (world-readable by design, copied — never moved) | ✅ works without privileges |
| Import `.xapk` / `.apks` / `.apkm` bundles | ZIP extraction (`java.util.zip.ZipFile`) → base + splits staged into the clone namespace | ✅ documented API |
| Load foreign dex code in-process | `PathClassLoader` with base+splits joined on one dex path | ✅ documented API |
| Attach foreign resources | `AssetManager.addAssetPath` (hidden; via `VMRuntime.setHiddenApiExemptions("L")` using the LSPosed `hiddenapibypass` library) | ⚠️ hidden-API dependent, see §4 |
| Map guest components onto declared stub components | host manifest stubs + instrumentation hook | ⚠️ internal, process-local |
| Redirect "own storage" paths per clone | `ContextWrapper` overrides (`getFilesDir`, `getCacheDir`, `openOrCreateDatabase`, …) | ✅ regular subclassing |
| Serve guest its own package identity | `VirtualPackageManager` / `VirtualContext` | ⚠️ internal, process-local |
| Per-clone launcher shortcuts | `ShortcutManagerCompat` | ✅ documented API |
| Persist registry / settings | Room + DataStore | ✅ documented API |
| Host APK updates | GitHub Releases + `DownloadManager` + `REQUEST_INSTALL_PACKAGES` consent flow | ✅ documented API |

## 2. What is impossible for a normal third-party app

These are **kernel / system-server boundaries**. No amount of clever user-space code changes them, and this project does not attempt them:

- **Running guests in separate OS processes with separate UIDs.** Real sandboxing (like Android's Work Profile or user profiles) requires system privileges (`MANAGE_USERS` is signature-level). Our container runs guests **in the host process** — isolation is class-loader + namespace isolation, not process isolation.
- **True filesystem redirection (mount namespaces / bind mounts).** A normal app cannot intercept another UID's file I/O. We redirect every path a guest *asks for* through its context; direct writes to absolute paths (e.g. `/sdcard/WhatsApp/...`) are **not** redirected.
- **Virtualizing services, broadcast receivers and content providers.** Stub activities are cheap to declare; a correct stub provider/service architecture (provider authorities are system-registered) is not feasible without system hooks. Guests relying on their own providers (e.g. Firebase auto-init providers) run degraded.
- **Spoofing device identity to satisfy integrity checks.** Play Integrity, SafetyNet, DRM (Widevine), attestation and bank-app hardening detect or reject container environments. We do not try to defeat them, and this project will never add code that does.
- **Multiple login for apps whose servers bind state to device identity** — even a perfect client-side container cannot force the server to allow it.

## 3. App categories that cannot be virtualized (or run reduced)

| Category | Status |
|---|---|
| Split-APK / App Bundle apps | ✅ **supported since v1.1** (base + splits imported & loaded together) |
| Apps with signature / integrity verification (WhatsApp, banking apps, some games) | detect the container, refuse to run or fail |
| Apps relying on their own `ContentProvider` auto-init (Firebase-init style) | providers are not virtualized; app runs degraded or crashes — surfaced in Clone Info |
| Apps using foreground services heavily | services are not virtualized |
| Apps with native libraries requiring their own process name / `/proc` identity | process identity remains `org.setbd.cloner` |
| Multi-user / work-profile-dependent apps | obviously out of scope |

Simple monolithic and App Bundle apps (tools, launchers, note apps, many offline games, older messengers without integrity checks) are the sweet spot.

## 4. Android version-specific notes

| Version | Behavior |
|---|---|
| **8.0–8.1 (API 26–27)** | No hidden-API enforcement — hooks work directly. Largest container surface. |
| **9.0 (API 28)** | Non-SDK interface enforcement begins. We unlock via `VMRuntime.setHiddenApiExemptions("L")` (meta-reflection through the LSPosed `hiddenapibypass` library — no root needed). |
| **10–12 (API 29–32)** | Same technique continues to work. `startActivity` interception targets `IActivityTaskManager` + `IActivityManager` client proxies. |
| **13–15 (API 33–35)** | Restrictions tighten per release (more blocked hidden APIs, stricter `Parcelable` extras, notification permission needed for update downloads). The engine degrades gracefully: if any hook fails, clones fall back per the user's compatibility setting instead of crashing. |
| **Future versions** | Google keeps narrowing user-space container techniques. Expect per-release verification; the architecture isolates all breakage behind `HiddenApiUnlock` + hook install results. |

## 5. Security model (what we will never do)

- ❌ bypass package signature verification
- ❌ circumvent Play Integrity / SafetyNet / DRM / Widevine
- ❌ defeat authentication, sandbox security, SELinux, or device security mechanisms
- ❌ hide from the user that a fallback launch opened the ORIGINAL app
- ✅ use documented Android APIs wherever possible; internal APIs only for the container mechanics, process-locally, with the user's full knowledge

## 6. Known v1 (POC) scope decisions

- Activities: virtualized. Services / receivers / providers: **not** virtualized (explicitly logged + reported in Clone Info).
- `startActivity` initiated by guest code: intercepted & remapped. Direct `ActivityManager` calls from guest code: not intercepted.
- Theme/`:configChanges`: guest activities run on stub config (orientation changes of guests not honored individually).
- Guest `Application` is instantiated & initialized; failure marks the clone **degraded** and is visible in the UI instead of silently pretending.
- Preferences are stored in the host prefs directory **namespaced per clone** (`clone<N>__name`) — strictly isolated between clones, but not physically inside `clone_N/shared_prefs/`.
- One process for all clones by design; a crashing guest can take the host down (crash handler reports the clone). True process isolation requires the system features listed in §2.

## 7. Roadmap (only after POC validation on device)

1. Stub service pool + guest service mapping.
2. IO-redirect for absolute paths (requires native lib — assess per Android version).
3. Split-APK re-joining at import time.
4. Per-clone process fork via `ActivityThread` cloning (requires per-version hook maintenance).
5. De-Googled notification + account mediation layers.
