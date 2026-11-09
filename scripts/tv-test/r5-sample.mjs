#!/usr/bin/env node
// usage: node r5-sample.mjs <label> [--keys DPAD_CENTER,w1500,nav:/path (w<ms> waits, nav: sets location)] [--after-playing 5] [--max 60] [--interval 200]
// Round 5 / Level 0 resize probe. Attaches to the visible TV page over CDP, installs a sampler in EVERY frame
// (current and future) and logs every `interval` ms: window.innerWidth/innerHeight, devicePixelRatio, visualViewport,
// the first <video> rect, fullscreen state, readyState/paused, plus resize/fullscreenchange/playing/waiting events.
// Presses the given keys (adb) once sampling runs, then stops `after-playing` s after the first `playing` event.
// Prints only the state CHANGES per frame (no host names are recorded: frames are labelled by depth and index).
import { execSync } from 'node:child_process';

const ADB = 'C:/Users/ak818/AppData/Local/Android/Sdk/platform-tools/adb.exe';
const SERIAL = process.env.SERIAL || 'emulator-5554';
const PORT = Number(process.env.CDP_PORT || 9222);
const adb = (c) => execSync(`"${ADB}" -s ${SERIAL} ${c}`, { encoding: 'utf8' }).trim();
const argv = process.argv.slice(2);
const label = argv[0] || 'run';
const opt = (n, d) => { const i = argv.indexOf('--' + n); return i >= 0 ? argv[i + 1] : d; };
const keys = (opt('keys', 'DPAD_CENTER')).split(',').filter(Boolean);
const AFTER = Number(opt('after-playing', 5)) * 1000;
const MAX = Number(opt('max', 60)) * 1000;
const INTERVAL = Number(opt('interval', 200));
const BACK = argv.includes('--back');

const SAMPLER = `(() => {
  if (window.__r5s) return; window.__r5s = 1;
  const depth = (() => { let d = 0, w = window; try { while (w !== w.parent) { d++; w = w.parent; } } catch (e) { d = -1; } return d; })();
  const r = (n) => Math.round(n * 10) / 10;
  const snap = () => {
    const v = document.querySelector('video');
    let vr = null, vs = null;
    if (v) { const b = v.getBoundingClientRect(); vr = [r(b.left), r(b.top), r(b.width), r(b.height)]; vs = [v.readyState, v.paused ? 1 : 0, v.videoWidth + 'x' + v.videoHeight]; }
    const vv = window.visualViewport;
    return { d: depth, iw: innerWidth, ih: innerHeight, dpr: devicePixelRatio, vv: vv ? [r(vv.width), r(vv.height), r(vv.scale)] : null,
      cw: document.documentElement.clientWidth, fs: !!document.fullscreenElement, vr, vs };
  };
  const emit = (ev) => { try { console.debug('R5S ' + JSON.stringify({ t: Date.now(), ev, s: snap() })); } catch (e) {} };
  setInterval(() => emit('tick'), ${INTERVAL});
  addEventListener('resize', () => emit('resize'), true);
  document.addEventListener('fullscreenchange', () => emit('fullscreenchange'), true);
  for (const e of ['playing', 'waiting', 'loadedmetadata', 'canplay', 'play', 'seeked']) document.addEventListener(e, (ev) => { if (ev.target && ev.target.tagName === 'VIDEO') emit('video:' + e); }, true);
})()`;

const pid = adb('shell pidof com.ayushkumar.movy.tv').split(/\s+/)[0];
adb(`forward tcp:${PORT} localabstract:webview_devtools_remote_${pid}`);
const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const visibleOf = (p) => { try { const d = JSON.parse(p.description || '{}'); return !!(d.visible && d.attached); } catch { return false; } };
const pages = list.filter((p) => p.type === 'page');
const page = pages.find(visibleOf) ?? pages[0];
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0; const pending = new Map();
const send = (method, params = {}) => new Promise((res) => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
const ctxs = new Map(); // contextId -> auxData
const rows = []; // {ctx, t, ev, s}
let t0 = 0, playingAt = 0;
ws.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); return; }
  if (d.method === 'Runtime.executionContextCreated') {
    const c = d.params.context; ctxs.set(c.id, c);
    if (c.auxData && c.auxData.isDefault) send('Runtime.evaluate', { expression: SAMPLER, contextId: c.id });
  } else if (d.method === 'Runtime.consoleAPICalled') {
    const a = d.params.args && d.params.args[0];
    if (a && typeof a.value === 'string' && a.value.startsWith('R5S ')) {
      try {
        const o = JSON.parse(a.value.slice(4)); o.ctx = d.params.executionContextId; rows.push(o);
        if (t0 && o.ev === 'video:playing' && !playingAt) playingAt = o.t;
      } catch {}
    }
  }
};
await new Promise((r) => (ws.onopen = r));
await send('Runtime.enable');
await send('Page.enable');
await send('Page.addScriptToEvaluateOnNewDocument', { source: SAMPLER });
await new Promise((r) => setTimeout(r, 1000)); // sampler up in all current frames
t0 = Date.now();
const key = (k) => adb('shell input keyevent KEYCODE_' + k.replace(/^KEYCODE_/, ''));
if (!argv.includes('--nowake')) { key('SHIFT_LEFT'); await new Promise((r) => setTimeout(r, 400)); }
const press = Date.now();
for (const k of keys) {
  if (/^wd+$/.test(k)) { await new Promise((r) => setTimeout(r, Number(k.slice(1)))); continue; }
  if (k.startsWith('nav:')) { const top = [...rows].reverse().find((o) => o.s.d === 0); if (top) await send('Runtime.evaluate', { expression: 'location.href=' + JSON.stringify(k.slice(4)), contextId: top.ctx }); continue; }
  key(k); await new Promise((r) => setTimeout(r, 400));
}
while (Date.now() - t0 < MAX) {
  if (playingAt && Date.now() - playingAt >= AFTER) break;
  await new Promise((r) => setTimeout(r, 250));
}
// --back: BACK key after the post-playing wait; poll the top frame until the catalog is interactive (no fullscreen, no playing video, focus ring present)
let backInfo = null;
if (BACK) {
  await new Promise((r) => setTimeout(r, 1500));
  const tb = Date.now(); key('BACK'); let pressed = 1; let ok = null; let path = null;
  const COND = `(() => { const v = document.querySelector('video'); const playing = v && !v.paused && v.getBoundingClientRect().width > 300;
    return JSON.stringify({ ok: !document.fullscreenElement && !playing && !!document.querySelector('.movy-tvf'), p: location.pathname.replace(/[0-9]{4,}/g, '#') }); })()`;
  while (Date.now() - tb < 15000 && !ok) {
    await new Promise((r) => setTimeout(r, 100));
    const top = [...rows].reverse().find((o) => o.s.d === 0 && Date.now() - o.t < 1500);
    if (!top) continue;
    const r = await send('Runtime.evaluate', { expression: COND, contextId: top.ctx, returnByValue: true });
    try { const v = JSON.parse(r.result.result.value); path = v.p; if (v.ok) ok = Date.now() - tb; } catch {}
    // the first key after >5 s idle only wakes the screen (MainActivity tvIdle), so BACK is repeated at 0.7 s and 3 s
    if (!ok && pressed === 1 && Date.now() - tb > 700) { key('BACK'); pressed = 2; }
    if (!ok && pressed === 2 && Date.now() - tb > 3000) { key('BACK'); pressed = 3; }
  }
  backInfo = { backToInteractiveMs: ok, backPresses: pressed, path };
}
// report: per context, collapse consecutive identical states
const byCtx = new Map();
for (const o of rows) { if (!byCtx.has(o.ctx)) byCtx.set(o.ctx, []); byCtx.get(o.ctx).push(o); }
const out = { label, playingAfterPressMs: playingAt ? playingAt - press : null, back: backInfo, frames: [] };
let n = 0;
for (const [ctx, arr] of byCtx) {
  const changes = []; let prev = '';
  for (const o of arr) {
    const k = JSON.stringify(o.s);
    if (k !== prev || o.ev !== 'tick') { changes.push({ ms: o.t - press, ev: o.ev, ...o.s }); prev = k; }
  }
  out.frames.push({ frame: n++, depth: arr[0].s.d, samples: arr.length, changes });
}
console.log(JSON.stringify(out));
ws.close();
process.exit(0);
