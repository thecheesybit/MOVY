'use strict';
// Assembles the window: site view, island, panel (splash / chooser / settings / error), launch flow, failover and
// the live-config effects. All decisions live in src/core; this file only wires them to Electron.
const { BrowserWindow, app } = require('electron');
const windowState = require('./window-state');
const { Views } = require('./views');
const { createUiBridge } = require('./ui-bridge');
const { installShortcuts } = require('./shortcuts');
const { installContextMenu } = require('./context-menu');
const { installMenu } = require('./menu');
const { SourceRepository } = require('../core/repository');
const { IslandVisibility } = require('../core/island-visibility');
const { createNavigationPolicy } = require('./navigation');
const { SourceHealth } = require('./health');
const storage = require('./storage');
const live = require('../core/live-config');

const ERR_ABORTED = -3;
const WATCHDOG_MS = 15_000;
const SPLASH_CAP_MS = 2500;
const PANEL_FADE_MS = 320;
// Developer flag --hold-splash=<ms>: keeps the splash up longer (screenshots). Capped at 10 s.
const HOLD_MS = Math.min(10_000, Number((process.argv.find((a) => a.startsWith('--hold-splash=')) ?? '').slice(14)) || 0);

/** @returns {{win, site, repo, state, rules}} */
function createShell({ app: appCfg, defaults, prefs, rules, stats, ses, partition, gen, userDir, icon, adblockConfigured, isEngineBlocked, log }) {
  const saved = windowState.load(userDir);
  const win = new BrowserWindow({
    ...(saved.x !== undefined && saved.y !== undefined ? { x: saved.x, y: saved.y } : {}),
    width: saved.width,
    height: saved.height,
    minWidth: 480,
    minHeight: 320,
    title: appCfg.appName,
    icon,
    backgroundColor: appCfg.backgroundColor,
    autoHideMenuBar: true,
    show: false,
    webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false },
  });
  if (saved.maximized) win.maximize();
  windowState.track(win, userDir);
  win.on('page-title-updated', (e) => e.preventDefault());

  const repo = new SourceRepository(prefs, defaults);
  const views = new Views({ win, ses, background: appCfg.backgroundColor, prefs });
  const wc = views.site.webContents;
  const health = new SourceHealth(repo);
  const state = { htmlFullscreen: false };
  const ui = { panel: null, error: null, storageBytes: null, islandShown: false };
  let panelBase = null; // 'error' while the failure panel is what Settings returns to
  let splashAt = 0;
  let watchdog = null;
  let hideTimer = null;
  let statsTimer = null;
  let bridge = null;

  // ---- island visibility
  const visibility = new IslandVisibility({
    repo,
    onChange: (shown) => {
      ui.islandShown = shown;
      // The island view sizes itself (ui:island-view) so its animations are never cut off.
      bridge?.pushNow();
    },
  });
  repo.addListener({ onActiveSourceChanged: (s) => visibility.onActiveSourceChanged(s) });

  // ---- panel layer
  function setPanel(mode) {
    clearTimeout(hideTimer);
    ui.panel = mode;
    visibility.setBlocked(mode === 'splash' || mode === 'chooser' || mode === 'settings');
    visibility.setError(mode === 'error');
    if (mode) {
      views.showPanel();
      bridge.pushNow();
    } else {
      bridge.pushNow(); // the renderer fades out, then the layer is removed
      hideTimer = setTimeout(() => {
        if (ui.panel === null) views.hidePanel();
      }, PANEL_FADE_MS);
    }
    clearInterval(statsTimer);
    statsTimer = mode === 'settings' ? setInterval(() => bridge.pushState(), 1000) : null;
  }

  const loadMain = (url) => {
    rules.beginLoad(url);
    wc.loadURL(url).catch((e) => {
      if (e && e.code !== 'ERR_ABORTED') log(`[load] rejected: ${e.code ?? e.message}`);
    });
  };
  repo.setLoader((_s, url) => {
    if (ui.panel === 'error') setPanel(null);
    panelBase = null;
    ui.error = null;
    loadMain(url);
  });

  // ---- failure panel (never switches silently)
  function showFailure() {
    clearTimeout(watchdog);
    const active = repo.active();
    if (!active || ui.panel === 'chooser') return;
    health.refreshNow();
    const suggestion = health.suggestion(active.id);
    ui.error = { name: active.name, suggestionId: suggestion ? suggestion.id : null, suggestionName: suggestion ? suggestion.name : null };
    panelBase = 'error';
    setPanel('error');
    if (suggestion) bridge.event('pulse', { id: suggestion.id });
  }
  health.onChange(() => {
    const active = repo.active();
    if (ui.panel === 'error' && active && !ui.error.suggestionId) {
      const s = health.suggestion(active.id);
      if (s) ui.error = { ...ui.error, suggestionId: s.id, suggestionName: s.name };
    }
    bridge?.pushState();
  });

  // ---- launch flow
  function dropSplash() {
    if (ui.panel === 'splash') setPanel(panelBase);
  }
  function showChooser() {
    setPanel('chooser');
  }
  function startLaunch() {
    splashAt = Date.now();
    setPanel('splash');
    const launch = repo.launchSource();
    log(`[launch] ${launch ? 'saved source' : 'chooser'}`);
    if (launch) {
      repo.switchTo(launch.id);
      setTimeout(dropSplash, SPLASH_CAP_MS + HOLD_MS);
    } else {
      setTimeout(showChooser, repo.config().splashMinMs + HOLD_MS);
    }
  }
  const afterMinSplash = (fn) => setTimeout(fn, Math.max(0, repo.config().splashMinMs + HOLD_MS - (Date.now() - splashAt)));

  // ---- site events
  wc.on('did-start-loading', () => views.bar.start());
  // The 15 s watchdog only guards real main-frame navigations (not subframes or SPA route changes).
  wc.on('did-start-navigation', (e) => {
    if (!e.isMainFrame || e.isSameDocument || String(e.url).startsWith('chrome-error:')) return;
    clearTimeout(watchdog);
    watchdog = setTimeout(showFailure, WATCHDOG_MS);
  });
  wc.on('did-stop-loading', () => {
    clearTimeout(watchdog);
    views.bar.done();
  });
  wc.on('dom-ready', () => {
    clearTimeout(watchdog);
    if (ui.panel === 'splash') afterMinSplash(dropSplash);
  });
  wc.on('did-fail-load', (_e, code, desc, _url, isMainFrame) => {
    if (!isMainFrame || code === ERR_ABORTED) return;
    log(`[load] main frame failed (${code} ${desc})`);
    showFailure();
  });
  wc.on('did-navigate', (_e, url) => onUrl(url));
  wc.on('did-navigate-in-page', (_e, url, isMain) => isMain && onUrl(url));
  wc.on('did-start-navigation', (e) => {
    if (e.isMainFrame && !e.isSameDocument && ui.panel === 'error' && !String(e.url).startsWith('chrome-error:')) {
      panelBase = null;
      ui.error = null;
      setPanel(null);
    }
  });
  function onUrl(url) {
    repo.rememberUrl(url);
    visibility.onUrl(url);
  }

  // ---- fullscreen: HTML5 <-> window
  wc.on('enter-html-full-screen', () => {
    state.htmlFullscreen = true;
    win.setFullScreen(true);
    views.bar.view.setVisible(false);
    visibility.setFullscreen(true);
  });
  wc.on('leave-html-full-screen', () => {
    state.htmlFullscreen = false;
    win.setFullScreen(false);
    visibility.setFullscreen(false);
  });
  win.on('leave-full-screen', () => {
    if (state.htmlFullscreen) wc.executeJavaScript('document.fullscreenElement&&document.exitFullscreen()').catch(() => {});
  });

  // ---- live config
  repo.addListener({
    onConfigChanged: (cfg) => {
      rules.update({ allowedHosts: live.allAllowedHosts(cfg), startHosts: live.startHosts(cfg), authHosts: cfg.authHosts });
      visibility.refresh();
      bridge?.pushState();
    },
  });

  // ---- actions from the UI views
  const closeSettings = () => {
    if (ui.panel === 'settings') setPanel(panelBase);
  };
  const actions = {
    pick: (id) => {
      const a = repo.active();
      if (!a || a.id !== id) repo.switchTo(id);
    },
    switchSource: (id) => repo.switchTo(id),
    choose: (id, always) => {
      prefs.set('askEveryLaunch', !always);
      if (always) prefs.set('defaultSourceId', id);
      repo.switchTo(id);
      setPanel(null);
    },
    setDefault: (id) => {
      prefs.set('defaultSourceId', id);
      bridge.pushNow();
    },
    setAsk: (on) => {
      prefs.set('askEveryLaunch', on);
      bridge.pushNow();
    },
    setAdblock: (on) => {
      prefs.set('adblockEnabled', on);
      bridge.pushNow();
      wc.reload();
    },
    openSettings: () => {
      if (ui.panel === 'settings' || ui.panel === 'chooser' || ui.panel === 'splash') return;
      setPanel('settings');
      health.refresh();
      storage.usedBytes(userDir, partition).then((n) => {
        ui.storageBytes = n;
        bridge.pushState();
      });
    },
    closeSettings,
    retry: () => {
      const a = repo.active();
      if (a) loadMain(repo.lastUrl(a));
    },
    checkSources: () => health.refreshNow(),
    clearCache: async () => {
      await storage.clearCache(ses);
      ui.storageBytes = await storage.usedBytes(userDir, partition);
      bridge.event('toast', { text: 'Cache cleared' });
      bridge.pushState();
    },
    signOut: async () => {
      await storage.signOutEverywhere(ses, userDir);
      ui.storageBytes = await storage.usedBytes(userDir, partition);
      bridge.event('toast', { text: 'Signed out' });
      bridge.pushState();
      wc.reload();
    },
    islandExpanded: (on) => {
      if (on) health.refresh();
      else visibility.clearForced();
    },
    panelHidden: () => {
      if (ui.panel === null) views.hidePanel();
    },
    /** Ctrl+K / menu. */
    toggleIsland: () => {
      if (ui.panel === 'settings' || ui.panel === 'chooser' || ui.panel === 'splash') return;
      const wasShown = visibility.shown;
      if (!wasShown) visibility.toggleForced();
      setTimeout(() => bridge.event('toggle'), wasShown ? 0 : 120);
    },
  };

  bridge = createUiBridge({
    views, repo, prefs, health, stats, gen, adblockConfigured, ui, actions, log,
    app: { appName: appCfg.appName, version: appCfg.version, build: appCfg.build, remote: !!appCfg.remote },
  });

  // ---- policy, shortcuts, menu
  const policy = createNavigationPolicy({ rules, blockPopups: appCfg.adblock.blockPopups, isEngineBlocked, stats, loadMain, log });
  policy.attach(wc);
  const isSite = (c) => c.id === wc.id;
  for (const c of [wc, ...views.uiContents()]) {
    installShortcuts(c, win, state, actions, isSite(c));
    if (!isSite(c)) {
      c.on('will-navigate', (e) => e.preventDefault());
      c.setWindowOpenHandler(() => ({ action: 'deny' }));
    }
  }
  installContextMenu(wc, win);
  installMenu(wc, win, actions);
  wc.setVisualZoomLevelLimits(1, 3).catch(() => {});

  win.on('resize', () => bridge.pushState());
  // --inactive (developer runs): show without taking keyboard focus from whatever the developer is typing in.
  const reveal = () => (process.argv.includes('--inactive') ? win.showInactive() : win.show());
  win.once('ready-to-show', reveal);
  reveal();
  app.on('before-quit', () => prefs.flush());

  return { win, site: views.site, repo, health, actions, start: startLaunch };
}

module.exports = { createShell };
