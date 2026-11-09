// usage: node scripts/tv-test/rebuild-engine.mjs  -> rebuilds the ad engine from config/apps/movy.json (lists + customRules)
// and copies engine.dat / resources.json into the TV assets (git-ignored), without a full app build.
import fs from 'node:fs';
import path from 'node:path';
import { loadResolved } from '@movy/config';
import { ROOT } from '../paths.mjs';
import { buildEngine } from '../filters.mjs';

const cfg = loadResolved(ROOT, 'movy', 'tv');
const engine = await buildEngine(cfg.adblock);
const dir = path.join(ROOT, 'apps', 'mobile', 'android', 'app', 'src', 'main', 'assets', 'adblock');
fs.copyFileSync(engine.engineDat, path.join(dir, 'engine.dat'));
fs.copyFileSync(engine.resourcesJson, path.join(dir, 'resources.json'));
console.log(`engine ${engine.cached ? '(cached) ' : ''}rebuilt with ${cfg.adblock.customRules.length} custom rules -> ${fs.statSync(path.join(dir, 'engine.dat')).size} bytes`);
