# Cannsheet backend sync performance rollback

Status: production version 8 is live and verified. Version 7 is the immediate
production code rollback target on the same deployment and endpoint.

## Immediate rollback targets

### Production

- Apps Script project:
  `1C_I7_vWIuZoxQN3ZR3iAcNWq0-X3aJj4cS1EHbk2nW6yJT2dVfgy3vA2`
- Existing deployment ID:
  `AKfycbys-9r8PnkcTwUwbWL4hITr73n3nF240WQ1Vz6PW_V2XBwzusnMU3Br8tLaCgTiFz7hmQ`
- Current optimized version: `8`,
  `Backend sync performance and recoverable atomic apply`
- Immediate rollback version: `7`
- The `/exec` URL and deployment ID must never be replaced during rollback.
- Current production endpoint:
  `https://script.google.com/macros/s/AKfycbys-9r8PnkcTwUwbWL4hITr73n3nF240WQ1Vz6PW_V2XBwzusnMU3Br8tLaCgTiFz7hmQ/exec`

### Sandbox

- Apps Script project:
  `14GdK-_WOr3lFwU9Xmx3OuvhzWKljPYKFH5L7MRCaC0dXsOOHG9LJQ-_o`
- Existing deployment ID:
  `AKfycbxHPo_Zet7ctELj-rDl4iZSbDwAOxOWOHHsmVUVyteKzZbrjNDVI3GOKhJN_IX69AinuA`
- Optimized version: `10`, `Phase 9 final promotion candidate smoke`
- Performance-tested hot-path version: `9`, `Phase 7 no-op ledger fast path`
- Tested pre-recovery rollback version: `5`,
  `Phase 4 compact GET projection`

## Backups

- Fresh production spreadsheet copy taken immediately before promotion:
  `CannsheetG Production Backup 2026-07-17 21-56-01 EDT - before backend sync performance promotion`
  (`15OT5fu0qzVuA11srEJ7o-Siqq2xLwTrTXLTtmZMf45M`)
- Fresh local production source, manifest, and spreadsheet:
  `C:\Users\noamv\Downloads\cannsheet-mobile-main\backups\2026-07-17\backend-sync-performance\production-before-promotion-21-56-01-EDT`
- Backed-up production source SHA-256:
  `6779BDF52E8801417A0496E87C7BE9A2C57A7FAADE459FFC6E0A9703478B1D2C`
- Backed-up production manifest SHA-256:
  `34A83AAE72323956C2EB9E69514297084EF2A5791BED7649C0D127226D5FCCCE`
- Backed-up production workbook SHA-256:
  `84ECAF9E8EE24410176AC440AA40073B1144078882AB9C9787DC42743FA96DD7`
- Post-promotion, before-real-sync snapshot:
  `C:\Users\noamv\Downloads\cannsheet-mobile-main\backups\2026-07-17\backend-sync-performance\production-after-promotion-version-8\CannsheetG-after-promotion-before-real-sync.xlsx`
- Post-first-real-sync snapshot:
  `C:\Users\noamv\Downloads\cannsheet-mobile-main\backups\2026-07-17\backend-sync-performance\production-after-promotion-version-8\CannsheetG-after-first-real-sync.xlsx`
  (SHA-256
  `82189CCB0607E1E04498A758F9FD99766A96AC3BEB2E4B9A71B517A438B5215E`)
- Local sandbox source:
  `C:\Users\noamv\Downloads\cannsheet-mobile-main\backups\2026-07-14\backend-sync-performance\sandbox\before-baseline`

## Roll back immediately when

- GET or an existing client breaks;
- environment validation changes or fails open;
- the deployment ID or `/exec` URL changes;
- a duplicate purchase or event appears;
- Uses increments twice or does not increment;
- latest-interaction, finished state, compatibility history, or canonical
  history drifts;
- reconciliation reports an unexpected difference;
- Form choices, Form submission, or either trigger breaks;
- warm latency materially regresses; or
- a rejection-only production check writes any row.

## Code and fast-path rollback procedure

1. Pause app syncs, Form submissions, and inventory edits. Record the failing
   request UUID, event UUIDs, time, response, and observed rows.
2. While the optimized HEAD source is still loaded, run
   `reconcileRecoverableSyncApply()`. If it reports a pending apply or incomplete
   journal, run `repairRecoverableSyncApply()` and then reconcile again. Do not
   continue until `PENDING_APPLY_KEY` is blank and incomplete journals are zero.
   Save the reconciliation output. Never clear the pending pointer manually.
3. If the write path is implicated, run `disableRecoverableSyncApply()` after
   recovery is clean. If GET summaries are implicated, run
   `disableInteractionSummaryFastPath()`. These switches preserve the additive
   data while selecting the compatible path.
4. Edit the existing production deployment and select version `7`. Do not create
   a deployment, change the deployment ID, change the `/exec` URL, or change
   access settings.
5. Remember that both installed triggers use `HEAD`, not deployment version 7.
   If the defect can affect a Form submission or inventory edit, restore the
   backed-up `Code.gs` and `appsscript.json` to HEAD as well. For a general
   code-wide rollback, restoring both backed-up files is the safest choice.
6. Script Properties and trigger definitions were not changed by this rollout.
   Restore them only if inspection proves that they drifted.
7. Save a new Apps Script version for the restored HEAD source and record every
   action taken. Resume traffic only after the verification below passes.

## Verification after rollback

1. Confirm the deployment ID, `/exec` URL, deploying-user identity, and public
   access are unchanged.
2. Run a read-only GET and verify HTTP 200, API version 2, `PRODUCTION`, and the
   expected product response shape.
3. Send only malformed, unsupported-version, and wrong-environment requests.
   Confirm they are rejected and exact before/after sheet hashes are identical.
4. Inspect Script Properties. They must still contain the production
   environment, Sheet ID, and Form ID.
5. Confirm exactly two HEAD triggers:
   `onInventoryEdit` / From spreadsheet - On edit and
   `onFormSubmit` / From spreadsheet - On form submit.
6. Compare the current state with the clean checkpoint recorded at
   2026-07-17 22:20 EDT:

   - 329 Purchases, with 9 active / 320 finished / 0 unopened;
   - 3,574 compatibility rows;
   - 3,574 unique canonical events and no duplicate Event UUID;
   - total Uses 5,401.51;
   - 39 unique SyncLedger request UUIDs;
   - two complete SyncApplyJournal rows and no incomplete row;
   - blank pending apply;
   - zero MigrationReport rows; and
   - Form published with 3,458 native responses and nine active-product choices.

   These are dated checkpoint values, not permanent totals. Account for every
   legitimate sync, Form submission, or inventory change after that time and
   compare row-by-row rather than treating later valid activity as corruption.

7. Observe the next real user sync. Verify one acknowledgement, one canonical
   event, correct compatibility lineage, one ledger request UUID, a complete
   journal, exactly one product update, and no unrelated change before declaring
   recovery complete.

## Data-restore rule

Do not delete `SyncApplyJournal`, the additive `Last quantity` projection, the
deterministic repair event, or other migration state merely to roll back code.
Do not restore the whole spreadsheet because a deployment was reverted. Restore
spreadsheet data only after a row-level comparison proves corruption, and then
restore only the verified affected rows when practical. Preserve valid events
accepted before the rollback.

Every rollback must record its cause, affected requests, data impact, deployed
version, Config flag changes, verification results, and any row-level recovery.
