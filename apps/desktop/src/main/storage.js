'use strict';
// Settings -> Storage: size of the app's data, "Clear Cache" and "Sign Out of All Sites". Both destructive actions
// only ever run after an explicit confirm in the UI; nothing is cleared automatically.
const fs = require('node:fs');
const path = require('node:path');

/** Recursive size in bytes; unreadable entries count as 0. */
async function dirSize(dir) {
  let total = 0;
  let entries;
  try {
    entries = await fs.promises.readdir(dir, { withFileTypes: true });
  } catch {
    return 0;
  }
  for (const e of entries) {
    const p = path.join(dir, e.name);
    try {
      total += e.isDirectory() ? await dirSize(p) : (await fs.promises.stat(p)).size;
    } catch {
      /* file vanished */
    }
  }
  return total;
}

/** Everything the app keeps for the partition: Partitions/<name>, plus the Cache folders. */
async function usedBytes(userDir, partition) {
  const name = partition.replace(/^persist:/, '');
  const sizes = await Promise.all(
    [path.join(userDir, 'Partitions', name), path.join(userDir, 'Cache'), path.join(userDir, 'GPUCache'), path.join(userDir, 'Code Cache')].map(dirSize),
  );
  return sizes.reduce((a, b) => a + b, 0);
}

async function clearCache(ses) {
  await ses.clearCache();
  await ses.clearCodeCaches({});
}

/** Deletes all site data (cookies, localStorage, IndexedDB, caches, service workers) and the cookie snapshot. */
async function signOutEverywhere(ses, userDir) {
  await ses.clearStorageData();
  await ses.clearCache();
  await ses.cookies.flushStore();
  try {
    await fs.promises.rm(path.join(userDir, 'session-cookies.json'), { force: true });
  } catch {
    /* nothing to remove */
  }
}

module.exports = { usedBytes, clearCache, signOutEverywhere, dirSize };
