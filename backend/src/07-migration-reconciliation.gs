// -----------------------------------------------------------------------------
// Migration and reconciliation
// -----------------------------------------------------------------------------

function runReliabilityMigration() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    bumpMutationWatermark_();
    ensureCoreSchema_(ss);
    const purchaseResult = backfillPurchases_(ss);
    const eventResult = backfillConsumptionEvents_(ss);
    const interactionSummary = rebuildInteractionSummaryLocked_(ss, false);
    applySheetSafety_(ss);
    updateFormAndDescriptionLocked_(ss);
    const reconciliation = reconcileReliabilityMigration_(ss);
    const result = {
      purchaseBackfill: purchaseResult,
      eventBackfill: eventResult,
      interactionSummary: interactionSummary,
      reconciliation: reconciliation
    };
    bumpMutationWatermark_();
    console.log(JSON.stringify(result));
    return result;
  } finally {
    lock.releaseLock();
  }
}

/**
 * Explicit additive migration for the fast GET interaction projection.
 *
 * Run this editor function while the prior deployment remains active. It leaves
 * the Config marker at 0, so both the prior deployment and this source keep using
 * the legacy GET calculation. After this returns with zero differences, deploy
 * the Phase 4 source, then run enableInteractionSummaryFastPath().
 */
function runInteractionSummaryMigration() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const result = rebuildInteractionSummaryLocked_(ss, false);
    console.log(JSON.stringify({
      type: 'interaction_summary_prepared',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

/**
 * Explicit maintenance entrypoint. Rebuilds only from canonical history and
 * deliberately leaves the fast path disabled until the separate enable step.
 */
function rebuildInteractionSummary() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const result = rebuildInteractionSummaryLocked_(ss, false);
    console.log(JSON.stringify({
      type: 'interaction_summary_rebuilt',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

/**
 * Final rollout step. Run only after this Phase 4 source is active on the web
 * deployment. It rebuilds once more under the lock, covering any requests that
 * arrived between preparation and deployment, then enables the fast GET path.
 */
function enableInteractionSummaryFastPath() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const result = rebuildInteractionSummaryLocked_(ss, true);
    console.log(JSON.stringify({
      type: 'interaction_summary_fast_path_enabled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

/** Immediate rollback switch. Leaves the additive column and data in place. */
function disableInteractionSummaryFastPath() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    setConfigValue_(
      ss,
      'INTERACTION_SUMMARY_VERSION',
      0,
      'Purchases interaction-summary version'
    );
    SpreadsheetApp.flush();
    const result = {
      summaryVersion: 0,
      fastPathEnabled: false,
      fallback: 'LEGACY_HISTORY'
    };
    console.log(JSON.stringify({
      type: 'interaction_summary_fast_path_disabled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

/** Read-only maintenance entrypoint. It does not repair or mark readiness. */
function reconcileInteractionSummary() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const result = reconcileInteractionSummary_(ss);
    console.log(JSON.stringify({
      type: 'interaction_summary_reconciled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function rebuildInteractionSummaryLocked_(ss, enableFastPath) {
  // Fail over to the legacy read path before any projection cells move. This
  // marker is ignored by the previous deployment and understood by this one.
  //
  // Deliberately no pre-write watermark bump here. Re-running this migration
  // must leave durable state byte-identical (see backend_spreadsheet_test.js
  // "Running the migration again leaves all durable cells identical"), and this
  // is a supervised maintenance operation rather than a request path, so the
  // trailing bump below is sufficient.
  setConfigValue_(
    ss,
    'INTERACTION_SUMMARY_VERSION',
    0,
    'Purchases interaction-summary version'
  );
  SpreadsheetApp.flush();

  const purchases = ensureInteractionSummarySchema_(ss);
  const headers = headerMap_(purchases);
  requireExactHeaders_(headers, CANN.PURCHASE_HEADERS, CANN.SHEETS.PURCHASES);

  // This is the exact legacy GET calculation retained only for migration and
  // explicit reconciliation. It is never called by the normal GET path.
  const legacy = latestInteractions_(ss);
  const canonicalResult = canonicalInteractionSummary_(ss);
  if (canonicalResult.invalidEvents) {
    throw new Error(
      'INTERACTION_SUMMARY_MIGRATION_BLOCKED: canonical history contains ' +
      canonicalResult.invalidEvents + ' invalid event row(s)'
    );
  }
  const legacyComparison = compareInteractionSummaryMaps_(
    legacy,
    canonicalResult.interactions
  );
  if (legacyComparison.length) {
    throw new Error(
      'INTERACTION_SUMMARY_MIGRATION_BLOCKED: canonical state differs from legacy GET: ' +
      JSON.stringify(legacyComparison.slice(0, 10))
    );
  }

  const writeResult = writeInteractionSummary_(
    purchases,
    headers,
    canonicalResult.interactions
  );
  SpreadsheetApp.flush();

  const reconciliation = reconcileInteractionSummary_(ss);
  if (reconciliation.differences.length) {
    throw new Error(
      'INTERACTION_SUMMARY_MIGRATION_BLOCKED: rebuilt summary did not reconcile: ' +
      JSON.stringify(reconciliation.differences.slice(0, 10))
    );
  }

  const fastPathEnabled = enableFastPath === true;
  if (fastPathEnabled) {
    setConfigValue_(
      ss,
      'INTERACTION_SUMMARY_VERSION',
      CANN.INTERACTION_SUMMARY_VERSION,
      'Purchases interaction-summary version'
    );
    SpreadsheetApp.flush();
  }

  return {
    preparedSummaryVersion: CANN.INTERACTION_SUMMARY_VERSION,
    configSummaryVersion: fastPathEnabled
      ? CANN.INTERACTION_SUMMARY_VERSION
      : 0,
    canonicalEventRows: canonicalResult.eventRows,
    validCanonicalEvents: canonicalResult.validEvents,
    invalidCanonicalEvents: canonicalResult.invalidEvents,
    summarizedProducts: Object.keys(canonicalResult.interactions).length,
    purchaseRows: writeResult.purchaseRows,
    populatedSummaries: writeResult.populatedSummaries,
    legacyComparisonDifferences: legacyComparison.length,
    reconciliationDifferences: reconciliation.differences.length,
    readyToEnable: true,
    fastPathEnabled: fastPathEnabled
  };
  bumpMutationWatermark_();
  return result;
}

function ensureInteractionSummarySchema_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const summaryColumn = CANN.PURCHASE_HEADERS.length;
  const expectedPrefix = CANN.PURCHASE_HEADERS.slice(0, summaryColumn - 1);
  const currentHeaders = headerMap_(sheet);
  requireExactHeaders_(currentHeaders, expectedPrefix, CANN.SHEETS.PURCHASES);
  if (sheet.getMaxColumns() < summaryColumn) {
    sheet.insertColumnsAfter(
      sheet.getMaxColumns(),
      summaryColumn - sheet.getMaxColumns()
    );
  }
  const range = sheet.getRange(1, summaryColumn);
  const existing = text_(range.getValue());
  const expected = CANN.PURCHASE_HEADERS[summaryColumn - 1];
  if (existing && existing !== expected) {
    throw new Error(
      'SCHEMA_MISMATCH: ' + CANN.SHEETS.PURCHASES + ' column ' +
      summaryColumn + ' expected ' + expected + ' but found ' + existing
    );
  }
  if (!existing) range.setValue(expected);
  return sheet;
}

function canonicalInteractionSummary_(ss) {
  if (typeof PropertiesService === 'undefined') {
    return rawCanonicalInteractionSummary_(ss);
  }
  const runtimeConfig = readAndAssertRuntimeConfig_(ss, environment_());
  const capability = consumptionCorrectionCapability_(
    runtimeConfig.values
  );
  if (!capability.version) return rawCanonicalInteractionSummary_(ss);
  const state = effectiveConsumptionState_(ss);
  const interactions = {};
  state.resolution.effectiveEvents.forEach(event => {
    const legacyProductId = event.product.productId;
    const existing = interactions[legacyProductId];
    if (
      !existing ||
      event.occurredAtEpochMillis > existing.lastLoggedAtEpochMillis
    ) {
      interactions[legacyProductId] = {
        lastLoggedAtEpochMillis: event.occurredAtEpochMillis,
        lastQuantity: event.quantity,
        timestamp: new Date(event.occurredAtEpochMillis),
        canonicalRow: event.canonicalRow
      };
    }
  });
  return {
    interactions: interactions,
    eventRows: state.snapshot.eventRows.length,
    correctionRows: state.snapshot.correctionRows.length,
    validEvents: state.resolution.effectiveEvents.length,
    invalidEvents: 0
  };
}

function rawCanonicalInteractionSummary_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const headers = headerMap_(sheet);
  requireExactHeaders_(headers, CANN.EVENT_HEADERS, CANN.SHEETS.EVENTS);
  const lastRow = sheet.getLastRow();
  const rows = lastRow < 2
    ? []
    : sheet.getRange(2, 1, lastRow - 1, sheet.getLastColumn()).getValues();
  const interactions = {};
  let validEvents = 0;
  let invalidEvents = 0;

  rows.forEach((row, index) => {
    if (!row.some(cell => cell !== '' && cell != null)) return;
    const legacyProductId = text_(value_(row, headers, 'Legacy Product ID'));
    const timestamp = value_(row, headers, 'Timestamp');
    const timestampMillis = timestampMillisOrNull_(timestamp);
    const lastQuantity = optionalFiniteNumber_(value_(row, headers, 'Uses'));
    if (!legacyProductId || timestampMillis == null || lastQuantity == null) {
      invalidEvents++;
      return;
    }
    validEvents++;
    const existing = interactions[legacyProductId];
    // Strictly greater preserves the deployed rule: for equal timestamps, the
    // earlier physical canonical row keeps its quantity.
    if (!existing || timestampMillis > existing.lastLoggedAtEpochMillis) {
      interactions[legacyProductId] = {
        lastLoggedAtEpochMillis: timestampMillis,
        lastQuantity: lastQuantity,
        timestamp: new Date(timestampMillis),
        canonicalRow: index + 2
      };
    }
  });

  return {
    interactions: interactions,
    eventRows: rows.length,
    validEvents: validEvents,
    invalidEvents: invalidEvents
  };
}

function writeInteractionSummary_(sheet, headers, interactions) {
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return { purchaseRows: 0, populatedSummaries: 0 };
  const rows = sheet.getRange(
    2,
    1,
    lastRow - 1,
    sheet.getLastColumn()
  ).getValues();
  const latestValues = [];
  const quantityValues = [];
  let purchaseRows = 0;
  let populatedSummaries = 0;

  rows.forEach(row => {
    const legacyProductId = text_(value_(row, headers, 'Product ID'));
    const interaction = legacyProductId ? interactions[legacyProductId] : null;
    if (legacyProductId) purchaseRows++;
    if (interaction) populatedSummaries++;
    latestValues.push([interaction ? interaction.timestamp : '']);
    quantityValues.push([
      interaction ? interaction.lastQuantity : ''
    ]);
  });

  sheet.getRange(
    2,
    headers['Most recent use'] + 1,
    latestValues.length,
    1
  ).setValues(latestValues);
  sheet.getRange(
    2,
    headers['Last quantity'] + 1,
    quantityValues.length,
    1
  ).setValues(quantityValues);
  return {
    purchaseRows: purchaseRows,
    populatedSummaries: populatedSummaries
  };
}

function reconcileInteractionSummary_(ss) {
  const canonicalResult = canonicalInteractionSummary_(ss);
  const expected = canonicalResult.interactions;
  const sheet = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const headers = headerMap_(sheet);
  requireExactHeaders_(headers, CANN.PURCHASE_HEADERS, CANN.SHEETS.PURCHASES);
  const lastRow = sheet.getLastRow();
  const rows = lastRow < 2
    ? []
    : sheet.getRange(2, 1, lastRow - 1, sheet.getLastColumn()).getValues();
  const differences = [];
  const seen = {};
  let purchaseRows = 0;

  rows.forEach((row, index) => {
    const legacyProductId = text_(value_(row, headers, 'Product ID'));
    if (!legacyProductId) return;
    purchaseRows++;
    if (seen[legacyProductId]) {
      differences.push({
        type: 'DUPLICATE_PRODUCT_ID',
        legacyProductId: legacyProductId,
        rowNumber: index + 2,
        firstRowNumber: seen[legacyProductId]
      });
      return;
    }
    seen[legacyProductId] = index + 2;
    const actualMillis = timestampMillisOrNull_(
      value_(row, headers, 'Most recent use')
    );
    const actualQuantity = optionalFiniteNumber_(
      value_(row, headers, 'Last quantity')
    );
    const expectedState = expected[legacyProductId] || null;
    if (!interactionStateMatches_(
      actualMillis,
      actualQuantity,
      expectedState
    )) {
      differences.push({
        type: 'STATE_MISMATCH',
        legacyProductId: legacyProductId,
        rowNumber: index + 2,
        expected: interactionStateForReport_(expectedState),
        actual: {
          lastLoggedAtEpochMillis: actualMillis,
          lastQuantity: actualQuantity
        }
      });
    }
  });

  Object.keys(expected).forEach(legacyProductId => {
    if (!seen[legacyProductId]) {
      differences.push({
        type: 'MISSING_PRODUCT',
        legacyProductId: legacyProductId,
        canonicalRow: expected[legacyProductId].canonicalRow
      });
    }
  });

  return {
    canonicalEventRows: canonicalResult.eventRows,
    validCanonicalEvents: canonicalResult.validEvents,
    invalidCanonicalEvents: canonicalResult.invalidEvents,
    purchaseRows: purchaseRows,
    summarizedProducts: Object.keys(expected).length,
    differences: differences
  };
}

function compareInteractionSummaryMaps_(left, right) {
  const differences = [];
  const keys = {};
  Object.keys(left || {}).forEach(key => { keys[key] = true; });
  Object.keys(right || {}).forEach(key => { keys[key] = true; });
  Object.keys(keys).sort().forEach(legacyProductId => {
    const leftState = (left || {})[legacyProductId] || null;
    const rightState = (right || {})[legacyProductId] || null;
    const leftMillis = leftState
      ? timestampMillisOrNull_(leftState.lastLoggedAtEpochMillis)
      : null;
    const leftQuantity = leftState
      ? optionalFiniteNumber_(leftState.lastQuantity)
      : null;
    if (!interactionStateMatches_(leftMillis, leftQuantity, rightState)) {
      differences.push({
        legacyProductId: legacyProductId,
        legacy: interactionStateForReport_(leftState),
        canonical: interactionStateForReport_(rightState)
      });
    }
  });
  return differences;
}

function interactionStateMatches_(actualMillis, actualQuantity, expectedState) {
  if (!expectedState) return actualMillis == null && actualQuantity == null;
  const expectedMillis = timestampMillisOrNull_(
    expectedState.lastLoggedAtEpochMillis
  );
  const expectedQuantity = optionalFiniteNumber_(expectedState.lastQuantity);
  return actualMillis === expectedMillis &&
    actualQuantity != null &&
    expectedQuantity != null &&
    Math.abs(actualQuantity - expectedQuantity) <= 1e-9;
}

function interactionStateForReport_(state) {
  if (!state) return null;
  return {
    lastLoggedAtEpochMillis: timestampMillisOrNull_(
      state.lastLoggedAtEpochMillis
    ),
    lastQuantity: optionalFiniteNumber_(state.lastQuantity)
  };
}

function backfillPurchases_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const headers = headerMap_(sheet);
  const lastRow = sheet.getLastRow();
  const rows = lastRow < 2 ? [] : sheet.getRange(2, 1, lastRow - 1, sheet.getLastColumn()).getValues();
  const responseTotals = responseMetrics_(ss);
  const ids = {};
  let backfilled = 0;
  let purchaseRows = 0;
  const idValues = [], usesValues = [], finalValues = [], latestValues = [], identityValues = [];

  rows.forEach((row, index) => {
    const rowNumber = index + 2;
    const name = text_(value_(row, headers, 'Product name'));
    const legacyId = text_(value_(row, headers, 'Product ID'));
    const preservedIdentity = [
      value_(row, headers, 'Product UUID'),
      value_(row, headers, 'Client Action UUID'),
      value_(row, headers, 'Created At'),
      value_(row, headers, 'Finished At')
    ];
    if (!name) {
      idValues.push([value_(row, headers, 'Product ID')]);
      usesValues.push([value_(row, headers, 'Uses')]);
      finalValues.push([value_(row, headers, 'Final cost')]);
      latestValues.push([value_(row, headers, 'Most recent use')]);
      identityValues.push(preservedIdentity);
      return;
    }
    purchaseRows++;
    if (!legacyId) {
      recordMigrationIssue_(ss, 'BLANK_PRODUCT_ID', sheet.getName(), rowNumber, '', 'Purchase row retained without guessing an ID');
      idValues.push([value_(row, headers, 'Product ID')]);
      usesValues.push([value_(row, headers, 'Uses')]);
      finalValues.push([value_(row, headers, 'Final cost')]);
      latestValues.push([value_(row, headers, 'Most recent use')]);
      identityValues.push(preservedIdentity);
      return;
    }
    if (ids[legacyId]) {
      recordMigrationIssue_(ss, 'DUPLICATE_PRODUCT_ID', sheet.getName(), rowNumber, legacyId, 'Duplicate display ID');
      idValues.push([value_(row, headers, 'Product ID')]);
      usesValues.push([value_(row, headers, 'Uses')]);
      finalValues.push([value_(row, headers, 'Final cost')]);
      latestValues.push([value_(row, headers, 'Most recent use')]);
      identityValues.push(preservedIdentity);
      return;
    }
    ids[legacyId] = true;
    const productUuid = text_(value_(row, headers, 'Product UUID')) || Utilities.getUuid();
    const actionUuid = text_(value_(row, headers, 'Client Action UUID')) || Utilities.getUuid();
    const createdAt = value_(row, headers, 'Created At') || value_(row, headers, 'Date') || new Date();
    const metrics = responseTotals[legacyId] || { uses: 0, latest: '' };
    const cost = finiteNumber_(value_(row, headers, 'Pre-tax cost'));
    const postTax = truthy_(value_(row, headers, 'Post-tax'));
    const finalCost = cost == null ? '' : (postTax ? cost : cost * (1 + taxRate_(ss)));
    idValues.push([legacyId]);
    usesValues.push([metrics.uses]);
    finalValues.push([finalCost]);
    latestValues.push([metrics.latest]);
    identityValues.push([productUuid, actionUuid, createdAt, value_(row, headers, 'Finished At') || '']);
    if (!text_(value_(row, headers, 'Product UUID')) || !text_(value_(row, headers, 'Client Action UUID'))) backfilled++;
  });

  if (rows.length) {
    sheet.getRange(2, headers['Product ID'] + 1, idValues.length, 1).setValues(idValues);
    sheet.getRange(2, headers['Uses'] + 1, usesValues.length, 1).setValues(usesValues);
    sheet.getRange(2, headers['Final cost'] + 1, finalValues.length, 1).setValues(finalValues);
    sheet.getRange(2, headers['Most recent use'] + 1, latestValues.length, 1).setValues(latestValues);
    sheet.getRange(2, headers['Product UUID'] + 1, identityValues.length, 4).setValues(identityValues);
  }
  return { purchaseRows: purchaseRows, identitiesBackfilled: backfilled };
}

function backfillConsumptionEvents_(ss) {
  const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const headers = headerMap_(responses);
  requireHeaders_(headers, ['Timestamp', 'Product', 'Uses']);
  const cutoffRow = responses.getLastRow();
  const rows = cutoffRow < 2 ? [] : responses.getRange(2, 1, cutoffRow - 1, responses.getLastColumn()).getValues();
  const products = productContext_(ss);
  const eventSheet = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const existing = eventContext_(ss).eventIds;
  const append = [];
  let unresolved = 0;

  rows.forEach((row, index) => {
    const rowNumber = index + 2;
    if (!row.some(cell => cell !== '' && cell != null)) return;
    const eventId = deterministicLegacyEventUuid_(ss.getId(), responses.getName(), rowNumber);
    if (existing.has(eventId)) return;
    const legacyId = text_(value_(row, headers, 'Product'));
    const product = products.byLegacyId[legacyId];
    if (!product) {
      unresolved++;
      recordMigrationIssue_(ss, 'UNKNOWN_PRODUCT', responses.getName(), rowNumber, legacyId, 'Historical row preserved; canonical relationship unresolved');
      return;
    }
    const rawTimestamp = value_(row, headers, 'Timestamp');
    const timestamp = dateOrNull_(rawTimestamp);
    if (!timestamp) {
      unresolved++;
      recordMigrationIssue_(ss, 'INVALID_TIMESTAMP', responses.getName(), rowNumber, legacyId, 'Historical row preserved; timestamp is missing or invalid');
      return;
    }
    append.push({
      eventId: eventId,
      timestamp: timestamp,
      localDate: formatDate_(timestamp),
      localTime: formatTime_(timestamp),
      productUuid: product.productUuid,
      legacyProductId: legacyId,
      uses: finiteNumberOr_(value_(row, headers, 'Uses'), 0),
      weightCode: text_(value_(row, headers, 'Weight code')),
      isFinished: truthy_(value_(row, headers, 'Mark as Finished?')),
      source: 'FORM_LEGACY',
      requestId: '',
      legacySourceSheet: responses.getName(),
      legacySourceRow: rowNumber,
      compatibilityRow: null
    });
  });
  appendEventRows_(eventSheet, append);
  return { cutoffRow: cutoffRow, eventsBackfilled: append.length, unresolvedRows: unresolved };
}

function reconcileReliabilityMigration_(ss) {
  const purchases = productContext_(ss);
  const responses = responseMetrics_(ss);
  const events = canonicalMetrics_(ss);
  const differences = [];
  Object.keys(purchases.byLegacyId).forEach(legacyId => {
    const response = responses[legacyId] || { uses: 0, count: 0, earliest: '', latest: '' };
    const event = events[legacyId] || { uses: 0, count: 0, earliest: '', latest: '' };
    if (Math.abs(response.uses - event.uses) > 1e-9 || response.count !== event.count || String(response.earliest) !== String(event.earliest) || String(response.latest) !== String(event.latest)) {
      differences.push({ legacyProductId: legacyId, response: response, canonical: event });
    }
  });
  const reportSheet = requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT);
  const reportHeaders = headerMap_(reportSheet);
  const unresolvedRows = readDataRows_(reportSheet).filter(row =>
    reportHeaders['Resolved At'] === undefined ||
    !value_(row, reportHeaders, 'Resolved At')
  ).length;
  return {
    purchaseCount: Object.keys(purchases.byLegacyId).length,
    responseEventCount: Object.keys(responses).reduce((sum, key) => sum + responses[key].count, 0),
    canonicalEventCount: Object.keys(events).reduce((sum, key) => sum + events[key].count, 0),
    unresolvedRows: unresolvedRows,
    differences: differences
  };
}

