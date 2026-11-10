// Movy TV site search, page side (the native side is TvSearchController / SearchKeyboard).
// - OK on the site's search box opens the native TV keyboard (MovyTvNative.searchOpen). The box itself is never used for typing:
//   it is marked inputmode=none and read-only (the WebView keyboard cannot pop up, the page still gets OK) and the app mirrors each keystroke back (TV.searchSet),
//   presses Enter for Done (TV.searchSubmit) and gives the D-pad back to the box when the keyboard closes (TV.searchClosed).
// - A round mic button sits right of the box, same height, as a normal D-pad stop. OK starts the native recogniser
//   (MovyTvNative.micStart); the phrase comes back through TV.micResult, is typed into the box and the focus moves to the first result.
// - The drawer's Search item calls TV.searchFromDrawer: show the box (opener button or route), focus it, open the keyboard.
// The box, opener, route, result selector and the "press Enter" flag come from the profile (`search`); nothing here knows a site.
// Top frame only. Needs window.__movyTv (tvnav.js).
(function (root) {
  'use strict';

  // ---------- pure rules (unit-tested) ----------

  /** Index of the search box to use among candidates {w,h,top,left,inView}: a laid-out one, in the viewport first, then the topmost. -1 = none. */
  function chooseField(items) {
    var best = -1;
    for (var i = 0; i < items.length; i++) {
      var c = items[i];
      if (!c || !(c.w > 0) || !(c.h > 0)) continue;
      if (best < 0) { best = i; continue; }
      var b = items[best];
      if (!!c.inView !== !!b.inView) { if (c.inView) best = i; continue; }
      if (c.top < b.top - 1 || (Math.abs(c.top - b.top) <= 1 && c.left < b.left)) best = i;
    }
    return best;
  }

  /**
   * Where the mic button goes: right of the field, the field's height (clamped 36..72), vertically centred on it. When there is no
   * room to the right (needs `size + gap + margin`, or `forceInside`: something else is there) it sits inside the field's right end instead.
   */
  function micBox(f, viewW, gap, margin, forceInside) {
    var size = Math.max(36, Math.min(72, Math.round(f.h)));
    var left = f.l + f.w + gap;
    var inside = !!forceInside || left + size > viewW - margin;
    if (inside) left = f.l + f.w - size - 6;
    return { left: Math.round(left), top: Math.round(f.t + (f.h - size) / 2), size: size, inside: inside };
  }

  /**
   * How many ancestors (nearest first) belong to the visible search "pill" around the input: each must hold the input, be at most
   * 1.8x as tall, at most 1.6x + 80 px as wide and at most 90% of the view wide (a whole header row is none of these).
   * The input itself is often narrower than the pill drawn around it (icons, a clear button).
   */
  function pillDepth(input, ancestors, viewW) {
    var n = 0;
    for (var i = 0; i < ancestors.length; i++) {
      var a = ancestors[i];
      if (!a || a.w > viewW * 0.9 || a.w > input.w * 1.6 + 80 || a.h > input.h * 1.8 || a.l > input.l + 1 || a.l + a.w < input.l + input.w - 1) break;
      n = i + 1;
    }
    return n;
  }

  /** The rect of that pill (the input's own rect when it has none). */
  function pillRect(input, ancestors, viewW) {
    var n = pillDepth(input, ancestors, viewW);
    return n ? ancestors[n - 1] : input;
  }

  /** The result to focus after a spoken search among {t,l,w,h} rects: the topmost, then leftmost, below the field and on screen. -1 = none. */
  function firstResult(cands, field, viewH) {
    var best = -1;
    for (var i = 0; i < cands.length; i++) {
      var c = cands[i];
      if (!c || !(c.w > 0) || !(c.h > 0)) continue;
      if (c.t < field.b - 4 || c.t >= viewH) continue;
      if (best < 0 || c.t < cands[best].t - 6 || (Math.abs(c.t - cands[best].t) <= 6 && c.l < cands[best].l)) best = i;
    }
    return best;
  }

  /** OK on the box (a real key, once): the keyboard opens. Held keys and our own synthetic Enter do not. */
  function isOpenKey(e) {
    return !!e && e.type === 'keydown' && (e.keyCode === 13 || e.keyCode === 23) && !e.repeat && e.isTrusted !== false;
  }

  /** The selection that lets tvnav treat the caret as "at the edge" for LEFT / RIGHT, so the box never traps the D-pad. */
  function caretFor(dir, len) {
    if (dir === 'left') return [0, 0];
    if (dir === 'right') return [len, len];
    return null;
  }

  var api = { chooseField: chooseField, micBox: micBox, pillRect: pillRect, pillDepth: pillDepth, firstResult: firstResult, isOpenKey: isOpenKey, caretFor: caretFor };
  if (typeof module === 'object' && module && module.exports) { module.exports = api; return; }

  var TV = root.__movyTv;
  if (!TV || TV.searchLoaded || root.parent !== root) return;
  TV.searchLoaded = true;
  var doc = root.document;
  var KBD_KEY = 'movy_tv_open_kbd';
  var TICK_MS = 150;
  var MIC_ID = 'movy-tv-mic';

  function native() { return root.MovyTvNative; }
  function cfg() { return (TV.PF && TV.PF.raw && TV.PF.raw.search) || null; }

  // ---------- finding the box ----------

  function all(sel) {
    if (!sel) return [];
    try { return [].slice.call(doc.querySelectorAll(sel)); } catch (e) { return []; }
  }

  function rectOf(el) { var r = el.getBoundingClientRect(); return { l: r.left, t: r.top, w: r.width, h: r.height, b: r.bottom }; }

  function laidOut(el) {
    if (!el.isConnected) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 20 || r.height < 14) return false;
    var cs = root.getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none' && parseFloat(cs.opacity) > 0.05;
  }

  /** The search box that is on screen now (the profile's selector), or null. */
  function visibleField() {
    var c = cfg();
    if (!c) return null;
    var els = all(c.input).filter(laidOut);
    var items = els.map(function (el) {
      var r = rectOf(el);
      return { w: r.w, h: r.h, top: r.t, left: r.l, inView: r.b > 0 && r.t < root.innerHeight && r.l < root.innerWidth };
    });
    var i = chooseField(items);
    return i < 0 ? null : els[i];
  }

  function isField(el) {
    var c = cfg();
    if (!c || !el || !el.matches) return false;
    try { return el.tagName === 'INPUT' && el.matches(c.input); } catch (e) { return false; }
  }

  /**
   * The site's own keyboard must never come up for the box: the app types into it through us. The box is read-only for the WebView:
   * on an editable field Chromium takes OK for itself (to show its keyboard) and the page never sees the key; a read-only one
   * passes it on, and the value setter in setValue still works.
   */
  function quiet() {
    var c = cfg();
    if (!c) return;
    all(c.input).forEach(function (el) {
      if (el.getAttribute('inputmode') !== 'none') el.setAttribute('inputmode', 'none');
      if (!el.readOnly) el.readOnly = true;
    });
  }

  // ---------- typing into the box ----------

  var active = null; // the box the keyboard / mic works on

  function setValue(el, text) {
    var proto = el.tagName === 'TEXTAREA' ? root.HTMLTextAreaElement.prototype : root.HTMLInputElement.prototype;
    var d = Object.getOwnPropertyDescriptor(proto, 'value');
    if (d && d.set) d.set.call(el, text); else el.value = text; // the prototype setter: React's value tracker must see the change
    try { el.dispatchEvent(new root.InputEvent('input', { bubbles: true, inputType: 'insertText', data: null })); } catch (e) { el.dispatchEvent(new root.Event('input', { bubbles: true })); }
  }

  var synth = false;

  function pressEnter(el) {
    var o = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
    synth = true; // tvnav's key hook skips our own events
    try {
      ['keydown', 'keypress', 'keyup'].forEach(function (t) { el.dispatchEvent(new root.KeyboardEvent(t, o)); });
    } finally { synth = false; }
  }

  function currentBox() { return active && active.isConnected ? active : visibleField(); }

  TV.searchSet = function (text) {
    var el = currentBox();
    if (el) setValue(el, String(text || ''));
  };

  TV.searchSubmit = function () {
    var el = currentBox();
    if (el) { pressEnter(el); el.dispatchEvent(new root.Event('change', { bubbles: true })); }
  };

  /** The keyboard closed (Done, BACK, hidden): the D-pad focus goes back to the box. */
  TV.searchClosed = function () {
    var el = currentBox();
    active = null;
    if (!el) return;
    TV.focusHeld = false;
    TV.setCurrent(el, null);
  };

  function openKeyboard(el) {
    var n = native();
    if (!n || !n.searchOpen) return false;
    active = el;
    if (TV.currentEl() !== el) TV.setCurrent(el, null);
    n.searchOpen(String(el.value || ''));
    return true;
  }

  // ---------- keys (chained in front of the other tvnav key hooks) ----------

  var prevKey = TV.hooks.key;
  TV.hooks.key = function (e, dir, a) {
    if (synth) return true; // our own Enter: tvnav must not act on it, the site still sees it
    if (!a) a = TV.currentEl();
    if (a && isField(a)) {
      var code = e.keyCode;
      if ((code === 13 || code === 23) && e.type === 'keydown') {
        e.preventDefault();
        e.stopPropagation();
        if (isOpenKey(e)) openKeyboard(a);
        return true;
      }
      var sel = caretFor(dir, (a.value || '').length);
      if (sel) { try { a.setSelectionRange(sel[0], sel[1]); } catch (x) { /* some input types have no selection */ } }
    }
    return prevKey ? prevKey(e, dir, a) : false;
  };

  // ---------- the mic button ----------

  var btn = null;
  var micOk = false;
  var micCheckedAt = 0;
  var micField = null;
  var micInside = false;

  var MIC_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="9" y="3" width="6" height="11" rx="3"></rect><path d="M5 11a7 7 0 0 0 14 0M12 18v3"></path></svg>';

  function installStyle() {
    var c = cfg();
    var site = doc.getElementById('__movy_tv_search_css');
    if (c && c.css && !site) {
      site = doc.createElement('style');
      site.id = '__movy_tv_search_css';
      site.textContent = c.css;
      (doc.head || doc.documentElement).appendChild(site);
    }
    if (doc.getElementById('__movy_tv_mic_style')) return;
    var st = doc.createElement('style');
    st.id = '__movy_tv_mic_style';
    st.textContent =
      '#' + MIC_ID + '{position:absolute;z-index:2147482000;display:none;align-items:center;justify-content:center;margin:0;padding:0;' +
      'box-sizing:border-box;border-radius:9999px;border:2px solid rgba(255,255,255,.55);background:rgba(15,16,20,.78);color:#fff;' +
      'cursor:pointer;-webkit-appearance:none;appearance:none}' +
      '#' + MIC_ID + '.on{display:flex}' +
      '#' + MIC_ID + ' svg{width:48%;height:48%;stroke:currentColor;fill:none;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}';
    (doc.head || doc.documentElement).appendChild(st);
  }

  function ensureButton() {
    if (btn && btn.isConnected) return btn;
    installStyle();
    btn = doc.createElement('button');
    btn.id = MIC_ID;
    btn.type = 'button';
    btn.setAttribute('aria-label', 'Voice search');
    btn.innerHTML = MIC_SVG;
    // A site that closes its search on an outside press must not hear the presses on the mic (they stop here, the click still works).
    ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(function (t) { btn.addEventListener(t, function (e) { e.stopPropagation(); }); });
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      var n = native();
      micField = visibleField();
      if (n && n.micStart) n.micStart();
    });
    (doc.body || doc.documentElement).appendChild(btn);
    return btn;
  }

  /**
   * The mic lives beside the box's pill, inside the pill's parent: a click on it then counts as "inside the search" for a site that
   * closes its search on an outside click, and tvnav files it under the same fixed bar (header) as the box.
   */
  function micHost(field) {
    var ups = [];
    for (var p = field.parentElement; p && ups.length < 4 && p !== doc.body; p = p.parentElement) ups.push(p);
    var n = pillDepth(rectOf(field), ups.map(rectOf), root.innerWidth);
    var pill = n ? ups[n - 1] : field;
    return { pill: pill, host: pill.parentElement && pill.parentElement !== doc.documentElement ? pill.parentElement : (doc.body || doc.documentElement) };
  }

  /** Absolute position inside the host so the mic scrolls with it (page, scroller or fixed header alike). */
  function placeAt(el, b) {
    var op = el.offsetParent;
    var s = el.style;
    s.position = 'absolute';
    if (!op || op === doc.body || op === doc.documentElement) {
      s.left = Math.round(b.left + root.pageXOffset) + 'px';
      s.top = Math.round(b.top + root.pageYOffset) + 'px';
      return;
    }
    var pr = op.getBoundingClientRect();
    s.left = Math.round(b.left - pr.left - op.clientLeft + op.scrollLeft) + 'px';
    s.top = Math.round(b.top - pr.top - op.clientTop + op.scrollTop) + 'px';
  }

  function micAvailable() {
    var now = Date.now();
    if (now - micCheckedAt > 5000) {
      micCheckedAt = now;
      var n = native();
      try { micOk = !!(n && n.micAvailable && n.micAvailable()); } catch (e) { micOk = false; }
    }
    return micOk;
  }

  /** Places the button beside the box, or hides it (no box on screen, a player, no recogniser). */
  function syncMic() {
    var f = visibleField();
    var show = !!f && micAvailable() && !(TV.isPlayerActive && TV.isPlayerActive(true));
    if (!show) {
      micInside = false;
      if (btn && btn.classList.contains('on')) { btn.classList.remove('on'); if (TV.currentEl() === btn) TV.releaseFocus(); }
      return;
    }
    var mh = micHost(f);
    var el = ensureButton();
    var pr = rectOf(mh.pill);
    var b = micBox(pr, root.innerWidth, 14, 12, false);
    // Another control already sits where the mic would go (a header button right of the pill): the mic goes inside the pill instead.
    var hit = el.classList.contains('on') ? null : doc.elementFromPoint(b.left + b.size / 2, b.top + b.size / 2);
    if (hit && hit !== el && !mh.host.contains(hit) && hit !== doc.body && hit !== doc.documentElement) b = micBox(pr, root.innerWidth, 14, 12, true);
    if (el.classList.contains('on') && micInside) b = micBox(pr, root.innerWidth, 14, 12, true);
    micInside = b.inside;
    if (el.parentElement !== mh.host) mh.host.appendChild(el);
    if (!el.classList.contains('on')) el.classList.add('on'); // laid out first: offsetParent needs it
    var s = el.style;
    s.width = b.size + 'px';
    s.height = b.size + 'px';
    placeAt(el, b);
  }

  // Not setInterval: some sites sweep and clear every interval id. A frame loop with a time check cannot be cancelled that way.
  (function loop() {
    var last = 0;
    root.requestAnimationFrame(function frame(ts) {
      if (ts - last >= TICK_MS) {
        last = ts;
        try { installStyle(); quiet(); syncMic(); } catch (e) { /* never end the loop */ }
      }
      root.requestAnimationFrame(frame);
    });
  })();
  doc.addEventListener('focusin', function (e) { if (isField(e.target)) quiet(); }, true);
  root.addEventListener('scroll', function () { try { syncMic(); } catch (e) { /* ignore */ } }, { capture: true, passive: true });

  // ---------- a spoken search ----------

  /** The hrefs / texts of the first few results: when it stops changing for a moment the live search has settled. */
  function signature(els) {
    return els.slice(0, 3).map(function (e) { return e.getAttribute('href') || (e.textContent || '').slice(0, 24); }).join('|');
  }

  /** Waits until the first results stopped changing, then puts the D-pad focus on the first one. */
  function focusFirstResult(field, started, last) {
    var c = cfg() || {};
    var f = rectOf(field);
    var cands = all(c.results || 'a[href]').filter(function (el) { return el.id !== MIC_ID && laidOut(el); });
    var i = firstResult(cands.map(rectOf), f, root.innerHeight);
    var now = Date.now();
    var sig = i >= 0 ? signature(cands) : '';
    var state = last && last.sig === sig ? last : { sig: sig, at: now };
    if (i >= 0 && now - state.at > 900 && now - started > 1500) {
      TV.setCurrent(cands[i], 'down');
      try { cands[i].scrollIntoView({ block: 'nearest' }); } catch (e) { /* ignore */ }
      return;
    }
    if (now - started > 10000) return;
    setTimeout(function () { focusFirstResult(field, started, state); }, 250);
  }

  TV.micResult = function (text) {
    var el = micField && micField.isConnected && laidOut(micField) ? micField : visibleField();
    text = String(text || '');
    if (!el || !text) return;
    active = el;
    setValue(el, text);
    el.dispatchEvent(new root.Event('change', { bubbles: true }));
    var c = cfg() || {};
    if (c.enter) pressEnter(el);
    focusFirstResult(el, Date.now(), null);
  };

  // ---------- the drawer's Search item ----------

  function focusAndOpen(el, h) {
    if (h && h.showHeaderOf && !laidOut(el)) h.showHeaderOf(el);
    setTimeout(function () { openKeyboard(el); }, 120);
  }

  function waitForField(h, tries) {
    var f = visibleField();
    if (f) { focusAndOpen(f, h); return; }
    if (tries > 0) setTimeout(function () { waitForField(h, tries - 1); }, 200);
  }

  /** helpers: { showHeaderOf(el), press(el), go(path) } from tvdrawer.js. */
  TV.searchFromDrawer = function (h) {
    var c = cfg();
    if (!c) return;
    var f = visibleField();
    if (f) { focusAndOpen(f, h); return; }
    var opener = all(c.open)[0];
    if (opener) {
      h.showHeaderOf(opener);
      setTimeout(function () { h.press(opener); waitForField(h, 20); }, 150);
      return;
    }
    if (c.route) {
      try { root.sessionStorage.setItem(KBD_KEY, '1'); } catch (e) { /* ignore */ }
      h.go(c.route);
      return;
    }
    waitForField(h, 10);
  };

  function pendingOpen() {
    var on = false;
    try { on = root.sessionStorage.getItem(KBD_KEY) === '1'; } catch (e) { return; }
    if (!on) return;
    try { root.sessionStorage.removeItem(KBD_KEY); } catch (e) { /* ignore */ }
    var tries = 0;
    (function look() {
      var f = visibleField();
      if (f) { focusAndOpen(f, null); return; }
      if (++tries < 40) setTimeout(look, 250);
    })();
  }

  var prevRoute = TV.hooks.route;
  TV.hooks.route = function () {
    if (prevRoute) prevRoute();
    active = null;
    pendingOpen();
  };
  if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', pendingOpen); else pendingOpen();

  TV.search = { field: visibleField };
})(typeof window !== 'undefined' ? window : {});
