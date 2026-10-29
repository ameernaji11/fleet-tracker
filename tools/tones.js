// Generates the buzzer tones once, for both audiences:
//   www/tones/<type>.wav  - played by the app itself while it is on screen
//   res/raw/tone_<type>.wav (copied by the Build APK workflow) - the sound an
//     Android notification channel plays, which SystemUI renders for us. That is
//     the only path that is guaranteed to be heard when the screen is off, the
//     app is covered by another one, or Android has frozen the WebView.
// The shapes mirror the tones the dashboard previews, so the tone an admin picks
// is the tone the driver hears.
const fs = require('fs');
const path = require('path');
const SR = 16000, DUR = 2.6;

const WAVE = {
  sine: p => Math.sin(p * 2 * Math.PI),
  square: p => (p % 1) < 0.5 ? 1 : -1,
  triangle: p => 4 * Math.abs(((p % 1) - 0.5)) - 1,
  sawtooth: p => 2 * (p % 1) - 1,
};

// note(from, to, type, t0, t1, f2) writes one beep into the sample buffer
function render(segs) {
  const n = Math.round(SR * DUR), out = new Float64Array(n);
  for (const s of segs) {
    const w = WAVE[s.type || 'sine'] || WAVE.sine;
    const quiet = (s.type === 'square' || s.type === 'sawtooth') ? 0.45 : 1;
    for (let i = Math.round(s.at * SR); i < Math.round(s.to * SR) && i < n; i++) {
      const u = (i - s.at * SR) / SR, f = s.f2 ? s.f + (s.f2 - s.f) * (u / (s.to - s.at)) : s.f;
      s.phase = (s.phase || 0) + f / SR;
      out[i] += w(s.phase) * quiet * 0.9;
    }
  }
  return out;
}

function seq(span, add) {
  const segs = [];
  for (let t = 0; t < DUR; t += span) add(t, segs);
  return segs;
}

const TONES = {
  continuous: [{ at: 0, to: DUR, f: 880 }],
  discontinuous: seq(0.5, (t, s) => s.push({ at: t, to: t + 0.25, f: 880 })),
  fast: seq(0.2, (t, s) => s.push({ at: t, to: t + 0.1, f: 1000 })),
  twotone: seq(0.8, (t, s) => { s.push({ at: t, to: t + 0.4, f: 960, type: 'square' }); s.push({ at: t + 0.4, to: t + 0.8, f: 640, type: 'square' }) }),
  siren: seq(1.2, (t, s) => { s.push({ at: t, to: t + 0.6, f: 600, f2: 1200, type: 'sawtooth' }); s.push({ at: t + 0.6, to: t + 1.2, f: 1200, f2: 600, type: 'sawtooth' }) }),
  chime: seq(1.4, (t, s) => { s.push({ at: t, to: t + 0.5, f: 1319, type: 'triangle' }); s.push({ at: t + 0.5, to: t + 1.2, f: 1047, type: 'triangle' }) }),
  pulse: seq(0.8, (t, s) => [600, 800, 1000, 1200].forEach((f, i) => s.push({ at: t + i * 0.16, to: t + i * 0.16 + 0.12, f }))),
};

// a fade at both ends keeps the loop click out of the continuous tones
function wav(samples) {
  const n = samples.length, body = Buffer.alloc(n * 2);
  for (let i = 0; i < n; i++) {
    const edge = Math.min(1, i / (SR * 0.012), (n - i) / (SR * 0.012));
    let v = Math.max(-1, Math.min(1, samples[i])) * edge;
    body.writeInt16LE(Math.round(v * 32000), i * 2);
  }
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + body.length, 4); h.write('WAVE', 8);
  h.write('fmt ', 12); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22);
  h.writeUInt32LE(SR, 24); h.writeUInt32LE(SR * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34);
  h.write('data', 36); h.writeUInt32LE(body.length, 40);
  return Buffer.concat([h, body]);
}

const dir = process.argv[2] || path.join(__dirname, '..', 'www', 'tones');
fs.mkdirSync(dir, { recursive: true });
for (const [name, segs] of Object.entries(TONES)) {
  const b = wav(render(segs));
  fs.writeFileSync(path.join(dir, name + '.wav'), b);
  console.log(name + '.wav', b.length, 'bytes');
}
