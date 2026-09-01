import type { Alert, AlertKind, BBox, Env, Severity, SourceId } from '../lib/types';

export interface Source {
  id: SourceId;
  /** Human name for the /v1/health readout. */
  label: string;
  /**
   * True when this source is queried per bounding box rather than statewide.
   * Such a source returning nothing means "nobody was driving there", not "the
   * road is clear", so its rows must expire on their TTL rather than be pruned.
   */
  bboxScoped?: boolean;
  /** False when a required key is missing or the state is not in ACTIVE_STATES. */
  enabled(env: Env): boolean;
  /**
   * `regions` are the bounding boxes devices have polled in the last hour.
   * Statewide feeds ignore it; bbox-scoped feeds (Waze, TomTom) use it so we
   * only poll where somebody is actually driving.
   */
  fetch(env: Env, now: number, regions: BBox[]): Promise<Alert[]>;
}

/** Government feeds re-assert live incidents every poll, so a short TTL is safe. */
export const FEED_TTL_MS = 20 * 60 * 1000;
export const WAZE_TTL_MS = 12 * 60 * 1000;

export function stateEnabled(env: Env, code: string): boolean {
  return env.ACTIVE_STATES.split(',')
    .map((s) => s.trim().toUpperCase())
    .includes(code);
}

export function clampSeverity(n: number): Severity {
  return Math.max(0, Math.min(3, Math.round(n))) as Severity;
}

export function asString(v: unknown): string | null {
  if (typeof v === 'string' && v.trim() !== '') return v.trim();
  if (typeof v === 'number') return String(v);
  return null;
}

export function asNumber(v: unknown): number | null {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string') {
    const n = Number(v);
    if (Number.isFinite(n)) return n;
  }
  return null;
}

/** Parse an ISO timestamp or epoch value into epoch millis. */
export function asEpoch(v: unknown): number | null {
  if (typeof v === 'number' && Number.isFinite(v)) {
    // Heuristic: anything below year 2001 in millis is really seconds.
    return v < 1_000_000_000_000 ? v * 1000 : v;
  }
  if (typeof v === 'string') {
    const t = Date.parse(v);
    if (Number.isFinite(t)) return t;
  }
  return null;
}

/** First non-empty string from a list of candidate property keys. */
export function pick(
  props: Record<string, unknown>,
  ...keys: string[]
): string | null {
  for (const k of keys) {
    const v = asString(props[k]);
    if (v) return v;
  }
  return null;
}

export function truncate(s: string, max = 400): string {
  return s.length <= max ? s : `${s.slice(0, max - 1)}…`;
}

export interface GeoJSONFeature {
  type?: string;
  id?: string | number;
  geometry?: unknown;
  properties?: Record<string, unknown> | null;
}

export interface GeoJSONCollection {
  type?: string;
  features?: GeoJSONFeature[];
}

/** Pull an ordered [lon, lat] line out of a geometry, if it has one. */
export function lineOf(geometry: unknown, limit = 200): [number, number][] | null {
  const g = geometry as { type?: string; coordinates?: unknown } | null;
  if (!g || typeof g !== 'object') return null;
  const out: [number, number][] = [];
  const walkLine = (node: unknown): void => {
    if (!Array.isArray(node) || out.length >= limit) return;
    if (
      node.length >= 2 &&
      typeof node[0] === 'number' &&
      typeof node[1] === 'number'
    ) {
      out.push([node[0], node[1]]);
      return;
    }
    for (const child of node) walkLine(child);
  };
  if (g.type === 'LineString' || g.type === 'MultiLineString') {
    walkLine(g.coordinates);
  } else if (g.type === 'GeometryCollection') {
    const geoms = (g as { geometries?: unknown[] }).geometries ?? [];
    for (const child of geoms) {
      const nested = lineOf(child, limit - out.length);
      if (nested) out.push(...nested);
    }
  }
  return out.length >= 2 ? out : null;
}

export function makeAlert(
  base: Partial<Alert> & {
    id: string;
    source: SourceId;
    kind: AlertKind;
    lat: number;
    lon: number;
    headline: string;
    updatedAt: number;
    expiresAt: number;
  },
): Alert {
  return {
    detail: null,
    road: null,
    bearing: null,
    severity: 1,
    startedAt: null,
    confidence: 1,
    polyline: null,
    ...base,
  };
}
