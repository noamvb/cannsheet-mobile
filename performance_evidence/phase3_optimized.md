# Cannsheet backend sync benchmark evidence

- Suite: `optimized`
- Generated (UTC): `2026-07-16T23:58:38+00:00`
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
| concurrent_identical | warm | POST+GET | 6 | 6 | 4779.0 / 5752.0 / 6398.0 / - | - | 6640.105 / 8080.991 / 8450.703 / - | 4429.525 / 4960.584 / 5618.475 / - | 11078.784 / 12729.637 / 13848.282 / - |
| duplicate_retry | warm | POST+GET | 10 | 10 | 3235.0 / 4363.0 / 6640.0 / - | - | 4551.409 / 6186.955 / 8634.959 / - | 4224.327 / 4984.383 / 5926.357 / - | 9774.129 / 10806.367 / 13424.623 / - |
| duplicate_seed | warm | POST+GET | 1 | 1 | 5972.0 / 5972.0 / 5972.0 / - | - | 8131.682 / 8131.682 / 8131.682 / - | 4666.507 / 4666.507 / 4666.507 / - | 12798.189 / 12798.189 / 12798.189 / - |
| empty_v2 | warm | POST+GET | 5 | 5 | 2220.0 / 2982.0 / 3613.0 / - | - | 3548.005 / 4619.968 / 6452.35 / - | 4245.809 / 5621.171 / 7162.754 / - | 8865.776 / 10556.338 / 12073.521 / - |
| get | cold | GET | 5 | 5 | 2564.0 / 2918.0 / 4288.0 / - | 4556.427 / 4817.299 / 6306.054 / - | - | - | - |
| get | warm | GET | 20 | 20 | 2566.0 / 3310.0 / 4264.0 / 4198.0 | 3911.332 / 4998.897 / 7058.805 / 6263.198 | - | - | - |
| mixed_purchase_consumption | warm | POST+GET | 5 | 5 | 6531.0 / 6778.0 / 9441.0 / - | - | 8315.459 / 9239.454 / 11527.621 / - | 4891.105 / 5496.642 / 5946.028 / - | 13822.254 / 14130.558 / 17093.468 / - |
| new_purchase | warm | POST+GET | 5 | 5 | 4636.0 / 6797.0 / 9867.0 / - | - | 6398.947 / 9294.417 / 11919.426 / - | 4011.945 / 4390.158 / 7858.238 / - | 11226.56 / 13306.362 / 18418.617 / - |
| one_consumption | cold | POST+GET | 5 | 5 | 4355.0 / 6136.0 / 7199.0 / - | - | 7694.759 / 7834.938 / 9249.107 / - | 4523.573 / 4592.214 / 5195.207 / - | 12346.425 / 13030.145 / 13772.68 / - |
| one_consumption | warm | POST+GET | 20 | 20 | 3667.0 / 5417.0 / 6434.0 / 6429.0 | - | 5366.719 / 7289.641 / 9321.144 / 8826.468 | 3817.081 / 4871.752 / 7088.284 / 7012.01 | 10892.978 / 12252.782 / 14338.281 / 14174.802 |
| partial_rejection | warm | POST+GET | 5 | 5 | 4982.0 / 5632.0 / 6174.0 / - | - | 6884.06 / 7523.55 / 8092.674 / - | 3889.03 / 4959.813 / 6370.711 / - | 11843.872 / 12280.285 / 13894.261 / - |

## Individual results

Full payloads, responses, checks, and follow-up GET evidence are in the JSON file.

| # | Scenario | Label | HTTP | Request | Item IDs | Server ms | Wall ms | Follow-up GET ms | Combined ms | Check |
|---:|---|---|---:|---|---|---:|---:|---:|---:|---|
| 1 | get | cold | 200 | - | - | 4288.0 | 6306.054 | - | - | PASS |
| 2 | get | cold | 200 | - | - | 2790.0 | 4556.427 | - | - | PASS |
| 3 | get | cold | 200 | - | - | 2918.0 | 4642.443 | - | - | PASS |
| 4 | get | cold | 200 | - | - | 3097.0 | 4817.299 | - | - | PASS |
| 5 | get | cold | 200 | - | - | 2564.0 | 5334.965 | - | - | PASS |
| 6 | get | warm | 200 | - | - | 2629.0 | 4084.374 | - | - | PASS |
| 7 | get | warm | 200 | - | - | 3045.0 | 5080.994 | - | - | PASS |
| 8 | get | warm | 200 | - | - | 4198.0 | 6263.198 | - | - | PASS |
| 9 | get | warm | 200 | - | - | 3650.0 | 5819.304 | - | - | PASS |
| 10 | get | warm | 200 | - | - | 2566.0 | 4165.043 | - | - | PASS |
| 11 | get | warm | 200 | - | - | 3580.0 | 5397.098 | - | - | PASS |
| 12 | get | warm | 200 | - | - | 2872.0 | 4570.488 | - | - | PASS |
| 13 | get | warm | 200 | - | - | 2799.0 | 4173.772 | - | - | PASS |
| 14 | get | warm | 200 | - | - | 4264.0 | 5639.79 | - | - | PASS |
| 15 | get | warm | 200 | - | - | 3727.0 | 5502.179 | - | - | PASS |
| 16 | get | warm | 200 | - | - | 2604.0 | 3911.332 | - | - | PASS |
| 17 | get | warm | 200 | - | - | 3439.0 | 4983.949 | - | - | PASS |
| 18 | get | warm | 200 | - | - | 3415.0 | 5177.057 | - | - | PASS |
| 19 | get | warm | 200 | - | - | 3207.0 | 5096.912 | - | - | PASS |
| 20 | get | warm | 200 | - | - | 3364.0 | 5013.846 | - | - | PASS |
| 21 | get | warm | 200 | - | - | 3580.0 | 7058.805 | - | - | PASS |
| 22 | get | warm | 200 | - | - | 2601.0 | 4074.98 | - | - | PASS |
| 23 | get | warm | 200 | - | - | 3473.0 | 4818.084 | - | - | PASS |
| 24 | get | warm | 200 | - | - | 3256.0 | 4900.829 | - | - | PASS |
| 25 | get | warm | 200 | - | - | 2913.0 | 4455.134 | - | - | PASS |
| 26 | empty_v2 | warm | 200 | bf83f8f4 | - | 3441.0 | 6452.35 | 5621.171 | 12073.521 | PASS |
| 27 | empty_v2 | warm | 200 | 001f1631 | - | 3613.0 | 5592.064 | 4964.274 | 10556.338 | PASS |
| 28 | empty_v2 | warm | 200 | 7e1d3e9f | - | 2605.0 | 4307.451 | 5817.461 | 10124.912 | PASS |
| 29 | empty_v2 | warm | 200 | ee48d242 | - | 2982.0 | 4619.968 | 4245.809 | 8865.776 | PASS |
| 30 | empty_v2 | warm | 200 | 786991a1 | - | 2220.0 | 3548.005 | 7162.754 | 10710.759 | PASS |
| 31 | one_consumption | warm | 200 | 58d100d2 | 7622cbce | 3667.0 | 5366.719 | 5755.348 | 11122.067 | PASS |
| 32 | one_consumption | warm | 200 | a6bf00d5 | 0f24d4c1 | 5473.0 | 7267.059 | 5021.631 | 12288.69 | PASS |
| 33 | one_consumption | warm | 200 | 3d2ef187 | 65bb2329 | 4763.0 | 6444.257 | 7088.284 | 13532.541 | PASS |
| 34 | one_consumption | warm | 200 | 292a7541 | 38733cb0 | 5389.0 | 7153.52 | 4625.832 | 11779.353 | PASS |
| 35 | one_consumption | warm | 200 | 56a738fa | fee90f9f | 6067.0 | 7864.276 | 5994.455 | 13858.731 | PASS |
| 36 | one_consumption | warm | 200 | 0a5eadc5 | 070262e2 | 5830.0 | 7637.828 | 5810.421 | 13448.249 | PASS |
| 37 | one_consumption | warm | 200 | 0790c9ac | 2c597879 | 5445.0 | 7143.474 | 3817.081 | 10960.555 | PASS |
| 38 | one_consumption | warm | 200 | 34cf6a72 | 1bea01fe | 5103.0 | 7326.271 | 7012.01 | 14338.281 | PASS |
| 39 | one_consumption | warm | 200 | 7922ebf1 | 3a4ea2eb | 5525.0 | 7312.224 | 4904.65 | 12216.874 | PASS |
| 40 | one_consumption | warm | 200 | 62eafabd | 62a5a992 | 4369.0 | 6172.82 | 4720.157 | 10892.978 | PASS |
| 41 | one_consumption | warm | 200 | ac703a21 | 3e9b2569 | 5057.0 | 6581.576 | 5042.36 | 11623.937 | PASS |
| 42 | one_consumption | warm | 200 | ae737205 | 20a5b575 | 5343.0 | 7230.293 | 4060.723 | 11291.016 | PASS |
| 43 | one_consumption | warm | 200 | 50994a32 | 95f35ffe | 6372.0 | 9321.144 | 4048.923 | 13370.067 | PASS |
| 44 | one_consumption | warm | 200 | df7e6c68 | e5af419b | 6429.0 | 8303.892 | 4838.854 | 13142.746 | PASS |
| 45 | one_consumption | warm | 200 | a1d8bfd9 | 24c80c78 | 4436.0 | 6146.047 | 5219.885 | 11365.932 | PASS |
| 46 | one_consumption | warm | 200 | e3282ac2 | 6f74d92c | 6270.0 | 8230.461 | 4779.36 | 13009.821 | PASS |
| 47 | one_consumption | warm | 200 | 9bb11762 | fa9c4c2a | 6434.0 | 8826.468 | 4631.495 | 13457.963 | PASS |
| 48 | one_consumption | warm | 200 | 86968cfb | 23abaa2a | 5573.0 | 7538.965 | 4244.354 | 11783.319 | PASS |
| 49 | one_consumption | warm | 200 | 7852bf7a | 66b344ef | 4811.0 | 7620.78 | 6554.022 | 14174.802 | PASS |
| 50 | one_consumption | warm | 200 | 9180080a | 17aa7fb2 | 5075.0 | 6946.846 | 4506.333 | 11453.179 | PASS |
| 51 | one_consumption | cold | 200 | f9797d83 | 2c12aaf0 | 6422.0 | 9053.362 | 4592.214 | 13645.576 | PASS |
| 52 | one_consumption | cold | 200 | 197be014 | aab1e056 | 6136.0 | 7834.938 | 5195.207 | 13030.145 | PASS |
| 53 | one_consumption | cold | 200 | 6976a7d5 | 32e0e745 | 4355.0 | 7694.759 | 4844.507 | 12539.266 | PASS |
| 54 | one_consumption | cold | 200 | 7176c549 | dd5d7dfa | 7199.0 | 9249.107 | 4523.573 | 13772.68 | PASS |
| 55 | one_consumption | cold | 200 | 4930bc93 | d3aaef08 | 5685.0 | 7760.698 | 4585.728 | 12346.425 | PASS |
| 56 | duplicate_seed | warm | 200 | aa0b63fd | ca15a02b | 5972.0 | 8131.682 | 4666.507 | 12798.189 | PASS |
| 57 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 3846.0 | 5823.975 | 4224.327 | 10048.302 | PASS |
| 58 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 5185.0 | 7264.533 | 5926.357 | 13190.89 | PASS |
| 59 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 3371.0 | 4972.378 | 4971.034 | 9943.412 | PASS |
| 60 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 3436.0 | 5442.635 | 4997.732 | 10440.366 | PASS |
| 61 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4880.0 | 7348.52 | 5161.483 | 12510.002 | PASS |
| 62 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4966.0 | 6773.625 | 5804.45 | 12578.075 | PASS |
| 63 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 3235.0 | 4551.409 | 5222.72 | 9774.129 | PASS |
| 64 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 3605.0 | 5312.143 | 4643.713 | 9955.856 | PASS |
| 65 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 4911.0 | 6549.935 | 4622.432 | 11172.367 | PASS |
| 66 | duplicate_retry | warm | 200 | aa0b63fd | ca15a02b | 6640.0 | 8634.959 | 4789.663 | 13424.623 | PASS |
| 67 | new_purchase | warm | 200 | 6a26656e | 15e17427 | 6797.0 | 9294.417 | 4011.945 | 13306.362 | PASS |
| 68 | new_purchase | warm | 200 | 997316cf | b82a0a04 | 6716.0 | 8697.075 | 4173.909 | 12870.984 | PASS |
| 69 | new_purchase | warm | 200 | 35b2b33c | 85ad964a | 4636.0 | 6398.947 | 4827.613 | 11226.56 | PASS |
| 70 | new_purchase | warm | 200 | 3d32a780 | 734eacd3 | 8658.0 | 10560.379 | 7858.238 | 18418.617 | PASS |
| 71 | new_purchase | warm | 200 | 8e768c67 | be755b74 | 9867.0 | 11919.426 | 4390.158 | 16309.585 | PASS |
| 72 | mixed_purchase_consumption | warm | 200 | 741f8c18 | e7266fd4, 90475175 | 8227.0 | 11147.44 | 5946.028 | 17093.468 | PASS |
| 73 | mixed_purchase_consumption | warm | 200 | 135d2bc7 | 728d7156, 98a6fe66 | 6551.0 | 8315.459 | 5650.785 | 13966.243 | PASS |
| 74 | mixed_purchase_consumption | warm | 200 | 36ce64e0 | 75c4a50f, b897a6f6 | 6531.0 | 8325.612 | 5496.642 | 13822.254 | PASS |
| 75 | mixed_purchase_consumption | warm | 200 | 6e42606e | e69b8959, 7d599e9c | 9441.0 | 11527.621 | 4940.206 | 16467.827 | PASS |
| 76 | mixed_purchase_consumption | warm | 200 | 5ed1179e | 444d574e, e40ccbce | 6778.0 | 9239.454 | 4891.105 | 14130.558 | PASS |
| 77 | partial_rejection | warm | 200 | 49d2fd52 | 82bbf1fe, 1d1865c7 | 4982.0 | 6884.06 | 4959.813 | 11843.872 | PASS |
| 78 | partial_rejection | warm | 200 | 7c412ef9 | 0c52f997, 20b51a00 | 6174.0 | 8092.674 | 3889.03 | 11981.704 | PASS |
| 79 | partial_rejection | warm | 200 | 9f4e1c88 | 5b22af25, 25c22dc3 | 5998.0 | 8061.786 | 4218.499 | 12280.285 | PASS |
| 80 | partial_rejection | warm | 200 | 7d14c3e2 | 1a47b01c, 728bc1f3 | 5623.0 | 7485.965 | 5851.283 | 13337.248 | PASS |
| 81 | partial_rejection | warm | 200 | a39873a1 | a9ef66f2, 20917ff1 | 5632.0 | 7523.55 | 6370.711 | 13894.261 | PASS |
| 82 | concurrent_identical | warm | 200 | c3de9226 | 8e7f5758 | 6142.0 | 8364.71 | 4429.525 | 12794.235 | PASS |
| 83 | concurrent_identical | warm | 200 | c3de9226 | 8e7f5758 | 4779.0 | 6640.105 | 4438.679 | 11078.784 | PASS |
| 84 | concurrent_identical | warm | 200 | 143c2b9a | 32df983b | 5362.0 | 7932.176 | 4732.863 | 12665.039 | PASS |
| 85 | concurrent_identical | warm | 200 | 143c2b9a | 32df983b | 6398.0 | 8450.703 | 5271.526 | 13722.229 | PASS |
| 86 | concurrent_identical | warm | 200 | d8a3412f | 7aa0e90c | 6143.0 | 8229.806 | 5618.475 | 13848.282 | PASS |
| 87 | concurrent_identical | warm | 200 | d8a3412f | 7aa0e90c | 5273.0 | 7123.338 | 5188.305 | 12311.643 | PASS |

## Concurrent identical pairs

| Pair | Request | Event | Returned statuses | Exactly one commit |
|---:|---|---|---|---|
| 1 | c3de9226 | 8e7f5758 | duplicate, committed | PASS |
| 2 | 143c2b9a | 32df983b | committed, duplicate | PASS |
| 3 | d8a3412f | 7aa0e90c | duplicate, committed | PASS |

## Safety and interpretation

- The harness sent no POST until a read-only GET explicitly returned `environment: SANDBOX` and a products array.
- Every POST also declares `environment: SANDBOX`.
- Product names, prices, potency, and weights returned by GET are not copied into evidence; only the count and chosen identifiers are retained.
- `serverDurationMs` is reported only when the HTTP response exposes it. Client wall-clock time is always measured.
- The combined time is POST wall time plus the immediately following GET wall time.
- These requests mutate the sandbox. Restore the normal fixture after collecting evidence.
