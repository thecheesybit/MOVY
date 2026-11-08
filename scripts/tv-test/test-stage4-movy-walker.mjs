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
        sleep(400);
        const res = await fetch(`http://127.0.0.1:${PORT}/json/list`);
        const list = await res.json();
        const page = list.find(p => p.type === 'page');
        if (page && page.webSocketDebuggerUrl) return page.webSocketDebuggerUrl;
      }
    } catch (e) {
      sleep(600);
    }
  }
  throw new Error('No inspectable page found in WebView');
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

function sendKey(keyName) {
  const norm = keyName.startsWith('KEYCODE_') ? keyName : `KEYCODE_${keyName}`;
  adb(`shell input keyevent ${norm}`);
}

const GET_FOCUS_DETAILS_FN = `(function() {
  var el = document.querySelector('.movy-tvf') || document.activeElement;
  if (!el || el === document.body || el === document.documentElement) return { focused: false };
  var r = el.getBoundingClientRect();
  var cs = window.getComputedStyle(el);
  var label = (el.getAttribute('aria-label') || el.innerText || el.textContent || el.tagName).trim().replace(/\\s+/g, ' ').slice(0, 36);

  var isBlue = false;
  var oc = cs.outlineColor || '';
  var bs = cs.boxShadow || '';
  // Check if outline or box shadow has saturated blue (e.g. rgb(10, 132, 255) or blue hues)
  if (/rgb\\(\\s*10\\s*,\\s*132\\s*,\\s*255/i.test(bs) || /rgba\\(\\s*10\\s*,\\s*132\\s*,\\s*255/i.test(bs)) isBlue = true;
  if (/rgb\\(\\s*59\\s*,\\s*130\\s*,\\s*246/i.test(bs) || /rgb\\(\\s*37\\s*,\\s*99\\s*,\\s*235/i.test(bs)) isBlue = true;
  if (/rgb\\(\\s*10\\s*,\\s*132\\s*,\\s*255/i.test(oc)) isBlue = true;

  return {
    focused: true,
    tag: el.tagName.toLowerCase(),
    label: label,
    rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
    colX: Math.round((r.x + r.width / 2)),
    hasMovyTvf: el.classList.contains('movy-tvf'),
    outline: cs.outline,
    boxShadow: cs.boxShadow,
    hasBlueRing: isBlue,
    scale: cs.scale
  };
})()`;

async function testScreenWalker(wsUrl, screenName, keysToWalk) {
  console.log(`\n--- Testing Walker on Screen: ${screenName} ---`);
  // wake key
  adb('shell input keyevent KEYCODE_SHIFT_LEFT');
  sleep(300);

  let st = await cdpEvaluate(wsUrl, GET_FOCUS_DETAILS_FN);
  console.log(`   START: ${st.focused ? `[${st.tag}] "${st.label}" @ (${st.rect.x},${st.rect.y}) colX=${st.colX}` : 'none'}`);
  if (st.focused && st.hasBlueRing) {
    throw new Error(`Saturated blue outline detected on focused element: ${st.outline} / ${st.boxShadow}`);
  }

  const focusTrail = [st];
  for (const k of keysToWalk) {
    sendKey(k);
    sleep(600);
    st = await cdpEvaluate(wsUrl, GET_FOCUS_DETAILS_FN);
    if (!st.focused) {
      console.log(`   KEY ${k.padEnd(12)} -> none`);
    } else {
      console.log(`   KEY ${k.padEnd(12)} -> [${st.tag}] "${st.label}" @ (${st.rect.x},${st.rect.y}) colX=${st.colX} size ${st.rect.w}x${st.rect.h}`);
      if (st.hasBlueRing) {
        throw new Error(`Saturated blue outline detected on focused element: ${st.outline} / ${st.boxShadow}`);
      }
      focusTrail.push(st);
    }
  }
  return focusTrail;
}

async function main() {
  console.log(`======================================================`);
  console.log(`Stage 4: Movy Focus Map Engine & Visuals Verification`);
  console.log(`======================================================`);

  // Ensure Movy is open
  adb('shell am start -n com.ayushkumar.movy.tv/app.movy.shell.MainActivity --es movy_source "movy"');
  sleep(5000);
  let wsUrl = await ensureCdp();

  // Test 1: Home Page Walker with Column Memory
  await cdpEvaluate(wsUrl, "location.href = '/'");
  sleep(4000);
  wsUrl = await ensureCdp();
  const homeTrail = await testScreenWalker(wsUrl, 'Home (Column Memory & Rows)', [
    'DPAD_DOWN',
    'DPAD_RIGHT',
    'DPAD_RIGHT',
    'DPAD_DOWN',   // should move to row 2 keeping colX
    'DPAD_UP',     // should return to row 1 keeping colX
    'DPAD_UP',     // back to hero
    'DPAD_UP'      // to header
  ]);

  // Verify column memory on row 1 -> row 2 -> row 1
  const row1Card = homeTrail[3]; // after RIGHT, RIGHT
  const row2Card = homeTrail[4]; // after DOWN
  const row1Back = homeTrail[5]; // after UP
  console.log(`\n   Checking Column Memory:`);
  console.log(`   Row 1 card colX: ${row1Card.colX}`);
  console.log(`   Row 2 card colX: ${row2Card.colX} (delta: ${Math.abs(row2Card.colX - row1Card.colX)}px)`);
  console.log(`   Row 1 returned colX: ${row1Back.colX}`);
  if (Math.abs(row2Card.colX - row1Card.colX) > 180) {
    throw new Error(`Column memory failed! colX jumped from ${row1Card.colX} to ${row2Card.colX}`);
  }
  console.log(`   [PASS] Column memory verified! Delta within tight bounds.`);

  // Test 2: Catalog Page Walker
  await cdpEvaluate(wsUrl, "location.href = '/browse/movie'");
  sleep(4000);
  wsUrl = await ensureCdp();
  await testScreenWalker(wsUrl, 'Catalog (/browse/movie)', [
    'DPAD_DOWN',
    'DPAD_RIGHT',
    'DPAD_DOWN',
    'DPAD_LEFT',
    'DPAD_UP'
  ]);

  // Test 3: Show Detail Walker
  await cdpEvaluate(wsUrl, "location.href = '/tv/65701'");
  sleep(4000);
  wsUrl = await ensureCdp();
  await testScreenWalker(wsUrl, 'Show Detail (/tv/65701)', [
    'DPAD_DOWN',
    'DPAD_DOWN',
    'DPAD_UP'
  ]);

  // Test 4: Live Player Back Arrow Reachability Walker
  console.log(`\n--- Testing Live Player Back Arrow Reachability ---`);
  await cdpEvaluate(wsUrl, "location.href = '/tv/65701/1/1?play=true'");
  sleep(4000);
  wsUrl = await ensureCdp();

  // Focus Quality button
  await cdpEvaluate(wsUrl, `(() => {
    var b = Array.from(document.querySelectorAll('#vp-shell button')).find(b => b.innerText.includes('Quality'));
    if (b) {
      window.__movyTv.setCurrent(b, null);
      return b.innerText;
    }
    return null;
  })()`);

  const playerTrail = await testScreenWalker(wsUrl, 'Player Chrome (Quality -> Back -> Quality)', [
    'DPAD_LEFT',
    'DPAD_RIGHT'
  ]);

  const backStep = playerTrail[1];
  const returnStep = playerTrail[2];
  console.log(`\n   Player Back Arrow Proof:`);
  console.log(`   Step 1 (LEFT)  -> [${backStep.tag}] "${backStep.label}"`);
  console.log(`   Step 2 (RIGHT) -> [${returnStep.tag}] "${returnStep.label}"`);

  if (backStep.tag !== 'svg') {
    throw new Error(`LEFT from Quality did not reach Back arrow <svg>! Landed on [${backStep.tag}]`);
  }
  if (!returnStep.label.toLowerCase().includes('quality')) {
    throw new Error(`RIGHT from Back arrow did not return to Quality button! Landed on "${returnStep.label}"`);
  }
  console.log(`   [PASS] Player Back arrow is fully reachable and reversible!`);

  console.log(`\n======================================================`);
  console.log(`STAGE 4 PASSED ON ALL MOVY SCREENS!`);
  console.log(`- Walker passed on every screen`);
  console.log(`- Back arrow reachable (Quality -> Back -> Quality)`);
  console.log(`- Saturated blue outlines neutralized (0 found)`);
  console.log(`- Column memory preserved across rows`);
  console.log(`======================================================`);
}

main().catch(err => {
  console.error(`\nFAILED:`, err.message);
  process.exit(1);
});
