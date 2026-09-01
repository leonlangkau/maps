import type { AlertKind } from '../lib/types';
import { asString } from './common';
import { geoJsonSource } from './geojson';

/**
 * Victoria is the one mainland feed that is not open-access: the Data Exchange
 * issues tokens on request (traffic_requests@vicroads.vic.gov.au) and rate
 * limits to roughly 3 calls a minute with a 10 minute cache, which our 2 minute
 * cron sits comfortably inside.
 *
 * The exact path moved when the legacy Disruptions API was retired, so it is a
 * variable rather than a constant: set VIC_API_URL alongside VIC_API_KEY and
 * this adapter picks it up without a redeploy.
 */
const DEFAULT_VIC_URL =
  'https://data-exchange.vicroads.vic.gov.au/opendata/disruptions/road/v1/all';

const KIND_BY_TYPE: Record<string, AlertKind> = {
  accident: 'crash',
  crash: 'crash',
  incident: 'crash',
  roadworks: 'roadwork',
  planned: 'roadwork',
  works: 'roadwork',
  flood: 'flood',
  flooding: 'flood',
  fire: 'fire',
  closure: 'closure',
  closed: 'closure',
  event: 'event',
  hazard: 'hazard',
};

export const vicSource = geoJsonSource({
  id: 'vic',
  label: 'VicRoads Data Exchange disruptions',
  state: 'VIC',
  url: (env) => {
    if (!env.VIC_API_KEY) return null;
    const base = (env as unknown as { VIC_API_URL?: string }).VIC_API_URL;
    return base && base.length > 0 ? base : DEFAULT_VIC_URL;
  },
  headers: (env) => ({ 'Ocp-Apim-Subscription-Key': env.VIC_API_KEY ?? '' }),
  idKeys: ['id', 'disruptionId', 'eventId', 'reference'],
  headlineKeys: ['description', 'title', 'name', 'eventType'],
  detailKeys: ['comment', 'advice', 'impact', 'details'],
  roadKeys: ['roadName', 'road_name', 'location', 'locality'],
  startKeys: ['startTime', 'start', 'fromDate'],
  kindOf: (props) => {
    const raw =
      asString(props['eventType']) ??
      asString(props['type']) ??
      asString(props['category']) ??
      '';
    return KIND_BY_TYPE[raw.toLowerCase()] ?? 'hazard';
  },
  skip: (props) => {
    const status = (asString(props['status']) ?? '').toLowerCase();
    return status === 'closed' || status === 'completed' || status === 'cancelled';
  },
});
