# Troubleshooting

**Gradle sync fails with "Unsupported class file major version" / JDK errors.**
Use the JDK bundled with Android Studio: *Settings → Build Tools → Gradle → Gradle JDK → jbr-21* (JDK 17+ works).

**Sync fails resolving plugins/dependencies.**
Check network/proxy (*Settings → HTTP Proxy*). Versions are pinned in `gradle/libs.versions.toml`; if your Studio
requires a newer AGP, use *Tools → AGP Upgrade Assistant*, and bump `kotlin` and `ksp` together
(KSP version = `<kotlin>-<ksp release>`).

**Emulator won't start / "x86 emulation currently requires hardware acceleration".**
Enable VT-x/AMD-V in BIOS and *Windows Hypervisor Platform* in Windows features; reboot. On ARM Windows use an ARM64 image.

**Every channel shows OFF AIR.**
Expected on a fresh install: no recordings are approved. Use *Setup → Curation*, or import a manifest.

**"Playback verification failed" in Curation.**
The emulator may lack a decoder for that file (common with high-profile H.264 or VP9 on old images). Try another
rendition/item, a newer system image, or a physical device. The app never assumes every MP4 plays everywhere.

**"No file is reachable with byte-range seeking".**
The server ignored `Range` requests; such files can't join mid-program and are not eligible for linear air.

**Technical difficulties slate on a channel that worked before.**
Open *Setup → Diagnostics*: look for `playback.error` with `http=404/403` (file withdrawn → fallback pool is used),
`429/5xx` (Archive busy → automatic retry), or a decoder error (alternate rendition tried). *Refresh now* re-checks health.

**Video stays on "ACQUIRING SIGNAL".**
Slow link or throttling. The app retries with backoff and always rejoins live. *Data saver* prefers verified
low-bandwidth renditions.

**Guide times look wrong after changing time zone.**
Labels follow the device zone immediately; program positions never change (schedules are UTC).

**Two devices aren't in sync.**
Without a schedule server each device uses its own clock. Configure a server (see BACKEND_API.md) or ensure automatic time.

**Using a local schedule server with the emulator.**
The app requires HTTPS. Easiest: run the server behind a TLS tunnel/reverse proxy with a real certificate. For local-only
development you can add `src/debug/res/xml/network_security_config.xml` permitting cleartext to `10.0.2.2` and reference it
from a debug manifest; never ship that in release.

**Manifest import "Rejected".**
The message lists the first errors (duplicate channel numbers, non-HTTPS/non-Archive URLs, missing asset references,
unknown keys, schemaVersion). Validate offline with `gradlew :backend:run --args="check --manifest <file>"`.

**Room "Migration didn't properly handle" / schema errors after editing entities.**
Bump `AppDatabase.VERSION`, add a `Migration` to `MIGRATIONS`, commit the regenerated `app/schemas/*.json`.
Destructive migration is intentionally disabled (rights decisions and takedowns must never be silently wiped).
