import { useMutation } from '@tanstack/react-query';
import { apiFetch } from './client';
import type { BookingAcceptedResponse, BookingRequest } from './types';

export function useCreateBooking() {
  return useMutation({
    mutationFn: (req: BookingRequest) =>
      apiFetch<BookingAcceptedResponse>('/api/bookings', {
        method: 'POST',
        body: req,
      }),
  });
}
