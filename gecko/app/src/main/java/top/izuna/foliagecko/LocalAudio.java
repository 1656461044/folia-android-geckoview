package top.izuna.foliagecko;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 当前文件：本地音乐扫描（MediaStore）与音频文件服务。
 *
 * Folia 前端通过 FoliaNative.scanLocalAudio 拿曲目列表，
 * 播放时用 http://127.0.0.1:<port>/media/<id> 回源，由 LocalAssetServer 转发到 MediaStore。
 */
final class LocalAudio {
    private final Activity activity;

    LocalAudio(Activity activity) {
        this.activity = activity;
    }

    /** 是否已获得读取音频权限。 */
    boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return activity.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 请求读取音频权限（异步，结果需下次扫描体现）。 */
    void requestPermission() {
        String perm = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        activity.requestPermissions(new String[]{perm}, 1001);
    }

    /** 扫描 MediaStore 音频，返回 Folia 期望的 tracks 结构。 */
    JSONArray scan() throws JSONException {
        JSONArray tracks = new JSONArray();
        if (!hasPermission()) return tracks;

        ContentResolver cr = activity.getContentResolver();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0";

        try (Cursor c = cr.query(uri, projection, selection, null,
                MediaStore.Audio.Media.TITLE + " ASC")) {
            if (c == null) return tracks;
            int idxId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int idxTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int idxArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int idxAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int idxDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int idxData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);

            while (c.moveToNext()) {
                long id = c.getLong(idxId);
                String title = c.getString(idxTitle);
                String path = c.getString(idxData);
                String fileName = path != null
                        ? path.substring(path.lastIndexOf('/') + 1) : (title + ".mp3");

                JSONObject t = new JSONObject();
                t.put("id", id);
                t.put("title", title);
                t.put("artist", c.getString(idxArtist));
                t.put("album", c.getString(idxAlbum));
                t.put("duration", c.getLong(idxDur));
                t.put("fileName", fileName);
                t.put("filePath", path == null ? "" : path);
                tracks.put(t);
            }
        }
        return tracks;
    }
}
