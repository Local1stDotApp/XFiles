<div align="center">

<img src="docs/assets/logo.png" width="104" alt="XFiles logo">

# XFiles

**一款离线、开源的 Android 文件管理器，沿用 X-plore 的操作方式** —— 双栏树形浏览、
压缩包当文件夹逛、应用管理、APK/AAB/XAPK 安装、root 与 Shizuku 访问，
界面采用 Material 3 Expressive。

[![Release](https://img.shields.io/github/v/release/Local1stDotApp/XFiles?include_prereleases&sort=semver&label=release)](https://github.com/Local1stDotApp/XFiles/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0--only-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](#下载)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)](#构建)
[![No network](https://img.shields.io/badge/network-none-success)](#权限与隐私)

[English](README.md) · 简体中文

<img src="docs/assets/demo.gif" width="300" alt="一台 OnePlus 7 Pro 上的一条完整演示：复制文件、查看已安装应用的组件和 APK 拆分包，再浏览 /data 下只有 root 才能访问的目录">

<sub>真机录制（OnePlus 7 Pro，Android 16），已加速。一条连起来：<b>文件复制</b> → <b>应用管理</b>（应用的组件与 APK 拆分包）→ <b>Root</b>（真实文件系统，<code>/data</code> 一览无余）。</sub>

</div>

---

## 缘起

- **X-plore 在 [Waydroid](https://waydro.id) 上用不了了**，得找个替代品。
- **现在是 LLM 时代** —— 工具不趁手，那就自己写一个。
- **能动 root 的软件，就该开源、彻底离线、什么都不收集。**
  XFiles 没有 `INTERNET` 权限，也没有任何统计埋点。

## 下载

到 [**Releases**](https://github.com/Local1stDotApp/XFiles/releases) 下载 APK：
**`vX.Y`** 是稳定版，**`nightly`** 是滚动预发布，`main` 每次推送都会重新构建。
需要 Android 8.0 及以上。首次启动请授予"所有文件访问权限"，App 会直接跳到系统设置页。

> **用 iPhone 或 iPad？** 试试 [**XFiles Pro**](https://apps.apple.com/app/id6796674895)。
> 同样的树形 + 双栏浏览搬到了 iOS 上，还补上了这个 Android 版刻意不做的联网部分：
> 带 SSH 终端的 SFTP、SMB/NAS、WebDAV、FTP(S)、S3、Google Drive、Dropbox、OneDrive、
> Jellyfin 和 Emby。它也会作为一个位置出现在系统「文件」App 里。它直连你添加的服务器，
> 没有中转，也没有统计。免费下载即可保存 3 个网络位置，Pro 解锁无限网络位置和 SSH
> 终端。它是一个独立的 App，不是从这个仓库构建的；目前中国大陆区 App Store 暂未上架。

## 功能

- **双栏树形浏览。** 两栏互不干扰，宽屏左右并排，手机上左右滑动切换。文件夹原地展开成树，
  图片和视频缩略图直接显示在树里，压缩包跟普通文件夹一样能点进去。
- **文件操作。** 复制、移动、压缩、解压的目的地就是另一栏，带进度、可取消，冲突时可选
  跳过 / 覆盖 / 两个都留。任务退到后台也继续跑，压缩会用满所有 CPU 核心。
  内置存储、SD 卡和 U 盘上的删除会先进回收站，之后可以还原。
- **各种存储卷。** 内置存储、SD 卡和 USB OTG 设备都是栏根。**添加位置**可以把其他应用
  提供的文档树固定成栏根（RSAF/rclone、CIFS Documents Provider、Nextcloud，或任何
  `DocumentsProvider`），XFiles 自己不联网也能访问网络存储。
  [操作说明](https://xfiles.local1st.app/zh/add-location)。
- **压缩包。** zip/jar/apk、7z、tar(.gz/.bz2/.xz)、rar 都能只读浏览，想解压就复制出来。
- **应用管理。** 已安装和系统应用，带图标和详情。展开一个应用，能看到它的
  activity / service / provider / receiver 以及真实的 `exported` / `enabled` 状态，
  还有 `base.apk` 和每个拆分 APK。支持启动、卸载、导出 APK，系统允许时还能启用/禁用组件。
- **软件包安装器。** APK、拆分包（`.apks` / `.apkm` / `.xapk`，连同 OBB）以及原始
  `.aab` 都能装。内置的 [bundletool](https://github.com/google/bundletool) 直接在手机上
  把 AAB 转成匹配本机的拆分 APK，不需要电脑，也不需要 Play 商店。
- **Root 与 Shizuku。** 有 `su` 就提供一个 **Root**（`/`）入口；没有 root 也可以通过
  [Shizuku](https://shizuku.rikka.app/) 获得 adb shell 级别的访问。两者都能打开
  `Android/data` 和 `Android/obb`；`/data` 本身需要 `su`。**Read-only** 开关默认打开，
  会挡掉所有特权写操作，让你能进去看，但没法把系统搞坏。
- **查看器。** 图片、文本（可编辑）、十六进制、音频，以及支持逐帧步进和拖动预览的视频播放器。
- **搜索。** 流式实时递归搜索，支持 `*` / `?` 通配符，也会搜进压缩包里。
- **用 XFiles 打开**压缩包、图片和视频。三个开关都需要手动开启，默认全关。
- **Material 3 Expressive**、动态取色、边到边显示，支持 18 种语言。

## 权限与隐私

没有网络权限，没有埋点，没有账号，没有广告。App 声明的每一个权限及其用途：

| 权限 | 用途 |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | 浏览和修改整个共享存储 |
| `READ_EXTERNAL_STORAGE`（≤ API 32）、`WRITE_EXTERNAL_STORAGE`（≤ API 29） | 老版本 Android 上的同等权限 |
| `QUERY_ALL_PACKAGES` | 应用管理要列出已安装的应用 |
| `REQUEST_INSTALL_PACKAGES`、`REQUEST_DELETE_PACKAGES` | 安装和卸载应用 |
| `POST_NOTIFICATIONS` | 长任务的进度通知 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`WAKE_LOCK` | 退到后台后让复制、移动或安装继续跑 |
| **`INTERNET`** | **没有申请。** App 根本没法访问网络 |

最后一行由操作系统强制保证。你可以去
[`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) 里看，
或者对 APK 跑一下 `aapt dump permissions` 验证。

## 构建

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17+ 和装了 platform 37 的 Android SDK。项目用 Kotlin 和 Jetpack Compose 写成，
material3 锁在 1.5 alpha 线以使用 Expressive API。另外用到 MVVM + StateFlow、Coil 3、
Media3、commons-compress 和 junrar、Shizuku，以及 `vendor/` 下 shade 过的 bundletool。
具体版本见 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)。

`app/src/main/java/app/local1st/files/` 下的主要目录：

```
core/fs/       XFileSystem 背后的各个文件系统：本地、压缩包、应用、root、SAF、回收站
core/fs/priv/  特权通道：su shell 与 Shizuku 用户服务
core/ops/      OperationEngine 和前台服务 OpsService
core/util/     软件包安装：PackageInstaller、AAB → APK、XAPK/OBB、签名
ui/browser/    栏的树状态机与列表行
ui/main/       双栏主界面与悬浮工具栏
ui/viewer/     图片、文本、十六进制、音频和视频查看器
```

[`release.yml`](.github/workflows/release.yml) 会在每次推送到 `main` 时构建签名 APK。
`versionCode` 取运行编号；提升 `version.properties` 里的 `versionName` 就会发布新的稳定版。
签名读取仓库 secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

## 许可证

[GPL-3.0-only](LICENSE)。一个能被交到 root 手里的文件管理器，
理应用一个能让后续所有副本都保持开放的许可证。你要是发布改过的 XFiles，请连源码一起发。

---

*这是一个受 X-plore File Manager 启发的学习/仿写项目，不含原作的任何代码或素材。*
