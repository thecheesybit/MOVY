import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const { keyFor, findKey, remember } = createRequire(import.meta.url)('../tvfocusmem.js');

test('key: link path wins, host and hash are dropped, then label, then text', () => {
  assert.equal(keyFor({ href: 'https://a.example/movie/12-x#top', label: 'L', text: 'T' }), 'h:/movie/12-x');
  assert.equal(keyFor({ href: '/tv/5?x=1' }), 'h:/tv/5?x=1');
  assert.equal(keyFor({ label: ' Play ' }), 'l:Play');
  assert.equal(keyFor({ text: '  The   Title \n x' }), 't:The Title x');
  assert.equal(keyFor({}), '');
});

test('findKey returns the matching descriptor index', () => {
  const list = [{ href: '/a' }, { href: '/b' }, { label: 'Play' }];
  assert.equal(findKey(list, 'h:/b'), 1);
  assert.equal(findKey(list, 'l:Play'), 2);
  assert.equal(findKey(list, 'h:/zzz'), -1);
  assert.equal(findKey(list, ''), -1);
});

test('remember keeps the newest pages and never mutates the old store', () => {
  let s = {};
  for (let i = 0; i < 14; i++) s = remember(s, '/p' + i, 'h:/c' + i);
  assert.equal(s.__order.length, 12);
  assert.equal(s['/p0'], undefined);
  assert.equal(s['/p13'], 'h:/c13');
  const before = JSON.stringify(s);
  const next = remember(s, '/p5', 'h:/new');
  assert.equal(JSON.stringify(s), before);
  assert.equal(next['/p5'], 'h:/new');
  assert.equal(next.__order[next.__order.length - 1], '/p5');
});
