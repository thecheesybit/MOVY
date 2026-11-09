#!/usr/bin/env node
// usage: node cdpf.mjs list                      -> the frames of the visible TV page (depth, origin class, size)
//        node cdpf.mjs <n> '<js>' | -f file.js   -> evaluates in the n-th child frame (1 = first, document order of creation)
// Needed for the cross-origin player embeds, which cdp.mjs (main frame only) cannot reach. Prints no host names.
import { execSync } from 'node:child_process';
import fs from 'node:fs';

const ADB = 'C:/Users/ak818/AppData/Local/Android/Sdk/platform-tools/adb.exe';
const SERIAL = process.env.SERIAL || 'emulator-5554';
const PORT = Number(process.env.CDP_PORT || 9222);
const adb = (c) => execSync(`"${ADB}" -s ${SERIAL} ${c}`, { encoding: 'utf8' }).trim();
const pid = adb('shell pidof com.ayushkumar.movy.tv').split(/\s+/)[0];
adb(`forward tcp:${PORT} localabstract:webview_devtools_remote_${pid}`);
const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const visibleOf = (p) => { try { const d = JSON.parse(p.description || '{}'); return !!(d.visible && d.attached); } catch { return false; } };
const pages = list.filter((p) => p.type === 'page');
const page = pages.find(visibleOf) ?? pages[0];
const [which, ...rest] = process.argv.slice(2);
const expr = rest[0] === '-f' ? fs.readFileSync(rest[1], 'utf8') : rest.join(' ');

const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();
const contexts = [];
const send = (method, params = {}) => new Promise((res) => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
ws.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); }
  else if (d.method === 'Runtime.executionContextCreated') contexts.push(d.params.context);
};
await new Promise((r) => { ws.onopen = r; });
setTimeout(() => { console.error('timeout'); process.exit(2); }, 25000);
await send('Page.enable');
await send('Runtime.enable');
await new Promise((r) => setTimeout(r, 700));
const tree = (await send('Page.getFrameTree')).result.frameTree;
const frames = [];
(function walk(t, depth) { for (const c of t.childFrames || []) { frames.push({ id: c.frame.id, depth, origin: c.frame.securityOrigin, sameOrigin: c.frame.securityOrigin === tree.frame.securityOrigin }); walk(c, depth + 1); } })(tree, 1);
if (which === 'list') {
  console.log(JSON.stringify(frames.map((f, i) => ({ n: i + 1, depth: f.depth, sameOrigin: f.sameOrigin, hasContext: contexts.some((c) => c.auxData && c.auxData.frameId === f.id && c.auxData.isDefault) }))));
  process.exit(0);
}
const f = frames[Number(which) - 1];
if (!f) { console.error('no such frame'); process.exit(1); }
const ctx = contexts.find((c) => c.auxData && c.auxData.frameId === f.id && c.auxData.isDefault);
if (!ctx) { console.error('frame has no default context'); process.exit(1); }
const r = await send('Runtime.evaluate', { expression: expr, contextId: ctx.id, returnByValue: true, awaitPromise: true });
if (r.result?.exceptionDetails) console.error(r.result.exceptionDetails.exception?.description || 'exception');
else console.log(JSON.stringify(r.result?.result?.value));
process.exit(0);
