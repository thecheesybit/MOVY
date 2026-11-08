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
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      const pid = adb('shell pidof com.ayushkumar.movy.tv', { ignoreError: true });
      if (pid) {
        adb(`forward tcp:${PORT} localabstract:webview_devtools_remote_${pid.trim().split(/\s+/)[0]}`);
        sleep(500);
        const res = await fetch(`http://127.0.0.1:${PORT}/json/list`);
        const list = await res.json();
        const page = list.find(p => p.type === 'page');
        if (page && page.webSocketDebuggerUrl) return page.webSocketDebuggerUrl;
      }
    } catch (e) {
      sleep(800);
    }
  }
  throw new Error('No inspectable page found in WebView after retries');
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

async function testPreloadGate(siteId) {
  console.log(`\n--- Testing Preload Gate on ${siteId.toUpperCase()} ---`);
  adb('shell am force-stop com.ayushkumar.movy.tv');
  sleep(1000);
  adb('logcat -c');

  const tStart = Date.now();
  adb(`shell am start -n com.ayushkumar.movy.tv/app.movy.shell.MainActivity --es movy_source "${siteId}"`);
  
  // Wait up to 10s to see if adapter ready was received in logcat
  let readyLog = '';
  for (let i = 0; i < 20; i++) {
    sleep(500);
    const logs = adb('logcat -d');
    if (logs.includes('adapter ready received; dismissing TV splash')) {
      readyLog = 'adapter ready received; dismissing TV splash';
      const tElapsed = Date.now() - tStart;
      console.log(`   [PASS] MovyTvNative.ready() received at t+${tElapsed}ms!`);
      break;
    }
  }

  if (!readyLog) {
    throw new Error(`Did not observe 'adapter ready received' in logcat within timeout on ${siteId}`);
  }

  const wsUrl = await ensureCdp();
  const state = await cdpEvaluate(wsUrl, `({
    veilLifted: window.__movyTv?.veilLifted,
    firstFocusDone: window.__movyTv?.firstFocusDone,
    readySignaled: window.__movyTv?.readySignaled,
    innerWidth: window.innerWidth,
    hasNativeReady: typeof window.MovyTvNative?.ready === 'function',
    vpLog: window.__movyTv?.vpLog || []
  })`);

  console.log(`   Page state: veilLifted=${state.veilLifted}, firstFocusDone=${state.firstFocusDone}, readySignaled=${state.readySignaled}, innerWidth=${state.innerWidth}`);
  if (!state.veilLifted || !state.readySignaled || state.innerWidth !== 1280) {
    throw new Error(`Invalid state after ready(): ${JSON.stringify(state)}`);
  }
  return true;
}

async function main() {
  console.log(`======================================================`);
  console.log(`Stage 3: Preload Gate & Boot Veil Pass Gate Verification`);
  console.log(`======================================================`);

  for (const s of ['movy', 'cinejoy', '7movies', 'moonflix']) {
    await testPreloadGate(s);
  }

  console.log(`\n======================================================`);
  console.log(`ALL SITES PASSED STAGE 3 BOOT VEIL & PRELOAD GATE!`);
  console.log(`======================================================`);
}

main().catch(err => {
  console.error(`\nFAILED:`, err.message);
  process.exit(1);
});
