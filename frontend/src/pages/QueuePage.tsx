import { useEffect, useMemo, useRef } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { QueueOverlay } from '@/components/QueueOverlay';
import { useBookingPoll } from '@/hooks/useBookingPoll';
import { useToast } from '@/hooks/useToast';

/**
 * Phase 5 — Queue screen.
 *
 * Wires the long-poll lifecycle into the immersive QueueOverlay:
 *
 * - state === 'polling' (≤ 30s)  → overlay state="queueing"
 * - state === 'polling' (> 30s)  → overlay state="long-wait" (subline tweak)
 * - state === 'success' (BOOKED) → auto-navigate /confirm/:bookingId,
 *     handing the resolved BookingResponse via location.state so
 *     phase-6's ConfirmPage does not have to re-fetch.
 * - state === 'failed' (REJECTED / 5xx exhausted / 60s deadline)
 *     → overlay state="failed" + back-to-event / back-to-list CTAs.
 *
 * Also installs a popstate guard so users who hit the browser back button
 * during polling get a confirm toast (cannot programmatically prevent the
 * navigation in React Router v6 without a Data Router blocker, so we settle
 * for a warning + immediate re-push to keep them on this page during the
 * critical "still allocating" window).
 *
 * The 60s hard deadline + 5xx exponential backoff are owned by
 * `useBookingPoll` — see hooks/useBookingPoll.ts for the contract.
 */
export function QueuePage() {
  const { bookingId } = useParams<{ bookingId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const { push: pushToast } = useToast();

  const { data, state, elapsedSec, error } = useBookingPoll(bookingId);

  // Capture incoming hint (if any) about which event we came from so the
  // "回活動詳情" CTA after failure can route back precisely. EventDetailPage
  // is permitted to omit this — we degrade gracefully to navigate(-1).
  const fromEventId = useMemo(() => {
    const incoming = (location.state as { fromEventId?: number } | null)?.fromEventId;
    return typeof incoming === 'number' ? incoming : null;
  }, [location.state]);

  // Phase 7: also forward `selectedSection` (carrying `basePrice`) through to
  // ConfirmPage so the total-price line item can render without an extra fetch.
  // EventDetailPage is permitted to omit this — ConfirmPage degrades to hiding
  // the total chip if the snapshot is missing.
  const selectedSectionSnapshot = useMemo(() => {
    const incoming = (location.state as {
      selectedSection?: { section: string; basePrice: number | null; seatCount: number };
    } | null)?.selectedSection;
    return incoming ?? null;
  }, [location.state]);

  // ─── Side-effect: success → navigate to confirm page ────────────────────
  useEffect(() => {
    if (state === 'success' && data?.status === 'BOOKED' && bookingId) {
      navigate(`/confirm/${bookingId}`, {
        replace: true,
        state: {
          booking: data,
          selectedSection: selectedSectionSnapshot ?? undefined,
        },
      });
    }
  }, [state, data, bookingId, navigate, selectedSectionSnapshot]);

  // ─── Side-effect: surface a toast on failure for accessibility (aria-live
  // already announces via QueueOverlay text changes, but the toast gives
  // users who navigated away by mistake a clearer recovery hint).
  const failureToastedRef = useRef(false);
  useEffect(() => {
    if (state === 'failed' && !failureToastedRef.current) {
      failureToastedRef.current = true;
      const msg = error ?? '很抱歉，這次沒搶到';
      pushToast(msg, { variant: 'error', timeoutMs: 6000 });
    }
  }, [state, error, pushToast]);

  // ─── Side-effect: warn on browser back / tab close while polling ────────
  useEffect(() => {
    if (state !== 'polling') return;

    // Push a sentinel history entry so the first "back" pops us back into
    // /queue/:id rather than the previous page; we then surface a toast.
    window.history.pushState({ queueGuard: true }, '', window.location.href);

    const onPopState = () => {
      pushToast('離開將取消請求', { variant: 'info', timeoutMs: 4000 });
      window.history.pushState({ queueGuard: true }, '', window.location.href);
    };

    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      // Modern browsers ignore custom strings but still honour preventDefault.
      e.preventDefault();
      e.returnValue = '';
    };

    window.addEventListener('popstate', onPopState);
    window.addEventListener('beforeunload', onBeforeUnload);

    return () => {
      window.removeEventListener('popstate', onPopState);
      window.removeEventListener('beforeunload', onBeforeUnload);
    };
  }, [state, pushToast]);

  const overlayState: 'queueing' | 'long-wait' | 'failed' =
    state === 'failed' ? 'failed' : elapsedSec > 30 ? 'long-wait' : 'queueing';

  return (
    <QueueOverlay
      bookingId={bookingId ?? ''}
      elapsedSec={elapsedSec}
      state={overlayState}
      onRetry={() => {
        // Prefer the precise event we came from; fall back to history.
        if (fromEventId != null) {
          navigate(`/events/${fromEventId}`, { replace: true });
        } else {
          navigate(-1);
        }
      }}
      onBackToList={() => navigate('/', { replace: true })}
    />
  );
}
