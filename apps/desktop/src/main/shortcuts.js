'use strict';

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

/** Browser-like keyboard shortcuts for the site's webContents. */
function installShortcuts(wc, win, state) {
  const nav = wc.navigationHistory;
  const zoom = (delta) => wc.setZoomLevel(delta === 0 ? 0 : clamp(wc.getZoomLevel() + delta, -3, 5));

  wc.on('before-input-event', (event, input) => {
    if (input.type !== 'keyDown') return;
    const ctrl = input.control || input.meta;
    const key = input.key;
    let handled = true;

    if (key === 'F11') win.setFullScreen(!win.isFullScreen());
    else if (key === 'Escape' && win.isFullScreen() && !state.htmlFullscreen) win.setFullScreen(false);
    else if (key === 'F5' || (ctrl && !input.shift && key.toLowerCase() === 'r')) wc.reload();
    else if ((ctrl && input.shift && key.toLowerCase() === 'r') || (ctrl && key === 'F5')) wc.reloadIgnoringCache();
    else if (input.alt && key === 'ArrowLeft') nav.canGoBack() && nav.goBack();
    else if (input.alt && key === 'ArrowRight') nav.canGoForward() && nav.goForward();
    else if (ctrl && (key === '=' || key === '+')) zoom(0.5);
    else if (ctrl && (key === '-' || key === '_')) zoom(-0.5);
    else if (ctrl && key === '0') zoom(0);
    else handled = false;

    if (handled) event.preventDefault();
  });
}

module.exports = { installShortcuts };
