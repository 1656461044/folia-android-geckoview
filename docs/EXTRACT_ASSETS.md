# 从你自己的 Folia APK 提取 web 资源

本仓库里的 `gecko/app/src/main/assets/web/` 是上游 Folia 的**构建产物**（28.4 MB / 1919 个文件）。
如果你不想直接从本仓库取这份文件（比如出于许可或版本考虑），可以自己从**你手上的** Folia APK 里提取。

只要你的 APK 版本和本仓库适配的版本接近（`0.7.29` 系列），壳就能正常工作。

---

## 为什么可以这么做

Folia 原版是 Capacitor 应用，它的前端被打包成静态资源放在 APK 的 `assets/public/` 里。
我们的壳做的事就是：把这些静态资源**原样**放到 `assets/web/`，然后由内置的 GeckoView 渲染。

换句话说，**换了引擎，但前端一行没改**。

---

## 方法一：用 unzip（最简单）

APK 本质是 zip。

```bash
# 1. 看看 APK 里有什么
unzip -l Folia-Android-0.7.29-android.473.apk | head -40

# 2. 只提取 assets/public/ 到临时目录
unzip -q Folia-Android-0.7.29-android.473.apk 'assets/public/*' -d ./extracted

# 3. 挪到我们的 assets/web 下
mkdir -p gecko/app/src/main/assets/web
cp -r ./extracted/assets/public/* gecko/app/src/main/assets/web/
```

Windows（PowerShell，用 7-Zip 或 Expand-Archive）：

```powershell
Expand-Archive -Path .\Folia-Android-0.7.29-android.473.apk -DestinationPath .\extracted
Copy-Item -Recurse .\extracted\assets\public\* .\gecko\app\src\main\assets\web\
```

> 注意：`Expand-Archive` 要求文件扩展名是 `.zip`，可以先复制一份改名。

---

## 方法二：用 apktool / aapt2

如果你只需要资源，不需要反编译 dex，其实 unzip 就够了。要用 apktool 的话：

```bash
apktool d -s -f Folia-Android-0.7.29-android.473.apk -o ./folia_decoded
# -s 表示不反编译 dex，只解资源
```

资源会出现在 `folia_decoded/assets/public/`。

---

## 验证提取结果

提取完应有 **1919 个文件 / 约 28.4 MB**：

```bash
cd gecko/app/src/main/assets/web
find . -type f | wc -l        # 期望 ~1919
du -sh .                       # 期望 ~28.4M
```

关键文件必须在：

```bash
ls -la index.html registerSW.js manifest.webmanifest
ls assets/ | head            # 应该看到很多 folia-*.js / *.css
```

`index.html` 里应该有 `<script src="./registerSW.js">` 之类的 PWA 注册代码。
**不用管它** —— 壳里的 shim 会在最早期把 `serviceWorker.register` 掐掉，
因为 SW 的 precache 会缓存 `index.html`，导致后续换包不生效（这个坑踩过）。

---

## 版本不匹配怎么办

如果你手上的 APK 版本和本仓库适配的版本差得远（比如 `0.6.x` 对 `0.7.29`），可能会遇到：

| 现象 | 原因 |
|---|---|
| 白屏 / 报 JS 错误 | 前端资源与 `folia-shim.js` 期望的 API 对不上 |
| 本地音乐入口置灰 | 前端判定的 Capacitor 方法名变了 |
| 插件调用报 `UNIMPLEMENTED` | 前端调了壳没实现的方法（会打日志，能看到方法名） |

处理办法：看 `adb logcat | grep FoliaGeckoBridge` 找出缺哪个方法，去 `NativeBridge.java` 里补。

---

## 提取后别忘了

`assets/web/` 里的文件**不要做任何修改**。

如果你看到 `index.html` 里没有 shim，那是正常的 —— shim 不在文件里，
而是 `LocalAssetServer` 在**返回响应时动态内联**进 `<head>` 的（见 `buildIndexWithShim()`）。

这样设计是因为：往磁盘上的 index.html 里塞东西，一旦上游更新就会冲突；
运行时注入则永远跟着 `folia-shim.js` 走。
