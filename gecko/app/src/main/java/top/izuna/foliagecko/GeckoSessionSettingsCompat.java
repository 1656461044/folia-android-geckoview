package top.izuna.foliagecko;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;

/**
 * 当前文件：集中设置 GeckoSession 的行为（用户代理、视口、全屏、媒体自动播放）。
 *
 * 注意 API 归属：这些开关在 GeckoSessionSettings 上，不是 GeckoSession.Settings
 * （后者在 GeckoView 139 里已不存在，属于早期版本命名）。
 */
final class GeckoSessionSettingsCompat {
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; DiLink) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/139.0.0.0 Mobile Safari/537.36 FoliaGecko/1.0";

    private GeckoSessionSettingsCompat() {
    }

    static void apply(GeckoSession session) {
        GeckoSessionSettings s = session.getSettings();
        s.setUserAgentOverride(UA);
        s.setViewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
        s.setDisplayMode(GeckoSessionSettings.DISPLAY_MODE_FULLSCREEN);
        s.setAllowJavascript(true);
        s.setSuspendMediaWhenInactive(false);
        s.setUseTrackingProtection(false);
    }
}
