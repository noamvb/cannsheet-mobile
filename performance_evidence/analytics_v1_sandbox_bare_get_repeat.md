# Cannsheet backend sync benchmark evidence

- Suite: `optimized`
- Generated (UTC): `2026-07-18T17:43:45+00:00`
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
| get | warm | GET | 20 | 20 | 1181.0 / 1720.5 / 2503.0 / 2331.0 | 2773.188 / 3569.359 / 4541.033 / 4351.722 | - | - | - |

## Individual results

Full payloads, responses, checks, and follow-up GET evidence are in the JSON file.

| # | Scenario | Label | HTTP | Request | Item IDs | Server ms | Wall ms | Follow-up GET ms | Combined ms | Check |
|---:|---|---|---:|---|---|---:|---:|---:|---:|---|
| 1 | get | warm | 200 | - | - | 1191.0 | 3947.708 | - | - | PASS |
| 2 | get | warm | 200 | - | - | 1451.0 | 3035.536 | - | - | PASS |
| 3 | get | warm | 200 | - | - | 2064.0 | 4019.777 | - | - | PASS |
| 4 | get | warm | 200 | - | - | 1524.0 | 3099.812 | - | - | PASS |
| 5 | get | warm | 200 | - | - | 2331.0 | 3971.803 | - | - | PASS |
| 6 | get | warm | 200 | - | - | 1479.0 | 3394.56 | - | - | PASS |
| 7 | get | warm | 200 | - | - | 2503.0 | 4541.033 | - | - | PASS |
| 8 | get | warm | 200 | - | - | 2115.0 | 4351.722 | - | - | PASS |
| 9 | get | warm | 200 | - | - | 1873.0 | 3982.751 | - | - | PASS |
| 10 | get | warm | 200 | - | - | 1181.0 | 2773.188 | - | - | PASS |
| 11 | get | warm | 200 | - | - | 1917.0 | 3532.607 | - | - | PASS |
| 12 | get | warm | 200 | - | - | 2236.0 | 3669.282 | - | - | PASS |
| 13 | get | warm | 200 | - | - | 1285.0 | 2902.531 | - | - | PASS |
| 14 | get | warm | 200 | - | - | 1381.0 | 3050.636 | - | - | PASS |
| 15 | get | warm | 200 | - | - | 1225.0 | 2944.733 | - | - | PASS |
| 16 | get | warm | 200 | - | - | 1743.0 | 3606.11 | - | - | PASS |
| 17 | get | warm | 200 | - | - | 1563.0 | 2849.175 | - | - | PASS |
| 18 | get | warm | 200 | - | - | 1977.0 | 3713.239 | - | - | PASS |
| 19 | get | warm | 200 | - | - | 2229.0 | 3994.889 | - | - | PASS |
| 20 | get | warm | 200 | - | - | 1698.0 | 3504.136 | - | - | PASS |

## Safety and interpretation

- The harness sent no POST until a read-only GET explicitly returned `environment: SANDBOX` and a products array.
- Every POST also declares `environment: SANDBOX`.
- Product names, prices, potency, and weights returned by GET are not copied into evidence; only the count and chosen identifiers are retained.
- `serverDurationMs` is reported only when the HTTP response exposes it. Client wall-clock time is always measured.
- The combined time is POST wall time plus the immediately following GET wall time.
- These requests mutate the sandbox. Restore the normal fixture after collecting evidence.
