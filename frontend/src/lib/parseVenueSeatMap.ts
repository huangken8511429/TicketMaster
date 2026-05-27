import type { VenueSeatMap, VenueSeatMapSection } from '@/api/types';

const VIEWBOX_RE = /^-?\d+(\.\d+)?\s+-?\d+(\.\d+)?\s+\d+(\.\d+)?\s+\d+(\.\d+)?$/;

function hasShapePayload(s: VenueSeatMapSection): boolean {
  if (s.shape === 'polygon') return Array.isArray(s.polygon) && s.polygon.length >= 3;
  if (s.shape === 'rect') return !!s.rect && typeof s.rect.width === 'number' && typeof s.rect.height === 'number';
  if (s.shape === 'circle') return !!s.circle && typeof s.circle.r === 'number';
  return false;
}

/**
 * Parse a raw venue.seatMap JSON string into a VenueSeatMap.
 * Returns null when:
 *   - raw is null / undefined / empty / "{}"
 *   - JSON parse fails
 *   - schemaVersion ≠ 1
 *   - required fields missing or malformed
 * Callers should fall back to <SectionList> when null is returned.
 */
export function parseVenueSeatMap(raw: string | null | undefined): VenueSeatMap | null {
  if (!raw || raw === '{}') return null;

  let obj: unknown;
  try {
    obj = JSON.parse(raw);
  } catch {
    return null;
  }

  if (!obj || typeof obj !== 'object') return null;
  const candidate = obj as Partial<VenueSeatMap>;

  if (candidate.schemaVersion !== 1) return null;
  if (typeof candidate.viewBox !== 'string' || !VIEWBOX_RE.test(candidate.viewBox)) return null;
  if (!candidate.stage || typeof candidate.stage !== 'object') return null;
  if (!Array.isArray(candidate.sections) || candidate.sections.length === 0) return null;

  for (const s of candidate.sections) {
    if (typeof s.name !== 'string' || !s.name) return null;
    if (!hasShapePayload(s)) return null;
  }

  return candidate as VenueSeatMap;
}
