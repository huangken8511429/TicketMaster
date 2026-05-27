/**
 * Integration test for the SSE pipeline that powers Phase 4's event detail page.
 *
 * Why it matters: the SSE + React-Query write-through is the single most fragile
 * part of the MVP (per phase-2 handoff §「對 Phase 3-6 的建議」). This test fakes
 * a minimal `EventSource` and proves that:
 *   1. `useSectionStatusStream` primes the cache from GET /sections,
 *   2. SectionBadge re-renders when a `section-status` event arrives,
 *   3. status visuals follow the payload (ON_SALE_PLENTY → ON_SALE_FEW).
 *
 * If this test breaks, the live "票區徽章隨 4s tick 變化" behaviour also breaks.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SectionBadge } from '@/components/SectionBadge';
import { useSections } from '@/api/sections';
import { useSectionStatusStream } from '@/hooks/useSectionStatusStream';
import type { SectionAvailability } from '@/api/types';

type Listener = (evt: MessageEvent) => void;

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  static OPEN = 1 as const;
  url: string;
  listeners = new Map<string, Set<Listener>>();
  readyState = 0;

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
    queueMicrotask(() => {
      this.readyState = 1;
      this.dispatch('open', new MessageEvent('open'));
    });
  }

  addEventListener(type: string, listener: Listener) {
    let set = this.listeners.get(type);
    if (!set) {
      set = new Set();
      this.listeners.set(type, set);
    }
    set.add(listener);
  }

  removeEventListener(type: string, listener: Listener) {
    this.listeners.get(type)?.delete(listener);
  }

  dispatch(type: string, evt: MessageEvent) {
    this.listeners.get(type)?.forEach((fn) => fn(evt));
  }

  emit(type: string, data: unknown) {
    this.dispatch(type, new MessageEvent(type, { data: JSON.stringify(data) }));
  }

  close() {
    this.readyState = 2;
  }
}

(globalThis as unknown as { EventSource: typeof FakeEventSource }).EventSource =
  FakeEventSource;

function Harness({ eventId }: { eventId: number }) {
  useSectionStatusStream(eventId);
  const { data } = useSections(eventId);
  if (!data) return <p>loading</p>;
  return (
    <div>
      {data.map((s) => (
        <SectionBadge key={s.section} section={s.section} status={s.status} />
      ))}
    </div>
  );
}

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: Infinity },
    },
  });
}

const initialSections: SectionAvailability[] = [
  { eventId: 1, section: 'A', totalSeats: 1000, availableCount: 800, status: 'ON_SALE_PLENTY' },
  { eventId: 1, section: 'B', totalSeats: 1000, availableCount: 200, status: 'ON_SALE_LIMITED' },
];

describe('SSE + SectionBadge integration', () => {
  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo) => {
        const url = typeof input === 'string' ? input : (input as Request).url;
        if (url.endsWith('/api/events/1/sections')) {
          return new Response(JSON.stringify(initialSections), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        return new Response('{}', { status: 404 });
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders initial badges from the prime GET', async () => {
    const client = makeClient();
    render(
      <QueryClientProvider client={client}>
        <Harness eventId={1} />
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(screen.getByLabelText(/區域 A：熱賣中/)).toBeTruthy();
      expect(screen.getByLabelText(/區域 B：即將售完/)).toBeTruthy();
    });
  });

  it('updates a badge when a section-status SSE event arrives', async () => {
    const client = makeClient();
    render(
      <QueryClientProvider client={client}>
        <Harness eventId={1} />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(FakeEventSource.instances.length).toBe(1));
    await waitFor(() => expect(screen.getByLabelText(/區域 A：熱賣中/)).toBeTruthy());

    const es = FakeEventSource.instances[0];
    expect(es.url).toContain('/api/events/1/sections/stream');

    act(() => {
      es.emit('section-status', {
        eventId: 1,
        section: 'A',
        totalSeats: 1000,
        availableCount: 30,
        status: 'ON_SALE_FEW',
      });
    });

    await waitFor(() => {
      expect(screen.getByLabelText(/區域 A：僅剩數張/)).toBeTruthy();
    });

    // The other section is untouched by the update.
    expect(screen.getByLabelText(/區域 B：即將售完/)).toBeTruthy();
  });

  it('closes the EventSource on unmount', async () => {
    const client = makeClient();
    const { unmount } = render(
      <QueryClientProvider client={client}>
        <Harness eventId={1} />
      </QueryClientProvider>,
    );
    await waitFor(() => expect(FakeEventSource.instances.length).toBe(1));
    const es = FakeEventSource.instances[0];
    unmount();
    expect(es.readyState).toBe(2);
  });
});
