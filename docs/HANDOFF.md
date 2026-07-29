# Latest handoff

Last updated: 2026-07-29

Branch: `codex/history-corrections-backend`

Repository base: `HEAD` and `origin/main` are
`7ee3f3a6995475c25addc712259cc44f8530b7a0`, tagged `v1.2.16`.

Working tree status: backend correction implementation, tests, CI registration,
and shared-context documentation are modified or untracked. Nothing is staged.
Nothing from this branch has been committed, pushed, deployed, or released.

## Purpose of this session

Implement the backend milestone for user-editable consumption History while
preserving auditability, offline retry safety, effective analytics, and strict
sandbox/production isolation. Stop at the plan's approval gate before creating
the pull request.

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

## Files changed by this task

- `.github/workflows/android-pr-checks.yml`
- `AGENTS.md`
- `backend_additions.gs`
- `sandbox_provisioning.gs`
- `tests/backend_analytics_test.js`
- `tests/backend_corrections_test.js`
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

The correction scale case passed with 3,600 events and 600 corrections.

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
the Python benchmark, and diff validation after the sandbox-safety repair.

## Validation not performed

- GitHub Actions has not run for this branch.
- No pull request or remote code review exists.
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
- `gh auth status` reported that the active `noamvb` token is invalid. Public
  release inspection still worked anonymously, but authenticated pull-request
  actions require reauthentication or a verified signed-in browser session.

## Approval gate and next action

The local backend milestone is ready. Obtain explicit user approval before:

1. staging and committing these files;
2. pushing `codex/history-corrections-backend`; and
3. opening the focused backend pull request.

After approval, review the exact staged diff, create the PR with the complete
test and data-safety evidence, and wait for backend CI. Do not merge without a
separate approval. After merge, do not deploy to the live sandbox without its
separate approval and target-identity verification.

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
