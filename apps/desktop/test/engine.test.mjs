import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { ensureNodeBinding } from '../../../scripts/desktop/native.mjs';
import { ROOT } from '../../../scripts/paths.mjs';

const require = createRequire(import.meta.url);
const { Engine } = require(ensureNodeBinding());

const NOOP = Buffer.from('// noop').toString('base64');
const RESOURCES = JSON.stringify([{ name: 'noop.js', aliases: [], kind: { mime: 'application/javascript' }, content: NOOP }]);
const RULES = [
  '||ads.example.com^',
  '@@||ads.example.com/allowed^',
  '||tracker.net^$script',
  '||redir.example.net/a.js$script,redirect=noop.js',
  '##.ad-banner',
  'example.org##.sponsored',
].join('\n');

const engine = Engine.fromRules(RULES, RESOURCES);
const page = 'https://site.test/';

test('blocks a matching request', () => {
  const r = engine.check('https://ads.example.com/banner.png', page, 'image');
  assert.equal(r.matched, true);
  assert.equal(r.exception, false);
});

test('allows unrelated requests and exception rules', () => {
  assert.equal(engine.check('https://cdn.site.test/app.js', page, 'script').matched, false);
  const ex = engine.check('https://ads.example.com/allowed/x.png', page, 'image');
  assert.equal(ex.matched, false);
  assert.equal(ex.exception, true);
});

test('respects the request type', () => {
  assert.equal(engine.check('https://tracker.net/t.js', page, 'script').matched, true);
  assert.equal(engine.check('https://tracker.net/t.png', page, 'image').matched, false);
});

test('redirect rules return a data: URL', () => {
  const r = engine.check('https://redir.example.net/a.js', page, 'script');
  assert.equal(r.matched, true);
  assert.match(r.redirect, /^data:application\/javascript;base64,/);
});

test('invalid URLs are not matched and never throw', () => {
  assert.equal(engine.check('not a url', page, 'script').matched, false);
});

test('cosmetic resources and generic selectors', () => {
  const res = JSON.parse(engine.urlCosmeticResources('https://example.org/page'));
  assert.ok(res.hide_selectors.includes('.sponsored'));
  const generic = JSON.parse(engine.hiddenClassIdSelectors(['ad-banner', 'plain'], [], res.exceptions));
  assert.deepEqual(generic, ['.ad-banner']);
});

test('serialize round-trips through fromSerialized', () => {
  const copy = Engine.fromSerialized(engine.serialize(), RESOURCES);
  assert.equal(copy.check('https://ads.example.com/banner.png', page, 'image').matched, true);
  assert.equal(copy.check('https://cdn.site.test/app.js', page, 'script').matched, false);
});

test('corrupt engine data is rejected', () => {
  assert.throws(() => Engine.fromSerialized(Buffer.from('definitely not an engine'), RESOURCES));
});

test('built engine.dat (when a build has produced one) blocks well known ad hosts', (t) => {
  const dir = path.join(ROOT, 'apps', 'desktop', 'gen');
  if (!fs.existsSync(path.join(dir, 'engine.dat'))) return t.skip('no gen/engine.dat yet');
  const e = Engine.fromSerialized(fs.readFileSync(path.join(dir, 'engine.dat')), fs.readFileSync(path.join(dir, 'resources.json'), 'utf8'));
  assert.equal(e.check('https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js', page, 'script').matched, true);
  assert.equal(e.check('https://site.test/app.js', page, 'script').matched, false);
});
