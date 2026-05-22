/**
 * Behavioural tests for BookingConfirmModal — mirrors the scenarios in
 * src/features/event-detail.feature §「點擊熱賣中票區開啟確認 modal」 / §「搶票成功跳轉排隊」.
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { BookingConfirmModal } from '@/components/BookingConfirmModal';
import type { EventResponse, SectionAvailability } from '@/api/types';

const baseEvent: EventResponse = {
  id: 1,
  name: 'Aurora Wavelength — Live in Taipei',
  description: 'demo',
  eventStartTime: '2026-08-15T19:30:00',
  eventEndTime: null,
  venueId: 11,
  venueName: 'Taipei Arena',
  performerName: 'Aurora Wavelength',
  totalSeats: 12000,
  sectionCount: 5,
  salesStartAt: '2026-08-01T10:00:00',
};

const baseSection: SectionAvailability = {
  eventId: 1,
  section: 'B',
  totalSeats: 2400,
  availableCount: 480,
  status: 'ON_SALE_LIMITED',
  basePrice: 2800,
};

describe('BookingConfirmModal', () => {
  it('renders section name and starts at 1 ticket', () => {
    render(
      <BookingConfirmModal
        open
        event={baseEvent}
        section={baseSection}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(screen.getByText('B')).toBeTruthy();
    expect(screen.getByLabelText('減少張數')).toBeTruthy();
    // 預估金額: 2800 * 1
    expect(screen.getByText(/2,800/)).toBeTruthy();
  });

  it('stepper respects 1-4 bounds', () => {
    render(
      <BookingConfirmModal
        open
        event={baseEvent}
        section={baseSection}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    const inc = screen.getByLabelText('增加張數');
    const dec = screen.getByLabelText('減少張數');

    // At seatCount=1, dec is disabled
    expect((dec as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(inc);
    fireEvent.click(inc);
    fireEvent.click(inc); // → 4
    expect((inc as HTMLButtonElement).disabled).toBe(true);
    // 預估金額: 2800 * 4 = 11200
    expect(screen.getByText(/11,200/)).toBeTruthy();
  });

  it('fires onConfirm with the chosen seat count', async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    render(
      <BookingConfirmModal
        open
        event={baseEvent}
        section={baseSection}
        onConfirm={onConfirm}
        onCancel={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByLabelText('增加張數'));
    fireEvent.click(screen.getByText('確認搶票'));
    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith(2));
  });

  it('shows inline error when onConfirm rejects', async () => {
    const onConfirm = vi.fn().mockRejectedValue(new Error('boom'));
    render(
      <BookingConfirmModal
        open
        event={baseEvent}
        section={baseSection}
        onConfirm={onConfirm}
        onCancel={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByText('確認搶票'));
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('boom'));
  });

  it('ESC key triggers onCancel', () => {
    const onCancel = vi.fn();
    render(
      <BookingConfirmModal
        open
        event={baseEvent}
        section={baseSection}
        onConfirm={vi.fn()}
        onCancel={onCancel}
      />,
    );
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onCancel).toHaveBeenCalled();
  });

  it('falls back to "票價未定" when basePrice is null', () => {
    render(
      <BookingConfirmModal
        open
        event={baseEvent}
        section={{ ...baseSection, basePrice: null }}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.getByText('票價未定')).toBeTruthy();
  });
});
