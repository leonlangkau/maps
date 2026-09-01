import { bboxArea, padBBox, parseBBox, snapBBox } from '../lib/geo';
import { problem } from '../lib/http';
import { queryAlerts, queryReports, touchRegion } from '../lib/store';
import type { Alert, Env } from '../lib/types';

/** Refuse absurd windows so one client cannot ask for the whole continent. */
const MAX_BBOX_AREA_DEG2 = 6;
const CACHE_TTL_S = 45;

export function toFeatureCollection(alerts: Alert[]) {
  return {
    type: 'FeatureCollection',
    features: alerts.map((a) => ({
      type: 'Feature',
      id: a.id,
      geometry: { type: 'Point', coordinates: [a.lon, a.lat] },
      properties: {
        id: a.id,
        source: a.source,
        kind: a.kind,
        headline: a.headline,
        detail: a.detail,
        road: a.road,
        bearing: a.bearing,
        severity: a.severity,
        startedAt: a.startedAt,
        updatedAt: a.updatedAt,
        expiresAt: a.expiresAt,
        confidence: a.confidence,
        polyline: a.polyline,
      },
    })),
  };
}

export async function handleAlerts(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
): Promise<Response> {
  const url = new URL(request.url);
  const bbox = parseBBox(url.searchParams.get('bbox'));
  if (!bbox) return problem(400, 'bbox=minLon,minLat,maxLon,maxLat is required');
  if (bboxArea(bbox) > MAX_BBOX_AREA_DEG2) return problem(400, 'bbox too large');

  const sinceRaw = url.searchParams.get('since');
  const since = sinceRaw ? Number(sinceRaw) : null;
  if (sinceRaw && (since === null || !Number.isFinite(since))) {
    return problem(400, 'since must be epoch millis');
  }

  const now = Date.now();

  // Remember where people are driving; the cron only polls Waze and TomTom for
  // these cells, so request volume tracks real use instead of map size.
  ctx.waitUntil(touchRegion(env, snapBBox(bbox, 0.25), now).catch(() => {}));

  // Look slightly beyond the visible window so a hazard just off-screen is
  // already loaded by the time the driver reaches it.
  const padded = padBBox(bbox, 3000);

  // Devices in the same area share a cache entry. Deltas skip the cache: they
  // are cheap, and a stale cursor would silently drop updates.
  const key = snapBBox(padded, 0.1);
  const cacheKey = `alerts:${key.minLon.toFixed(2)},${key.minLat.toFixed(2)}`;
  if (since === null) {
    const cached = await env.CACHE.get(cacheKey);
    if (cached) {
      return new Response(cached, {
        headers: {
          'content-type': 'application/json; charset=utf-8',
          'x-cache': 'hit',
        },
      });
    }
  }

  const [feed, community] = await Promise.all([
    queryAlerts(env, padded, since, now),
    queryReports(env, padded, now),
  ]);

  const merged = [...feed, ...community].sort(
    (a, b) => b.severity - a.severity || b.updatedAt - a.updatedAt,
  );
  const body = JSON.stringify({
    ...toFeatureCollection(merged),
    generatedAt: now,
  });

  if (since === null) {
    ctx.waitUntil(
      env.CACHE.put(cacheKey, body, { expirationTtl: CACHE_TTL_S }).catch(() => {}),
    );
  }

  return new Response(body, {
    headers: { 'content-type': 'application/json; charset=utf-8', 'x-cache': 'miss' },
  });
}
