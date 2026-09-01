import { centroidOf, withinAustralia } from '../lib/geo';
import { fetchWithTimeout } from '../lib/http';
import type { Alert, AlertKind, Env, SourceId } from '../lib/types';
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

/**
 * Several jurisdictions publish plain GeoJSON with only the property names
 * differing (WA via ArcGIS, VIC via the Data Exchange, and the smaller states
 * when their feeds are wired up). One configurable adapter beats five files
 * that differ by a handful of string keys.
 */
export interface GeoJsonFeedConfig {
  id: SourceId;
  label: string;
  state: string;
  /** Returns null when the feed is not configured yet. */
  url(env: Env): string | null;
  headers?(env: Env): Record<string, string>;
  kindOf(props: Record<string, unknown>): AlertKind;
  headlineKeys: string[];
  detailKeys: string[];
  roadKeys: string[];
  idKeys: string[];
  startKeys?: string[];
  severityOf?(props: Record<string, unknown>, kind: AlertKind): number;
  /** Return true to drop a feature (already cleared, out of scope, ...). */
  skip?(props: Record<string, unknown>): boolean;
}

export function geoJsonSource(config: GeoJsonFeedConfig): Source {
  return {
    id: config.id,
    label: config.label,
    enabled: (env) => config.url(env) !== null && stateEnabled(env, config.state),
    async fetch(env, now) {
      const url = config.url(env);
      if (!url) return [];

      const res = await fetchWithTimeout(url, {
        headers: { Accept: 'application/json', ...(config.headers?.(env) ?? {}) },
      });
      if (!res.ok) throw new Error(`${config.id} HTTP ${res.status}`);

      const contentType = res.headers.get('content-type') ?? '';
      if (!contentType.includes('json')) {
        throw new Error(`${config.id} returned ${contentType || 'unknown type'}`);
      }

      const body = (await res.json()) as GeoJSONCollection;
      const out: Alert[] = [];

      for (const feature of body.features ?? []) {
        const props = feature.properties ?? {};
        if (config.skip?.(props)) continue;

        const centre = centroidOf(feature.geometry);
        if (!centre || !withinAustralia(centre.lat, centre.lon)) continue;

        const rawId = config.idKeys
          .map((k) => props[k])
          .find((v) => v !== undefined && v !== null) ?? feature.id;
        if (rawId === undefined || rawId === null) continue;

        const kind = config.kindOf(props);
        const headline = pick(props, ...config.headlineKeys) ?? config.label;

        out.push(
          makeAlert({
            id: `${config.id}:${String(rawId)}`,
            source: config.id,
            kind,
            lat: centre.lat,
            lon: centre.lon,
            headline: truncate(headline, 160),
            detail: pick(props, ...config.detailKeys),
            road: pick(props, ...config.roadKeys),
            severity: clampSeverity(
              config.severityOf?.(props, kind) ?? (kind === 'roadwork' ? 0 : 1),
            ),
            startedAt: config.startKeys
              ? config.startKeys.map((k) => asEpoch(props[k])).find((v) => v !== null) ?? null
              : null,
            updatedAt: now,
            expiresAt: now + FEED_TTL_MS,
            polyline: lineOf(feature.geometry),
          }),
        );
      }
      return out;
    },
  };
}
