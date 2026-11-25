# 已知问题与踩坑记录

这份文档记录开发过程中真实踩到的坑，以及最终是怎么解决的。
目的不是好看，是让后来的人（或 AI）**不要再踩一遍**。

---

## 一、渲染与显示

### 1. 原版在车机上「界面不刷新」

**现象**：APK 能装能跑，`FoliaNative` 桥调用正常、`recordPlaybackHeartbeat` 每 10 秒回传、
React 与全部 Store 初始化完成、DOM 结构完整，但屏幕就一直停在一层模糊背景上。

**排查过程**：

```
$ adb shell dumpsys webviewupdate
Current WebView package: com.google.android.webview
Version: 95.0.4638.74

$ adb shell dumpsys SurfaceFlinger
窗口 isOpaque=1
VisibleRegion 覆盖全屏
activeBuffer 有真实帧缓冲
```

**结论**：系统认为它在正常显示，屏幕却不是它的内容 → **车机合成/刷新链路的问题**，
与内核版本、CSS 特性、Folia 业务代码**都无关**。

**为什么这条重要**：我一开始断言「Chromium 95 跑不动 Folia、需要 111+」，
还花时间统计了 `?.` 9997 处、`color-mix()` 890 处……全是白费。**判断错了，后面全错。**

教训见 [LESSONS.md](LESSONS.md) 第 1 条。

### 2. Service Worker 把 `index.html` 缓存了（最坑的一个）

**现象**：改了内联在 `index.html` 里的 `folia-shim.js`，重新打包安装，
**行为完全没变**。日志里 shim 版本号停在最初那版，`platform` 一直是 `web`。

**原因**：Folia 是 PWA，`registerSW.js` 注册了 workbox 的 `/sw.js`，
precache 里包含 `index.html` —— 而 shim 就内联在 `index.html` 里。
SW 一旦装上，之后**换 APK、加 `?v=` cache-buster 全都没用**，页面永远跑 SW 缓存里那份旧 HTML。
而且 SW 缓存按 origin 持久化，杀进程、覆盖安装都不清。

**这解释了好几个「怎么都修不好」的毛病**：本地歌曲入口一直置灰、
原生播放桥一直装不上（所以没有 MediaSession、没有后台播放、没有媒体键）。

**解决（三层防护）**：

1. **换端口 = 换 origin**：47821 → 47831，绕开被污染的旧缓存
2. **shim 最早期掐掉 SW 注册**：
   ```js
   navigator.serviceWorker.register = () => Promise.reject(new Error('disabled'));
   ```
3. **引擎层关掉**：`geckoview-config.yaml` 里
   ```yaml
   dom.serviceWorkers.enabled: false
   ```

另外 Gecko 的 HTTP 缓存对固定 origin 也可能喂回旧 `index.html`，
所以 `loadUri` 要带「包安装时间」做 cache-buster。

### 3. `androidBridge` 哨兵：不要伪造 `getPlatform`

**现象**：把 `window.Capacitor.getPlatform` 用 `defineProperty` 锁成返回 `'android'`，
结果整个包初始化失败。

**原因**：Capacitor 自己的平台判定是

```js
const getPlatformId = (win) =>
  win?.androidBridge ? 'android'
  : win?.webkit?.messageHandlers?.bridge ? 'ios' : 'web';
```

页面模块 `createCapacitor()` 里会写 `cap.getPlatform = ...` 覆盖回去。
而 ES module 是**严格模式**，属性被锁成不可写时赋值会**直接抛 TypeError**，整包挂掉。

**正确做法**：补上 `window.androidBridge` 哨兵，让页面自己算出 `'android'`。

这一条同时决定两件事：

- 本地库可用性判定 → 否则「本地歌曲」入口置灰
- 原生播放桥是否安装 → 否则没有 `updatePlaybackState`，也就没有 MediaSession / 后台播放 / 媒体键

---

## 二、原生桥

### 4. GeckoView 没有 `addJavascriptInterface`

| 能力 | WebView | GeckoView |
|---|---|---|
| `addJavascriptInterface` | ✅ | ❌ |
| `evaluateJS` | ✅ | ❌ |
| `CookieManager` | ✅ | ❌ |
| WebExtension `nativeMessaging` | — | ⚠️ 理论上可以，实际不行 |

原计划走 WebExtension，实测两个硬阻塞：

- 安装本地扩展强制要求签名 → `InstallException: -5`
- MV2 的 `nativeMessaging` 被判定 `Invalid extension permission`

**解法**：本地 HTTP 双向通道。

```
页面  --POST /__native/call-->  LocalAssetServer  -->  NativeBridge  -->  能力实现
页面  <--GET /__native/poll--   反向事件队列（返回键、系统音量、媒体键）
```

### 5. 跨站 `fetch` 被 CORS 拦成 `NetworkError`

**现象**：页面报 `NetworkError when attempting to fetch resource`，
请求**根本没到原生层**，日志里什么都没有。

这是最初「连不上 API 服务器」的真正原因。我先后猜过内核版本、CSS、CORS 白名单、
WebExtension 签名——**全错**。

**解法**：shim 劫持 `window.fetch`，把 43 个白名单域名转给原生 OkHttp 代发，其余仍走原生 fetch。

> 不能依赖页面自带的 `installNativeAndroidBridge` 劫持——两套 hook 有时序竞争，会失效。

### 6. 桥返回值必须包在 `value` 里

**现象**：`httpRequest` 返回 `status=undefined bodyLen=0`。

**原因**：`handleCall` 曾把 `id` / `ok` 与业务字段**平铺**在同一层，
前端解包时读到错误的层级。

**解法**：统一信封 `{id, ok, value|error}`，业务返回值一律放 `value`。

---

## 三、音频

### 7. 音量不受控：AAudio MMAP 直通

**现象**：车机上按音量键、转旋钮，声音完全没变化。

**排查**：

```
$ adb shell dumpsys media.audio_flinger
Output thread ... name AudioMmapOut_12, type 5 (MMAP_PLAYBACK):
  Stream type: 3   Stream volume: 1.000000   ← 恒为 1.0

$ adb shell dumpsys audio
- STREAM_MUSIC:
    Muted: true
    streamVolume:0      ← 已经调到 0 了！
```

**结论**：这条 MMAP 通道**绕开了 AudioFlinger 混音器**，系统音量对它完全无效。

WebView 用普通 AudioTrack 走混音器，所以原版没这个问题。

**试过但无效的方案**：引擎层换 cubeb 后端

```yaml
media.cubeb.backend: opensl
```

设了之后输出线程**仍然是 `AudioMmapOut_*`**，没切换成功。

**最终方案**：页面侧软件增益。

### 8. 音量「升三格降三格，一路掉到 0」

这个 bug 很典型，值得单独记。

**现象**：按音量键，无论升还是降，声音都变小，每按一次掉一截，直到 0。
只有拖动应用内音量滑块才恢复。

**原因**：`applyVolume()` 里每次都**现场重新取**原始描述符

```js
Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume')
```

但那时原型上已经是我们的 hook 了，拿到的 `desc.set` **就是我们自己的 setter**。
于是形成自我递归：

1. 音量键变 → `applyVolume()` → 调自己的 setter
2. setter 把「缩放后的值」当成页面基准值存起来
3. 再乘一次系统音量下发

每按一次键就乘一遍系统音量（< 1），单调下降。

**解法（三处）**：

1. **原始描述符只抓一次**，在 `defineProperty` 之前存成模块级常量
2. **getter 隔离**：劫持后页面读到的是**它自己的基准值**，不是缩放后的真实值
   （否则页面监听 `volumechange` 读到缩放值、当成自己的音量存回去，又是一次自乘）
3. **只在一处施加系统音量**：去掉 WebAudio 主增益那条路。
   播放器多为 `<audio> → MediaElementSource → destination`，
   元素音量和主增益会各乘一次（0.33² ≈ 0.11），声音远小于预期

**验证**：日志打「页面基准值 / 真实下发值」

```
DIAG volume-apply :: sys=0.28 els=2 1.00/0.28 -1.00/0.28
DIAG volume-apply :: sys=0.23 els=2 1.00/0.23 -1.00/0.23
```

基准值恒为 `1.00` 不漂移，真实值严格跟随系统音量 → 自乘链断了。

---

## 四、媒体键

### 9. BYD 车机的媒体方案不需要私有权限

反编译 `com.byd.mediacontroller`（`/system/app/MediaController`）后确认链路：

1. `MediaSessionManager.getActiveSessions(null)` 发现所有播放器
2. `AudioManager.getCurrentAudioFocusPackage()`（BYD 扩展方法）取当前持焦点的包名
3. 按包名匹配到 `MediaController`，然后走**标准 `TransportControls`**：
   `play()` / `pause()` / `skipToNext()` / `skipToPrevious()` / `seekTo()`

**所以第三方应用只要「持音频焦点 + 有活跃 MediaSession」就能被识别和遥控。**

方控按键本身走 `BYDAutoBodyworkDevice` 的 `BODYWORK_MULTIMEDIA_KEY`（签名级私有通道），
但那是**系统侧**的事，它拿到按键后转成标准调用，我们不碰。

**验证**：

```
$ adb shell dumpsys media_session
Media button session is top.izuna.foliagecko/FoliaPlayback
state=PlaybackState {state=3, position=...}

$ adb shell input keyevent 87   # NEXT
FoliaPlayback: media key -> next
```

> 它另有官方 SDK（bind `com.byd.mediacontroller.thirdapp.controller`）可做
> RemoteViews 媒体卡片 / 歌单 / 登录态上报，属可选增强。

---

## 五、本地音乐

### 10. 导入不要复制文件

**一开始的设计**：SAF 选目录 → 全部复制到 `filesDir/imported-audio`。

**实测代价**：

| | 数值 |
|---|---|
| `/sdcard/Music` 里 flac | 55 个 |
| 总大小 | 3.0 GB |
| 最大单文件 | 268 MB |

等于同样内容在设备上存两份。

**改成 A + B 零复制**：

- **A｜MediaStore 命中**：按「文件名 + 大小」反查，返回 `/media/<id>`，零复制零授权
- **B｜SAF 命中**：`takePersistableUriPermission` + `SharedPreferences` 存映射，
  播放时 `/audio/<ref>` 现场 `openInputStream` 流式转发

### 11. web 端根本不读我们返回的 `track.url`

**现象**：导入成功，但播放报「无法访问文件，请重新导入文件夹」。

**原因**：web 端完全忽略 `track.url` 字段，自己按 `nativeAudioRef` 拼：

```js
_N = async e => `http://127.0.0.1:${port}/audio/${encodeURIComponent(e)}`
```

而我上一版 `/audio/` 的分流只处理两种 ref：

- `imported-uri-*` → SAF 流式
- 其它 → 私有目录副本

**漏掉了 `imported-ms-*`**（A 方案的 MediaStore 直达）——它被当成私有目录文件名去找，404，
页面探测到 `missing` 就报「无法访问文件」。

### 12. `deleteImportedAudio` 完全失效

**现象**：点删歌没反应，也不报错。

**原因**：web 端删除逻辑写死了前缀过滤

```js
refs.filter(r => r.startsWith('imported-'))
```

而我生成的是 `imp-<hash>` —— **前缀对不上，传下去是空数组**。

**教训**：跨端（web ↔ native）的 id/ref 契约必须拿前端源码确认前缀与过滤规则，不要自己命名。

### 13. Range 的 `end` 被忽略

**现象**：探测请求 `Range: bytes=0-0`（只要 1 字节），结果**把整个 34MB 文件当响应体发出去**。

**原因**：解析 Range 时只读了 `start`，没读 `end`。

**正确响应**：

```
HTTP/1.1 206 Partial Content
Content-Type: audio/flac
Content-Range: bytes 0-0/34119555
Content-Length: 1
```

**为什么重要**：web 端用 `fetch(url, {headers:{Range:'bytes=0-0'}})` 探测可用性，
`t.ok`（含 206）判 ok、`404` 判 missing、其它判 blocked。
每次探测传一遍整首歌，几首就能把内存和流量吃满。

### 14. `Content-Type` 不能写死 `audio/mpeg`

原来的 `streamMedia` 无条件标 `audio/mpeg`，但曲库里大半是 flac。
类型标错会让 GeckoView 选错解码器。

按 `MediaStore.MIME_TYPE` 取，取不到再按扩展名猜。

---

## 六、布局

### 15. 不要用「设备品类」推断物理形态

**我犯的错**：给车机特判锁了 `SENSOR_LANDSCAPE`，理由是「车机是固定横屏面板」。
用户直接指出：**「车机不需要锁横屏，因为车机屏幕可以旋转」**。

DiLink 这类车机屏幕本身可旋转，按 `automotive` 一刀切锁方向是错的。

**正确做法**：默认 `FULL_SENSOR` 谁都不锁，只在用户显式开启「手机适配」并选了方向时才覆盖。

**连带错误**：刘海策略也按设备分叉（车机 `SHORT_EDGES` / 手机 `DEFAULT`）。
既然车机能竖屏，转过来同样可能被圆角遮挡，应统一 `DEFAULT`。

### 16. GeckoView 不支持 `env(safe-area-inset-*)`

那是 WebView/Chromium 与 Safari 经由 WindowInsets 注入的。GeckoView 没有。

**后果**：页面无法自己避让刘海/挖孔，只能由原生决定 `layoutInDisplayCutoutMode`。

无刘海设备上 `DEFAULT` 与 `SHORT_EDGES` 表现一致（没有可避让区域，画面照样铺满），
所以统一 `DEFAULT` 既不影响车机观感，又能在有挖孔的姿态下不盖住内容。

### 17. 旋转时不要重建 Activity

`AndroidManifest.xml` 里声明了

```xml
android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|uiMode|density"
```

所以 `onConfigurationChanged` 不重建 Activity —— **Gecko 会话与播放队列不丢**。
只重铺窗口 + `geckoView.requestLayout()`。

另外 `GeckoRuntime` / `GeckoSession` / `LocalAssetServer` / `NativeBridge`
全部做成**进程级单例**：

- `GeckoRuntime` 一个进程只能 `create` 一次，重复会抛 `IllegalStateException`
- 桥必须单例，否则 server 和 service 持有的引用不一致，媒体键事件会投进没人取的队列

---

## 七、构建环境

| 坑 | 处理 |
|---|---|
| `AppData\Temp` 被策略拦截，Gradle 报 `AccessDenied` | `gradle.properties` 里 `-Djava.io.tmpdir` 指到可写目录 |
| PowerShell 写文件带 UTF-8 BOM，Groovy 报 `Unexpected character '\ufeff'` | 用 `[IO.File]::WriteAllText` + `UTF8Encoding($false)` |
| jadx CLI 卡在 `AppData\Roaming\skylot\jadx\config` 拒绝访问 | 覆盖 `APPDATA` / `LOCALAPPDATA` / `USERPROFILE` 到可写目录 |
| `sdkmanager` 拉不到包清单 | 直接下 `platform-34-ext7_r03.zip` 解压到 `sdk/platforms/android-34` |
| GeckoView 160 要求 `compileSdk 37` / `AGP 9.1` | 降级到 139，并 `exclude` 传递依赖再 `force` 回旧版 |
| GeckoView 设值类名不是 `GeckoSession.Settings` | 是 `GeckoSessionSettings`（早期版本命名不同） |

---

## 八、测试环境

### 18. adb 模拟点击不可靠

系统文件选择器（`com.android.documentsui`）用 `input tap` 反复点偏：

- 横屏 3192×1368 与 downscale 后的预览图坐标对不上
- 会误进子目录、误触根面包屑
- 几轮下来选择器被应用界面盖住、任务超时

**结论**：这类「一锤子交互」不要靠自动化点击，直接请用户手动点一次，
说清楚点哪里、期望什么、看哪条日志，比自己试五六次快得多也准得多。

### 19. 第三方远控会截走点击

手机上装了「UU远程」`com.netease.uuremote`，会接管屏幕镜像并弹「被控端版本过低」，
导致：

- adb 模拟点击被它截走
- `settings put system user_rotation` 不生效（屏幕死守 `ROTATION_0`）

排查：`adb shell dumpsys window | Select-String mCurrentFocus` 看焦点窗口是谁。

### 20. adb 无法可靠验证旋转

`settings put system user_rotation` 常因第三方远控/系统锁而无效，
而且强制旋转会让 Activity 退到后台、需要重新 `am start`。

**更可靠的替代判据**：

```bash
adb shell dumpsys activity activities | findstr requestedOrientation
# 看到 SCREEN_ORIENTATION_FULL_SENSOR 就说明应用没锁方向
```

或者打日志记录 `setRequestedOrientation` 的实参
（10 = `FULL_SENSOR`、6 = `SENSOR_LANDSCAPE`、7 = `SENSOR_PORTRAIT`）。
