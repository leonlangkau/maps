import { bboxArea, parseBBox } from '../lib/geo';
import { json, problem } from '../lib/http';
import { queryCameras } from '../lib/store';
import type { Env } from '../lib/types';

const MAX_BBOX_AREA_DEG2 = 20;

/**
 * Cameras change quarterly, not continuously, so the phone downloads the whole
 * bundle once and keeps it. This bbox endpoint exists for the map view and for
 * a phone that has not synced yet — the alerting path never depends on it,
 * because alerting has to work with no signal.
 */
export async function handleCameras(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const bbox = parseBBox(url.searchParams.get('bbox'));
  if (!bbox) return problem(400, 'bbox=minLon,minLat,maxLon,maxLat is required');
  if (bboxArea(bbox) > MAX_BBOX_AREA_DEG2) return problem(400, 'bbox too large');

  const cameras = await queryCameras(env, bbox);
  return json(
    { cameras, count: cameras.length },
    { headers: { 'cache-control': 'public, max-age=600' } },
  );
}

/** Version pointer: the app polls this and only re-downloads when it changes. */
export async function handleCameraVersion(env: Env): Promise<Response> {
  const version = (await env.CACHE.get('cameras:bundle-version')) ?? '0';
  return json(
    { version: Number(version), url: '/v1/cameras/bundle' },
    { headers: { 'cache-control': 'public, max-age=300' } },
  );
}

/** The full offline set, streamed straight out of R2. */
export async function handleCameraBundle(env: Env): Promise<Response> {
  const object = await env.TILES.get('bundles/cameras.json');
  if (!object) return problem(404, 'bundle not built yet');
  return new Response(object.body, {
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'public, max-age=3600',
      etag: object.httpEtag,
    },
  });
}
