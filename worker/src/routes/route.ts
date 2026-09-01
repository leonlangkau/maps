import { json, problem } from '../lib/http';
import type { Env } from '../lib/types';
import { mapboxProvider } from '../routing/mapbox';
import { valhallaProvider } from '../routing/valhalla';
import type { RoutingProvider, Waypoint } from '../routing/types';

/**
 * The routing provider lives behind this one function so the apps never learn
 * which vendor is in use. Set VALHALLA_URL to move off Mapbox without touching
 * either app.
 */
function providerFor(env: Env): RoutingProvider | null {
  const valhalla = (env as unknown as { VALHALLA_URL?: string }).VALHALLA_URL;
  if (valhalla) return valhallaProvider(valhalla);
  if (env.MAPBOX_TOKEN) return mapboxProvider(env.MAPBOX_TOKEN);
  return null;
}

function parsePoint(raw: string | null): Waypoint | null {
  if (!raw) return null;
  const parts = raw.split(',').map(Number);
  if (parts.length !== 2) return null;
  const [lon, lat] = parts as [number, number];
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
  return { lat, lon };
}

export async function handleRoute(request: Request, env: Env): Promise<Response> {
  const provider = providerFor(env);
  if (!provider) return problem(503, 'no routing provider configured');

  const url = new URL(request.url);
  const from = parsePoint(url.searchParams.get('from'));
  const to = parsePoint(url.searchParams.get('to'));
  if (!from || !to) return problem(400, 'from and to must be lon,lat pairs');

  try {
    const result = await provider.route(from, to);
    return json(result, { headers: { 'cache-control': 'no-store' } });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return problem(502, `routing failed: ${message}`);
  }
}

export async function handleSearch(request: Request, env: Env): Promise<Response> {
  const provider = providerFor(env);
  if (!provider) return problem(503, 'no search provider configured');

  const url = new URL(request.url);
  const query = url.searchParams.get('q');
  if (!query || query.trim().length < 2) return problem(400, 'q must be at least 2 characters');

  const near = parsePoint(url.searchParams.get('near'));

  try {
    const places = await provider.search(query.trim(), near);
    return json({ places }, { headers: { 'cache-control': 'public, max-age=120' } });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return problem(502, `search failed: ${message}`);
  }
}
