// Movy TV focus memory. Top frame only. Remembers the card that was opened (OK) on a page, and when the user comes back to that
// page (BACK / history), puts the focus on the same card again and scrolls it into view. Keyed by page path, stored per tab.
(function (root) {
  'use strict';
  var STORE = '__movy_tv_focusmem';
  var MAX_PAGES = 12;
  var TRIES = 14;
  var TRY_MS = 250;

  /** Pure. A stable identity for a focus target: its link path, else its label, else its start of text. */
  function keyFor(d) {
    if (d.href) return 'h:' + String(d.href).replace(/^https?:\/\/[^/]+/i, '').split('#')[0];
    if (d.label) return 'l:' + String(d.label).trim().slice(0, 60);
    return d.text ? 't:' + String(d.text).replace(/\s+/g, ' ').trim().slice(0, 40) : '';
  }

  /** Pure. Index of the first descriptor whose key equals `key`, or -1. */
  function findKey(list, key) {
    if (!key) return -1;
    for (var i = 0; i < list.length; i++) if (keyFor(list[i]) === key) return i;
    return -1;
  }

  /** Pure. New store with `key` remembered for `page`; the oldest pages beyond MAX_PAGES are dropped (no mutation). */
  function remember(store, page, key) {
    var out = {}, order = (store.__order || []).filter(function (p) { return p !== page; });
    order.push(page);
    while (order.length > MAX_PAGES) order.shift();
    order.forEach(function (p) { out[p] = p === page ? key : store[p]; });
    out.__order = order;
    return out;
  }

  var api = { keyFor: keyFor, findKey: findKey, remember: remember };
  if (typeof module === 'object' && module && module.exports) { module.exports = api; return; }
  var TV = root.__movyTv;
  if (!TV || root.parent !== root) return;

  function read() { try { return JSON.parse(root.sessionStorage.getItem(STORE) || '{}'); } catch (e) { return {}; } }
  function write(s) { try { root.sessionStorage.setItem(STORE, JSON.stringify(s)); } catch (e) { /* storage blocked */ } }
  function page() { return root.location.pathname + root.location.search; }

  function describe(el) {
    var a = el.closest && el.closest('a[href]');
    return { href: a ? a.getAttribute('href') : '', label: el.getAttribute && el.getAttribute('aria-label'), text: el.textContent };
  }

  var prev = TV.hooks.beforeActivate;
  TV.hooks.beforeActivate = function (el) {
    try {
      var k = keyFor(describe(el));
      if (k) write(remember(read(), page(), k));
    } catch (e) { /* never block activation */ }
    return prev ? prev.apply(this, arguments) : undefined;
  };

  var touched = false;
  root.addEventListener('keydown', function (e) { if (e.keyCode >= 19 && e.keyCode <= 22) touched = true; }, true);

  function restore(key, left) {
    if (touched || !key) return;
    var items = [];
    try { items = TV.collect(); } catch (e) { /* not ready */ }
    var list = items.map(function (c) { return describe(c.el); });
    var i = findKey(list, key);
    if (i >= 0) {
      var el = items[i].el;
      try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (e) { /* ignore */ }
      TV.setCurrent(el, null);
      return;
    }
    if (left > 0) root.setTimeout(function () { restore(key, left - 1); }, TRY_MS);
  }

  var lastPage = page();
  function onRoute() {
    var p = page();
    if (p === lastPage) return;
    lastPage = p;
    touched = false;
    var key = read()[p];
    if (key) root.setTimeout(function () { restore(key, TRIES); }, TRY_MS);
  }
  // SPA route changes: pushState/replaceState do not fire an event of their own, so wrap them (no polling).
  ['pushState', 'replaceState'].forEach(function (m) {
    var orig = root.history && root.history[m];
    if (typeof orig !== 'function') return;
    root.history[m] = function () {
      var r = orig.apply(this, arguments);
      root.setTimeout(onRoute, 0);
      return r;
    };
  });
  root.addEventListener('popstate', onRoute);
  root.addEventListener('hashchange', onRoute);
  var key0 = read()[page()];
  if (key0) root.addEventListener('load', function () { root.setTimeout(function () { restore(key0, TRIES); }, TRY_MS); });
})(typeof window !== 'undefined' ? window : {});
