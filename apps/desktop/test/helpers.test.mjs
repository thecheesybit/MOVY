import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { electronBuilderConfig } from '../../../scripts/desktop/builder-config.mjs';

const require = createRequire(import.meta.url);
const { toAdblockType } = require('../src/main/request-types.js');
const { chromeUserAgent } = require('../src/main/user-agent.js');
const { createSites } = require('../src/main/sites.js');
const { NavRules } = require('../src/core/nav-rules.js');

test('maps electron resource types to adblock types', () => {
  const expected = {
    mainFrame: 'document', subFrame: 'subdocument', stylesheet: 'stylesheet', script: 'script', image: 'image',
    font: 'font', object: 'object', xhr: 'xmlhttprequest', ping: 'ping', cspReport: 'csp_report', media: 'media',
    webSocket: 'websocket', other: 'other', somethingNew: 'other',
  };
  for (const [k, v] of Object.entries(expected)) assert.equal(toAdblockType(k), v, k);
});

test('user agent loses Electron and app tokens', () => {
  const ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Movy/1.0.0 Chrome/156.0.0.0 Electron/45.2.3 Safari/537.36';
  assert.equal(chromeUserAgent(ua, ['Movy']), 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36');
  assert.equal(chromeUserAgent(ua.replace('Movy', '@movy/desktop'), []).includes('movy'), false);
});

test('sites: start url, wildcard hosts, http(s) only', () => {
  const s = createSites(new NavRules({ startUrl: 'https://movie.example/', allowedHosts: ['movie.example', '*.movie.example'] }));
  assert.equal(s.isAllowed('https://movie.example/a'), true);
  assert.equal(s.isAllowed('https://cdn.movie.example/a'), true);
  assert.equal(s.isAllowed('https://evilmovie.example/'), false);
  assert.equal(s.isAllowed('file:///c:/x'), false);
  assert.equal(s.isAllowedHost('movie.example'), true);
});

test('installer keeps user data and output goes to dist', () => {
  const c = electronBuilderConfig({ name: 'x', appName: 'X', appId: 'com.a.x', version: '2.0.0' }, { outDir: 'out', gen: 'gen' });
  assert.equal(c.nsis.deleteAppDataOnUninstall, false);
  assert.equal(c.appId, 'com.a.x');
  assert.equal(c.directories.output, 'out');
  assert.equal(c.extraMetadata.version, '2.0.0');
});
