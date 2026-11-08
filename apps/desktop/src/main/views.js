'use strict';
// The window's layers, bottom to top: [site] [panel (splash/chooser/settings/error), only while shown] [island] [bar].
// The island is its own small transparent view whose bounds follow its state, so the rest of the window (site) keeps
// receiving every click and scroll. Nothing here renders a URL.
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { WebContentsView } = require('electron');
const { uiDir, uiPreloadPath, preloadPath } = require('./paths');
const { createProgressBar } = require('./overlays');

const ISLAND_COLLAPSED = Object.freeze({ w: 28, h: 88 });
const ISLAND_EXPANDED_W = 150;
const ISLAND_MARGIN = 8;
const BAR_H = 3;

const uiPrefs = () => ({ backgroundThrottling: false, preload: uiPreloadPath(), sandbox: true, contextIsolation: true, nodeIntegration: false });
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

class Views {
  /** @param {{win:Electron.BrowserWindow, ses:Electron.Session, background:string, prefs:object}} o */
  constructor({ win, ses, background, prefs }) {
    this.win = win;
    this.prefs = prefs;
    this.panelShown = false;
    this.islandMode = 'hidden';
    this.islandHeight = 360;
    this.dragTop = null;

    this.site = new WebContentsView({
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
    this.site.setBackgroundColor(background);
    this.panel = this.makeUi('panel.html', '#00000000');
    this.island = this.makeUi('island.html', '#00000000');
    this.island.setVisible(false);
    this.bar = createProgressBar();

    win.contentView.addChildView(this.site);
    win.contentView.addChildView(this.island);
    win.contentView.addChildView(this.bar.view);
    win.on('resize', () => this.layout());
    win.on('enter-full-screen', () => this.layout());
    win.on('leave-full-screen', () => this.layout());
    this.layout();
  }

  makeUi(file, background) {
    const view = new WebContentsView({ webPreferences: uiPrefs() });
    view.setBackgroundColor(background);
    view.webContents.loadURL(pathToFileURL(path.join(uiDir(), file)).href).catch(() => {});
    return view;
  }

  uiContents() {
    return [this.panel.webContents, this.island.webContents];
  }

  size() {
    const { width, height } = this.win.contentView.getBounds();
    return { width, height };
  }

  layout() {
    const { width, height } = this.size();
    const full = { x: 0, y: 0, width, height };
    this.site.setBounds(full);
    this.panel.setBounds(full);
    this.bar.view.setBounds({ x: 0, y: 0, width, height: BAR_H });
    this.applyIsland();
  }

  showPanel() {
    if (this.panelShown) return;
    this.panelShown = true;
    this.win.contentView.addChildView(this.panel, 1);
  }

  hidePanel() {
    if (!this.panelShown) return;
    this.panelShown = false;
    this.win.contentView.removeChildView(this.panel);
  }

  /** mode: 'hidden' | 'collapsed' | 'expanded'. Resolves once the bounds are applied. */
  setIslandMode(mode, height) {
    this.islandMode = mode;
    if (Number.isFinite(height)) this.islandHeight = clamp(Math.round(height), 120, 900);
    this.applyIsland();
  }

  /** Top edge of the island view for the saved vertical position (fraction of the window height). */
  islandTop(h) {
    const { height } = this.size();
    const centre = this.prefs.get('islandY') * height;
    return Math.round(clamp(centre - h / 2, ISLAND_MARGIN, Math.max(ISLAND_MARGIN, height - h - ISLAND_MARGIN)));
  }

  applyIsland() {
    const view = this.island;
    if (this.islandMode === 'hidden') {
      view.setVisible(false);
      return;
    }
    const { width, height } = this.size();
    const expanded = this.islandMode === 'expanded';
    const w = expanded ? ISLAND_EXPANDED_W : ISLAND_COLLAPSED.w;
    const h = Math.min(expanded ? this.islandHeight : ISLAND_COLLAPSED.h, height - 2 * ISLAND_MARGIN);
    const top = this.dragTop ?? this.islandTop(h);
    view.setBounds({ x: width - w, y: top, width: w, height: h });
    view.setVisible(true);
  }

  dragStart() {
    this.dragTop = this.island.getBounds().y;
    this.dragBase = this.dragTop;
  }

  dragMove(dy) {
    if (this.dragTop === null) return;
    const { height } = this.size();
    const h = this.island.getBounds().height;
    this.dragTop = clamp(Math.round(this.dragBase + dy), ISLAND_MARGIN, Math.max(ISLAND_MARGIN, height - h - ISLAND_MARGIN));
    this.applyIsland();
  }

  /** Ends a drag and persists the new vertical position as a fraction. */
  dragEnd() {
    if (this.dragTop === null) return;
    const { height } = this.size();
    const h = this.island.getBounds().height;
    this.prefs.set('islandY', Math.round(((this.dragTop + h / 2) / height) * 1000) / 1000);
    this.dragTop = null;
    this.applyIsland();
  }
}

module.exports = { Views };
