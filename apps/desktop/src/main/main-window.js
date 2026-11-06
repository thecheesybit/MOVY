'use strict';
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { BrowserWindow, WebContentsView } = require('electron');
const { preloadPath } = require('./paths');
const { createSplash, createProgressBar } = require('./overlays');
const windowState = require('./window-state');
const { installShortcuts } = require('./shortcuts');
const { installContextMenu } = require('./context-menu');
const { installMenu } = require('./menu');

const SPLASH_FALLBACK_MS = 8000;
const ERR_ABORTED = -3;

/**
 * One window: [site view] + [progress bar] + [splash], all stacked in the content area.
 * Returns { win, site, state } where site is the WebContentsView that shows the remote page.
 */
function createMainWindow({ cfg, sites, ses, gen, userDir, icon, policy, log }) {
  const saved = windowState.load(userDir);
  const win = new BrowserWindow({
    ...(saved.x !== undefined && saved.y !== undefined ? { x: saved.x, y: saved.y } : {}),
    width: saved.width,
    height: saved.height,
    minWidth: 480,
    minHeight: 320,
    title: cfg.appName,
    icon,
    backgroundColor: cfg.backgroundColor,
    autoHideMenuBar: true,
    show: false,
    webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false },
  });
  if (saved.maximized) win.maximize();
  windowState.track(win, userDir);
  win.on('page-title-updated', (e) => e.preventDefault());

  const site = new WebContentsView({
    webPreferences: {
      session: ses,
      preload: preloadPath(),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      nodeIntegrationInSubFrames: true, // preload in iframes; still sandboxed, so no Node anywhere
      webSecurity: true,
      allowRunningInsecureContent: false,
      autoplayPolicy: 'no-user-gesture-required',
    },
  });
  site.setBackgroundColor(cfg.backgroundColor);
  const wc = site.webContents;
  const bar = createProgressBar();
  const splash = createSplash(cfg);
  win.contentView.addChildView(site);
  win.contentView.addChildView(bar.view);
  win.contentView.addChildView(splash);

  const layout = () => {
    const { width, height } = win.contentView.getBounds();
    site.setBounds({ x: 0, y: 0, width, height });
    bar.view.setBounds({ x: 0, y: 0, width, height: 3 });
    splash.setBounds({ x: 0, y: 0, width, height });
  };
  win.on('resize', layout);
  win.on('enter-full-screen', layout);
  win.on('leave-full-screen', layout);
  layout();

  // --- splash: until the first page has loaded (or failed), with a time limit
  let splashShown = true;
  const dropSplash = () => {
    if (!splashShown) return;
    splashShown = false;
    clearTimeout(splashTimer);
    win.contentView.removeChildView(splash);
    splash.webContents.close();
  };
  const splashTimer = setTimeout(dropSplash, SPLASH_FALLBACK_MS);
  wc.once('did-finish-load', dropSplash);
  wc.once('did-fail-load', dropSplash);

  // --- progress bar
  wc.on('did-start-loading', () => bar.start());
  wc.on('did-stop-loading', () => bar.done());

  // --- offline / error page
  const offlineUrl = pathToFileURL(path.join(gen, 'offline.html')).href;
  wc.on('did-fail-load', (_e, code, desc, url, isMainFrame) => {
    if (!isMainFrame || code === ERR_ABORTED || url === offlineUrl) return;
    log(`[load] failed (${code} ${desc}); showing offline page`);
    wc.loadURL(offlineUrl);
  });

  // --- HTML5 fullscreen <-> window fullscreen
  const state = { htmlFullscreen: false };
  wc.on('enter-html-full-screen', () => {
    state.htmlFullscreen = true;
    win.setFullScreen(true);
  });
  wc.on('leave-html-full-screen', () => {
    state.htmlFullscreen = false;
    win.setFullScreen(false);
  });
  win.on('leave-full-screen', () => {
    if (state.htmlFullscreen) wc.executeJavaScript('document.fullscreenElement&&document.exitFullscreen()').catch(() => {});
  });

  policy.attach(wc);
  installShortcuts(wc, win, state);
  installContextMenu(wc, win, policy);
  installMenu(wc, win);
  wc.setVisualZoomLevelLimits(1, 3).catch(() => {});

  win.once('ready-to-show', () => win.show());
  win.show();
  return { win, site, state, offlineUrl };
}

module.exports = { createMainWindow };
