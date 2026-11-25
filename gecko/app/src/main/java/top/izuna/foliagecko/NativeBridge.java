package top.izuna.foliagecko;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 当前文件：FoliaNative 桥的 GeckoView 实现（HTTP 轮询版）。
 *
 * 为什么不用 WebExtension：
 * GeckoView 139 安装本地扩展要求签名（InstallException -5），且 MV2 的 nativeMessaging
 * 权限被标记 invalid，本地开发无法通过。因此改用「本地 HTTP 双向队列」：
 *   - 页面 -> 原生：shim 用 fetch POST /__native/call 提交调用，拿到 response
 *   - 原生 -> 页面：页面定时 fetch GET /__native/poll 收事件（本版暂无事件源）
 * 这与「本地 HTTP 服务」天然契合，也绕开了扩展签名限制。
 */
final class NativeBridge {
    private static final String TAG = "FoliaGeckoBridge";

    private volatile MainActivity activity;
    private final LocalAudio localAudio;
    private final HttpProxy httpProxy;
    private SystemVolume systemVolume;
    private java.util.function.IntSupplier portSupplier;
    /**
     * 原生 -> 页面的上行事件队列。
     *
     * GeckoView 没有 evaluateJS，无法主动往页面注入脚本，所以反向通信只能靠
     * 页面轮询 GET /__native/poll 把事件取走（返回键、媒体按键都走这里）。
     */
    private final java.util.Queue<org.json.JSONObject> outboundEvents =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    NativeBridge(MainActivity activity) {
        this.activity = activity;
        this.localAudio = new LocalAudio(activity);
        this.httpProxy = new HttpProxy(activity.getApplicationContext());
    }

    /**
     * Activity 重建时把引用换到新实例上。
     *
     * 桥本身必须是进程级单例：LocalAssetServer 和 FoliaPlaybackService 都长期持有它，
     * 而页面轮询 /__native/poll 走的是 server 里那份引用。如果每次 onCreate 都 new 一个，
     * 媒体按键事件就会投递到一个没人轮询的队列里，表现为「切后台再回来就没有媒体键了」。
     */
    void updateActivity(MainActivity a) {
        if (a != null) this.activity = a;
    }

    /** 投递一个事件给页面。 */
    void emitEvent(String name, org.json.JSONObject payload) {
        try {
            org.json.JSONObject ev = new org.json.JSONObject();
            ev.put("type", "event");
            ev.put("name", name);
            ev.put("payload", payload == null ? new org.json.JSONObject() : payload);
            outboundEvents.offer(ev);
        } catch (JSONException ignored) {
            // 忽略
        }
    }

    /** 页面轮询取事件；没有就返回空数组。 */
    JSONObject pollEvents() throws JSONException {
        JSONArray arr = new JSONArray();
        org.json.JSONObject ev;
        while ((ev = outboundEvents.poll()) != null) {
            arr.put(ev);
            if (arr.length() >= 32) break;
        }
        return new JSONObject().put("events", arr);
    }

    /** 返回键的处理结果回调。 */
    interface BackResult {
        /** 页面关掉了一层界面（不该退出）。 */
        void onHandled();

        /** 页面没有可返回的层级（应该退出到桌面）。 */
        void onNotHandled();
    }

    /**
     * 处理一次返回键。
     *
     * GeckoView 不能同步调用页面，所以这里用「事件 + 短窗口」的方式：
     *   1. 把 backButton 事件压进队列给页面轮询
     *   2. 页面处理完会调 FoliaNative.noteBackHandled({handled})
     *   3. 在 BACK_WINDOW_MS 内收到 handled=true 就算页面消费了；
     *      超时或收到 handled=false 则回调 onNotHandled（退出到桌面）
     */
    void requestBack(final BackResult result) {
        final long token = ++backToken;
        backHandled = false;
        backAnswered = false;
        emitEvent("backButton", new org.json.JSONObject());

        backHandler.postDelayed(() -> {
            // 超时判定：只有最新一次请求的结果有效
            if (token != backToken) return;
            if (backAnswered && backHandled) {
                result.onHandled();
            } else {
                result.onNotHandled();
            }
        }, BACK_WINDOW_MS);
    }

    /** 页面回报返回键处理结果。 */
    void noteBackHandled(boolean handled) {
        backAnswered = true;
        backHandled = handled;
    }

    private static final long BACK_WINDOW_MS = 260;
    private final android.os.Handler backHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile long backToken = 0;
    private volatile boolean backHandled = false;
    private volatile boolean backAnswered = false;

    /** 注入本地服务端口，供 localAudioServerPort 返回。 */
    void setPortSupplier(java.util.function.IntSupplier supplier) {
        this.portSupplier = supplier;
    }

    /** 启动系统音量监听（MainActivity 创建完桥之后调用）。 */
    void attachSystemVolume() {
        if (systemVolume == null) {
            systemVolume = new SystemVolume(activity, this);
            systemVolume.start();
        }
    }

    void detachSystemVolume() {
        if (systemVolume != null) {
            systemVolume.stop();
            systemVolume = null;
        }
    }

    /** 前台服务启动后把桥挂上去，媒体键才能回传给页面。 */
    void attachPlaybackService() {
        FoliaPlaybackService.attachBridge(this);
        serviceStarted = true;
    }

    /**
     * 前端推来播放状态时，确保前台服务在跑并同步元数据。
     *
     * 前端在播放且可见时会每秒调一次（force），暂停时也会推一次。
     * 只要有过一次状态就启动前台服务，保证切后台/锁屏不断播。
     */
    private void updatePlaybackState(JSONObject s) {
        if (s == null) return;
        try {
            if (!serviceStarted) {
                android.content.Intent i = new android.content.Intent(
                        activity, FoliaPlaybackService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    activity.startForegroundService(i);
                } else {
                    activity.startService(i);
                }
                attachPlaybackService();
                Log.i(TAG, "playback service started");
            }
            android.content.Intent upd = new android.content.Intent(
                    activity, FoliaPlaybackService.class);
            upd.putExtra("title", s.optString("title", ""));
            upd.putExtra("artist", s.optString("artist", ""));
            upd.putExtra("album", s.optString("album", ""));
            upd.putExtra("coverUrl", s.optString("coverUrl", ""));
            upd.putExtra("songKey", s.optString("songKey", ""));
            upd.putExtra("playing", s.optBoolean("playing", false));
            upd.putExtra("position", s.optLong("position", 0));
            upd.putExtra("duration", s.optLong("duration", 0));
            lastPlaybackState = s;
            activity.startService(upd);
        } catch (Throwable t) {
            Log.w(TAG, "update playback state failed", t);
        }
    }

    private void stopPlaybackService() {
        try {
            android.content.Intent i = new android.content.Intent(
                    activity, FoliaPlaybackService.class);
            i.setAction("clear");
            activity.startService(i);
            serviceStarted = false;
            lastPlaybackState = null;
        } catch (Throwable t) {
            Log.w(TAG, "stop playback service failed", t);
        }
    }

    private volatile boolean serviceStarted = false;
    private volatile JSONObject lastPlaybackState = null;

    /** 处理一次来自页面的调用，返回 {id, ok, value|error} 信封。 */
    JSONObject handleCall(JSONObject msg) {
        Object id = msg.opt("id");
        String pluginId = msg.optString("pluginId", "");
        String method = msg.optString("methodName", "");
        JSONObject options = msg.optJSONObject("options");
        Log.i(TAG, "call " + pluginId + "." + method);

        JSONObject envelope = new JSONObject();
        try {
            JSONObject value = dispatch(pluginId, method, options);
            envelope.put("id", id);
            envelope.put("ok", true);
            // 注意：业务返回值必须放在 value 里，不能和信封字段平铺，
            // 否则会和 id/ok 混在同一层，前端解包时读到错误的层级。
            envelope.put("value", value == null ? new JSONObject() : value);
        } catch (Throwable t) {
            try {
                envelope.put("id", id);
                envelope.put("ok", false);
                envelope.put("error", t.getMessage() == null ? t.toString() : t.getMessage());
            } catch (JSONException ignored) {
            }
        }
        return envelope;
    }

    private JSONObject dispatch(String pluginId, String method, JSONObject options) throws JSONException {
        if ("FoliaNative".equals(pluginId)) {
            switch (method) {
                // ---- 上行事件轮询（返回键等） ----
                case "pollEvents":
                    return pollEvents();
                case "getSystemVolume":
                    return new JSONObject()
                            .put("percent", systemVolume == null ? 100 : systemVolume.currentPercent())
                            .put("fraction", systemVolume == null ? 1.0 : systemVolume.currentFraction());
                case "noteBackHandled":
                    noteBackHandled(options != null && options.optBoolean("handled", true));
                    return new JSONObject().put("ok", true);

                // ---- 播放状态 -> 前台服务 / MediaSession ----
                case "updatePlaybackState":
                    updatePlaybackState(options);
                    return new JSONObject().put("ok", true);
                case "clearPlaybackState":
                    stopPlaybackService();
                    return new JSONObject().put("ok", true);
                // ---- 网页部署所需的核心两项：HTTP 代理 + Cookie ----
                case "httpRequest":
                    return httpProxy.httpRequest(options == null ? new JSONObject() : options);
                case "cookiesGet":
                    return httpProxy.cookiesGet(options == null ? new JSONObject() : options);
                case "cookiesGetAll":
                    return httpProxy.cookiesGetAll(options == null ? new JSONObject() : options);
                case "cookiesGetBatch":
                    return httpProxy.cookiesGetBatch(options == null ? new JSONObject() : options);
                case "cookiesSet":
                    return httpProxy.cookiesSet(options == null ? new JSONObject() : options);
                case "cookiesRemove":
                    return httpProxy.cookiesRemove(options == null ? new JSONObject() : options);
                case "cookiesRemoveBatch":
                    return httpProxy.cookiesRemoveBatch(options == null ? new JSONObject() : options);
                case "localAudioServerPort":
                    // 前端会把该端口拼成 http://127.0.0.1:<port>/audio/<ref>；
                    // 我们的 /media/<id> 挂在同一服务上，端口即服务端口。
                    return new JSONObject().put("port", portSupplier == null ? 0 : portSupplier.getAsInt());
                case "setKeepScreenOn":
                    activity.runOnUiThread(() -> activity.getWindow().addFlags(
                            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
                    return new JSONObject();
                // ---- 屏幕方向 / 沉浸式布局 / 刘海屏 ----
                // 对应 Folia 设置里的「Android 壳」分组：
                //   手机适配(phoneFit) + 方向(orientationFit) + 自适应显示(adaptiveDisplay)
                // web 端（ponderRegistry-CbAMeSS5.js / bootstrap）会真的调这些方法，
                // 不实现就是设置点了没反应。
                case "getPhoneFitLayout": {
                    boolean enabled = PhoneLayout.isEnabled(activity);
                    String orientation = PhoneLayout.getOrientation(activity);
                    return new JSONObject()
                            .put("enabled", enabled)
                            .put("orientation", orientation)
                            .put("automotive", PhoneLayout.isAutomotive(activity));
                }
                case "setPhoneFitLayout": {
                    boolean enabled = options != null && options.optBoolean("enabled", false);
                    String orientation = options == null ? null
                            : options.optString("orientation", null);
                    if (orientation == null || orientation.isEmpty()) {
                        orientation = PhoneLayout.getOrientation(activity);
                    }
                    PhoneLayout.update(activity, enabled, orientation);
                    activity.applyPhoneLayout();
                    return new JSONObject()
                            .put("enabled", enabled)
                            .put("orientation", PhoneLayout.getOrientation(activity));
                }
                case "getAdaptiveLayout":
                    return new JSONObject()
                            .put("enabled", PhoneLayout.isAdaptiveLayoutEnabled(activity));
                case "setAdaptiveLayout": {
                    boolean enabled = options != null && options.optBoolean("enabled", false);
                    PhoneLayout.setAdaptiveLayout(activity, enabled);
                    activity.applyPhoneLayout();
                    return new JSONObject().put("enabled", enabled);
                }
                case "getDisplayCutout":
                    return PhoneLayout.describeCutout(activity);
                case "setDisplayFrameRate":
                    return new JSONObject().put("fps", 0);
                case "getBackgroundThrottlingState":
                    return new JSONObject().put("enabled", false);
                case "scanLocalAudio": {
                    boolean granted = localAudio.hasPermission();
                    if (!granted) {
                        // 首次调用先申请权限，前端会在拿到 needsAllFilesAccess 后重试
                        activity.runOnUiThread(localAudio::requestPermission);
                        return new JSONObject()
                                .put("tracks", new JSONArray())
                                .put("needsAllFilesAccess", true);
                    }
                    // 有权限就真的去扫 MediaStore。
                    // Folia 的本地音乐入口只有在拿到 tracks 之后才可点，
                    // 返回空数组会导致「本地歌曲」一直置灰。
                    JSONArray tracks = localAudio.scan();
                    return new JSONObject()
                            .put("tracks", tracks)
                            .put("needsAllFilesAccess", false);
                }
                case "requestAllFilesAccess":
                    activity.runOnUiThread(localAudio::requestPermission);
                    return new JSONObject().put("granted", localAudio.hasPermission());
                case "copyLocalAudio":
                    return new JSONObject().put("copied", false);
                // ---- 本地音乐导入（SAF）----
                // 页面在「本地歌曲 → 添加文件夹/添加文件」时调用。
                // **不需要任何存储权限**：SAF 由系统文件选择器授权 URI，
                // 不走 READ_EXTERNAL_STORAGE / READ_MEDIA_AUDIO。
                // 这里会阻塞等待用户选择（最长 300s），结果由
                // MainActivity.onActivityResult → ImportedAudio.onActivityResult 放行。
                case "pickAudioFolder":
                case "pickAudioFiles": {
                    int p = portSupplier == null ? 0 : portSupplier.getAsInt();
                    String mode = "pickAudioFolder".equals(method) ? "folder" : "files";
                    try {
                        return ImportedAudio.pick(activity, mode, p);
                    } catch (Throwable t) {
                        Log.w(TAG, "pick failed", t);
                        return new JSONObject()
                                .put("cancelled", true)
                                .put("picked", 0)
                                .put("tracks", new JSONArray())
                                .put("failures", new JSONArray())
                                .put("resultCode", 0)
                                .put("error", String.valueOf(t.getMessage()));
                    }
                }
                // ---- 删除导入条目 ----
                // web 端只把以 imported- 开头的 id 传下来（见 bootstrap 的 Cbe），
                // 所以 ref 前缀必须严格对上，否则「删歌曲」静默失效。
                // 只清理我们自己的东西（URI 映射/持久授权/私有副本），绝不删用户原文件。
                case "deleteImportedAudio": {
                    JSONArray refs = options == null ? null : options.optJSONArray("refs");
                    if (refs == null) refs = new JSONArray();
                    try {
                        return ImportedAudio.delete(activity, refs);
                    } catch (Throwable t) {
                        Log.w(TAG, "delete imported failed", t);
                        return new JSONObject()
                                .put("deleted", new JSONArray())
                                .put("failed", refs)
                                .put("error", String.valueOf(t.getMessage()));
                    }
                }
                case "getWallpaperDisplays":
                    return new JSONObject().put("displays", new JSONArray());
                case "shareTextFile":
                    return new JSONObject().put("shared", false);
                default:
                    return new JSONObject();
            }
        }
        if ("FoliaColorOsLyrics".equals(pluginId)) {
            return new JSONObject().put("supported", false);
        }
        if ("Diag".equals(pluginId)) {
            Log.i(TAG, "DIAG " + method + " :: " + (options == null ? "" : options.optString("detail", "")));
            return new JSONObject().put("ok", true);
        }
        if ("FoliaNativeStage".equals(pluginId) && "stats".equals(method)) {
            return new JSONObject().put("active", false).put("renderer", "none");
        }
        return new JSONObject();
    }
}
