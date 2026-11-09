#!/usr/bin/env node
// usage: node r5-net.mjs -f action.js [--wait 4] [--filter substr]
// Round 5 / Level 0: runs the JS in the visible TV page (top frame) while recording its XHR/fetch/document requests over CDP.
// Prints, per request: method, same-origin|external (no host), path + query (values of long/identifying params kept), the NAMES
// of non-standard request headers, status, content type and - for JSON replies - the key structure of the body (first element
// of any array). Host names are never printed.
import { execSync } from 'node:child_process';
import fs from 'node:fs';

const ADB = 'C:/Users/ak818/AppData/Local/Android/Sdk/platform-tools/adb.exe';
const SERIAL = process.env.SERIAL || 'emulator-5554';
const PORT = Number(process.env.CDP_PORT || 9222);
const adb = (c) => execSync(`"${ADB}" -s ${SERIAL} ${c}`, { encoding: 'utf8' }).trim();
const argv = process.argv.slice(2);
const opt = (n, d) => { const i = argv.indexOf(n === 'f' ? '-f' : '--' + n); return i >= 0 ? argv[i + 1] : d; };
const action = fs.readFileSync(opt('f'), 'utf8');
const WAIT = Number(opt('wait', 4)) * 1000;
const FILTER = opt('filter', '');
const pid = adb('shell pidof com.ayushkumar.movy.tv').split(/\s+/)[0];
adb(`forward tcp:${PORT} localabstract:webview_devtools_remote_${pid}`);
const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const visibleOf = (p) => { try { const d = JSON.parse(p.description || '{}'); return !!(d.visible && d.attached); } catch { return false; } };
const pages = list.filter((p) => p.type === 'page');
const page = pages.find(visibleOf) ?? pages[0];
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0; const pending = new Map();
const send = (method, params = {}) => new Promise((res) => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
const reqs = new Map();
let origin = '';
const STD = /^(accept|accept-language|accept-encoding|user-agent|referer|origin|connection|host|content-length|sec-|cache-control|pragma|upgrade-insecure|cookie$)/i;
ws.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); return; }
  const p = d.params;
  if (d.method === 'Network.requestWillBeSent') reqs.set(p.requestId, { method: p.request.method, url: p.request.url, type: p.type, headers: p.request.headers, post: p.request.postData });
  else if (d.method === 'Network.requestWillBeSentExtraInfo') { const r = reqs.get(p.requestId); if (r) r.hasCookie = Object.keys(p.headers).some((h) => h.toLowerCase() === 'cookie'); }
  else if (d.method === 'Network.responseReceived') { const r = reqs.get(p.requestId); if (r) { r.status = p.response.status; r.mime = p.response.mimeType; } }
  else if (d.method === 'Network.loadingFinished') { const r = reqs.get(p.requestId); if (r) r.done = true; }
};
await new Promise((r) => (ws.onopen = r));
await send('Network.enable');
await send('Runtime.enable');
const o = await send('Runtime.evaluate', { expression: 'location.origin', returnByValue: true });
origin = o.result.result.value;
const a = await send('Runtime.evaluate', { expression: action, awaitPromise: true, returnByValue: true });
if (a.result && a.result.exceptionDetails) console.log('action error', JSON.stringify(a.result.exceptionDetails.exception && a.result.exceptionDetails.exception.description).slice(0, 200));
else console.log('action result', String(JSON.stringify(a.result.result.value)).slice(0, 600));
await new Promise((r) => setTimeout(r, WAIT));
const shape = (v, depth = 0) => {
  if (Array.isArray(v)) return '[' + v.length + 'x ' + (v.length ? shape(v[0], depth + 1) : '') + ']';
  if (v && typeof v === 'object') return depth > 3 ? '{...}' : '{' + Object.entries(v).slice(0, 30).map(([k, x]) => k + ':' + shape(x, depth + 1)).join(', ') + '}';
  return typeof v === 'string' ? 'str' : typeof v;
};
for (const [rid, r] of reqs) {
  if (!(process.env.R5_TYPES || 'XHR,Fetch,Document').split(',').includes(r.type)) continue;
  let u; try { u = new URL(r.url); } catch { continue; }
  const path = (u.pathname + u.search).replace(/((?:api_?key|key|token|auth|secret)=)[^&]+/gi, '$1<redacted>');
  if (FILTER && !path.includes(FILTER)) continue;
  if (/\.(png|jpe?g|webp|svg|css|js|woff2?|ico)(\?|$)/i.test(u.pathname)) continue;
  const extra = Object.keys(r.headers || {}).filter((h) => !STD.test(h));
  console.log(`${r.method} ${u.origin === origin ? 'same-origin' : 'external'} ${path.slice(0, 200)} -> ${r.status} ${r.mime} cookie=${!!r.hasCookie} hdr=[${extra.join(',')}]${r.post ? ' body=<' + r.post.length + 'b>' : ''}`);
  if (r.done && /json/.test(r.mime || '')) {
    const b = await send('Network.getResponseBody', { requestId: rid });
    try { console.log('    json:', shape(JSON.parse(b.result.body)).slice(0, 900)); } catch { /* ignore */ }
  }
}
ws.close();
process.exit(0);
