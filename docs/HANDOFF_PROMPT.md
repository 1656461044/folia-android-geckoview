# 交给目标机器 AI 的任务提示词

下面整段可以直接粘贴给另一台机器上的 AI（或未来的自己）。它是一份自包含的
安装/交接流程，不需要对方再来问上下文。

---

## 前置说明（给人看的，不用粘贴）

- 这份提示词假设对方**手里已经有** `Folia-GeckoView-arm64.apk`。
  如果对方要的是「从零重建」，用下面 `模式 B`。
- 两条路径都写了安全阀（先 dry-run / 先只读检查），避免一上手就改环境。

---

## 可直接粘贴的提示词（模式 A：只装 APK）

```
你要帮我往一台 Android 车机/设备上安装一个 APK。请严格按以下 8 步执行，
不要跳步，不要自己猜参数，遇到不符就停下来把差异告诉我。

【目标产物】
APK 文件名：Folia-GeckoView-arm64.apk
包名：top.izuna.foliagecko
这是一个内置 GeckoView 内核的 Folia 音乐播放器，不依赖系统 WebView。

【第 1 步：定位 APK】
在当前工作目录及桌面、下载目录中查找 Folia-GeckoView-arm64.apk。
找到后报告绝对路径与文件大小（应在 95MB 左右）。
不要用别处的同名文件替代；如果找不到，停下来告诉我。

【第 2 步：确认 adb 可用】
运行 `adb version`。
如果 adb 不在 PATH，按以下顺序找：
  - Windows: D:\MAA\platform-tools\adb.exe
  - 常见位置: %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
找不到就停下来，把已尝试的路径列给我。

【第 3 步：连接设备】
运行 `adb devices -l`。
- 若有且仅有 1 台 device：记下序列号，继续。
- 若有 unauthorized：提示我在设备上确认「允许USB调试」，等我在设备上点完。
- 若为空：先试 `adb connect 192.168.1.14:5555`（这是已知车机地址）；
  仍为空则停下来，告诉我要开无线调试或插 USB。
不要在有多个设备的情况下随便挑一台。

【第 4 步：只读检查（不要改动任何东西）】
用第 3 步的序列号执行并汇报：
  adb -s <序列号> shell getprop ro.build.version.release
  adb -s <序列号> shell getprop ro.build.version.sdk
  adb -s <序列号> shell getprop ro.product.cpu.abi
要求：SDK >= 26 且 ABI 包含 arm64-v8a。任一不满足就停下来告诉我原因。

【第 5 步：安装】
  adb -s <序列号> install -r <第1步的绝对路径>
若报 INSTALL_FAILED_UPDATE_INCOMPATIBLE：说明设备上已有同包名但签名不同的版本，
先 `adb uninstall top.izuna.foliagecko` 再装，并在汇报里提到这一步。

【第 6 步：授予媒体权限】
  adb -s <序列号> shell pm grant top.izuna.foliagecko android.permission.READ_EXTERNAL_STORAGE
若设备是 Android 13+，改用：
  adb -s <序列号> shell pm grant top.izuna.foliagecko android.permission.READ_MEDIA_AUDIO

【第 7 步：启动并验证】
  adb -s <序列号> logcat -c
  adb -s <序列号> shell am start -n top.izuna.foliagecko/.MainActivity
等 20 秒后执行：
  adb -s <序列号> logcat -d | grep -E "DIAG|FoliaGeckoBridge: call"
判定标准（必须全部满足）：
  a) 出现 `DIAG shim-installed :: fetch-hijack+cookies+bridge`
  b) 出现 `DIAG selftest-qq :: status=200`
  c) 出现至少一条 `FoliaGeckoBridge: call FoliaNative.recordPlaybackHeartbeat`
任一缺失就把完整日志贴给我，不要自行修改 APK。

【第 8 步：截图留证】
  adb -s <序列号> shell screencap -p /sdcard/folia_check.png
  adb -s <序列号> pull /sdcard/folia_check.png ./folia_check.png
  adb -s <序列号> shell rm /sdcard/folia_check.png
把截图路径告诉我。

【元规则】
1. 所有设备序列号、路径都用第 1/3 步实测得到的值，不要自己猜。
2. 任一步失败就停下汇报，不要跳过继续往下做。
3. 不要重签 APK、不要改包名、不要动设备其它应用。
4. 做完给我一段简短报告：装到哪台设备、版本、验证结果、截图路径。
   不要反复找我确认。
```

---

## 可直接粘贴的提示词（模式 B：从源码重建）

```
你要在一台 Windows 机器上重建一个 Android APK。请按以下 8 步执行，
参数不要猜，缺失就停下来问我。

【目标】
把 Folia 的 GeckoView 移植版重新构建出来，产物约 95MB，包名 top.izuna.foliagecko。

【第 1 步：确认工程存在】
工程目录：E:\AI\folia_gecko\gecko
关键文件应包含：settings.gradle、app/build.gradle、build.ps1、
app/src/main/assets/web/index.html、app/src/main/assets/folia-shim.js、
app/src/main/java/top/izuna/foliagecko/*.java
任一缺失就停下来告诉我，不要试图从 GitHub 重新搭。

【第 2 步：检查依赖】
- JDK 21：E:\AI\jdk\jdk21\bin\java.exe
- Gradle 8.9：E:\AI\folia_gecko\dl\gradle-8.9\bin\gradle.bat
- Android SDK：E:\AI\folia_gecko\sdk，须含 platforms\android-34 与 build-tools\34.0.0
逐个 Test-Path 汇报结果。缺哪个就停下来说清楚缺什么。

【第 3 步：确认 web 资源完整】
统计 E:\AI\folia_gecko\gecko\app\src\main\assets\web 下文件数，
应为 1919 个左右、合计约 28.4MB。
明显不符就停下来（说明 web 包没拷全）。

【第 4 步：干跑一次语法检查】
对 build.ps1 做语法校验：
  $e=$null; [System.Management.Automation.Language.Parser]::ParseFile('<路径>',[ref]$null,[ref]$e); $e.Count
应为 0。不为 0 就停下贴错误。

【第 5 步：正式构建】
  cd E:\AI\folia_gecko\gecko
  powershell -ExecutionPolicy Bypass -File .\build.ps1 -Release
预期结尾出现 `=== 构建成功 ===` 与 APK 路径。
若报 AccessDenied 且路径含 AppData：说明 java.io.tmpdir 没生效，检查 gradle.properties。
若报 `Unexpected character` 且指向 .gradle 文件：说明有 UTF-8 BOM，用
  [IO.File]::WriteAllText($f, $c.TrimStart([char]0xFEFF), (New-Object Text.UTF8Encoding($false)))
   逐个清理后重试。

【第 6 步：校验产物】
  用 build-tools\34.0.0\apksigner.bat verify --print-certs <apk>
  用 build-tools\34.0.0\aapt2.exe dump badging <apk> | findstr package
确认：包名 top.izuna.foliagecko、minSdk 26、native-code 仅 arm64-v8a、已签名。

【第 7 步：装机验证】
按「模式 A」第 3~7 步执行，判定标准相同。

【第 8 步：汇报】
给我：构建耗时、APK 大小、签名摘要、装机验证结果。
如果中途改动了任何文件，逐条列出来并说明为什么改。

【元规则】
1. 路径用实测的，不要猜。
2. 失败就停下汇报，不要绕过。
3. 不要升级 AGP / GeckoView 版本——当前组合（GeckoView 139 + AGP 8.7.3 +
   compileSdk 34）是踩过坑验证过的，升级会触发 compileSdk 37 要求。
4. 做完给简短报告，不要反复确认。
```

---

## 关键事实（供对方自查）

| 项 | 值 |
|---|---|
| 包名 | `top.izuna.foliagecko` |
| 版本 | `0.7.29-gecko.1` |
| 签名 | 自签名，别名 `folia`，口令 `folia123` |
| minSdk / targetSdk | 26 / 34 |
| ABI | 仅 `arm64-v8a` |
| 内核 | GeckoView `139.0.20250609112858` |
| APK 体积 | 约 95 MB |
| 启动 Activity | `top.izuna.foliagecko/.MainActivity` |

## 验证用的判定串

```
DIAG shim-installed :: fetch-hijack+cookies+bridge
DIAG selftest-qq :: status=200
FoliaGeckoBridge: call FoliaNative.recordPlaybackHeartbeat
```

三条都出现，说明：注入层活了、跨站请求经原生代发成功、原生桥往返正常。
