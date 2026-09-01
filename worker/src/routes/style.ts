import { layers, namedTheme } from 'protomaps-themes-base';
import { json, problem } from '../lib/http';
import type { Env } from '../lib/types';

/**
 * The MapLibre style document. Generated rather than checked in so the tile URL
 * always matches the deployment it was served from, and so a theme change is a
 * query parameter instead of a new file to ship in both apps.
 */
const THEMES = new Set(['light', 'dark', 'white', 'black', 'grayscale']);

export async function handleStyle(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const requested = url.searchParams.get('theme') ?? 'dark';
  if (!THEMES.has(requested)) {
    return problem(400, `theme must be one of: ${[...THEMES].join(', ')}`);
  }

  const header = await env.TILES.head(env.PMTILES_KEY);
  if (!header) return problem(503, 'basemap not uploaded yet');

  const style = {
    version: 8,
    name: `Radar AU (${requested})`,
    glyphs: 'https://protomaps.github.io/basemaps-assets/fonts/{fontstack}/{range}.pbf',
    sprite: `https://protomaps.github.io/basemaps-assets/sprites/v4/${
      requested === 'dark' || requested === 'black' ? 'dark' : 'light'
    }`,
    sources: {
      protomaps: {
        type: 'vector',
        tiles: [`${url.origin}/tiles/{z}/{x}/{y}.mvt`],
        maxzoom: 15,
        attribution:
          '<a href="https://protomaps.com">Protomaps</a> © <a href="https://openstreetmap.org">OpenStreetMap</a>',
      },
    },
    layers: layers('protomaps', namedTheme(requested), { lang: 'en' }),
  };

  return json(style, {
    headers: { 'cache-control': 'public, max-age=3600' },
  });
}
