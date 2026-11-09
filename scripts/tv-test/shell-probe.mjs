#!/usr/bin/env node
// usage: node shell-probe.mjs  -> for every source's candidate shell routes: status, size, root markers, build id kind, challenge.
// Reads the (git-ignored) app config; prints no host names.
import fs from 'node:fs';

const cfg = JSON.parse(fs.readFileSync('config/apps/movy.json', 'utf8'));
const ROUTES = {
  movy: ['/', '/browse/movie', '/browse/tv', '/browse/anime'],
  cinejoy: ['/', '/movies', '/series'],
  '7movies': ['/', '/movies', '/tv', '/discover'],
  moonflix: ['/', '/browse', '/movies', '/tv-shows'],
};
const UA = cfg.userAgent || 'Mozilla/5.0 (Linux; Android 12; Sony) AppleWebKit/537.36 Chrome/120 Safari/537.36';
const ROOTS = ['id="__next"', 'id="root"', 'id="app"', 'id="svelte"', 'data-sveltekit', '<body'];
for (const s of cfg.sources) {
  const base = new URL(s.url).origin;
  for (const r of ROUTES[s.id] || ['/']) {
    try {
      const res = await fetch(base + r, { redirect: 'manual', headers: { 'user-agent': UA, accept: 'text/html' }, signal: AbortSignal.timeout(15000) });
      const body = await res.text();
      const kinds = [];
      if (/"buildId"\s*:\s*"/.test(body)) kinds.push('next-buildId');
      if (/\/_next\/static\/[A-Za-z0-9_-]{8,}\/_buildManifest/.test(body)) kinds.push('next-path');
      if (/_app\/immutable\/entry\/start\./.test(body)) kinds.push('svelte');
      if (/\/assets\/index-[A-Za-z0-9_-]+\.(m?js|css)/.test(body)) kinds.push('vite');
      const roots = ROOTS.filter((x) => body.toLowerCase().includes(x));
      console.log(s.id.padEnd(9), r.padEnd(15), res.status, (res.headers.get('content-type') || '').slice(0, 24).padEnd(24), String(body.length).padStart(8),
        'etag=' + (res.headers.has('etag') ? 'y' : 'n'), 'lm=' + (res.headers.has('last-modified') ? 'y' : 'n'), 'cookie=' + (res.headers.has('set-cookie') ? 'y' : 'n'),
        'csp=' + (res.headers.has('content-security-policy') ? 'y' : 'n'), 'build=' + (kinds.join('+') || 'none'), 'roots=' + roots.join('|'),
        /cf-chl|just a moment|challenge-platform/i.test(body.slice(0, 20000)) ? 'CHALLENGE' : '');
    } catch (e) {
      console.log(s.id.padEnd(9), r.padEnd(15), 'ERR', e.message.replace(/https?:\/\/\S+/g, '<url>').slice(0, 60));
    }
  }
}
