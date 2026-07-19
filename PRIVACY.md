# Privacy Policy — XFiles

**Last updated: 2026-07-19**

XFiles (`app.local1st.files`) is an offline file manager for Android, published as
open source under [GPL-3.0-only](LICENSE).

## The short version

**XFiles collects nothing, sends nothing, and cannot send anything.**

The app does not request the `INTERNET` permission. Android therefore blocks it from
opening any network connection at all — this is enforced by the operating system, not by
a promise in this document. You can verify it yourself:

- read [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) in this repository, or
- run `aapt dump permissions` against the published APK, or
- put the device in a monitored network and watch it stay silent.

## Data we collect

None.

There is no analytics SDK, no crash reporter, no advertising SDK, no telemetry, no
account system, no cloud sync, and no remote configuration. No personal data, device
identifier, usage statistic, or file content ever leaves your device, because there is no
mechanism by which it could.

## Data the app accesses on your device

XFiles is a file manager, so it necessarily reads and writes your files. All of this
happens locally, on your device only:

| What it accesses | Why | Leaves the device? |
|---|---|---|
| Files and folders in shared storage | To list, open, copy, move, rename, delete, compress and extract them — the app's core function | No |
| The list of installed apps | To show the App manager, with icons, versions and components | No |
| Files anywhere on the filesystem, as superuser | Only if you explicitly enable **Root access** in Settings, and only on a rooted device | No |

Thumbnails generated for images and videos are cached on your device's private storage so
they load quickly next time. Clearing the app's storage removes them.

Your app settings (theme, sort order, and similar) are stored locally with Android
DataStore.

## Permissions

Every permission the app declares, and why, is documented in the
[README](README.md#permissions--privacy). Notably absent: `INTERNET`.

## Children

XFiles is a general-purpose utility and is not directed at children. It collects no data
from anyone, of any age.

## Third parties

XFiles contains no third-party SDKs that collect data. It bundles open-source libraries
(Jetpack Compose, Coil, Apache Commons Compress, Media3/ExoPlayer, junrar) purely as
local code — none of them are given network access, because the app has none.

## Changes to this policy

If this policy ever changes, the revision will appear in this file's git history in the
public repository, and the date at the top will be updated.

## Contact

Questions or reports: open an issue at
<https://github.com/Local1stDotApp/XFiles/issues>.
