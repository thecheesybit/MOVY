'use strict';
// A real-Chrome user agent: drop the "Electron/x" token and the app-name token Electron inserts.

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** @param {string} ua session.defaultUserAgent  @param {string[]} appTokens names to strip (app name, package name) */
function chromeUserAgent(ua, appTokens = []) {
  let out = ua.replace(/\s+Electron\/\S+/g, '');
  for (const name of appTokens) {
    if (name) out = out.replace(new RegExp(`\\s+${escapeRegExp(name)}\\/\\S+`, 'gi'), '');
  }
  // Safety net: any other "name/version" token directly before Chrome/.
  out = out.replace(/\s+[^\s/]+\/\S+(?=\s+Chrome\/)/g, '');
  return out.replace(/\s{2,}/g, ' ').trim();
}

module.exports = { chromeUserAgent };
