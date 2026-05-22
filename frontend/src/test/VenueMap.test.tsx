import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { VenueMap } from '@/components/VenueMap';
import type { SectionAvailability, VenueSeatMap } from '@/api/types';

const seatMap: VenueSeatMap = {
  schemaVersion: 1,
  viewBox: '0 0 800 600',
  stage: { position: 'north', shape: 'rect', rect: { x: 280, y: 30, width: 240, height: 50 }, label: 'STAGE' },
  sections: [
    { name: 'A', shape: 'polygon', polygon: [[100, 100], [300, 100], [300, 200], [100, 200]] },
    { name: 'B', shape: 'rect', rect: { x: 400, y: 100, width: 200, height: 100 } },
    { name: 'C', shape: 'circle', circle: { cx: 200, cy: 400, r: 60 } },
    { name: 'Unconfigured', shape: 'polygon', polygon: [[600, 400], [700, 400], [700, 500], [600, 500]] },
  ],
};

function mkSection(name: string, status: SectionAvailability['status'], available = 100): SectionAvailability {
  return {
    eventId: 1,
    section: name,
    totalSeats: 100,
    availableCount: available,
    status,
    basePrice: 1000,
  };
}

describe('<VenueMap />', () => {
  it('renders STAGE label and all three shape types', () => {
    render(<VenueMap seatMap={seatMap} sections={[]} onPick={() => {}} />);
    // One STAGE label per section + the main venue STAGE = sections.length + 1
    expect(screen.getAllByText('STAGE').length).toBe(seatMap.sections.length + 1);
    // A polygon + Unconfigured polygon = 2 polygons (section shapes)
    expect(document.querySelectorAll('polygon').length).toBe(2);
    // Stage rect + section B's rect + 1 mini-STAGE rect per section (4) = 6 rects total
    expect(document.querySelectorAll('rect').length).toBe(2 + seatMap.sections.length);
    expect(document.querySelectorAll('circle').length).toBe(1);
  });

  it('marks unmatched sections as aria-disabled (unconfigured)', () => {
    render(
      <VenueMap
        seatMap={seatMap}
        sections={[mkSection('A', 'ON_SALE_PLENTY')]}
        onPick={() => {}}
      />,
    );
    const unconfigured = screen.getByLabelText(/Unconfigured：未配置/);
    expect(unconfigured.getAttribute('aria-disabled')).toBe('true');
  });

  it('fires onPick when an ON_SALE polygon is clicked', () => {
    const onPick = vi.fn();
    render(
      <VenueMap
        seatMap={seatMap}
        sections={[mkSection('A', 'ON_SALE_PLENTY')]}
        onPick={onPick}
      />,
    );
    fireEvent.click(screen.getByLabelText(/A：熱賣中/));
    expect(onPick).toHaveBeenCalledTimes(1);
    expect(onPick.mock.calls[0][0].section).toBe('A');
  });

  it('does NOT fire onPick when a SOLD_OUT polygon is clicked', () => {
    const onPick = vi.fn();
    render(
      <VenueMap
        seatMap={seatMap}
        sections={[mkSection('A', 'SOLD_OUT', 0)]}
        onPick={onPick}
      />,
    );
    fireEvent.click(screen.getByLabelText(/A：已售完/));
    expect(onPick).not.toHaveBeenCalled();
  });

  it('fires onPick on Enter key for an interactive polygon', () => {
    const onPick = vi.fn();
    render(
      <VenueMap
        seatMap={seatMap}
        sections={[mkSection('B', 'ON_SALE_FEW', 5)]}
        onPick={onPick}
      />,
    );
    const target = screen.getByLabelText(/B：僅剩數張/);
    fireEvent.keyDown(target, { key: 'Enter' });
    expect(onPick).toHaveBeenCalledTimes(1);
  });
});
