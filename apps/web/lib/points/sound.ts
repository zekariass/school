/**
 * Original feedback sounds, synthesized with WebAudio: no audio files, nothing copied. A sound is a few short
 * tones; the player is created on the first use (browsers need a user gesture) and shared.
 */

export interface Tone {
  /** Hz */
  frequency: number;
  /** Seconds after the sound started. */
  at: number;
  /** Seconds. */
  duration: number;
  type?: OscillatorType;
  /** 0..1 */
  gain?: number;
}

export type SoundName = "award" | "needsWork" | "undo" | "alarm" | "pick";

/** A rising, happy "pop-pop-ping" for an award. */
const AWARD: Tone[] = [
  { frequency: 523.25, at: 0, duration: 0.12, type: "triangle", gain: 0.5 },
  { frequency: 659.25, at: 0.09, duration: 0.12, type: "triangle", gain: 0.5 },
  { frequency: 987.77, at: 0.18, duration: 0.28, type: "sine", gain: 0.45 },
];
/** A soft, low two-note "hmm", never harsh. */
const NEEDS_WORK: Tone[] = [
  { frequency: 329.63, at: 0, duration: 0.16, type: "sine", gain: 0.4 },
  { frequency: 261.63, at: 0.14, duration: 0.24, type: "sine", gain: 0.4 },
];
const UNDO: Tone[] = [{ frequency: 440, at: 0, duration: 0.1, type: "sine", gain: 0.3 }];
/** The end of a timer: three bright notes, repeated once. */
const ALARM: Tone[] = [0, 0.6].flatMap((offset) => [
  { frequency: 783.99, at: offset, duration: 0.16, type: "triangle" as const, gain: 0.5 },
  { frequency: 783.99, at: offset + 0.2, duration: 0.16, type: "triangle" as const, gain: 0.5 },
  { frequency: 1046.5, at: offset + 0.4, duration: 0.3, type: "triangle" as const, gain: 0.5 },
]);
const PICK: Tone[] = [
  { frequency: 587.33, at: 0, duration: 0.1, type: "square", gain: 0.2 },
  { frequency: 880, at: 0.1, duration: 0.2, type: "triangle", gain: 0.4 },
];

export const SOUNDS: Record<SoundName, readonly Tone[]> = {
  award: AWARD,
  needsWork: NEEDS_WORK,
  undo: UNDO,
  alarm: ALARM,
  pick: PICK,
};

/** The stored mute preference ("1" = muted). Anything else, or nothing stored, means sound is on. */
export const parseMuted = (raw: string | null | undefined): boolean => raw === "1";

export const MUTE_KEY = "school.points.muted";

/** Total length of a sound in seconds. */
export const soundLength = (name: SoundName): number => Math.max(...SOUNDS[name].map((t) => t.at + t.duration));

type AudioCtor = typeof AudioContext;
let context: AudioContext | null = null;

function audio(): AudioContext | null {
  if (typeof window === "undefined") return null;
  const Ctor: AudioCtor | undefined =
    window.AudioContext ?? (window as unknown as { webkitAudioContext?: AudioCtor }).webkitAudioContext;
  if (!Ctor) return null;
  try {
    context ??= new Ctor();
    if (context.state === "suspended") void context.resume();
    return context;
  } catch {
    return null;
  }
}

/** Plays a sound unless muted; never throws (no audio device, autoplay policy). */
export function playSound(name: SoundName, muted: boolean): void {
  if (muted) return;
  const ctx = audio();
  if (!ctx) return;
  try {
    const start = ctx.currentTime + 0.01;
    for (const tone of SOUNDS[name]) {
      const osc = ctx.createOscillator();
      const amp = ctx.createGain();
      const peak = tone.gain ?? 0.4;
      const from = start + tone.at;
      const to = from + tone.duration;
      osc.type = tone.type ?? "sine";
      osc.frequency.setValueAtTime(tone.frequency, from);
      amp.gain.setValueAtTime(0.0001, from);
      amp.gain.exponentialRampToValueAtTime(peak, from + 0.015);
      amp.gain.exponentialRampToValueAtTime(0.0001, to);
      osc.connect(amp).connect(ctx.destination);
      osc.start(from);
      osc.stop(to + 0.02);
    }
  } catch {
    // Sound is a nicety: never let it break an award.
  }
}
