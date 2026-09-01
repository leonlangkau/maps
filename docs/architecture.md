# Architecture

## The shape of it

The phone is not clever. It holds a map, a list of cameras, a list of hazards,
and the engine that decides when to speak. Everything upstream of that — six
government feeds in five formats, a commercial traffic API, an unofficial
crowd-source endpoint, and other drivers' reports — is reconciled by the Worker
into one list of things with a position and a severity.

That split is deliberate. Feed formats change, agencies re-publish datasets,
and endpoints move. When that happens it should be a Worker deploy, not an app
release waiting on App Store review while the app shows nothing.

## The backend

One Worker, `worker/`, doing four jobs.

**Polling.** A cron fires every two minutes and asks every configured source for
what is live. Sources are polled concurrently and failures are isolated: a dead
VicRoads token must not stop NSW from updating. Each source's last success,
last attempt and last error are recorded, because a feed that quietly stops
returning data looks exactly like a quiet road, and `/v1/health` is what tells
the two apart.

**Merging.** Each source has an adapter that maps its shape onto one `Alert`
model. NSW nests road names inside a `roads` array; Queensland uses
`road_summary`; ArcGIS uses whatever the publishing agency felt like. The
adapters absorb that so nothing downstream has to know.

**Serving.** `/v1/alerts?bbox=` returns hazards for a window, padded three
kilometres beyond what the driver can see so something just off-screen is
already loaded by the time they reach it. Nearby devices share a cache entry:
the bbox is snapped to a grid before it becomes a cache key, so two people in
the same suburb cost one query rather than two.

**Serving tiles.** The basemap is a single PMTiles archive in R2. Tile requests
become byte-range reads into that one object. There is no tile server, no
per-tile storage, and R2 charges nothing for egress.

### Only polling where people are

Waze and TomTom are queried per bounding box, so a naive implementation would
crawl the whole continent every two minutes: expensive with TomTom's 2,500
requests a day, and conspicuous with Waze.

Instead, every `/v1/alerts` request records its (snapped) bounding box in an
`active_regions` table. The cron only polls cells seen in the last hour, capped
at six boxes for Waze and three for TomTom. With nobody driving, the app makes
no outbound requests at all; with three friends driving in Sydney, it makes one.

## Storage

**D1** holds alerts, cameras, reports and votes. Bounding-box queries run against
an index on `(lat, lon)`. There is no spatial index and none is needed: this is
a few thousand rows, and the alternative — geohash prefixes — would be more code
for no measurable gain at this size.

**KV** caches merged bbox responses for 45 seconds and holds the Waze circuit
breaker.

**R2** holds the basemap archive and the offline camera bundle.

## Community reports

Reports are keyed to a random UUID the phone generates on first launch. It is
not the device id, not an advertising id, not tied to an account. It exists to
rate-limit one device to twelve reports an hour and to enforce one vote per
report, and it vanishes when the app is uninstalled.

Reports do not become rows in the alerts table. They stay in `reports` and are
converted at read time, which keeps the confidence arithmetic — 0.35 to start,
±0.2 per vote — in exactly one place. A report at low confidence chimes rather
than speaks, and three denials remove it.

## The app

Cameras are downloaded once as a bundle and kept on disk. This is the whole
reason the app works on a country highway: the cameras are already there and
the network is only needed for live hazards. When the network goes, the app
degrades to cameras-only rather than to nothing, and says so on screen.

Hazards refetch on distance travelled rather than on a timer, because a fixed
interval is either wasteful in traffic or too slow on a highway.

The alert engine is pure — same inputs, same output, no clock, no I/O — which
is what makes it testable, and it is implemented twice against one shared file
of fixtures. See [alert-engine.md](alert-engine.md).

## Choices worth defending

**MapLibre and self-hosted tiles over Mapbox's map.** Mapbox charges per map
load and per monthly active user. An always-on driving app is close to the worst
possible shape for that pricing. MapLibre renders the same vector tiles with a
near-identical API, and one PMTiles file in R2 costs a few dollars a month
regardless of how much anyone drives.

**Mapbox for routing anyway.** Routing is genuinely hard and Cloudflare Workers
cannot host a routing engine. Mapbox Directions has the most generous free tier
of the hosted options. One caveat is recorded in [data-sources.md](data-sources.md):
Mapbox's terms have historically restricted displaying their API results on a
non-Mapbox map, which is exactly what this does. That clause could not be
verified while this was written, so routing sits behind an interface with a
Valhalla implementation already in place.

**Two native apps rather than one cross-platform one.** An always-on driving app
lives or dies on background location, battery behaviour and CarPlay/Android
Auto, and all three are better served natively. The cost is the alert engine
existing twice, which the shared fixtures are there to contain.

**A shared token rather than accounts.** For a handful of known users, accounts
are all cost and no benefit. This is not a security boundary and the code says
so where it matters — it stops the endpoint being trivially scraped, nothing
more. Going wider means real per-user credentials first.
