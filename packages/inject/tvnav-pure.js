// Movy TV navigation: pure functions (no DOM). Rects are {l,t,r,b}. Loaded before tvnav.js in the browser
// (exported as window.__movyTvPure) and required by the node unit tests (module.exports).
(function (root) {
  'use strict';

  var DIRS = { left: [-1, 0], right: [1, 0], up: [0, -1], down: [0, 1] };

  // ---------- pure: scoring ----------

  /** rect: {l,t,r,b}. Returns a score (lower is better) or -1 when `to` is not a candidate in direction `dir`. */
  function score(from, to, dir) {
    var d = DIRS[dir];
    var fcx = (from.l + from.r) / 2, fcy = (from.t + from.b) / 2;
    var tcx = (to.l + to.r) / 2, tcy = (to.t + to.b) / 2;
    var main, gap, perpCenter, perpGap, fromHalfPerp, toHalfPerp;
    if (d[0] !== 0) {
      main = (tcx - fcx) * d[0];
      gap = d[0] > 0 ? to.l - from.r : from.l - to.r;
      perpCenter = Math.abs(tcy - fcy);
      perpGap = Math.max(0, Math.max(from.t, to.t) - Math.min(from.b, to.b));
      fromHalfPerp = (from.b - from.t) / 2;
      toHalfPerp = (to.b - to.t) / 2;
    } else {
      main = (tcy - fcy) * d[1];
      gap = d[1] > 0 ? to.t - from.b : from.t - to.b;
      perpCenter = Math.abs(tcx - fcx);
      perpGap = Math.max(0, Math.max(from.l, to.l) - Math.min(from.r, to.r));
      fromHalfPerp = (from.r - from.l) / 2;
      toHalfPerp = (to.r - to.l) / 2;
    }
    if (main <= 1) return -1; // must lie ahead of the current element's centre
    if (gap < -Math.min(fromHalfPerp, toHalfPerp) * 0.5 - 2 && gap < 0) {
      // heavily overlapping along the axis: only accept when clearly ahead
      if (main < 8) return -1;
    }
    // cone: roughly +-68 degrees, widened by the element sizes so big cards stay reachable
    if (perpCenter > main * 2.5 + fromHalfPerp + toHalfPerp) return -1;
    var g = Math.max(0, gap);
    return g + perpGap * 2.4 + perpCenter * 0.6 + main * 0.05;
  }

  /** cands: [{rect}] ; returns the best candidate for `dir` from `from`, or null. */
  function pick(from, cands, dir) {
    var best = null, bestScore = Infinity;
    for (var i = 0; i < cands.length; i++) {
      var s = score(from, cands[i].rect, dir);
      if (s >= 0 && s < bestScore) { best = cands[i]; bestScore = s; }
    }
    return best;
  }

  /** First element in reading order inside the viewport (used when nothing is focused yet). */
  function pickInitial(cands, vw, vh) {
    var best = null, bestKey = Infinity;
    for (var i = 0; i < cands.length; i++) {
      var r = cands[i].rect;
      if (r.b < 0 || r.t > vh || r.r < 0 || r.l > vw) continue;
      var key = Math.max(0, r.t) * 3 + Math.max(0, r.l);
      if (key < bestKey) { best = cands[i]; bestKey = key; }
    }
    return best;
  }

  /** Seeks by `delta` seconds within [0, duration]. */
  function seekTarget(current, duration, delta) {
    var t = current + delta;
    if (t < 0) t = 0;
    if (isFinite(duration) && duration > 0 && t > duration) t = duration;
    return t;
  }

  function fmt(sec) {
    if (!isFinite(sec) || sec < 0) sec = 0;
    sec = Math.floor(sec);
    var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    var mm = (h > 0 && m < 10 ? '0' : '') + m, ss = (s < 10 ? '0' : '') + s;
    return (h > 0 ? h + ':' : '') + mm + ':' + ss;
  }


  // ---------- pure: visibility ----------

  /**
   * Whether an element described by `d` can take focus. d: {w, h, display, visibility, pointerEvents, opacity,
   * hiddenAncestor, closedDetails, offscreenLayer, disabled}. Never focusable: anything not rendered, inside a closed
   * <details>, inside an aria-hidden ancestor or an off-screen slider layer.
   */
  function renderable(d, minSize) {
    var m = minSize == null ? 14 : minSize;
    if (!d || d.disabled) return false;
    if (d.w < m || d.h < m) return false;
    if (d.display === 'none' || d.visibility === 'hidden' || d.visibility === 'collapse') return false;
    if (d.pointerEvents === 'none') return false;
    if (d.opacity !== undefined && d.opacity === 0) return false;
    if (d.hiddenAncestor || d.closedDetails || d.offscreenLayer) return false;
    return true;
  }

  // ---------- pure: rows, lines and column keeping ----------

  function cx(r) { return (r.l + r.r) / 2; }
  function cy(r) { return (r.t + r.b) / 2; }

  /** Vertical overlap of two rects as a fraction of the smaller height. */
  function vOverlap(a, b) {
    var o = Math.min(a.b, b.b) - Math.max(a.t, b.t);
    var h = Math.min(a.b - a.t, b.b - b.t);
    return h > 0 ? o / h : 0;
  }

  /**
   * Groups items [{id, rect, row}] into horizontal lines. All items with the same non-null `row` form one line (a
   * carousel); free items (row == null) join a line when they overlap vertically by at least half. Lines are sorted by
   * centre y, and each line's items by centre x.
   */
  function buildLines(items) {
    var byRow = {}, lines = [], free = [], i, it, l;
    for (i = 0; i < items.length; i++) {
      it = items[i];
      if (it.row != null) {
        l = byRow[it.row];
        if (!l) { l = byRow[it.row] = { row: it.row, items: [], free: false }; lines.push(l); }
        l.items.push(it);
      } else free.push(it);
    }
    free.sort(function (a, b) { return cy(a.rect) - cy(b.rect); });
    var freeLines = [];
    for (i = 0; i < free.length; i++) {
      it = free[i];
      l = null;
      for (var j = freeLines.length - 1; j >= 0 && j >= freeLines.length - 3; j--) {
        if (vOverlap(it.rect, freeLines[j].box) >= 0.5) { l = freeLines[j]; break; }
      }
      if (!l) { l = { row: null, items: [], free: true, box: { l: it.rect.l, t: it.rect.t, r: it.rect.r, b: it.rect.b } }; freeLines.push(l); lines.push(l); }
      l.items.push(it);
      l.box = { l: Math.min(l.box.l, it.rect.l), t: Math.min(l.box.t, it.rect.t), r: Math.max(l.box.r, it.rect.r), b: Math.max(l.box.b, it.rect.b) };
    }
    for (i = 0; i < lines.length; i++) {
      l = lines[i];
      l.items.sort(function (a, b) { return cx(a.rect) - cx(b.rect); });
      var t = Infinity, b = -Infinity;
      for (var k = 0; k < l.items.length; k++) { t = Math.min(t, l.items[k].rect.t); b = Math.max(b, l.items[k].rect.b); }
      l.top = t; l.bottom = b; l.cy = (t + b) / 2;
    }
    lines.sort(function (a, b2) { return a.cy - b2.cy; });
    return lines;
  }

  function lineOf(lines, id) {
    for (var i = 0; i < lines.length; i++) {
      for (var j = 0; j < lines[i].items.length; j++) if (lines[i].items[j].id === id) return { line: i, index: j };
    }
    return null;
  }

  /** Item of `line` closest to column x: one containing x wins, then the nearest edge, then the nearest centre. */
  function nearestInColumn(line, x, vw) {
    var best = null, bd = Infinity, bc = Infinity;
    for (var i = 0; i < line.items.length; i++) {
      var r = line.items[i].rect, c = cx(r);
      if (vw && (c < 0 || c > vw)) continue;
      var d = x < r.l ? r.l - x : x > r.r ? x - r.r : 0;
      var dc = Math.abs(c - x);
      if (d < bd || (d === bd && dc < bc)) { best = line.items[i]; bd = d; bc = dc; }
    }
    return best;
  }

  /** UP/DOWN: nearest card of the previous/next line, keeping the column `x`. Returns an item or null. */
  function pickVertical(lines, id, dir, x, vw) {
    var at = lineOf(lines, id);
    if (!at) return null;
    var step = dir === 'down' ? 1 : -1;
    var curLine = lines[at.line];
    for (var i = at.line + step; i >= 0 && i < lines.length; i += step) {
      var l = lines[i];
      if (Math.abs(l.cy - curLine.cy) < 4) continue;
      var it = nearestInColumn(l, x, vw);
      if (it) return it;
    }
    return null;
  }

  /**
   * LEFT/RIGHT: inside a carousel the neighbour in order (null at the ends, the row never leaks); otherwise the
   * neighbour on the same free line, else the best spatial candidate among all items.
   */
  function pickHorizontal(lines, id, dir, from, all) {
    var at = lineOf(lines, id);
    if (!at) return pick(from, all, dir);
    var line = lines[at.line];
    var step = dir === 'right' ? 1 : -1;
    if (!line.free) return line.items[at.index + step] || null;
    var next = line.items[at.index + step];
    if (next) return next;
    return pick(from, all.filter(function (c) { return c.id !== id && line.items.indexOf(c) < 0; }), dir);
  }

  /** Column bookkeeping: horizontal moves reset the column to the new element's centre; vertical moves keep it. */
  function nextColumn(col, rect, dir) {
    if (dir === 'up' || dir === 'down') return col == null ? cx(rect) : col;
    return cx(rect);
  }

  /** Horizontal scroll delta for a card inside a scroller so it is fully visible with a peek of the next one. */
  function rowScrollDelta(card, view, peek) {
    var p = peek || 0;
    if (card.l - p < view.l) return card.l - p - view.l;
    if (card.r + p > view.r) return card.r + p - view.r;
    return 0;
  }

  // ---------- pure: player menus ----------

  /** A row that opens a sub-list or page instead of choosing a value (stays in the menu after OK). */
  function menuIsDrill(text, expandable) {
    var t = String(text || '').trim();
    if (expandable) return true;
    if (/(^|\s)\d{1,4}$/.test(t)) return true; // a language group with its track count ("English 73")
    return /\b(customi[sz]e|appearance|sync|search on|search subtitles|settings|more options)\b/i.test(t);
  }

  /** Rows that open a file picker or a translation tool: never focused with a remote. */
  function menuIsUnusable(text) {
    return /\b(upload|translate|download)\b/i.test(String(text || ''));
  }

  function itemCy(it) { return (it.rect.t + it.rect.b) / 2; }
  function itemCx(it) { return (it.rect.l + it.rect.r) / 2; }

  /**
   * Splits menu items [{id, rect, col, selected}] (DOM order; col = column index, or -1 when outside every column) into
   * columns and footer rows. Footer items on one visual line form a row, rows go top to bottom.
   */
  function menuGroups(items) {
    var cols = [], foot = [], i, it, c;
    for (i = 0; i < items.length; i++) {
      it = items[i];
      if (it.col >= 0) {
        c = cols[it.col];
        if (!c) { c = []; cols[it.col] = c; }
        c.push(it);
      } else foot.push(it);
    }
    var columns = [];
    for (i = 0; i < cols.length; i++) if (cols[i] && cols[i].length) columns.push(cols[i]);
    var rows = [];
    foot.sort(function (a, b) { return itemCy(a) - itemCy(b) || itemCx(a) - itemCx(b); });
    for (i = 0; i < foot.length; i++) {
      var last = rows[rows.length - 1];
      if (last && Math.abs(itemCy(last[0]) - itemCy(foot[i])) < 10) last.push(foot[i]); else rows.push([foot[i]]);
    }
    for (i = 0; i < rows.length; i++) rows[i].sort(function (a, b) { return itemCx(a) - itemCx(b); });
    return { columns: columns, footer: rows };
  }

  /** The item that gets focus when a menu opens: the selected one (leftmost column first), else the first of the first column. */
  function menuInitial(g) {
    var i, j;
    for (i = 0; i < g.columns.length; i++) for (j = 0; j < g.columns[i].length; j++) if (g.columns[i][j].selected) return g.columns[i][j];
    if (g.columns.length) return g.columns[0][0];
    return g.footer.length ? g.footer[0][0] : null;
  }

  function locate(g, id) {
    var i, j;
    for (i = 0; i < g.columns.length; i++) for (j = 0; j < g.columns[i].length; j++) if (g.columns[i][j].id === id) return { kind: 'col', c: i, i: j };
    for (i = 0; i < g.footer.length; i++) for (j = 0; j < g.footer[i].length; j++) if (g.footer[i][j].id === id) return { kind: 'foot', r: i, i: j };
    return null;
  }

  function closestBy(list, key) {
    var best = null, bd = Infinity;
    for (var i = 0; i < list.length; i++) { var d = Math.abs(key(list[i])); if (d <= bd) { best = list[i]; bd = d; } }
    return best;
  }

  /**
   * A menu without columns is one list laid out on screen: rows of one item (a plain list) or of several (a tile grid).
   * LEFT/RIGHT step along the row, UP/DOWN go to the nearest row and its item closest in x. null = the menu edge.
   */
  function gridStep(list, cur, dir) {
    var ROW = 10, cy = itemCy(cur), cx = itemCx(cur), horizontal = dir === 'left' || dir === 'right';
    var forward = dir === 'down' || dir === 'right', cands = [], i, it, dy, dx, nearest = Infinity;
    for (i = 0; i < list.length; i++) {
      it = list[i];
      if (it === cur) continue;
      dy = itemCy(it) - cy; dx = itemCx(it) - cx;
      if (horizontal) { if (Math.abs(dy) >= ROW || (forward ? dx <= 0 : dx >= 0)) continue; cands.push({ it: it, d: Math.abs(dx) }); continue; }
      if (forward ? dy <= ROW : dy >= -ROW) continue;
      nearest = Math.min(nearest, Math.abs(dy));
      cands.push({ it: it, dy: Math.abs(dy), d: Math.abs(dx) });
    }
    var best = null, bd = Infinity;
    for (i = 0; i < cands.length; i++) {
      if (!horizontal && cands[i].dy - nearest >= ROW) continue; // not on the nearest row
      if (cands[i].d < bd) { best = cands[i].it; bd = cands[i].d; }
    }
    return best;
  }

  /**
   * The item to move to from `id`, or null to stay put (the menu edge). UP/DOWN walk a column in DOM order; DOWN past its
   * end enters the footer, UP from the footer returns to the nearest column. LEFT/RIGHT jump to the column on that side at
   * the closest height (inside a footer row they step along the row).
   */
  function menuStep(g, id, dir) {
    var at = locate(g, id), cur;
    if (!at) return null;
    var vertical = dir === 'up' || dir === 'down', step = dir === 'down' || dir === 'right' ? 1 : -1, j;
    if (at.kind === 'col') {
      cur = g.columns[at.c][at.i];
      if (g.columns.length === 1 && !g.footer.length) return gridStep(g.columns[0], cur, dir); // one list: follow the screen layout
      if (vertical) {
        var next = g.columns[at.c][at.i + step];
        if (next) return next;
        return dir === 'down' && g.footer.length ? closestBy(g.footer[0], function (x) { return itemCx(x) - itemCx(cur); }) : null;
      }
      j = at.c + step;
      if (j < 0 || j >= g.columns.length) return null;
      return closestBy(g.columns[j], function (x) { return itemCy(x) - itemCy(cur); });
    }
    cur = g.footer[at.r][at.i];
    if (!vertical) return g.footer[at.r][at.i + step] || null;
    var row = g.footer[at.r + step];
    if (row) return closestBy(row, function (x) { return itemCx(x) - itemCx(cur); });
    if (dir === 'up' && g.columns.length) {
      var col = closestBy(g.columns, function (c) { return itemCx(c[0]) - itemCx(cur); });
      return col[col.length - 1];
    }
    return null;
  }

  /**
   * The value a range slider takes after one LEFT (sign -1) or RIGHT (+1) press: about 5 % of its span, in whole steps, kept on the
   * step grid and inside [min, max]. Missing or odd attributes fall back to the browser defaults (0..100, step 1).
   */
  function rangeNext(value, min, max, step, sign) {
    var lo = isFinite(min) ? +min : 0, hi = isFinite(max) ? +max : 100, st = isFinite(step) && +step > 0 ? +step : 1;
    if (hi < lo) hi = lo;
    var v = isFinite(value) ? +value : lo;
    var jump = Math.max(st, Math.round((hi - lo) / 20 / st) * st);
    var next = Math.min(hi, Math.max(lo, v + (sign < 0 ? -jump : jump)));
    next = lo + Math.round((next - lo) / st) * st;
    next = Math.min(hi, Math.max(lo, next));
    return parseFloat(next.toFixed(6));
  }

  /** Order of the ways to close a menu: the profile's own first, then the rest that are possible. */
  function menuCloseOrder(mode, available) {
    var all = ['escape', 'toggle', 'click'], out = [];
    if (all.indexOf(mode) >= 0) out.push(mode);
    for (var i = 0; i < all.length; i++) if (out.indexOf(all[i]) < 0) out.push(all[i]);
    return out.filter(function (m) { return m === 'escape' || !!(available && available[m]); });
  }

  // ---------- pure: rail inset ----------

  /** x where an element's content starts when `prop` is the property being adjusted (padding shifts content, margin/left shift the box). */
  function insetContentStart(prop, rectLeft, base) {
    return prop === 'padding-left' ? rectLeft + (base || 0) : rectLeft;
  }

  /** Extra whole pixels to add so content starts at `target`; 0 when it already does (never negative). */
  function insetNeed(contentStart, target) {
    var n = target - contentStart;
    return n > 0.5 ? Math.round(n) : 0;
  }

  /** Entry candidate when focus enters a frame from direction `dir`. */
  function pickEntry(cands, dir) {
    var best = null, bk = Infinity;
    for (var i = 0; i < cands.length; i++) {
      var r = cands[i].rect;
      var key = dir === 'down' ? r.t * 4 + r.l : dir === 'up' ? -r.b * 4 + r.l : dir === 'right' ? r.l * 4 + r.t : -r.r * 4 + r.t;
      if (key < bk) { best = cands[i]; bk = key; }
    }
    return best;
  }

  // ---------- pure: recent titles (drawer "Continue watching") ----------

  var DETAIL_RE = /\/(movie|movies|tv|series|show|shows|anime|watch|title|details?)\/[^\/]+/i;

  /** A page title reduced to the film's name ("Watch X - Free Streaming" -> "X"). */
  function cleanTitle(t) {
    t = String(t || '').replace(/^\s*watch\s+/i, '');
    t = t.split(/\s+[-|–•·]\s+/)[0];
    t = t.replace(/\s+(online|free|full movie|streaming).*$/i, '');
    return t.trim().slice(0, 80);
  }

  function isDetailPath(p) { return DETAIL_RE.test(p || ''); }

  /** New list with `entry` ({t, p, q?, ts}) first; an older entry with the same path (or the same q for a modal title) is dropped. */
  function addRecent(list, entry, max) {
    var rest = (list || []).filter(function (e) { return entry.q ? e.q !== entry.q : e.p !== entry.p; });
    return [entry].concat(rest).slice(0, max || 8);
  }

  /** Entries worth showing for the page at `curPath` (the title being watched is not its own "recent"), at most `n`. */
  function recentShown(list, curPath, n) {
    return (list || []).filter(function (e) {
      return e && typeof e.t === 'string' && typeof e.p === 'string' && e.p.charAt(0) === '/' && (e.p !== curPath || !!e.q);
    }).slice(0, n);
  }

  // ---------- load-time and key-repeat throttles ----------

  var IGNORED_NODES = { SCRIPT: 1, STYLE: 1, LINK: 1, META: 1, NOSCRIPT: 1, TEMPLATE: 1 };

  function relevantNode(n) {
    if (!n || n.nodeType !== 1 || IGNORED_NODES[String(n.nodeName).toUpperCase()]) return false;
    return !(typeof n.id === 'string' && n.id.indexOf('__movy') === 0);
  }

  /** True when a batch of MutationRecords can change what is focusable (elements came or went, or a state attribute flipped). */
  function mutationsRelevant(recs) {
    for (var i = 0; i < recs.length; i++) {
      var r = recs[i], j;
      if (r.type === 'attributes') return true;
      for (j = 0; r.addedNodes && j < r.addedNodes.length; j++) if (relevantNode(r.addedNodes[j])) return true;
      for (j = 0; r.removedNodes && j < r.removedNodes.length; j++) if (relevantNode(r.removedNodes[j])) return true;
    }
    return false;
  }

  /** Wait before the next candidate rebuild: at least 250 ms (600 ms while the page is still loading and nobody navigates), 8x the last cost. */
  function rebuildDelay(lastCostMs, sinceBootMs, userMoved) {
    return Math.max(!userMoved && sinceBootMs < 10000 ? 600 : 250, (lastCostMs || 0) * 8);
  }

  /** Held D-pad key: about 8 moves a second, about 14 after one second of holding. */
  function repeatInterval(heldMs) { return heldMs >= 1000 ? 70 : 125; }

  /** st = {start, last} kept by the caller. A fresh press always moves; repeats are paced. */
  function holdGate(st, repeat, now) {
    if (!repeat || !st.start) { st.start = now; st.last = now; return true; }
    if (now - st.last < repeatInterval(now - st.start)) return false;
    st.last = now;
    return true;
  }

  var api = {
    mutationsRelevant: mutationsRelevant, rebuildDelay: rebuildDelay, repeatInterval: repeatInterval, holdGate: holdGate,
    score: score, pick: pick, pickInitial: pickInitial, seekTarget: seekTarget, fmt: fmt,
    renderable: renderable, buildLines: buildLines, lineOf: lineOf, nearestInColumn: nearestInColumn,
    pickVertical: pickVertical, pickHorizontal: pickHorizontal, nextColumn: nextColumn, rowScrollDelta: rowScrollDelta,
    pickEntry: pickEntry, insetContentStart: insetContentStart, insetNeed: insetNeed,
    menuIsDrill: menuIsDrill, menuIsUnusable: menuIsUnusable, menuGroups: menuGroups, menuInitial: menuInitial, menuStep: menuStep,
    menuCloseOrder: menuCloseOrder, rangeNext: rangeNext,
    cleanTitle: cleanTitle, isDetailPath: isDetailPath, addRecent: addRecent, recentShown: recentShown
  };
  if (typeof module === 'object' && module && module.exports) module.exports = api;
  else if (typeof window !== 'undefined') window.__movyTvPure = api;
})(typeof window !== 'undefined' ? window : {});
