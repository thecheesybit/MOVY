// Movy TV first-paint signal. Top frame only. Reports to the app (MovyTvNative.painted) once the page shows its first real
// content: an image of at least 60 px that is loaded and in view, or a video that has data. The app uses it to lift the
// loading cover and to open the site from the start screen. A page that never shows one reports after PATIENCE_MS.
(function (root) {
  'use strict';
  if (typeof window === 'undefined' || root.parent !== root || root.__movyPaint) return;
  var N = root.MovyTvNative;
  if (!N || typeof N.painted !== 'function') return;
  root.__movyPaint = true;

  var POLL_MS = 250;
  var PATIENCE_MS = 20000;
  var MIN_PX = 60;
  var t0 = Date.now();
  var done = false;

  function inView(r) {
    return r.width >= MIN_PX && r.height >= MIN_PX && r.bottom > 0 && r.top < root.innerHeight && r.right > 0 && r.left < root.innerWidth;
  }

  function painted() {
    var doc = root.document, i, el;
    var imgs = doc.images;
    for (i = 0; i < imgs.length; i++) {
      el = imgs[i];
      if (el.complete && el.naturalWidth > 0 && inView(el.getBoundingClientRect())) return true;
    }
    var vids = doc.getElementsByTagName('video');
    for (i = 0; i < vids.length; i++) if (vids[i].readyState >= 2 && inView(vids[i].getBoundingClientRect())) return true;
    return false;
  }

  function fire() {
    if (done) return;
    done = true;
    try { N.painted(); } catch (e) { /* ignore */ }
  }

  function poll() {
    if (done) return;
    var late = Date.now() - t0 > PATIENCE_MS;
    if (root.document.body && (painted() || late)) { fire(); return; }
    if (late) { fire(); return; }
    setTimeout(poll, POLL_MS);
  }

  setTimeout(poll, POLL_MS);
})(typeof window !== 'undefined' ? window : {});
