'use strict';
// Developer verification flags (not used in normal runs):
//   --screenshot <png>   after the first load + --settle-ms (default 8000) save a screenshot, log stats, quit.
//   --storage-check set|get   'set' writes a session cookie + localStorage key and quits; 'get' logs what survived.
const fs = require('node:fs');
const { app } = require('electron');

function arg(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

const flag = (name) => process.argv.includes(name);

function installScreenshot(wc, stats, log) {
  const file = arg('--screenshot');
  if (!file) return;
  const settle = Number(arg('--settle-ms') ?? 8000);
  wc.once('did-finish-load', () => {
    setTimeout(async () => {
      try {
        const probe = await wc.executeJavaScript(
          `JSON.stringify({url:location.href,title:document.title,adoptedSheets:document.adoptedStyleSheets.length,` +
            `hiddenByCosmetic:[...document.adoptedStyleSheets].reduce((n,s)=>n+s.cssRules.length,0)})`,
        );
        log('[probe]', probe);
        const img = await wc.capturePage();
        fs.writeFileSync(file, img.toPNG());
        log('[screenshot] saved', file);
      } catch (e) {
        log('[screenshot] failed:', e.message);
      }
      log(stats.summary());
      app.quit();
    }, settle);
  });
}

/** Persistence check: run once with `set`, then again with `get` and compare the logged values. */
function installStorageCheck(wc, log) {
  const mode = arg('--storage-check');
  if (!mode) return;
  wc.once('did-finish-load', async () => {
    try {
      if (mode === 'set') {
        await wc.executeJavaScript("document.cookie='movy_session=abc123; path=/';localStorage.setItem('movy_ls','xyz789')");
        log('[storage-check] set session cookie movy_session and localStorage movy_ls');
      } else {
        const got = await wc.executeJavaScript('JSON.stringify({cookie:document.cookie,ls:localStorage.getItem("movy_ls")})');
        log('[storage-check] get', got);
      }
    } catch (e) {
      log('[storage-check] failed:', e.message);
    }
    setTimeout(() => app.quit(), 500);
  });
}

module.exports = { installScreenshot, installStorageCheck, arg, flag };
