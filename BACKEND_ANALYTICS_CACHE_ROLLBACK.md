# Analytics response cache - deployment record and rollback

Status: production Apps Script **version 16** is live and verified. **Version 15
is the immediate rollback target** on the same unchanged deployment.

Written 2026-09-02. For the maintained current state read `docs/PROJECT_STATE.md`
and `docs/HANDOFF.md`; this file records one deployment, its measurements, and
how to undo it.

This supersedes nothing. `BACKEND_SYNC_PERFORMANCE_ROLLBACK.md` is a separate,
historical record of the July 2026 sync-performance work and its version 7/8
rollback targets, which are long superseded.

## What was deployed

Apps Script project `1C_I7_vWIuZoxQN3ZR3iAcNWq0-X3aJj4cS1EHbk2nW6yJT2dVfgy3vA2`,
deployment `AKfycbys-9r8PnkcTwUwbWL4hITr73n3nF240WQ1Vz6PW_V2XBwzusnMU3Br8tLaCgTiFz7hmQ`
- unchanged, so the client endpoint is unchanged.

Version 16, described `Analytics response cache with pre-write invalidation (main bda15e6)`,
published 2026-09-02 22:33 EDT. Its source is `backend_additions.gs` at `main`
commit `bda15e6`, SHA-256
`795126ac202e57153dec75392d9e1466a899bdc224e95cb01fa6a92490b56a16`, 323,673
bytes. That hash was verified in the editor after saving and again on a fresh
page load before deploying.

It carries three things that were not in production before:

- The analytics response cache from ADR-021 (`0462e38`, #83) - chunked
  `CacheService` keyed on `MUTATION_WATERMARK`, `Sheets.Spreadsheets.Values.batchGet`,
  and a `ScriptLock` scoped to the atomic data fetch.
- Its corrections from ADR-022 (`b3c869b`, #87) - durable watermark persistence,
  batch/sequential `dataVersion` parity, and the padded-row fix.
- The pre-write invalidation added by ADR-053 (#178).

## Before/after, measured

Baseline, production version 15, from the app's own on-screen readout on the
owner's SM-F966W, reading 4,132 events and 373 purchases:

| Sample | Insights server duration |
|---|---|
| 1 | 13,413 ms |
| 2 | 13,413 ms |
| 3 | 10,853 ms |

After, production version 16, four consecutive identical GETs to the live
endpoint:

| Request | Server duration | `generatedAtEpochMillis` |
|---|---|---|
| 1 (cold) | 15,429 ms | 1788402831014 |
| 2 | 120 ms | 1788402831014 |
| 3 | 118 ms | 1788402831014 |
| 4 | 100 ms | 1788402831014 |

Wall clock for the full request fell from 17.6 s to 2.4 s. History behaved the
same way: 16,577 ms cold, 122 ms on the next read.

The identical `generatedAtEpochMillis` across all four is deliberate evidence,
not an artifact: a cache hit overwrites only `serverDurationMs` and preserves the
payload's original generation time, which is what the client's new
`Updated <time>` line displays.

A cold read is not faster than before. The cache is the whole of the win.

## Correctness check

Compared against the pre-deploy fingerprint captured from the phone:

| Field | Before | After |
|---|---|---|
| `purchaseRowCount` | 373 | 373 |
| Unknown personal costs | 3 | 3 |
| Unknown borrowed costs | 16 | 16 |
| Invalid THC values | 10 | 10 |
| Invalid gram values | 14 | 14 |
| Local date mismatches | 3572 | 3572 |
| `eventRowCount` | 4132 | 4133 |
| Local time mismatches | 4132 | 4133 |

The only two figures that moved are the two that track `eventRowCount`, and they
moved together by exactly one - a real log recorded between the two
measurements, not a caching defect.

## Rollback

Rolling back is a version change on the same deployment; nothing else moves and
no data is touched.

1. Apps Script editor > **Deploy** > **Manage deployments**.
2. Select the active deployment `AKfycbys-9r8Pnk...`.
3. Click the pencil (**Edit**).
4. Set **Version** to **Version 15 on Sep 2, 2026, 6:35 PM**.
5. Click **Deploy**.

Confirm the dialog then reads `Version 15`, and confirm the endpoint is serving
uncached responses again: two consecutive identical Insights GETs should return
*different* `generatedAtEpochMillis` values and server durations in the ten-second
range.

Version 15 is `0392591` plus the v1.10.0 tax-basis fields, SHA-256
`ae10a86f3df7d289017aab727f07f67638c3fe61dd1f3f17841a172ba4460e49`, 303,698
bytes. It has no response cache, so rolling back restores the 10-13 second
Insights reads and nothing else. The v1.10.0 tax-basis behaviour survives it.

If an incident ever requires stepping back further, the targets differ and the
difference matters:

- **Version 14** also returns `taxRate` and per-product `postTax`, so the
  purchase form and its preview keep working. It differs from 15 in one respect:
  it parses the sheet with `truthy_`, which never returns null, so a blank
  `Post-tax` cell is reported as `false` rather than omitted. An unrecorded basis
  would therefore be presented as pre-tax, and the "Tax basis wasn't recorded"
  warning from ADR-052 could never fire. When version 15 was published the live
  sheet had no blanks in that column (106 true, 267 false), so this was latent
  rather than active. Version 14 is a viable emergency target with that caveat
  understood.
- **Version 13 and earlier** do not carry `taxRate` or per-product `postTax` at
  all. Rolling back that far removes the fields the purchase form's preview
  depends on. Do not go past 14 to resolve a caching problem.

## Spreadsheet backup

A full copy of the production spreadsheet was taken immediately before this
deployment and verified at 882,829 bytes against the original's 882,022:

`CannsheetG Production Backup 2026-09-02 21-40 EDT - before analytics caching deploy`
(Drive id `1lgoPL5c8WUQIPcsuDf3gAdH9iaOEvNi8_FZudHxNht0`).

Nothing in this deployment writes to the spreadsheet differently from version
15, so the backup is precautionary rather than expected to be needed.

## Known accepted behaviour

A cache hit returns before `readAnalyticsSnapshot_` and therefore skips the
environment, schema-version, timezone and `PENDING_APPLY` guards that a cache
miss enforces. This was accepted deliberately in ADR-022 and re-affirmed in
ADR-053: re-checking them needs a Sheets read on every request, which is the
cost the cache exists to avoid. The environment is part of the cache key, so
this is not cross-environment exposure.

The practical effect is that during a pending recoverable sync apply, an Insights
request can return `success` from cache where a cache miss would return
`BACKEND_BUSY`, until the entry expires or any write bumps the watermark.
