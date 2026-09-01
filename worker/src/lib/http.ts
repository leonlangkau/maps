import type { Env } from './types';

export const JSON_HEADERS = {
  'content-type': 'application/json; charset=utf-8',
} as const;

export function json(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { ...JSON_HEADERS, ...(init.headers ?? {}) },
  });
}

export function problem(status: number, message: string): Response {
  return json({ error: message }, { status });
}

/**
 * Constant-time-ish comparison so a wrong token cannot be recovered by timing
 * the response. Lengths differing is fine to leak; the contents are not.
 */
function tokensMatch(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** Every /v1 route requires the shared app token. Returns null when authorised. */
export function requireAuth(request: Request, env: Env): Response | null {
  const header = request.headers.get('authorization') ?? '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (!env.APP_TOKEN) return problem(503, 'server missing APP_TOKEN');
  if (!token || !tokensMatch(token, env.APP_TOKEN)) {
    return problem(401, 'unauthorised');
  }
  return null;
}

/** The device's self-assigned anonymous id. Required for anything that writes. */
export function deviceId(request: Request): string | null {
  const id = request.headers.get('x-device-id');
  if (!id || id.length < 8 || id.length > 64) return null;
  if (!/^[A-Za-z0-9._-]+$/.test(id)) return null;
  return id;
}

/** fetch with a hard timeout, so one dead feed cannot stall the whole cron run. */
export async function fetchWithTimeout(
  url: string,
  init: RequestInit = {},
  ms = 8000,
): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ms);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}
