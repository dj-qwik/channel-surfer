# Test plan

## Automated

| Suite | Runs on | Command |
|---|---|---|
| `core` unit tests (75) | any JVM | `gradlew :core:test` |
| `backend` test (1, real HTTP) | any JVM | `gradlew :backend:test` |
| `app` JVM tests (mock Archive via OkHttp interceptor) | any JVM | `gradlew :app:testDebugUnitTest` |
| `app` instrumentation + Compose UI | emulator | `gradlew :app:connectedDebugAndroidTest` |

Mock fixtures (`*/src/test/resources/fixtures`) mean no test depends on Internet Archive uptime.

### Results at hand-off

`core` (75/75) and `backend` (1/1) were compiled with Kotlin 2.0.21 and executed in the authoring environment: all
passed. That environment had no Android SDK or Google Maven access, so `app` was not compiled there; its unit and
instrumentation tests must be run after the first Gradle sync.

## Acceptance criteria → tests

| Requirement | Automated by |
|---|---|
| Tune A at 14:00, return 14:10 → advanced exactly 10 min | `BroadcastAcceptanceTest.tuneAt1400ReturnAt1410AdvancesExactlyTenMinutes` |
| Kill process, reopen after 30 min → aligned to UTC | `…processDeathAndRelaunchAfter30MinutesStaysAlignedToUtc`, `CatalogPersistenceTest.importPersistsAndScheduleSurvivesReopen` |
| Switch A→B→A → A advanced | `…switchAtoBtoAAdvancesAWhileTunedAway` |
| Tune during original commercial → ad plays, no skip | `…tuningDuringOriginalCommercialPlaysTheCommercial`, `BroadcastUiTest.noTransportControls…`, `…mediaTransportKeysAreSwallowed` |
| 45 s buffering → catch up to live | `…bufferingFor45SecondsCatchesUpToLivePosition` (+ `PlayerController.onReady` reconcile) |
| Clock / time-zone / midnight changes stable | `…deviceClockAndTimezoneChangesDoNotMoveServerSyncedBroadcast`, `GuideBuilderTest.*` (DST fall-back & spring-forward, midnight, cross-zone) |
| Deleted / unseekable item → fallback, no drift | `…deletedArchiveItemFallsBackWithoutScheduleDrift`, `…noFallbackPool…`, `TuningAndRetryTest.eligibility…` |
| No pause/scrub/replay/skip/episode selection | `BroadcastUiTest.noTransportControlsExistAnywhereOnTheTvSurface`, `…mediaTransportKeysAreSwallowed` |
| Two devices same instant → same position | `…twoDevicesAtSameInstantSeeSamePosition`, `ScheduleServerTest` (server == client engine) |
| Guide gapless, 30-min aligned, local labels | `ScheduleEngineTest.sevenDaysAhead…`, `GuideBuilderTest.*` |
| Dayparts (Toonami, Adult Swim past midnight), daily rotation, no reshuffle of today, fillers, DST days | `DaypartSchedulerTest.*` |
| Content typing (episode/movie/tape/ad) | `ContentClassifierTest.*` |
| All curated titles in 1990–2005 | `TuningAndRetryTest.eligibility…`, `ScheduleEngineTest.ineligibleAssetsAreNeverScheduled` |
| Embedded commercials not removed | `…tuningDuringOriginalCommercial…` (tape is one indivisible block) |
| HTTP 404/429/5xx handling | `ArchiveHttpTest.*`, `TuningAndRetryTest.httpFailureClassification/retryAfter/backoff` |
| Missing metadata, short assets, duplicate numbers, empty catalog | `ArchiveParsingTest.missingMetadataFieldsDoNotCrash`, `ScheduleEngineTest.shortAssets…`, `ManifestCodecTest.duplicateChannelNumbers…`, `ScheduleEngineTest.emptyCatalog…`, `BroadcastUiTest.emptyCatalogShowsSetupState…` |
| Player cancellation races | `TuningAndRetryTest.rapidChannelChangesOnlyLastWins` (+ `TuneSequencer` in `PlayerController`) |
| Persistence after restart | `CatalogPersistenceTest.*`, `SettingsPersistenceTest.*` |
| Takedown removes blocked content | `CatalogPersistenceTest.takedownBlocksImmediatelyAndSurvivesReimport` |
| No unapproved media in default manifest | `CatalogPersistenceTest.bundledLineupSeedsWithNoMedia` |

## Manual emulator checklist

Needs network: on first launch discovery fills channels automatically (or use Setup → Find new programs now).

1. Cold launch: splash → last channel, correct position, no seek bar.
2. Portrait ↔ landscape: video continues without restarting; 4:3 pillarboxed.
3. Airplane mode on: NO SIGNAL; guide still readable. Off: rejoins live.
4. Emulator *Extended controls → Cellular → Network type: GSM* (slow link): watch "ACQUIRING SIGNAL", then catch-up.
5. Home for 2 min, return: program advanced ~2 min.
6. `adb shell am kill com.channelsurf.tv.debug` while backgrounded, relaunch: same channel, live position.
7. Change emulator time zone (Settings → System → Date & time): guide labels change, program position doesn't.
8. Dark/light OS mode and *Font size: Largest*: setup screens follow; TV surface keeps the classic look and stays usable.
9. TalkBack: guide cells announce channel, title, times and "on now"; remote buttons have labels.
10. Android Studio *Profiler → Memory*: channel-surf 50 times; heap returns to baseline (single player, no leaks).
11. Two emulators, same schedule server, same channel: positions match within ~1 s.
12. Cast (physical phone + Chromecast/Google TV on the same Wi-Fi): CAST → pick TV → live position on the TV;
    CH ▲ changes the TV; pausing from Google Home resumes at live within a second or two; STOP CASTING returns to the phone at live.
13. Day-to-day variety: Setup → Diagnostics shows `schedule.refresh` daily; compare tomorrow's guide (◀▶) with today's.
