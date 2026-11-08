// Writes apps/desktop/gen/: everything the Electron app reads at runtime (packaged to resources/movy).
// Source URLs are stored obfuscated (XOR with a per-build key + base64), never as plain strings.
import fs from 'node:fs';
import path from 'node:path';
import { DESKTOP_DIR, ROOT } from '../paths.mjs';
import { writeDesktopIcon } from './icon.mjs';
import { buildDefaults, newObfuscationKey, obfuscate } from '../android/defaults.mjs';

export const GEN = path.join(DESKTOP_DIR, 'gen');
const INJECT = ['cosmetic.js', 'loading.js', 'scamguard.js', 'navguard.js'];

/**
 * Firebase REST credentials from google-services.json (git-ignored): project number, API key and the Android client
 * whose package matches appId (else the first client). Returns null when remote config is not configured/available.
 */
export function remoteCredentials(cfg, rootDir = ROOT) {
  if (!cfg.remote) return null;
  const file = path.join(rootDir, cfg.remote.googleServices);
  if (!fs.existsSync(file)) {
    console.warn(`WARNING: ${cfg.remote.googleServices} not found; the desktop build has no live config`);
    return null;
  }
  const j = JSON.parse(fs.readFileSync(file, 'utf8'));
  const clients = j.client ?? [];
  const client = clients.find((c) => c.client_info?.android_client_info?.package_name === cfg.appId) ?? clients[0];
  const apiKey = client?.api_key?.[0]?.current_key;
  const appId = client?.client_info?.mobilesdk_app_id;
  const projectNumber = j.project_info?.project_number;
  if (!apiKey || !appId || !projectNumber) {
    console.warn('WARNING: google-services.json lacks an API key / app id / project number; no live config');
    return null;
  }
  return { projectNumber, apiKey, appId, parameter: cfg.remote.parameter ?? 'movy_config' };
}

/** Host lists are obfuscated too (allowedHostsEnc / dohHostsEnc); the app decodes them with the build key. */
export function obfuscateHosts(defaults, key, authHosts) {
  const enc = (list) => list.map((h) => obfuscate(h, key));
  const { dohHosts, ...rest } = defaults;
  return {
    ...rest,
    sources: defaults.sources.map(({ allowedHosts, ...s }) => ({ ...s, allowedHostsEnc: enc(allowedHosts) })),
    dohHostsEnc: enc(dohHosts ?? []),
    authHosts,
  };
}

/** The app-config.json object (pure; unit tested). */
export function buildAppConfig(cfg, { adblockOn, buildNumber, key, remote }) {
  return {
    name: cfg.name,
    appName: cfg.appName,
    appId: cfg.appId,
    version: cfg.version,
    build: buildNumber,
    obfKey: key.toString('base64'),
    defaults: obfuscateHosts(buildDefaults(cfg, key), key, cfg.authHosts ?? []),
    externalLinks: cfg.externalLinks,
    userAgent: cfg.userAgent,
    backgroundColor: cfg.backgroundColor,
    adblock: { enabled: adblockOn, cosmetic: cfg.adblock.cosmetic, blockPopups: cfg.adblock.blockPopups },
    remote,
  };
}

/** @param {object} cfg resolved desktop config  @param {{engineDat:string,resourcesJson:string}|null} engine  @param {string} nodeFile */
export function writeGenerated(cfg, engine, nodeFile, buildNumber = cfg.versionCode) {
  fs.rmSync(GEN, { recursive: true, force: true });
  fs.mkdirSync(GEN, { recursive: true });
  const adblockOn = cfg.adblock.enabled && !!engine;
  const app = buildAppConfig(cfg, { adblockOn, buildNumber, key: newObfuscationKey(), remote: remoteCredentials(cfg) });
  fs.writeFileSync(path.join(GEN, 'app-config.json'), JSON.stringify(app, null, 2) + '\n');
  for (const f of INJECT) fs.copyFileSync(path.join(ROOT, 'packages', 'inject', f), path.join(GEN, f));
  writeDesktopIcon(cfg, ROOT, path.join(GEN, 'icon.png'));
  if (adblockOn) {
    fs.copyFileSync(engine.engineDat, path.join(GEN, 'engine.dat'));
    fs.copyFileSync(engine.resourcesJson, path.join(GEN, 'resources.json'));
    fs.copyFileSync(nodeFile, path.join(GEN, 'movy_adblock_node.node'));
  }
  return app;
}
