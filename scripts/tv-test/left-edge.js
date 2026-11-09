// Evaluated in the TV page via cdp.mjs -f: where does the content start (x) and which wrappers hold it?
// Lists the left-most visible text/image/button leaves (excluding the shell's own rail) with their ancestors' paddings.
(() => {
  const vw = innerWidth, vh = innerHeight;
  const out = [];
  const seen = new Set();
  const all = document.body.getElementsByTagName('*');
  for (const el of all) {
    if (el.closest('#__movy_tv_rail,#__movy_tv_rail_scrim') || /^scroll /i.test(el.getAttribute('aria-label') || '')) continue;
    const r = el.getBoundingClientRect();
    if (r.width < 8 || r.height < 8 || r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw / 2) continue;
    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) < 0.1) continue;
    const leafText = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim().length > 1);
    const visual = el.tagName === 'IMG' || el.tagName === 'BUTTON' || el.tagName === 'A' || leafText;
    if (!visual) continue;
    // ignore full-bleed backdrops
    if (r.width > vw * 0.8 && r.height > vh * 0.5) continue;
    out.push({ el, left: Math.round(r.left), top: Math.round(r.top), w: Math.round(r.width) });
  }
  out.sort((a, b) => a.left - b.left);
  const desc = (e) => e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (typeof e.className === 'string' && e.className ? '.' + e.className.trim().split(/\s+/).slice(0, 3).join('.') : '');
  const rows = [];
  for (const o of out) {
    if (rows.length >= 8) break;
    const chain = [];
    for (let p = o.el.parentElement, n = 0; p && p !== document.body && n < 6; p = p.parentElement, n++) {
      const c = getComputedStyle(p), r = p.getBoundingClientRect();
      chain.push(desc(p).slice(0, 48) + ' [L' + Math.round(r.left) + ' pl' + c.paddingLeft + ' ml' + c.marginLeft + (c.position === 'fixed' || c.position === 'absolute' ? ' ' + c.position + ' left:' + c.left : '') + ']');
    }
    rows.push({ x: o.left, y: o.top, el: desc(o.el).slice(0, 60), chain });
  }
  return JSON.stringify({ vw, minLeft: out.length ? out[0].left : null, rows }, null, 1);
})()
