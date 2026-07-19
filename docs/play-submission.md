# Google Play submission — working notes

## Status as of 2026-07-19

Done, verified live via the Publisher API:

- App created in Play Console; **app signing = Google-generated key** (so Play-served
  builds and the GitHub-release APKs have *different* signatures — see §0).
- Service account granted access to `app.local1st.files` (`reviews.list` → HTTP 200).
- **AAB uploaded and committed to the `internal` track** — versionCode 9, sha1 `949088f8…`.
  The API accepted the first bundle, so the manual-first-upload caveat in
  `nearby/flutter_app/PLAY_PUBLISHING.md` §2.7 did not apply here.
- **Store listing pushed**: en-US (title 6 / short 77 / full 3005 chars) and zh-CN
  (6 / 32 / 1327), plus icon, feature graphic and 7 phone screenshots on en-US.

Still outstanding — all Console-only, see §8:

1. App content forms: privacy policy URL, Data safety, Content rating, Target audience, Ads
2. Foreground service type declaration (§5) + video link
3. Permissions declaration form (§4) + video link
4. Upload the recorded compliance video to YouTube as **unlisted**
5. Submit for review

Scripts used (kept out of the repo, in the session scratchpad): `play_upload.py`,
`play_listing.py` — dependency-free Publisher API clients. Both default to a dry run and
abandon the edit unless `--commit` is passed. Note the image endpoint is
`.../edits/{editId}/listings/{language}/{imageType}` with camelCase types
(`icon`, `featureGraphic`, `phoneScreenshots`) — not `/images/`, and not `appIcon`.

---


Verified against Play policy on **2026-07-19**. Policy pages move; re-check anything
here that looks stale. Account situation assumed: **personal developer account
registered before 2023-11-13**, which is exempt from the 12-tester × 14-day closed
testing gate.

---

## 0. Decide this before you upload anything

**Play App Signing key choice — effectively irreversible.**

Play App Signing is mandatory for new apps. You have two options and they are not
equivalent for a project that also ships APKs on GitHub:

| Option | Consequence |
|---|---|
| Let Google generate the app signing key (default) | Play-installed and GitHub-installed copies have **different signatures**. A user who installed from GitHub cannot update from Play, or vice versa — they must uninstall first, losing app data. |
| **Upload your existing `release.jks` as the app signing key** | Play and GitHub builds share one signature. Users move between channels freely. Same key also keeps F-Droid/IzzyOnDroid consistent. |

For this project the second option is almost certainly right, since the release keystore
already exists. You can only supply your own app signing key **at the time the app is
created** in Play Console; changing it later requires Google's key-upgrade process and
still leaves old installs stranded.

Either way the release keystore remains the **upload key**. Its location is deliberately
not recorded here — it lives outside this repository and is the only recoverable copy,
since the CI secrets are write-only.

> **What was actually chosen:** Google-generated app signing key. Play-served builds and
> the GitHub-release APKs therefore have different signatures, and users cannot move
> between the two channels without uninstalling first. Worth a line in the README's
> download section.

---

## 1. Build artifact

Play requires an **AAB**, not an APK. The release workflow now runs
`:app:bundleRelease` alongside `:app:assembleRelease` and uploads
`app-release.aab` as a build artifact (90-day retention). Download it from the
workflow run and upload that to Play.

Verified locally: the bundle builds clean with R8/`isMinifyEnabled = true` (7.4 MB AAB,
3.2 MB release APK).

> **Test the release build on a device before submitting.** The debug build is what has
> been exercised so far. R8 shrinking plus reflective archive code
> (commons-compress, junrar) is exactly where a release-only crash hides, and
> `proguard-rules.pro` only `-keep`s `org.tukaani.xz.**`. Install a signed release APK
> and open a zip, a 7z, a tar.gz and a rar before you ship.

`versionCode` comes from `github.run_number` and increases monotonically — fine for
Play, which rejects re-used or decreasing codes.

---

## 2. Data safety form

Mandatory even though the app collects nothing.

- Does your app collect or share any of the required user data types? → **No**
- Is all of the user data collected by your app encrypted in transit? → N/A (no collection)
- Do you provide a way for users to request that their data is deleted? → N/A
- Data types: **none selected**
- Justification if queried: the app declares no `INTERNET` permission, so it is
  incapable of transmitting anything off-device.

Keep this consistent with `PRIVACY.md` — an inconsistency here is a common rejection.

---

## 3. Privacy policy URL

Required, because the app requests sensitive permissions. Must be reachable publicly and
linked in Console.

```
https://github.com/Local1stDotApp/XFiles/blob/main/PRIVACY.md
```

A GitHub blob URL is accepted and needs no hosting. (If you'd rather have a real page,
enable GitHub Pages on `docs/` and publish an HTML version — not required.)

---

## 4. Permissions declaration form

Four separately-reviewed items. A brand-new listing declaring all four is a
high-scrutiny profile, so the wording matters. One video can cover all of them.

### 4a. `MANAGE_EXTERNAL_STORAGE` (All files access)

Permitted use case to select: **file management** — "app's core purpose involves the
access, editing and management of files and folders outside of its app-specific storage".

The load-bearing field is *why SAF/MediaStore is insufficient*. Suggested text:

> XFiles is a general-purpose file manager. Its core function is to present the entire
> contents of shared storage as a single browsable tree — including files and folders
> created by other applications — and to perform bulk operations across arbitrary
> locations: multi-select copy, move, delete, rename, compress and extract.
>
> The Storage Access Framework cannot serve this purpose. SAF is built around the user
> manually selecting individual files, or granting one directory at a time; a file
> manager must show the whole hierarchy without the user first granting dozens of
> folders separately. Operations such as "select 40 files spread across 12 folders and
> move them into a new zip" are not expressible through per-file SAF pickers.
>
> MediaStore is also insufficient: it exposes only media collections and Downloads. It
> cannot see documents, archives, APKs, configuration files or arbitrary user-created
> folders, all of which a file manager must display and operate on.
>
> All files access is used solely to list, read, write, rename, move, delete, compress
> and extract files at the user's explicit direction. The app declares no INTERNET
> permission, so file contents cannot leave the device.

### 4b. `QUERY_ALL_PACKAGES`

Permitted use case: **file managers and browsers** (explicitly listed).

> XFiles includes an App manager that lists installed and system applications so the
> user can inspect an app's APK and split APKs as files, copy an APK out to share it,
> launch, install or uninstall it, and browse its declared components. Presenting a
> complete, browsable list of installed apps is the feature itself, so a filtered
> `<queries>` declaration cannot substitute — the set of packages to show is not known
> in advance.

### 4c. `REQUEST_INSTALL_PACKAGES`

Permitted use case: **file sharing and management** (explicitly listed).

> A file manager must be able to act on an APK the user has selected. XFiles offers an
> "install" action for `.apk` and split `.apks` files the user taps, whether copied out
> of the App manager or downloaded by another app. Installation is always initiated by
> an explicit user action and hands off to the system installer, which shows its own
> confirmation.

### 4d. `REQUEST_DELETE_PACKAGES`

Not clearly on the restricted list; may not prompt a declaration. If asked: uninstall is
offered from the App manager, is user-initiated, and delegates to the system uninstall
dialog.

---

## 5. Foreground service declaration — separate, easy to miss

Targeting API 34+, `dataSync` must be declared under **Policy → App content →
Foreground service types**, with its own description **and its own video link**. This is
a *second* obligation independent of §4.

> Copying, moving, compressing and extracting files can take minutes for large
> selections. XFiles runs these operations in a `dataSync` foreground service so they
> continue when the user leaves the app, with an ongoing notification showing progress
> and a Cancel action, plus a wake lock to prevent the device sleeping mid-write. If the
> work were deferred or interrupted, the user's copy or move would be left half-finished
> with files partially written — so it cannot be deferred to a background job. All
> processing is local; the app has no network permission.

---

## 6. The declaration video

**The existing `docs/assets/demo.mp4` is not sufficient for this.** It is a 14-second
marketing loop of one copy inside a purpose-made demo folder. Play's reviewer needs to
watch each declared permission being exercised in its real context. See the shot list in
§6a.

Requirements: unlisted or public YouTube link (preferred) or a cloud-storage link to an
mp4. Not age-restricted, embeddable, ads disabled.

### 6a. Shot list

Record one continuous take, roughly 2–3 minutes, unhurried — a reviewer is following
along. On-screen text or narration labelling each section helps.

1. **All files access, and why SAF won't do** *(the important one)*
   - Show the permission grant screen the app deep-links to on first run.
   - Browse into folders the app did not create — `Android/media/...`, `DCIM/`,
     `Download/`, a folder made by another app. Make it visibly *general* storage, not a
     demo folder.
   - Multi-select several files **across two different folders**, then move them
     somewhere else. This is the thing SAF cannot express.
   - Rename and delete a file in a third-party folder.
2. **Foreground service (`dataSync`)**
   - Start a large copy (hundreds of MB, so it lasts), press Home, pull down the shade,
     show the ongoing progress notification and its Cancel action, return to the app.
3. **App manager (`QUERY_ALL_PACKAGES`)**
   - Show the installed/system list, expand one app to its components and split APKs.
4. **APK install (`REQUEST_INSTALL_PACKAGES`)**
   - Copy an APK out, tap it, show the system installer dialog appearing. You can cancel
     at the dialog — the reviewer needs to see the hand-off, not a completed install.
5. **Root — keep it brief and last**
   - Show it is off by default in Settings, and that Read-only is on. Do not lead with
     this and do not dwell on it.

---

## 7. Store listing values

Everything below is in `fastlane/metadata/android/` so it stays version-controlled and
also feeds F-Droid/IzzyOnDroid.

| Field | Value / file |
|---|---|
| App name | `XFiles` |
| Short description (80) | `en-US/short_description.txt` — 77 chars |
| Full description (4000) | `en-US/full_description.txt` |
| Icon 512×512 32-bit PNG | `docs/assets/icon-store.png` (opaque, 6.8 KB) |
| Feature graphic 1024×500 24-bit PNG, no alpha | `docs/assets/feature-graphic.png` (55 KB) |
| Phone screenshots | `en-US/images/phoneScreenshots/` — 7, all ≤2:1, no alpha |
| Category | Tools |
| Contact email | required — pick one you'll read |
| Content rating | IARC questionnaire; expect **Everyone**. Answer "no" to all UGC/violence/ads questions. |
| Target audience | 18+ or 13+; **not** child-directed |
| Ads | Contains ads → **No** |
| App access | All functionality available without sign-in → no credentials needed |

Chinese listing copy is ready at `zh-CN/` if you add that locale.

---

## 8. Who does what

The Play Developer API **cannot create an app** — there is no such endpoint. Everything
in the left column is Console-only and must be done by a human. This matches the
prerequisites already written down in `nearby/flutter_app/PLAY_PUBLISHING.md` §2.

| Console-only (you) | Automatable (fastlane, after the app exists) |
|---|---|
| Create the app — name, default language, App, Free | — |
| **Choose the app signing key** (§0) — only possible at creation | — |
| Grant the service account access to *this* app (§8a) | — |
| Privacy policy URL, Data safety, Content rating, Target audience, Ads | — |
| Foreground service type declaration + its video (§5) | — |
| Permissions declaration form + its video (§4, §6) | — |
| **First AAB upload** — the Play API routinely refuses the first bundle of a new package | Every subsequent upload: `fastlane android deploy` |
| Submit for review | — |
| — | Store listing text/images/screenshots: `fastlane android upload_play metadata:true` |

### 8a. Service account access

Uploads reuse the Play service account already set up for the developer's other app; its
key file and address are kept out of this repository. Supply it to the lanes as
`PLAY_SERVICE_ACCOUNT_JSON` (raw JSON, for CI) or `PLAY_JSON_KEY_PATH` (a local path).

A service account's GCP project does **not** limit which apps it can manage — that is
controlled entirely by Play Console → *Users and permissions*. An account invited with
**app-specific** access must be granted access to each new app separately; one with
**account-level** permissions covers everything, present and future.

This bit us here: after XFiles was created, the API returned `403 The caller does not
have permission` until the service account was explicitly given access to the new app.
Note the failure modes are distinguishable and worth reading carefully:

```
404 Package not found  -> app not created yet, OR no binary ever uploaded
403 caller does not have permission -> app exists, service account lacks access
200                    -> ready
```

A brand-new package returns 404 even when the account is correct, because the Play API
does not know a package until its first binary lands.

Scope is checked in Play Console under *Users and permissions*, against the service
account's address.

### 8b. Order of operations

1. Create the app — **choose the app signing key here** (§0).
2. Confirm/extend service-account access (§8a).
3. Store listing: name, descriptions, icon, feature graphic, screenshots.
4. App content: privacy policy URL, data safety, content rating, target audience, ads,
   foreground service types (§5).
5. Upload the first AAB **by hand** to an Internal testing release.
6. Permissions declaration form (§4) with the video link (§6).
7. Submit. Expect **up to several weeks** while the sensitive-permission declarations are
   reviewed; the app sits in "pending publication".
8. From then on: `fastlane android deploy track:internal` (lanes in `fastlane/Fastfile`).

---

## 9. Things worth a second thought

- **App name.** "XFiles" is close to the "The X-Files" trademark. Trademark protection is
  class-specific and "X" + "Files" is descriptive for a file manager, so the risk is low
  — but a complaint is possible and Play acts on IP reports quickly. The `applicationId`
  can never change once published; the *display name* can.
- **X-plore resemblance.** The listing text already states the app is independent and
  shares no code or assets. Keep screenshots free of anything X-plore-derived.
- **targetSdk.** 37 is well ahead of the current floor (35) and the 2026-08-31 bump to
  36. No action needed.
- **Developer verification.** New Play-wide verification regime announced 2026-07-15;
  enforcement starts 2026-09-30 in some regions, global 2027. Registering through Play
  Console covers it.
- **Consider F-Droid/IzzyOnDroid in parallel.** The metadata in `fastlane/` already
  satisfies both. Neither restricts All files access or root, so they can carry the app
  while Play review runs.
