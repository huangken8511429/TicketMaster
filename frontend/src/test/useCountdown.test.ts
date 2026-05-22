import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useCountdown } from '@/hooks/useCountdown';

describe('useCountdown', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('counts seconds down toward target and fires onExpired once', () => {
    const target = Date.now() + 3_000;
    const onExpired = vi.fn();
    const { result } = renderHook(() => useCountdown(target, onExpired));

    expect(result.current.seconds).toBe(3);
    expect(result.current.expired).toBe(false);

    act(() => {
      vi.advanceTimersByTime(4_000);
    });

    expect(result.current.expired).toBe(true);
    expect(onExpired).toHaveBeenCalledTimes(1);

    act(() => {
      vi.advanceTimersByTime(2_000);
    });
    // Should not re-fire after expiry.
    expect(onExpired).toHaveBeenCalledTimes(1);
  });
});
