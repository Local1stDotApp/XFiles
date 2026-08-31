# Add a network location to XFiles

XFiles has **no `INTERNET` permission**. Android itself blocks it from opening a socket, so it cannot talk to SFTP, SMB, WebDAV, Google Drive, or any other remote server.

It *can* browse a folder that **another app** has already mounted and exposed through Android’s Storage Access Framework (the system folder picker). You copy in XFiles; the other app is what goes online.

This is the intended design, not a missing feature.

## What you need

1. **XFiles** (this app).
2. A **document-provider app** that speaks the protocol you care about and shows up in the system picker.
3. A working connection to the server (Wi‑Fi, VPN, credentials).

XFiles never stores hostnames or passwords. Those stay in the other app.

## Which companion app?

Pick one. You only need a provider that appears in the Android folder picker.

| If you want… | Use | Where |
|---|---|---|
| SFTP, SMB, WebDAV, Google Drive, and most of what [rclone](https://rclone.org) supports | **[RSAF](https://github.com/chenxiaolong/RSAF)** | [F-Droid](https://f-droid.org/packages/com.chiller3.rsaf/), GitHub Releases |
| SMB / FTP / SFTP on a LAN, with a small dedicated UI | **[CIFS Documents Provider](https://github.com/wa2c/cifs-documents-provider)** | [F-Droid](https://f-droid.org/packages/com.wa2c.android.cifsdocumentsprovider/), Play Store |
| Your Nextcloud / ownCloud account | The official **Nextcloud** or **ownCloud** Android app | Play Store / F-Droid |
| rclone as a full file manager (optional) | RCX or Round-Sync | F-Droid / GitHub |

**RSAF** is the usual choice when you want “any network location”: it is a document provider for rclone, not a second file manager. Configure the remote once; every SAF-aware app can then open it, including XFiles.

**CIFS Documents Provider** is simpler if you only need a NAS share (SMB) or a single SFTP box and do not want rclone’s config wizard.

Root/`rclone mount` into `/sdcard` also works — XFiles already lists ordinary folders — but that is a Magisk/Termux setup, not what **Add location** is for.

## Walkthrough: RSAF (rclone)

These steps are for a typical SFTP, SMB, or Drive remote. The same pattern applies to any rclone backend RSAF can configure.

### 1. Install RSAF

Install from [F-Droid](https://f-droid.org/packages/com.chiller3.rsaf/) or [GitHub Releases](https://github.com/chenxiaolong/RSAF/releases). Android 9 or newer.

RSAF needs network access. That is expected: it is the process that talks to the server.

### 2. Add a remote in RSAF

Open RSAF and either import an existing `rclone.conf` or add a remote from scratch (the in-app flow matches `rclone config`).

Examples:

- **SFTP** — host, port, username, password or key.
- **SMB** — host, share, username, password.
- **WebDAV** — URL, username, password.
- **Google Drive** — follow RSAF’s OAuth prompts. rclone’s shared Google client ID is being revoked; if Drive sign-in fails, use your own client ID as described in RSAF / rclone docs.

Give the remote a short name you will recognize in the picker (`nas`, `vps`, `drive`).

### 3. Keep RSAF alive while you copy

Transfers die if Android kills the provider. If RSAF offers a persistent notification or “ignore battery optimizations”, turn it on before a long copy.

### 4. Add the folder in XFiles

1. Open XFiles.
2. Tap **Add location** on the home screen (bottom of the root list) or in **Settings → Locations**.
3. The first time, a short explanation appears. Continue to the system picker.
4. Open the picker’s navigation drawer (☰ / the list of sources).
5. Select **RSAF** (or the remote name), then the folder you want — the share root is fine.
6. Confirm with **Use this folder** (wording varies by Android version).

XFiles pins that tree as a pane root. It does not get a real `/storage/…` path; it is a `saf://` location.

If you accidentally pick internal storage or an SD card that XFiles already shows, it becomes a **favorite** instead of a duplicate root.

### 5. Copy like any other folder

Put the network location in one pane and local storage (or another location) in the other. Copy, move, zip, and extract use the other pane as the destination, same as a USB drive.

Progress, cancel, and skip/overwrite/keep-both work as usual. Bytes flow:

`server → RSAF → Binder → XFiles → local disk`

or the other way around.

## Walkthrough: CIFS Documents Provider (SMB / SFTP)

1. Install [CIFS Documents Provider](https://f-droid.org/packages/com.wa2c.android.cifsdocumentsprovider/).
2. Add a connection (host, share or path, credentials). SFTP, FTP, and FTPS are in the same app.
3. Enable the **notification** option so Android is less likely to kill it mid-transfer.
4. In XFiles, **Add location**, open the picker drawer, choose **CIFS Documents Provider**, then **Use this folder**.

There is no file manager inside that app — it only publishes the share. Browsing and copying happen in XFiles.

## Walkthrough: Nextcloud

1. Install the official Nextcloud app and sign in.
2. In XFiles, **Add location**.
3. In the picker drawer, choose **Nextcloud** and the folder (account root is fine).

The Nextcloud app holds the session; XFiles never sees the password.

## If the provider does not appear

- Confirm the companion app is installed and you have created at least one remote/connection.
- In the system picker, open the **left-hand list of sources**, not only the current folder. Third-party providers live there.
- Some OEMs hide extra providers until you tap a overflow / “Show internal storage” control.
- Uninstalling the companion app, clearing its data, or revoking the grant makes the XFiles root show **Not available**. Remove it in XFiles and add it again after you fix the other app.

## Limits (by design)

- XFiles still has no network permission. If the companion app is stopped, sleeping, or offline, the location is unavailable.
- In-app image/video/hex viewers expect a local file. For a network entry, use **Open with…** / **Share** (the provider’s content URI).
- Search under a network folder matches that folder’s children only — it will not walk a whole NAS.
- A zip sitting on the remote is not browsed as a folder until you copy it locally.
- Move across “local ↔ remote” is copy + delete, not a server-side rename.

## Privacy

Granting a document tree lets XFiles read and write **that folder** through the other app. File bytes may leave the device because **you** pointed a network provider at a server, not because XFiles opened a connection. You can verify XFiles itself cannot: there is no `INTERNET` permission in its manifest.

## Related

- [RSAF](https://github.com/chenxiaolong/RSAF) — rclone as a document provider
- [CIFS Documents Provider](https://github.com/wa2c/cifs-documents-provider) — SMB / FTP / SFTP provider
- [rclone documentation](https://rclone.org/docs/)
- [XFiles source](https://github.com/Local1stDotApp/XFiles)
