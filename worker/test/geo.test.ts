import { describe, expect, it } from 'vitest';
import {
  bearingDeg,
  bearingDelta,
  centroidOf,
  distanceM,
  padBBox,
  parseBBox,
  snapBBox,
  tileBBox,
  withinAustralia,
} from '../src/lib/geo';

describe('distanceM', () => {
  it('is zero for the same point', () => {
    expect(distanceM(-33.87, 151.21, -33.87, 151.21)).toBe(0);
  });

  it('matches a known Sydney to Melbourne great-circle distance', () => {
    // Sydney Opera House to Flinders Street Station: ~713 km.
    const d = distanceM(-33.8568, 151.2153, -37.8183, 144.9671);
    expect(d).toBeGreaterThan(705_000);
    expect(d).toBeLessThan(720_000);
  });

  it('handles a short suburban hop', () => {
    // Roughly 1.11 km per 0.01 degrees of latitude.
    const d = distanceM(-33.87, 151.21, -33.88, 151.21);
    expect(d).toBeGreaterThan(1_090);
    expect(d).toBeLessThan(1_120);
  });
});

describe('bearingDeg', () => {
  it('reads due north as 0', () => {
    expect(bearingDeg(-33.87, 151.21, -33.86, 151.21)).toBeCloseTo(0, 1);
  });

  it('reads due east as 90', () => {
    expect(bearingDeg(-33.87, 151.21, -33.87, 151.22)).toBeCloseTo(90, 1);
  });

  it('reads due south as 180', () => {
    expect(bearingDeg(-33.87, 151.21, -33.88, 151.21)).toBeCloseTo(180, 1);
  });
});

describe('bearingDelta', () => {
  it('is zero for identical bearings', () => {
    expect(bearingDelta(0, 0)).toBe(0);
    expect(bearingDelta(217, 217)).toBe(0);
  });

  it('is 180 for opposing bearings', () => {
    expect(bearingDelta(0, 180)).toBe(180);
    expect(bearingDelta(90, 270)).toBe(180);
  });

  it('wraps across north instead of going the long way', () => {
    expect(bearingDelta(10, 350)).toBe(20);
    expect(bearingDelta(350, 10)).toBe(20);
  });

  it('never exceeds 180', () => {
    for (let a = 0; a < 360; a += 17) {
      for (let b = 0; b < 360; b += 23) {
        const d = bearingDelta(a, b);
        expect(d).toBeGreaterThanOrEqual(0);
        expect(d).toBeLessThanOrEqual(180);
      }
    }
  });
});

describe('parseBBox', () => {
  it('parses a well-formed bbox', () => {
    expect(parseBBox('150,-34,151,-33')).toEqual({
      minLon: 150,
      minLat: -34,
      maxLon: 151,
      maxLat: -33,
    });
  });

  it('rejects inverted, short, and non-numeric input', () => {
    expect(parseBBox('151,-33,150,-34')).toBeNull();
    expect(parseBBox('150,-34,151')).toBeNull();
    expect(parseBBox('a,b,c,d')).toBeNull();
    expect(parseBBox(null)).toBeNull();
  });

  it('rejects out-of-range coordinates', () => {
    expect(parseBBox('150,-91,151,-33')).toBeNull();
    expect(parseBBox('150,-34,181,-33')).toBeNull();
  });
});

describe('padBBox', () => {
  it('grows the box in both axes', () => {
    const padded = padBBox({ minLon: 151, minLat: -34, maxLon: 151.1, maxLat: -33.9 }, 1000);
    expect(padded.minLat).toBeLessThan(-34);
    expect(padded.maxLat).toBeGreaterThan(-33.9);
    expect(padded.minLon).toBeLessThan(151);
    expect(padded.maxLon).toBeGreaterThan(151.1);
  });

  it('clamps at the poles rather than producing an invalid box', () => {
    const padded = padBBox({ minLon: 0, minLat: -89.99, maxLon: 1, maxLat: 89.99 }, 500_000);
    expect(padded.minLat).toBeGreaterThanOrEqual(-90);
    expect(padded.maxLat).toBeLessThanOrEqual(90);
  });
});

describe('snapBBox', () => {
  it('snaps two nearby boxes onto the same grid cell', () => {
    const a = snapBBox({ minLon: 151.11, minLat: -33.87, maxLon: 151.13, maxLat: -33.85 }, 0.25);
    const b = snapBBox({ minLon: 151.19, minLat: -33.89, maxLon: 151.21, maxLat: -33.86 }, 0.25);
    expect(a).toEqual(b);
  });

  it('always contains the original box', () => {
    const original = { minLon: 151.11, minLat: -33.87, maxLon: 151.13, maxLat: -33.85 };
    const snapped = snapBBox(original, 0.25);
    expect(snapped.minLon).toBeLessThanOrEqual(original.minLon);
    expect(snapped.maxLon).toBeGreaterThanOrEqual(original.maxLon);
    expect(snapped.minLat).toBeLessThanOrEqual(original.minLat);
    expect(snapped.maxLat).toBeGreaterThanOrEqual(original.maxLat);
  });
});

describe('tileBBox', () => {
  it('covers the requested area with whole tiles', () => {
    const tiles = tileBBox({ minLon: 150, minLat: -34, maxLon: 151, maxLat: -33 }, 0.5);
    expect(tiles.length).toBe(4);
  });
});

describe('centroidOf', () => {
  it('returns the point itself for a Point geometry', () => {
    expect(centroidOf({ type: 'Point', coordinates: [151.2, -33.8] })).toEqual({
      lon: 151.2,
      lat: -33.8,
    });
  });

  it('averages a LineString', () => {
    const c = centroidOf({
      type: 'LineString',
      coordinates: [
        [151.0, -34.0],
        [151.2, -33.8],
      ],
    });
    expect(c?.lon).toBeCloseTo(151.1, 6);
    expect(c?.lat).toBeCloseTo(-33.9, 6);
  });

  it('walks nested Polygon rings', () => {
    const c = centroidOf({
      type: 'Polygon',
      coordinates: [
        [
          [151.0, -34.0],
          [151.2, -34.0],
          [151.2, -33.8],
          [151.0, -33.8],
        ],
      ],
    });
    expect(c?.lon).toBeCloseTo(151.1, 6);
    expect(c?.lat).toBeCloseTo(-33.9, 6);
  });

  it('returns null for geometry with no coordinates', () => {
    expect(centroidOf(null)).toBeNull();
    expect(centroidOf({ type: 'Point' })).toBeNull();
    expect(centroidOf({ type: 'Point', coordinates: [] })).toBeNull();
  });
});

describe('withinAustralia', () => {
  it('accepts the capitals', () => {
    expect(withinAustralia(-33.87, 151.21)).toBe(true); // Sydney
    expect(withinAustralia(-31.95, 115.86)).toBe(true); // Perth
    expect(withinAustralia(-12.46, 130.84)).toBe(true); // Darwin
    expect(withinAustralia(-42.88, 147.33)).toBe(true); // Hobart
  });

  it('rejects points well outside the continent', () => {
    expect(withinAustralia(-36.85, 174.76)).toBe(false); // Auckland
    expect(withinAustralia(51.5, -0.12)).toBe(false); // London
    expect(withinAustralia(0, 0)).toBe(false);
  });
});
