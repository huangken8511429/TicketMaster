import { useEffect, useRef, useState } from 'react';
import { API_BASE_URL } from '@/api/client';
import type { BookingResponse } from '@/api/types';

export type BookingPollState = 'polling' | 'success' | 'failed';

export type UseBookingPollResult = {
  data: BookingResponse | null;
  state: BookingPollState;
  elapsedSec: number;
  retryCount: number;
  error: string | null;
};

const CLIENT_TIMEOUT_MS = 11_000; // server long-poll is 10s; +1s headroom
const MAX_BACKOFF_RETRIES = 3;
const HARD_DEADLINE_MS = 60_000;
const BACKOFF_SCHEDULE = [1_000, 2_000, 4_000];

/**
 * Long-polls `GET /api/bookings/{id}` per api-contract.md §3.2.
 *
 * Semantics:
 * - 200 + BOOKED → state = 'success', stop.
 * - 200 + REJECTED → state = 'failed', stop.
 * - 202 → immediately re-poll (no sleep) — server timed out without result.
 * - 5xx / network → exponential backoff (1s, 2s, 4s); after 3 fails → state = 'failed'.
 * - Total elapsed > 60s → state = 'failed'.
 *
 * Cleanup: cancels in-flight fetch via AbortController on unmount.
 */
export function useBookingPoll(bookingId: string | null | undefined): UseBookingPollResult {
  const [data, setData] = useState<BookingResponse | null>(null);
  const [state, setState] = useState<BookingPollState>('polling');
  const [elapsedSec, setElapsedSec] = useState(0);
  const [retryCount, setRetryCount] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const cancelledRef = useRef(false);

  useEffect(() => {
    if (!bookingId) return;

    cancelledRef.current = false;
    setData(null);
    setState('polling');
    setElapsedSec(0);
    setRetryCount(0);
    setError(null);

    const startedAt = Date.now();
    let backoffFailures = 0;
    let abortController: AbortController | null = null;

    const elapsedTimer = window.setInterval(() => {
      if (cancelledRef.current) return;
      setElapsedSec(Math.floor((Date.now() - startedAt) / 1000));
    }, 1000);

    const sleep = (ms: number) =>
      new Promise<void>((resolve) => {
        const t = window.setTimeout(resolve, ms);
        // Best-effort cancel on unmount — we always resolve, just early.
        if (cancelledRef.current) {
          window.clearTimeout(t);
          resolve();
        }
      });

    const fail = (reason: string) => {
      if (cancelledRef.current) return;
      setError(reason);
      setState('failed');
    };

    const loop = async () => {
      while (!cancelledRef.current) {
        if (Date.now() - startedAt >= HARD_DEADLINE_MS) {
          fail('Booking timed out after 60s');
          return;
        }

        abortController = new AbortController();
        const clientTimeoutId = window.setTimeout(
          () => abortController?.abort(),
          CLIENT_TIMEOUT_MS,
        );

        try {
          const res = await fetch(`${API_BASE_URL}/api/bookings/${bookingId}`, {
            signal: abortController.signal,
            headers: { Accept: 'application/json' },
          });
          window.clearTimeout(clientTimeoutId);

          if (cancelledRef.current) return;

          if (res.status === 202) {
            // Server long-poll timed out — re-issue immediately.
            backoffFailures = 0;
            continue;
          }

          if (res.status === 200) {
            const json = (await res.json()) as BookingResponse;
            if (cancelledRef.current) return;
            setData(json);
            setState(json.status === 'BOOKED' ? 'success' : 'failed');
            return;
          }

          if (res.status >= 500) {
            backoffFailures += 1;
            setRetryCount(backoffFailures);
            if (backoffFailures > MAX_BACKOFF_RETRIES) {
              fail(`Server error: HTTP ${res.status}`);
              return;
            }
            await sleep(BACKOFF_SCHEDULE[backoffFailures - 1] ?? 4_000);
            continue;
          }

          // 4xx other than 202 — treat as terminal failure.
          const body = await res.text();
          fail(body || `HTTP ${res.status}`);
          return;
        } catch (err) {
          window.clearTimeout(clientTimeoutId);
          if (cancelledRef.current) return;
          if (err instanceof DOMException && err.name === 'AbortError') {
            // Treat as 202 — server slow / our timeout fired. Re-poll immediately.
            continue;
          }
          backoffFailures += 1;
          setRetryCount(backoffFailures);
          if (backoffFailures > MAX_BACKOFF_RETRIES) {
            fail(err instanceof Error ? err.message : 'Network error');
            return;
          }
          await sleep(BACKOFF_SCHEDULE[backoffFailures - 1] ?? 4_000);
        }
      }
    };

    void loop();

    return () => {
      cancelledRef.current = true;
      abortController?.abort();
      window.clearInterval(elapsedTimer);
    };
  }, [bookingId]);

  return { data, state, elapsedSec, retryCount, error };
}
