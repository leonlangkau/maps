# The Waze layer

This is the one component with real risk attached. It ships disabled
(`WAZE_ENABLED=false`) and this document is why.

## What it does

Waze's live map is backed by an endpoint that returns crowd-sourced alerts for a
bounding box — police, crashes, hazards, closures, all reported by drivers.
That is the thing the government feeds cannot give you, because no road
authority publishes "someone saw a police car here nine minutes ago". It is also
the single feature that makes Waze worth using.

`worker/src/sources/waze.ts` reads that endpoint and folds the results into the
same alert model as everything else.

## Why it is off by default

**It is not an API.** There is no documentation, no terms permitting this use,
and no stability promise. Waze's supported data-sharing programme is Waze for
Cities, and it is open to government agencies and private road operators, not to
individuals. Reading the live map endpoint is outside what Waze permits.

**Waze is owned by Google, who also review the Play Store listing.** For a
sideloaded app among friends this is close to irrelevant. For a published app it
is a genuine removal risk, and it would be the kind that arrives without warning
after the app already has users.

**It can break at any time**, and it can break by *changing shape* rather than
by erroring, which is the worse failure.

You chose to include it while keeping distribution to yourself and a few
people. That is a coherent position and the code supports it. It stops being
coherent the moment this goes on a store listing.

## How the risk is contained

**It only polls where somebody is driving.** Every `/v1/alerts` request records
its snapped bounding box; the cron polls only cells seen in the last hour,
capped at six per run and de-duplicated so two people in one suburb cost one
request. With nobody driving, no requests are made at all. This is not just
politeness — a continent-wide crawl every two minutes is exactly the traffic
pattern that gets an IP blocked.

**A circuit breaker.** If every request in a run fails, a flag goes into KV and
the source stops trying for thirty minutes. A block or a rate limit degrades to
"government feeds only" rather than a Worker hammering a closed door.

**Non-JSON is treated as a block, not as an empty area.** A challenge page
returning 200 with HTML would otherwise look like "no hazards here", which is
the most dangerous possible failure for a warning app: confident silence. The
adapter rejects any response that is not JSON.

**Reports are confidence-weighted.** Waze exposes reliability and confidence on
0–10 scales; these are blended into our 0–1 and a low-confidence report chimes
rather than speaks. One stranger's unconfirmed tap does not get a voice alert.

**Failure is isolated.** Waze failing does not stop NSW, Queensland or anything
else from updating, and `/v1/health` shows exactly what is stale.

## Turning it on

Set `WAZE_ENABLED` to `true` in the Worker's variables. Nothing else changes.
Watch `/v1/health` for the first day: `last_error` on the `waze` row tells you
whether it is working, and a circuit-breaker message tells you it is not.

## The way out of needing it

The community reporting built into the app does the same job without the
dependency: a driver taps "police", it appears for everyone else nearby, and
confirmations extend its life while denials kill it. Today that is worth little,
because it needs drivers to be worth anything.

But it is the only version of this that is yours, cannot be switched off by
someone else, and would survive the app becoming public. The Waze layer is
scaffolding while there is nobody using the app. It is worth treating as
temporary rather than as a feature.
