'use strict';
// Last-good remote config on disk (userData/remote-last-good.dat). Obfuscated with the build key so source URLs are
// not stored as plain text. A broken or invalid file is ignored.
const fs = require('node:fs');
const path = require('node:path');

const FILE = 'remote-last-good.dat';

function saveLastGood(dir, obf, json) {
  try {
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, FILE), obf.encode(json));
  } catch {
    /* best effort */
  }
}

/** @returns {string|null} */
function loadLastGood(dir, obf) {
  try {
    return obf.decode(fs.readFileSync(path.join(dir, FILE), 'utf8'));
  } catch {
    return null;
  }
}

module.exports = { saveLastGood, loadLastGood };
