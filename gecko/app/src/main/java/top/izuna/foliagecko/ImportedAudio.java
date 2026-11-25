package top.izuna.foliagecko;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 当前文件：本地音乐「添加文件夹 / 添加文件」的 SAF 导入。
 *
 * 设计原则（2026-11-21 改版）：**一律不复制文件**。
 *
 * 早先版本会把选中的音频整份复制到 filesDir/imported-audio，
 * 用户实测 /sdcard/Music 里 55 个 flac 共 3.0GB —— 复制等于让同样内容在设备上存两份。
 * 现在按 A + B 两级策略，两条都不占额外空间：
 *
 *   A. MediaStore 命中：文件已经在系统媒体库里，直接返回 /media/<MediaStoreId>
 *      的 URL，连 URI 权限都不用持久化。绝大多数手机音乐都走这条。
 *
 *   B. SAF 命中：文件不在媒体库（SD 卡、下载目录、云盘等），
 *      takePersistableUriPermission 拿到持久读权限，把 ref→uri 映射存进
 *      SharedPreferences，播放时由 /audio/<ref> 现场 openInputStream 流式转发。
 *
 * ref 前缀必须是 `imported-`：
 *   web 端删除逻辑写死了 `refs.filter(r => r.startsWith('imported-'))`
 *   （见 bootstrap 里的 Cbe 函数），前缀不对会导致「删歌曲」完全失效。
 *
 * 删除（deleteImportedAudio）语义：
 *   - imported-uri-* ：先删本地残留文件（老版本复制的），再撤销持久 URI 权限并清映射
 *   - imported-ms-*  ：只从映射里移除，**绝不删用户的原文件**
 *   - 其它           ：当作已删除（幂等），保证前端列表能干净移除
 */
final class ImportedAudio {
    private static final String TAG = "FoliaAudioImport";
    private static final String DIR_NAME = "imported-audio";
    /** 老版本复制留下的目录，保留引用用于兼容删除。 */
    static final String LEGACY_DIR = DIR_NAME;
    private static final String PREFS = "folia-imported-audio";
    private static final long PICK_TIMEOUT_SEC = 300;

    static final String REF_MS = "imported-ms-";
    static final String REF_URI = "imported-uri-";
    static final String REF_FILE = "imported-file-";

    private static final String[] AUDIO_EXT = {
            ".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg",
            ".opus", ".ape", ".wma", ".mp4", ".mka"
    };

    private static Pending sPending;

    private ImportedAudio() {
    }

    static File directory(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), DIR_NAME);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create import dir");
        }
        return dir;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile int resultCode = Activity.RESULT_CANCELED;
        volatile Intent data;
        volatile String mode = "folder";
    }

    // ================= 选择 =================

    static JSONObject pick(Activity activity, String mode, int port) throws Exception {
        JSONObject out = new JSONObject();
        if (activity == null) {
            return out.put("cancelled", true).put("picked", 0)
                    .put("tracks", new JSONArray()).put("resultCode", 0)
                    .put("error", "no activity");
        }

        boolean asFolder = !"files".equals(mode);
        Pending pending = new Pending();
        pending.mode = asFolder ? "folder" : "files";

        Intent intent;
        if (asFolder) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }

        synchronized (ImportedAudio.class) {
            if (sPending != null) {
                return out.put("cancelled", true).put("picked", 0)
                        .put("tracks", new JSONArray()).put("resultCode", 0)
                        .put("error", "picker already open");
            }
            sPending = pending;
        }

        try {
            activity.runOnUiThread(() -> {
                try {
                    activity.startActivityForResult(intent, 0x5F01);
                } catch (Throwable t) {
                    Log.w(TAG, "startActivityForResult failed", t);
                    synchronized (ImportedAudio.class) {
                        Pending p = sPending;
                        sPending = null;
                        if (p != null) p.latch.countDown();
                    }
                }
            });

            boolean done = pending.latch.await(PICK_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!done) {
                Log.w(TAG, "picker timed out");
                return out.put("cancelled", true).put("picked", 0)
                        .put("tracks", new JSONArray()).put("resultCode", 0)
                        .put("error", "picker timeout");
            }
        } finally {
            synchronized (ImportedAudio.class) {
                if (sPending == pending) sPending = null;
            }
        }

        if (pending.resultCode != Activity.RESULT_OK || pending.data == null) {
            return out.put("cancelled", true).put("picked", 0)
                    .put("tracks", new JSONArray())
                    .put("resultCode", pending.resultCode);
        }

        List<Uri> uris = new ArrayList<>();
        Intent data = pending.data;

        if (asFolder) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                try {
                    int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    activity.getContentResolver().takePersistableUriPermission(treeUri, flags);
                } catch (Throwable t) {
                    Log.w(TAG, "takePersistableUriPermission(tree) failed", t);
                }
                String docId;
                try {
                    docId = DocumentsContract.getTreeDocumentId(treeUri);
                } catch (Throwable t) {
                    docId = null;
                }
                collectDocuments(activity, treeUri, docId, uris, 0);
            }
        } else {
            android.content.ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        }

        return buildTracks(activity, uris, port, pending.resultCode, data.getFlags());
    }

    private static void collectDocuments(Context context, Uri treeUri, String parentId,
                                         List<Uri> output, int depth) {
        if (parentId == null || depth > 12) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(children, projection, null, null, null);
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                String documentId = cursor.getString(0);
                String displayName = cursor.getString(1);
                String mimeType = cursor.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    collectDocuments(context, treeUri, documentId, output, depth + 1);
                } else if (isAudio(displayName, mimeType)) {
                    output.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "collectDocuments failed", t);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean isAudio(String displayName, String mimeType) {
        if (mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("audio/")) {
            return true;
        }
        String lower = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        for (String ext : AUDIO_EXT) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    // ================= 构造曲目（A + B，都不复制） =================

    private static JSONObject buildTracks(Activity activity, List<Uri> uris, int port,
                                          int resultCode, int grantFlags) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray tracks = new JSONArray();
        JSONArray failures = new JSONArray();
        ContentResolver resolver = activity.getContentResolver();
        SharedPreferences sp = prefs(activity);
        int msHits = 0;
        int uriHits = 0;

        for (Uri uri : uris) {
            try {
                String name = queryDisplayName(resolver, uri);
                if (name == null || name.trim().isEmpty()) {
                    name = "track-" + System.currentTimeMillis();
                }
                long size = queryFileSize(resolver, uri);

                // ---- A. 先试 MediaStore 命中：命中就不碰文件，直接用 /media/<id> ----
                long mediaId = findInMediaStore(activity, name, size);
                if (mediaId > 0) {
                    JSONObject track = new JSONObject();
                    String ref = REF_MS + mediaId;
                    track.put("id", ref);
                    track.put("fileName", name);
                    track.put("fileSize", size);
                    track.put("mimeType", guessMimeType(name));
                    track.put("url", "http://127.0.0.1:" + port + "/media/" + mediaId);
                    track.put("source", "mediastore");
                    tracks.put(track);
                    msHits++;
                    continue;
                }

                // ---- B. 不在媒体库：持久授权 + 记映射，播放时流式读取 ----
                try {
                    int flags = grantFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    resolver.takePersistableUriPermission(uri, flags);
                } catch (Throwable t) {
                    // 有些 provider 不支持持久授权，此时仍可按本次会话临时权限播放
                    Log.w(TAG, "persist uri permission failed: " + t.getMessage());
                }

                String ref = REF_URI + hashOf(name, size);
                sp.edit().putString(ref, uri.toString()).apply();
                // 记下大小，服务端据此发 Content-Length / Content-Range
                activity.getApplicationContext()
                        .getSharedPreferences("folia-imported-size", Context.MODE_PRIVATE)
                        .edit().putLong(ref, size > 0 ? size : -1).apply();

                JSONObject track = new JSONObject();
                track.put("id", ref);
                track.put("fileName", name);
                track.put("fileSize", size);
                track.put("mimeType", guessMimeType(name));
                track.put("url", "http://127.0.0.1:" + port + "/audio/" + ref);
                track.put("source", "saf");
                tracks.put(track);
                uriHits++;
            } catch (Throwable t) {
                Log.w(TAG, "build track failed", t);
                JSONObject fail = new JSONObject();
                fail.put("message", t.getClass().getSimpleName() + ": " + t.getMessage());
                fail.put("uri", String.valueOf(uri));
                failures.put(fail);
            }
        }

        Log.i(TAG, "pick done: picked=" + uris.size() + " mediastore=" + msHits
                + " saf=" + uriHits + " failures=" + failures.length() + " copied=0");
        return out.put("cancelled", false)
                .put("picked", uris.size())
                .put("copied", 0)
                .put("tracks", tracks)
                .put("failures", failures)
                .put("resultCode", resultCode)
                .put("port", port);
    }

    /** 在 MediaStore 里按「文件名 + 大小」找同一个文件，返回其 id；找不到返回 -1。 */
    private static long findInMediaStore(Context context, String displayName, long size) {
        if (displayName == null || displayName.isEmpty()) return -1;
        ContentResolver cr = context.getContentResolver();
        String[] projection = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.SIZE};
        String selection;
        String[] args;
        if (size > 0) {
            selection = MediaStore.Audio.Media.DISPLAY_NAME + "=? AND "
                    + MediaStore.Audio.Media.SIZE + "=?";
            args = new String[]{displayName, String.valueOf(size)};
        } else {
            selection = MediaStore.Audio.Media.DISPLAY_NAME + "=?";
            args = new String[]{displayName};
        }
        Cursor c = null;
        try {
            c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                    selection, args, null);
            if (c != null && c.moveToFirst()) {
                return c.getLong(0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "media store lookup failed", t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return -1;
    }

    // ================= 服务端取流用 =================

    /** 把 ref 解析成 SAF 的 content:// URI；不是 SAF 引用则返回 null。 */
    static Uri resolveSafUri(Context context, String ref) {
        if (ref == null || !ref.startsWith(REF_URI)) return null;
        String raw = prefs(context).getString(ref, null);
        if (raw == null) return null;
        try {
            return Uri.parse(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 老版本复制留下的文件（imported-file-* 或磁盘上确实存在的同名文件）。 */
    static File resolveLegacyFile(Context context, String ref) {
        if (ref == null || ref.contains("/") || ref.contains("\\") || ref.contains("..")) {
            return null;
        }
        File f = new File(directory(context), ref);
        return f.isFile() ? f : null;
    }

    // ================= 删除 =================

    /**
     * 删除导入条目。
     *
     * 关键点：**只清理我们自己造的东西**（私有目录副本、URI 映射、持久授权），
     * 绝不删用户的原始音频文件。MediaStore 引用直接视为已移除。
     */
    static JSONObject delete(Context context, JSONArray refs) throws Exception {
        JSONArray deleted = new JSONArray();
        JSONArray failed = new JSONArray();
        SharedPreferences sp = prefs(context);
        ContentResolver resolver = context.getContentResolver();

        for (int i = 0; i < refs.length(); i++) {
            String ref = refs.optString(i, null);
            if (ref == null || ref.isEmpty()) continue;
            try {
                if (ref.startsWith(REF_MS)) {
                    // 媒体库引用：文件属于系统，不能删，只从库里移除
                    sp.edit().remove(ref).apply();
                    deleted.put(ref);
                } else if (ref.startsWith(REF_URI)) {
                    String raw = sp.getString(ref, null);
                    if (raw != null) {
                        try {
                            resolver.releasePersistableUriPermission(Uri.parse(raw),
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Throwable t) {
                            Log.w(TAG, "release uri permission failed: " + t.getMessage());
                        }
                    }
                    sp.edit().remove(ref).apply();
                    context.getApplicationContext()
                            .getSharedPreferences("folia-imported-size", Context.MODE_PRIVATE)
                            .edit().remove(ref).apply();
                    // 清掉可能残留的旧副本（同名文件）
                    File legacy = resolveLegacyFile(context, ref);
                    if (legacy != null) {
                        //noinspection ResultOfMethodCallIgnored
                        legacy.delete();
                    }
                    deleted.put(ref);
                } else {
                    // imported-file-* 或历史 imp-*：删私有目录里的副本
                    File f = resolveLegacyFile(context, ref);
                    if (f == null || f.delete()) {
                        deleted.put(ref);
                    } else {
                        failed.put(ref);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "delete failed: " + ref, t);
                failed.put(ref);
            }
        }

        Log.i(TAG, "delete done: requested=" + refs.length()
                + " deleted=" + deleted.length() + " failed=" + failed.length());
        return new JSONObject().put("deleted", deleted).put("failed", failed);
    }

    /** 清掉目录里没有任何引用的孤儿文件（防止老版本副本永久占空间）。 */
    static void sweepOrphans(Context context) {
        try {
            File dir = directory(context);
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) return;
            SharedPreferences sp = prefs(context);
            int removed = 0;
            for (File f : files) {
                String name = f.getName();
                boolean referenced = sp.contains(REF_URI + name)
                        || sp.contains(REF_FILE + name)
                        || sp.contains(name);
                // 只有当映射里没有任何线索时才认为它是孤儿
                if (!referenced) {
                    if (f.delete()) removed++;
                }
            }
            if (removed > 0) Log.i(TAG, "swept " + removed + " orphan file(s)");
        } catch (Throwable t) {
            Log.w(TAG, "sweepOrphans failed", t);
        }
    }

    // ================= 工具 =================

    static String guessMimeType(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".m4a") || n.endsWith(".mp4")) return "audio/mp4";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".opus")) return "audio/opus";
        if (n.endsWith(".ape")) return "audio/x-ape";
        if (n.endsWith(".wma")) return "audio/x-ms-wma";
        return "application/octet-stream";
    }

    private static String hashOf(String name, long size) {
        String base = name + "|" + size;
        return Integer.toHexString(base.hashCode());
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? null : last.replaceAll("^.*/", "");
    }

    private static long queryFileSize(ContentResolver resolver, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return 0;
    }

    static void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != 0x5F01) return;
        Pending p;
        synchronized (ImportedAudio.class) {
            p = sPending;
            sPending = null;
        }
        if (p == null) return;
        p.resultCode = resultCode;
        p.data = data;
        p.latch.countDown();
    }
}
