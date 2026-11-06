'use strict';
const { ipcMain } = require('electron');
const { isHttp } = require('./hosts');

const MAX_LIST = 20000;
const strings = (a) => (Array.isArray(a) ? a.filter((s) => typeof s === 'string').slice(0, MAX_LIST) : []);

/**
 * Sync IPC used by the site preload (the only channel the remote frames have into the main process).
 * Only webContents accepted by `isSite` are served; URLs come from the sender frame, not the renderer.
 */
function installCosmeticIpc({ engine, cosmetic, assets, isSite, stats }) {
  const trusted = (e) => isSite(e.sender);

  ipcMain.on('movy:boot', (e) => {
    e.returnValue = trusted(e)
      ? { cosmetic: !!(cosmetic && engine), cosmeticJs: assets.cosmeticJs, loadingJs: assets.loadingJs }
      : null;
  });

  ipcMain.on('movy:cosmetic', (e) => {
    let out = '{}';
    try {
      const url = e.senderFrame?.url ?? '';
      if (trusted(e) && engine && cosmetic && isHttp(url)) {
        out = engine.urlCosmeticResources(url);
        stats.recordCosmetic(JSON.parse(out));
      }
    } catch {
      out = '{}';
    }
    e.returnValue = out;
  });

  ipcMain.on('movy:hidden', (e, classes, ids, exceptions) => {
    let out = '[]';
    try {
      if (trusted(e) && engine && cosmetic) {
        out = engine.hiddenClassIdSelectors(strings(classes), strings(ids), strings(exceptions));
      }
    } catch {
      out = '[]';
    }
    e.returnValue = out;
  });
}

module.exports = { installCosmeticIpc };
