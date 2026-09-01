import { json } from '../lib/http';
import { sourceStatuses } from '../lib/store';
import { SOURCES } from '../sources';
import type { Env } from '../lib/types';

/**
 * A feed that quietly stops returning data looks identical to a quiet road.
 * This endpoint makes the difference visible: every source reports when it last
 * succeeded and what it last returned.
 */
export async function handleHealth(env: Env): Promise<Response> {
  const [statuses, alertCount, cameraCount, bundleVersion] = await Promise.all([
    sourceStatuses(env),
    env.DB.prepare('SELECT COUNT(*) AS n FROM alerts').first<{ n: number }>(),
    env.DB.prepare('SELECT COUNT(*) AS n FROM cameras').first<{ n: number }>(),
    env.CACHE.get('cameras:bundle-version'),
  ]);

  return json({
    ok: true,
    now: Date.now(),
    alerts: alertCount?.n ?? 0,
    cameras: cameraCount?.n ?? 0,
    cameraBundleVersion: Number(bundleVersion ?? 0),
    configured: SOURCES.map((s) => ({
      id: s.id,
      label: s.label,
      enabled: s.enabled(env),
    })),
    sources: statuses,
  });
}
