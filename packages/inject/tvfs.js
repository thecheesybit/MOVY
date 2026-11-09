// Movy TV: profile CSS, the idle mode, the fullscreen policy, the media API and the layout width.
// Concatenated after tvnav-pure.js and tvnav.js (needs window.__movyTv). tv flavor only, every frame.
// - Idle: native calls __movyTv.idle(true|false); `html.movy-idle` hides profile header/bottomBar/player controls, the ring, the cursor.
// - Fullscreen: Play (OK on a profile playButton, or the first `playing` after a user key) goes fullscreen on the profile
//   player.fullscreenTarget, stays fullscreen until BACK (re-entered after a swap), and the site's own buttons are hidden.
// - Media: __movyTv.media(action, secs) and __movyTv.mediaStatus() (polled by the native player bar later).
// - Layout width: top frame forces <meta viewport width=profile.viewportWidth> (default 1280).
(function (root) {
  'use strict';
  var TV = root.__movyTv;
  var P = root.__movyTvPure;
  if (!TV || !P || TV.fsLoaded) return;
  TV.fsLoaded = true;

  var doc = document;
  var inFrame = root.parent !== root;
  var GENERIC_CONTROLS = '.vjs-control-bar,.jw-controlbar,.plyr__controls,.shaka-controls-container,[class*="controlbar" i],[class*="control-bar" i]';
  var bubbleTimer = 0;
  var lastSeekAt = 0;
  var lastKeyAt = 0;
  var wantFs = false;
  var userExitAt = 0;
  var WANTFS_KEY = '__movy_tv_wantfs';
  var WANTFS_TTL_MS = 90000;

  /** Fullscreen is wanted across document loads: a server switch or an embed reload replaces the page and used to drop it. */
  function rememberWantFs(on) {
    try { if (on) root.sessionStorage.setItem(WANTFS_KEY, String(Date.now())); else root.sessionStorage.removeItem(WANTFS_KEY); } catch (e) { /* storage blocked */ }
  }
  function recallWantFs() {
    try { var t = parseInt(root.sessionStorage.getItem(WANTFS_KEY) || '0', 10); return t > 0 && Date.now() - t < WANTFS_TTL_MS; } catch (e) { return false; }
  }
  if (!inFrame && recallWantFs()) wantFs = true; // the next `playing` re-enters fullscreen (a user BACK clears this)
  var fsTries = 0;
  var fsTimer = 0;
  var idleOn = false;
  var statusAt = 0;

  function fsElement() { return doc.fullscreenElement || doc.webkitFullscreenElement || null; }
  function up(msg) { try { root.parent.postMessage(msg, '*'); } catch (e) { /* ignore */ } }

  // ---------- profile CSS ----------

  function styleEl() {
    var st = doc.getElementById('__movy_tv_profile_style');
    if (!st) {
      st = doc.createElement('style');
      st.id = '__movy_tv_profile_style';
      (doc.head || doc.documentElement).appendChild(st);
    }
    return st;
  }

  function applyCss(PF) {
    var css = '';
    var hide = function (sel) { return sel ? sel + '{display:none!important}' : ''; };
    css += hide(PF.player.fullscreenButton) + hide(PF.player.overlayKill);
    var fadeWith = function (cls, sels) {
      return sels.filter(Boolean).map(function (s) { return 'html.' + cls + ' ' + s.split(',').join(',html.' + cls + ' '); }).join(',');
    };
    var FADE = '{opacity:0!important;pointer-events:none!important;transition:opacity .25s ease-out!important}';
    // Page chrome fades with the shell's 5 s idle; the player's own controls have a separate 5 s timer (see armControlsHide).
    css += fadeWith('movy-idle', [PF.header, PF.bottomBar]) + FADE;
    css += fadeWith('movy-pctl-off', [PF.player.controls, PF.player.hide, GENERIC_CONTROLS]) + FADE;
    // Subtitle boxes sit above the bar while it is up; when the bar fades they drop back to the bottom of the picture.
    if (PF.player.subtitles) css += 'html.movy-pctl-off ' + PF.player.subtitles.split(',').join(',html.movy-pctl-off ') + '{bottom:6%!important;transition:bottom .25s ease-out}';
    css += 'html.movy-idle,html.movy-idle *{cursor:none!important}';
    css += 'html.movy-idle .movy-tvf,html.movy-idle :focus{outline-color:transparent!important;box-shadow:none!important;scale:1!important}';
    css += 'html.movy-idle #__movy_tv_bubble{opacity:0!important}';
    css += ':fullscreen:not(video),:-webkit-full-screen:not(video){width:100%!important;height:100%!important}';
    try { styleEl().textContent = css; } catch (e) { /* head not ready yet: retried on DOMContentLoaded */ }
  }

  // ---------- idle ----------

  // ---------- player controls auto-hide (5 s without a key; video only) ----------

  var CONTROLS_HIDE_MS = 5000;
  var controlsTimer = 0;

  var wakeTimer = 0, lastWakeAt = 0;

  /** A player is up: fullscreen, or (not fullscreen) a player iframe covering the page, whose controls live in a frame. */
  function playerUp() {
    return !!fsElement() || !!(TV.PF && TV.bigIframe && TV.bigIframe());
  }

  /** The video of this document, or the status of the one inside a player frame. */
  function videoPlaying() {
    var v = findVideo();
    if (v && isMainVideo(v)) return !v.paused;
    return !!(TV.lastStatus && !TV.lastStatus.paused); // the real video is inside a player frame
  }

  function setPctlOff(off) {
    var h = doc.documentElement;
    if (h) h.classList.toggle('movy-pctl-off', off);
    TV.relayDown({ __movyTvPctl: off ? 1 : 0 }); // the player frames fade and wake with the page chrome: one idle state
  }

  function showControls() {
    clearTimeout(controlsTimer);
    clearInterval(wakeTimer);
    wakeTimer = 0;
    var h = doc.documentElement;
    if (h && h.classList.contains('movy-pctl-off')) setPctlOff(false);
  }

  function menuUp() {
    return !!TV.menuNow() || !!(TV.childAgg && TV.childAgg.menu);
  }

  function hideControls() {
    if (inFrame || !playerUp() || !videoPlaying() || menuUp()) return; // paused or a menu is open: leave them up
    TV.releaseFocus();
    setPctlOff(true);
    hideBubble();
  }

  /** (Re)starts the countdown; called on every key, on playback start and when a menu closes. */
  function armControlsHide() {
    clearTimeout(controlsTimer);
    if (inFrame || !playerUp()) return;
    controlsTimer = setTimeout(function () {
      clearInterval(wakeTimer);
      wakeTimer = 0;
      if (menuUp()) armControlsHide(); else hideControls();
    }, CONTROLS_HIDE_MS);
    if (!wakeTimer) {
      // The site's own bar hides (and closes its menus) on mouse inactivity, usually sooner than 5 s: keep it up until ours fires.
      wakeTimer = setInterval(function () { var v = findVideo(); if (v && fsElement()) wakeControls(v); }, 1500);
    }
  }
  TV.showControls = function () {
    showControls();
    armControlsHide();
    if (!inFrame && TV.PF && TV.bigIframe && TV.bigIframe()) TV.relayDown({ __movyTvWake: 1 }); // the player frame wakes its own bar too
  };

  TV.idle = function (on) {
    idleOn = !!on;
    var h = doc.documentElement;
    if (!on) TV.showControls();
    if (h) { if (on) h.classList.add('movy-idle'); else h.classList.remove('movy-idle'); }
    if (on) hideBubble();
    TV.relayDown({ __movyTvIdle: idleOn });
    return idleOn;
  };
  TV.isIdle = function () { return idleOn; };

  // ---------- killOnTv ----------

  var killed = new WeakSet();
  function killHeavy(PF) {
    var sels = PF.killOnTv || [];
    for (var i = 0; i < sels.length; i++) {
      if (!TV.okSel(sels[i])) continue;
      var nodes = doc.querySelectorAll(sels[i]);
      for (var j = 0; j < nodes.length; j++) {
        var el = nodes[j];
        if (killed.has(el)) continue;
        killed.add(el);
        try {
          if (el.tagName === 'IFRAME') { el.setAttribute('data-movy-killed', el.getAttribute('src') || ''); el.src = 'about:blank'; }
          else if (el.tagName === 'VIDEO') { el.pause(); el.removeAttribute('autoplay'); el.removeAttribute('src'); el.load(); }
          el.style.setProperty('display', 'none', 'important');
        } catch (e) { /* ignore */ }
      }
    }
  }

  // ---------- layout width ----------

  (function enforceViewportInvariant() {
    if (inFrame) return;
    try {
      var origW = Object.getOwnPropertyDescriptor(root, 'innerWidth');
      var getW = origW && origW.get ? origW.get : function () { return root.innerWidth; };
      Object.defineProperty(root, 'innerWidth', {
        configurable: true,
        enumerable: true,
        get: function () {
          var target = (TV.PF && TV.PF.viewportWidth) || 1280;
          if (doc.fullscreenElement || doc.webkitFullscreenElement) return target;
          var w = getW.call(root);
          return (w <= 1000) ? target : w;
        }
      });
      var origH = Object.getOwnPropertyDescriptor(root, 'innerHeight');
      var getH = origH && origH.get ? origH.get : function () { return root.innerHeight; };
      Object.defineProperty(root, 'innerHeight', {
        configurable: true,
        enumerable: true,
        get: function () {
          if (doc.fullscreenElement || doc.webkitFullscreenElement) return 720;
          var h = getH.call(root);
          return (h <= 600) ? 720 : h;
        }
      });
    } catch (e) {}
  })();

  var viewportWatch = null;
  // Diagnostics: every layout-width change with its time and the viewport meta in force (TV.vpLog, read via movy_eval).
  TV.vpLog = [];
  (function () {
    var last = 0;
    function note(why) {
      var w = root.innerWidth;
      if (w === last && why !== 'meta') return;
      last = w;
      var m = doc.querySelector && doc.querySelector('meta[name=viewport]');
      if (TV.vpLog.length < 60) TV.vpLog.push(Math.round(root.performance.now()) + ':' + why + ':' + w + ':' + (m ? m.getAttribute('content') : 'none'));
    }
    root.addEventListener('resize', function () { note('resize'); });
    note('start');
  })();

  function applyViewport(PF) {
    if (inFrame) return;
    var w = PF.viewportWidth || 1280;
    var content = 'width=' + w;
    var set = function () {
      var all = doc.querySelectorAll('meta[name=viewport]');
      for (var i = 0; i < all.length; i++) if (all[i].getAttribute('content') !== content) all[i].setAttribute('content', content); // the last one wins
      if (all.length) return true;
      if (!doc.head) return false;
      var m = doc.createElement('meta');
      m.name = 'viewport';
      m.content = content;
      doc.head.insertBefore(m, doc.head.firstChild);
      return true;
    };
    TV.viewportContent = content;
    guardViewportWrites();
    set();
    if (!viewportWatch && doc.documentElement) {
      // Synchronous (a MutationObserver callback is a microtask: it runs before the next layout, so a site that re-inserts
      // its own viewport meta never gets a frame at its own width). Bounded to 40 fixes per second against a ping-pong.
      var fixes = 0, windowStart = Date.now();
      viewportWatch = new MutationObserver(function () {
        var now = Date.now();
        if (now - windowStart > 1000) { windowStart = now; fixes = 0; }
        if (fixes++ > 40) return;
        set();
      });
      viewportWatch.observe(doc.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name'] });
    }
  }

  /** Writes to a viewport meta (setAttribute or .content) are rewritten to ours before they ever reach layout. */
  function guardViewportWrites() {
    if (TV.vpGuarded || inFrame) return;
    TV.vpGuarded = true;
    try {
      var isVp = function (el) { return el && el.tagName === 'META' && String(el.getAttribute('name') || '').toLowerCase() === 'viewport'; };
      var origSet = Element.prototype.setAttribute;
      Element.prototype.setAttribute = function (n, v) {
        if (TV.viewportContent && isVp(this) && String(n).toLowerCase() === 'content') v = TV.viewportContent;
        return origSet.call(this, n, v);
      };
      var d = Object.getOwnPropertyDescriptor(HTMLMetaElement.prototype, 'content');
      if (d && d.set) {
        Object.defineProperty(HTMLMetaElement.prototype, 'content', {
          configurable: true, enumerable: d.enumerable, get: d.get,
          set: function (v) { d.set.call(this, TV.viewportContent && isVp(this) ? TV.viewportContent : v); }
        });
      }
    } catch (e) { /* the observer is the fallback */ }
  }

  // ---------- boot veil ----------
  // The first paint of a top-level page waits for the layout width to be right (and the profile CSS to be in), so there is
  // no zoom jump or unstyled flash. Hard cap 2.5 s; the native splash is still up over this anyway.

  TV.veilLifted = false;
  TV.firstFocusDone = false;
  TV.readySignaled = false;
  TV.bootStartAt = Date.now();
  TV.signalReady = function () {
    if (inFrame || TV.readySignaled) return;
    if (TV.veilLifted && (TV.firstFocusDone || Date.now() - TV.bootStartAt > 2000)) {
      TV.readySignaled = true;
      try {
        if (root.MovyTvNative && root.MovyTvNative.ready) {
          root.MovyTvNative.ready();
        }
      } catch (e) {}
    }
  };

  (function bootVeil() {
    if (inFrame) return;
    var t0 = Date.now(), done = false, style = null;
    function lift() {
      if (done) return;
      done = true;
      try { if (style && style.parentNode) style.parentNode.removeChild(style); } catch (e) { /* ignore */ }
      TV.veilLifted = true;
      TV.signalReady();
    }
    function drop() {
      var de = doc.documentElement;
      if (!de) { setTimeout(drop, 5); return; }
      style = doc.createElement('style');
      style.id = '__movy_veil';
      style.textContent = 'html{background:#000!important}body{visibility:hidden!important}';
      de.appendChild(style);
      check();
    }
    function ready() {
      var want = parseInt(String(TV.viewportContent || '').replace('width=', ''), 10) || 0;
      return want && Math.abs(root.innerWidth - want) <= 2 && doc.readyState !== 'loading' && !!doc.body;
    }
    function check() {
      if (done) return;
      if (ready() || Date.now() - t0 > 2500) {
        // two frames: the layout at the right width has been committed before the page becomes visible
        root.requestAnimationFrame(function () { root.requestAnimationFrame(lift); });
        return;
      }
      setTimeout(check, 30);
    }
    drop();
  })();

  TV.hooks.profile = function (PF) {
    applyCss(PF);
    killHeavy(PF);
    applyViewport(PF);
  };

  // ---------- media ----------

  function findVideo() {
    var fs = fsElement();
    if (fs) {
      if (fs.tagName === 'VIDEO') return fs;
      var v = fs.querySelector('video');
      if (v) return v;
    }
    var PF = TV.PF;
    if (PF && PF.player.video && TV.okSel(PF.player.video)) {
      var pv = doc.querySelector(PF.player.video);
      if (pv) return pv;
    }
    var vids = doc.getElementsByTagName('video');
    var best = null, bestArea = 0;
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect();
      var area = r.width * r.height;
      if (!vids[i].paused) area *= 4;
      if (area > bestArea && r.width > 80 && r.height > 60) { best = vids[i]; bestArea = area; }
    }
    return best;
  }

  /** Whether a <video> is the main player rather than decorative media (hero trailer, preview). */
  function isMainVideo(v) {
    var PF = TV.PF;
    if (PF && PF.player.video && TV.okSel(PF.player.video) && v.matches(PF.player.video)) return true;
    var r = v.getBoundingClientRect();
    if (inFrame) return r.width * r.height >= root.innerWidth * root.innerHeight * 0.4;
    return !(v.muted && v.loop) && r.width * r.height >= root.innerWidth * root.innerHeight * 0.5;
  }

  function hideBubble() {
    var b = doc.getElementById('__movy_tv_bubble');
    if (b && b.parentNode) b.parentNode.removeChild(b);
  }

  /** Interface icons for the playback popup: one solid rounded set, same 24 grid, no emoji or keyboard glyphs. */
  var BUBBLE_ICONS = {
    play: '<path d="M8 5.6v12.8a1 1 0 0 0 1.5.86l10.4-6.4a1 1 0 0 0 0-1.72L9.5 4.74A1 1 0 0 0 8 5.6z"/>',
    pause: '<rect x="6" y="4.5" width="4.2" height="15" rx="1.3"/><rect x="13.8" y="4.5" width="4.2" height="15" rx="1.3"/>',
    fwd: '<path d="M4 6.2v11.6a.9.9 0 0 0 1.4.75l7.2-5.8a.95.95 0 0 0 0-1.5L5.4 5.45A.9.9 0 0 0 4 6.2z"/><path d="M12.6 6.2v11.6a.9.9 0 0 0 1.4.75l7.2-5.8a.95.95 0 0 0 0-1.5L14 5.45a.9.9 0 0 0-1.4.75z"/>',
    back: '<path d="M20 6.2v11.6a.9.9 0 0 1-1.4.75l-7.2-5.8a.95.95 0 0 1 0-1.5l7.2-5.8A.9.9 0 0 1 20 6.2z"/><path d="M11.4 6.2v11.6a.9.9 0 0 1-1.4.75l-7.2-5.8a.95.95 0 0 1 0-1.5L10 5.45a.9.9 0 0 1 1.4.75z"/>'
  };
  function bubbleHtml(icon, text) {
    var safe = String(text).replace(/[&<>]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]; });
    return '<span style="display:inline-flex;align-items:center;gap:14px"><svg viewBox="0 0 24 24" width="34" height="34" fill="currentColor" aria-hidden="true">' +
      (BUBBLE_ICONS[icon] || '') + '</svg><span>' + safe + '</span></span>';
  }

  function bubble(text, icon) {
    var host = fsElement() || doc.documentElement;
    var b = doc.getElementById('__movy_tv_bubble');
    if (!b) {
      b = doc.createElement('div');
      b.id = '__movy_tv_bubble';
      b.style.cssText = 'position:fixed;left:50%;bottom:12%;transform:translateX(-50%);padding:12px 26px;border-radius:999px;' +
        'background:rgba(20,20,22,.88);color:#fff;font:600 26px/1.2 system-ui,sans-serif;letter-spacing:-.01em;' +
        'z-index:2147483647;pointer-events:none;box-shadow:0 6px 24px rgba(0,0,0,.5);white-space:nowrap';
    }
    if (b.parentNode !== host) host.appendChild(b);
    if (icon) b.innerHTML = bubbleHtml(icon, text); else b.textContent = text;
    b.style.opacity = '1';
    clearTimeout(bubbleTimer);
    bubbleTimer = setTimeout(function () { b.style.opacity = '0'; setTimeout(function () { if (b.parentNode) b.parentNode.removeChild(b); }, 200); }, 1100);
  }

  function seek(v, delta) {
    var t = P.seekTarget(v.currentTime, v.duration, delta);
    try { v.currentTime = t; } catch (e) { return; }
    bubble((delta > 0 ? '+' : '−') + Math.abs(delta) + 's   ' + P.fmt(t) + ' / ' + P.fmt(v.duration), delta < 0 ? 'back' : 'fwd');
  }

  function togglePlay(v, force) {
    var play = force === 'play' ? true : force === 'pause' ? false : v.paused;
    if (play) { var p = v.play(); if (p && p.catch) p.catch(function () {}); } else v.pause();
    bubble(play ? 'Play' : 'Pause', play ? 'play' : 'pause');
  }

  /**
   * Wakes the site's control bar with a synthetic mouse move. The point is a neutral spot of the picture (centre, about 38 % down),
   * away from the top and bottom bars: near the progress bar the site read it as a hover and showed a seek preview for the bar's
   * middle. The target is never a bar control, and the seek bar is told the pointer left, so any hover preview closes.
   */
  function wakeControls(v) {
    var host = fsElement() || (v && v.parentElement) || doc.body;
    var r = (v || host).getBoundingClientRect();
    var x = r.left + r.width / 2, y = r.top + r.height * 0.38;
    var PF = TV.PF;
    var bars = PF ? [PF.player.controls, PF.player.seekBar].filter(Boolean).join(',') : '';
    var tgt = doc.elementFromPoint(x, y);
    if (!tgt || (bars && (tgt.matches(bars) || tgt.closest(bars)))) tgt = v || host;
    var seq = ['mouseover', 'mousemove', 'pointermove'];
    for (var i = 0; i < seq.length; i++) {
      try { tgt.dispatchEvent(new MouseEvent(seq[i], { bubbles: true, clientX: x + (i % 2), clientY: y, view: root })); } catch (e) { /* ignore */ }
    }
    clearSeekHover();
  }

  /** Tells the seek bar the pointer is not on it (profile player.seekBar), so a hover thumbnail / time preview is removed. */
  function clearSeekHover() {
    var PF = TV.PF;
    if (!PF || !PF.player.seekBar) return;
    var bars = doc.querySelectorAll(PF.player.seekBar);
    var types = [['pointerout', true], ['mouseout', true], ['pointerleave', false], ['mouseleave', false]];
    for (var i = 0; i < bars.length; i++) {
      for (var t = 0; t < types.length; t++) {
        var Ctor = types[t][0].indexOf('pointer') === 0 && typeof PointerEvent === 'function' ? PointerEvent : MouseEvent;
        try { bars[i].dispatchEvent(new Ctor(types[t][0], { bubbles: types[t][1], cancelable: true, view: root, clientX: -1, clientY: -1 })); } catch (e) { /* ignore */ }
      }
    }
  }
  TV.wakeControls = function () { wakeControls(findVideo()); };

  /** Transport keys from the native shell: playpause | play | pause | seek (seconds). Relayed into iframes when this frame has no video. */
  /** Unmutes every visible playing-or-ready video (hero banners start muted). Prefers the site's own Unmute button so its UI stays in step. */
  function unmuteVideos() {
    var did = false;
    var vids = doc.getElementsByTagName('video');
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (!v.muted && v.volume > 0) continue;
      var r = v.getBoundingClientRect();
      if (r.width < 80 || r.height < 60) continue;
      if (v.paused && v.readyState < 2) continue;
      did = true;
      var scope = v.closest('section,main,div[class*="hero" i],div[class*="banner" i]') || doc;
      var btn = scope.querySelector('button[aria-label*="unmute" i],[role=button][aria-label*="unmute" i]') || doc.querySelector('button[aria-label*="unmute" i]');
      if (btn) { try { btn.click(); } catch (e) { /* ignore */ } }
      (function (vid) {
        setTimeout(function () {
          if (vid.muted) vid.muted = false;
          if (vid.volume === 0) vid.volume = 1;
        }, 80);
      })(v);
    }
    return did;
  }
  TV.unmute = unmuteVideos;

  TV.media = function (action, secs) {
    if (action === 'unmute') { var um = unmuteVideos(); TV.relayDown({ __movyTvMedia: [action, secs] }); return um; }
    var v = findVideo();
    if (!v) { TV.relayDown({ __movyTvMedia: [action, secs] }); return false; }
    if (action === 'seek') seek(v, secs || 10);
    else togglePlay(v, action === 'playpause' ? null : action);
    return true;
  };

  /** Status of this frame's main video, or null. Aggregated upwards by postMessage (see TV.lastStatus). */
  TV.status = function () {
    var v = findVideo();
    if (!v) return null;
    return { paused: v.paused, currentTime: v.currentTime || 0, duration: isFinite(v.duration) ? v.duration : 0, muted: v.muted,
      ended: v.ended, fullscreen: !!fsElement(), inFrame: inFrame, title: doc.title || '' };
  };
  TV.lastStatus = null;
  TV.mediaStatus = function () { return TV.lastStatus || TV.status(); };

  function publishStatus(force) {
    var now = Date.now();
    if (!force && now - statusAt < 900) return;
    statusAt = now;
    var st = TV.status();
    if (!st) return;
    if (inFrame) up({ __movyTvStatus: st });
    else receiveStatus(st);
  }

  function receiveStatus(st) {
    TV.lastStatus = st;
    try { if (root.MovyTvNative && root.MovyTvNative.onMedia) root.MovyTvNative.onMedia(JSON.stringify(st)); } catch (e) { /* ignore */ }
  }

  // ---------- fullscreen policy ----------

  /** The element to fullscreen in THIS document, or null. */
  function fsTarget() {
    var PF = TV.PF;
    if (PF) {
      var sels = [PF.player.fullscreenTarget, PF.player.frame, PF.player.root];
      for (var i = 0; i < sels.length; i++) {
        if (!sels[i] || !TV.okSel(sels[i])) continue;
        var el = doc.querySelector(sels[i]);
        if (el) return el;
      }
    }
    var big = TV.bigIframe();
    if (big) return big;
    return null;
  }

  function videoContainer() {
    var v = findVideo();
    if (!v) return null;
    var p = v, r = v.getBoundingClientRect(), n = 0;
    while (p.parentElement && n++ < 3) {
      var pr = p.parentElement.getBoundingClientRect();
      if (pr.width * pr.height > r.width * r.height * 1.6) break;
      p = p.parentElement;
    }
    return p;
  }

  function requestFs(el) {
    var fn = el.requestFullscreen || el.webkitRequestFullscreen;
    if (!fn) return Promise.reject(new Error('unsupported'));
    var res = fn.call(el);
    return res && res.then ? res : Promise.resolve();
  }

  function nativeUserExit() {
    try { return !!(root.MovyTvNative && root.MovyTvNative.userLeftFullscreen && root.MovyTvNative.userLeftFullscreen()); } catch (e) { return false; }
  }

  function requestGesture() {
    try { if (root.MovyTvNative && root.MovyTvNative.gesture) root.MovyTvNative.gesture(); } catch (e) { /* ignore */ }
  }

  /** Top frame: enter fullscreen on the profile target (inside a key handler when possible). */
  function userJustLeft() { return Date.now() - userExitAt < 3000 || nativeUserExit(); }

  function ensureFs(why) {
    if (inFrame) { up({ __movyTvFs: why || 'child' }); return; }
    if (fsElement()) return;
    if (why !== 'api' && why !== 'activate' && userJustLeft()) return; // BACK left fullscreen: stay out
    var el = fsTarget() || videoContainer();
    clearTimeout(fsTimer);
    if (!el) {
      if (fsTries++ < 8) fsTimer = setTimeout(function () { ensureFs(why); }, 500);
      return;
    }
    requestFs(el).then(function () { fsTries = 0; rememberWantFs(true); }, function () {
      if (fsTries++ >= 4) return;
      requestGesture(); // a synthetic key from native grants the transient user activation
      fsTimer = setTimeout(function () { ensureFs('retry'); }, 220);
    });
  }

  TV.enterFullscreen = function () { wantFs = true; fsTries = 0; ensureFs('api'); };
  TV.userExitFs = function () {
    userExitAt = Date.now();
    wantFs = false;
    rememberWantFs(false);
    TV.relayDown({ __movyTvUserExit: 1 });
  };
  TV.wantsFullscreen = function () { return wantFs; };

  TV.hooks.beforeActivate = function (el) {
    lastKeyAt = Date.now();
    if (!TV.menuNow()) TV.menuToggle = el; // whatever opens a player menu is what closes it again
    var PF = TV.PF;
    if (!PF || inFrame) return;
    var isPlay = (PF.playButtons && (el.matches(PF.playButtons) || el.closest(PF.playButtons)))
      || (PF.player.frame && TV.okSel(PF.player.frame) && el.closest(PF.player.frame));
    if (isPlay) { wantFs = true; fsTries = 0; userExitAt = 0; }
  };
  TV.hooks.afterActivate = function () {
    if (wantFs && !inFrame && !fsElement()) {
      // inside the key handler: counts as a user gesture; the target may not exist yet (route change), so retry shortly
      var el = fsTarget();
      if (el && findVideoPlaying()) ensureFs('activate');
    }
  };

  function findVideoPlaying() {
    var v = findVideo();
    return !!(v && !v.paused && v.readyState > 2);
  }

  function onPlaying(e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO' || !isMainVideo(v)) return;
    publishStatus(true);
    if (inFrame) { up({ __movyTvPlaying: 1 }); return; }
    onChildPlaying();
  }

  function onChildPlaying() {
    armControlsHide(); // a player frame started playing: the page chrome over it fades after 5 s without a key
    var recent = Date.now() - lastKeyAt < 25000;
    if (userJustLeft() || (!wantFs && !recent)) return;
    wantFs = true;
    fsTries = 0;
    ensureFs('playing');
  }

  root.addEventListener('playing', onPlaying, true);
  ['timeupdate', 'pause', 'ended', 'loadedmetadata'].forEach(function (t) {
    root.addEventListener(t, function (e) { if (e.target && e.target.tagName === 'VIDEO' && isMainVideo(e.target)) publishStatus(t !== 'timeupdate'); }, true);
  });

  doc.addEventListener('fullscreenchange', function () {
    if (inFrame) return;
    if (TV.PF) applyViewport(TV.PF);
    if (fsElement()) { fsTries = 0; if (findVideo()) { TV.releaseFocus(); armControlsHide(); } return; } // playback starts "free": OK pauses, arrows seek
    showControls();
    if (Date.now() - userExitAt < 2500 || nativeUserExit()) { wantFs = false; rememberWantFs(false); return; }
    if (wantFs) setTimeout(function () { if (wantFs && !fsElement() && !nativeUserExit()) ensureFs('lost'); }, 250); // swapped by the site: re-enter
  });

  // ---------- fullscreen layout-width compensation ----------
  // The page lays out at the profile width (1280 CSS px) but the native fullscreen view is the real window (960 CSS px at 2x),
  // so entering fullscreen used to reflow the whole player UI ~33% bigger. Zooming the fullscreen element by (fs width / normal
  // width) keeps the exact same layout and scale as before fullscreen: no jump, same on every site (top frame and player iframes).
  var normalW = 0, zoomedEl = null;
  function clientW() { return doc.documentElement ? doc.documentElement.clientWidth : 0; }
  function noteNormalWidth() {
    if (fsElement()) return;
    var w = clientW();
    if (w > 0) normalW = w;
  }
  root.addEventListener('resize', noteNormalWidth);
  noteNormalWidth();
  doc.addEventListener('fullscreenchange', function () {
    var el = fsElement();
    if (zoomedEl && zoomedEl !== el) {
      try { zoomedEl.style.removeProperty('zoom'); } catch (e) { /* detached */ }
      zoomedEl = null;
    }
    if (!el || el === doc.documentElement) return;
    var w = clientW();
    if (normalW && w && w < normalW * 0.98) {
      el.style.setProperty('zoom', String(Math.round((w / normalW) * 1000) / 1000), 'important');
      zoomedEl = el;
    }
  });

  // ---------- keys inside video ----------

  var lastAliveUp = 0;
  TV.hooks.key = function (e, dir, a) {
    lastKeyAt = Date.now();
    if (inFrame && lastKeyAt - lastAliveUp > 300) { lastAliveUp = lastKeyAt; up({ __movyTvAlive: 1 }); } // keys inside a player frame restart the page's 5 s fade too
    if (!inFrame && wantFs && !fsElement() && findVideoPlaying()) ensureFs('key');
    if (idleOn) TV.idle(false);
    TV.showControls(); // any key brings the controls back and restarts the 5 s countdown
    var fs = fsElement();
    var video = (fs || inFrame) ? findVideo() : null;
    if (!video) return false;
    if (fs) TV.refresh();
    if (fs && Date.now() - lastWakeAt > 700) { lastWakeAt = Date.now(); wakeControls(video); }
    var free = !a || a === video || a === fs || a.tagName === 'VIDEO' || a === doc.body || (!TV.menuNow() && !controlVisible(a));
    if (!free) return false;
    if (a && a !== video && a !== fs && a !== doc.body && a.tagName !== 'VIDEO') TV.releaseFocus(); // stale focus on a hidden control
    if (e.keyCode === 13 || e.keyCode === 23) { e.preventDefault(); e.stopPropagation(); if (!e.repeat) togglePlay(video); return true; }
    if (dir === 'left' || dir === 'right') {
      e.preventDefault(); e.stopPropagation();
      var now = Date.now();
      if (now - lastSeekAt > 180) { lastSeekAt = now; seek(video, dir === 'left' ? -10 : 10); }
      return true;
    }
    if (dir === 'up' || dir === 'down') {
      e.preventDefault(); e.stopPropagation();
      wakeControls(video);
      var ctl = pickControl(dir);
      if (ctl) { TV.setCurrent(ctl, dir); return true; }
      if (!TV.move(dir) && inFrame) up({ __movyTvExit: dir });
      return true;
    }
    return false;
  };

  /** Whether a focused player control can actually be seen (sites auto-hide their bars without removing them). */
  function controlVisible(el) {
    if (!el || !el.isConnected) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4 || r.bottom <= 0 || r.top >= root.innerHeight) return false;
    var cs = root.getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none') return false;
    for (var p = el, n = 0; p && n++ < 8; p = p.parentElement) if (parseFloat(root.getComputedStyle(p).opacity) < 0.1) return false;
    return true;
  }

  /** Free mode UP: the top-most (then left-most) visible player control; DOWN: the one nearest the vertical centre. */
  function pickControl(dir) {
    var PF = TV.PF;
    if (!PF || !PF.player.controls) return null;
    var list = doc.querySelectorAll(PF.player.controls), best = null, bestScore = 1e9;
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (!controlVisible(el)) continue;
      var r = el.getBoundingClientRect();
      var score = dir === 'up' ? r.top * 4 + r.left : Math.abs((r.top + r.height / 2) - root.innerHeight / 2) * 4 + Math.abs(r.left + r.width / 2 - root.innerWidth / 2);
      if (score < bestScore) { best = el; bestScore = score; }
    }
    return best;
  }

  /**
   * Native BACK offers itself to the page first. Consumed here: close an open player menu (back to its toggle), or drop
   * focus from the controls back to free playback. Not consumed: the shell leaves fullscreen / goes back.
   */
  var prevBack = TV.back;
  TV.back = function () {
    if (inFrame) return false;
    if (TV.isDrawerOpen && TV.isDrawerOpen()) {
      TV.closeDrawer();
      return true;
    }
    if (prevBack && prevBack()) return true;
    var fs = fsElement();
    if (TV.menuNow()) {
      var toggle = TV.menuToggle;
      try {
        var ev = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
        (doc.activeElement || doc.body).dispatchEvent(new KeyboardEvent('keydown', ev));
        doc.dispatchEvent(new KeyboardEvent('keyup', ev));
      } catch (e) { /* ignore */ }
      setTimeout(function () {
        if (TV.menuNow() && toggle && toggle.isConnected) toggle.click(); // Escape did nothing: the opening button closes it
        setTimeout(function () {
          TV.menuNow();
          if (toggle && toggle.isConnected && !TV.menuEl) TV.setCurrent(toggle, null); // back on the control that opened it
        }, 220);
      }, 500); // Escape closes it with a short fade: only click the toggle if it is still up afterwards
      return true;
    }
    var a = TV.activeEl();
    if (fs && a && a !== fs && a.tagName !== 'VIDEO' && controlVisible(a)) { TV.releaseFocus(); return true; }
    return false;
  };

  // ---------- messages ----------

  root.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || typeof d !== 'object') return;
    if (d.__movyTvMedia && e.source === root.parent) { TV.media(d.__movyTvMedia[0], d.__movyTvMedia[1]); return; }
    if (d.__movyTvIdle !== undefined && e.source === root.parent) { TV.idle(d.__movyTvIdle); return; }
    if (d.__movyTvAlive && e.source !== root) {
      if (inFrame) up(d); else TV.showControls();
      return;
    }
    if (d.__movyTvWake && e.source === root.parent) {
      var wv = findVideo();
      if (wv) wakeControls(wv);
      TV.relayDown(d);
      return;
    }
    if (d.__movyTvPctl !== undefined && e.source === root.parent) {
      if (doc.documentElement) doc.documentElement.classList.toggle('movy-pctl-off', !!d.__movyTvPctl);
      TV.relayDown(d);
      return;
    }
    if (d.__movyTvUserExit && e.source === root.parent) { userExitAt = Date.now(); wantFs = false; TV.relayDown(d); return; }
    if (d.__movyTvPlaying) { if (inFrame) up(d); else onChildPlaying(); return; }
    if (d.__movyTvFs) { if (inFrame) up(d); else ensureFs('child'); return; }
    if (d.__movyTvStatus) { if (inFrame) up(d); else receiveStatus(d.__movyTvStatus); }
  });

  function init() {
    if (TV.PF) { applyCss(TV.PF); killHeavy(TV.PF); applyViewport(TV.PF); }
  }
  doc.addEventListener('DOMContentLoaded', init);
  root.addEventListener('load', function () { init(); setTimeout(init, 1200); });
  var killTimer = 0;
  new MutationObserver(function () {
    if (killTimer) return;
    killTimer = setTimeout(function () { killTimer = 0; if (TV.PF) killHeavy(TV.PF); }, 600);
  }).observe(doc.documentElement, { childList: true, subtree: true });
  init();
})(typeof window !== 'undefined' ? window : {});
