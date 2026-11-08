'use strict';
// Firebase Remote Config client: cached last-good config applied synchronously at startup, then a REST poll.
const { net } = require('electron');
const live = require('../core/live-config');
const { RemotePoller } = require('../core/remote-rest');
const { saveLastGood, loadLastGood } = require('./live-store');

const FETCH_TIMEOUT_MS = 10_000;

async function post({ url, headers, body }) {
  const res = await net.fetch(url, { method: 'POST', headers, body, credentials: 'omit', signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
  return { status: res.status, body: await res.text() };
}

/** Defaults merged with the cached last-good remote config (an invalid cache is ignored). */
function initialConfig({ defaults, obf, userDir, log }) {
  const cached = loadLastGood(userDir, obf);
  if (!cached) return defaults;
  try {
    return live.merge(defaults, cached, obf.decode);
  } catch (e) {
    log('[remote] cached config ignored:', e.message);
    return defaults;
  }
}

/** Starts polling; each valid config is merged over the *defaults* and handed to `repo.update`. No-op without creds. */
function startRemote({ app, defaults, obf, userDir, prefs, repo, log }) {
  if (!app.remote) return null;
  const poller = new RemotePoller({
    creds: app.remote,
    parameter: app.remote.parameter,
    instanceId: prefs.instanceId(),
    post,
    log,
    apply: (json) => {
      try {
        const next = live.merge(defaults, json, obf.decode);
        saveLastGood(userDir, obf, json);
        repo.update(next);
        log(`[remote] applied config v${next.contentVersion}`);
      } catch (e) {
        log('[remote] config rejected, keeping the last good one:', e.message);
      }
    },
  });
  poller.start();
  return poller;
}

module.exports = { initialConfig, startRemote };
