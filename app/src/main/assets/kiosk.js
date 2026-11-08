/*
 * 全画面ブラウザ 注入スクリプト
 *
 * WebViewCompat.addDocumentStartJavaScript で「すべてのフレーム（iframe含む）」に注入される。
 * GAS の Webアプリは script.google.com（外側）→ googleusercontent.com（内側 iframe 2段）
 * という入れ子構造なので、ページ本体のスクロールは内側の iframe で行う必要がある。
 * そのため各フレームが自分でスクロール対象を探し、ネイティブ側（KioskBridge）と
 * メッセージをやり取りして「開始 / 停止 / 最下部到達」を連携する。
 */
(function () {
  'use strict';
  if (window.__kioskBrowserInjected) return;
  window.__kioskBrowserInjected = true;

  var bridge = window.KioskBridge || null;

  function send(obj) {
    try { if (bridge) bridge.postMessage(JSON.stringify(obj)); } catch (e) { /* ignore */ }
  }

  /* ------------------------------------------------------------------
   * 1. GAS の警告バナー除去
   *   「このアプリケーションは、Google Apps Script のユーザーによって作成されました」
   *   は script.google.com の最上位フレームに表示される。ユーザーのコンテンツは
   *   iframe 内にあるので、「iframe を含まず、文言を含む要素」だけを非表示にし、
   *   残った iframe を画面いっぱいに広げる。
   * ------------------------------------------------------------------ */
  var host = '';
  try { host = location.hostname || ''; } catch (e) { /* ignore */ }
  var isGasTop = /(^|\.)script\.google\.com$/i.test(host) && window === window.top;
  var BANNER_TEXT = /Google\s*Apps\s*Script/i;
  var BANNER_IDS = ['warning', 'warning-bar', 'warning-bar-table'];

  function hideEl(el) {
    if (el.style.getPropertyValue('display') !== 'none') {
      el.style.setProperty('display', 'none', 'important');
    }
  }

  function cleanGas() {
    var body = document.body;
    if (!body) return;

    BANNER_IDS.forEach(function (id) {
      var el = document.getElementById(id);
      if (el && !el.querySelector('iframe')) hideEl(el);
    });

    (function walk(parent) {
      var kids = parent.children;
      for (var i = 0; i < kids.length; i++) {
        var k = kids[i];
        var tag = k.tagName;
        if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'IFRAME' || tag === 'NOSCRIPT') continue;
        if (k.querySelector('iframe')) { walk(k); continue; }
        if (BANNER_TEXT.test(k.textContent || '')) hideEl(k);
      }
    })(body);

    // 一番大きい iframe（＝ユーザーのページ）を全画面に
    var frames = document.getElementsByTagName('iframe');
    var best = null, bestArea = 0;
    for (var j = 0; j < frames.length; j++) {
      var r = frames[j].getBoundingClientRect();
      var a = r.width * r.height;
      if (a > bestArea) { bestArea = a; best = frames[j]; }
    }
    if (best) {
      var s = best.style;
      [['position', 'fixed'], ['top', '0'], ['left', '0'], ['width', '100%'],
       ['height', '100%'], ['border', '0'], ['margin', '0'], ['z-index', '2147483000']]
        .forEach(function (p) { s.setProperty(p[0], p[1], 'important'); });
    }
  }

  if (isGasTop) {
    var pending = false;
    var schedule = function () {
      if (pending) return;
      pending = true;
      setTimeout(function () { pending = false; cleanGas(); }, 50);
    };
    var observe = function () {
      cleanGas();
      try {
        new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
      } catch (e) { /* ignore */ }
    };
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', observe);
    } else {
      observe();
    }
    window.addEventListener('load', cleanGas);
  }

  /* ------------------------------------------------------------------
   * 2. 自動スクロール
   * ------------------------------------------------------------------ */
  var st = {
    active: false, gen: 0, cycle: -1, speed: 60, startAt: 0,
    pos: 0, scroller: null, lastTs: 0, lastScan: 0, bottomSince: 0,
    reportedScrolling: false, reportedBottom: false
  };

  function viewportH() {
    return window.innerHeight || (document.documentElement && document.documentElement.clientHeight) || 0;
  }

  function maxScroll(el) {
    return Math.max(0, el.scrollHeight - el.clientHeight);
  }

  // このフレームでスクロールすべき要素を探す（画面の半分以上の高さがあるもの）
  function findScroller() {
    var vh = viewportH();
    if (vh < 100) return null; // 小さい/非表示のフレームは対象外
    var se = document.scrollingElement || document.documentElement;
    if (se && maxScroll(se) > 4) return se;
    if (!document.body) return null;
    var all = document.body.getElementsByTagName('*');
    var best = null, bestArea = 0;
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      if (e.clientHeight < vh * 0.5 || maxScroll(e) <= 4) continue;
      var oy = getComputedStyle(e).overflowY;
      if (oy !== 'auto' && oy !== 'scroll' && oy !== 'overlay') continue;
      var area = e.clientWidth * e.clientHeight;
      if (area > bestArea) { bestArea = area; best = e; }
    }
    return best;
  }

  function loop(gen) {
    return function step(ts) {
      if (!st.active || gen !== st.gen) return;
      requestAnimationFrame(step);

      var now = Date.now();
      var dt = st.lastTs ? Math.min(ts - st.lastTs, 100) : 0;
      st.lastTs = ts;
      if (now < st.startAt) return;

      var sc = st.scroller;
      if (!sc || !sc.isConnected || maxScroll(sc) <= 4) {
        if (now - st.lastScan < 1000) return;
        st.lastScan = now;
        sc = st.scroller = findScroller();
        if (!sc) return;
        st.pos = sc.scrollTop;
      }

      if (!st.reportedScrolling) {
        st.reportedScrolling = true;
        send({ t: 'scrolling', cycle: st.cycle });
      }

      var max = maxScroll(sc);
      if (st.pos < max - 1) {
        st.pos = Math.min(max, st.pos + st.speed * dt / 1000);
        sc.scrollTop = st.pos;
        st.bottomSince = 0;
      } else if (!st.bottomSince) {
        st.bottomSince = now;
      } else if (!st.reportedBottom && now - st.bottomSince > 1500) {
        // 1.5秒間最下部のまま（遅延読み込みで伸びなかった）→ 到達を通知
        st.reportedBottom = true;
        send({ t: 'bottom', cycle: st.cycle });
      }
    };
  }

  function start(m) {
    if (st.active && st.cycle === m.cycle) return;
    st.gen++;
    st.active = true;
    st.cycle = m.cycle;
    st.speed = m.speed || 60;
    st.startAt = Date.now() + (m.topPause || 0);
    st.scroller = null;
    st.lastTs = 0;
    st.lastScan = 0;
    st.bottomSince = 0;
    st.reportedScrolling = false;
    st.reportedBottom = false;
    st.pos = 0;
    try {
      var sc = findScroller();
      if (sc) sc.scrollTop = 0;
      window.scrollTo(0, 0);
    } catch (e) { /* ignore */ }
    requestAnimationFrame(loop(st.gen));
  }

  function stop() {
    st.active = false;
    st.gen++;
  }

  if (bridge) {
    bridge.onmessage = function (ev) {
      var m;
      try { m = JSON.parse(ev.data); } catch (e) { return; }
      if (!m) return;
      if (m.cmd === 'start') start(m);
      else if (m.cmd === 'stop') stop();
      else if (m.cmd === 'speed' && m.speed) st.speed = m.speed;
    };
    // ネイティブ側のページ切替処理と前後しても取りこぼさないよう、複数回通知する
    send({ t: 'ready' });
    document.addEventListener('DOMContentLoaded', function () { send({ t: 'ready' }); });
    window.addEventListener('load', function () { send({ t: 'ready' }); });
  }
})();
