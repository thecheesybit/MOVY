'use strict';
const { shell } = require('electron');
const { isHttp } = require('./hosts');

const GESTURE_MS = 1500;
const GESTURE_INPUT = new Set(['mouseDown', 'mouseUp', 'keyDown', 'rawKeyDown', 'touchStart', 'gestureTap']);

/**
 * Enforces allowed sites / externalLinks / blockPopups for the site's webContents.
 * Verdict for a URL: 'allow' (load in-app), 'deny' (drop) or 'external' (hand to the OS browser).
 */
function createNavigationPolicy({ cfg, sites, engine, offlineUrl, log }) {
  let offline = offlineUrl;
  function decide(url, sourceUrl) {
    if (offline && url === offline) return 'allow';
    if (!isHttp(url)) return 'deny'; // file:, javascript:, intent:, custom schemes, ...
    if (sites.isAllowed(url)) return 'allow';
    if (engine && engine.check(url, sourceUrl || url, 'document').matched) return 'deny'; // known ad host
    if (cfg.externalLinks === 'in-app') return 'allow';
    if (cfg.externalLinks === 'block') return 'deny';
    return 'external';
  }

  function openExternal(url) {
    if (isHttp(url)) shell.openExternal(url).catch((e) => log('[nav] openExternal failed:', e.message));
  }

  function attach(wc) {
    let lastGesture = 0;
    wc.on('input-event', (_e, input) => {
      if (GESTURE_INPUT.has(input.type)) lastGesture = Date.now();
    });

    wc.setWindowOpenHandler(({ url }) => {
      const gesture = Date.now() - lastGesture < GESTURE_MS;
      if (cfg.adblock.blockPopups && !gesture && !sites.isAllowed(url)) {
        log('[nav] popup dropped (no user gesture):', url);
        return { action: 'deny' };
      }
      const verdict = decide(url, wc.getURL());
      if (verdict === 'allow') setImmediate(() => wc.loadURL(url));
      else if (verdict === 'external') openExternal(url);
      else log('[nav] popup blocked:', url);
      return { action: 'deny' };
    });

    const guard = (e) => {
      if (e.isSameDocument || !e.isMainFrame) return;
      const verdict = decide(e.url, wc.getURL());
      if (verdict === 'allow') return;
      e.preventDefault();
      if (verdict === 'external') openExternal(e.url);
      else log('[nav] navigation blocked:', e.url);
    };
    wc.on('will-navigate', guard);
    wc.on('will-redirect', guard);
  }

  return { attach, decide, openExternal, setOfflineUrl: (u) => { offline = u; } };
}

module.exports = { createNavigationPolicy };
