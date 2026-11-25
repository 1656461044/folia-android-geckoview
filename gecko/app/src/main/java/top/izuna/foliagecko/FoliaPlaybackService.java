package top.izuna.foliagecko;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 当前文件：前台播放服务（后台播放 + 系统媒体键 + 通知栏/锁屏控制）。
 *
 * 与原版 FoliaPlaybackService 的协议保持一致：
 *   - 前端通过 FoliaNative.updatePlaybackState 推送曲目信息
 *   - 系统媒体键（车机方控、蓝牙、锁屏）触发 MediaSession.Callback
 *   - 回调通过 NativeBridge.emitEvent("mediaAction", {action}) 推回页面
 *   - 前端监听 mediaAction 并派发 folia-native-media-action 事件
 *
 * 后台存活靠 startForeground + WakeLock；网络播放另加 WifiLock。
 */
public class FoliaPlaybackService extends Service {
    private static final String TAG = "FoliaPlayback";
    private static final String CHANNEL_ID = "folia_playback";
    private static final int NOTIFICATION_ID = 31009;

    private MediaSession mediaSession;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
    private final OkHttpClient artworkClient = new OkHttpClient();

    private volatile String title = "";
    private volatile String artist = "";
    private volatile String album = "";
    private volatile String coverUrl = "";
    private volatile String songKey = "";
    private volatile boolean playing = false;
    private volatile long positionMs = 0;
    private volatile long durationMs = 0;
    private volatile Bitmap artwork = null;
    private volatile String appliedCoverUrl = "";

    /** 与 NativeBridge 通信的静态引用（Activity 创建桥时注入）。 */
    private static volatile NativeBridge bridgeRef;

    /**
     * 音频焦点。
     *
     * 车机（BYD DiLink）的媒体方案不是私有广播，而是标准 Android：
     *   1. com.byd.mediacontroller 用 MediaSessionManager.getActiveSessions(null) 发现播放器
     *   2. 用 AudioManager.getCurrentAudioFocusPackage() 决定「当前播放的是谁」
     *      （见 MediaTaskManager.registerSessionCallbacks：按 focus package 匹配 MediaController）
     *   3. 匹配上之后，方控/面板媒体键 → MediaController.getTransportControls().play()/pause()/
     *      skipToNext()/skipToPrevious()/seekTo() → 落到我们的 MediaSession.Callback
     *
     * 也就是说：**必须持有音频焦点，否则车机不会把我们当成当前播放器。**
     * GeckoView 自己也会申请焦点，但它在后台/被暂停时可能放弃，
     * 前台服务这里再申请一次，双保险（同一 uid 重复申请是幂等的）。
     */
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private volatile boolean hasAudioFocus = false;

    static void attachBridge(NativeBridge bridge) {
        bridgeRef = bridge;
    }

    /** 供通知栏按钮直接投递媒体动作。 */
    static void emitMediaAction(JSONObject payload) {
        NativeBridge b = bridgeRef;
        if (b != null) {
            b.emitEvent("mediaAction", payload);
            Log.i(TAG, "notification action -> " + payload.optString("action"));
        } else {
            Log.w(TAG, "no bridge attached, dropping mediaAction");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        mediaSession = new MediaSession(this, "FoliaPlayback");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                emit("play");
            }

            @Override
            public void onPause() {
                emit("pause");
            }

            @Override
            public void onSkipToNext() {
                emit("next");
            }

            @Override
            public void onSkipToPrevious() {
                emit("previous");
            }

            @Override
            public void onStop() {
                emit("stop");
            }

            @Override
            public void onSeekTo(long pos) {
                try {
                    JSONObject p = new JSONObject();
                    p.put("action", "seek");
                    p.put("position", pos);
                    emitPayload("mediaAction", p);
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        });
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);

        // 车机的媒体卡片/通知点一下要能回到应用；也满足 API 33+ 的会话归属要求。
        try {
            Intent openIntent = new Intent(this, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
            mediaSession.setSessionActivity(
                    PendingIntent.getActivity(this, 100, openIntent, piFlags));
        } catch (Throwable t) {
            Log.w(TAG, "setSessionActivity failed", t);
        }

        mediaSession.setActive(true);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(this::onAudioFocusChange)
                    .build();
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Folia:Playback");
            wakeLock.setReferenceCounted(false);
        }
        WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int mode = Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "Folia:Playback");
            wifiLock.setReferenceCounted(false);
        }

        // 立刻进入前台，否则 Android 8+ 随时会杀掉服务
        startForeground(NOTIFICATION_ID, buildNotification());
        Log.i(TAG, "playback service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            startForeground(NOTIFICATION_ID, buildNotification());
            return START_STICKY;
        }
        String action = intent.getAction();
        if ("clear".equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 从页面推来的最新状态
        String t = intent.getStringExtra("title");
        if (t != null) title = t;
        String a = intent.getStringExtra("artist");
        if (a != null) artist = a;
        String al = intent.getStringExtra("album");
        if (al != null) album = al;
        String c = intent.getStringExtra("coverUrl");
        if (c != null) coverUrl = c;
        String k = intent.getStringExtra("songKey");
        if (k != null) songKey = k;
        playing = intent.getBooleanExtra("playing", playing);
        positionMs = intent.getLongExtra("position", positionMs);
        durationMs = intent.getLongExtra("duration", durationMs);

        applyMetadata();
        updateLocks();
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
        startForeground(NOTIFICATION_ID, buildNotification());

        // 封面异步加载
        loadArtwork(coverUrl);
        return START_STICKY;
    }

    /** 更新播放状态（供 NativeBridge 调用）。 */
    void updateState(String t, String a, String al, String c, String k,
                     boolean isPlaying, long position, long duration) {
        this.title = t == null ? "" : t;
        this.artist = a == null ? "" : a;
        this.album = al == null ? "" : al;
        this.coverUrl = c == null ? "" : c;
        this.songKey = k == null ? "" : k;
        this.playing = isPlaying;
        this.positionMs = Math.max(0, position);
        this.durationMs = Math.max(0, duration);

        mainHandler.post(() -> {
            applyMetadata();
            updateLocks();
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
            loadArtwork(this.coverUrl);
        });
    }

    private void applyMetadata() {
        if (mediaSession == null) return;
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title.isEmpty() ? "Folia" : title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
        if (artwork != null) {
            md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
            md.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork);
        }
        mediaSession.setMetadata(md.build());

        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SEEK_TO;
        PlaybackState.Builder st = new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        positionMs, playing ? 1.0f : 0.0f);
        mediaSession.setPlaybackState(st.build());
    }

    private void updateLocks() {
        try {
            if (playing) {
                requestAudioFocus();
                if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
                if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
            } else {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            }
        } catch (Throwable t) {
            Log.w(TAG, "update locks failed", t);
        }
    }

    /** 申请音频焦点：车机据此判定「当前播放器是谁」。 */
    private void requestAudioFocus() {
        if (hasAudioFocus || audioManager == null) return;
        try {
            int ret;
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
                ret = audioManager.requestAudioFocus(focusRequest);
            } else {
                ret = audioManager.requestAudioFocus(null,
                        AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
            hasAudioFocus = ret == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            Log.i(TAG, "requestAudioFocus -> " + hasAudioFocus + " (ret=" + ret + ")");
        } catch (Throwable t) {
            Log.w(TAG, "requestAudioFocus failed", t);
        }
    }

    private void abandonAudioFocus() {
        if (!hasAudioFocus || audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                audioManager.abandonAudioFocus(null);
            }
            hasAudioFocus = false;
            Log.i(TAG, "abandonAudioFocus");
        } catch (Throwable t) {
            Log.w(TAG, "abandonAudioFocus failed", t);
        }
    }

    /** 被其它应用抢焦点时让页面暂停，避免两路声音叠在一起。 */
    private void onAudioFocusChange(int change) {
        Log.i(TAG, "onAudioFocusChange " + change);
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                hasAudioFocus = false;
                emit("pause");
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                hasAudioFocus = true;
                break;
            default:
                break;
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, flags);

        Intent prev = new Intent(this, PlaybackActionReceiver.class).setAction("previous");
        Intent toggle = new Intent(this, PlaybackActionReceiver.class)
                .setAction(playing ? "pause" : "play");
        Intent next = new Intent(this, PlaybackActionReceiver.class).setAction("next");
        PendingIntent piPrev = PendingIntent.getBroadcast(this, 2, prev, flags);
        PendingIntent piToggle = PendingIntent.getBroadcast(this, 1, toggle, flags);
        PendingIntent piNext = PendingIntent.getBroadcast(this, 3, next, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.isEmpty() ? "Folia" : title)
                .setContentText(artist)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Previous", piPrev)
                .addAction(playing ? android.R.drawable.ic_media_pause
                                : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play", piToggle)
                .addAction(android.R.drawable.ic_media_next, "Next", piNext)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (artwork != null) b.setLargeIcon(artwork);
        return b.build();
    }

    private void loadArtwork(final String url) {
        if (url == null || url.isEmpty() || url.equals(appliedCoverUrl)) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;
        artworkExecutor.execute(() -> {
            Bitmap bmp = null;
            try (Response resp = artworkClient.newCall(
                    new Request.Builder().url(url)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Mobile)")
                            .build()).execute()) {
                ResponseBody body = resp.body();
                if (resp.isSuccessful() && body != null) {
                    try (java.io.InputStream in = body.byteStream()) {
                        bmp = BitmapFactory.decodeStream(in);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "load artwork failed", t);
            }
            if (bmp != null) {
                final Bitmap resolved = bmp;
                artwork = resolved;
                appliedCoverUrl = url;
                mainHandler.post(() -> {
                    applyMetadata();
                    NotificationManager nm =
                            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
                });
            }
        });
    }

    /** 媒体键动作 -> 页面。 */
    private void emit(String action) {
        try {
            JSONObject p = new JSONObject();
            p.put("action", action);
            emitPayload("mediaAction", p);
        } catch (Exception ignored) {
            // 忽略
        }
    }

    private void emitPayload(String name, JSONObject payload) {
        NativeBridge b = bridgeRef;
        if (b != null) {
            b.emitEvent(name, payload);
            Log.i(TAG, "media key -> " + payload.optString("action"));
        } else {
            Log.w(TAG, "no bridge attached, dropping " + name);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Folia playback", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 从最近任务划掉时保持播放（与原版一致）
        Log.i(TAG, "task removed, keep playing");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "playback service destroyed");
        abandonAudioFocus();
        updateLocks();
        artworkExecutor.shutdownNow();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }
}
