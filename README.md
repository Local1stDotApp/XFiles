# XFiles

An offline, open-source Android file manager with X-plore's workflow — dual-pane
tree browsing, archive-as-folder, app manager, root access — on the latest Android
stack with a **Material 3 Expressive** UI.

Package `app.local1st.files`. **No network permission, no telemetry.**

## Why

- **X-plore broke on [Waydroid](https://waydro.id)** over the past half-year; I needed a replacement.
- **It's the LLM era** — if a tool doesn't fit, build your own.
- **Software with dangerous root powers should be open-source, fully offline, and
  collect nothing.** XFiles has no `INTERNET` permission and no analytics.

## Features

- **Dual-pane tree browser** — X-plore's signature: two independent panes
  (side-by-side on wide screens, swipeable pager on phones); folders expand
  *in place* as a tree with indent guide lines; floating breadcrumb pill per pane.
- **Thumbnails in the tree** — images and video poster frames inline; video frames
  are extracted once at thumbnail size and disk-cached (instant after restarts),
  with a play badge and icon fallback while loading.
- **Storage roots** — internal storage / SD / USB volumes with free-space usage
  bars, plus the App manager root.
- **File operations** — multi-select via right-edge checkmarks; **copy/move/extract
  to an explicit destination** chosen in a full-screen folder picker (defaults to the
  other pane, browse anywhere, make new folders), or `Copy to…`/`Move to…` from the
  long-press menu; delete, rename, new folder. Background engine with progress (wavy
  Expressive indicator), cancellation, and Skip/Overwrite/Keep-both conflict resolution.
- **High-performance zip** — creation deflates every entry across all CPU cores
  (commons-compress `ParallelScatterZipCreator`, STORE for already-compressed media),
  extraction runs one `ZipFile` handle per worker off a shared queue. Zip-Slip guarded;
  falls back to single-threaded streaming when temp space is tight.
- **Foreground service** — long copy/move/zip/extract operations keep running when the
  app is backgrounded, shown in an ongoing notification with a Cancel action and a wake
  lock; the service self-stops when idle.
- **Archives as folders** — browse zip/jar/apk, 7z, tar(.gz/.bz2/.xz), rar
  read-only; extract by copying out; APK install shortcut.
- **App manager** — installed apps with real icons, version/package badges and
  rich app details; activities/services/receivers/providers browsable as a tree
  (launch activities, create shortcuts, enable/disable components where possible);
  APK install, launch, uninstall, copy APK out (share an app as file).
- **Root access** — on rooted devices a **Root** entry (`/`) appears and browses
  the whole filesystem as superuser via `su`: list/read/write/mkdir/rename/delete
  under `/data`, `/system`, … Files stream through `su cat`/`cat >`, so the app's
  own viewers can open protected files. Falls back to a read-only `/` view when
  `su` is unavailable.
- **Viewers** — image viewer (pager + pinch zoom), text viewer with edit/save,
  hex viewer with on-demand paging, audio player, and a custom video player
  (Media3/ExoPlayer) with **frame-accurate stepping**: tap the time readout for a
  frame counter, step ±1 frame, swipe on the picture to scrub by time or frames
  with live preview, drag the compact control card out of the way, fullscreen
  immersive playback.
- **Search** — live streaming recursive search with `*`/`?` wildcards,
  descends into archives, reveal-in-tree on tap.
- **Material 3 Expressive** — `MaterialExpressiveTheme` + expressive motion,
  dynamic color (Android 12+), light/dark/system, floating toolbar,
  `LoadingIndicator`/`LinearWavyProgressIndicator`. True edge-to-edge: no top
  app bar — content scrolls under the status bar behind a gradient scrim, with
  floating breadcrumb and settings buttons.

## Tech stack

| Layer | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose (BOM 2026.06.01), material3 **1.5.0-alpha23** (Expressive APIs) |
| Build | AGP 9.2.1 (built-in Kotlin, no KGP), Gradle 9.4.1, compileSdk 37 / target 37 / min 26 |
| Architecture | Single module, MVVM + StateFlow, manual DI composition root (`di/Graph`) |
| Persistence | DataStore Preferences |
| Media/Images | Coil 3 (GIF, custom fetchers: app icons, disk-cached video thumbnails), Media3 ExoPlayer |
| Archives | java.util.zip, commons-compress (+xz), junrar |

Note: material3 is pinned to `1.5.0-alpha23` because the Expressive APIs are
`internal` in the 1.4.0 stable release.

## Project layout

```
app/src/main/java/app/local1st/files/
├── core/
│   ├── fs/        XEntry model, XId id scheme, XFileSystem + FsRegistry,
│   │              Local/Archive/Apps/Root filesystems, RootShell (su), storage roots
│   ├── ops/       OperationEngine (copy/move/delete/compress + conflicts)
│   ├── search/    recursive SearchEngine
│   ├── prefs/     DataStore settings
│   ├── thumb/     Coil fetchers: app icons, disk-cached video thumbnails
│   └── util/      formatters, mime/category mapping, intents, APK install/inspect
├── di/            Graph (composition root) + GraphInit wiring
└── ui/
    ├── browser/   PaneController (tree state machine), PaneView, EntryRow
    ├── main/      MainViewModel, MainScreen (dual pane + floating toolbar), PermissionGate
    ├── dialogs/   rename/new-folder/delete/zip/details, ops progress + conflicts
    ├── viewer/    image / text / hex viewers, audio player, frame-accurate video player
    ├── search/    search overlay
    ├── settings/  settings screen
    ├── appinfo/   app details overlay
    └── theme/     MaterialExpressiveTheme setup
```

Entry ids are URI-like strings: `file:///abs/path`,
`zip:///abs/archive.zip!/inner/path`, `apps://package.name`, `root:///abs/path`.

## Build & run

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and an Android SDK with platform 37. On first launch grant
“All files access” (the app deep-links to the system page).

## Releases

A **self-hosted** GitHub Actions workflow (`.github/workflows/release.yml`) builds a
signed APK on every push to `main`:

- The build number (`versionCode`) increments each run (`github.run_number`).
- `versionName` lives in `version.properties`. While it's unchanged, each push just
  refreshes a single rolling **`nightly`** prerelease with the latest build. Bump
  `versionName` to cut a new stable `vX.Y` release.
- Signing keys/passwords come from repo secrets: `KEYSTORE_BASE64`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. The runner needs the Android SDK.

---
*This is a study/clone project inspired by X-plore File Manager; it shares no
code or assets with the original.*
