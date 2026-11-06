// Writes apps/desktop/gen/: everything the Electron app reads at runtime (packaged to resources/movy).
import fs from 'node:fs';
import path from 'node:path';
import { DESKTOP_DIR, ROOT } from '../paths.mjs';
import { writeDesktopIcon } from './icon.mjs';

export const GEN = path.join(DESKTOP_DIR, 'gen');

const escHtml = (s) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

/** Offline/error page from the same template as Android. */
function offlineHtml(cfg) {
  const tpl = fs.readFileSync(path.join(ROOT, 'apps', 'mobile', 'offline.template.html'), 'utf8');
  return tpl
    .replaceAll('{{APP_NAME}}', escHtml(cfg.appName))
    .replaceAll('{{BG}}', cfg.backgroundColor)
    .replace('{{URL_JSON}}', JSON.stringify(cfg.url).replaceAll('<', '\u003c'));
}

/** @param {object} cfg resolved desktop config  @param {{engineDat:string,resourcesJson:string}|null} engine  @param {string} nodeFile */
export function writeGenerated(cfg, engine, nodeFile) {
  fs.rmSync(GEN, { recursive: true, force: true });
  fs.mkdirSync(GEN, { recursive: true });
  const adblockOn = cfg.adblock.enabled && !!engine;
  fs.writeFileSync(
    path.join(GEN, 'app-config.json'),
    JSON.stringify(
      {
        name: cfg.name,
        appName: cfg.appName,
        appId: cfg.appId,
        url: cfg.url,
        allowedHosts: cfg.allowedHosts,
        externalLinks: cfg.externalLinks,
        userAgent: cfg.userAgent,
        backgroundColor: cfg.backgroundColor,
        adblock: { enabled: adblockOn, cosmetic: cfg.adblock.cosmetic, blockPopups: cfg.adblock.blockPopups },
      },
      null,
      2,
    ) + '\n',
  );
  fs.writeFileSync(path.join(GEN, 'offline.html'), offlineHtml(cfg));
  for (const f of ['cosmetic.js', 'loading.js']) fs.copyFileSync(path.join(ROOT, 'packages', 'inject', f), path.join(GEN, f));
  writeDesktopIcon(cfg, ROOT, path.join(GEN, 'icon.png'));
  if (adblockOn) {
    fs.copyFileSync(engine.engineDat, path.join(GEN, 'engine.dat'));
    fs.copyFileSync(engine.resourcesJson, path.join(GEN, 'resources.json'));
    fs.copyFileSync(nodeFile, path.join(GEN, 'movy_adblock_node.node'));
  }
}
