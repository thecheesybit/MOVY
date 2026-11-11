'use strict';
const { Menu, app } = require('electron');

/** Minimal menu bar (hidden until Alt is pressed). Keyboard handling lives in shortcuts.js (no accelerators here). */
function installMenu(wc, win, actions) {
  const nav = wc.navigationHistory;
  const zoom = (d) => wc.setZoomLevel(d === 0 ? 0 : wc.getZoomLevel() + d);
  const hint = (accelerator) => ({ accelerator, registerAccelerator: false });
  const template = [
    {
      label: 'File',
      submenu: [{ label: 'Reload', ...hint('F5'), click: () => wc.reload() }, { type: 'separator' }, { label: 'Quit', click: () => app.quit() }],
    },
    { label: 'Edit', submenu: [{ role: 'undo' }, { role: 'redo' }, { type: 'separator' }, { role: 'cut' }, { role: 'copy' }, { role: 'paste' }, { role: 'selectAll' }] },
    {
      label: 'View',
      submenu: [
        { label: 'Sources', ...hint('CommandOrControl+K'), click: () => actions.toggleIsland() },
        { label: 'Settings', ...hint('CommandOrControl+,'), click: () => actions.openSettings() },
        { type: 'separator' },
        { label: 'Zoom in', click: () => zoom(0.5) },
        { label: 'Zoom out', click: () => zoom(-0.5) },
        { label: 'Actual size', click: () => zoom(0) },
        { type: 'separator' },
        { label: 'Video brightness up', ...hint('Alt+Up'), click: () => actions.adjustBrightness(1) },
        { label: 'Video brightness down', ...hint('Alt+Down'), click: () => actions.adjustBrightness(-1) },
        { label: 'Video brightness reset', ...hint('Alt+0'), click: () => actions.resetBrightness() },
        { type: 'separator' },
        { label: 'Full screen', ...hint('F11'), click: () => win.setFullScreen(!win.isFullScreen()) },
      ],
    },
    {
      label: 'History',
      submenu: [
        { label: 'Back', click: () => nav.canGoBack() && nav.goBack() },
        { label: 'Forward', click: () => nav.canGoForward() && nav.goForward() },
      ],
    },
  ];
  win.setMenu(Menu.buildFromTemplate(template));
}

module.exports = { installMenu };
