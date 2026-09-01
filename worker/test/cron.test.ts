import { afterEach, describe, expect, it, vi } from 'vitest';
import { pollFeeds } from '../src/cron';
import { pruneSource, upsertAlerts } from '../src/lib/store';
import type { Env } from '../src/lib/types';

/** Records every statement the code prepares, so we can assert on the SQL. */
function fakeDb() {
  const statements: Array<{ sql: string; binds: unknown[] }> = [];

  const prepare = (sql: string) => {
    const record = { sql, binds: [] as unknown[] };
    const stmt = {
      bind(...binds: unknown[]) {
        record.binds = binds;
        statements.push(record);
        return stmt;
      },
      async run() {
        return { meta: { changes: 1 } };
      },
      async all() {
        return { results: [] };
      },
      async first() {
        return null;
      },
    };
    return stmt;
  };

  return {
    statements,
    db: {
      prepare,
      async batch(list: unknown[]) {
        return list.map(() => ({ results: [] }));
      },
    } as unknown as D1Database,
  };
}

function fakeKv() {
  const store = new Map<string, string>();
  return {
    get: async (k: string) => store.get(k) ?? null,
    put: async (k: string, v: string) => void store.set(k, v),
  } as unknown as KVNamespace;
}

function envWith(db: D1Database, overrides: Partial<Env> = {}): Env {
  return {
    DB: db,
    CACHE: fakeKv(),
    ACTIVE_STATES: 'NSW',
    WAZE_ENABLED: 'false',
    PMTILES_KEY: 'australia.pmtiles',
    APP_TOKEN: 'test',
    MAPBOX_TOKEN: 'test',
    ...overrides,
  } as Env;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('pruneSource', () => {
  it('only deletes rows this run did not re-assert', async () => {
    const { db, statements } = fakeDb();
    await pruneSource(envWith(db), 'nsw', 5_000);

    const deletes = statements.filter((s) => s.sql.includes('DELETE FROM alerts'));
    expect(deletes).toHaveLength(1);
    // The bug this guards: a DELETE with no updated_at clause wipes the rows
    // that were just written, leaving the table permanently empty.
    expect(deletes[0]!.sql).toContain('updated_at <');
    expect(deletes[0]!.binds).toEqual(['nsw', 5_000]);
  });

  it('never issues a delete that would take out freshly written rows', async () => {
    const { db, statements } = fakeDb();
    const now = 9_000;

    await upsertAlerts(envWith(db), [
      {
        id: 'nsw:incident:1',
        source: 'nsw',
        kind: 'crash',
        lat: -33.8,
        lon: 151.2,
        headline: 'Crash',
        detail: null,
        road: null,
        bearing: null,
        severity: 2,
        startedAt: null,
        updatedAt: now,
        expiresAt: now + 1000,
        confidence: 1,
        polyline: null,
      },
    ]);
    await pruneSource(envWith(db), 'nsw', now);

    const del = statements.find((s) => s.sql.includes('DELETE FROM alerts'))!;
    // The row was written with updated_at == now, and the delete is strictly
    // less-than, so it survives.
    expect(del.binds[1]).toBe(now);
    expect(del.sql).toMatch(/updated_at\s*<\s*\?/);
  });
});

describe('pollFeeds', () => {
  it('prunes a statewide feed but leaves bbox-scoped sources to expire', async () => {
    // NSW answers with one live incident; every category returns the same.
    vi.stubGlobal('fetch', async () =>
      new Response(
        JSON.stringify({
          type: 'FeatureCollection',
          features: [
            {
              id: 1,
              geometry: { type: 'Point', coordinates: [151.2, -33.8] },
              properties: { headline: 'Crash' },
            },
          ],
        }),
        { headers: { 'content-type': 'application/json' } },
      ),
    );

    const { db, statements } = fakeDb();
    const env = envWith(db, {
      NSW_API_KEY: 'k',
      TOMTOM_API_KEY: 'k',
      ACTIVE_STATES: 'NSW',
    });

    await pollFeeds(env, 12_345);

    const pruned = statements
      .filter((s) => s.sql.includes('DELETE FROM alerts WHERE source = ?'))
      .map((s) => s.binds[0]);

    expect(pruned).toContain('nsw');
    // TomTom is bbox-scoped: an empty result means nobody was driving there,
    // not that the roads are clear, so its rows must not be pruned.
    expect(pruned).not.toContain('tomtom');
    expect(pruned).not.toContain('waze');
  });

  it('records a failure for a source instead of throwing the whole run', async () => {
    vi.stubGlobal('fetch', async () => new Response('down', { status: 503 }));

    const { db, statements } = fakeDb();
    const env = envWith(db, { NSW_API_KEY: 'k' });

    await expect(pollFeeds(env, 1_000)).resolves.toBeUndefined();

    const status = statements.find((s) => s.sql.includes('source_status'));
    expect(status).toBeDefined();
    // last_ok_at is null and an error string is recorded, which is what makes
    // a silently dead feed visible on /v1/health.
    expect(status!.binds[1]).toBeNull();
    expect(String(status!.binds[3])).toContain('503');
  });
});
