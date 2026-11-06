'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { WebContentsView } = require('electron');
const { uiDir } = require('./paths');

const RED = '#E50914';
const PREFS = { sandbox: true, contextIsolation: true, nodeIntegration: false };

const esc = (s) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

function isLight(hex) {
  const n = parseInt(hex.replace('#', '').padEnd(6, '0').slice(0, 6), 16);
  const lum = 0.2126 * ((n >> 16) & 255) + 0.7152 * ((n >> 8) & 255) + 0.0722 * (n & 255);
  return lum > 150;
}

const dataUrl = (html) => 'data:text/html;charset=utf-8,' + encodeURIComponent(html);

/** Branded splash (background + app name + red spinner), shown above the site until it has loaded. */
function createSplash(cfg) {
  const tpl = fs.readFileSync(path.join(uiDir(), 'splash.html'), 'utf8');
  const html = tpl
    .replaceAll('{{APP_NAME}}', esc(cfg.appName))
    .replaceAll('{{BG}}', cfg.backgroundColor)
    .replaceAll('{{FG}}', isLight(cfg.backgroundColor) ? '#111111' : '#f2f2f2');
  const view = new WebContentsView({ webPreferences: PREFS });
  view.setBackgroundColor(cfg.backgroundColor);
  view.webContents.loadURL(dataUrl(html));
  return view;
}

/** Thin red progress bar at the top edge; only visible (and clickable) while a navigation runs. */
function createProgressBar() {
  const view = new WebContentsView({ webPreferences: PREFS });
  view.setBackgroundColor('#00000000');
  view.setVisible(false);
  const html =
    `<body style="margin:0;background:transparent;overflow:hidden"><div id=b style="height:3px;width:0;background:${RED};` +
    `transition:width .25s ease-out,opacity .3s;opacity:0"></div></body>`;
  const ready = view.webContents.loadURL(dataUrl(html)).catch(() => {});
  let timer = null;
  let p = 0;
  const paint = (width, opacity) =>
    ready.then(() => view.webContents.executeJavaScript(`b.style.width='${width}%';b.style.opacity=${opacity}`)).catch(() => {});

  return {
    view,
    start() {
      clearInterval(timer);
      p = 0.08;
      view.setVisible(true);
      paint(8, 1);
      timer = setInterval(() => {
        p += (0.9 - p) * 0.07;
        paint(Math.round(p * 100), 1);
      }, 220);
    },
    done() {
      clearInterval(timer);
      timer = null;
      paint(100, 1);
      setTimeout(() => paint(100, 0), 150);
      setTimeout(() => {
        if (!timer && !view.webContents.isDestroyed()) view.setVisible(false);
      }, 500);
    },
  };
}

module.exports = { createSplash, createProgressBar };
