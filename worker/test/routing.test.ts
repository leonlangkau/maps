import { afterEach, describe, expect, it, vi } from 'vitest';
import { mapboxProvider } from '../src/routing/mapbox';

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    headers: { 'content-type': 'application/json' },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('mapbox routing', () => {
  it('asks for the annotations the apps need', async () => {
    const seen: string[] = [];
    vi.stubGlobal('fetch', async (url: string) => {
      seen.push(url);
      return jsonResponse({ routes: [] });
    });

    await mapboxProvider('tok').route({ lat: -33.87, lon: 151.21 }, { lat: -33.8, lon: 151.0 });

    const url = seen[0]!;
    // congestion paints the line, and without polyline6 the decoder on the
    // phone reads every coordinate off by a factor of ten.
    expect(url).toContain('congestion');
    expect(url).toContain('geometries=polyline6');
    expect(url).toContain('alternatives=true');
    expect(url).toContain('/driving-traffic/');
  });

  it('carries congestion and the typical duration through', async () => {
    vi.stubGlobal('fetch', async () =>
      jsonResponse({
        routes: [
          {
            distance: 18_400,
            duration: 1_800,
            duration_typical: 1_440,
            geometry: 'abc',
            legs: [
              {
                distance: 18_400,
                duration: 1_800,
                annotation: { congestion: ['low', 'heavy', 'severe'] },
                steps: [
                  {
                    distance: 500,
                    duration: 60,
                    name: 'George Street',
                    maneuver: { instruction: 'Turn left', modifier: 'left' },
                  },
                ],
              },
            ],
          },
        ],
      }),
    );

    const result = await mapboxProvider('tok').route(
      { lat: -33.87, lon: 151.21 },
      { lat: -33.8, lon: 151.0 },
    );

    const route = result.routes[0]!;
    expect(route.durationS).toBe(1_800);
    expect(route.durationFreeFlowS).toBe(1_440);
    expect(route.legs[0]!.congestion).toEqual(['low', 'heavy', 'severe']);
    expect(route.legs[0]!.steps[0]!.instruction).toBe('Turn left');
  });

  it('normalises the nulls Mapbox sometimes emits in the congestion array', async () => {
    vi.stubGlobal('fetch', async () =>
      jsonResponse({
        routes: [
          {
            distance: 100,
            duration: 10,
            geometry: 'abc',
            legs: [
              {
                distance: 100,
                duration: 10,
                annotation: { congestion: ['low', null, 'nonsense', 'severe'] },
              },
            ],
          },
        ],
      }),
    );

    const result = await mapboxProvider('tok').route(
      { lat: -33.87, lon: 151.21 },
      { lat: -33.8, lon: 151.0 },
    );

    // The apps pair congestion entries with geometry segments by index, so a
    // dropped entry would misalign every colour after it. Unknown values have
    // to be replaced, never removed.
    expect(result.routes[0]!.legs[0]!.congestion).toEqual([
      'low',
      'unknown',
      'unknown',
      'severe',
    ]);
  });

  it('falls back to the live duration when there is no typical one', async () => {
    vi.stubGlobal('fetch', async () =>
      jsonResponse({
        routes: [{ distance: 100, duration: 600, geometry: 'abc', legs: [] }],
      }),
    );

    const result = await mapboxProvider('tok').route(
      { lat: -33.87, lon: 151.21 },
      { lat: -33.8, lon: 151.0 },
    );

    // Equal durations mean "no delay", which is the honest answer. Leaving it
    // at zero would report the entire trip as traffic delay.
    const route = result.routes[0]!;
    expect(route.durationFreeFlowS).toBe(600);
    expect(route.durationS - route.durationFreeFlowS).toBe(0);
  });

  it('a leg with no annotation yields an empty congestion array', async () => {
    vi.stubGlobal('fetch', async () =>
      jsonResponse({
        routes: [
          { distance: 100, duration: 10, geometry: 'abc', legs: [{ distance: 100, duration: 10 }] },
        ],
      }),
    );

    const result = await mapboxProvider('tok').route(
      { lat: -33.87, lon: 151.21 },
      { lat: -33.8, lon: 151.0 },
    );
    // Empty means "unknown" to the apps, which draw one flat colour.
    expect(result.routes[0]!.legs[0]!.congestion).toEqual([]);
  });

  it('surfaces a routing failure rather than returning an empty route', async () => {
    vi.stubGlobal('fetch', async () => new Response('nope', { status: 422 }));
    await expect(
      mapboxProvider('tok').route({ lat: -33.87, lon: 151.21 }, { lat: -33.8, lon: 151.0 }),
    ).rejects.toThrow(/422/);
  });
});
