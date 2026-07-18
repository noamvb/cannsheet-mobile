# Cannsheet backend sync benchmark evidence

- Suite: `optimized`
- Generated (UTC): `2026-07-18T17:42:07+00:00`
- Endpoint (query removed): `https://script.google.com/macros/s/AKfycbxHPo_Zet7ctELj-rDl4iZSbDwAOxOWOHHsmVUVyteKzZbrjNDVI3GOKhJN_IX69AinuA/exec`
- Confirmed environment: `SANDBOX`
- Deterministic namespace: `5752a4e2-e54a-5f9d-9230-3354a437f52c`
- Timestamp base: `2030-01-15T12:00:00`
- Cold idle scheduled before each cold-labelled request: `0.0` seconds
- Overall correctness: **PASS** (20/20 records passed)

The suite label is not part of UUID generation. Reset and reseed the same
sandbox fixture before the baseline and optimized runs so both suites see
the same starting data and submit byte-equivalent deterministic payloads.

## Timing summary

All timing cells are `minimum / median / maximum / p95` in milliseconds.
A p95 is shown only when that metric has at least 20 samples.

| Scenario | Label | Operation | Samples | Passed | Server | GET | POST | Follow-up GET | Combined |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| get | warm | GET | 20 | 20 | 1178.0 / 1629.5 / 8308.0 / 3213.0 | 2681.676 / 3469.016 / 10645.557 / 5588.524 | - | - | - |

## Individual results

Full payloads, responses, checks, and follow-up GET evidence are in the JSON file.

| # | Scenario | Label | HTTP | Request | Item IDs | Server ms | Wall ms | Follow-up GET ms | Combined ms | Check |
|---:|---|---|---:|---|---|---:|---:|---:|---:|---|
| 1 | get | warm | 200 | - | - | 1534.0 | 3120.411 | - | - | PASS |
| 2 | get | warm | 200 | - | - | 1917.0 | 3656.483 | - | - | PASS |
| 3 | get | warm | 200 | - | - | 2630.0 | 4956.717 | - | - | PASS |
| 4 | get | warm | 200 | - | - | 3213.0 | 5588.524 | - | - | PASS |
| 5 | get | warm | 200 | - | - | 2433.0 | 4232.345 | - | - | PASS |
| 6 | get | warm | 200 | - | - | 2047.0 | 3917.88 | - | - | PASS |
| 7 | get | warm | 200 | - | - | 1426.0 | 3241.024 | - | - | PASS |
| 8 | get | warm | 200 | - | - | 1449.0 | 3036.125 | - | - | PASS |
| 9 | get | warm | 200 | - | - | 8308.0 | 10645.557 | - | - | PASS |
| 10 | get | warm | 200 | - | - | 1725.0 | 3503.043 | - | - | PASS |
| 11 | get | warm | 200 | - | - | 1210.0 | 2964.552 | - | - | PASS |
| 12 | get | warm | 200 | - | - | 1506.0 | 3819.979 | - | - | PASS |
| 13 | get | warm | 200 | - | - | 1178.0 | 2681.676 | - | - | PASS |
| 14 | get | warm | 200 | - | - | 1406.0 | 3273.542 | - | - | PASS |
| 15 | get | warm | 200 | - | - | 2023.0 | 3851.669 | - | - | PASS |
| 16 | get | warm | 200 | - | - | 1471.0 | 3389.039 | - | - | PASS |
| 17 | get | warm | 200 | - | - | 1611.0 | 3434.989 | - | - | PASS |
| 18 | get | warm | 200 | - | - | 2383.0 | 4432.993 | - | - | PASS |
| 19 | get | warm | 200 | - | - | 1357.0 | 2924.902 | - | - | PASS |
| 20 | get | warm | 200 | - | - | 1648.0 | 3110.368 | - | - | PASS |

## Safety and interpretation

- The harness sent no POST until a read-only GET explicitly returned `environment: SANDBOX` and a products array.
- Every POST also declares `environment: SANDBOX`.
- Product names, prices, potency, and weights returned by GET are not copied into evidence; only the count and chosen identifiers are retained.
- `serverDurationMs` is reported only when the HTTP response exposes it. Client wall-clock time is always measured.
- The combined time is POST wall time plus the immediately following GET wall time.
- These requests mutate the sandbox. Restore the normal fixture after collecting evidence.
