/**
 * Seed fixtures for MSW. 3 events × 5 sections each.
 * Times are computed relative to "now" so countdowns are meaningful when you reload.
 */

import type {
  EventResponse,
  SectionAvailability,
  SectionStatus,
  VenueResponse,
  VenueSeatMap,
} from '@/api/types';

const MIN = 60 * 1000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

const now = Date.now();
const iso = (offset: number) => new Date(now + offset).toISOString().replace(/\.\d{3}Z$/, '');

export const seedEvents: EventResponse[] = [
  {
    id: 1,
    name: 'Aurora Wavelength — Live in Taipei',
    description:
      '一場橫跨 90 分鐘的視聽編年史。電子聲響、雷射網格、與你之間僅一張票的距離。',
    eventStartTime: iso(14 * DAY + 19 * HOUR + 30 * MIN),
    eventEndTime: iso(14 * DAY + 22 * HOUR),
    venueId: 11,
    venueName: 'Taipei Arena',
    performerName: 'Aurora Wavelength',
    totalSeats: 12_000,
    sectionCount: 5,
    // Sales already started — countdown will render LIVE
    salesStartAt: iso(-2 * HOUR),
    // Phase A: demo SECTION_VISUAL on the LIVE event so reviewers see the SVG map first.
    bookingMode: 'SECTION_VISUAL',
  },
  {
    id: 2,
    name: 'Monolith — Geometry of Sound',
    description:
      '極簡幾何與沉浸式聲場的對位法。座位即視角，視角即敘事。',
    eventStartTime: iso(30 * DAY + 20 * HOUR),
    eventEndTime: null,
    venueId: 12,
    venueName: 'Kaohsiung Music Center',
    performerName: 'Monolith Ensemble',
    totalSeats: 8_000,
    sectionCount: 5,
    // Sales open in 2 hours — used to demo hero countdown
    salesStartAt: iso(2 * HOUR + 30 * MIN),
    bookingMode: 'SECTION_TEXT',
  },
  {
    id: 3,
    name: 'Static Cathedral — Closing Night',
    description:
      '巡演最終場。沒有 encore，沒有重來。',
    eventStartTime: iso(7 * DAY + 19 * HOUR),
    eventEndTime: iso(7 * DAY + 22 * HOUR + 30 * MIN),
    venueId: 13,
    venueName: 'Taichung Intercontinental Hall',
    performerName: 'Static Cathedral',
    totalSeats: 6_000,
    sectionCount: 5,
    // Sold-out scenario
    salesStartAt: iso(-7 * DAY),
    bookingMode: 'SECTION_TEXT',
  },
];

/**
 * Shared MSW seatMap — A–E sections matching seedSections naming.
 * Stadium-style layout with stage at top.
 */
const stadiumSeatMap: VenueSeatMap = {
  schemaVersion: 1,
  viewBox: '0 0 800 600',
  stage: {
    position: 'north',
    shape: 'rect',
    rect: { x: 280, y: 30, width: 240, height: 50 },
    label: 'STAGE',
  },
  sections: [
    {
      name: 'A',
      displayName: 'A 區',
      tier: 'vip',
      shape: 'polygon',
      polygon: [[260, 110], [540, 110], [540, 210], [260, 210]],
      stageFacing: 'north',
    },
    {
      name: 'B',
      displayName: 'B 區',
      tier: 'tier1',
      shape: 'polygon',
      polygon: [[100, 220], [280, 220], [280, 380], [80, 400]],
      stageFacing: 'east',
    },
    {
      name: 'C',
      displayName: 'C 區',
      tier: 'tier1',
      shape: 'polygon',
      polygon: [[260, 230], [540, 230], [540, 360], [260, 360]],
      stageFacing: 'north',
    },
    {
      name: 'D',
      displayName: 'D 區',
      tier: 'tier1',
      shape: 'polygon',
      polygon: [[520, 220], [700, 220], [720, 400], [520, 380]],
      stageFacing: 'west',
    },
    {
      name: 'E',
      displayName: 'E 區',
      tier: 'tier2',
      shape: 'polygon',
      polygon: [[120, 410], [680, 410], [620, 530], [180, 530]],
      stageFacing: 'north',
    },
  ],
  legend: [
    { label: 'VIP', swatch: 'vip' },
    { label: '1F', swatch: 'tier1' },
    { label: '2F', swatch: 'tier2' },
  ],
};

const stadiumSeatMapJson = JSON.stringify(stadiumSeatMap);

export const seedVenues: VenueResponse[] = [
  { id: 11, name: 'Taipei Arena', location: '台北市松山區', seatMap: stadiumSeatMapJson },
  { id: 12, name: 'Kaohsiung Music Center', location: '高雄市鹽埕區', seatMap: stadiumSeatMapJson },
  { id: 13, name: 'Taichung Intercontinental Hall', location: '台中市西屯區', seatMap: stadiumSeatMapJson },
];

function sectionsForEvent(
  eventId: number,
  spec: Array<{ name: string; status: SectionStatus; total: number; available: number; price?: number | null }>,
): SectionAvailability[] {
  return spec.map((s) => ({
    eventId,
    section: s.name,
    totalSeats: s.total,
    availableCount: s.available,
    status: s.status,
    basePrice: s.price ?? null,
  }));
}

export const seedSections: Record<number, SectionAvailability[]> = {
  1: sectionsForEvent(1, [
    { name: 'A', status: 'ON_SALE_PLENTY', total: 2400, available: 1800, price: 3800 },
    { name: 'B', status: 'ON_SALE_LIMITED', total: 2400, available: 480, price: 2800 },
    { name: 'C', status: 'ON_SALE_FEW', total: 2400, available: 80, price: 2200 },
    { name: 'D', status: 'ON_SALE_PLENTY', total: 2400, available: 2000, price: 1800 },
    { name: 'E', status: 'SOLD_OUT', total: 2400, available: 0, price: 1200 },
  ]),
  2: sectionsForEvent(2, [
    { name: 'A', status: 'NOT_STARTED', total: 1600, available: 1600, price: 4200 },
    { name: 'B', status: 'NOT_STARTED', total: 1600, available: 1600, price: 3200 },
    { name: 'C', status: 'NOT_STARTED', total: 1600, available: 1600, price: 2600 },
    { name: 'D', status: 'NOT_STARTED', total: 1600, available: 1600, price: 2000 },
    { name: 'E', status: 'NOT_STARTED', total: 1600, available: 1600, price: null },
  ]),
  3: sectionsForEvent(3, [
    { name: 'A', status: 'SOLD_OUT', total: 1200, available: 0, price: 3600 },
    { name: 'B', status: 'SOLD_OUT', total: 1200, available: 0, price: 2800 },
    { name: 'C', status: 'ON_SALE_FEW', total: 1200, available: 12, price: 2200 },
    { name: 'D', status: 'SOLD_OUT', total: 1200, available: 0, price: 1800 },
    { name: 'E', status: 'SOLD_OUT', total: 1200, available: 0, price: 1200 },
  ]),
};
