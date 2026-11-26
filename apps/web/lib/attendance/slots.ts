export const SLOTS = ["DAY", "AM", "PM", "P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8"] as const;
export type Slot = (typeof SLOTS)[number];

export const isKnownSlot = (value: string): value is Slot => (SLOTS as readonly string[]).includes(value);
