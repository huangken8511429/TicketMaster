import { HttpResponse, http, delay } from 'msw';
import type {
  BookingAcceptedResponse,
  BookingRequest,
  BookingResponse,
  SectionAvailability,
  SectionStatus,
} from '@/api/types';
import { seedEvents, seedSections, seedVenues } from './seed';

// ---- In-memory booking ledger -------------------------------------------------

type BookingRecord =
  | { stage: 'pending'; createdAt: number; req: BookingRequest; resolveAt: number }
  | { stage: 'resolved'; response: BookingResponse };

const bookings = new Map<string, BookingRecord>();

function pickAllocatedSeats(section: string, seatCount: number): string[] {
  // Stable but plausible allocation. Section + Row + Col.
  const baseRow = Math.floor(Math.random() * 18) + 3;
  const baseCol = Math.floor(Math.random() * 14) + 4;
  return Array.from({ length: seatCount }, (_, i) => `${section}-${baseRow}-${baseCol + i}`);
}

function resolveBooking(bookingId: string): BookingResponse {
  const record = bookings.get(bookingId);
  if (!record || record.stage === 'resolved') {
    throw new Error('cannot resolve');
  }
  // 88% success — exercise both branches.
  const success = Math.random() < 0.88;
  const resolved: BookingResponse = {
    bookingId,
    eventId: record.req.eventId,
    section: record.req.section,
    seatCount: record.req.seatCount,
    userId: record.req.userId,
    status: success ? 'BOOKED' : 'REJECTED',
    allocatedSeats: success ? pickAllocatedSeats(record.req.section, record.req.seatCount) : [],
    createdAt: new Date(record.createdAt).toISOString(),
  };
  bookings.set(bookingId, { stage: 'resolved', response: resolved });
  return resolved;
}

// ---- Live section status stream registry --------------------------------------

const streamSubscribers = new Map<number, Set<(evt: SectionAvailability) => void>>();

function subscribe(eventId: number, fn: (evt: SectionAvailability) => void) {
  let set = streamSubscribers.get(eventId);
  if (!set) {
    set = new Set();
    streamSubscribers.set(eventId, set);
  }
  set.add(fn);
  return () => set?.delete(fn);
}

function emit(eventId: number, evt: SectionAvailability) {
  streamSubscribers.get(eventId)?.forEach((fn) => fn(evt));
}

// Periodically nudge section counts so the UI shows live updates.
const TICK_MS = 4000;
if (typeof window !== 'undefined') {
  setInterval(() => {
    for (const [eventId, list] of Object.entries(seedSections)) {
      const idx = Math.floor(Math.random() * list.length);
      const target = list[idx];
      if (!target || target.status === 'NOT_STARTED' || target.status === 'SOLD_OUT') continue;
      const drop = Math.min(target.availableCount, Math.floor(Math.random() * 12) + 1);
      target.availableCount = Math.max(0, target.availableCount - drop);
      const ratio = target.availableCount / target.totalSeats;
      const nextStatus: SectionStatus =
        target.availableCount === 0
          ? 'SOLD_OUT'
          : ratio < 0.05
            ? 'ON_SALE_FEW'
            : ratio < 0.3
              ? 'ON_SALE_LIMITED'
              : 'ON_SALE_PLENTY';
      target.status = nextStatus;
      emit(Number(eventId), { ...target });
    }
  }, TICK_MS);
}

// ---- HTTP handlers ------------------------------------------------------------

export const handlers = [
  http.get('*/api/events', async () => {
    await delay(120);
    return HttpResponse.json(seedEvents);
  }),

  http.get('*/api/events/:id', async ({ params }) => {
    await delay(80);
    const id = Number(params.id);
    const evt = seedEvents.find((e) => e.id === id);
    if (!evt) return HttpResponse.json({ error: 'Event not found' }, { status: 404 });
    return HttpResponse.json(evt);
  }),

  http.get('*/api/venues/:id', async ({ params }) => {
    await delay(60);
    const id = Number(params.id);
    const v = seedVenues.find((x) => x.id === id);
    if (!v) return HttpResponse.json({ error: 'Venue not found' }, { status: 404 });
    return HttpResponse.json(v);
  }),

  http.get('*/api/events/:id/sections', async ({ params }) => {
    await delay(140);
    const id = Number(params.id);
    const list = seedSections[id];
    if (!list) return HttpResponse.json({ error: 'Sections not found' }, { status: 404 });
    return HttpResponse.json(list);
  }),

  // SSE — section status stream
  http.get('*/api/events/:id/sections/stream', ({ params }) => {
    const id = Number(params.id);
    const encoder = new TextEncoder();

    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        const send = (event: string, data: unknown) => {
          controller.enqueue(
            encoder.encode(`event: ${event}\nid: ${Date.now()}\ndata: ${JSON.stringify(data)}\n\n`),
          );
        };

        // Send initial snapshot so the client doesn't need to wait for the first nudge.
        for (const section of seedSections[id] ?? []) {
          send('section-status', section);
        }

        const unsubscribe = subscribe(id, (evt) => send('section-status', evt));
        const heartbeat = setInterval(() => send('heartbeat', { t: Date.now() }), 15_000);

        // Closing signal — clean up when the consumer cancels.
        const close = () => {
          unsubscribe();
          clearInterval(heartbeat);
          try {
            controller.close();
          } catch {
            // already closed
          }
        };
        // MSW does not give us a direct "client disconnect" hook;
        // rely on the browser to GC the stream when the EventSource closes.
        // Expose `close` on the controller for tests if needed.
        (controller as unknown as { _close?: () => void })._close = close;
      },
    });

    return new HttpResponse(stream, {
      status: 200,
      headers: {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
        'X-Accel-Buffering': 'no',
      },
    });
  }),

  http.post('*/api/bookings', async ({ request }) => {
    const req = (await request.json()) as BookingRequest;
    await delay(60);

    // 422 if the section is sold-out in our ledger.
    const sectionRow = seedSections[req.eventId]?.find((s) => s.section === req.section);
    if (!sectionRow || sectionRow.status === 'SOLD_OUT' || sectionRow.availableCount === 0) {
      return HttpResponse.json({ error: 'No seats available' }, { status: 422 });
    }

    const bookingId = (typeof crypto !== 'undefined' && 'randomUUID' in crypto)
      ? crypto.randomUUID()
      : `mock-${Math.random().toString(36).slice(2)}`;

    // Resolve after a random delay (1.5s ~ 4.5s) — covers both fast path & long-poll re-issue.
    const resolveAt = Date.now() + 1500 + Math.random() * 3000;
    bookings.set(bookingId, { stage: 'pending', createdAt: Date.now(), req, resolveAt });

    return HttpResponse.json<BookingAcceptedResponse>({ bookingId }, { status: 202 });
  }),

  // Long-poll up to 10s, mimicking DeferredResult.
  http.get('*/api/bookings/:bookingId', async ({ params }) => {
    const bookingId = String(params.bookingId);
    const record = bookings.get(bookingId);
    if (!record) {
      return HttpResponse.json({ error: 'Booking not found' }, { status: 404 });
    }

    if (record.stage === 'resolved') {
      return HttpResponse.json(record.response);
    }

    const remainingMs = record.resolveAt - Date.now();
    if (remainingMs <= 0) {
      const resolved = resolveBooking(bookingId);
      return HttpResponse.json(resolved);
    }

    const longPollWindow = 10_000;
    if (remainingMs <= longPollWindow) {
      await delay(remainingMs);
      const resolved = resolveBooking(bookingId);
      return HttpResponse.json(resolved);
    }

    // Still pending after the long-poll window — return 202.
    await delay(longPollWindow);
    return new HttpResponse(null, { status: 202 });
  }),
];
