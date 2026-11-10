import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const { chooseField, micBox, pillRect, firstResult, isOpenKey, caretFor } = createRequire(import.meta.url)('../tvsearchmic.js');

const field = (top, left, extra = {}) => ({ w: 400, h: 48, top, left, inView: true, ...extra });

test('chooseField: skips boxes that are not laid out, prefers the ones on screen, then the topmost', () => {
  assert.equal(chooseField([]), -1);
  assert.equal(chooseField([field(10, 0, { w: 0 }), field(10, 0, { h: 0 })]), -1);
  assert.equal(chooseField([field(300, 0), field(40, 0)]), 1);
  assert.equal(chooseField([field(40, 0, { inView: false }), field(600, 0)]), 1);
  assert.equal(chooseField([field(40, 300), field(40, 100)]), 1); // same row: the leftmost
  assert.equal(chooseField([null, field(40, 0)]), 1);
});

test('micBox: right of the field, the same height, centred on it', () => {
  const b = micBox({ l: 100, t: 50, w: 600, h: 56 }, 1280, 14, 12);
  assert.deepEqual(b, { left: 714, top: 50, size: 56, inside: false });
});

test('micBox: the size is clamped and the button stays vertically centred', () => {
  assert.equal(micBox({ l: 0, t: 0, w: 300, h: 20 }, 1280, 14, 12).size, 36);
  assert.equal(micBox({ l: 0, t: 0, w: 300, h: 200 }, 1280, 14, 12).size, 72);
  assert.equal(micBox({ l: 0, t: 100, w: 300, h: 20 }, 1280, 14, 12).top, 92); // 100 + (20 - 36) / 2
});

test('micBox: forceInside puts it inside even when there is room', () => {
  const b = micBox({ l: 100, t: 50, w: 600, h: 56 }, 1280, 14, 12, true);
  assert.equal(b.inside, true);
  assert.equal(b.left, 100 + 600 - 56 - 6);
});

test('micBox: with no room on the right it sits inside the field\'s right end', () => {
  const b = micBox({ l: 40, t: 10, w: 1200, h: 48 }, 1280, 14, 12);
  assert.equal(b.inside, true);
  assert.equal(b.left, 40 + 1200 - 48 - 6);
});

test('pillRect: grows to the pill drawn around a narrower input, stops at anything bigger', () => {
  const input = { l: 140, t: 20, w: 900, h: 60 };
  const pill = { l: 100, t: 20, w: 1000, h: 60 };
  const row = { l: 0, t: 0, w: 1270, h: 200 }; // the whole row: too tall and too wide
  assert.deepEqual(pillRect(input, [pill, row], 1280), pill);
  assert.deepEqual(pillRect(input, [row], 1280), input);
  assert.deepEqual(pillRect(input, [], 1280), input);
  const narrow = { l: 619, t: 23, w: 270, h: 20 };
  assert.deepEqual(pillRect(narrow, [{ l: 619, t: 23, w: 340, h: 34 }, { l: 619, t: 20, w: 597, h: 40 }], 1280), { l: 619, t: 23, w: 340, h: 34 }); // the pill, not the header cluster
  assert.deepEqual(pillRect(input, [{ l: 200, t: 20, w: 400, h: 60 }, pill], 1280), input); // does not hold the input: stop
});

test('firstResult: the topmost, then leftmost, below the field and on screen', () => {
  const f = { b: 100 };
  const r = (t, l, extra = {}) => ({ t, l, w: 150, h: 220, ...extra });
  assert.equal(firstResult([], f, 720), -1);
  assert.equal(firstResult([r(40, 0)], f, 720), -1); // above the field
  assert.equal(firstResult([r(400, 0), r(160, 300), r(162, 20)], f, 720), 2); // same row within 6 px: leftmost
  assert.equal(firstResult([r(800, 0)], f, 720), -1); // below the screen
  assert.equal(firstResult([r(160, 0, { w: 0 }), r(300, 10)], f, 720), 1);
});

test('isOpenKey: a single real OK press only', () => {
  const k = (o) => ({ type: 'keydown', keyCode: 13, repeat: false, isTrusted: true, ...o });
  assert.equal(isOpenKey(k({})), true);
  assert.equal(isOpenKey(k({ keyCode: 23 })), true);
  assert.equal(isOpenKey(k({ repeat: true })), false);
  assert.equal(isOpenKey(k({ type: 'keyup' })), false);
  assert.equal(isOpenKey(k({ isTrusted: false })), false); // our own synthetic Enter
  assert.equal(isOpenKey(k({ keyCode: 37 })), false);
  assert.equal(isOpenKey(null), false);
});

test('caretFor: LEFT puts the caret at the start, RIGHT at the end, others leave it', () => {
  assert.deepEqual(caretFor('left', 5), [0, 0]);
  assert.deepEqual(caretFor('right', 5), [5, 5]);
  assert.equal(caretFor('up', 5), null);
  assert.equal(caretFor(null, 5), null);
});
