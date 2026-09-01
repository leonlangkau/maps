import type { Alert, AlertKind, BBox, Camera, Env, Severity, SourceId } from './types';

/** D1 caps bound parameters per statement, so writes go out in chunks. */
const CHUNK = 40;

function chunked<T>(items: T[], size = CHUNK): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

interface AlertRow {
  id: string;
  source: string;
  kind: string;
  lat: number;
  lon: number;
  headline: string;
  detail: string | null;
  road: string | null;
  bearing: number | null;
  severity: number;
  started_at: number | null;
  updated_at: number;
  expires_at: number;
  confidence: number;
  polyline: string | null;
}

function rowToAlert(r: AlertRow): Alert {
  let polyline: [number, number][] | null = null;
  if (r.polyline) {
    try {
      polyline = JSON.parse(r.polyline) as [number, number][];
    } catch {
      polyline = null;
    }
  }
  return {
    id: r.id,
    source: r.source as SourceId,
    kind: r.kind as AlertKind,
    lat: r.lat,
    lon: r.lon,
    headline: r.headline,
    detail: r.detail,
    road: r.road,
    bearing: r.bearing,
    severity: Math.max(0, Math.min(3, Math.round(r.severity))) as Severity,
    startedAt: r.started_at,
    updatedAt: r.updated_at,
    expiresAt: r.expires_at,
    confidence: r.confidence,
    polyline,
  };
}

export async function upsertAlerts(env: Env, alerts: Alert[]): Promise<number> {
  if (alerts.length === 0) return 0;
  const sql = `
    INSERT INTO alerts
      (id, source, kind, lat, lon, headline, detail, road, bearing,
       severity, started_at, updated_at, expires_at, confidence, polyline)
    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
    ON CONFLICT(id) DO UPDATE SET
      kind       = excluded.kind,
      lat        = excluded.lat,
      lon        = excluded.lon,
      headline   = excluded.headline,
      detail     = excluded.detail,
      road       = excluded.road,
      bearing    = excluded.bearing,
      severity   = excluded.severity,
      updated_at = excluded.updated_at,
      expires_at = excluded.expires_at,
      confidence = excluded.confidence,
      polyline   = excluded.polyline`;

  let written = 0;
  for (const batch of chunked(alerts)) {
    const statements = batch.map((a) =>
      env.DB.prepare(sql).bind(
        a.id,
        a.source,
        a.kind,
        a.lat,
        a.lon,
        a.headline,
        a.detail,
        a.road,
        a.bearing,
        a.severity,
        a.startedAt,
        a.updatedAt,
        a.expiresAt,
        a.confidence,
        a.polyline ? JSON.stringify(a.polyline) : null,
      ),
    );
    await env.DB.batch(statements);
    written += batch.length;
  }
  return written;
}

export async function queryAlerts(
  env: Env,
  bbox: BBox,
  since: number | null,
  now: number,
): Promise<Alert[]> {
  const clauses = [
    'lat BETWEEN ? AND ?',
    'lon BETWEEN ? AND ?',
    'expires_at > ?',
  ];
  const binds: unknown[] = [bbox.minLat, bbox.maxLat, bbox.minLon, bbox.maxLon, now];
  if (since !== null) {
    clauses.push('updated_at > ?');
    binds.push(since);
  }
  const { results } = await env.DB.prepare(
    `SELECT * FROM alerts WHERE ${clauses.join(' AND ')}
     ORDER BY severity DESC, updated_at DESC LIMIT 1000`,
  )
    .bind(...binds)
    .all<AlertRow>();
  return (results ?? []).map(rowToAlert);
}

/**
 * Drop expired hazards. Feeds re-assert anything still live on the next poll,
 * so deleting is safe and keeps the hot table small.
 */
export async function sweepExpired(env: Env, now: number): Promise<void> {
  await env.DB.batch([
    env.DB.prepare('DELETE FROM alerts WHERE expires_at < ?').bind(now),
    env.DB.prepare('DELETE FROM reports WHERE expires_at < ?').bind(now),
    env.DB.prepare('DELETE FROM active_regions WHERE last_seen < ?').bind(
      now - 60 * 60 * 1000,
    ),
  ]);
}

/** Remove rows a source no longer lists, so cleared incidents disappear. */
export async function pruneSource(
  env: Env,
  source: SourceId,
  keepIds: string[],
  now: number,
): Promise<void> {
  if (keepIds.length === 0) {
    await env.DB.prepare('DELETE FROM alerts WHERE source = ?').bind(source).run();
    return;
  }
  // Anything from this source not re-asserted in this run is stale. Compare on
  // updated_at rather than building a huge NOT IN list.
  await env.DB.prepare(
    'DELETE FROM alerts WHERE source = ? AND updated_at < ?',
  )
    .bind(source, now)
    .run();
}

interface CameraRow {
  id: string;
  source: string;
  kind: string;
  lat: number;
  lon: number;
  road: string | null;
  suburb: string | null;
  state: string;
  speed_limit: number | null;
  bearing: number | null;
  verified_at: number;
}

function rowToCamera(r: CameraRow): Camera {
  return {
    id: r.id,
    source: r.source,
    kind: r.kind as Camera['kind'],
    lat: r.lat,
    lon: r.lon,
    road: r.road,
    suburb: r.suburb,
    state: r.state,
    speedLimit: r.speed_limit,
    bearing: r.bearing,
    verifiedAt: r.verified_at,
  };
}

export async function upsertCameras(env: Env, cameras: Camera[]): Promise<number> {
  if (cameras.length === 0) return 0;
  const sql = `
    INSERT INTO cameras
      (id, source, kind, lat, lon, road, suburb, state, speed_limit, bearing, verified_at)
    VALUES (?,?,?,?,?,?,?,?,?,?,?)
    ON CONFLICT(id) DO UPDATE SET
      kind        = excluded.kind,
      lat         = excluded.lat,
      lon         = excluded.lon,
      road        = excluded.road,
      suburb      = excluded.suburb,
      state       = excluded.state,
      speed_limit = excluded.speed_limit,
      bearing     = excluded.bearing,
      verified_at = excluded.verified_at`;

  let written = 0;
  for (const batch of chunked(cameras)) {
    await env.DB.batch(
      batch.map((c) =>
        env.DB.prepare(sql).bind(
          c.id,
          c.source,
          c.kind,
          c.lat,
          c.lon,
          c.road,
          c.suburb,
          c.state,
          c.speedLimit,
          c.bearing,
          c.verifiedAt,
        ),
      ),
    );
    written += batch.length;
  }
  return written;
}

export async function queryCameras(env: Env, bbox: BBox): Promise<Camera[]> {
  const { results } = await env.DB.prepare(
    `SELECT * FROM cameras
     WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
     LIMIT 2000`,
  )
    .bind(bbox.minLat, bbox.maxLat, bbox.minLon, bbox.maxLon)
    .all<CameraRow>();
  return (results ?? []).map(rowToCamera);
}

export async function allCameras(env: Env): Promise<Camera[]> {
  const out: Camera[] = [];
  let offset = 0;
  for (;;) {
    const { results } = await env.DB.prepare(
      'SELECT * FROM cameras ORDER BY id LIMIT 1000 OFFSET ?',
    )
      .bind(offset)
      .all<CameraRow>();
    const page = results ?? [];
    out.push(...page.map(rowToCamera));
    if (page.length < 1000) break;
    offset += 1000;
  }
  return out;
}

export async function dropCameraSource(env: Env, source: string): Promise<void> {
  await env.DB.prepare('DELETE FROM cameras WHERE source = ?').bind(source).run();
}

export interface ReportRow {
  id: string;
  device_id: string;
  kind: string;
  lat: number;
  lon: number;
  bearing: number | null;
  note: string | null;
  created_at: number;
  expires_at: number;
  confirms: number;
  denies: number;
  retracted: number;
}

/**
 * Community reports become alerts at read time. Confidence starts low and climbs
 * with confirmations, so a single bad report never triggers a loud warning.
 */
export function reportToAlert(r: ReportRow): Alert {
  const net = r.confirms - r.denies;
  const confidence = Math.max(0.15, Math.min(1, 0.35 + net * 0.2));
  const severity: Severity = confidence >= 0.75 ? 2 : 1;
  const labels: Record<string, string> = {
    police: 'Police reported',
    mobile_camera: 'Mobile camera reported',
    crash: 'Crash reported',
    hazard: 'Hazard reported',
    object_on_road: 'Object on road',
    stopped_vehicle: 'Stopped vehicle',
  };
  return {
    id: `community:${r.id}`,
    source: 'community',
    kind: r.kind as AlertKind,
    lat: r.lat,
    lon: r.lon,
    headline: labels[r.kind] ?? 'Reported by a driver',
    detail: r.note,
    road: null,
    bearing: r.bearing,
    severity,
    startedAt: r.created_at,
    updatedAt: r.created_at,
    expiresAt: r.expires_at,
    confidence,
    polyline: null,
  };
}

export async function queryReports(
  env: Env,
  bbox: BBox,
  now: number,
): Promise<Alert[]> {
  const { results } = await env.DB.prepare(
    `SELECT * FROM reports
     WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
       AND expires_at > ? AND retracted = 0 AND denies < 3
     ORDER BY created_at DESC LIMIT 300`,
  )
    .bind(bbox.minLat, bbox.maxLat, bbox.minLon, bbox.maxLon, now)
    .all<ReportRow>();
  return (results ?? []).map(reportToAlert);
}

export async function insertReport(env: Env, r: ReportRow): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO reports
       (id, device_id, kind, lat, lon, bearing, note, created_at, expires_at,
        confirms, denies, retracted)
     VALUES (?,?,?,?,?,?,?,?,?,0,0,0)`,
  )
    .bind(
      r.id,
      r.device_id,
      r.kind,
      r.lat,
      r.lon,
      r.bearing,
      r.note,
      r.created_at,
      r.expires_at,
    )
    .run();
}

/** How many reports this device filed in the trailing window. Abuse brake. */
export async function reportCountSince(
  env: Env,
  device: string,
  since: number,
): Promise<number> {
  const row = await env.DB.prepare(
    'SELECT COUNT(*) AS n FROM reports WHERE device_id = ? AND created_at > ?',
  )
    .bind(device, since)
    .first<{ n: number }>();
  return row?.n ?? 0;
}

/** Returns false when this device already voted on this report. */
export async function voteOnReport(
  env: Env,
  reportId: string,
  device: string,
  confirm: boolean,
  now: number,
): Promise<boolean> {
  try {
    await env.DB.prepare(
      'INSERT INTO report_votes (report_id, device_id, vote, created_at) VALUES (?,?,?,?)',
    )
      .bind(reportId, device, confirm ? 1 : -1, now)
      .run();
  } catch {
    return false;
  }
  const column = confirm ? 'confirms' : 'denies';
  // A confirmation also buys the report more life, up to a ceiling.
  const extend = confirm ? ', expires_at = MIN(expires_at + 900000, ? + 7200000)' : '';
  await env.DB.prepare(
    `UPDATE reports SET ${column} = ${column} + 1${extend} WHERE id = ?`,
  )
    .bind(...(confirm ? [now, reportId] : [reportId]))
    .run();
  return true;
}

export async function retractReport(
  env: Env,
  reportId: string,
  device: string,
): Promise<boolean> {
  const res = await env.DB.prepare(
    'UPDATE reports SET retracted = 1 WHERE id = ? AND device_id = ?',
  )
    .bind(reportId, device)
    .run();
  return (res.meta?.changes ?? 0) > 0;
}

/**
 * Record that someone is driving here. The cron sweep only polls cells seen in
 * the last hour, which keeps outbound request volume proportional to real use.
 */
export async function touchRegion(env: Env, bbox: BBox, now: number): Promise<void> {
  const cell = `${bbox.minLon.toFixed(2)},${bbox.minLat.toFixed(2)}`;
  await env.DB.prepare(
    `INSERT INTO active_regions (cell, min_lon, min_lat, max_lon, max_lat, last_seen)
     VALUES (?,?,?,?,?,?)
     ON CONFLICT(cell) DO UPDATE SET last_seen = excluded.last_seen`,
  )
    .bind(cell, bbox.minLon, bbox.minLat, bbox.maxLon, bbox.maxLat, now)
    .run();
}

export async function activeRegions(env: Env, since: number): Promise<BBox[]> {
  const { results } = await env.DB.prepare(
    'SELECT min_lon, min_lat, max_lon, max_lat FROM active_regions WHERE last_seen > ? LIMIT 40',
  )
    .bind(since)
    .all<{ min_lon: number; min_lat: number; max_lon: number; max_lat: number }>();
  return (results ?? []).map((r) => ({
    minLon: r.min_lon,
    minLat: r.min_lat,
    maxLon: r.max_lon,
    maxLat: r.max_lat,
  }));
}

export async function recordSourceStatus(
  env: Env,
  source: string,
  ok: boolean,
  count: number,
  error: string | null,
  now: number,
): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO source_status (source, last_ok_at, last_try_at, last_error, last_count)
     VALUES (?,?,?,?,?)
     ON CONFLICT(source) DO UPDATE SET
       last_ok_at  = CASE WHEN ? THEN ? ELSE source_status.last_ok_at END,
       last_try_at = excluded.last_try_at,
       last_error  = excluded.last_error,
       last_count  = excluded.last_count`,
  )
    .bind(source, ok ? now : null, now, error, count, ok ? 1 : 0, now)
    .run();
}

export async function sourceStatuses(env: Env): Promise<unknown[]> {
  const { results } = await env.DB.prepare(
    'SELECT * FROM source_status ORDER BY source',
  ).all();
  return results ?? [];
}
