import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const { needs } = createRequire(import.meta.url)('../tvgate.js');
const frame = (o) => ({ top: false, video: false, marker: false, w: 0, h: 0, ...o });

test('the top document always gets the TV scripts', () => {
  assert.equal(needs(frame({ top: true })), true);
});

test('empty, tiny and banner frames get nothing', () => {
  assert.equal(needs(frame({})), false);
  assert.equal(needs(frame({ w: 1, h: 1 })), false);
  assert.equal(needs(frame({ w: 300, h: 250 })), false);
  assert.equal(needs(frame({ w: 728, h: 90 })), false);
});

test('a frame with a video or a profile marker is a player', () => {
  assert.equal(needs(frame({ video: true, w: 10, h: 10 })), true);
  assert.equal(needs(frame({ marker: true })), true);
});

test('a player-sized frame (wrapper of the real player) gets the scripts', () => {
  assert.equal(needs(frame({ w: 1280, h: 720 })), true);
  assert.equal(needs(frame({ w: 640, h: 360 })), true);
  assert.equal(needs(frame({ w: 639, h: 720 })), false);
});
