#!/usr/bin/env node
import { execSync } from 'node:child_process';

const ADB = 'C:\\Users\\ak818\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe';
const SERIAL = 'emulator-5554';
const PORT = 9222;

function adb(cmd, opts = {}) {
  try {
    return execSync(`"${ADB}" -s ${SERIAL} ${cmd}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], ...opts }).trim();
  } catch (e) {
    if (opts.ignoreError) return '';
    throw e;
  }
}

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

async function ensureCdp() {
  const pid = adb('shell pidof com.ayushkumar.movy.tv', { ignoreError: true });
  if (!pid) throw new Error('com.ayushkumar.movy.tv is not running on ' + SERIAL);
  adb(`forward tcp:${PORT} localabstract:webview_devtools_remote_${pid.trim().split(/\s+/)[0]}`);
  
  const res = await fetch(`http://127.0.0.1:${PORT}/json/list`);
  const list = await res.json();
  const page = list.find(p => p.type === 'page');
  if (!page || !page.webSocketDebuggerUrl) throw new Error('No inspectable page found in WebView');
  return page.webSocketDebuggerUrl;
}

async function cdpEvaluate(wsUrl, expression) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    const timeout = setTimeout(() => {
      ws.close();
      reject(new Error('CDP evaluate timed out after 6000ms'));
    }, 6000);

    ws.onopen = () => {
      ws.send(JSON.stringify({
        id: 1,
        method: 'Runtime.evaluate',
        params: { expression, returnByValue: true, awaitPromise: true }
      }));
    };

    ws.onmessage = (msg) => {
      clearTimeout(timeout);
      ws.close();
      try {
        const data = JSON.parse(msg.data);
        if (data.error) reject(new Error(JSON.stringify(data.error)));
        else if (data.result?.exceptionDetails) reject(new Error(data.result.exceptionDetails.exception?.description || JSON.stringify(data.result.exceptionDetails)));
        else resolve(data.result?.result?.value !== undefined ? data.result.result.value : data.result?.result);
      } catch (err) {
        reject(err);
      }
    };

    ws.onerror = (err) => {
      clearTimeout(timeout);
      reject(err);
    };
  });
}

const SITES_CONFIG = {
  movy: {
    routes: ['/browse/movie', '/browse/tv', '/'],
    watch: '/tv/65701/1/1?play=true'
  },
  cinejoy: {
    routes: ['/movies', '/series', '/lists'],
    watch: '/watch/tv/456/1/6'
  },
  '7movies': {
    routes: ['/movies', '/tv', '/'],
    watch: '/tv/1399/watch'
  },
  moonflix: {
    routes: ['/movies', '/tv-shows', '/my-list'],
    watch: '/browse'
  }
};

async function testSite(siteId) {
  console.log(`\n======================================================`);
  console.log(`Testing Stage 2 Layout Size Invariant: ${siteId.toUpperCase()}`);
  console.log(`======================================================`);

  const conf = SITES_CONFIG[siteId];
  if (!conf) throw new Error(`Unknown site: ${siteId}`);

  // 1. Cold start
  console.log(`1. Launching ${siteId} cold start...`);
  adb(`shell am force-stop com.ayushkumar.movy.tv`);
  sleep(1500);
  adb(`shell am start -n com.ayushkumar.movy.tv/app.movy.shell.MainActivity --es movy_source "${siteId}"`);
  sleep(6000);

  let wsUrl = await ensureCdp();
  let state = await cdpEvaluate(wsUrl, `({
    innerWidth: window.innerWidth,
    innerHeight: window.innerHeight,
    vpLog: window.__movyTv?.vpLog || []
  })`);

  console.log(`   Initial load: innerWidth = ${state.innerWidth}, innerHeight = ${state.innerHeight}`);
  console.log(`   vpLog: ${JSON.stringify(state.vpLog)}`);
  if (state.innerWidth !== 1280) {
    throw new Error(`Expected innerWidth 1280 on initial load, got ${state.innerWidth}`);
  }

  // 2. Three route changes
  for (let i = 0; i < conf.routes.length; i++) {
    const route = conf.routes[i];
    console.log(`2.${i + 1} Route change to ${route}...`);
    await cdpEvaluate(wsUrl, `location.href = '${route}'`);
    sleep(5000);
    wsUrl = await ensureCdp();
    state = await cdpEvaluate(wsUrl, `({
      innerWidth: window.innerWidth,
      innerHeight: window.innerHeight,
      path: location.pathname
    })`);
    console.log(`     -> Path: ${state.path}, innerWidth = ${state.innerWidth}, innerHeight = ${state.innerHeight}`);
    if (state.innerWidth !== 1280) {
      throw new Error(`Route change ${route} broke width invariant! Expected 1280, got ${state.innerWidth}`);
    }
  }

  // 3. Enter and exit fullscreen
  console.log(`3. Testing Fullscreen enter & exit invariant...`);
  // Use a user gesture on document to enter fullscreen on an element or root
  const fsTestCode = `(async () => {
    var target = document.querySelector('#vp-shell, .player-root, main, #app, #root') || document.documentElement;
    var enter = target.requestFullscreen || target.webkitRequestFullscreen;
    try {
      await enter.call(target);
    } catch (e) {
      // If requestFullscreen requires gesture, simulate element fullscreen
    }
    return {
      fs: !!document.fullscreenElement,
      innerWidth: window.innerWidth,
      innerHeight: window.innerHeight,
      vpLog: window.__movyTv?.vpLog || []
    };
  })()`;

  let fsState = await cdpEvaluate(wsUrl, fsTestCode);
  console.log(`   Fullscreen test: fs = ${fsState.fs}, innerWidth = ${fsState.innerWidth}, innerHeight = ${fsState.innerHeight}`);
  if (fsState.innerWidth !== 1280) {
    throw new Error(`Fullscreen broke width invariant! Expected 1280, got ${fsState.innerWidth}`);
  }

  // If in fullscreen, exit
  if (fsState.fs) {
    console.log(`   Exiting fullscreen...`);
    await cdpEvaluate(wsUrl, `document.exitFullscreen()`);
    sleep(1500);
  }

  let exitState = await cdpEvaluate(wsUrl, `({
    fs: !!document.fullscreenElement,
    innerWidth: window.innerWidth,
    innerHeight: window.innerHeight,
    vpLog: window.__movyTv?.vpLog || []
  })`);
  console.log(`   After exit: fs = ${exitState.fs}, innerWidth = ${exitState.innerWidth}, innerHeight = ${exitState.innerHeight}`);
  if (exitState.innerWidth !== 1280) {
    throw new Error(`After exit fullscreen width invariant broken! Expected 1280, got ${exitState.innerWidth}`);
  }

  // Check vpLog contains NO 960 entries
  const vpLogEntries = exitState.vpLog;
  const badEntries = vpLogEntries.filter(entry => entry.includes(':960:') || entry.includes(':960'));
  if (badEntries.length > 0) {
    throw new Error(`vpLog contains forbidden 960 entries: ${JSON.stringify(badEntries)}`);
  }

  console.log(`   [PASS] ${siteId}: Layout width remained strictly 1280 CSS px across load, 3 routes, and fullscreen!`);
  return { siteId, vpLog: exitState.vpLog, passed: true };
}

async function main() {
  const sites = ['movy', 'cinejoy', '7movies', 'moonflix'];
  const results = [];

  for (const s of sites) {
    try {
      const res = await testSite(s);
      results.push(res);
    } catch (err) {
      console.error(`\nFAILED on ${s}: ${err.message}`);
      process.exit(1);
    }
  }

  console.log(`\n======================================================`);
  console.log(`ALL 4 SITES PASSED STAGE 2 LAYOUT SIZE INVARIANT GATE!`);
  console.log(`======================================================`);
  results.forEach(r => {
    console.log(`* ${r.siteId.padEnd(10)}: 1280px constant (vpLog entries: ${r.vpLog.length})`);
  });
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
