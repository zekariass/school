import { useEffect, useState } from 'react';

import { nowMs } from './otp-timer';

/** Re-renders once a second while mounted (countdowns). */
export function useNow(): number {
  const [now, setNow] = useState(nowMs);
  useEffect(() => {
    const timer = setInterval(() => setNow(nowMs()), 1000);
    return () => clearInterval(timer);
  }, []);
  return now;
}
