# Latest handoff

Last updated: 2026-07-29

Branch: `codex/history-corrections-backend`

Repository position:

- the latest safety-repair implementation commit is
  `a39e19902215e670c9d5817dc30a35aa2b7ba5f1`;
- GitHub Actions validated the prior implementation-plus-handoff head
  `05c4b58e89b2451f54a566e244f1761c578ca68a`;
- the initial backend milestone is
  `870e99215506c36802c2430dfa9bd0449d414286`;
- `origin/main` remains
  `7ee3f3a6995475c25addc712259cc44f8530b7a0`, tagged `v1.2.16`; and
- [PR #19](https://github.com/noamvb/cannsheet-mobile/pull/19) targets `main`.

Working tree status: clean after the implementation and this handoff refresh are
committed and pushed to PR #19. Nothing has been merged, deployed, provisioned,
enabled, or released.

## Purpose of this session

Implement the backend milestone for user-editable consumption History while
preserving auditability, offline retry safety, effective analytics, and strict
sandbox/production isolation. Take the focused backend pull request through its
approved merge, then stop at the separate live-sandbox approval gate.

## Product outcome

The intended user-facing feature will let a person correct, void, or restore a
mistaken consumption entry from History. This branch implements only the server
contract and safety foundation. The current Android APK has no correction UI or
local correction queue yet.

## Work completed

- Added append-only `ConsumptionEventCorrections` support with:
  - `REPLACE`, `VOID`, and `RESTORE` operations;
  - stable correction/action UUIDs;
  - expected-head conflict detection;
  - exact duplicate acknowledgement and different-content conflict rejection;
  - immutable replacement validation; and
  - safe product-reopen checks.
- Integrated corrections with:
  - mixed version-2 sync requests and exact acknowledgements;
  - the durable apply journal and recovery/finalization path;
  - reconciliation;
  - product projections;
  - legacy and current Insights/History reads;
  - History audit metadata; and
  - correction-aware stale-cursor detection.
- Added additive rollout helpers:
  - production schema provisioning starts with correction writes disabled;
  - reconciliation is required before enabling writes;
  - sandbox provisioning enables the schema only after its target guard passes.
- Registered the focused correction suite in backend-only CI and classified
  `sandbox_provisioning.gs` as backend code.
- Added fake-runtime support and regression, scale, recovery, capability-gate,
  lifecycle, cursor, audit, duplicate, and conflict coverage.
- Made version-2 consumption, finish, and correction wall-clock parsing explicit
  in `CANN.TIME_ZONE`, independent of the Apps Script or test host timezone.
- Updated architecture, project-state, decision, contributor, and handoff
  documentation.

## Independent safety review

The first read-only verification pass found one defect: `provisionSandbox()`
could create schema before checking an existing production Config marker.

The repaired implementation now performs all environment, configured/active/
bound spreadsheet, form destination, and existing Config checks before its first
mutation. A production-marker regression compares full before/after snapshots
and proves zero cell writes, structural changes, batch writes, or form changes.
The second independent verification pass found the backend milestone ready.
Required GitHub review then identified two additional P1 edge cases: a request
could retain a stale correction-write gate while waiting for the script lock,
and a nonexistent New York spring-forward wall time could be stored with a
different normalized timestamp. The repair rereads mutable rollout state under
the acquired lock and strictly rejects nonexistent correction replacement
times. Deterministic regressions cover both cases, including the intentional
idempotent `SyncLedger` audit row for a rejected request.

## Pull request CI diagnosis and repair

PR #19's first run, `30496452018`, passed classification, Android static
validation, and API 24 emulator validation. Backend validation failed in the new
same-request correction retry.

A Terra/high read-only audit reproduced the failure locally with `TZ=UTC`.
`parseClientDateTime_()` used host-local `new Date()` for the app's zone-less
wall-clock value. An Eastern host stored 11:30 as 15:30Z; a UTC host stored it as
11:30Z. Reading the persisted row in canonical New York time then rejected the
retry with `INTERNAL_ERROR` because its local time no longer matched.

The production repair uses Apps Script `Utilities.parseDate()` with
`CANN.TIME_ZONE` and a timezone round-trip check. The fake runtime now models
that API. Correction commit/retry, recovery, and spreadsheet expectations are
timezone-stable, and the complete backend matrix passes with both UTC and New
York host zones.

Follow-up GitHub Actions run `30498099177` passed all required jobs for the
prior exact head `05c4b58e89b2451f54a566e244f1761c578ca68a`:

- Classify changes and scan repository;
- Backend validation;
- Android static validation;
- Emulator API 24; and
- Cannsheet Android PR validation.

Safety-repair commit `a39e19902215e670c9d5817dc30a35aa2b7ba5f1`
adds the lock-interleaving and DST-gap regressions. The focused correction suite
passed under UTC and New York host zones; the final required PR checks remain the
authoritative merge gate for that head.

## Files changed by this task

- `.github/workflows/android-pr-checks.yml`
- `AGENTS.md`
- `backend_additions.gs`
- `sandbox_provisioning.gs`
- `tests/backend_analytics_test.js`
- `tests/backend_corrections_test.js`
- `tests/backend_recovery_test.js`
- `tests/backend_spreadsheet_test.js`
- `tests/fake_apps_script_runtime.js`
- `tests/sandbox_provisioning_test.js`
- `docs/ARCHITECTURE.md`
- `docs/DECISIONS.md`
- `docs/PROJECT_STATE.md`
- `docs/HANDOFF.md`

No Android source, version, endpoint, application ID, namespace/package,
signing, credential, secret, tag, or release file was changed.

## Validation performed

All eight Node backend suites passed:

```powershell
node tests/backend_analytics_test.js
node tests/backend_contract_test.js
node tests/backend_corrections_test.js
node tests/backend_recovery_test.js
node tests/backend_spreadsheet_test.js
node tests/fake_sheets_batch_update_test.js
node tests/sandbox_performance_fixture_test.js
node tests/sandbox_provisioning_test.js
```

The correction scale case passed with 3,600 events and 600 corrections. The
complete eight-suite matrix passed twice:

```powershell
$env:TZ='UTC'
Get-ChildItem tests -Filter '*_test.js' | Sort-Object Name |
  ForEach-Object { node $_.FullName }

$env:TZ='America/New_York'
Get-ChildItem tests -Filter '*_test.js' | Sort-Object Name |
  ForEach-Object { node $_.FullName }
```

The pre-fix source from exact commit `870e992` fails the UTC correction retry
with the expected diagnostic:

```text
INTERNAL_ERROR: Correction replacement local date/time does not match its timestamp
```

Both Apps Script syntax checks passed:

```powershell
Get-Content -Raw backend_additions.gs | node --check
Get-Content -Raw sandbox_provisioning.gs | node --check
```

The bundled Python runtime passed 13 benchmark tests:

```powershell
C:\Users\noamv\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe `
  -m unittest discover -s tests -p 'test_backend_sync_benchmark.py'
```

Diff whitespace validation passed:

```powershell
git -c core.fsmonitor=false diff --check origin/main
```

The independent verifier reran the complete Node matrix, both syntax checks,
the Python benchmark, and diff validation after the sandbox-safety repair. A
separate CI-audit agent reproduced and reviewed the timezone failure, and
separate Sol/xhigh and Terra/high agents implemented and tested its production
and test repairs.

## Validation not performed

- No Apps Script source has been deployed.
- No live sandbox spreadsheet, trigger, web app, or reconciliation has been
  exercised.
- Production has not been provisioned, reconciled, enabled, or deployed.
- Android code has not been changed, so Gradle, emulator, and device checks for
  the eventual UI are pending.
- No signed APK has been built or published for this feature.

## Current external state

- `v1.2.16` is the current source tag and local version metadata
  (`versionCode` 19).
- A read-only public release query on 2026-07-29 confirmed
  [v1.2.16](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.16)
  with an APK and checksum.
- GitHub CLI was reauthenticated through the signed-in in-app browser and now
  reports active `noamvb` access with repository/workflow scopes.

## Approval gate and next action

The user approved the initial commit, push, PR, and squash merge. Merge only
after the exact safety-repair head passes required checks and all required review
conversations are resolved. After merge, do not deploy to the live sandbox
without its separate approval and target-identity verification.

Android implementation starts only after the backend contract is merged and
verified in the sandbox.

## Remaining delivery sequence

1. Backend PR, CI, review, and approved merge.
2. Approved live sandbox provisioning and backend contract verification.
3. Android Room migration, correction queue, network DTOs, repository logic,
   ViewModel state, and History edit/void/restore UI.
4. JVM, migration, Compose, CI, emulator, and physical-device verification
   against the sandbox.
5. Approved production disabled-first provisioning, reconciliation, deployment,
   and write enablement.
6. Approved version bump, focused release PR, tag, signed APK publication, and
   independent public-artifact verification.

## Risks and unresolved questions

- Live Apps Script, spreadsheet schema, trigger, and deployment identity are
  not established by local tests.
- Version-2 app times are New York wall-clock values. Tests and future code must
  use the canonical timezone explicitly rather than inherit the machine's zone.
- The user confirmed that ordinary app entries will continue arriving during
  this work. Treat production as continuously changing: never rely on a saved
  row count or long-lived snapshot. Provisioning, reconciliation, and write
  enablement must each use the script lock and freshly read live state. Ordinary
  logging must remain available throughout the additive, disabled-first rollout.
- A correction is data-sensitive. The Android queue must retain a correction
  until the server returns the exact accepted acknowledgement.
- Stale correction-head conflicts need plain user-facing recovery rather than
  silent overwrite.
- Product reopen remains deliberately conservative; unsafe cases return
  `REOPEN_NOT_SAFE`.
- The final device and Android version for physical validation have not been
  selected.
