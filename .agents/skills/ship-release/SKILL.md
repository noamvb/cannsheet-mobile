---
name: ship-release
description: Take a change from an empty working tree all the way to a signed APK the phone owner can install through Obtainium, including branch, validation, pull request, exact-SHA main proof, version bump, tag, publication, and independent verification.
---

# Ship a release

Use this skill when the task is to implement a change **and** get the resulting
APK onto the phone. Use `.agents/skills/project-handoff/SKILL.md` instead when
work is only being transferred, and follow `AGENTS.md` alone when the task is a
normal pull request with no release.

Read `AGENTS.md` before starting. This skill adds the release mechanics that
`AGENTS.md` deliberately leaves out; it does not replace any rule in it.

The release pipeline is defined by two workflows. Read them if anything below
appears to disagree with reality — the workflows are the authority:

- `.github/workflows/android-pr-checks.yml` — validation, workflow name
  **"Cannsheet PR checks"**
- `.github/workflows/release-apk.yml` — signing and publication, triggered
  **only** by pushing a tag matching `v*`

## The one thing that breaks releases

`release-apk.yml` refuses to publish unless the **exact tagged commit SHA** has
a **completed, successful, `push`-event run on `main`** of "Cannsheet PR checks"
in which all six of these jobs individually succeeded:

```
Classify changes and scan repository
Backend validation
Android static validation
Emulator API 24
Emulator API 36
Cannsheet Android PR validation
```

Two consequences that have already cost a release cycle:

1. **A green pull-request check is not proof.** Pull requests classify as
   `api_levels=[24]`; only pushes to `main` run API 36. The publish workflow
   requires the API 36 job by name, so it can only ever be satisfied by the
   push-to-main run.
2. **Back-to-back merges cancel each other.** Merging a second pull request
   while the first merge's main run is still going cancels the first run, and a
   `cancelled` run is not a `success`. In v1.3.2 the main runs for `0462e38` and
   `9118da2` were both cancelled; only the final commit `e610e81` ever went
   green, which is why the tag had to point there.

So: **land every commit first, then wait for the final `main` SHA to go fully
green, then tag that SHA.** Never tag a commit whose main run you have not
personally confirmed.

`release-apk.yml` additionally requires all of the following, or it fails:

- `versionName` in `app/build.gradle.kts` equals the tag with `v` stripped.
- The tagged commit is **exactly** the current tip of `origin/main`. Anything
  pushed to `main` after tagging invalidates the tag.
- `versionCode` is strictly greater than the `versionCode` of the APK in the
  latest release of `noamvb/cannsheet-mobile-releases` — it reads the previous
  APK, not the previous tag.
- The tag does not already exist in the releases repository. Published releases
  are never overwritten; a failed publish needs a new version, not a retag.

## Environment

This machine needs all three exported explicitly. Without them the toolchain
looks absent and agents wrongly fall back to CI-only validation.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="/Users/sophiaparis/Library/Application Support/com.raycast.macos/NodeJS/runtime/22.14.0/bin:/opt/homebrew/bin:$PATH"
```

`/usr/libexec/java_home -V` reports only JDK 1.8 and is misleading — the
Homebrew JDK 17 is not registered with it. There is no system `node`; it comes
from the Raycast runtime above. `adb` is at `/opt/homebrew/bin/adb`.

Never create or commit `local.properties`. The classify job fails the build if
it is tracked.

## Phase 1 — Branch and implement

Start from an up-to-date `main`, never from a previous task branch.

```bash
git switch main && git pull --ff-only && git switch -c agent/short-task-name
```

Keep one coherent change per pull request. Follow every convention in
`AGENTS.md`, especially the data-safety rules covering Room migrations, the
offline queue, synchronization idempotency, and analytics normalization. Add
regression tests at the boundary you changed.

**Tests must call the code under test.** A test that asserts against the fake
runtime, or that re-implements the logic in its own body and asserts on that,
proves nothing and is worse than no test because it reads as coverage. Before
adding a backend test, confirm it reaches `backend_additions.gs` — through
`doGet`/`doPost` via the `get`/`insights`/`history`/`post` helpers in
`tests/backend_analytics_test.js`, or by calling the function directly on
`runtime.context`. If the fake runtime cannot express the behavior you need to
test, extend the fake to model the real service faithfully; do not settle for a
test that passes because the fake is more forgiving than production.

## Phase 2 — Validate locally

Run everything relevant and record exact results. Report anything skipped.

```bash
./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug
```

```bash
node tests/backend_analytics_test.js && node tests/backend_contract_test.js && node tests/backend_corrections_test.js && node tests/backend_recovery_test.js && node tests/backend_spreadsheet_test.js && node tests/fake_sheets_batch_update_test.js && node tests/sandbox_performance_fixture_test.js && node tests/sandbox_provisioning_test.js
```

```bash
python3 -m unittest tests/test_backend_sync_benchmark.py
```

`tests/run_e2e_verification.sh` runs all three groups but hardcodes this
machine's Node path, so it only works here.

Never describe an unexecuted check as passing, and never quote a performance
number you did not measure. If a claim like "3-5x faster" cannot be backed by a
recorded measurement, do not write it into a commit message, an ADR, or
`docs/HANDOFF.md`.

## Phase 3 — Pull request

Review the complete diff before committing. Confirm it contains no secrets, no
personal machine paths, no accidental version or signing changes, and no
production endpoint, application ID, package, or environment changes.

```bash
git push -u origin agent/short-task-name
```

Open the pull request with the description sections `AGENTS.md` requires:
summary, motivation/root cause, implementation decisions, exact automated test
results, manual validation, risks and data-safety considerations, and
screenshots for visible UI changes or a reason they are absent.

Wait for the pull-request checks, then squash merge and delete the branch:

```bash
gh pr checks <number> --watch
gh pr merge <number> --squash --delete-branch
```

## Phase 4 — Version bump and documentation

Do these **before** tagging, because the tag must land on the tip of `main`.

Bump both fields in `app/build.gradle.kts` under `defaultConfig`:

```kotlin
versionCode = 34
versionName = "1.3.3"
```

Update the shared-context documents in the same or an adjacent pull request:

- `docs/PROJECT_STATE.md` — the verified current state.
- `docs/DECISIONS.md` — a new ADR only for a durable decision, numbered after
  the last one present.
- `docs/HANDOFF.md` — replace it; do not append a session diary.

`docs/HANDOFF.md` must record the release provenance, which v1.3.2 omitted:

- which pull requests merged, with their squashed commit SHAs;
- the exact `main` run ID that proved the tagged SHA, and that all six jobs
  passed;
- the statement that the annotated tag points at that exact validated commit;
- the published asset names and the APK SHA-256;
- confirmation that the signing certificate matches the previous release, so
  the phone can update in place.

Merge, then confirm `main` is what you expect:

```bash
git switch main && git pull --ff-only && git log --oneline -3
```

## Phase 5 — Prove the exact SHA is green

This is the gate. Do not skip it and do not infer it from the pull-request run.

```bash
git rev-parse HEAD
gh run list --branch main --limit 5 --json databaseId,headSha,event,conclusion
```

Find the run whose `headSha` equals `HEAD` and whose `event` is `push`. If it is
`cancelled`, `failure`, or absent, the tag will be rejected — re-run it and wait:

```bash
gh run rerun <run-id>
gh run watch <run-id> --exit-status
```

Confirm all six jobs individually succeeded:

```bash
gh run view <run-id> --json jobs --jq '.jobs[] | "\(.conclusion)\t\(.name)"'
```

Only when every line reads `success` may you tag.

## Phase 6 — Tag and publish

Publication is outward-facing and irreversible: it creates a public GitHub
release that cannot be overwritten. **Confirm with the repository owner before
pushing the tag**, unless they have already asked for this specific release.

```bash
git tag -a v1.3.3 -m "Cannsheet Mobile 1.3.3"
git push origin v1.3.3
```

```bash
gh run list --workflow=release-apk.yml --limit 1
gh run watch <run-id> --exit-status
```

The workflow confirms the main validation, verifies tag/version/tip agreement
and versionCode monotonicity, runs tests and lint, builds and signs the APK,
verifies signature and badging, publishes to `noamvb/cannsheet-mobile-releases`,
then re-downloads and re-verifies the published assets.

If it fails **before** the publish job, fix the cause, land the fix, wait for a
new green main run, delete the tag locally and remotely, and retag. If it fails
**after** publishing, do not retag — bump to the next version instead.

## Phase 7 — Verify the publication independently

Do not rely solely on the workflow's own self-check.

```bash
gh release view v1.3.3 --repo noamvb/cannsheet-mobile-releases --json tagName,publishedAt,assets
```

Confirm exactly two assets named `Cannsheet-Mobile-1.3.3.apk` and
`Cannsheet-Mobile-1.3.3.apk.sha256`, then download and inspect:

```bash
gh release download v1.3.3 --repo noamvb/cannsheet-mobile-releases --dir /tmp/verify-1.3.3
cd /tmp/verify-1.3.3 && shasum -a 256 -c Cannsheet-Mobile-1.3.3.apk.sha256
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging Cannsheet-Mobile-1.3.3.apk | head -3
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs Cannsheet-Mobile-1.3.3.apk
```

Expect package `com.noamv.cannsheet.mobile`, the intended versionCode and
versionName, minSdk 24, targetSdk 36, and APK Signature Scheme v2. Compare the
certificate digest against the previous release — **a changed signing
certificate makes in-place update impossible and would force an uninstall,
destroying the Room database and any pending offline queue rows.**

Record the SHA-256 and the certificate comparison in `docs/HANDOFF.md`.

## Phase 8 — Hand off to the phone

The phone owner updates through **Obtainium**, which tracks the release
repository `noamvb/cannsheet-mobile-releases`. Tell them plainly:

> v1.3.3 is published. Open Obtainium, pull to refresh or tap **Check for
> updates**, and install Cannsheet Mobile 1.3.3.

Never install anything on the phone yourself, and never suggest sideloading a
locally built APK. Debug builds use `applicationId com.noamv.cannsheet.mobile`
with no suffix and debug signing, so they cannot install over the release build
without an uninstall — which deletes the Room database including pending
offline queue rows. Local device work needs a temporary build type with an
`applicationIdSuffix`, reverted before the pull request.

## Hard prohibitions

- Do not change `versionCode`, `versionName`, signing configuration, tags, or
  releases unless the task explicitly asks for release work.
- Do not change the production Apps Script endpoint, application ID,
  package/namespace, environment IDs, credentials, or secrets.
- Do not commit keystores, credentials, tokens, `sandbox.properties`,
  `local.properties`, or any personal machine path into repository files.
- Do not touch the production spreadsheet or Apps Script deployment. The
  checked-in `backend_additions.gs` is source; deploying it is a separate
  owner-performed step, so a backend change is not live merely because it
  merged.
- Do not overwrite or delete a published release.
- Do not report a release as delivered until Phase 7 verification has actually
  run and passed.
