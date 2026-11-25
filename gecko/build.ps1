# 当前文件：GeckoView 版 Folia 的一键构建脚本。
#
# 用途：在一台已装好 JDK21 + Android SDK(platform-34 / build-tools 34.0.0) + Gradle 8.9
#       的机器上重建 APK。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File build.ps1            # 构建 debug
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Release   # 构建 release（有签名配置时自动签名）
#
# 路径解析顺序：命令行参数 > 环境变量 > 自动探测 > 报错退出。
# 换机器时优先设环境变量，不要改本文件：
#   JAVA_HOME / ANDROID_HOME / ANDROID_SDK_ROOT / GRADLE_HOME / GRADLE_USER_HOME

param(
    [switch]$Release,
    [string]$JavaHome = "",
    [string]$AndroidSdk = "",
    [string]$GradleHome = "",
    [string]$GradleUserHome = "",
    [string]$TmpDir = "",
    [string]$Proxy = ""
)

$ErrorActionPreference = "Stop"
$proj = Split-Path -Parent $MyInvocation.MyCommand.Path

function Resolve-Tool {
    param([string]$Explicit, [string]$EnvName, [string[]]$Candidates, [string]$Label)

    if ($Explicit -and (Test-Path $Explicit)) { return $Explicit }
    $envVal = [Environment]::GetEnvironmentVariable($EnvName)
    if ($envVal -and (Test-Path $envVal)) { return $envVal }
    foreach ($c in $Candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    return ""
}

# ---------------- 路径解析 ----------------

$javaCandidates = @(
    $env:JAVA_HOME,
    "C:\Program Files\Java\jdk-21",
    "C:\Program Files\Eclipse Adoptium\jdk-21*",
    "D:\jdk\jdk21"
) | Where-Object { $_ }

$JavaHome = Resolve-Tool -Explicit $JavaHome -EnvName "JAVA_HOME" `
    -Candidates $javaCandidates -Label "JDK21"

$sdkCandidates = @(
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    "$env:LOCALAPPDATA\Android\Sdk",
    "C:\Android\Sdk"
) | Where-Object { $_ }

$AndroidSdk = Resolve-Tool -Explicit $AndroidSdk -EnvName "ANDROID_SDK_ROOT" `
    -Candidates $sdkCandidates -Label "Android SDK"

$gradleCandidates = @(
    $env:GRADLE_HOME,
    (Join-Path $proj "gradle-8.9")
) | Where-Object { $_ }

$GradleHome = Resolve-Tool -Explicit $GradleHome -EnvName "GRADLE_HOME" `
    -Candidates $gradleCandidates -Label "Gradle 8.9"

if (-not $GradleUserHome) {
    $GradleUserHome = [Environment]::GetEnvironmentVariable("GRADLE_USER_HOME")
}
if (-not $GradleUserHome) {
    $GradleUserHome = Join-Path $proj ".gradle-user"
}

if (-not $TmpDir) { $TmpDir = Join-Path $proj ".build-tmp" }

# ---------------- 前置校验 ----------------

$missing = @()
if (-not $JavaHome)    { $missing += "JDK 21（设 JAVA_HOME）" }
if (-not $AndroidSdk)  { $missing += "Android SDK（设 ANDROID_SDK_ROOT）" }
if (-not $GradleHome)  { $missing += "Gradle 8.9（设 GRADLE_HOME 或把 gradle-8.9 放到工程根）" }

if ($missing.Count -gt 0) {
    Write-Host "=== 缺少依赖 ===" -ForegroundColor Red
    $missing | ForEach-Object { Write-Host "  - $_" }
    Write-Host ""
    Write-Host "本脚本不再写死路径。请设置对应环境变量后重试，例如：" -ForegroundColor Yellow
    Write-Host '  $env:JAVA_HOME = "C:\Program Files\Java\jdk-21"'
    Write-Host '  $env:ANDROID_SDK_ROOT = "C:\Android\Sdk"'
    Write-Host '  $env:GRADLE_HOME = "C:\gradle-8.9"'
    exit 1
}

# platform-34 / build-tools 34.0.0 必须在
$platformOk = Test-Path (Join-Path $AndroidSdk "platforms\android-34")
$btOk = Test-Path (Join-Path $AndroidSdk "build-tools\34.0.0")
if (-not $platformOk -or -not $btOk) {
    Write-Host "=== SDK 组件不全 ===" -ForegroundColor Red
    if (-not $platformOk) { Write-Host "  缺 platforms\android-34" }
    if (-not $btOk)       { Write-Host "  缺 build-tools\34.0.0" }
    Write-Host ""
    Write-Host "用 sdkmanager 安装：sdkmanager 'platforms;android-34' 'build-tools;34.0.0'"
    Write-Host "若 sdkmanager 拉不到清单，也可直接下载 zip 解压到对应目录。"
    exit 1
}

Write-Host "=== 环境 ===" -ForegroundColor Cyan
$env:JAVA_HOME        = $JavaHome
$env:GRADLE_USER_HOME = $GradleUserHome
$env:ANDROID_HOME     = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk

New-Item -ItemType Directory -Force -Path $TmpDir | Out-Null
New-Item -ItemType Directory -Force -Path $GradleUserHome | Out-Null
$env:TEMP = $TmpDir
$env:TMP  = $TmpDir

# 部分机器的 AppData\Temp 受策略限制，GeckoView 的 native 资源合并会抛
# AccessDenied；把 java 临时目录指到工程内的可写位置。
$jvmOpts = "-Djava.io.tmpdir=$($TmpDir -replace '\\','/')"
if ($Proxy -ne "") {
    $jvmOpts += " -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808"
    $jvmOpts += " -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808"
}
$env:JAVA_TOOL_OPTIONS = $jvmOpts

Write-Host "JAVA_HOME = $env:JAVA_HOME"
Write-Host "SDK       = $env:ANDROID_SDK_ROOT"
Write-Host "GRADLE    = $GradleHome"
Write-Host "TMP       = $env:TEMP"
Write-Host "MODE      = $(if ($Release) { 'release' } else { 'debug' })"

# local.properties 指向本机 SDK
"sdk.dir=$($AndroidSdk -replace '\\','\\\\')" |
    Set-Content (Join-Path $proj "local.properties") -Encoding ascii

# 清理 UTF-8 BOM：Groovy 解析 .gradle 时遇到 BOM 会报 Unexpected character
foreach ($f in @(
    (Join-Path $proj "app\build.gradle"),
    (Join-Path $proj "gradle.properties"),
    (Join-Path $proj "settings.gradle"),
    (Join-Path $proj "build.gradle"))) {
    if (Test-Path $f) {
        $c = [IO.File]::ReadAllText($f)
        [IO.File]::WriteAllText($f, $c.TrimStart([char]0xFEFF), (New-Object Text.UTF8Encoding($false)))
    }
}

# 说明：本项目不使用 WebExtension。GeckoView 安装本地扩展强制要求签名
# （InstallException -5），因此 JS <-> 原生 走的是本地 HTTP 通道。

$task = if ($Release) { "assembleRelease" } else { "assembleDebug" }

Write-Host "`n=== 开始构建 ($task) ===" -ForegroundColor Cyan
Push-Location $proj
try {
    & (Join-Path $GradleHome "bin\gradle.bat") $task --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "构建失败，退出码 $LASTEXITCODE" }
} finally {
    Pop-Location
}

$apk = if ($Release) {
    Join-Path $proj "app\build\outputs\apk\release\app-release.apk"
} else {
    Join-Path $proj "app\build\outputs\apk\debug\app-debug.apk"
}

if (Test-Path $apk) {
    $mb = [math]::Round((Get-Item $apk).Length / 1MB, 1)
    Write-Host "`n=== 构建成功 ===" -ForegroundColor Green
    Write-Host "APK: $apk  ($mb MB)"
    if (-not (Test-Path (Join-Path $proj "keystore.properties"))) {
        Write-Host ""
        Write-Host "提示：未找到 keystore.properties，产物是未签名 APK。" -ForegroundColor Yellow
        Write-Host "     想签名就把 keystore.properties.example 复制成 keystore.properties 并填好。" -ForegroundColor Yellow
    }
} else {
    throw "未找到产物 APK：$apk"
}
