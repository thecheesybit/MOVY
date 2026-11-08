'use strict';
const path = require('node:path');
const { app } = require('electron');

/** Folder with the generated, per-app files (app-config.json, engine.dat, ...). */
function genDir() {
  return app.isPackaged ? path.join(process.resourcesPath, 'movy') : path.join(__dirname, '..', '..', 'gen');
}

const uiDir = () => path.join(__dirname, '..', 'ui');
const preloadPath = () => path.join(__dirname, '..', 'preload', 'site.js');
const uiPreloadPath = () => path.join(__dirname, '..', 'preload', 'ui.js');

module.exports = { genDir, uiDir, preloadPath, uiPreloadPath };
