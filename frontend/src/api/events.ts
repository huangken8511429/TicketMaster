import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client';
import type { EventResponse } from './types';

export const eventsKeys = {
  all: ['events'] as const,
  detail: (id: number | string) => ['events', String(id)] as const,
};

export function useEvents() {
  return useQuery({
    queryKey: eventsKeys.all,
    queryFn: () => apiFetch<EventResponse[]>('/api/events'),
    staleTime: 5 * 60 * 1000,
  });
}

export function useEventDetail(id: number | string | undefined) {
  return useQuery({
    queryKey: eventsKeys.detail(id ?? 'pending'),
    queryFn: () => apiFetch<EventResponse>(`/api/events/${id}`),
    enabled: id !== undefined && id !== null && id !== '',
    staleTime: 5 * 60 * 1000,
  });
}
