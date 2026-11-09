# F-Droid

Working notes for getting XFiles into the main [F-Droid](https://f-droid.org) repository
([issue #9](https://github.com/Local1stDotApp/XFiles/issues/9)).

## Archives in the F-Droid build

F-Droid builds every app from source and only ships free software. One dependency fails that:
**junrar**, a RAR reader, is under the UnRAR licence. F-Droid counts that licence as non-free,
and fdroidserver's scanner rejects the library outright
([fdroidserver!1091](https://gitlab.com/fdroid/fdroidserver/-/merge_requests/1091)).

Both flavors read every archive format through **libxfarchive**, the app's own native library
(`app/src/main/cpp`): a read-only subset of [libarchive](https://libarchive.org) 3.8 (BSD) with
decode-only zstd, liblzma, bzip2 and lz4, and a random-access zip reader of our own. All of it is
built from the sources vendored in `app/src/main/cpp/third_party`, whose README lists the
upstream versions and licences. So the two product flavors differ only in one corner of RAR:

| Flavor | Ships as | Archives made by RAR 1.x (the mid-1990s) |
|---|---|---|
| `full` | GitHub releases, Google Play | junrar (pure Java) |
| `fdroid` | F-Droid | not supported: a RAR 1.4 archive fails to open, and a member packed by RAR 1.5x fails to read, with an error |

libarchive reads RAR 2.0 to RAR5, multi-volume sets and solid archives included, through
`third_party/patches/libarchive-rar4.patch`. Upstream libarchive gives up on **solid RAR 4**
(WinRAR's "Create solid archive") at the second member and on filters in PPMd blocks. It also
decodes every member with the RAR 2.9 algorithm, so members packed by **RAR 2.x** fail, and it
takes the comments and recovery records of RAR 2.x archives for broken headers. The patch fixes
all of these (`SolidRar4Test`, `Rar2Test`). The solid and filter support is written from
libarchive's own code, and the RAR 2.0 decoder is ported from rardecode (BSD); neither comes
from UnRAR's source. What is left is older: members packed by **RAR 1.5x**, and the **RAR 1.4**
format. The full flavor hands those to junrar; `RarVolumes.isBeyondLibarchive` spots them from
the headers. junrar can't read a member whose header holds a file comment, which RAR 1.x
allowed.

Neither flavor can read encrypted entries, since there is no password prompt; opening one fails
with an error.

The difference lives in `RarArchives`, one copy per flavor: `app/src/full/…` wraps junrar, and
`app/src/fdroid/…` declines. `testFdroid`'s `RarFlavorTest` fails if junrar ever reaches the
fdroid classpath; `testFull`'s checks which archives junrar gets. The JVM tests build
libxfarchive for the host (`:app:buildHostNative`), so `ArchiveFormatsTest`, `RarReadingTest`,
`SolidRar4Test` and `Rar2Test` run the native readers in both flavors. CI builds
`:app:assembleFdroidRelease` on every push and keeps the APK as the `XFiles-<build>-fdroid-apk`
artifact, so a change that breaks F-Droid's build fails there first.

Build it locally with `./gradlew :app:assembleFdroidRelease`.

## How F-Droid finds a new release

F-Droid reads the version from the source at each release tag, so `version.properties` carries
both numbers:

```properties
versionName=1.5.0
versionCode=44
```

- A stable release (`vX.Y.Z`) is built with that `versionCode` as it stands, so F-Droid
  building the tag gets the same number as the GitHub and Play builds.
- Each nightly is that `versionCode` plus the number of commits since the release, so every
  build still outranks the one before it.
- **Cutting a release:** bump `versionName`, and in the same commit set `versionCode` above the
  current nightly's build number, which the nightly release's title shows. If the number is too
  low, CI fails the release and prints the number to use.

F-Droid's update checker scans tags matching `^v[0-9.]+$`, so the rolling `nightly` tag is
ignored. When it sees a new tag, it adds a build for it to the recipe, and the update ships with
F-Droid's next build cycle.

## Submitting (once)

1. Cut a stable release that contains the `fdroid` flavor. No release has it yet, so the first
   one after this change is what F-Droid builds first.
2. Fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata) on GitLab and add
   `metadata/app.local1st.files.yml` (below), with that release's version name, version code,
   and the **full commit hash** of its tag.
3. Open a merge request titled "New app: XFiles" with fdroiddata's template. Their CI runs
   `fdroid lint` and `fdroid build` on it, and a reviewer takes it from there.

The listing text, icon, feature graphic and screenshots come from `fastlane/metadata/android/`,
which Play uses too, so the text has to hold for both flavors. F-Droid shows
`changelogs/default.txt` unless a `changelogs/<versionCode>.txt` exists.

F-Droid signs the APK with its own key. Its installs therefore can't update to or from the
GitHub and Play builds without an uninstall. That already holds between GitHub and Play (see
[play-submission.md](play-submission.md) §0).

```yaml
Categories:
  - App Manager
  - File Manager
  - System
License: GPL-3.0-only
WebSite: https://xfiles.local1st.app
SourceCode: https://github.com/Local1stDotApp/XFiles
IssueTracker: https://github.com/Local1stDotApp/XFiles/issues
Changelog: https://github.com/Local1stDotApp/XFiles/releases

AutoName: XFiles

RepoType: git
Repo: https://github.com/Local1stDotApp/XFiles.git

Builds:
  - versionName: X.Y.Z
    versionCode: NN
    commit: <full commit hash of vX.Y.Z>
    subdir: app
    sudo:
      - apt-get update
      - apt-get install -y openjdk-25-jdk-headless
      - update-alternatives --auto java
    gradle:
      - fdroid
    ndk: 29.0.14206865

MaintainerNotes: |-
  The fdroid flavor leaves out junrar (UnRAR licence), which the full flavor keeps for archives
  made by RAR 1.x. Archives are read by native code built from source (app/src/main/cpp,
  libarchive with a patch for solid RAR 4 and RAR 2.x), hence ndk.
  versionCode is read from version.properties at each tag (UpdateCheckData).

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
UpdateCheckData: version.properties|versionCode=(\d+)|.|versionName=(.+)
CurrentVersion: X.Y.Z
CurrentVersionCode: NN
```

Why the recipe looks the way it does:

- **`sudo`**: F-Droid's build server runs Debian trixie, whose default JDK is 21. CI builds with
  JDK 25, and Debian trixie packages `openjdk-25-jdk-headless`. F-Droid's scanner deletes
  `gradle/gradle-daemon-jvm.properties`, so Gradle runs on whichever `java` is the default.
- **Gradle**: F-Droid swaps `gradlew` for its own `gradlew-fdroid`. That wrapper takes the
  version from `gradle-wrapper.properties` and checks the download against
  [its checksum log](https://gitlab.com/fdroid/gradle-transparency-log). A Gradle bump only
  builds there once the log lists that version.
- **Dependencies**: all come from Google's Maven, Maven Central and the Gradle Plugin Portal,
  which the scanner allows. Nothing else in the dependency tree is on its non-free list.
- **`ndk`**: libxfarchive is compiled from the C sources in the repository, with the NDK that
  `app/build.gradle.kts` pins (`ndkVersion`). CMake comes from the SDK: AGP installs the version
  `externalNativeBuild` asks for (3.31.6) on first use, as it does on CI.
- **Third-party C sources**: `app/src/main/cpp/third_party` holds only sources and licences, no
  binaries, so the scanner has nothing to flag there. `third_party/update.sh` re-creates the
  subset from upstream release tarballs.
