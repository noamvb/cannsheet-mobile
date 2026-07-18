# Cannsheet backend sync benchmark evidence

- Suite: `optimized`
- Generated (UTC): `2026-07-18T00:33:52+00:00`
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
| concurrent_identical | warm | POST+GET | 6 | 6 | 5192.0 / 7149.5 / 10893.0 / - | - | 6938.55 / 8813.072 / 12914.404 / - | 2384.222 / 2674.247 / 4034.499 / - | 9690.71 / 11681.002 / 16948.902 / - |
| duplicate_retry | warm | POST+GET | 20 | 20 | 3968.0 / 5673.0 / 9535.0 / 9307.0 | - | 5873.215 / 7529.825 / 11645.239 / 11523.611 | 2454.235 / 3175.512 / 4032.136 / 3702.853 | 8327.45 / 10963.317 / 14669.388 / 14245.911 |
| duplicate_seed | warm | POST+GET | 1 | 1 | 7006.0 / 7006.0 / 7006.0 / - | - | 8892.453 / 8892.453 / 8892.453 / - | 4118.002 / 4118.002 / 4118.002 / - | 13010.455 / 13010.455 / 13010.455 / - |
| empty_v2 | warm | POST+GET | 5 | 5 | 4324.0 / 6764.0 / 7655.0 / - | - | 6192.716 / 8577.297 / 10348.069 / - | 2285.305 / 2914.605 / 4032.996 / - | 10125.431 / 11491.902 / 12854.034 / - |
| get | cold | GET | 5 | 5 | 1196.0 / 1354.0 / 1725.0 / - | 3201.474 / 3483.494 / 4745.832 / - | - | - | - |
| get | warm | GET | 20 | 20 | 891.0 / 1367.0 / 2115.0 / 2060.0 | 2487.752 / 3208.274 / 4057.816 / 3927.268 | - | - | - |
| mixed_purchase_consumption | warm | POST+GET | 5 | 5 | 6815.0 / 7452.0 / 11237.0 / - | - | 8611.206 / 9520.266 / 13363.17 / - | 2337.701 / 2613.083 / 3476.307 / - | 11218.792 / 12619.199 / 15976.254 / - |
| new_purchase | warm | POST+GET | 5 | 5 | 5956.0 / 7089.0 / 11555.0 / - | - | 7961.464 / 8835.892 / 13521.45 / - | 2695.15 / 3180.044 / 3719.759 / - | 10656.614 / 11903.269 / 16701.493 / - |
| one_consumption | cold | POST+GET | 5 | 5 | 5289.0 / 6392.0 / 8454.0 / - | - | 7176.906 / 8456.597 / 10360.302 / - | 2455.991 / 2835.339 / 4662.453 / - | 9632.897 / 12428.984 / 13145.678 / - |
| one_consumption | warm | POST+GET | 20 | 20 | 4374.0 / 5530.5 / 8761.0 / 8585.0 | - | 6168.055 / 7244.323 / 11412.055 / 10994.454 | 2399.136 / 3055.903 / 4817.791 / 3713.281 | 8896.828 / 10475.537 / 14795.802 / 14261.29 |
| partial_rejection | warm | POST+GET | 5 | 5 | 4415.0 / 4826.0 / 9342.0 / - | - | 5964.781 / 6580.408 / 11199.067 / - | 2297.172 / 3014.67 / 5205.962 / - | 8670.684 / 9595.078 / 16405.029 / - |

## Individual results

Full payloads, responses, checks, and follow-up GET evidence are in the JSON file.

| # | Scenario | Label | HTTP | Request | Item IDs | Server ms | Wall ms | Follow-up GET ms | Combined ms | Check |
|---:|---|---|---:|---|---|---:|---:|---:|---:|---|
| 1 | get | cold | 200 | - | - | 1196.0 | 3252.003 | - | - | PASS |
| 2 | get | cold | 200 | - | - | 1354.0 | 3201.474 | - | - | PASS |
| 3 | get | cold | 200 | - | - | 1725.0 | 4745.832 | - | - | PASS |
| 4 | get | cold | 200 | - | - | 1324.0 | 4277.388 | - | - | PASS |
| 5 | get | cold | 200 | - | - | 1471.0 | 3483.494 | - | - | PASS |
| 6 | get | warm | 200 | - | - | 2060.0 | 3927.268 | - | - | PASS |
| 7 | get | warm | 200 | - | - | 1193.0 | 3181.136 | - | - | PASS |
| 8 | get | warm | 200 | - | - | 964.0 | 3106.268 | - | - | PASS |
| 9 | get | warm | 200 | - | - | 1552.0 | 3668.916 | - | - | PASS |
| 10 | get | warm | 200 | - | - | 1862.0 | 3603.836 | - | - | PASS |
| 11 | get | warm | 200 | - | - | 2035.0 | 4057.816 | - | - | PASS |
| 12 | get | warm | 200 | - | - | 1476.0 | 3365.762 | - | - | PASS |
| 13 | get | warm | 200 | - | - | 1072.0 | 2719.915 | - | - | PASS |
| 14 | get | warm | 200 | - | - | 1436.0 | 2887.498 | - | - | PASS |
| 15 | get | warm | 200 | - | - | 1298.0 | 3114.912 | - | - | PASS |
| 16 | get | warm | 200 | - | - | 1121.0 | 3083.733 | - | - | PASS |
| 17 | get | warm | 200 | - | - | 1601.0 | 3402.836 | - | - | PASS |
| 18 | get | warm | 200 | - | - | 1072.0 | 3235.412 | - | - | PASS |
| 19 | get | warm | 200 | - | - | 1071.0 | 2941.573 | - | - | PASS |
| 20 | get | warm | 200 | - | - | 1088.0 | 2520.221 | - | - | PASS |
| 21 | get | warm | 200 | - | - | 1706.0 | 3723.159 | - | - | PASS |
| 22 | get | warm | 200 | - | - | 1011.0 | 2955.544 | - | - | PASS |
| 23 | get | warm | 200 | - | - | 1641.0 | 3734.987 | - | - | PASS |
| 24 | get | warm | 200 | - | - | 2115.0 | 3663.858 | - | - | PASS |
| 25 | get | warm | 200 | - | - | 891.0 | 2487.752 | - | - | PASS |
| 26 | empty_v2 | warm | 200 | bf83f8f4 | - | 5890.0 | 8329.436 | 4032.996 | 12362.432 | PASS |
| 27 | empty_v2 | warm | 200 | 001f1631 | - | 6764.0 | 8577.297 | 2914.605 | 11491.902 | PASS |
| 28 | empty_v2 | warm | 200 | 7e1d3e9f | - | 6908.0 | 8772.836 | 2285.305 | 11058.142 | PASS |
| 29 | empty_v2 | warm | 200 | ee48d242 | - | 7655.0 | 10348.069 | 2505.965 | 12854.034 | PASS |
| 30 | empty_v2 | warm | 200 | 786991a1 | - | 4324.0 | 6192.716 | 3932.715 | 10125.431 | PASS |
| 31 | one_consumption | warm | 200 | 58d100d2 | 7622cbce | 5433.0 | 6961.928 | 3401.506 | 10363.434 | PASS |
| 32 | one_consumption | warm | 200 | a6bf00d5 | 0f24d4c1 | 8439.0 | 10994.454 | 2664.002 | 13658.456 | PASS |
| 33 | one_consumption | warm | 200 | 3d2ef187 | 65bb2329 | 5628.0 | 8675.41 | 2595.58 | 11270.99 | PASS |
| 34 | one_consumption | warm | 200 | 292a7541 | 38733cb0 | 6410.0 | 8213.427 | 3625.148 | 11838.575 | PASS |
| 35 | one_consumption | warm | 200 | 56a738fa | fee90f9f | 5736.0 | 7427.361 | 3337.459 | 10764.82 | PASS |
| 36 | one_consumption | warm | 200 | 0a5eadc5 | 070262e2 | 5051.0 | 6989.857 | 2935.066 | 9924.923 | PASS |
| 37 | one_consumption | warm | 200 | 0790c9ac | 2c597879 | 8761.0 | 10808.585 | 2856.624 | 13665.209 | PASS |
| 38 | one_consumption | warm | 200 | 34cf6a72 | 1bea01fe | 5303.0 | 6927.889 | 3094.959 | 10022.848 | PASS |
| 39 | one_consumption | warm | 200 | 7922ebf1 | 3a4ea2eb | 5711.0 | 7862.11 | 2668.947 | 10531.058 | PASS |
| 40 | one_consumption | warm | 200 | 62eafabd | 62a5a992 | 4547.0 | 6229.52 | 3175.834 | 9405.354 | PASS |
| 41 | one_consumption | warm | 200 | ac703a21 | 3e9b2569 | 5367.0 | 7061.284 | 3358.731 | 10420.015 | PASS |
| 42 | one_consumption | warm | 200 | ae737205 | 20a5b575 | 4374.0 | 6327.816 | 2611.272 | 8939.087 | PASS |
| 43 | one_consumption | warm | 200 | 50994a32 | 95f35ffe | 4831.0 | 6613.237 | 2399.136 | 9012.373 | PASS |
| 44 | one_consumption | warm | 200 | df7e6c68 | e5af419b | 8138.0 | 11412.055 | 3383.747 | 14795.802 | PASS |
| 45 | one_consumption | warm | 200 | a1d8bfd9 | 24c80c78 | 4728.0 | 6168.055 | 2728.773 | 8896.828 | PASS |
| 46 | one_consumption | warm | 200 | e3282ac2 | 6f74d92c | 8420.0 | 10548.009 | 3713.281 | 14261.29 | PASS |
| 47 | one_consumption | warm | 200 | 9bb11762 | fa9c4c2a | 8585.0 | 10489.96 | 2841.8 | 13331.76 | PASS |
| 48 | one_consumption | warm | 200 | 86968cfb | 23abaa2a | 5365.0 | 7041.289 | 3016.847 | 10058.136 | PASS |
| 49 | one_consumption | warm | 200 | 7852bf7a | 66b344ef | 6798.0 | 8661.229 | 4817.791 | 13479.02 | PASS |
| 50 | one_consumption | warm | 200 | 9180080a | 17aa7fb2 | 4934.0 | 6771.266 | 3295.786 | 10067.052 | PASS |
| 51 | one_consumption | cold | 200 | f9797d83 | 2c12aaf0 | 6707.0 | 8456.597 | 3949.503 | 12406.1 | PASS |
| 52 | one_consumption | cold | 200 | 197be014 | aab1e056 | 6392.0 | 9593.646 | 2835.339 | 12428.984 | PASS |
| 53 | one_consumption | cold | 200 | 6976a7d5 | 32e0e745 | 8454.0 | 10360.302 | 2785.376 | 13145.678 | PASS |
| 54 | one_consumption | cold | 200 | 7176c549 | dd5d7dfa | 6218.0 | 8182.879 | 4662.453 | 12845.331 | PASS |
| 55 | one_consumption | cold | 200 | 4930bc93 | d3aaef08 | 5289.0 | 7176.906 | 2455.991 | 9632.897 | PASS |
| 56 | duplicate_seed | warm | 200 | aa0b63fd | ca15a02b | 7006.0 | 8892.453 | 4118.002 | 13010.455 | PASS |
| 57 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 6105.0 | 9388.592 | 2703.517 | 12092.11 | PASS |
| 58 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 5144.0 | 6687.852 | 3544.252 | 10232.104 | PASS |
| 59 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 5100.0 | 6882.076 | 3614.799 | 10496.875 | PASS |
| 60 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4801.0 | 6906.709 | 3277.392 | 10184.101 | PASS |
| 61 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4665.0 | 6556.861 | 2995.01 | 9551.872 | PASS |
| 62 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 5882.0 | 7628.337 | 2666.489 | 10294.826 | PASS |
| 63 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 5061.0 | 7032.83 | 4032.136 | 11064.966 | PASS |
| 64 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4981.0 | 7218.284 | 3643.384 | 10861.668 | PASS |
| 65 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 9307.0 | 11146.526 | 2703.812 | 13850.338 | PASS |
| 66 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 7496.0 | 10107.913 | 2779.834 | 12887.746 | PASS |
| 67 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4282.0 | 6358.301 | 3254.07 | 9612.371 | PASS |
| 68 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 5464.0 | 7431.313 | 3234.26 | 10665.573 | PASS |
| 69 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 3968.0 | 5873.215 | 2454.235 | 8327.45 | PASS |
| 70 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 6834.0 | 8768.154 | 3419.144 | 12187.299 | PASS |
| 71 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 8812.0 | 11645.239 | 2600.673 | 14245.911 | PASS |
| 72 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 9535.0 | 11523.611 | 3145.777 | 14669.388 | PASS |
| 73 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 6741.0 | 8550.568 | 2673.826 | 11224.395 | PASS |
| 74 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 6160.0 | 7802.279 | 3702.853 | 11505.132 | PASS |
| 75 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4061.0 | 5878.388 | 2747.582 | 8625.97 | PASS |
| 76 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 8452.0 | 10550.557 | 3205.247 | 13755.804 | PASS |
| 77 | new_purchase | warm | 200 | 6a26656e | 15e17427 | 7089.0 | 8835.892 | 2748.074 | 11583.967 | PASS |
| 78 | new_purchase | warm | 200 | 997316cf | b82a0a04 | 11555.0 | 13521.45 | 3180.044 | 16701.493 | PASS |
| 79 | new_purchase | warm | 200 | 35b2b33c | 85ad964a | 7929.0 | 9639.123 | 3190.6 | 12829.723 | PASS |
| 80 | new_purchase | warm | 200 | 3d32a780 | 734eacd3 | 6149.0 | 7961.464 | 2695.15 | 10656.614 | PASS |
| 81 | new_purchase | warm | 200 | 8e768c67 | be755b74 | 5956.0 | 8183.51 | 3719.759 | 11903.269 | PASS |
| 82 | mixed_purchase_consumption | warm | 200 | 741f8c18 | e7266fd4, 90475175 | 6981.0 | 8611.206 | 2607.586 | 11218.792 | PASS |
| 83 | mixed_purchase_consumption | warm | 200 | 135d2bc7 | 728d7156, 98a6fe66 | 11237.0 | 13363.17 | 2613.083 | 15976.254 | PASS |
| 84 | mixed_purchase_consumption | warm | 200 | 36ce64e0 | 75c4a50f, b897a6f6 | 6815.0 | 9883.694 | 2735.505 | 12619.199 | PASS |
| 85 | mixed_purchase_consumption | warm | 200 | 6e42606e | e69b8959, 7d599e9c | 7690.0 | 9520.266 | 3476.307 | 12996.573 | PASS |
| 86 | mixed_purchase_consumption | warm | 200 | 5ed1179e | 444d574e, e40ccbce | 7452.0 | 9097.678 | 2337.701 | 11435.379 | PASS |
| 87 | partial_rejection | warm | 200 | 49d2fd52 | 82bbf1fe, 1d1865c7 | 9342.0 | 11199.067 | 5205.962 | 16405.029 | PASS |
| 88 | partial_rejection | warm | 200 | 7c412ef9 | 0c52f997, 20b51a00 | 4952.0 | 6730.663 | 2297.172 | 9027.835 | PASS |
| 89 | partial_rejection | warm | 200 | 9f4e1c88 | 5b22af25, 25c22dc3 | 4826.0 | 6580.408 | 3014.67 | 9595.078 | PASS |
| 90 | partial_rejection | warm | 200 | 7d14c3e2 | 1a47b01c, 728bc1f3 | 4752.0 | 6578.808 | 3748.747 | 10327.556 | PASS |
| 91 | partial_rejection | warm | 200 | a39873a1 | a9ef66f2, 20917ff1 | 4415.0 | 5964.781 | 2705.903 | 8670.684 | PASS |
| 92 | concurrent_identical | warm | 200 | c3de9226 | 8e7f5758 | 8845.0 | 10264.569 | 2596.334 | 12860.903 | PASS |
| 93 | concurrent_identical | warm | 200 | c3de9226 | 8e7f5758 | 5253.0 | 6989.9 | 3511.2 | 10501.1 | PASS |
| 94 | concurrent_identical | warm | 200 | 143c2b9a | 32df983b | 5834.0 | 7361.574 | 2554.181 | 9915.754 | PASS |
| 95 | concurrent_identical | warm | 200 | 143c2b9a | 32df983b | 10893.0 | 12914.404 | 4034.499 | 16948.902 | PASS |
| 96 | concurrent_identical | warm | 200 | d8a3412f | 7aa0e90c | 5192.0 | 6938.55 | 2752.16 | 9690.71 | PASS |
| 97 | concurrent_identical | warm | 200 | d8a3412f | 7aa0e90c | 8465.0 | 10485.947 | 2384.222 | 12870.169 | PASS |

## Concurrent identical pairs

| Pair | Request | Event | Returned statuses | Exactly one commit |
|---:|---|---|---|---|
| 1 | c3de9226 | 8e7f5758 | duplicate, committed | PASS |
| 2 | 143c2b9a | 32df983b | committed, duplicate | PASS |
| 3 | d8a3412f | 7aa0e90c | committed, duplicate | PASS |

## Safety and interpretation

- The harness sent no POST until a read-only GET explicitly returned `environment: SANDBOX` and a products array.
- Every POST also declares `environment: SANDBOX`.
- Product names, prices, potency, and weights returned by GET are not copied into evidence; only the count and chosen identifiers are retained.
- `serverDurationMs` is reported only when the HTTP response exposes it. Client wall-clock time is always measured.
- The combined time is POST wall time plus the immediately following GET wall time.
- These requests mutate the sandbox. Restore the normal fixture after collecting evidence.
