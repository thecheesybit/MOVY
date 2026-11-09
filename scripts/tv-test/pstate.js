// Evaluated via cdp.mjs / cdpf.mjs -f: compact player state of this document (focus, menu, fade, fullscreen, subtitle track).
(() => {
  const TV = window.__movyTv;
  const a = document.activeElement;
  const ring = document.querySelector('.movy-tvf');
  const t = (e) => (e ? ((e.innerText || e.getAttribute('aria-label') || e.id || e.tagName) + '').trim().replace(/\s+/g, ' ').slice(0, 30) : null);
  const v = document.querySelector('video');
  let track = null;
  if (v) for (const x of v.textTracks) if (x.mode === 'showing') track = (x.label || x.language || 'on');
  const menu = TV && TV.menuNow && TV.menuNow();
  const m = menu ? menu.id || String(menu.className).slice(0, 24) : null;
  return JSON.stringify({ fs: !!document.fullscreenElement, menu: m, focus: t(a === document.body ? null : a), ring: t(ring), fade: document.documentElement.classList.contains('movy-pctl-off'),
    paused: v ? v.paused : null, track, agg: TV && TV.childAgg || null, frame: window.parent !== window });
})()
