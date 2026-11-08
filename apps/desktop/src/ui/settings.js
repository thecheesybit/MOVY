'use strict';
// iOS-style Settings card: inset grouped sections, icon badges, spring switches, push sub-page, confirm alerts.
// A pure renderer: it reads main's state snapshot and sends actions; nothing here knows a URL.
(function (root) {
  var h = UI.h;
  var play = UI.play;
  var T = UI.T;

  var ICON = {
    play: 'M8 5v14l11-7z',
    replay: 'M12 5V1L7 6l5 5V7a6 6 0 1 1-6 6H4a8 8 0 1 0 8-8z',
    shield: 'M12 1 3 5v6c0 5.5 3.8 10.7 9 12 5.2-1.3 9-6.5 9-12V5l-9-4z',
    block: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zM4 12a8 8 0 0 1 12.9-6.3L5.7 16.9A8 8 0 0 1 4 12zm8 8a8 8 0 0 1-4.9-1.7L18.3 7.1A8 8 0 0 1 12 20z',
    storage: 'M2 20h20v-4H2v4zm2-3h2v2H4v-2zM2 4v4h20V4H2zm4 3H4V5h2v2zm-4 7h20v-4H2v4zm2-3h2v2H4v-2z',
  };
  var BADGE = { blue: '#0a84ff', green: '#30d158', orange: '#ff9f0a', purple: '#bf5af2', grey: '#8e8e93', red: '#ff453a' };

  function badge(color, icon) {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    var p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    p.setAttribute('d', ICON[icon]);
    svg.appendChild(p);
    var b = h('span.badge', { style: 'background:' + color });
    b.appendChild(svg);
    return b;
  }

  // ---- row factories: { el, update(state) } ----
  function toggleRow(label, color, icon, read, onSet) {
    var sw = h('span.switch', { role: 'switch', 'aria-checked': 'false' });
    var el = h('button.row', { role: 'switch', onclick: function () { onSet(sw.getAttribute('aria-checked') !== 'true'); } },
      badge(color, icon), h('span.label', { text: label }), sw);
    return { el: el, update: function (s) { var on = read(s); sw.setAttribute('aria-checked', String(on)); el.setAttribute('aria-checked', String(on)); } };
  }
  function valueRow(label, color, icon, read) {
    var v = h('span.value');
    return { el: h('div.row', null, badge(color, icon), h('span.label', { text: label }), v), update: function (s) { v.textContent = read(s); } };
  }
  function navRow(label, color, icon, read, onOpen) {
    var v = h('span.value');
    return { el: h('button.row', { onclick: onOpen }, badge(color, icon), h('span.label', { text: label }), v, h('span.chev', { text: '›' })),
      update: function (s) { v.textContent = read(s); } };
  }
  function actionRow(label, cls, onClick) {
    return { el: h('button.row.plain.' + cls, { onclick: onClick }, h('span.label', { text: label })), update: function () {} };
  }

  function section(head, foot, rows, plain) {
    var group = h('div.group' + (plain ? '.nobadge' : ''), null, rows.map(function (r) { return r.el; }));
    var el = h('div.section', null, head ? h('div.head', { text: head }) : null, group, foot || null);
    return { el: el, rows: rows, update: function (s) { rows.forEach(function (r) { r.update(s); }); } };
  }

  function create(rootEl, act) {
    var card = h('div.card', { role: 'dialog', 'aria-label': 'Settings' }, h('div.grab'));
    var pagesEl = h('div.pages');
    card.appendChild(pagesEl);
    var toast = h('div.toast');
    card.appendChild(toast);
    rootEl.appendChild(card);

    var last = null;
    var sections = [];
    var sourcesSig = '';
    var stack = [];
    var closing = false;
    var rootBody = null;
    var sourcesHolder = null;
    var defaultPage = null;

    function pageEl(title, backLabel, endLabel, onBack, onEnd, bodyChildren, big) {
      var back = h('button', { text: backLabel ? '‹ ' + backLabel : '', onclick: onBack });
      var end = h('button.end', { text: endLabel || '', onclick: onEnd });
      var body = h('div.body', null, big ? h('div.big', { text: big }) : null, bodyChildren);
      var page = h('div.page', null, h('div.bar', null, back, h('span.ttl', { text: big ? '' : title }), end), body);
      return { el: page, body: body };
    }

    function buildRoot(s) {
      sections = [];
      var srcRows = [
        navRow('Default Source', BADGE.blue, 'play', function (st) { return (st.sources.find(function (x) { return x.id === st.defaultId; }) || { name: '' }).name; }, openDefault),
        toggleRow('Ask on Every Launch', BADGE.orange, 'replay', function (st) { return st.askEveryLaunch; }, function (on) { act('set-ask', { on: on }); }),
      ];
      sections.push(section('Source', h('div.foot', { text: 'The source that opens when you launch the app.' }), srcRows));
      if (s.adblock.configured) {
        sections.push(section('Protection', null, [
          toggleRow('Block Ads & Trackers', BADGE.green, 'shield', function (st) { return st.adblock.on; }, function (on) { act('set-adblock', { on: on }); }),
          valueRow('Ads Blocked', BADGE.red, 'block', function (st) { return String(st.adblock.ads); }),
          valueRow('Redirects Stopped', BADGE.purple, 'replay', function (st) { return String(st.adblock.redirects); }),
        ]));
      }
      sourcesHolder = h('div');
      sections.push({ el: sourcesHolder, update: function (st) { updateSources(st); } });
      sections.push(section('Storage', null, [
        valueRow('Used', BADGE.grey, 'storage', function (st) { return UI.formatBytes(st.storageBytes); }),
        actionRow('Clear Cache', 'blue', function () {
          confirmAlert('Clear Cache?', 'Temporary files are removed. You stay signed in.', 'Clear', false, function () { act('clear-cache'); });
        }),
        actionRow('Sign Out of All Sites', 'red', function () {
          confirmAlert('Sign Out of All Sites?', 'This signs you out of every site and deletes all saved site data. You will have to log in again.', 'Sign Out', true,
            function () { act('sign-out'); });
        }),
      ]));
      var about = h('div.about', null, s.icon ? h('img', { src: s.icon, alt: '' }) : null, h('div.n', { text: s.app.name }),
        h('div.t', { text: s.tagline }), h('div.c', { text: s.credit ? 'by ' + s.credit : '' }), h('div.v'));
      sections.push({ el: h('div.section', null, h('div.head', { text: 'About' }), h('div.group', null, about)),
        update: function (st) {
          about.querySelector('.v').textContent = 'v' + st.app.version + ' (build ' + st.app.build + ')' + (st.app.cfg ? ' · cfg ' + st.app.cfg : '');
        } });
      var p = pageEl('Settings', '', 'Done', null, function () { act('close-settings'); }, sections.map(function (x) { return x.el; }), 'Settings');
      rootBody = p.body;
      return p.el;
    }

    function updateSources(s) {
      var sig = s.sources.map(function (x) { return x.id + x.name; }).join('|');
      var rows = s.sources.map(function (x) {
        var dot = h('span.dot.plain.h-' + x.health);
        var txt = h('span.value', { text: '' });
        var el = h('div.row', null, UI.tile(x, 29, false), h('span.label', { text: x.name }), dot, txt);
        return { el: el, update: function (st) {
          var cur = st.sources.find(function (y) { return y.id === x.id; }) || x;
          dot.className = 'dot plain h-' + cur.health;
          txt.textContent = st.checking ? 'Checking…' : UI.HEALTH[cur.health];
        } };
      });
      if (sig !== sourcesSig) {
        sourcesSig = sig;
        var check = h('button', { text: 'Check Now', onclick: function () { act('check-sources'); } });
        var sec = section('Sources', h('div.foot', null, check), rows);
        sourcesHolder.textContent = '';
        sourcesHolder.appendChild(sec.el);
        sourcesHolder.rows = rows;
      }
      sourcesHolder.rows.forEach(function (r) { r.update(s); });
    }

    function buildDefault(s) {
      var rows = s.sources.map(function (x) {
        var mark = h('span.check');
        var el = h('button.row', { onclick: function () { act('set-default', { id: x.id }); } }, UI.tile(x, 29, false), h('span.label', { text: x.name }), mark);
        return { el: el, update: function (st) { mark.textContent = st.defaultId === x.id ? '✓' : ''; } };
      });
      var sec = section(null, h('div.foot', { text: 'This source opens automatically. You can still switch from the Island at any time.' }), rows);
      var p = pageEl('Default Source', 'Settings', '', popPage, null, [sec.el]);
      p.rows = sec;
      return p;
    }

    function openDefault() {
      if (stack.length > 1) return;
      defaultPage = buildDefault(last);
      defaultPage.rows.update(last);
      pagesEl.appendChild(defaultPage.el);
      stack.push(defaultPage);
      play(defaultPage.el, [{ transform: 'translateX(100%)' }, { transform: 'translateX(0)' }], T.panel);
      play(stack[0].el, [{ transform: 'translateX(0)' }, { transform: 'translateX(-28%)' }], T.panel);
    }

    function popPage() {
      if (stack.length < 2) return;
      var top = stack.pop();
      play(top.el, [{ transform: 'translateX(0)' }, { transform: 'translateX(100%)' }], T.panel).then(function () { top.el.remove(); });
      play(stack[0].el, [{ transform: 'translateX(-28%)' }, { transform: 'translateX(0)' }], T.panel);
    }

    function confirmAlert(title, msg, okLabel, destructive, onOk) {
      var scrim = h('div.alert-scrim');
      var close = function () {
        play(scrim, [{ opacity: 1 }, { opacity: 0 }], T.soft, { duration: 140 }).then(function () { scrim.remove(); });
      };
      var box = h('div.alert', { role: 'alertdialog' }, h('div.t', { text: title }), h('div.m', { text: msg }),
        h('div.btns', null, h('button', { text: 'Cancel', onclick: close }),
          h('button' + (destructive ? '.red' : ''), { text: okLabel, onclick: function () { close(); onOk(); } })));
      scrim.appendChild(box);
      card.appendChild(scrim);
      play(scrim, [{ opacity: 0 }, { opacity: 1 }], T.soft, { duration: 160 });
      play(box, [{ transform: 'scale(1.1)' }, { transform: 'scale(1)' }], T.soft);
    }

    return {
      /** Builds (once) and springs the card in. */
      open: function (s) {
        last = s;
        closing = false;
        pagesEl.textContent = '';
        stack = [];
        sourcesSig = '';
        var rootPage = { el: buildRoot(s) };
        pagesEl.appendChild(rootPage.el);
        stack.push(rootPage);
        sections.forEach(function (sec) { sec.update(s); });
        var sheet = window.innerWidth < 640;
        card.classList.toggle('sheet', sheet);
        var off = sheet ? 'translateY(110%)' : 'translateX(110%)';
        var on = sheet ? 'translateY(0)' : 'translateX(0)';
        card.style.transform = off;
        play(card, [{ transform: off }, { transform: on }], T.panel);
        rootBody.scrollTop = 0;
      },
      update: function (s) {
        last = s;
        if (closing) return;
        sections.forEach(function (sec) { sec.update(s); });
        if (defaultPage && stack.length > 1) defaultPage.rows.update(s);
      },
      /** Springs the card out; resolves when gone. */
      close: function () {
        closing = true;
        var sheet = card.classList.contains('sheet');
        var off = sheet ? 'translateY(110%)' : 'translateX(110%)';
        var on = sheet ? 'translateY(0)' : 'translateX(0)';
        return play(card, [{ transform: on }, { transform: off }], T.collapse, { duration: 260 });
      },
      back: function () {
        if (stack.length > 1) { popPage(); return true; }
        return false;
      },
      toast: function (text) {
        toast.textContent = text;
        toast.animate([{ opacity: 0, transform: 'translate(-50%, 20px)' }, { opacity: 1, transform: 'translate(-50%, 0)', offset: 0.12 },
          { opacity: 1, transform: 'translate(-50%, 0)', offset: 0.85 }, { opacity: 0, transform: 'translate(-50%, 0)' }], { duration: 2000 });
      },
    };
  }

  root.Settings = { create: create };
})(window);
