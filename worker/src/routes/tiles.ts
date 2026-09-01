import { Compression, PMTiles, type RangeResponse, type Source } from 'pmtiles';
import { problem } from '../lib/http';
import type { Env } from '../lib/types';

/**
 * The basemap is a single PMTiles archive in R2. Serving it means answering
 * XYZ tile requests with byte ranges into that one object: no tile server, no
 * per-tile storage, and no egress bill because R2 does not charge for it.
 *
 * MapLibre Native has no PMTiles protocol handler, so the translation from
 * /tiles/{z}/{x}/{y}.mvt to a range read happens here rather than on the phone.
 */
class R2RangeSource implements Source {
  constructor(
    private readonly bucket: R2Bucket,
    private readonly key: string,
  ) {}

  getKey(): string {
    return this.key;
  }

  async getBytes(offset: number, length: number): Promise<RangeResponse> {
    const object = await this.bucket.get(this.key, {
      range: { offset, length },
    });
    if (!object) throw new Error(`missing PMTiles archive: ${this.key}`);
    return {
      data: await object.arrayBuffer(),
      etag: object.httpEtag,
    };
  }
}

/** Workers has no zlib, but it does have DecompressionStream. */
async function decompress(
  buffer: ArrayBuffer,
  compression: Compression,
): Promise<ArrayBuffer> {
  if (compression === Compression.None || compression === Compression.Unknown) {
    return buffer;
  }
  const format =
    compression === Compression.Gzip
      ? 'gzip'
      : compression === Compression.Brotli
        ? undefined
        : 'deflate';
  if (!format) throw new Error('brotli-compressed PMTiles are not supported here');

  const stream = new Response(buffer).body;
  if (!stream) throw new Error('empty PMTiles range');
  return new Response(
    stream.pipeThrough(new DecompressionStream(format)),
  ).arrayBuffer();
}

const archives = new Map<string, PMTiles>();

function archiveFor(env: Env): PMTiles {
  const key = env.PMTILES_KEY;
  let archive = archives.get(key);
  if (!archive) {
    archive = new PMTiles(new R2RangeSource(env.TILES, key), undefined, decompress);
    archives.set(key, archive);
  }
  return archive;
}

const TILE_PATH = /^\/tiles\/(\d{1,2})\/(\d+)\/(\d+)\.(mvt|pbf)$/;

export async function handleTile(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const match = TILE_PATH.exec(url.pathname);
  if (!match) return problem(404, 'not a tile path');

  const z = Number(match[1]);
  const x = Number(match[2]);
  const y = Number(match[3]);
  if (z > 20) return problem(400, 'zoom out of range');
  // Outside the pyramid at this zoom is a client bug, not an empty tile.
  const limit = 2 ** z;
  if (x >= limit || y >= limit) return problem(400, 'tile out of range');

  try {
    const archive = archiveFor(env);
    const header = await archive.getHeader();
    const tile = await archive.getZxy(z, x, y);

    // A hole in the pyramid is normal: ocean, or outside the extract.
    if (!tile) return new Response(null, { status: 204 });

    const headers: Record<string, string> = {
      'content-type': 'application/vnd.mapbox-vector-tile',
      'cache-control': 'public, max-age=604800, immutable',
    };
    // Tiles are stored compressed; hand them over as-is and let the HTTP layer
    // do the decompressing rather than burning Worker CPU on every tile.
    if (header.tileCompression === Compression.Gzip) {
      headers['content-encoding'] = 'gzip';
    }
    return new Response(tile.data, { headers });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return problem(502, `tile read failed: ${message}`);
  }
}
