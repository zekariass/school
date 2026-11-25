#!/usr/bin/env node
/**
 * Generates the app's feedback sounds as small 16-bit mono WAV files, from scratch: sums of sine tones with a
 * quick attack and an exponential decay. The sounds are original (synthesised here, no samples, nothing taken
 * from another product). Run `node apps/mobile/scripts/generate-sounds.mjs`; the outputs in
 * `apps/mobile/assets/sounds/` are committed (a few KB each) so builds do not need this script.
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const SAMPLE_RATE = 22050;
const OUT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'assets', 'sounds');

// Note frequencies (Hz), equal temperament.
const N = { G3: 196.0, A3: 220.0, C5: 523.25, E5: 659.25, G5: 783.99, C6: 1046.5, E6: 1318.5 };

/**
 * A note: a sine plus a soft octave and a faint third partial, so it sounds like a small bell rather than a
 * beep. `decay` is the time constant of the exponential fade in seconds.
 */
function note(buffer, { freq, start, length, gain = 0.5, decay = 0.12 }) {
  const first = Math.floor(start * SAMPLE_RATE);
  const count = Math.floor(length * SAMPLE_RATE);
  const attack = 0.006 * SAMPLE_RATE;
  for (let i = 0; i < count && first + i < buffer.length; i++) {
    const t = i / SAMPLE_RATE;
    const env = Math.min(1, i / attack) * Math.exp(-t / decay) * Math.min(1, (count - i) / (0.02 * SAMPLE_RATE));
    const wave =
      Math.sin(2 * Math.PI * freq * t) + 0.35 * Math.sin(2 * Math.PI * freq * 2 * t) + 0.1 * Math.sin(2 * Math.PI * freq * 3.01 * t);
    buffer[first + i] += gain * env * wave;
  }
}

function render(seconds, notes) {
  const buffer = new Float64Array(Math.ceil(seconds * SAMPLE_RATE));
  for (const spec of notes) note(buffer, spec);
  // Normalise to a comfortable peak, never clip.
  const peak = buffer.reduce((max, v) => Math.max(max, Math.abs(v)), 0) || 1;
  const scale = 0.8 / peak;
  const pcm = Buffer.alloc(buffer.length * 2);
  buffer.forEach((v, i) => pcm.writeInt16LE(Math.round(Math.max(-1, Math.min(1, v * scale)) * 32767), i * 2));
  return pcm;
}

function wav(pcm) {
  const header = Buffer.alloc(44);
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write('WAVE', 8);
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20); // PCM
  header.writeUInt16LE(1, 22); // mono
  header.writeUInt32LE(SAMPLE_RATE, 24);
  header.writeUInt32LE(SAMPLE_RATE * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write('data', 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

const SOUNDS = {
  // A bright rising three-note chime: "well done".
  award: render(0.7, [
    { freq: N.C5, start: 0, length: 0.5, decay: 0.1 },
    { freq: N.E5, start: 0.08, length: 0.5, decay: 0.1 },
    { freq: N.G5, start: 0.16, length: 0.55, decay: 0.16, gain: 0.6 },
  ]),
  // The whole class: a longer arpeggio that ends on a ringing chord.
  'whole-class': render(1.1, [
    { freq: N.C5, start: 0, length: 0.6, decay: 0.12 },
    { freq: N.E5, start: 0.09, length: 0.6, decay: 0.12 },
    { freq: N.G5, start: 0.18, length: 0.7, decay: 0.14 },
    { freq: N.C6, start: 0.27, length: 0.8, decay: 0.2, gain: 0.6 },
    { freq: N.E6, start: 0.27, length: 0.8, decay: 0.2, gain: 0.3 },
  ]),
  // Needs work: one low, soft, short note. Gentle, not a buzzer.
  'needs-work': render(0.45, [
    { freq: N.A3, start: 0, length: 0.4, decay: 0.09, gain: 0.5 },
    { freq: N.G3, start: 0.1, length: 0.35, decay: 0.09, gain: 0.35 },
  ]),
  // Undo: two quick falling notes.
  undo: render(0.35, [
    { freq: N.E5, start: 0, length: 0.2, decay: 0.05 },
    { freq: N.C5, start: 0.09, length: 0.25, decay: 0.07 },
  ]),
  // Timer done: three bell pings.
  'timer-done': render(1.5, [
    { freq: N.G5, start: 0, length: 0.5, decay: 0.2 },
    { freq: N.G5, start: 0.45, length: 0.5, decay: 0.2 },
    { freq: N.C6, start: 0.9, length: 0.6, decay: 0.28, gain: 0.7 },
  ]),
  // The random picker landing on somebody.
  pick: render(0.4, [
    { freq: N.E5, start: 0, length: 0.2, decay: 0.06 },
    { freq: N.G5, start: 0.07, length: 0.3, decay: 0.1 },
  ]),
};

mkdirSync(OUT_DIR, { recursive: true });
for (const [name, pcm] of Object.entries(SOUNDS)) {
  const file = join(OUT_DIR, `${name}.wav`);
  writeFileSync(file, wav(pcm));
  console.log(`${name}.wav  ${(pcm.length + 44) / 1024 | 0} KB`);
}
