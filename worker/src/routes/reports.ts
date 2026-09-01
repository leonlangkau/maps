import { withinAustralia } from '../lib/geo';
import { deviceId, json, problem } from '../lib/http';
import {
  insertReport,
  reportCountSince,
  retractReport,
  voteOnReport,
} from '../lib/store';
import type { AlertKind, Env } from '../lib/types';

/** What a driver can report with one tap. Deliberately short. */
const REPORTABLE: AlertKind[] = [
  'police',
  'mobile_camera',
  'crash',
  'hazard',
  'object_on_road',
  'stopped_vehicle',
];

/**
 * How long each kind stays live without confirmation. A mobile camera packs up;
 * a crash gets cleared. Nothing outlives its usefulness on the road.
 */
const TTL_BY_KIND: Record<string, number> = {
  police: 30 * 60 * 1000,
  mobile_camera: 45 * 60 * 1000,
  crash: 60 * 60 * 1000,
  hazard: 45 * 60 * 1000,
  object_on_road: 45 * 60 * 1000,
  stopped_vehicle: 30 * 60 * 1000,
};

const MAX_REPORTS_PER_HOUR = 12;

interface ReportBody {
  kind?: unknown;
  lat?: unknown;
  lon?: unknown;
  bearing?: unknown;
  note?: unknown;
}

export async function handleCreateReport(request: Request, env: Env): Promise<Response> {
  const device = deviceId(request);
  if (!device) return problem(400, 'x-device-id header is required');

  let body: ReportBody;
  try {
    body = (await request.json()) as ReportBody;
  } catch {
    return problem(400, 'body must be JSON');
  }

  const kind = typeof body.kind === 'string' ? (body.kind as AlertKind) : null;
  if (!kind || !REPORTABLE.includes(kind)) {
    return problem(400, `kind must be one of: ${REPORTABLE.join(', ')}`);
  }

  const lat = Number(body.lat);
  const lon = Number(body.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || !withinAustralia(lat, lon)) {
    return problem(400, 'lat/lon must be a position within Australia');
  }

  const bearingRaw = body.bearing === undefined || body.bearing === null
    ? null
    : Number(body.bearing);
  if (bearingRaw !== null && (!Number.isFinite(bearingRaw) || bearingRaw < 0 || bearingRaw >= 360)) {
    return problem(400, 'bearing must be 0..359');
  }

  const note =
    typeof body.note === 'string' && body.note.trim() !== ''
      ? body.note.trim().slice(0, 200)
      : null;

  const now = Date.now();
  const recent = await reportCountSince(env, device, now - 60 * 60 * 1000);
  if (recent >= MAX_REPORTS_PER_HOUR) {
    return problem(429, 'too many reports from this device in the last hour');
  }

  const id = crypto.randomUUID();
  await insertReport(env, {
    id,
    device_id: device,
    kind,
    lat,
    lon,
    bearing: bearingRaw,
    note,
    created_at: now,
    expires_at: now + (TTL_BY_KIND[kind] ?? 30 * 60 * 1000),
    confirms: 0,
    denies: 0,
    retracted: 0,
  });

  return json({ id, kind, expiresAt: now + (TTL_BY_KIND[kind] ?? 1800000) }, { status: 201 });
}

export async function handleVoteReport(
  request: Request,
  env: Env,
  reportId: string,
  confirm: boolean,
): Promise<Response> {
  const device = deviceId(request);
  if (!device) return problem(400, 'x-device-id header is required');

  const accepted = await voteOnReport(env, reportId, device, confirm, Date.now());
  if (!accepted) return problem(409, 'this device already voted on that report');
  return json({ ok: true });
}

export async function handleRetractReport(
  request: Request,
  env: Env,
  reportId: string,
): Promise<Response> {
  const device = deviceId(request);
  if (!device) return problem(400, 'x-device-id header is required');

  const removed = await retractReport(env, reportId, device);
  if (!removed) return problem(404, 'no such report from this device');
  return json({ ok: true });
}
