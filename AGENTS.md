# Cannsheet Mobile contributor instructions

## Project overview

Cannsheet Mobile is a single-module Kotlin Android application. Jetpack Compose provides the UI, Room stores products and pending actions locally, and Retrofit/OkHttp/Moshi communicate with the Google Apps Script backend in `backend_additions.gs`. The backend reads and writes the connected Google Sheets data.

## Architecture and data flow

- `app/src/main/java/com/example/ui` owns Compose screens, navigation, UI state, and `CannsheetViewModel`.
- `app/src/main/java/com/example/data` owns Room entities and migrations, the local repository, network payloads, preferences, synchronization acknowledgement logic, and analytics caching.
- Purchases and consumption records are written to Room first. After the user-visible cancellation countdown, the app sends an immutable snapshot of the offline queue to Apps Script. Only server-acknowledged or duplicate-safe items are removed from that queue.
- Product refreshes replace server-backed product data while preserving pending local purchases and merging newer product-interaction data.
- Insights and History use a versioned, environment-checked backend contract. Valid responses are cached in Room; History pagination also protects against stale cursors.
- `tests/backend_analytics_test.js` runs `backend_additions.gs` inside the fake Apps Script/Sheets runtime in `tests/fake_apps_script_runtime.js`.

## Change and release rules

- Propose feature and bug-fix work through a pull request targeting `main`.
- Keep each pull request to one coherent change. Put unrelated cleanup in another pull request.
- Do not change `versionCode` or `versionName`, create a tag or release, build a signed release, or modify signing configuration unless the task explicitly requests release work.
- Do not change the production Apps Script endpoint, application ID, package/namespace, environment IDs, credentials, or secrets unless the task explicitly requests that exact change.
- Never commit keystores, credentials, tokens, `sandbox.properties`, or other local secrets.

## Verified checks

The project requires JDK 17 or newer, Gradle 9.3.1 through the wrapper, Android SDK Platform 36.1, and Android Build Tools 36.0.0. On Windows, Android Studio's bundled JDK can be selected before running Gradle:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon testDebugUnitTest assembleDebug
node tests/backend_analytics_test.js
```

On macOS, Linux, or GitHub Actions, use `./gradlew` for the same Gradle tasks. The backend analytics test has no npm dependencies and uses Node.js built-ins plus the checked-in fake runtime.

Run the checks relevant to every change. Report every test, build, lint, device check, or manual check that was not run or did not pass; do not describe an unexecuted check as successful.

For a visible UI change, include screenshots or a short recording in the pull request. If screenshots cannot be produced, state why and describe the manual visual check that was performed instead.

## Data safety and regression coverage

- Treat Room migrations and the offline purchase/consumption queues as user data. Preserve existing rows and stable IDs unless an explicitly approved migration requires otherwise.
- Never use destructive database fallback as a shortcut. Add a forward migration and test upgrades from every supported prior schema when the schema changes.
- Preserve synchronization idempotency, request/action/event UUIDs, environment checks, acknowledgement handling, locking, and retry behavior. A timeout must not cause duplicate spreadsheet rows or silently discard a queued action.
- Delete a pending purchase or consumption only after the existing acknowledgement rules prove that the server committed it or already has the same immutable ID.
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
