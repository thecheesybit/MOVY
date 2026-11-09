// Movy TV script gate. Concatenated by CosmeticBridge after the TV bundle, which it receives as `__movyTvRun`:
//   (function(){ var __movyTvRun = function(){ <tv scripts> }; <this file> })();
// The top document always gets the bundle. A sub-frame gets it only when it is a player: it holds a <video>, matches a profile
// `player.docMarkers` selector, or is big enough to be one (ad, tracker and trace frames are tiny or empty and get nothing).
// A frame that is not one yet watches (cheaply, for a while) and boots the bundle the moment it becomes one.
var __movyGate = (function (root) {
  'use strict';
  var MIN_W = 640;
  var MIN_H = 360;
  var WATCH_MS = 20000;
  var CHECK_MS = 400;

  /** Pure rule. i = { top, video, marker, w, h }. */
  function needs(i) {
    if (i.top) return true;
    if (i.video || i.marker) return true;
    return i.w >= MIN_W && i.h >= MIN_H;
  }

  function markerSelector() {
    try {
      var N = root.MovyTvNative;
      if (!N || !N.profile) return '';
      var pf = JSON.parse(N.profile('') || '{}');
      var m = pf.player && pf.player.docMarkers;
      return m && m.length ? m.join(',') : '';
    } catch (e) { return ''; }
  }

  function install(run) {
    var d = root.document;
    var top = root.parent === root;
    var ran = false;
    var mo = null;
    var timer = 0;
    var sel = null;

    function probe() {
      var video = false, marker = false;
      try {
        video = !!d.querySelector('video');
        if (!video && !top) {
          if (sel === null) sel = markerSelector();
          marker = !!(sel && d.querySelector(sel));
        }
      } catch (e) { /* selector rejected */ }
      return needs({ top: top, video: video, marker: marker, w: root.innerWidth || 0, h: root.innerHeight || 0 });
    }

    function stop() {
      if (mo) { mo.disconnect(); mo = null; }
      clearTimeout(timer);
      root.removeEventListener('resize', check);
      ['DOMContentLoaded'].forEach(function (t) { d.removeEventListener(t, check); });
      ['loadstart', 'loadedmetadata', 'play', 'playing'].forEach(function (t) { d.removeEventListener(t, check, true); });
    }

    function go() {
      if (ran) return;
      ran = true;
      stop();
      run();
    }

    function check() {
      if (!ran && probe()) go();
    }

    function later() {
      if (timer) return;
      timer = setTimeout(function () { timer = 0; check(); }, CHECK_MS);
    }

    if (probe()) { go(); return; }
    root.addEventListener('resize', check);
    d.addEventListener('DOMContentLoaded', check);
    ['loadstart', 'loadedmetadata', 'play', 'playing'].forEach(function (t) { d.addEventListener(t, check, true); });
    var t0 = Date.now();
    function observe() {
      if (ran) return;
      if (!d.documentElement) { setTimeout(observe, 20); return; }
      mo = new MutationObserver(function () {
        if (Date.now() - t0 > WATCH_MS) { if (mo) { mo.disconnect(); mo = null; } return; }
        later();
      });
      mo.observe(d.documentElement, { childList: true, subtree: true });
    }
    observe();
  }

  return { needs: needs, install: install };
})(typeof window !== 'undefined' ? window : {});
if (typeof module === 'object' && module && module.exports) module.exports = __movyGate;
else __movyGate.install(__movyTvRun);
