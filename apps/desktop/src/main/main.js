'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { app, session } = require('electron');
const { genDir } = require('./paths');
const { initLog, log } = require('./log');
const { createSites } = require('./sites');
const { chromeUserAgent } = require('./user-agent');
const { loadEngine } = require('./engine');
const { BlockStats } = require('./stats');
const { installNetworkBlocking } = require('./adblock');
const { installCosmeticIpc } = require('./cosmetic-ipc');
const { installPermissions } = require('./permissions');
const { installDownloads } = require('./downloads');
const { createNavigationPolicy } = require('./navigation');
const { createMainWindow } = require('./main-window');
const { saveSessionCookies, restoreSessionCookies } = require('./cookie-store');
const { installScreenshot, installStorageCheck } = require('./debug');

const gen = genDir();
const cfg = JSON.parse(fs.readFileSync(path.join(gen, 'app-config.json'), 'utf8'));

// Stable per-app user data folder: survives updates and reinstalls (the installer never deletes it).
const userDir = path.join(app.getPath('appData'), `movy-${cfg.name}`);
app.setPath('userData', userDir);

// Always a persistent, named partition (logins, localStorage, IndexedDB, Cache Storage, service workers).
const PARTITION = `persist:${cfg.appId}`;

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  start();
}

function start() {
  let mainWin = null;
  app.on('second-instance', () => {
    if (!mainWin) return;
    if (mainWin.isMinimized()) mainWin.restore();
    mainWin.focus();
  });
  app.on('window-all-closed', () => app.quit());
  app.on('web-contents-created', (_e, wc) => wc.on('will-attach-webview', (e) => e.preventDefault()));

  app.whenReady().then(async () => {
    initLog(userDir);
    const sites = createSites(cfg);
    const stats = new BlockStats();
    const ses = session.fromPartition(PARTITION);

    const ua = cfg.userAgent || chromeUserAgent(ses.getUserAgent(), [app.getName(), cfg.appName, 'movy']);
    ses.setUserAgent(ua);
    app.userAgentFallback = ua;

    let engine = null;
    if (cfg.adblock.enabled) {
      try {
        engine = loadEngine(gen);
      } catch (e) {
        log('[adblock] engine failed to load, running without blocking:', e.message);
      }
    }
    log(`[adblock] engine ${engine ? 'loaded' : 'not loaded'}; UA=${ua}`);
    if (engine) installNetworkBlocking(ses, engine, sites, stats, log);
    installPermissions(ses, sites);

    const read = (f) => fs.readFileSync(path.join(gen, f), 'utf8');
    let site = null;
    installCosmeticIpc({
      engine,
      cosmetic: cfg.adblock.cosmetic,
      assets: { cosmeticJs: read('cosmetic.js'), loadingJs: read('loading.js') },
      isSite: (wc) => !!site && wc.id === site.webContents.id,
      stats,
    });

    await restoreSessionCookies(ses, sites, userDir, log);

    const policy = createNavigationPolicy({ cfg, sites, engine, offlineUrl: null, log });
    const icon = path.join(gen, 'icon.png');
    const made = createMainWindow({ cfg, sites, ses, gen, userDir, icon, policy, log });
    mainWin = made.win;
    site = made.site;
    const wc = site.webContents;
    policy.setOfflineUrl(made.offlineUrl);
    installDownloads(ses, mainWin, log);

    // Persist cookies eagerly: after each load, when the window loses focus, and on quit.
    const flush = () => ses.cookies.flushStore().catch(() => {});
    wc.on('did-finish-load', flush);
    mainWin.on('blur', flush);
    wc.on('did-stop-loading', () => log(stats.summary()));

    let quitting = false;
    app.on('before-quit', (e) => {
      if (quitting) return;
      e.preventDefault();
      quitting = true;
      Promise.all([saveSessionCookies(ses, sites, userDir, log), ses.cookies.flushStore()])
        .catch(() => {})
        .finally(() => app.quit());
    });

    installScreenshot(wc, stats, log);
    installStorageCheck(wc, log);
    wc.loadURL(sites.startUrl);
  });
}
