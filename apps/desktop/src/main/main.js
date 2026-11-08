'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { app, session } = require('electron');
const { genDir } = require('./paths');
const { initLog, log } = require('./log');
const { loadAppConfig } = require('./config');
const { NavRules } = require('../core/nav-rules');
const live = require('../core/live-config');
const { Prefs } = require('../core/prefs');
const { resolverOptions } = require('../core/doh');
const { createSites } = require('./sites');
const { chromeUserAgent } = require('./user-agent');
const { loadEngine } = require('./engine');
const { BlockStats } = require('./stats');
const { installNetworkBlocking } = require('./adblock');
const { installCosmeticIpc } = require('./cosmetic-ipc');
const { installPermissions } = require('./permissions');
const { installDownloads } = require('./downloads');
const { createShell } = require('./shell');
const { initialConfig, startRemote } = require('./remote');
const { saveSessionCookies, restoreSessionCookies } = require('./cookie-store');
const { installScreenshot, installStorageCheck } = require('./debug');

const gen = genDir();
const { app: appCfg, defaults, obf } = loadAppConfig(gen);

// Stable per-app user data folder: survives updates and reinstalls (the installer never deletes it).
const userDir = path.join(app.getPath('appData'), `movy-${appCfg.name}`);
app.setPath('userData', userDir);

// Always a persistent, named partition (logins, localStorage, IndexedDB, Cache Storage, service workers).
const PARTITION = `persist:${appCfg.appId}`;

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
    const prefs = new Prefs(userDir, log);
    const initial = initialConfig({ defaults, obf, userDir, log });

    // DNS-over-HTTPS rescue for hosts the ISP resolver poisons (Cloudflare first, dns.google fallback).
    const doh = resolverOptions(initial.dohHosts);
    if (doh) {
      app.configureHostResolver(doh);
      log('[doh] secure DNS enabled (automatic, cloudflare + google)');
    }

    const rules = new NavRules({
      startUrl: initial.sources[0].url,
      allowedHosts: live.allAllowedHosts(initial),
      authHosts: initial.authHosts,
      externalLinks: appCfg.externalLinks,
    });
    rules.update({ allowedHosts: live.allAllowedHosts(initial), startHosts: live.startHosts(initial), authHosts: initial.authHosts });
    const sites = createSites(rules);
    const stats = new BlockStats();
    const ses = session.fromPartition(PARTITION);

    const ua = appCfg.userAgent || chromeUserAgent(ses.getUserAgent(), [app.getName(), appCfg.appName, 'movy']);
    ses.setUserAgent(ua);
    app.userAgentFallback = ua;

    let engine = null;
    if (appCfg.adblock.enabled) {
      try {
        engine = loadEngine(gen);
      } catch (e) {
        log('[adblock] engine failed to load, running without blocking:', e.message);
      }
    }
    log(`[adblock] engine ${engine ? 'loaded' : 'not loaded'}`);
    const adblockOn = () => !!prefs.get('adblockEnabled');
    if (engine) installNetworkBlocking(ses, engine, sites, stats, log, adblockOn);
    installPermissions(ses, sites);

    const read = (f) => fs.readFileSync(path.join(gen, f), 'utf8');
    let siteId = -1;
    installCosmeticIpc({
      engine,
      cosmetic: appCfg.adblock.cosmetic,
      isEnabled: adblockOn,
      assets: { cosmeticJs: read('cosmetic.js'), loadingJs: read('loading.js'), scamguardJs: read('scamguard.js'), navguardJs: read('navguard.js') },
      navConfig: () => ({ allow: rules.allowedPatterns(), auth: rules.authPatterns() }),
      isSite: (wc) => wc.id === siteId,
      stats,
    });

    await restoreSessionCookies(ses, sites, userDir, log);

    const isEngineBlocked = (url, source) => !!engine && adblockOn() && engine.check(url, source || url, 'document').matched;
    const shell = createShell({
      app: appCfg, defaults: initial, prefs, rules, stats, ses, partition: PARTITION, gen, userDir,
      icon: path.join(gen, 'icon.png'), adblockConfigured: !!engine, isEngineBlocked, log,
    });
    mainWin = shell.win;
    siteId = shell.site.webContents.id;
    const wc = shell.site.webContents;
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

    startRemote({ app: appCfg, defaults, obf, userDir, prefs, repo: shell.repo, log });
    installScreenshot(wc, stats, log);
    installStorageCheck(wc, log);
    shell.start();
    shell.health.refresh();
  });
}
