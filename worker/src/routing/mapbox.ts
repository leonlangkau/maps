import { fetchWithTimeout } from '../lib/http';
import type {
  PlaceResult,
  RouteLeg,
  RouteOption,
  RouteResult,
  RouteStep,
  RoutingProvider,
  Waypoint,
} from './types';

interface MapboxStep {
  distance?: number;
  duration?: number;
  name?: string;
  maneuver?: { instruction?: string; modifier?: string };
}

interface MapboxLeg {
  distance?: number;
  duration?: number;
  steps?: MapboxStep[];
}

interface MapboxRoute {
  distance?: number;
  duration?: number;
  geometry?: string;
  legs?: MapboxLeg[];
}

function toStep(step: MapboxStep): RouteStep {
  return {
    instruction: step.maneuver?.instruction ?? '',
    distanceM: step.distance ?? 0,
    durationS: step.duration ?? 0,
    modifier: step.maneuver?.modifier ?? null,
    name: step.name && step.name.length > 0 ? step.name : null,
  };
}

function toLeg(leg: MapboxLeg): RouteLeg {
  return {
    distanceM: leg.distance ?? 0,
    durationS: leg.duration ?? 0,
    steps: (leg.steps ?? []).map(toStep),
  };
}

function toRoute(route: MapboxRoute): RouteOption {
  return {
    distanceM: route.distance ?? 0,
    durationS: route.duration ?? 0,
    geometry: route.geometry ?? '',
    legs: (route.legs ?? []).map(toLeg),
  };
}

export function mapboxProvider(token: string): RoutingProvider {
  return {
    id: 'mapbox',

    async route(from: Waypoint, to: Waypoint) {
      const coords = `${from.lon},${from.lat};${to.lon},${to.lat}`;
      const url =
        `https://api.mapbox.com/directions/v5/mapbox/driving-traffic/${coords}` +
        `?access_token=${encodeURIComponent(token)}` +
        `&geometries=polyline6&overview=full&steps=true&alternatives=true` +
        `&annotations=congestion,duration&language=en&voice_units=metric`;

      const res = await fetchWithTimeout(url, { headers: { Accept: 'application/json' } }, 10000);
      if (!res.ok) {
        throw new Error(`mapbox directions HTTP ${res.status}`);
      }
      const body = (await res.json()) as { routes?: MapboxRoute[] };
      return {
        provider: 'mapbox',
        routes: (body.routes ?? []).map(toRoute),
      } satisfies RouteResult;
    },

    async search(query: string, near: Waypoint | null) {
      const proximity = near ? `&proximity=${near.lon},${near.lat}` : '';
      const url =
        `https://api.mapbox.com/search/geocode/v6/forward` +
        `?q=${encodeURIComponent(query)}&country=au&limit=8&language=en` +
        `${proximity}&access_token=${encodeURIComponent(token)}`;

      const res = await fetchWithTimeout(url, { headers: { Accept: 'application/json' } }, 8000);
      if (!res.ok) throw new Error(`mapbox geocode HTTP ${res.status}`);

      const body = (await res.json()) as {
        features?: Array<{
          properties?: {
            name?: string;
            full_address?: string;
            place_formatted?: string;
            coordinates?: { latitude?: number; longitude?: number };
          };
        }>;
      };

      const out: PlaceResult[] = [];
      for (const feature of body.features ?? []) {
        const props = feature.properties ?? {};
        const lat = props.coordinates?.latitude;
        const lon = props.coordinates?.longitude;
        if (typeof lat !== 'number' || typeof lon !== 'number') continue;
        out.push({
          name: props.name ?? props.full_address ?? 'Unnamed place',
          address: props.full_address ?? props.place_formatted ?? null,
          lat,
          lon,
        });
      }
      return out;
    },
  };
}
