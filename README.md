# Radar AU

A speed camera and road hazard warning app for Australia, for iPhone and
Android. It tells you what is on the road ahead — cameras, crashes, closures,
flooding, roadworks — and otherwise stays quiet.

## What is here

| Piece | State |
|---|---|
| `worker/` — Cloudflare Worker backend | Complete, 67 tests passing |
| `android/core/` — alert, route, speed and API engines (pure Kotlin) | Complete, 81 tests passing |
| `shared/` — cross-platform engine fixtures | 43 cases |
| `ios/RadarKit/` — the same engines and client in Swift | Written, not yet compiled |
| `ios/App/` — SwiftUI app | Written, needs a Mac and Xcode |
| `android/app/` — Compose app | Written, needs the Android SDK |

The backend and the engines are the parts that are hard to get right, and both
are tested. The two app shells are written but have never been compiled — there
is no Xcode or Android SDK in the environment they were written in, so expect to
fix small things on the first build.

**[See the interface preview](ui-preview.html)** for the six screens, the glass
treatment on both platforms, and why Android paints its glass rather than
blurring it.

## How it fits together

    state gov feeds ─┐
    TomTom traffic ──┤
    Waze (optional) ─┼─► Cloudflare Worker ─► phone ─► alert engine ─► voice
    driver reports ──┘     (merge, cache)      (map)      (decides)

Everything runs on Cloudflare: Workers for the API and the cron polling, D1 for
the alert and camera tables, KV for hot caching, R2 for the basemap and the
offline camera bundle. There is no VPS and nothing to keep patched.

Routing is the one thing Cloudflare does not do, so `/v1/route` proxies Mapbox
Directions. The provider sits behind an interface, and a self-hosted Valhalla
implementation is already written for the day that arrangement stops suiting.

## Reading order

1. **[docs/architecture.md](docs/architecture.md)** — how the pieces fit and why.
2. **[docs/alert-engine.md](docs/alert-engine.md)** — how it decides whether
   something is on your road, in front of you, beside you, or not worth
   mentioning. This is the heart of the app.
3. **[docs/cloudflare-setup.md](docs/cloudflare-setup.md)** — standing the backend
   up, through the dashboard.
4. **[docs/data-sources.md](docs/data-sources.md)** — every feed, what it costs,
   and how to get a key.
5. **[docs/speed-and-traffic.md](docs/speed-and-traffic.md)** — why the
   speedometer is filtered the way it is, and why it is the one thing that is
   not glass.
6. **[docs/waze-layer.md](docs/waze-layer.md)** — the one component with real
   risk attached. Read before enabling it.
7. **[docs/building-the-apps.md](docs/building-the-apps.md)** — getting it onto a
   phone.
8. **[docs/ui-preview.html](docs/ui-preview.html)** — the interface, screen by
   screen. Open it in a browser.

## Is this legal?

The camera warnings: yes. GPS-based speed camera apps are legal in every
Australian state and territory — Waze and Google Maps both do it. What is
banned nationwide is *radar detectors*: hardware that detects or jams an
enforcement signal. This app has no such thing and never will. The distinction
in law is between detecting a signal and knowing a location, and this is
squarely the second.

The data: the state government feeds are open data published for exactly this
purpose. TomTom's free tier permits commercial use. The Waze layer is the
exception, and it has its own document.
