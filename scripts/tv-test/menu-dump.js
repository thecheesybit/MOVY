// Evaluated in the TV page via cdp.mjs -f: describes the open player menu (container, columns, items, selected markers).
(() => {
  const sel = window.__movyTv && window.__movyTv.PF && window.__movyTv.PF.player.menu;
  const menu = window.__movyTv && window.__movyTv.menuNow && window.__movyTv.menuNow();
  const out = { sel, found: !!menu };
  const root = menu || document.body;
  const items = [...root.querySelectorAll('button,[role=option],[role=menuitem],[role=radio],li,a[href],input,.lb-opt,[tabindex]')].filter((e) => {
    const r = e.getBoundingClientRect();
    return r.width > 6 && r.height > 6;
  });
  out.n = items.length;
  out.items = items.slice(0, 60).map((e) => {
    const r = e.getBoundingClientRect();
    const attrs = ['aria-checked', 'aria-selected', 'aria-current', 'aria-expanded', 'data-state', 'tabindex', 'type', 'role'].map((a) => (e.getAttribute(a) != null ? a + '=' + e.getAttribute(a) : '')).filter(Boolean).join(' ');
    return [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height), e.tagName.toLowerCase(), String(e.className).slice(0, 70), attrs, (e.innerText || e.value || '').trim().slice(0, 28)].join(' | ');
  });
  return JSON.stringify(out, null, 1);
})()
