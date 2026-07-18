# Cannsheet backend sync benchmark evidence

- Suite: `optimized`
- Generated (UTC): `2026-07-18T00:47:15+00:00`
- Endpoint (query removed): `https://script.google.com/macros/s/AKfycbxHPo_Zet7ctELj-rDl4iZSbDwAOxOWOHHsmVUVyteKzZbrjNDVI3GOKhJN_IX69AinuA/exec`
- Confirmed environment: `SANDBOX`
- Deterministic namespace: `8752a4e2-e54a-5f9d-9230-3354a437f52c`
- Timestamp base: `2031-01-15T12:00:00`
- Cold idle scheduled before each cold-labelled request: `0.0` seconds
- Overall correctness: **PASS** (14/14 records passed)

The suite label is not part of UUID generation. Reset and reseed the same
sandbox fixture before the baseline and optimized runs so both suites see
the same starting data and submit byte-equivalent deterministic payloads.

## Timing summary

All timing cells are `minimum / median / maximum / p95` in milliseconds.
A p95 is shown only when that metric has at least 20 samples.

| Scenario | Label | Operation | Samples | Passed | Server | GET | POST | Follow-up GET | Combined |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| concurrent_identical | warm | POST+GET | 2 | 2 | 5384.0 / 6062.0 / 6740.0 / - | - | 7606.935 / 8108.434 / 8609.932 / - | 2847.067 / 3035.156 / 3223.245 / - | 10454.003 / 11143.59 / 11833.177 / - |
| duplicate_retry | warm | POST+GET | 5 | 5 | 3101.0 / 3306.0 / 5517.0 / - | - | 4514.386 / 5274.069 / 8565.857 / - | 2168.126 / 2931.692 / 3001.893 / - | 6682.512 / 7785.64 / 11567.75 / - |
| duplicate_seed | warm | POST+GET | 1 | 1 | 4400.0 / 4400.0 / 4400.0 / - | - | 6048.145 / 6048.145 / 6048.145 / - | 2623.155 / 2623.155 / 2623.155 / - | 8671.3 / 8671.3 / 8671.3 / - |
| empty_v2 | warm | POST+GET | 5 | 5 | 2112.0 / 2534.0 / 4017.0 / - | - | 3968.907 / 4253.178 / 5658.638 / - | 2587.518 / 3340.994 / 3921.114 / - | 6671.102 / 8110.46 / 8999.632 / - |
| one_consumption | warm | POST+GET | 1 | 1 | 5737.0 / 5737.0 / 5737.0 / - | - | 7617.804 / 7617.804 / 7617.804 / - | 3754.767 / 3754.767 / 3754.767 / - | 11372.571 / 11372.571 / 11372.571 / - |

## Individual results

Full payloads, responses, checks, and follow-up GET evidence are in the JSON file.

| # | Scenario | Label | HTTP | Request | Item IDs | Server ms | Wall ms | Follow-up GET ms | Combined ms | Check |
|---:|---|---|---:|---|---|---:|---:|---:|---:|---|
| 1 | empty_v2 | warm | 200 | d6ba0a83 | - | 2112.0 | 3968.907 | 2702.194 | 6671.102 | PASS |
| 2 | empty_v2 | warm | 200 | d6d03ac2 | - | 2308.0 | 4253.178 | 2587.518 | 6840.697 | PASS |
| 3 | empty_v2 | warm | 200 | f6d9fcab | - | 2904.0 | 4679.939 | 3733.474 | 8413.413 | PASS |
| 4 | empty_v2 | warm | 200 | 35d8b551 | - | 2534.0 | 4189.346 | 3921.114 | 8110.46 | PASS |
| 5 | empty_v2 | warm | 200 | c3ed9944 | - | 4017.0 | 5658.638 | 3340.994 | 8999.632 | PASS |
| 6 | one_consumption | warm | 200 | 1a19ed4d | cf243dbe | 5737.0 | 7617.804 | 3754.767 | 11372.571 | PASS |
| 7 | duplicate_seed | warm | 200 | 80673eaf | d4e29496 | 4400.0 | 6048.145 | 2623.155 | 8671.3 | PASS |
| 8 | duplicate_retry | warm | 200 | 80673eaf | d4e29496 | 3203.0 | 4683.717 | 2931.692 | 7615.41 | PASS |
| 9 | duplicate_retry | warm | 200 | 80673eaf | d4e29496 | 3679.0 | 5402.398 | 2383.242 | 7785.64 | PASS |
| 10 | duplicate_retry | warm | 200 | 80673eaf | d4e29496 | 5517.0 | 8565.857 | 3001.893 | 11567.75 | PASS |
| 11 | duplicate_retry | warm | 200 | 80673eaf | d4e29496 | 3306.0 | 5274.069 | 2935.204 | 8209.273 | PASS |
| 12 | duplicate_retry | warm | 200 | 80673eaf | d4e29496 | 3101.0 | 4514.386 | 2168.126 | 6682.512 | PASS |
| 13 | concurrent_identical | warm | 200 | 95329d52 | 7621e293 | 5384.0 | 7606.935 | 2847.067 | 10454.003 | PASS |
| 14 | concurrent_identical | warm | 200 | 95329d52 | 7621e293 | 6740.0 | 8609.932 | 3223.245 | 11833.177 | PASS |

## Concurrent identical pairs

| Pair | Request | Event | Returned statuses | Exactly one commit |
|---:|---|---|---|---|
| 1 | 95329d52 | 7621e293 | committed, duplicate | PASS |

## Safety and interpretation

- The harness sent no POST until a read-only GET explicitly returned `environment: SANDBOX` and a products array.
- Every POST also declares `environment: SANDBOX`.
- Product names, prices, potency, and weights returned by GET are not copied into evidence; only the count and chosen identifiers are retained.
- `serverDurationMs` is reported only when the HTTP response exposes it. Client wall-clock time is always measured.
- The combined time is POST wall time plus the immediately following GET wall time.
- These requests mutate the sandbox. Restore the normal fixture after collecting evidence.
