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
mode, so both are tested against the same file of hand-written cases,
`shared/alert-engine-fixtures.json`. A change to the rules means changing the
fixtures first, then making both sides pass.

## The pipeline

A threat is any camera or hazard. Each tick, every threat runs this gauntlet and
at most one announcement comes out the far side.

### 1. Is it ahead of me?

Anything beyond 3 km is ignored outright. For the rest, we compare the bearing
from the car to the threat against the car's heading.

The tolerance narrows with speed. At 100 km/h the road ahead is effectively a
straight line and anything 40° off your nose is on a different road, so the cone
is tight. Crawling through a suburb, the next turn could put almost anything in
front of you, so it opens up:

    coneHalfAngle = clamp(70 - speedKmh * 0.45, 22, 70)

That is 70° at a standstill, 47° at 50 km/h, 25° at 100 km/h.

With no heading at all — stopped, or GPS has not settled — direction is
meaningless, so we fall back to a plain 500 m radius.

### 2. Does it apply to my side of the road?

Fixed cameras and many hazards only affect one carriageway. When a threat
carries its own bearing, the car's heading must be within 60° of it. This is
what stops a northbound camera firing at you on the southbound lanes of a
divided highway.

Threats with no bearing are assumed to apply both ways, which is the safe
default: a false warning costs you a glance, a missed one costs more.

### 3. Is it time yet?

Warning distance is time-based, not distance-based, because 500 m is ten seconds
of highway and a full minute in traffic. Each threat gets a lead time and the
range follows from current speed:

| Threat                | Lead time |
|-----------------------|-----------|
| Camera                | 18 s      |
| Critical (severity 3) | 30 s      |
| Major (severity 2)    | 22 s      |
| Minor (severity 1)    | 15 s      |

    triggerRange = clamp(speedMps * leadSeconds, 250 m, 1500 m)

The floor stops warnings arriving impossibly late in slow traffic; the ceiling
stops a highway alert firing while the thing is still two suburbs away. Below
8 km/h the whole model breaks down, so it reverts to a flat 400 m.

### 4. Do I believe it?

Government feeds are certain. A single unconfirmed tap from one stranger is not.

- Confidence below 0.3: dropped. Not shown, not spoken.
- Confidence below 0.5: capped at severity 1, so it chimes but never speaks.
- Cameras: always spoken. A camera dataset is either right or out of date, and
  out of date is not the same as unreliable.

### 5. Have I already said this?

Three guards, in order:

- **Per-threat cooldown, 10 minutes.** Sitting in a jam beside a hazard should
  not produce a warning every tick.
- **Global gap, 6 seconds.** Two warnings on top of each other are one warning
  nobody parsed.
- **Passed threats.** Once a threat is behind you and receding it is retired for
  good, not merely cooled down.

### 6. Pick one

Whatever survives is sorted by severity first, then by distance, and the single
top item is announced. Never two.

## Announcement levels

- `chime` — a tone. Low-severity and low-confidence items.
- `speak` — spoken through the car audio: `"Speed camera, 600 metres"`.

Distances are read the way a person would say them: to the nearest 100 m below
a kilometre, to one decimal place above it.

## Deliberate omissions

**No alerts behind you.** Some apps warn about cameras you have already passed
on the theory that you might turn around. In practice it trains you to ignore
the voice.

**No speed-limit enforcement.** The app never says "you are speeding". It says
where the camera is and what the limit is there, and leaves the arithmetic to
the driver.

**No alerts while stationary for more than a minute.** Parked next to a camera
should be silent.
