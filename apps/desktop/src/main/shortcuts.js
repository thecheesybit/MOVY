'use strict';

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

/**
 * Keyboard shortcuts. `app` keys (F11, Ctrl+K island, Ctrl+, settings) work from every view; the browser-like ones
 * (reload, back/forward, zoom) only apply to the site view (`wc` is the site when `isSite`).
 * actions = { toggleIsland(), openSettings() }.
 */
function installShortcuts(wc, win, state, actions, isSite) {
  const nav = wc.navigationHistory;
  const zoom = (delta) => wc.setZoomLevel(delta === 0 ? 0 : clamp(wc.getZoomLevel() + delta, -3, 5));

  wc.on('before-input-event', (event, input) => {
    if (input.type !== 'keyDown') return;
    const ctrl = input.control || input.meta;
    const key = input.key;
    const lower = key.toLowerCase();
    let handled = true;

    if (key === 'F11') win.setFullScreen(!win.isFullScreen());
    else if (ctrl && !input.shift && !input.alt && lower === 'k') actions.toggleIsland();
    else if (ctrl && !input.shift && key === ',') actions.openSettings();
    else if (!isSite) handled = false;
    else if (key === 'Escape' && win.isFullScreen() && !state.htmlFullscreen) win.setFullScreen(false);
    else if (key === 'F5' || (ctrl && !input.shift && lower === 'r')) wc.reload();
    else if ((ctrl && input.shift && lower === 'r') || (ctrl && key === 'F5')) wc.reloadIgnoringCache();
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
