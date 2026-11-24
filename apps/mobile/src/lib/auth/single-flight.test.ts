import { describe, expect, it, vi } from 'vitest';

import { singleFlight } from './single-flight';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('singleFlight', () => {
  it('runs once for concurrent callers and shares the result', async () => {
    const gate = deferred<string>();
    const run = vi.fn(() => gate.promise);
    const call = singleFlight(run);

    const results = [call(), call(), call()];
    gate.resolve('token');

    await expect(Promise.all(results)).resolves.toEqual(['token', 'token', 'token']);
    expect(run).toHaveBeenCalledTimes(1);
  });

  it('starts a new run once the previous one settled', async () => {
    let n = 0;
    const call = singleFlight(async () => ++n);
    expect(await call()).toBe(1);
    expect(await call()).toBe(2);
  });

  it('shares a failure and recovers afterwards', async () => {
    const gate = deferred<string>();
    const run = vi.fn().mockReturnValueOnce(gate.promise).mockResolvedValueOnce('ok');
    const call = singleFlight(run);

    const first = call();
    const second = call();
    gate.reject(new Error('boom'));
    await expect(first).rejects.toThrow('boom');
    await expect(second).rejects.toThrow('boom');

    await expect(call()).resolves.toBe('ok');
    expect(run).toHaveBeenCalledTimes(2);
  });

  it('keeps sharing while a slow run is pending (fake timers)', async () => {
    vi.useFakeTimers();
    try {
      const run = vi.fn(() => new Promise<string>((resolve) => setTimeout(() => resolve('late'), 5_000)));
      const call = singleFlight(run);
      const a = call();
      await vi.advanceTimersByTimeAsync(4_000);
      const b = call();
      await vi.advanceTimersByTimeAsync(1_000);
      await expect(Promise.all([a, b])).resolves.toEqual(['late', 'late']);
      expect(run).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });
});
