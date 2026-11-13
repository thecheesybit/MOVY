// Movy player HUD: a VLC-style touch layer over the main <video> of any frame (phone/tablet shell only).
// Wrapped by CosmeticBridge as (function(){ var MOVY_HUD={enabled, hide:[...], fs:[...]}; <this file> })();
// A single tap on the picture only shows or hides the controls (never play/pause); double tap seeks or toggles;
// horizontal drag scrubs. Brightness (left) and volume (right) vertical swipes are native and arrive as pointercancel.
// "Site menu" hands touches back to the site's own bars (subtitles, quality, servers) for a while.
// Pure helpers are exported for node --test when loaded as a CommonJS module.
(function (root) {
  'use strict';

  var SLOP = 12;
  var TAP_MS = 300;
  var DOUBLE_MS = 250;

  // ---- pure helpers ----

  /** i = { connected, w, h, vw, vh, sw, sh, duration, loop, muted, inFs, fsIsVideo } */
  function isMainVideo(i) {
    if (!i || !i.connected || i.fsIsVideo) return false; // a fullscreen <video> cannot hold children
    if (!(i.w > 0 && i.h > 0 && i.vw > 0 && i.vh > 0)) return false;
    if (!(i.duration > 60 || i.duration === Infinity)) return false;
    if (i.loop || (i.muted && !i.inFs)) return false;
    if (i.w * i.h < 0.6 * i.vw * i.vh) return false;
    return !!i.inFs || i.vw * i.vh >= 0.6 * (i.sw || 0) * (i.sh || 0);
  }

  function fmt(t) {
    if (t === Infinity) return 'LIVE';
    if (!(t >= 0)) t = 0;
    t = Math.floor(t);
    var h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = t % 60;
    var ss = (s < 10 ? '0' : '') + s;
    return h ? h + ':' + (m < 10 ? '0' : '') + m + ':' + ss : m + ':' + ss;
  }

  /** 'left' | 'mid' | 'right' third of the width. */
  function zone(x, w) {
    var f = w > 0 ? x / w : 0.5;
    return f < 1 / 3 ? 'left' : f > 2 / 3 ? 'right' : 'mid';
  }

  /** Drag kind from the movement so far: 'scrub' (horizontal), 'vertical' (native swipe, ignored) or ''. */
  function dragKind(dx, dy) {
    var ax = Math.abs(dx), ay = Math.abs(dy);
    if (ax > SLOP && ax > ay * 2) return 'scrub';
    if (ay > SLOP && ay > ax * 1.5) return 'vertical';
    return '';
  }

  function isTap(dx, dy, dt) {
    return dt <= TAP_MS && Math.abs(dx) <= SLOP && Math.abs(dy) <= SLOP;
  }

  /** Seek target of a horizontal drag: the full width is 90 s (or 10% of the duration when that is longer); clamped. */
  function scrubTarget(cur, duration, dx, width) {
    if (!(duration > 0) || duration === Infinity || !(width > 0)) return cur;
    var span = Math.max(90, duration * 0.1);
    return Math.max(0, Math.min(duration, cur + (dx / width) * span));
  }

  var RATES = [0.5, 0.75, 1, 1.25, 1.5, 2];
  function nextRate(r) {
    for (var i = 0; i < RATES.length; i++) if (RATES[i] > r + 0.001) return RATES[i];
    return RATES[0];
  }

  var FITS = ['contain', 'cover', 'fill'];
  function nextFit(f) {
    var i = FITS.indexOf(f);
    return FITS[(i + 1) % FITS.length];
  }

  var api = { isMainVideo: isMainVideo, fmt: fmt, zone: zone, dragKind: dragKind, isTap: isTap, scrubTarget: scrubTarget, nextRate: nextRate, nextFit: nextFit };
  if (typeof module === 'object' && module && module.exports) { module.exports = api; return; }

  // ---- runtime ----
  var opts = typeof MOVY_HUD !== 'undefined' ? MOVY_HUD : null;
  if (!opts || !opts.enabled || root.__movyHud || !root.document) return;
  try { if (root.MovyAdblock && root.MovyAdblock.hudEnabled && !root.MovyAdblock.hudEnabled()) return; } catch (e) { /* default on */ }
  var doc = root.document;

  var video = null;     // current main video
  var host = null;      // our element
  var shadow = null;
  var ui = {};
  var visible = false;
  var locked = false;
  var siteMode = false;
  var siteTimer = 0, hideTimer = 0, lockTimer = 0, raf = 0, pollTimer = 0;
  var fit = 'contain';
  var showRemaining = false;
  var hideStyle = null;
  var seekAccum = 0, seekAccumAt = 0;

  var ICON = {
    play: 'M8 5v14l11-7z',
    pause: 'M6 5h4v14H6zM14 5h4v14h-4z',
    back10: 'M12 5V1L7 6l5 5V7a6 6 0 1 1-6 6H4a8 8 0 1 0 8-8z',
    fwd10: 'M12 5V1l5 5-5 5V7a6 6 0 1 0 6 6h2a8 8 0 1 1-8-8z',
    lock: 'M17 9h-1V7a4 4 0 0 0-8 0v2H7a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-8a2 2 0 0 0-2-2zM10 7a2 2 0 0 1 4 0v2h-4z',
    fs: 'M5 5h5v2H7v3H5zM14 5h5v5h-2V7h-3zM5 14h2v3h3v2H5zM17 14h2v5h-5v-2h3z',
    menu: 'M4 6h16v2H4zM4 11h16v2H4zM4 16h16v2H4z'
  };

  function svg(path) {
    return '<svg viewBox="0 0 24 24" width="28" height="28" fill="#fff"><path d="' + path + '"/></svg>';
  }

  var CSS = ':host{all:initial}' +
    '.layer{position:fixed;left:0;top:0;right:0;bottom:0;touch-action:none;user-select:none;-webkit-user-select:none;font:600 14px/1 system-ui,sans-serif;color:#fff;-webkit-tap-highlight-color:transparent}' +
    '.layer.site{pointer-events:none}' +
    '.fade{opacity:0;transition:opacity .15s;pointer-events:none}.fade.on{opacity:1}.fade.on [data-ctl]{pointer-events:auto}' +
    '.shade{position:absolute;left:0;right:0;bottom:0;height:45%;background:linear-gradient(transparent,rgba(0,0,0,.65))}' +
    '.center{position:absolute;left:0;right:0;top:50%;transform:translateY(-50%);display:flex;justify-content:center;align-items:center;gap:48px}' +
    'button{all:unset;box-sizing:border-box;min-width:44px;min-height:44px;display:flex;align-items:center;justify-content:center;border-radius:50%;cursor:pointer;padding:6px}' +
    'button.big{width:72px;height:72px;background:rgba(0,0,0,.5)}button.big svg{width:40px;height:40px}' +
    '.bottom{position:absolute;left:0;right:0;bottom:0;padding:0 16px 10px}' +
    '.bar{display:flex;align-items:center;gap:10px}.bar span{min-width:44px;text-align:center}' +
    '.track{position:relative;flex:1;height:44px;touch-action:none}' +
    '.rail{position:absolute;left:0;right:0;top:20px;height:4px;border-radius:2px;background:rgba(255,255,255,.3)}' +
    '.buf,.cur{position:absolute;left:0;top:0;bottom:0;border-radius:2px;width:0}.buf{background:rgba(255,255,255,.45)}.cur{background:#ff8800}' +
    '.knob{position:absolute;top:14px;width:16px;height:16px;margin-left:-8px;border-radius:50%;background:#ff8800}' +
    '.row{display:flex;justify-content:space-between;align-items:center;margin-top:2px}.row .grp{display:flex;gap:4px;align-items:center}' +
    'button.txt{border-radius:8px;padding:6px 10px;font:600 14px/1 system-ui,sans-serif;color:#fff}' +
    '.label{position:absolute;left:50%;top:22%;transform:translateX(-50%);padding:8px 14px;border-radius:8px;background:rgba(0,0,0,.7);font-size:20px;opacity:0;transition:opacity .15s;pointer-events:none}.label.on{opacity:1}' +
    '.unlock{position:absolute;left:50%;bottom:24px;transform:translateX(-50%);background:rgba(0,0,0,.6);border-radius:24px;padding:10px 18px;opacity:0;pointer-events:none;transition:opacity .15s}.unlock.on{opacity:1;pointer-events:auto}' +
    '.done{position:absolute;right:12px;top:50%;transform:translateY(-50%);background:rgba(0,0,0,.7);border-radius:24px;padding:10px 20px;display:none;pointer-events:auto}.layer.site .done{display:block}';

  function mk(tag, cls, parent, html) {
    var e = doc.createElement(tag);
    if (cls) e.className = cls;
    if (html) e.innerHTML = html;
    if (parent) parent.appendChild(e);
    return e;
  }

  function btn(parent, key, html, cls) {
    var b = mk('button', cls || '', parent, html);
    b.setAttribute('data-ctl', key);
    return b;
  }

  function build() {
    host = doc.createElement('div');
    host.setAttribute('data-movy-hud', '1');
    host.style.cssText = 'all:initial;position:fixed;left:0;top:0;width:0;height:0;z-index:2147483646';
    shadow = host.attachShadow({ mode: 'closed' });
    mk('style', '', shadow).textContent = CSS;
    var layer = ui.layer = mk('div', 'layer', shadow);
    ui.label = mk('div', 'label', layer);
    var fade = ui.fade = mk('div', 'fade layer', layer);
    fade.style.pointerEvents = 'none';
    mk('div', 'shade', fade);
    var center = mk('div', 'center', fade);
    btn(center, 'back', svg(ICON.back10));
    ui.play = btn(center, 'play', svg(ICON.play), 'big');
    btn(center, 'fwd', svg(ICON.fwd10));
    var bottom = mk('div', 'bottom', fade);
    var bar = mk('div', 'bar', bottom);
    ui.cur = mk('span', '', bar, '0:00');
    var track = ui.track = mk('div', 'track', bar);
    track.setAttribute('data-ctl', 'seek');
    mk('div', 'rail', track);
    ui.buf = mk('div', 'buf', track.firstChild);
    ui.fill = mk('div', 'cur', track.firstChild);
    ui.knob = mk('div', 'knob', track);
    ui.dur = mk('span', '', bar, '0:00');
    ui.dur.setAttribute('data-ctl', 'dur');
    var row = mk('div', 'row', bottom);
    var g1 = mk('div', 'grp', row), g2 = mk('div', 'grp', row);
    btn(g1, 'lock', svg(ICON.lock));
    ui.rate = btn(g1, 'rate', '1x', 'txt');
    ui.fit = btn(g1, 'fit', 'Fit', 'txt');
    btn(g2, 'fs', svg(ICON.fs));
    btn(g2, 'site', svg(ICON.menu) + '<span style="margin-left:6px">Subtitles · Quality · Servers</span>', 'txt');
    ui.unlock = mk('div', 'unlock', layer, 'Tap to unlock');
    ui.unlock.setAttribute('data-ctl', 'unlock');
    ui.done = mk('div', 'done', layer, 'Done');
    ui.done.setAttribute('data-ctl', 'done');
    wire(layer);
  }

  // ---- state helpers ----
  function fsEl() { return doc.fullscreenElement || doc.webkitFullscreenElement || null; }

  function placeHost() {
    if (!host) return;
    var p = fsEl() || doc.documentElement;
    if (p && p.tagName !== 'VIDEO' && host.parentNode !== p) p.appendChild(host);
  }

  function setVisible(on) {
    visible = on;
    clearTimeout(hideTimer);
    ui.fade.classList.toggle('on', on && !locked);
    if (on) {
      draw();
      if (video && !video.paused && !scrubbing) hideTimer = setTimeout(function () { setVisible(false); }, 4000);
    }
  }

  function flash(text) {
    ui.label.textContent = text;
    ui.label.classList.add('on');
    clearTimeout(flash.t);
    flash.t = setTimeout(function () { ui.label.classList.remove('on'); }, 700);
  }

  function draw() {
    if (!video || !visible) return;
    var d = video.duration, c = video.currentTime;
    if (ui.play.__paused !== video.paused) { // only on change: replacing the icon under a finger loses the tap
      ui.play.__paused = video.paused;
      ui.play.firstChild.firstChild.setAttribute('d', video.paused ? ICON.play : ICON.pause);
    }
    ui.cur.textContent = fmt(c);
    ui.dur.textContent = d === Infinity ? 'LIVE' : showRemaining && d > 0 ? '-' + fmt(d - c) : fmt(d);
    var f = d > 0 && d !== Infinity ? Math.min(1, c / d) : 0;
    ui.fill.style.width = f * 100 + '%';
    ui.knob.style.left = f * 100 + '%';
    var b = 0;
    try { for (var i = 0; i < video.buffered.length; i++) if (video.buffered.start(i) <= c && video.buffered.end(i) >= c) b = video.buffered.end(i); } catch (e) { /* none */ }
    ui.buf.style.width = (d > 0 && d !== Infinity ? Math.min(1, b / d) : 0) * 100 + '%';
    ui.rate.textContent = (video.playbackRate || 1) + 'x';
    ui.fit.textContent = fit === 'contain' ? 'Fit' : fit === 'cover' ? 'Fill' : 'Stretch';
  }

  function loop() {
    raf = 0;
    if (!visible || !video) return;
    draw();
    raf = root.requestAnimationFrame(loop);
  }
  function kick() { if (!raf && visible) raf = root.requestAnimationFrame(loop); }

  function seekBy(sec) {
    if (!video || !(video.duration > 0)) return;
    video.currentTime = Math.max(0, Math.min(video.duration, video.currentTime + sec));
  }
  function toggle() { if (!video) return; if (video.paused) { var p = video.play(); if (p && p.catch) p.catch(function () {}); } else video.pause(); }

  // ---- gestures ----
  var down = null, scrubbing = false, lastTap = null, tapSeq = 0;

  function ctl(e) {
    var n = e.target;
    while (n && n !== ui.layer) { if (n.getAttribute && n.getAttribute('data-ctl')) return n; n = n.parentNode; }
    return null;
  }

  function setScrub(on) { scrubbing = on; root.__movyHudScrub = on; }

  function wire(layer) {
    ['click', 'dblclick', 'contextmenu', 'mousedown', 'touchstart'].forEach(function (t) {
      layer.addEventListener(t, function (e) { if (!siteMode) { e.stopPropagation(); if (t !== 'touchstart') e.preventDefault(); } }, false);
    });
    layer.addEventListener('pointerdown', function (e) {
      if (!video) return;
      e.stopPropagation();
      var c = ctl(e);
      if (locked && !(c && c.getAttribute('data-ctl') === 'unlock')) { showUnlock(); return; }
      if (c) { down = { ctl: c, id: e.pointerId, x: e.clientX, y: e.clientY }; if (c.getAttribute('data-ctl') === 'seek') { startSeekDrag(e); } return; }
      down = { x: e.clientX, y: e.clientY, t: Date.now(), id: e.pointerId, start: video.currentTime, kind: '' };
    });
    layer.addEventListener('pointermove', function (e) {
      if (!down || down.ctl || e.pointerId !== down.id) return;
      var dx = e.clientX - down.x, dy = e.clientY - down.y;
      if (!down.kind) down.kind = dragKind(dx, dy);
      if (down.kind === 'scrub' && video.duration > 0 && video.duration !== Infinity) {
        setScrub(true);
        setVisible(true);
        var t = scrubTarget(down.start, video.duration, dx, root.innerWidth);
        down.target = t;
        flash(fmt(t) + '  (' + (t >= down.start ? '+' : '-') + fmt(Math.abs(t - down.start)) + ')');
        video.currentTime = t;
      }
    });
    layer.addEventListener('pointerup', function (e) {
      var d = down;
      down = null;
      if (!d || e.pointerId !== d.id) return;
      e.stopPropagation();
      if (d.ctl) { press(d.ctl.getAttribute('data-ctl')); return; }
      if (scrubbing) { setScrub(false); setVisible(true); return; }
      if (d.kind === 'vertical' || !isTap(e.clientX - d.x, e.clientY - d.y, Date.now() - d.t)) return;
      onTap(e.clientX);
    });
    layer.addEventListener('pointercancel', function () { down = null; if (scrubbing) setScrub(false); });
  }

  function startSeekDrag(e) {
    var track = ui.track;
    var move = function (ev) {
      var r = track.getBoundingClientRect();
      var f = Math.max(0, Math.min(1, (ev.clientX - r.left) / Math.max(1, r.width)));
      if (video.duration > 0 && video.duration !== Infinity) { setScrub(true); video.currentTime = f * video.duration; flash(fmt(video.currentTime)); draw(); }
    };
    var up = function () {
      root.removeEventListener('pointermove', move, true);
      root.removeEventListener('pointerup', up, true);
      root.removeEventListener('pointercancel', up, true);
      setScrub(false);
      down = null;
      setVisible(true);
    };
    root.addEventListener('pointermove', move, true);
    root.addEventListener('pointerup', up, true);
    root.addEventListener('pointercancel', up, true);
    move(e);
  }

  function onTap(x) {
    var now = Date.now();
    if (lastTap && now - lastTap.t <= DOUBLE_MS) {
      var wasVisible = lastTap.wasVisible;
      lastTap = null;
      setVisible(wasVisible); // the first tap of the pair toggled the HUD: undo it
      var z = zone(x, root.innerWidth);
      if (z === 'mid') { toggle(); setVisible(true); return; }
      if (now - seekAccumAt > 800) seekAccum = 0;
      seekAccumAt = now;
      seekAccum += z === 'left' ? -10 : 10;
      seekBy(z === 'left' ? -10 : 10);
      flash((seekAccum > 0 ? '+' : '') + seekAccum + ' s');
      return;
    }
    lastTap = { t: now, wasVisible: visible };
    setVisible(!visible);
  }

  function showUnlock() {
    ui.unlock.classList.add('on');
    clearTimeout(lockTimer);
    lockTimer = setTimeout(function () { ui.unlock.classList.remove('on'); }, 2000);
  }

  function setLocked(on) {
    locked = on;
    ui.unlock.classList.remove('on');
    if (on) { setVisible(false); showUnlock(); } else setVisible(true);
  }

  function press(k) {
    if (!video) return;
    if (k === 'play') toggle();
    else if (k === 'back') seekBy(-10);
    else if (k === 'fwd') seekBy(10);
    else if (k === 'lock') { setLocked(true); return; }
    else if (k === 'unlock') { setLocked(false); return; }
    else if (k === 'rate') video.playbackRate = nextRate(video.playbackRate || 1);
    else if (k === 'fit') { fit = nextFit(fit); applyFit(); }
    else if (k === 'dur') showRemaining = !showRemaining;
    else if (k === 'fs') toggleFullscreen();
    else if (k === 'site') { enterSite(); return; }
    else if (k === 'done') { exitSite(); return; }
    setVisible(true);
  }

  function applyFit() { if (video) video.style.setProperty('object-fit', fit, 'important'); }

  // ---- fullscreen ----
  function pointerClick(el) {
    ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function (t) {
      var Ctor = t.indexOf('pointer') === 0 && root.PointerEvent ? root.PointerEvent : root.MouseEvent;
      el.dispatchEvent(new Ctor(t, { bubbles: true, cancelable: true, view: root, button: 0 }));
    });
  }

  function toggleFullscreen() {
    if (fsEl()) { (doc.exitFullscreen || doc.webkitExitFullscreen).call(doc); return; }
    var sels = opts.fs || [];
    for (var i = 0; i < sels.length; i++) {
      var b = null;
      try { b = doc.querySelector(sels[i]); } catch (e) { /* bad selector */ }
      if (b) { pointerClick(b); return; }
    }
    var r = video.getBoundingClientRect(), el = video, p = video.parentElement;
    while (p && p !== doc.body && p !== doc.documentElement) {
      var pr = p.getBoundingClientRect();
      if (Math.abs(pr.width - r.width) > 2 || Math.abs(pr.height - r.height) > 2) break;
      el = p; p = p.parentElement;
    }
    if (el === video) el = doc.documentElement; // a fullscreen <video> cannot show our layer
    var req = el.requestFullscreen || el.webkitRequestFullscreen;
    if (req) { try { var pr2 = req.call(el); if (pr2 && pr2.catch) pr2.catch(function () {}); } catch (e) { /* refused */ } }
  }

  // ---- hide the site's own controls while ours are up ----
  // Two HUDs on one picture is the failure this avoids. Profile selectors (opts.hide) cover known bars; everything else is
  // found generically: any interactive element drawn over the picture (and the wrapper it sits in) and any touch-catching
  // cover at the picture's centre is marked and hidden. Subtitle displays are spared. A site menu frame above a player
  // iframe has no <video> of its own, so the video frame asks its ancestors (postMessage) to hide their covers too.
  var MARK = 'data-movy-hid';
  var INTERACTIVE = 'button,[role=button],[role=slider],input,select,a[href],[aria-label],[tabindex]';
  var SUBSEL = '[class*="sub" i],[class*="caption" i],[class*="cue" i],[id*="sub" i],[id*="caption" i]';
  var hideTarget = null;   // the <video> (video frame) or the player <iframe> (ancestor frame)
  var relayFrame = null;   // ancestor frame: the iframe that asked
  var scanTimer = 0;

  function installHide() {
    if (hideStyle) return;
    var rules = ['html.movy-hud-on [' + MARK + ']'];
    var list = opts.hide || [];
    for (var i = 0; i < list.length; i++) {
      try { doc.querySelector(list[i]); rules.push('html.movy-hud-on ' + list[i]); } catch (e) { /* skip */ }
    }
    hideStyle = doc.createElement('style');
    hideStyle.textContent = rules.join(',\n') + '{opacity:0!important;pointer-events:none!important}';
    (doc.head || doc.documentElement).appendChild(hideStyle);
  }

  function subtitleEls() {
    var out = [], all;
    try { all = doc.querySelectorAll(SUBSEL); } catch (e) { return out; }
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      // a subtitle display holds text only; a subtitles button or menu is a control
      if (e.matches(INTERACTIVE) || e.closest('button,[role=button],a') || e.querySelector(INTERACTIVE)) continue;
      out.push(e);
    }
    return out;
  }

  function scanHide() {
    var target = hideTarget;
    if (!target || !target.isConnected) return;
    var tr = target.getBoundingClientRect();
    if (!(tr.width > 0 && tr.height > 0)) return;
    var subs = subtitleEls();
    var holdsSubs = function (el) { for (var i = 0; i < subs.length; i++) if (el === subs[i] || el.contains(subs[i])) return true; return false; };
    var spare = function (el) { return el === host || el === target || el.contains(target) || el === doc.body || el === doc.documentElement; };
    var mark = function (el) {
      if (spare(el) || el.hasAttribute('data-movy-hud') || el.closest('[' + MARK + ']')) return;
      var top = el; // the whole bar, not just the button: climb while the wrapper holds neither the picture nor subtitles
      // (and is not a page-sized wrapper: only things the size of the player are bars)
      while (top.parentElement && !spare(top.parentElement) && !holdsSubs(top.parentElement)
          && top.parentElement.getBoundingClientRect().height <= root.innerHeight * 1.5) top = top.parentElement;
      top.setAttribute(MARK, '1');
    };
    var list = doc.querySelectorAll(INTERACTIVE);
    for (var i = 0; i < list.length; i++) {
      var r = list[i].getBoundingClientRect();
      if (r.width > 0 && r.height > 0 && r.right > tr.left && r.left < tr.right && r.bottom > tr.top && r.top < tr.bottom) mark(list[i]);
    }
    // covers that would eat the touch before it reaches the picture
    var stack = doc.elementsFromPoint ? doc.elementsFromPoint(tr.left + tr.width / 2, tr.top + tr.height / 2) : [];
    for (var j = 0; j < stack.length; j++) {
      if (stack[j] === target || stack[j].contains(target)) break;
      if (stack[j] !== host && !holdsSubs(stack[j])) mark(stack[j]);
    }
  }

  function unmark() {
    var m = doc.querySelectorAll('[' + MARK + ']');
    for (var i = 0; i < m.length; i++) m[i].removeAttribute(MARK);
  }

  function tellParent(what) {
    if (root.parent !== root) { try { root.parent.postMessage({ __movyHud: what }, '*'); } catch (e) { /* detached */ } }
  }

  function hideLocal(on, target) {
    clearInterval(scanTimer); scanTimer = 0;
    if (on) {
      hideTarget = target;
      installHide();
      scanHide();
      scanTimer = setInterval(scanHide, 1000); // frameworks re-render their bars
    } else {
      hideTarget = null;
      unmark();
    }
    doc.documentElement.classList.toggle('movy-hud-on', on);
  }

  // Same recipe as the TV adapter: a synthetic mouse move on a neutral spot of the picture (centre, 38% down). Sites hide
  // their bars (and close their menus) after a second or two without one, so it repeats while the site menu is wanted.
  var wakeTimer = 0;
  function wakeSite() {
    var t0 = video || relayFrame;
    var r = t0 && t0.isConnected ? t0.getBoundingClientRect() : { left: 0, top: 0, width: root.innerWidth, height: root.innerHeight };
    var x = r.left + r.width / 2, y = r.top + r.height * 0.38;
    var t = doc.elementFromPoint(x, y) || t0;
    if (!t) return;
    ['mouseover', 'mousemove', 'pointermove'].forEach(function (n, i) {
      var Ctor = n === 'pointermove' && root.PointerEvent ? root.PointerEvent : root.MouseEvent;
      try { t.dispatchEvent(new Ctor(n, { bubbles: true, cancelable: true, view: root, clientX: x + (i % 2), clientY: y })); } catch (e) { /* ignore */ }
    });
  }
  function keepAwake(on) {
    clearInterval(wakeTimer); wakeTimer = 0;
    if (on) { wakeSite(); wakeTimer = setInterval(wakeSite, 1500); }
  }

  /** what: 'on' (our controls), 'site' (site menu: show and wake the site's bars), 'off' (no main video). */
  function setHide(what) {
    hideLocal(what === 'on', video);
    keepAwake(what === 'site');
    tellParent(what);
  }

  // ancestor frames: a player frame below asks for the same treatment of whatever this document draws over it
  root.addEventListener('message', function (e) {
    var what = e.data && e.data.__movyHud;
    if (!what || !e.source) return;
    if (what === 'touch') { touchSite(); return; }
    var frames = doc.querySelectorAll('iframe'), f = null;
    for (var i = 0; i < frames.length; i++) if (frames[i].contentWindow === e.source) f = frames[i];
    if (!f || video) return;
    relayFrame = what === 'off' ? null : f;
    hideLocal(what === 'on', f);
    keepAwake(what === 'site');
    tellParent(what);
  });
  root.addEventListener('pointerdown', function (e) {
    if (relayFrame && relayFrame.contentWindow) { try { relayFrame.contentWindow.postMessage({ __movyHud: 'touch' }, '*'); } catch (x) { /* gone */ } }
    if (siteMode && !(e.composedPath && e.composedPath()[0] === ui.done)) touchSite();
  }, true);

  // ---- site mode ----
  function enterSite() {
    siteMode = true;
    setVisible(false);
    ui.layer.classList.add('site');
    setHide('site');
    touchSite();
  }
  function touchSite() { clearTimeout(siteTimer); if (siteMode) siteTimer = setTimeout(exitSite, 8000); }
  function exitSite() {
    siteMode = false;
    clearTimeout(siteTimer);
    ui.layer.classList.remove('site');
    setHide('on');
    setVisible(true);
  }

  // ---- candidate tracking ----
  function infoOf(v) {
    var r = v.getBoundingClientRect(), f = fsEl();
    return { connected: v.isConnected, w: r.width, h: r.height, vw: root.innerWidth, vh: root.innerHeight,
      sw: root.screen.width, sh: root.screen.height, duration: v.duration, loop: v.loop, muted: v.muted,
      inFs: !!(f && (f === v || f.contains(v))), fsIsVideo: !!(f && f.tagName === 'VIDEO') };
  }

  function pick() {
    var vs = doc.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++) if (isMainVideo(infoOf(vs[i]))) return vs[i];
    return null;
  }

  function detach() {
    if (!video) return;
    video = null;
    siteMode = false; locked = false;
    setScrub(false);
    clearTimeout(siteTimer); clearTimeout(hideTimer);
    if (host && host.parentNode) host.parentNode.removeChild(host);
    setHide('off');
    stopPoll();
  }

  function attach(v) {
    if (!host) build();
    video = v;
    fit = 'contain';
    placeHost();
    setHide('on');
    ui.layer.classList.remove('site');
    setVisible(false);
  }

  function evaluate() {
    var v = pick();
    if (v === video) { if (v) placeHost(); return; }
    if (video) detach();
    if (v) { attach(v); startPoll(); }
  }

  function startPoll() { if (!pollTimer) pollTimer = setInterval(evaluate, 1000); }
  function stopPoll() { clearInterval(pollTimer); pollTimer = 0; }

  ['play', 'playing', 'loadedmetadata', 'durationchange', 'emptied'].forEach(function (t) { doc.addEventListener(t, evaluate, true); });
  ['pause', 'play', 'timeupdate', 'seeked', 'ratechange', 'volumechange'].forEach(function (t) {
    doc.addEventListener(t, function (e) {
      if (e.target !== video) return;
      if (t === 'play' || t === 'pause') setVisible(visible);
      kick();
    }, true);
  });
  doc.addEventListener('fullscreenchange', function () { placeHost(); evaluate(); }, false);
  doc.addEventListener('webkitfullscreenchange', function () { placeHost(); evaluate(); }, false);
  root.addEventListener('resize', evaluate);
  // videos are usually created after document start: look again while the page settles
  var warm = setInterval(function () { evaluate(); }, 1500);
  setTimeout(function () { clearInterval(warm); }, 30000);

  /** For MainActivity's BACK key: true when the key was consumed (unlock / leave site mode). */
  root.__movyHud = {
    back: function () {
      if (locked) { setLocked(false); return true; }
      if (siteMode) { exitSite(); return true; }
      return false;
    }
  };
})(typeof window !== 'undefined' ? window : {});
