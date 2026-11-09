// usage: node patch-profiles.mjs <file.json>  -> {"<source id>": {<profile keys>}, ...}; each entry goes through patch-profile.mjs
// (merged into config/apps/movy.json and the generated movy-defaults.json asset, so both stay in sync).
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';

const all = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
for (const [id, patch] of Object.entries(all)) {
  execFileSync(process.execPath, ['scripts/tv-test/patch-profile.mjs', id, JSON.stringify(patch)], { stdio: 'inherit' });
}
