import { afterEach, describe, expect, it, vi } from 'vitest';
import { nswSource } from '../src/sources/nsw';
import { qldSource } from '../src/sources/qld';
import { wazeSource } from '../src/sources/waze';
import { waSource } from '../src/sources/wa';
import type { Env } from '../src/lib/types';

const NOW = 1_760_000_000_000;

function envWith(overrides: Partial<Env> = {}): Env {
  return {
    ACTIVE_STATES: 'NSW,QLD,VIC,WA',
    WAZE_ENABLED: 'false',
    PMTILES_KEY: 'australia.pmtiles',
    APP_TOKEN: 'test',
    MAPBOX_TOKEN: 'test',
    ...overrides,
  } as Env;
}

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    headers: { 'content-type': 'application/json' },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('nswSource', () => {
  it('is disabled without an API key', () => {
    expect(nswSource.enabled(envWith())).toBe(false);
    expect(nswSource.enabled(envWith({ NSW_API_KEY: 'k' }))).toBe(true);
  });

  it('is disabled when NSW is not in ACTIVE_STATES', () => {
    expect(nswSource.enabled(envWith({ NSW_API_KEY: 'k', ACTIVE_STATES: 'QLD' }))).toBe(false);
  });

  it('normalises an incident feature', async () => {
    vi.stubGlobal('fetch', async (url: string) =>
      url.includes('/incident/')
        ? jsonResponse({
            type: 'FeatureCollection',
            features: [
              {
                id: 12345,
                geometry: { type: 'Point', coordinates: [151.2093, -33.8688] },
                properties: {
                  headline: 'Crash blocking a lane',
                  created: '2026-08-31T22:00:00Z',
                  isMajor: true,
                  isEnded: false,
                  otherAdvice: 'Allow extra travel time',
                  roads: [{ mainStreet: 'Pacific Motorway', suburb: 'Wahroonga' }],
                },
              },
            ],
          })
        : jsonResponse({ type: 'FeatureCollection', features: [] }),
    );

    const alerts = await nswSource.fetch(envWith({ NSW_API_KEY: 'k' }), NOW, []);
    expect(alerts).toHaveLength(1);
    const alert = alerts[0]!;
    expect(alert.id).toBe('nsw:incident:12345');
    expect(alert.source).toBe('nsw');
    expect(alert.kind).toBe('crash');
    expect(alert.lat).toBeCloseTo(-33.8688, 4);
    expect(alert.lon).toBeCloseTo(151.2093, 4);
    expect(alert.severity).toBe(3);
    expect(alert.road).toBe('Pacific Motorway, Wahroonga');
    expect(alert.detail).toBe('Allow extra travel time');
    expect(alert.expiresAt).toBeGreaterThan(NOW);
  });

  it('drops hazards the feed has already ended', async () => {
    vi.stubGlobal('fetch', async () =>
      jsonResponse({
        type: 'FeatureCollection',
        features: [
          {
            id: 1,
            geometry: { type: 'Point', coordinates: [151.2, -33.8] },
            properties: { headline: 'Cleared', isEnded: true },
          },
        ],
      }),
    );
    expect(await nswSource.fetch(envWith({ NSW_API_KEY: 'k' }), NOW, [])).toHaveLength(0);
  });

  it('drops features positioned outside Australia', async () => {
    vi.stubGlobal('fetch', async () =>
      jsonResponse({
        type: 'FeatureCollection',
        features: [
          {
            id: 1,
            geometry: { type: 'Point', coordinates: [-0.12, 51.5] },
            properties: { headline: 'London' },
          },
        ],
      }),
    );
    expect(await nswSource.fetch(envWith({ NSW_API_KEY: 'k' }), NOW, [])).toHaveLength(0);
  });

  it('keeps the categories that worked when one category fails', async () => {
    vi.stubGlobal('fetch', async (url: string) => {
      if (url.includes('/fire/')) return new Response('nope', { status: 500 });
      return jsonResponse({
        type: 'FeatureCollection',
        features: [
          {
            id: url.includes('/incident/') ? 1 : 2,
            geometry: { type: 'Point', coordinates: [151.2, -33.8] },
            properties: { headline: 'Something' },
          },
        ],
      });
    });
    const alerts = await nswSource.fetch(envWith({ NSW_API_KEY: 'k' }), NOW, []);
    // Five of six categories returned a feature; the failing one is skipped.
    expect(alerts.length).toBe(5);
  });

  it('throws when every category fails, so health reports the outage', async () => {
    vi.stubGlobal('fetch', async () => new Response('down', { status: 503 }));
    await expect(nswSource.fetch(envWith({ NSW_API_KEY: 'k' }), NOW, [])).rejects.toThrow();
  });

  it('marks roadworks as informational rather than a hazard', async () => {
    vi.stubGlobal('fetch', async (url: string) =>
      url.includes('/roadwork/')
        ? jsonResponse({
            type: 'FeatureCollection',
            features: [
              {
                id: 9,
                geometry: { type: 'Point', coordinates: [151.2, -33.8] },
                properties: { headline: 'Night works' },
              },
            ],
          })
        : jsonResponse({ type: 'FeatureCollection', features: [] }),
    );
    const alerts = await nswSource.fetch(envWith({ NSW_API_KEY: 'k' }), NOW, []);
    expect(alerts[0]?.kind).toBe('roadwork');
    expect(alerts[0]?.severity).toBe(0);
  });
});

describe('qldSource', () => {
  it('maps event types and priorities', async () => {
    vi.stubGlobal('fetch', async () =>
      jsonResponse({
        type: 'FeatureCollection',
        features: [
          {
            geometry: { type: 'Point', coordinates: [153.0251, -27.4698] },
            properties: {
              id: 'QLD-1',
              status: 'published',
              event_type: 'Crash',
              event_priority: 'highest',
              description: 'Multi-vehicle crash',
              advice: 'Avoid the area',
              road_summary: { road_name: 'Ipswich Motorway', locality: 'Rocklea' },
              duration: { start: '2026-09-01T01:00:00Z', end: '2026-09-01T05:00:00Z' },
            },
          },
        ],
      }),
    );

    const alerts = await qldSource.fetch(envWith({ QLD_API_KEY: 'k' }), NOW, []);
    expect(alerts).toHaveLength(1);
    expect(alerts[0]?.id).toBe('qld:QLD-1');
    expect(alerts[0]?.kind).toBe('crash');
    expect(alerts[0]?.severity).toBe(3);
    expect(alerts[0]?.road).toBe('Ipswich Motorway, Rocklea');
  });

  it('skips cleared events', async () => {
    vi.stubGlobal('fetch', async () =>
      jsonResponse({
        features: [
          {
            geometry: { type: 'Point', coordinates: [153.0, -27.4] },
            properties: { id: 'x', status: 'Cleared', event_type: 'Crash' },
          },
        ],
      }),
    );
    expect(await qldSource.fetch(envWith({ QLD_API_KEY: 'k' }), NOW, [])).toHaveLength(0);
  });

  it('never lets a declared end time push expiry past the feed TTL', async () => {
    vi.stubGlobal('fetch', async () =>
      jsonResponse({
        features: [
          {
            geometry: { type: 'Point', coordinates: [153.0, -27.4] },
            properties: {
              id: 'long',
              event_type: 'roadworks',
              description: 'Six month project',
              duration: { end: '2027-01-01T00:00:00Z' },
            },
          },
        ],
      }),
    );
    const alerts = await qldSource.fetch(envWith({ QLD_API_KEY: 'k' }), NOW, []);
    // A feed that stops asserting an event must not leave it on the map for
    // months, so the TTL always wins over a distant declared end.
    expect(alerts[0]!.expiresAt - NOW).toBeLessThanOrEqual(20 * 60 * 1000);
  });

  it('raises an error on a non-OK response', async () => {
    vi.stubGlobal('fetch', async () => new Response('bad key', { status: 403 }));
    await expect(qldSource.fetch(envWith({ QLD_API_KEY: 'k' }), NOW, [])).rejects.toThrow(
      /403/,
    );
  });
});

describe('waSource', () => {
  it('reads ArcGIS-style attributes and skips cleared rows', async () => {
    vi.stubGlobal('fetch', async () =>
      jsonResponse({
        type: 'FeatureCollection',
        features: [
          {
            geometry: { type: 'Point', coordinates: [115.8605, -31.9505] },
            properties: {
              OBJECTID: 42,
              IncidentType: 'Crash',
              Description: 'Two car crash',
              RoadName: 'Kwinana Fwy',
              Status: 'Open',
              Impact: 'Major delays',
            },
          },
          {
            geometry: { type: 'Point', coordinates: [115.9, -31.9] },
            properties: { OBJECTID: 43, Status: 'Cleared', IncidentType: 'Crash' },
          },
        ],
      }),
    );

    const alerts = await waSource.fetch(envWith(), NOW, []);
    expect(alerts).toHaveLength(1);
    expect(alerts[0]?.id).toBe('wa:42');
    expect(alerts[0]?.kind).toBe('crash');
    expect(alerts[0]?.severity).toBe(3);
  });

  it('rejects a feed that answers with HTML instead of JSON', async () => {
    vi.stubGlobal('fetch', async () =>
      new Response('<html>maintenance</html>', {
        headers: { 'content-type': 'text/html' },
      }),
    );
    await expect(waSource.fetch(envWith(), NOW, [])).rejects.toThrow(/text\/html/);
  });
});

describe('wazeSource', () => {
  const regions = [{ minLon: 151.0, minLat: -34.0, maxLon: 151.3, maxLat: -33.7 }];

  function kvStub(initial: Record<string, string> = {}) {
    const store = new Map(Object.entries(initial));
    return {
      get: async (k: string) => store.get(k) ?? null,
      put: async (k: string, v: string) => void store.set(k, v),
    } as unknown as KVNamespace;
  }

  it('stays off unless explicitly enabled', () => {
    expect(wazeSource.enabled(envWith())).toBe(false);
    expect(wazeSource.enabled(envWith({ WAZE_ENABLED: 'true' }))).toBe(true);
  });

  it('maps alert types and subtypes', async () => {
    vi.stubGlobal('fetch', async () =>
      jsonResponse({
        alerts: [
          {
            uuid: 'a1',
            type: 'POLICE',
            location: { x: 151.2, y: -33.87 },
            street: 'M1 Pacific Mwy',
            reliability: 9,
            confidence: 8,
            pubMillis: NOW - 60_000,
          },
          {
            uuid: 'a2',
            type: 'HAZARD',
            subtype: 'HAZARD_ON_ROAD_CAR_STOPPED',
            location: { x: 151.21, y: -33.88 },
            reliability: 3,
            confidence: 1,
          },
          { uuid: 'a3', type: 'CHIT_CHAT', location: { x: 151.2, y: -33.8 } },
        ],
      }),
    );

    const env = envWith({ WAZE_ENABLED: 'true', CACHE: kvStub() });
    const alerts = await wazeSource.fetch(env, NOW, regions);

    expect(alerts.map((a) => a.id).sort()).toEqual(['waze:a1', 'waze:a2']);
    const police = alerts.find((a) => a.id === 'waze:a1')!;
    expect(police.kind).toBe('police');
    expect(police.severity).toBe(2);
    expect(police.confidence).toBeGreaterThan(0.7);

    const stopped = alerts.find((a) => a.id === 'waze:a2')!;
    expect(stopped.kind).toBe('stopped_vehicle');
    // A low-reliability report must not reach voice-alert severity.
    expect(stopped.severity).toBe(1);
  });

  it('opens the circuit breaker when every request fails', async () => {
    vi.stubGlobal('fetch', async () => new Response('blocked', { status: 403 }));
    const cache = kvStub();
    const env = envWith({ WAZE_ENABLED: 'true', CACHE: cache });

    await expect(wazeSource.fetch(env, NOW, regions)).rejects.toThrow(/backing off/);
    expect(await cache.get('waze:blocked-until')).not.toBeNull();
  });

  it('refuses to call out while the breaker is open', async () => {
    const fetchSpy = vi.fn(async () => jsonResponse({ alerts: [] }));
    vi.stubGlobal('fetch', fetchSpy);
    const env = envWith({
      WAZE_ENABLED: 'true',
      CACHE: kvStub({ 'waze:blocked-until': String(NOW + 600_000) }),
    });

    await expect(wazeSource.fetch(env, NOW, regions)).rejects.toThrow(/circuit breaker/);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('treats a non-JSON body as a block, not an empty area', async () => {
    vi.stubGlobal('fetch', async () =>
      new Response('<html>captcha</html>', { headers: { 'content-type': 'text/html' } }),
    );
    const env = envWith({ WAZE_ENABLED: 'true', CACHE: kvStub() });
    await expect(wazeSource.fetch(env, NOW, regions)).rejects.toThrow();
  });

  it('makes no requests when nobody is driving', async () => {
    const fetchSpy = vi.fn(async () => jsonResponse({ alerts: [] }));
    vi.stubGlobal('fetch', fetchSpy);
    const env = envWith({ WAZE_ENABLED: 'true', CACHE: kvStub() });

    expect(await wazeSource.fetch(env, NOW, [])).toEqual([]);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('collapses nearby regions into one request', async () => {
    const fetchSpy = vi.fn(async () => jsonResponse({ alerts: [] }));
    vi.stubGlobal('fetch', fetchSpy);
    const env = envWith({ WAZE_ENABLED: 'true', CACHE: kvStub() });

    await wazeSource.fetch(env, NOW, [
      { minLon: 151.11, minLat: -33.87, maxLon: 151.13, maxLat: -33.85 },
      { minLon: 151.12, minLat: -33.86, maxLon: 151.14, maxLat: -33.84 },
    ]);
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });
});
