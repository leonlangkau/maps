# Speed, and traffic

Two small subsystems that both come down to the same thing: showing a number a
driver can trust at a glance.

## The speedometer

### Why the raw value is already good

Phones do not derive speed by differencing positions. They read it off the
Doppler shift on the GNSS carrier, which is a direct measurement rather than a
computed one, and it is typically accurate to a few tenths of a metre per
second — better than the car's own speedometer, which Australian Design Rules
require to read high and never low.

So the app is not trying to correct the reading. It is trying not to ruin it.

### What actually needs fixing

The raw value is accurate but not *steady*. It jitters by a few tenths from fix
to fix, and a display flickering between 98 and 101 reads as broken even while
it is more correct than the dashboard.

That gives one job and one constraint: smooth the jitter, and never lag real
acceleration. A readout that takes three seconds to notice you braked is worse
than one that wobbles.

### How

**Adaptive smoothing.** An exponential filter whose time constant depends on how
big the change is: 1.2 s for small movements, so noise averages out, and 0.15 s
once the change exceeds 1.5 m/s, so real braking is followed within a fix or
two.

**A plausibility gate.** Smoothing alone cannot tell a hard stop from a GPS
glitch — tuned fast enough to follow real braking, it follows a spike just as
faithfully. Physics can tell them apart. A road car manages about 4.5 m/s²
under power and about 9 m/s² braking hard on dry tarmac, so a reading outside
that window since the last fix did not happen, and is clamped to what could
have. This is what stops a single bad fix throwing 68 m/s onto the dial.

**A deadband at rest.** Below 0.6 m/s the output is a flat zero. GNSS noise keeps
a parked car reading one or two km/h, and a speedometer that will not sit still
at a red light looks broken.

**Holding through a bad patch.** A fix the platform flags as unreliable — poor
speed accuracy, or the negative value both iOS and Android use for "no idea" —
holds the last good number for three seconds, then gives up and says so rather
than showing a stale number indefinitely. The dial dims while it is coasting.

**Snapping after a gap.** More than five seconds without a fix is a tunnel, not
a sample. The filter restarts rather than crawling up from the speed you were
doing before you went under.

The filtered value feeds the alert engine too, not just the display — otherwise
a GPS spike would briefly stretch every warning range, which is the failure you
would least want.

### Why the dial is not glass

Everything else in the app is glass. The speedometer is a solid disc with a red
ring, and that is deliberate.

Glass is lovely for chrome you glance past, and wrong for the number you check
most often: it borrows whatever the map is doing underneath, so the same digits
sit on dark asphalt one second and pale parkland the next. A solid disc reads
identically over anything, at a glance, in sunlight.

The ring is red and circular because that is the shape an Australian driver
already reads as "a speed number". The posted limit sits beside it as a plainly
different object — a small labelled pill — so the two can never be mistaken for
each other.

## Traffic

### Routing

Routes come from Mapbox's `driving-traffic` profile, so they are already routed
around congestion rather than merely reporting it.

### Showing where the delay is

The Worker asks for the `congestion` annotation, which gives a level for each
pair of adjacent points on the route: `low`, `moderate`, `heavy`, `severe`.

The app splits the route into runs of equal level and draws each as its own line,
sharing boundary points so there is no gap where the colour changes. The result
is that a driver can see *where* the jam is, not just that the ETA went up.

A provider with no live traffic — a self-hosted Valhalla, say — sends an empty
array, which produces one `unknown` span covering the whole route. The map then
draws a single flat colour rather than nothing.

### Keeping the ETA honest

A route built while the motorway was clear is a lie twenty minutes into a jam,
so the route is re-requested every two minutes while navigating. That refreshes
both the ETA and the colouring.

It is a refresh, not a reroute — the driver has not gone wrong — so it happens
silently. One wrinkle: a fresh route restarts its step numbering, which would
re-announce the turn already being approached. The step counter is nudged
forward on each refresh to prevent that.

### Choosing between alternatives

The picker shows each route's live duration, how much of it is traffic
(`durationS` against Mapbox's `duration_typical`), and what you would drive past
on it.

That last part is the point. Comparing routes on time alone throws away the one
axis this app knows about, and *"three minutes longer, nothing on it"* is a
trade a driver can actually make.
