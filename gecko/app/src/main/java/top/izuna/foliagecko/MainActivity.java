package top.izuna.foliagecko;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/**
 * 当前文件：GeckoView 版 Folia 的主界面。
 *
 * 架构：
 *   GeckoView  <-> http://127.0.0.1:<port>  (LocalAssetServer)
 *   Folia 前端 <-> window.Capacitor.Plugins.* (folia-shim.js 内联注入)
 *   shim       <-> POST /__native/call      (NativeBridge)
 *
 * 选择 HTTP 而非 WebExtension 的原因：GeckoView 139 安装本地扩展强制要求签名
 * （InstallException -5），MV2 的 nativeMessaging 权限也被判 invalid，
 * 本地开发无法绕过。HTTP 通道不需要任何签名。
 */
public class MainActivity extends Activity {
    private static final String TAG = "FoliaGecko";

    private GeckoRuntime runtime;
    private GeckoSession session;
    private GeckoView geckoView;
    private LocalAssetServer assetServer;
    private NativeBridge bridge;

    /**
     * GeckoRuntime 是**进程级单例**：一个进程只能 create 一次，
     * 重复 create 会抛 IllegalStateException（实测在 Activity 重建时必崩）。
     * 所以这里用静态字段持有，整个进程复用。
     */
    private static volatile GeckoRuntime sRuntime;

    private static GeckoRuntime obtainRuntime(android.content.Context ctx) {
        if (sRuntime == null) {
            synchronized (MainActivity.class) {
                if (sRuntime == null) {
                    GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                            .configFilePath("geckoview-config.yaml")
                            .javaScriptEnabled(true)
                            .consoleOutput(true)
                            .remoteDebuggingEnabled(true)
                            .webManifest(true)
                            .build();
                    sRuntime = GeckoRuntime.create(ctx.getApplicationContext(), settings);
                }
            }
        }
        return sRuntime;
    }

    /**
     * 与 GeckoRuntime 一样，GeckoSession 也做成进程级单例：
     *   - Activity 重建时复用它，页面状态（播放队列、当前歌曲）不会丢
     *   - 退出 Activity 后会话仍在，后台播放不受影响
     */
    private static volatile GeckoSession sSession;

    private static synchronized GeckoSession obtainSession(GeckoRuntime runtime) {
        if (sSession == null) {
            sSession = new GeckoSession();
            sSession.open(runtime);
            GeckoSessionSettingsCompat.apply(sSession);
        } else if (!sSession.isOpen()) {
            sSession.open(runtime);
            GeckoSessionSettingsCompat.apply(sSession);
        }
        return sSession;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 0. 屏幕方向 + 沉浸式窗口（竖屏支持在这里生效）
        //    必须在 setContentView 之前调用：layoutInDisplayCutoutMode 和
        //    decorFitsSystemWindows 都是窗口级属性，晚了会先闪一下再跳。
        PhoneLayout.applyOrientation(this);
        PhoneLayout.applyWindow(this);

        // 0.5 清理历史遗留的私有音频副本
        //     老版本导入会把整份音频复制到 filesDir/imported-audio（用户实测 3GB）。
        //     现在改成「MediaStore 命中直连 + SAF 流式读取」，不再复制；
        //     这里把没人引用的孤儿副本扫掉，把被白占的空间还回去。
        //     放后台线程，别拖慢启动。
        final android.content.Context appCtx = getApplicationContext();
        new Thread(() -> ImportedAudio.sweepOrphans(appCtx), "folia-import-sweep").start();

        // 1. GeckoRuntime（进程级单例，重复进入 Activity 不能重建）
        runtime = obtainRuntime(this);

        // 2. 桥与本地资源服务（服务也要进程级复用：端口固定，重进时不能重开）
        bridge = obtainBridge(this);
        if (sAssetServer == null) {
            sAssetServer = new LocalAssetServer(getApplicationContext(), getAssets(), bridge);
            int started = sAssetServer.start();
            if (started <= 0) {
                Log.e(TAG, "asset server failed");
                finish();
                return;
            }
            sPort = started;
        }
        assetServer = sAssetServer;
        final int port = sPort;
        bridge.setPortSupplier(assetServer::getPort);
        // 系统媒体音量 -> 页面（车机音量键/旋钮直接生效）
        bridge.attachSystemVolume();
        installBackHandler();

        // 3. 视图
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#09090b"));
        geckoView = new GeckoView(this);
        root.addView(geckoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // 4. 会话（进程级复用，Activity 重进不重建）
        session = obtainSession(runtime);
        geckoView.setSession(session);

        // 5. 只在首次加载页面；重进 Activity 时页面还在，不需要重载
        if (!pageLoaded) {
            pageLoaded = true;
            // 用安装时间做 cache-buster：
            // 本地服务虽然发了 Cache-Control: no-store，但 Gecko 的 HTTP 缓存
            // 在同一个固定 origin（127.0.0.1:47821）上仍会把旧的 index.html
            // （内含旧版内联 shim）喂回来，表现为「换包后行为没变」。
            // 加一个包更新后才变化的参数：同版本内 URL 稳定（localStorage/Cookie 不受影响），
            // 每次覆盖安装则强制重新拉一遍。
            long stamp;
            try {
                stamp = getPackageManager()
                        .getPackageInfo(getPackageName(), 0).lastUpdateTime;
            } catch (Throwable t) {
                stamp = System.currentTimeMillis();
            }
            String url = "http://127.0.0.1:" + port + "/index.html?v=" + stamp;
            Log.i(TAG, "loading " + url);
            session.loadUri(url);
        } else {
            Log.i(TAG, "page already loaded, reusing session");
        }
    }

    /** 页面是否已经加载过（静态，跨 Activity 实例保留）。 */
    private static volatile boolean pageLoaded = false;
    /** 本地资源服务与端口也是进程级单例（端口固定，重进 Activity 不能重开）。 */
    private static volatile LocalAssetServer sAssetServer;
    private static volatile int sPort = -1;

    /**
     * 桥同样是进程级单例。
     *
     * LocalAssetServer 与 FoliaPlaybackService 都长期持有桥引用：
     * 页面轮询 /__native/poll 走 server 里那份，媒体键回传走 service 里那份。
     * 每次 onCreate 都 new 一个的话，这两条通道会指向不同对象，
     * 媒体键/返回键事件就投进没人取的队列了。这里只换 Activity 引用。
     */
    private static volatile NativeBridge sBridge;

    private static synchronized NativeBridge obtainBridge(MainActivity a) {
        if (sBridge == null) {
            sBridge = new NativeBridge(a);
        } else {
            sBridge.updateActivity(a);
        }
        return sBridge;
    }

    @Override
    protected void onDestroy() {
        // 注意：这里**不关闭 GeckoSession、不停 LocalAssetServer**。
        //
        // Folia 的播放器是页面里的 WebAudio/audio 元素，会话一关就等于停止播放；
        // 而音频流要从 127.0.0.1 拉，服务一停声音就断。
        // 两者都改成跟随进程生命周期（进程被系统回收时自然释放），
        // 这样按返回键退出 Activity 后，后台播放仍能继续。
        bridge = null;
        super.onDestroy();
    }

    /**
     * 切到后台时不要销毁浏览器会话——Folia 的播放器跑在页面里，
     * 销毁就等同于停止播放。这里保持会话存活，只降低优先级。
     */
    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (session != null) session.setActive(false);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (session != null) session.setActive(true);
        } catch (Throwable ignored) {
            // 忽略
        }
        // 从后台回来时系统可能重置过窗口标志，重新铺一次
        PhoneLayout.applyWindow(this);
    }

    /**
     * 屏幕旋转 / 尺寸变化。
     *
     * manifest 里已经声明了 orientation|screenSize 等 configChanges，
     * 所以这里**不会**重建 Activity（重建会丢掉 Gecko 会话状态和播放队列）。
     * 但窗口属性需要重新铺一遍，并且要通知 GeckoView 重新布局。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.i(TAG, "onConfigurationChanged orientation="
                + (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                ? "landscape" : "portrait")
                + " size=" + newConfig.screenWidthDp + "x" + newConfig.screenHeightDp);
        PhoneLayout.applyWindow(this);
        if (geckoView != null) {
            geckoView.requestLayout();
            geckoView.post(() -> {
                if (geckoView != null) geckoView.requestLayout();
            });
        }
    }

    /** 统一的返回处理入口（旧系统 onBackPressed 与 Android13+ 的 callback 都走这里）。 */
    private void dispatchBack() {
        if (bridge == null) {
            finish();
            return;
        }
        bridge.requestBack(new NativeBridge.BackResult() {
            @Override
            public void onHandled() {
                // 页面已经关掉了一层界面，什么都不用做
            }

            @Override
            public void onNotHandled() {
                // 页面没有可返回的层级 —— 这才是「回到桌面」的时机
                runOnUiThread(MainActivity.this::finish);
            }
        });
    }

    @Override
    public void onBackPressed() {
        dispatchBack();
    }

    /**
     * SAF 选择器的结果回调（本地音乐「添加文件夹 / 添加文件」）。
     *
     * 桥的调用线程在 ImportedAudio.pick() 里用 CountDownLatch 等着，
     * 这里必须转发过去把它放行；不转发页面就会一直等到 300 秒超时。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ImportedAudio.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Android 13+ 用 OnBackInvokedDispatcher 接管返回键。
     * 不注册的话系统会直接 finish 当前 Activity（表现为「返回直接回桌面」）。
     */
    private void installBackHandler() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        try {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::dispatchBack);
        } catch (Throwable t) {
            Log.w(TAG, "install back handler failed", t);
        }
    }

    GeckoView getGeckoView() {
        return geckoView;
    }

    /** 给 NativeBridge 用：设置方向后需要立刻生效。 */
    void applyPhoneLayout() {
        runOnUiThread(() -> {
            PhoneLayout.applyOrientation(this);
            PhoneLayout.applyWindow(this);
            if (geckoView != null) geckoView.requestLayout();
        });
    }
}
