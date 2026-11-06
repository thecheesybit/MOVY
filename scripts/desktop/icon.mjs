// Desktop icon: config/assets/<name>/icon.png when present, else the same generated letter icon as Android.
import fs from 'node:fs';
import path from 'node:path';
import { createCanvas } from '@napi-rs/canvas';
import { defaultColor } from '../android/icons.mjs';

const SIZE = 512;
const FONT = 'Segoe UI, Arial, Helvetica, sans-serif';

export function writeDesktopIcon(cfg, rootDir, outFile) {
  const custom = path.join(rootDir, 'config', 'assets', cfg.name, 'icon.png');
  if (fs.existsSync(custom)) {
    fs.copyFileSync(custom, outFile);
    return;
  }
  const c = createCanvas(SIZE, SIZE);
  const ctx = c.getContext('2d');
  ctx.fillStyle = defaultColor(cfg.name);
  ctx.beginPath();
  ctx.roundRect(0, 0, SIZE, SIZE, SIZE * 0.2);
  ctx.fill();
  ctx.fillStyle = '#ffffff';
  ctx.font = `700 ${SIZE * 0.5}px ${FONT}`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(([...cfg.appName.trim()][0] ?? 'M').toUpperCase(), SIZE / 2, SIZE / 2 + SIZE * 0.03);
  fs.writeFileSync(outFile, c.toBuffer('image/png'));
}
