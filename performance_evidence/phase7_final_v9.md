# Cannsheet backend sync benchmark evidence

- Suite: `optimized`
- Generated (UTC): `2026-07-18T01:08:07+00:00`
- Endpoint (query removed): `https://script.google.com/macros/s/AKfycbxHPo_Zet7ctELj-rDl4iZSbDwAOxOWOHHsmVUVyteKzZbrjNDVI3GOKhJN_IX69AinuA/exec`
- Confirmed environment: `SANDBOX`
- Deterministic namespace: `5752a4e2-e54a-5f9d-9230-3354a437f52c`
- Timestamp base: `2030-01-15T12:00:00`
- Cold idle scheduled before each cold-labelled request: `35.0` seconds
- Overall correctness: **PASS** (97/97 records passed)

The suite label is not part of UUID generation. Reset and reseed the same
sandbox fixture before the baseline and optimized runs so both suites see
the same starting data and submit byte-equivalent deterministic payloads.

## Timing summary

All timing cells are `minimum / median / maximum / p95` in milliseconds.
A p95 is shown only when that metric has at least 20 samples.

| Scenario | Label | Operation | Samples | Passed | Server | GET | POST | Follow-up GET | Combined |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| concurrent_identical | warm | POST+GET | 6 | 6 | 3899.0 / 4726.5 / 6711.0 / - | - | 5465.232 / 6438.015 / 8674.908 / - | 2431.73 / 2702.439 / 3703.128 / - | 7963.39 / 9780.266 / 11431.137 / - |
| duplicate_retry | warm | POST+GET | 20 | 20 | 2359.0 / 3597.5 / 6700.0 / 6685.0 | - | 4123.234 / 5568.843 / 9650.491 / 8653.219 | 2458.886 / 3147.267 / 11725.165 / 4386.666 | 6974.878 / 8718.766 / 19157.461 / 13121.891 |
| duplicate_seed | warm | POST+GET | 1 | 1 | 4841.0 / 4841.0 / 4841.0 / - | - | 6835.276 / 6835.276 / 6835.276 / - | 3848.117 / 3848.117 / 3848.117 / - | 10683.393 / 10683.393 / 10683.393 / - |
| empty_v2 | warm | POST+GET | 5 | 5 | 2182.0 / 2470.0 / 2748.0 / - | - | 3815.099 / 4212.874 / 4449.723 / - | 2490.473 / 2566.089 / 3481.411 / - | 6381.188 / 7009.92 / 7875.048 / - |
| get | cold | GET | 5 | 5 | 992.0 / 1355.0 / 1638.0 / - | 2671.039 / 3739.309 / 4868.034 / - | - | - | - |
| get | warm | GET | 20 | 20 | 804.0 / 1176.5 / 2114.0 / 1841.0 | 2360.049 / 3162.749 / 4030.799 / 3831.513 | - | - | - |
| mixed_purchase_consumption | warm | POST+GET | 5 | 5 | 6001.0 / 7117.0 / 11786.0 / - | - | 8469.669 / 9181.608 / 14446.655 / - | 2338.334 / 3410.914 / 3929.558 / - | 11519.942 / 12399.227 / 17934.427 / - |
| new_purchase | warm | POST+GET | 5 | 5 | 5931.0 / 6241.0 / 9958.0 / - | - | 7511.39 / 8106.079 / 11947.337 / - | 2225.349 / 2370.052 / 2960.372 / - | 9881.441 / 11066.451 / 14263.909 / - |
| one_consumption | cold | POST+GET | 5 | 5 | 4726.0 / 5537.0 / 10346.0 / - | - | 7026.82 / 8458.765 / 13901.185 / - | 2230.294 / 2579.677 / 3556.171 / - | 9606.497 / 11700.221 / 16131.479 / - |
| one_consumption | warm | POST+GET | 20 | 20 | 4075.0 / 5276.0 / 8692.0 / 8621.0 | - | 5366.298 / 7150.037 / 10982.361 / 10581.904 | 2347.545 / 3081.708 / 4320.818 / 4001.191 | 8263.32 / 10788.764 / 14902.722 / 14089.213 |
| partial_rejection | warm | POST+GET | 5 | 5 | 4740.0 / 5482.0 / 7124.0 / - | - | 6748.061 / 6966.16 / 8979.274 / - | 2455.01 / 3286.015 / 4605.453 / - | 9421.17 / 11353.514 / 11994.447 / - |

## Individual results

Full payloads, responses, checks, and follow-up GET evidence are in the JSON file.

| # | Scenario | Label | HTTP | Request | Item IDs | Server ms | Wall ms | Follow-up GET ms | Combined ms | Check |
|---:|---|---|---:|---|---|---:|---:|---:|---:|---|
| 1 | get | cold | 200 | - | - | 1355.0 | 3141.863 | - | - | PASS |
| 2 | get | cold | 200 | - | - | 1598.0 | 3827.767 | - | - | PASS |
| 3 | get | cold | 200 | - | - | 1022.0 | 4868.034 | - | - | PASS |
| 4 | get | cold | 200 | - | - | 1638.0 | 3739.309 | - | - | PASS |
| 5 | get | cold | 200 | - | - | 992.0 | 2671.039 | - | - | PASS |
| 6 | get | warm | 200 | - | - | 873.0 | 2856.178 | - | - | PASS |
| 7 | get | warm | 200 | - | - | 1516.0 | 3291.924 | - | - | PASS |
| 8 | get | warm | 200 | - | - | 2114.0 | 4030.799 | - | - | PASS |
| 9 | get | warm | 200 | - | - | 1531.0 | 3375.476 | - | - | PASS |
| 10 | get | warm | 200 | - | - | 804.0 | 2360.049 | - | - | PASS |
| 11 | get | warm | 200 | - | - | 910.0 | 2936.669 | - | - | PASS |
| 12 | get | warm | 200 | - | - | 919.0 | 2972.886 | - | - | PASS |
| 13 | get | warm | 200 | - | - | 1194.0 | 3118.3 | - | - | PASS |
| 14 | get | warm | 200 | - | - | 907.0 | 2520.043 | - | - | PASS |
| 15 | get | warm | 200 | - | - | 1079.0 | 3295.609 | - | - | PASS |
| 16 | get | warm | 200 | - | - | 1451.0 | 3464.823 | - | - | PASS |
| 17 | get | warm | 200 | - | - | 1690.0 | 3207.198 | - | - | PASS |
| 18 | get | warm | 200 | - | - | 1699.0 | 3393.402 | - | - | PASS |
| 19 | get | warm | 200 | - | - | 1304.0 | 3261.339 | - | - | PASS |
| 20 | get | warm | 200 | - | - | 1159.0 | 2879.774 | - | - | PASS |
| 21 | get | warm | 200 | - | - | 1841.0 | 3790.027 | - | - | PASS |
| 22 | get | warm | 200 | - | - | 1014.0 | 2922.726 | - | - | PASS |
| 23 | get | warm | 200 | - | - | 1016.0 | 3831.513 | - | - | PASS |
| 24 | get | warm | 200 | - | - | 1158.0 | 2524.049 | - | - | PASS |
| 25 | get | warm | 200 | - | - | 1397.0 | 2942.066 | - | - | PASS |
| 26 | empty_v2 | warm | 200 | bf83f8f4 | - | 2609.0 | 4449.723 | 2560.198 | 7009.92 | PASS |
| 27 | empty_v2 | warm | 200 | 001f1631 | - | 2470.0 | 4059.692 | 3130.934 | 7190.626 | PASS |
| 28 | empty_v2 | warm | 200 | 7e1d3e9f | - | 2315.0 | 3815.099 | 2566.089 | 6381.188 | PASS |
| 29 | empty_v2 | warm | 200 | ee48d242 | - | 2748.0 | 4393.637 | 3481.411 | 7875.048 | PASS |
| 30 | empty_v2 | warm | 200 | 786991a1 | - | 2182.0 | 4212.874 | 2490.473 | 6703.347 | PASS |
| 31 | one_consumption | warm | 200 | 58d100d2 | 7622cbce | 4676.0 | 6446.327 | 2347.545 | 8793.872 | PASS |
| 32 | one_consumption | warm | 200 | a6bf00d5 | 0f24d4c1 | 7890.0 | 9775.803 | 3684.734 | 13460.537 | PASS |
| 33 | one_consumption | warm | 200 | 3d2ef187 | 65bb2329 | 4374.0 | 6296.489 | 2971.395 | 9267.884 | PASS |
| 34 | one_consumption | warm | 200 | 292a7541 | 38733cb0 | 8185.0 | 10433.329 | 2680.266 | 13113.595 | PASS |
| 35 | one_consumption | warm | 200 | 56a738fa | fee90f9f | 8469.0 | 10508.117 | 3581.096 | 14089.213 | PASS |
| 36 | one_consumption | warm | 200 | 0a5eadc5 | 070262e2 | 8621.0 | 10581.904 | 4320.818 | 14902.722 | PASS |
| 37 | one_consumption | warm | 200 | 0790c9ac | 2c597879 | 4862.0 | 6744.591 | 2496.198 | 9240.788 | PASS |
| 38 | one_consumption | warm | 200 | 34cf6a72 | 1bea01fe | 4766.0 | 6823.347 | 3600.539 | 10423.886 | PASS |
| 39 | one_consumption | warm | 200 | 7922ebf1 | 3a4ea2eb | 5348.0 | 7760.223 | 3095.685 | 10855.908 | PASS |
| 40 | one_consumption | warm | 200 | 62eafabd | 62a5a992 | 8126.0 | 9981.863 | 2558.209 | 12540.072 | PASS |
| 41 | one_consumption | warm | 200 | ac703a21 | 3e9b2569 | 4075.0 | 5366.298 | 3465.721 | 8832.019 | PASS |
| 42 | one_consumption | warm | 200 | ae737205 | 20a5b575 | 5381.0 | 7017.474 | 4001.191 | 11018.665 | PASS |
| 43 | one_consumption | warm | 200 | 50994a32 | 95f35ffe | 8137.0 | 10055.682 | 3067.732 | 13123.414 | PASS |
| 44 | one_consumption | warm | 200 | df7e6c68 | e5af419b | 4321.0 | 5814.985 | 2448.335 | 8263.32 | PASS |
| 45 | one_consumption | warm | 200 | a1d8bfd9 | 24c80c78 | 5204.0 | 7282.599 | 3439.021 | 10721.621 | PASS |
| 46 | one_consumption | warm | 200 | e3282ac2 | 6f74d92c | 8692.0 | 10982.361 | 2765.204 | 13747.565 | PASS |
| 47 | one_consumption | warm | 200 | 9bb11762 | fa9c4c2a | 4656.0 | 6085.716 | 2685.895 | 8771.611 | PASS |
| 48 | one_consumption | warm | 200 | 86968cfb | 23abaa2a | 6911.0 | 8772.997 | 3393.613 | 12166.61 | PASS |
| 49 | one_consumption | warm | 200 | 7852bf7a | 66b344ef | 4501.0 | 6928.298 | 3232.985 | 10161.283 | PASS |
| 50 | one_consumption | warm | 200 | 9180080a | 17aa7fb2 | 4610.0 | 6279.85 | 2674.297 | 8954.147 | PASS |
| 51 | one_consumption | cold | 200 | f9797d83 | 2c12aaf0 | 10346.0 | 13901.185 | 2230.294 | 16131.479 | PASS |
| 52 | one_consumption | cold | 200 | 197be014 | aab1e056 | 6967.0 | 8824.026 | 2876.196 | 11700.221 | PASS |
| 53 | one_consumption | cold | 200 | 6976a7d5 | 32e0e745 | 5227.0 | 7026.82 | 2579.677 | 9606.497 | PASS |
| 54 | one_consumption | cold | 200 | 7176c549 | dd5d7dfa | 4726.0 | 7451.622 | 2419.312 | 9870.934 | PASS |
| 55 | one_consumption | cold | 200 | 4930bc93 | d3aaef08 | 5537.0 | 8458.765 | 3556.171 | 12014.936 | PASS |
| 56 | duplicate_seed | warm | 200 | aa0b63fd | ca15a02b | 4841.0 | 6835.276 | 3848.117 | 10683.393 | PASS |
| 57 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 5060.0 | 6738.412 | 3737.262 | 10475.674 | PASS |
| 58 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4757.0 | 7432.295 | 11725.165 | 19157.461 | PASS |
| 59 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 3592.0 | 7642.292 | 3457.028 | 11099.32 | PASS |
| 60 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 3480.0 | 5255.646 | 2605.692 | 7861.338 | PASS |
| 61 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4159.0 | 6034.991 | 2697.859 | 8732.85 | PASS |
| 62 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 3029.0 | 4911.667 | 3069.047 | 7980.715 | PASS |
| 63 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 3309.0 | 5199.217 | 2458.886 | 7658.103 | PASS |
| 64 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 2888.0 | 4756.433 | 3797.26 | 8553.693 | PASS |
| 65 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 6700.0 | 9650.491 | 3471.4 | 13121.891 | PASS |
| 66 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 6685.0 | 8653.219 | 3225.488 | 11878.707 | PASS |
| 67 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 3603.0 | 5335.498 | 2968.832 | 8304.33 | PASS |
| 68 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 2715.0 | 4318.015 | 4386.666 | 8704.681 | PASS |
| 69 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 2544.0 | 4581.909 | 2634.623 | 7216.532 | PASS |
| 70 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 2701.0 | 4598.873 | 3317.129 | 7916.002 | PASS |
| 71 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 2359.0 | 4123.234 | 2851.644 | 6974.878 | PASS |
| 72 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 5241.0 | 6914.792 | 2815.253 | 9730.045 | PASS |
| 73 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4167.0 | 5802.189 | 3622.974 | 9425.162 | PASS |
| 74 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4468.0 | 6346.143 | 3632.419 | 9978.562 | PASS |
| 75 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4441.0 | 6413.632 | 2665.999 | 9079.631 | PASS |
| 76 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 2897.0 | 4386.748 | 3006.954 | 7393.701 | PASS |
| 77 | new_purchase | warm | 200 | 6a26656e | 15e17427 | 6726.0 | 9350.595 | 2225.349 | 11575.943 | PASS |
| 78 | new_purchase | warm | 200 | 997316cf | b82a0a04 | 6241.0 | 8082.909 | 2725.04 | 10807.949 | PASS |
| 79 | new_purchase | warm | 200 | 35b2b33c | 85ad964a | 5959.0 | 8106.079 | 2960.372 | 11066.451 | PASS |
| 80 | new_purchase | warm | 200 | 3d32a780 | 734eacd3 | 5931.0 | 7511.39 | 2370.052 | 9881.441 | PASS |
| 81 | new_purchase | warm | 200 | 8e768c67 | be755b74 | 9958.0 | 11947.337 | 2316.572 | 14263.909 | PASS |
| 82 | mixed_purchase_consumption | warm | 200 | 741f8c18 | e7266fd4, 90475175 | 7057.0 | 9181.608 | 2338.334 | 11519.942 | PASS |
| 83 | mixed_purchase_consumption | warm | 200 | 135d2bc7 | 728d7156, 98a6fe66 | 7117.0 | 8701.003 | 3410.914 | 12111.917 | PASS |
| 84 | mixed_purchase_consumption | warm | 200 | 36ce64e0 | 75c4a50f, b897a6f6 | 7999.0 | 9999.818 | 2509.158 | 12508.976 | PASS |
| 85 | mixed_purchase_consumption | warm | 200 | 6e42606e | e69b8959, 7d599e9c | 6001.0 | 8469.669 | 3929.558 | 12399.227 | PASS |
| 86 | mixed_purchase_consumption | warm | 200 | 5ed1179e | 444d574e, e40ccbce | 11786.0 | 14446.655 | 3487.772 | 17934.427 | PASS |
| 87 | partial_rejection | warm | 200 | 49d2fd52 | 82bbf1fe, 1d1865c7 | 4740.0 | 6748.061 | 4605.453 | 11353.514 | PASS |
| 88 | partial_rejection | warm | 200 | 7c412ef9 | 0c52f997, 20b51a00 | 5482.0 | 6966.16 | 2455.01 | 9421.17 | PASS |
| 89 | partial_rejection | warm | 200 | 9f4e1c88 | 5b22af25, 25c22dc3 | 6893.0 | 8708.432 | 3286.015 | 11994.447 | PASS |
| 90 | partial_rejection | warm | 200 | 7d14c3e2 | 1a47b01c, 728bc1f3 | 7124.0 | 8979.274 | 2980.051 | 11959.326 | PASS |
| 91 | partial_rejection | warm | 200 | a39873a1 | a9ef66f2, 20917ff1 | 5286.0 | 6777.033 | 3750.548 | 10527.582 | PASS |
| 92 | concurrent_identical | warm | 200 | c3de9226 | 8e7f5758 | 4407.0 | 6124.776 | 3703.128 | 9827.903 | PASS |
| 93 | concurrent_identical | warm | 200 | c3de9226 | 8e7f5758 | 6711.0 | 8674.908 | 2756.23 | 11431.137 | PASS |
| 94 | concurrent_identical | warm | 200 | 143c2b9a | 32df983b | 5046.0 | 6640.75 | 2648.649 | 9289.399 | PASS |
| 95 | concurrent_identical | warm | 200 | 143c2b9a | 32df983b | 3899.0 | 5465.232 | 2498.158 | 7963.39 | PASS |
| 96 | concurrent_identical | warm | 200 | d8a3412f | 7aa0e90c | 4270.0 | 6235.28 | 3497.35 | 9732.63 | PASS |
| 97 | concurrent_identical | warm | 200 | d8a3412f | 7aa0e90c | 5569.0 | 7725.351 | 2431.73 | 10157.081 | PASS |

## Concurrent identical pairs

| Pair | Request | Event | Returned statuses | Exactly one commit |
|---:|---|---|---|---|
| 1 | c3de9226 | 8e7f5758 | committed, duplicate | PASS |
| 2 | 143c2b9a | 32df983b | duplicate, committed | PASS |
| 3 | d8a3412f | 7aa0e90c | committed, duplicate | PASS |

## Safety and interpretation

- The harness sent no POST until a read-only GET explicitly returned `environment: SANDBOX` and a products array.
- Every POST also declares `environment: SANDBOX`.
- Product names, prices, potency, and weights returned by GET are not copied into evidence; only the count and chosen identifiers are retained.
- `serverDurationMs` is reported only when the HTTP response exposes it. Client wall-clock time is always measured.
- The combined time is POST wall time plus the immediately following GET wall time.
- These requests mutate the sandbox. Restore the normal fixture after collecting evidence.
