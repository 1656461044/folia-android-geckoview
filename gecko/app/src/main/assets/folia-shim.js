/* 当前文件：注入到 Folia 页面的原生桥兼容层。
 *
 * Folia 在 build:capacitor 时把 VITE_*_API_BASE 全设成 'extension'，
 * 意思是「不要外部 API 服务器，由设备本地提供」。要在 GeckoView 里跑，需要补齐：
 *
 *   A. Capacitor 运行时      —— 页面据此判定原生环境，插件调用转到 /__native/call
 *   B. chrome.cookies        —— 各平台登录要读写跨域 Cookie
 *   C. window.fetch 劫持     —— GeckoView 的跨站 fetch 会被 CORS 拦成 NetworkError
 *   D. 上行事件轮询          —— 返回键、系统音量（GeckoView 没有 evaluateJS）
 *   E. 音量接管              —— 默认关闭，音量交给系统 STREAM_MUSIC（见 E 段注释）
 */
(function () {
  'use strict';

  if (window.__FOLIA_GECKO_SHIM__) return;
  window.__FOLIA_GECKO_SHIM__ = true;

  var PAGE_SOURCE = 'folia-web-page';
  var BRIDGE_SOURCE = 'folia-extension-bridge';
  var BRIDGE_VERSION = 'android-0.1.2';

  var LISTENERS = Object.create(null);
  var nextId = 1;

  // 保存原生 fetch，避免劫持后自己调自己
  var rawFetch = window.fetch.bind(window);

  function makeError(msg, code) {
    var err = new Error(msg);
    err.code = code || 'UNIMPLEMENTED';
    return err;
  }

  function report(tag, detail) {
    try {
      rawFetch('/__native/call', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          id: 0, pluginId: 'Diag', methodName: tag,
          options: { detail: String(detail || '') }
        })
      }).catch(function () {});
    } catch (e) { /* 忽略 */ }
  }

  function nativeCall(pluginId, methodName, options) {
    var id = nextId++;
    return rawFetch('/__native/call', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        id: id, pluginId: pluginId, methodName: methodName, options: options || {}
      })
    }).then(function (res) {
      if (!res.ok) throw makeError('native http ' + res.status, 'UNAVAILABLE');
      return res.json();
    }).then(function (msg) {
      if (msg && msg.ok) return msg.value != null ? msg.value : {};
      throw makeError((msg && msg.error) || 'native error', msg && msg.code);
    });
  }

  // ============ 0. 掐掉 Service Worker ============
  /**
   * Folia 是 PWA，registerSW.js 会注册 /sw.js（workbox precache），
   * 而 precache 里带着 index.html —— 我们的 shim 就内联在 index.html 里。
   * 一旦 SW 装上，之后不管换 APK、加 cache-buster，页面都从 SW 拿旧 index.html，
   * 于是「改了 shim 但行为不变」（实测踩过：shim 版本一直停在最初那份，platform 永远是 web）。
   *
   * 壳里不需要离线缓存（资源本来就在本地 127.0.0.1），直接把 register 废掉。
   * 引擎层另有 geckoview-config.yaml 关掉 dom.serviceWorkers.enabled，双保险。
   */
  try {
    if (typeof navigator !== 'undefined' && navigator.serviceWorker) {
      var noSW = function () {
        return Promise.reject(
          new Error('Service Worker disabled in GeckoView shell'));
      };
      try {
        Object.defineProperty(navigator.serviceWorker, 'register', {
          configurable: true, writable: true, value: noSW
        });
      } catch (e1) {
        navigator.serviceWorker.register = noSW;
      }
    }
  } catch (e) { /* 忽略 */ }

  // ============ 0. 原生环境哨兵（必须在页面模块之前） ============
  /**
   * Capacitor 自己的平台判定只看 window.androidBridge 存不存在：
   *
   *   const getPlatformId = (win) =>
   *     win?.androidBridge ? 'android'
   *       : win?.webkit?.messageHandlers?.bridge ? 'ios' : 'web';
   *
   * 所以不要伪造 getPlatform —— 页面模块 createCapacitor() 里会写
   * `cap.getPlatform = ...` 把实现覆盖回去（而且是严格模式，属性被锁成
   * 不可写会直接抛 TypeError 让整包初始化挂掉）。正确做法是把哨兵补上，
   * 让页面自己算出 'android'。
   *
   * 这一条同时决定了两件事：
   *   1) 本地库可用性判定 platform === 'android' → supported（否则入口置灰）
   *   2) 原生播放桥是否安装（isNativePlatform() || getPlatform() === 'android'）
   *      → 没装上就没有 updatePlaybackState，也就没有 MediaSession / 后台播放 / 方控媒体键
   */
  if (!window.androidBridge) {
    try {
      window.androidBridge = {
        // Capacitor 这个版本的 bundle 只把 androidBridge 当平台哨兵用，
        // 不会真的 postMessage；这里留个实现，万一以后走到也不至于崩。
        postMessage: function (raw) {
          try {
            var m = typeof raw === 'string' ? JSON.parse(raw) : raw;
            if (!m || m.type !== 'plugin') return;
            nativeCall(m.pluginId, m.methodName, m.options).catch(function () {});
          } catch (e) { /* 忽略 */ }
        }
      };
    } catch (e) { /* 忽略 */ }
  }

  /**
   * 强制页面处于「原生 Android 环境」。
   *
   * 页面模块会把 window.Capacitor 换成自己的实现（platform 变 'web'），
   * 还会覆盖一些全局。这个函数是幂等的，可以在任意时机重复调用：
   *   - 保证 window.Capacitor.getPlatform() === 'android'
   *   - 保证 showDirectoryPicker 存在（Folia 用它判断本地库可用性）
   * 每次调用都重新补，被覆盖了也能补回来。
   */
  function enforceNativeEnv() {
    try {
      var cap = window.Capacitor || {};
      if (typeof cap.getPlatform !== 'function' || cap.getPlatform() !== 'android') {
        cap.getPlatform = function () { return 'android'; };
      }
      cap.isNativePlatform = function () { return true; };
      cap.isNative = true;
      if (!cap.Plugins) {
        cap.Plugins = new Proxy({}, {
          get: function (obj, prop) {
            if (typeof prop !== 'string') return undefined;
            return getPlugin(prop);
          }
        });
      }
      window.Capacitor = cap;
    } catch (e) { /* 忽略 */ }

    try {
      // 用 defineProperty 而不是直接赋值：页面可能是只读属性或有 setter
      var dp = Object.getOwnPropertyDescriptor(window, 'showDirectoryPicker');
      if (!dp || dp.configurable) {
        Object.defineProperty(window, 'showDirectoryPicker', {
          configurable: true,
          writable: true,
          enumerable: true,
          value: function () { return Promise.resolve(makeEmptyDir()); }
        });
      } else if (typeof window.showDirectoryPicker !== 'function') {
        // 不可重定义就退到原型链
        try {
          Object.defineProperty(Window.prototype, 'showDirectoryPicker', {
            configurable: true,
            value: function () { return Promise.resolve(makeEmptyDir()); }
          });
        } catch (e2) { /* 忽略 */ }
      }
    } catch (e) {
      try { window.showDirectoryPicker = function () { return Promise.resolve(makeEmptyDir()); }; }
      catch (e3) { /* 忽略 */ }
    }

    try {
      if (navigator.storage && typeof navigator.storage.getDirectory !== 'function') {
        navigator.storage.getDirectory = function () {
          return Promise.resolve(makeEmptyDir());
        };
      }
    } catch (e) { /* 忽略 */ }

    // 最后一次尝试：直接打标记，跳过 Folia 的文件系统检测
    try {
      window.__FOLIA_FORCE_LOCAL_LIBRARY__ = true;
    } catch (e) { /* 忽略 */ }
  }

  /** 构造一个空但结构合法的目录句柄。 */
  function makeEmptyDir() {
    function done() { return Promise.resolve({ done: true, value: undefined }); }
    return {
      kind: 'directory',
      name: 'Android 本地音乐',
      entries: function () { return { next: done }; },
      keys: function () { return { next: done }; },
      values: function () { return { next: done }; },
      getFileHandle: function () {
        return Promise.reject(new DOMException('Not found', 'NotFoundError'));
      },
      getDirectoryHandle: function () {
        return Promise.reject(new DOMException('Not found', 'NotFoundError'));
      },
      queryPermission: function () { return Promise.resolve('granted'); },
      requestPermission: function () { return Promise.resolve('granted'); },
      removeEntry: function () { return Promise.resolve(); }
    };
  }

  // 首次补齐原生环境（页面加载后还会再补，见文件末尾的 load 钩子）
  enforceNativeEnv();

  // ============ A. Capacitor 运行时 ============

  function emitEvent(name, payload) {
    var ls = LISTENERS[name];
    if (!ls || !ls.length) return false;
    ls.slice().forEach(function (fn) {
      try { fn(payload || {}); } catch (e) { /* 忽略单个监听器异常 */ }
    });
    return true;
  }
  window.__foliaGeckoEmit = emitEvent;

  function makePlugin(pluginId) {
    return new Proxy({}, {
      get: function (obj, prop) {
        if (prop === 'addListener' || prop === 'removeListener' || prop === 'removeAllListeners') {
          return function (eventName, cb) {
            if (!LISTENERS[eventName]) LISTENERS[eventName] = [];
            var handler = typeof cb === 'function' ? cb : function () {};
            LISTENERS[eventName].push(handler);
            var removed = false;
            var handle = {
              remove: function () {
                if (removed) return;
                removed = true;
                var ls = LISTENERS[eventName];
                if (ls) { var i = ls.indexOf(handler); if (i >= 0) ls.splice(i, 1); }
              }
            };
            if (typeof cb === 'function') { try { cb(handle); } catch (e) { /* 忽略 */ } }
            return Promise.resolve(handle);
          };
        }
        if (prop === 'then') return undefined;
        if (typeof prop !== 'string') return undefined;
        return function () {
          return nativeCall(pluginId, prop, arguments.length ? arguments[0] : {});
        };
      }
    });
  }

  var pluginCache = Object.create(null);
  function getPlugin(name) {
    if (!pluginCache[name]) pluginCache[name] = makePlugin(name);
    return pluginCache[name];
  }

  var Capacitor = window.Capacitor || {};
  /**
   * getPlatform 必须是 'android' 且不能被页面后续覆盖。
   *
   * Folia 的本地库可用性判定是：
   *   if (window.electron || Capacitor.getPlatform() === 'android')
   *       return { supported: true };
   *   ... 否则再检查 showDirectoryPicker（GeckoView 没有）→ 判定不可用 → 本地入口置灰
   *
   * 实测发现页面加载后会重新写 window.Capacitor（platform 变成 'web'），
   * 导致判定走错分支。这里用 defineProperty 固定，禁止被覆盖。
   */
  function lockPlatform() {
    // 只做普通赋值，绝不 defineProperty 硬锁：
    // 页面模块 createCapacitor() 里写 `cap.getPlatform = ...`，
    // 属性不可写时在 ES module 的严格模式下会抛 TypeError，整包初始化直接失败。
    // 真正的平台判定由上面的 window.androidBridge 哨兵负责，这里只是兜底。
    try {
      Capacitor.getPlatform = function () { return 'android'; };
      Capacitor.isNativePlatform = function () { return true; };
    } catch (e) { /* 忽略 */ }
  }

  Capacitor.isNative = true;
  Capacitor.Plugins = new Proxy({}, {
    get: function (obj, prop) {
      if (typeof prop !== 'string') return undefined;
      return getPlugin(prop);
    }
  });
  Capacitor.convertFileSrc = function (p) { return p; };
  Capacitor.registerPlugin = function (n) { return getPlugin(n); };
  lockPlatform();
  window.Capacitor = Capacitor;

  // 页面模块可能整体重写 window.Capacitor，这里在之后持续纠偏
  (function guardCapacitor() {
    var target = Capacitor;
    var desc = Object.getOwnPropertyDescriptor(window, 'Capacitor');
    try {
      Object.defineProperty(window, 'Capacitor', {
        configurable: true,
        enumerable: true,
        get: function () { return target; },
        set: function (incoming) {
          if (incoming && typeof incoming === 'object') {
            // 保留页面的其它字段，但平台判定必须是 android
            try {
              incoming.getPlatform = function () { return 'android'; };
              incoming.isNativePlatform = function () { return true; };
              incoming.isNative = true;
            } catch (e) { /* 忽略 */ }
            target = incoming;
          }
        }
      });
    } catch (e) {
      // 忽略：拿不到 defineProperty 就走普通引用
    }
  })();

  // ============ B. chrome.cookies 兼容层 ============

  var chromeObj = window.chrome || {};
  chromeObj.runtime = chromeObj.runtime || {
    id: 'folia-native-android',
    getManifest: function () { return { version: BRIDGE_VERSION }; }
  };
  chromeObj.storage = chromeObj.storage || {
    local: {
      get: function (keys) {
        var out = {};
        var list = keys == null ? Object.keys(localStorage)
          : (Array.isArray(keys) ? keys : [keys]);
        list.forEach(function (k) {
          var v = localStorage.getItem(String(k));
          if (v != null) {
            try { out[String(k)] = JSON.parse(v); } catch (e) { out[String(k)] = v; }
          }
        });
        return Promise.resolve(out);
      },
      set: function (obj) {
        Object.keys(obj || {}).forEach(function (k) {
          localStorage.setItem(String(k), JSON.stringify(obj[k]));
        });
        return Promise.resolve();
      },
      remove: function (keys) {
        (Array.isArray(keys) ? keys : [keys]).forEach(function (k) {
          localStorage.removeItem(String(k));
        });
        return Promise.resolve();
      }
    }
  };

  var cookieCache = new Map();
  var cookieQueue = [];
  var cookieFlushTimer = null;
  var COOKIE_TTL = 1500;

  function cookieKey(details) {
    return String(details && details.url || '') + '|' + String(details && details.domain || '');
  }

  function flushCookieQueue() {
    cookieFlushTimer = null;
    var batch = cookieQueue;
    cookieQueue = [];
    if (!batch.length) return;
    var targets = batch.map(function (item) {
      return {
        url: typeof item.details.url === 'string' ? item.details.url : '',
        domain: typeof item.details.domain === 'string' ? item.details.domain : ''
      };
    });
    nativeCall('FoliaNative', 'cookiesGetBatch', { targets: targets })
      .then(function (r) {
        var results = (r && r.results) || [];
        batch.forEach(function (item, i) {
          var rows = Array.isArray(results[i]) ? results[i] : [];
          cookieCache.set(item.key, { at: Date.now(), rows: rows });
          item.resolve(rows);
        });
      })
      .catch(function () {
        batch.forEach(function (item) { item.resolve([]); });
      });
  }

  function cachedCookies(details) {
    var key = cookieKey(details);
    var hit = cookieCache.get(key);
    if (hit && Date.now() - hit.at < COOKIE_TTL) {
      return Promise.resolve(hit.rows);
    }
    return new Promise(function (resolve) {
      cookieQueue.push({ key: key, details: details, resolve: resolve });
      if (!cookieFlushTimer) cookieFlushTimer = setTimeout(flushCookieQueue, 0);
    });
  }

  function invalidateCookieCache(details) {
    var url = typeof (details && details.url) === 'string' ? details.url : '';
    var domain = typeof (details && details.domain) === 'string' ? details.domain : '';
    if (!url && !domain) { cookieCache.clear(); return; }
    Array.from(cookieCache.keys()).forEach(function (k) {
      var tail = '|' + domain;
      if ((url && k.indexOf(url + '|') === 0) ||
          (domain && k.length >= tail.length && k.slice(-tail.length) === tail)) {
        cookieCache.delete(k);
      }
    });
  }

  chromeObj.cookies = {
    getAll: function (details) { return cachedCookies(details || {}); },
    get: function (details) {
      var name = typeof (details && details.name) === 'string' ? details.name : '';
      if (!name) return Promise.resolve(null);
      return cachedCookies({ url: details.url, domain: details.domain })
        .then(function (rows) {
          for (var i = 0; i < rows.length; i++) {
            if (rows[i] && rows[i].name === name) return rows[i];
          }
          return null;
        });
    },
    set: function (details) {
      return nativeCall('FoliaNative', 'cookiesSet', details || {})
        .then(function () { invalidateCookieCache(details); return details; });
    },
    remove: function (details) {
      return nativeCall('FoliaNative', 'cookiesRemove', details || {})
        .then(function (r) {
          invalidateCookieCache(details);
          return (r && r.ok) ? details : null;
        });
    },
    removeBatch: function (entries) {
      return nativeCall('FoliaNative', 'cookiesRemoveBatch', { entries: entries })
        .then(function () {
          (entries || []).forEach(function (e) { invalidateCookieCache({ url: e.url }); });
          return entries;
        });
    }
  };

  chromeObj.declarativeNetRequest = {
    RuleActionType: { MODIFY_HEADERS: 'modifyHeaders' },
    updateSessionRules: function () { return Promise.resolve(); }
  };
  chromeObj.tabs = {
    create: function (opts) {
      if (opts && opts.url) window.open(opts.url, '_blank', 'noopener,noreferrer');
      return Promise.resolve({});
    },
    query: function () { return Promise.resolve([]); },
    onRemoved: { addListener: function () {} },
    onUpdated: { addListener: function () {} }
  };
  window.chrome = chromeObj;

  // ============ E. 音量接管（必须开启，见下） ============

  /**
   * 为什么必须用软件增益接管（车机实测结论，别再删）：
   *
   * GeckoView 的音频在 Android 上走 cubeb → AAudio，而 AAudio 在这台车机上
   * 打开的是 **MMAP 直通通道**。`dumpsys media.audio_flinger` 里看得一清二楚：
   *
   *   Output thread ... name AudioMmapOut_12, type 5 (MMAP_PLAYBACK):
   *     Attributes: content type 2 usage 1
   *     Stream type: 3  Stream volume: 1.000000  ← 一直是 1.0
   *
   * 同时 `dumpsys audio` 里 STREAM_MUSIC 已经从 8/39 被旋钮调到 0/39（甚至 Muted: true），
   * MMAP 线程的 Stream volume 依然是 1.000000 —— 也就是说 **AudioFlinger 混音器根本
   * 不碰这条通道**，按音量键/转旋钮对声音零影响。
   * （WebView/Chromium 用的是普通 AudioTrack，走混音器，所以原版没这个问题。）
   *
   * 想从引擎层改：cubeb 的后端可由 pref `media.cubeb.backend` 选 opensl/aaudio，
   * 但实测设成 opensl 后线程仍然是 AudioMmapOut_*，没生效（见 geckoview-config.yaml）。
   * 所以只能由页面侧乘一个主增益。
   *
   * 正确做法（旧版在这里连着犯了两个错，别重蹈）：
   *
   * 错 1「双写」：既插入 WebAudio 主增益，又乘元素 volume。
   *   很多播放器是 <audio> → MediaElementSourceNode → ... → destination，
   *   元素音量和主增益会各乘一次系统音量（0.33² ≈ 0.11），声音远小于预期。
   *   → 现在只在一处施加：元素 volume。元素音量对「直接播放」和
   *     「MediaElementSource 送进 WebAudio」两种接法都生效，单一施加点不会重复。
   *
   * 错 2「读回回声自乘」（用户实测：升三格降三格，一路降到 0，只有拖应用内滑块才恢复）：
   *   我们改了元素真实 volume 会触发 volumechange，页面监听到就去读 el.volume，
   *   把「缩放后的值」当成自己的音量存进状态，再写回来 → 我们又乘一次系统音量，
   *   每按一次键就乘一遍，单调下降，直到 0。
   *   → 所以 getter 必须返回**页面自己的基准值**（base），而不是真实值；
   *     页面对 volume 的读-写闭环就稳定了，系统音量只在我们内部生效。
   */
  var VOLUME_TAKEOVER = true;

  var systemFraction = 1.0;
  /** 页面希望的音量（每个 media 元素各自的），与系统音量分开存。 */
  var baseVolumes = new WeakMap();
  /** 我们最近一次写进元素的真实值，用来识别「页面把我们的值又写回来」的回声。 */
  var lastApplied = new WeakMap();

  /**
   * 原始 volume 描述符，**必须在 defineProperty 之前抓取并只抓一次**。
   *
   * 踩过的坑：旧版 applyVolume() 里每次都 `Object.getOwnPropertyDescriptor(
   * HTMLMediaElement.prototype, 'volume')` 现场取，而那时原型上已经是我们的 hook，
   * 于是拿到的 set 就是我们自己 —— 系统音量被套着乘两遍，还把「缩放后的值」
   * 当成页面基准值写回 baseVolumes，表现为：
   *   音量键升也降、降也降，每按一次乘一遍，一路掉到 0，
   *   只有拖应用内滑块（重置 base）才恢复。
   */
  var origVolumeDesc = (function () {
    try {
      return Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume');
    } catch (e) {
      return null;
    }
  })();

  function effectiveVolume() {
    if (!VOLUME_TAKEOVER) return 1.0;
    return Math.max(0, Math.min(1, systemFraction));
  }

  function applyVolume() {
    var v = effectiveVolume();
    window.__FOLIA_SYSTEM_VOLUME__ = v;
    if (!VOLUME_TAKEOVER) return;
    if (!origVolumeDesc || !origVolumeDesc.set) return;
    try {
      document.querySelectorAll('audio, video').forEach(function (el) {
        var base = baseVolumes.get(el);
        if (typeof base !== 'number') base = 1;
        var target = base * v;
        lastApplied.set(el, target);
        origVolumeDesc.set.call(el, target);
      });
    } catch (e) { /* 忽略 */ }
  }

  // 元素音量：页面设的是「基准值」，真实输出 = 基准 × 系统音量；读回给页面的也是基准
  (function hookElementVolume() {
    if (!VOLUME_TAKEOVER) return;
    if (!origVolumeDesc || !origVolumeDesc.set) return;
    try {
      Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
        configurable: true,
        enumerable: origVolumeDesc.enumerable,
        get: function () {
          var base = baseVolumes.get(this);
          // 页面没设过音量时，它眼里应该是默认的 1.0，
          // 绝不能返回我们缩过的真实值，否则页面会把系统音量当成自己的音量再乘一遍。
          return typeof base === 'number' ? base : 1;
        },
        set: function (v) {
          var base = (typeof v === 'number' && isFinite(v)) ? v : 1;
          // 页面把我们写下去的真实值原样写回来时（回声），不要覆盖基准，否则会自乘
          var just = lastApplied.get(this);
          if (typeof just === 'number' && Math.abs(just - base) < 1e-4) return;
          baseVolumes.set(this, base);
          var target = base * effectiveVolume();
          lastApplied.set(this, target);
          origVolumeDesc.set.call(this, target);
        }
      });
    } catch (e) { /* 忽略 */ }
  })();

  // ============ C. window.fetch 劫持 ============

  var BRIDGE_HOSTS = [
    'music.163.com', 'interface.music.163.com', 'interface3.music.163.com',
    'c.y.qq.com', 'u.y.qq.com', 'mu.y.qq.com', 'ssl.ptlogin2.qq.com',
    'xui.ptlogin2.qq.com', 'graph.qq.com', 'weixin.qq.com',
    'kugou.com', 'kuwo.cn', 'api.qrserver.com',
    'spotify.com', 'spotifycdn.com', 'scdn.co',
    'y.gtimg.cn', 'qlogo.cn', '126.net', 'kgimg.com', 'y.qq.com',
    'api.tencentmusic.com', 'qishui.com',
    'douyin.com', 'douyinpic.com', 'douyinvod.com', 'douyinscdn.com',
    'douyinstatic.com', 'byteimg.com', '365yg.com', 'bytefcdn.com',
    'bytecdn.cn', 'byteicdn.com', 'ibytedtos.com', 'pstatp.com',
    'snssdk.com', 'bytedance.com', 'bytetos.com', 'volcvideo.com',
    'volccdn.com', 'volces.com', 'volcengineapi.com', 'zijieapi.com', 'zjcdn.com'
  ];

  function shouldBridge(rawUrl) {
    try {
      var u = new URL(rawUrl, location.href);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') return false;
      var host = u.hostname;
      for (var i = 0; i < BRIDGE_HOSTS.length; i++) {
        var d = BRIDGE_HOSTS[i];
        if (host === d || host.slice(-(d.length + 1)) === '.' + d) return u.toString();
      }
      return false;
    } catch (e) {
      return false;
    }
  }

  function toHeaderEntries(h) {
    if (!h) return [];
    if (typeof Headers !== 'undefined' && h instanceof Headers) return Array.from(h.entries());
    if (Array.isArray(h)) return h.map(function (kv) { return [String(kv[0]), String(kv[1])]; });
    return Object.keys(h).map(function (k) { return [String(k), String(h[k])]; });
  }

  function u8ToBase64(u8) {
    var s = '';
    var chunk = 0x8000;
    for (var i = 0; i < u8.length; i += chunk) {
      s += String.fromCharCode.apply(null, u8.subarray(i, i + chunk));
    }
    return btoa(s);
  }

  function base64ToU8(b64) {
    if (!b64) return new Uint8Array(0);
    var bin = atob(b64);
    var u8 = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    return u8;
  }

  function serializeBody(body) {
    if (body == null) return Promise.resolve({});
    if (typeof body === 'string') return Promise.resolve({ bodyText: body });
    if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
      return Promise.resolve({
        bodyText: body.toString(),
        contentType: 'application/x-www-form-urlencoded'
      });
    }
    if (body instanceof Uint8Array) {
      return Promise.resolve({ bodyBase64: u8ToBase64(body) });
    }
    return new Response(body).arrayBuffer().then(function (buf) {
      return { bodyBase64: u8ToBase64(new Uint8Array(buf)) };
    }).catch(function () { return {}; });
  }

  window.fetch = function (input, init) {
    var req = (typeof Request !== 'undefined' && input instanceof Request) ? input : null;
    var rawUrl = req ? req.url : String(input);
    var target = shouldBridge(rawUrl);
    if (!target) return rawFetch(input, init);

    init = init || {};
    var method = (init.method || (req && req.method) || 'GET').toUpperCase();

    var headers = {};
    toHeaderEntries(req ? req.headers : null).forEach(function (kv) { headers[kv[0]] = kv[1]; });
    toHeaderEntries(init.headers).forEach(function (kv) { headers[kv[0]] = kv[1]; });

    var bodySource = init.body != null ? init.body : null;

    return serializeBody(bodySource).then(function (serialized) {
      var hs = {};
      Object.keys(headers).forEach(function (k) { hs[k] = headers[k]; });
      if (serialized.contentType && !hs['content-type']) {
        hs['content-type'] = serialized.contentType;
      }
      return nativeCall('FoliaNative', 'httpRequest', {
        url: target,
        method: method,
        headers: hs,
        bodyText: serialized.bodyText || '',
        bodyBase64: serialized.bodyBase64 || '',
        redirect: init.redirect || (req && req.redirect) || 'follow',
        sendCookies: (init.credentials || (req && req.credentials)) === 'omit' ? false : true
      });
    }).then(function (res) {
      var bytes = base64ToU8(res && res.bodyBase64);
      var out = new Headers();
      var setCookies = [];
      if (res && Array.isArray(res.headers)) {
        res.headers.forEach(function (e) {
          if (!e || !e.name || e.value == null) return;
          if (String(e.name).toLowerCase() === 'set-cookie') {
            out.append(e.name, String(e.value));
            setCookies.push(String(e.value));
          } else {
            out.set(e.name, String(e.value));
          }
        });
      }
      setCookies.forEach(function (v) { out.append('x-folia-set-cookie', v); });
      return new Response(bytes, { status: (res && res.status) || 0, headers: out });
    });
  };

  // ============ B2. 扩展桥消息协议 ============

  var apiHandlerPromise = null;

  function loadApiHandler() {
    if (apiHandlerPromise) return apiHandlerPromise;
    apiHandlerPromise = (function () {
      var names = [];
      try {
        names = performance.getEntriesByType('resource')
          .map(function (e) { return e.name; })
          .filter(function (n) { return /\/assets\/folia-[^/]+\.js$/.test(n); });
      } catch (e) { /* 忽略 */ }
      if (names.length === 0) names = ['/assets/folia-Bo3oehB7.js'];

      function tryNext(idx) {
        if (idx >= names.length) return Promise.resolve(null);
        return import(names[idx]).then(function (mod) {
          if (mod && typeof mod.handleFoliaApiRequest === 'function') {
            return mod.handleFoliaApiRequest;
          }
          return tryNext(idx + 1);
        }).catch(function () {
          return tryNext(idx + 1);
        });
      }
      return tryNext(0);
    })();
    return apiHandlerPromise;
  }

  function postToPage(msg) {
    msg.source = BRIDGE_SOURCE;
    try { window.postMessage(msg, '*'); } catch (e) { /* 忽略 */ }
  }

  function handleApiRequest(id, payload) {
    loadApiHandler().then(function (handler) {
      if (!handler) {
        postToPage({
          type: 'FOLIA_API_RESPONSE', id: id, ok: false,
          error: 'FOLIA_API_HANDLER_UNAVAILABLE'
        });
        return;
      }
      Promise.resolve()
        .then(function () { return handler(payload || {}); })
        .then(function (data) {
          postToPage({ type: 'FOLIA_API_RESPONSE', id: id, ok: true, data: data });
        })
        .catch(function (err) {
          postToPage({
            type: 'FOLIA_API_RESPONSE', id: id, ok: false,
            error: (err && err.message) ? err.message : String(err)
          });
        });
    });
  }

  window.addEventListener('message', function (e) {
    if (e.source !== window) return;
    var data = e.data;
    if (!data || data.source !== PAGE_SOURCE) return;

    if (data.type === 'FOLIA_BRIDGE_PING') {
      postToPage({
        type: 'FOLIA_BRIDGE_PONG', ready: true,
        version: BRIDGE_VERSION, extId: 'folia-native-android'
      });
      return;
    }
    if (data.type === 'FOLIA_API_REQUEST' && typeof data.id === 'string') {
      handleApiRequest(data.id, data.payload);
    }
  });

  setTimeout(function () {
    postToPage({
      type: 'FOLIA_BRIDGE_READY', ready: true,
      version: BRIDGE_VERSION, extId: 'folia-native-android'
    });
  }, 0);

  // ============ D. 上行事件轮询 ============

  var EVENT_POLL_MS = 120;

  function handleEvent(ev) {
    if (!ev || ev.type !== 'event') return;
    if (ev.name === 'backButton') {
      // 派发 Escape，让 Folia 自己的返回层级生效；
      // 用 defaultPrevented 判断页面有没有真的关掉一层
      var keyDown = new KeyboardEvent('keydown', {
        key: 'Escape', code: 'Escape', bubbles: true, cancelable: true
      });
      var prevented = !window.dispatchEvent(keyDown) || keyDown.defaultPrevented;
      emitEvent('backButton', ev.payload || {});
      // 把结果同步回报给原生：true = 页面已消费，false = 没有层级可退
      nativeCall('FoliaNative', 'noteBackHandled', { handled: prevented }).catch(function () {});
      return;
    }
    if (ev.name === 'systemVolume') {
      var p = ev.payload;
      if (p && typeof p.fraction === 'number') {
        systemFraction = p.fraction;
        applyVolume();
        // 自检：把每个元素的「页面基准值」和「真实下发值」打出来。
        // 基准值必须恒定，只有真实值随系统音量变化 —— 基准值要是越变越小，
        // 就说明又出现了「读回回声自乘」（升三格降三格、一路降到 0）。
        try {
          var pairs = [];
          document.querySelectorAll('audio, video').forEach(function (el) {
            var b = baseVolumes.get(el);
            pairs.push((typeof b === 'number' ? b : -1).toFixed(2) +
              '/' + (origVolumeDesc ? origVolumeDesc.get.call(el) : -1).toFixed(2));
          });
          report('volume-apply', 'sys=' + p.fraction.toFixed(2) +
            ' els=' + pairs.length + ' ' + pairs.join(' '));
        } catch (e) { /* 忽略 */ }
      }
      emitEvent('systemVolume', p || {});
      return;
    }
    emitEvent(ev.name, ev.payload || {});
  }

  function pollOnce() {
    return rawFetch('/__native/poll', { method: 'GET' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (data && Array.isArray(data.events)) {
          data.events.forEach(handleEvent);
        }
      })
      .catch(function () { /* 忽略，下一轮再试 */ });
  }

  (function startPolling() {
    (function tick() {
      pollOnce().then(function () {
        setTimeout(tick, EVENT_POLL_MS);
      });
    })();
  })();

  // 启动时先取一次当前系统音量，否则要等到用户第一次动音量键才生效
  (function primeSystemVolume() {
    nativeCall('FoliaNative', 'getSystemVolume', {})
      .then(function (r) {
        if (r && typeof r.fraction === 'number') {
          systemFraction = r.fraction;
          applyVolume();
        }
      })
      .catch(function () { /* 忽略 */ });
  })();

  // ============ Cordova / ApkUpdater / MediaSession ============

  if (!window.cordova) {
    window.cordova = {
      define: function () {},
      require: function () { return null; },
      platformId: 'android',
      version: 'gecko'
    };
  }
  if (!window.ApkUpdater) {
    window.ApkUpdater = {
      check: function () { return Promise.resolve({ updateAvailable: false }); },
      download: function () { return Promise.reject(makeError('unavailable')); },
      install: function () { return Promise.reject(makeError('unavailable')); }
    };
  }
  if (!('mediaSession' in navigator)) {
    var noop = function () {};
    navigator.mediaSession = {
      metadata: null, playbackState: 'none',
      setActionHandler: noop, setPositionState: noop
    };
  }

  window.__FOLIA_GECKO_MEDIA_BASE__ = '/media/';

  // ============ F. 屏幕方向 / 手机适配的双向同步 ============
  /**
   * Folia 前端把「手机适配 / 方向 / 自适应显示」存在 localStorage
   * （key：folia_android_phone_fit、folia_android_phone_fit_orientation），
   * 原生侧存在 SharedPreferences。两边互不知道对方，会出现：
   *   - 重装 APK 后原生存的是旧的，而 localStorage 还在 → 方向不是用户上次选的
   *   - 页面自己调 apply() 只改 localStorage，不通知原生（只有用户拖开关才调插件）
   *
   * 所以这里在页面最早期做一次「以原生为准」的回灌：把原生的值写回 localStorage，
   * 并设置好 data-folia-phone-fit 属性（CSS 的适配规则依赖它）。
   * 之后用户在设置里改，走插件的 setPhoneFitLayout，两边天然一致。
   */
  (function syncPhoneFit() {
    var KEY_FIT = 'folia_android_phone_fit';
    var KEY_ORI = 'folia_android_phone_fit_orientation';

    function applyDomAttr(fit, ori) {
      try {
        var el = document.documentElement;
        if (!el) return;
        el.setAttribute('data-folia-phone-fit', fit ? 'true' : 'false');
        el.setAttribute('data-folia-phone-orientation', ori);
      } catch (e) { /* 忽略 */ }
    }

    nativeCall('FoliaNative', 'getPhoneFitLayout', {})
      .then(function (r) {
        if (!r) return;
        var enabled = !!r.enabled;
        var orientation = r.orientation === 'landscape' ? 'landscape' : 'portrait';
        try {
          localStorage.setItem(KEY_FIT, enabled ? 'true' : 'false');
          localStorage.setItem(KEY_ORI, orientation);
        } catch (e) { /* 忽略 */ }
        applyDomAttr(enabled, orientation);
        report('phone-fit', 'enabled=' + enabled + ' orientation=' + orientation +
          ' automotive=' + r.automotive);
      })
      .catch(function (e) {
        report('phone-fit-fail', String(e && e.message || e));
      });
  })();

  // 不再监听 localStorage 变化回推原生：页面拖开关时
  // （ponderRegistry 的 setPhoneFitEnabled）本来就同时调了 setPhoneFitLayout 插件，
  // 再加一层回推容易和原生形成回声环，反而把方向来回切换。
  // 只有「启动时以原生为准」这一步是必须的，上面已经做了。

  report('shim-installed',
    'v=' + BRIDGE_VERSION +
    ' href=' + location.href +
    ' androidBridge=' + !!window.androidBridge +
    ' takeover=' + VOLUME_TAKEOVER);

  // 自检：验证「页面 fetch → 原生 httpRequest → 外网」闭环
  window.__foliaGeckoSelfTest = function () {
    window.fetch('https://ssl.ptlogin2.qq.com/ptqrshow?appid=716027609&e=2&l=M&s=3&d=72&v=4&t='
      + Math.random() + '&daid=383&pt_3rd_aid=100497308',
      { method: 'GET', headers: { Referer: 'https://xui.ptlogin2.qq.com/' } })
      .then(function (r) {
        report('selftest-qq', 'status=' + r.status);
        return r.arrayBuffer();
      })
      .then(function (buf) { report('selftest-qq-body', 'len=' + buf.byteLength); })
      .catch(function (e) { report('selftest-qq-fail', String(e && e.message || e)); });
  };
  setTimeout(function () { window.__foliaGeckoSelfTest(); }, 6000);

  // 诊断：本地音乐为什么不可用 + 扫描是否真能返回曲目
  setTimeout(function () {
    // 页面模块加载后会整体重写 window.Capacitor / 覆盖属性，
    // 所以这里再做一次兜底补齐（幂等，覆盖了就补回来）。
    enforceNativeEnv();
    try {
      var p = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.FoliaNative;
      report('local-env',
        'platform=' + (window.Capacitor && window.Capacitor.getPlatform && window.Capacitor.getPlatform()) +
        ' isNative=' + !!(window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform()) +
        ' androidBridge=' + !!window.androidBridge +
        ' hasPlugin=' + !!p +
        ' hasScan=' + !!(p && p.scanLocalAudio) +
        ' hasPlayback=' + !!(p && p.updatePlaybackState) +
        ' showDirectoryPicker=' + ('showDirectoryPicker' in window) +
        ' lang=' + (navigator.languages ? navigator.languages.join(',') : navigator.language) +
        ' sysVol=' + systemFraction +
        ' isSecureContext=' + window.isSecureContext +
        ' origin=' + location.origin);
      if (p && p.scanLocalAudio) {
        p.scanLocalAudio({}).then(function (r) {
          report('local-scan', 'tracks=' + ((r && r.tracks) ? r.tracks.length : -1) +
            ' needsAllFiles=' + (r && r.needsAllFilesAccess));
        }).catch(function (e) {
          report('local-scan-fail', String(e && e.message || e));
        });
      }
    } catch (e) {
      report('local-env-fail', String(e && e.message || e));
    }
  }, 8000);

  // 页面模块加载后会整体重写 window.Capacitor / 覆盖全局属性，
  // 一次性补齐按不住（实测 platform 会变回 'web'、showDirectoryPicker 会消失）。
  // 所以用低频定时器持续兜底：被覆盖了就补回来，代价可忽略。
  (function keepNativeEnv() {
    var tick = function () {
      try { enforceNativeEnv(); } catch (e) { /* 忽略 */ }
    };
    window.addEventListener('load', function () {
      tick();
      setTimeout(tick, 300);
      setTimeout(tick, 1200);
      setTimeout(tick, 3000);
    });
    setInterval(tick, 2000);
  })();
})();
