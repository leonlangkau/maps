import { scheduled } from './cron';
import { json, problem, requireAuth } from './lib/http';
import type { Env } from './lib/types';
import { handleAlerts } from './routes/alerts';
import {
  handleCameraBundle,
  handleCameraVersion,
  handleCameras,
} from './routes/cameras';
import { handleHealth } from './routes/health';
import {
  handleCreateReport,
  handleRetractReport,
  handleVoteReport,
} from './routes/reports';
import { handleRoute, handleSearch } from './routes/route';
import { handleStyle } from './routes/style';
import { handleTile } from './routes/tiles';
import { refreshCameras } from './cameras/ingest';
import { pollFeeds } from './cron';

const REPORT_VOTE = /^\/v1\/reports\/([A-Za-z0-9-]{8,64})\/(confirm|deny)$/;
const REPORT_ID = /^\/v1\/reports\/([A-Za-z0-9-]{8,64})$/;

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method.toUpperCase();

    if (method === 'OPTIONS') {
      return new Response(null, { status: 204 });
    }

    // Tiles and the style document are public: they carry no user data, and
    // MapLibre Native cannot attach a bearer token to its tile requests.
    if (path.startsWith('/tiles/')) return handleTile(request, env);
    if (path === '/v1/style.json') return handleStyle(request, env);

    // Everything else is behind the shared app token.
    const unauthorised = requireAuth(request, env);
    if (unauthorised) return unauthorised;

    if (path === '/v1/health') return handleHealth(env);

    if (path === '/v1/alerts' && method === 'GET') {
      return handleAlerts(request, env, ctx);
    }

    if (path === '/v1/cameras' && method === 'GET') return handleCameras(request, env);
    if (path === '/v1/cameras/version') return handleCameraVersion(env);
    if (path === '/v1/cameras/bundle') return handleCameraBundle(env);

    if (path === '/v1/reports' && method === 'POST') {
      return handleCreateReport(request, env);
    }

    const vote = REPORT_VOTE.exec(path);
    if (vote && method === 'POST') {
      return handleVoteReport(request, env, vote[1]!, vote[2] === 'confirm');
    }

    const retract = REPORT_ID.exec(path);
    if (retract && method === 'DELETE') {
      return handleRetractReport(request, env, retract[1]!);
    }

    if (path === '/v1/route' && method === 'GET') return handleRoute(request, env);
    if (path === '/v1/search' && method === 'GET') return handleSearch(request, env);

    // Manual triggers, so a new deployment does not have to wait for the cron.
    if (path === '/v1/admin/poll' && method === 'POST') {
      ctx.waitUntil(pollFeeds(env, Date.now()));
      return json({ started: true });
    }
    if (path === '/v1/admin/cameras' && method === 'POST') {
      const report = await refreshCameras(env, Date.now());
      return json({ report });
    }

    return problem(404, 'no such endpoint');
  },

  scheduled,
};
