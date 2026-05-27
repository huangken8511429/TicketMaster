/**
 * Integration tests for Phase 3 — Event List page.
 *
 * Covers the BDD scenarios in src/features/event-list.feature:
 *   - list render with LIVE + UPCOMING cards
 *   - empty state
 *   - countdown → LIVE auto-switch (fake timers, no manual refresh)
 *   - error state with retry CTA
 *   - click navigates to /events/:id
 *
 * Why these specifically: phase-3 acceptance §D9 requires at least one
 * integration test covering list render + empty state + sales-countdown
 * transition; we also cover error/retry + click navigation to lock down the
 * remaining critical paths so phase-7 polish doesn't regress them.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { EventsListPage } from '@/pages/EventsListPage';
import { EventCard } from '@/components/EventCard';
import type { EventResponse } from '@/api/types';

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: Infinity, gcTime: 0 },
    },
  });
}

function renderApp(client = makeClient()) {
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<EventsListPage />} />
          <Route path="/events/:id" element={<div data-testid="event-detail-stub" />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockEventsFetch(payload: EventResponse[] | Error | { status: number; body?: string }) {
  return vi.fn(async (input: RequestInfo) => {
    const url = typeof input === 'string' ? input : (input as Request).url;
    if (!url.endsWith('/api/events')) {
      return new Response('{}', { status: 404 });
    }
    if (payload instanceof Error) {
      throw payload;
    }
    if (Array.isArray(payload)) {
      return new Response(JSON.stringify(payload), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }
    return new Response(payload.body ?? JSON.stringify({ error: 'boom' }), {
      status: payload.status,
      headers: { 'Content-Type': 'application/json' },
    });
  });
}

const livePast = new Date(Date.now() - 60 * 60 * 1000).toISOString(); // 1h ago
const upcomingFuture = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(); // +1d

const liveEvent: EventResponse = {
  id: 1,
  name: 'Aurora Wavelength — Live in Taipei',
  description: 'demo',
  eventStartTime: new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString(),
  eventEndTime: null,
  venueId: 11,
  venueName: 'Taipei Arena',
  performerName: 'Aurora Wavelength',
  totalSeats: 12000,
  sectionCount: 5,
  salesStartAt: livePast,
};

const upcomingEvent: EventResponse = {
  id: 2,
  name: 'Monolith — Geometry of Sound',
  description: 'demo',
  eventStartTime: new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString(),
  eventEndTime: null,
  venueId: 12,
  venueName: 'Kaohsiung Music Center',
  performerName: 'Monolith Ensemble',
  totalSeats: 8000,
  sectionCount: 5,
  salesStartAt: upcomingFuture,
};

describe('EventsListPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders LIVE and UPCOMING cards from the API', async () => {
    vi.stubGlobal('fetch', mockEventsFetch([liveEvent, upcomingEvent]));
    renderApp();

    await waitFor(() => {
      expect(screen.getByText(/Aurora Wavelength — Live in Taipei/)).toBeTruthy();
      expect(screen.getByText(/Monolith — Geometry of Sound/)).toBeTruthy();
    });

    // LIVE pill present (event 1)
    expect(screen.getAllByText('LIVE').length).toBeGreaterThanOrEqual(1);
    // UPCOMING pill present (event 2 — sales in the future)
    expect(screen.getAllByText('UPCOMING').length).toBeGreaterThanOrEqual(1);
  });

  it('shows empty state when API returns []', async () => {
    vi.stubGlobal('fetch', mockEventsFetch([]));
    renderApp();

    await waitFor(() => {
      expect(screen.getByText(/目前沒有/)).toBeTruthy();
    });
    expect(screen.queryByRole('list', { name: '活動列表' })).toBeNull();
  });

  it('shows error block + retry button on 500, refetch on click', async () => {
    const fetchMock = mockEventsFetch({ status: 500, body: JSON.stringify({ error: 'oops' }) });
    vi.stubGlobal('fetch', fetchMock);
    renderApp();

    await waitFor(() => {
      expect(screen.getByText(/載入失敗，請稍後再試/)).toBeTruthy();
    });
    const retry = screen.getByRole('button', { name: '重試' });
    expect(retry).toBeTruthy();

    // Click retry → another fetch is fired.
    const initialCalls = fetchMock.mock.calls.length;
    fireEvent.click(retry);
    await waitFor(() => {
      expect(fetchMock.mock.calls.length).toBeGreaterThan(initialCalls);
    });
  });

  it('navigates to /events/:id when a card is clicked', async () => {
    vi.stubGlobal('fetch', mockEventsFetch([liveEvent]));
    renderApp();

    const card = await screen.findByTestId('event-card-1');
    fireEvent.click(card);

    await waitFor(() => {
      expect(screen.getByTestId('event-detail-stub')).toBeTruthy();
    });
  });

  /**
   * Card-level test for the countdown → LIVE auto-flip. We render <EventCard>
   * in isolation (not the whole page) to avoid the well-known vitest + fake
   * timers + fetch deadlock: vi.useFakeTimers() pauses the microtask queue
   * that resolves the fetch mock, so waitFor inside the same test never
   * settles. The card-level test exercises the same hook chain
   * (useCountdown → onElapsed → setIsLive) that the page relies on.
   */
  describe('EventCard sales countdown auto-flip', () => {
    beforeEach(() => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date('2026-06-01T00:00:00Z'));
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it('flips UPCOMING → LIVE when salesStartAt elapses, without remount', () => {
      const salesIn3s = new Date(Date.now() + 3_000).toISOString();
      const evt: EventResponse = {
        ...upcomingEvent,
        id: 99,
        name: 'Countdown Test Event',
        salesStartAt: salesIn3s,
      };

      render(
        <MemoryRouter>
          <EventCard event={evt} />
        </MemoryRouter>,
      );

      // Initially UPCOMING — sales 3s in the future.
      expect(screen.getByText('UPCOMING')).toBeTruthy();
      expect(screen.queryByText('LIVE')).toBeNull();

      // Advance past sales start. useCountdown uses requestAnimationFrame,
      // which jsdom polyfills onto setTimeout(16ms) — vi.advanceTimersByTime
      // drives both.
      act(() => {
        vi.advanceTimersByTime(4_000);
      });

      // After the countdown elapses both the StatusPill and the SalesCountdown
      // render LIVE chips — so we expect at least one LIVE label and zero
      // UPCOMING labels.
      expect(screen.getAllByText('LIVE').length).toBeGreaterThanOrEqual(1);
      expect(screen.queryByText('UPCOMING')).toBeNull();
    });
  });
});
