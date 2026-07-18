/**
 * SANDBOX-ONLY performance fixture utilities.
 *
 * This file is intentionally separate from the deployable backend. Add it only
 * to the sandbox Apps Script project. The public functions below are manual
 * editor entrypoints and must never be called by doGet(), doPost(), or a trigger.
 *
 * Rerun seedSandboxPerformanceFixture() to replace the performance data with
 * the same deterministic rows. Run the existing resetSandboxData() editor
 * utility to restore the normal six-product sandbox fixture afterward.
 */

const SANDBOX_PERFORMANCE_FIXTURE = Object.freeze({
  version: 'phase-4-v1',
  purchaseCount: 400,
  interactedPurchaseCount: 360,
  eventCount: 3600,
  startEpochMillis: Date.UTC(2025, 0, 1, 8, 0, 0),
  eventStepMinutes: 13,
  source: 'SANDBOX_PERFORMANCE_FIXTURE',
  productTypes: Object.freeze(['P', 'E', 'J', 'F', 'S', 'K']),
  quantities: Object.freeze([0.25, 0.5, 0.75, 1, 1.25]),
  grams: Object.freeze([0.5, 1, 2, 3.5, 7]),
  weightCodes: Object.freeze(['A', 'B', 'C', 'D', ''])
});

const SANDBOX_UUID_LOOKUP_BENCHMARK = Object.freeze({
  version: 'phase-3-v1',
  minimumEventRows: 3600,
  runsPerBatch: 6,
  batchSizes: Object.freeze([1, 5, 10, 20])
});

// No-argument editor entrypoints for Apps Script's function selector.
function sandboxFaultAfterCompatibility() {
  return setSandboxSyncApplyFault(CANN.SYNC_APPLY_FAULTS.COMPATIBILITY);
}

function sandboxFaultAfterCanonical() {
  return setSandboxSyncApplyFault(CANN.SYNC_APPLY_FAULTS.CANONICAL);
}

function sandboxFaultAfterProductEffects() {
  return setSandboxSyncApplyFault(CANN.SYNC_APPLY_FAULTS.PRODUCT_EFFECTS);
}

function sandboxFaultAfterInteractionSummary() {
  return setSandboxSyncApplyFault(
    CANN.SYNC_APPLY_FAULTS.INTERACTION_SUMMARY
  );
}

function sandboxFaultAfterCoreCommit() {
  return setSandboxSyncApplyFault(CANN.SYNC_APPLY_FAULTS.CORE_COMMITTED);
}

function sandboxFaultBeforeFinalLedger() {
  return setSandboxSyncApplyFault(CANN.SYNC_APPLY_FAULTS.LEDGER);
}

function sandboxFaultAfterComplete() {
  return setSandboxSyncApplyFault(CANN.SYNC_APPLY_FAULTS.POST_COMPLETE);
}

function sandboxClearSyncApplyFault() {
  return clearSandboxSyncApplyFault();
}

function sandboxRepairSyncApply() {
  return repairRecoverableSyncApply();
}

/** Manual Apps Script editor entrypoint. Never call from an HTTP handler. */
function seedSandboxPerformanceFixture() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('SANDBOX_PERFORMANCE_LOCK_TIMEOUT');
  try {
    const guarded = sandboxPerformanceGuard_();
    const fixture = sandboxPerformanceBuildFixture_();
    sandboxPerformanceAssertGeneratedFixture_(fixture);

    const ss = guarded.ss;
    const purchases = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
    const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
    const events = requiredSheet_(ss, CANN.SHEETS.EVENTS);
    const ledger = requiredSheet_(ss, CANN.SHEETS.LEDGER);
    const migrationReport = requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT);
    const applyJournal = ss.getSheetByName(CANN.SHEETS.APPLY_JOURNAL);

    ensureHeaders_(purchases, CANN.PURCHASE_HEADERS);
    ensureHeaders_(events, CANN.EVENT_HEADERS);
    const responseHeaders = headerMap_(responses);
    requireHeaders_(responseHeaders, ['Timestamp', 'Product', 'Uses']);
    const responseWidth = responses.getLastColumn();
    const responseRows = sandboxPerformanceBuildResponseRows_(fixture.events, responseHeaders, responseWidth);

    // The guard completes every safety read before the first destructive action.
    guarded.form.deleteAllResponses();
    sandboxPerformanceReplaceRows_(purchases, fixture.purchases, CANN.PURCHASE_HEADERS.length);
    sandboxPerformanceReplaceRows_(responses, responseRows, responseWidth);
    sandboxPerformanceReplaceRows_(events, fixture.events, CANN.EVENT_HEADERS.length);
    sandboxPerformanceReplaceRows_(ledger, [], ledger.getLastColumn());
    sandboxPerformanceReplaceRows_(migrationReport, [], migrationReport.getLastColumn());
    if (applyJournal) {
      sandboxPerformanceReplaceRows_(
        applyJournal,
        [],
        applyJournal.getLastColumn()
      );
    }
    if (configValue_(ss, CANN.PENDING_APPLY_KEY, null) != null) {
      setConfigValue_(
        ss,
        CANN.PENDING_APPLY_KEY,
        '',
        'Apply UUID awaiting finalization'
      );
    }
    PropertiesService.getScriptProperties().deleteProperty(
      CANN.SANDBOX_FAULT_PROPERTY
    );
    applySheetSafety_(ss);
    updateFormAndDescriptionLocked_(ss);
    SpreadsheetApp.flush();

    const metrics = sandboxPerformanceMetrics_(ss);
    sandboxPerformanceAssertStoredFixture_(metrics, fixture.expected);
    console.log(JSON.stringify({
      type: 'sandbox_performance_fixture_seeded',
      fixtureVersion: SANDBOX_PERFORMANCE_FIXTURE.version,
      metrics: metrics,
      restoreWith: 'resetSandboxData'
    }));
    return Object.assign({}, metrics, { restoreWith: 'resetSandboxData' });
  } finally {
    lock.releaseLock();
  }
}

/** Read-only manual editor entrypoint for checking the seeded row counts. */
function verifySandboxPerformanceFixture() {
  const guarded = sandboxPerformanceGuard_();
  const expected = sandboxPerformanceBuildFixture_().expected;
  const metrics = sandboxPerformanceMetrics_(guarded.ss);
  sandboxPerformanceAssertStoredFixture_(metrics, expected);
  return Object.assign({}, metrics, { restoreWith: 'resetSandboxData' });
}

/** Read-only manual helper for opening the real sandbox responder form. */
function inspectSandboxPerformanceForm() {
  const guarded = sandboxPerformanceGuard_();
  const result = {
    formId: guarded.form.getId(),
    destinationId: guarded.form.getDestinationId(),
    publishedUrl: guarded.form.getPublishedUrl(),
    acceptingResponses: guarded.form.isAcceptingResponses()
  };
  console.log(JSON.stringify(result));
  return result;
}

/**
 * Read-only, SANDBOX-only comparison of two Event UUID lookup strategies.
 *
 * Run benchmarkSandboxEventUuidLookup() manually in the sandbox Apps Script
 * editor after seeding at least 3,600 events. It compares:
 *   1. one batched read of the Event UUID column followed by a JavaScript Set;
 *   2. exact whole-cell TextFinder searches restricted to that same range.
 *
 * The first measured run for each batch is labelled separately. The remaining
 * five runs are warm repeats. Fixture-validation reads happen before timing, so
 * "first-measured" must not be interpreted as a guaranteed cold server start.
 */
function benchmarkSandboxEventUuidLookup() {
  const guarded = sandboxPerformanceGuard_();
  const events = requiredSheet_(guarded.ss, CANN.SHEETS.EVENTS);
  const headers = headerMap_(events);
  requireHeaders_(headers, ['Event UUID']);

  const eventUuidColumn = headers['Event UUID'] + 1;
  const eventRowCount = Math.max(0, events.getLastRow() - 1);
  if (eventRowCount < SANDBOX_UUID_LOOKUP_BENCHMARK.minimumEventRows) {
    throw new Error('SANDBOX_UUID_LOOKUP_BENCHMARK_REQUIRES_3600_EVENTS: found ' + eventRowCount);
  }

  // Exactly one column, excluding the header. All TextFinder calls below are
  // created from this range, so they cannot search timestamps or other fields.
  const uuidRange = events.getRange(2, eventUuidColumn, eventRowCount, 1);
  const beforeUuids = uuidRange.getValues().map(row => text_(row[0]));
  if (beforeUuids.some(uuid => !uuid)) {
    throw new Error('SANDBOX_UUID_LOOKUP_BENCHMARK_BLANK_UUID');
  }
  const existingUuidSet = new Set(beforeUuids);
  if (existingUuidSet.size !== beforeUuids.length) {
    throw new Error('SANDBOX_UUID_LOOKUP_BENCHMARK_DUPLICATE_UUID');
  }

  const beforeChecksum = sandboxPerformanceLookupChecksum_(
    beforeUuids,
    beforeUuids.map(() => true)
  );
  const cases = SANDBOX_UUID_LOOKUP_BENCHMARK.batchSizes.map(batchSize => {
    const batch = sandboxPerformanceBuildUuidLookupBatch_(beforeUuids, existingUuidSet, batchSize);
    const expectedChecksum = sandboxPerformanceLookupChecksum_(batch.submittedUuids, batch.expectedFound);
    const runs = [];

    for (let runIndex = 0; runIndex < SANDBOX_UUID_LOOKUP_BENCHMARK.runsPerBatch; runIndex++) {
      let columnReadSet;
      let exactTextFinder;
      const order = runIndex % 2 === 0
        ? ['columnReadSet', 'exactTextFinder']
        : ['exactTextFinder', 'columnReadSet'];

      // Alternate order so neither strategy always receives the second-run
      // advantage within a pair.
      if (order[0] === 'columnReadSet') {
        columnReadSet = sandboxPerformanceMeasureColumnSetLookup_(uuidRange, batch.submittedUuids);
        exactTextFinder = sandboxPerformanceMeasureExactTextFinderLookup_(uuidRange, batch.submittedUuids);
      } else {
        exactTextFinder = sandboxPerformanceMeasureExactTextFinderLookup_(uuidRange, batch.submittedUuids);
        columnReadSet = sandboxPerformanceMeasureColumnSetLookup_(uuidRange, batch.submittedUuids);
      }

      const equal = sandboxPerformanceLookupArraysEqual_(columnReadSet.found, exactTextFinder.found);
      const matchesExpected = sandboxPerformanceLookupArraysEqual_(columnReadSet.found, batch.expectedFound) &&
        sandboxPerformanceLookupArraysEqual_(exactTextFinder.found, batch.expectedFound) &&
        columnReadSet.checksum === expectedChecksum &&
        exactTextFinder.checksum === expectedChecksum;
      runs.push({
        runIndex: runIndex + 1,
        label: runIndex === 0 ? 'first-measured' : 'warm-' + runIndex,
        isFirstMeasuredRun: runIndex === 0,
        order: order,
        columnReadSet: {
          durationMs: columnReadSet.durationMs,
          foundCount: columnReadSet.foundCount,
          checksum: columnReadSet.checksum
        },
        exactTextFinder: {
          durationMs: exactTextFinder.durationMs,
          foundCount: exactTextFinder.foundCount,
          checksum: exactTextFinder.checksum
        },
        equal: equal,
        matchesExpected: matchesExpected
      });
    }

    return {
      batchSize: batchSize,
      existingSubmitted: batch.expectedFound.filter(Boolean).length,
      missingSubmitted: batch.expectedFound.filter(found => !found).length,
      submittedChecksum: sandboxPerformanceLookupChecksum_(
        batch.submittedUuids,
        batch.submittedUuids.map(() => true)
      ),
      expectedResultChecksum: expectedChecksum,
      runs: runs,
      summary: {
        firstMeasuredMs: {
          columnReadSet: runs[0].columnReadSet.durationMs,
          exactTextFinder: runs[0].exactTextFinder.durationMs
        },
        warmColumnReadSetMs: sandboxPerformanceSummarizeDurations_(
          runs.slice(1).map(run => run.columnReadSet.durationMs)
        ),
        warmExactTextFinderMs: sandboxPerformanceSummarizeDurations_(
          runs.slice(1).map(run => run.exactTextFinder.durationMs)
        ),
        columnReadSetMs: sandboxPerformanceSummarizeDurations_(
          runs.map(run => run.columnReadSet.durationMs)
        ),
        exactTextFinderMs: sandboxPerformanceSummarizeDurations_(
          runs.map(run => run.exactTextFinder.durationMs)
        )
      },
      allRunsEqual: runs.every(run => run.equal),
      allRunsMatchExpected: runs.every(run => run.matchesExpected)
    };
  });

  // A final read verifies that the only benchmarked column is byte-for-byte
  // unchanged and that the event row count did not move.
  const afterUuids = uuidRange.getValues().map(row => text_(row[0]));
  const afterChecksum = sandboxPerformanceLookupChecksum_(
    afterUuids,
    afterUuids.map(() => true)
  );
  const result = {
    type: 'sandbox_event_uuid_lookup_benchmark',
    schemaVersion: 1,
    benchmarkVersion: SANDBOX_UUID_LOOKUP_BENCHMARK.version,
    environment: 'SANDBOX',
    readOnly: true,
    eventRows: eventRowCount,
    eventUuidColumn: eventUuidColumn,
    batchSizes: SANDBOX_UUID_LOOKUP_BENCHMARK.batchSizes.slice(),
    runsPerBatch: SANDBOX_UUID_LOOKUP_BENCHMARK.runsPerBatch,
    timingNote: 'first-measured follows fixture validation reads; warm-N are repeated runs, not guaranteed server cache states',
    eventUuidChecksumBefore: beforeChecksum,
    eventUuidChecksumAfter: afterChecksum,
    cases: cases,
    checks: {
      allStrategiesEqual: cases.every(item => item.allRunsEqual),
      allResultsMatchExpected: cases.every(item => item.allRunsMatchExpected),
      eventUuidColumnUnchanged: beforeChecksum === afterChecksum &&
        sandboxPerformanceLookupArraysEqual_(beforeUuids, afterUuids),
      eventRowCountUnchanged: Math.max(0, events.getLastRow() - 1) === eventRowCount
    }
  };
  result.checks.passed = result.checks.allStrategiesEqual &&
    result.checks.allResultsMatchExpected &&
    result.checks.eventUuidColumnUnchanged &&
    result.checks.eventRowCountUnchanged;

  console.log(JSON.stringify({
    type: 'sandbox_event_uuid_lookup_benchmark_summary',
    benchmarkVersion: result.benchmarkVersion,
    environment: result.environment,
    readOnly: result.readOnly,
    eventRows: result.eventRows,
    eventUuidChecksumBefore: result.eventUuidChecksumBefore,
    eventUuidChecksumAfter: result.eventUuidChecksumAfter,
    cases: result.cases.map(item => ({
      batchSize: item.batchSize,
      existingSubmitted: item.existingSubmitted,
      missingSubmitted: item.missingSubmitted,
      summary: item.summary,
      allRunsEqual: item.allRunsEqual,
      allRunsMatchExpected: item.allRunsMatchExpected
    })),
    checks: result.checks
  }));
  console.log(JSON.stringify(result));
  if (!result.checks.passed) {
    throw new Error('SANDBOX_UUID_LOOKUP_BENCHMARK_CHECK_FAILED: ' + JSON.stringify(result.checks));
  }
  return result;
}

function sandboxPerformanceBuildUuidLookupBatch_(existingUuids, existingUuidSet, batchSize) {
  const submittedUuids = [];
  const expectedFound = [];
  let existingOrdinal = 0;
  let missingOrdinal = 0;

  for (let index = 0; index < batchSize; index++) {
    if (index % 2 === 0) {
      const existingIndex = (batchSize + existingOrdinal * 173) % existingUuids.length;
      submittedUuids.push(existingUuids[existingIndex]);
      expectedFound.push(true);
      existingOrdinal++;
    } else {
      let serial = batchSize * 1000 + missingOrdinal + 1;
      let candidate = sandboxPerformanceUuid_('93000000', serial);
      while (existingUuidSet.has(candidate)) {
        serial += 100000;
        candidate = sandboxPerformanceUuid_('93000000', serial);
      }
      submittedUuids.push(candidate);
      expectedFound.push(false);
      missingOrdinal++;
    }
  }
  return { submittedUuids: submittedUuids, expectedFound: expectedFound };
}

function sandboxPerformanceMeasureColumnSetLookup_(uuidRange, submittedUuids) {
  const startedAt = Date.now();
  const existing = new Set(
    uuidRange.getValues().map(row => text_(row[0])).filter(uuid => uuid)
  );
  const found = submittedUuids.map(uuid => existing.has(uuid));
  const durationMs = Date.now() - startedAt;
  return {
    durationMs: durationMs,
    found: found,
    foundCount: found.filter(Boolean).length,
    checksum: sandboxPerformanceLookupChecksum_(submittedUuids, found)
  };
}

function sandboxPerformanceMeasureExactTextFinderLookup_(uuidRange, submittedUuids) {
  const startedAt = Date.now();
  const found = submittedUuids.map(uuid => uuidRange
    .createTextFinder(uuid)
    .matchEntireCell(true)
    .useRegularExpression(false)
    .findNext() !== null);
  const durationMs = Date.now() - startedAt;
  return {
    durationMs: durationMs,
    found: found,
    foundCount: found.filter(Boolean).length,
    checksum: sandboxPerformanceLookupChecksum_(submittedUuids, found)
  };
}

function sandboxPerformanceLookupArraysEqual_(left, right) {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function sandboxPerformanceLookupChecksum_(submittedUuids, found) {
  let hash = 2166136261;
  submittedUuids.forEach((uuid, index) => {
    const value = String(uuid) + '=' + (found[index] ? '1' : '0') + '\n';
    for (let charIndex = 0; charIndex < value.length; charIndex++) {
      hash ^= value.charCodeAt(charIndex);
      hash = Math.imul(hash, 16777619) >>> 0;
    }
  });
  return ('00000000' + hash.toString(16)).slice(-8);
}

function sandboxPerformanceSummarizeDurations_(rawDurations) {
  if (!rawDurations.length) throw new Error('SANDBOX_UUID_LOOKUP_BENCHMARK_NO_DURATIONS');
  const ordered = rawDurations.slice().sort((left, right) => left - right);
  const middle = Math.floor(ordered.length / 2);
  const median = ordered.length % 2
    ? ordered[middle]
    : (ordered[middle - 1] + ordered[middle]) / 2;
  return {
    rawMs: rawDurations.slice(),
    minMs: ordered[0],
    medianMs: median,
    maxMs: ordered[ordered.length - 1]
  };
}

/** Manual SANDBOX-only helper used around live Form trigger tests. */
function enableSandboxPerformanceFormResponses() {
  return sandboxPerformanceSetFormAcceptingResponses_(true);
}

/** Manual SANDBOX-only helper that restores the original closed Form state. */
function disableSandboxPerformanceFormResponses() {
  return sandboxPerformanceSetFormAcceptingResponses_(false);
}

/**
 * Manual SANDBOX-only preparation for one real Google Form submission.
 *
 * Google Forms keeps its own next-response row even after deleteAllResponses().
 * Inserting rows above that pointer shifts the pointer too, so this helper moves
 * the synthetic history downward without inserting above row 2. Canonical
 * lineage moves with it and a blank runway remains for real Form responses.
 */
function prepareSandboxPerformanceFormSubmission() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('SANDBOX_PERFORMANCE_LOCK_TIMEOUT');
  try {
    const guarded = sandboxPerformanceGuard_();
    const expected = sandboxPerformanceBuildFixture_().expected;
    sandboxPerformanceAssertStoredFixture_(sandboxPerformanceMetrics_(guarded.ss), expected);

    const responses = requiredSheet_(guarded.ss, CANN.SHEETS.RESPONSES);
    const events = requiredSheet_(guarded.ss, CANN.SHEETS.EVENTS);
    const eventCount = Math.max(0, events.getLastRow() - 1);
    const sources = eventCount ? events.getRange(2, 12, eventCount, 2).getValues() : [];
    const responseWidth = responses.getLastColumn();
    const syntheticResponses = sources.length
      ? responses.getRange(2, 1, sources.length, responseWidth).getValues()
      : [];
    sources.forEach((row, index) => {
      if (text_(row[0]) !== CANN.SHEETS.RESPONSES || Number(row[1]) !== index + 2) {
        throw new Error('SANDBOX_PERFORMANCE_FORM_PREP_LINEAGE_MISMATCH at event row ' + (index + 2));
      }
    });

    const reservedRows = 500;
    const requiredRows = reservedRows + syntheticResponses.length + 1;
    if (responses.getMaxRows() < requiredRows) {
      responses.insertRowsAfter(responses.getMaxRows(), requiredRows - responses.getMaxRows());
    }
    const currentDataRows = Math.max(0, responses.getLastRow() - 1);
    if (currentDataRows) {
      responses.getRange(2, 1, currentDataRows, responses.getMaxColumns()).clearContent();
    }
    if (syntheticResponses.length) {
      responses.getRange(reservedRows + 2, 1, syntheticResponses.length, responseWidth)
        .setValues(syntheticResponses);
    }
    if (sources.length) {
      events.getRange(2, 13, sources.length, 1).setValues(
        sources.map(row => [Number(row[1]) + reservedRows])
      );
    }
    SpreadsheetApp.flush();

    const result = {
      reservedResponseStartRow: 2,
      reservedResponseEndRow: reservedRows + 1,
      reservedResponseRows: reservedRows,
      shiftedSyntheticResponseStartRow: reservedRows + 2,
      shiftedSyntheticResponseEndRow: sources.length + reservedRows + 1,
      shiftedCanonicalLineageRows: sources.length
    };
    console.log(JSON.stringify(result));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function sandboxPerformanceSetFormAcceptingResponses_(accepting) {
  const guarded = sandboxPerformanceGuard_();
  guarded.form.setAcceptingResponses(accepting === true);
  const result = {
    formId: guarded.form.getId(),
    acceptingResponses: guarded.form.isAcceptingResponses()
  };
  console.log(JSON.stringify(result));
  return result;
}

function sandboxPerformanceGuard_() {
  const environment = requiredScriptProperty_('ENVIRONMENT');
  if (environment !== 'SANDBOX') {
    throw new Error('SANDBOX_PERFORMANCE_GUARD: ENVIRONMENT must be SANDBOX');
  }

  const spreadsheetId = requiredScriptProperty_('SPREADSHEET_ID');
  const configured = SpreadsheetApp.openById(spreadsheetId);
  const active = SpreadsheetApp.getActiveSpreadsheet();
  const bound = SpreadsheetApp.getActive();
  if (!configured || configured.getId() !== spreadsheetId) {
    throw new Error('SANDBOX_PERFORMANCE_GUARD: configured spreadsheet mismatch');
  }
  if (!active || active.getId() !== spreadsheetId) {
    throw new Error('SANDBOX_PERFORMANCE_GUARD: active spreadsheet mismatch');
  }
  if (!bound || bound.getId() !== spreadsheetId) {
    throw new Error('SANDBOX_PERFORMANCE_GUARD: bound spreadsheet mismatch');
  }

  const marker = text_(configValue_(configured, 'ENVIRONMENT', ''));
  if (marker !== environment) {
    throw new Error('SANDBOX_PERFORMANCE_GUARD: Config ENVIRONMENT marker mismatch');
  }

  const formId = requiredScriptProperty_('FORM_ID');
  const form = FormApp.openById(formId);
  if (!form || form.getId() !== formId) {
    throw new Error('SANDBOX_PERFORMANCE_GUARD: configured Form mismatch');
  }
  if (form.getDestinationId() !== spreadsheetId) {
    throw new Error('SANDBOX_PERFORMANCE_GUARD: Form destination mismatch');
  }
  if (typeof resetSandboxData !== 'function') {
    throw new Error('SANDBOX_PERFORMANCE_GUARD: resetSandboxData() is required for recovery');
  }

  return { ss: configured, form: form };
}

function sandboxPerformanceBuildFixture_() {
  const settings = SANDBOX_PERFORMANCE_FIXTURE;
  const displayIdCounters = {};
  const products = [];

  for (let index = 0; index < settings.purchaseCount; index++) {
    const type = settings.productTypes[index % settings.productTypes.length];
    const borrowed = index % 17 === 0;
    const counterKey = type + (borrowed ? 'B' : '');
    displayIdCounters[counterKey] = (displayIdCounters[counterKey] || 0) + 1;
    const legacyProductId = '*' + type + displayIdCounters[counterKey] + (borrowed ? 'B' : '');
    const createdDate = sandboxPerformanceDate_(Date.UTC(2023, 0, 1) + index * 86400000);
    const status = index >= settings.interactedPurchaseCount
      ? CANN.STATUS.UNOPENED
      : (index % 15 === 0 ? CANN.STATUS.ACTIVE : CANN.STATUS.FINISHED);

    products.push({
      index: index,
      type: type,
      borrowed: borrowed,
      status: status,
      legacyProductId: legacyProductId,
      productUuid: sandboxPerformanceUuid_('51000000', index + 1),
      actionUuid: sandboxPerformanceUuid_('52000000', index + 1),
      createdDate: createdDate,
      uses: 0,
      mostRecentUse: '',
      lastQuantity: ''
    });
  }

  const events = [];
  let totalUses = 0;
  let finishedEvents = 0;
  for (let index = 0; index < settings.eventCount; index++) {
    const productIndex = index % settings.interactedPurchaseCount;
    const occurrence = Math.floor(index / settings.interactedPurchaseCount);
    const product = products[productIndex];
    const timestamp = sandboxPerformanceTimestamp_(settings.startEpochMillis + index * settings.eventStepMinutes * 60000);
    const quantity = settings.quantities[(productIndex + occurrence * 2) % settings.quantities.length];
    const marksFinished = product.status === CANN.STATUS.FINISHED &&
      occurrence === (settings.eventCount / settings.interactedPurchaseCount) - 1;

    product.uses = sandboxPerformanceRound_(product.uses + quantity);
    product.mostRecentUse = timestamp;
    product.lastQuantity = quantity;
    totalUses = sandboxPerformanceRound_(totalUses + quantity);
    if (marksFinished) finishedEvents++;

    events.push([
      sandboxPerformanceUuid_('53000000', index + 1),
      timestamp,
      timestamp.slice(0, 10),
      timestamp.slice(11, 16),
      product.productUuid,
      product.legacyProductId,
      quantity,
      settings.weightCodes[(productIndex + occurrence) % settings.weightCodes.length],
      marksFinished,
      settings.source,
      sandboxPerformanceUuid_('54000000', index + 1),
      CANN.SHEETS.RESPONSES,
      index + 2
    ]);
  }

  const purchases = products.map(product => {
    const index = product.index;
    const cost = product.borrowed ? 0 : sandboxPerformanceRound_(10 + (index % 23) + (index % 4) * 0.25);
    const postTax = index % 2 === 0;
    const finalCost = postTax ? cost : sandboxPerformanceRound_(cost * 1.13);
    return [
      product.createdDate,
      product.type,
      'SANDBOX PERF ' + product.type + ' ' + String(index + 1).padStart(3, '0'),
      cost,
      5 + (index % 31),
      settings.grams[index % settings.grams.length],
      product.borrowed ? 1 : 0,
      product.status,
      product.legacyProductId,
      product.uses,
      postTax,
      finalCost,
      product.mostRecentUse,
      product.productUuid,
      product.actionUuid,
      product.createdDate + ' 12:00',
      product.status === CANN.STATUS.FINISHED ? product.mostRecentUse : '',
      product.lastQuantity
    ];
  });

  return {
    purchases: purchases,
    events: events,
    expected: {
      fixtureVersion: settings.version,
      purchases: settings.purchaseCount,
      responses: settings.eventCount,
      events: settings.eventCount,
      uniqueProducts: settings.purchaseCount,
      uniqueEvents: settings.eventCount,
      active: 24,
      finished: 336,
      unopened: 40,
      finishedEvents: finishedEvents,
      totalUses: totalUses,
      interactionSummaryRows: settings.interactedPurchaseCount,
      interactionSummaryMismatches: 0,
      lineageRows: settings.eventCount,
      uniqueLineage: settings.eventCount,
      contiguousLineage: true,
      ledgerRows: 0,
      migrationRows: 0
    }
  };
}

function sandboxPerformanceBuildResponseRows_(events, headers, width) {
  return events.map(event => {
    const row = new Array(width).fill('');
    row[headers.Timestamp] = event[1];
    if (headers.Date !== undefined) row[headers.Date] = event[2];
    if (headers.Time !== undefined) row[headers.Time] = event[3];
    row[headers.Product] = event[5];
    row[headers.Uses] = event[6];
    if (headers['Weight code'] !== undefined) row[headers['Weight code']] = event[7];
    if (headers['Mark as Finished?'] !== undefined) row[headers['Mark as Finished?']] = event[8] ? 'Yes' : 'No';
    if (headers[CANN.COMPATIBILITY_EVENT_HEADER] !== undefined) {
      row[headers[CANN.COMPATIBILITY_EVENT_HEADER]] = event[0];
      row[headers[CANN.COMPATIBILITY_REQUEST_HEADER]] = event[10] || '';
    }
    return row;
  });
}

function sandboxPerformanceReplaceRows_(sheet, rows, width) {
  const dataRows = Math.max(0, sheet.getLastRow() - 1);
  if (dataRows) sheet.getRange(2, 1, dataRows, sheet.getMaxColumns()).clearContent();
  if (!rows.length) return;

  const requiredRows = rows.length + 1;
  if (sheet.getMaxRows() < requiredRows) {
    sheet.insertRowsAfter(sheet.getMaxRows(), requiredRows - sheet.getMaxRows());
  }
  if (sheet.getMaxColumns() < width) {
    sheet.insertColumnsAfter(sheet.getMaxColumns(), width - sheet.getMaxColumns());
  }
  sheet.getRange(2, 1, rows.length, width).setValues(rows);
}

function sandboxPerformanceMetrics_(ss) {
  const purchases = readDataRows_(requiredSheet_(ss, CANN.SHEETS.PURCHASES));
  const events = readDataRows_(requiredSheet_(ss, CANN.SHEETS.EVENTS));
  const responses = readDataRows_(requiredSheet_(ss, CANN.SHEETS.RESPONSES));
  const statuses = purchases.reduce((counts, row) => {
    const status = String(row[7]);
    counts[status] = (counts[status] || 0) + 1;
    return counts;
  }, {});
  const lineageRows = events.filter(row =>
    String(row[11]) === CANN.SHEETS.RESPONSES && Number(row[12]) >= 2
  );
  const latestByProduct = {};
  events.forEach(row => {
    const legacyProductId = String(row[5]);
    const timestampMillis = new Date(row[1]).getTime();
    if (!legacyProductId || !Number.isFinite(timestampMillis)) return;
    if (!latestByProduct[legacyProductId] ||
        timestampMillis > latestByProduct[legacyProductId].timestampMillis) {
      latestByProduct[legacyProductId] = {
        timestampMillis: timestampMillis,
        lastQuantity: finiteNumberOr_(row[6], 0)
      };
    }
  });
  const interactionSummaryRows = purchases.filter(row =>
    row[12] !== '' && row[12] != null && row[17] !== '' && row[17] != null
  ).length;
  const interactionSummaryMismatches = purchases.filter(row => {
    const expected = latestByProduct[String(row[8])] || null;
    const actualTimestamp = row[12] === '' || row[12] == null
      ? null
      : new Date(row[12]).getTime();
    const actualQuantity = row[17] === '' || row[17] == null
      ? null
      : finiteNumberOr_(row[17], 0);
    if (!expected) return actualTimestamp != null || actualQuantity != null;
    return actualTimestamp !== expected.timestampMillis ||
      actualQuantity == null ||
      Math.abs(actualQuantity - expected.lastQuantity) > 1e-9;
  }).length;

  return {
    fixtureVersion: SANDBOX_PERFORMANCE_FIXTURE.version,
    purchases: purchases.length,
    responses: responses.length,
    events: events.length,
    uniqueProducts: new Set(purchases.map(row => String(row[13]))).size,
    uniqueEvents: new Set(events.map(row => String(row[0]))).size,
    active: statuses[String(CANN.STATUS.ACTIVE)] || 0,
    finished: statuses[String(CANN.STATUS.FINISHED)] || 0,
    unopened: statuses[String(CANN.STATUS.UNOPENED)] || 0,
    finishedEvents: events.filter(row => row[8] === true || String(row[8]).toLowerCase() === 'true').length,
    totalUses: sandboxPerformanceRound_(events.reduce((sum, row) => sum + finiteNumberOr_(row[6], 0), 0)),
    interactionSummaryRows: interactionSummaryRows,
    interactionSummaryMismatches: interactionSummaryMismatches,
    lineageRows: lineageRows.length,
    uniqueLineage: new Set(lineageRows.map(row => String(row[11]) + ':' + String(row[12]))).size,
    contiguousLineage: events.every((row, index) =>
      String(row[11]) === CANN.SHEETS.RESPONSES && Number(row[12]) === index + 2
    ),
    ledgerRows: readDataRows_(requiredSheet_(ss, CANN.SHEETS.LEDGER)).length,
    migrationRows: readDataRows_(requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT)).length
  };
}

function sandboxPerformanceAssertGeneratedFixture_(fixture) {
  const settings = SANDBOX_PERFORMANCE_FIXTURE;
  if (fixture.purchases.length !== settings.purchaseCount || fixture.events.length !== settings.eventCount) {
    throw new Error('SANDBOX_PERFORMANCE_FIXTURE_INVALID: generated row count mismatch');
  }
  if (fixture.purchases.some(row => row.length !== CANN.PURCHASE_HEADERS.length)) {
    throw new Error('SANDBOX_PERFORMANCE_FIXTURE_INVALID: purchase width mismatch');
  }
  if (fixture.events.some(row => row.length !== CANN.EVENT_HEADERS.length)) {
    throw new Error('SANDBOX_PERFORMANCE_FIXTURE_INVALID: event width mismatch');
  }

  const productUuids = fixture.purchases.map(row => String(row[13]));
  const eventUuids = fixture.events.map(row => String(row[0]));
  const productIds = fixture.purchases.map(row => String(row[8]));
  if (new Set(productUuids).size !== productUuids.length ||
      new Set(productIds).size !== productIds.length ||
      new Set(eventUuids).size !== eventUuids.length) {
    throw new Error('SANDBOX_PERFORMANCE_FIXTURE_INVALID: identifiers must be unique');
  }
  const knownProducts = new Set(productUuids);
  if (fixture.events.some(row => !knownProducts.has(String(row[4])))) {
    throw new Error('SANDBOX_PERFORMANCE_FIXTURE_INVALID: event references an unknown product');
  }

  const lineage = fixture.events.map(row => String(row[11]) + ':' + String(row[12]));
  if (new Set(lineage).size !== fixture.events.length ||
      fixture.events.some((row, index) => row[11] !== CANN.SHEETS.RESPONSES || row[12] !== index + 2) ||
      fixture.expected.responses !== fixture.events.length) {
    throw new Error('SANDBOX_PERFORMANCE_FIXTURE_INVALID: response lineage must be unique and contiguous');
  }
}

function sandboxPerformanceAssertStoredFixture_(actual, expected) {
  const mismatches = Object.keys(expected).filter(key => actual[key] !== expected[key]);
  if (mismatches.length) {
    throw new Error('SANDBOX_PERFORMANCE_FIXTURE_MISMATCH: ' + JSON.stringify({
      fields: mismatches,
      expected: expected,
      actual: actual
    }));
  }
}

function sandboxPerformanceUuid_(prefix, index) {
  return prefix + '-0000-4000-8000-' + String(index).padStart(12, '0');
}

function sandboxPerformanceTimestamp_(epochMillis) {
  const iso = new Date(epochMillis).toISOString();
  return iso.slice(0, 10) + ' ' + iso.slice(11, 16);
}

function sandboxPerformanceDate_(epochMillis) {
  return new Date(epochMillis).toISOString().slice(0, 10);
}

function sandboxPerformanceRound_(value) {
  return Math.round(value * 1000000) / 1000000;
}
