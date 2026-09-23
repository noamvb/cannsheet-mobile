# Current handoff

Last updated: 2026-09-22

Repository: public `noamvb/cannsheet-mobile`

## Unreleased - consumption cancel window persists across restarts (PR #197)

**Status: open PR on `fix/persist-cancel-window`; not released, no version bump.**

- Cause: the Settings "Cancel window" was a process-local
  `MutableStateFlow(5)` in `CannsheetViewModel`; see ADR-056.
- Fix: persisted as `submission_timer_seconds` in the `consumption_preferences`
  DataStore through `ConsumptionPreferencesRepository`.
- Evidence: `./gradlew --no-daemon testDebugUnitTest assembleDebug lintDebug`
  passed with 683 unit tests and 0 failures; `assembleSandbox` passed. Removing
  the DataStore write fails 2 of the 5 new `SubmissionTimerPreferenceTest` tests.
  On the owner's phone (sandbox build) 2 s survived force-stop and relaunch;
  the `main` build reset it to 5.
- Operational note: the phone's previous sandbox install was signed with a
  different debug key, so it was uninstalled (with the owner's approval) and
  reinstalled from this Mac; its local sandbox data was cleared.
- Outstanding: merge, then ship in the next release.

## Cannsheet Mobile v1.12.5 (code 63) - sync no longer sends `"clientState": null`

**Status: published, independently verified, installed on the owner's phone
over adb and confirmed live on 2026-09-17.**

### What changed and why

Since #181 (v1.12.0) every sync request whose `SyncPayload.clientState` is `null` - i.e. whenever no loaded-pen change is pending, which is almost always - was encoded as:
```json
{"apiVersion":2,"requestId":"r","environment":"PRODUCTION","purchases":[],"consumptions":[],"finishActions":[],"consumptionCorrections":[],"clientState":null}
```
The backend (`backend_additions.gs` `preflightSyncRequest_`) treats a present `clientState` key as a client-state update and answers `{"success":false,"errorCode":"INVALID_ITEM","message":"clientState must be an object"}`, so the whole request failed and the offline queue never drained until a pen swap made one request carry an object. The phone showed exactly that message on v1.12.4.

The cause was that `SyncClientStateJsonAdapterFactory` in `app/src/main/java/com/example/data/Network.kt` wrapped the `SyncClientState` adapter with `JsonAdapter.serializeNulls()`. While intended to preserve `loadedPenProductId: null` inside the object, calling `serializeNulls()` enabled `serializeNulls` on the writer before delegating, which also turned a null `SyncClientState` value into an explicit `"clientState":null`.

`SyncClientStateJsonAdapterFactory` in `app/src/main/java/com/example/data/Network.kt` was replaced with an adapter that calls `writer.nullValue()` with the writer's `serializeNulls` left as it is when the value is null (so Moshi drops the deferred name), and delegates with `serializeNulls` enabled (`delegate.serializeNulls().toJson(writer, value)`) only when the value is non-null.

### Evidence

Focused test command:
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew --no-configuration-cache :app:testDebugUnitTest --tests 'com.example.data.NetworkClientStateTest'
```

Red run (before `Network.kt` edit, with new tests):
```
NetworkClientStateTest > payloadWithoutPendingPenOmitsClientStateEntirely FAILED
    org.junit.ComparisonFailure at NetworkClientStateTest.kt:49

5 tests completed, 1 failed
```
Failing line in `payloadWithoutPendingPenOmitsClientStateEntirely`:
`org.junit.ComparisonFailure: expected:<...ptionCorrections":[][]}> but was:<...ptionCorrections":[][,"clientState":null]}>`

Green run (after `Network.kt` edit):
```
BUILD SUCCESSFUL in 3m 24s
29 actionable tasks: 7 executed, 22 up-to-date
```
`<testcase` count in `app/build/test-results/testDebugUnitTest/TEST-com.example.data.NetworkClientStateTest.xml`: 5 (5 tests, 0 failures, 0 skipped).

SyncEngine test command:
```
./gradlew --no-configuration-cache :app:testDebugUnitTest --tests 'com.example.data.SyncEngineTest'
```
Result:
```
BUILD SUCCESSFUL in 3s
29 actionable tasks: 1 executed, 28 up-to-date
```

### Release provenance

Pull request merged: #195 `2da880f`. Delegation cc -> agy
gemini-3.8-flash-high; dispatcher gate (gradle-verify, node backend suites,
focused tests) green; focused tests and a mutation drill (re-enabling
serializeNulls on the null path reds the new test) re-run by hand.

Tag `v1.12.5` points at `2da880f`. PR checks were green on all five jobs.
Release run `35287632811` was green; published 2026-09-17 23:44 UTC.

The published artifact is `Cannsheet-Mobile-1.12.5.apk`, 38,020,149 bytes,
SHA-256 `66f9eead76c6c97d6e15b20ba618e76754652342d445ba94530a204b0d8e8faa`, on
`noamvb/cannsheet-mobile-releases`, downloaded independently of CI and verified
against its published `.sha256`; `aapt` reports versionCode 63, versionName
1.12.5; signing certificate SHA-256 `a9787249…`, unchanged since v1.9.1.

Installed on the owner's SM-F966W with `adb install -r` over wireless adb at
19:44 EDT. Live check: one freshly queued action with no pending pen change
synced on the first Sync Now (POST answered 200 at 19:44:58), queue 1 -> 0,
"Everything is synced" - the case that failed on every earlier v1.12.x.

### Outstanding

The v1.12.4 outstanding items still apply.

## Cannsheet Mobile v1.12.4 (code 62) - sync failures show the backend's message

**Status: published, independently verified, and installed on the owner's phone
over adb on 2026-09-17.**

### What changed and why

The owner's phone has had `Sync failed: INVALID_ITEM` since 2026-09-17 ~12:20 EDT with 4 actions queued. When whole-request sync failures occurred, `SyncOutcome.Failed` retained both `errorCode` and `message` from the backend's response, but `syncStatusMessage` in `app/src/main/java/com/example/ui/CannsheetViewModel.kt` previously rendered only `errorCode ?: message`, discarding the server's descriptive message (which names the failing check, e.g. `loadedPenUpdatedAtEpochMillis must be a positive integer` or `Duplicate UUID inside request`).

`syncStatusMessage` now formats `SyncOutcome.Failed` to show both the code and trimmed message (`Sync failed: <errorCode> - <message>`) when both are present and non-blank, falling back to code only, message only, or `Sync failed: Unknown error` when neither is present.

### Evidence

Focused test command:
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew --no-configuration-cache :app:testDebugUnitTest --tests 'com.example.ui.SyncStatusMessageTest'
```

Red run (before `CannsheetViewModel.kt` edit, with updated `BACKEND_BUSY - Busy` assertion and new `failedOutcomeShowsBackendMessageNextToTheCode` test):
```
SyncStatusMessageTest > nonAppliedOutcomesPreserveExistingMessages FAILED
    org.junit.ComparisonFailure at SyncStatusMessageTest.kt:19

SyncStatusMessageTest > failedOutcomeShowsBackendMessageNextToTheCode FAILED
    org.junit.ComparisonFailure at SyncStatusMessageTest.kt:51

3 tests completed, 2 failed
```
Failing line in `failedOutcomeShowsBackendMessageNextToTheCode`:
`org.junit.ComparisonFailure: expected:<...failed: INVALID_ITEM[ - loadedPenUpdatedAtEpochMillis must be a positive integer]> but was:<...failed: INVALID_ITEM[]>`

Green run (after `CannsheetViewModel.kt` edit):
```
BUILD SUCCESSFUL in 46s
29 actionable tasks: 6 executed, 23 up-to-date
```
`<testcase` count in `app/build/test-results/testDebugUnitTest/TEST-com.example.ui.SyncStatusMessageTest.xml`: 3 (previous count was 2; 3 tests, 0 failures, 0 skipped).

### Release provenance

Pull request merged: #193 `d9ab386` (change, test, version bump, docs in one
squash). Delegation cc -> agy gemini-3.8-flash-high; dispatcher gate
(gradle-verify, node backend suites) green; focused test and a mutation drill
(dropping the message from the join reds the new test) re-run by hand.

Tag `v1.12.4` points at `d9ab386`. PR run `35283980085` was green on all five
jobs. Release run `35284428033` was green on all three jobs; published
2026-09-17 23:03 UTC.

The published artifact is `Cannsheet-Mobile-1.12.4.apk`, 38,020,149 bytes,
SHA-256 `67f5dca3d3ddc748fe7e8f46477c3337e40480b6e528d09515da5ebc601f90cc`, on
`noamvb/cannsheet-mobile-releases`, downloaded independently of CI and verified
against its published `.sha256`; `aapt` reports versionCode 62, versionName
1.12.4; signing certificate SHA-256 `a9787249…`, unchanged since v1.9.1.

Installed on the owner's SM-F966W with `adb install -r` over wireless adb at
19:03 EDT.

### Outstanding

- The INVALID_ITEM jam cleared on its own at about 18:50 EDT, before this
  build was installed: the queue drained (four uses and the void of
  `1f9c1d43-531b-4ab0-85c2-f0b408505e92` reached the sheet) and the status
  reads "Sync successful". The cause was never observed. It was a
  whole-request `success:false`, which only `preflightSyncRequest_` produces
  (clientState, batch size, requestId, duplicate UUID, apiVersion);
  It did, the same evening: `clientState must be an object` - fixed in v1.12.5.
- Two panel-created test events remain to be voided from the phone:
  17:18:52 x1 (`f8beb18d…`) and 18:16:14 x1 (`b9dedd79…`) on 2026-09-17.

## Cannsheet Mobile v1.12.3 (code 61) - analytics GETs capped at 45 s per attempt

**Status: published, independently verified, and installed on the owner's phone
over adb on 2026-09-17.**

### What changed and why

v1.12.2 added retry with backoff for analytics GETs, and the same afternoon
the phone's logcat showed each attempt through Google's
`script.googleusercontent.com` hop taking 62-105 s before answering, so a
fully failed fetch was about three and a half minutes before Insights showed
an error. The shared OkHttp client has `readTimeout(60 s)` but no
`callTimeout`, and readTimeout is per socket read. Fix in #191 (`097823e`):
`AnalyticsRepository` now uses `analyticsApiService`, a Retrofit service on
`client.newBuilder().callTimeout(45 s)` (`ANALYTICS_CALL_TIMEOUT_SECONDS` in
`CannsheetGraph.kt`); sync POSTs and the catalog refresher keep the uncapped
client, because a POST cut short may already have committed on the server.
`fetchWithRetry` also retries `InterruptedIOException` (what `callTimeout`
throws; parent of `SocketTimeoutException`), and `analyticsUiError` maps it to
`TIMEOUT` instead of falling through to "No connection". Worst case is now
about 3 x 45 s + 4 s = 139 s.

### Evidence

- Implemented by `cc -> agy gemini-3.8-flash-high`, run `20260917-145716-agy-29034`.
  The call-timeout retry test was written first and failed against the
  previous code; mutation drill: dropping the `InterruptedIOException` clause
  reddened 2 tests, reverting the UI mapping reddened 1, changing the cap to
  60 s reddened 1.
- Local gate on the merged code with `--rerun-tasks`: `testDebugUnitTest
  compileDebugAndroidTestKotlin lintDebug assembleDebug` green, **674 unit
  tests, 0 failures** (31 in `AnalyticsDataTest`, plus new
  `AnalyticsUiErrorTest` (3) and `AnalyticsCallTimeoutTest` (1)).

### Release provenance

Pull request merged: #191 `097823e` (change, tests, version bump, docs in one
squash).

Tag `v1.12.3` points at `097823e`. Main run `35266186992` at that commit was
green on all six jobs including Emulator API 36 on the first attempt. Release
run `35266801522` was green on all three jobs; published 2026-09-17 19:55 UTC.

The published artifact is `Cannsheet-Mobile-1.12.3.apk`, 38,020,149 bytes,
SHA-256 `4643d39838381cb157c736e5fb5344e2f10814b33615eeab436c363a293318be`, on
`noamvb/cannsheet-mobile-releases`, downloaded independently of CI and verified
against its published `.sha256`; `aapt` reports versionCode 61, versionName
1.12.3; signing certificate SHA-256 `a9787249…08665e`, unchanged since v1.9.1.

Installed on the owner's SM-F966W with `adb install -r` over wireless adb at
15:56 EDT. On first launch the hop was healthy (analytics GETs answered in
1.7-4 s); one later GET got a 404 after 34 s and its retry returned 200. The
45 s cap itself did not need to fire in that window and cannot be forced from
outside, so its behaviour rests on the unit tests and the pinned constant.

### Outstanding

- The v1.12.0 outstanding items below still apply (panel-logged events reach
  Today only on the periodic prefetch; the ai-orch profile lacks
  `compileDebugAndroidTestKotlin`).

## Cannsheet Mobile v1.12.2 (code 60) - analytics GETs retry with backoff

**Status: published, independently verified, installed on the owner's phone over
adb, and the retry observed firing there on 2026-09-17.**

### What changed and why

Minutes after v1.12.1 was installed, Insights showed `HTTP 404
(INTERNAL_ERROR)`. OkHttp logged `<-- 404 https://script.googleusercontent.com/...`
after 16-32 s; the identical request retried seconds later returned 200. That
host is Google's redirect target behind the Apps Script 302 and it fails
intermittently on Google's side (reproduced from the phone shell and from a
Mac with curl). The app retried nothing on a non-2xx status: `fetchWithBusyRetry`
covered only the envelope code `BACKEND_BUSY`. Fix in #189 (`add22bc`):
`AnalyticsRepository.fetchWithRetry` makes up to 3 attempts with 1 s / 3 s
backoff on `HttpException` 404/429/500/502/503/504 and `BACKEND_BUSY`; every
other error propagates on the first attempt and the last retryable error is
rethrown unchanged. The delay is a constructor parameter so tests do not sleep.

### Evidence

- Implemented by `cc -> agy gemini-3.8-flash-high`, run `20260917-132954-agy-19549`.
  The 404-then-200 test was written first and failed against the previous
  code; mutation drill: max attempts 3->1 reddened 4 tests, dropping 404 from
  the set reddened 3, backoff 1 s/1 s reddened 1.
- Local gate on the merged code with `--rerun-tasks`: `testDebugUnitTest
  compileDebugAndroidTestKotlin lintDebug assembleDebug` green, **667 unit
  tests, 0 failures** (28 in `AnalyticsDataTest`, 6 new).

### Release provenance

Pull request merged: #189 `add22bc` (change, tests, version bump and
PROJECT_STATE in one squash).

Tag `v1.12.2` points at `add22bc`. Main run `35257783571` at that commit was
green on all six jobs including Emulator API 36 on the first attempt. Release
run `35258484140` was green on all three jobs; published 2026-09-17 18:30 UTC.

The published artifact is `Cannsheet-Mobile-1.12.2.apk`, 38020149 bytes,
SHA-256 `731a300d53856ad73b49cb44b3ca7abdc1aabceb7534568840a4caed5cf102e5`, on
`noamvb/cannsheet-mobile-releases`, downloaded independently of CI and verified
against its published `.sha256`; `aapt` reports versionCode 60, versionName
1.12.2; signing certificate SHA-256 `a9787249…08665e`, unchanged since v1.9.1.

Installed on the owner's SM-F966W with `adb install -r` over wireless adb at
14:31 EDT. On first launch, logcat (`okhttp.OkHttpClient`) showed one
analytics GET receive `404` after 62 s, retry 1 s later, receive `404` after
66 s, retry 3 s later, and receive `200` after 16.5 s - the exact backoff
schedule, on a request that v1.12.1 would have surfaced as `HTTP 404`.

### Outstanding

- Done on `main` after this release, unreleased: the `googleusercontent` hop
  was still slow (60-105 s per attempt) that afternoon, so a fully failed
  fetch could take about three and a half minutes. #191 caps each analytics
  GET at 45 s (`callTimeout` on a derived client; sync POSTs stay uncapped),
  retries `InterruptedIOException` in `fetchWithRetry`, and maps it to
  `TIMEOUT` in the UI. Worst case is now about 3 x 45 s + 4 s = 139 s. It
  ships as v1.12.3 (code 61).
- The v1.12.0 outstanding items below still apply (panel-logged events reach
  Today only on the periodic prefetch; the ai-orch profile lacks
  `compileDebugAndroidTestKotlin`).

## Cannsheet Mobile v1.12.1 (code 59) - Insights presets roll with the calendar

**Status: published, independently verified, installed on the owner's phone over
adb, and the fix observed working there on 2026-09-17.**

### What changed and why

v1.12.0 was verified on the owner's phone on the morning of 2026-09-17 (loaded
pen published at 03:23, Today widget counting a panel-logged event), and that
check surfaced a month-old defect: Insights read `2026-05-21 – 2026-08-18 ·
Updated Sep 17, 10:09 a.m.` with the 90-day chip highlighted and "Days since
last log: 30", and the Runway/Spend widgets printed "as of 2026-08-18". The
30/90-day chips were absolute `Custom` windows anchored on `data.range.to`
(the cached window's own end), so a stale window re-selected itself, and the
periodic prefetch re-fetched whatever range was cached. Fresh cache, frozen
window. Fix in #186 (`bdf6104`), ADR-055: relative `InsightsRange.LastDays`
presets resolved at request time, the requested kind persisted in the cache
row and read back through the new `readCachedInsightsRequest()`, and a
one-time heal of a cached 30/90-day `Custom` window.

### Evidence

- Local gate on the merged code: `--rerun-tasks testDebugUnitTest
  compileDebugAndroidTestKotlin lintDebug assembleDebug` green, **661 unit
  tests, 0 failures** (660 + the review follow-up test).
- The frozen-window prefetcher test was written first and did not compile
  against the previous code; mutation drills reddened it (prefetcher ignoring
  the cached request), the upgrade-heal test (90-day condition broken) and
  the best-effort test (guard removed).
- Emulator API 24 flaked once on #186 with the known `InstallException:
  Broken pipe`; the rerun was green.

### Release provenance

Pull requests merged: #186 `bdf6104`, #187 `db5b568` (release).

Tag `v1.12.1` points at `db5b568`. Main run `35247541474` at that commit was
green on all six jobs including Emulator API 36 after one rerun (the first
attempt failed API 36 with `Failed to inject touch input` in
`ProductTypeQuantityEditorTest`, an emulator input flake on a docs-and-version
commit; the identical code had passed API 36 at `bdf6104`, run `35245626942`).
Release run `35248941668` was green on all three jobs; published 2026-09-17
17:02 UTC.

The published artifact is `Cannsheet-Mobile-1.12.1.apk`, 38,020,149 bytes,
SHA-256 `1ce56173e3e343a6b8eb47a7c1d4db0ff419c5c7244487585be8b3e7925bf900`, on
`noamvb/cannsheet-mobile-releases`, downloaded independently of CI and verified
against its published `.sha256`; `aapt` reports versionCode 59, versionName
1.12.1; signing certificate SHA-256 `a9787249…08665e`, unchanged since v1.9.1.

Installed on the owner's SM-F966W with `adb install -r` over wireless adb at
13:03 EDT. Opening Insights showed the healed 90-day chip selected, then a
refresh produced `2026-06-20 – 2026-09-17 · Updated Sep 17, 1:04 p.m.`, and the
Runway and Spend widgets read "as of 2026-09-17".

### Outstanding

- Observed while verifying on the phone: Google's `script.googleusercontent.com`
  hop was intermittently taking 16-110 s and answering HTTP 404 for analytics
  GETs during the early afternoon of 2026-09-17, from the phone and from a
  Mac alike, for tiny and large responses alike; the app surfaced it as
  `HTTP 404 (INTERNAL_ERROR)` and recovered on a later refresh (40 s, inside
  the 60 s read timeout). Not caused by this release; worth a retry-with-backoff
  on non-2xx if it recurs.
- The v1.12.0 outstanding items below still apply (panel-logged events reach
  Today only on the periodic prefetch; the ai-orch profile lacks
  `compileDebugAndroidTestKotlin`).

## Cannsheet Mobile v1.12.0 (code 58) - loaded pen published, server events mirrored into Today

**Status: published, independently verified against the release artifact, and
driven on the owner's phone on 2026-09-17 (see below).**

This release is the phone's half of "Cannsheet on the household panel". The
panel itself (Home Assistant on the Pixelbook; Inbox repo
`tools/homeassistant/packages/cannsheet.yaml`, spec
`Inbox/docs/SPEC-cannsheet-panel-A.md`) polls the backend for the last five
events, offers four pen-quicklog buttons (10/15/20/30 s at 10 s per use) that
POST straight to `doPost`, and polls `?resource=clientState` for the pen the
phone has loaded, falling back to a pinned UUID until the phone has published.

### What changed and why

- The loaded pen lived only in the phone's DataStore. It now rides inside the
  ordinary apiVersion-2 sync request as `clientState`; the backend stores it in
  the Config sheet and serves it read-only. Design and the stale/rejected
  acknowledgement rules: ADR-054. #181, squashed as `a162cae`.
- Events the panel logs have eventIds the phone never minted, so they reached
  the sheet and Analytics but not `consumption_history`, the only table the
  Today widget reads. `ServerHistoryIngestor` now mirrors the last ten days of
  server history into it (insert-if-absent / upsert corrected / delete voided,
  device-local calendar), from every persisted History page and from its own
  unfiltered fetch in the periodic worker. #183, squashed as `2c8b258`.
- Backend: production Apps Script **version 17** (deployment unchanged,
  `AKfycbys-9r8...`), published 2026-09-17 00:41 EDT from the #181 source,
  SHA-256 `26e766c32978740963498961a1760426c7d15e91dcaec723f750567c037d1744`.
  Version 16 is the rollback target. Sandbox got the same source as its
  version 15 first and round-tripped a `clientState` POST/GET.
- CI: the runner image stopped serving the SDK `tools` package;
  `setup-android` is now asked for `platform-tools` only. #182, `5544521`.

### Evidence

- Local gate on the merged code: `--rerun-tasks testDebugUnitTest lintDebug
  assembleDebug` green, **653 unit tests, 0 failures**; all node backend
  suites green; `compileDebugAndroidTestKotlin` green (CI caught that the
  registered local gate omits it - see Outstanding).
- Mutation drills: removing the pending-pen term from `SyncEngine`'s
  NothingToSync guard reds 4 tests; dropping the backwards guard in
  `markLoadedPenStateSynced` reds 1 (that test was added after the first drill
  showed the original could not tell the two apart); backend `>=`->`>` on the
  stale comparison reds client-state case 2; removing the VOIDED filter and
  the lookback filter in the ingestor red one case each.
- Backend round trip on the sandbox: POST `clientState` for `*K1` ->
  `acknowledgedClientState.status = committed`; GET `resource=clientState` ->
  `{"productId":"*K1","productUuid":"10000000-0000-4000-8000-000000000006",
  "productName":"SANDBOX Cartridge","updatedAtEpochMillis":1789619856000}`.
- Panel round trips before this release: sandbox event `c7e76caa…` (1.5
  uses) and one production test press `1f9c1d43-531b-4ab0-85c2-f0b408505e92`
  (1 use of BH Grape Smuggler at 00:01 EDT, 17 Sep) - **void that one from the
  phone**.

### Release provenance

Pull requests merged, in order: #182 `5544521` (CI), #181 `a162cae` (loaded pen),
#183 `2c8b258` (server history ingest), #184 `b90d6df` (release 1.12.0).

Tag `v1.12.0` points at `b90d6df`. Main run `35187821255` at that commit was
green on all six jobs including Emulator API 36 (the earlier main runs
`35184899623` at `a162cae` and `35187012975` at `2c8b258` were green too).
Release run `35188401194` was green on all three of `Verify and build signed
APK`, `Confirm tested main commit` and `Publish verified Cannsheet APK`;
published 2026-09-17 06:12 UTC.

The published artifact is `Cannsheet-Mobile-1.12.0.apk`, 38,020,149 bytes,
SHA-256 `45f8f10b298f23837d4dba5d621adbafc59836a062fb0a4aee5f829ec6fec3ec`, on
`noamvb/cannsheet-mobile-releases`. It was downloaded here independently of CI,
verified against its own published `.sha256`; `aapt` reports package
`com.noamv.cannsheet.mobile`, versionCode 58, versionName 1.12.0, minSdk 24,
targetSdk 36; signing certificate SHA-256
`a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`, identical
to v1.11.0, so the phone updates in place.

Emulator API 24 failed once on #183 with the known infrastructure flake
(`InstallException: Broken pipe (32)`, no test failure); the rerun was green.

### Outstanding

- Verified on the owner's SM-F966W over wireless adb on 2026-09-17: 1.12.0
  (code 58) installed by Obtainium at 03:14; the pre-upgrade loaded pen
  `*P115` was published at 03:23 (`?resource=clientState` names it, the
  Board's "Logs to" line follows it); the Today widget showed 80 s = 8 uses,
  i.e. the phone's two logs plus the panel test press, so the server-history
  ingest works. The test press was then voided from the phone.
- Panel-logged events reach Today only after the periodic worker's next
  prefetch (hours). A push path is out of scope.
- The registered ai-orch verification profile for this repo runs
  `testDebugUnitTest lintDebug assembleDebug` but not
  `compileDebugAndroidTestKotlin`; add it, or every delegate gate will keep
  missing androidTest fakes that need new interface members.
- `sandbox.properties` (gitignored) now exists on this Mac again with the
  sandbox `/exec` URL; the sandbox Apps Script project id is
  `14GdK-_WOr3lFwU9Xmx3OuvhzWKljPYKFH5L7MRCaC0dXsOOHG9LJQ-_o`, production is
  `1C_I7_vWIuZoxQN3ZR3iAcNWq0-X3aJj4cS1EHbk2nW6yJT2dVfgy3vA2` (many "Copy of"
  projects exist; use the ids).

## Cannsheet Mobile v1.11.0 (code 57) - analytics cache deployed, invalidated before writes

**Status: published and independently verified against the release artifact; not
yet driven on the owner's phone.** Production Apps Script is on **version 16**,
published
2026-09-02 22:33 EDT from `main` commit `bda15e6` on the unchanged deployment
`AKfycbys-9r8PnkcTwUwbWL4hITr73n3nF240WQ1Vz6PW_V2XBwzusnMU3Br8tLaCgTiFz7hmQ`.
Version 15 is the rollback target. The full deployment record, measurements and
rollback procedure are in `BACKEND_ANALYTICS_CACHE_ROLLBACK.md`; the reasoning is
ADR-053.

### What changed and why

The analytics response cache from `0462e38` (#83, 14 August) had never been
deployed. Its client half shipped in every release since v1.3.2, so Insights was
re-reading the whole spreadsheet on every open: 10,853 to 13,413 ms of server
duration across three samples on the owner's SM-F966W, reading 4,132 events and
373 purchases.

Preparing the deploy exposed a real defect in the caching design. Bumping
`MUTATION_WATERMARK` is the only cache invalidation there is, and every bump sat
*after* its function's spreadsheet writes - in `applyRecoverableSyncLocked_`,
about 98 lines after, with a Google Forms call in between that can hit the Apps
Script execution limit. An execution stopping in that window left the sheet
changed, the watermark unchanged, and the pre-mutation response served as
`success` for up to six hours. Each such path now bumps before its first write as
well as after; both are needed, and three paths deliberately do not (see
ADR-053).

Separately, Insights and History showed the data's age only for Room-cached
responses, so a backend cache hit rendered no timestamp at all. They now state it
for every dated response.

### Evidence

Measured against version 16 on the live endpoint, four consecutive identical
Insights GETs: **15,429 ms cold, then 120, 118 and 100 ms**, with
`generatedAtEpochMillis` identical across all four - which is both the speed
result and direct proof that a cache hit preserves the payload's original
generation time. History: 16,577 ms cold, 122 ms warm. Wall clock 17.6 s to
2.4 s.

Correctness against the pre-deploy fingerprint: `purchaseRowCount` 373 unchanged
and every data-quality warning identical (3 / 16 / 10 / 14 / 3572), with only the
two counts tracking `eventRowCount` moving by exactly one for a real new log.

All eight Node backend suites pass, and 624 unit tests pass under
`--rerun-tasks`. Both new backend regression tests were confirmed red against
unmodified code before the fix, and three mutations were run: removing the
`applyRecoverableSyncLocked_` pre-write bump reds `a failed recoverable apply
returned the stale W1 cached payload`, removing the V2 one reds `a failed V2 sync
returned the stale W1 cached payload`, and removing the new UI branch reds
`aFreshNetworkResponseStatesItsAge` and nothing else.

A verified full spreadsheet backup was taken first:
`CannsheetG Production Backup 2026-09-02 21-40 EDT - before analytics caching deploy`,
882,829 bytes against the original's 882,022.

### Release provenance

Tag `v1.11.0` points at `a1b4bfc`. Main run `33709160407` at that commit was green
on all six jobs including Emulator API 36; release run `33709172010` was green on
all three of `Verify and build signed APK`, `Confirm tested main commit` and
`Publish verified Cannsheet APK`.

The published artifact is `Cannsheet-Mobile-1.11.0.apk`, 38,003,765 bytes,
SHA-256 `400a802672a1a500ce15162f6303ea99f30397925f83705405948d091dcc99e3`, on
`noamvb/cannsheet-mobile-releases`. It was downloaded here independently of CI and
verified against its own published `.sha256`.

The main run at `bda15e6` (#178) failed Emulator API 36 on an infrastructure
flake - repeated `adb` exit code 1 and `InstallException: Broken pipe (32)`, with
no test failure anywhere in the log. The identical code passed API 36 at
`a1b4bfc`, so that job is green on evidence rather than by assumption.

### Outstanding

- Nothing on the phone has been driven against version 16. The phone was locked
  at release time, so the after-measurements were taken against the live endpoint
  with `curl` rather than through the app.
- No screenshot of the new `Updated <time>` line has been captured.
- Duplicate-safe retries already bumped the watermark before this change and
  still do. Pre-existing, out of scope here, and worth revisiting only if cache
  hit rates disappoint in use.

## Cannsheet Mobile v1.10.0 (code 56) - purchase tax basis, autofill basis, THC formatting

**Status: v1.10.0 published, independently verified, confirmed working against the
live backend, and confirmed on the owner's phone on 2026-09-02.** The owner
installed it through Obtainium and reported the converted figure rendering under
the cost field, matching the emulator result below. Nothing about this release is
outstanding.

The published APK - the release artifact, not a local build - was installed fresh
on an API 36 emulator with the network enabled and driven against production:

- The catalog synced from the live feed.
- Typing `50` under Pre-tax showed `$56.50 with 13% tax`; switching to Post-tax
  left the typed `50` alone and showed `$44.25 before 13% tax`.
- Autofilling a real product (`1964 GLTO #41`) filled cost `41` and previewed
  `$46.33 with 13% tax`, which is `41 * 1.13` to the cent.
- Its THC autofilled as `75.76` - two decimals, no float artifact.
- **No "Tax basis wasn't recorded" warning appeared**, which is the positive
  evidence that the feed delivered a known basis for that product rather than
  omitting the field. Version 15's behaviour is therefore confirmed from the
  client side as well as from `curl`.

The emulator's queues were empty before the network was enabled (`purchase_actions`,
`consumption_actions` and `finish_actions` all 0), so nothing could be written to
the production spreadsheet by this exercise; the app only read the catalog.

### What shipped

Three changes to the purchase form:

- The tax basis is an explicit Pre-tax / Post-tax segmented choice headed "Price
  entered as", sited above the cost field, whose label follows the choice. The
  converted amount shows under the field - `$56.50 with 13% tax`, or `$44.25 before
  13% tax`. ADR-051.
- That basis now travels with every autofilled cost. An unrecorded basis is `null`,
  deliberately distinct from `false`, and autofill warns rather than assuming
  pre-tax. ADR-052.
- The autofilled THC percent is rounded to two decimals, ending a float artifact
  (`27.140000000000004`) that predated both features.

### Release provenance

**Pull request merged**

- `#173` "Purchase tax basis, autofill basis, and THC formatting (release 1.10.0)",
  squash-merged as `9144353326ef3e0a033b53524902c27cae4d4fb6`.

**Main validation**

- Run `33690914527`, `event: push`, `headSha`
  `9144353326ef3e0a033b53524902c27cae4d4fb6`, final conclusion `success`.
- All six required jobs individually `success`: Classify changes and scan
  repository, Backend validation, Android static validation, Emulator API 24,
  Emulator API 36, Cannsheet Android PR validation.
- `Emulator API 24` failed on the first attempt with
  `Failed to install split APK(s)` / `ShellCommandUnresponsiveException` and an
  emulator console that never started - the install flake the workflow itself
  documents, not a test failure. `Emulator API 36` passed on that same attempt with
  the same commit. The failed jobs were re-run on the **same run id**, so the green
  result still carries `event: push` at the required SHA.

**Tag**

- Annotated tag `v1.10.0` points at exactly `9144353326ef3e0a033b53524902c27cae4d4fb6`,
  which was the tip of `origin/main` when the tag was pushed.

**Published assets** (`noamvb/cannsheet-mobile-releases`, 2026-09-02T22:52:21Z)

- `Cannsheet-Mobile-1.10.0.apk` (38,003,765 bytes)
- `Cannsheet-Mobile-1.10.0.apk.sha256`
- APK SHA-256 `dac9d2b6dc5283f850d6a03e94ae613039e4f00227d4cf13538383a69d002497`,
  recomputed from the downloaded asset and matching the published checksum file.
- `aapt2 dump badging` reports package `com.noamv.cannsheet.mobile`, versionCode 56,
  versionName 1.10.0.
- Signing certificate SHA-256
  `a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`, **identical to
  v1.9.1**, so the phone updates in place.

### Backend

The client half needs a backend that publishes two fields, and that backend is live.

- Production Apps Script **version 15** on the unchanged deployment
  `AKfycbys-9r8PnkcTwUwbWL4hITr73n3nF240WQ1Vz6PW_V2XBwzusnMU3Br8tLaCgTiFz7hmQ`;
  the `/exec` URL never changed.
- Verified against the live endpoint: `taxRate: 0.13`, 373 products, every one
  carrying `postTax` where the sheet records it.
- Version 14 was published first and was **wrong**: it used `truthy_`, which never
  returns null, so a blank `Post-tax` cell became `false` and the client's
  unknown-basis warning could never fire. Version 15 uses `strictPostTax_` - the
  same helper `analyticsCost_` uses to refuse a final cost rather than guess - and
  omits the field unless the sheet records it.
- **The live sheet has no blank `Post-tax` cells**, so versions 14 and 15 return
  identical data for it (106 true, 267 false, 0 absent). The live feed therefore
  cannot distinguish them. What proves version 15's behaviour is the backend test
  seeding an explicit blank row, which is mutation-verified: reverting to `truthy_`
  reds it. The unknown-basis warning is correspondingly dormant for catalog products
  on this data, and active for saved defaults stored before this release.

### Still outstanding

- **The signing certificate is `CN=Android Debug`** and has been for every release,
  including 1.9.1. Updates work and nothing is broken, but this is a weaker signing
  story than a dedicated release key. Changing it would break in-place updates and
  force a reinstall, so it is a deliberate decision to make rather than drift into.
- The four July backend documents were **stale rather than wrong**, and an earlier
  revision of this file said "wrong", which overstated it.
  `BACKEND_SYNC_PERFORMANCE_REPORT.md` and `BACKEND_ANALYTICS_ROLLBACK.md` were
  committed on 2026-07-17 and 2026-07-18, when versions 8 and 9 were in fact live;
  their present-tense status lines simply stopped being true as production moved
  on. All four now carry a dated historical-record banner pointing at this file and
  `docs/PROJECT_STATE.md`, and the two rollback documents warn that following their
  version targets today would discard every backend change since July - the sync
  one names version 7, which would undo the tax-basis fields v1.10.0 depends on.
- Still genuinely open: the analytics caching and batch-fetch work of `0462e38`
  (14 Aug, #83) has **never served production**. It is a separate thing from the
  July "backend sync performance" of version 8, which is what the similarly named
  document describes. A deployment titled "Backend sync performance and recoverable
  atomic apply" sits in the project's Archived list. Whether `0462e38` should ever
  be deployed is undecided.
- ~~On-device confirmation of 1.10.0 by the owner.~~ Done 2026-09-02: installed
  through Obtainium, converted figure rendering under the cost field.

## Cannsheet Mobile v1.9.1 (code 55) - pen widget step repaired, label case corrected

**Status: v1.9.1 published and independently verified on 2026-08-31.** It corrects a
label-case defect the owner found on their phone immediately after installing v1.9.0.

### The v1.9.0 follow-up defect

v1.9.0 drew the new step labels as `+10S` / `-10S`, which reads as `+105`. The strings are
lowercase (`+%1$ds`), but `Button` styles default to `textAllCaps`, applied as a
`TransformationMethod` at draw time, so the lowercase `s` never reached the screen. Fixed by
setting `android:textAllCaps="false"` on the five labelled buttons in
`widget_pen_consumption.xml`. The three preset buttons had rendered `10S` / `20S` / `30S`
since #109 and are corrected in the same change rather than left inconsistent.

The renderer test did not catch it, and the reason is worth keeping: it asserted
`plus.text.toString()`, which returns the **stored** string, while `textAllCaps` transforms
the text only at draw time. The assertion was green while the screen showed something else -
the same "check the announcement, not the behaviour" mistake that let the original step
defect ship, one level down. `PenWidgetRendererTest` now asserts through
`transformationMethod.getTransformation(...)`, so it fails against the un-fixed layout.

The pen widget's `+`/`-` buttons now move the counter by the number they state, and that
number is configuration rather than a function of widget size. See ADR-050 and the
"Pen widget step size" section of `docs/PROJECT_STATE.md`.

### What was wrong, and why the tests did not catch it

The report was "the 2x2 widget steps by 30 seconds instead of 10". Driving the real widget
over ADB on a Fold 7 (API 36) showed something narrower and worse: the `+` button announced
**"Increase duration by 10 seconds"** and moved the counter to **30**. The label and the
behaviour disagreed.

`PenWidgetUpdater` builds three `RemoteViews` per instance on API 31+ - buckets 110x110,
140x160 and 280x320 - and `PenWidgetSizing.resolve` gave only the largest
`stepSeconds = 30`. Each bucket asked `pendingIntent()` for a step-carrying intent, but
that function derived uniqueness from `31 * appWidgetId + action.hashCode()` and the data
URI `cannsheet://pen-widget/<id>/<action>`, neither of which encoded the step.
`PendingIntent` equality ignores extras, so all three buckets resolved to one
`PendingIntent`, `FLAG_UPDATE_CURRENT` made the last write win, and the large bucket was
written last. Text and content descriptions live inside each bucket's own `RemoteViews`,
so they stayed truthful while the effect did not.

The size rule added in #110 therefore never gated anything. It only poisoned the shared
intent, so **every** API 31+ pen widget without an explicit override stepped by 30 at every
size, including compact.

The suite passed throughout, because `PenWidgetRendererTest` asserted the content
description - precisely the half that was correct - and nothing asserted what a tap
actually delivered. A test that checks only the announcement of a behaviour cannot see the
behaviour diverge from it.

### What v1.9.0 changed

- `stepSeconds` is removed from `PenWidgetLayoutSpec` entirely, so "the buckets disagree
  about the step" is no longer expressible rather than merely false. `PenWidgetUpdater`
  resolves the step once per update and passes that one value to every bucket.
- `EXTRA_STEP_SECONDS` is deleted. `PenWidgetActionRouter` reads the step from the config
  repository it already held, so a stale `PendingIntent` issued by a pre-upgrade build is
  inert rather than authoritative - the fix applies on the next tap, before any repaint.
- `pendingIntent()`'s data URI now encodes `commitId` too, under a comment recording the
  rule that the URI must cover every extra that changes the intent's effect.
- An app-wide default step lives under an unsuffixed key in the existing
  `pen_widget_config` DataStore. `PenWidgetConfigRepository.effectiveStepSeconds` is the
  only resolver of `override ?: default`. The key is excluded from `clear`,
  `remapWidgetIds` and legacy adoption, and an invalid stored value falls back in memory
  without being rewritten.
- `stepSecondsOverride == null` inherits that default. The configure screen previously read
  null as 10 and wrote it back as an explicit 10, so merely opening and saving it detached
  that widget from the default forever; it now carries null through and offers Default.
- Full layouts render `+10s` / `-10s`. The compact layout keeps bare symbols, where a
  four-glyph label clips at roughly 45dp. The step's invisibility is why this survived.
- The provider is `reconfigurable`, and Settings gains a Widgets section with the app
  default plus a per-widget list. Before this the picker existed but could only be reached
  by deleting the widget and adding it again.
- Both Settings write paths await their repaint. A durable configuration write paired with
  a dropped render would reproduce the same label/behaviour split, so
  `PenWidgetUpdater.updateAllNow` suspends until the render lands rather than enqueueing it.

### Behaviour change on upgrade

Every placed pen widget without an explicit override steps by 10 after this release,
**including a large one that stepped by 30 before**. No migration runs. An individual
widget can be raised to 30 in Settings, or by long-pressing it and reconfiguring.

### Verification performed

- Local, `--rerun-tasks` so nothing came off a cache:
  `testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`,
  `BUILD SUCCESSFUL in 8m 12s`, **588 unit tests, 0 failures, 0 errors, 0 skipped**
  (the `main` baseline was 587: three deleted size-to-step assertions, four new
  default-step tests).
- 8 Node backend suites pass; `python3 -m unittest tests/test_backend_sync_benchmark.py`
  reports 13 tests, OK.
- Pull request `noamvb/cannsheet-mobile#169`: all five checks green, including
  Emulator API 24 at 4m53s, which is the first actual execution of the new instrumentation
  coverage.

New coverage aimed squarely at the gap that let this ship:

- `PenWidgetActionRouterTest.incrementUsesEffectiveStepAndIgnoresAStaleIntentExtra` puts
  the literal legacy extra name with value `30` on the intent and asserts the draft still
  moves by the effective step. It fails against the pre-fix code.
- `PenWidgetRendererTest.everyBucketKeepsVisibleLabelsAndDescriptionsAlignedWithTheEffectiveStep`
  asserts visible text and content description **together** across all three buckets, with
  a step of 5 so it cannot pass by coincidence.
- `PenWidgetDefaultStepTest` covers inheritance, clamping, `clear`/`remapWidgetIds`
  preservation, and that an invalid stored value falls back without being repaired.
- `PenWidgetConfigureActivityTest` proves a null override selects Default and Save emits
  null.

### Release provenance

**v1.9.1 (code 55) - current release**

- Pull request merged: `noamvb/cannsheet-mobile#171`, squash merged
- Squashed commit on `main`: `d59925899880333eea133efc53993fde4f029d2b`
- Proving `main` run: `33342371169`, `event=push`, `conclusion=success`, all six required
  jobs green on that exact SHA. The first attempt failed in `Emulator API 36` on
  `PurchaseContentTest.changingTypeClearsFieldsButReselectingTypePreservesThem` with
  `Failed to inject touch input`, alongside `Failed to start Emulator console for 5554` and
  repeated `adb` exit-code-1 lines - the documented emulator flakiness, unrelated to the
  change, which touched only a widget layout and a widget renderer test. Re-running the
  failed jobs on the same commit passed.
- Annotated tag `v1.9.1` points at exactly that commit
- Published at 2026-08-31T00:44:16Z: `Cannsheet-Mobile-1.9.1.apk` (38,003,769 bytes) and
  `Cannsheet-Mobile-1.9.1.apk.sha256`
- APK SHA-256: `71795b64d0fb3caa9e0fa920c1997c76a5b1e3d3792984678324318c88a50ed8`,
  re-downloaded and checked with `shasum -a 256 -c` after publication: OK. `aapt dump
  badging` reports package `com.noamv.cannsheet.mobile`, versionCode 55, versionName 1.9.1,
  compileSdk 36.
- Signing certificate: `a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`
  (`CN=Android Debug`), unchanged from v1.9.0 and v1.8.1. `apksigner verify` reports
  `Verifies` with APK Signature Scheme v2. The phone updates in place; no Room data or
  queued offline row is at risk.

**v1.9.0 (code 54) - superseded after a few hours**

- Squashed commit `7e9eb58f4dcb9687d8594deb324be7ae4926b97f` via `#169`, proving run
  `33315508424`, published 2026-08-30T14:10:40Z, APK SHA-256
  `cdb44ae9fd126e0b987b62f0f8bba6a1a5d87cac9e56ff6147321ec0f598eb6d`, same signing
  certificate. Provenance recorded in `#170`. Superseded by v1.9.1 because its new step
  labels drew as `+10S`.

### Known limitations

- Instrumentation tests were compiled locally but not executed there.
  `connectedDebugAndroidTest` targets every attached device, and the owner's phone carries
  the release build; a debug build shares its application id but is signed differently, so
  running them would force an uninstall and destroy user data. CI covers them on both API
  levels.
- No screenshots accompany the pull request, for the same reason: the only attached device
  is the owner's phone, and no local emulator is provisioned. The visible change is
  asserted mechanically instead, by the renderer test checking exact button text at each
  bucket.
- On-device confirmation of the shipped v1.9.1 build has **not** happened, for either the
  stepping fix or the label case, and cannot until the owner installs 1.9.1 through
  Obtainium. The pre-fix behaviour of both was confirmed on hardware - the step defect over
  ADB, the `+10S` label by the owner reading their own home screen - but neither fix is
  proven anywhere except CI.
- The `+10s` / `-10s` labels need a widget repaint to appear, so they may briefly lag after
  install. The stepping itself is correct from the first tap, because the router reads
  configuration rather than an intent extra.

### Operational notes worth keeping

- The CI emulator jobs are intermittently unreliable in a way that looks like a hang: test
  progress stops at a fixed count and the step is killed at its 20-minute cap. One such
  failure and its immediate re-run on the same commit are decisive - the failed run went
  silent at 105/174 for sixteen minutes, the re-run completed the remaining 66 tests in
  nine seconds. Silence at a fixed count means a dead emulator, not a hanging test, and
  warrants a re-run rather than a fix.
- `main` is governed by a repository ruleset, not classic branch protection, and it sets
  `required_review_thread_resolution`. An unresolved review thread blocks the merge even
  with every check green, and `gh pr merge` reports only "the base branch policy prohibits
  the merge". The `chatgpt-codex-connector` bot posts review threads automatically, so a
  pull request can arrive at "all checks green" and still be unmergeable until those
  threads are read and resolved. Resolving requires the GraphQL `resolveReviewThread`
  mutation; `gh pr review` does not do it.
- A delegated run can be killed by the orchestrator's own harness restarting, leaving every
  file written, no verification build ever run, and no report. A complete-looking working
  tree says nothing about whether the code compiles. Run acceptance yourself.

### Next step for the phone owner

v1.9.1 is published. Open Obtainium, pull to refresh or tap **Check for updates**, and
install Cannsheet Mobile 1.9.1.

Two things worth a look after installing: tap `+` once on the small pen widget and confirm
the counter moves to 10 rather than 30, and confirm the buttons now read `+10s` / `-10s`
rather than `+10S`. On-device confirmation of either has not been performed here - the
pre-fix behaviour was reproduced on hardware, but both fixes are so far proven only by CI,
because installing a debug build over the release build would force an uninstall and
destroy the Room database.
