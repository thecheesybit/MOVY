import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const { css, parse } = createRequire(import.meta.url)('../tvsubstyle.js');

test('site default adds no CSS', () => {
  assert.equal(css(0, 0, '.subs'), '');
});

test('size and colour reach ::cue and every site subtitle selector with its children', () => {
  const out = css(3, 2, '.vjs-text-track-display, .subtitle-box');
  assert.match(out, /::cue\{font-size:5\.4vh!important;line-height:1\.25!important;color:#ffd60a!important;background:/);
  assert.match(out, /\.vjs-text-track-display,\.vjs-text-track-display \*,\.subtitle-box,\.subtitle-box \*\{/);
});

test('size only leaves the colour and caption background alone', () => {
  const out = css(1, 0, '');
  assert.equal(out, '::cue{font-size:3.2vh!important;line-height:1.25!important;}');
});

test('parse clamps and rejects junk', () => {
  assert.deepEqual(parse('2,1'), [2, 1]);
  assert.deepEqual(parse('9,9'), [4, 2]);
  assert.deepEqual(parse('x'), [0, 0]);
  assert.deepEqual(parse(null), [0, 0]);
});
