import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client';
import type { VenueResponse, VenueSeatMap } from './types';
import { parseVenueSeatMap } from '@/lib/parseVenueSeatMap';

export const venuesKeys = {
  detail: (id: number | string) => ['venue', String(id)] as const,
};

export function useVenue(id: number | string | undefined) {
  return useQuery({
    queryKey: venuesKeys.detail(id ?? 'pending'),
    queryFn: () => apiFetch<VenueResponse>(`/api/venues/${id}`),
    enabled: id !== undefined && id !== null && id !== '',
    staleTime: 5 * 60 * 1000,
  });
}

/**
 * Convenience wrapper: fetch venue then parse its seatMap JSON.
 * Returns null when venue is not yet loaded OR seatMap is invalid / empty.
 */
export function useParsedVenueSeatMap(id: number | string | undefined): VenueSeatMap | null {
  const { data } = useVenue(id);
  return useMemo(() => parseVenueSeatMap(data?.seatMap), [data?.seatMap]);
}
