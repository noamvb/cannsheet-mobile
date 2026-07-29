# Latest handoff

Last updated: 2026-07-28

Branch: `ci/tiered-validation-release-gate`

Working tree status: Modified workflow definitions (`.github/workflows/android-pr-checks.yml`, `.github/workflows/release-apk.yml`) and project documentation (`docs/DECISIONS.md`, `docs/PROJECT_STATE.md`, `docs/HANDOFF.md`).

## Purpose of this session

Implement a focused overhaul of GitHub Actions validation and release workflows to optimize PR speed, enforce API 24/36 matrix validation on `main`, require exact-SHA main validation proof for release publishing, introduce AVD snapshot caching, and harden signing/publishing safeguards.

## Work completed

- Refactored `.github/workflows/android-pr-checks.yml`:
  - Added triggers for `pull_request` (main), `push` (main), and `workflow_dispatch`.
  - Added `classify` job for security scanning (forbidden tracked files and private key headers) and fail-safe path classification (docs-only skips android/backend, backend-only runs backend, Android PRs run API 24, main push runs API 24 + API 36 matrix).
  - Added `backend` job for checked-in Node and Python backend test suites.
  - Added `android-static` job for unit tests, lint, and debug assemble with `cannsheet-debug-apk` artifact upload.
  - Added `emulator` job with AVD snapshot restore/save caching (`cannsheet-avd-v1`) seeded from `main`.
  - Added `required` job (`Cannsheet Android PR validation`) preserving branch protection required status check contract.
- Restructured `.github/workflows/release-apk.yml`:
  - Set permissions to `contents: read` and `actions: read`.
  - Added `confirm-main-validation` job verifying via GitHub Actions API that the exact tagged commit SHA passed all validation jobs on `main` push.
  - Updated `verify` job to check tag vs `versionName`, verify SHA equals `origin/main`, enforce version code monotonicity against public releases, run unit/lint, build signed release with `--no-configuration-cache`, verify signature/package badging, and upload `signed-apk` artifact.
  - Added `publish` job depending on `verify` and `confirm-main-validation` to verify prepared APK, reject existing public release tags (no `--clobber`), create release, and perform post-publication verification.
- Documented decisions and state updates in `docs/DECISIONS.md` (ADR-004), `docs/PROJECT_STATE.md`, and `docs/HANDOFF.md`.

## Current project state

- All workflow files updated according to specification.
- Local validation tests (classification rules, exact-SHA confirmation logic, backend suites, Gradle unit tests, lint, assembleDebug, configuration cache reuse) passed cleanly.

## Validation performed

- `git diff --check` passed cleanly with no syntax errors.
- Path classification logic tested across 10 representative file sets (100% pass).
- Exact-SHA confirmation logic tested across 14 scenarios (100% pass).
- Local backend test suites executed and passed:
  - `node tests/backend_contract_test.js`
  - `node tests/backend_analytics_test.js`
  - `node tests/backend_recovery_test.js`
  - `node tests/backend_spreadsheet_test.js`
  - `node tests/fake_sheets_batch_update_test.js`
  - `node tests/sandbox_performance_fixture_test.js`
  - `node tests/sandbox_provisioning_test.js`
  - `PYTHONPATH=. python -m unittest discover -s tests -p "test_backend_sync_benchmark.py"`
- Local Gradle static validation passed:
  - `.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug` (passed)
  - Gradle configuration cache reuse verified (`Reusing configuration cache`).

## Validation not performed

- Live GitHub Actions execution on GitHub servers (requires pushing branch and opening PR / pushing tag).
- Emulator API 24 and API 36 execution on live runner KVM environment.
- Live release publishing to `noamvb/cannsheet-mobile-releases`.

## Recommended next action

1. Commit the changes to `ci/tiered-validation-release-gate`.
2. Push branch to `origin/ci/tiered-validation-release-gate`.
3. Open a draft pull request against `main` for PR validation testing.

## Risks, assumptions, and unresolved questions

- Live GitHub Actions behavior (AVD snapshot loading speed, `gh api` rate limits, emulator execution time on API 36) remains to be observed during the first live CI runs.
- Release workflow requires GitHub Actions token permissions (`actions: read`) to query workflow run jobs.

## Relevant files

- `.github/workflows/android-pr-checks.yml`
- `.github/workflows/release-apk.yml`
- `docs/DECISIONS.md`
- `docs/PROJECT_STATE.md`
- `docs/HANDOFF.md`
- `AGENTS.md`
- `GEMINI.md`
