# Standing up the backend

All of this is done in the Cloudflare dashboard. Nothing here needs a terminal,
with one exception noted at the end (the basemap file, which is too big for a
browser upload and wants a desktop S3 client instead).

You have two Cloudflare accounts. Pick one and stay in it — the bindings below
are per-account and mixing them will produce confusing "resource not found"
errors on deploy.

## 1. The database

**Storage & Databases → D1 → Create database.** Name it `radar-au`.

Open the new database, choose the **Console** tab, and paste in the contents of
`worker/migrations/0001_init.sql`. Run it. You should end up with six tables:
`alerts`, `cameras`, `reports`, `report_votes`, `active_regions`,
`source_status`.

Copy the **Database ID** from the database's overview page. You will paste it
into `worker/wrangler.toml` where it currently says `PASTE_FROM_DASHBOARD`.

## 2. The cache

**Storage & Databases → KV → Create namespace.** Name it `radar-au-cache`.
Copy its ID into `wrangler.toml` the same way.

## 3. The bucket

**R2 → Create bucket.** Name it `radar-au-tiles`. Leave the location on
automatic. No public access — the Worker reads it through its binding, so the
bucket never needs to be exposed.

## 4. The Worker

**Compute → Workers & Pages → Create → Import a repository.** Point it at
`leonlangkau/maps`, branch `claude/mobile-app-maps-traffic-4zln20`.

Set the build configuration:

- **Root directory:** `worker`
- **Build command:** `npm install`
- **Deploy command:** `npx wrangler deploy`

Cloudflare will build and deploy on every push to that branch from then on, so
this is the last time you touch deployment.

## 5. Bindings

On the Worker: **Settings → Bindings → Add**, three times.

| Type | Variable name | Points at |
|---|---|---|
| D1 database | `DB` | `radar-au` |
| KV namespace | `CACHE` | `radar-au-cache` |
| R2 bucket | `TILES` | `radar-au-tiles` |

The names in the left column are not cosmetic — the code looks for exactly
these.

## 6. Secrets

**Settings → Variables and Secrets → Add.** Tick **Encrypt** on every one of
these, which makes them write-only: nobody, including you, can read them back
out of the dashboard afterwards.

| Name | What it is | Required |
|---|---|---|
| `APP_TOKEN` | A long random string you invent. The apps send it. | Yes |
| `MAPBOX_TOKEN` | Mapbox token for Directions and Geocoding | For routing and search |
| `NSW_API_KEY` | Transport for NSW open data | For NSW |
| `QLD_API_KEY` | QLDTraffic | For QLD |
| `VIC_API_KEY` | VicRoads Data Exchange | For VIC |
| `TOMTOM_API_KEY` | TomTom, for live congestion | Optional |

Where each of these comes from is in [data-sources.md](data-sources.md).

For `APP_TOKEN`, any long random string works. Generate it somewhere you trust
and paste the same value into both apps.

Western Australia needs no key — Main Roads publish incidents openly.

## 7. Plain variables

Also under **Variables and Secrets**, but *not* encrypted, so you can see and
change them later:

| Name | Suggested value | What it does |
|---|---|---|
| `ACTIVE_STATES` | `NSW` | Which states to poll. Comma-separated. |
| `WAZE_ENABLED` | `false` | Leave off until you have read the Waze doc. |
| `PMTILES_KEY` | `australia.pmtiles` | The basemap object name in R2. |

Set `ACTIVE_STATES` to just the states you actually drive in. Every extra state
is another feed polled every two minutes for data nobody is looking at.

## 8. Check it

Visit `https://<your-worker>.workers.dev/v1/health` with the header
`Authorization: Bearer <your APP_TOKEN>`. Any API client will do this; the
dashboard's own **Quick Edit → Preview** pane will not, because it cannot set
headers.

The response lists every source, whether it is enabled, when it last succeeded
and what it last returned. All zeroes immediately after deploy is expected —
the first cron has not fired yet. To skip the wait, POST to `/v1/admin/poll`
with the same header.

Then POST to `/v1/admin/cameras` once to build the camera bundle.

## 9. The basemap

This is the one step the dashboard cannot finish, because R2's browser uploader
caps out at 300 MB and an Australia-wide basemap is well past that.

Get the file: Protomaps publish daily planet builds and a `pmtiles extract`
tool that pulls a region out of one without downloading the whole thing. See
<https://docs.protomaps.com/basemaps/downloads>.

Then upload it, choosing whichever fits:

- **A state-sized extract, or capped at zoom 13.** Often under 300 MB, in which
  case **R2 → radar-au-tiles → Upload** in the dashboard just works. Zoom 13 is
  a little coarse for street level but perfectly usable for a highway app.
- **Cyberduck** (or any S3 desktop client). R2 speaks the S3 API, so a GUI
  client handles multi-gigabyte uploads with a drag and drop. Create an R2 API
  token under **R2 → Manage R2 API Tokens**, and connect Cyberduck to
  `https://<account-id>.r2.cloudflarestorage.com` with it.

Name the object to match `PMTILES_KEY`. Then `/v1/style.json` will return a
style and `/tiles/{z}/{x}/{y}.mvt` will serve tiles.

Until the basemap is uploaded the apps will show alerts on a blank background.
Everything else works.
