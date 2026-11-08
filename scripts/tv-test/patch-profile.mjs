// usage: node patch-profile.mjs <id> '<json merge>'  -> merges into config/apps/movy.json tvProfiles[id] and the generated asset
import fs from 'node:fs';
const [id, patch] = process.argv.slice(2);
const p = JSON.parse(patch);
const files = ['config/apps/movy.json', 'apps/mobile/android/app/src/main/assets/movy-defaults.json'];
for (const f of files) {
  const j = JSON.parse(fs.readFileSync(f, 'utf8'));
  const cur = j.tvProfiles[id] ?? {};
  for (const [k, v] of Object.entries(p)) {
    if (k === 'player') cur.player = { ...(cur.player ?? {}), ...v };
    else if (k.startsWith('+')) cur[k.slice(1)] = [...new Set([...(cur[k.slice(1)] ?? []), ...v])];
    else cur[k] = v;
  }
  j.tvProfiles[id] = cur;
  fs.writeFileSync(f, JSON.stringify(j, null, f.startsWith('config') ? 2 : 0) + '\n');
  console.log('patched', f);
}
