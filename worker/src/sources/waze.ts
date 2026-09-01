import { snapBBox, withinAustralia } from '../lib/geo';
import { fetchWithTimeout } from '../lib/http';
import type { Alert, AlertKind, BBox, Env } from '../lib/types';
import { WAZE_TTL_MS, asNumber, asString, makeAlert, truncate, type Source } from './common';

/**
 * The crowd-sourced layer. This reads Waze's public live-map endpoint, which is
 * not a documented or supported API: it can change shape without notice and
 * Waze may rate-limit or block the caller. Two consequences are designed for
 * here rather than discovered in traffic:
 *
 *   1. We only poll bounding boxes a device has actually asked about in the
 *      last hour, capped per run. No continent-wide crawling.
 *   2. Repeated failures trip a circuit breaker held in KV, so a block degrades
 *      to "government feeds only" instead of hammering a closed door.
 *
 * Keep WAZE_ENABLED false for anything you intend to put in front of the public.
 * See docs/waze-layer.md.
 */

const ENDPOINT = 'https://www.waze.com/live-map/api/georss';
const MAX_BOXES_PER_RUN = 6;
const BREAKER_KEY = 'waze:blocked-until';
const BREAKER_MS = 30 * 60 * 1000;

const KIND_BY_TYPE: Record<string, AlertKind> = {
  POLICE: 'police',
  ACCIDENT: 'crash',
  JAM: 'congestion',
  ROAD_CLOSED: 'closure',
  HAZARD: 'hazard',
  WEATHERHAZARD: 'hazard',
};

const KIND_BY_SUBTYPE: Record<string, AlertKind> = {
  HAZARD_ON_ROAD_OBJECT: 'object_on_road',
  HAZARD_ON_ROAD_CAR_STOPPED: 'stopped_vehicle',
  HAZARD_ON_SHOULDER_CAR_STOPPED: 'stopped_vehicle',
  HAZARD_WEATHER_FLOOD: 'flood',
  HAZARD_WEATHER_FOG: 'hazard',
  POLICE_HIDING: 'police',
  ROAD_CLOSED_EVENT: 'closure',
  ROAD_CLOSED_CONSTRUCTION: 'roadwork',
  ROAD_CLOSED_HAZARD: 'closure',
};

interface WazeAlert {
  uuid?: string;
  type?: string;
  subtype?: string;
  street?: string;
  city?: string;
  location?: { x?: number; y?: number };
  pubMillis?: number;
  reportRating?: number;
  confidence?: number;
  reliability?: number;
  nThumbsUp?: number;
}

interface WazeResponse {
  alerts?: WazeAlert[];
}

function headlineFor(kind: AlertKind, a: WazeAlert): string {
  const where = asString(a.street) ?? asString(a.city);
  const label: Record<string, string> = {
    police: 'Police reported',
    crash: 'Crash reported',
    congestion: 'Heavy traffic',
    closure: 'Road closed',
    hazard: 'Hazard reported',
    object_on_road: 'Object on road',
    stopped_vehicle: 'Stopped vehicle',
    flood: 'Flooding reported',
    roadwork: 'Roadworks',
  };
  const base = label[kind] ?? 'Reported hazard';
  return where ? `${base} — ${where}` : base;
}

/**
 * Waze exposes reliability and confidence on 0..10 scales. Blend them into our
 * 0..1 so a single unconfirmed ping never reaches voice-alert severity.
 */
function confidenceOf(a: WazeAlert): number {
  const reliability = asNumber(a.reliability) ?? 5;
  const confidence = asNumber(a.confidence) ?? 0;
  const blended = (reliability / 10) * 0.6 + (confidence / 10) * 0.4;
  return Math.max(0.2, Math.min(1, blended));
}

async function fetchBox(box: BBox, now: number): Promise<Alert[]> {
  const url =
    `${ENDPOINT}?top=${box.maxLat}&bottom=${box.minLat}` +
    `&left=${box.minLon}&right=${box.maxLon}&env=row&types=alerts`;

  const res = await fetchWithTimeout(url, {
    headers: {
      Accept: 'application/json',
      'User-Agent': 'radar-au/0.1 (personal use)',
      Referer: 'https://www.waze.com/live-map',
    },
  });
  if (!res.ok) throw new Error(`waze HTTP ${res.status}`);

  const contentType = res.headers.get('content-type') ?? '';
  if (!contentType.includes('json')) {
    // A challenge page or redirect: treat as a block, not as an empty area.
    throw new Error(`waze returned ${contentType || 'non-JSON'}`);
  }

  const body = (await res.json()) as WazeResponse;
  const out: Alert[] = [];

  for (const alert of body.alerts ?? []) {
    const type = asString(alert.type)?.toUpperCase() ?? '';
    if (type === 'CHIT_CHAT' || type === '') continue;

    const subtype = asString(alert.subtype)?.toUpperCase() ?? '';
    const kind = KIND_BY_SUBTYPE[subtype] ?? KIND_BY_TYPE[type];
    if (!kind) continue;

    const lon = asNumber(alert.location?.x);
    const lat = asNumber(alert.location?.y);
    if (lat === null || lon === null || !withinAustralia(lat, lon)) continue;

    const uuid = asString(alert.uuid);
    if (!uuid) continue;

    const confidence = confidenceOf(alert);
    out.push(
      makeAlert({
        id: `waze:${uuid}`,
        source: 'waze',
        kind,
        lat,
        lon,
        headline: truncate(headlineFor(kind, alert), 160),
        road: asString(alert.street),
        // Crowd reports stay at chime level until they are well corroborated.
        severity: kind === 'closure' ? 3 : confidence >= 0.7 ? 2 : 1,
        startedAt: asNumber(alert.pubMillis),
        updatedAt: now,
        expiresAt: now + WAZE_TTL_MS,
        confidence,
      }),
    );
  }
  return out;
}

export const wazeSource: Source = {
  id: 'waze',
  label: 'Waze live map (unofficial)',
  enabled: (env) => env.WAZE_ENABLED === 'true',
  async fetch(env, now, regions) {
    const blockedUntil = Number((await env.CACHE.get(BREAKER_KEY)) ?? 0);
    if (blockedUntil > now) {
      throw new Error(
        `circuit breaker open for ${Math.round((blockedUntil - now) / 1000)}s`,
      );
    }

    // Snap and de-duplicate so two devices in the same suburb cost one request.
    const boxes = new Map<string, BBox>();
    for (const region of regions) {
      const snapped = snapBBox(region, 0.25);
      boxes.set(`${snapped.minLon},${snapped.minLat}`, snapped);
      if (boxes.size >= MAX_BOXES_PER_RUN) break;
    }
    if (boxes.size === 0) return [];

    const settled = await Promise.allSettled(
      [...boxes.values()].map((box) => fetchBox(box, now)),
    );

    const alerts: Alert[] = [];
    let failures = 0;
    for (const result of settled) {
      if (result.status === 'fulfilled') alerts.push(...result.value);
      else failures += 1;
    }

    // Every box failing is a block, not bad luck. Back off rather than retry.
    if (failures === settled.length) {
      await env.CACHE.put(BREAKER_KEY, String(now + BREAKER_MS), {
        expirationTtl: Math.ceil(BREAKER_MS / 1000),
      });
      throw new Error(`all ${failures} Waze requests failed; backing off`);
    }

    // De-duplicate: overlapping boxes return the same uuid more than once.
    const unique = new Map<string, Alert>();
    for (const alert of alerts) unique.set(alert.id, alert);
    return [...unique.values()];
  },
};
