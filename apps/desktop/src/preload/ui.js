'use strict';
// Preload of the app's own UI views (island, panel). Exposes a tiny, fixed API; no Node, no arbitrary channels.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('movy', {
  state: () => ipcRenderer.invoke('ui:state'),
  act: (type, payload) => ipcRenderer.send('ui:act', { type, ...(payload && typeof payload === 'object' ? payload : {}) }),
  islandView: (mode, height) => ipcRenderer.invoke('ui:island-view', { mode, height }),
  onPush: (cb) => ipcRenderer.on('ui:push', (_e, msg) => cb(msg)),
});
