# The alert engine

This is the part of the app that matters. Everything else — maps, feeds, routing
— exists so that this can decide one thing correctly:

> Given where I am, how fast I am going and which way I am pointing, is there
> something ahead worth interrupting me about, and is now the moment to say it?

Getting it wrong in either direction ruins the app. Announce too much and it
becomes noise you learn to ignore, which is worse than silence because you stop
hearing the one that mattered. Announce too late and the warning arrives after
the camera.

Because the app is native on both platforms, this logic exists twice: once in
Swift, once in Kotlin. Two implementations drifting apart is the obvious failure
mode, so both are tested against the same file of cases,
`shared/alert-engine-fixtures.json`. Those expectations are produced by a *third*
implementation of these rules, written in Python — so a bug the Swift and Kotlin
engines happen to share still fails the suite. Changing the rules means changing
this document, then the Python, then making both platforms pass.

## The central question: is it on my road?

Everything turns on one distinction. A police car a kilometre ahead **on the road
you are driving** is worth knowing about now. A police car a kilometre away **on
some other road** is not — you may never go near it. Treating those the same
either floods you with irrelevance or warns you far too late about the one that
counts.

Four answers, in the order the engine tries them.

### On the route (best answer)

When you are following a route, the app already knows the road. A threat within
**45 m of the route line** and in front of you is on your road, and the distance
that matters is the distance **along the road**, not the straight line.

This is the only answer that survives a bend. A police car 1 km ahead around a
sweeping curve can sit 60° off your current heading — invisible to anything
derived from an instantaneous compass reading. Following the route sees it.

### The corridor (no route set)

Without a route, the best available proxy is a narrow corridor straight down your
heading. Split the distance to the threat into how far ahead it is and how far to
one side:

    cross-track = distance × sin(angle off my heading)

If the cross-track is inside the corridor and the threat is in front, call it my
road. The corridor is not a fixed width, because a fixed width is wrong at both
ends — too generous close up, where it swallows the parallel street, and too mean
far off, where one degree of heading noise moves it by tens of metres:

    corridor half-width = min(40 m + distance × 0.02, 90 m)

That is 40 m alongside you, 50 m at 500 m, 80 m at 2 km, and capped at 90 m
before it can reach the next road over. Australian suburban blocks put parallel
roads 100 m or more apart, so the cap is what keeps the next street out.

### The cone (in front, but not necessarily my road)

Anything else within the forward cone is in front of you but off the corridor: a
service road, a slip lane, a bend the corridor could not follow. It gets a normal
warning rather than the long one.

The cone narrows with speed. At 100 km/h the road ahead is effectively straight
and anything 40° off your nose is somewhere else; crawling through a suburb, the
next turn could put almost anything in front of you:

    cone half-angle = clamp(70 - speedKmh × 0.45, 22, 70)

70° at a standstill, 47° at 50 km/h, 25° at 100 km/h.

### The radius (geometry gave up)

Beyond about 120° a threat is beside or behind you, and beyond that nothing is
raised at all — something behind you has already been passed. But **inside a
radius, direction stops mattering**. A police car at the intersection you are
approaching sits at nearly 90°. One just around a corner you cannot see is off in
some arbitrary direction. Within the radius the app stops asking which road it is
on and simply tells you it is near.

The radius is per kind, because a police car is worth knowing about from further
away than a red-light camera you are not approaching.

## How far ahead is "far enough"?

Warning distance is a lead **time**, not a distance, because 500 m is ten seconds
of highway and a full minute in traffic:

    lead range = clamp(speed × lead seconds, 300 m, 2000 m)

And the lead time is multiplied when the threat is on your road:

    on my road → lead seconds × 1.7

That multiplier is the whole point of the distinction above. Police default to a
45-second lead, so on your own road at 100 km/h you are warned about 2 km out —
roughly 76 seconds. On a different road in front of you, the same police car
waits until 1250 m.

Then the two questions get two different ranges:

| Where it is | Range that applies |
|---|---|
| On my road, or in front | `max(radius, lead range)` |
| Beside me | the radius, and only the radius |

The second row is not an oversight. Letting a long lead time reach sideways would
warn you about a police car half a kilometre off your route purely because you
happened to be going fast — but the direction you are travelling tells you
nothing about how soon you reach something beside you.

Below 8 km/h the time model collapses, so the range becomes a flat 400 m. With no
usable heading at all — stopped, or GPS has not settled — everything is simply
`NEARBY`.

## Per-kind settings

The kinds are not equivalent, so they are not configured together. A fixed camera
is at a known point and wants a fixed distance of warning. A police car is
somewhere in an area, might be facing either way, and is worth knowing about
earlier and more insistently. Roadworks, most of the time, you want on the map
and out of your ears.

Every kind has four settings the driver can change: on or off, spoken or a tone,
flashing or not, and its lead time and radius. The defaults are opinionated:

| Kind | Lead | Radius | Voice | Flash |
|---|---|---|---|---|
| Police | 45 s | 500 m | yes | **yes** |
| Mobile camera (reported) | 40 s | 450 m | yes | **yes** |
| Mobile camera zone | 35 s | 400 m | yes | **yes** |
| Road closed | 45 s | 500 m | yes | **yes** |
| Crash | 35 s | 400 m | yes | **yes** |
| Fixed speed camera | 25 s | 300 m | yes | no |
| Red light camera | 22 s | 250 m | yes | no |
| Heavy traffic | 20 s | 250 m | tone | no |
| Roadworks | 15 s | 200 m | tone | no |

## The screen flash

Some warnings are worth not missing. Those pulse the screen three times as well
as speaking.

Two deliberate choices. It flashes the **edges** rather than the whole screen: a
full-screen white-out at night ruins your night vision and hides the map at the
exact moment you want to look at it. And it **keeps working when the app is
muted** — someone driving with the radio up is precisely the person who needs the
visual instead of the voice.

Turning off animations system-wide replaces the pulse with one steady glow.

## Do I believe it?

Government feeds are certain. A single unconfirmed tap from one stranger is not.

- Confidence below 0.3: dropped. Not shown, not spoken.
- Confidence below 0.5: capped at severity 1, so it chimes but never speaks.
- Cameras: always spoken. A camera dataset is either right or out of date, and
  out of date is not the same as unreliable.

## Have I already said this?

Three guards, in order:

- **Per-threat cooldown, 10 minutes.** Sitting in a jam beside a hazard should
  not produce a warning every tick.
- **Global gap, 6 seconds.** Two warnings on top of each other are one warning
  nobody parsed.
- **Passed threats.** Once a threat is behind you and receding it is retired for
  good, not merely cooled down.

Whatever survives is sorted by severity first, then by distance, and the single
top item is announced. Never two.

## Announcement levels

- `chime` — a tone. Low-severity items, kinds the driver set to tone-only, and
  reports we only half believe.
- `speak` — spoken through the car audio: `"Police reported, 1.4 kilometres"`.

Distances are read the way a person would say them: to the nearest 100 m below a
kilometre, to one decimal place above it.

## Deliberate omissions

**No alerts behind you.** Some apps warn about cameras you have already passed on
the theory that you might turn around. In practice it trains you to ignore the
voice.

**No speed-limit enforcement.** The app never says "you are speeding". It says
where the camera is and what the limit is there, and leaves the arithmetic to the
driver.

**No alerts while stationary for more than a minute.** Parked next to a camera
should be silent.

**No radar or laser detection, ever.** That hardware is illegal in every
Australian state and territory. This app knows where things are; it does not
listen for them. The distinction is the whole reason it is legal.
