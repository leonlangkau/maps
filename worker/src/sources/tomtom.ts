import { snapBBox, withinAustralia } from '../lib/geo';
import { fetchWithTimeout } from '../lib/http';
import type { Alert, AlertKind, BBox, Env } from '../lib/types';
import { asEpoch, asNumber, asString, makeAlert, truncate, type Source } from './common';

/**
 * Live congestion and incidents from TomTom. The free tier allows 2,500
 * non-tile requests a day across all TomTom services, so this is bbox-scoped to
 * active regions and capped per run: at 4 boxes every 2 minutes we would use
 * 2,880 a day, so the cap is 3 and the cron is the 2-minute one.
 */

const ENDPOINT = 'https://api.tomtom.com/traffic/services/5/incidentDetails';
const MAX_BOXES_PER_RUN = 3;
const TTL_MS = 10 * 60 * 1000;

const FIELDS =
  '{incidents{type,geometry{type,coordinates},properties{iconCategory,' +
  'magnitudeOfDelay,events{description,code,iconCategory},startTime,endTime,' +
  'from,to,length,delay,roadNumbers}}}';

/** TomTom iconCategory codes, per their Traffic API v5 documentation. */
const KIND_BY_ICON: Record<number, AlertKind> = {
  1: 'crash',
  2: 'hazard',
  3: 'hazard',
  4: 'hazard',
  5: 'hazard',
  6: 'congestion',
  7: 'hazard',
  8: 'closure',
  9: 'roadwork',
  10: 'hazard',
  11: 'flood',
  14: 'stopped_vehicle',
};

interface TomTomIncident {
  geometry?: { type?: string; coordinates?: unknown };
  properties?: {
    iconCategory?: number;
    magnitudeOfDelay?: number;
    events?: Array<{ description?: string; code?: number }>;
    startTime?: string;
    endTime?: string;
    from?: string;
    to?: string;
    delay?: number;
    roadNumbers?: string[];
  };
}

function firstPoint(geometry: unknown): { lat: number; lon: number } | null {
  const coords = (geometry as { coordinates?: unknown } | null)?.coordinates;
  const walk = (node: unknown): [number, number] | null => {
    if (!Array.isArray(node)) return null;
    if (node.length >= 2 && typeof node[0] === 'number' && typeof node[1] === 'number') {
      return [node[0], node[1]];
    }
    for (const child of node) {
      const found = walk(child);
      if (found) return found;
    }
    return null;
  };
  const point = walk(coords);
  return point ? { lon: point[0], lat: point[1] } : null;
}

async function fetchBox(env: Env, box: BBox, now: number): Promise<Alert[]> {
  const bbox = `${box.minLon},${box.minLat},${box.maxLon},${box.maxLat}`;
  const url =
    `${ENDPOINT}?key=${encodeURIComponent(env.TOMTOM_API_KEY ?? '')}` +
    `&bbox=${bbox}&fields=${encodeURIComponent(FIELDS)}` +
    `&language=en-GB&timeValidityFilter=present`;

  const res = await fetchWithTimeout(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error(`tomtom HTTP ${res.status}`);

  const body = (await res.json()) as { incidents?: TomTomIncident[] };
  const out: Alert[] = [];

  for (const incident of body.incidents ?? []) {
    const props = incident.properties ?? {};
    const point = firstPoint(incident.geometry);
    if (!point || !withinAustralia(point.lat, point.lon)) continue;

    const icon = asNumber(props.iconCategory) ?? 0;
    const kind = KIND_BY_ICON[icon] ?? 'hazard';
    const description = props.events?.[0]?.description;
    const road = props.roadNumbers?.[0] ?? asString(props.from);

    // TomTom has no stable incident id in this projection, so derive one from
    // position and category. Same incident, same id, so upserts stay idempotent.
    const id = `tomtom:${point.lat.toFixed(5)},${point.lon.toFixed(5)}:${icon}`;
    const magnitude = asNumber(props.magnitudeOfDelay) ?? 0;

    out.push(
      makeAlert({
        id,
        source: 'tomtom',
        kind,
        lat: point.lat,
        lon: point.lon,
        headline: truncate(
          asString(description) ?? (kind === 'congestion' ? 'Slow traffic' : 'Traffic incident'),
          160,
        ),
        detail: props.to ? `Towards ${props.to}` : null,
        road: asString(road),
        severity: kind === 'closure' ? 3 : magnitude >= 3 ? 2 : magnitude >= 2 ? 1 : 0,
        startedAt: asEpoch(props.startTime),
        updatedAt: now,
        expiresAt: now + TTL_MS,
      }),
    );
  }
  return out;
}

export const tomtomSource: Source = {
  id: 'tomtom',
  label: 'TomTom Traffic incidents',
  enabled: (env) => Boolean(env.TOMTOM_API_KEY),
  async fetch(env, now, regions) {
    const boxes = new Map<string, BBox>();
    for (const region of regions) {
      const snapped = snapBBox(region, 0.25);
      boxes.set(`${snapped.minLon},${snapped.minLat}`, snapped);
      if (boxes.size >= MAX_BOXES_PER_RUN) break;
    }
    if (boxes.size === 0) return [];

    const settled = await Promise.allSettled(
      [...boxes.values()].map((box) => fetchBox(env, box, now)),
    );
    const alerts: Alert[] = [];
    let failures = 0;
    for (const result of settled) {
      if (result.status === 'fulfilled') alerts.push(...result.value);
      else failures += 1;
    }
    if (failures === settled.length) throw new Error('all TomTom requests failed');

    const unique = new Map<string, Alert>();
    for (const alert of alerts) unique.set(alert.id, alert);
    return [...unique.values()];
  },
};
