# Phase 8 sandbox rollback and restoration proof

- Generated (UTC): `2026-07-18T01:18:48.0468819Z`
- Environment: `SANDBOX`
- Optimized version: `9`
- Tested rollback version: `5`
- Deployment ID and `/exec` URL: unchanged throughout
- Final source SHA-256:
  `4E74C83D69FE7EBA89B39F343750BAD5610022F10B46DB43C4688F0C93361C01`
- Overall result: **PASS**

## Rollback exercise

The recoverable-write marker was first disabled and its pending pointer was
confirmed blank. The existing sandbox deployment was then edited in place from
version 9 to version 5. No deployment or endpoint was created.

A read-only GET against the rollback version returned HTTP 200, API version 2,
`environment: SANDBOX`, and all 410 fixture products. It took 1,578 ms of
reported server work and 3,289.302 ms wall-clock.

Exact connector reads before and after the GET were unchanged:

| Item | Before | After |
| --- | ---: | ---: |
| Purchases | 410 | 410 |
| Form-compatible responses | 3,639 | 3,639 |
| Canonical events | 3,639 | 3,639 |
| SyncLedger rows | 49 | 49 |
| SyncApplyJournal rows | 44 | 44 |
| Interaction-summary marker | 1 | 1 |
| Recoverable-write marker | 0 | 0 |
| Pending apply pointer | blank | blank |

The same deployment was restored in place to version 9, the recoverable-write
marker was re-enabled, and reconciliation reported 3,639 canonical rows, 44
completed journals, zero incomplete journals, no differences, and no blockers.

## Final normal-baseline restoration

The deterministic reset restored exactly:

- 6 purchases: 3 active, 2 finished, and 1 unopened;
- 5 Form-compatible rows and 5 unique canonical events;
- total Uses of 5.25;
- zero SyncLedger, SyncApplyJournal, and MigrationReport data rows;
- interaction-summary and recoverable-write markers both at version 1; and
- a blank pending apply pointer.

A final reconciliation reported 5 canonical rows, zero journal rows, zero
incomplete journals, zero interaction-summary differences, no differences, and
no blockers. The linked Google Form is unpublished and displays 0 responses.

The rollback path is therefore executable, the optimized deployment is restored,
and the sandbox is clean for handoff.
