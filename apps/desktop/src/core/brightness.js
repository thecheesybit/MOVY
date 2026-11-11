'use strict';
// Pure rules for the fullscreen-video brightness control (no Electron). The value is a CSS brightness multiplier:
// 1 = the video exactly as the site sends it (no filter at all), below 1 dims, above 1 brightens.

const MIN = 0.4;
const MAX = 2;
const STEP = 0.05;
const DEFAULT = 1;

const clamp = (v) => (Number.isFinite(v) ? Math.max(MIN, Math.min(MAX, v)) : DEFAULT);
const round2 = (v) => Math.round(v * 100) / 100;

/** Saved value from prefs -> a usable multiplier (anything odd falls back to 1). */
function normalize(v) {
  return typeof v === 'number' ? round2(clamp(v)) : DEFAULT;
}

/** Next 5% grid line above (dir > 0) or below (dir < 0) the value, so repeated presses stay tidy. */
function step(value, dir) {
  const n = round2(clamp(value) / STEP); // grid position; round2 absorbs float noise (0.95 / 0.05)
  const next = dir > 0 ? Math.floor(n + 1e-6) + 1 : Math.ceil(n - 1e-6) - 1;
  return round2(clamp(next * STEP));
}

const isNeutral = (value) => Math.abs(clamp(value) - 1) < 0.001;

/**
 * CSS applied to the fullscreen element only while the value is not neutral; null means "add nothing".
 * Separate rules: a browser that does not know one pseudo-class would drop a combined selector list.
 */
function css(value) {
  if (isNeutral(value)) return null;
  const rule = `{filter:brightness(${round2(clamp(value))})!important}`;
  return `:fullscreen${rule}\n:-webkit-full-screen${rule}`;
}

const percent = (value) => Math.round(clamp(value) * 100);

module.exports = { MIN, MAX, STEP, DEFAULT, clamp, normalize, step, isNeutral, css, percent };
