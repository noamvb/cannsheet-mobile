# Phase 3 Event UUID lookup benchmark

This read-only benchmark ran in the live Cannsheet sandbox against 3,601 canonical
event rows. It compared two duplicate-detection strategies on the same data:

- one read of only the Event UUID column into a Set; and
- exact whole-cell TextFinder searches restricted to that column.

Each batch size had one first-measured run after a scheduled 35-second idle and
five repeated warm runs. The idle period does not prove that Google created a new
server instance. All strategies returned the expected UUIDs, and the row count and
UUID checksum (`dd087577`) were unchanged afterward.

| Batch | First Set | First TextFinder | Warm Set min / median / max | Warm TextFinder min / median / max |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 562 ms | 103 ms | 437 / 491 / 510 ms | 98 / 142 / 215 ms |
| 5 | 432 ms | 585 ms | 439 / 575 / 784 ms | 506 / 548 / 753 ms |
| 10 | 459 ms | 1,046 ms | 418 / 460 / 567 ms | 979 / 1,111 / 2,057 ms |
| 20 | 502 ms | 3,209 ms | 424 / 461 / 975 ms | 2,468 / 2,777 / 3,228 ms |

Decision: use exact TextFinder for submitted batches of at most five UUIDs and a
single-column Set for larger batches or full maintenance scans. TextFinder is much
faster for the normal one-item sync, the approaches are effectively tied at five,
and the one-column Set clearly wins at ten and twenty. This keeps
`ConsumptionEvents` authoritative and avoids adding a new durable index.
