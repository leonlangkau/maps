import { fetchWithTimeout } from '../lib/http';
import type {
  PlaceResult,
  RouteOption,
  RouteResult,
  RoutingProvider,
  Waypoint,
} from './types';

/**
 * Self-hosted Valhalla, for if the Mapbox arrangement stops suiting us. Valhalla
 * is the standard open-source escape hatch: one box, the Australia OSM extract,
 * unlimited routes, and no clause about which basemap the result is drawn on.
 *
 * Set VALHALLA_URL to the base of a Valhalla instance to switch over. Geocoding
 * is not part of Valhalla, so search falls back to Nominatim or Photon; wire
 * that up alongside it when the time comes.
 */

interface ValhallaManeuver {
  instruction?: string;
  length?: number;
  time?: number;
  street_names?: string[];
}

interface ValhallaLeg {
  shape?: string;
  summary?: { length?: number; time?: number };
  maneuvers?: ValhallaManeuver[];
}

export function valhallaProvider(baseUrl: string): RoutingProvider {
  return {
    id: 'valhalla',

    async route(from: Waypoint, to: Waypoint) {
      const body = {
        locations: [
          { lat: from.lat, lon: from.lon },
          { lat: to.lat, lon: to.lon },
        ],
        costing: 'auto',
        directions_options: { units: 'kilometers', language: 'en-AU' },
      };

      const res = await fetchWithTimeout(
        `${baseUrl.replace(/\/$/, '')}/route`,
        {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(body),
        },
        10000,
      );
      if (!res.ok) throw new Error(`valhalla HTTP ${res.status}`);

      const parsed = (await res.json()) as {
        trip?: { legs?: ValhallaLeg[]; summary?: { length?: number; time?: number } };
      };
      const trip = parsed.trip;
      if (!trip) return { provider: 'valhalla', routes: [] };

      // Valhalla reports leg length in kilometres when units are kilometers.
      const route: RouteOption = {
        distanceM: (trip.summary?.length ?? 0) * 1000,
        durationS: trip.summary?.time ?? 0,
        geometry: trip.legs?.[0]?.shape ?? '',
        legs: (trip.legs ?? []).map((leg) => ({
          distanceM: (leg.summary?.length ?? 0) * 1000,
          durationS: leg.summary?.time ?? 0,
          steps: (leg.maneuvers ?? []).map((m) => ({
            instruction: m.instruction ?? '',
            distanceM: (m.length ?? 0) * 1000,
            durationS: m.time ?? 0,
            modifier: null,
            name: m.street_names?.[0] ?? null,
          })),
        })),
      };

      return { provider: 'valhalla', routes: [route] } satisfies RouteResult;
    },

    async search(): Promise<PlaceResult[]> {
      throw new Error('valhalla has no geocoder; configure a search provider');
    },
  };
}
