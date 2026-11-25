package top.izuna.foliagecko;

import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 当前文件：本地 HTTP 服务，把 APK 里的 assets/web 暴露到 127.0.0.1，
 * 同时充当 GeckoView 与页面之间的原生桥（GeckoView 无 addJavascriptInterface）。
 *
 * 端点：
 *   GET  /                  -> index.html（并内联注入 folia-shim.js）
 *   GET  /<path>            -> assets/web/<path>，未命中回退 index.html（SPA）
 *   POST /__native/call     -> 交给 NativeBridge 执行，返回 JSON
 */
public final class LocalAssetServer {
    private static final String TAG = "FoliaAssetServer";
    private static final String ASSET_ROOT = "web";
    private static final String SHIM_ASSET = "folia-shim.js";
    /**
     * 固定端口，保证页面 origin 稳定（localStorage / IndexedDB 按 origin 隔离）。
     *
     * ⚠ 端口同时也是「换壳清缓存」的开关：
     * 上一版用 47821，那个 origin 已经被 Folia 自带的 Service Worker（sw.js + workbox）
     * 用 precache 缓存了 index.html —— 而 index.html 里内联着 folia-shim.js。
     * 结果就是：无论怎么换 APK、怎么加 cache-buster（?v=安装时间也不管用），
     * 页面永远拿到 SW 里那份旧 index.html，执行的是旧 shim
     * （实测日志里 shim 版本一直停在最初那份，platform 永远是 web）。
     * 改端口 = 换 origin = 绕开那份被污染的 SW 缓存。
     * 之后再也不会被污染：新 shim 会在页面最早期把 serviceWorker.register 掐掉，
     * 同时 geckoview-config.yaml 里也把 dom.serviceWorkers.enabled 关掉了。
     */
    private static final int PREFERRED_PORT = 47831;

    private final AssetManager assets;
    private final NativeBridge bridge;
    private final android.content.Context context;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private int port = -1;
    private volatile boolean running;
    private volatile String shimSource;

    LocalAssetServer(android.content.Context context, AssetManager assets, NativeBridge bridge) {
        this.context = context;
        this.assets = assets;
        this.bridge = bridge;
    }

    /** 启动服务，返回实际监听端口；失败返回 -1。 */
    public int start() {
        // 端口必须固定：页面 origin 是 http://127.0.0.1:<port>，
        // 而 localStorage / IndexedDB 都按 origin 隔离。若每次都换随机端口，
        // 每次启动都会被当成全新站点，导致「设置引导每次都重新出现」、
        // 播放历史与登录态全部丢失。这里固定端口，冲突时按 +1 顺延。
        int[] candidates = {PREFERRED_PORT, PREFERRED_PORT + 1, PREFERRED_PORT + 2,
                PREFERRED_PORT + 3, PREFERRED_PORT + 4};
        for (int candidate : candidates) {
            try {
                serverSocket = new ServerSocket(candidate, 32, InetAddress.getByName("127.0.0.1"));
                port = serverSocket.getLocalPort();
                break;
            } catch (IOException ignored) {
                // 端口被占用，试下一个
            }
        }
        if (serverSocket == null) {
            // 兜底：真的都被占了才用随机端口（此时本地存储会丢，但至少能启动）
            try {
                serverSocket = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
                port = serverSocket.getLocalPort();
                Log.w(TAG, "preferred ports busy, fell back to random " + port +
                        " (local storage origin will change)");
            } catch (IOException e) {
                Log.e(TAG, "cannot start server", e);
                return -1;
            }
        }
        // 端口已确定，开始接受连接
        running = true;
        pool = Executors.newFixedThreadPool(4);
        Thread accept = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    pool.execute(() -> handle(socket));
                } catch (IOException e) {
                    if (running) Log.w(TAG, "accept failed", e);
                }
            }
        }, "FoliaAssetAccept");
        accept.setDaemon(true);
        accept.start();
        Log.i(TAG, "serving assets at http://127.0.0.1:" + port + "/");
        return port;
    }

    public int getPort() {
        return port;
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(20000);
            InputStream in = s.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;

            int contentLength = 0;
            String rangeHeader = null;
            String header;
            while ((header = readLine(in)) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon > 0) {
                    String name = header.substring(0, colon).trim();
                    if (name.equalsIgnoreCase("Content-Length")) {
                        try {
                            contentLength = Integer.parseInt(header.substring(colon + 1).trim());
                        } catch (NumberFormatException ignored) {
                        }
                    } else if (name.equalsIgnoreCase("Range")) {
                        rangeHeader = header.substring(colon + 1).trim();
                    }
                }
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String rawPath = parts[1];
            int q = rawPath.indexOf('?');
            if (q >= 0) rawPath = rawPath.substring(0, q);
            String path = URLDecoder.decode(rawPath, "UTF-8");

            OutputStream out = s.getOutputStream();

            // ---- 原生桥端点 ----
            if (path.equals("/__native/poll")) {
                JSONObject ev;
                try {
                    ev = bridge.pollEvents();
                } catch (Exception e) {
                    ev = new JSONObject();
                    try {
                        ev.put("events", new JSONArray());
                    } catch (Exception ignored) {
                    }
                }
                writeResponse(out, 200, "application/json; charset=utf-8",
                        ev.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (path.equals("/__native/call")) {
                byte[] body = new byte[Math.max(0, contentLength)];
                int read = 0;
                while (read < contentLength) {
                    int n = in.read(body, read, contentLength - read);
                    if (n <= 0) break;
                    read += n;
                }
                JSONObject result;
                try {
                    JSONObject req = new JSONObject(new String(body, 0, read, StandardCharsets.UTF_8));
                    result = bridge.handleCall(req);
                } catch (Exception e) {
                    result = new JSONObject();
                    try {
                        result.put("ok", false);
                        result.put("error", String.valueOf(e.getMessage()));
                    } catch (Exception ignored) {
                    }
                }
                writeResponse(out, 200, "application/json; charset=utf-8",
                        result.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }

            // ---- 本地音频流：/media/<MediaStore id> ----
            if (path.startsWith("/media/")) {
                streamMedia(path.substring("/media/".length()), rangeHeader, s, out);
                return;
            }

            // ---- 导入音频（SAF 复制到私有目录后回源）----
            // 页面侧拼的是 http://127.0.0.1:<port>/audio/<id>，
            // id 是 ImportedAudio 生成的哈希文件名（不含路径分隔符）。
            if (path.startsWith("/audio/")) {
                String ref = path.substring("/audio/".length());
                // web 端只按 nativeAudioRef 拼 /audio/<ref>，不认我们返回的 url 字段
                // （见 bootstrap 里的 _N：`http://127.0.0.1:${port}/audio/${encodeURIComponent(ref)}`）。
                // 所以三种 ref 都要能在这里分流，少一种就表现为
                // 「无法访问文件，请重新导入文件夹」。
                if (ref.startsWith(ImportedAudio.REF_MS)) {
                    // A 方案：MediaStore 直达，转给 /media/ 那条流
                    streamMedia(ref.substring(ImportedAudio.REF_MS.length()),
                            rangeHeader, s, out);
                } else if (ref.startsWith(ImportedAudio.REF_URI)) {
                    // B 方案：SAF 持久授权 + 现场 openInputStream
                    streamSaf(ref, rangeHeader, out);
                } else {
                    // 历史遗留的私有目录副本（imported-file-* / 旧 imp-*）
                    streamImported(ref, rangeHeader, out);
                }
                return;
            }

            // ---- 静态资源 ----
            if (path.equals("/") || path.isEmpty()) path = "/index.html";
            String rel = path.startsWith("/") ? path.substring(1) : path;
            if (rel.contains("..")) {
                writeResponse(out, 400, "text/plain; charset=utf-8",
                        "Bad Request".getBytes(StandardCharsets.UTF_8));
                return;
            }

            // index.html 需要注入 shim，必须走单独分支
            if (rel.equals("index.html")) {
                byte[] html = buildIndexWithShim();
                if (html == null) {
                    writeResponse(out, 404, "text/plain; charset=utf-8",
                            "Not Found".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                writeResponse(out, 200, "text/html; charset=utf-8", html);
                return;
            }

            byte[] body = readAsset(ASSET_ROOT + "/" + rel);
            if (body == null) {
                // SPA 回退
                byte[] html = buildIndexWithShim();
                if (html == null) {
                    writeResponse(out, 404, "text/plain; charset=utf-8",
                            "Not Found".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                writeResponse(out, 200, "text/html; charset=utf-8", html);
                return;
            }
            writeResponse(out, 200, contentType(rel), body);
        } catch (Exception e) {
            Log.w(TAG, "handle failed", e);
        }
    }

    /**
     * 把 MediaStore 里的音频按 id 流式输出。
     *
     * 两个坑（都会导致 Web 端报「无法访问文件」或播放异常）：
     *   ① Content-Type 不能写死 audio/mpeg —— 曲库里大半是 flac，
     *      类型标错 GeckoView 的解码器选择会走错路。这里按 MediaStore 的 MIME 取，
     *      取不到再按文件扩展名猜。
     *   ② 要支持 Range。web 端探测可用性时发的是 `Range: bytes=0-0`，
     *      而且 <audio> 拖动进度也依赖它。Cursor/InputStream 无法随机定位，
     *      所以对 Range 请求读出前 N 个字节就断开（探测只需 status=206/200）。
     *      真正的 seek 由页面缓存的 blob 兜住。
     */
    private void streamMedia(String idText, Socket socket, OutputStream out) {
        streamMedia(idText, null, socket, out);
    }

    private void streamMedia(String idText, String range, Socket socket, OutputStream out) {
        long id;
        try {
            id = Long.parseLong(idText.trim());
        } catch (NumberFormatException e) {
            try {
                writeResponse(out, 400, "text/plain; charset=utf-8",
                        "Bad id".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
            return;
        }
        android.net.Uri uri = android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);

        // 查 MIME 与大小，用于正确的 Content-Type / Content-Range
        String mime = null;
        long totalSize = -1;
        android.database.Cursor c = null;
        try {
            c = context.getContentResolver().query(uri,
                    new String[]{android.provider.MediaStore.Audio.Media.MIME_TYPE,
                            android.provider.MediaStore.Audio.Media.SIZE},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                mime = c.getString(0);
                if (!c.isNull(1)) totalSize = c.getLong(1);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (mime == null || mime.isEmpty() || "application/octet-stream".equals(mime)) {
            mime = ImportedAudio.guessMimeType(uri.getLastPathSegment());
        }

        // 解析 Range：支持 "bytes=start-" 与 "bytes=start-end"
        long start = 0;
        long reqEnd = -1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                try {
                    String a = spec.substring(0, dash).trim();
                    String b = spec.substring(dash + 1).trim();
                    if (!a.isEmpty()) {
                        start = Long.parseLong(a);
                        partial = true;
                    }
                    if (!b.isEmpty()) reqEnd = Long.parseLong(b);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (start < 0) start = 0;
        if (totalSize > 0 && start >= totalSize) start = 0;

        // 计算实际要发的字节区间（含 end）。
        // 探测请求是 `bytes=0-0`，必须只回 1 字节并标 206；
        // 之前忽略了 end，导致把整个文件都当成响应体发出去。
        long end = (reqEnd >= 0) ? reqEnd : (totalSize > 0 ? totalSize - 1 : -1);
        if (totalSize > 0 && end >= totalSize) end = totalSize - 1;
        long length = (end >= 0 && end >= start) ? (end - start + 1) : -1;

        InputStream is = null;
        try {
            is = context.getContentResolver().openInputStream(uri);
            if (is == null) {
                writeResponse(out, 404, "text/plain; charset=utf-8",
                        "Not Found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            // 跳读到 start
            long skipped = 0;
            byte[] skipBuf = new byte[65536];
            while (skipped < start) {
                int want = (int) Math.min(skipBuf.length, start - skipped);
                int n = is.read(skipBuf, 0, want);
                if (n <= 0) break;
                skipped += n;
            }

            StringBuilder head = new StringBuilder();
            head.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            head.append("Content-Type: ").append(mime).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            if (length > 0) {
                if (partial && totalSize > 0) {
                    head.append("Content-Range: bytes ").append(start).append('-')
                            .append(end).append('/').append(totalSize).append("\r\n");
                }
                head.append("Content-Length: ").append(length).append("\r\n");
            } else {
                // 长度未知时用 chunked，避免把半个文件当成定长发出去
                head.append("Transfer-Encoding: chunked\r\n");
            }
            head.append("Cache-Control: no-store\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));

            byte[] buf = new byte[32768];
            int n;
            boolean chunked = length <= 0;
            long remain = chunked ? -1 : length;
            while (remain != 0 && (n = is.read(buf, 0,
                    remain > 0 ? (int) Math.min(buf.length, remain) : buf.length)) > 0) {
                if (chunked) {
                    out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(buf, 0, n);
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    out.write(buf, 0, n);
                    remain -= n;
                }
                out.flush();
            }
            if (chunked) {
                out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception e) {
            Log.w(TAG, "stream media failed", e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 输出导入音频文件（SAF 复制到 filesDir/imported-audio 后的回源）。
     *
     * 支持 Range：GeckoView 的 <audio> 做 seek 时会带 Range: bytes=...
     * 只发整段 200 的话，拖动进度条可能失效或整首重下。
     */
    private void streamImported(String rawName, String range, OutputStream out) {
        String name;
        try {
            name = java.net.URLDecoder.decode(rawName, "UTF-8");
        } catch (Exception e) {
            name = rawName;
        }
        // 只允许简单文件名，挡住 ../ 之类的穿越
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            try {
                writeResponse(out, 400, "text/plain; charset=utf-8",
                        "Bad name".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
            return;
        }
        java.io.File file = new java.io.File(ImportedAudio.directory(context), name);
        if (!file.isFile()) {
            try {
                writeResponse(out, 404, "text/plain; charset=utf-8",
                        "Not Found".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
            return;
        }
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(file, "r");
            long length = raf.length();
            long start = 0;
            long end = length - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String spec = range.substring("bytes=".length()).trim();
                int dash = spec.indexOf('-');
                if (dash >= 0) {
                    try {
                        String a = spec.substring(0, dash).trim();
                        String b = spec.substring(dash + 1).trim();
                        if (!a.isEmpty()) start = Long.parseLong(a);
                        if (!b.isEmpty()) end = Long.parseLong(b);
                        if (end >= length) end = length - 1;
                        if (start < 0) start = 0;
                        if (start <= end) partial = true;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (!partial) {
                start = 0;
                end = length - 1;
            }
            long count = end - start + 1;
            StringBuilder head = new StringBuilder();
            head.append(partial ? "HTTP/1.1 206 Partial Content\r\n"
                    : "HTTP/1.1 200 OK\r\n");
            head.append("Content-Type: ").append(ImportedAudio.guessMimeType(name)).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            head.append("Content-Length: ").append(count).append("\r\n");
            if (partial) {
                head.append("Content-Range: bytes ").append(start).append('-')
                        .append(end).append('/').append(length).append("\r\n");
            }
            head.append("Cache-Control: no-store\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            raf.seek(start);
            byte[] buf = new byte[65536];
            long remain = count;
            while (remain > 0) {
                int want = (int) Math.min(buf.length, remain);
                int n = raf.read(buf, 0, want);
                if (n <= 0) break;
                out.write(buf, 0, n);
                remain -= n;
            }
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "stream imported failed", e);
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 输出 SAF 音频（不复制，现场流式转发）。
     *
     * ref 形如 imported-uri-<hash>，映射存在 SharedPreferences 里。
     * 支持 Range：ContentResolver.openInputStream 没法随机定位，
     * 这里退化为「跳读」——按 start 一路读到目标位置再发，
     * 对本地文件够快，且能保证 <audio> 的 seek 正常工作。
     */
    private void streamSaf(String ref, String range, OutputStream out) {
        android.net.Uri uri = ImportedAudio.resolveSafUri(context, ref);
        if (uri == null) {
            try {
                writeResponse(out, 404, "text/plain; charset=utf-8",
                        "Not Found".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
            return;
        }
        String mime = ImportedAudio.guessMimeType(ref);
        long start = 0;
        long end = -1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                try {
                    String a = spec.substring(0, dash).trim();
                    String b = spec.substring(dash + 1).trim();
                    if (!a.isEmpty()) start = Long.parseLong(a);
                    if (!b.isEmpty()) end = Long.parseLong(b);
                    partial = true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (start < 0) start = 0;

        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                writeResponse(out, 404, "text/plain; charset=utf-8",
                        "Not Found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            // 跳读到 start（buffer 略大，减少循环次数）
            long skipped = 0;
            byte[] skipBuf = new byte[65536];
            while (skipped < start) {
                int want = (int) Math.min(skipBuf.length, start - skipped);
                int n = in.read(skipBuf, 0, want);
                if (n <= 0) break;
                skipped += n;
            }

            long length = -1;
            try {
                length = sizeOfImported(ref);
            } catch (Throwable ignored) {
            }
            long count = -1;
            if (length > 0) {
                if (end < 0 || end >= length) end = length - 1;
                count = end - start + 1;
            }

            StringBuilder head = new StringBuilder();
            head.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            head.append("Content-Type: ").append(mime).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            if (count >= 0) head.append("Content-Length: ").append(count).append("\r\n");
            if (partial && length > 0) {
                head.append("Content-Range: bytes ").append(start).append('-')
                        .append(end).append('/').append(length).append("\r\n");
            }
            head.append("Cache-Control: no-store\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));

            byte[] buf = new byte[65536];
            long remain = count;
            while (true) {
                int want = remain < 0 ? buf.length : (int) Math.min(buf.length, remain);
                if (want <= 0) break;
                int n = in.read(buf, 0, want);
                if (n <= 0) break;
                out.write(buf, 0, n);
                if (remain > 0) remain -= n;
            }
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "stream saf failed", e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 导入条目的文件大小（用于 Content-Length / Content-Range）。 */
    private long sizeOfImported(String ref) {
        try {
            return context.getSharedPreferences("folia-imported-size", 0)
                    .getLong(ref, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 读取 index.html 并在 <head> 最前面内联 shim，保证先于所有 Folia 模块执行。 */
    private byte[] buildIndexWithShim() {
        byte[] raw = readAsset(ASSET_ROOT + "/index.html");
        if (raw == null) return null;
        String shim = shimSource();
        if (shim == null) return raw;

        String html = new String(raw, StandardCharsets.UTF_8);
        String tag = "<script>\n" + shim + "\n</script>";
        int head = html.indexOf("<head>");
        if (head >= 0) {
            int insertAt = head + "<head>".length();
            html = html.substring(0, insertAt) + tag + html.substring(insertAt);
        } else {
            // 没有 head 就放最前面
            html = tag + html;
        }
        return html.getBytes(StandardCharsets.UTF_8);
    }

    private String shimSource() {
        if (shimSource == null) {
            byte[] b = readAsset(SHIM_ASSET);
            if (b != null) shimSource = new String(b, StandardCharsets.UTF_8);
        }
        return shimSource;
    }

    private byte[] readAsset(String path) {
        try (InputStream is = assets.open(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, is.available()));
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeResponse(OutputStream out, int code, String type, byte[] body) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(code == 200 ? "OK" : "Error").append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("js", "text/javascript; charset=utf-8");
        MIME.put("mjs", "text/javascript; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("webmanifest", "application/manifest+json; charset=utf-8");
        MIME.put("svg", "image/svg+xml");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("webp", "image/webp");
        MIME.put("gif", "image/gif");
        MIME.put("ico", "image/x-icon");
        MIME.put("woff", "font/woff");
        MIME.put("woff2", "font/woff2");
        MIME.put("ttf", "font/ttf");
        MIME.put("otf", "font/otf");
        MIME.put("mp3", "audio/mpeg");
        MIME.put("mp4", "video/mp4");
        MIME.put("wav", "audio/wav");
        MIME.put("wasm", "application/wasm");
        MIME.put("txt", "text/plain; charset=utf-8");
    }

    private static String contentType(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = path.substring(dot + 1).toLowerCase();
        String type = MIME.get(ext);
        return type != null ? type : "application/octet-stream";
    }
}
