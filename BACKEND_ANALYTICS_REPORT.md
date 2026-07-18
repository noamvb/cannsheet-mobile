# Cannsheet Backend Analytics v1 — Implementation and Rollout Report

Date: 2026-07-18
Status: implemented, sandbox-validated, and promoted to production

## Outcome

The existing Apps Script backend now exposes two additive, read-only GET
resources:

- `resource=insights` for dashboard totals, time series, inventory, product,
  spending, sync-health, and data-quality views.
- `resource=history` for searchable, filterable, cursor-paginated consumption
  history.

The existing bare `GET /exec` and all POST/trigger behavior remain compatible.
Analytics reads do not repair, reconcile, append, update, or otherwise mutate
Google Sheets.

## Deployed state

### Production

- Apps Script project: `1C_I7_vWIuZoxQN3ZR3iAcNWq0-X3aJj4cS1EHbk2nW6yJT2dVfgy3vA2`
- Active deployment version: `9`
- Deployment ID: `AKfycbys-9r8PnkcTwUwbWL4hITr73n3nF240WQ1Vz6PW_V2XBwzusnMU3Br8tLaCgTiFz7hmQ`
- Public `/exec` URL: unchanged
- Immediate deployment rollback: version `8`
- Script properties verified: `ENVIRONMENT=PRODUCTION`
- Time zone verified: `America/New_York`
- Runtime verified: V8
- Triggers verified after promotion: exactly two HEAD triggers,
  `onInventoryEdit` and `onFormSubmit`
- Pre-promotion spreadsheet backup:
  `https://docs.google.com/spreadsheets/d/1tsnlLNuhCyYSGoMPLc_uso4ekmW6z3ID8scs7LkFWdo/edit`

### Sandbox

- Apps Script project: `14GdK-_WOr3lFwU9Xmx3OuvhzWKljPYKFH5L7MRCaC0dXsOOHG9LJQ-_o`
- Active deployment version: `12`
- Deployment ID: `AKfycbxHPo_Zet7ctELj-rDl4iZSbDwAOxOWOHHsmVUVyteKzZbrjNDVI3GOKhJN_IX69AinuA`
- Immediate deployment rollback: version `10`
- Production-sized validation fixture: 400 products, 3,600 form responses,
  and 3,600 canonical events
- Fixture was restored after validation to the normal six-product/five-event
  sandbox baseline with `resetSandboxData()`
- Pre-validation spreadsheet backup:
  `https://docs.google.com/spreadsheets/d/1rLRE05tpIxAZ0wPVre2cp6LwW9On9ejY16KI3acGpg4/edit`

The source deployed to sandbox and production had SHA-256:
`a25d1bb148ac3b3bd8fff77eecdd39f69067378300a679ab567c968faf48836b`.

## HTTP contract

Every analytics request must include:

```text
analyticsVersion=1
environment=PRODUCTION|SANDBOX
resource=insights|history
```

The server compares the requested environment with its script property before
reading the Purchases, ConsumptionEvents, or SyncLedger bodies.

### Insights

Default, trailing 180 days:

```text
GET /exec?resource=insights&analyticsVersion=1&environment=PRODUCTION
```

Custom inclusive local-date range:

```text
GET /exec?resource=insights&analyticsVersion=1&environment=PRODUCTION&from=2026-01-01&to=2026-07-18
```

All available data:

```text
GET /exec?resource=insights&analyticsVersion=1&environment=PRODUCTION&scope=all
```

Only `resource`, `analyticsVersion`, `environment`, `from`, `to`, and `scope`
are accepted. `from` and `to` must appear together, use `YYYY-MM-DD`, and span
no more than 3,660 inclusive days. `scope=all` cannot be combined with dates.

The successful response includes:

- `overview`
- zero-filled `dailyActivity`, `byWeekday`, and `byHour`
- `inventory`
- `byType`
- per-product analytics in `products`
- `spending` with all-time, selected-range, and monthly buckets
- server-acknowledged `syncHealth`
- `dataQuality`
- `sourceRevision`

Costs are returned as integer cents. Borrowed value is kept separate from
personal spending. Purchase dates expose whether the recorded date or a
Created At fallback was used. Potency and gram-quality issues are reported
instead of silently discarded.

### History

```text
GET /exec?resource=history&analyticsVersion=1&environment=PRODUCTION&limit=50
```

Optional filters:

- `from` and/or `to`, inclusive New York local dates
- exactly one of `productUuid` or legacy `productId`
- `type`
- case-insensitive text query `q`, maximum 80 characters
- `limit`, default 50 and maximum 200
- opaque `cursor`, maximum 1,024 characters

History is sorted newest-first with deterministic tie-breakers. The returned
cursor binds the filter set, source snapshot, and last item. Rows appended
after page 1 do not leak into later pages. Destructive changes to the captured
snapshot return `CURSOR_STALE`.

### Errors

Analytics failures still return JSON and `success: false`. Stable error codes
include:

- `INVALID_QUERY`
- `UNSUPPORTED_RESOURCE`
- `UNSUPPORTED_ANALYTICS_VERSION`
- `ENVIRONMENT_MISMATCH`
- `INVALID_CURSOR`
- `CURSOR_STALE`
- `BACKEND_BUSY`
- `DATA_INTEGRITY_ERROR`
- `RANGE_TOO_LARGE`
- `SCHEMA_MISMATCH`
- `CONFIGURATION_ERROR`
- `INTERNAL_ERROR`

Unknown or duplicate recognized query parameters are rejected.

## Read and consistency behavior

Each request:

1. validates the route, version, and environment;
2. obtains the script lock for at most five seconds;
3. validates the environment, schema, recoverable-apply version, pending-apply
   marker, time zone, and exact sheet headers;
4. performs one contiguous body read from Purchases and ConsumptionEvents;
5. additionally reads SyncLedger only for Insights;
6. normalizes and aggregates in memory;
7. returns a source revision/hash with the source row counts.

There are no analytics summary tabs, caches, repair calls, flushes, or writes.

## Validation evidence

### Automated tests

All of the following passed locally:

- `node tests/backend_contract_test.js`
- `node tests/backend_spreadsheet_test.js`
- `node tests/backend_recovery_test.js`
- `node tests/backend_analytics_test.js`
- `node tests/fake_sheets_batch_update_test.js`
- `node tests/sandbox_performance_fixture_test.js`
- `node tests/sandbox_provisioning_test.js`
- `python -m unittest tests.test_backend_sync_benchmark` — 13 tests
- Apps Script and JavaScript syntax checks

The analytics test covers bare-GET compatibility, exact no-mutation snapshots,
default/custom/all ranges, zero filling, money and borrowed separation,
inventory, source and data-quality fields, filters, deterministic sorting,
cursor pagination, append isolation, stale cursors, environment/schema/lock
guards, New York midnight and both DST transitions, backdated/future/equal
timestamps, hard identity errors, and the 400/3,600 scale fixture.

Local scale result after payload optimization:

- Insights: 290,285 bytes
- History maximum-page fixture: 68,116 bytes

### Deployed sandbox

- Fixture self-check: 400 purchases, 3,600 responses, 3,600 events, 400 unique
  products, 3,600 unique events, contiguous lineage, zero ledger rows, zero
  migration rows, and zero interaction-summary mismatches.
- Default Insights: 293,224 bytes, below the 300 KB gate.
- History `limit=50`: 17,901–17,902 bytes, below the 150 KB gate.
- All-time validation: 3,600 logs over 1,295 zero-filled days. Its payload is
  370,112 bytes; the 300 KB gate applies to the default app view.
- History pages 1 and 2 had 50 events each and no overlap.
- Unknown parameters, duplicate parameters, bad cursors, wrong versions, and
  wrong environments returned their stable errors.
- A read-only fixture assertion after all analytics traffic completed without
  error, proving the fixture remained unchanged.

Bare GET production-shape benchmark:

- Version 10 baseline, 20 warm samples: median 3,760.1 ms, p95 4,528.0 ms.
- Version 12 repeat, 20 warm samples: median 3,569.4 ms, p95 4,351.7 ms.
- Existing response size remained exactly 80,829 bytes.
- All 20 benchmark records passed.

Evidence files:

- `performance_evidence/analytics_v1_sandbox_bare_get.json`
- `performance_evidence/analytics_v1_sandbox_bare_get.md`
- `performance_evidence/analytics_v1_sandbox_bare_get_repeat.json`
- `performance_evidence/analytics_v1_sandbox_bare_get_repeat.md`

### Production smoke check

- Bare GET before and after: 69,614 bytes, API v2, 329 products.
- Default Insights: 243,186 bytes, 329 purchases, 3,580 events, 43 ledger rows.
- History `limit=50`: 17,702 bytes and 50 events.
- Wrong `environment=SANDBOX`: `ENVIRONMENT_MISMATCH` before body reads.
- Repeated Insights and History calls returned unchanged source hashes and row
  counts.
- Exactly two production HEAD triggers remained installed after promotion.

## Payload optimization

The first sandbox candidate produced a 332,648-byte default Insights response.
One optimization pass removed three redundant public per-product fields:
`costKnown`, `createdAtEpochMillis`, and `purchaseFinishedAtEpochMillis`.

No source information was lost:

- known cost is represented by non-null cost-cent fields;
- the effective purchase date and its source are returned;
- the latest finished-log timestamp and all-time first/last timestamps remain.

The optimized response passed the strict default payload gate without adding
state, caching, or new sheet tabs.
