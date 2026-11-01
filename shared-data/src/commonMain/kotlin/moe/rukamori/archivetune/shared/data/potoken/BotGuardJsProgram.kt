package moe.rukamori.archivetune.shared.data.potoken

/**
 * The JavaScript program the shared BotGuard engine evaluates in the host QuickJS runtime. The
 * preamble fakes the slice of browser environment YouTube's BotGuard interpreter reads — the same
 * role the WebView DOM plays on Android — and the `__bg*` driver functions port `po_token.html`
 * verbatim: `runBotGuard` executes the challenge interpreter and its VM program, `createPoTokenMinter`
 * binds the integrity token into a minter callback once, and `obtainPoToken` mints per identifier.
 *
 * Two host bindings must be defined before evaluation: `__hostSleep(ms)` returning a Promise for
 * the timer queue, and `__hostRandom(byteLength)` returning random bytes for `crypto.getRandomValues`.
 * Nothing here performs network I/O: the Create/GenerateIT requests stay Kotlin-side, exactly like
 * `blockNetworkLoads` on the WebView recipe.
 */
internal const val BOTGUARD_ENV_PROGRAM: String = """
(function () {
  var G = globalThis;
  var YT_ORIGIN = "https://www.youtube.com";
  var WV_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3";

  G.window = G;
  G.self = G;
  G.top = G;
  G.parent = G;
  G.frames = G;
  G.frameElement = null;
  G.opener = null;
  G.closed = false;
  G.isSecureContext = true;
  G.crossOriginIsolated = false;
  G.origin = YT_ORIGIN;
  G.baseURI = YT_ORIGIN + "/";
  G.name = "";
  G.devicePixelRatio = 1;

  // Timers: the only real scheduling the recipe needs (loadBotGuard's 1 ms poll) rides on the
  // async __hostSleep binding, so delayed callbacks run as ordinary promise jobs.
  var __timers = {};
  var __timerSeq = 1;
  function __clampMs(ms) { var n = ms | 0; if (n < 0) n = 0; if (n > 3600000) n = 3600000; return n; }
  G.setTimeout = function (fn, ms) {
    var id = __timerSeq++;
    var args = [].slice.call(arguments, 2);
    var t = { dead: false };
    __timers[id] = t;
    __hostSleep(__clampMs(ms)).then(function () {
      if (t.dead) return;
      delete __timers[id];
      if (typeof fn === "function") fn.apply(G, args);
      else if (typeof fn === "string") (0, eval)(fn);
    });
    return id;
  };
  G.clearTimeout = function (id) { var t = __timers[id]; if (t) { t.dead = true; delete __timers[id]; } };
  G.setInterval = function (fn, ms) {
    var id = __timerSeq++;
    var args = [].slice.call(arguments, 2);
    var t = { dead: false };
    __timers[id] = t;
    var tick = function () {
      __hostSleep(__clampMs(ms)).then(function () {
        if (t.dead) return;
        if (typeof fn === "function") fn.apply(G, args);
        else if (typeof fn === "string") (0, eval)(fn);
        tick();
      });
    };
    tick();
    return id;
  };
  G.clearInterval = G.clearTimeout;
  G.setImmediate = function (fn) { return G.setTimeout.apply(G, [fn, 0].concat([].slice.call(arguments, 1))); };
  G.clearImmediate = G.clearTimeout;
  if (typeof G.queueMicrotask !== "function") G.queueMicrotask = function (fn) { Promise.resolve().then(fn); };
  G.requestAnimationFrame = function (fn) { return G.setTimeout(function () { fn(G.performance.now()); }, 16); };
  G.cancelAnimationFrame = G.clearTimeout;

  // Base64: QuickJS has no atob/btoa and BotGuard decodes heavily.
  var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  G.btoa = function (input) {
    var str = String(input), out = "", i = 0;
    while (i < str.length) {
      var c1 = str.charCodeAt(i++) & 0xff;
      if (i >= str.length) { out += B64.charAt(c1 >> 2) + B64.charAt((c1 & 3) << 4) + "=="; break; }
      var c2 = str.charCodeAt(i++) & 0xff;
      if (i >= str.length) { out += B64.charAt(c1 >> 2) + B64.charAt(((c1 & 3) << 4) | (c2 >> 4)) + B64.charAt((c2 & 15) << 2) + "="; break; }
      var c3 = str.charCodeAt(i++) & 0xff;
      out += B64.charAt(c1 >> 2) + B64.charAt(((c1 & 3) << 4) | (c2 >> 4)) + B64.charAt(((c2 & 15) << 2) | (c3 >> 6)) + B64.charAt(c3 & 63);
    }
    return out;
  };
  G.atob = function (input) {
    var str = String(input).replace(/[=]+$/, ""), out = "";
    if (str.length % 4 === 1) throw new DOMException("InvalidCharacterError");
    for (var bc = 0, bs, buffer, idx = 0; (buffer = str.charAt(idx++)); ) {
      var p = B64.indexOf(buffer);
      if (p === -1) continue;
      bs = bc % 4 ? (bs << 6) + p : p;
      if (bc++ % 4) out += String.fromCharCode(255 & (bs >> ((-2 * bc) & 6)));
    }
    return out;
  };

  // UTF-8 TextEncoder/TextDecoder: BotGuard hashes identifiers as bytes.
  if (typeof G.TextEncoder !== "function") {
    G.TextEncoder = function TextEncoder() {};
    G.TextEncoder.prototype.encode = function (input) {
      var str = String(input == null ? "" : input), bytes = [];
      for (var i = 0; i < str.length; i++) {
        var cp = str.charCodeAt(i);
        if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < str.length) {
          var lo = str.charCodeAt(i + 1);
          if (lo >= 0xdc00 && lo <= 0xdfff) { cp = 0x10000 + ((cp - 0xd800) << 10) + (lo - 0xdc00); i++; }
        }
        if (cp < 0x80) bytes.push(cp);
        else if (cp < 0x800) bytes.push(0xc0 | (cp >> 6), 0x80 | (cp & 63));
        else if (cp < 0x10000) bytes.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
        else bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 63), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
      }
      return new Uint8Array(bytes);
    };
    G.TextEncoder.prototype.encodeInto = function (input, dest) {
      var src = this.encode(input), n = Math.min(src.length, dest.length);
      dest.set(src.subarray(0, n));
      return { read: n, written: n };
    };
  }
  if (typeof G.TextDecoder !== "function") {
    G.TextDecoder = function TextDecoder() {};
    G.TextDecoder.prototype.decode = function (input) {
      if (input == null) return "";
      var bytes = input instanceof ArrayBuffer ? new Uint8Array(input) : new Uint8Array(input.buffer, input.byteOffset || 0, input.byteLength);
      var out = "", i = 0;
      while (i < bytes.length) {
        var b = bytes[i++];
        if (b < 0x80) out += String.fromCharCode(b);
        else if (b < 0xe0) out += String.fromCharCode(((b & 31) << 6) | (bytes[i++] & 63));
        else if (b < 0xf0) out += String.fromCharCode(((b & 15) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63));
        else {
          var cp = ((b & 7) << 18) | ((bytes[i++] & 63) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63);
          cp -= 0x10000;
          out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 1023));
        }
      }
      return out;
    };
  }

  // WebCrypto: only getRandomValues is synchronous-usable; subtle stays an empty, honest absence.
  var cryptoObj = {
    getRandomValues: function (view) {
      if (!view || typeof view.byteLength !== "number") throw new TypeError("expected a typed array");
      var src = __hostRandom(view.byteLength);
      var arr = src && src.buffer && typeof src.length === "number" ? src : new Uint8Array(src);
      view.set(new Uint8Array(arr.buffer, arr.byteOffset || 0, Math.min(arr.byteLength, view.byteLength)));
      return view;
    },
    subtle: {},
    randomUUID: function () {
      var b = new Uint8Array(16);
      this.getRandomValues(b);
      b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128;
      var h = [];
      for (var i = 0; i < 16; i++) h.push(("0" + b[i].toString(16)).slice(-2));
      return h[0]+h[1]+h[2]+h[3]+"-"+h[4]+h[5]+"-"+h[6]+h[7]+"-"+h[8]+h[9]+"-"+h[10]+h[11]+h[12]+h[13]+h[14]+h[15];
    }
  };
  try { Object.defineProperty(G, "crypto", { value: cryptoObj }); } catch (e) { G.crypto = cryptoObj; }

  var perfStart = Date.now();
  G.performance = {
    timeOrigin: perfStart,
    now: function () { return Date.now() - perfStart; },
    mark: function () {}, measure: function () {},
    getEntries: function () { return []; }, getEntriesByName: function () { return []; }, getEntriesByType: function () { return []; },
    clearMarks: function () {}, clearMeasures: function () {}, clearResourceTimings: function () {},
    setResourceTimingBufferSize: function () {}
  };
  G.PerformanceObserver = function () { this.observe = function () {}; this.disconnect = function () {}; this.takeRecords = function () { return []; }; };

  var consoleSink = function () {};
  G.console = {
    log: consoleSink, debug: consoleSink, info: consoleSink, warn: consoleSink, error: consoleSink,
    trace: consoleSink, group: consoleSink, groupEnd: consoleSink, groupCollapsed: consoleSink,
    count: consoleSink, time: consoleSink, timeEnd: consoleSink, dir: consoleSink, table: consoleSink,
    assert: consoleSink, profile: consoleSink, profileEnd: consoleSink, timeStamp: consoleSink
  };

  // navigator / screen / location / history: plain browser-shaped facts, not a real engine's values.
  G.navigator = {
    appCodeName: "Mozilla", appName: "Netscape", appVersion: "5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3",
    userAgent: WV_UA, platform: "Win32", vendor: "Google Inc.", vendorSub: "", product: "Gecko", productSub: "20030107",
    language: "en-US", languages: ["en-US", "en"], onLine: true, cookieEnabled: true, doNotTrack: null,
    hardwareConcurrency: 8, deviceMemory: 8, maxTouchPoints: 0, webdriver: false,
    pdfViewerEnabled: true, plugins: { length: 0, item: function () { return null; }, namedItem: function () { return null; }, refresh: function () {} },
    mimeTypes: { length: 0, item: function () { return null; }, namedItem: function () { return null; } },
    userAgentData: { brands: [{ brand: "Chromium", version: "131" }, { brand: "Google Chrome", version: "131" }], mobile: false, platform: "Windows", getHighEntropyValues: function () { return Promise.resolve({}); } },
    mediaDevices: { enumerateDevices: function () { return Promise.resolve([]); } },
    getBattery: function () { return Promise.resolve({ charging: true, chargingTime: 0, dischargingTime: Infinity, level: 1 }); },
    javaEnabled: function () { return false; }, sendBeacon: function () { return true; },
    connection: { effectiveType: "4g", rtt: 50, downlink: 10, saveData: false },
    storage: { estimate: function () { return Promise.resolve({ quota: 0, usage: 0 }); } }
  };
  G.screen = { width: 1920, height: 1080, availWidth: 1920, availHeight: 1040, colorDepth: 24, pixelDepth: 24, availLeft: 0, availTop: 0 };
  G.location = {
    href: YT_ORIGIN + "/", origin: YT_ORIGIN, protocol: "https:", host: "www.youtube.com", hostname: "www.youtube.com",
    port: "", pathname: "/", search: "", hash: "",
    assign: function () {}, replace: function () {}, reload: function () {}, toString: function () { return YT_ORIGIN + "/"; }
  };
  G.history = { length: 1, state: null, scrollRestoration: "auto", back: function () {}, forward: function () {}, go: function () {}, pushState: function () {}, replaceState: function () {} };

  var makeStorage = function () {
    var map = {};
    return {
      get length() { return Object.keys(map).length; },
      key: function (i) { return Object.keys(map)[i] == null ? null : Object.keys(map)[i]; },
      getItem: function (k) { k = String(k); return Object.prototype.hasOwnProperty.call(map, k) ? map[k] : null; },
      setItem: function (k, v) { map[String(k)] = String(v); },
      removeItem: function (k) { delete map[String(k)]; },
      clear: function () { map = {}; }
    };
  };
  G.localStorage = makeStorage();
  G.sessionStorage = makeStorage();
  G.indexedDB = { open: function () { var r = { result: null, error: null, onsuccess: null, onerror: null, onupgradeneeded: null }; return r; }, deleteDatabase: function () { return {}; } };

  // Minimal event system: listeners register and are never fired, like a silent WebView page.
  var makeEventTarget = function () {
    var listeners = {};
    return {
      addEventListener: function (type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
      removeEventListener: function (type, fn) {
        var l = listeners[type]; if (!l) return;
        for (var i = l.length - 1; i >= 0; i--) if (l[i] === fn) l.splice(i, 1);
      },
      dispatchEvent: function () { return true; },
      __listeners: listeners
    };
  };
  var winEvents = makeEventTarget();
  G.addEventListener = winEvents.addEventListener;
  G.removeEventListener = winEvents.removeEventListener;
  G.dispatchEvent = winEvents.dispatchEvent;
  G.attachEvent = function () {};
  G.detachEvent = function () {};

  var DOMExceptionCtor = function (message, name) { this.message = String(message || ""); this.name = name || "Error"; };
  DOMExceptionCtor.prototype = Object.create(Error.prototype);
  G.DOMException = G.DOMException || DOMExceptionCtor;
  G.Event = G.Event || function (type, init) { this.type = String(type || ""); this.bubbles = !!(init && init.bubbles); this.cancelable = !!(init && init.cancelable); this.defaultPrevented = false; this.preventDefault = function () { this.defaultPrevented = true; }; this.stopPropagation = function () {}; this.stopImmediatePropagation = function () {}; };
  G.CustomEvent = G.CustomEvent || function (type, init) { G.Event.call(this, type, init); this.detail = init && init.detail; };
  G.MutationObserver = G.MutationObserver || function () { this.observe = function () {}; this.disconnect = function () {}; this.takeRecords = function () { return []; }; };
  G.ResizeObserver = G.ResizeObserver || function () { this.observe = function () {}; this.unobserve = function () {}; this.disconnect = function () {}; };
  G.IntersectionObserver = G.IntersectionObserver || function () { this.observe = function () {}; this.unobserve = function () {}; this.disconnect = function () {}; this.takeRecords = function () { return []; }; };

  // DOM stubs: every element reads like a detached, feature-empty node. BotGuard sees a real
  // document shape; canvas/WebGL answer "absent", which is fingerprint data, not a crash.
  var styleOf = function () { return { setProperty: function () {}, getPropertyValue: function () { return ""; }, removeProperty: function () {} }; };
  var rectOf = function () { return { x: 0, y: 0, top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0, toJSON: function () { return {}; } }; };
  var makeElement = function (tag) {
    var el = {
      nodeType: 1, nodeName: String(tag).toUpperCase(), tagName: String(tag).toUpperCase(), localName: String(tag).toLowerCase(),
      ownerDocument: null, parentNode: null, parentElement: null, childNodes: [], children: [], childElementCount: 0,
      style: styleOf(), dataset: {}, classList: { add: function () {}, remove: function () {}, toggle: function () {}, contains: function () { return false; } },
      attributes: {}, id: "", className: "", innerHTML: "", outerHTML: "", innerText: "", textContent: "",
      setAttribute: function (k, v) { this.attributes[k] = String(v); }, getAttribute: function (k) { return k in this.attributes ? this.attributes[k] : null; },
      hasAttribute: function (k) { return k in this.attributes; }, removeAttribute: function (k) { delete this.attributes[k]; },
      appendChild: function (c) { this.childNodes.push(c); if (c && c.nodeType === 1) { this.children.push(c); this.childElementCount = this.children.length; } return c; },
      removeChild: function (c) { var i = this.childNodes.indexOf(c); if (i >= 0) this.childNodes.splice(i, 1); return c; },
      insertBefore: function (c) { return this.appendChild(c); }, replaceChild: function (n, o) { return o; },
      cloneNode: function () { return makeElement(this.tagName); },
      addEventListener: function () {}, removeEventListener: function () {}, dispatchEvent: function () { return true; },
      getElementsByTagName: function () { return []; }, getElementsByClassName: function () { return []; },
      querySelector: function () { return null; }, querySelectorAll: function () { return []; },
      getBoundingClientRect: rectOf, getClientRects: function () { return []; },
      scrollIntoView: function () {}, focus: function () {}, blur: function () {}, click: function () {},
      contains: function () { return false; }, hasChildNodes: function () { return this.childNodes.length > 0; },
      insertAdjacentHTML: function () {}, insertAdjacentElement: function (p, c) { return this.appendChild(c); },
      attachShadow: function () { return makeElement("shadow-root"); }, matches: function () { return false; },
      closest: function () { return null; }, remove: function () {}
    };
    if (String(tag).toLowerCase() === "canvas") {
      el.width = 300; el.height = 150;
      el.getContext = function (kind) {
        if (kind === "2d") {
          return {
            canvas: el, fillRect: function () {}, clearRect: function () {}, strokeRect: function () {},
            fillText: function () {}, strokeText: function () {}, measureText: function () { return { width: 0 }; },
            drawImage: function () {}, getImageData: function (x, y, w, h) { return { data: new Uint8ClampedArray((w | 0) * (h | 0) * 4), width: w | 0, height: h | 0 }; },
            putImageData: function () {}, createImageData: function (w, h) { return { data: new Uint8ClampedArray((w | 0) * (h | 0) * 4), width: w | 0, height: h | 0 }; },
            beginPath: function () {}, closePath: function () {}, moveTo: function () {}, lineTo: function () {}, arc: function () {}, rect: function () {},
            fill: function () {}, stroke: function () {}, clip: function () {}, save: function () {}, restore: function () {},
            translate: function () {}, scale: function () {}, rotate: function () {}, transform: function () {}, setTransform: function () {},
            createLinearGradient: function () { return { addColorStop: function () {} }; }, createRadialGradient: function () { return { addColorStop: function () {} }; },
            createPattern: function () { return null; }, setLineDash: function () {}, getLineDash: function () { return []; },
            isPointInPath: function () { return false; }, isPointInStroke: function () { return false; }
          };
        }
        return null;
      };
      el.toDataURL = function () { return "data:image/png;base64,"; };
      el.toBlob = function (cb) { if (typeof cb === "function") cb(null); };
      el.captureStream = function () { return { getTracks: function () { return []; } }; };
    }
    if (String(tag).toLowerCase() === "iframe") { el.contentWindow = G; el.contentDocument = null; el.src = ""; }
    if (String(tag).toLowerCase() === "video" || String(tag).toLowerCase() === "audio") {
      el.canPlayType = function () { return ""; }; el.play = function () { return Promise.reject(new Error("no media")); };
      el.pause = function () {}; el.load = function () {}; el.src = ""; el.currentTime = 0; el.duration = 0;
    }
    if (String(tag).toLowerCase() === "img") { el.src = ""; el.complete = false; el.naturalWidth = 0; el.naturalHeight = 0; }
    return el;
  };

  var docEvents = makeEventTarget();
  G.document = {
    nodeType: 9, documentElement: makeElement("html"), head: makeElement("head"), body: makeElement("body"),
    title: "", URL: YT_ORIGIN + "/", documentURI: YT_ORIGIN + "/", baseURI: YT_ORIGIN + "/", domain: "www.youtube.com",
    location: G.location, referrer: "", cookie: "", readyState: "complete", hidden: false, visibilityState: "visible",
    characterSet: "UTF-8", charset: "UTF-8", inputEncoding: "UTF-8", contentType: "text/html", compatMode: "CSS1Compat",
    defaultView: G, currentScript: null, activeElement: null,
    scripts: [], embeds: [], forms: [], images: [], anchors: [], links: [],
    createElement: function (tag) { var el = makeElement(tag); el.ownerDocument = this; return el; },
    createElementNS: function (ns, tag) { return this.createElement(tag); },
    createTextNode: function (t) { return { nodeType: 3, textContent: String(t), ownerDocument: this }; },
    createComment: function (t) { return { nodeType: 8, textContent: String(t) }; },
    createDocumentFragment: function () { return { nodeType: 11, childNodes: [], children: [], appendChild: function (c) { this.childNodes.push(c); return c; }, querySelector: function () { return null; }, querySelectorAll: function () { return []; } }; },
    createEvent: function () { return { initEvent: function () {}, type: "" }; },
    getElementById: function () { return null; }, getElementsByTagName: function () { return []; },
    getElementsByClassName: function () { return []; }, getElementsByName: function () { return []; },
    querySelector: function () { return null; }, querySelectorAll: function () { return []; },
    addEventListener: docEvents.addEventListener, removeEventListener: docEvents.removeEventListener, dispatchEvent: docEvents.dispatchEvent,
    hasFocus: function () { return true; }, adoptNode: function (n) { return n; }, importNode: function (n) { return n; },
    implementation: { hasFeature: function () { return true; }, createHTMLDocument: function () { return this.ownerDocument || G.document; }, createDocumentType: function () { return {}; } },
    fonts: { size: 0, check: function () { return false; }, ready: Promise.resolve(), values: function () { return { next: function () { return { done: true }; } }; } },
    fullscreenElement: null, exitFullscreen: function () {}, documentMode: undefined
  };
  G.document.documentElement.ownerDocument = G.document;
  G.document.head.ownerDocument = G.document;
  G.document.body.ownerDocument = G.document;

  G.getComputedStyle = function () { return { getPropertyValue: function () { return ""; }, setProperty: function () {}, length: 0 }; };
  G.matchMedia = function (q) {
    return { matches: false, media: String(q || ""), addListener: function () {}, removeListener: function () {}, addEventListener: function () {}, removeEventListener: function () {}, dispatchEvent: function () { return true; } };
  };

  // XMLHttpRequest: BotGuard may report through it; requests complete silently with an empty body.
  G.XMLHttpRequest = function XMLHttpRequest() {
    this.readyState = 0; this.status = 0; this.statusText = ""; this.responseText = ""; this.response = "";
    this.responseType = ""; this.responseURL = ""; this.timeout = 0; this.withCredentials = false;
    this.onreadystatechange = null; this.onload = null; this.onerror = null; this.onloadend = null;
    var self = this;
    this.open = function () { self.readyState = 1; };
    this.setRequestHeader = function () {};
    this.getResponseHeader = function () { return null; };
    this.getAllResponseHeaders = function () { return ""; };
    this.send = function () {
      self.readyState = 4; self.status = 0;
      if (typeof self.onreadystatechange === "function") self.onreadystatechange();
      if (typeof self.onloadend === "function") self.onloadend();
    };
    this.abort = function () {}; this.addEventListener = function () {}; this.removeEventListener = function () {};
    this.overrideMimeType = function () {}; this.upload = { addEventListener: function () {} };
  };
  G.Image = function Image() { var el = makeElement("img"); return el; };
  G.Blob = G.Blob || function (parts, opts) { this.size = 0; this.type = (opts && opts.type) || ""; this.text = function () { return Promise.resolve(""); }; this.arrayBuffer = function () { return Promise.resolve(new ArrayBuffer(0)); }; };
  G.FileReader = function () { this.readAsText = function () {}; this.readAsArrayBuffer = function () {}; this.readAsDataURL = function () {}; this.result = null; this.onload = null; this.onerror = null; };
  G.FormData = function () { var d = []; this.append = function (k, v) { d.push([k, v]); }; this.get = function (k) { for (var i = 0; i < d.length; i++) if (d[i][0] === k) return d[i][1]; return null; }; };
  G.Headers = function (init) { this.get = function () { return null; }; this.set = function () {}; this.append = function () {}; this.has = function () { return false; }; };
  G.fetch = function () { return Promise.reject(new TypeError("fetch is not available")); };
  G.AbortController = function () { this.signal = { aborted: false, addEventListener: function () {} }; this.abort = function () { this.signal.aborted = true; }; };
  G.Worker = undefined;
  G.SharedWorker = undefined;
  G.WebSocket = undefined;
  G.EventSource = undefined;
  G.Notification = undefined;
  G.openDatabase = undefined;
  G.webkitSpeechRecognition = undefined;
  G.speechSynthesis = undefined;
  G.open = function () { return null; };
  G.close = function () {};
  G.alert = function () {}; G.confirm = function () { return false; }; G.prompt = function () { return null; };
  G.print = function () {};
  G.postMessage = function () {};
  G.getSelection = function () { return { rangeCount: 0, toString: function () { return ""; }, removeAllRanges: function () {} }; };
  G.chrome = { app: { isInstalled: false }, runtime: {} };
  G.CSS = { supports: function () { return false; }, escape: function (s) { return String(s); } };
  if (typeof G.Intl !== "object" || G.Intl == null) {
    G.Intl = {
      DateTimeFormat: function () { return { format: function (d) { return new Date(d).toUTCString(); }, formatToParts: function () { return []; }, resolvedOptions: function () { return { timeZone: "UTC", locale: "en-US", calendar: "gregory", numberingSystem: "latn" }; } }; },
      NumberFormat: function () { return { format: function (n) { return String(n); }, formatToParts: function () { return []; }, resolvedOptions: function () { return { locale: "en-US" }; } }; },
      Collator: function () { return { compare: function (a, b) { return a < b ? -1 : a > b ? 1 : 0; }, resolvedOptions: function () { return { locale: "en-US" }; } }; },
      Locale: function (tag) { return { toString: function () { return String(tag); } }; },
      supportedValuesOf: function () { return []; }
    };
  }
  try { Object.defineProperty(G, "Atomics", { value: undefined }); } catch (e) { G.Atomics = undefined; }
  try { Object.defineProperty(G, "SharedArrayBuffer", { value: undefined }); } catch (e) { G.SharedArrayBuffer = undefined; }

  // ---- po_token.html recipe, namespaced under __bg -------------------------------------------------

  var __bg = { vmFunctions: null, vm: null, program: null, minter: null, webPoSignalOutput: [], challenge: null };
  G.__bg = __bg;

  function __bgLoadBotGuard(challengeData) {
    __bg.vm = window[challengeData.globalName];
    __bg.program = challengeData.program;
    __bg.vmFunctions = null;
    if (!__bg.vm) throw new Error("[BotGuardClient]: VM not found in the global object");
    if (!__bg.vm.a) throw new Error("[BotGuardClient]: Could not load program");

    var vmFunctionsCallback = function (asyncSnapshotFunction, shutdownFunction, passEventFunction, checkCameraFunction) {
      __bg.vmFunctions = {
        asyncSnapshotFunction: asyncSnapshotFunction,
        shutdownFunction: shutdownFunction,
        passEventFunction: passEventFunction,
        checkCameraFunction: checkCameraFunction
      };
    };

    try {
      __bg.vm.a(__bg.program, vmFunctionsCallback, true, undefined, function () {}, [[], []]);
    } catch (e) {
      throw new Error("[BotGuardClient]: Failed to execute program: " + e.message);
    }

    return new Promise(function (resolve, reject) {
      var attempts = 0;
      var maxAttempts = 10000; // 10 seconds at 1ms intervals
      var checkInterval = setInterval(function () {
        if (__bg.vmFunctions && __bg.vmFunctions.asyncSnapshotFunction) {
          clearInterval(checkInterval);
          resolve({ vmFunctions: __bg.vmFunctions, vm: __bg.vm, program: __bg.program });
        } else if (attempts >= maxAttempts) {
          clearInterval(checkInterval);
          reject(new Error("[BotGuardClient]: Timeout waiting for asyncSnapshotFunction"));
        }
        attempts++;
      }, 1);
    });
  }

  function __bgSnapshot(botguard, args) {
    return new Promise(function (resolve, reject) {
      if (!botguard.vmFunctions || !botguard.vmFunctions.asyncSnapshotFunction) {
        return reject(new Error("[BotGuardClient]: Async snapshot function not found"));
      }
      try {
        botguard.vmFunctions.asyncSnapshotFunction(function (response) { resolve(response); },
          [args.contentBinding, args.signedTimestamp, args.webPoSignalOutput, args.skipPrivacyBuffer]);
      } catch (e) {
        reject(new Error("[BotGuardClient]: Snapshot failed: " + e.message));
      }
    });
  }

  G.__bgRunBotGuard = function (challengeData) {
    var interpreterJavascript = challengeData.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;
    if (interpreterJavascript) {
      new Function(interpreterJavascript)();
    } else {
      throw new Error("[BotGuardClient]: Could not load VM - no interpreter JavaScript");
    }
    __bg.webPoSignalOutput = [];
    return __bgLoadBotGuard({ globalName: challengeData.globalName, program: challengeData.program })
      .then(function (botguard) { return __bgSnapshot(botguard, { webPoSignalOutput: __bg.webPoSignalOutput }); })
      .then(function (botguardResponse) { return botguardResponse; });
  };

  G.__bgCreateMinter = function (integrityToken) {
    var getMinter = __bg.webPoSignalOutput[0];
    if (!getMinter) return Promise.reject(new Error("PMD:Undefined - webPoSignalOutput[0] is not defined"));
    if (typeof getMinter !== "function") return Promise.reject(new Error("PMD:NotFunction - webPoSignalOutput[0] is not a function, got: " + typeof getMinter));
    var minterResult;
    try {
      minterResult = getMinter(integrityToken);
    } catch (e) {
      return Promise.reject(new Error("GMC:Failed - getMinter() threw: " + e.message));
    }
    return Promise.resolve(minterResult).then(function (mintCallback) {
      if (!mintCallback) throw new Error("APF:Undefined - mintCallback is undefined");
      if (typeof mintCallback !== "function") throw new Error("APF:NotFunction - mintCallback is not a function, got: " + typeof mintCallback);
      __bg.minter = mintCallback;
      return "ok";
    });
  };

  G.__bgMint = function (identifier) {
    if (!__bg.minter) return Promise.reject(new Error("MNT:NotInit - poTokenMinter was not initialized. Call __bgCreateMinter first."));
    var mintResult;
    try {
      mintResult = __bg.minter(identifier);
    } catch (e) {
      return Promise.reject(new Error("MNT:Failed - mintCallback() threw: " + e.message));
    }
    return Promise.resolve(mintResult).then(function (result) {
      if (!result) throw new Error("YNJ:Undefined - mint result is undefined");
      if (!(result instanceof Uint8Array)) throw new Error("ODM:Invalid - result is not Uint8Array, got: " + Object.prototype.toString.call(result));
      return result.join(",");
    });
  };
})();
"""
