'use strict';
// Sandboxed preload for every frame of the remote site (nodeIntegrationInSubFrames + sandbox: no Node).
// It exposes NOTHING to the page. It runs the shared loading.js / cosmetic.js / scamguard.js in the preload's isolated
// world (DOM access is shared) and sends scriptlets and navguard.js to the page's main world.
const { ipcRenderer, webFrame } = require('electron');

(function () {
  const proto = location.protocol;
  if (proto !== 'http:' && proto !== 'https:') return;

  const boot = ipcRenderer.sendSync('movy:boot');
  if (!boot) return;

  // Desktop: pages scroll (wheel, keys, touch) but no scrollbar is ever drawn; every frame, every navigation.
  const NO_SCROLLBARS = '::-webkit-scrollbar{display:none!important;width:0!important;height:0!important}html,body,*{scrollbar-width:none!important}';
  try {
    webFrame.insertCSS(NO_SCROLLBARS);
  } catch (e) { /* page without a document yet: nothing to style */ }

  const run = (code, vars) => {
    try {
      new Function(...Object.keys(vars), code)(...Object.values(vars));
    } catch (e) {
      /* a broken injected script must never break the page */
    }
  };

  /** Runs `code` in the page's main world: inline <script> first (synchronous), webFrame as CSP fallback. */
  const runInPage = (code) => {
    try {
      const s = document.createElement('script');
      s.textContent = `(function(){try{${code}\n}catch(e){}\ndocument.currentScript.setAttribute('data-movy','1');})();`;
      (document.head || document.documentElement).appendChild(s);
      const ok = s.getAttribute('data-movy') === '1';
      s.remove();
      if (ok) return;
    } catch (e) { /* fall through */ }
    webFrame.executeJavaScript(`(function(){try{${code}\n}catch(e){}})();`).catch(() => {});
  };

  let top = false;
  try {
    top = window.self === window.top;
  } catch (e) { /* cross-origin parent: not top */ }

  run(boot.loadingJs, {});
  if (top) run(boot.scamguardJs, {}); // fake-captcha / QR overlays only appear in the top document
  if (boot.navguardJs) runInPage(`window.MOVY_NAV=${JSON.stringify(boot.nav)};${boot.navguardJs}`);

  if (boot.cosmetic) {
    const bridge = {
      cosmetic: () => ipcRenderer.sendSync('movy:cosmetic'),
      hidden: (c, i, x) => {
        try {
          return ipcRenderer.sendSync('movy:hidden', JSON.parse(c), JSON.parse(i), JSON.parse(x));
        } catch (e) {
          return '[]';
        }
      },
    };
    run(boot.cosmeticJs, { MOVY_OPTS: { cosmetic: true }, MOVY_BRIDGE: bridge, MOVY_EVAL: runInPage });
  }
})();
