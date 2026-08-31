# 把网络位置加进 XFiles

XFiles **没有 `INTERNET` 权限**。系统禁止它自己开套接字，因此不能直连 SFTP、SMB、WebDAV、Google Drive 或任何远程服务器。

它可以浏览**另一个应用已经挂好、并通过安卓存储访问框架（系统文件夹选择器）公开的文件夹**。复制在 XFiles 里做；联网的是那个应用。

这是产品设计，不是漏做的功能。

## 你需要什么

1. **XFiles**（本应用）。
2. 一个会把你要的协议暴露成文档树、并出现在系统选择器里的 **文档提供程序应用**。
3. 到服务器的可用连接（Wi‑Fi、VPN、账号）。

主机名和密码只存在那个应用里，XFiles 不存。

## 选哪个伴侣应用？

选一个即可，只要它会出现在系统文件夹选择器里。

| 你想要… | 用 | 从哪装 |
|---|---|---|
| SFTP、SMB、WebDAV、Google Drive，以及 [rclone](https://rclone.org) 支持的大多数后端 | **[RSAF](https://github.com/chenxiaolong/RSAF)** | [F-Droid](https://f-droid.org/packages/com.chiller3.rsaf/)、GitHub Releases |
| 局域网 NAS 的 SMB / FTP / SFTP，界面更简单 | **[CIFS Documents Provider](https://github.com/wa2c/cifs-documents-provider)** | [F-Droid](https://f-droid.org/packages/com.wa2c.android.cifsdocumentsprovider/)、Play 商店 |
| Nextcloud / ownCloud 账号 | 官方 **Nextcloud** 或 **ownCloud** 应用 | Play / F-Droid |
| 顺便当 rclone 文件管理器（可选） | RCX 或 Round-Sync | F-Droid / GitHub |

需要「任意网络位置」时，优先 **RSAF**：它是 rclone 的文档提供程序，不是第二个文件管理器。远程配置一次，所有支持 SAF 的应用都能打开，包括 XFiles。

只连一台 NAS 或一台 SFTP 机器、不想碰 rclone 配置向导时，用 **CIFS Documents Provider** 更直接。

有 root 时也可以 `rclone mount` 进 `/sdcard`——XFiles 本来就能逛普通文件夹——但那是 Magisk/Termux 方案，不是 **添加位置** 要解决的事。

## 逐步：RSAF（rclone）

以常见的 SFTP、SMB、Drive 为例。RSAF 能配的 rclone 后端都是同一套步骤。

### 1. 安装 RSAF

从 [F-Droid](https://f-droid.org/packages/com.chiller3.rsaf/) 或 [GitHub Releases](https://github.com/chenxiaolong/RSAF/releases) 安装。需要 Android 9 或更新。

RSAF 需要网络权限。这是正常的：连服务器的是它。

### 2. 在 RSAF 里加远程

打开 RSAF，导入已有的 `rclone.conf`，或按应用内向导新增（流程接近 `rclone config`）。

例子：

- **SFTP** — 主机、端口、用户名、密码或密钥。
- **SMB** — 主机、共享名、用户名、密码。
- **WebDAV** — URL、用户名、密码。
- **Google Drive** — 按 RSAF 的 OAuth 提示走。rclone 公用的 Google 客户端 ID 即将失效；Drive 登录失败时，按 RSAF / rclone 文档改用自己的客户端 ID。

给远程起一个选择器里能认出来的短名（`nas`、`vps`、`drive`）。

### 3. 复制时别让系统杀掉 RSAF

提供程序被杀，传输就会断。RSAF 如果有常驻通知或「忽略电池优化」，长传之前打开。

### 4. 在 XFiles 里添加文件夹

1. 打开 XFiles。
2. 在首页根列表底部，或 **设置 → 位置**，点 **添加位置**。
3. 第一次会看到一段说明。继续后进入系统选择器。
4. 打开选择器的导航栏（☰ / 来源列表）。
5. 选 **RSAF**（或远程名），再选要挂进来的文件夹——共享根目录即可。
6. 用 **使用此文件夹** 确认（各厂商文案可能不同）。

XFiles 把这棵文档树钉成栏根。它没有真正的 `/storage/…` 路径，而是一个 `saf://` 位置。

如果误选了 XFiles 已经能看到的内部存储或 SD 卡，会变成**收藏**，而不是重复的根。

### 5. 和对面一栏互相复制

一栏打开网络位置，另一栏打开本地（或另一个位置）。复制、移动、压缩、解压都把对面一栏当目的地，和 U 盘一样。

进度、取消、跳过/覆盖/两个都留都照常。数据路径是：

`服务器 → RSAF → Binder → XFiles → 本地盘`

或反过来。

## 逐步：CIFS Documents Provider（SMB / SFTP）

1. 安装 [CIFS Documents Provider](https://f-droid.org/packages/com.wa2c.android.cifsdocumentsprovider/)。
2. 添加连接（主机、共享或路径、凭据）。SFTP、FTP、FTPS 也在同一个应用里。
3. 打开**通知**，降低复制中途被杀的概率。
4. 在 XFiles 里 **添加位置**，打开选择器侧栏，选 **CIFS Documents Provider**，再 **使用此文件夹**。

这个应用本身不管文件——它只对外发布共享。浏览和复制都在 XFiles 里做。

## 逐步：Nextcloud

1. 安装官方 Nextcloud 应用并登录。
2. 在 XFiles 里 **添加位置**。
3. 在选择器侧栏选 **Nextcloud** 和文件夹（账号根目录即可）。

会话留在 Nextcloud 应用里，XFiles 看不到密码。

## 选择器里看不到提供程序

- 确认伴侣应用已安装，并且至少建好了一个远程/连接。
- 在系统选择器里打开**左侧来源列表**，不要只看当前文件夹。第三方提供程序在那里。
- 部分厂商要把溢出菜单 /「显示内部存储」打开，才会列出额外来源。
- 卸载伴侣应用、清数据或撤销授权后，XFiles 上的根会显示 **不可用**。先在 XFiles 里移除，修好另一个应用后再加一次。

## 限制（有意为之）

- XFiles 始终没有网络权限。伴侣应用被停、休眠或断网，这个位置就不可用。
- 应用内图片/视频/十六进制查看器按本地文件来。网络条目请用 **打开方式…** / **分享**（提供程序的 content URI）。
- 在网络文件夹下搜索只匹配这一层子项，不会整盘扫 NAS。
- 远程上的 zip 不会当文件夹逛，先复制到本地。
- 「本地 ↔ 远程」的移动是复制再删除，不是服务器上的 rename。

## 隐私

授权一棵文档树，等于允许 XFiles **通过另一个应用** 读写那个文件夹。文件内容可能离开本机，是因为**你**把网络提供程序指到了服务器，不是 XFiles 自己开了连接。可以自己核对：清单里没有 `INTERNET` 权限。

## 相关链接

- [RSAF](https://github.com/chenxiaolong/RSAF) — 把 rclone 做成文档提供程序
- [CIFS Documents Provider](https://github.com/wa2c/cifs-documents-provider) — SMB / FTP / SFTP 提供程序
- [rclone 文档](https://rclone.org/docs/)
- [XFiles 源码](https://github.com/Local1stDotApp/XFiles)
