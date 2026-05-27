import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { API_BASE_URL, apiFetch } from '@/api/client';
import { sectionsKeys } from '@/api/sections';
import type { SectionAvailability, SectionStatusEvent } from '@/api/types';

export type SectionStreamState = {
  /** Map keyed by section name → latest availability snapshot. */
  sections: Map<string, SectionAvailability>;
  connected: boolean;
  /** Increments each reconnect (useful for tests / debugging). */
  reconnectCount: number;
};

const HEARTBEAT_EVENT = 'heartbeat';
const SECTION_STATUS_EVENT = 'section-status';

/**
 * SSE bridge to `GET /api/events/{eventId}/sections/stream`.
 *
 * - On mount: opens EventSource + fires one GET /sections to prime cache.
 * - On `section-status` event: aggregates into Map and writes through to React Query cache,
 *   so consumers of `useSections` also see updates without re-fetching.
 * - On reconnect (browser-driven): fires a fresh GET /sections to recover any missed deltas.
 * - On unmount: closes EventSource.
 *
 * Aggregation: payload may carry per-sub-partition deltas; backend SHOULD send aggregated
 * SectionAvailability (api-contract.md §4.2 "recommended"). We still merge by `section` key.
 */
export function useSectionStatusStream(eventId: number | string | undefined): SectionStreamState {
  const queryClient = useQueryClient();
  const [sections, setSections] = useState<Map<string, SectionAvailability>>(new Map());
  const [connected, setConnected] = useState(false);
  const [reconnectCount, setReconnectCount] = useState(0);

  useEffect(() => {
    if (eventId === undefined || eventId === null || eventId === '') return;

    let closed = false;
    let es: EventSource | null = null;

    const mergeIntoCache = (entries: SectionAvailability[]) => {
      setSections((prev) => {
        const next = new Map(prev);
        for (const entry of entries) {
          next.set(entry.section, entry);
        }
        return next;
      });
      queryClient.setQueryData<SectionAvailability[]>(sectionsKeys.byEvent(eventId), (curr) => {
        if (!curr) return entries;
        const byName = new Map(curr.map((s) => [s.section, s] as const));
        for (const entry of entries) byName.set(entry.section, entry);
        return Array.from(byName.values());
      });
    };

    const primeFromApi = async () => {
      try {
        const initial = await apiFetch<SectionAvailability[]>(`/api/events/${eventId}/sections`);
        if (closed) return;
        mergeIntoCache(initial);
      } catch {
        // Non-fatal — SSE may still hydrate state.
      }
    };

    const open = () => {
      es = new EventSource(`${API_BASE_URL}/api/events/${eventId}/sections/stream`);

      es.addEventListener('open', () => {
        if (closed) return;
        setConnected(true);
      });

      es.addEventListener(SECTION_STATUS_EVENT, (raw) => {
        if (closed) return;
        try {
          const evt = JSON.parse((raw as MessageEvent).data) as SectionStatusEvent;
          mergeIntoCache([
            {
              eventId: evt.eventId,
              section: evt.section,
              totalSeats: evt.totalSeats,
              availableCount: evt.availableCount,
              status: evt.status,
            },
          ]);
        } catch {
          // Ignore malformed payloads — heartbeat / unrelated events.
        }
      });

      es.addEventListener(HEARTBEAT_EVENT, () => {
        // Keep-alive only; nothing to do.
      });

      es.addEventListener('error', () => {
        if (closed) return;
        setConnected(false);
        // EventSource will auto-reconnect — when it does, re-fetch initial state.
        setReconnectCount((n) => n + 1);
        void primeFromApi();
      });
    };

    void primeFromApi();
    open();

    return () => {
      closed = true;
      es?.close();
    };
  }, [eventId, queryClient]);

  return { sections, connected, reconnectCount };
}
