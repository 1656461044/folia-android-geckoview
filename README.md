# Folia · GeckoView 版（Android）

给 [Folia](https://github.com/chthollyphile/folia-major) 做的一个**自带浏览器内核**的 Android 外壳。

原版是 Capacitor + 系统 WebView；这个版本把渲染引擎换成 **GeckoView**，因此不再依赖设备自带的 WebView 版本。

---

## 制作初衷

起因是车机。

我的车机（BYD DiLink 5.0，Android 12）系统 WebView 是 Chromium 95，厂家定制、无法升级，第三方 WebView（Chrome / Cromite 等）又不在 ROM 白名单里，装得上也替换不了。

原版 Folia 的 APK 能装上、能跑，但**界面不刷新**——表现为一直停在一层模糊背景上。查下来：

| 检查项 | 结果 |
|---|---|
| `dumpsys webviewupdate` | Chromium 95.0.4638.74，系统签名 |
| JS 是否执行 | 正常。`FoliaNative` 桥调用、`recordPlaybackHeartbeat` 每 10s 回传、React 与全部 Store 初始化完成 |
| DOM 结构 | 完整。书库 / 歌单 / 推荐 / 探索 / 专辑 / 本地 全部存在 |
| SurfaceFlinger | 窗口 `isOpaque=1`、`VisibleRegion` 覆盖全屏、`activeBuffer` 有真实帧缓冲 |

也就是说系统认为它在正常显示，屏幕却不是它的内容。**这是车机合成/刷新链路的问题，与内核版本、CSS 特性、Folia 业务代码都无关。**

所以有了这个项目：**用一个自带引擎的壳，绕开设备侧的显示问题，同时彻底摆脱对系统 WebView 的依赖。**

后来在手机上也能正常用，竖屏、后台播放、媒体键、本地音乐都通了。

---

## ⚠️ 关于发布形式与许可证（请先读）

Folia 上游（[chthollyphile/folia-major](https://github.com/chthollyphile/folia-major)，参考实现 [whycfawa/folia-android](https://github.com/whycfawa/folia-android)）采用 **AGPL-3.0**。

本仓库包含两部分：

| 部分 | 来源 | 许可 |
|---|---|---|
| `gecko/`（Java 壳、`folia-shim.js`、构建脚本） | 本项目新写 | **AGPL-3.0**（见 `LICENSE`） |
| `gecko/app/src/main/assets/web/`（1919 个文件 / 28.4 MB） | 上游 AGPL 项目的**构建产物** | **AGPL-3.0**，版权归上游作者 |

**因此：**

1. 本仓库整体以 **AGPL-3.0** 发布。你要是分发改过的版本，同样得开源，并提供完整对应源码。
2. `assets/web/` 是上游编译后的前端包，不是我们的代码。它的著作权属于 Folia 作者。
   我没有、也无权改变它的许可。
3. **如果你只是想自己用**，最干净的做法是**不下载这里的 web 资源**，改用自己的 Folia APK 提取：
   见 [`docs/EXTRACT_ASSETS.md`](docs/EXTRACT_ASSETS.md)，一条命令的事。
4. 本仓库**不包含任何私钥、账号、Token**。签名用的 `*.keystore` 与 `keystore.properties`
   都在 `.gitignore` 里排除；想自己签名请参考 `gecko/keystore.properties.example`。
   仓库 `release/` 下那个 APK 用的是开发期自签名证书，**不代表任何官方身份**。

---

## 修改明细（相对原版）

### 1. 渲染引擎

| | 原版 | 本版 |
|---|---|---|
| 引擎 | 系统 WebView（本机 Chromium 95） | **GeckoView 139.0.20250609112858** |
| 版本约束 | 跟随系统 | 完全自持，minSdk 26 即可 |
| APK 体积 | 小（只含 web 资源） | **95.11 MB**（内含 `libxul.so`） |

选了 GeckoView **139** 而不是最新的 160：160 的 AAR 元数据要求 `compileSdk ≥ 37` 与 `AGP ≥ 9.1`，本机只有 API 34。139 配 `compileSdk 34 / AGP 8.7.3` 可用，代价是必须 `exclude` 它带来的新版 `androidx.core` / `media3` 再 `force` 回旧版本（见 `app/build.gradle`）。

### 2. 原生桥：从 `addJavascriptInterface` 改为本地 HTTP

GeckoView **没有** `addJavascriptInterface`，也**没有** `evaluateJS`。原本走 WebExtension `nativeMessaging` 来补，但实测：

- 安装本地扩展强制要求签名 → `InstallException: -5`
- MV2 的 `nativeMessaging` 被判定 `Invalid extension permission`

于是改成**本地 HTTP 双向通道**：

```
页面  --POST /__native/call-->  LocalAssetServer  -->  NativeBridge  -->  各能力实现
页面  <--GET /__native/poll--   反向事件队列（返回键、系统音量、媒体键）
```

### 3. 跨站请求必须劫持 `window.fetch`

GeckoView 里页面直接 `fetch` 跨站资源会被 CORS 拦成
`NetworkError when attempting to fetch resource`，请求根本到不了原生层。

这是最初「连不上 API 服务器」的真正原因——不是内核老、不是 CSS、不是证书。

解法：`folia-shim.js` 里劫持 `window.fetch`，把 43 个白名单域名转给原生 OkHttp 代发，其余仍走浏览器原生 fetch。

### 4. 音量：必须软件接管（这条最有意思）

GeckoView 的音频走 cubeb → AAudio，在我的车机上打开的是 **MMAP 直通通道**：

```
Output thread ... name AudioMmapOut_12, type 5 (MMAP_PLAYBACK):
  Attributes: content type 2 usage 1
  Stream type: 3   Stream volume: 1.000000   ← 恒为 1.0
```

同一时刻 `dumpsys audio` 里 `STREAM_MUSIC` 已被旋钮从 8/39 调到 **0/39（Muted: true）**，而 MMAP 线程的 `Stream volume` 依然是 1.0。

**结论：这条通道绕开了 AudioFlinger 混音器，系统音量对它完全无效。**

（WebView 用普通 AudioTrack，走混音器，所以原版没这个问题。）

试过引擎层换后端（`media.cubeb.backend: opensl`）**无效**，输出线程仍是 `AudioMmapOut_*`。所以只能由页面侧做软件增益：

- **基准值 / 系统音量分离**：页面设的是 `baseVolume`，真实下发 `base × 系统音量`，getter 返回 `base`。
- **原始描述符只抓一次**，在 `defineProperty` 之前存成常量——否则 `getOwnPropertyDescriptor` 拿到的是我们自己的 setter，会自我递归、每按一次键乘一遍，表现为「音量键升也降、降也降，一路掉到 0」。

### 5. 后台播放与媒体键

| 能力 | 实现 |
|---|---|
| 后台存活 | `FoliaPlaybackService` 前台服务 + `WakeLock` / `WifiLock` |
| 系统媒体键 | `MediaSession` + `MediaSession.Callback` |
| 音频焦点 | `AudioManager.requestAudioFocus`（车机据此判定「当前播放器是谁」） |
| 通知栏 | `MediaStyle` 三键（上一首 / 播放暂停 / 下一首） |

**顺带查清了 BYD 车机的媒体方案，不需要任何私有权限：**

反编译 `com.byd.mediacontroller` 后确认，它的链路是

1. `MediaSessionManager.getActiveSessions(null)` 发现所有播放器
2. `AudioManager.getCurrentAudioFocusPackage()`（BYD 扩展）取当前持焦点的包名
3. 按包名匹配到 `MediaController`，然后走**标准 `TransportControls`**：`play()` / `pause()` / `skipToNext()` / `skipToPrevious()` / `seekTo()`

所以第三方应用只要**持音频焦点 + 有活跃的 MediaSession**，就能被识别和遥控。

实测 `dumpsys media_session`：

```
Media button session is top.izuna.foliagecko/FoliaPlayback
state=PlaybackState {state=3, position=...}
```

`keyevent 87/85/88` 全部命中。

> 它另有官方 SDK（bind `com.byd.mediacontroller.thirdapp.controller`）可做 RemoteViews 媒体卡片 / 歌单 / 登录态上报，属可选增强，媒体键和后台播放用不到。

### 6. 本地音乐：零复制的 A + B 方案

一开始我做的是「SAF 选目录 → 全部复制到私有目录」。实测用户曲库后立刻发现不行：

| | 数值 |
|---|---|
| `/sdcard/Music` 里 flac | 55 个 |
| 总大小 | **3.0 GB** |
| 最大单文件 | 268 MB |

复制等于同样的内容在设备上存两份。改成**两级、都不复制**：

- **A｜MediaStore 命中**：按「文件名 + 大小」反查系统媒体库，命中就返回 `/media/<id>`，零复制零授权（绝大多数手机音乐走这条）
- **B｜SAF 命中**：文件不在媒体库（SD 卡、下载目录、云盘），`takePersistableUriPermission` 拿持久读权限，映射存 `SharedPreferences`，播放时 `/audio/<ref>` 现场 `openInputStream` 流式转发

配套还修了三件事：

- `deleteImportedAudio` —— **ref 前缀必须是 `imported-`**，因为 web 端删除逻辑写死了 `startsWith('imported-')`。前缀不对会静默失效。
- 删除语义严格区分归属：`imported-ms-*` 只移除映射，**绝不删用户原文件**；`imported-uri-*` 撤销 URI 权限；`imported-file-*` 才删私有副本。
- 启动时 `sweepOrphans()` 清历史副本（实测清掉 62 个文件，归还约 3 GB）。

### 7. 屏幕方向与刘海

原版写死 `sensorLandscape`，手机上被强行掰横屏。

现在：**默认 `FULL_SENSOR`，不锁方向**（车机屏幕也能转），只有在设置里显式开启「手机适配」并选了方向才锁。

刘海统一 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT` —— 因为 **GeckoView 不支持 `env(safe-area-inset-*)`**，页面没法自己避让，只能靠系统留安全区。无刘海设备上 `DEFAULT` 与 `SHORT_EDGES` 表现一致。

---

## 实现方法（关键设计）

### 端口固定为 47831

页面 origin 是 `http://127.0.0.1:<port>`，而 `localStorage` / `IndexedDB` / Cookie **按 origin 隔离**。端口一变，登录态和设置全丢。

最初用 47821，后来被迫改到 47831 —— 因为 **PWA 的 Service Worker 把内联了 shim 的 `index.html` 缓存了**。

这个坑值得单独说：Folia 是 PWA，`registerSW.js` 注册了 workbox 的 `/sw.js`，precache 里带 `index.html`，而 shim 就内联在里面。SW 一旦装上，之后**换 APK、加 `?v=` cache-buster 全都没用**，页面永远跑旧 shim。我当时连续几版改动都没生效，`platform` 一直停在 `web`。

现在三层防护：

1. 换端口 = 换 origin，绕开被污染的旧缓存
2. shim 在页面最早期把 `navigator.serviceWorker.register` 掐掉
3. `geckoview-config.yaml` 里关 `dom.serviceWorkers.enabled`

### `androidBridge` 哨兵，而不是伪造 `getPlatform`

Capacitor 自己的平台判定是：

```js
const getPlatformId = (win) =>
  win?.androidBridge ? 'android'
  : win?.webkit?.messageHandlers?.bridge ? 'ios' : 'web';
```

所以**不能**去伪造 `getPlatform` —— 页面模块 `createCapacitor()` 里会写 `cap.getPlatform = ...` 覆盖回去，而且 ES module 是严格模式，属性被锁成不可写会**直接抛 TypeError 让整包初始化挂掉**。

正确做法是把 `window.androidBridge` 这个哨兵补上，让页面自己算出 `android`。这一条同时决定两件事：

- 本地库可用性判定 → 否则「本地歌曲」入口置灰
- 原生播放桥是否安装 → 否则没有 `updatePlaybackState`，也就没有 MediaSession、后台播放、媒体键

### shim 的注入方式

`LocalAssetServer` 在返回 `index.html` 时，把 `folia-shim.js` 内联进 `<head>` 最前面，保证先于所有 Folia 模块执行。

---

## 使用方法

### 装现成 APK

```bash
adb install -r Folia-GeckoView-arm64.apk
adb shell am start -n top.izuna.foliagecko/.MainActivity
```

要求：`arm64-v8a`、Android 8.0（SDK 26）以上。

本地音乐需要媒体读取权限：

```bash
# Android 13+
adb shell pm grant top.izuna.foliagecko android.permission.READ_MEDIA_AUDIO
# Android 12 及以下
adb shell pm grant top.izuna.foliagecko android.permission.READ_EXTERNAL_STORAGE
```

也可以进应用后在「本地歌曲 → 添加文件夹」时由系统弹窗授权。

### 从源码构建

需要 JDK 21、Android SDK `platform-34` + `build-tools 34.0.0`、Gradle 8.9。

**脚本不写死任何路径**，用环境变量告诉它装在哪：

```powershell
$env:JAVA_HOME         = "C:\Program Files\Java\jdk-21"
$env:ANDROID_SDK_ROOT  = "C:\Android\Sdk"
$env:GRADLE_HOME       = "C:\gradle-8.9"

cd gecko
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Release
```

也可以直接传参：`-JavaHome ... -AndroidSdk ... -GradleHome ...`。
缺依赖或 SDK 组件不全时脚本会明确告诉你缺什么然后退出，不会半路失败。

产物：`gecko/app/build/outputs/apk/release/app-release.apk`（约 95 MB）

要生成已签名的包，先把 `gecko/keystore.properties.example` 复制成
`gecko/keystore.properties` 并填好你自己的 keystore。不做这一步也能构建，
只是产物是未签名的。

更详细的交接流程见 [`docs/HANDOFF_PROMPT.md`](docs/HANDOFF_PROMPT.md)。

### 验证是否正常

```bash
adb logcat -d | grep -E "DIAG|FoliaGeckoBridge: call"
```

正常应出现：

```
DIAG shim-installed :: v=android-0.1.x ... androidBridge=true takeover=true
DIAG local-env :: platform=android isNative=true hasPlayback=true
DIAG selftest-qq :: status=200
FoliaGeckoBridge: call FoliaNative.recordPlaybackHeartbeat
```

四条齐全说明：注入层活了、跨站请求经原生代发成功、原生桥往返正常。

---

## 已知问题

### 1. 「猜你喜欢」电台卡片点击无明显反应（未确认）

「每日推荐」卡片点击正常进入歌单列表；「猜你喜欢」是 `isMix` 类型的 QQ 音乐电台，点击后**确认发出了请求且返回 200**，但没有观察到页面切换。

从文案「播放时可以换听歌模式」推测，它的设计可能是**直接替换当前播放队列而不跳转页面**——如果是这样那是正常行为。但我没有在真机上确认到播放条变化，**存疑，未修**。

### 2. QQ 扫码的请求头注入失效

原版用 `declarativeNetRequest` 改写 QQ 扫码流程的请求头，GeckoView 没有这个 API。代码会优雅降级。

实测影响：**不影响登录**（QQ 音乐登录态正常、歌单完整加载），只是少了那层头注入。

### 3. 「原生播放舞台（实验）」是假开关

设置里能看到，但 `NativeBridge` 对 `FoliaNativeStage` 只返回 `active: false`，**点了没有任何效果**。原版用原生 View 绘制播放页背景和歌词以减少 WebView 渲染压力，这个版本没实现。

### 4. APK 体积 95 MB

`libxul.so` 未压缩就有 136 MB。当前用 `packagingOptions.jniLibs.useLegacyPackaging = true` 压到 95 MB（`false` 会变成 184 MB）。

还有优化空间：换 GeckoView 单 ABI 包、剥符号。没做。

### 5. 车机上的音量是软件模拟

见上面第 4 条。代价是页面音量和系统音量会相乘（`base × system`），如果页面内滑块本身调到很低，系统音量再大也推不上去。这是绕开 MMAP 通道的必要代价。

### 6. 依赖组合不要升级

`GeckoView 139 + AGP 8.7.3 + compileSdk 34` 是踩过坑验证过的。升 GeckoView 会触发 `compileSdk 37` / `AGP 9.1` 要求。

---

## 目录

```
.
├── README.md                        # 本文档
├── LICENSE                          # AGPL-3.0
├── release/
│   └── Folia-GeckoView-arm64.apk    # 现成产物（95.11 MB，仅 arm64-v8a）
├── docs/
│   ├── KNOWN_ISSUES.md              # 踩坑记录（20 条）
│   ├── LESSONS.md                   # 可迁移的通用教训（14 条）
│   ├── EXTRACT_ASSETS.md            # 从你自己的 Folia APK 提取 web 资源
│   └── HANDOFF_PROMPT.md            # 交给别的机器/AI 的安装与重建提示词
└── gecko/
    ├── build.ps1                    # 一键构建（路径全走环境变量，不写死）
    ├── settings.gradle              # 仓库：阿里云镜像优先，GeckoView 走 Mozilla
    ├── build.gradle                 # AGP 8.7.3
    ├── gradle.properties            # 构建参数
    ├── keystore.properties.example  # 签名配置模板（复制成 keystore.properties 后填自己的）
    └── app/
        ├── build.gradle             # compileSdk 34 / minSdk 26 / 仅 arm64-v8a
        └── src/main/
            ├── AndroidManifest.xml
            ├── assets/
            │   ├── web/             # 上游前端包（1919 文件 / 28.4 MB，AGPL，见上）
            │   ├── folia-shim.js    # 注入层（935 行）
            │   └── geckoview-config.yaml
            ├── java/top/izuna/foliagecko/
            │   ├── MainActivity.java              263 行   GeckoView 宿主 / 单例 / 返回键
            │   ├── LocalAssetServer.java          690 行   静态资源 + 桥端点 + 音频流
            │   ├── NativeBridge.java              368 行   能力分发
            │   ├── HttpProxy.java                 397 行   HTTP 代发 + Cookie 罐
            │   ├── ImportedAudio.java             534 行   SAF 导入 / 删除 / 清理
            │   ├── FoliaPlaybackService.java      470 行   前台服务 + MediaSession
            │   ├── PhoneLayout.java               287 行   方向 / 刘海 / 沉浸式
            │   ├── LocalAudio.java                 92 行   MediaStore 扫描
            │   ├── SystemVolume.java               85 行   系统音量轮询
            │   ├── PlaybackActionReceiver.java     32 行   通知栏按钮
            │   └── GeckoSessionSettingsCompat.java 27 行   UA / 视口 / 全屏
            └── res/
```

---

## 免责与维护说明

**这个仓库是应网友请求发布的，不一定继续维护。**

- 我自己在用，遇到问题会顺手修，但**不承诺响应时间、不承诺修完**。
- 有 bug 建议**直接拿着源码问 AI**——这个项目的绝大部分代码本来就是这么写出来的，
  文档、注释、踩坑记录都在仓库里，比问人快。
- 不要期待我处理「能不能加 XX 音乐平台」这类上游功能请求，那属于 Folia 本体，不是这个壳的范围。

**不提供任何形式的担保。** 装来源不明的 APK 有风险，请自行判断。

---

## 致谢

- [chthollyphile/folia-major](https://github.com/chthollyphile/folia-major) — Folia 本体（AGPL-3.0）
- [whycfawa/folia-android](https://github.com/whycfawa/folia-android) — Android 移植参考
- [Mozilla GeckoView](https://mozilla.github.io/geckoview/) — 内嵌渲染引擎

本项目的所有原创代码同样以 **AGPL-3.0** 发布，见 [LICENSE](LICENSE)。
