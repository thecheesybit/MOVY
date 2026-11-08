// config -> filters -> napi binding -> apps/desktop/gen -> electron-builder -> dist/<app>/desktop
import fs from 'node:fs';
import path from 'node:path';
import { loadResolved } from '@movy/config';
import { DESKTOP_DIR, DIST, ROOT } from './paths.mjs';
import { buildEngine } from './filters.mjs';
import { ensureNodeBinding } from './desktop/native.mjs';
import { GEN, writeGenerated } from './desktop/generate.mjs';
import { electronBuilderConfig } from './desktop/builder-config.mjs';

/** Builds the Windows desktop app (NSIS installer + unpacked dir); returns the installer path. */
export async function buildDesktop(app, opts = {}) {
  console.log(`\n=== desktop: ${app} ===`);
  const cfg = loadResolved(ROOT, app, 'desktop');
  const engine = cfg.adblock.enabled ? await buildEngine(cfg.adblock) : null;
  const nodeFile = engine ? ensureNodeBinding() : null;
  writeGenerated(cfg, engine, nodeFile, opts.buildNumber ?? cfg.versionCode);

  const outDir = path.join(DIST, app, 'desktop');
  fs.rmSync(outDir, { recursive: true, force: true });
  const { build, Platform, Arch } = await import('electron-builder');
  await build({
    targets: Platform.WINDOWS.createTarget(['nsis', 'dir'], Arch.x64),
    projectDir: DESKTOP_DIR,
    config: electronBuilderConfig(cfg, { outDir, gen: GEN }),
    publish: 'never',
  });

  const installer = fs.readdirSync(outDir).find((f) => f.endsWith('.exe') && f.includes('Setup'));
  if (!installer) throw new Error(`electron-builder finished but no installer was found in ${outDir}`);
  const out = path.join(outDir, installer);
  // Stable, documented name next to the Android artifacts: dist/<app>/release/<AppName>-<version>-desktop-setup.exe
  const releaseDir = path.join(DIST, app, 'release');
  fs.mkdirSync(releaseDir, { recursive: true });
  const named = path.join(releaseDir, `${cfg.appName}-${cfg.version}-desktop-setup.exe`);
  fs.copyFileSync(out, named);
  console.log(`[release] ${named}`);
  console.log(`[done] ${out} (${(fs.statSync(out).size / 1048576).toFixed(1)} MiB); unpacked: ${path.join(outDir, 'win-unpacked')}`);
  return named;
}
