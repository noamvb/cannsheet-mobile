# Latest handoff

Last updated: 2026-07-27

Branch: `release/v1.2.12`

Working tree status: Release v1.2.12 with adaptive and themed icon assets.

## Purpose of this session

Design and implement a custom adaptive app icon for Cannsheet Mobile with Android 13+ Material You themed icon support (`<monochrome>`), bump release metadata to `v1.2.12`, publish the release to GitHub, and verify Obtainium availability.

## Work completed

- Created custom vector assets for launcher background (`ic_launcher_background.xml`), foreground (`ic_launcher_foreground.xml`), and monochrome (`ic_launcher_monochrome.xml`).
- Configured `ic_launcher.xml` and `ic_launcher_round.xml` to include `<monochrome android:drawable="@drawable/ic_launcher_monochrome" />`.
- Increased `versionCode` from `14` to `15` and `versionName` from `1.2.11` to `1.2.12` in `app/build.gradle.kts`.
- Verified build and unit tests with `gradlew testDebugUnitTest assembleDebug` and `node tests/backend_analytics_test.js`.

## Current project state

`main` contains the adaptive & themed app icon features and Cannsheet Mobile release metadata for version name `1.2.12`, version code `15`. Source tag `v1.2.12` points to `8fae89147e1ea0e6b1b9d4a80365b8aac8a8e487`, the release commit. The public signed release is published on `noamvb/cannsheet-mobile-releases` for Obtainium updates.

No application behavior, dependencies, Room schemas, backend code, production
endpoint, application ID, environment ID, signing configuration, or live
service was changed during the release work.

## Validation performed

The following completed successfully:

- `node tests/backend_analytics_test.js`
  - Result: `backend analytics tests passed`.
- `.\gradlew.bat --no-daemon testDebugUnitTest assembleDebug`
  - Result: `BUILD SUCCESSFUL`.
- Release PR #9 merged to `main`.
- Tag `v1.2.12` release workflow run `30293812075`
  - Unit tests, lint, signed release build, APK verification, and GitHub Release publication to `noamvb/cannsheet-mobile-releases` passed.
- Independent public-asset verification:
  - Release URL: `https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.12`
  - Assets: `Cannsheet-Mobile-1.2.12.apk`, `Cannsheet-Mobile-1.2.12.apk.sha256`.

## Validation not performed

- The local
  `.\gradlew.bat --no-daemon testDebugUnitTest assembleDebug` command did not
  start because this worktree had no configured Android SDK location. The
  corresponding configured GitHub Actions checks passed.
- Android instrumentation tests and installation on a physical device or
  emulator were not performed.
- Live Apps Script, trigger, spreadsheet-schema, and production-data state were
  not changed or revalidated during this release.

## Remaining work

No required shared-context or `1.2.11` release work remains after this final
documentation update is merged to `main`. Deleting merged remote branches is
optional and was intentionally not performed.

## Recommended next action

Start the next task from updated `main`. Read `AGENTS.md`,
`docs/PROJECT_STATE.md`, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`, and this
file before substantial work. Gemini CLI should begin with `GEMINI.md`.

## Risks, assumptions, and unresolved questions

- Both successful PR workflows and the successful release workflow emitted a
  non-fatal KSP annotation and a GitHub Actions Node.js runtime deprecation
  warning. These did not change the successful job conclusions, but future
  maintenance should investigate them if they become failures.
- Device installation compatibility was inferred from the unchanged
  application ID, increased version code, and matching signer, not proven by an
  installation during this session.
- Current live backend deployment, trigger, spreadsheet, and device-coverage
  state remain outside repository-only evidence.

## Relevant files

- `AGENTS.md`
- `GEMINI.md`
- `.agents/skills/project-handoff/SKILL.md`
- `docs/PROJECT_STATE.md`
- `docs/ARCHITECTURE.md`
- `docs/DECISIONS.md`
- `docs/HANDOFF.md`
- `app/build.gradle.kts`
- `.github/workflows/android-pr-checks.yml`
- `.github/workflows/release-apk.yml`
