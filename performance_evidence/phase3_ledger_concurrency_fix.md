# Cannsheet backend sync benchmark evidence

- Suite: `optimized`
- Generated (UTC): `2026-07-17T03:41:29+00:00`
- Endpoint (query removed): `https://script.google.com/macros/s/AKfycbxHPo_Zet7ctELj-rDl4iZSbDwAOxOWOHHsmVUVyteKzZbrjNDVI3GOKhJN_IX69AinuA/exec`
- Confirmed environment: `SANDBOX`
- Deterministic namespace: `6d8d9d52-24c9-4a5a-8cd9-3cf8402e8bb0`
- Timestamp base: `2030-03-01T12:00:00`
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
| concurrent_identical | warm | POST+GET | 20 | 20 | 3872.0 / 5414.5 / 7693.0 / 6850.0 | - | 5766.486 / 7236.53 / 9783.594 / 8580.483 | 4227.971 / 5331.166 / 6657.18 / 6185.529 | 10446.773 / 12500.83 / 14597.487 / 14375.833 |

## Individual results

Full payloads, responses, checks, and follow-up GET evidence are in the JSON file.

| # | Scenario | Label | HTTP | Request | Item IDs | Server ms | Wall ms | Follow-up GET ms | Combined ms | Check |
|---:|---|---|---:|---|---|---:|---:|---:|---:|---|
| 1 | concurrent_identical | warm | 200 | 68d9d00f | d277eb43 | 7693.0 | 9783.594 | 4592.239 | 14375.833 | PASS |
| 2 | concurrent_identical | warm | 200 | 68d9d00f | d277eb43 | 5485.0 | 7307.71 | 5971.315 | 13279.025 | PASS |
| 3 | concurrent_identical | warm | 200 | e7dfa23d | 7d5e4063 | 5547.0 | 7455.102 | 4912.181 | 12367.283 | PASS |
| 4 | concurrent_identical | warm | 200 | e7dfa23d | 7d5e4063 | 4403.0 | 6337.727 | 5160.706 | 11498.433 | PASS |
| 5 | concurrent_identical | warm | 200 | 33dbeef6 | 211fb14a | 4502.0 | 6100.704 | 6023.869 | 12124.573 | PASS |
| 6 | concurrent_identical | warm | 200 | 33dbeef6 | 211fb14a | 5527.0 | 7314.706 | 5708.289 | 13022.995 | PASS |
| 7 | concurrent_identical | warm | 200 | 76c7c953 | 7259ba28 | 5045.0 | 6604.907 | 5442.96 | 12047.867 | PASS |
| 8 | concurrent_identical | warm | 200 | 76c7c953 | 7259ba28 | 6153.0 | 7715.125 | 6185.529 | 13900.654 | PASS |
| 9 | concurrent_identical | warm | 200 | a12d3150 | f89f4716 | 4833.0 | 6408.268 | 5308.481 | 11716.75 | PASS |
| 10 | concurrent_identical | warm | 200 | a12d3150 | f89f4716 | 6492.0 | 8470.596 | 4227.971 | 12698.566 | PASS |
| 11 | concurrent_identical | warm | 200 | f3aac8b2 | 84fbb438 | 6850.0 | 8580.483 | 4812.862 | 13393.345 | PASS |
| 12 | concurrent_identical | warm | 200 | f3aac8b2 | 84fbb438 | 5344.0 | 6714.644 | 5627.528 | 12342.173 | PASS |
| 13 | concurrent_identical | warm | 200 | ea806efa | d10f7b9a | 6519.0 | 8492.945 | 6104.542 | 14597.487 | PASS |
| 14 | concurrent_identical | warm | 200 | ea806efa | d10f7b9a | 4926.0 | 6463.282 | 4437.382 | 10900.664 | PASS |
| 15 | concurrent_identical | warm | 200 | abf5dc52 | 6c1f3650 | 6352.0 | 8144.416 | 4489.961 | 12634.377 | PASS |
| 16 | concurrent_identical | warm | 200 | abf5dc52 | 6c1f3650 | 4420.0 | 5874.016 | 4572.756 | 10446.773 | PASS |
| 17 | concurrent_identical | warm | 200 | 6454c4b8 | c2b1ec8e | 4246.0 | 5911.467 | 5353.85 | 11265.318 | PASS |
| 18 | concurrent_identical | warm | 200 | 6454c4b8 | c2b1ec8e | 5253.0 | 7165.351 | 6657.18 | 13822.532 | PASS |
| 19 | concurrent_identical | warm | 200 | b0ae5a57 | b080d23d | 3872.0 | 5766.486 | 5540.916 | 11307.402 | PASS |
| 20 | concurrent_identical | warm | 200 | b0ae5a57 | b080d23d | 6360.0 | 8108.673 | 4893.59 | 13002.263 | PASS |

## Concurrent identical pairs

| Pair | Request | Event | Returned statuses | Exactly one commit |
|---:|---|---|---|---|
| 1 | 68d9d00f | d277eb43 | duplicate, committed | PASS |
| 2 | e7dfa23d | 7d5e4063 | duplicate, committed | PASS |
| 3 | 33dbeef6 | 211fb14a | committed, duplicate | PASS |
| 4 | 76c7c953 | 7259ba28 | committed, duplicate | PASS |
| 5 | a12d3150 | f89f4716 | committed, duplicate | PASS |
| 6 | f3aac8b2 | 84fbb438 | duplicate, committed | PASS |
| 7 | ea806efa | d10f7b9a | duplicate, committed | PASS |
| 8 | abf5dc52 | 6c1f3650 | duplicate, committed | PASS |
| 9 | 6454c4b8 | c2b1ec8e | committed, duplicate | PASS |
| 10 | b0ae5a57 | b080d23d | committed, duplicate | PASS |

## Safety and interpretation

- The harness sent no POST until a read-only GET explicitly returned `environment: SANDBOX` and a products array.
- Every POST also declares `environment: SANDBOX`.
- Product names, prices, potency, and weights returned by GET are not copied into evidence; only the count and chosen identifiers are retained.
- `serverDurationMs` is reported only when the HTTP response exposes it. Client wall-clock time is always measured.
- The combined time is POST wall time plus the immediately following GET wall time.
- These requests mutate the sandbox. Restore the normal fixture after collecting evidence.
