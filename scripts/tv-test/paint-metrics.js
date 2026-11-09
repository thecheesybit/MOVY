// Evaluated in the TV page via cdp.mjs -f: when did the first paint and the posters in view finish?
// Times are device epoch ms (timeOrigin + entry time), comparable with `logcat -v epoch` seconds * 1000.
(() => {
  const o = performance.timeOrigin;
  const fcp = (performance.getEntriesByType('paint').find((p) => p.name === 'first-contentful-paint') || {}).startTime;
  const res = new Map(performance.getEntriesByType('resource').map((r) => [r.name, r]));
  const vh = innerHeight, vw = innerWidth;
  const ends = [];
  let inView = 0, cached = 0;
  for (const im of document.images) {
    const r = im.getBoundingClientRect();
    if (r.width < 20 || r.height < 20 || r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw) continue;
    inView++;
    const e = res.get(im.currentSrc);
    if (e && e.responseEnd) ends.push(e.responseEnd);
    if (e && e.transferSize === 0) cached++;
  }
  ends.sort((a, b) => a - b);
  const q = (p) => (ends.length ? Math.round(o + ends[Math.min(ends.length - 1, Math.floor(ends.length * p))]) : null);
  return JSON.stringify({ origin: Math.round(o), fcp: fcp == null ? null : Math.round(o + fcp), imgInView: inView, imgTimed: ends.length, imgNoTransfer: cached, p50: q(0.5), p90: q(0.9), now: Math.round(o + performance.now()) });
})()
