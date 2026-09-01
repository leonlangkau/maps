# Data sources

What each feed gives you, what it costs, and how to get a key.

## State government hazard feeds

These are the backbone. They are open data, published by road authorities for
exactly this purpose, free, and there is no licensing question about using them.

### New South Wales — best of the lot

`https://api.transport.nsw.gov.au/v1/live/hazards/{category}/open`,
authenticated with a header of `Authorization: apikey YOUR_KEY`.

Six categories, all polled: `incident`, `roadwork`, `fire`, `flood`,
`majorevent`, `alpine`. GeoJSON, with `isMajor` and `isEnded` flags the adapter
uses for severity and filtering, and a `roads` array carrying road and suburb.

**Getting a key:** register at <https://opendata.transport.nsw.gov.au>, create an
application, and subscribe it to the Live Traffic APIs. Free.

### Queensland

`https://api.qldtraffic.qld.gov.au/v2/events?apikey=YOUR_KEY`

One endpoint for everything: crashes, hazards, congestion, roadworks, flooding
and special events, with an `event_priority` from `lowest` to `highest` that
maps cleanly onto our severity scale.

**Getting a key:** request one through the developer page at
<https://qldtraffic.qld.gov.au/more/Developers-and-Data/index.html>. Free.

### Victoria — the awkward one

VicRoads moved to a Data Exchange platform and retired the old Disruptions API.
The replacement is not self-service: you email
`traffic_requests@vicroads.vic.gov.au` and ask for a token. It rate-limits to
about three calls a minute with a ten-minute cache, which our two-minute cron
sits comfortably inside.

Because the endpoint path moved and could not be confirmed without a token, the
adapter reads it from a `VIC_API_URL` variable and falls back to a documented
default. When your token arrives, set that variable to whatever path the
documentation you are sent specifies — no code change, no redeploy.

### Western Australia — no key needed

Main Roads publish incidents through their ArcGIS Hub at
`https://opendata.arcgis.com/datasets/a385c672d93c4a88b5b232e7fe38f915_0.geojson`.

Column names vary the way agency ArcGIS layers do, so the adapter tries several
candidates for each field. If Main Roads re-publish the layer, change the
dataset id at the top of `worker/src/sources/wa.ts`.

### South Australia, Tasmania, Northern Territory, ACT

Not wired up. None publishes an incident feed as clean as the four above. When
you need one, `worker/src/sources/geojson.ts` is a configurable adapter — a new
state is a config block, not a new file. Follow the WA one as a template.

## Speed cameras

### What is wired up

Queensland publishes active mobile speed camera sites and camera trailer sites
through their CKAN open data portal, updated quarterly, with stable resource
ids. Both are ingested.

### What needs a resource id pasted in

NSW publishes fixed, red-light-speed and mobile camera locations; Victoria
publishes its mobile camera locations. Both are CKAN portals, so the ingest code
already handles them — but the resource ids need to come from the portal pages,
and are deliberately left blank rather than guessed, because a wrong id imports
the wrong dataset silently.

Find them at:

- NSW: <https://opendata.transport.nsw.gov.au/dataset/nsw-speed-cameras>
- VIC: <https://discover.data.vic.gov.au/dataset/road-safety-camera-network-mobile-camera-locations>

On the dataset page, open the resource you want and copy the id out of the URL.
Paste it into `worker/src/cameras/datasets.ts` and set `verified: true`.

### ExCam and SCDB

ExCam is the format Highway Radar consumes; SCDB.info sells a worldwide database
for around ten euros a year with better nationwide coverage than the free
government sets, including average-speed and point-to-point cameras.

That data is licensed, and a licence to use it is not a licence to redistribute
it. As long as this is a handful of people it makes little difference, but the
architecture keeps the two apart on purpose: government data ships in the
bundle from the server, and a licensed set should be imported on the device by
whoever holds the licence. The device-side import is not built yet.

## Live congestion — TomTom

Optional. Adds flow-based congestion and commercial incident data on top of the
government feeds.

The free tier is 2,500 non-tile requests a day across *all* TomTom services,
which is why this is bbox-scoped and capped at three boxes per two-minute run.
Commercial use is permitted on the free tier.

**Getting a key:** register at <https://developer.tomtom.com>.

## Routing and search — Mapbox

`/v1/route` and `/v1/search` proxy Mapbox Directions and Geocoding. The token
lives as a Worker secret and never ships inside either app, which is the main
reason those endpoints exist as proxies rather than direct calls.

Free tier is generous — 100,000 directions requests a month.

**One caveat, unresolved.** Mapbox's terms have historically restricted
displaying results from their APIs on a non-Mapbox map, and this app draws
Mapbox routes on a MapLibre map with self-hosted tiles. That clause could not be
checked while this was written, because Mapbox's domains were unreachable from
the environment it was written in. Worth five minutes of your reading before you
depend on it.

If it turns out to be a problem there are two outs, and neither is a rewrite:
use Mapbox's own tiles for the map, or point `VALHALLA_URL` at a self-hosted
Valhalla — that implementation is already written in
`worker/src/routing/valhalla.ts`, and would want a small VPS with the Australia
OSM extract.

## The basemap — Protomaps and OpenStreetMap

OpenStreetMap data, built by Protomaps into a single PMTiles archive, served out
of R2. No API key, no per-load cost, no vendor.

Attribution is required and is already in the generated style document. Leave it
visible.
