import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client';
import type { SectionAvailability } from './types';

export const sectionsKeys = {
  byEvent: (eventId: number | string) => ['sections', String(eventId)] as const,
};

export function useSections(eventId: number | string | undefined) {
  return useQuery({
    queryKey: sectionsKeys.byEvent(eventId ?? 'pending'),
    queryFn: () => apiFetch<SectionAvailability[]>(`/api/events/${eventId}/sections`),
    enabled: eventId !== undefined && eventId !== null && eventId !== '',
    staleTime: 5 * 60 * 1000,
    // We allow SSE pushes to mutate this cache via queryClient.setQueryData.
  });
}
