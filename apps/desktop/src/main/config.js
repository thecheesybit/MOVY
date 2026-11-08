'use strict';
// Loads the generated app-config.json: static app facts + the default live config (source URLs obfuscated).
const fs = require('node:fs');
const path = require('node:path');
const { createObfuscator } = require('../core/obfuscator');
const live = require('../core/live-config');

/** @returns {{app:object, obf:object, defaults:object}} */
function loadAppConfig(gen) {
  const raw = JSON.parse(fs.readFileSync(path.join(gen, 'app-config.json'), 'utf8'));
  const obf = createObfuscator(raw.obfKey);
  const defaults = live.merge(null, raw.defaults, obf.decode);
  return { app: raw, obf, defaults };
}

module.exports = { loadAppConfig };
