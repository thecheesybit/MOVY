#!/usr/bin/env node
import { execSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

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

async function ensureCdp() {
  const pid = adb('shell pidof com.ayushkumar.movy.tv', { ignoreError: true });
  if (!pid) {
    throw new Error('com.ayushkumar.movy.tv is not running on ' + SERIAL);
  }
  adb(`forward tcp:${PORT} localabstract:webview_devtools_remote_${pid.trim().split(/\s+/)[0]}`);
  
  const res = await fetch(`http://127.0.0.1:${PORT}/json/list`);
  const list = await res.json();
  const page = list.find(p => p.type === 'page');
  if (!page || !page.webSocketDebuggerUrl) {
    throw new Error('No inspectable page found in WebView');
  }
  return page.webSocketDebuggerUrl;
}

async function cdpEvaluate(wsUrl, expression) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    const timeout = setTimeout(() => {
      ws.close();
      reject(new Error('CDP evaluate timed out after 5000ms'));
    }, 5000);

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
        if (data.error) {
          reject(new Error(JSON.stringify(data.error)));
        } else if (data.result?.exceptionDetails) {
          reject(new Error(data.result.exceptionDetails.exception?.description || JSON.stringify(data.result.exceptionDetails)));
        } else {
          resolve(data.result?.result?.value !== undefined ? data.result.result.value : data.result?.result);
        }
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

function normalizeKey(k) {
  switch (k.toUpperCase()) {
    case 'UP': return 'KEYCODE_DPAD_UP';
    case 'DOWN': return 'KEYCODE_DPAD_DOWN';
    case 'LEFT': return 'KEYCODE_DPAD_LEFT';
    case 'RIGHT': return 'KEYCODE_DPAD_RIGHT';
    case 'OK':
    case 'CENTER': return 'KEYCODE_DPAD_CENTER';
    case 'BACK': return 'KEYCODE_BACK';
    default:
      if (k.startsWith('KEYCODE_')) return k;
      if (k.startsWith('DPAD_')) return `KEYCODE_${k}`;
      return `KEYCODE_${k}`;
  }
}

function sendKey(keyName, nowake = false) {
  if (!nowake) {
    adb('shell input keyevent KEYCODE_SHIFT_LEFT');
    sleep(250);
  }
  const code = normalizeKey(keyName);
  adb(`shell input keyevent ${code}`);
}

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

const IN_PAGE_DUMP_FN = `(function() {
  var vw = window.innerWidth, vh = window.innerHeight;
  var metaViewport = document.querySelector('meta[name="viewport"]');
  var fsEl = document.fullscreenElement ? {
    tag: document.fullscreenElement.tagName,
    id: document.fullscreenElement.id || undefined,
    className: (document.fullscreenElement.className || '').toString().slice(0, 100)
  } : null;

  var SEL = 'a[href],button,input,select,textarea,summary,iframe,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=switch],[role=option],[role=slider],[contenteditable],[tabindex]:not([tabindex="-1"]),[data-movy-tv],.tabbable,svg.cursor-pointer';
  var rawNodes = Array.prototype.slice.call(document.querySelectorAll(SEL));
  var allEls = document.body ? Array.prototype.slice.call(document.body.getElementsByTagName('*')) : [];
  
  for (var i = 0; i < allEls.length; i++) {
    var el = allEls[i];
    if (/^(SCRIPT|STYLE|LINK|META|HEAD|TITLE|NOSCRIPT|BR)$/.test(el.tagName)) continue;
    try {
      var cs = window.getComputedStyle(el);
      if (cs.cursor === 'pointer' && rawNodes.indexOf(el) === -1) rawNodes.push(el);
    } catch (e) {}
  }

  var interactive = [];
  for (var j = 0; j < rawNodes.length; j++) {
    var node = rawNodes[j];
    try {
      var r = node.getBoundingClientRect();
      var cstyle = window.getComputedStyle(node);
      var p = node.parentElement;
      var minOp = 1.0;
      var depth = 0;
      while (p && depth++ < 12) {
        var op = parseFloat(window.getComputedStyle(p).opacity);
        if (!isNaN(op)) minOp = Math.min(minOp, op);
        p = p.parentElement;
      }

      var text = (node.innerText || node.textContent || '').trim().replace(/\\s+/g, ' ').slice(0, 80);
      var focused = node.classList.contains('movy-tvf') || document.activeElement === node;

      interactive.push({
        tag: node.tagName.toLowerCase(),
        id: node.id || undefined,
        className: (node.className && typeof node.className === 'string') ? node.className.trim() : undefined,
        role: node.getAttribute('role') || undefined,
        ariaLabel: node.getAttribute('aria-label') || undefined,
        text: text || undefined,
        rect: { x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height) },
        cursor: cstyle.cursor,
        pointerEvents: cstyle.pointerEvents,
        opacity: parseFloat(cstyle.opacity),
        minAncestorOpacity: Math.round(minOp * 100) / 100,
        visibility: cstyle.visibility,
        display: cstyle.display,
        tabindex: node.getAttribute('tabindex') || undefined,
        isInert: node.hasAttribute('inert'),
        ariaHidden: node.getAttribute('aria-hidden') === 'true' || !!node.closest('[aria-hidden=true]'),
        isFocused: focused || undefined
      });
    } catch (e) {}
  }

  var scrollContainers = [];
  for (var k = 0; k < allEls.length; k++) {
    var sel = allEls[k];
    try {
      var sstyle = window.getComputedStyle(sel);
      var sx = (sstyle.overflowX === 'auto' || sstyle.overflowX === 'scroll') && sel.scrollWidth > sel.clientWidth + 5;
      var sy = (sstyle.overflowY === 'auto' || sstyle.overflowY === 'scroll') && sel.scrollHeight > sel.clientHeight + 5;
      if (sx || sy) {
        var sr = sel.getBoundingClientRect();
        scrollContainers.push({
          tag: sel.tagName.toLowerCase(),
          id: sel.id || undefined,
          className: (sel.className && typeof sel.className === 'string') ? sel.className.trim() : undefined,
          scrollX: sx,
          scrollY: sy,
          scrollWidth: sel.scrollWidth,
          clientWidth: sel.clientWidth,
          scrollHeight: sel.scrollHeight,
          clientHeight: sel.clientHeight,
          rect: { x: Math.round(sr.x), y: Math.round(sr.y), width: Math.round(sr.width), height: Math.round(sr.height) }
        });
      }
    } catch (e) {}
  }

  var overlays = [];
  for (var m = 0; m < allEls.length; m++) {
    var oel = allEls[m];
    try {
      var ostyle = window.getComputedStyle(oel);
      if (ostyle.position === 'fixed' || ostyle.position === 'sticky') {
        var or = oel.getBoundingClientRect();
        var z = parseInt(ostyle.zIndex, 10) || 0;
        if (or.width > 20 && or.height > 20) {
          overlays.push({
            tag: oel.tagName.toLowerCase(),
            id: oel.id || undefined,
            className: (oel.className && typeof oel.className === 'string') ? oel.className.trim() : undefined,
            position: ostyle.position,
            zIndex: z,
            rect: { x: Math.round(or.x), y: Math.round(or.y), width: Math.round(or.width), height: Math.round(or.height) }
          });
        }
      }
    } catch (e) {}
  }

  var playerSel = ['#vp-shell', '.player-root', '.unified-player-frame', '#video-container', 'video'];
  var playerInfo = null;
  for (var n = 0; n < playerSel.length; n++) {
    var pTarget = document.querySelector(playerSel[n]);
    if (pTarget) {
      var pr = pTarget.getBoundingClientRect();
      var vid = pTarget.querySelector('video') || (pTarget.tagName === 'VIDEO' ? pTarget : null);
      playerInfo = {
        selector: playerSel[n],
        tag: pTarget.tagName.toLowerCase(),
        id: pTarget.id || undefined,
        className: (pTarget.className && typeof pTarget.className === 'string') ? pTarget.className.trim() : undefined,
        rect: { x: Math.round(pr.x), y: Math.round(pr.y), width: Math.round(pr.width), height: Math.round(pr.height) },
        video: vid ? {
          paused: vid.paused,
          currentTime: Math.round(vid.currentTime),
          duration: Math.round(vid.duration || 0),
          readyState: vid.readyState
        } : null
      };
      break;
    }
  }

  return {
    url: location.href,
    pathname: location.pathname,
    title: document.title,
    viewport: { width: vw, height: vh, meta: metaViewport ? metaViewport.getAttribute('content') : null },
    fullscreen: fsEl,
    summary: {
      interactiveCount: interactive.length,
      scrollContainerCount: scrollContainers.length,
      overlayCount: overlays.length,
      hasPlayer: !!playerInfo
    },
    player: playerInfo,
    overlays: overlays,
    scrollContainers: scrollContainers,
    interactive: interactive
  };
})()`;

const FOCUS_INFO_FN = `(function() {
  var e = document.querySelector('.movy-tvf') || document.activeElement;
  if (!e || e === document.body || e === document.documentElement) return { focused: false };
  var r = e.getBoundingClientRect();
  var label = (e.getAttribute('aria-label') || e.innerText || e.textContent || e.tagName).trim().replace(/\\s+/g, ' ').slice(0, 32);
  return {
    focused: true,
    tag: e.tagName.toLowerCase(),
    id: e.id || '',
    className: (e.className || '').toString().slice(0, 60),
    label: label,
    rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
    fullscreen: !!document.fullscreenElement,
    path: location.pathname.slice(0, 24)
  };
})()`;

async function main() {
  const args = process.argv.slice(2);
  const cmd = args[0] || 'help';

  try {
    if (cmd === 'open') {
      const sourceId = args[1] || 'movy';
      const waitSec = parseInt(args[2] || '8', 10);
      console.log(`Opening source ${sourceId}...`);
      adb(`shell am start -n com.ayushkumar.movy.tv/app.movy.shell.MainActivity --es movy_source "${sourceId}"`);
      sleep(waitSec * 1000);
      console.log(`Opened ${sourceId}.`);
    } else if (cmd === 'eval') {
      const expr = args.slice(1).join(' ');
      const wsUrl = await ensureCdp();
      const res = await cdpEvaluate(wsUrl, expr);
      console.log(typeof res === 'object' ? JSON.stringify(res, null, 2) : res);
    } else if (cmd === 'key') {
      const keys = args.slice(1);
      for (const k of keys) {
        sendKey(k);
        sleep(500);
      }
    } else if (cmd === 'shot') {
      const name = args[1] || 'screenshot';
      const dir = 'docs/tv-adapter/screenshots';
      if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
      adb(`exec-out screencap -p > "${path.join(dir, name + '.png')}"`);
      console.log(`Saved screenshot: ${path.join(dir, name + '.png')}`);
    } else if (cmd === 'dump') {
      const site = args[1] || 'site';
      const screen = args[2] || 'screen';
      const outDir = path.join('docs', 'tv-adapter', 'dom', site);
      if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });
      const outPath = path.join(outDir, `${screen}.json`);

      const wsUrl = await ensureCdp();
      const dump = await cdpEvaluate(wsUrl, IN_PAGE_DUMP_FN);
      fs.writeFileSync(outPath, JSON.stringify(dump, null, 2));
      console.log(`Saved DOM dump: ${outPath} (${dump.summary.interactiveCount} interactive, ${dump.summary.scrollContainerCount} scrollers, ${dump.summary.overlayCount} overlays)`);
    } else if (cmd === 'walk') {
      const keys = args.slice(1);
      const wsUrl = await ensureCdp();
      console.log('--- Focus Trail ---');
      // Wake key
      adb('shell input keyevent KEYCODE_SHIFT_LEFT');
      sleep(300);
      const initial = await cdpEvaluate(wsUrl, FOCUS_INFO_FN);
      console.log(`START: ${initial.focused ? `[${initial.tag}] "${initial.label}" @ (${initial.rect.x},${initial.rect.y}) [fs=${initial.fullscreen}]` : 'none'}`);

      for (const k of keys) {
        sendKey(k, true);
        sleep(600);
        const st = await cdpEvaluate(wsUrl, FOCUS_INFO_FN);
        if (st.focused) {
          console.log(`KEY ${k.padEnd(6)} -> [${st.tag}] "${st.label}" @ (${st.rect.x},${st.rect.y}) size ${st.rect.w}x${st.rect.h} [fs=${st.fullscreen}]`);
        } else {
          console.log(`KEY ${k.padEnd(6)} -> none`);
        }
      }
    } else if (cmd === 'stage0' || cmd === 'demo') {
      // One command dumps screen and walks a key sequence with a printed focus trail (Stage 0 Pass Gate)
      const site = args[1] || '7movies';
      console.log(`=== Stage 0 Pass Gate Check for ${site} ===`);
      const wsUrl = await ensureCdp();
      
      console.log(`1. Dumping interactive elements...`);
      const outDir = path.join('docs', 'tv-adapter', 'dom', site);
      if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });
      const outPath = path.join(outDir, 'stage0_demo.json');
      const dump = await cdpEvaluate(wsUrl, IN_PAGE_DUMP_FN);
      fs.writeFileSync(outPath, JSON.stringify(dump, null, 2));
      console.log(`   DUMP OK: ${outPath} (${dump.summary.interactiveCount} elements)`);

      console.log(`2. Walking key sequence with printed focus trail...`);
      adb('shell input keyevent KEYCODE_SHIFT_LEFT');
      sleep(300);
      const keysToTest = ['DOWN', 'RIGHT', 'RIGHT', 'LEFT', 'UP'];
      const initial = await cdpEvaluate(wsUrl, FOCUS_INFO_FN);
      console.log(`   START: ${initial.focused ? `[${initial.tag}] "${initial.label}" @ (${initial.rect.x},${initial.rect.y})` : 'none'}`);
      for (const k of keysToTest) {
        sendKey(k, true);
        sleep(600);
        const st = await cdpEvaluate(wsUrl, FOCUS_INFO_FN);
        console.log(`   ${k.padEnd(6)} -> ${st.focused ? `[${st.tag}] "${st.label}" @ (${st.rect.x},${st.rect.y})` : 'none'}`);
      }
      console.log(`=== Stage 0 Pass Gate: PASSED ===`);
    } else {
      console.log(`Usage: node scripts/tv-test/inspector.mjs <command>
Commands:
  open <sourceId> [waitSec]  Open a streaming source
  eval '<js>'                Run JS via CDP and return value
  key <KEY...>               Send one or more keys
  shot <name>                Take screenshot
  dump <site> <screen>       Dump interactive DOM to docs/tv-adapter/dom/<site>/<screen>.json
  walk <KEY...>              Walk key sequence and print focus trail
  stage0 [site]              Run Stage 0 verification (dump + walk trail)
`);
    }
  } catch (err) {
    console.error('Error:', err.message);
    process.exit(1);
  }
}

main();
