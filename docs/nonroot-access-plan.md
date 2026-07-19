# XFiles — 非 root 存储访问增强 · 实施计划

> 面向实现者（codex）的规格说明。

## 证据等级（每条结论的来源，2026-07-19 核验）

| 级别 | 含义 | 本文中的适用范围 |
|---|---|---|
| **【真机】** | 在 OnePlus 7 Pro (LineageOS, Android 16/API 36) 或 OPPO R11s (Android 9/API 28) 上实测 | 全部权限边界结论、SAF 不可行结论、SD 卡写入结论 |
| **【产物】** | 从 Maven Central 下载官方产物后 `javap`/解包核对 | Shizuku 版本冻结、`newProcess` 私有化、`IShizukuService` 可用性 |
| **【源码】** | AOSP / GitHub 一手源码或官方文档 | 黑名单实现、binder 上限、持久化授权上限、Play 政策 |
| **【转述】** | 调研 agent 声称验证但我未复核 | 三款商业 app 的内部实现方式（**仅影响「参考谁」，不影响任何架构决策**） |

**未经证实、实现前必须自行验证的**：无。原先唯一一条（P5 的 SD 卡写入）已于 2026-07-19 实测确认，见该节。

---

## 0. 结论先行

| 目标 | 现状 | 唯一可行手段 |
|---|---|---|
| `Android/data` `Android/obb` 浏览/读写 | ❌ 显示 "Cannot read data" | **Shizuku 或 root，无第三条路** |
| SD 卡根目录读写（API ≥ 30） | ✅ 已支持 | 已有 MANAGE_EXTERNAL_STORAGE |
| SD 卡写入（API 26–29） | ❌ 静默失败 | SAF（唯一真空档） |
| `/data/data` | 仅 root | root（Shizuku 被 SELinux 硬阻断） |

**不要做**：完整的 SAF 后端。app 已强制要求 `MANAGE_EXTERNAL_STORAGE`，SAF 的覆盖范围是其真子集（唯一例外见上表第三行）。

### 权限边界的根因（实测）

```
uid=2000(shell) groups=...,1078(ext_data_rw),1079(ext_obb_rw),...
```

`ext_data_rw` / `ext_obb_rw` 是 FUSE 层放行 `Android/{data,obb}` 的凭据，普通 app 的 uid 永不在其中，且**组成员资格在安装时固定、无法后天授予**。
`pm grant MANAGE_EXTERNAL_STORAGE` → `SecurityException: not a changeable permission type`（实测）。
`appops set ... allow` 可行但无增益——它给的就是用户在设置里点两下给的东西，同样不含 `Android/data`。

**推论：文件 I/O 必须发生在特权进程内部，不能靠给 app 自己授权。** 这条决定了整个架构。

### SELinux 硬边界（实测 AVC）

```
avc: denied { getattr } for path="/data/data/com.android.settings"
     scontext=u:r:shell:s0 tcontext=u:object_r:system_app_data_file:s0
avc: denied { getattr } for path="/data/media"
     scontext=u:r:shell:s0 tcontext=u:object_r:media_userdir_file:s0
```

`shell` 域被 `app_domain(shell)` 拉入 `appdomain`，因而获得 `sdcard_type`/`fuse`/`media_rw_data_file`——这是 `Android/data` 能通的原因；但 `/data/media` 本身标签是 `media_userdir_file`，不在授权列表内。所以 Shizuku 永远到不了 `/data/data`，只有 root 能。

---

## 1. 架构决策

### 决策 1：Shizuku 不引入新 scheme，作为 `root://` 的第二种传输

**否决方案**：新增 `shizuku://`。理由——会产生同一路径的第三种 id 表示，`XId.parent()`/`child()` 要再加分支，`AppsFileSystem.addExternalDir()` 的降级链要三选一，会话恢复/收藏夹里存的 id 在传输方式变化后失效。

**采纳方案**：抽出传输层，`root://` 语义从「通过 su 访问」放宽为「通过当前可用的特权通道访问」。

```
core/fs/priv/
  PrivilegedTransport.kt   ← 接口
  SuTransport.kt           ← 现 RootShell 平移
  ShizukuTransport.kt      ← 新增
  PrivilegedAccess.kt      ← 现 RootAccess 扩展：选择 + 能力位
```

```kotlin
interface PrivilegedTransport {
    val id: TransportId                       // SU | SHIZUKU
    val caps: Caps
    fun isAvailable(): Boolean
    fun exec(script: String): String          // 抛 IOException，message 面向用户
    fun execOneShot(script: String): String   // 长任务，不占共享锁
    fun openRead(path: String): InputStream
    fun openWrite(path: String): OutputStream
    fun reset()
}

data class Caps(
    val appPrivateData: Boolean,   // /data/data —— su=true, shizuku=false
    val wholeFilesystem: Boolean,  // 挂 "/" 根节点 —— su=true, shizuku=false
    val remount: Boolean,          // su=true, shizuku=false
    val otherUsers: Boolean,       // /storage/emulated/10 —— su=true, shizuku=false（实测）
)
```

`RootFileSystem` 的 shell 脚本与解析逻辑**完全不动**，只把 `RootShell.exec` 换成 `PrivilegedAccess.active.exec`。

**能力差异必须反映到 UI**，否则 Shizuku 用户会看到一堆 permission denied：
- `DefaultRootsRepository.paneRoots()` 里的 `/` 根节点，改为仅当 `caps.wholeFilesystem` 时挂出。
- Shizuku 模式下，特权通道只服务 `AppsFileSystem.addExternalDir()` 那条降级链（即 `Android/{data,obb}`）。
- 错误文案带上当前传输名：`"Shizuku 无法访问应用私有目录（需要 root）"`。

### 决策 2：分两阶段实现 Shizuku 传输

`Shizuku.newProcess()` 在 13.1.5 里已是 `private`，官方明示 API 14 移除。但**库自 2023-09 冻结在 13.1.5，Shizuku app 自 2025-05 冻结在 v13.6.0**——"API 14" 大概率不会存在。服务端 `Service.java` 的 transaction 7 仍然实现着 `Runtime.exec`。

因此：

- **P1（快速通路）**：绕过被私有化的包装，直接用 `dev.rikka.shizuku:aidl`（`:api` 的传递依赖）里已编译好的 `IShizukuService`：
  ```kotlin
  val svc = IShizukuService.Stub.asInterface(Shizuku.getBinder())
  val proc: IRemoteProcess = svc.newProcess(arrayOf("sh"), null, null)
  ```
  然后**照搬 `RootShell` 的常驻 shell 设计**（随机标记分帧、`2>&1` 子 shell、一次性进程跑长任务）。新增代码 ~200 行，`RootFileSystem` 零改动即可浏览 `Android/data`。
- **P3（正式通路）**：user service + 自定义 AIDL，替换 P1 的传输内部实现，接口不变。

P1 不是白做的临时代码：它验证权限流程、onboarding、能力位分派、UI 降级这些占工作量大头的部分，而这些在 P3 完全复用。

### 决策 3：`localPath` 缺口用 PFD 补，不落地缓存

`XEntry.localPath` 是缩略图 / 用其他应用打开 / 分享 / ExoPlayer / APK 安装的**唯一通道**，Shizuku 读出来的文件没有可用的 localPath。

- ❌ 拷贝到 cache 再用：大视频代价不可接受，且要管缓存生命周期。
- ✅ **`ParcelFileDescriptor`**：特权进程 `ParcelFileDescriptor.open()` 后经 binder 传给 app 进程。**fd 在传递时被复制并保留打开者的访问权限**——app 进程可以直接读它本来 `open()` 不了的路径，且之后所有 read/write/seek 走内核，无 binder 开销。

接入点（都已有同类先例可仿）：
- Coil：仿 `core/thumb/VideoThumb`/`AppIcon` 的自定义 model + Fetcher，从 PFD 建 `ImageSource`。
- ExoPlayer：自定义 `DataSource`，包 `ParcelFileDescriptor.AutoCloseInputStream`（可 seek）。
- 分享 / 用其他应用打开：需要 `content://` URI，用现有 FileProvider 无法表达。**降级：这两项在 Shizuku 路径下先禁用**，菜单项置灰并提示原因，别给一个会失败的按钮。

P1 阶段（shell 传输）拿不到 PFD，只能用 `cat` 流——不可 seek。所以 **P1 只保证浏览和复制，不保证缩略图与播放**，这是可接受的阶段性状态。

---

## 2. 分阶段任务

### P0 · 传输层抽取（纯重构，无新依赖，无行为变化）

1. 新建 `core/fs/priv/`，把 `RootShell` 平移为 `SuTransport`，实现 `PrivilegedTransport`。
2. `RootAccess` → `PrivilegedAccess`：持有 `active: PrivilegedTransport?`，`usable()` 语义不变，新增 `caps`。
3. `RootFileSystem` 所有 `RootShell.xxx` 调用改走 `PrivilegedAccess.active`。
4. `core/util/AppComponents.setEnabled` 同步改（它也直接用 `RootShell.exec`）。
5. `DefaultRootsRepository.paneRoots()` 的 `/` 根节点加 `caps.wholeFilesystem` 判据。

**验收**：root 开关、只读模式、`/` 浏览、`pm enable/disable`、`Android/data` 经 root 降级——全部与改动前逐一致。设备：OnePlus（有 su）。

### P1 · Shizuku 传输（shell 通路）

依赖（**pin 死，不要用动态版本**）：
```toml
shizuku = "13.1.5"
shizuku-api      = { module = "dev.rikka.shizuku:api",      version.ref = "shizuku" }
shizuku-provider = { module = "dev.rikka.shizuku:provider", version.ref = "shizuku" }
```

Manifest：
```xml
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:multiprocess="false"
    android:enabled="true"
    android:exported="true"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```
（`INTERACT_ACROSS_USERS_FULL` 是刻意的：没有普通应用持有它，等于把这个 exported provider 对第三方关死。）

实现要点：
- `ShizukuTransport` 持有常驻 `sh` 进程，复用 `RootShell` 的分帧协议（`__XF_DONE__$UUID` 随机标记——`stat %n` 会输出原始文件名字节，固定标记可被伪造）。
- **binder 未就绪时任何 `Shizuku.*` 调用都抛 `IllegalStateException`**，全部包 try/catch。
- 监听器：`addBinderReceivedListenerSticky` / `addBinderDeadListener` / `addRequestPermissionResultListener`，`onDestroy` 全部反注册。binder 死亡 → 作废句柄 + 标记特权根不可用；收到 binder → 重建。
- **检测 Shizuku 是否安装用 `getPermissionInfo` 而非包名**：权限在全局命名空间，不受 package visibility 过滤，也能识别 Shizuku 的分支版本，还省掉 `<queries>`。
  ```kotlin
  context.packageManager.getPermissionInfo(ShizukuProvider.PERMISSION, 0).packageName
  ```

**验收**（OnePlus，需先装 Shizuku 并用无线调试启动）：
- `Android/data/<pkg>` 能展开、能读文件、能复制出来；
- 复制到 `Android/data` 成功**或**给出明确失败提示（见风险 R1）；
- 关闭 Shizuku 服务后 app 不崩溃，该目录回落到「不可用」而非卡死。

### P2 · 权限与引导 UI

- `SettingsOverlay` 现有分组 `Appearance / Browsing / Root / About` → 把 `Root` 改为 `Access`，内含：特权通道选择（自动 / 仅 root / 仅 Shizuku / 关闭）、只读开关、当前状态行（`Shizuku 已连接 (uid 2000)` / `未运行` / `未安装`）。
- 引导页**绝对不要硬编码启动命令**。v13.6.0 起 starter 已改为 APK 内的原生二进制 `libshizuku.so`，旧文档里 `/sdcard/Android/data/moe.shizuku.privileged.api/start.sh` 那条命令是错的。正确做法：引导用户在 Shizuku app 里点「查看命令」自行复制。
- 排障页要写 OEM 坑：**ColorOS（OPPO/一加）需要关闭「权限监控」**、MIUI 需单独开「USB 调试（安全设置）」、Android 11+ 要开「停用 adb 授权超时」、Shizuku 需加入电池优化白名单否则配对发现失败。

### P3 · User service + AIDL（正式通路）

```kotlin
Shizuku.bindUserService(
    Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, PrivFileService::class.java.name))
        .daemon(false)                       // 默认是 true！文件管理器必须传 false
        .processNameSuffix("privfs")         // 必填，否则 NPE
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)   // 版本号变化会销毁旧服务重建
        .tag("xfiles-privfs"),               // 必须稳定，否则 R8 改名后身份错乱
    connection)
```

AIDL 必须包含 Shizuku 保留槽位，且 unbind **不会**杀进程：
```aidl
interface IPrivFileService {
    void destroy() = 16777114;
    ParcelFileDescriptor open(String path, int mode) = 1;
    ...
}
```
```java
@Override public void destroy() { System.exit(0); }
```

数据面设计：
- **内容走 PFD**，一次 binder 换一个 fd，之后零 IPC。
- **目录列表走分片或流式**。binder 单事务软上限 `IBinder.MAX_IPC_SIZE = 64KB`，进程内所有并发事务共享 ~1MB（`BINDER_VM_SIZE`），5 万条目录必炸。两种成熟做法：`ParceledListSlice` 式分片（写满 64KB 就嵌回调 binder 让对端拉下一片），或返回一个 `RemoteInputStream` 把条目序列化进去。
- **异常不能直接抛过 binder**（`Parcel.writeException` 只认几种类型）。要么每个方法带 `out ParcelableException`，要么统一编码进一种异常的 message。必须显式选一种，默认失败模式是「一个什么都没说的堆栈」。
- 服务侧拿到的 `Context` 是残缺的（`registerReceiver`、`getContentResolver` 都不可用）。需要完整 Context 时用 `ActivityThread.currentActivityThread().getSystemContext().createPackageContext(...)` 重建。
- **建议：一套 service 实现配两个启动器**（su 与 Shizuku），FV File Manager 就是这么做的，避免两份几乎相同的特权代码。

### P4 · localPath 缺口（Coil fetcher + ExoPlayer DataSource）

见决策 3。依赖 P3 的 PFD 通路。

### P5 · SAF 补 API 26–29 的 SD 卡写入（低优先级，可不做）

**【真机】已确证问题存在**（2026-07-19，OPPO R11s / Android 9 / API 28，`WRITE_EXTERNAL_STORAGE` 与 `READ_EXTERNAL_STORAGE` 均 `granted=true`）：

设备原本没有 SD 卡，用 `sm set-virtual-disk true` + `sm partition disk:7:0 public` 造了一个 vfat 便携卷 `86C4-1D0B`（挂载于 `/storage/86C4-1D0B`，sdcardfs 之上）。App 正确识别并显示为「SD卡 511 MB」，`kindOf` 也判对了 `VOLUME_SD`。

| 组 | 目标 | 操作序列 | 结果 |
|---|---|---|---|
| 实验组 | `/storage/86C4-1D0B` | 新建文件夹 `sdtest_A` | ❌ **"Cannot create folder sdtest_A in SD卡"**，文件系统确认未创建 |
| 对照组 | `/sdcard` | **完全相同**的自动化序列，`sdtest_B` | ✅ 成功，`/sdcard/sdtest_B` 存在 |

对照组存在的意义：排除自动化点击失误（第一次尝试确实因键盘遮挡误触过）。同一 app、同一时刻、同一操作流，仅目标卷不同 → **API 26–29 上 `WRITE_EXTERNAL_STORAGE` 不覆盖次级卷，结论成立**。

（`sm set-virtual-disk` 是无需 root 的官方测试手段，就是给这种场景准备的。测试后已 `set-virtual-disk false` 并清理，设备恢复原状。注意：关闭虚拟磁盘瞬间 `emulated` 会短暂 unmounted，几秒后自愈。）

要点：
- `DocumentFile` **不要用**——androidx 自己的文档就说它开销大。`TreeDocumentFile.listFiles()` 只投影 `COLUMN_DOCUMENT_ID`，之后每个属性访问都是一次 `ContentResolver.query`，且每次查询是两跳 IPC。实测 ~600 个文件走 `listFiles()+getName()` 约 30 秒。
- 正确做法：`DocumentsContract.buildChildDocumentsUriUsingTree()` + 一次性投影 8 个列。
- `selection` / `sortOrder` 参数**在 `FileSystemProvider` 里根本没被读**，一律客户端排序过滤。
- `DocumentsContract.copyDocument()` **在 `ExternalStorageProvider` 上必抛 `UnsupportedOperationException`**（`FLAG_SUPPORTS_COPY` 从不设置）。复制必须自己用两个 fd 实现。
- 持久化授权上限 **512 条，超出静默 LRU 淘汰、无回调**。只在卷/子树根持久化，别按目录存。

---

## 3. 风险登记

| # | 风险 | 应对 |
|---|---|---|
| **R1** | **Android 16 + 新版 Google Play 系统更新后 `/Android/data` 写入失效**。[Shizuku #1807](https://github.com/rikkaapps/shizuku/issues/1807) —— 【真机核验 API】标题 *"Latest Google Play System Update breaks file explorer access through Shizuku to /android/ folder"*，状态 **open**，创建 2026-01-23，最后更新 2026-05-01。**但只有 1 条评论**，所以「MiXplorer/MT/SD Maid 同时中招」是报告者单方陈述，我未独立复核。我在 LineageOS 上实测写入成功，但该 ROM 不带 Google 的 MediaProvider APEX。 | **按「写可能失败」设计**：写操作前探测，失败给明确文案而非静默。读路径不受影响。实现时应在带 GMS 的 Android 16 设备上复测。 |
| R2 | 库已冻结 | 【产物核验】Maven `maven-metadata.xml` 显示 `latest=13.1.5`、`lastUpdated=20230921`；`api-13.1.5-sources.jar` 中 `newProcess` 确为 `private static`，注释写明计划在 API 14 移除；`aidl-13.1.5.aar` 的 `classes.jar` 中 `IShizukuService.newProcess(String[],String[],String)` 为 **public**，`IRemoteProcess` 提供 PFD 形式的三个流 + `waitFor` → **P1 方案前提成立**。pin 死版本；**自己 vendor 一份 AIDL** |
| R3 | Shizuku 重启后失效（非 root 时每次重启都要重新拉起；Android 13+ 且连可信 WLAN 才有自动启动） | binder 死亡监听 + 明确的「未运行」状态，不要表现为「目录空」 |
| R4 | 用户设备是 ColorOS（你的 R11s 就是） | 排障页必须写「关闭权限监控」 |
| R5 | API 35+ `dataSync` 前台服务有 **24 小时内累计 6 小时**上限 | 这条**现在就影响长时间复制任务**，与 Shizuku 无关，建议顺带排查 `OpsService` |
| R6 | Play 政策 | 无风险。Solid Explorer / FV / Total Commander 都在 Play 上架且内含 Shizuku。真正的门槛是 All files access 声明表（文件管理器是白名单类目），审核可能数周 |

---

## 4. 测试

**项目当前零测试**（无 `src/test`、无 `src/androidTest`、无测试依赖）。特权 I/O 是最不该裸奔的部分，建议至少建立设备冒烟脚本。

设备矩阵：

| 设备 | 版本 | 角色 |
|---|---|---|
| OnePlus 7 Pro `48fc3d44` | Android 16 / API 36，有 `/product/bin/su` | 主力：root 与 Shizuku 双通道回归 |
| OPPO R11s `925602f` | Android 9 / API 28，无 su | 回归：API 28 上 `Android/data` 本来就直通，验证不被新代码破坏；P5 的 SD 卡场景 |

注意 R11s 上 Shizuku 无意义（该版本 `Android/data` 本就可直接访问），别在那台上验证 P1/P3。

每阶段验收项见各阶段小节。建议每阶段一个独立 commit，我逐段 review——重点看 binder 生命周期、异常跨进程传递、fd 泄漏、以及能力位分派是否覆盖了 Shizuku 够不到的路径。
