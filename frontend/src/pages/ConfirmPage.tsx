import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { Button } from '@/components/Button';
import { HoldCountdown } from '@/components/HoldCountdown';
import { useToast } from '@/hooks/useToast';
import { cn } from '@/lib/cn';
import type { BookingResponse } from '@/api/types';

/**
 * Phase 6 — Hold Confirm screen.
 *
 * Renders the seats that the backend allocated for this booking and runs a
 * pure-frontend 5-minute UX countdown (MVP has no real backend TTL — this is
 * the user-facing reassurance ritual, see specs/frontend-mvp/activity-flow.md §5).
 *
 * Data source:
 *  - Phase 5 (QueuePage) navigates to /confirm/:bookingId with
 *    `location.state.booking: BookingResponse` once the long-poll resolves as
 *    BOOKED. We do NOT re-fetch /api/bookings/:id — the resolved snapshot is
 *    the final, authoritative copy (refetching could race with the demo MSW
 *    layer which clears its booking record after handoff).
 *
 * Direct-access protection:
 *  - If `location.state.booking` is missing (user refreshed, deep-linked, or
 *    bookmarked the URL), we show a brief loading state then toast + redirect
 *    home. This matches the activity-flow.md §5 expired-or-lost-session UX.
 *
 * Lifecycle states:
 *   missing-state  → 1s loading → toast + navigate("/")
 *   active         → countdown ticking, primary CTA enabled
 *   expired        → countdown grey, seats dimmed, CTA → "重新搶票" (→ "/")
 *   confirmed      → demo success toast (MVP terminus — checkout out of scope)
 */

type LifecycleState = 'missing-state' | 'active' | 'expired' | 'confirmed';

/**
 * Optional pricing snapshot carried over from EventDetailPage → QueuePage →
 * ConfirmPage. We do not refetch /api/events/:id/sections from this page —
 * the snapshot is "best effort" and the price line item is hidden when absent.
 */
type SelectedSectionSnapshot = {
  section: string;
  basePrice: number | null;
  seatCount: number;
};

type LocationBookingState = {
  booking?: BookingResponse;
  selectedSection?: SelectedSectionSnapshot;
} | null;

const MISSING_STATE_GRACE_MS = 1_000;

export function ConfirmPage() {
  const { bookingId } = useParams<{ bookingId: string }>();
  const location = useLocation();
  const navigate = useNavigate();
  const { push: pushToast } = useToast();

  const routerState = location.state as LocationBookingState;
  const booking = routerState?.booking ?? null;
  const selectedSection = routerState?.selectedSection ?? null;

  // ─── Lifecycle state machine ──────────────────────────────────────────────
  const [phase, setPhase] = useState<LifecycleState>(
    booking ? 'active' : 'missing-state',
  );

  // Anchor the 5-minute window to booking completion time (createdAt) when
  // available, otherwise to mount-time as a safe fallback. This survives
  // remounts within the same JS tab (e.g. theme switch).
  const startedAt = useMemo(() => {
    if (!booking) return Date.now();
    const parsed = Date.parse(booking.createdAt);
    return Number.isFinite(parsed) ? parsed : Date.now();
  }, [booking]);

  // ─── Side-effect: missing state → toast + redirect after grace period ─────
  const missingToastedRef = useRef(false);
  useEffect(() => {
    if (phase !== 'missing-state' || missingToastedRef.current) return;
    const t = window.setTimeout(() => {
      missingToastedRef.current = true;
      pushToast('無法取得保留資訊，請重新搶票', {
        variant: 'error',
        timeoutMs: 6000,
      });
      navigate('/', { replace: true });
    }, MISSING_STATE_GRACE_MS);
    return () => window.clearTimeout(t);
  }, [phase, navigate, pushToast]);

  // ─── Handlers ─────────────────────────────────────────────────────────────
  const handleExpired = () => {
    // Phase 7 race guard: once the user has pressed "確認保留", we freeze the
    // demo-success UI even if the 5-minute timer subsequently fires. Demo logic
    // (no real TTL); avoids the jarring "你已確認 → 已過期" flip if the user
    // lingers on the success screen past 5 minutes.
    setPhase((current) => (current === 'confirmed' ? current : 'expired'));
  };

  const handleConfirm = () => {
    if (phase !== 'active') return;
    setPhase('confirmed');
    pushToast('結帳流程不在本 MVP — Demo 完成', {
      variant: 'success',
      timeoutMs: 5000,
    });
  };

  const handleRetry = () => {
    navigate('/', { replace: true });
  };

  // ─── Render: missing-state placeholder ────────────────────────────────────
  if (phase === 'missing-state') {
    return (
      <main
        className="min-h-screen bg-ink flex flex-col items-center justify-center px-6"
        aria-busy="true"
      >
        <span className="text-caption uppercase tracking-[0.18em] text-fg-tertiary mb-3">
          / Loading hold session
        </span>
        <p className="text-body-md text-fg-secondary">正在確認保留資訊…</p>
      </main>
    );
  }

  // ─── Render: full hold-confirm UI ─────────────────────────────────────────
  const expired = phase === 'expired';
  const confirmed = phase === 'confirmed';
  const seats = booking?.allocatedSeats ?? [];
  const totalPrice = computeTotalPrice(booking, selectedSection);

  return (
    <main className="min-h-screen bg-ink flex flex-col">
      <header className="border-b border-line-subtle">
        <div className="mx-auto max-w-5xl px-6 py-5 flex items-center justify-between gap-4">
          <Link to="/" className="flex items-center gap-2 group">
            <span aria-hidden className="inline-block h-2 w-6 bg-accent rounded-sm" />
            <span className="text-heading-md font-extrabold tracking-tight">
              ticket<span className="text-accent">/</span>master
            </span>
          </Link>
          <span className="text-caption uppercase tracking-[0.16em] text-fg-tertiary font-mono">
            booking · {(bookingId ?? '').slice(0, 8) || '——'}
          </span>
        </div>
      </header>

      <section className="flex-1 mx-auto w-full max-w-5xl px-6 py-12 md:py-16 flex flex-col gap-10 animate-fade-up">
        <div className="flex flex-col gap-3 max-w-3xl">
          <span className="text-caption uppercase tracking-[0.18em] text-fg-tertiary">
            / Hold Confirmed
          </span>
          <h1 className="text-display-md md:text-display-lg font-extrabold tracking-tight leading-[1.05]">
            {expired ? '保留時間已過' : '已為您保留座位'}
          </h1>
          {booking && (
            <p className="text-body-lg text-fg-secondary">
              {booking.eventId
                ? `活動 · ${formatEventRef(booking.eventId)}`
                : '活動資訊'}
              <span className="text-fg-tertiary"> · </span>
              {booking.section} 區
              <span className="text-fg-tertiary"> · </span>
              {booking.seatCount} 張
            </p>
          )}
        </div>

        <div
          className={cn(
            'grid grid-cols-1 md:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)] gap-10 items-start',
          )}
        >
          {/* Countdown column ------------------------------------------------- */}
          <div className="flex flex-col gap-5">
            <span className="text-caption uppercase tracking-[0.18em] text-fg-tertiary">
              / Hold Expires In
            </span>
            <HoldCountdown
              startedAt={startedAt}
              onExpired={handleExpired}
            />
            {expired ? (
              <p
                className="text-body-md text-signal-error border-l-2 border-signal-error/60 pl-4"
                role="alert"
              >
                您的座位保留已過期，請重新搶票。
              </p>
            ) : (
              <p className="text-body-sm text-fg-tertiary border-l-2 border-line-strong pl-4">
                Demo 倒數為純前端 UX；本 MVP 後端不會在 5 分鐘後釋出座位。
              </p>
            )}
          </div>

          {/* Seats column ---------------------------------------------------- */}
          <div className="flex flex-col gap-4">
            <div className="flex items-baseline justify-between gap-4">
              <h2 className="text-heading-lg font-bold tracking-tight">
                您的座位
              </h2>
              {totalPrice != null && (
                <span className="text-caption uppercase tracking-[0.14em] text-fg-tertiary">
                  總計 ·{' '}
                  <span className="text-accent font-mono text-body-md">
                    NT$ {totalPrice.toLocaleString()}
                  </span>
                </span>
              )}
            </div>

            {seats.length === 0 ? (
              <p className="text-body-md text-fg-secondary border border-line-subtle p-4 rounded-sm">
                尚未取得座位資訊。
              </p>
            ) : (
              <ul
                className={cn(
                  'grid gap-3 grid-cols-1 sm:grid-cols-2',
                  expired && 'opacity-50',
                )}
                aria-label="已分配座位列表"
              >
                {seats.map((seat) => {
                  const parsed = parseSeat(seat);
                  return (
                    <li
                      key={seat}
                      className={cn(
                        'bg-surface border border-line-subtle rounded-sm p-4',
                        'flex flex-col gap-1.5',
                        'transition-colors duration-base',
                        !expired && 'hover:border-line-strong',
                      )}
                    >
                      <span className="text-caption uppercase tracking-[0.12em] text-fg-tertiary">
                        Section · Row · Seat
                      </span>
                      <span className="text-heading-lg font-bold tracking-tight font-mono text-fg-primary">
                        {parsed.section} 區 · {parsed.row} 排 · {parsed.col} 號
                      </span>
                      <span className="text-body-sm text-fg-secondary font-mono">
                        {seat}
                      </span>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </div>

        {/* CTAs ------------------------------------------------------------- */}
        <div className="flex flex-wrap items-center gap-3 pt-4 border-t border-line-subtle">
          {expired ? (
            <Button variant="primary" size="lg" onClick={handleRetry}>
              重新搶票
            </Button>
          ) : (
            <>
              <Button
                variant="primary"
                size="lg"
                onClick={handleConfirm}
                disabled={confirmed}
                aria-disabled={confirmed}
              >
                {confirmed ? '已確認' : '確認保留'}
              </Button>
              <Button
                variant="ghost"
                size="md"
                onClick={() => navigate('/', { replace: true })}
              >
                取消並回活動列表
              </Button>
            </>
          )}
        </div>
      </section>

      <footer className="border-t border-line-subtle">
        <div className="mx-auto max-w-5xl px-6 py-5 text-caption uppercase tracking-[0.12em] text-fg-tertiary flex items-center justify-between">
          <span>TicketMaster MVP — Hold Confirmation</span>
          <span className="font-mono">phase · 6</span>
        </div>
      </footer>
    </main>
  );
}

// ─── Helpers ────────────────────────────────────────────────────────────────

type ParsedSeat = { section: string; row: string; col: string };

/** Parse "A-3-5" → { section: "A", row: "3", col: "5" }. Tolerant of edge cases. */
function parseSeat(raw: string): ParsedSeat {
  const parts = raw.split('-');
  if (parts.length < 3) {
    return { section: raw, row: '—', col: '—' };
  }
  const [section, row, col] = parts;
  return { section, row, col };
}

/** Pretty-format an event ref for the meta line (eventId is the only handle we have). */
function formatEventRef(eventId: number): string {
  return `EVT/${String(eventId).padStart(4, '0')}`;
}

/**
 * Compute the total price for the booking when the upstream chain forwarded a
 * `SelectedSectionSnapshot` (carrying `basePrice`) via router state. Falls
 * back to `null` when the snapshot is missing or has no price configured, so
 * callers can hide the chip rather than render a misleading "NT$ 0".
 */
function computeTotalPrice(
  booking: BookingResponse | null,
  selectedSection: { basePrice: number | null; seatCount: number } | null,
): number | null {
  if (!booking) return null;
  const price = selectedSection?.basePrice;
  if (typeof price !== 'number' || !Number.isFinite(price) || price <= 0) return null;
  // Prefer the authoritative booked seat count from the backend response.
  const qty = booking.seatCount ?? selectedSection?.seatCount ?? 0;
  if (qty <= 0) return null;
  return price * qty;
}
