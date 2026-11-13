// Movy-owned subtitle style (TV, plan stage 5c): size and colour chosen in the native Settings, applied in every document of
// the TV bundle (top page and player frames) to the browser's own captions (::cue) and to the site's subtitle box (profile
// player.subtitles). Needed because some sources (Moonflix) have no subtitle styling at all. "Site default" adds nothing.
(function (root) {
  'use strict';

  var SIZES = ['', '3.2vh', '4.2vh', '5.4vh', '6.8vh']; // site default, S, M, L, XL
  var COLOURS = ['', '#ffffff', '#ffd60a']; // site default, white, yellow

  /** The CSS for size step 0..4 and colour 0..2 over the site subtitle selector list ("" when nothing is overridden). */
  function css(size, colour, selectors) {
    var fs = SIZES[size] || '', c = COLOURS[colour] || '';
    if (!fs && !c) return '';
    var decl = (fs ? 'font-size:' + fs + '!important;line-height:1.25!important;' : '') + (c ? 'color:' + c + '!important;' : '');
    var out = '::cue{' + decl + (c ? 'background:rgba(0,0,0,.55)!important;' : '') + '}';
    var sels = String(selectors || '').split(',').map(function (s) { return s.trim(); }).filter(Boolean);
    if (sels.length) {
      var all = sels.map(function (s) { return s + ',' + s + ' *'; }).join(',');
      out += '\n' + all + '{' + decl + 'text-shadow:0 0 4px #000,0 0 2px #000!important;}';
    }
    return out;
  }

  /** "size,colour" from the app, clamped; [0, 0] when absent or malformed. */
  function parse(v) {
    var m = /^(\d),(\d)$/.exec(String(v || ''));
    if (!m) return [0, 0];
    return [Math.min(+m[1], SIZES.length - 1), Math.min(+m[2], COLOURS.length - 1)];
  }

  var api = { css: css, parse: parse };
  if (typeof module === 'object' && module && module.exports) { module.exports = api; return; }

  var TV = root.__movyTv;
  var N = root.MovyTvNative;
  if (!TV || TV.subStyleLoaded || !N || typeof N.subtitleStyle !== 'function') return;
  TV.subStyleLoaded = true;

  var STYLE_ID = '__movy_subs';
  var last = null;

  function apply() {
    var v;
    try { v = N.subtitleStyle(); } catch (e) { return; }
    var pf = TV.PF && TV.PF.player;
    var key = v + '|' + (pf && pf.subtitles || '');
    if (key === last) return;
    last = key;
    var p = parse(v);
    var text = css(p[0], p[1], pf && pf.subtitles);
    var doc = root.document;
    var s = doc.getElementById(STYLE_ID);
    if (!text) { if (s) s.remove(); return; }
    if (!s) {
      s = doc.createElement('style');
      s.id = STYLE_ID;
      (doc.head || doc.documentElement).appendChild(s);
    }
    s.textContent = text;
  }

  // Settings can change while a video plays: re-read when media starts and every few seconds while a video exists.
  root.document.addEventListener('playing', apply, true);
  root.document.addEventListener('loadeddata', apply, true);
  setInterval(function () { if (root.document.querySelector('video')) apply(); }, 3000);
  apply();
})(typeof window !== 'undefined' ? window : {});
