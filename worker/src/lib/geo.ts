import type { BBox } from './types';

const EARTH_RADIUS_M = 6_371_008.8;

const toRad = (deg: number) => (deg * Math.PI) / 180;
const toDeg = (rad: number) => (rad * 180) / Math.PI;

/** Great-circle distance in metres. */
export function distanceM(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number,
): number {
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
}

/** Initial bearing from point 1 to point 2, in degrees clockwise from north. */
export function bearingDeg(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number,
): number {
  const dLon = toRad(lon2 - lon1);
  const y = Math.sin(dLon) * Math.cos(toRad(lat2));
  const x =
    Math.cos(toRad(lat1)) * Math.sin(toRad(lat2)) -
    Math.sin(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.cos(dLon);
  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}

/** Smallest absolute difference between two bearings, 0..180. */
export function bearingDelta(a: number, b: number): number {
  return Math.abs((((a - b) % 360) + 540) % 360 - 180);
}

export function parseBBox(raw: string | null): BBox | null {
  if (!raw) return null;
  const parts = raw.split(',').map((p) => Number(p.trim()));
  if (parts.length !== 4 || parts.some((n) => !Number.isFinite(n))) return null;
  const [minLon, minLat, maxLon, maxLat] = parts as [number, number, number, number];
  if (minLat > maxLat || minLon > maxLon) return null;
  if (minLat < -90 || maxLat > 90 || minLon < -180 || maxLon > 180) return null;
  return { minLon, minLat, maxLon, maxLat };
}

/** Grow a bbox by a margin in metres, clamped to valid lat/lon. */
export function padBBox(b: BBox, metres: number): BBox {
  const dLat = (metres / EARTH_RADIUS_M) * (180 / Math.PI);
  const midLat = (b.minLat + b.maxLat) / 2;
  const cos = Math.max(0.01, Math.cos(toRad(midLat)));
  const dLon = dLat / cos;
  return {
    minLat: Math.max(-90, b.minLat - dLat),
    maxLat: Math.min(90, b.maxLat + dLat),
    minLon: Math.max(-180, b.minLon - dLon),
    maxLon: Math.min(180, b.maxLon + dLon),
  };
}

export function bboxArea(b: BBox): number {
  return (b.maxLat - b.minLat) * (b.maxLon - b.minLon);
}

/**
 * Snap a bbox out to a whole-degree-fraction grid. Used to key the Waze sweep
 * and the alert cache so nearby devices share cache entries instead of each
 * minting their own.
 */
export function snapBBox(b: BBox, step = 0.25): BBox {
  return {
    minLon: Math.floor(b.minLon / step) * step,
    minLat: Math.floor(b.minLat / step) * step,
    maxLon: Math.ceil(b.maxLon / step) * step,
    maxLat: Math.ceil(b.maxLat / step) * step,
  };
}

/** Split a bbox into tiles no larger than `step` degrees on a side. */
export function tileBBox(b: BBox, step = 0.5): BBox[] {
  const out: BBox[] = [];
  for (let lat = Math.floor(b.minLat / step) * step; lat < b.maxLat; lat += step) {
    for (let lon = Math.floor(b.minLon / step) * step; lon < b.maxLon; lon += step) {
      out.push({
        minLat: lat,
        maxLat: lat + step,
        minLon: lon,
        maxLon: lon + step,
      });
    }
  }
  return out;
}

/** Rough centroid of a GeoJSON geometry, or null if it has no usable coords. */
export function centroidOf(geometry: unknown): { lat: number; lon: number } | null {
  const coords: [number, number][] = [];
  const walk = (node: unknown): void => {
    if (!Array.isArray(node)) return;
    if (
      node.length >= 2 &&
      typeof node[0] === 'number' &&
      typeof node[1] === 'number'
    ) {
      coords.push([node[0], node[1]]);
      return;
    }
    for (const child of node) walk(child);
  };
  const g = geometry as { coordinates?: unknown } | null;
  if (!g || typeof g !== 'object') return null;
  walk(g.coordinates);
  if (coords.length === 0) return null;
  let sumLon = 0;
  let sumLat = 0;
  for (const [lon, lat] of coords) {
    sumLon += lon;
    sumLat += lat;
  }
  return { lon: sumLon / coords.length, lat: sumLat / coords.length };
}

/** Australia's rough bounding box, including the external territories we care about. */
export const AUSTRALIA: BBox = {
  minLon: 112.0,
  minLat: -44.0,
  maxLon: 154.0,
  maxLat: -9.0,
};

export function withinAustralia(lat: number, lon: number): boolean {
  return (
    lat >= AUSTRALIA.minLat &&
    lat <= AUSTRALIA.maxLat &&
    lon >= AUSTRALIA.minLon &&
    lon <= AUSTRALIA.maxLon
  );
}
