'use strict';
// Tiny DOM + animation helpers shared by the island and the panel (classic script, exposes window.UI).
(function (root) {
  var TOKENS = {
    expand: Spring.spring(380, 0.78),
    collapse: Spring.spring(500, 0.9),
    press: Spring.spring(900, 0.7),
    soft: Spring.spring(420, 0.82),
    panel: Spring.spring(320, 0.86),
  };

  /** h('div.cls#id', {attr}, children...) */
  function h(tag, attrs) {
    var m = /^([a-z0-9]+)((?:[.#][\w-]+)*)$/i.exec(tag);
    var el = document.createElement(m ? m[1] : 'div');
    if (m && m[2]) {
      m[2].replace(/([.#])([\w-]+)/g, function (_, k, v) {
        if (k === '.') el.classList.add(v);
        else el.id = v;
        return '';
      });
    }
    if (attrs instanceof Node || Array.isArray(attrs) || typeof attrs === 'string') {
      append(el, attrs);
    } else if (attrs && typeof attrs === 'object') {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (v === null || v === undefined || v === false) return;
        if (k === 'text') el.textContent = v;
        else if (k === 'style') el.style.cssText = v;
        else if (k.slice(0, 2) === 'on') el.addEventListener(k.slice(2), v);
        else el.setAttribute(k, v === true ? '' : v);
      });
    }
    for (var i = 2; i < arguments.length; i++) append(el, arguments[i]);
    return el;
  }

  function append(el, c) {
    if (c === null || c === undefined || c === false) return;
    if (Array.isArray(c)) c.forEach(function (x) { append(el, x); });
    else el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
  }

  /**
   * Plays keyframes with a spring token, then commits the final frame as inline style so the element rests there.
   * Returns a promise that resolves when done.
   */
  function play(el, frames, token, opts) {
    var o = opts || {};
    var anim = el.animate(frames, {
      duration: o.duration || token.duration,
      easing: token.css,
      delay: o.delay || 0,
      fill: 'both',
    });
    // A hidden/occluded view may not run frames, so a timer settles the promise too (the animation never gets stuck).
    var total = (o.duration || token.duration) + (o.delay || 0) + 60;
    var settle = new Promise(function (r) { setTimeout(r, total); });
    return Promise.race([anim.finished, settle]).then(function () {
      try {
        anim.commitStyles();
      } catch (e) { /* element detached */ }
      anim.cancel();
    }, function () { /* cancelled by a newer animation */ });
  }

  var wait = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };

  /** Health colours / labels. */
  var HEALTH = { up: 'Online', slow: 'Slow', down: 'Offline', unknown: 'Not checked' };

  function gradient(accent) {
    return 'linear-gradient(180deg,' + accent + ',' + darken(accent, 0.25) + ')';
  }

  function darken(hex, amount) {
    var n = parseInt(hex.slice(1), 16);
    var k = 1 - amount;
    var r = Math.round(((n >> 16) & 255) * k);
    var g = Math.round(((n >> 8) & 255) * k);
    var b = Math.round((n & 255) * k);
    return 'rgb(' + r + ',' + g + ',' + b + ')';
  }

  /** Squircle source tile (gradient, monogram, optional health dot). */
  function tile(source, size, withDot) {
    return h('span.tile', { style: 'width:' + size + 'px;height:' + size + 'px;background:' + gradient(source.accent) },
      h('span.mono', { text: source.mono, style: 'font-size:' + Math.round(size * 0.41) + 'px' }),
      withDot ? h('span.dot.h-' + source.health, { 'data-health': source.id }) : null);
  }

  function formatBytes(n) {
    if (n === null || n === undefined) return '…';
    var u = ['B', 'KB', 'MB', 'GB'];
    var i = 0;
    var v = n;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return (i === 0 ? v : v.toFixed(v < 10 ? 1 : 0)) + ' ' + u[i];
  }

  root.UI = { h: h, play: play, wait: wait, T: TOKENS, HEALTH: HEALTH, gradient: gradient, tile: tile, formatBytes: formatBytes };
})(window);
