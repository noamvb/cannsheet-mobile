# Phase 9 production promotion evidence

Status: complete. Production version 8 is live, all zero-mutation checks pass,
and the first genuine user sync has been verified end to end.

## Promotion

- Existing deployment ID and `/exec` URL: unchanged.
- Deployment: version 7 to version 8, `Backend sync performance and recoverable
  atomic apply`.
- Final source/manifest match the local promotion candidate after newline
  normalization.
- Fresh Drive backup:
  `CannsheetG Production Backup 2026-07-17 21-56-01 EDT - before backend sync performance promotion`
  (`15OT5fu0qzVuA11srEJ7o-Siqq2xLwTrTXLTtmZMf45M`).

## Guarded migration

- The interaction summary prepared from 3,572 valid canonical events with zero
  legacy-comparison or reconciliation differences.
- Recoverable-write preparation backfilled 3,570 safe compatibility identities
  and stopped only on the two exact pre-known lineage errors at row 3535.
- The explicit repair relinked rows 3534 and 3535, canonicalized row 3524 once,
  and left no unresolved row.
- Its same-execution product check saw one stale SpreadsheetApp cache value
  immediately after the Advanced Sheets batch. A fresh, separate all-product
  check scanned all 329 products and returned no difference. Fresh summary and
  recovery checks were also clean, so rollback was not warranted.
- Final preparation returned zero blockers. Both optimization markers are 1 and
  the pending pointer is blank.

## Exact migration delta

| Metric | Before | After |
| --- | ---: | ---: |
| Purchases | 329 | 329 |
| Active / finished / unopened | 9 / 320 / 0 | 9 / 320 / 0 |
| Compatibility rows | 3,573 | 3,573 |
| Canonical events / unique UUIDs | 3,572 / 3,572 | 3,573 / 3,573 |
| Purchases total Uses | 5,398.51 | 5,399.51 |
| `*J127` Uses | 3 | 4 |
| SyncLedger rows | 38 | 38 |
| SyncApplyJournal complete / incomplete | 0 / 0 | 1 / 0 |
| MigrationReport rows | 0 | 0 |

The new deterministic repair event is
`dfabd249-0d95-52a1-8b2b-70a5b60d93e4`. Every compatibility row now has an
identity, lineage rows 3534/3535 point to their correct existing events, there
are no duplicate event or ledger UUIDs, and every fresh reconciliation is
clean.

## Zero-mutation endpoint proof

GET returned HTTP 200, API version 2, `PRODUCTION`, and 329 products. Malformed,
unsupported-version, and wrong-environment requests returned `INVALID_JSON`,
`UNSUPPORTED_API_VERSION`, and `ENVIRONMENT_MISMATCH`. No accepted empty request
was sent.

All content hashes for all ten sheets were identical before and after these
four requests. The deployment remains public as the deploying user, Script
Properties are unchanged, and the two HEAD triggers remain
`onInventoryEdit`/spreadsheet edit and `onFormSubmit`/spreadsheet form submit.

The Form remains published with 3,458 native responses. Its Product choices
were refreshed from seven stale choices to the exact nine active products; the
two additions, `*S69` and `*P94`, were already active in Purchases.

## First genuine app sync

- Request UUID: `8ca578a3-e553-4a6e-9161-93cf07946fff`
- Event UUID: `c9fd36d1-93b2-4594-bea4-a4f6673284f3`
- Recoverable apply UUID: `eac357b0-4d66-4ce5-8404-eb34d4121210`
- Product: `*P94`
- Quantity: 2 uses
- Acknowledgement: success, all accepted, consumption status `committed`
- SyncLedger duration: 6,355 ms
- Apps Script execution: version 8 `doPost`, completed in 8.676 seconds
- Follow-up execution: version 8 `doGet`, completed in 2.351 seconds

An exact pre/post workbook comparison found changes only in Purchases, Form
Responses 1, ConsumptionEvents, SyncLedger, and SyncApplyJournal. The sync
created exactly one compatibility row, one canonical event with correct lineage,
one unique ledger row, and one complete journal row. Product `*P94` moved from
53 to 55 Uses. Config and every unrelated sheet were unchanged.

Afterward there were 3,574 compatibility rows, 3,574 unique canonical events,
39 unique ledger requests, two complete journal rows, no incomplete journal,
and a blank pending pointer. Fresh product, summary, and recoverable-apply
reconciliations all returned no difference or blocker.

The post-sync workbook is preserved at
`C:\Users\noamv\Downloads\cannsheet-mobile-main\backups\2026-07-17\backend-sync-performance\production-after-promotion-version-8\CannsheetG-after-first-real-sync.xlsx`
with SHA-256
`82189CCB0607E1E04498A758F9FD99766A96AC3BEB2E4B9A71B517A438B5215E`.

The final live check still shows exactly the two expected HEAD triggers. No
synthetic personal production data was manufactured for this rollout.
