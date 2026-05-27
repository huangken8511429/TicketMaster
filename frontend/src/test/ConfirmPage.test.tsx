/**
 * Integration tests for Phase 6 — Hold Confirm page.
 *
 * Strategy: render <ConfirmPage> in a MemoryRouter with controlled
 * `location.state.booking`, then exercise the lifecycle state machine:
 *
 *  1. router state present   → renders allocated seats + countdown
 *  2. router state missing   → 1s loading then redirect to "/"
 *  3. countdown elapses      → confirm CTA replaced by "重新搶票",
 *                              expired UI visible, seats dimmed
 *  4. expired CTA clicked    → navigate to "/"
 *  5. confirm CTA pressed    → demo toast + button locks to "已確認"
 *  6. cancel CTA pressed     → navigate back to "/"
 *
 * Note: tests that depend on the 5-min HoldCountdown / 1s missing-state grace
 * use fake timers + `vi.advanceTimersByTimeAsync`. Tests that exercise only
 * synchronous user clicks use real timers + RTL `waitFor`. Mixing the two on
 * a single test triggers waitFor-vs-fakeTimers deadlock (waitFor polls real
 * time which never advances), so we keep the modes per-test.
 */

import { describe, it, expect, vi, afterEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { ToastProvider } from '@/hooks/useToast';
import { ToastViewport } from '@/components/Toast';
import { ConfirmPage } from '@/pages/ConfirmPage';
import type { BookingResponse } from '@/api/types';

const FIXED_NOW = new Date('2026-05-18T10:00:00Z').getTime();

function makeBooking(overrides: Partial<BookingResponse> = {}): BookingResponse {
  return {
    bookingId: 'abc-123',
    eventId: 7,
    section: 'A',
    seatCount: 2,
    userId: 'user-1',
    status: 'BOOKED',
    allocatedSeats: ['A-3-5', 'A-3-6'],
    createdAt: new Date(FIXED_NOW).toISOString(),
    ...overrides,
  };
}

function LocationSpy({
  onChange,
}: {
  onChange: (loc: { pathname: string; state: unknown }) => void;
}) {
  const loc = useLocation();
  onChange({ pathname: loc.pathname, state: loc.state });
  return null;
}

function renderConfirmAt(
  bookingId: string,
  initialState?: unknown,
): {
  locations: Array<{ pathname: string; state: unknown }>;
} {
  const locations: Array<{ pathname: string; state: unknown }> = [];
  render(
    <ToastProvider>
      <MemoryRouter
        initialEntries={[{ pathname: `/confirm/${bookingId}`, state: initialState }]}
      >
        <Routes>
          <Route path="/" element={<div data-testid="list-page">List</div>} />
          <Route path="/confirm/:bookingId" element={<ConfirmPage />} />
        </Routes>
        <LocationSpy onChange={(l) => locations.push(l)} />
      </MemoryRouter>
      <ToastViewport />
    </ToastProvider>,
  );
  return { locations };
}

describe('ConfirmPage (Phase 6)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  // ─── Real-timer tests: synchronous user interactions ─────────────────────

  it('renders allocated seats and initial 05:00 countdown when router state is present', () => {
    // Anchor createdAt to real Date.now() so the countdown initial state is
    // exactly 5:00. Real timers + real Date.now() are in play here.
    const booking = makeBooking({
      allocatedSeats: ['A-3-5', 'A-3-6'],
      createdAt: new Date(Date.now()).toISOString(),
    });
    renderConfirmAt('abc-123', { booking });

    expect(screen.getByRole('heading', { name: '已為您保留座位' })).toBeTruthy();

    const list = screen.getByLabelText('已分配座位列表');
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(within(items[0]).getByText('A 區 · 3 排 · 5 號')).toBeTruthy();
    expect(within(items[1]).getByText('A 區 · 3 排 · 6 號')).toBeTruthy();

    // Countdown is rendered with role=timer. Initial state should display
    // a "5 minutes remaining" snapshot (5:00, with possible 4:59 race if the
    // rAF tick lands across the second boundary).
    const timer = screen.getByRole('timer');
    const txt = (timer.textContent ?? '').replace(/\s+/g, '');
    expect(txt).toMatch(/^0[45]:\d{2}/);
    expect(timer.textContent).toContain('請於倒數時間內確認您的座位');

    // Confirm CTA is the primary, enabled action.
    const confirm = screen.getByRole('button', { name: '確認保留' });
    expect(confirm.hasAttribute('disabled')).toBe(false);

    // Retry CTA does not exist while active.
    expect(screen.queryByRole('button', { name: '重新搶票' })).toBeNull();
  });

  it('shows a demo toast and locks the confirm button after pressing "確認保留"', () => {
    const booking = makeBooking();
    renderConfirmAt('abc-123', { booking });

    fireEvent.click(screen.getByRole('button', { name: '確認保留' }));

    expect(screen.getByText('結帳流程不在本 MVP — Demo 完成')).toBeTruthy();
    const locked = screen.getByRole('button', { name: '已確認' });
    expect(locked.hasAttribute('disabled')).toBe(true);
  });

  it('navigates back to "/" when the cancel CTA is clicked while active', async () => {
    const booking = makeBooking();
    const { locations } = renderConfirmAt('abc-123', { booking });

    fireEvent.click(screen.getByRole('button', { name: '取消並回活動列表' }));

    await waitFor(() => expect(screen.getByTestId('list-page')).toBeTruthy());
    expect(locations[locations.length - 1].pathname).toBe('/');
  });

  // ─── Fake-timer tests: time-dependent flows ──────────────────────────────

  it('redirects to "/" with a toast when router state is missing', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(FIXED_NOW));

    const { locations } = renderConfirmAt('abc-123');

    // Loading placeholder appears immediately.
    expect(screen.getByText('正在確認保留資訊…')).toBeTruthy();
    expect(screen.queryByText('已為您保留座位')).toBeNull();

    // Grace timer (1s) fires → toast pushed + navigate("/", replace).
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_100);
    });

    expect(screen.getByTestId('list-page')).toBeTruthy();
    const last = locations[locations.length - 1];
    expect(last.pathname).toBe('/');
    expect(screen.getByText('無法取得保留資訊，請重新搶票')).toBeTruthy();
  });

  it('switches to expired UI when the 5-minute countdown elapses', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(FIXED_NOW));

    // Pre-expire the booking by anchoring createdAt 6 minutes in the past.
    // Avoids having to advance 5+ minutes of fake-rAF ticks (which is O(N)
    // and balloons test time / risks scheduler deadlocks).
    const booking = makeBooking({
      createdAt: new Date(FIXED_NOW - 6 * 60 * 1000).toISOString(),
    });
    renderConfirmAt('abc-123', { booking });

    // First rAF tick observes target < now → fires onExpired → setPhase('expired').
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });

    expect(screen.getByRole('heading', { name: '保留時間已過' })).toBeTruthy();
    expect(
      screen.getByText('您的座位保留已過期，請重新搶票。'),
    ).toBeTruthy();

    // Confirm CTA replaced by the retry CTA.
    expect(screen.queryByRole('button', { name: '確認保留' })).toBeNull();
    expect(screen.getByRole('button', { name: '重新搶票' })).toBeTruthy();

    // Seat list is dimmed via opacity (class hint).
    const list = screen.getByLabelText('已分配座位列表');
    expect(list.className).toMatch(/opacity-50/);
  });

  it('navigates to "/" when the expired CTA is clicked', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(FIXED_NOW));

    const booking = makeBooking({
      createdAt: new Date(FIXED_NOW - 6 * 60 * 1000).toISOString(),
    });
    const { locations } = renderConfirmAt('abc-123', { booking });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });

    const retry = screen.getByRole('button', { name: '重新搶票' });
    // Switch back to real timers so React Router's navigation microtask
    // (which awaits real `Promise.resolve()`) can flush via waitFor.
    vi.useRealTimers();
    fireEvent.click(retry);

    await waitFor(() => expect(screen.getByTestId('list-page')).toBeTruthy());
    expect(locations[locations.length - 1].pathname).toBe('/');
  });

  it('renders the total price when selectedSection.basePrice is forwarded via router state', () => {
    const booking = makeBooking({
      seatCount: 2,
      createdAt: new Date(Date.now()).toISOString(),
    });
    renderConfirmAt('abc-123', {
      booking,
      selectedSection: { section: 'A', basePrice: 2800, seatCount: 2 },
    });

    // 2800 × 2 = 5600 — formatted with locale separators.
    expect(screen.getByText('NT$ 5,600')).toBeTruthy();
  });

  it('does not flip from confirmed back to expired when the 5-minute timer elapses', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(FIXED_NOW));

    const booking = makeBooking({
      // 4 minutes 59 seconds in the past: countdown will tick to 00:00 very soon.
      createdAt: new Date(FIXED_NOW - (5 * 60 - 1) * 1000).toISOString(),
    });
    renderConfirmAt('abc-123', { booking });

    // User taps confirm before the timer runs out.
    fireEvent.click(screen.getByRole('button', { name: '確認保留' }));
    expect(screen.getByRole('button', { name: '已確認' })).toBeTruthy();

    // Now let time pass beyond the 5-minute window — the rAF tick will fire
    // onExpired but the guard should keep us in the "confirmed" UI.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_000);
    });

    expect(screen.getByRole('button', { name: '已確認' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '重新搶票' })).toBeNull();
    expect(screen.queryByRole('heading', { name: '保留時間已過' })).toBeNull();
  });
});
