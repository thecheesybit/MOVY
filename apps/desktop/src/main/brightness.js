'use strict';
// Fullscreen-video brightness for the desktop app: Alt+Up / Alt+Down / Alt+0 (also in the View menu) while a video is
// fullscreen. The picture is filtered, the app draws nothing over it except a small pill while changing. Neutral (100%)
// adds no CSS at all, so by default the video is shown exactly as the site sends it.
const { WebContentsView } = require('electron');
const b = require('../core/brightness');

const HUD_W = 56;
const HUD_H = 176;
const HUD_MARGIN = 28;
const HUD_HIDE_MS = 900;

const dataUrl = (html) => 'data:text/html;charset=utf-8,' + encodeURIComponent(html);

/** Small transparent view at the left edge, visible only while the value is changing. */
function createHud() {
  const view = new WebContentsView({ webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false } });
  view.setBackgroundColor('#00000000');
  view.setVisible(false);
  const html =
    '<body style="margin:0;background:transparent;overflow:hidden;font:700 13px system-ui,sans-serif;color:#fff">' +
    '<div style="width:100vw;height:100vh;box-sizing:border-box;border-radius:28px;background:rgba(0,0,0,.7);display:flex;flex-direction:column;align-items:center;padding:14px 0 10px">' +
    '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="4.5"/>' +
    '<path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"/></svg>' +
    '<div style="flex:1;width:6px;margin:10px 0;border-radius:3px;background:rgba(255,255,255,.25);position:relative;overflow:hidden">' +
    '<div id=f style="position:absolute;left:0;right:0;bottom:0;height:50%;background:#fff;border-radius:3px"></div></div>' +
    '<span id=p>100%</span></div></body>';
  const ready = view.webContents.loadURL(dataUrl(html)).catch(() => {});
  return {
    view,
    paint(fraction, text) {
      const f = Math.max(0, Math.min(1, fraction));
      return ready
        .then(() => view.webContents.executeJavaScript(`f.style.height='${(f * 100).toFixed(1)}%';p.textContent=${JSON.stringify(text)}`))
        .catch(() => {});
    },
  };
}

/**
 * @param {{wc:Electron.WebContents, win:Electron.BrowserWindow, prefs:{get:Function,set:Function}}} o
 * @returns {{adjust(dir:number):void, reset():void, setFullscreen(on:boolean):void}}
 */
function createBrightness({ wc, win, prefs }) {
  const hud = createHud();
  win.contentView.addChildView(hud.view); // topmost, so it sits above the site while fullscreen
  let value = b.normalize(prefs.get('videoBrightness'));
  let fullscreen = false;
  let cssKey = null;
  let hideTimer = null;
  let applying = Promise.resolve();

  const layoutHud = () => {
    const { height } = win.contentView.getBounds();
    hud.view.setBounds({ x: HUD_MARGIN, y: Math.round((height - HUD_H) / 2), width: HUD_W, height: HUD_H });
  };
  win.on('resize', layoutHud);
  win.on('enter-full-screen', layoutHud);
  win.on('leave-full-screen', layoutHud);
  layoutHud();

  /** Serialised so quick key repeats never leave two stylesheets behind. */
  function apply() {
    applying = applying.then(async () => {
      try {
        if (cssKey) {
          const old = cssKey;
          cssKey = null;
          await wc.removeInsertedCSS(old);
        }
        const rule = fullscreen ? b.css(value) : null;
        if (rule) cssKey = await wc.insertCSS(rule);
      } catch {
        cssKey = null; // page gone mid-navigation: the next fullscreen applies again
      }
    });
    return applying;
  }

  function flash() {
    layoutHud();
    hud.paint((value - b.MIN) / (b.MAX - b.MIN), `${b.percent(value)}%`);
    hud.view.setVisible(true);
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => hud.view.setVisible(false), HUD_HIDE_MS);
  }

  function set(next) {
    value = b.normalize(next);
    prefs.set('videoBrightness', value);
    apply();
    flash();
  }

  return {
    /** dir > 0 brighter, dir < 0 dimmer. Only while a video is fullscreen. */
    adjust(dir) {
      if (fullscreen) set(b.step(value, dir));
    },
    reset() {
      if (fullscreen) set(b.DEFAULT);
    },
    setFullscreen(on) {
      fullscreen = on;
      if (!on) {
        clearTimeout(hideTimer);
        hud.view.setVisible(false);
      }
      apply();
    },
  };
}

module.exports = { createBrightness };
