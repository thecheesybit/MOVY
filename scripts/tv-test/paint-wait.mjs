#!/usr/bin/env node
// usage: node paint-wait.mjs launch|switch <source|-> [timeoutSecs]
// Starts the app (launch: force-stop first) or switches source with the debug intent, then polls the visible page over CDP and
// prints how long after the start command (device clock) the first poster and 90% of the posters in view were painted.
import { execSync } from 'node:child_process';

const ADB = 'C:/Users/ak818/AppData/Local/Android/Sdk/platform-tools/adb.exe';
const SERIAL = process.env.SERIAL || 'emulator-5554';
const PORT = Number(process.env.CDP_PORT || 9222);
const PKG = 'com.ayushkumar.movy.tv';
const [mode, source = '-', timeoutArg = '40'] = process.argv.slice(2);
const adb = (c) => execSync(`"${ADB}" -s ${SERIAL} ${c}`, { encoding: 'utf8' }).trim();

const PROBE = `(() => {
  const vh = innerHeight, vw = innerWidth; let inView = 0, done = 0;
  for (const im of document.images) {
    const r = im.getBoundingClientRect();
    if (r.width < 60 || r.height < 60 || r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw) continue;
    inView++; if (im.complete && im.naturalWidth > 0) done++;
  }
  return JSON.stringify({ o: Math.round(performance.timeOrigin), n: Math.round(performance.timeOrigin + performance.now()), inView, done, rs: document.readyState });
})()`;

async function probe() {
  const pid = adb('shell pidof ' + PKG).split(/\s+/)[0];
  if (!pid) return null;
  adb(`forward tcp:${PORT} localabstract:webview_devtools_remote_${pid}`);
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
  const vis = (p) => { try { const d = JSON.parse(p.description || '{}'); return d.visible && d.attached; } catch { return false; } };
  const page = list.filter((p) => p.type === 'page').find(vis);
  if (!page) return null;
  return await new Promise((resolve) => {
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    const t = setTimeout(() => { try { ws.close(); } catch {} resolve(null); }, 3000);
    ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: PROBE, returnByValue: true } }));
    ws.onmessage = (m) => { clearTimeout(t); try { resolve(JSON.parse(JSON.parse(m.data).result.result.value)); } catch { resolve(null); } ws.close(); };
    ws.onerror = () => { clearTimeout(t); resolve(null); };
  });
}

if (mode === 'launch') { adb('shell am force-stop ' + PKG); await new Promise((r) => setTimeout(r, 1000)); }
const t0 = Number(adb('shell date +%s%3N'));
const extra = source === '-' ? '' : ` --es movy_source ${source}`;
adb(`shell am start -n ${PKG}/app.movy.shell.MainActivity${extra}`);
const deadline = Date.now() + Number(timeoutArg) * 1000;
let first = null, ninety = null, last = null;
while (Date.now() < deadline && !ninety) {
  let s = null;
  try { s = await probe(); } catch { s = null; }
  if (s && s.o >= t0 - 150) { // a document created after the start command
    last = s;
    if (s.done >= 1 && first == null) first = s.n - t0;
    if (s.inView >= 6 && s.done / s.inView >= 0.9) ninety = s.n - t0;
  }
  await new Promise((r) => setTimeout(r, 120));
}
console.log(JSON.stringify({ mode, firstPosterMs: first, posters90Ms: ninety, lastInView: last && last.inView, lastDone: last && last.done }));
