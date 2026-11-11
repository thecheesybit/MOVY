import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const b = require('../src/core/brightness.js');

test('neutral brightness adds no CSS at all', () => {
  assert.equal(b.css(1), null);
  assert.equal(b.css(1.0004), null);
  assert.equal(b.isNeutral(1), true);
});

test('non-neutral brightness filters only the fullscreen element', () => {
  const css = b.css(0.8);
  assert.match(css, /:fullscreen\{filter:brightness\(0\.8\)!important\}/);
  assert.match(css, /:-webkit-full-screen\{filter:brightness\(0\.8\)!important\}/);
  assert.ok(!/html|body/.test(css));
});

test('values are clamped and junk falls back to 1', () => {
  assert.equal(b.normalize(0.1), b.MIN);
  assert.equal(b.normalize(9), b.MAX);
  assert.equal(b.normalize('x'), 1);
  assert.equal(b.normalize(undefined), 1);
  assert.equal(b.clamp(Number.NaN), 1);
});

test('steps move by 5% on a tidy grid and stop at the ends', () => {
  assert.equal(b.step(1, 1), 1.05);
  assert.equal(b.step(1, -1), 0.95);
  assert.equal(b.step(0.97, -1), 0.95);
  assert.equal(b.step(b.MAX, 1), b.MAX);
  assert.equal(b.step(b.MIN, -1), b.MIN);
  let v = 1;
  for (let i = 0; i < 40; i++) v = b.step(v, -1);
  assert.equal(v, b.MIN);
});

test('percent for the HUD', () => {
  assert.equal(b.percent(1), 100);
  assert.equal(b.percent(0.85), 85);
});

test('the top of the range is 200%', () => {
  assert.equal(b.MAX, 2);
  assert.equal(b.normalize(2), 2);
  assert.equal(b.step(1.95, 1), 2);
  assert.equal(b.step(2, 1), 2);
  assert.match(b.css(2), /brightness\(2\)/);
  assert.equal(b.percent(2), 200);
});
