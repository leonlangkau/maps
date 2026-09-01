import { withinAustralia } from '../lib/geo';
import { fetchWithTimeout } from '../lib/http';
import {
  allCameras,
  dropCameraSource,
  recordSourceStatus,
  upsertCameras,
} from '../lib/store';
import type { Camera, Env } from '../lib/types';
import { CAMERA_DATASETS, type CameraDataset } from './datasets';
import { LAT_COLUMNS, LON_COLUMNS, column, parseCsv } from './parse';

const CKAN_PAGE = 1000;

interface CkanResponse {
  success?: boolean;
  result?: { records?: Record<string, unknown>[]; total?: number };
}

/** Read every record from a CKAN datastore resource, paging until exhausted. */
async function readCkan(dataset: CameraDataset): Promise<Record<string, string>[]> {
  const out: Record<string, string>[] = [];
  let offset = 0;

  for (;;) {
    const url =
      `${dataset.portal}/api/3/action/datastore_search` +
      `?resource_id=${encodeURIComponent(dataset.resourceId)}` +
      `&limit=${CKAN_PAGE}&offset=${offset}`;

    const res = await fetchWithTimeout(url, { headers: { Accept: 'application/json' } }, 15000);
    if (!res.ok) throw new Error(`${dataset.source} HTTP ${res.status}`);

    const body = (await res.json()) as CkanResponse;
    if (body.success === false) throw new Error(`${dataset.source} CKAN rejected the query`);

    const records = body.result?.records ?? [];
    for (const record of records) {
      const flat: Record<string, string> = {};
      for (const [key, value] of Object.entries(record)) {
        flat[key] = value === null || value === undefined ? '' : String(value);
      }
      out.push(flat);
    }
    if (records.length < CKAN_PAGE) break;
    offset += CKAN_PAGE;
    if (offset > 50_000) break; // Guard against a portal that never stops paging.
  }
  return out;
}

/** Fallback for datasets published as a plain CSV file rather than a datastore. */
async function readCsvUrl(url: string): Promise<Record<string, string>[]> {
  const res = await fetchWithTimeout(url, { headers: { Accept: 'text/csv' } }, 15000);
  if (!res.ok) throw new Error(`camera CSV HTTP ${res.status}`);
  return parseCsv(await res.text());
}

function toCameras(
  dataset: CameraDataset,
  records: Record<string, string>[],
  now: number,
): Camera[] {
  const out: Camera[] = [];
  const seen = new Set<string>();

  for (const [index, record] of records.entries()) {
    const lat = Number(column(record, LAT_COLUMNS));
    const lon = Number(column(record, LON_COLUMNS));
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) continue;
    if (!withinAustralia(lat, lon)) continue;

    const road = column(record, dataset.roadColumns);
    const suburb = column(record, dataset.suburbColumns);
    const speedRaw = dataset.speedColumns
      ? column(record, dataset.speedColumns)
      : null;
    const speed = speedRaw ? Number(speedRaw.replace(/[^0-9]/g, '')) : NaN;

    // Position-derived id keeps re-imports idempotent even when the portal
    // renumbers its rows between quarterly updates.
    const id = `${dataset.source}:${lat.toFixed(5)},${lon.toFixed(5)}`;
    if (seen.has(id)) continue;
    seen.add(id);

    out.push({
      id,
      source: dataset.source,
      kind: dataset.kind,
      lat,
      lon,
      road: road ?? null,
      suburb: suburb ?? null,
      state: dataset.state,
      speedLimit: Number.isFinite(speed) && speed > 0 ? speed : null,
      bearing: null,
      verifiedAt: now,
    });

    if (index > 100_000) break;
  }
  return out;
}

export interface IngestReport {
  source: string;
  ok: boolean;
  count: number;
  error: string | null;
}

export async function refreshCameras(env: Env, now: number): Promise<IngestReport[]> {
  const active = env.ACTIVE_STATES.split(',').map((s) => s.trim().toUpperCase());
  const reports: IngestReport[] = [];

  for (const dataset of CAMERA_DATASETS) {
    if (!active.includes(dataset.state)) continue;
    if (!dataset.resourceId) {
      reports.push({
        source: dataset.source,
        ok: false,
        count: 0,
        error: 'no resource id configured — see worker/src/cameras/datasets.ts',
      });
      continue;
    }

    try {
      const records = await readCkan(dataset);
      const cameras = toCameras(dataset, records, now);

      // Only replace the existing set once the new one parsed successfully, so a
      // portal outage cannot wipe cameras the app is relying on.
      if (cameras.length > 0) {
        await dropCameraSource(env, dataset.source);
        await upsertCameras(env, cameras);
      }

      await recordSourceStatus(env, `cameras:${dataset.source}`, true, cameras.length, null, now);
      reports.push({ source: dataset.source, ok: true, count: cameras.length, error: null });
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      await recordSourceStatus(env, `cameras:${dataset.source}`, false, 0, message, now);
      reports.push({ source: dataset.source, ok: false, count: 0, error: message });
    }
  }

  await rebuildBundle(env, now);
  return reports;
}

/**
 * The offline bundle: one gzipped JSON of every camera, written to R2 and
 * fetched by the phone on a version change. Cameras must work with no signal,
 * so the app never depends on a live query for them.
 */
export async function rebuildBundle(env: Env, now: number): Promise<number> {
  const cameras = await allCameras(env);
  const payload = JSON.stringify({
    version: now,
    count: cameras.length,
    cameras,
  });

  await env.TILES.put('bundles/cameras.json', payload, {
    httpMetadata: { contentType: 'application/json', cacheControl: 'public, max-age=3600' },
  });
  await env.CACHE.put('cameras:bundle-version', String(now));
  return cameras.length;
}

export { readCsvUrl };
