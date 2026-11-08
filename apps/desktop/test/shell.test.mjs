import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { buildAppConfig, obfuscateHosts, remoteCredentials } from '../../../scripts/desktop/generate.mjs';
import { deobfuscate } from '../../../scripts/android/defaults.mjs';

const require = createRequire(import.meta.url);
const { createObfuscator } = require('../src/core/obfuscator.js');
const live = require('../src/core/live-config.js');
const { buildRequest, extractParameter, RemotePoller } = require('../src/core/remote-rest.js');
const { resolverOptions, SERVERS } = require('../src/core/doh.js');
const { buildUiState, monogram } = require('../src/core/ui-state.js');
const { IslandVisibility } = require('../src/core/island-visibility.js');
const { spring } = require('../src/ui/spring.js');

const KEY = Buffer.from('fedcba9876543210');
const ob = createObfuscator(KEY.toString('base64'));

const cfg = {
  name: 'demo', appName: 'Demo', appId: 'com.example.demo', version: '1.2.3', versionCode: 5, tagline: 'Tag', credit: 'Me',
  island: { visibility: 'home' }, externalLinks: 'block', userAgent: null, backgroundColor: '#000000', target: 'desktop',
  authHosts: ['sso.example.org'], dohHosts: ['api.source-1.example'], recommendedSource: 'a',
  adblock: { enabled: true, cosmetic: true, blockPopups: true },
  sources: [
    { id: 'a', name: 'Alpha', url: 'https://source-1.example/', allowedHosts: ['source-1.example', '*.source-1.example'], accent: '#3B82F6', enabled: true },
    { id: 'b', name: 'Beta', url: 'https://source-2.example/', allowedHosts: ['source-2.example'], accent: '#E50914', enabled: true },
  ],
};

test('generated app config: no plain URL or host, decodes back to the same live config', () => {
  const app = buildAppConfig(cfg, { adblockOn: true, buildNumber: 9, key: KEY, remote: null });
  const text = JSON.stringify(app);
  assert.ok(!/source-\d\.example/.test(text), 'no host or URL in the output');
  assert.ok(!text.includes('https://'));
  assert.equal(app.build, 9);
  const c = live.merge(null, app.defaults, ob.decode);
  assert.equal(c.sources[0].url, 'https://source-1.example/');
  assert.deepEqual(c.sources[0].allowedHosts, ['source-1.example', '*.source-1.example']);
  assert.deepEqual(c.dohHosts, ['api.source-1.example']);
  assert.deepEqual(c.authHosts, ['sso.example.org']);
  assert.equal(c.recommendedSource, 'a');
  assert.equal(c.tagline, 'Tag');
});

test('obfuscateHosts matches the shared android obfuscation scheme', () => {
  const out = obfuscateHosts({ sources: [{ id: 'a', allowedHosts: ['x.example'] }], dohHosts: ['d.example'] }, KEY, []);
  assert.equal(deobfuscate(out.sources[0].allowedHostsEnc[0], KEY), 'x.example');
  assert.equal(deobfuscate(out.dohHostsEnc[0], KEY), 'd.example');
  assert.equal(out.sources[0].allowedHosts, undefined);
});

test('remote credentials come from google-services.json for the matching package', async () => {
  const fs = await import('node:fs');
  const os = await import('node:os');
  const path = await import('node:path');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'movy-gs-'));
  fs.writeFileSync(path.join(dir, 'gs.json'), JSON.stringify({
    project_info: { project_number: '123' },
    client: [
      { client_info: { mobilesdk_app_id: '1:123:android:aaa', android_client_info: { package_name: 'other' } }, api_key: [{ current_key: 'KEY-A' }] },
      { client_info: { mobilesdk_app_id: '1:123:android:bbb', android_client_info: { package_name: 'com.example.demo' } }, api_key: [{ current_key: 'KEY-B' }] },
    ],
  }));
  const c = remoteCredentials({ ...cfg, remote: { googleServices: 'gs.json', parameter: 'p' } }, dir);
  assert.deepEqual(c, { projectNumber: '123', apiKey: 'KEY-B', appId: '1:123:android:bbb', parameter: 'p' });
  assert.equal(remoteCredentials({ ...cfg }, dir), null);
  assert.equal(remoteCredentials({ ...cfg, remote: { googleServices: 'missing.json' } }, dir), null);
  fs.rmSync(dir, { recursive: true, force: true });
});

test('remote REST: request shape and parameter extraction', () => {
  const r = buildRequest({ projectNumber: '123', apiKey: 'K', appId: 'APP' }, 'inst');
  assert.equal(r.url, 'https://firebaseremoteconfig.googleapis.com/v1/projects/123/namespaces/firebase:fetch');
  assert.equal(r.headers['x-goog-api-key'], 'K');
  assert.deepEqual(JSON.parse(r.body), { appId: 'APP', appInstanceId: 'inst' });
  assert.equal(extractParameter({ entries: { movy_config: '{"a":1}' } }, 'movy_config'), '{"a":1}');
  assert.equal(extractParameter({ state: 'NO_CHANGE' }, 'movy_config'), null);
  assert.equal(extractParameter({ entries: { movy_config: '' } }, 'movy_config'), null);
});

test('remote poller applies only changed, successful responses and never throws', async () => {
  const applied = [];
  const logs = [];
  const replies = [
    { status: 200, body: JSON.stringify({ entries: { p: '{"v":1}' } }) },
    { status: 200, body: JSON.stringify({ entries: { p: '{"v":1}' } }) },
    { status: 500, body: '' },
    { status: 200, body: '{not json' },
    { status: 200, body: JSON.stringify({ entries: { p: '{"v":2}' } }) },
  ];
  let i = 0;
  const poller = new RemotePoller({
    creds: { projectNumber: '1', apiKey: 'k', appId: 'a' }, parameter: 'p', instanceId: 'i', log: (m) => logs.push(m),
    post: async () => replies[i++], apply: (j) => applied.push(j),
  });
  const results = [];
  for (let n = 0; n < 5; n++) results.push(await poller.tick());
  assert.deepEqual(results, [true, false, false, false, true]);
  assert.deepEqual(applied, ['{"v":1}', '{"v":2}']);
  const failing = new RemotePoller({ creds: {}, parameter: 'p', instanceId: 'i', log: () => {}, post: async () => { throw new Error('net'); }, apply: () => {} });
  assert.equal(await failing.tick(), false);
});

test('doh: only configured when hosts need it; cloudflare first, google fallback, automatic mode', () => {
  assert.equal(resolverOptions([]), null);
  assert.equal(resolverOptions(undefined), null);
  const o = resolverOptions(['api.source-1.example']);
  assert.equal(o.secureDnsMode, 'automatic');
  assert.match(o.secureDnsServers[0], /cloudflare/);
  assert.match(o.secureDnsServers[1], /dns\.google/);
  assert.deepEqual(o.secureDnsServers, SERVERS);
});

function repoStub(c, activeId = 'a') {
  return {
    config: () => c,
    active: () => live.find(c, activeId),
    enabledSources: () => live.enabledSources(c),
    defaultSource: () => live.recommended(c),
    preselected: () => live.recommended(c),
    lastUrl: (s) => s.url,
  };
}

test('ui state: names and accents only, never a URL or host', () => {
  const c = live.merge(null, { sources: [{ id: 'a', name: 'Alpha', url: 'https://source-1.example/', accent: '#3B82F6' }], tagline: 't', credit: 'me' }, ob.decode);
  const stats = { adsBlocked: () => 7, redirectsStopped: () => 2 };
  const prefs = { get: (k) => ({ askEveryLaunch: false, adblockEnabled: true, islandY: 0.5 })[k] };
  const s = buildUiState({ app: { appName: 'Demo', version: '1', build: 3, remote: true }, repo: repoStub(c), prefs, healthOf: () => 'up', stats, panel: null,
    island: { shown: true }, storageBytes: 10, checking: false, adblockConfigured: true });
  assert.ok(!/source-1/.test(JSON.stringify(s)));
  assert.equal(s.sources[0].mono, 'A');
  assert.equal(s.sources[0].health, 'up');
  assert.deepEqual([s.adblock.ads, s.adblock.redirects], [7, 2]);
  assert.equal(s.app.cfg, 0, 'cfg suffix only when a remote version exists');
  assert.equal(monogram('  moon'), 'M');
});

function visFixture(visibility = 'home') {
  const c = live.merge(null, {
    sources: [
      { id: 'a', name: 'A', url: 'https://source-1.example/', allowedHosts: ['source-1.example'] },
      { id: 'b', name: 'B', url: 'https://source-2.example/', allowedHosts: ['source-2.example'] },
    ],
    island: { visibility },
  }, ob.decode);
  let active = 'a';
  const repo = { ...repoStub(c), active: () => live.find(c, active), lastUrl: (s) => s.url };
  let now = 1000;
  const events = [];
  const v = new IslandVisibility({ repo, now: () => now, onChange: (s) => events.push(s) });
  return { v, events, setActive: (id) => { active = id; v.onActiveSourceChanged(live.find(c, id)); }, tick: (ms) => { now += ms; } };
}

test('island visibility: shown only on home pages, hidden deeper, back again', () => {
  const { v, events } = visFixture();
  v.onUrl('https://source-1.example/');
  v.onUrl('https://source-1.example/movie/1');
  v.onUrl('https://source-1.example/?x=1');
  assert.deepEqual(events, [true, false, true]);
});

test('island visibility: fullscreen, settings/chooser and forced (Ctrl+K) overrides', () => {
  const { v, events } = visFixture();
  v.onUrl('https://source-1.example/');
  v.setFullscreen(true);
  v.setFullscreen(false);
  v.setBlocked(true);
  v.setBlocked(false);
  assert.deepEqual(events, [true, false, true, false, true]);
  v.onUrl('https://source-1.example/watch/9');
  assert.equal(v.shown, false);
  v.toggleForced();
  assert.equal(v.shown, true);
  v.clearForced();
  assert.equal(v.shown, false);
  v.setError(true);
  assert.equal(v.shown, true, 'the error panel always shows the island');
});

test('island visibility: "always" mode shows it on deep pages too', () => {
  const { v } = visFixture('always');
  v.onUrl('https://source-1.example/watch/9');
  assert.equal(v.shown, true);
});

test('island visibility: learns the landing page of a redirecting start page, per source', () => {
  const { v, setActive, tick } = visFixture();
  v.onActiveSourceChanged({ id: 'a', url: 'https://source-1.example/' });
  v.onUrl('https://source-1.example/'); // home by rule
  v.onUrl('https://source-1.example/landing'); // redirect within the window: learned
  assert.equal(v.shown, true);
  tick(4000); // past the learning window: later route changes no longer redefine the landing page
  v.onUrl('https://source-1.example/movie/2');
  assert.equal(v.shown, false);
  v.onUrl('https://source-1.example/landing');
  assert.equal(v.shown, true, 'the learned landing page counts as home');
  setActive('b');
  v.onUrl('https://source-1.example/landing');
  assert.equal(v.landing, null, 'landing is per source');
  setActive('a');
  assert.equal(v.landing, 'source-1.example/landing', 'restored when switching back');
});

test('spring easing: overshoots when under-damped, settles at 1, monotonic when over-damped', () => {
  const s = spring(380, 0.78);
  assert.ok(Math.max(...s.samples) > 1.0, 'overshoot');
  assert.equal(s.samples.at(-1), 1);
  assert.ok(s.duration > 100 && s.duration < 1600);
  assert.match(s.css, /^linear\(0, /);
  const o = spring(500, 1.2);
  assert.ok(Math.max(...o.samples) <= 1.0001);
});
