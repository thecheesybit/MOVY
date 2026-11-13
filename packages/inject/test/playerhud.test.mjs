import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const H = createRequire(import.meta.url)('../playerhud.js');
const main = { connected: true, w: 900, h: 500, vw: 960, vh: 540, sw: 960, sh: 540, duration: 5400, loop: false, muted: false, inFs: false, fsIsVideo: false };
const M = (o) => H.isMainVideo({ ...main, ...o });

test('isMainVideo accepts the film and rejects trailers, small and fullscreen-video cases', () => {
  assert.equal(M({}), true);
  assert.equal(M({ duration: 30 }), false);
  assert.equal(M({ loop: true }), false);
  assert.equal(M({ muted: true }), false);
  assert.equal(M({ muted: true, inFs: true }), true);
  assert.equal(M({ w: 300, h: 200 }), false);
  assert.equal(M({ vw: 360, vh: 200, w: 360, h: 200 }), false); // small inline player in a big screen
  assert.equal(M({ fsIsVideo: true, inFs: true }), false);
  assert.equal(M({ duration: Infinity }), true);
  assert.equal(M({ connected: false }), false);
});

test('fmt', () => {
  assert.equal(H.fmt(7), '0:07');
  assert.equal(H.fmt(3723), '1:02:03');
  assert.equal(H.fmt(Infinity), 'LIVE');
  assert.equal(H.fmt(NaN), '0:00');
});

test('tap and drag classification: a tap never counts as a drag', () => {
  assert.equal(H.isTap(2, 3, 120), true);
  assert.equal(H.isTap(2, 3, 500), false);
  assert.equal(H.isTap(30, 0, 100), false);
  assert.equal(H.dragKind(40, 5), 'scrub');
  assert.equal(H.dragKind(5, 40), 'vertical');
  assert.equal(H.dragKind(5, 5), '');
});

test('zone thirds', () => {
  assert.equal(H.zone(10, 900), 'left');
  assert.equal(H.zone(450, 900), 'mid');
  assert.equal(H.zone(890, 900), 'right');
});

test('scrubTarget clamps and ignores live', () => {
  assert.equal(H.scrubTarget(100, 5400, 100, 1000), 154); // 10% of 5400 = 540 s per width
  assert.equal(H.scrubTarget(10, 5400, -1000, 1000), 0);
  assert.equal(H.scrubTarget(5390, 5400, 1000, 1000), 5400);
  assert.equal(H.scrubTarget(50, Infinity, 100, 1000), 50);
});

test('speed and aspect cycle', () => {
  assert.equal(H.nextRate(1), 1.25);
  assert.equal(H.nextRate(2), 0.5);
  assert.equal(H.nextFit('contain'), 'cover');
  assert.equal(H.nextFit('fill'), 'contain');
});
