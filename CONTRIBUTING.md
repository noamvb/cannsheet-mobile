# Contributing to Cannsheet Mobile

Cannsheet is maintained by one developer, but a pull request still provides a useful safety boundary: it keeps each task separate, shows the full diff before merge, and gives automated checks a place to run.

## The basic Git terms

- A **commit** is one saved checkpoint, such as "add a regression test for duplicate-safe sync."
- A **branch** is a temporary line of work that holds one task without changing `main`, such as `agent/fix-sync-message`.
- A **pull request (PR)** compares that branch with `main` so the change can be reviewed and discussed before it becomes part of the app.
- A **CI status check** is an automated GitHub test. For Cannsheet, the PR check runs Android unit tests, the backend analytics test, and a debug APK build.
- A **merge** accepts the reviewed pull request into `main`. Until the merge, the proposed files remain on the task branch.

## Workflow for each task

1. Start from an up-to-date `main`:

   ```powershell
   git switch main
   git pull --ff-only
   ```

2. Give Codex one narrowly scoped task with clear acceptance criteria and safety limits.
3. Work on a separate task branch or Codex worktree. A typical branch command is:

   ```powershell
   git switch -c agent/short-task-name
   ```

4. Open a **draft** pull request targeting `main` as soon as the focused change is ready for review.
5. Review the complete diff and Codex summary. Confirm that only intended files changed and that no secret, version, signing, ID, or production configuration changed accidentally.
6. Wait for the `Cannsheet Android PR validation` CI check to finish successfully.
7. Manually test changes that affect visible UI, Room data, synchronization, spreadsheet writes, deletion, purchases, consumption records, or offline behavior.
8. Request a targeted Codex review when a risky area would benefit from a second pass, for example: "Review only the Room migration and offline-queue safety."
9. Resolve review conversations, make fixes on the same branch, and push them to update the existing pull request.
10. Prefer **squash merge** for a focused pull request so `main` receives one clear commit.
11. Delete the temporary branch after the merge.
12. Start the next task from the newly updated `main`, not from the previous task branch.
13. Keep version bumps and releases in separate, explicitly requested release pull requests. A normal feature or bug-fix pull request must not create a release or tag.

## Local checks

The Android build uses the checked-in Gradle 9.3.1 wrapper, JDK 17 or newer, Android SDK Platform 36.1, and Android Build Tools 36.0.0. On Windows, Android Studio's bundled Java can be selected explicitly:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon testDebugUnitTest assembleDebug
node tests/backend_analytics_test.js
```

On macOS or Linux, use `./gradlew` instead of `./gradlew.bat`.

Report every command that did not run or did not pass. A visible UI change should include screenshots or a recording in the pull request; if that was not possible, explain why and describe the manual visual check.

## Pull request description

Use the repository template and include the summary, why the change is needed, important implementation decisions, exact automated test results, manual validation, risks/data-safety considerations, and screenshots when the UI changes.
