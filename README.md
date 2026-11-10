# Channel Surfer — 1990s Cable TV for Android

A native Android app that recreates watching American cable in the 1990s: numbered channels, a blue grid program
guide, tuning static, and **24/7 simulated-live** programming built from approved Internet Archive VHS recordings,
original commercials included.

Broadcast rules are enforced in code, not just UI: there is no pause, seek, rewind, fast-forward, skip, restart or
episode picker anywhere. Every channel is a pure function of a UTC clock, so leaving for ten minutes means missing
ten minutes, and returning always joins the program where it is *now*.

## What's in the box

| Module | What it is |
|---|---|
| `core/` | Pure Kotlin: deterministic schedulers (cyclic, fixed, cable-style dayparted), tune resolver (substitution, drift), guide builder, manifest codec + strict validation, Archive parsing / file selection / content classifier, retry policy, numeric entry, signatures. 75 unit tests. |
| `app/` | Android app: Compose UI (TV surface, blue grid EPG, remote, setup, curation, diagnostics), Media3/ExoPlayer controller, Room, DataStore, OkHttp Archive client, WorkManager refresh. |
| `backend/` | Optional schedule server (JDK HTTP server + `core`): authoritative UTC time, ECDSA-signed manifest, schedule API. |
| `manifest/` | The default 48-channel cable lineup with era dayparts (no hardcoded media). |
| `tools/` | `generate_lineup.py` — the editable lineup definition that writes the manifest. |
| `docs/` | Architecture, backend API, curation & rights workflow, test plan, troubleshooting. |

## The lineup

48 channels modeled on a late-1990s / early-2000s US analog cable system (numbers varied by market): the
broadcast networks (CBS, PBS, NBC, FOX, UPN, ABC, The WB), an independent station, public access, C-SPAN, QVC, HSN,
The Weather Channel, CNN, Headline News, ESPN/ESPN2, TBS, TNT, USA, A&E, Discovery, TLC, History, Nickelodeon,
Cartoon Network, Disney Channel, Comedy Central, MTV, VH1, BET, CMT, Sci-Fi, TV Land, E!, Lifetime, Food Network,
HGTV, AMC, TCM, FX, Court TV, Animal Planet, HBO, Cinemax, Showtime, the Prevue Channel on 01, and a Classic
Commercials channel on 99. Edit `tools/generate_lineup.py` and rerun it to change anything.

Each channel runs **era programming blocks** on an Eastern-time feed, for example:
Toonami (weekdays 4–6 PM, Saturday nights), Adult Swim (Sun–Thu 11 PM–5 AM), Cartoon Cartoon Fridays, Boomerang;
Nick Jr., Nicktoons, SNICK, Nick at Nite; TRL, Yo! MTV Raps, 120 Minutes, Headbangers Ball; Pop-Up Video,
Behind the Music; TGIF, Must See TV, One Saturday Morning, Fox Kids, Kids' WB!, TNBC; WCW Monday Nitro, WWF Raw,
USA Up All Night, Dinner and a Movie; MST3K; HBO Feature Presentation; PBS Kids; and so on.

Network names appear as text only (no logos). They're trademarks of their owners: fine for personal use, but use
generic names before distributing the app publicly.

## Programming that keeps changing

* **Full episodes and movies first.** Every recording is typed (episode, movie, aircheck tape, music video,
  commercial, bumper…). Blocks prefer full episodes/movies; whole-tape airchecks (original ads intact) are used when
  nothing else is available and may be cut at the end of a block. Episodes and movies are never cut.
* **A new schedule every day.** Each broadcast day (6 AM–6 AM) is rebuilt from the channel's pools with a fresh
  deterministic shuffle that works through the whole pool before repeating. Every device still computes the
  identical schedule, and today's lineup never reshuffles once it has started.
* **Automatic discovery.** A daily background job searches the Internet Archive for each channel and block
  (e.g. "toonami", "total request live"), picks a stream-ready file, verifies it plays and seeks on the device,
  types it, and tags it into the right block. A brand-new install starts this immediately; empty channels light up
  as soon as something is found, busier ones pick up new finds at the next 6 AM rollover. Short leftover gaps
  are filled with period commercials and bumpers, like a real station break.
* **No manual approval by default** (Setup → *Air new finds without review*). Items whose detected year is
  outside 1990–2005 are skipped automatically. Turn the setting off to require a rights and broadcast-year
  sign-off per recording. Takedown (Curation) still blocks anything instantly and permanently.

Nothing is hardcoded: the shipped manifest contains channels and blocks only, and every recording comes from a
live Archive lookup.

## Cast to your TV

Tap **CAST** on the remote (or press C) to pick a Chromecast, Google TV or Cast-enabled TV on the same Wi-Fi.
The TV plays the channel at its live position, follows block changes, and changing channels on the phone
changes the TV. Pauses or seeks from the TV remote or Google Home are undone so the broadcast stays live. The
phone notification only offers "stop casting". Needs Google Play services; on the emulator use a Google APIs
image (Cast discovery won't find real TVs from an emulator — test on a phone).

Other ways onto a TV: install the app directly on an **Android TV / Google TV** box (it appears in the TV
launcher and works with the TV remote), or use Android's built-in screen mirroring.

## Build and run (Windows, emulator first)

1. Install **Android Studio** (current stable). In *SDK Manager* install: Android SDK Platform 35, Android SDK
   Platform-Tools, Android Emulator, and a **Google APIs** x86_64 system image (API 34 or 35).
2. Enable hardware virtualization: in BIOS/UEFI turn on Intel VT-x / AMD-V; in Windows enable *Windows Hypervisor
   Platform* (Settings → Optional features → More Windows features). Reboot.
3. *File → Open* the `ChannelSurf` folder. Let Gradle sync (it downloads Gradle 8.14.3 and dependencies).
4. *Device Manager* → create a **Pixel 8** (or similar) with the Google APIs image → start it.
5. Select the `app` run configuration → **Run ▶**.

The emulator's network is usually reported as metered; the app asks once whether to continue on mobile data.

### Command line

```bat
gradlew.bat :core:test :backend:test          :: pure JVM tests, no device needed
gradlew.bat :app:testDebugUnitTest            :: app JVM tests (mock Archive, no network)
gradlew.bat :app:connectedDebugAndroidTest    :: instrumentation + Compose UI tests (emulator running)
gradlew.bat :app:assembleDebug                :: app\build\outputs\apk\debug\app-debug.apk
```

Install the debug APK on a running emulator/device: `adb install -r app\build\outputs\apk\debug\app-debug.apk`.

### Release AAB

Create a keystore once (`keytool -genkeypair -v -keystore channelsurf.jks -keyalg RSA -keysize 4096 -validity 10000 -alias channelsurf`),
then create `keystore.properties` in the project root (git-ignored):

```properties
storeFile=channelsurf.jks
storePassword=…
keyAlias=channelsurf
keyPassword=…
```

`gradlew.bat :app:bundleRelease` → `app\build\outputs\bundle\release\app-release.aab` (R8-minified). Release builds
never air `TEST_ONLY` assets.

## Using it

| Action | Touch | Keyboard / remote / gamepad |
|---|---|---|
| Channel up / down | CH ▲ / CH ▼ | ↑ / ↓, CH+/CH−, Page Up/Down |
| Previous channel | LAST | L, LAST key, L1 |
| Direct tune | keypad + ENTER (auto-commits after 1.5 s) | 0–9, Enter |
| Guide | GUIDE | G, GUIDE, Y. In the guide: ↑↓ channel, ←→ time, Enter tunes **live**, N = now |
| Info | INFO (twice = details + Archive link) | I, INFO, X, Enter (when guide closed) |
| Favorite | ☆ FAV | F |
| Mute / volume | MUTE, VOL ± | M; hardware volume stays with Android |
| Captions | CC | — (Setup) |
| Cast to TV | CAST | C |
| Back | — | Back / Esc / B closes the top overlay |

Media keys (play/pause/rewind/skip…) are deliberately swallowed with a "LIVE TV" notice.

Portrait: picture on top (4:3, pillarboxed), remote or guide below. Landscape: full-width picture; tap to show the
remote, GUIDE overlays the lower ~¾ of the screen.

## Shared schedules (optional)

Without a server every device runs the identical deterministic schedule against its own clock. To make all viewers
see precisely the same thing, run the backend and enter its URL and public key in *Setup → Schedule server*. See
[docs/BACKEND_API.md](docs/BACKEND_API.md).

```bash
./gradlew :backend:run --args="generate-key keys"
./gradlew :backend:run --args="serve --manifest ../manifest/channel-surf-manifest.json --key keys/manifest-signing.pk8 --port 8080"
```

(Put it behind HTTPS; the app refuses cleartext.)

## Decisions made where the spec was open or conflicting

- **Lineup and approval** follow your later instructions over the original bible: real network names and blocks
  (text only, no logos) instead of fictional brands, and automatic approval instead of manual rights/year review
  (switchable in Setup).
- **Strictest playback rule wins**: no transport controls anywhere, including on the Cast notification.
- **DI**: lightweight manual DI (`AppContainer`) instead of Hilt.
- **HTTP**: OkHttp + kotlinx-serialization's JSON tree API instead of Retrofit (Archive metadata is polymorphic).
- **Library versions**: a known-compatible set (AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, Media3 1.5.1,
  Room 2.6.1). Android Studio's Upgrade Assistant can move them forward together.
- **Health is device-local**: a failing file is substituted from the fallback pool for that slot; the clock never moves.
- **autoPlay off** opens the guide on launch instead of tuning.

## Known limitations

- How full each channel gets depends on what the Internet Archive actually has. Niche blocks may stay sparse
  (they show "no recordings found for this block yet") until discovery or you add items.
- Discovery verifies at most ~20 recordings per run (Android limits background work to ~10 minutes); a full
  pass over all 48 channels takes a few chained runs.
- Archive tagging is inconsistent, so automatic typing/tagging is best-effort; fix any item in Curation.
- Without a schedule server, "same position on two devices" depends on both device clocks being correct.
- While casting, if Android kills the app in the background, the TV finishes the current program but won't advance
  to the next block until the app is opened again.
- Files without byte-range support can't join mid-program and are skipped.
- The project has not been through a Gradle sync in the environment it was written in (no Android SDK there); the
  `core` and `backend` modules were compiled and their tests run. See [docs/TEST_PLAN.md](docs/TEST_PLAN.md).

More: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)
