import { describe, it, expect } from 'vitest';
import { parseVenueSeatMap } from '@/lib/parseVenueSeatMap';

const validJson = JSON.stringify({
  schemaVersion: 1,
  viewBox: '0 0 800 600',
  stage: { position: 'north', shape: 'rect', rect: { x: 0, y: 0, width: 100, height: 50 }, label: 'STAGE' },
  sections: [
    { name: 'A', shape: 'polygon', polygon: [[0, 0], [10, 0], [5, 10]] },
    { name: 'B', shape: 'rect', rect: { x: 20, y: 20, width: 30, height: 40 } },
    { name: 'C', shape: 'circle', circle: { cx: 50, cy: 50, r: 20 } },
  ],
});

describe('parseVenueSeatMap', () => {
  it('returns null for null / undefined / empty / "{}"', () => {
    expect(parseVenueSeatMap(null)).toBeNull();
    expect(parseVenueSeatMap(undefined)).toBeNull();
    expect(parseVenueSeatMap('')).toBeNull();
    expect(parseVenueSeatMap('{}')).toBeNull();
  });

  it('returns null when JSON is malformed', () => {
    expect(parseVenueSeatMap('{not json')).toBeNull();
    expect(parseVenueSeatMap('[]')).toBeNull();
  });

  it('returns null when schemaVersion is not 1', () => {
    const v2 = JSON.stringify({
      schemaVersion: 2,
      viewBox: '0 0 800 600',
      stage: { position: 'north', shape: 'rect', label: 'STAGE' },
      sections: [{ name: 'A', shape: 'polygon', polygon: [[0, 0], [1, 0], [0, 1]] }],
    });
    expect(parseVenueSeatMap(v2)).toBeNull();
  });

  it('returns null when viewBox is invalid', () => {
    const bad = JSON.stringify({
      schemaVersion: 1,
      viewBox: 'not a viewbox',
      stage: { position: 'north', shape: 'rect', label: 'STAGE' },
      sections: [{ name: 'A', shape: 'polygon', polygon: [[0, 0], [1, 0], [0, 1]] }],
    });
    expect(parseVenueSeatMap(bad)).toBeNull();
  });

  it('returns null when sections array is empty', () => {
    const bad = JSON.stringify({
      schemaVersion: 1,
      viewBox: '0 0 100 100',
      stage: { position: 'north', shape: 'rect', label: 'STAGE' },
      sections: [],
    });
    expect(parseVenueSeatMap(bad)).toBeNull();
  });

  it('returns null when a section is missing its shape payload', () => {
    const bad = JSON.stringify({
      schemaVersion: 1,
      viewBox: '0 0 100 100',
      stage: { position: 'north', shape: 'rect', label: 'STAGE' },
      sections: [{ name: 'A', shape: 'polygon' }],
    });
    expect(parseVenueSeatMap(bad)).toBeNull();
  });

  it('parses a valid payload preserving all three shape types', () => {
    const result = parseVenueSeatMap(validJson);
    expect(result).not.toBeNull();
    expect(result?.schemaVersion).toBe(1);
    expect(result?.sections).toHaveLength(3);
    expect(result?.sections.map((s) => s.shape)).toEqual(['polygon', 'rect', 'circle']);
  });
});
