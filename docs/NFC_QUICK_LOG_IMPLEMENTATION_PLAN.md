# NFC Quick-Log Tags for Cannsheet Mobile

Status: approved design and implementation specification, 2026-08-21.

Implementation status (working branches `codex/nfc-deferred-core` and
`codex/nfc-quick-log-tags`, with the latter currently checked out, 2026-08-21): the
v3 direct-uses outbox, exact protocol/parser, fail-closed registry, dedicated
scan/result and writer activities, Settings integration, optional manifest
feature, surface routing, backup comments, and focused tests are implemented in
the shared workspace. The full serialized local Android static gate and all
checked-in backend suites have passed. PR creation/merge, exact remote-main
proof, Android emulator execution, the owner-approved Samsung feasibility probe,
physical sandbox RF/write evidence, signed publication, and Obtainium handoff
remain unperformed. No production NFC tag has been written or tapped.

This document is the durable build specification for the NFC quick-log feature. The
initial request described a “10 second” tag; the approved contract supersedes that
wording. Tags carry a whole number of uses (1 through 10), and no seconds conversion
or seconds-per-use rate is consulted by the NFC path.

## Outcome and invariants

Settings can create and manage multiple registered, rewritable NFC tags. A tag is
bound to a random per-tag UUID and a configured whole quantity, not to a product. At
tap time the existing Pen loaded-cart resolver is used: a valid explicit loaded Pen
ID wins, otherwise the most recently logged selectable Pen is used. The resolved
product, UUID, quantity, event UUID, and local date/time are captured before the
five-second durable Undo window. Delayed commit uses the captured product and
`updateLoadedCart = false`.

Only `uses` enter `ConsumptionAction`, Room, the offline queue, Apps Script, or
Sheets. NFC payloads contain only protocol version, tag UUID, and uses. Labels,
products, dates, endpoints, seconds, and event IDs never enter the tag. Every
intentional presentation receives a fresh event ID; retries preserve that ID.

The physical tag is an allowlisted convenience capability, not cryptographic
authentication. A clone with the same UUID and quantity is accepted by design.
Registry corruption fails closed. A successful physical write is not activated until
the exact bytes are read back and the registry mutation succeeds. A foreign message
requires inspection, explicit overwrite confirmation, and a second presentation.
Blank `NdefFormatable` tags require a verification retap. No read-only operation or
tag erasure is included.

The app remains installable on devices without NFC. The desired Samsung SM-F966W
screen-off/locked behavior is a bounded physical probe result, not a general Android
promise. If ordinary settings cannot deliver it, unlocked operation is the release
fallback.

## Durable tag protocol (v1)

Write exactly two NDEF records in this order:

1. `NdefRecord.createExternal("com.noamv.cannsheet.mobile", "pen-quick-log", payload)`.
2. `NdefRecord.createApplicationRecord(context.packageName)`.

The first record has an empty ID and exactly 18 payload bytes:

| Offset | Length | Meaning |
| ---: | ---: | --- |
| 0 | 1 | Protocol version `0x01` |
| 1 | 16 | Random RFC-4122 UUID in network/big-endian byte order |
| 17 | 1 | Unsigned uses value in `1..10` |

The canonical dispatch URI is
`vnd.android.nfc://ext/com.noamv.cannsheet.mobile:pen-quick-log`. The AAR keeps
the installed application as the preferred receiver and naturally isolates the
production and `.sandbox` package registries. The parser accepts only
`ACTION_NDEF_DISCOVERED`, the exact URI, one message, two records, exact record
order/TNF/type/ID/payload, and the exact running-package AAR. It rejects malformed
parcelables, extra records/messages, wrong packages, unknown versions, unregistered
UUIDs, and registry quantity mismatches.

The serialized message size estimate is non-authoritative; capacity checks use
`NdefMessage.toByteArray().size`.

## Persistence

`nfc_quick_log_registry` is a Preferences DataStore containing one versioned JSON
value:

```kotlin
data class RegisteredNfcQuickLogTag(
    val tagId: UUID,
    val uses: Int,
    val label: String?,
    val registeredAtEpochMillis: Long,
)

data class NfcQuickLogRegistryPayload(
    val version: Int = 1,
    val tags: List<RegisteredNfcQuickLogTag>,
)
```

There are at most 50 entries. UUIDs are canonical and unique; uses are `1..10`;
labels are trimmed, blank-to-null, and at most 40 Unicode code points;
`registeredAtEpochMillis` is nonnegative. Mutations use one atomic full-map
`DataStore.edit`. Corruption exposes an error and no tags, with a confirmed reset
that removes only local authorization/labels. Register/adopt/rewrite/repair happen
only after exact physical verification; revoke leaves physical bytes untouched.

The registry is included in cloud/device backup. Room, `sync_preferences`, and the
transient `pen_widget_state` outbox remain excluded.

## PR 1: durable direct-uses outbox

Branch: `codex/nfc-deferred-core`, from freshly verified `origin/main`. This PR has
no visible NFC UI, manifest entry, version change, endpoint change, Room migration,
or backend change.

The existing per-surface DataStore keys and WorkManager names remain unchanged. The
payload becomes version 3 while decoding valid v1/v2:

```kotlin
enum class DeferredPenInputKind { DURATION_SECONDS, DIRECT_USES }

data class PenWidgetCommitPayload(
    val version: Int,
    val commitId: String,
    val eventId: String,
    val submittedAtEpochMillis: Long,
    val commitAtEpochMillis: Long,
    val claimId: String?,
    val claimedAtEpochMillis: Long?,
    val productId: String,
    val productUuid: String?,
    val inputKind: DeferredPenInputKind,
    val seconds: Int?,
    val secondsPerUse: Double?,
    val restoreDraftSeconds: Int?,
    val uses: Double,
    val date: String,
    val time: String,
)
```

Duration v1/v2 payloads migrate deterministically to v3. Direct payloads have all
duration fields null. Unknown or malformed raw state is preserved for diagnosis and
never silently deleted. `submitDirectCommit(surfaceId, builder)` atomically refuses
to replace a valid pending payload and never touches a draft. Undo removes a direct
payload without creating a draft; duration Undo restores the exact seconds. Claim,
Room write, completion, retry, stable event IDs, and acknowledgement-only sync remain
the existing boundaries.

Extract a process-safe `PenQuickLogDataSource` that reads the graph and delegates the
existing explicit-then-most-recent resolver. NFC must not call the cold-start-sensitive
`CannsheetViewModel.quickLogPen`.

PR 1 tests cover v1/v2/v3 codec compatibility, direct validation/Undo, claim races,
Room failure retention, completion ordering, startup flush, and all existing widget,
tile, and multi-cart behavior. Merge only after local checks, PR checks, and the
exact merged-main API 24/API 36 run succeed.

## PR 2: NFC feature

Branch: `codex/nfc-quick-log-tags`, from exact merged PR-1 main. Add a focused
`com.example.nfc` package with contract, registry, coordinator, presentation gate,
scan/result activity, writer, writer activity, and Settings section.

Manifest changes are narrowly scoped:

- `android.permission.NFC`;
- optional `android.hardware.nfc` feature (`required="false"`);
- exported `singleTop`, `noHistory`, `excludeFromRecents` NFC handler with one exact
  `ACTION_NDEF_DISCOVERED` filter for the external URI;
- non-exported writer activity with no filter.

The handler consumes both cold and warm intents through one serialized path, clears
the original intent immediately, parses and validates the exact contract, checks the
private registry, loads current Pen state through the shared data source, captures a
fresh event ID and tap time, writes a direct-uses pending payload, and schedules the
fixed five-second timer plus WorkManager backstop. It ignores the rate and never
updates the loaded-cart preference. The NFC surface ID is
`Int.MAX_VALUE - 1`; the existing tile ID `Int.MAX_VALUE` is unchanged and all real
widget IDs remain independent.

Continuous presentation is gated by logical tag UUID and `NfcAdapter.ignore`/
`OnTagRemovedListener` where available. Re-presentation after removal creates a new
event. A pending NFC payload is never overwritten: a normal intentional retap first
force-commits the old payload, then stages the new one; a live claim or failed first
write rejects the second with Retry.

The lock-safe result activity shows generic quantity only while locked, with Undo and
Submit now. After unlock it may show quantity and cart name. It never opens the full
Log screen over the keyguard. Failure states remain actionable; no-cart guidance opens
the existing cart picker only after unlock and requires a fresh tap.

Settings exposes hardware/NFC/Android-16 Launch-via-NFC state, current resolver
source, registered tags, labels, quantity, Verify, Adopt, Rename, Rewrite, Revoke,
Reset-corrupt-registry, and a focused writer. Writing is allowed without a current
cart but explains that tags resolve dynamically later. Writer reader mode uses A/B/F/V
without `FLAG_READER_SKIP_NDEF_CHECK`; all I/O is off the main thread, tags stay
rewritable, foreign content requires confirmation and retap, and formatable tags need
verification retap before activation.

## Phase 0 physical feasibility probe

Before PR 1, use an isolated temporary sandbox package with no Room, network, or
production manifest changes. With owner-approved ADB boundaries and at least three
rewritable tags (144+ bytes), test unlocked cold/warm, awake lock screen, screen-off
lock, hold/wobble, remove/retap, Android-16 Launch-via-NFC allow/disallow, and any
ordinary Secure NFC setting one at a time. Restore settings, remove the probe, stop
ADB, and report exactly what was observed. Never use root, accessibility, hidden APIs,
device-owner APIs, or a permanent security weakening.

## Tests and evidence

Pure/JVM tests cover protocol bytes, parser, registry, coordinator, presentation
gate, outbox arbitration, and writer fakes. Instrumentation covers real framework
NDEF serialization, manifest resolution, typed Parcelable extraction on API 24–36,
Compose state/privacy/accessibility, backup XML, recreation, and optional hardware
metadata. Emulator results do not prove RF behavior. Physical sandbox evidence is
kept separate from synthetic tests, signed APK verification, Obtainium state, and
production behavior.

The full local gate is:

```bash
./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug
node tests/backend_analytics_test.js
node tests/backend_contract_test.js
node tests/backend_corrections_test.js
node tests/backend_recovery_test.js
node tests/backend_spreadsheet_test.js
node tests/fake_sheets_batch_update_test.js
node tests/sandbox_performance_fixture_test.js
node tests/sandbox_provisioning_test.js
python3 -m unittest tests/test_backend_sync_benchmark.py
git diff --check
```

Physical sandbox acceptance covers 1-use and 3-use writes, readback, rename/rewrite/
revoke/adopt, foreign overwrite, blank formatting, exact Room uses, dynamic cart
resolution, no-cart guidance, Undo, Submit now, rapid remove-and-retap, process death,
offline persistence, launch-disabled behavior, and lock/privacy copy. No production
tag is written or tapped.

## Documentation and release

Update `docs/ARCHITECTURE.md`, `docs/DECISIONS.md` (ADR-044),
`docs/PROJECT_STATE.md`, `docs/HANDOFF.md`, `AGENTS.md`, and both backup-policy XML
comments. Keep labels, UUIDs, private devices, credentials, endpoints, and production
data out of public documentation.

After PR 2 and exact merged-main validation, open a version-only PR. If v1.5.2/code44
is still current, use `1.6.0`/code `45`; otherwise stop and recompute with the owner.
The release workflow requires an exact tagged-main push run with all six jobs green,
owner confirmation immediately before the irreversible tag push, signed APK
publication, independent checksum/alignment/metadata/signer verification, and owner
Obtainium handoff. Do not install locally or claim production NFC behavior.

## Parallelization

The primary agent owns device/ADB work, deferred-core integration, manifest, graph and
surface routing, cross-package APIs, docs, full diff review, physical evidence,
GitHub/release actions, and the final acceptance gate. After interfaces are frozen,
contract/registry, writer/Settings, and scan/result work may be assigned to
non-overlapping subagents. Read-only reviewers should separately audit exported-intent
security, queue durability, registry/write atomicity, accessibility/privacy, and
release evidence. No subagent may touch the phone, overwrite tags, push a tag, publish,
or mutate GitHub state.

## Definition of done

The feature is complete only when the Samsung probe contract is recorded; PR 1 and PR
2 pass their exact-main gates; v1 tags, registry matching, dynamic cart resolution,
five-second direct Undo, rapid retap, cold/warm/process-death recovery, privacy and
all actionable failures are covered; local/backend/CI and bounded physical sandbox
evidence are complete; the final diff is free of probes/secrets/production changes;
and the staged version-only release, signed artifact verification, Obtainium handoff,
and explicit no-production-action statement are documented.
