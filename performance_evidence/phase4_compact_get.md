# Cannsheet backend sync benchmark evidence

- Suite: `optimized`
- Generated (UTC): `2026-07-17T04:45:51+00:00`
- Endpoint (query removed): `https://script.google.com/macros/s/AKfycbxHPo_Zet7ctELj-rDl4iZSbDwAOxOWOHHsmVUVyteKzZbrjNDVI3GOKhJN_IX69AinuA/exec`
- Confirmed environment: `SANDBOX`
- Deterministic namespace: `5752a4e2-e54a-5f9d-9230-3354a437f52c`
- Timestamp base: `2030-01-15T12:00:00`
- Cold idle scheduled before each cold-labelled request: `35.0` seconds
- Overall correctness: **PASS** (25/25 records passed)

The suite label is not part of UUID generation. Reset and reseed the same
sandbox fixture before the baseline and optimized runs so both suites see
the same starting data and submit byte-equivalent deterministic payloads.

## Timing summary

All timing cells are `minimum / median / maximum / p95` in milliseconds.
A p95 is shown only when that metric has at least 20 samples.

| Scenario | Label | Operation | Samples | Passed | Server | GET | POST | Follow-up GET | Combined |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| get | cold | GET | 5 | 5 | 1446.0 / 1867.0 / 2250.0 / - | 3399.35 / 3578.342 / 4433.537 / - | - | - | - |
| get | warm | GET | 20 | 20 | 1244.0 / 1557.0 / 2337.0 / 2294.0 | 2731.646 / 3270.518 / 4951.787 / 4356.193 | - | - | - |

## Individual results

Full payloads, responses, checks, and follow-up GET evidence are in the JSON file.

| # | Scenario | Label | HTTP | Request | Item IDs | Server ms | Wall ms | Follow-up GET ms | Combined ms | Check |
|---:|---|---|---:|---|---|---:|---:|---:|---:|---|
| 1 | get | cold | 200 | - | - | 2143.0 | 4433.537 | - | - | PASS |
| 2 | get | cold | 200 | - | - | 1867.0 | 3578.342 | - | - | PASS |
| 3 | get | cold | 200 | - | - | 1666.0 | 3399.35 | - | - | PASS |
| 4 | get | cold | 200 | - | - | 2250.0 | 4282.427 | - | - | PASS |
| 5 | get | cold | 200 | - | - | 1446.0 | 3520.648 | - | - | PASS |
| 6 | get | warm | 200 | - | - | 1453.0 | 3352.403 | - | - | PASS |
| 7 | get | warm | 200 | - | - | 1263.0 | 2909.384 | - | - | PASS |
| 8 | get | warm | 200 | - | - | 1442.0 | 3117.798 | - | - | PASS |
| 9 | get | warm | 200 | - | - | 1722.0 | 3650.863 | - | - | PASS |
| 10 | get | warm | 200 | - | - | 2337.0 | 4356.193 | - | - | PASS |
| 11 | get | warm | 200 | - | - | 1602.0 | 3317.542 | - | - | PASS |
| 12 | get | warm | 200 | - | - | 1626.0 | 3516.264 | - | - | PASS |
| 13 | get | warm | 200 | - | - | 1487.0 | 3102.563 | - | - | PASS |
| 14 | get | warm | 200 | - | - | 1507.0 | 3322.055 | - | - | PASS |
| 15 | get | warm | 200 | - | - | 1589.0 | 3156.304 | - | - | PASS |
| 16 | get | warm | 200 | - | - | 1462.0 | 2907.438 | - | - | PASS |
| 17 | get | warm | 200 | - | - | 2083.0 | 4206.601 | - | - | PASS |
| 18 | get | warm | 200 | - | - | 1244.0 | 2871.423 | - | - | PASS |
| 19 | get | warm | 200 | - | - | 2099.0 | 4951.787 | - | - | PASS |
| 20 | get | warm | 200 | - | - | 1525.0 | 2900.371 | - | - | PASS |
| 21 | get | warm | 200 | - | - | 1745.0 | 3077.996 | - | - | PASS |
| 22 | get | warm | 200 | - | - | 1399.0 | 2731.646 | - | - | PASS |
| 23 | get | warm | 200 | - | - | 1494.0 | 3223.494 | - | - | PASS |
| 24 | get | warm | 200 | - | - | 1602.0 | 3433.32 | - | - | PASS |
| 25 | get | warm | 200 | - | - | 2294.0 | 4056.815 | - | - | PASS |

## Safety and interpretation

- The harness sent no POST until a read-only GET explicitly returned `environment: SANDBOX` and a products array.
- Every POST also declares `environment: SANDBOX`.
- Product names, prices, potency, and weights returned by GET are not copied into evidence; only the count and chosen identifiers are retained.
- `serverDurationMs` is reported only when the HTTP response exposes it. Client wall-clock time is always measured.
- The combined time is POST wall time plus the immediately following GET wall time.
- These requests mutate the sandbox. Restore the normal fixture after collecting evidence.
