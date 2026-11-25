package top.izuna.foliagecko;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 当前文件：网页部署所需的两项原生能力 —— 通用 HTTP 请求代理 + Cookie 罐。
 *
 * Folia 的在线音乐业务逻辑（网易云 / QQ / 酷狗 / 波点 / 天气）全部在前端 JS 里，
 * 原生侧只需提供两件事：
 *   1. httpRequest：绕过浏览器 CORS / UA / 证书限制，替前端发真实请求
 *   2. cookies*   ：跨域 Cookie 的读写删（浏览器同源策略不允许）
 */
final class HttpProxy {
    private static final String TAG = "FoliaGeckoHttp";
    private static final String PREFS = "folia-gecko-cookies";
    private static final MediaType OCTET = MediaType.parse("application/octet-stream");

    private final OkHttpClient client;
    private final Map<String, Map<String, String>> cookieJar = new ConcurrentHashMap<>();
    private final SharedPreferences prefs;

    HttpProxy(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.client = new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        loadCookies();
    }

    // ---------------- HTTP ----------------

    /** 日志用：只保留 host + path 前 80 字符，避免把 query 里的 token 打进日志。 */
    private static String shortenUrl(String url) {
        try {
            int q = url.indexOf('?');
            String base = q >= 0 ? url.substring(0, q) : url;
            if (base.length() > 120) base = base.substring(0, 120) + "...";
            return base;
        } catch (Throwable t) {
            return "(url)";
        }
    }

    /** 对应 FoliaNative.httpRequest，返回 {status, headers:[[name,value]...], bodyBase64}。 */
    JSONObject httpRequest(JSONObject options) throws JSONException {
        String url = options.optString("url", "");
        String method = options.optString("method", "GET").toUpperCase(Locale.ROOT);
        String bodyText = options.optString("bodyText", "");
        String bodyBase64 = options.optString("bodyBase64", "");
        boolean sendCookies = options.optBoolean("sendCookies", true);
        String redirect = options.optString("redirect", "follow");

        if (url.isEmpty()) throw new IllegalArgumentException("Missing request url");

        Request.Builder builder = new Request.Builder().url(url);

        // 请求头
        JSONObject headers = options.optJSONObject("headers");
        String contentTypeHeader = null;
        boolean hasCookieHeader = false;
        if (headers != null) {
            java.util.Iterator<String> it = headers.keys();
            while (it.hasNext()) {
                String name = it.next();
                String value = String.valueOf(headers.opt(name));
                if ("content-type".equalsIgnoreCase(name)) contentTypeHeader = value;
                if ("cookie".equalsIgnoreCase(name)) hasCookieHeader = true;
                try {
                    builder.header(name, value);
                } catch (IllegalArgumentException ignored) {
                    // 非法头名跳过，不能让单个头拖垮整个请求
                }
            }
        }

        // 自动带上 Cookie 罐里的同域 Cookie
        if (sendCookies && !hasCookieHeader) {
            String stored = cookieHeaderFor(url);
            if (!stored.isEmpty()) {
                try {
                    builder.header("Cookie", stored);
                } catch (IllegalArgumentException ignored) {
                    // 忽略
                }
            }
        }

        // 请求体
        RequestBody body = null;
        if (!bodyBase64.isEmpty()) {
            body = RequestBody.create(OCTET, Base64.decode(bodyBase64, Base64.DEFAULT));
        } else if (!bodyText.isEmpty()) {
            MediaType type = contentTypeHeader != null ? MediaType.parse(contentTypeHeader) : null;
            body = RequestBody.create(type, bodyText);
        } else if (!"GET".equals(method) && !"HEAD".equals(method)) {
            // GET/HEAD 不能带 body；其它方法无内容时给空体，避免 OkHttp 抛异常
            body = RequestBody.create(null, new byte[0]);
        }

        if ("GET".equals(method) || "HEAD".equals(method)) {
            builder.method(method, null);
        } else {
            builder.method(method, body);
        }

        OkHttpClient active = client;
        if ("manual".equalsIgnoreCase(redirect)) {
            active = client.newBuilder().followRedirects(false).followSslRedirects(false).build();
        }

        try (Response response = active.newCall(builder.build()).execute()) {
            JSONArray headerArray = new JSONArray();
            Headers rh = response.headers();
            for (String name : rh.names()) {
                for (String value : rh.values(name)) {
                    JSONObject entry = new JSONObject();
                    entry.put("name", name);
                    entry.put("value", value);
                    headerArray.put(entry);
                }
            }

            storeResponseCookies(url, rh);

            ResponseBody rb = response.body();
            byte[] bytes = rb == null ? new byte[0] : rb.bytes();

            JSONObject result = new JSONObject();
            result.put("status", response.code());
            result.put("headers", headerArray);
            result.put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP));
            // 打 URL + 状态 + 长度：排查「某个功能点了没反应」时，
            // 必须先确认请求到底发没发、返回了什么，否则只能靠猜。
            Log.i(TAG, method + " " + response.code() + " len=" + bytes.length + " " + shortenUrl(url));
            return result;
        } catch (IOException e) {
            // 网络失败也返回结构化结果，前端有各自的降级分支
            JSONObject result = new JSONObject();
            result.put("status", 0);
            result.put("headers", new JSONArray());
            result.put("bodyBase64", "");
            result.put("error", String.valueOf(e.getMessage()));
            return result;
        }
    }

    // ---------------- Cookie ----------------

    private String hostOf(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String host = u.getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** 拼出该 URL 可用的 Cookie 头。 */
    String cookieHeaderFor(String url) {
        String host = hostOf(url);
        if (host.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, String>> e : cookieJar.entrySet()) {
            String domain = e.getKey();
            if (host.equals(domain) || host.endsWith("." + domain)) {
                for (Map.Entry<String, String> c : e.getValue().entrySet()) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(c.getKey()).append('=').append(c.getValue());
                }
            }
        }
        return sb.toString();
    }

    /** 解析并保存响应里的 Set-Cookie。 */
    private void storeResponseCookies(String url, Headers headers) {
        List<String> setCookies = headers.values("Set-Cookie");
        if (setCookies.isEmpty()) return;
        String host = hostOf(url);
        if (host.isEmpty()) return;

        boolean changed = false;
        for (String raw : setCookies) {
            String[] parts = raw.split(";");
            if (parts.length == 0) continue;
            int eq = parts[0].indexOf('=');
            if (eq <= 0) continue;
            String name = parts[0].substring(0, eq).trim();
            String value = parts[0].substring(eq + 1).trim();
            if (name.isEmpty()) continue;

            String domain = host;
            for (int i = 1; i < parts.length; i++) {
                String attr = parts[i].trim();
                int ae = attr.indexOf('=');
                if (ae <= 0) continue;
                String key = attr.substring(0, ae).trim().toLowerCase(Locale.ROOT);
                String val = attr.substring(ae + 1).trim();
                if (key.equals("domain") && !val.isEmpty()) {
                    domain = val.startsWith(".") ? val.substring(1).toLowerCase(Locale.ROOT)
                            : val.toLowerCase(Locale.ROOT);
                }
            }
            putCookie(domain, name, value);
            changed = true;
        }
        if (changed) persistCookies();
    }

    private void putCookie(String domain, String name, String value) {
        cookieJar.computeIfAbsent(domain, k -> new ConcurrentHashMap<>()).put(name, value);
    }

    /** 对应 cookiesGet：返回 {cookie:{...}} 或空对象。 */
    JSONObject cookiesGet(JSONObject options) throws JSONException {
        String url = options.optString("url", "https://localhost/");
        String name = options.optString("name", "");
        JSONObject result = new JSONObject();
        if (name.isEmpty()) return result;

        List<JSONObject> all = readCookies(url, "");
        for (JSONObject c : all) {
            if (name.equals(c.optString("name"))) {
                result.put("cookie", c);
                return result;
            }
        }
        return result;
    }

    /** 对应 cookiesGetAll：返回 {cookies:[{name,value,...}]}。 */
    JSONObject cookiesGetAll(JSONObject options) throws JSONException {
        String url = options.optString("url", "");
        String domainOpt = options.optString("domain", "");
        JSONObject result = new JSONObject();
        result.put("cookies", new JSONArray(readCookies(url, domainOpt)));
        return result;
    }

    /** 对应 cookiesGetBatch：targets 为 [{url,domain}]，返回一一对应的二维数组。 */
    JSONObject cookiesGetBatch(JSONObject options) throws JSONException {
        JSONArray targets = options.optJSONArray("targets");
        JSONArray results = new JSONArray();
        if (targets != null) {
            for (int i = 0; i < targets.length(); i++) {
                JSONObject t = targets.optJSONObject(i);
                String url = t == null ? "" : t.optString("url", "");
                String domain = t == null ? "" : t.optString("domain", "");
                results.put(new JSONArray(readCookies(url, domain)));
            }
        }
        JSONObject result = new JSONObject();
        result.put("results", results);
        return result;
    }

    /** 对应 cookiesSet。 */
    JSONObject cookiesSet(JSONObject options) throws JSONException {
        String url = options.optString("url", "");
        String name = options.optString("name", "");
        String value = options.optString("value", "");
        String domainOpt = options.optString("domain", "");
        if (name.isEmpty()) throw new IllegalArgumentException("Missing cookie name");

        String domain = domainOpt.isEmpty() ? hostOf(url) : domainOpt;
        if (domain.startsWith(".")) domain = domain.substring(1);
        if (domain.isEmpty()) domain = "localhost";
        putCookie(domain.toLowerCase(Locale.ROOT), name, value);
        persistCookies();
        return new JSONObject().put("ok", true);
    }

    /** 对应 cookiesRemove。 */
    JSONObject cookiesRemove(JSONObject options) throws JSONException {
        String url = options.optString("url", "");
        String name = options.optString("name", "");
        String domain = options.optString("domain", hostOf(url));
        if (domain.startsWith(".")) domain = domain.substring(1);
        Map<String, String> jar = cookieJar.get(domain.toLowerCase(Locale.ROOT));
        if (jar != null) jar.remove(name);
        persistCookies();
        return new JSONObject().put("ok", true);
    }

    /** 对应 cookiesRemoveBatch：接受 entries（页面侧命名）与 targets 两种字段。 */
    JSONObject cookiesRemoveBatch(JSONObject options) throws JSONException {
        JSONArray targets = options.optJSONArray("entries");
        if (targets == null) targets = options.optJSONArray("targets");
        if (targets != null) {
            for (int i = 0; i < targets.length(); i++) {
                JSONObject t = targets.optJSONObject(i);
                if (t == null) continue;
                String domain = t.optString("domain", hostOf(t.optString("url", "")));
                if (domain.startsWith(".")) domain = domain.substring(1);
                String name = t.optString("name", "");
                Map<String, String> jar = cookieJar.get(domain.toLowerCase(Locale.ROOT));
                if (jar != null) {
                    if (name.isEmpty()) jar.clear();
                    else jar.remove(name);
                }
            }
        }
        persistCookies();
        return new JSONObject().put("ok", true);
    }

    /** 收集该 URL / domain 下可见的 Cookie。 */
    private List<JSONObject> readCookies(String url, String domainOpt) {
        List<JSONObject> out = new ArrayList<>();
        String host = !domainOpt.isEmpty()
                ? (domainOpt.startsWith(".") ? domainOpt.substring(1) : domainOpt).toLowerCase(Locale.ROOT)
                : hostOf(url);
        if (host.isEmpty()) return out;

        for (Map.Entry<String, Map<String, String>> e : cookieJar.entrySet()) {
            String domain = e.getKey();
            boolean match = host.equals(domain) || host.endsWith("." + domain)
                    || (!domainOpt.isEmpty() && domain.equalsIgnoreCase(host));
            if (!match) continue;
            for (Map.Entry<String, String> c : e.getValue().entrySet()) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", c.getKey());
                    o.put("value", c.getValue());
                    o.put("domain", domain);
                    o.put("path", "/");
                    out.add(o);
                } catch (JSONException ignored) {
                    // 忽略
                }
            }
        }
        return out;
    }

    // ---------------- 持久化 ----------------

    private void persistCookies() {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, Map<String, String>> e : cookieJar.entrySet()) {
                JSONObject jar = new JSONObject();
                for (Map.Entry<String, String> c : e.getValue().entrySet()) {
                    jar.put(c.getKey(), c.getValue());
                }
                root.put(e.getKey(), jar);
            }
            prefs.edit().putString("jar", root.toString()).apply();
        } catch (JSONException e) {
            Log.w(TAG, "persist cookies failed", e);
        }
    }

    private void loadCookies() {
        String raw = prefs.getString("jar", "");
        if (raw.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(raw);
            java.util.Iterator<String> domains = root.keys();
            while (domains.hasNext()) {
                String domain = domains.next();
                JSONObject jar = root.optJSONObject(domain);
                if (jar == null) continue;
                Map<String, String> map = new LinkedHashMap<>();
                java.util.Iterator<String> names = jar.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    map.put(name, jar.optString(name, ""));
                }
                cookieJar.put(domain, new ConcurrentHashMap<>(map));
            }
        } catch (JSONException e) {
            Log.w(TAG, "load cookies failed", e);
        }
    }
}
