/**
 * Integration tests for Phase 5 — Queue page.
 *
 * Strategy: mock the `useBookingPoll` hook (its own contract is covered
 * elsewhere; here we exercise the page's wiring around it) and a
 * lightweight memory-router harness so we can assert real react-router
 * navigation side-effects.
 *
 * Covered scenarios (mirrors features/queue.feature):
 *  - polling → renders QueueOverlay + immersive copy
 *  - success (BOOKED) → auto-navigates to /confirm/:bookingId and forwards
 *    the resolved BookingResponse via history state
 *  - failed (REJECTED / 5xx exhausted / 60s deadline) → renders failed UI
 *    with "回上一頁" + "回活動列表" CTAs
 *  - failed → back-to-list button routes to "/"
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import { ToastProvider } from '@/hooks/useToast';
import { QueuePage } from '@/pages/QueuePage';
import type { BookingResponse } from '@/api/types';
import type { UseBookingPollResult } from '@/hooks/useBookingPoll';

// ─── Mock the long-poll hook ───────────────────────────────────────────────
type PollMock = Partial<UseBookingPollResult>;

const pollState: { current: PollMock } = { current: { state: 'polling', elapsedSec: 0 } };

vi.mock('@/hooks/useBookingPoll', () => ({
  useBookingPoll: () => ({
    data: pollState.current.data ?? null,
    state: pollState.current.state ?? 'polling',
    elapsedSec: pollState.current.elapsedSec ?? 0,
    retryCount: pollState.current.retryCount ?? 0,
    error: pollState.current.error ?? null,
  }),
}));

// ─── Helpers ───────────────────────────────────────────────────────────────
function LocationSpy({ onChange }: { onChange: (loc: { pathname: string; state: unknown }) => void }) {
  const loc = useLocation();
  onChange({ pathname: loc.pathname, state: loc.state });
  return null;
}

function renderQueueAt(bookingId: string, initialState?: unknown) {
  const locations: Array<{ pathname: string; state: unknown }> = [];
  const utils = render(
    <ToastProvider>
      <MemoryRouter
        initialEntries={[{ pathname: `/queue/${bookingId}`, state: initialState }]}
      >
        <Routes>
          <Route path="/" element={<div data-testid="list-page">List</div>} />
          <Route path="/events/:id" element={<div data-testid="event-page">Event</div>} />
          <Route path="/queue/:bookingId" element={<QueuePage />} />
          <Route path="/confirm/:bookingId" element={<div data-testid="confirm-page">Confirm</div>} />
        </Routes>
        <LocationSpy onChange={(l) => locations.push(l)} />
      </MemoryRouter>
    </ToastProvider>,
  );
  return { ...utils, locations };
}

// ─── Tests ─────────────────────────────────────────────────────────────────
describe('QueuePage (Phase 5)', () => {
  beforeEach(() => {
    pollState.current = { state: 'polling', elapsedSec: 0 };
  });

  it('renders QueueOverlay with queueing copy while polling', () => {
    pollState.current = { state: 'polling', elapsedSec: 5 };
    renderQueueAt('abc-123');

    expect(screen.getByText('正在為您處理...')).toBeTruthy();
    expect(screen.getByText('預估等待時間：約 10 秒')).toBeTruthy();
    // Booking id strip + elapsed seconds rendered for the polling state.
    expect(screen.getByText(/booking · abc-123/)).toBeTruthy();
  });

  it('switches subline to long-wait copy after 30 seconds', () => {
    pollState.current = { state: 'polling', elapsedSec: 35 };
    renderQueueAt('abc-123');

    expect(screen.getByText('處理時間較長，請耐心等候')).toBeTruthy();
  });

  it('navigates to /confirm/:bookingId and forwards BookingResponse on success', async () => {
    const booking: BookingResponse = {
      bookingId: 'abc-123',
      eventId: 1,
      section: 'A',
      seatCount: 2,
      userId: 'user-1',
      status: 'BOOKED',
      allocatedSeats: ['A-3-5', 'A-3-6'],
      createdAt: new Date().toISOString(),
    };
    pollState.current = { state: 'success', data: booking, elapsedSec: 4 };
    const { locations } = renderQueueAt('abc-123');

    await waitFor(() => {
      expect(screen.getByTestId('confirm-page')).toBeTruthy();
    });

    const last = locations[locations.length - 1];
    expect(last.pathname).toBe('/confirm/abc-123');
    // BookingResponse handed off via router state so phase-6 ConfirmPage can
    // render seats without a redundant fetch.
    expect((last.state as { booking?: BookingResponse } | null)?.booking).toEqual(booking);
  });

  it('shows failed UI with both CTAs when poll terminates as failed', () => {
    pollState.current = {
      state: 'failed',
      error: 'Booking timed out after 60s',
      elapsedSec: 61,
    };
    renderQueueAt('abc-123');

    expect(screen.getByText('很抱歉，這次沒搶到')).toBeTruthy();
    expect(screen.getByText('您可以再試一次')).toBeTruthy();
    expect(screen.getByRole('button', { name: '回活動詳情' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '回活動列表' })).toBeTruthy();
  });

  it('does not auto-navigate to /confirm when state is failed', () => {
    pollState.current = {
      state: 'failed',
      data: {
        bookingId: 'abc-123',
        eventId: 1,
        section: 'A',
        seatCount: 1,
        userId: 'u',
        status: 'REJECTED',
        allocatedSeats: [],
        createdAt: new Date().toISOString(),
      },
      elapsedSec: 8,
    };
    renderQueueAt('abc-123');

    expect(screen.queryByTestId('confirm-page')).toBeNull();
    expect(screen.getByText('很抱歉，這次沒搶到')).toBeTruthy();
  });

  it('routes to "/" when user presses "回活動列表" after failure', async () => {
    pollState.current = { state: 'failed', error: 'sold out', elapsedSec: 5 };
    renderQueueAt('abc-123');

    fireEvent.click(screen.getByRole('button', { name: '回活動列表' }));

    await waitFor(() => expect(screen.getByTestId('list-page')).toBeTruthy());
  });

  it('routes back to /events/:id when fromEventId hint is provided', async () => {
    pollState.current = { state: 'failed', error: 'rejected', elapsedSec: 3 };
    renderQueueAt('abc-123', { fromEventId: 42 });

    fireEvent.click(screen.getByRole('button', { name: '回活動詳情' }));

    await waitFor(() => expect(screen.getByTestId('event-page')).toBeTruthy());
  });
});
