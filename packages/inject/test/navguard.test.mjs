import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const { shouldRemoveOverlay } = createRequire(import.meta.url)('../navguard.js');
const jack = { inFullscreen: false, menuOpen: false, uiState: false, mediaStarted: false, transparent: true, directBodyChild: true, covers: true };
const T = (o) => shouldRemoveOverlay({ ...jack, ...o });

test('an empty transparent body-level cover is a click-jack', () => assert.equal(T({}), true));
test('menu backdrops and playing video are never removed', () => {
  assert.equal(T({ inFullscreen: true }), false);
  assert.equal(T({ menuOpen: true }), false);
  assert.equal(T({ uiState: true }), false);
  assert.equal(T({ mediaStarted: true }), false);
});
test('needs transparent, body-level and covering', () => {
  assert.equal(T({ transparent: false }), false);
  assert.equal(T({ directBodyChild: false }), false);
  assert.equal(T({ covers: false }), false);
});
