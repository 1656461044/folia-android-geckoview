package top.izuna.foliagecko;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 当前文件：把系统媒体音量接到应用内。
 *
 * 问题背景：Folia 的音量是应用内 WebAudio 的 softwareVolume，完全独立于
 * Android 的媒体音量流。车机上表现为「按音量键没反应，声音大小只能靠 App 内滑块」。
 * 原版是 WebView 走系统音频流，天然跟随系统音量；GeckoView 不会自动这么做。
 *
 * 实现要点（踩过的坑）：
 *   - 不能用 ContentObserver(Settings.System.CONTENT_URI) 监听音量：
 *     Android 6+ 起 STREAM_MUSIC 音量不再写 Settings.System，该 URI 收不到回调。
 *   - 正确做法是轮询 AudioManager.getStreamVolume(STREAM_MUSIC)，成本极低
 *     （一次系统调用），而且能覆盖音量键、车机旋钮、蓝牙按键等所有来源。
 */
final class SystemVolume {
    private static final String TAG = "FoliaGeckoVolume";
    /** 轮询间隔：既能跟上按键手感，又不会有可感知的功耗。 */
    private static final long POLL_MS = 300;

    private final Context context;
    private final AudioManager audioManager;
    private final NativeBridge bridge;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int lastPercent = -1;
    private boolean running;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            pushCurrent();
            handler.postDelayed(this, POLL_MS);
        }
    };

    SystemVolume(Context context, NativeBridge bridge) {
        this.context = context.getApplicationContext();
        this.bridge = bridge;
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    void start() {
        if (audioManager == null || running) return;
        running = true;
        pushCurrent();
        handler.postDelayed(poller, POLL_MS);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(poller);
    }

    /** 当前系统媒体音量（0.0 ~ 1.0）。 */
    double currentFraction() {
        if (audioManager == null) return 1.0;
        try {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) return 1.0;
            return Math.max(0.0, Math.min(1.0, (double) cur / (double) max));
        } catch (Throwable t) {
            return 1.0;
        }
    }

    int currentPercent() {
        return (int) Math.round(currentFraction() * 100);
    }

    /** 音量变化时推给页面。 */
    private void pushCurrent() {
        int percent = currentPercent();
        if (percent == lastPercent) return;
        lastPercent = percent;
        Log.i(TAG, "system media volume -> " + percent + "%");
        try {
            org.json.JSONObject payload = new org.json.JSONObject();
            payload.put("percent", percent);
            payload.put("fraction", currentFraction());
            bridge.emitEvent("systemVolume", payload);
        } catch (org.json.JSONException ignored) {
            // 忽略
        }
    }
}
