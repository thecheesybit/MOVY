// Movy TV: breathing space next to the mini rail. Concatenated after tvnav.js / tvfs.js (needs window.__movyTv). Top frame only.
// The profile lists page wrappers (`railInset`: [{selector, prop}]). While the mini rail is on screen, each wrapper whose content
// would start closer to the left edge than RAIL_CONTENT_START gets just enough extra padding-left / margin-left / left, so
// nothing sits under the rail. The amount is measured once per element with the rule off; the rule itself is plain CSS under
// `html.movy-rail-mini`, so showing or hiding the rail (player, fullscreen, modal) switches it without layout work in JS.
// No transform on body/html: it would break position:fixed and fullscreen.
(function (root) {
  'use strict';
  var TV = root.__movyTv;
  var P = root.__movyTvPure;
  if (!TV || !P || TV.insetLoaded || root.parent !== root) return;
  TV.insetLoaded = true;

  var doc = document;
  var RAIL_CONTENT_START = 88; // 60 px rail + 28 px of air
  var CLS = 'movy-rail-mini';
  var ATTR = 'data-movy-ri';
  var PROPS = ['padding-left', 'margin-left', 'left'];
  var visible = false;
  var scanTimer = 0;
  var seen = new WeakMap(); // last geometry signature of each measured wrapper

  function entries() {
    var raw = TV.PF && TV.PF.raw;
    return raw && Array.isArray(raw.railInset) ? raw.railInset : [];
  }

  function installRule() {
    if (doc.getElementById('__movy_tv_inset_style')) return;
    var css = PROPS.map(function (p) {
      var v = 'calc(var(--movy-ri-base,0px) + var(--movy-ri-add,0px))';
      // A snapping carousel would otherwise re-snap its first card to the box edge (under the rail) after the padding grows.
      return 'html.' + CLS + ' [' + ATTR + '="' + p + '"]{' + p + ':' + v + '!important' + (p === 'padding-left' ? ';scroll-padding-left:' + v + '!important' : '') + '}';
    }).join('');
    var st = doc.createElement('style');
    st.id = '__movy_tv_inset_style';
    st.textContent = css;
    (doc.head || doc.documentElement).appendChild(st);
  }

  var retry = false;
  var retryRounds = 0;

  /** No ancestor is mid-animation (a hero slide sliding in is measured at its resting place, not where it is now). */
  function settled(el) {
    for (var p = el, n = 0; p && p !== doc.documentElement && n < 7; p = p.parentElement, n++) {
      var t = root.getComputedStyle(p).transform;
      if (!t || t === 'none') continue;
      var m = /^matrix\(([^)]+)\)$/.exec(t);
      if (!m) return false;
      var v = m[1].split(',').map(parseFloat);
      if (Math.abs(v[0] - 1) > 0.01 || Math.abs(v[3] - 1) > 0.01 || Math.abs(v[4]) > 0.5 || Math.abs(v[5]) > 0.5) return false;
    }
    return true;
  }

  /**
   * Measures a wrapper (rule off) and tags it when its content starts too close to the left edge. A wrapper counts only once
   * two scans in a row saw the same geometry: pages animate and re-layout while loading, and a half-settled measurement
   * would leave a wrong offset behind.
   */
  function tag(el, prop) {
    if (!settled(el)) { retry = true; return; }
    var cs = root.getComputedStyle(el);
    if (prop === 'left' && cs.position === 'static') return;
    var base = parseFloat(cs.getPropertyValue(prop));
    if (isNaN(base)) base = 0;
    var r = el.getBoundingClientRect();
    if (r.width < 10 || r.height < 4) { retry = true; return; } // not laid out yet (hidden tab, lazy row): measured later
    var sig = Math.round(r.left * 2) + '|' + Math.round(base * 2);
    var prev = seen.get(el);
    seen.set(el, sig);
    if (prev !== sig) { retry = true; return; }
    var need = P.insetNeed(P.insetContentStart(prop, r.left, base), RAIL_CONTENT_START);
    if (!need) {
      if (el.hasAttribute(ATTR)) untag(el);
      return;
    }
    el.setAttribute(ATTR, prop);
    el.style.setProperty('--movy-ri-base', base + 'px');
    el.style.setProperty('--movy-ri-add', need + 'px');
  }

  function untag(el) {
    el.removeAttribute(ATTR);
    el.style.removeProperty('--movy-ri-base');
    el.style.removeProperty('--movy-ri-add');
  }

  function matchesEntry(el, prop, list) {
    for (var i = 0; i < list.length; i++) {
      var e = list[i];
      if (e && e.prop === prop && TV.okSel(e.selector) && el.matches(e.selector)) return true;
    }
    return false;
  }

  /** A framework may reuse a tagged node for something else (keyed rows): untag whatever no longer matches its entry. */
  function untagStale(list) {
    var tagged = doc.querySelectorAll('[' + ATTR + ']');
    for (var i = 0; i < tagged.length; i++) {
      var el = tagged[i];
      if (matchesEntry(el, el.getAttribute(ATTR), list)) continue;
      untag(el);
      seen.delete(el);
    }
  }

  /** Measures untagged wrappers with the rule switched off, so a shifted layout is never mistaken for the original. */
  function scan() {
    scanTimer = 0;
    lastScanAt = Date.now();
    var list = entries();
    if (!list.length || !doc.body) return;
    var h = doc.documentElement;
    var had = h.classList.contains(CLS);
    if (had) h.classList.remove(CLS);
    try {
      untagStale(list);
      for (var i = 0; i < list.length; i++) {
        var e = list[i];
        if (!e || PROPS.indexOf(e.prop) < 0 || !TV.okSel(e.selector)) continue;
        var els = doc.querySelectorAll(e.selector);
        for (var j = 0; j < els.length; j++) tag(els[j], e.prop);
      }
    } finally {
      if (had) h.classList.add(CLS);
    }
    if (retry) { retry = false; if (retryRounds++ < 12) scheduleScan(250, true); } // bounded: a hidden wrapper must not keep a timer alive forever
  }

  var lastScanAt = 0;
  var BOOT_AT = Date.now();
  // busy pages mutate constantly; measuring toggles the rule, so never more often than this (far less often while the page loads)
  function minGap() { return Date.now() - BOOT_AT < 10000 ? 3000 : 1200; }

  function scheduleScan(delay, settling) {
    if (scanTimer || !visible) return;
    var wait = settling ? delay : Math.max(delay, minGap() - (Date.now() - lastScanAt));
    scanTimer = setTimeout(scan, Math.max(0, wait));
  }

  /** Called by the rail whenever its visibility is (re)evaluated. */
  function setVisible(on) {
    on = !!on;
    var h = doc.documentElement;
    if (!h) return;
    installRule();
    if (on === visible && h.classList.contains(CLS) === on) return; // the rail polls this every 600 ms
    visible = on;
    if (on) { seen = new WeakMap(); scan(); } // measure again, with the rule off, before it applies
    h.classList.toggle(CLS, on);
  }

  TV.hooks.railVisible = setVisible;
  TV.insetScan = function () { scan(); };

  // New wrappers (route changes, rows that mount late) are measured shortly after the DOM settles.
  function watch() {
    if (!doc.documentElement) { setTimeout(watch, 30); return; }
    new MutationObserver(function (recs) { if (P.mutationsRelevant(recs)) { retryRounds = 0; scheduleScan(400); } }).observe(doc.documentElement, { childList: true, subtree: true });
  }
  watch();
})(typeof window !== 'undefined' ? window : {});
