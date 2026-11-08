'use strict';
// Electron wiring of the pure NavRules for the site's webContents. The app never opens an external app or a browser:
// every verdict is "load here" or "drop". Subframes and subresources are judged only by the ad engine.
const { ALLOW } = require('../core/nav-rules');

const GESTURE_MS = 1500;
const GESTURE_INPUT = new Set(['mouseDown', 'mouseUp', 'keyDown', 'rawKeyDown', 'touchStart', 'gestureTap']);

/**
 * @param {{rules:import('../core/nav-rules').NavRules, blockPopups:boolean, isEngineBlocked:(url:string, source:string)=>boolean,
 *          stats:{recordKill:()=>void}, loadMain:(url:string)=>void, log:Function}} o
 */
function createNavigationPolicy({ rules, blockPopups, isEngineBlocked, stats, loadMain, log }) {
  function attach(wc) {
    let lastGesture = 0;
    const gesture = () => Date.now() - lastGesture < GESTURE_MS;
    wc.on('input-event', (_e, input) => {
      if (GESTURE_INPUT.has(input.type)) lastGesture = Date.now();
    });

    // Never a new window: either load it in the main view or drop it.
    wc.setWindowOpenHandler(({ url }) => {
      const g = gesture();
      const blocked = isEngineBlocked(url, wc.getURL());
      if (rules.popupOpensInMain(url, g, blocked) && rules.mainFrame(url, false, g, wc.getURL()) === ALLOW) {
        setImmediate(() => loadMain(url));
      } else {
        stats.recordKill();
        log(`[nav] popup dropped (gesture=${g}${blockPopups ? '' : ', blockPopups off'})`);
      }
      return { action: 'deny' };
    });

    const guard = (redirect) => (e) => {
      if (e.isSameDocument || !e.isMainFrame) return;
      if (rules.mainFrame(e.url, redirect, gesture(), wc.getURL()) === ALLOW) return;
      e.preventDefault();
      stats.recordKill();
      log(`[nav] ${redirect ? 'redirect' : 'navigation'} blocked`);
    };
    wc.on('will-navigate', guard(false));
    wc.on('will-redirect', guard(true));
    wc.on('will-frame-navigate', (e) => {
      if (e.isMainFrame || rules.subframeAllowed(e.url)) return;
      e.preventDefault(); // intent:/market:/custom schemes could launch other apps
      stats.recordKill();
    });
  }

  return { attach };
}

module.exports = { createNavigationPolicy };
