// Movy TV drawer, page side. The drawer itself is native (TvDrawerView); this module only
//  - reports to the app: whether the collapsed strip may show (not in a player / fullscreen / modal), the page path and the recent titles;
//  - runs the actions the app asks for (TV.drawerAction): route, search, pick-from-a-menu, sign-in;
//  - hands focus over: TV.drawerOpened() parks the focused element, TV.drawerClosed(restore) gives it back;
//  - owns the recent-titles store (movy_tv_recent, per origin).
// The page also tells the app, a moment after each focus change, whether LEFT would leave the page (TV.leftBlocked): then the app opens
// the drawer by itself on LEFT, without waiting for a busy page to handle the key.
// Items come from the profile (`drawer.items`), never from the host name. Top frame only. Needs window.__movyTv and __movyTvPure.
(function (root) {
  'use strict';
  var TV = root.__movyTv;
  var P = root.__movyTvPure;
  if (!TV || !P || TV.drawerLoaded || root.parent !== root) return;
  TV.drawerLoaded = true;

  var doc = document;
  var RECENT_KEY = 'movy_tv_recent';
  var OPEN_KEY = 'movy_tv_open_title';
  var SIGNIN_KEY = 'movy_tv_open_signin';
  var RECENT_MAX = 8;
  var RECENT_SHOWN = 3;

  function native() { return root.MovyTvNative; }
  function cfg() { return (TV.PF && TV.PF.raw && TV.PF.raw.drawer) || { items: [] }; }
  function path() { return root.location.pathname || '/'; }

  // ---------- recent titles ----------

  function readRecent() {
    try {
      var a = JSON.parse(root.localStorage.getItem(RECENT_KEY) || '[]');
      return Array.isArray(a) ? a : [];
    } catch (e) { return []; }
  }

  function writeRecent(list) {
    try { root.localStorage.setItem(RECENT_KEY, JSON.stringify(list)); return true; } catch (e) { return false; }
  }

  function shown() { return P.recentShown(readRecent(), path(), RECENT_SHOWN); }

  var recordedPath = null;
  var lastPushed = '';

  function brandIs(title) {
    var b = cfg().brand;
    return !!b && title.toLowerCase() === String(b).toLowerCase();
  }

  function recordRecent() {
    var p = path();
    if (!P.isDetailPath(p)) return;
    var title = P.cleanTitle(doc.title);
    if (!title || brandIs(title)) return;
    if (writeRecent(P.addRecent(readRecent(), { t: title, p: p, ts: Date.now() }, RECENT_MAX))) { recordedPath = p; pushState(); }
  }

  /** Sites whose detail view is a modal without its own URL: remember the title and re-open it from the cards of the page. */
  function recordModalRecent(modal) {
    var h = modal && modal.querySelector('h1,h2');
    var title = P.cleanTitle(h && h.textContent);
    if (!title || brandIs(title) || !cfg().modalTitles) return;
    if (writeRecent(P.addRecent(readRecent(), { t: title, p: path(), q: title, ts: Date.now() }, RECENT_MAX))) pushState();
  }

  function openRecentTitle() {
    var q = '';
    try { q = root.sessionStorage.getItem(OPEN_KEY) || ''; } catch (e) { return; }
    if (!q) return;
    var tries = 0;
    (function look() {
      var btns = doc.querySelectorAll('button[aria-label^="More info about"]');
      for (var i = 0; i < btns.length; i++) {
        if ((btns[i].getAttribute('aria-label') || '').toLowerCase().indexOf(q.toLowerCase()) < 0) continue;
        try { root.sessionStorage.removeItem(OPEN_KEY); } catch (e) { /* ignore */ }
        TV.pressLike(btns[i]);
        return;
      }
      if (++tries < 12) setTimeout(look, 700); else { try { root.sessionStorage.removeItem(OPEN_KEY); } catch (e) { /* ignore */ } }
    })();
  }

  // ---------- reports to the app ----------

  /** Page path + the titles the drawer lists under "Continue watching". Sent only when it changed. */
  function pushState() {
    var n = native();
    if (!n || !n.drawerState) return;
    var titles = shown().map(function (e) { return e.t; });
    var s = path() + '|' + JSON.stringify(titles);
    if (s === lastPushed) return;
    lastPushed = s;
    try { n.drawerState(path(), JSON.stringify(titles)); } catch (e) { /* ignore */ }
  }

  var lastVisible = null;
  var lastPlayer = null;
  var lastReportAt = 0;

  /** The strip steps aside for players, fullscreen and modals; tvinset.js moves the page content only while it shows. */
  function syncVisibility() {
    if (recordedPath !== path() && P.isDetailPath(path())) recordRecent(); // the title may land late on SPAs
    var player = !!doc.fullscreenElement || TV.isPlayerActive(true);
    var on = !(player || TV.modalOpen());
    // Re-sent every 3 s even when unchanged: the app resets its copy when a source is (re)activated, and must never stay out of step with the inset.
    if (on !== lastVisible || player !== lastPlayer || Date.now() - lastReportAt > 3000) {
      lastVisible = on;
      lastPlayer = player;
      lastReportAt = Date.now();
      var n = native();
      if (n && n.drawerVisible) { try { n.drawerVisible(on, player); } catch (e) { /* ignore */ } }
    }
    if (TV.hooks.railVisible) TV.hooks.railVisible(on);
  }

  // Not setInterval: some sites sweep and clear every interval id. A frame loop with a time check cannot be cancelled that way.
  function startLoop() {
    var last = 0;
    (function frame(ts) {
      if (ts - last >= 600) {
        last = ts;
        try { syncVisibility(); pushState(); } catch (e) { /* never end the loop */ }
      }
      if (edgeDue && Date.now() > edgeDue + 400) { try { probeEdge(); } catch (e) { /* ignore */ } } // the timer was swept by the site
      root.requestAnimationFrame(frame);
    })(0);
    syncVisibility();
    pushState();
    setTimeout(recordRecent, 1800);
  }

  // ---------- LEFT-edge report ----------

  var edgeLast = null;
  var edgeDue = 0;

  function reportEdge(on) {
    if (on === edgeLast) return;
    edgeLast = on;
    var n = native();
    if (n && n.edgeState) { try { n.edgeState(on); } catch (e) { /* ignore */ } }
  }

  function probeEdge() {
    edgeDue = 0;
    var on = false;
    try { on = !!TV.leftBlocked(); } catch (e) { /* unknown: not at the edge */ }
    reportEdge(on);
  }

  /** The focus moved (or the page changed): not at the edge until the page has been still for a moment. */
  function scheduleEdge() {
    reportEdge(false);
    var due = edgeDue = Date.now() + 160;
    setTimeout(function () { if (edgeDue === due) probeEdge(); }, 170); // only the last focus change's timer probes
  }

  TV.hooks.focused = scheduleEdge;

  // ---------- focus handoff ----------

  var parked = null;

  TV.drawerOpened = function () {
    if (!parked) parked = TV.activeEl() || TV.currentEl();
    TV.focusHeld = true; // no automatic first focus while the drawer is open
    TV.releaseFocus();
  };

  TV.drawerClosed = function (restore) {
    var el = parked;
    parked = null;
    TV.focusHeld = false;
    if (restore === false) return;
    if (el && el.isConnected && TV.renderableNow(el)) TV.setCurrent(el, null);
    else TV.focusFirst(null, true);
  };

  /** LEFT at the page's left edge (called by tvnav): park the focus and ask the app to open the drawer. */
  TV.edgeLeft = function () {
    var n = native();
    if (!n || !n.edgeLeft) return;
    TV.drawerOpened();
    n.edgeLeft();
  };

  // ---------- actions ----------

  function press(el) { TV.pressLike(el); }

  /** Opens a route inside the SPA when it can (a link of the page, else history + popstate), else a normal navigation. */
  function go(target) {
    if (path() === target) return;
    var a = doc.querySelector('a[href="' + target + '"]');
    if (a) { press(a); return; }
    var changed = 0;
    var mo = new MutationObserver(function (recs) { changed += recs.length; });
    try {
      mo.observe(doc.body, { childList: true, subtree: true });
      root.history.pushState({}, '', target);
      root.dispatchEvent(new PopStateEvent('popstate', { state: {} }));
    } catch (e) { mo.disconnect(); root.location.href = target; return; }
    setTimeout(function () {
      mo.disconnect();
      if (changed < 20) root.location.href = target; // the router ignored it: a real navigation
    }, 1000);
  }

  var shownHeader = null;

  /** Some sites hide their header on TV: while its search is in use the header (and the search box in it) must be on screen. */
  function showHeaderOf(el) {
    if (el.getBoundingClientRect().width) return;
    var h = el.closest('header, div.fixed.top-0.z-50, header.topbar, div.pref-pill-wrap, header.fixed');
    if (!h) return;
    h.style.setProperty('display', 'block', 'important');
    h.style.setProperty('opacity', '1', 'important');
    shownHeader = h;
  }

  function restoreHeader() {
    if (!shownHeader) return;
    shownHeader.style.removeProperty('display');
    shownHeader.style.removeProperty('opacity');
    shownHeader = null;
  }

  /** The drawer's Search item: tvsearchmic.js shows the site box (profile `search`), focuses it and opens the native TV keyboard. */
  function openSiteSearch() {
    if (TV.searchFromDrawer) TV.searchFromDrawer({ showHeaderOf: showHeaderOf, press: press, go: go });
  }
  /** The remote's search / mic key (native): the same as the drawer's Search item. */
  TV.siteSearch = function () { openSiteSearch(); return !!TV.searchFromDrawer; };

  /** A button pressed before its page finished hydrating does nothing: press again (a few times) until a dialog is up. */
  function confirmOpened(btn, n) {
    setTimeout(function () {
      TV.collect(); // refreshes the modal detection (it is only recomputed on a rebuild)
      if (TV.modalOpen() || n >= 4 || !btn.isConnected) return;
      press(btn);
      confirmOpened(btn, n + 1);
    }, 1400);
  }

  function pressByText(re, tries, expectDialog, onPressed) {
    var rx = new RegExp(re, 'i');
    var n = 0;
    (function find() {
      var btns = doc.querySelectorAll('button, a');
      for (var i = 0; i < btns.length; i++) {
        if (rx.test((btns[i].textContent || '').trim()) && btns[i].getBoundingClientRect().width > 0) { press(btns[i]); if (onPressed) onPressed(); if (expectDialog) confirmOpened(btns[i], 0); return; }
      }
      if (++n < (tries || 15)) setTimeout(find, 150);
    })();
  }

  /** Opens a menu element, then presses the entry inside it (a header button that opens a popover). */
  function pick(it) {
    var openEl = null;
    if (it.openText) {
      var reg = new RegExp(it.openText, 'i');
      var all = doc.querySelectorAll(it.open || 'button, a');
      for (var i = 0; i < all.length; i++) {
        if (reg.test((all[i].innerText || all[i].getAttribute('aria-label') || '').trim())) { openEl = all[i]; break; }
      }
    } else if (it.open) {
      openEl = doc.querySelector(it.open);
    }
    if (!openEl) return;
    showHeaderOf(openEl); // a header hidden on TV is laid out while its menu is pressed (the menu needs a box)
    press(openEl);
    if (!it.item) { setTimeout(restoreHeader, 700); return; }
    var itemRe = new RegExp(it.item, 'i');
    var tries = 0;
    (function findItem() {
      var cands = doc.querySelectorAll(it.itemSel || '.profile-menu-primary, .profile-menu-actions button, .profile-menu-actions a, [role="menuitem"], [role="menu"] button, .profile-popover button, .player-surface button');
      for (var j = 0; j < cands.length; j++) {
        var txt = (cands[j].innerText || cands[j].getAttribute('aria-label') || '').trim();
        if (itemRe.test(txt)) { press(cands[j]); setTimeout(restoreHeader, 700); return; } // the menu must still be laid out while it is pressed
      }
      if (++tries < 12) setTimeout(findItem, 100); else restoreHeader();
    })();
  }

  /** `section`: open the route, then scroll to the heading the item names (openText) and focus its first tile (a site's own "continue watching" row). */
  function section(it) {
    var rx = new RegExp(it.openText || '^$', 'i');
    if (it.path && path() !== it.path) go(it.path);
    var tries = 0;
    (function look() {
      var heads = doc.querySelectorAll('h1,h2,h3,[role="heading"]');
      for (var i = 0; i < heads.length; i++) {
        if (!rx.test((heads[i].textContent || '').trim()) || !heads[i].getBoundingClientRect().width) continue;
        var hb = heads[i].getBoundingClientRect().bottom;
        var all = TV.collect();
        for (var box = heads[i].parentElement, up = 0; box && up < 5; up++, box = box.parentElement) {
          var tiles = all.filter(function (c) { return box.contains(c.el) && c.rect.t >= hb - 4; }); // the tiles under the heading (not its own buttons)
          tiles.sort(function (a, b) { return a.rect.t - b.rect.t || a.rect.l - b.rect.l; });
          if (tiles.length) { TV.setCurrent(tiles[0].el, null); tiles[0].el.scrollIntoView({ block: 'center' }); return; }
        }
      }
      if (++tries < 16) setTimeout(look, 300);
    })();
  }

  /** `account` with a path: open that page, then press the sign-in button the item names (openText). */
  function account(it) {
    if (!it.path) { pick(it); return; }
    if (path() === it.path) { pressByText(it.openText || '^sign in$', 40, true); return; }
    try { root.sessionStorage.setItem(SIGNIN_KEY, it.openText || '^sign in$'); } catch (e) { /* ignore */ }
    go(it.path);
  }

  /** A button pressed before the page finished hydrating does nothing: wait for the load event (then a moment more). */
  function afterLoad(fn) {
    if (doc.readyState === 'complete') { setTimeout(fn, 800); return; }
    root.addEventListener('load', function () { setTimeout(fn, 1200); }, { once: true });
  }

  function checkSignIn() {
    var re = '';
    try { re = root.sessionStorage.getItem(SIGNIN_KEY) || ''; } catch (e) { return; }
    if (!re) return;
    var items = cfg().items || [];
    for (var i = 0; i < items.length; i++) {
      if (items[i].action === 'account' && items[i].path === path()) {
        // The flag stays until the button was pressed: the page may still be replaced by a real navigation (go() falls back to one).
        afterLoad(function () {
          pressByText(re, 40, true, function () { try { root.sessionStorage.removeItem(SIGNIN_KEY); } catch (e) { /* ignore */ } });
        });
        return;
      }
    }
  }

  function recentAction(i) {
    var e = shown()[i];
    if (!e) return;
    if (e.q) { // a modal title: reopen it from the cards of the page it was seen on
      try { root.sessionStorage.setItem(OPEN_KEY, e.q); } catch (x) { /* ignore */ }
      if (path() === e.p) openRecentTitle(); else go(e.p);
      return;
    }
    go(e.p);
  }

  /**
   * The app picked an item (id from the profile, or "recent:N"). The parked focus is dropped (the page is about to change or
   * the action focuses something itself); a pick that changes nothing (the page it opens is the one already shown) restores it.
   */
  TV.drawerAction = function (id) {
    id = String(id || '');
    var before = path();
    var el = parked;
    parked = null;
    TV.focusHeld = false;
    var done = runAction(id);
    setTimeout(function () {
      if (done && before === path() && el && el.isConnected && !TV.activeEl() && TV.renderableNow(el)) TV.setCurrent(el, null);
    }, 450);
    return done;
  };

  function runAction(id) {
    if (id.indexOf('recent:') === 0) { recentAction(parseInt(id.slice(7), 10)); return true; }
    var items = cfg().items || [];
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      if (it.id !== id) continue;
      if (it.action === 'search') openSiteSearch();
      else if (it.action === 'pick') pick(it);
      else if (it.action === 'account') account(it);
      else if (it.action === 'section') section(it);
      else if (it.path) go(it.path);
      return true;
    }
    return false;
  }

  // ---------- hooks from tvnav ----------

  TV.hooks.modalOpened = function (m) { setTimeout(function () { recordModalRecent(m); }, 700); };
  TV.hooks.route = function () {
    restoreHeader();
    setTimeout(recordRecent, 1800);
    setTimeout(function () { syncVisibility(); pushState(); }, 400);
    scheduleEdge();
    checkSignIn();
  };
  TV.hooks.loaded = function () { setTimeout(openRecentTitle, 900); };
  TV.recordRecent = recordRecent;

  checkSignIn();
  if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', startLoop); else startLoop();
  root.addEventListener('load', function () { setTimeout(recordRecent, 1800); });
})(typeof window !== 'undefined' ? window : {});
