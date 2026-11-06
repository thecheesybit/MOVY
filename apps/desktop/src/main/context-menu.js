'use strict';
const { Menu, clipboard } = require('electron');

/** Small right-click menu: navigation, clipboard and "open link in browser". */
function installContextMenu(wc, win, policy) {
  wc.on('context-menu', (_e, p) => {
    const nav = wc.navigationHistory;
    const t = [];

    if (p.linkURL) {
      t.push({ label: 'Open link in browser', click: () => policy.openExternal(p.linkURL) });
      t.push({ label: 'Copy link address', click: () => clipboard.writeText(p.linkURL) });
      t.push({ type: 'separator' });
    }
    if (p.mediaType === 'image') {
      t.push({ label: 'Copy image', click: () => wc.copyImageAt(p.x, p.y) });
      t.push({ type: 'separator' });
    }
    if (p.isEditable) {
      t.push({ role: 'cut', enabled: p.editFlags.canCut }, { role: 'copy', enabled: p.editFlags.canCopy });
      t.push({ role: 'paste', enabled: p.editFlags.canPaste }, { role: 'selectAll' }, { type: 'separator' });
    } else if (p.selectionText) {
      t.push({ role: 'copy' }, { type: 'separator' });
    }
    t.push(
      { label: 'Back', enabled: nav.canGoBack(), click: () => nav.goBack() },
      { label: 'Forward', enabled: nav.canGoForward(), click: () => nav.goForward() },
      { label: 'Reload', click: () => wc.reload() },
    );
    Menu.buildFromTemplate(t).popup({ window: win });
  });
}

module.exports = { installContextMenu };
