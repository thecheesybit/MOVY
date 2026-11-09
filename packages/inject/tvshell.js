// Movy TV: boot check of a page that was served from a local shell snapshot. Top frame only, tv flavor only.
// Reports MovyTvNative.shellBoot(ok) once per document: ok as soon as the site's root has rendered content, not ok when a
// chunk/script of the site fails to load or nothing rendered within 4 s. The verdict is given at the end of the 4 s window unless a failure comes first. Native only acts on it when it just served a snapshot.
(function (root) {
  'use strict';
  var TV = root.__movyTv;
  if (!TV || root.parent !== root || TV.shellLoaded) return;
  TV.shellLoaded = true;

  var BOOT_MS = 4000;
  var POLL_MS = 250;
  var CHUNK_ERROR = /ChunkLoadError|Loading chunk|Loading CSS chunk|dynamically imported module|Importing a module script failed|error loading dynamically/i;
  var reported = false;
  var t0 = Date.now();

  function report(ok, why) {
    if (reported) return;
    reported = true;
    TV.shellBoot = { ok: !!ok, why: String(why || ''), ms: Date.now() - t0 };
    try { if (root.MovyTvNative && root.MovyTvNative.shellBoot) root.MovyTvNative.shellBoot(!!ok, String(why || '')); } catch (e) { /* ignore */ }
  }

  function sameOrigin(url) {
    try { return new URL(url, root.location.href).origin === root.location.origin; } catch (e) { return false; }
  }

  // A script or stylesheet of the site itself failed (a pinned asset is gone or corrupt): the snapshot cannot boot.
  function isCritical(t) {
    if (t.tagName === 'SCRIPT') return true;
    return t.tagName === 'LINK' && /stylesheet|modulepreload/i.test(t.rel || '');
  }

  root.addEventListener('error', function (e) {
    var t = e.target;
    if (t && t !== root && t.tagName && isCritical(t) && sameOrigin(t.src || t.href || '')) { report(false, 'asset'); return; }
    if (e.message && CHUNK_ERROR.test(e.message)) report(false, 'chunk');
  }, true);

  root.addEventListener('unhandledrejection', function (e) {
    var r = e.reason;
    var m = r && (r.name || r.message) ? String(r.name) + ' ' + String(r.message) : String(r);
    if (CHUNK_ERROR.test(m)) report(false, 'chunk');
  });

  function rootSelectors() {
    var sh = TV.PF && TV.PF.raw && TV.PF.raw.shell;
    var list = sh && sh.root && sh.root.length ? sh.root : ['#__next', '#root', '#app'];
    return list.concat(['body']);
  }

  /** The site's root holds real content (not an empty mount point or only a loader). */
  function rendered() {
    var sels = rootSelectors();
    for (var i = 0; i < sels.length; i++) {
      var el = null;
      try { el = root.document.querySelector(sels[i]); } catch (e) { el = null; }
      if (el && el.children.length > 0 && (el.innerText || '').trim().length > 40) return true;
    }
    return false;
  }

  (function poll() {
    if (reported) return;
    var elapsed = Date.now() - t0;
    // Async chunks of a slow page are requested a second or two after the document: judge at the end of the window, so a
    // chunk that 404s (an asset the live site no longer has) is seen. Errors above report immediately.
    if (elapsed >= BOOT_MS) { report(rendered(), rendered() ? 'rendered' : 'blank'); return; }
    setTimeout(poll, POLL_MS);
  })();
})(typeof window !== 'undefined' ? window : {});
