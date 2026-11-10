import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const { zoomFor, stable } = createRequire(import.meta.url)('../tvplayerfx.js');

test('zoom keeps the layout width inside a narrower fullscreen window', () => {
  assert.equal(zoomFor(1280, 960), 0.75);
  assert.equal(zoomFor(1280, 1280), 1);
  assert.equal(zoomFor(1280, 1260), 1); // within 2%: no zoom
  assert.equal(zoomFor(0, 960), 1);
  assert.equal(zoomFor(1280, 0), 1);
});

test('stable needs the same non-empty size for the last N frames', () => {
  const r = (w, h) => ({ w, h });
  assert.equal(stable([r(960, 540)], 2), false);
  assert.equal(stable([r(1280, 720), r(960, 540)], 2), false);
  assert.equal(stable([r(1280, 720), r(960, 540), r(960, 540)], 2), true);
  assert.equal(stable([r(0, 0), r(0, 0)], 2), false);
});

test('only the top document zooms its fullscreen element', () => {
  const { mayZoom } = createRequire(import.meta.url)('../tvplayerfx.js');
  assert.equal(mayZoom(false, 0.75), true);
  assert.equal(mayZoom(false, 1), false);
  assert.equal(mayZoom(true, 0.75), false);
});
