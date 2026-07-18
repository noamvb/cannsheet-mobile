# Cannsheet backend sync benchmark evidence

- Suite: `baseline`
- Generated (UTC): `2026-07-15T01:43:01+00:00`
- Endpoint (query removed): `https://script.google.com/macros/s/AKfycbxHPo_Zet7ctELj-rDl4iZSbDwAOxOWOHHsmVUVyteKzZbrjNDVI3GOKhJN_IX69AinuA/exec`
- Confirmed environment: `SANDBOX`
- Deterministic namespace: `5752a4e2-e54a-5f9d-9230-3354a437f52c`
- Timestamp base: `2030-01-15T12:00:00`
- Cold idle scheduled before each cold-labelled request: `35.0` seconds
- Overall correctness: **PASS** (87/87 records passed)

The suite label is not part of UUID generation. Reset and reseed the same
sandbox fixture before the baseline and optimized runs so both suites see
the same starting data and submit byte-equivalent deterministic payloads.

## Timing summary

All timing cells are `minimum / median / maximum / p95` in milliseconds.
A p95 is shown only when that metric has at least 20 samples.

| Scenario | Label | Operation | Samples | Passed | Server | GET | POST | Follow-up GET | Combined |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| concurrent_identical | warm | POST+GET | 6 | 6 | 8122.0 / 11350.0 / 14796.0 / - | - | 9623.063 / 13102.709 / 16870.936 / - | 3209.977 / 3875.977 / 4557.636 / - | 14180.699 / 17155.457 / 20080.913 / - |
| duplicate_retry | warm | POST+GET | 10 | 10 | 3686.0 / 4923.5 / 8949.0 / - | - | 5547.553 / 6888.438 / 18356.016 / - | 2794.433 / 3703.772 / 6701.854 / - | 8731.312 / 11549.812 / 21749.693 / - |
| duplicate_seed | warm | POST+GET | 1 | 1 | 8403.0 / 8403.0 / 8403.0 / - | - | 10241.074 / 10241.074 / 10241.074 / - | 3555.053 / 3555.053 / 3555.053 / - | 13796.127 / 13796.127 / 13796.127 / - |
| empty_v2 | warm | POST+GET | 5 | 5 | 6044.0 / 6947.0 / 9203.0 / - | - | 7705.525 / 8832.421 / 11060.076 / - | 4221.372 / 5103.409 / 6186.818 / - | 11926.897 / 13839.545 / 17246.894 / - |
| get | cold | GET | 5 | 5 | 2702.0 / 2975.0 / 3238.0 / - | 4731.629 / 5390.987 / 6244.734 / - | - | - | - |
| get | warm | GET | 20 | 20 | 2590.0 / 3349.5 / 4743.0 / 4638.0 | 4121.714 / 5167.99 / 6804.973 / 6741.979 | - | - | - |
| mixed_purchase_consumption | warm | POST+GET | 5 | 5 | 7905.0 / 13446.0 / 14763.0 / - | - | 10916.517 / 16371.63 / 17162.379 / - | 3519.381 / 4371.018 / 6271.269 / - | 17187.786 / 20108.215 / 20742.648 / - |
| new_purchase | warm | POST+GET | 5 | 5 | 7481.0 / 8385.0 / 10172.0 / - | - | 9246.375 / 10376.419 / 12011.613 / - | 3143.532 / 4389.901 / 5193.606 / - | 13636.277 / 15155.145 / 15239.611 / - |
| one_consumption | cold | POST+GET | 5 | 5 | 9342.0 / 11079.0 / 12500.0 / - | - | 11305.092 / 13496.861 / 15578.469 / - | 4859.497 / 4998.766 / 7589.83 / - | 16303.858 / 18384.032 / 23168.299 / - |
| one_consumption | warm | POST+GET | 20 | 20 | 8414.0 / 10904.5 / 18411.0 / 14372.0 | - | 10286.036 / 13196.814 / 23691.282 / 16775.378 | 4273.739 / 4751.519 / 6915.324 / 6790.371 | 14896.392 / 18678.686 / 28343.078 / 23088.327 |
| partial_rejection | warm | POST+GET | 5 | 5 | 5973.0 / 8591.0 / 11057.0 / - | - | 7840.217 / 11298.26 / 13201.543 / - | 3657.329 / 4288.203 / 5224.013 / - | 13064.23 / 15016.633 / 17489.746 / - |

## Individual results

Full payloads, responses, checks, and follow-up GET evidence are in the JSON file.

| # | Scenario | Label | HTTP | Request | Item IDs | Server ms | Wall ms | Follow-up GET ms | Combined ms | Check |
|---:|---|---|---:|---|---|---:|---:|---:|---:|---|
| 1 | get | cold | 200 | - | - | 3238.0 | 6244.734 | - | - | PASS |
| 2 | get | cold | 200 | - | - | 2970.0 | 4731.629 | - | - | PASS |
| 3 | get | cold | 200 | - | - | 2702.0 | 5390.987 | - | - | PASS |
| 4 | get | cold | 200 | - | - | 3174.0 | 5207.439 | - | - | PASS |
| 5 | get | cold | 200 | - | - | 2975.0 | 6049.8 | - | - | PASS |
| 6 | get | warm | 200 | - | - | 4743.0 | 6741.979 | - | - | PASS |
| 7 | get | warm | 200 | - | - | 3615.0 | 5492.04 | - | - | PASS |
| 8 | get | warm | 200 | - | - | 3964.0 | 6804.973 | - | - | PASS |
| 9 | get | warm | 200 | - | - | 2718.0 | 4121.714 | - | - | PASS |
| 10 | get | warm | 200 | - | - | 3113.0 | 4875.398 | - | - | PASS |
| 11 | get | warm | 200 | - | - | 2983.0 | 4513.323 | - | - | PASS |
| 12 | get | warm | 200 | - | - | 3938.0 | 5444.12 | - | - | PASS |
| 13 | get | warm | 200 | - | - | 3147.0 | 4829.834 | - | - | PASS |
| 14 | get | warm | 200 | - | - | 2628.0 | 5357.17 | - | - | PASS |
| 15 | get | warm | 200 | - | - | 2926.0 | 4577.588 | - | - | PASS |
| 16 | get | warm | 200 | - | - | 2590.0 | 4402.716 | - | - | PASS |
| 17 | get | warm | 200 | - | - | 2911.0 | 4222.395 | - | - | PASS |
| 18 | get | warm | 200 | - | - | 3851.0 | 5326.839 | - | - | PASS |
| 19 | get | warm | 200 | - | - | 3209.0 | 4473.305 | - | - | PASS |
| 20 | get | warm | 200 | - | - | 4178.0 | 5914.214 | - | - | PASS |
| 21 | get | warm | 200 | - | - | 4169.0 | 5762.417 | - | - | PASS |
| 22 | get | warm | 200 | - | - | 3335.0 | 4918.317 | - | - | PASS |
| 23 | get | warm | 200 | - | - | 4240.0 | 6138.247 | - | - | PASS |
| 24 | get | warm | 200 | - | - | 3364.0 | 5009.142 | - | - | PASS |
| 25 | get | warm | 200 | - | - | 4638.0 | 6269.699 | - | - | PASS |
| 26 | empty_v2 | warm | 200 | bf83f8f4 | - | 9203.0 | 11060.076 | 6186.818 | 17246.894 | PASS |
| 27 | empty_v2 | warm | 200 | 001f1631 | - | 6947.0 | 8832.421 | 5007.124 | 13839.545 | PASS |
| 28 | empty_v2 | warm | 200 | 7e1d3e9f | - | 6044.0 | 7705.525 | 4221.372 | 11926.897 | PASS |
| 29 | empty_v2 | warm | 200 | ee48d242 | - | 6502.0 | 8393.494 | 5269.84 | 13663.335 | PASS |
| 30 | empty_v2 | warm | 200 | 786991a1 | - | 7639.0 | 10242.982 | 5103.409 | 15346.391 | PASS |
| 31 | one_consumption | warm | 200 | 58d100d2 | 7622cbce | 10650.0 | 12759.428 | 4550.407 | 17309.834 | PASS |
| 32 | one_consumption | warm | 200 | a6bf00d5 | 0f24d4c1 | 11645.0 | 13659.232 | 6915.324 | 20574.556 | PASS |
| 33 | one_consumption | warm | 200 | 3d2ef187 | 65bb2329 | 9935.0 | 11890.343 | 4279.875 | 16170.218 | PASS |
| 34 | one_consumption | warm | 200 | 292a7541 | 38733cb0 | 9883.0 | 11705.587 | 4551.861 | 16257.448 | PASS |
| 35 | one_consumption | warm | 200 | 56a738fa | fee90f9f | 8718.0 | 10524.694 | 4939.804 | 15464.499 | PASS |
| 36 | one_consumption | warm | 200 | 0a5eadc5 | 070262e2 | 9028.0 | 11126.206 | 6790.371 | 17916.577 | PASS |
| 37 | one_consumption | warm | 200 | 0790c9ac | 2c597879 | 12853.0 | 14395.742 | 4367.587 | 18763.328 | PASS |
| 38 | one_consumption | warm | 200 | 34cf6a72 | 1bea01fe | 8836.0 | 10728.081 | 5892.161 | 16620.242 | PASS |
| 39 | one_consumption | warm | 200 | 7922ebf1 | 3a4ea2eb | 11113.0 | 13634.199 | 6003.262 | 19637.461 | PASS |
| 40 | one_consumption | warm | 200 | 62eafabd | 62a5a992 | 14372.0 | 16301.727 | 4324.644 | 20626.371 | PASS |
| 41 | one_consumption | warm | 200 | ac703a21 | 3e9b2569 | 10696.0 | 12666.714 | 5927.331 | 18594.045 | PASS |
| 42 | one_consumption | warm | 200 | ae737205 | 20a5b575 | 9155.0 | 10917.687 | 4273.739 | 15191.426 | PASS |
| 43 | one_consumption | warm | 200 | 50994a32 | 95f35ffe | 12524.0 | 14927.84 | 4563.165 | 19491.005 | PASS |
| 44 | one_consumption | warm | 200 | df7e6c68 | e5af419b | 9483.0 | 11270.044 | 4807.578 | 16077.622 | PASS |
| 45 | one_consumption | warm | 200 | a1d8bfd9 | 24c80c78 | 8414.0 | 10286.036 | 4610.356 | 14896.392 | PASS |
| 46 | one_consumption | warm | 200 | e3282ac2 | 6f74d92c | 18411.0 | 23691.282 | 4651.796 | 28343.078 | PASS |
| 47 | one_consumption | warm | 200 | 9bb11762 | fa9c4c2a | 12238.0 | 14100.741 | 5231.587 | 19332.327 | PASS |
| 48 | one_consumption | warm | 200 | 86968cfb | 23abaa2a | 11832.0 | 14025.539 | 6639.017 | 20664.555 | PASS |
| 49 | one_consumption | warm | 200 | 7852bf7a | 66b344ef | 13626.0 | 15936.954 | 4695.459 | 20632.413 | PASS |
| 50 | one_consumption | warm | 200 | 9180080a | 17aa7fb2 | 14084.0 | 16775.378 | 6312.949 | 23088.327 | PASS |
| 51 | one_consumption | cold | 200 | f9797d83 | 2c12aaf0 | 11631.0 | 13606.746 | 4859.497 | 18466.243 | PASS |
| 52 | one_consumption | cold | 200 | 197be014 | aab1e056 | 12500.0 | 15578.469 | 7589.83 | 23168.299 | PASS |
| 53 | one_consumption | cold | 200 | 6976a7d5 | 32e0e745 | 11079.0 | 13496.861 | 4887.171 | 18384.032 | PASS |
| 54 | one_consumption | cold | 200 | 7176c549 | dd5d7dfa | 9342.0 | 11305.092 | 4998.766 | 16303.858 | PASS |
| 55 | one_consumption | cold | 200 | 4930bc93 | d3aaef08 | 9363.0 | 12156.191 | 5465.987 | 17622.178 | PASS |
| 56 | duplicate_seed | warm | 200 | aa0b63fd | ca15a02b | 8403.0 | 10241.074 | 3555.053 | 13796.127 | PASS |
| 57 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 5474.0 | 7618.001 | 5006.359 | 12624.36 | PASS |
| 58 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 8949.0 | 18356.016 | 3393.678 | 21749.693 | PASS |
| 59 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4137.0 | 5936.879 | 2794.433 | 8731.312 | PASS |
| 60 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 3686.0 | 5547.553 | 6701.854 | 12249.407 | PASS |
| 61 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4845.0 | 6771.403 | 3762.465 | 10533.868 | PASS |
| 62 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4144.0 | 6480.122 | 3219.592 | 9699.713 | PASS |
| 63 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4330.0 | 6040.011 | 5738.671 | 11778.682 | PASS |
| 64 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 5767.0 | 7973.205 | 3347.737 | 11320.942 | PASS |
| 65 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 6922.0 | 10071.114 | 4538.015 | 14609.129 | PASS |
| 66 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 5002.0 | 7005.472 | 3645.079 | 10650.551 | PASS |
| 67 | new_purchase | warm | 200 | 6a26656e | 15e17427 | 10172.0 | 12011.613 | 3143.532 | 15155.145 | PASS |
| 68 | new_purchase | warm | 200 | 997316cf | b82a0a04 | 8385.0 | 10376.419 | 4841.439 | 15217.858 | PASS |
| 69 | new_purchase | warm | 200 | 35b2b33c | 85ad964a | 9182.0 | 10840.147 | 3758.237 | 14598.383 | PASS |
| 70 | new_purchase | warm | 200 | 3d32a780 | 734eacd3 | 7481.0 | 9246.375 | 4389.901 | 13636.277 | PASS |
| 71 | new_purchase | warm | 200 | 8e768c67 | be755b74 | 8093.0 | 10046.005 | 5193.606 | 15239.611 | PASS |
| 72 | mixed_purchase_consumption | warm | 200 | 741f8c18 | e7266fd4, 90475175 | 14270.0 | 16371.63 | 4371.018 | 20742.648 | PASS |
| 73 | mixed_purchase_consumption | warm | 200 | 135d2bc7 | 728d7156, 98a6fe66 | 7905.0 | 10916.517 | 6271.269 | 17187.786 | PASS |
| 74 | mixed_purchase_consumption | warm | 200 | 36ce64e0 | 75c4a50f, b897a6f6 | 13446.0 | 16378.163 | 3730.052 | 20108.215 | PASS |
| 75 | mixed_purchase_consumption | warm | 200 | 6e42606e | e69b8959, 7d599e9c | 11977.0 | 13833.704 | 5587.136 | 19420.84 | PASS |
| 76 | mixed_purchase_consumption | warm | 200 | 5ed1179e | 444d574e, e40ccbce | 14763.0 | 17162.379 | 3519.381 | 20681.76 | PASS |
| 77 | partial_rejection | warm | 200 | 49d2fd52 | 82bbf1fe, 1d1865c7 | 8366.0 | 10443.733 | 4572.9 | 15016.633 | PASS |
| 78 | partial_rejection | warm | 200 | 7c412ef9 | 0c52f997, 20b51a00 | 11057.0 | 13201.543 | 4288.203 | 17489.746 | PASS |
| 79 | partial_rejection | warm | 200 | 9f4e1c88 | 5b22af25, 25c22dc3 | 9435.0 | 11356.148 | 3657.329 | 15013.477 | PASS |
| 80 | partial_rejection | warm | 200 | 7d14c3e2 | 1a47b01c, 728bc1f3 | 5973.0 | 7840.217 | 5224.013 | 13064.23 | PASS |
| 81 | partial_rejection | warm | 200 | a39873a1 | a9ef66f2, 20917ff1 | 8591.0 | 11298.26 | 3744.034 | 15042.294 | PASS |
| 82 | concurrent_identical | warm | 200 | c3de9226 | 8e7f5758 | 8122.0 | 9623.063 | 4557.636 | 14180.699 | PASS |
| 83 | concurrent_identical | warm | 200 | c3de9226 | 8e7f5758 | 13629.0 | 15790.32 | 3879.466 | 19669.786 | PASS |
| 84 | concurrent_identical | warm | 200 | 143c2b9a | 32df983b | 8933.0 | 10853.474 | 3448.961 | 14302.434 | PASS |
| 85 | concurrent_identical | warm | 200 | 143c2b9a | 32df983b | 14796.0 | 16870.936 | 3209.977 | 20080.913 | PASS |
| 86 | concurrent_identical | warm | 200 | d8a3412f | 7aa0e90c | 9604.0 | 11225.99 | 4233.01 | 15459.0 | PASS |
| 87 | concurrent_identical | warm | 200 | d8a3412f | 7aa0e90c | 13096.0 | 14979.427 | 3872.488 | 18851.914 | PASS |

## Concurrent identical pairs

| Pair | Request | Event | Returned statuses | Exactly one commit |
|---:|---|---|---|---|
| 1 | c3de9226 | 8e7f5758 | committed, duplicate | PASS |
| 2 | 143c2b9a | 32df983b | committed, duplicate | PASS |
| 3 | d8a3412f | 7aa0e90c | committed, duplicate | PASS |

## Safety and interpretation

- The harness sent no POST until a read-only GET explicitly returned `environment: SANDBOX` and a products array.
- Every POST also declares `environment: SANDBOX`.
- Product names, prices, potency, and weights returned by GET are not copied into evidence; only the count and chosen identifiers are retained.
- `serverDurationMs` is reported only when the HTTP response exposes it. Client wall-clock time is always measured.
- The combined time is POST wall time plus the immediately following GET wall time.
- These requests mutate the sandbox. Restore the normal fixture after collecting evidence.
