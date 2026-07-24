<div align="center">

<img src="docs/assets/logo.png" width="104" alt="XFiles logo">

# XFiles

**An offline, open-source Android file manager with X-plore's workflow** — dual-pane
tree browsing, archive-as-folder, app manager, APK/AAB/XAPK install, root & Shizuku
access — on the latest Android stack with a Material 3 Expressive UI.

[![Release](https://img.shields.io/github/v/release/Local1stDotApp/XFiles?include_prereleases&sort=semver&label=release)](https://github.com/Local1stDotApp/XFiles/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0--only-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](#build--run)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)](#tech-stack)
[![No network](https://img.shields.io/badge/network-none-success)](#permissions--privacy)

English · [简体中文](README.zh-CN.md)

<img src="docs/assets/demo.gif" width="300" alt="One tour on a OnePlus 7 Pro: multi-select two files in Download and Copy to… an explicit destination via the full-screen picker; open the App manager and expand an app in place to its Components node, base.apk and every split_config APK; open Root and browse the real filesystem, with /data revealing adb, anr, app, app-private and other root-only directories">

<sub>Real capture on a OnePlus 7 Pro (Android 16), sped up. One run: <b>Copy to…</b> an explicit destination → <b>App manager</b> (an app's components &amp; APK splits) → <b>Root</b> (the real filesystem, <code>/data</code> and all).</sub>

</div>

---

## Why

- **X-plore broke on [Waydroid](https://waydro.id)** over the past half-year; I needed a replacement.
- **It's the LLM era** — if a tool doesn't fit, build your own.
- **Software with dangerous root powers should be open-source, fully offline, and
  collect nothing.** XFiles has no `INTERNET` permission and no analytics.

## Download

Grab an APK from [**Releases**](https://github.com/Local1stDotApp/XFiles/releases):

- **`vX.Y`** — stable, cut whenever `versionName` is bumped.
- **`nightly`** — a single rolling prerelease, refreshed on every push to `main`.

Requires **Android 8.0 (API 26)** or newer. On first launch, grant *All files access*
(the app deep-links to the system page). Or [build it yourself](#build--run).

## Features

### Dual-pane tree browser

X-plore's signature: two independent panes — side-by-side on wide screens, a swipeable
pager on phones. Folders expand **in place** as a tree with indent guide lines, and each
pane carries its own floating breadcrumb pill.

Archives sit in the tree like any other folder — the breadcrumb descends straight into
`project.zip`.

### Thumbnails in the tree

Images and video poster frames render inline. Video frames are extracted once at
thumbnail size and disk-cached, so they are instant after a restart, with a play badge
and an icon fallback while loading.

### File operations

Multi-select via right-edge checkmarks. **Copy/move/extract go to an explicit
destination** chosen in a full-screen folder picker — it defaults to the other pane, but
you can browse anywhere and make new folders — or use `Copy to…`/`Move to…` from the
long-press menu. Plus delete, rename, new folder. On Android 8–10, writes to SD cards
and other secondary volumes work through a one-time SAF grant.

A background engine drives it all with progress (the wavy Expressive indicator),
cancellation, and Skip / Overwrite / Keep-both conflict resolution.

### High-performance zip

Creation deflates every entry across all CPU cores (commons-compress
`ParallelScatterZipCreator`, STORE for already-compressed media). Extraction runs one
`ZipFile` handle per worker off a shared queue. Zip-Slip guarded; falls back to
single-threaded streaming when temp space is tight.

### Foreground service

Long copy/move/zip/extract operations keep running when the app is backgrounded, shown
in an ongoing notification with a Cancel action and a wake lock. The service self-stops
when idle.

### Archives as folders

Browse zip/jar/apk, 7z, tar(.gz/.bz2/.xz) and rar read-only; extract by copying out.
Anything installable installs from right there — see below.

### App manager

Installed and system apps in two groups, with real icons, version/package badges and
rich details. Install, launch, uninstall, or copy an APK out to share an app as a file.

Expand an app and you get everything that belongs to it in one place: a **Components**
node broken down into activities / providers / receivers / services, plus `base.apk` and
every `split_config.*` APK — each one expandable, because an APK is just a zip.

Drill into a category and each component shows its class name and its real manifest
state — `exported` / `not exported`, `enabled` / `disabled`. Launch activities, create
shortcuts, and enable/disable components where the system permits.

### Package installer

APKs install with a tap — and so does everything APK-shaped: split bundles
(`.apks` / `.apkm` / `.xapk`, with XAPK **OBB** expansion files placed where the game
expects them) and even raw **`.aab`** files. A vendored
[bundletool](https://github.com/google/bundletool) converts the bundle on the phone
into split APKs matched to the device and signs them with a built-in certificate — no
PC, no Play Store. Installs run in the foreground service, so backgrounding the app
mid-install doesn't kill the session.

### Root & Shizuku

Off by default. Turn on **Root access** in Settings and a **Root** entry (`/`) joins the
storage roots — with a separate **Read-only** switch that blocks anything needing
privilege to write, so you can go look without being able to break your system.

Two interchangeable transports power it, and Settings lets you pick (or leave it on
auto):

- **`su`** — full superuser on rooted devices. `/data` opens up to `adb`, `anr`, `app`,
  `app-private`, `dalvik-cache` — directories a normal app can't even list — with
  list/read/write/mkdir/rename/delete under `/data`, `/system`, …
- **[Shizuku](https://shizuku.rikka.app/)** — no root required: XFiles binds a Shizuku
  user service running at shell (ADB) privilege that hands over real file descriptors.
  Settings walks you through setup and permission.

Whichever transport is live also kicks in transparently where plain file access is
denied — most notably **`Android/data`** and **`Android/obb`**, which open like any
other folder. Thumbnails, viewers and even video playback work on privileged paths.
With no transport available, Root falls back to a read-only `/` view.

The settings screen carries the rest of the preferences too — theme, dynamic color,
hidden files, folders-first, sort key and direction.

### Viewers

An image viewer (pager + pinch zoom), a text viewer with edit/save, a hex viewer with
on-demand paging, an audio player, and a custom video player (Media3/ExoPlayer) with
**frame-accurate stepping**.

Tap the time readout to swap it for a frame counter — current frame, total, and the real
frame rate — then step ±1 frame, swipe on the picture to scrub by time or frames with
live preview, drag the compact control card out of the way, or go fullscreen immersive.

### Search

Live streaming recursive search with `*`/`?` wildcards. Descends into archives, and
reveals results in the tree on tap.

### Open from other apps

Off by default so XFiles never hijacks anything. Three opt-in toggles in Settings
register XFiles with the system resolver for **archives**, **images** and **videos** —
after that, "open with XFiles" from any app lands in the archive tree or the right
viewer.

### Material 3 Expressive

`MaterialExpressiveTheme` + expressive motion, dynamic color (Android 12+),
light/dark/system, floating toolbar, `LoadingIndicator` / `LinearWavyProgressIndicator`.
True edge-to-edge: no top app bar — content scrolls under the status bar behind a
gradient scrim, with floating breadcrumb and settings buttons.

### 18 languages

The UI follows the system language: English plus Arabic, Chinese (Simplified and
Traditional), Dutch, French, German, Hindi, Indonesian, Italian, Japanese, Korean,
Polish, Portuguese (Brazil), Russian, Spanish, Turkish and Vietnamese.

## Permissions & privacy

No network permission, no telemetry, no accounts, no ads. Every permission the app
declares, and why:

| Permission | Why |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | Browse and modify all of shared storage — the whole point of an X-plore-style manager |
| `READ_EXTERNAL_STORAGE` *(≤ API 32)* | Legacy read path on older Android |
| `WRITE_EXTERNAL_STORAGE` *(≤ API 29)* | Legacy write path on older Android |
| `QUERY_ALL_PACKAGES` | The App manager lists what is installed |
| `REQUEST_DELETE_PACKAGES` | Uninstall from the App manager |
| `REQUEST_INSTALL_PACKAGES` | The package installer: APKs, split bundles (`.apks`/`.apkm`/`.xapk`), AABs |
| `POST_NOTIFICATIONS` | Show the progress notification for long operations |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keep a copy/move or install running when backgrounded |
| `WAKE_LOCK` | Don't sleep mid-operation |
| **`INTERNET`** | **Not requested.** The app cannot talk to the network at all |

That last row is enforced by the OS, not by policy — verify it yourself in
[`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) or with
`aapt dump permissions` on the APK.

## Tech stack

| Layer | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose (BOM 2026.06.01), material3 **1.5.0-alpha23** (Expressive APIs) |
| Build | AGP 9.2.1 (built-in Kotlin, no KGP), Gradle 9.4.1, compileSdk 37 / target 37 / min 26 |
| Architecture | MVVM + StateFlow, manual DI composition root (`di/Graph`); app module + a shaded bundletool vendor module |
| Persistence | DataStore Preferences |
| Media/Images | Coil 3 (GIF, custom fetchers: app icons, disk-cached video thumbnails), Media3 ExoPlayer |
| Archives | java.util.zip, commons-compress (+xz), junrar |
| Privileged access | Shizuku 13.1.5 (user service, real fds) · `su` shell |
| Package install | PackageInstaller sessions · vendored bundletool 1.18.3 · ARSCLib (in-process aapt2) · minimal self-signed signer |

Note: material3 is pinned to `1.5.0-alpha23` because the Expressive APIs are
`internal` in the 1.4.0 stable release.

## Project layout

```
app/src/main/java/app/local1st/files/
├── core/
│   ├── fs/        XEntry model, XId id scheme, XFileSystem + FsRegistry,
│   │   │          Local/Archive/Apps/Root filesystems, storage roots, legacy SAF writes
│   │   └── priv/  privileged transports — su shell & Shizuku user service (real fds)
│   ├── ops/       OperationEngine (copy/move/delete/compress + conflicts), OpsService
│   ├── search/    recursive SearchEngine
│   ├── prefs/     DataStore settings
│   ├── thumb/     Coil fetchers: app icons, disk-cached video thumbnails
│   └── util/      formatters, mime/category mapping, intents; package install —
│                  PackageInstaller sessions, AAB→APKs (bundletool), XAPK/OBB,
│                  in-process aapt2 (ARSCLib), self-signed signing
├── di/            Graph (composition root) + GraphInit wiring
└── ui/
    ├── browser/   PaneController (tree state machine), PaneView, EntryRow
    ├── components/ shared Compose bits (tooltips, predictive back)
    ├── main/      MainViewModel, MainScreen (dual pane + floating toolbar), PermissionGate
    ├── dialogs/   rename/new-folder/delete/zip/details, ops progress + conflicts
    ├── viewer/    image / text / hex viewers, audio player, frame-accurate video player
    ├── search/    search overlay
    ├── settings/  settings screen
    ├── appinfo/   app details overlay
    └── theme/     MaterialExpressiveTheme setup

vendor/bundletool-shaded/   Gradle module shading bundletool 1.18.3 + its pinned deps
```

Entry ids are URI-like strings: `file:///abs/path`,
`zip:///abs/archive.zip!/inner/path`, `apps://package.name`, `root:///abs/path`.

## Build & run

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and an Android SDK with platform 37. On first launch grant
"All files access" (the app deep-links to the system page).

## Releases

A **self-hosted** GitHub Actions workflow ([`.github/workflows/release.yml`](.github/workflows/release.yml))
builds a signed APK on every push to `main`:

- The build number (`versionCode`) increments each run (`github.run_number`).
- `versionName` lives in `version.properties`. While it's unchanged, each push just
  refreshes a single rolling **`nightly`** prerelease with the latest build. Bump
  `versionName` to cut a new stable `vX.Y` release.
- Signing keys/passwords come from repo secrets: `KEYSTORE_BASE64`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. The runner needs the Android SDK.

## License

[GPL-3.0-only](LICENSE). A file manager that can be handed root deserves a licence that
keeps every future copy open — if you ship a modified XFiles, ship its source too.

---

*This is a study/clone project inspired by X-plore File Manager; it shares no
code or assets with the original.*
