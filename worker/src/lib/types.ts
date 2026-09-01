/** Every hazard the app can warn about, from any source, reduced to one shape. */
export type AlertKind =
  | 'crash'
  | 'hazard'
  | 'roadwork'
  | 'flood'
  | 'fire'
  | 'congestion'
  | 'closure'
  | 'event'
  | 'police'
  | 'mobile_camera'
  | 'object_on_road'
  | 'stopped_vehicle'
  | 'alpine';

export type SourceId =
  | 'nsw' | 'qld' | 'vic' | 'wa' | 'sa' | 'tas' | 'nt'
  | 'waze' | 'tomtom' | 'community';

/**
 * Severity drives how loudly the app warns:
 *   0 informational  - shown on the map only
 *   1 minor          - chime at close range
 *   2 major          - voice alert
 *   3 critical       - voice alert at long range (closure, crash blocking lanes)
 */
export type Severity = 0 | 1 | 2 | 3;

export interface Alert {
  id: string;
  source: SourceId;
  kind: AlertKind;
  lat: number;
  lon: number;
  headline: string;
  detail: string | null;
  road: string | null;
  /** Compass bearing in degrees the hazard applies to, or null for both directions. */
  bearing: number | null;
  severity: Severity;
  startedAt: number | null;
  updatedAt: number;
  expiresAt: number;
  /** 0..1. Government feeds are 1. Community reports climb with confirmations. */
  confidence: number;
  /** Optional [lon, lat] line for hazards that span a stretch of road. */
  polyline: [number, number][] | null;
}

export type CameraKind =
  | 'fixed_speed'
  | 'red_light'
  | 'red_light_speed'
  | 'average_speed_start'
  | 'average_speed_end'
  | 'mobile_zone'
  | 'trailer'
  | 'unknown';

export interface Camera {
  id: string;
  source: string;
  kind: CameraKind;
  lat: number;
  lon: number;
  road: string | null;
  suburb: string | null;
  state: string;
  speedLimit: number | null;
  /** Bearing the camera faces, or null if it catches both directions. */
  bearing: number | null;
  verifiedAt: number;
}

export interface BBox {
  minLon: number;
  minLat: number;
  maxLon: number;
  maxLat: number;
}

export interface Env {
  DB: D1Database;
  CACHE: KVNamespace;
  TILES: R2Bucket;

  WAZE_ENABLED: string;
  ACTIVE_STATES: string;
  PMTILES_KEY: string;

  APP_TOKEN: string;
  MAPBOX_TOKEN: string;
  NSW_API_KEY?: string;
  QLD_API_KEY?: string;
  VIC_API_KEY?: string;
  TOMTOM_API_KEY?: string;
}
