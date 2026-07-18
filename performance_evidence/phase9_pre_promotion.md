# Phase 9 production pre-promotion snapshot

- Captured (UTC): `2026-07-18T01:41:19.8915261Z`
- Production state at capture: **unchanged**
- Spreadsheet: `CannsheetG`
- Script project: `Cannsheet Backend`
- Active deployment: version 7 on the existing deployment ID and `/exec` URL
- Existing source SHA-256:
  `6779BDF52E8801417A0496E87C7BE9A2C57A7FAADE459FFC6E0A9703478B1D2C`
- Existing source still exactly matches the Phase 1 intake copy.

## Fresh backups

- Drive copy:
  `CannsheetG Production Backup 2026-07-17 21-28-09 EDT - before backend sync performance promotion`
  (`1WdcBkNDqshwz5_Pi6HMMVxc3DfAZNDTnpQ-J2YTpTqw`)
- Local source/manifest and XLSX snapshot:
  `backups/2026-07-17/backend-sync-performance/production-before-promotion-21-28-09-EDT`
- Local XLSX SHA-256:
  `C8A22D592F8F37B764ACC9C53F18CE7390C9F0946E6F4AA8DE0A5EB442344ABA`

The copied spreadsheet was reopened through its metadata and contains the same
nine tabs, grid sizes, locale, and New York timezone as production.

## Deployment and runtime identity

- Version: `7`, `Environment handshake and sandbox isolation`
- Deployment ID:
  `AKfycbys-9r8PnkcTwUwbWL4hITr73n3nF240WQ1Vz6PW_V2XBwzusnMU3Br8tLaCgTiFz7hmQ`
- Execute as: deploying user
- Access: anyone
- Script Properties: `ENVIRONMENT=PRODUCTION`, exact production Spreadsheet ID
  and Form ID
- Triggers: exactly `onInventoryEdit` from spreadsheet edit and `onFormSubmit`
  from spreadsheet form submit, both on HEAD
- Form: published, 3,458 native responses, seven current product choices

The live manifest contains the required public web-app block. The candidate
manifest therefore merges Sheets v4 while preserving:

```json
"webapp": {
  "executeAs": "USER_DEPLOYING",
  "access": "ANYONE_ANONYMOUS"
}
```

## Fresh production totals

| Metric | T0 |
| --- | ---: |
| Purchases | 329 |
| Active / finished / unopened | 9 / 320 / 0 |
| Compatibility rows | 3,572 |
| Canonical events | 3,571 |
| Unique / duplicate Event UUIDs | 3,571 / 0 |
| Purchases total Uses | 5,396.51 |
| SyncLedger rows / unique request UUIDs | 38 / 38 |
| MigrationReport rows / unresolved | 0 / 0 |

Config contains only the six pre-promotion keys and no duplicate key. Purchases
R:Z contain no values or formulas, no data validation targets those columns, and
the sheet is not protected. Form Responses A:I remain Form-owned and J:K are
available. MigrationReport G:H are available for additive resolution fields.

A read-only GET returned HTTP 200, API version 2, `PRODUCTION`, and all 329
products in 4,689.65 ms client time. Its normalized product projection hash is
`792381F75770984FD9F935FF9AE70C4892ED9E3B94BF506440A82A05D9138122`.

## Reconfirmed historical repair target

The known drift has not changed:

- response row 3524 is an unidentified one-use `*J127` row with no canonical
  match;
- `*J127` is purchase row 325, currently finished with 3 Uses;
- response rows 3534 and 3535 are the two unidentified `*P93` rows;
- canonical rows 3531 and 3532 both currently point to response row 3535.

The intended repair remains one deterministic canonical event
`dfabd249-0d95-52a1-8b2b-70a5b60d93e4`, two safe relinks, `*J127` and total
Uses increasing exactly by 1, one completed maintenance journal, no ledger
change, and no unresolved row.
