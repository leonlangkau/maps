# Building the apps

Neither app has ever been compiled. They were written in a Linux container with
no Xcode and no Android SDK, so the first build of each will almost certainly
turn up small things — a missing import, an API that moved between library
versions. The parts that were testable have been tested: the Worker (67 tests)
and the Kotlin alert, route, speed and API engines (81 tests).

Both apps need the backend running first. See
[cloudflare-setup.md](cloudflare-setup.md).

## Android

**Needs:** Android Studio, and a device or emulator on API 26+.

Open `android/` in Android Studio. The `:app` module only appears once an
Android SDK is visible — `settings.gradle.kts` skips it otherwise, so the core
tests can run on machines and CI boxes with no SDK.

Put your backend details in `android/local.properties`, which is gitignored:

    radarBaseUrl=https://radar-au.<your-subdomain>.workers.dev
    radarAppToken=<the APP_TOKEN you set in the Worker>

Then Run. Grant location "while using the app" when asked.

To run just the engine tests, from `android/`:

    gradle :core:test

That works with no SDK and no emulator, which is the point of keeping the engine
in a plain JVM module.

### Things to expect

- MapLibre's Android artifact is `org.maplibre.gl:android-sdk:11.5.1`. If the
  class names in `MapScreen.kt` do not resolve, the package moved between
  versions — check their release notes rather than guessing.
- `RadarApplication` must run `MapLibre.getInstance()` before any MapView is
  inflated. It does; if you restructure, keep that ordering.
- There is no launcher icon yet, so `@mipmap/ic_launcher` will fail to resolve
  until you add one via **File → New → Image Asset**.

## iOS — step by step

**Needs:** a Mac, Xcode, an Apple ID, an iPhone on iOS 16 or later, and a cable
for the first connection. The simulator has no real GPS, so a phone is the only
way to test what this app actually does.

### 1. Install the tools

- **Xcode**, from the Mac App Store. It is free and large (allow half an hour).
  Open it once, accept the licence, and let it install the iOS platform when it
  asks. Xcode 26 gives you real Liquid Glass; anything from Xcode 15 builds the
  app and falls back to the frosted material.
- **Homebrew**, if you do not have it: the one-line installer at
  <https://brew.sh>.
- **XcodeGen**: `brew install xcodegen`. The Xcode project is generated from
  `project.yml` rather than checked in, because `.xcodeproj` files are
  merge-conflict machines.

### 2. Get the code

    git clone https://github.com/leonlangkau/maps.git
    cd maps
    git checkout claude/mobile-app-maps-traffic-4zln20

### 3. Run the engine tests first

    cd ios/RadarKit
    swift test

This runs the alert engine, the route tracker and the speed filter against the
same fixtures the Kotlin side passes — without touching Xcode or a phone. If
this fails, the failure is in logic that needs fixing before it is worth
building the app around it; paste the output back and it is usually a one-line
mirror-translation slip.

### 4. Tell it where the backend is (optional for the first run)

    cd ../App
    cp Local.xcconfig.example Local.xcconfig

Edit `Local.xcconfig` with your Worker URL and the `APP_TOKEN` you set in
Cloudflare. It is gitignored. Skip this entirely for a first run: without a
backend the map is blank and there are no real cameras, but GPS, the
speedometer, settings and the test-camera button all work, and that is enough
to prove the app runs on your phone.

### 5. Generate and open the project

    xcodegen generate
    open RadarAU.xcodeproj

Xcode will resolve the MapLibre package on first open. It is a large binary;
give it a minute and watch the progress bar at the top.

### 6. Sign it

Free Apple ID is enough for your own phone.

1. **Xcode → Settings → Accounts → +** and sign in with your Apple ID.
2. Select the **RadarAU** project in the sidebar, then the **RadarAU** target,
   then **Signing & Capabilities**.
3. Tick **Automatically manage signing** and choose your name under **Team**
   (it will say "Personal Team").
4. If Xcode complains the bundle identifier is taken, change it to something
   unique — `au.radar.app.yourname` — in the same pane.

A free Personal Team has two limits worth knowing: the app stops launching
after **seven days** and has to be re-run from Xcode, and you can have at most
three such apps installed. The paid Developer Program (US$99 a year) removes
both and unlocks TestFlight for handing it to friends.

### 7. Put the phone in Developer Mode

On the iPhone: **Settings → Privacy & Security → Developer Mode → on**, then
restart it when asked. Plug it into the Mac and tap **Trust** on the phone.

### 8. Run it

In Xcode's toolbar, click the device picker (it says "Any iOS Device" or a
simulator name) and choose your iPhone. Press **⌘R**.

The first build is slow. **Expect red errors** — this code has never been
compiled, because there is no Xcode where it was written. Each one is almost
certainly a moved MapLibre API name, a missing `import`, or a Swift
strictness complaint. Copy the error text and paste it back; they are
one-line fixes, and there will be a handful rather than dozens.

When it builds, the phone will refuse to open it once: **Settings → General →
VPN & Device Management → your Apple ID → Trust**. Then launch Radar AU from
the home screen and grant location access when asked. Choose **Always** if
offered — it is what keeps warnings coming with the screen off.

### 9. Test a real warning with no backend

Open the settings sheet (gear button), tap **Plant a test camera 600 m ahead**,
and drive towards it — or walk, though the warning range scales with speed so
on foot it will fire late. You should get the spoken warning, the banner, and
the speed dial responding. If you hear it, the whole chain works: GPS, the
engine, the voice, the audio session. Everything after that is data.

### 10. Then connect the backend

Fill in `Local.xcconfig`, `xcodegen generate` again, rebuild. Now the map has
tiles, the camera bundle downloads, and hazards appear. Check the settings
sheet: "Cameras stored" should be non-zero and "Live hazards" should say
Connected.

### Things to expect

- The location purpose strings in `project.yml` say plainly what location is
  for. Keep them that way — vague purpose strings get apps rejected, and these
  are accurate.
- Background location needs the `location` background mode, which is already
  in `project.yml`. Apple asks what it is for at review time; "warning drivers
  about hazards ahead while the screen is off" is a legitimate answer.
- CarPlay is not built, and cannot be until Apple grants the navigation
  entitlement — see below.

## Getting it onto other phones

**Android:** build a release APK and send it. Sideloading needs no Play account
and no review. For more than a couple of people, a Play Console account ($25
once) and an internal testing track is tidier.

**iOS:** TestFlight, which needs the $99/year Apple Developer account. Internal
testing is up to 100 devices and does not need App Review. This is where the
Waze layer's risk stays acceptable — TestFlight builds are not on the store.

## What is not built yet

- **CarPlay.** Not an oversight and not something that can simply be written:
  a CarPlay *navigation* app needs the `com.apple.developer.carplay-maps`
  entitlement, which Apple grants only on request. Until they grant it the app
  will not build against the CarPlay templates at all.
- **Android Auto.** Feasible — it needs a `CarAppService` and the Car App
  Library — but it is a large surface that cannot be verified without a device
  and the Desktop Head Unit, so it is deliberately left out rather than shipped
  untested.
- **On-device import of a licensed camera set.** See
  [data-sources.md](data-sources.md).
