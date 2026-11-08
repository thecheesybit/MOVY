#!/usr/bin/env node
// usage: node cdp.mjs '<js expression>'   (or: node cdp.mjs -f file.js)  -> prints the JSON result from the TV WebView.
import { execSync } from 'node:child_process';
import fs from 'node:fs';

const ADB = 'C:/Users/ak818/AppData/Local/Android/Sdk/platform-tools/adb.exe';
const SERIAL = process.env.SERIAL || 'emulator-5554';
const PORT = Number(process.env.CDP_PORT || 9222);

const adb = (c) => execSync(`"${ADB}" -s ${SERIAL} ${c}`, { encoding: 'utf8' }).trim();
const pid = adb('shell pidof com.ayushkumar.movy.tv').split(/\s+/)[0];
adb(`forward tcp:${PORT} localabstract:webview_devtools_remote_${pid}`);
const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const page = list.find((p) => p.type === 'page');
if (!page) throw new Error('no page');
const args = process.argv.slice(2);
const expr = args[0] === '-f' ? fs.readFileSync(args[1], 'utf8') : args.join(' ');
const ws = new WebSocket(page.webSocketDebuggerUrl);
const timer = setTimeout(() => { console.error('timeout'); process.exit(2); }, 20000);
ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true, awaitPromise: true } }));
ws.onmessage = (m) => {
  const d = JSON.parse(m.data);
  clearTimeout(timer);
  if (d.result?.exceptionDetails) console.error(d.result.exceptionDetails.exception?.description || 'exception');
  else console.log(JSON.stringify(d.result?.result?.value));
  ws.close();
  process.exit(0);
};
