# Architecture

```
                 ┌──────────────────────────── app ─────────────────────────────┐
  Remote / keys ─► MainActivity.dispatchKeyEvent ─► MainViewModel (overlays, guide, tuning)
  Touch         ─► Compose UI (TvScreen, GuideGrid, RemoteControl, Setup, Curation)
                                   │                         │
                                   ▼                         ▼
                           PlayerController ◄──── ScheduleRepository ◄──── CatalogRepository ◄── Room
                           (one ExoPlayer)        (engine per revision,      (channels, assets,
                                   │               7-day materialization)     playlists, takedowns)
                                   ▼                         ▲                       ▲
                       OkHttp (archive.org only)     SyncedBroadcastClock     Manifest import/export,
                                                     ▲                        Curation, RefreshWorker
                                   ScheduleServerClient (optional: time + signed manifest)
                 └──────────────────────────────────────────────────────────────┘
                 ┌──────────── core (pure Kotlin, shared with backend) ─────────┐
                 │ ScheduleEngine · TuneResolver · DriftPolicy · GuideBuilder   │
                 │ ManifestCodec · ArchiveParsing · PlayableFileSelector        │
                 │ YearEvidence · UrlPolicy · BackoffPolicy · NumberEntry       │
                 └──────────────────────────────────────────────────────────────┘
```

## The scheduling model

* A **channel** has a stable `epochUtcMs` and a policy: CYCLIC playlist, FIXED entries (optionally repeating), or
  **DAYPARTED** (the default lineup). DAYPARTED channels build each broadcast day (06:00→06:00 in the feed's zone,
  DST-aware) from dayparts (e.g. Toonami 16:00–18:00 weekdays) filled from tag-based pools: full programs that fit,
  aircheck tapes cut at the block end, then filler spots, then a station-break card. Each daypart reads an endless
  stream of seeded shuffles of its pool, offset per day, so programs rotate daily yet every device computes the same
  day. Assets carry `availableFromUtcMs`; a pool only takes new assets on days that start after it (unless the pool
  was empty), so the day on the air never reshuffles. See `DaypartScheduler`.
* `ScheduleEngine.blockAt(channel, nowUtc)` is a pure function. CYCLIC: `elapsed = now − epoch`,
  `pos = floorMod(elapsed, cycleLength)`, binary search over cumulative durations. FIXED: interval lookup; gaps
  become hour-aligned "Off Air" slates; a slot longer than its media ends in an honest "Technical Difficulties" slate.
* `blocksBetween` walks block-to-block, so windows are gapless by construction; `ScheduleValidator` checks it anyway.
* **Eligibility is editorial** (rights cleared, year verified and 1990–2005, seekable, approved host). **Health is not**:
  an unhealthy file keeps its slot, and `TuneResolver` substitutes a deterministic pick from the channel's fallback
  pool for that slot only. A substitute shorter than the remaining slot is never looped.
* Arithmetic is UTC milliseconds throughout. Time zones affect only guide labels (`GuideBuilder` aligns to *local*
  half hours and steps in UTC, so DST nights label correctly).
* There is no stored playhead anywhere. `PlaybackBookmark` holds last/previous channel and favorites only.

## Clock authority

`SyncedBroadcastClock` uses server UTC (NTP-style midpoint of one round trip, rejected if RTT > 5 s) anchored to
`SystemClock.elapsedRealtime()`, so changing the device clock or time zone cannot move the broadcast. Without a
server it falls back to the device wall clock.

## Playback

`PlayerController` owns the single ExoPlayer (scoped to `MainViewModel`, so rotation doesn't rebuffer) and implements:

* **Tune**: cancel in-flight work (`TuneSequencer` tokens), resolve, recompute the file position *at prepare time*,
  `setMediaItem(item, position)`, prepare, play. The UI shows 250–650 ms of static keyed to the tune token.
* **Catch-up**: on every READY and every 30 s while playing, `DriftPolicy` compares the player position with
  `expected = position + (now − resolvedAt)`; drift beyond tolerance triggers an internal seek. Repeated catch-ups
  widen tolerance to avoid seek-thrashing on slow links. Audio-focus resumes also reconcile.
* **Block end**: when the clock passes `playUntilUtcMs` (or media ends), re-resolve → next scheduled asset.
  Media ending early → technical-difficulties slate until the slot ends.
* **Errors**: 403/404/410 → mark unhealthy (session + Room) and re-resolve (substitution). 429/5xx/network → bounded
  backoff with jitter honoring `Retry-After`, then rejoin **live**. Decoder/format → next verified alternate rendition,
  then unhealthy. Offline → NO SIGNAL; reconnect → rejoin live.
* **Background**: `onStop` releases the decoder (`SUSPENDED`); `onStart` re-resolves and joins live.
* **Takedown**: `CatalogRepository.takedownEvents` → stop immediately and re-resolve.

States: `BOOTING, CATALOG_LOADING, TUNING, BUFFERING, PLAYING, RECOVERABLE_ERROR, NO_SIGNAL, OFFLINE,
DATA_BLOCKED, SUSPENDED`. The guide is an overlay (`OverlayState.guide.open`), not a playback state, so opening it
never touches the player.

## Data

Room tables: `channels`, `media_assets`, `playlist_entries`, `program_blocks` (24 h history + 7 days ahead, rebuilt per
revision), `manifest_revisions` (audit, last 20), `takedowns`, `archive_cache` (metadata/search JSON for offline).
The catalog revision is a hash of editorial content, so any edit rebuilds schedules without an app update.

DataStore: settings (effects, captions, Wi-Fi only, data saver, autoplay, theme, notices, schedule server) and the
bookmark.

## Casting

`CastManager` initializes the Cast framework (Default Media Receiver) and runs an in-app device picker over
MediaRouter. `PlayerController` holds the local ExoPlayer and, once a session starts, a Media3 `CastPlayer`; it
switches the *active* player and re-tunes at the live position. All broadcast logic (block advance, drift
reconciliation, error handling) runs against the active player. While casting, remote pauses are undone and
external seeks snap back to live; the Cast notification offers only "stop casting" and the media session is disabled.

## Discovery

`DiscoveryService` (via `DiscoveryWorker`, daily with chained follow-ups under WorkManager's time limit) rotates each
channel's queries and result pages week by week, validates and plays each new item, classifies it
(`ContentClassifier`), tags it for the daypart whose search found it, and records rejects in `dismissed_items`.

## Security

* Only HTTPS `archive.org` / `*.archive.org` URLs are fetched or played (`UrlPolicy`), enforced again by an OkHttp
  network interceptor on every redirect hop. Cleartext is disabled app-wide.
* Manifests are size-capped, schema-versioned, reject unknown keys, length-cap and strip control characters, and
  validate every cross-reference. Titles are rendered as plain text only.
* Server manifests must carry a valid ECDSA P-256 signature for the configured public key.
* Network work is off the main thread; ExoPlayer is touched only on the main thread.
* Diagnostics are local, contain no personal data, and are shared only on explicit request.

## Package map

`app/src/main/kotlin/com/channelsurf/`
`data/{local,prefs,archive,catalog,schedule,remote,net}` · `playback` · `diagnostics` · `work` ·
`ui/{theme,player,guide,controls,settings,curation,diagnostics}`
