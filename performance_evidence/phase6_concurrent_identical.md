# Cannsheet backend sync benchmark evidence

- Suite: `optimized`
- Generated (UTC): `2026-07-18T00:07:29+00:00`
- Endpoint (query removed): `https://script.google.com/macros/s/AKfycbxHPo_Zet7ctELj-rDl4iZSbDwAOxOWOHHsmVUVyteKzZbrjNDVI3GOKhJN_IX69AinuA/exec`
- Confirmed environment: `SANDBOX`
- Deterministic namespace: `5752a4e2-e54a-5f9d-9230-3354a437f52c`
- Timestamp base: `2030-01-15T12:00:00`
- Cold idle scheduled before each cold-labelled request: `0.0` seconds
- Overall correctness: **PASS** (2/2 records passed)

The suite label is not part of UUID generation. Reset and reseed the same
sandbox fixture before the baseline and optimized runs so both suites see
the same starting data and submit byte-equivalent deterministic payloads.

## Timing summary

All timing cells are `minimum / median / maximum / p95` in milliseconds.
A p95 is shown only when that metric has at least 20 samples.

| Scenario | Label | Operation | Samples | Passed | Server | GET | POST | Follow-up GET | Combined |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| concurrent_identical | warm | POST+GET | 2 | 2 | 8603.0 / 11252.5 / 13902.0 / - | - | 10662.171 / 13264.772 / 15867.373 / - | 2744.326 / 2831.062 / 2917.799 / - | 13579.97 / 16095.835 / 18611.699 / - |

## Individual results

Full payloads, responses, checks, and follow-up GET evidence are in the JSON file.

| # | Scenario | Label | HTTP | Request | Item IDs | Server ms | Wall ms | Follow-up GET ms | Combined ms | Check |
|---:|---|---|---:|---|---|---:|---:|---:|---:|---|
| 1 | concurrent_identical | warm | 200 | c3de9226 | 8e7f5758 | 13902.0 | 15867.373 | 2744.326 | 18611.699 | PASS |
| 2 | concurrent_identical | warm | 200 | c3de9226 | 8e7f5758 | 8603.0 | 10662.171 | 2917.799 | 13579.97 | PASS |

## Concurrent identical pairs

| Pair | Request | Event | Returned statuses | Exactly one commit |
|---:|---|---|---|---|
| 1 | c3de9226 | 8e7f5758 | duplicate, committed | PASS |

## Safety and interpretation

- The harness sent no POST until a read-only GET explicitly returned `environment: SANDBOX` and a products array.
- Every POST also declares `environment: SANDBOX`.
- Product names, prices, potency, and weights returned by GET are not copied into evidence; only the count and chosen identifiers are retained.
- `serverDurationMs` is reported only when the HTTP response exposes it. Client wall-clock time is always measured.
- The combined time is POST wall time plus the immediately following GET wall time.
- These requests mutate the sandbox. Restore the normal fixture after collecting evidence.
