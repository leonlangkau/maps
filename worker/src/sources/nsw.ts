import { centroidOf, withinAustralia } from '../lib/geo';
import { fetchWithTimeout } from '../lib/http';
import type { Alert, AlertKind, Env } from '../lib/types';
import {
  FEED_TTL_MS,
  asEpoch,
  clampSeverity,
  lineOf,
  makeAlert,
  pick,
  stateEnabled,
  truncate,
  type GeoJSONCollection,
  type Source,
} from './common';

const BASE = 'https://api.transport.nsw.gov.au/v1/live/hazards';

/** Categories the Live Traffic Hazards API exposes, mapped to our alert kinds. */
const CATEGORIES: Array<{ path: string; kind: AlertKind }> = [
  { path: 'incident', kind: 'crash' },
  { path: 'roadwork', kind: 'roadwork' },
  { path: 'fire', kind: 'fire' },
  { path: 'flood', kind: 'flood' },
  { path: 'majorevent', kind: 'event' },
  { path: 'alpine', kind: 'alpine' },
];

interface NswRoad {
  mainStreet?: unknown;
  crossStreet?: unknown;
  suburb?: unknown;
  delay?: unknown;
  impactedLanes?: unknown;
}

function roadName(props: Record<string, unknown>): string | null {
  const roads = props['roads'];
  if (Array.isArray(roads) && roads.length > 0) {
    const first = roads[0] as NswRoad;
    const main = typeof first.mainStreet === 'string' ? first.mainStreet : null;
    const suburb = typeof first.suburb === 'string' ? first.suburb : null;
    if (main && suburb) return `${main}, ${suburb}`;
    if (main) return main;
    if (suburb) return suburb;
  }
  return pick(props, 'mainStreet', 'displayName');
}

function severityOf(props: Record<string, unknown>, kind: AlertKind): number {
  if (props['isMajor'] === true) return 3;
  if (kind === 'roadwork') return 0;
  if (props['impactingNetwork'] === true) return 2;
  return 1;
}

async function fetchCategory(
  env: Env,
  category: { path: string; kind: AlertKind },
  now: number,
): Promise<Alert[]> {
  const res = await fetchWithTimeout(`${BASE}/${category.path}/open`, {
    headers: {
      Authorization: `apikey ${env.NSW_API_KEY}`,
      Accept: 'application/json',
    },
  });
  if (!res.ok) {
    throw new Error(`NSW ${category.path} HTTP ${res.status}`);
  }
  const body = (await res.json()) as GeoJSONCollection;
  const out: Alert[] = [];

  for (const feature of body.features ?? []) {
    const props = feature.properties ?? {};
    // The feed keeps closed hazards in some categories; skip anything resolved.
    if (props['isEnded'] === true) continue;

    const centre = centroidOf(feature.geometry);
    if (!centre || !withinAustralia(centre.lat, centre.lon)) continue;

    const headline =
      pick(props, 'headline', 'displayName', 'adviceA') ??
      `${category.path} on the NSW network`;
    const detail = pick(props, 'otherAdvice', 'additionalInfo', 'adviceB');
    const id = feature.id ?? props['id'];
    if (id === undefined || id === null) continue;

    out.push(
      makeAlert({
        id: `nsw:${category.path}:${id}`,
        source: 'nsw',
        kind: category.kind,
        lat: centre.lat,
        lon: centre.lon,
        headline: truncate(headline, 160),
        detail: detail ? truncate(detail) : null,
        road: roadName(props),
        severity: clampSeverity(severityOf(props, category.kind)),
        startedAt: asEpoch(props['created']),
        updatedAt: now,
        expiresAt: now + FEED_TTL_MS,
        polyline: lineOf(feature.geometry),
      }),
    );
  }
  return out;
}

export const nswSource: Source = {
  id: 'nsw',
  label: 'Transport for NSW Live Traffic',
  enabled: (env) => Boolean(env.NSW_API_KEY) && stateEnabled(env, 'NSW'),
  async fetch(env, now) {
    const batches = await Promise.allSettled(
      CATEGORIES.map((c) => fetchCategory(env, c, now)),
    );
    const alerts: Alert[] = [];
    const failures: string[] = [];
    for (const [i, result] of batches.entries()) {
      if (result.status === 'fulfilled') {
        alerts.push(...result.value);
      } else {
        failures.push(`${CATEGORIES[i]?.path}: ${String(result.reason)}`);
      }
    }
    // One dead category should not discard the five that worked, but a total
    // wipeout is a real outage and should surface as an error.
    if (alerts.length === 0 && failures.length > 0) {
      throw new Error(failures.join('; '));
    }
    return alerts;
  },
};
