# Privacy Policy — XFiles Pro (Apple)

**Last updated: 2026-08-13**

XFiles Pro (`app.local1st.files`) is a dual-pane file manager for iPhone and iPad.

## The short version

- **Local file management** does not require an account and does not send your files to us.
- **Optional network features** (FTP/FTPS/SFTP/WebDAV/SMB, Google Drive, Jellyfin, Emby, SSH terminal) only run when **you** add a connection or sign in.
- We do **not** run ads, analytics SDKs, or crash-reporting SDKs that phone home.
- Credentials and OAuth tokens for remotes stay on your device (Keychain / app storage).

## Data we collect

We do **not** operate a XFiles backend that receives your file contents, browse history, or analytics from the app.

## Data on your device

| What | Why | Leaves your device? |
|---|---|---|
| Local files and folders you open | Core file management | Only if **you** copy/upload them to a remote **you** configured |
| App preferences | Settings (hidden files, dual-pane, etc.) | No |
| Remote connection settings & secrets | Network locations you add | Secrets stay local; traffic goes only to hosts/services you choose |
| Google Drive OAuth tokens | Sign in with Google when you use Drive | Tokens stored on device; used with Google’s APIs per Google’s policies |

## Network

Network access is used only for features you enable, for example:

- Connecting to servers you configure (FTP, SFTP, SMB, WebDAV, media servers)
- Google Sign-In / Google Drive API when you choose Google Drive
- Loading content from those destinations

The Free Android product documented separately has no `INTERNET` permission; the Apple Pro build includes remotes and therefore uses the network for those features.

## Third parties

If you use Google Drive, Google processes authentication and Drive data under Google’s terms and privacy policy. Other remotes are servers you choose (your NAS, VPS, etc.).

## Children

XFiles Pro is a general-purpose utility and is not directed at children.

## Retention and deletion

XFiles does not maintain a developer-operated account or server-side user profile. Saved connection details remain on your device until you remove the Network Location or delete the app, subject to backups managed by Apple. Data held by a third-party service is controlled through that service.

## Contact

Developer: XFiles Pro

Questions or privacy requests: [nearby@local1st.app](mailto:nearby@local1st.app)

Support information: [XFiles Pro Support](APPLE_SUPPORT.md)
