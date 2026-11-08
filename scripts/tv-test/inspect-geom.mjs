import { execSync } from 'node:child_process';

const ADB = 'C:\\Users\\ak818\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe';
const SERIAL = 'emulator-5554';
const PORT = 9222;

const pid = execSync(`"${ADB}" -s ${SERIAL} shell pidof com.ayushkumar.movy.tv`).toString().trim().split(/\s+/)[0];
execSync(`"${ADB}" -s ${SERIAL} forward tcp:${PORT} localabstract:webview_devtools_remote_${pid}`);

const res = await fetch(`http://127.0.0.1:${PORT}/json/list`);
const list = await res.json();
const page = list.find(p => p.type === 'page');

const ws = new WebSocket(page.webSocketDebuggerUrl);
ws.onopen = () => {
  ws.send(JSON.stringify({
    id: 1,
    method: 'Runtime.evaluate',
    params: {
      expression: `(() => {
        const cur = document.querySelector('.movy-tvf') || document.activeElement;
        const r = cur ? cur.getBoundingClientRect() : null;
        return JSON.stringify({
          url: location.href,
          scrollY: window.scrollY,
          focused: cur ? {
            tag: cur.tagName,
            text: (cur.textContent || '').trim().substring(0, 40),
            cls: cur.className,
            x: Math.round(r.x),
            y: Math.round(r.y),
            w: Math.round(r.width),
            h: Math.round(r.height)
          } : null,
          visualViewport: {
            width: window.visualViewport ? window.visualViewport.width : null,
            height: window.visualViewport ? window.visualViewport.height : null,
            scale: window.visualViewport ? window.visualViewport.scale : null
          },
          dpr: window.devicePixelRatio,
          innerW: window.innerWidth,
          innerH: window.innerHeight
        });
      })()`,
      returnByValue: true
    }
  }));
};
ws.onmessage = (e) => {
  console.log(e.data);
  process.exit(0);
};
