package top.izuna.foliagecko;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.app.UiModeManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 当前文件：屏幕方向、沉浸式布局、刘海屏适配。
 *
 * 对应原版 APK 的 PhoneLayoutOrientation + PhoneFitCutout + AdaptiveLayout 三个类的合并版。
 *
 * 为什么需要它：
 *   ① 车机是固定横屏（1920×1080），手机是竖屏为主 —— 之前 manifest 写死
 *      `sensorLandscape`，手机上就被强行掰成横屏，竖着拿根本没法用。
 *   ② Folia 前端在「设置 → Android 壳」里有一组开关（手机适配 / 自适应显示），
 *      它们通过 FoliaNative.setPhoneFitLayout / setAdaptiveLayout 下发，
 *      web 包里的 ponderRegistry-CbAMeSS5.js 会真的调用，原生不实现就是空转。
 *
 * 方向取值约定（和原版一致，别改）：
 *   - 未启用「手机适配」：不锁定，交给系统（车机仍锁横屏，见 applyOrientation）
 *   - 启用 + landscape ：SCREEN_ORIENTATION_SENSOR_LANDSCAPE
 *   - 启用 + portrait  ：SCREEN_ORIENTATION_SENSOR_PORTRAIT
 */
final class PhoneLayout {
    private static final String TAG = "FoliaPhoneLayout";
    private static final String PREFS_NAME = "folia_native_prefs";
    private static final String KEY_ENABLED = "phone_fit_layout_enabled";
    private static final String KEY_ORIENTATION = "phone_fit_orientation";
    private static final String KEY_ADAPTIVE = "adaptive_layout";

    static final String MODE_LANDSCAPE = "landscape";
    static final String MODE_PORTRAIT = "portrait";

    private PhoneLayout() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 是否是车机（车载 Android）。仅用于诊断展示，不再影响方向策略。 */
    static boolean isAutomotive(Context context) {
        try {
            if (context.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                return true;
            }
            UiModeManager um = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
            return um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 「手机适配」开启时用户没选过方向时的默认值。
     *
     * 统一竖屏：车机屏幕也能旋转，不再假设它一定横屏。
     * 这个值只在用户开启手机适配、但还没显式选方向时用作初始值。
     */
    static String defaultOrientation(Context context) {
        return MODE_PORTRAIT;
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static String getOrientation(Context context) {
        String v = prefs(context).getString(KEY_ORIENTATION, null);
        if (MODE_LANDSCAPE.equals(v) || MODE_PORTRAIT.equals(v)) return v;
        return defaultOrientation(context);
    }

    static void update(Context context, boolean enabled, String orientation) {
        String normalized = MODE_LANDSCAPE.equals(orientation) ? MODE_LANDSCAPE : MODE_PORTRAIT;
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_ORIENTATION, normalized)
                .apply();
    }

    /**
     * 应用屏幕方向。
     *
     * 未启用「手机适配」时：**一律 FULL_SENSOR，不锁方向**。
     *
     * 曾经这里对车机特判锁 SENSOR_LANDSCAPE，依据是「车机是固定横屏面板」，
     * 但用户指出车机屏幕本身可以旋转（DiLink 屏幕可转），锁死横屏反而是错的。
     * 现在车机与手机同策略：竖着拿就竖屏，转过来就横屏。
     *
     * 启用「手机适配」后仍按用户显式选择的方向锁（SENSOR_LANDSCAPE / SENSOR_PORTRAIT），
     * 那是用户在设置里主动要求的，属于覆盖默认值。
     */
    static void applyOrientation(Activity activity) {
        if (activity == null) return;
        try {
            int requested;
            if (!isEnabled(activity)) {
                // 不锁方向：车机可旋转、手机可旋转，统一跟随系统/传感器
                requested = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
            } else if (MODE_LANDSCAPE.equals(getOrientation(activity))) {
                requested = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            } else {
                requested = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
            }
            if (activity.getRequestedOrientation() != requested) {
                Log.i(TAG, "requestedOrientation -> " + requested
                        + " (automotive=" + isAutomotive(activity)
                        + " phoneFit=" + isEnabled(activity) + ")");
                activity.setRequestedOrientation(requested);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyOrientation failed", t);
        }
    }

    static boolean isAdaptiveLayoutEnabled(Context context) {
        // 默认开启：本壳本来就是全屏沉浸式（车机一直是这个观感），
        // 返回 false 会让设置里的开关和实际表现对不上。
        return prefs(context).getBoolean(KEY_ADAPTIVE, true);
    }

    static void setAdaptiveLayout(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ADAPTIVE, enabled).apply();
    }

    /**
     * 应用窗口样式：沉浸式全屏 + 刘海屏适配。
     *
     * 刘海统一用 LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT，不按设备分叉。
     *
     * 曾经这里是「车机 SHORT_EDGES / 手机 DEFAULT」，理由是车机无刘海固定大屏。
     * 但车机屏幕可以旋转，转成竖屏后同样可能遇到圆角/遮挡；
     * 而 GeckoView 不支持 env(safe-area-inset-*)，页面自己无法避让，
     * 只能靠系统留安全区。对**无刘海设备**来说 DEFAULT 与 SHORT_EDGES 表现一致
     * （没有可避让的区域，画面照样铺满），所以统一 DEFAULT 既不影响车机观感，
     * 又能在有挖孔/圆角的姿态下不盖住内容。
     */
    static void applyWindow(Activity activity) {
        if (activity == null) return;
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            boolean adaptive = isAdaptiveLayoutEnabled(activity);

            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams attrs = window.getAttributes();
                attrs.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
                window.setAttributes(attrs);
            }

            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);

            // adaptive = 沉浸式（内容铺到系统栏下面）；否则让系统栏占位
            WindowCompat.setDecorFitsSystemWindows(window, !adaptive);

            WindowInsetsControllerCompat controller =
                    WindowCompat.getInsetsController(window, window.getDecorView());
            if (controller != null) {
                if (adaptive) {
                    controller.hide(WindowInsetsCompat.Type.systemBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT);
                }
            }

            View decor = window.getDecorView();
            if (decor != null) {
                decor.setBackgroundColor(Color.parseColor("#09090b"));
                // 系统栏占位时也不额外加内边距，交给内容自己处理
                decor.setPadding(0, 0, 0, 0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyWindow failed", t);
        }
    }

    /** 刘海屏 / 布局尺寸诊断（web 端「诊断」页会打印）。 */
    static JSONObject describeCutout(Activity activity) {
        JSONObject result = new JSONObject();
        try {
            result.put("sdk", Build.VERSION.SDK_INT);
            View decor = (activity == null || activity.getWindow() == null)
                    ? null : activity.getWindow().getDecorView();
            if (decor == null) {
                result.put("available", false);
                return result;
            }
            result.put("available", true);

            if (Build.VERSION.SDK_INT >= 28 && activity != null) {
                result.put("layoutInDisplayCutoutMode",
                        activity.getWindow().getAttributes().layoutInDisplayCutoutMode);
                WindowInsets insets = decor.getRootWindowInsets();
                DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
                result.put("hasCutout", cutout != null);
                if (cutout != null) {
                    JSONObject safe = new JSONObject();
                    safe.put("left", cutout.getSafeInsetLeft());
                    safe.put("top", cutout.getSafeInsetTop());
                    safe.put("right", cutout.getSafeInsetRight());
                    safe.put("bottom", cutout.getSafeInsetBottom());
                    result.put("safeInsets", safe);

                    JSONArray rects = new JSONArray();
                    if (Build.VERSION.SDK_INT >= 29) {
                        java.util.List<Rect> bounding = cutout.getBoundingRects();
                        if (bounding != null) {
                            for (Rect r : bounding) {
                                JSONObject o = new JSONObject();
                                o.put("left", r.left);
                                o.put("top", r.top);
                                o.put("right", r.right);
                                o.put("bottom", r.bottom);
                                rects.put(o);
                            }
                        }
                    }
                    result.put("boundingRects", rects);
                }
            }

            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            result.put("screenHeightPx", metrics.heightPixels);
            result.put("screenWidthPx", metrics.widthPixels);
            result.put("decorHeightPx", decor.getHeight());
            View content = decor.findViewById(android.R.id.content);
            result.put("contentHeightPx", content == null ? -1 : content.getHeight());
            GeckoHolder holder = GeckoHolder.find(decor);
            if (holder.view != null) {
                result.put("webViewHeightPx", holder.view.getHeight());
                result.put("webViewWidthPx", holder.view.getWidth());
                result.put("webViewPaddingTopPx", holder.view.getPaddingTop());
            }
            result.put("contentPaddingTopPx", content == null ? -1 : content.getPaddingTop());
            result.put("decorPaddingTopPx", decor.getPaddingTop());

            Rect frame = new Rect();
            decor.getWindowVisibleDisplayFrame(frame);
            JSONObject visible = new JSONObject();
            visible.put("left", frame.left);
            visible.put("top", frame.top);
            visible.put("right", frame.right);
            visible.put("bottom", frame.bottom);
            result.put("visibleFrame", visible);

            Configuration cfg = activity.getResources().getConfiguration();
            result.put("orientation", cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
                    ? "landscape" : "portrait");
            result.put("smallestWidthDp", cfg.smallestScreenWidthDp);
            result.put("screenWidthDp", cfg.screenWidthDp);
            result.put("screenHeightDp", cfg.screenHeightDp);
            result.put("automotive", isAutomotive(activity));
            return result;
        } catch (Throwable t) {
            Log.w(TAG, "describeCutout failed", t);
        }
        return result;
    }

    /** 在视图树里找 GeckoView（不直接引用类型，避免这里和 GeckoView 耦合）。 */
    private static final class GeckoHolder {
        final View view;

        GeckoHolder(View view) {
            this.view = view;
        }

        static GeckoHolder find(View root) {
            if (root instanceof org.mozilla.geckoview.GeckoView) {
                return new GeckoHolder(root);
            }
            if (root instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) root;
                for (int i = 0; i < g.getChildCount(); i++) {
                    GeckoHolder hit = find(g.getChildAt(i));
                    if (hit.view != null) return hit;
                }
            }
            return new GeckoHolder(null);
        }
    }
}
