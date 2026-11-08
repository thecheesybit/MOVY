'use strict';
// IPC between the main process and the app's own UI views (island, panel). The views are pure renderers of a state
// snapshot (core/ui-state.js, no URLs); every action they send is validated here before it reaches `actions`.
const fs = require('node:fs');
const path = require('node:path');
const { ipcMain } = require('electron');
const { buildUiState } = require('../core/ui-state');

const PUSH_MS = 40;
const MODES = new Set(['hidden', 'collapsed', 'expanded']);

/**
 * @param {{views:import('./views').Views, repo:object, prefs:object, health:object, stats:object, app:object, gen:string,
 *          adblockConfigured:boolean, ui:{panel:string|null, error:object|null, storageBytes:number|null, islandShown:boolean},
 *          actions:object, log:Function}} o
 */
function createUiBridge({ views, repo, prefs, health, stats, app, gen, adblockConfigured, ui, actions, log }) {
  const targets = () => views.uiContents().filter((wc) => !wc.isDestroyed());
  const isUi = (wc) => targets().some((t) => t.id === wc.id);
  let icon = null;
  try {
    icon = 'data:image/png;base64,' + fs.readFileSync(path.join(gen, 'icon.png')).toString('base64');
  } catch {
    log('[ui] icon missing');
  }

  const snapshot = () => ({
    ...buildUiState({
      app,
      repo,
      prefs,
      healthOf: (id) => health.status(id),
      stats,
      adblockConfigured,
      storageBytes: ui.storageBytes,
      checking: health.isChecking(),
      island: { shown: ui.islandShown },
      suggestion: ui.error ? ui.error.suggestionId : null,
    }),
    panel: ui.panel,
    error: ui.error,
    icon,
  });

  let timer = null;
  const send = (msg) => targets().forEach((wc) => wc.send('ui:push', msg));
  /** Coalesced snapshot push to both views. */
  const pushState = () => {
    if (timer) return;
    timer = setTimeout(() => {
      timer = null;
      send({ type: 'state', state: snapshot() });
    }, PUSH_MS);
  };

  ipcMain.handle('ui:state', (e) => (isUi(e.sender) ? snapshot() : null));

  ipcMain.handle('ui:island-view', (e, msg) => {
    if (!isUi(e.sender) || !msg || !MODES.has(msg.mode)) return false;
    views.setIslandMode(msg.mode, msg.height);
    return true;
  });

  const str = (v) => (typeof v === 'string' && v.length <= 60 ? v : null);
  const known = (v) => {
    const id = str(v);
    const s = id ? repo.byId(id) : null;
    return s && s.enabled ? s.id : null;
  };

  ipcMain.on('ui:act', (e, m) => {
    if (!isUi(e.sender) || !m || typeof m.type !== 'string') return;
    try {
      switch (m.type) {
        case 'pick': { const id = known(m.id); if (id) actions.pick(id); break; }
        case 'switch': { const id = known(m.id); if (id) actions.switchSource(id); break; }
        case 'choose': { const id = known(m.id); if (id) actions.choose(id, m.always === true); break; }
        case 'set-default': { const id = known(m.id); if (id) actions.setDefault(id); break; }
        case 'set-ask': actions.setAsk(m.on === true); break;
        case 'set-adblock': actions.setAdblock(m.on === true); break;
        case 'settings': actions.openSettings(); break;
        case 'close-settings': actions.closeSettings(); break;
        case 'retry': actions.retry(); break;
        case 'check-sources': actions.checkSources(); break;
        case 'clear-cache': actions.clearCache(); break;
        case 'sign-out': actions.signOut(); break;
        case 'island-expanded': actions.islandExpanded(m.on === true); break;
        case 'panel-hidden': actions.panelHidden(); break;
        case 'island-drag':
          if (m.phase === 'start') views.dragStart();
          else if (m.phase === 'move' && Number.isFinite(m.dy)) views.dragMove(m.dy);
          else if (m.phase === 'end') views.dragEnd();
          break;
        default: break;
      }
    } catch (err) {
      log(`[ui] action ${m.type} failed:`, err.message);
    }
  });

  return {
    pushState,
    /** Tells the views to play a transient animation / behaviour ('toggle' island, 'toast' text). */
    event: (type, extra = {}) => send({ type, ...extra }),
    /** Pushes immediately (no coalescing), for transitions that must not lag. */
    pushNow: () => {
      clearTimeout(timer);
      timer = null;
      send({ type: 'state', state: snapshot() });
    },
  };
}

module.exports = { createUiBridge };
