import { refreshCameras } from './cameras/ingest';
import { AUSTRALIA } from './lib/geo';
import {
  activeRegions,
  pruneSource,
  recordSourceStatus,
  sweepExpired,
  upsertAlerts,
} from './lib/store';
import type { Env } from './lib/types';
import { SOURCES } from './sources';

const ACTIVE_WINDOW_MS = 60 * 60 * 1000;

/**
 * Poll every configured feed, merge into the alerts table, then drop anything
 * no longer being asserted. Sources are polled concurrently and failures are
 * isolated: a dead VicRoads token must not stop NSW from updating.
 */
export async function pollFeeds(env: Env, now: number): Promise<void> {
  const regions = await activeRegions(env, now - ACTIVE_WINDOW_MS);

  // With nobody driving, bbox-scoped sources have nothing to poll. Statewide
  // feeds still refresh so the first request of the day is not empty.
  const enabled = SOURCES.filter((s) => s.enabled(env));

  await Promise.all(
    enabled.map(async (source) => {
      try {
        const alerts = await source.fetch(env, now, regions);
        await upsertAlerts(env, alerts);
        // Anything from this source not re-asserted just now has cleared.
        await pruneSource(env, source.id, [], now);
        await recordSourceStatus(env, source.id, true, alerts.length, null, now);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        // Leave existing rows alone on failure; they expire on their own TTL.
        await recordSourceStatus(env, source.id, false, 0, message, now);
      }
    }),
  );

  await sweepExpired(env, now);
}

export async function scheduled(
  event: ScheduledController,
  env: Env,
  ctx: ExecutionContext,
): Promise<void> {
  const now = Date.now();
  // The nightly cron rebuilds camera data; every other tick polls live feeds.
  if (event.cron === '17 16 * * *') {
    ctx.waitUntil(refreshCameras(env, now).then(() => undefined));
    return;
  }
  ctx.waitUntil(pollFeeds(env, now));
}

export { AUSTRALIA };
