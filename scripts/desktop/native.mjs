// Builds the napi-rs binding (native/adblock-node) and returns the path of the .node file.
import fs from 'node:fs';
import path from 'node:path';
import { ROOT, envWithCargo } from '../paths.mjs';
import { run } from '../run.mjs';
import { newestMtime } from '../fsutil.mjs';

const CRATE = path.join(ROOT, 'native', 'adblock-node');
const CORE = path.join(ROOT, 'native', 'adblock');
export const NODE_FILE = path.join(CRATE, 'movy_adblock_node.node');

/** cargo build --release (incremental) and copy the cdylib to movy_adblock_node.node. Skipped when up to date. */
export function ensureNodeBinding() {
  if (process.platform !== 'win32' || process.arch !== 'x64') {
    throw new Error('The desktop adblock binding is only built for win32-x64 (the host toolchain target)');
  }
  const srcTime = Math.max(
    newestMtime(path.join(CRATE, 'src')),
    newestMtime(path.join(CRATE, 'vendor')),
    newestMtime(path.join(CORE, 'src')),
    fs.statSync(path.join(CRATE, 'Cargo.toml')).mtimeMs,
    fs.statSync(path.join(CORE, 'Cargo.toml')).mtimeMs,
  );
  if (fs.existsSync(NODE_FILE) && fs.statSync(NODE_FILE).mtimeMs > srcTime) return NODE_FILE;
  console.log('[native] building movy_adblock_node.node (napi-rs, release)');
  run('cargo', ['build', '--release', '--quiet'], { cwd: CRATE, env: envWithCargo() });
  const dll = path.join(CRATE, 'target', 'release', 'movy_adblock_node.dll');
  if (!fs.existsSync(dll)) throw new Error(`cargo succeeded but ${dll} is missing`);
  fs.copyFileSync(dll, NODE_FILE);
  return NODE_FILE;
}
