import { centroidOf, withinAustralia } from '../lib/geo';
import { fetchWithTimeout } from '../lib/http';
import type { Alert, AlertKind, Env } from '../lib/types';
import {
  FEED_TTL_MS,
  asEpoch,
  asString,
  clampSeverity,
  lineOf,
  makeAlert,
  pick,
  stateEnabled,
  truncate,
  type GeoJSONCollection,
  type Source,
} from './common';

const ENDPOINT = 'https://api.qldtraffic.qld.gov.au/v2/events';

const KIND_BY_EVENT: Record<string, AlertKind> = {
  crash: 'crash',
  hazard: 'hazard',
  congestion: 'congestion',
  roadworks: 'roadwork',
  flooding: 'flood',
  'special event': 'event',
  'special_event': 'event',
};

const SEVERITY_BY_PRIORITY: Record<string, number> = {
  lowest: 0,
  low: 1,
  medium: 2,
  high: 3,
  highest: 3,
};

function roadOf(props: Record<string, unknown>): string | null {
  const summary = props['road_summary'];
  if (summary && typeof summary === 'object') {
    const s = summary as Record<string, unknown>;
    const name = asString(s['road_name']);
    const locality = asString(s['locality']);
    if (name && locality) return `${name}, ${locality}`;
    if (name) return name;
    if (locality) return locality;
  }
  return null;
}

function endOf(props: Record<string, unknown>): number | null {
  const duration = props['duration'];
  if (duration && typeof duration === 'object') {
    return asEpoch((duration as Record<string, unknown>)['end']);
  }
  return null;
}

function startOf(props: Record<string, unknown>): number | null {
  const duration = props['duration'];
  if (duration && typeof duration === 'object') {
    return asEpoch((duration as Record<string, unknown>)['start']);
  }
  return null;
}

export const qldSource: Source = {
  id: 'qld',
  label: 'QLDTraffic (131940)',
  enabled: (env) => Boolean(env.QLD_API_KEY) && stateEnabled(env, 'QLD'),
  async fetch(env, now) {
    const url = `${ENDPOINT}?apikey=${encodeURIComponent(env.QLD_API_KEY ?? '')}`;
    const res = await fetchWithTimeout(url, { headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error(`QLD HTTP ${res.status}`);

    const body = (await res.json()) as GeoJSONCollection;
    const out: Alert[] = [];

    for (const feature of body.features ?? []) {
      const props = feature.properties ?? {};
      const status = asString(props['status'])?.toLowerCase();
      if (status === 'cleared' || status === 'closed') continue;

      const centre = centroidOf(feature.geometry);
      if (!centre || !withinAustralia(centre.lat, centre.lon)) continue;

      const eventType = asString(props['event_type'])?.toLowerCase() ?? '';
      const kind = KIND_BY_EVENT[eventType] ?? 'hazard';
      const priority = asString(props['event_priority'])?.toLowerCase() ?? 'low';
      const id = props['id'] ?? feature.id;
      if (id === undefined || id === null) continue;

      const headline =
        pick(props, 'description', 'event_subtype', 'event_type') ??
        'Queensland traffic event';

      // The feed's own end time beats our default TTL when it is sooner.
      const declaredEnd = endOf(props);
      const expiry = declaredEnd && declaredEnd > now
        ? Math.min(declaredEnd, now + FEED_TTL_MS)
        : now + FEED_TTL_MS;

      out.push(
        makeAlert({
          id: `qld:${id}`,
          source: 'qld',
          kind,
          lat: centre.lat,
          lon: centre.lon,
          headline: truncate(headline, 160),
          detail: pick(props, 'advice', 'information'),
          road: roadOf(props),
          severity: clampSeverity(
            kind === 'roadwork' ? 0 : SEVERITY_BY_PRIORITY[priority] ?? 1,
          ),
          startedAt: startOf(props),
          updatedAt: now,
          expiresAt: expiry,
          polyline: lineOf(feature.geometry),
        }),
      );
    }
    return out;
  },
};
