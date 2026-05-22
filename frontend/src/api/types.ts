/**
 * API types — source of truth: specs/frontend-mvp/api-contract.md
 */

/**
 * Phase A 新增 (specs/seat-map/booking-mode-design.md):
 *   - SECTION_TEXT   既有票區清單模式（預設）
 *   - SECTION_VISUAL 視覺化選區（Phase A 新增 <VenueMap>）
 *   - SEAT_LEVEL     逐座位選位（Phase B 預留，目前顯示占位）
 */
export type BookingMode = 'SECTION_TEXT' | 'SECTION_VISUAL' | 'SEAT_LEVEL';

export type EventResponse = {
  id: number;
  name: string;
  description: string;
  eventStartTime: string;
  eventEndTime: string | null;
  venueId: number;
  venueName: string;
  performerName: string;
  totalSeats: number | null;
  sectionCount: number | null;
  /** Added in api-contract §4.3. Optional — null means "on sale now". */
  salesStartAt?: string | null;
  /** Added in seat-map Phase A. Optional — undefined falls back to SECTION_TEXT. */
  bookingMode?: BookingMode;
};

export type VenueResponse = {
  id: number;
  name: string;
  location: string;
  /** JSON-encoded seat map (VenueSeatMap shape). May be "{}" / null on legacy rows. */
  seatMap: string | null;
};

/* ============================================================
 * Venue seat map JSON shapes (specs/seat-map/venue-seatmap-schema.md §4)
 * Parsed by parseVenueSeatMap() before consumption.
 * ============================================================ */

export type SeatMapVersion = 1;
export type StagePosition = 'north' | 'south' | 'east' | 'west' | 'center';
export type SectionShape = 'polygon' | 'rect' | 'circle';

export type Rect = { x: number; y: number; width: number; height: number };
export type Circle = { cx: number; cy: number; r: number };
export type Polygon = Array<[number, number]>;

export type VenueSeatMapSection = {
  name: string;
  displayName?: string;
  tier?: 'vip' | 'tier1' | 'tier2' | 'standing' | string;
  shape: SectionShape;
  polygon?: Polygon;
  rect?: Rect;
  circle?: Circle;
  labelAnchor?: { x: number; y: number };
  rotationDeg?: number;
  stageFacing?: StagePosition;

  // Phase B placeholders — present in schema, NOT consumed by Phase A renderer
  rows?: null | Array<unknown>;
  seatGrid?: null | unknown;
  accessibilityZones?: null | Array<Polygon>;
  blockedSeats?: null | string[];
};

export type VenueSeatMap = {
  schemaVersion: SeatMapVersion;
  viewBox: string;
  stage: {
    position: StagePosition;
    shape: 'rect' | 'polygon';
    rect?: Rect;
    polygon?: Polygon;
    label: string;
  };
  sections: VenueSeatMapSection[];
  legend?: Array<{ label: string; swatch: string }>;
  meta?: {
    rotationDeg?: number;
    background?: string | null;
  };
};

export type SectionStatus =
  | 'NOT_STARTED'
  | 'ON_SALE_PLENTY'
  | 'ON_SALE_LIMITED'
  | 'ON_SALE_FEW'
  | 'SOLD_OUT';

export type SectionAvailability = {
  eventId: number;
  section: string;
  totalSeats: number;
  availableCount: number;
  status: SectionStatus;
  /**
   * Optional fixed price per seat (TWD). Phase 1 backend surfaces this column
   * (`Section.basePrice`). Null when not configured — frontend renders "票價未定".
   */
  basePrice?: number | null;
};

export type BookingRequest = {
  eventId: number;
  section: string;
  seatCount: number;
  userId: string;
};

export type BookingAcceptedResponse = {
  bookingId: string;
};

export type BookingStatus = 'BOOKED' | 'REJECTED';

export type BookingResponse = {
  bookingId: string;
  eventId: number;
  section: string;
  seatCount: number;
  userId: string;
  status: BookingStatus;
  /** Format: "section-row-col". e.g. ["A-3-5", "A-3-6"]. */
  allocatedSeats: string[];
  createdAt: string;
};

/** Single SSE message payload from `event: section-status` */
export type SectionStatusEvent = SectionAvailability & {
  subPartition?: number;
  totalSubPartitions?: number;
  timestamp?: number;
};
