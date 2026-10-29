// Rasterises the vector launcher icon into the PNGs the Android build needs.
// Default mode fills resources/ for @capacitor/assets; --direct writes res/ itself
// when the generator is unavailable.
const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

const ROOT = path.resolve(__dirname, '..');
const SVG = { icon: 'www/icon.svg', fg: 'icons/icon-foreground.svg', bg: 'icons/icon-background.svg' };
const LEGACY = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
const LAYER = { mdpi: 108, hdpi: 162, xhdpi: 216, xxhdpi: 324, xxxhdpi: 486 };

// sharp wants the channels flat on the colour object; a nested {rgba:{}} is only
// what getPixel() hands back, and passing it in throws "Unable to parse color".
const CLEAR = { r: 0, g: 0, b: 0, alpha: 0 };
const px = (src, size) => sharp(path.join(ROOT, src), { density: 300 })
  .resize(size, size, { fit: 'contain', background: CLEAR })
  .png()
  .toBuffer();

async function write(file, buf) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  await sharp(buf).png().toFile(file);
}

async function forAssets() {
  const r = path.join(ROOT, 'resources');
  await write(path.join(r, 'icon.png'), await px(SVG.icon, 1024));
  await write(path.join(r, 'android', 'icon-foreground.png'), await px(SVG.fg, 1024));
  await write(path.join(r, 'android', 'icon-background.png'), await px(SVG.bg, 1024));
  console.log('wrote resources/ icon layers');
}

async function direct() {
  const res = path.join(ROOT, 'android', 'app', 'src', 'main', 'res');
  if (!fs.existsSync(res)) {
    console.log('no android project yet - skipped');
    return;
  }
  for (const [dpi, size] of Object.entries(LEGACY)) {
    const buf = await px(SVG.icon, size);
    await write(path.join(res, 'mipmap-' + dpi, 'ic_launcher.png'), buf);
    await write(path.join(res, 'mipmap-' + dpi, 'ic_launcher_round.png'), buf);
  }
  for (const [dpi, size] of Object.entries(LAYER)) {
    await write(path.join(res, 'mipmap-' + dpi, 'ic_launcher_foreground.png'), await px(SVG.fg, size));
    await write(path.join(res, 'mipmap-' + dpi, 'ic_launcher_background.png'), await px(SVG.bg, size));
  }
  const v26 = path.join(res, 'mipmap-anydpi-v26');
  if (fs.existsSync(v26)) {
    const xml = '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@mipmap/ic_launcher_background" />\n    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n</adaptive-icon>\n';
    for (const name of ['ic_launcher.xml', 'ic_launcher_round.xml']) {
      fs.writeFileSync(path.join(v26, name), xml);
    }
  }
  console.log('wrote res/ mipmaps directly');
}

(async () => {
  if (process.argv.includes('--direct')) await direct();
  else await forAssets();
})().catch(e => {
  console.error('icon generation failed:', e.message);
  process.exit(1);
});
