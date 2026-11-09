// Movy TV: the player menu engine, the same on every site. Concatenated after tvnav.js / tvfs.js (needs window.__movyTv).
// A menu is "open" when the profile's player.menu selector is visible (TV.menuNow). While it is open:
//   - focus is confined to its items (visible, not player.never, no text/file inputs, no upload/translate rows);
//   - on open, focus lands on the selected item (player.menuSelected / aria / active class), else the first one;
//   - UP/DOWN walk the column in DOM order (DOWN past the end enters the footer rows), LEFT/RIGHT jump to the neighbour
//     column at the closest height, the edges stay put; every move scrolls the inner list (TV.setCurrent -> reveal);
//   - OK presses the item: a final choice closes the menu after 250 ms (Netflix style), a drill row (accordion, settings
//     page) stays open; player.menuConfirm is pressed after a choice when the site needs an Apply;
//   - BACK / Escape close it the way the profile says (escape | toggle | click, then the other ways as fallbacks) and focus
//     returns to the control that opened it; the player controls are held visible while a menu is up.
// Frames: a menu inside a player iframe reports `{menu, ctl}` upwards, so the top document knows BACK must be relayed down.
(function (root) {
  'use strict';
  var TV = root.__movyTv;
  var P = root.__movyTvPure;
  if (!TV || !P || TV.menuLoaded) return;
  TV.menuLoaded = true;

  var doc = document;
  var inFrame = root.parent !== root;
  var DEFAULT_ITEMS = 'button,[role=option],[role=menuitem],[role=menuitemradio],[role=radio],[role=checkbox],a[href],li[tabindex],li.track-selector,.lb-opt';
  var SKIP_LABEL = /favorite server|scroll (left|right)|close server list/i;
  var SELECTED_CLASS = /^(active|selected|checked|is-active|current)$/;
  var AFTER_CHOICE_MS = 250;
  var CLOSE_RETRY_MS = 320;
  var HOLD_MS = 1500;
  var TICK_MS = 150;

  var state = { menu: null, toggle: null, closing: false, holdAt: 0, openedAt: 0, moved: false };
  var childStates = new Map();
  var lastReport = '';

  function raw() { return (TV.PF && TV.PF.raw && TV.PF.raw.player) || {}; }

  function joined(list) {
    var out = [];
    for (var i = 0; list && i < list.length; i++) if (typeof list[i] === 'string' && TV.okSel(list[i])) out.push(list[i]);
    return out.join(',');
  }

  function up(msg) { try { root.parent.postMessage(msg, '*'); } catch (e) { /* ignore */ } }

  function stop(e) { e.preventDefault(); e.stopPropagation(); }

  function toRect(r) { return { l: r.left, t: r.top, r: r.right, b: r.bottom }; }

  /** The element can be seen and pressed: rendered, not faded out, not click-through, up to the menu container. */
  function shown(el, menu) {
    var r = el.getBoundingClientRect();
    if (r.width < 8 || r.height < 8) return false;
    for (var p = el; p && p !== doc.documentElement; p = p.parentElement) {
      var cs = root.getComputedStyle(p);
      if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) < 0.1) return false;
      if (p === menu) break;
    }
    return root.getComputedStyle(el).pointerEvents !== 'none';
  }

  function textOf(el) { return (el.innerText || el.getAttribute('aria-label') || el.value || '').trim(); }

  function isSelected(el, selSel) {
    if (selSel && el.matches(selSel)) return true;
    if (el.getAttribute('aria-checked') === 'true' || el.getAttribute('aria-selected') === 'true' || el.getAttribute('aria-current') === 'true') return true;
    for (var i = 0; i < el.classList.length; i++) if (SELECTED_CLASS.test(el.classList[i])) return true;
    return false;
  }

  /** Usable items of an open menu, in DOM order, as descriptors for the pure step logic. */
  function collect(menu) {
    var r = raw();
    var sel = joined(r.menuItems) || DEFAULT_ITEMS;
    var never = joined(r.never), selSel = joined(r.menuSelected), colSel = joined(r.menuColumns);
    var cols = colSel ? menu.querySelectorAll(colSel) : [];
    var nodes = menu.querySelectorAll(sel), els = [], i, j;
    for (i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName)) continue; // a text or file input never takes the remote's focus
      if (never && (el.matches(never) || el.closest(never))) continue;
      if (SKIP_LABEL.test(el.getAttribute('aria-label') || '')) continue;
      var text = textOf(el);
      if (P.menuIsUnusable(text) || !shown(el, menu)) continue;
      els.push(el);
    }
    var out = [];
    for (i = 0; i < els.length; i++) {
      var nested = false;
      for (j = 0; j < els.length && !nested; j++) if (j !== i && els[i].contains(els[j])) nested = true;
      if (nested) continue; // an outer wrapper of a real item
      var col = cols.length ? -1 : 0; // no columns in the profile: the whole menu is one list (so its selected row is found)
      for (j = 0; j < cols.length; j++) if (cols[j].contains(els[i])) { col = j; break; }
      out.push({ id: out.length, el: els[i], rect: toRect(els[i].getBoundingClientRect()), col: col,
        selected: isSelected(els[i], selSel), drill: P.menuIsDrill(textOf(els[i]), els[i].hasAttribute('aria-expanded') || els[i].hasAttribute('aria-haspopup')) });
    }
    return out;
  }

  function focusedItem(items) {
    var a = TV.activeEl();
    if (!a) return null;
    for (var i = 0; i < items.length; i++) if (items[i].el === a || items[i].el.contains(a)) return items[i];
    return null;
  }

  function blurTextInput() {
    var a = doc.activeElement;
    if (a && /^(INPUT|TEXTAREA)$/.test(a.tagName) && a.blur) { try { a.blur(); } catch (e) { /* ignore */ } }
  }

  function focusInitial(menu) {
    blurTextInput();
    var items = collect(menu);
    var first = P.menuInitial(P.menuGroups(items));
    if (first) TV.setCurrent(first.el, null);
    return !!first;
  }

  // ---------- pressing and closing ----------

  function press(el) {
    var seq = ['pointerdown', 'mousedown', 'pointerup', 'mouseup'];
    for (var i = 0; i < seq.length; i++) {
      try {
        var C = seq[i].indexOf('pointer') === 0 ? PointerEvent : MouseEvent;
        el.dispatchEvent(new C(seq[i], { bubbles: true, cancelable: true, view: root, pointerType: 'mouse' }));
      } catch (e) { /* ignore */ }
    }
    if (typeof el.click === 'function') el.click();
  }

  function pressEscape() {
    var ev = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
    try {
      (doc.activeElement || doc.body).dispatchEvent(new KeyboardEvent('keydown', ev));
      doc.dispatchEvent(new KeyboardEvent('keyup', ev));
    } catch (e) { /* ignore */ }
  }

  function doClose(mode, mc) {
    if (mode === 'escape') pressEscape();
    else if (mode === 'toggle') { if (state.toggle && state.toggle.isConnected) press(state.toggle); }
    else if (mode === 'click' && mc.selector && TV.okSel(mc.selector)) {
      var b = doc.querySelector(mc.selector);
      if (b) press(b);
    }
  }

  /** Closes the open menu: the profile's way first, then the others, each given 320 ms to take effect. */
  function closeMenu() {
    if (state.closing || !TV.menuNow()) return;
    state.closing = true;
    var mc = raw().menuClose || {};
    var order = P.menuCloseOrder(mc.mode, { toggle: !!(state.toggle && state.toggle.isConnected), click: !!(mc.selector && TV.okSel(mc.selector)) });
    var i = 0;
    (function attempt() {
      if (!TV.menuNow() || i >= order.length) { state.closing = false; return; }
      doClose(order[i++], mc);
      setTimeout(attempt, CLOSE_RETRY_MS);
    })();
  }

  function choose(item) {
    var confirmSel = raw().menuConfirm;
    press(item.el);
    if (item.drill) return; // an accordion or a settings page: the menu stays, DOWN walks into what opened
    if (confirmSel && TV.okSel(confirmSel)) {
      setTimeout(function () { var b = doc.querySelector(confirmSel); if (b) press(b); }, 120);
    }
    setTimeout(closeMenu, AFTER_CHOICE_MS);
  }

  // ---------- opening, holding, closing detection ----------

  function wakeFrame() {
    if (TV.wakeControls) TV.wakeControls(); // the shared wake: neutral point, never a bar, seek preview closed
  }

  /** Keeps the player bar up under an open menu (the site and the shell both fade it on idle). */
  function hold() {
    var now = Date.now();
    if (now - state.holdAt < HOLD_MS) return;
    state.holdAt = now;
    if (TV.showControls) TV.showControls();
    if (inFrame) wakeFrame();
  }

  function onOpen(menu) {
    state.menu = menu;
    state.closing = false;
    state.openedAt = Date.now();
    var opener = TV.menuToggle;
    state.toggle = opener && opener.isConnected ? opener : state.toggle;
    hold();
    state.moved = false;
    focusInitial(menu);
    // Menus render in stages (the list, then the check mark): settle on the selected row once the content is there, unless the
    // user has already moved.
    [250, 600].forEach(function (ms) {
      setTimeout(function () { if (state.menu === menu && !state.moved) focusInitial(menu); }, ms);
    });
  }

  function onClosed() {
    var toggle = state.toggle;
    state.menu = null;
    state.closing = false;
    if (toggle && toggle.isConnected) TV.setCurrent(toggle, null); // back on the control that opened it
    if (TV.showControls) TV.showControls(); // restarts the 5 s fade
  }

  function focusedControl() {
    var a = TV.activeEl();
    return !!(a && a.isConnected && a.tagName !== 'VIDEO' && a !== doc.body);
  }

  // ---------- frame state mirrored upwards ----------

  /** Forgets frames that are gone (a closed player must not keep BACK busy). */
  function pruneChildren() {
    if (!childStates.size) return;
    var alive = new Set();
    var frames = doc.getElementsByTagName('iframe');
    for (var i = 0; i < frames.length; i++) { try { alive.add(frames[i].contentWindow); } catch (e) { /* ignore */ } }
    childStates.forEach(function (s, win) { if (!alive.has(win)) childStates.delete(win); });
  }

  /** What is open in this frame and below. The top document counts only its children (it handles its own BACK). */
  function aggregate() {
    pruneChildren();
    var menu = inFrame && !!state.menu, ctl = inFrame && focusedControl();
    childStates.forEach(function (s) { menu = menu || s.menu; ctl = ctl || s.ctl; });
    return { menu: menu, ctl: ctl };
  }

  function publish() {
    var st = aggregate();
    var key = st.menu + '|' + st.ctl;
    if (key === lastReport) return;
    lastReport = key;
    TV.childAgg = st;
    if (inFrame) up({ __movyTvState: st });
  }

  function tick() {
    var m = TV.PF ? TV.menuNow() : null;
    if (m && m !== state.menu) onOpen(m);
    else if (!m && state.menu) onClosed();
    else if (m) hold();
    publish();
  }

  // A frame loop with a time check: some sites clear every interval id, which would silently stop a setInterval.
  (function loop() {
    var last = 0;
    (function frame(ts) {
      if (ts - last >= TICK_MS) {
        last = ts;
        try { tick(); } catch (e) { /* never end the loop */ }
      }
      root.requestAnimationFrame(frame);
    })(0);
  })();

  // ---------- keys ----------

  var prevKey = TV.hooks.key;
  TV.hooks.key = function (e, dir, a) {
    var m = TV.PF ? TV.menuNow() : null;
    if (!m && TV.childAgg && TV.childAgg.menu && (dir || e.keyCode === 13 || e.keyCode === 23 || e.keyCode === 27)) {
      // A menu is open inside a player frame but this document holds the key focus (the frame element itself): hand the key down.
      stop(e);
      if (e.keyCode === 27) TV.relayDown({ __movyTvBack: 1 });
      else if (!(e.repeat && e.keyCode !== 38 && e.keyCode !== 40)) TV.relayDown({ __movyTvKey: { k: e.keyCode, d: dir, r: !!e.repeat } });
      return true;
    }
    return onMenuKey(e, dir, a, m);
  };

  function onMenuKey(e, dir, a, m) {
    if (!m) return prevKey ? prevKey(e, dir, a) : false;
    if (m !== state.menu) onOpen(m);
    hold();
    if (e.keyCode === 27) {
      if (!e.isTrusted) return false; // our own synthetic Escape (closing) must reach the site
      stop(e);
      closeMenu();
      return true;
    }
    var items = collect(m);
    var cur = focusedItem(items);
    if (e.keyCode !== 27) state.moved = true;
    if (e.keyCode === 13 || e.keyCode === 23) {
      stop(e);
      if (e.repeat) return true;
      if (!cur) focusInitial(m); else choose(cur);
      return true;
    }
    if (!dir) return prevKey ? prevKey(e, dir, a) : false;
    stop(e);
    if (!cur) { focusInitial(m); return true; }
    var next = P.menuStep(P.menuGroups(items), cur.id, dir);
    if (next) TV.setCurrent(next.el, dir); // reveal() scrolls the inner list; the page itself never moves
    return true;
  }

  // ---------- BACK ----------

  var prevBack = TV.back;
  TV.back = function () {
    if (inFrame) return false;
    if (TV.PF && TV.menuNow()) { closeMenu(); return true; }
    var agg = TV.childAgg;
    if (agg && (agg.menu || agg.ctl)) { TV.relayDown({ __movyTvBack: 1 }); return true; } // a menu or a control inside a player frame
    return prevBack ? prevBack() : false;
  };

  root.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || typeof d !== 'object') return;
    if (d.__movyTvState && e.source !== root) {
      childStates.set(e.source, d.__movyTvState);
      publish();
      return;
    }
    if (d.__movyTvKey && e.source === root.parent) {
      var k = d.__movyTvKey, m = TV.PF ? TV.menuNow() : null;
      if (m) onMenuKey({ keyCode: k.k, repeat: !!k.r, isTrusted: true, preventDefault: function () {}, stopPropagation: function () {} }, k.d, null, m);
      else TV.relayDown(d); // a deeper frame holds the menu
      return;
    }
    if (d.__movyTvBack && e.source === root.parent) {
      if (TV.PF && TV.menuNow()) closeMenu();
      else if (focusedControl()) TV.releaseFocus();
      TV.relayDown(d); // a deeper frame may hold the menu
    }
  });
})(typeof window !== 'undefined' ? window : {});
