#!/usr/bin/env node
// usage: node r5-probe.mjs [--scripts]   (Round 5 / Level 0 inventory probe)
// Evaluates in EVERY frame of the visible TV page: which injected scripts are active (window flags), TV.stats, size, the
// resource-timing summary by initiator and a host-class (first-party = same registrable-ish suffix as the top frame, else
// the host label hashed to h<N> so nothing identifying is printed unless ROLE_HINT matches a known generic role).
// With --scripts: re-evaluates every injected script in a fresh about:blank iframe of the TOP frame and prints the
// compile+run time (5 reps, median). Needs the scripts under packages/inject.
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
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0; const pending = new Map(); const ctxs = [];
const send = (method, params = {}) => new Promise((res) => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
ws.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); }
  else if (d.method === 'Runtime.executionContextCreated') ctxs.push(d.params.context);
};
await new Promise((r) => (ws.onopen = r));
await send('Runtime.enable');
await new Promise((r) => setTimeout(r, 700));

const ROLE = [
  [/analytics|googletagmanager|google-analytics|clarity|heap|posthog|metrika|umami|plausible|mixpanel|segment|hotjar|sentry|insights|beacon|stat|counter|track/i, 'analytics/tracking'],
  [/doubleclick|adsystem|adservice|ads\.|\bads|adnxs|pubmatic|popads|propeller|exoclick|juicyads|taboola|outbrain|clickadu|adsterra|monetag|syndication|advert|banner/i, 'ads'],
  [/tmdb|themoviedb/i, 'metadata api'],
  [/fonts\.|gstatic|typekit/i, 'fonts'],
  [/cloudflare|jsdelivr|unpkg|cdnjs|cdn\./i, 'cdn/library'],
  [/image\.|img|poster|thumb/i, 'images'],
];
const SHOW = process.env.R5_SHOW ? 1 : 0;
const PROBE = `(() => {
  const ROLE = ${JSON.stringify(ROLE.map(([re, n]) => [re.source, re.flags, n]))};
  const top = (() => { try { return window.top === window; } catch (e) { return false; } })();
  const root = (h) => h.split('.').slice(-2).join('.');
  const myRoot = root(location.hostname || '');
  let ancestor = '';
  try { ancestor = root(new URL(document.referrer || location.href).hostname); } catch (e) {}
  const hashes = {}; let hn = 0;
  const cls = (h) => {
    for (const [s, f, n] of ROLE) if (new RegExp(s, f).test(h)) return n;
    if (root(h) === myRoot || root(h) === ancestor) return 'same-site';
    if (!hashes[h]) hashes[h] = 'h' + (++hn);
    return 'other:' + hashes[h];
  };
  const res = performance.getEntriesByType('resource');
  const by = {};
  for (const e of res) {
    let h = ''; try { h = new URL(e.name).hostname; } catch (x) { continue; }
    const kind = e.initiatorType === 'script' || e.initiatorType === 'link' || e.initiatorType === 'css' ? e.initiatorType : (e.initiatorType === 'img' ? 'img' : (e.initiatorType === 'iframe' ? 'iframe' : (e.initiatorType === 'fetch' || e.initiatorType === 'xmlhttprequest' ? 'xhr' : 'other')));
    const key = cls(h) + '|' + kind;
    const o = (by[key] = by[key] || { n: 0, kb: 0, ms: 0 }); o.n++; o.kb += (e.transferSize || 0) / 1024; o.ms += e.duration;
  }
  const sum = Object.entries(by).map(([k, v]) => k + ' n=' + v.n + ' kb=' + Math.round(v.kb) + ' ms=' + Math.round(v.ms)).sort();
  const TV = window.__movyTv;
  return JSON.stringify({ hosts: ${SHOW} ? hashes : undefined, top, w: innerWidth, h: innerHeight, path: location.pathname.replace(/[0-9]{4,}/g, '#'),
    flags: { tv: !!TV, loading: !!window.__movyLoading, pure: !!window.__movyTvPure, tvfs: !!(TV && (TV.fs || TV.menuNow || TV.back)), tvmenu: !!(TV && TV.openPlayerMenu), keys: TV ? Object.keys(TV).length : 0, cosmetic: !!window.__movyCosmetic },
    stats: TV && TV.stats, dom: document.getElementsByTagName('*').length, video: !!document.querySelector('video'), res: res.length, sum,
    scripts: document.scripts.length, iframes: document.querySelectorAll('iframe').length });
})()`;
const defaults = ctxs.filter((c) => c.auxData && c.auxData.isDefault);
let i = 0;
for (const c of defaults) {
  const r = await send('Runtime.evaluate', { expression: PROBE, contextId: c.id, returnByValue: true });
  const v = r.result && r.result.result && r.result.result.value;
  console.log('--- frame', i++, v ? '' : 'no result');
  if (v) { const o = JSON.parse(v); const sum = o.sum; delete o.sum; console.log(JSON.stringify(o)); for (const s of sum) console.log('   ', s); }
}
if (process.argv.includes('--scripts')) {
  const files = ['cosmetic.js', 'loading.js', 'navguard.js', 'scamguard.js', 'tvnav-pure.js', 'tvnav.js', 'tvfs.js', 'tvinset.js', 'tvmenu.js', 'tvshell.js'];
  const top = defaults[0];
  const MEASURE = (src, reps) => `(async () => {
    const src = ${JSON.stringify(src)}; const out = [];
    for (let k = 0; k < ${reps}; k++) {
      const f = document.createElement('iframe'); f.style.cssText = 'position:fixed;width:1px;height:1px;opacity:0;pointer-events:none'; document.documentElement.appendChild(f);
      const w = f.contentWindow;
      if (window.__r5pure) w.eval(window.__r5pure);
      const t = performance.now();
      let err = null; try { w.eval(src); } catch (e) { err = String(e).slice(0, 60); }
      out.push(Math.round((performance.now() - t) * 100) / 100); f.remove();
      if (err) return JSON.stringify({ err });
    }
    out.sort((a, b) => a - b); return JSON.stringify({ med: out[out.length >> 1], min: out[0], max: out[out.length - 1] });
  })()`;
  const pureSrc = fs.readFileSync('packages/inject/tvnav-pure.js', 'utf8');
  await send('Runtime.evaluate', { expression: `window.__r5pure=${JSON.stringify(pureSrc)}`, contextId: top.id });
  for (const f of files) {
    let src = fs.readFileSync(f === 'cosmetic.js' ? 'apps/mobile/android/app/src/main/assets/adblock/cosmetic.js' : 'packages/inject/' + f, 'utf8');
    if (f === 'cosmetic.js') src = '(function(){var MOVY_OPTS={cosmetic:true,tv:true};' + src + '\n})();';
    if (f === 'tvnav-pure.js') { console.log(f, 'compile+run per fresh doc (below)'); }
    // each later TV script needs tvnav-pure first (and tvnav needs it for tvfs..): run the chain once, time the file itself.
    const chain = ['tvnav.js', 'tvfs.js', 'tvinset.js', 'tvmenu.js', 'tvshell.js'];
    let pre = '';
    if (chain.includes(f)) for (const p of ['tvnav.js', 'tvfs.js', 'tvinset.js', 'tvmenu.js'].slice(0, ['tvnav.js', 'tvfs.js', 'tvinset.js', 'tvmenu.js', 'tvshell.js'].indexOf(f))) pre += fs.readFileSync('packages/inject/' + p, 'utf8') + '\n;';
    const expr = MEASURE(src, 5).replace('if (window.__r5pure) w.eval(window.__r5pure);', () => 'if (window.__r5pure) w.eval(window.__r5pure); ' + (pre ? 'w.eval(' + JSON.stringify(pre) + ');' : ''));
    const r = await send('Runtime.evaluate', { expression: expr, contextId: top.id, returnByValue: true, awaitPromise: true });
    console.log(f.padEnd(14), r.result && r.result.result && r.result.result.value, r.result && r.result.exceptionDetails ? JSON.stringify(r.result.exceptionDetails.exception && r.result.exceptionDetails.exception.description).slice(0,200) : '');
  }
}
ws.close();
process.exit(0);
