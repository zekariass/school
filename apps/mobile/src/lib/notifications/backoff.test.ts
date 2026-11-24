import { describe, expect, it } from 'vitest';

import { reconnectDelayMs } from './backoff';

describe('reconnectDelayMs', () => {
  it('doubles up to 30 seconds', () => {
    const top = () => 1;
    expect([0, 1, 2, 3, 4, 5, 6, 20].map((a) => reconnectDelayMs(a, top))).toEqual([
      1000, 2000, 4000, 8000, 16000, 30000, 30000, 30000,
    ]);
  });
  it('jitters between half and all of the delay', () => {
    expect(reconnectDelayMs(2, () => 0)).toBe(2000);
    expect(reconnectDelayMs(2, () => 0.5)).toBe(3000);
    expect(reconnectDelayMs(0, () => 0)).toBe(500);
  });
  it('treats a negative attempt as the first', () => {
    expect(reconnectDelayMs(-3, () => 1)).toBe(1000);
  });
});
