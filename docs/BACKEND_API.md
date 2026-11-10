# Schedule backend API (v1)

The backend is optional. It provides (1) authoritative UTC time and (2) a signed, versioned manifest. Clients compute
schedules locally with the same `core` engine, so `/v1/schedule` returns exactly what every client computes.

All responses are `application/json; charset=utf-8`. Serve behind HTTPS (reverse proxy or platform TLS).

| Endpoint | Response |
|---|---|
| `GET /healthz` | `{"ok":true,"revision":"…"}` |
| `GET /v1/time` | `{"utcMs":1762182000000}` · `Cache-Control: no-store` |
| `GET /v1/manifest` | Manifest JSON (canonical encoding). Headers: `X-Manifest-Signature` (base64 ECDSA P-256/SHA-256 over the exact body bytes), `X-Manifest-Revision`. `Cache-Control: max-age=300` |
| `GET /v1/schedule?channelId=&fromUtcMs=&toUtcMs=` | `{"revision","channelId","blocks":[{id,channelId,assetId?,startUtcMs,durationMs,sourceOffsetMs,displayTitle,kind,revision,provenance?}]}`. Window ≤ 8 days. 400 bad window, 404 unknown channel |
| `GET /v1/now?channelId=` | `{"revision","utcMs","block",offsetInBlockMs,"assetId"/"filePositionMs" or "slate"}` |

## Client behavior

* `ScheduleServerClient.sync()` (boot, *Save & Sync*, and every periodic refresh): samples `/v1/time` (rejects
  RTT > 5 s), then fetches `/v1/manifest`, verifies the signature with the configured public key, and imports it only
  when the revision changed. An invalid signature is logged and ignored.
* A server manifest replaces local curation (it is the operator's source of truth). Local takedowns still apply.

## Running

```bash
./gradlew :backend:run --args="generate-key keys"          # prints the public key to paste into the app
./gradlew :backend:run --args="check --manifest ../manifest/channel-surf-manifest.json"
./gradlew :backend:run --args="serve --manifest ../manifest/channel-surf-manifest.json --key keys/manifest-signing.pk8 --port 8080"
```

Keep `manifest-signing.pk8` secret. To publish a new lineup, edit/export the manifest, restart (or redeploy) the server,
and clients pick it up on their next sync.

## Manifest schema (v1)

Top level: `schemaVersion` (1), `revision`, `generatedAtUtcMs`, `eraPolicy {minYear,maxYear,preferredMaxYear}`,
`channels[]`, `assets[]`. Unknown top-level keys are rejected.

Channel: `id` (`[a-z0-9-]`), `number` (1–999, unique), `displayName` (≤40), `genre`, `categoryLabel`, `color` (#RRGGBB),
`enabled`, `epochUtcMs`, `policy` (`CYCLIC`|`FIXED`), `playlist[]`, `fallbackPool[]`, `fixedEntries[] {startOffsetMs,
durationMs, assetId, sourceOffsetMs, displayTitle?, description?}`, `fixedPeriodMs?`.

Asset: `id`, `archiveIdentifier`, `fileName`, `url` (HTTPS archive.org), `title`, `description`, `durationMs`,
`sourceStartMs`, `seekable`, `codec`, `container`, `aspectRatio`, `broadcastYear`, `yearVerified`, `yearEvidence`,
`rightsStatus` (`CLEARED`|`TEST_ONLY`|`REVIEW_REQUIRED`|`BLOCKED`), `rightsNotes`, `provenance`
(`ORIGINAL_RECORDING`|`RECONSTRUCTED`), `adOrigin` (`EMBEDDED_ORIGINAL`|`INSERTED`|`NONE`), `captionUrl?`,
`alternateUrls[]`, `segments[] {offsetMs,label,kind}` with kind `PROGRAM|COMMERCIAL|STATION_ID|PROMO|BUMPER|INTERRUPTION`.
