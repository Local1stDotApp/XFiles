# XFiles

An Android file manager that clones the workflow of **X-plore File Manager**
(lonelycatgames) — dual-pane tree browsing, archive-as-folder, app manager —
rebuilt on the latest Android stack with a **Material 3 Expressive** UI.

## Features

- **Dual-pane tree browser** — X-plore's signature: two independent panes
  (side-by-side on wide screens, swipeable pager on phones); folders expand
  *in place* as a tree with indent guide lines; breadcrumb bar per pane.
- **Storage roots** — internal storage / SD / USB volumes with free-space usage
  bars, plus the App manager root.
- **File operations** — multi-select via right-edge checkmarks; copy/move
  from the active pane into the other pane, delete, rename, new folder,
  zip compression; background engine with progress (wavy Expressive
  indicator), cancellation, and Skip/Overwrite/Keep-both conflict resolution.
- **Archives as folders** — browse zip/jar/apk, 7z, tar(.gz/.bz2/.xz), rar
  read-only; extract by copying out; APK install shortcut.
- **App manager** — installed apps with real icons, version/package badges;
  launch, app info, uninstall, copy APK out (share an app as file).
- **Viewers** — image viewer (pager + pinch zoom), text viewer with edit/save,
  hex viewer with on-demand paging, audio/video player (Media3/ExoPlayer).
- **Search** — live streaming recursive search with `*`/`?` wildcards,
  descends into archives, reveal-in-tree on tap.
- **Material 3 Expressive** — `MaterialExpressiveTheme` + expressive motion,
  dynamic color (Android 12+), light/dark/system, floating toolbar,
  `LoadingIndicator`/`LinearWavyProgressIndicator`, edge-to-edge.

## Tech stack

| Layer | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose (BOM 2026.06.01), material3 **1.5.0-alpha23** (Expressive APIs) |
| Build | AGP 9.2.1 (built-in Kotlin, no KGP), Gradle 9.4.1, compileSdk 37 / target 36 / min 26 |
| Architecture | Single module, MVVM + StateFlow, manual DI composition root (`di/Graph`) |
| Persistence | DataStore Preferences |
| Media/Images | Coil 3 (+ video frames, GIF, custom app-icon fetcher), Media3 ExoPlayer |
| Archives | java.util.zip, commons-compress (+xz), junrar |

Note: material3 is pinned to `1.5.0-alpha23` because the Expressive APIs are
`internal` in the 1.4.0 stable release.

## Project layout

```
app/src/main/java/com/xfiles/
├── core/
│   ├── fs/        XEntry model, XId id scheme, XFileSystem + FsRegistry,
│   │              Local/Archive/Apps filesystems, storage roots
│   ├── ops/       OperationEngine (copy/move/delete/compress + conflicts)
│   ├── search/    recursive SearchEngine
│   ├── prefs/     DataStore settings
│   ├── thumb/     Coil app-icon fetcher
│   └── util/      formatters, mime/category mapping, intents
├── di/            Graph (composition root) + GraphInit wiring
└── ui/
    ├── browser/   PaneController (tree state machine), PaneView, EntryRow
    ├── main/      MainViewModel, MainScreen (dual pane + floating toolbar), PermissionGate
    ├── dialogs/   rename/new-folder/delete/zip/details/sort, ops progress + conflicts
    ├── viewer/    image / text / hex / media viewers
    ├── search/    search overlay
    ├── settings/  settings screen
    └── theme/     MaterialExpressiveTheme setup
```

Entry ids are URI-like strings: `file:///abs/path`,
`zip:///abs/archive.zip!/inner/path`, `apps://package.name`.

## Build & run

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and an Android SDK with platform 37. On first launch grant
“All files access” (the app deep-links to the system page).

---
*This is a study/clone project inspired by X-plore File Manager; it shares no
code or assets with the original.*
