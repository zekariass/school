import { useEffect, useState } from 'react';

/** The current time as state, refreshed every `intervalMs` (ages, "ends in 3 h", schedule checks): render stays pure. */
export function useClock(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}
