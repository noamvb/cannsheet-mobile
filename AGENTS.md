# Cannsheet Mobile contributor instructions

## Project overview

Cannsheet Mobile is a personal Android app for recording cannabis purchases and
consumption. It is a single-module Kotlin application with a Compose UI, Room
storage, an offline action queue, and a Google Apps Script/Google Sheets backend.

## Required reading

Before substantial work, read:

- `README.md` and `CONTRIBUTING.md`;
- `docs/PROJECT_STATE.md` for the verified current state;
- `docs/ARCHITECTURE.md` for system boundaries and data flows;
- `docs/DECISIONS.md` for durable decisions; and
- `docs/HANDOFF.md` for the latest cross-session transfer state.

Treat code and configuration as stronger evidence than documentation. If they
disagree, verify the behavior and update the shared-context documents.

## Stack and repository structure

- Kotlin, Jetpack Compose/Material 3, Navigation Compose, coroutines/StateFlow,
  Room/KSP, and DataStore Preferences.
- Retrofit, OkHttp, and Moshi communicate with the configured Google Apps
  Script endpoint; `backend_additions.gs` is the checked-in server source.
- `app/src/main/java/com/example/ui`: screens, navigation, UI state, and view
  models.
- `app/src/main/java/com/example/data`: Room, repositories, network contracts,
  preferences, synchronization rules, and analytics caching.
- `app/src/test`: local JVM tests; `app/src/androidTest`: device/emulator tests.
- `tests`: dependency-free Node.js backend tests and a Python benchmark test.
- `.github/workflows`: pull-request validation and explicitly triggered release
  automation.

Keep detailed architecture and changing implementation status in the linked
documents, not in this file.

## Coding and documentation conventions

- Follow Kotlin's official code style and the existing Compose/data-layer split.
- Prefer immutable network and queue payloads, stable UUIDs, narrow
  responsibilities, and regression tests near the affected boundary.
- Keep one coherent change per pull request; put unrelated cleanup elsewhere.
- Use repository-relative paths in documentation.
- Do not invent test results, live deployment state, decisions, or rationale.
- Keep `docs/PROJECT_STATE.md`, `docs/DECISIONS.md`, and `docs/HANDOFF.md`
  current when their subject matter changes.
- Keep vendor adapters such as `GEMINI.md` concise. They should point agents to
  the canonical shared-context documents instead of duplicating project facts.

## Change and release rules

- Propose feature and bug-fix work through a pull request targeting `main`.
- Keep each pull request to one coherent change. Put unrelated cleanup in another pull request.
- Do not change `versionCode` or `versionName`, create a tag or release, build a signed release, or modify signing configuration unless the task explicitly requests release work.
- Do not change the production Apps Script endpoint, application ID, package/namespace, environment IDs, credentials, or secrets unless the task explicitly requests that exact change.
- Never commit keystores, credentials, tokens, `sandbox.properties`, or other local secrets.

## Verified checks

The project requires JDK 17 or newer, Gradle 9.3.1 through the wrapper, Android
SDK Platform 36.1, and Android Build Tools 36.0.0. Select a compatible JDK in
Android Studio or through `JAVA_HOME`.

On Windows:

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest assembleDebug
.\gradlew.bat --no-daemon lintDebug
node tests/backend_analytics_test.js
```

On macOS, Linux, or GitHub Actions, use `./gradlew` for the same Gradle tasks.
Additional checked-in backend suites can be run directly with Node:

```powershell
node tests/backend_contract_test.js
node tests/backend_recovery_test.js
node tests/backend_spreadsheet_test.js
node tests/fake_sheets_batch_update_test.js
node tests/sandbox_performance_fixture_test.js
node tests/sandbox_provisioning_test.js
python -m unittest tests/test_backend_sync_benchmark.py
```

The JavaScript tests use Node built-ins and checked-in fake runtimes; there is
no npm install step. No separate formatting or type-check command is configured.
Gradle compilation and Android lint provide the available Kotlin checks.

Run the checks relevant to every change. Report every test, build, lint, device check, or manual check that was not run or did not pass; do not describe an unexecuted check as successful.

For a visible UI change, include screenshots or a short recording in the pull request. If screenshots cannot be produced, state why and describe the manual visual check that was performed instead.

## Data safety and regression coverage

- Treat Room migrations and the offline purchase, consumption, and finish queues as user data. Preserve existing rows and stable IDs unless an explicitly approved migration requires otherwise.
- Never use destructive database fallback as a shortcut. Add a forward migration and test upgrades from every supported prior schema when the schema changes.
- Preserve synchronization idempotency, request/action/event UUIDs, environment checks, acknowledgement handling, locking, and retry behavior. A timeout must not cause duplicate spreadsheet rows or silently discard a queued action.
- Delete a pending purchase, consumption, or finish action only after the existing acknowledgement rules prove that the server committed it or already has the same immutable ID.
- Keep spreadsheet writes narrow and recoverable. Consider partial writes, retries, concurrent requests, duplicate delivery, and reconciliation before changing Apps Script write paths.
- Treat purchase, consumption, inventory, analytics, and deletion behavior as data-sensitive. Document any destructive effect and provide a rollback or recovery path.
- Add regression tests whenever changing Room persistence or migrations, synchronization, analytics normalization/caching/pagination, spreadsheet write behavior, deletion, or another destructive path.

## Pull request and self-review requirements

Every pull request description must include:

- summary;
- motivation or root cause;
- important implementation decisions;
- automated tests run and their exact results;
- manual validation performed;
- risks and data-safety considerations; and
- screenshots or recordings for visible UI changes, or an explanation of why they are absent.

Before committing, review the complete diff. Remove unrelated changes and confirm that it contains no secrets, accidental version changes, release/tag changes, signing changes, or unintended production endpoint, application ID, package, or environment changes.

## Completion and handoff protocol

Before declaring work complete or transferring it to another account/session:

1. Review the complete final diff.
2. Run the relevant available validation.
3. Record the exact commands and outcomes.
4. Report validation that could not be performed; never imply it passed.
5. Update `docs/PROJECT_STATE.md` when implementation state changes.
6. Record meaningful durable decisions in `docs/DECISIONS.md`.
7. Refresh `docs/HANDOFF.md` before transferring work.
8. Check that no secrets, credentials, generated artifacts, or unrelated files
   were introduced.

Use `.agents/skills/project-handoff/SKILL.md` when preparing a formal
cross-account or cross-session handoff.
