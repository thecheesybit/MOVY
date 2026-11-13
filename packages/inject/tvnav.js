// Movy TV remote navigation, profile driven. Injected at document start in every frame of the tv flavor only.
// Concatenated after tvnav-pure.js (window.__movyTvPure) and before tvfs.js (idle, fullscreen policy, media, viewport).
// - Model: the page is a list of lines: carousels ("rows", from the per-source profile or auto-detected horizontal
//   scrollers) holding cards, plus free-standing focusables. LEFT/RIGHT walk inside a row and scroll it so the card is
//   fully visible; UP/DOWN go to the nearest card of the previous/next line keeping the column (Netflix behaviour).
// - Never focusable: profile `skip`, anything not rendered, closed <details>, aria-hidden / off-screen slider layers.
// - First focus on load and on route change: profile `firstFocus` (hero Play), else the first card, never the header.
// - Candidates are cached and rebuilt on a throttled MutationObserver, so a key press only measures rects.
// - Frames: one instance per frame; `__movyTvExit` leaves a frame, `__movyTvEnter` enters one from a direction.
(function (root) {
  'use strict';
  if (typeof window === 'undefined' || window.__movyTv) return;
  var P = root.__movyTvPure;
  if (!P) return;

  var doc = document;
  var inFrame = root.parent !== root;
  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,iframe,[role=button],[role=link],[role=tab],' +
    '[role=menuitem],[role=checkbox],[role=switch],[role=option],[role=slider],[contenteditable=""],[contenteditable=true],' +
    '[tabindex]:not([tabindex="-1"]),[data-movy-tv]';
  var SKIP_TAG = { SCRIPT: 1, STYLE: 1, LINK: 1, META: 1, HEAD: 1, TITLE: 1, NOSCRIPT: 1, BR: 1, HTML: 1, BODY: 1, SVG: 1, PATH: 1,
    script: 1, style: 1, link: 1, meta: 1, head: 1, title: 1, noscript: 1, br: 1, html: 1, body: 1, svg: 1, path: 1, g: 1, G: 1,
    defs: 1, DEFS: 1, use: 1, USE: 1, clippath: 1, CLIPPATH: 1, circle: 1, CIRCLE: 1, rect: 1, RECT: 1, polygon: 1, POLYGON: 1 };
  var TEXT_INPUT = /^(text|search|email|url|tel|password|number)$/i;
  var MAX_CANDS = 700;

  var TV = { PF: null, timings: [], hooks: {}, stats: { rebuilds: 0, lastBuildMs: 0 } };
  root.__movyTv = TV;

  // ---------- profile ----------

  function okSel(s) { try { doc.documentElement.matches(s); return true; } catch (e) { return false; } }
  function joinSel(arr) {
    var out = [];
    for (var i = 0; arr && i < arr.length; i++) if (typeof arr[i] === 'string' && okSel(arr[i])) out.push(arr[i]);
    return out.join(',');
  }

  /** Valid {card, target} selector pairs only. */
  function compileCardActivate(list) {
    var out = [];
    for (var i = 0; list && i < list.length; i++) {
      var e = list[i];
      if (e && typeof e.card === 'string' && typeof e.target === 'string' && okSel(e.card) && okSel(e.target)) out.push({ card: e.card, target: e.target });
    }
    return out;
  }

  /** The pair whose card contains `el`, if any. */
  function cardActivateFor(el) {
    var list = (TV.PF || compile(null)).cardActivate;
    for (var i = 0; i < list.length; i++) {
      try { if (el.matches(list[i].card) || el.closest(list[i].card)) return list[i]; } catch (e) { /* bad selector */ }
    }
    return null;
  }
  TV.cardActivateFor = cardActivateFor;

  /** After a card press opened a preview, presses the target inside it; falls back to the card's own link. */
  function pressCardTarget(el, pair) {
    var tries = 0;
    var link = el.tagName === 'A' ? el : el.closest('a[href]');
    var tick = function () {
      var t = null, all = [];
      try { all = doc.querySelectorAll(pair.target); } catch (e) { /* ignore */ }
      for (var i = 0; i < all.length; i++) if (renderableNow(all[i])) { t = all[i]; break; }
      if (t) { pressLike(t); return; }
      if (++tries < 25) { root.setTimeout(tick, 120); return; }
      // The preview never showed a usable target (the site renamed it or is slow): go to the card's own page.
      if (link && link.isConnected) {
        var prevHref = root.location.href;
        try { link.click(); } catch (e) { /* ignore */ }
        root.setTimeout(function () {
          if (root.location.href === prevHref && link.href && link.href.indexOf('javascript:') !== 0) {
            try { root.location.assign(link.href); } catch (e) { /* ignore */ }
          }
        }, 400);
      }
    };
    root.setTimeout(tick, 120);
  }

  /** Normalises the raw profile into joined selector strings (empty string = none). */
  function compile(raw) {
    raw = raw || {};
    var pl = raw.player || {}, sv = raw.servers || {}, ep = raw.episodes || {};
    return {
      raw: raw,
      header: joinSel(raw.header), bottomBar: joinSel(raw.bottomBar), rows: joinSel(raw.rows), cards: joinSel(raw.cards),
      focusables: joinSel(raw.focusables), skip: joinSel(raw.skip), firstFocus: raw.firstFocus || [], playButtons: joinSel(raw.playButtons),
      killOnTv: raw.killOnTv || [], viewportWidth: raw.viewportWidth || 1280,
      player: { frame: pl.frame || '', video: pl.video || '', root: pl.root || '', fullscreenTarget: pl.fullscreenTarget || '',
        fullscreenButton: joinSel(pl.fullscreenButton), controls: joinSel(pl.controls), overlayKill: joinSel(pl.overlayKill),
        menu: joinSel(pl.menu), hide: joinSel(pl.hide), never: joinSel(pl.never), seekBar: joinSel(pl.seekBar), subtitles: joinSel(pl.subtitles), depth: pl.depth || 0 },
      cardActivate: compileCardActivate(raw.cardActivate),
      servers: sv, episodes: ep
    };
  }

  function loadProfile() {
    var json = null;
    try {
      // The profile follows the TOP-level site: an embed frame asks with its ancestor's host.
      var ao = root.location.ancestorOrigins, top = inFrame && ao && ao.length ? ao[ao.length - 1] : root.location.origin;
      if (root.MovyTvNative && root.MovyTvNative.profile) json = root.MovyTvNative.profile(top);
    } catch (e) { /* none */ }
    var raw = null;
    try { raw = json ? JSON.parse(json) : null; } catch (e) { raw = null; }
    setProfile(raw);
    return !!raw;
  }

  function setProfile(raw) {
    TV.PF = compile(raw);
    cache.dirty = true;
    rowSetKey = '';
    var hs = TV.hooks.profile;
    if (hs) hs(TV.PF);
    scheduleRebuild(0);
  }

  // ---------- state ----------

  var current = null;
  var lastRect = null;
  var colX = null;
  var userMoved = false, bootAt = Date.now(), hold = { start: 0, last: 0 };
  var idCounter = 0;
  var ids = new WeakMap();
  var cache = { list: [], dirty: true, builtAt: 0, rowOf: new WeakMap(), modal: null };
  var rowSetKey = '';
  var rebuildTimer = 0;
  var lastRebuildCost = 30;
  var beforeModal = null;
  var fallbackFocus = false; // first focus landed on a card because the hero Play was not there yet

  function idOf(el) {
    var id = ids.get(el);
    if (!id) { id = ++idCounter; ids.set(el, id); }
    return id;
  }

  // ---------- candidates ----------

  function toRect(r) { return { l: r.left, t: r.top, r: r.right, b: r.bottom }; }
  function inView(r, mx, my) {
    return r.right > -mx && r.left < root.innerWidth + mx && r.bottom > -my && r.top < root.innerHeight + my;
  }

  function closedDetails(el) {
    var d = el.closest('details');
    while (d) {
      if (!d.open && !(el.tagName === 'SUMMARY' && el.parentElement === d)) return true;
      d = d.parentElement ? d.parentElement.closest('details') : null;
    }
    return false;
  }

  /** -1: fixed to the top (header), +1: fixed to the bottom, 0: scrolls with the page. Fixed bars must not interleave with page rows. */
  function fixOf(el, PF) {
    if (PF.player && PF.player.root && el.closest(PF.player.root)) return 0;
    if (PF.header && el.closest(PF.header)) return -1;
    if (PF.bottomBar && el.closest(PF.bottomBar)) return 1;
    var p = el, n = 0;
    while (p && p !== doc.body && n++ < 8) {
      var pos = root.getComputedStyle(p).position;
      if (pos === 'fixed' || pos === 'sticky') {
        var r = p.getBoundingClientRect();
        if (r.height >= root.innerHeight * 0.75 && r.width >= root.innerWidth * 0.75) return 0;
        return (r.top + r.bottom) / 2 < root.innerHeight / 2 ? -1 : 1;
      }
      p = p.parentElement;
    }
    return 0;
  }

  /** Profile `skip` matches the element itself (list descendants explicitly, e.g. "footer a"); a profile card always wins. */
  function isSkipped(el, PF) {
    if (PF.player && PF.player.never && (el.matches(PF.player.never) || el.closest(PF.player.never))) return true; // the element or anything inside one // text inputs, full-cover buttons, legacy hidden panels
    var aria = (el.getAttribute('aria-label') || '').toLowerCase();
    if (aria.indexOf('scroll ') === 0 || aria === 'scroll left' || aria === 'scroll right') return true;
    if (aria === 'edit list') return true;
    if (aria === 'favorite server') return true;
    if (aria.indexOf('unmute') !== -1 || aria.indexOf('mute') !== -1) return true;
    if (aria.indexOf('notifications') !== -1) return true;
    var href = (el.getAttribute('href') || '').toLowerCase();
    if (href.indexOf('discord') !== -1 || href.indexOf('telegram') !== -1 || href.indexOf('t.me') !== -1 || href.indexOf('twitter') !== -1) return true;
    if (el.className && typeof el.className === 'string') {
      if (el.className.indexOf('group/label') !== -1) return true;
      if (el.className.indexOf('rail-arrow') !== -1) return true;
      if (el.className.indexOf('pref-pill') !== -1) return true;
      if (el.className.indexOf('unified-episode-download') !== -1) return true;
      if (el.className.indexOf('domain-banner') !== -1) return true;
    }
    if (el.innerText && el.innerText.trim() === 'Ads') return true;
    if (!PF.skip || !el.matches(PF.skip)) return false;
    return !(PF.cards && el.matches(PF.cards));
  }

  /** A tabindex scroll container that merely holds focusable cards (Movy track, Moonflix region). */
  function isScrollHolder(el) {
    if (!el.hasAttribute('tabindex') && el.tagName !== 'DIV' && el.tagName !== 'SECTION' && el.tagName !== 'UL') return false;
    if (el.tagName === 'A' || el.tagName === 'BUTTON' || el.tagName === 'INPUT') return false;
    if (el.scrollWidth <= el.clientWidth + 12) return false;
    return !!el.querySelector('a[href],button,[role=button],[tabindex]:not([tabindex="-1"])');
  }

  function horizontalScroller(el) {
    var p = el.parentElement, n = 0;
    while (p && n++ < 10) {
      if (p.scrollWidth > p.clientWidth + 12) {
        var ox = root.getComputedStyle(p).overflowX;
        if (ox === 'auto' || ox === 'scroll') return p;
      }
      p = p.parentElement;
    }
    return null;
  }

  /** Full (slow) candidate scan; runs off the key path and is cached. */
  function buildCache() {
    var t0 = root.performance.now();
    var PF = TV.PF || compile(null);
    var out = [], seen = new Set(), i, el, r;
    var sel = SEL + (PF.focusables ? ',' + PF.focusables : '') + (PF.cards ? ',' + PF.cards : '');
    var vh = root.innerHeight;

    function addCandidate(el, native, card, fix) {
      if (!el || seen.has(el) || isSkipped(el, PF)) return;
      var tag = el.tagName.toUpperCase();
      if (tag !== 'SVG' && (SKIP_TAG[tag] || SKIP_TAG[el.tagName])) return;
      if (el.ownerSVGElement) return;
      if (el.tagName === 'IFRAME' && !isPlayerFrame(el, PF)) return;
      var r = el.getBoundingClientRect();
      if (!inView(r, 3000, vh * 3)) return;
      if (r.width < 14 || r.height < 14) return;
      seen.add(el);
      out.push({ el: el, native: native, card: card, fix: fix !== undefined ? fix : fixOf(el, PF) });
    }

    // 1. High-priority layer budgets: player root, explicit focusables, active modal.
    // They are collected first so large catalogs never starve player controls or modals.
    if (PF.player && PF.player.root) {
      try {
        var pRoot = doc.querySelector(PF.player.root);
        if (pRoot) {
          var pnodes = pRoot.querySelectorAll(sel + ',svg.cursor-pointer');
          for (i = 0; i < pnodes.length; i++) addCandidate(pnodes[i], true, false, 0);
        }
      } catch (e) {}
    }
    if (PF.focusables) {
      try {
        var fnodes = doc.querySelectorAll(PF.focusables);
        for (i = 0; i < fnodes.length; i++) addCandidate(fnodes[i], true, false);
      } catch (e) {}
    }
    var modal = openPlayerMenu(PF) || cache.modal;
    if (modal) {
      try {
        var mnodes = modal.querySelectorAll(sel);
        for (i = 0; i < mnodes.length; i++) addCandidate(mnodes[i], true, false);
      } catch (e) {}
    }

    // 2. Main document candidates up to budget
    var nodes = doc.querySelectorAll(sel);
    for (i = 0; i < nodes.length && out.length < MAX_CANDS; i++) {
      el = nodes[i];
      addCandidate(el, true, !!(PF.cards && el.matches(PF.cards)));
    }
    // Clickable "cards": elements the page made clickable with cursor:pointer (outermost of each chain).
    var all = doc.body ? doc.body.getElementsByTagName('*') : [];
    var checked = 0;
    for (i = 0; i < all.length && checked < 2600 && out.length < MAX_CANDS; i++) {
      el = all[i];
      var tag = el.tagName.toUpperCase();
      if (SKIP_TAG[tag] || SKIP_TAG[el.tagName] || el.ownerSVGElement || seen.has(el)) continue;
      checked++;
      r = el.getBoundingClientRect();
      if (r.width < 24 || r.height < 24 || !inView(r, 400, vh * 2)) continue;
      var cs = root.getComputedStyle(el);
      if (cs.cursor !== 'pointer' || cs.visibility === 'hidden' || cs.pointerEvents === 'none') continue;
      var p = el.parentElement;
      if (p && !seen.has(p) && root.getComputedStyle(p).cursor === 'pointer' && !p.matches(SEL)) continue;
      var near = el.closest(SEL);
      if (near && near !== el) continue;
      if (el.closest('[aria-hidden=true]') || isSkipped(el, PF)) continue;
      seen.add(el);
      out.push({ el: el, native: false, card: false, fix: fixOf(el, PF) });
    }
    // Drop heuristic wrappers that contain other candidates, and anything inside a profile card or a holder.
    var res = [];
    for (i = 0; i < out.length; i++) {
      var c = out[i], drop = false;
      if (!c.native) {
        for (var j = 0; j < out.length && !drop; j++) if (j !== i && c.el.contains(out[j].el)) drop = true;
      } else if (!c.card && PF.cards) {
        var host = c.el.parentElement && c.el.parentElement.closest(PF.cards);
        if (host) drop = true;
      }
      if (!drop && c.native && c.el.tagName !== 'IFRAME' && isScrollHolder(c.el)) drop = true;
      if (!drop && c.native && /^(DIV|SECTION|MAIN|ARTICLE|UL)$/.test(c.el.tagName) && !c.card) {
        var br = c.el.getBoundingClientRect(); // a page-sized wrapper with a tabindex is not a control
        if (br.width > root.innerWidth * 0.9 && br.height > root.innerHeight * 0.9) drop = true;
      }
      if (!drop) res.push(c);
    }
    assignRows(res, PF);
    cache.list = res;
    cache.dirty = false;
    cache.builtAt = root.performance.now();
    lastRebuildCost = cache.builtAt - t0;
    TV.stats.rebuilds++;
    TV.stats.lastBuildMs = Math.round(lastRebuildCost);
    detectModal(PF);
    repairFocus();
    if (!inFrame && !TV.focusHeld && !userMoved && ((!current && !activeEl()) || fallbackFocus)) autoFirstFocus(); // content arrived after the first attempts
  }

  /** The page re-rendered the focused element (a rotating hero, a React key change): keep the highlight alive. */
  function repairFocus() {
    if (inFrame || !current || current.isConnected || activeEl()) return;
    var was = lastRect;
    current = null;
    if (!userMoved || !was) { focusFirst(); return; }
    var items = candItems(null), best = null, bd = Infinity;
    var cx = (was.l + was.r) / 2, cy = (was.t + was.b) / 2;
    for (var i = 0; i < items.length; i++) {
      if (items[i].fix) continue;
      var r = items[i].rect, d = Math.abs((r.l + r.r) / 2 - cx) + Math.abs((r.t + r.b) / 2 - cy);
      if (d < bd) { bd = d; best = items[i]; }
    }
    if (best) setCurrent(best.el, null);
  }

  function assignRows(list, PF) {
    var rowEls = new Map(), n = 0, i;
    if (PF.rows) {
      var rs = doc.querySelectorAll(PF.rows);
      for (i = 0; i < rs.length; i++) rowEls.set(rs[i], 'p' + (n++));
    }
    var scrollers = new Map();
    for (i = 0; i < list.length; i++) {
      var el = list[i].el, rid = null, p = el, depth = 0;
      while (p && depth++ < 14) {
        if (rowEls.has(p) && p !== el) { rid = rowEls.get(p); break; }
        p = p.parentElement;
      }
      if (rid == null) {
        var sc = horizontalScroller(el);
        if (sc) {
          if (!scrollers.has(sc)) scrollers.set(sc, { id: 'a' + (n++), count: 0 });
          var s = scrollers.get(sc);
          s.count++;
          list[i].auto = s;
        }
      }
      list[i].row = rid;
    }
    for (i = 0; i < list.length; i++) if (list[i].row == null && list[i].auto && list[i].auto.count >= 3) list[i].row = list[i].auto.id;
  }

  function scheduleRebuild(delay) {
    if (rebuildTimer) return;
    rebuildTimer = setTimeout(function () {
      rebuildTimer = 0;
      if (doc.hidden) { cache.dirty = true; return; }
      var run = function () { try { buildCache(); } catch (e) { cache.dirty = true; } }; if (root.requestIdleCallback) root.requestIdleCallback(run, { timeout: 500 }); else run();
    }, delay != null ? delay : P.rebuildDelay(lastRebuildCost, Date.now() - bootAt, userMoved));
  }

  function ensureCache() {
    if (cache.dirty && (!cache.list.length || root.performance.now() - cache.builtAt > 3000)) buildCache();
  }

  /** Cheap per-candidate validation on the key path (the cache may be stale). */
  function usable(el, rect) {
    if (!el.isConnected) return false;
    var w = rect.r - rect.l, h = rect.b - rect.t;
    if (w < 14 || h < 14) return false;
    var cs = root.getComputedStyle(el);
    return P.renderable({
      w: w, h: h, display: cs.display, visibility: cs.visibility, pointerEvents: cs.pointerEvents, opacity: parseFloat(cs.opacity),
      disabled: !!el.disabled, hiddenAncestor: !!el.closest('[aria-hidden=true],[hidden],[inert]'), closedDetails: closedDetails(el)
    }, 14);
  }

  // ---------- modal layers ----------

  /** A player popover (quality, subtitles, servers...) named by the profile's player.menu: visible means open. */
  function openPlayerMenu(PF) {
    if (!PF.player.menu) return null;
    var list = doc.querySelectorAll(PF.player.menu);
    for (var i = 0; i < list.length; i++) {
      var r = list[i].getBoundingClientRect();
      if (r.width < 40 || r.height < 40 || r.bottom <= 0 || r.top >= root.innerHeight) continue;
      var shown = true;
      for (var p = list[i], n = 0; p && p !== doc.documentElement && n++ < 8; p = p.parentElement) {
        var cs = root.getComputedStyle(p);
        // closed popovers stay in the DOM under a transparent wrapper; pointer-events is inherited, so only the menu itself counts
        if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) < 0.1 || (p === list[i] && cs.pointerEvents === 'none')) { shown = false; break; }
      }
      if (!shown) continue;
      return list[i];
    }
    return null;
  }

  function detectModal(PF) {
    var vw = root.innerWidth, vh = root.innerHeight, m = openPlayerMenu(PF);
    TV.menuEl = m;
    // An open player menu is owned by tvmenu.js (focus, steps, BACK, return focus): it is not a "modal" here, or this code
    // would also pull focus into it on open and push focus back to a stale element on close.
    var t = m ? null : doc.elementFromPoint(vw / 2, vh / 2);
    m = null;
    var p = t, n = 0;
    while (p && p !== doc.body && p !== doc.documentElement && n++ < 12) {
      var cs = root.getComputedStyle(p);
      if (cs.position === 'fixed') {
        var r = p.getBoundingClientRect();
        var z = parseInt(cs.zIndex, 10);
        if (r.width * r.height >= vw * vh * 0.5 && z >= 50 && !(p.tagName === 'IFRAME') && !(PF.header && p.matches(PF.header))
            && !p.querySelector('video') && p.querySelector('a[href],button,input,[role=button]')) m = p;
      }
      p = p.parentElement;
    }
    if (m !== cache.modal) {
      var was = cache.modal;
      cache.modal = m;
      if (m && !was) { beforeModal = current; setTimeout(function () { if (cache.modal === m) focusFirst(m); }, 120); setTimeout(function () { if (cache.modal === m) TV.hooks.modalOpened && TV.hooks.modalOpened(m); }, 0); }
      else if (!m && was && beforeModal && beforeModal.isConnected) { var b = beforeModal; beforeModal = null; setCurrent(b, null); }
    }
  }

  // ---------- focus ----------

  function activeEl() {
    var a = doc.activeElement;
    if (!a || a === doc.body || a === doc.documentElement || !a.isConnected) return null;
    return a;
  }

  function fire(el, type) {
    try { el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: root })); } catch (e) { /* ignore */ }
  }

  function firePointerMouse(el, type) {
    try { el.dispatchEvent(new PointerEvent('pointer' + type, { bubbles: true, cancelable: true, view: root, pointerType: 'mouse' })); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mouse' + type, { bubbles: true, cancelable: true, view: root })); } catch (e) {}
  }

  function clearRing(el) {
    if (el && el.classList) { el.classList.remove('movy-tvf'); el.classList.remove('movy-tvr'); el.classList.remove('movy-tvc'); }
  }

  function setCurrent(el, dir) {
    if (current && current !== el) { clearRing(current); fire(current, 'mouseout'); }
    current = el;
    if (TV.hooks.focused) TV.hooks.focused(); // tvdrawer.js: re-checks whether LEFT would leave the page
    if (!el) return;
    if (!TV.firstFocusDone) {
      TV.firstFocusDone = true;
      if (TV.signalReady) TV.signalReady();
    }
    el.classList.add('movy-tvf');
    try {
      var rad = root.getComputedStyle(el).borderTopLeftRadius;
      var r = el.getBoundingClientRect();
      if ((rad === '0px' || !rad) && r.height > 36) el.classList.add('movy-tvr');
      if ((r.width <= 48 && r.height <= 48) || (el.className && typeof el.className === 'string' && el.className.indexOf('rounded-full') !== -1)) el.classList.add('movy-tvc');
    } catch (e) { /* ignore */ }
    if (!el.matches('a,button,input,select,textarea,summary,iframe,[tabindex],[contenteditable]')) el.setAttribute('tabindex', '-1');
    try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
    if (isPlayerActive()) {
      fire(el, 'mouseover');
      fire(el, 'mousemove');
    }
    var r = el.getBoundingClientRect();
    lastRect = toRect(r);
    var dx = reveal(el, r);
    colX = P.nextColumn(colX, lastRect, dir);
    if ((dir === 'left' || dir === 'right') && dx) colX -= dx; // the card slides while its row scrolls
  }

  function topInset() {
    var PF = TV.PF, inset = 0;
    if (!PF || !PF.header) return 0;
    var hs = doc.querySelectorAll(PF.header);
    for (var i = 0; i < hs.length && i < 6; i++) {
      var r = hs[i].getBoundingClientRect();
      if (r.height > 0 && r.top <= 2 && r.bottom < root.innerHeight * 0.4) inset = Math.max(inset, r.bottom);
    }
    return inset;
  }

  /** Scrolls the row (horizontally) and the page (vertically) so the focused card is fully visible with a peek. */
  /** Nearest ancestor that scrolls vertically on its own (a popover list, a comments box), not the page. */
  function verticalScroller(el) {
    for (var p = el.parentElement, n = 0; p && p !== doc.body && p !== doc.documentElement && n++ < 12; p = p.parentElement) {
      if (p.scrollHeight <= p.clientHeight + 4) continue;
      var oy = root.getComputedStyle(p).overflowY;
      if (oy === 'auto' || oy === 'scroll') return p;
    }
    return null;
  }

  function reveal(el, r) {
    var vw = root.innerWidth, vh = root.innerHeight, shiftX = 0;
    var vs = verticalScroller(el);
    if (vs) {
      var vr = vs.getBoundingClientRect(), m = Math.min(16, vr.height / 6), d2 = 0;
      if (r.top < vr.top + m) d2 = r.top - vr.top - m;
      else if (r.bottom > vr.bottom - m) d2 = r.bottom - vr.bottom + m;
      if (d2) { vs.scrollTop += d2; r = el.getBoundingClientRect(); }
    }
    var sc = horizontalScroller(el);
    if (sc) {
      var sr = sc.getBoundingClientRect();
      var d = P.rowScrollDelta({ l: r.left, r: r.right }, { l: Math.max(sr.left, 0), r: Math.min(sr.right, vw) }, Math.min(56, r.width * 0.25));
      if (d) { shiftX = d; try { sc.scrollBy({ left: d, behavior: 'smooth' }); } catch (e) { sc.scrollLeft += d; } }
    } else if (r.left < 0 || r.right > vw) {
      shiftX = r.left < 0 ? r.left - 24 : r.right - vw + 24;
      try { root.scrollBy({ left: shiftX, behavior: 'smooth' }); } catch (e) { /* ignore */ }
    }
    var inHeader = !!(TV.PF && TV.PF.header && el.closest(TV.PF.header));
    var top = inHeader ? 0 : Math.max(Math.min(90, vh * 0.12), topInset() + 14), bottom = vh - Math.min(90, vh * 0.12);
    var dy = 0;
    if (r.height > bottom - top) dy = r.top - top;
    else if (r.top < top) dy = r.top - (vh - r.height) / 2;
    else if (r.bottom > bottom) dy = r.bottom - (vh + r.height) / 2;
    if (Math.abs(dy) > 2) { try { root.scrollBy({ top: dy, behavior: 'smooth' }); } catch (e) { root.scrollBy(0, dy); } }
    return shiftX;
  }

  function currentRect() {
    var a = activeEl() || (current && current.isConnected ? current : null);
    if (a) {
      var r = a.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) { lastRect = toRect(r); return lastRect; }
    }
    return lastRect;
  }

  function firstFocusEl(scope) {
    var PF = TV.PF || compile(null), i, list, el;
    var sels = PF.firstFocus;
    for (i = 0; i < sels.length; i++) {
      if (!okSel(sels[i])) continue;
      list = (scope || doc).querySelectorAll(sels[i]);
      for (var j = 0; j < list.length; j++) {
        el = list[j];
        var r = el.getBoundingClientRect();
        if (r.width >= 14 && r.height >= 14 && usable(el, toRect(r)) && !isSkipped(el, PF)) return el;
      }
    }
    return null;
  }

  /** First focus: profile firstFocus, else the first card of the first row, else the first non-header candidate. */
  function focusFirst(scope, allowHeader) {
    ensureCache();
    var PF = TV.PF || compile(null);
    var el = firstFocusEl(scope);
    var viaProfile = !!el;
    if (!el && scope && PF.playButtons) { // a modal: its Play button first
      var pbs = scope.querySelectorAll(PF.playButtons);
      for (var q = 0; q < pbs.length && !el; q++) if (renderableNow(pbs[q])) el = pbs[q];
    }
    if (!el) {
      var items = candItems(scope);
      var isBar = function (c) { return c.fix || (PF.header && c.el.closest(PF.header)); };
      var inRow = items.filter(function (c) { return c.row != null && !isBar(c); });
      var pool = inRow.length ? inRow : items.filter(function (c) { return !isBar(c); });
      TV.ff = { items: items.length, inRow: inRow.length, pool: pool.length, hdr: PF.header, ff: PF.firstFocus.length };
      if (!pool.length) { if (!allowHeader) return false; pool = items; } // auto first focus waits for content rather than landing on the header
      var best = P.pickInitial(pool, root.innerWidth, root.innerHeight) || (allowHeader ? pool[0] : null);
      el = best && best.el;
      if (!el && !scope) { var bf = bigIframe(); if (bf) el = bf; }
    }
    if (!el) return false;
    colX = null;
    fallbackFocus = !viaProfile && !scope;
    setCurrent(el, null);
    return true;
  }

  var FIX_SHIFT = 1e6;
  function shiftFixed(rect, fix) {
    if (!fix) return rect;
    var d = fix * FIX_SHIFT;
    return { l: rect.l, t: rect.t + d, r: rect.r, b: rect.b + d };
  }

  /** Cached candidates with fresh rects, near the viewport, restricted to the modal layer when one is up. */
  function candItems(scope) {
    var out = [], list = cache.list, modal = scope || cache.modal;
    for (var i = 0; i < list.length; i++) {
      var c = list[i], el = c.el;
      if (!el.isConnected) { cache.dirty = true; continue; }
      if (modal && !modal.contains(el)) continue;
      var r = el.getBoundingClientRect();
      if (r.width < 14 || r.height < 14 || !inView(r, 500, root.innerHeight * 1.6)) continue;
      out.push({ id: idOf(el), el: el, rect: shiftFixed(toRect(r), c.fix), row: c.row, native: c.native, fix: c.fix });
    }
    return out;
  }

  /** What move(dir) would do: { first: true } (no usable focus yet) or { from, best } (best is null at an edge). */
  function movePick(dir) {
    ensureCache();
    var cur = activeEl() || (current && current.isConnected && current.classList.contains('movy-tvf') ? current : null);
    var from = currentRect();
    if (!cur || !from || !usable(cur, from)) return { first: true };
    var items = candItems(null);
    var curId = idOf(cur);
    var hasCur = false, i;
    for (i = 0; i < items.length; i++) if (items[i].id === curId) { hasCur = true; break; }
    var curFix = fixOf(cur, TV.PF || compile(null));
    if (!hasCur) items.push({ id: curId, el: cur, rect: shiftFixed(from, curFix), row: rowOfEl(cur), native: true, fix: curFix });
    from = shiftFixed(from, curFix);
    var best = null, tries = 0;
    while (tries++ < 6) {
      var lines = P.buildLines(items);
      var vert = dir === 'up' || dir === 'down';
      best = vert ? P.pickVertical(lines, curId, dir, colX == null ? (from.l + from.r) / 2 : colX, root.innerWidth)
        : P.pickHorizontal(lines, curId, dir, from, items);
      if (!best) break;
      if (usable(best.el, best.rect)) break;
      cache.dirty = true;
      items = items.filter(function (c) { return c !== best; });
      best = null;
    }
    return { from: from, best: best && best.el !== cur ? best : null };
  }

  function move(dir) {
    var r = movePick(dir);
    if (r.first) return focusFirst(null, true);
    if (!r.best) return false;
    setCurrent(r.best.el, dir);
    return true;
  }

  /** True when LEFT has nowhere to go (no candidate to the left, or one at the same x). tvdrawer.js reports it to the app. */
  TV.leftBlocked = function () {
    var a = activeEl();
    if (a && isTextField(a)) return false;
    var r = movePick('left');
    return !r.first && (!r.best || r.best.rect.l >= r.from.l - 4);
  };

  function rowOfEl(el) {
    for (var i = 0; i < cache.list.length; i++) if (cache.list[i].el === el) return cache.list[i].row;
    return null;
  }

  function scrollPage(dir) {
    var dy = dir === 'down' ? 1 : dir === 'up' ? -1 : 0;
    var dx = dir === 'right' ? 1 : dir === 'left' ? -1 : 0;
    root.scrollBy({ top: dy * root.innerHeight * 0.6, left: dx * root.innerWidth * 0.6, behavior: 'smooth' });
  }

  /** A player iframe covering most of the page (cross-origin players live in these). */
  function bigIframe() {
    var frames = doc.getElementsByTagName('iframe');
    var best = null, bestArea = 0;
    for (var i = 0; i < frames.length; i++) {
      var r = frames[i].getBoundingClientRect();
      var area = r.width * r.height;
      if (area > root.innerWidth * root.innerHeight * 0.5 && area > bestArea) { best = frames[i]; bestArea = area; }
    }
    return best;
  }

  function isPlayerFrame(el, PF) {
    if (PF.player.frame && el.matches(PF.player.frame)) return true;
    if (PF.player.frame && el.closest(PF.player.frame)) return true;
    var r = el.getBoundingClientRect();
    return r.width * r.height > root.innerWidth * root.innerHeight * 0.5;
  }

  function isPlayerActive(strict) {
    if (doc.fullscreenElement || doc.webkitFullscreenElement) return true;
    var vp = doc.querySelector('#vp-shell');
    if (vp && root.getComputedStyle(vp).display !== 'none' && vp.getBoundingClientRect().height > 200) return true;
    var cj = doc.querySelector('.player-root');
    if (cj && root.getComputedStyle(cj).display !== 'none' && cj.getBoundingClientRect().height > 200) return true;
    if (root.location.pathname.indexOf('/watch') >= 0 || doc.querySelector('.unified-player-frame')) return true;
    var mf = doc.querySelector('iframe[title="Moonflix player"], iframe[src*="player.moonflix"]');
    if (mf && root.getComputedStyle(mf).display !== 'none' && mf.getBoundingClientRect().height > 200) return true;
    if (strict) return false; // strict: ignore bare autoplaying hero/trailer videos
    var v = doc.querySelector('video');
    if (v && !v.paused && v.getBoundingClientRect().width > root.innerWidth * 0.5) return true;
    return false;
  }
  TV.isPlayerActive = isPlayerActive;

  // ---------- keys ----------

  function dirOf(e) {
    switch (e.keyCode) {
      case 37: return 'left';
      case 38: return 'up';
      case 39: return 'right';
      case 40: return 'down';
      default: return null;
    }
  }

  function isTextField(el) {
    if (!el || el === doc.body) return false;
    var t = el.tagName;
    if (t === 'TEXTAREA') return true;
    if (t === 'INPUT') return TEXT_INPUT.test(el.type || 'text');
    return !!el.isContentEditable;
  }

  function caretAtEdge(el, dir) {
    if (!/^(INPUT|TEXTAREA)$/.test(el.tagName)) return true;
    try {
      var s = el.selectionStart, e = el.selectionEnd, n = (el.value || '').length;
      if (s == null) return true;
      return dir === 'left' ? (s === 0 && e === 0) : (s === n && e === n);
    } catch (x) { return true; }
  }

  function renderableNow(el) {
    if (!el.isConnected) return false;
    var r = el.getBoundingClientRect();
    return usable(el, toRect(r));
  }

  function activate(el) {
    if (!renderableNow(el)) { var f = firstFocusEl(); if (f) { setCurrent(f, null); el = f; } } // stale hero slide
    if (TV.hooks.beforeActivate) TV.hooks.beforeActivate(el);
    if (el.tagName === 'IFRAME') {
      try { el.focus(); el.contentWindow.focus(); } catch (e) { /* cross-origin focus is fine */ }
      enterFrame(el, null);
      return true;
    }
    if (isTextField(el)) {
      el.focus();
      firePointerMouse(el, 'down');
      firePointerMouse(el, 'up');
      fire(el, 'click');
      return false;
    }
    var type = (el.type || '').toLowerCase();
    if (el.tagName === 'SELECT' || el.tagName === 'TEXTAREA') return false;
    if (el.tagName === 'INPUT' && type === 'range') return false;

    // Dispatch full pointer and mouse sequence for Svelte 5 / React
    firePointerMouse(el, 'down');
    firePointerMouse(el, 'up');
    if (typeof el.click === 'function') {
      el.click();
    } else {
      fire(el, 'click');
    }

    var pair = cardActivateFor(el);
    if (pair) { pressCardTarget(el, pair); if (TV.hooks.afterActivate) TV.hooks.afterActivate(el); return true; }

    // Anchor link fallback for router links
    var link = el.tagName === 'A' ? el : el.closest('a[href]');
    if (link && link.href && link.href.indexOf('javascript:') !== 0) {
      var prevHref = root.location.href;
      setTimeout(function () {
        if (root.location.href === prevHref && link.isConnected && !root.location.hash) {
          try { link.click(); } catch (e) {}
        }
      }, 100);
    }

    if (TV.hooks.afterActivate) TV.hooks.afterActivate(el);
    return true;
  }

  /** Pointer sequence + click that also works on SVG elements (they have no click() method). */
  function pressLike(el) {
    firePointerMouse(el, 'down');
    firePointerMouse(el, 'up');
    if (typeof el.click === 'function') el.click(); else fire(el, 'click');
  }

  function enterFrame(el, dir) {
    try { el.contentWindow.postMessage({ __movyTvEnter: dir || 'down' }, '*'); } catch (e) { /* ignore */ }
  }

  function exitUp(dir) {
    try { root.parent.postMessage({ __movyTvExit: dir }, '*'); } catch (x) { /* ignore */ }
  }

  function record(e, t0, label) {
    var now = root.performance.now();
    var sync = now - t0;
    var lat = e && e.timeStamp ? t0 - e.timeStamp : 0;
    root.requestAnimationFrame(function () {
      root.requestAnimationFrame(function () {
        var rec = { dir: label, sync: Math.round(sync * 10) / 10, lat: Math.round(lat * 10) / 10, k2p: Math.round((root.performance.now() - (e && e.timeStamp ? e.timeStamp : t0)) * 10) / 10 };
        TV.timings.push(rec);
        if (TV.timings.length > 80) TV.timings.shift();
      });
    });
  }

  function onKey(e) {
    if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey) return;
    var t0 = root.performance.now();
    var dir = dirOf(e);
    var a = activeEl();

    if (TV.hooks.key && TV.hooks.key(e, dir, a)) return;

    if (isPlayerActive()) {
      var pRoot = doc.querySelector('.player-root, #vp-shell, .unified-player-frame');
      if (pRoot) firePointerMouse(pRoot, 'move');
      var h = doc.documentElement;
      if (h && h.classList.contains('movy-pctl-off')) h.classList.remove('movy-pctl-off');
    }

    if (dir) {
      if (a && isTextField(a) && (dir === 'left' || dir === 'right') && !caretAtEdge(a, dir)) return; // caret moves
      if (a && a.tagName === 'SELECT') return;

      e.preventDefault();
      e.stopPropagation();
      if (!P.holdGate(hold, e.repeat, Date.now())) return; // held key: paced moves
      userMoved = true;
      fallbackFocus = false;
      if (dir === 'up' || dir === 'down') { if (colX == null && lastRect) colX = (lastRect.l + lastRect.r) / 2; }
      var prevEl = a || current;
      var prevRect = dir === 'left' ? currentRect() : null;
      var moved = move(dir);
      if (moved && prevRect) {
        // A "move" that gains no ground to the left (sibling at the same x) means we are at the edge: hand over to the drawer.
        var nowRect = currentRect();
        if (nowRect && nowRect.l >= prevRect.l - 4) {
          if (prevEl && prevEl.isConnected && prevEl !== current) setCurrent(prevEl, null);
          moved = false;
        }
      }
      record(e, t0, dir + (moved ? '' : '!'));
      if (!moved) {
        if (inFrame) exitUp(dir);
        else if (dir === 'up' || dir === 'down') scrollPage(dir);
        else if (dir === 'left' && !e.repeat && !inFrame && !doc.fullscreenElement && !isPlayerActive(true) && !cache.modal) {
          if (TV.edgeLeft) TV.edgeLeft(); // the native drawer takes over (tvdrawer.js)
        }
      } else if (current && current.tagName === 'IFRAME' && TV.PF && isPlayerFrame(current, TV.PF)) {
        enterFrame(current, dir);
      }
      return;
    }

    if (e.keyCode === 13 || e.keyCode === 23) {
      if (a && a !== doc.body && a.tagName !== 'VIDEO') {
        if (e.repeat) { e.preventDefault(); return; }
        if (activate(a)) { e.preventDefault(); e.stopPropagation(); return; }
      }
      if (isPlayerActive()) {
        var activeVideo = doc.querySelector('video');
        if (activeVideo) {
          e.preventDefault(); e.stopPropagation();
          if (activeVideo.paused) activeVideo.play(); else activeVideo.pause();
          return;
        }
        var bigFrame = bigIframe() || doc.querySelector('iframe[title="Moonflix player"], .unified-player-frame iframe');
        if (bigFrame) {
          e.preventDefault(); e.stopPropagation();
          try {
            var bRect = bigFrame.getBoundingClientRect();
            var bTarget = doc.elementFromPoint(bRect.left + bRect.width / 2, bRect.top + bRect.height / 2);
            if (bTarget) { firePointerMouse(bTarget, 'down'); firePointerMouse(bTarget, 'up'); bTarget.click(); }
          } catch (err) {}
          return;
        }
      }
      var big = bigIframe();
      if (big) { e.preventDefault(); e.stopPropagation(); setCurrent(big, null); activate(big); return; }
      if (!e.repeat && !inFrame) {
        var ff = firstFocusEl();
        if (ff) { e.preventDefault(); e.stopPropagation(); setCurrent(ff, null); activate(ff); return; }
      }
      if (!e.repeat && inFrame) {
        var t = doc.elementFromPoint(root.innerWidth / 2, root.innerHeight / 2);
        if (t) { e.preventDefault(); t.click(); }
      }
    }
  }

  root.addEventListener('keydown', onKey, true);

  // ---------- frames ----------

  root.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || typeof d !== 'object') return;
    if (d.__movyTvEnter && e.source === root.parent) {
      root.focus();
      ensureCache();
      var items = candItems(null);
      var best = P.pickEntry(items, d.__movyTvEnter);
      if (best) { colX = null; setCurrent(best.el, null); }
      return;
    }
    if (d.__movyTvExit) {
      var frames = doc.getElementsByTagName('iframe');
      for (var i = 0; i < frames.length; i++) {
        if (frames[i].contentWindow !== e.source) continue;
        root.focus();
        frames[i].blur();
        var r = toRect(frames[i].getBoundingClientRect());
        var items2 = candItems(null).filter(function (c) { return c.el !== frames[i]; });
        var best2 = P.pick(r, items2, d.__movyTvExit);
        if (best2) setCurrent(best2.el, d.__movyTvExit); else setCurrent(frames[i], null);
        return;
      }
    }
    if (d.__movyTvProfile && e.source === root.parent) { setProfile(d.__movyTvProfile); relayDown(d); }
  });

  function relayDown(msg) {
    var frames = doc.getElementsByTagName('iframe');
    for (var i = 0; i < frames.length; i++) {
      try { frames[i].contentWindow.postMessage(msg, '*'); } catch (e) { /* ignore */ }
    }
  }

  // ---------- lifecycle ----------

  var lastHref = '';
  var firstFocusTries = 0;

  function autoFirstFocus() {
    if (inFrame || TV.focusHeld || cache.modal || (userMoved && (current || activeEl()))) return; // focusHeld: the drawer owns the focus
    var a = activeEl();
    if (a && isTextField(a)) return; // text field owns the focus
    if (a && a !== current && usable(a, toRect(a.getBoundingClientRect()))) {
      colX = null;
      fallbackFocus = false;
      setCurrent(a, null);
      return;
    }
    var ff = firstFocusEl();
    if (ff && ff !== current) { colX = null; fallbackFocus = false; setCurrent(ff, null); return; } // the hero Play showed up after a fallback focus
    if (a) return;
    if (focusFirst()) return;
    if (++firstFocusTries < 40) setTimeout(autoFirstFocus, 600);
  }

  function onRoute() {
    lastHref = root.location.href;
    userMoved = false;
    firstFocusTries = 0;
    clearRing(current);
    current = null;
    colX = null;
    cache.dirty = true;
    scheduleRebuild(250);
    [350, 900, 1800, 3200].forEach(function (ms) { setTimeout(autoFirstFocus, ms); });
    if (!inFrame && TV.hooks.route) TV.hooks.route();
  }

  function watch() {
    if (!doc.documentElement) { setTimeout(watch, 20); return; }
    var mo = new MutationObserver(function (recs) { if (P.mutationsRelevant(recs)) { cache.dirty = true; scheduleRebuild(); } });
    mo.observe(doc.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['open', 'hidden', 'aria-hidden', 'tabindex'] });
    lastHref = root.location.href;
    if (!inFrame) {
      setInterval(function () { if (root.location.href !== lastHref) onRoute(); }, 600);
      ['pushState', 'replaceState'].forEach(function (k) {
        var orig = root.history[k];
        root.history[k] = function () { var r = orig.apply(this, arguments); setTimeout(function () { if (root.location.href !== lastHref) onRoute(); }, 0); return r; };
      });
    }
    root.addEventListener('scroll', function () { cache.dirty = true; scheduleRebuild(450); }, { capture: true, passive: true });
    doc.addEventListener('DOMContentLoaded', function () { scheduleRebuild(100); setTimeout(autoFirstFocus, 400); });
    root.addEventListener('load', function () { if (TV.hooks.loaded) TV.hooks.loaded(); scheduleRebuild(100); setTimeout(autoFirstFocus, 300); [900, 2000, 3500].forEach(function (ms) { setTimeout(autoFirstFocus, ms); }); });
  }

  // The ring follows real focus: it is removed as soon as the element loses it (fullscreen changes, re-renders).
  doc.addEventListener('focusout', function (e) { clearRing(e.target); }, true);

  function installStyle() {
    if (doc.getElementById('__movy_tv_style')) return;
    var st = doc.createElement('style');
    st.id = '__movy_tv_style';
    st.textContent =
      ':focus,:focus-visible{outline:none!important;box-shadow:none!important;' +
      '--tw-ring-color:transparent!important;--tw-ring-shadow:none!important;--tw-ring-offset-shadow:none!important}' +
      '.movy-tvf,.movy-tvf:focus,.movy-tvf:focus-visible{' +
      'outline:2px solid rgba(255,255,255,0.95)!important;outline-offset:2px!important;' +
      'box-shadow:0 4px 20px rgba(0,0,0,0.65),0 0 0 1px rgba(255,255,255,0.2)!important;' +
      'scale:1.05!important;transition:scale 120ms ease-out,outline 120ms ease-out,box-shadow 120ms ease-out!important;' +
      'z-index:2147483000!important}' +
      '.movy-tvr{border-radius:16px!important}' +
      '.movy-tvc{border-radius:9999px!important}' +
      '@media(prefers-reduced-motion:reduce){.movy-tvf,.movy-tvf:focus{transition:none!important;scale:1!important}}' +
      'html{scroll-behavior:smooth}' +
      // Blurred poster glow behind the rows (Cinejoy): the artwork belongs to the top banner only, rows sit on the flat page colour.
      '.ambient-blur,.ambient-bleed{display:none!important}' +
      'a[href*="discord" i],a[href*="telegram" i],a[href*="t.me" i]{display:none!important}' +
      'header,div.fixed.top-0.z-50,header.topbar,div.pref-pill-wrap,header.fixed{display:none!important}';
    (doc.head || doc.documentElement).appendChild(st);
  }
  doc.addEventListener('DOMContentLoaded', installStyle);
  installStyle();

  // ---------- public ----------

  TV.move = move;
  TV.collect = function () { ensureCache(); return candItems(null); };
  TV.focusFirst = focusFirst;
  TV.autoFirstFocus = autoFirstFocus;
  TV.reloadProfile = function () {
    if (!loadProfile()) return false;
    if (!inFrame) relayDown({ __movyTvProfile: TV.PF.raw });
    return true;
  };
  TV.setCurrent = setCurrent;
  TV.pressLike = pressLike;
  TV.renderableNow = renderableNow;
  TV.modalOpen = function () { return !!cache.modal; };
  TV.back = function () {
    // Modals
    if (cache.modal) {
      var m = cache.modal;
      var cBtn = m.querySelector('button[aria-label*="close" i], button.absolute.top-4.right-4, button.close-btn');
      if (cBtn) { pressLike(cBtn); cache.modal = null; return true; }
    }
    var mfModal = doc.querySelector('.fixed.z-\\[300\\]:not(.bg-black\\/80)');
    if (mfModal && !isPlayerActive(true)) { // the player overlay is handled below: first BACK hides the controls, second leaves
      var mfClose = mfModal.querySelector('button[class*="top-4"][class*="right-4"], button.rounded-sm');
      if (mfClose) { pressLike(mfClose); return true; }
    }
    // Player sub-menus (Quality, Servers, Subtitles)
    if (TV.menuNow && TV.menuNow()) {
      try {
        var menu = TV.menuNow();
        if (menu) {
          var esc = new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true });
          menu.dispatchEvent(esc);
        }
      } catch (x) {}
      return true;
    }
    // Player active
    if (isPlayerActive()) {
      // One BACK leaves a playing player (a menu was closed above): no hide-the-controls step first
      if (TV.userExitFs) TV.userExitFs(); // the user is leaving the player: it must not pull the next video back into fullscreen
      var cjBack = doc.querySelector('.player-root button[aria-label="Back"]');
      if (cjBack) { pressLike(cjBack); return true; }
      var movyBack = doc.querySelector('#vp-shell svg.cursor-pointer');
      if (movyBack) { pressLike(movyBack); return true; } // an <svg> has no click(): same pointer sequence as OK
      var mfPlayerClose = doc.querySelector('button.absolute.top-4.right-4.z-50');
      if (mfPlayerClose) { pressLike(mfPlayerClose); return true; }
      if (root.location.pathname.indexOf('/watch') >= 0) {
        root.history.back();
        return true;
      }
    }
    return false;
  };
  /** Drops the focus ring and DOM focus: the page is "free" again (OK = play/pause, arrows = seek during playback). */
  TV.releaseFocus = function () {
    clearRing(current);
    current = null;
    try { var a = doc.activeElement; if (a && a !== doc.body && a.blur) a.blur(); } catch (e) { /* ignore */ }
  };
  TV.currentEl = function () { return current; };
  /** Rebuilds the candidate cache when it is older than 300 ms (a player bar that just appeared must be reachable at once). */
  TV.refresh = function () { if (root.performance.now() - cache.builtAt > 300) { try { buildCache(); } catch (e) { cache.dirty = true; } } };
  /** Live (uncached) answer: the open player popover or null. Also refreshes TV.menuEl. */
  TV.menuNow = function () { TV.menuEl = TV.PF ? openPlayerMenu(TV.PF) : null; return TV.menuEl; };
  TV.activeEl = activeEl;
  TV.isPlayerFrame = isPlayerFrame;
  TV.bigIframe = bigIframe;
  TV.relayDown = relayDown;
  TV.okSel = okSel;
  TV.score = P.score;
  TV.pick = P.pick;
  TV.stats.cacheSize = function () { return cache.list.length; };
  TV.debug = function () {
    var a = activeEl(), r = a && a.getBoundingClientRect();
    return { cur: a && (a.tagName + '.' + (a.className || '').toString().slice(0, 50) + ' "' + (a.innerText || a.getAttribute('aria-label') || '').trim().slice(0, 30) + '"'),
      rect: r && [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)], colX: colX && Math.round(colX), cands: cache.list.length,
      rows: cache.list.filter(function (c) { return c.row != null; }).length, modal: !!cache.modal, userMoved: userMoved, ff: TV.ff, sy: Math.round(root.scrollY),
      t: TV.timings.slice(-4) };
  };

  loadProfile();
  watch();
})(typeof window !== 'undefined' ? window : {});
