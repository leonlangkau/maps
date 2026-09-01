# Building the apps

Neither app has ever been compiled. They were written in a Linux container with
no Xcode and no Android SDK, so the first build of each will almost certainly
turn up small things — a missing import, an API that moved between library
versions. The parts that were testable have been tested: the Worker (61 tests)
and the Kotlin alert, route and API engines (42 tests).

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

## iOS

**Needs:** a Mac, Xcode 15+, and an Apple Developer account ($99/year) to run on
a real phone. The simulator has no GPS worth testing against, so a device is
effectively required.

The Xcode project is generated rather than committed, because `.xcodeproj` files
are merge-conflict machines:

    brew install xcodegen
    cd ios/App
    xcodegen generate
    open RadarAU.xcodeproj

Then set your backend details. Either add `RadarBaseUrl` and `RadarAppToken` to
the generated `Info.plist`, or set `RADAR_BASE_URL` and `RADAR_APP_TOKEN` in the
scheme's environment variables for a quick test — `AppConfig.swift` reads both.

Set your signing team on the RadarAU target, then Run.

To run the engine tests without the app:

    cd ios/RadarKit
    swift test

Those are the same 22 fixtures the Kotlin engine runs. If they pass on both
sides, the two apps agree about when to warn you.

### Things to expect

- Xcode will resolve MapLibre from
  `https://github.com/maplibre/maplibre-gl-native-distribution` on first open.
  It is a large binary; give it a minute.
- The location purpose strings in `project.yml` say plainly what location is for.
  Keep them that way — vague purpose strings get apps rejected, and these are
  accurate.
- Background location needs the `location` background mode, which is already in
  `project.yml`. Apple asks what it is for at review time; "warning drivers about
  hazards ahead while the screen is off" is a legitimate answer.

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
- **Alternative routes.** The Worker asks Mapbox for them and the API returns
  them; neither app offers a way to pick one.
