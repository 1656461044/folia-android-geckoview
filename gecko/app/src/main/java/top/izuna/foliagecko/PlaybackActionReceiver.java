package top.izuna.foliagecko;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

/**
 * 当前文件：通知栏按钮的广播接收器。
 *
 * MediaSession 的媒体键走 Callback，但通知栏上的按钮是 PendingIntent，
 * 需要单独的 Receiver 把动作转成 mediaAction 事件推给页面。
 */
public class PlaybackActionReceiver extends BroadcastReceiver {
    private static final String TAG = "FoliaPlaybackAction";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null || action.isEmpty()) return;
        Log.i(TAG, "notification action: " + action);
        try {
            JSONObject p = new JSONObject();
            p.put("action", action);
            // 通过服务把事件送到桥（服务持有桥引用）
            Intent svc = new Intent(context, FoliaPlaybackService.class);
            svc.setAction("noop");
            FoliaPlaybackService.emitMediaAction(p);
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
