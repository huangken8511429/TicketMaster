import { useEffect, useRef, useState } from 'react';

export type CountdownState = {
  /** Milliseconds remaining (>= 0). */
  remainingMs: number;
  /** Convenience fields. */
  days: number;
  hours: number;
  minutes: number;
  seconds: number;
  /** True once target has elapsed. */
  expired: boolean;
};

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

function compute(targetMs: number, nowMs: number): CountdownState {
  const remaining = Math.max(0, targetMs - nowMs);
  return {
    remainingMs: remaining,
    days: Math.floor(remaining / DAY),
    hours: Math.floor((remaining % DAY) / HOUR),
    minutes: Math.floor((remaining % HOUR) / MINUTE),
    seconds: Math.floor((remaining % MINUTE) / SECOND),
    expired: remaining === 0,
  };
}

/**
 * Generic 1Hz countdown driven by requestAnimationFrame (truncated to seconds).
 *
 * @param target Unix ms timestamp to count down toward, or null to disable.
 * @param onExpired fires once when remaining transitions to 0.
 */
export function useCountdown(target: number | null | undefined, onExpired?: () => void): CountdownState {
  const [state, setState] = useState<CountdownState>(() =>
    target == null ? compute(0, 0) : compute(target, Date.now()),
  );
  const firedRef = useRef(false);
  const onExpiredRef = useRef(onExpired);
  onExpiredRef.current = onExpired;

  useEffect(() => {
    if (target == null) return;
    firedRef.current = false;
    let raf = 0;
    let lastTick = -1;

    const tick = () => {
      const now = Date.now();
      const secondBucket = Math.floor(now / 1000);
      if (secondBucket !== lastTick) {
        lastTick = secondBucket;
        const next = compute(target, now);
        setState(next);
        if (next.expired && !firedRef.current) {
          firedRef.current = true;
          onExpiredRef.current?.();
        }
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [target]);

  return state;
}
