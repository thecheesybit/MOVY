// Movy TV player layout: no visible resize when a player goes fullscreen.
// - The page lays out at the profile width (1280 CSS px) but the native fullscreen view is the real window (960 CSS px at 2x), so
//   entering fullscreen used to reflow the whole player UI ~33% bigger. Zooming the fullscreen element by (fs width / normal width)
//   keeps the same layout and scale as before fullscreen (top document).
// - A cross-origin player embed is not zoomed (vw/vh sized UIs would shrink twice): fullscreen is entered when the player mounts, before its
//   first frame, so it is laid out at the fullscreen size from the start and never changes.
// - The fullscreen element stays hidden (opacity 0) until its rect and the video rect were the same for 2 frames, then fades in.
(function (root) {
  'use strict';
  var STABLE_FRAMES = 2;
  var FADE_MS = 150;
  var GIVE_UP_MS = 1500;

  /** Pure. Zoom that keeps a layout of `baseW` CSS px inside a window `w` px wide; 1 (none) when it is not narrower. */
  function zoomFor(baseW, w) {
    if (!baseW || !w || w >= baseW * 0.98) return 1;
    return Math.round((w / baseW) * 1000) / 1000;
  }

  /** Pure. True when the last `need` rects are all the same size and non-empty. */
  function stable(rects, need) {
    if (rects.length < need) return false;
    var last = rects[rects.length - 1];
    if (!last || last.w <= 0 || last.h <= 0) return false;
    for (var i = rects.length - need; i < rects.length; i++) if (rects[i].w !== last.w || rects[i].h !== last.h) return false;
    return true;
  }

  var api = { zoomFor: zoomFor, stable: stable };
  if (typeof module === 'object' && module && module.exports) { module.exports = api; return; }
  var TV = root.__movyTv;
  if (!TV) return;
  var doc = root.document;
  var inFrame = root.parent !== root;
  var normalW = 0, zoomedEl = null;

  function fsElement() { return doc.fullscreenElement || doc.webkitFullscreenElement || null; }
  function clientW() { return doc.documentElement ? doc.documentElement.clientWidth : 0; }

  function noteNormalWidth() {
    if (fsElement()) return;
    var w = clientW();
    if (w > 0) normalW = w;
  }
  root.addEventListener('resize', noteNormalWidth);
  noteNormalWidth();

  function style() {
    if (doc.getElementById('__movy_pfx')) return;
    var st = doc.createElement('style');
    st.id = '__movy_pfx';
    st.textContent = ':fullscreen:not(video),:-webkit-full-screen:not(video){transition:opacity ' + FADE_MS + 'ms ease-out}' +
      'html.movy-pveil :fullscreen:not(video),html.movy-pveil :-webkit-full-screen:not(video){opacity:0!important;transition:none!important}';
    (doc.head || doc.documentElement).appendChild(st);
  }

  function veilOff() { if (doc.documentElement) doc.documentElement.classList.remove('movy-pveil'); reveal(); }

  /** Hides the fullscreen element until its rect (and the video's, when it is in this document) held still for 2 frames. */
  function veilUntilStable(el) {
    var de = doc.documentElement;
    if (!de) return;
    style();
    de.classList.add('movy-pveil');
    var rects = [], t0 = Date.now();
    (function frame() {
      if (!fsElement()) { veilOff(); return; }
      var r = el.getBoundingClientRect(), v = doc.querySelector('video');
      var vr = v ? v.getBoundingClientRect() : null;
      rects.push({ w: Math.round(r.width + (vr ? vr.width : 0)), h: Math.round(r.height + (vr ? vr.height : 0)) });
      if (stable(rects, STABLE_FRAMES) || Date.now() - t0 > GIVE_UP_MS) { veilOff(); return; }
      root.requestAnimationFrame(frame);
    })();
  }

  // The fullscreen element is hidden from the moment the request is made (when the user activation makes success likely), so the
  // switch of the window size is never seen; it fades in once the veil lifts. A refused request restores it after GIVE_UP_MS.
  var hidden = null, hiddenTimer = 0;
  function reveal() {
    clearTimeout(hiddenTimer);
    var el = hidden;
    hidden = null;
    if (!el) return;
    try { el.style.setProperty('transition', 'opacity ' + FADE_MS + 'ms ease-out'); el.style.removeProperty('opacity'); } catch (e) { /* detached */ }
  }
  TV.hooks.fsRequest = function (el) {
    if (inFrame || !el || el === doc.documentElement || el.tagName === 'VIDEO') return;
    var ua = root.navigator.userActivation;
    if (!ua || !ua.isActive) return;
    hidden = el;
    el.style.setProperty('opacity', '0', 'important');
    clearTimeout(hiddenTimer);
    hiddenTimer = setTimeout(reveal, GIVE_UP_MS + 1000);
  };

  doc.addEventListener('fullscreenchange', function () {
    var el = fsElement();
    if (zoomedEl && zoomedEl !== el) {
      try { zoomedEl.style.removeProperty('zoom'); } catch (e) { /* detached */ }
      zoomedEl = null;
    }
    if (!el || el === doc.documentElement) { veilOff(); return; }
    var z = zoomFor(normalW, clientW());
    if (z < 1) {
      el.style.setProperty('zoom', String(z), 'important');
      zoomedEl = el;
    }
    if (!inFrame) veilUntilStable(el);
  });
})(typeof window !== 'undefined' ? window : {});
