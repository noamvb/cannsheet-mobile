// -----------------------------------------------------------------------------
// Recoverable multi-sheet apply: explicit rollout and reconciliation
// -----------------------------------------------------------------------------

/**
 * Additive, idempotent preparation. Marker 0 deliberately keeps the deployed
 * SpreadsheetApp write path active until enableRecoverableSyncApply() is run.
 */
function prepareRecoverableSyncApply() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    ensureCoreSchema_(ss);
    ensureMigrationResolutionSchema_(ss);
    assertSpreadsheetTimeZone_(ss);
    provisionRecoverableDateFormats_(ss);
    repairRecoverableStateLocked_({
      ss: ss,
      config: {},
      sheets: {},
      headers: {}
    });
    setConfigValue_(
      ss,
      CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY,
      0,
      'Recoverable multi-sheet apply version'
    );
    setConfigValue_(
      ss,
      CANN.PENDING_APPLY_KEY,
      '',
      'Apply UUID awaiting finalization'
    );
    const compatibility = ensureCompatibilityIdentitySchema_(ss);
    const backfill = backfillCompatibilityIdentities_(ss);
    SpreadsheetApp.flush();
    const reconciliation = reconcileRecoverableSyncApply_(ss);
    const result = {
      preparedVersion: CANN.RECOVERABLE_SYNC_APPLY_VERSION,
      configVersion: 0,
      fastPathEnabled: false,
      compatibilityIdentityColumns: compatibility,
      identityRowsBackfilled: backfill.rowsBackfilled,
      blockingDifferences: reconciliation.blockingDifferences.length
    };
    console.log(JSON.stringify({
      type: 'recoverable_sync_apply_prepared',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

/**
 * Marker-0 maintenance repair for rows that preparation cannot safely map.
 *
 * First, a blank compatibility identity is relinked only when timestamp,
 * product, quantity, weight, and finished flag select exactly one canonical
 * event and that Event UUID is not already owned by another response row.
 * If no canonical event exists, canonicalization is allowed only when the
 * Purchases Uses value still equals the canonical sum, which proves this
 * response quantity has not already been applied. Anything else is reported
 * and left untouched for human review.
 */
function repairPreparedCompatibilityRows() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    assertSpreadsheetTimeZone_(ss);
    assertAdvancedSheetsService_();
    ensureCompatibilityIdentitySchema_(ss);
    const configSheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
    const config = configValuesFromSheet_(
      configSheet,
      headerMap_(configSheet)
    );
    if (recoverableSyncApplyReady_(config)) {
      throw new Error(
        'PREPARED_COMPATIBILITY_REPAIR_BLOCKED: disable marker 1 first'
      );
    }
    if (!interactionSummaryReady_(config)) {
      throw new Error(
        'PREPARED_COMPATIBILITY_REPAIR_BLOCKED: interaction summary must be ready'
      );
    }
    repairRecoverableStateLocked_({
      ss: ss,
      config: config,
      sheets: {},
      headers: {}
    });

    const purchases = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
    const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
    const events = requiredSheet_(ss, CANN.SHEETS.EVENTS);
    const ledger = requiredSheet_(ss, CANN.SHEETS.LEDGER);
    const migrationReport =
      requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT);
    const applyJournal =
      requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
    const runtimeContext = {
      ss: ss,
      environment: environment_(),
      config: config,
      sheets: {
        purchases: purchases,
        responses: responses,
        events: events,
        ledger: ledger,
        config: configSheet,
        migrationReport: migrationReport,
        applyJournal: applyJournal
      },
      headers: {
        purchases: headerMap_(purchases),
        responses: headerMap_(responses),
        events: headerMap_(events),
        ledger: headerMap_(ledger),
        config: headerMap_(configSheet),
        migrationReport: headerMap_(migrationReport),
        applyJournal: headerMap_(applyJournal)
      }
    };
    const responseHeaders = runtimeContext.headers.responses;
    const eventHeaders = runtimeContext.headers.events;
    const responseRows = responses.getLastRow() < 2
      ? []
      : responses.getRange(
        2,
        1,
        responses.getLastRow() - 1,
        responses.getLastColumn()
      ).getValues();
    const canonicalRows = events.getLastRow() < 2
      ? []
      : events.getRange(
        2,
        1,
        events.getLastRow() - 1,
        events.getLastColumn()
      ).getValues();
    const identifiedByEventId = {};
    responseRows.forEach((row, index) => {
      const eventId = text_(value_(
        row,
        responseHeaders,
        CANN.COMPATIBILITY_EVENT_HEADER
      ));
      if (eventId) identifiedByEventId[eventId] = index + 2;
    });
    const canonicalUsesByProduct = {};
    const canonicalByFingerprint = {};
    canonicalRows.forEach((row, canonicalIndex) => {
      const legacyId = text_(value_(
        row,
        eventHeaders,
        'Legacy Product ID'
      ));
      if (!legacyId) return;
      canonicalUsesByProduct[legacyId] =
        finiteNumberOr_(canonicalUsesByProduct[legacyId], 0) +
        finiteNumberOr_(value_(row, eventHeaders, 'Uses'), 0);
      const fingerprint = canonicalCompatibilityFingerprint_(
        value_(row, eventHeaders, 'Timestamp'),
        legacyId,
        value_(row, eventHeaders, 'Uses'),
        value_(row, eventHeaders, 'Weight Code'),
        value_(row, eventHeaders, 'Finished')
      );
      if (!canonicalByFingerprint[fingerprint]) {
        canonicalByFingerprint[fingerprint] = [];
      }
      canonicalByFingerprint[fingerprint].push({
        row: row,
        rowNumber: canonicalIndex + 2
      });
    });

    let relinkedRows = 0;
    let canonicalizedRows = 0;
    const unresolved = [];
    const relinkRequests = [];
    const repairedSourceRows = [];
    const affectedProductIds = {};
    const maintenanceProductContext = productContext_(ss, {
      runtimeContext: runtimeContext
    });
    responseRows.forEach((row, index) => {
      const sourceRow = index + 2;
      if (text_(value_(
        row,
        responseHeaders,
        CANN.COMPATIBILITY_EVENT_HEADER
      ))) return;
      const timestamp = dateOrNull_(
        value_(row, responseHeaders, 'Timestamp')
      );
      const legacyId = text_(value_(row, responseHeaders, 'Product'));
      const uses = finiteNumber_(value_(row, responseHeaders, 'Uses'));
      if (!timestamp || !legacyId || uses == null) return;
      const weightCode = text_(
        value_(row, responseHeaders, 'Weight code')
      );
      const finished = truthy_(
        value_(row, responseHeaders, 'Mark as Finished?')
      );
      const fingerprint = canonicalCompatibilityFingerprint_(
        timestamp,
        legacyId,
        uses,
        weightCode,
        finished
      );
      const allCandidates = canonicalByFingerprint[fingerprint] || [];
      const candidates = allCandidates.filter(candidate => {
        const candidateEventId = text_(value_(
          candidate.row,
          eventHeaders,
          'Event UUID'
        ));
        return candidateEventId && !identifiedByEventId[candidateEventId];
      });

      if (candidates.length === 1) {
        const candidate = candidates[0];
        const eventId = text_(value_(
          candidate.row,
          eventHeaders,
          'Event UUID'
        ));
        const requestId = text_(value_(
          candidate.row,
          eventHeaders,
          'Request UUID'
        ));
        relinkRequests.push(updateCellsRequest_(
          responses,
          sourceRow,
          responseHeaders[CANN.COMPATIBILITY_EVENT_HEADER] + 1,
          [[eventId, requestId]]
        ));
        relinkRequests.push(updateCellsRequest_(
          events,
          candidate.rowNumber,
          eventHeaders['Legacy Source Sheet'] + 1,
          [[responses.getName(), sourceRow]]
        ));
        identifiedByEventId[eventId] = sourceRow;
        repairedSourceRows.push(sourceRow);
        affectedProductIds[legacyId] = true;
        relinkedRows++;
        return;
      }
      if (candidates.length > 1) {
        unresolved.push({
          type: 'AMBIGUOUS_CANONICAL_MATCH',
          sourceRow: sourceRow,
          candidateRows: candidates.map(candidate => candidate.rowNumber)
        });
        return;
      }
      if (allCandidates.length) {
        unresolved.push({
          type: 'CANONICAL_MATCH_ALREADY_IDENTIFIED',
          sourceRow: sourceRow,
          candidateRows: allCandidates.map(candidate => candidate.rowNumber)
        });
        return;
      }

      const context = maintenanceProductContext;
      const product = context.byLegacyId[legacyId];
      const canonicalUses =
        finiteNumberOr_(canonicalUsesByProduct[legacyId], 0);
      if (!product ||
          Math.abs(finiteNumberOr_(product.uses, 0) - canonicalUses) >
            1e-9) {
        unresolved.push({
          type: 'UNMATCHED_COMPATIBILITY_ROW',
          sourceRow: sourceRow,
          productId: legacyId,
          currentProductUses: product
            ? finiteNumberOr_(product.uses, 0)
            : null,
          canonicalUses: canonicalUses
        });
        return;
      }
      const eventId = deterministicLegacyEventUuid_(
        ss.getId(),
        responses.getName(),
        sourceRow
      );
      if (eventContext_(
        ss,
        runtimeContext,
        [eventId]
      ).eventIds.has(eventId)) {
        unresolved.push({
          type: 'DETERMINISTIC_EVENT_ID_CONFLICT',
          sourceRow: sourceRow,
          eventId: eventId
        });
        return;
      }
      const event = {
        eventId: eventId,
        timestamp: timestamp,
        localDate: formatDate_(timestamp),
        localTime: formatTime_(timestamp),
        productUuid: product.productUuid,
        legacyProductId: legacyId,
        uses: uses,
        weightCode: weightCode,
        isFinished: finished,
        source: 'PREPARED_COMPATIBILITY_REPAIR',
        requestId: '',
        legacySourceSheet: responses.getName(),
        legacySourceRow: sourceRow,
        compatibilityRow: null
      };
      applyRecoverableSyncLocked_({
        runtimeContext: runtimeContext,
        productContext: context,
        stagedPurchases: [],
        stagedConsumptions: [event],
        kind: 'PREPARED_COMPATIBILITY_REPAIR',
        apiVersion: 0,
        requestId: '',
        response: null,
        formRefreshRequired: true,
        compatibilityExistingRow: sourceRow,
        ledger: null,
        timing: null
      });
      canonicalUsesByProduct[legacyId] = canonicalUses + uses;
      const appendedCanonical = buildRecoverableEventRows_([event])[0];
      canonicalRows.push(appendedCanonical);
      const appendedFingerprint = canonicalCompatibilityFingerprint_(
        event.timestamp,
        event.legacyProductId,
        event.uses,
        event.weightCode,
        event.isFinished
      );
      if (!canonicalByFingerprint[appendedFingerprint]) {
        canonicalByFingerprint[appendedFingerprint] = [];
      }
      canonicalByFingerprint[appendedFingerprint].push({
        row: appendedCanonical,
        rowNumber: events.getLastRow()
      });
      identifiedByEventId[eventId] = sourceRow;
      repairedSourceRows.push(sourceRow);
      affectedProductIds[legacyId] = true;
      canonicalizedRows++;
    });

    sheetsBatchUpdateInChunks_(ss, relinkRequests, 400);
    const affectedProducts = Object.keys(affectedProductIds);
    if (affectedProducts.length) {
      rebuildProductProjectionsFromCanonical_(ss, affectedProducts);
    }
    const productReconciliation =
      reconcileProductProjections_(ss, affectedProducts);
    const summaryReconciliation = reconcileInteractionSummary_(ss);
    const compatibilityReconciliation =
      reconcileReliabilityMigration_(ss);
    const projectionsClean =
      productReconciliation.differences.length === 0 &&
      summaryReconciliation.differences.length === 0 &&
      compatibilityReconciliation.differences.length === 0 &&
      compatibilityReconciliation.responseEventCount ===
        compatibilityReconciliation.canonicalEventCount;
    let resolvedMigrationRows = 0;
    if (!unresolved.length && projectionsClean) {
      resolvedMigrationRows = resolveMigrationIssuesForSourceRows_(
        ss,
        responses.getName(),
        repairedSourceRows,
        'Resolved by recoverable compatibility repair'
      );
    }

    const result = {
      relinkedRows: relinkedRows,
      canonicalizedRows: canonicalizedRows,
      rebuiltProducts: affectedProducts.length,
      productProjectionDifferences:
        productReconciliation.differences.length,
      resolvedMigrationRows: resolvedMigrationRows,
      unresolvedRows: unresolved.length,
      unresolved: unresolved
    };
    console.log(JSON.stringify({
      type: 'prepared_compatibility_rows_repaired',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function canonicalCompatibilityFingerprint_(
  timestamp,
  legacyProductId,
  uses,
  weightCode,
  finished
) {
  return JSON.stringify([
    timestampMillisOrNull_(timestamp),
    text_(legacyProductId),
    finiteNumber_(uses),
    text_(weightCode),
    truthy_(finished)
  ]);
}

function effectiveConsumptionState_(
  ss,
  runtimeContext,
  stagedConsumptions,
  stagedCorrections
) {
  const runtimeConfig = runtimeContext
    ? { values: runtimeContext.config }
    : readAndAssertRuntimeConfig_(ss, environment_());
  const capability = consumptionCorrectionCapability_(
    runtimeConfig.values
  );
  const purchases = runtimeContext
    ? runtimeContext.sheets.purchases
    : requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const events = runtimeContext
    ? runtimeContext.sheets.events
    : requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const corrections = capability.version
    ? (
      runtimeContext
        ? runtimeContext.sheets.corrections
        : requiredSheet_(ss, CANN.SHEETS.CORRECTIONS)
    )
    : null;
  const purchaseHeaders = runtimeContext
    ? runtimeContext.headers.purchases
    : headerMap_(purchases);
  const eventHeaders = runtimeContext
    ? runtimeContext.headers.events
    : headerMap_(events);
  const correctionHeaders = corrections
    ? (
      runtimeContext
        ? runtimeContext.headers.corrections
        : headerMap_(corrections)
    )
    : {};
  requireExactHeaders_(
    purchaseHeaders,
    CANN.PURCHASE_HEADERS,
    CANN.SHEETS.PURCHASES
  );
  requireExactHeaders_(
    eventHeaders,
    CANN.EVENT_HEADERS,
    CANN.SHEETS.EVENTS
  );
  if (corrections) {
    requireExactHeaders_(
      correctionHeaders,
      CANN.CORRECTION_HEADERS,
      CANN.SHEETS.CORRECTIONS
    );
  }
  const applyEvidence = capability.version
    ? recoverableApplyEvidence_(ss, runtimeContext)
    : {
      eventIds: {},
      correctionIds: {},
      finishActionProductIds: {}
    };
  const snapshot = {
    environment: runtimeContext
      ? runtimeContext.environment
      : environment_(),
    taxRate: finiteNumberOr_(runtimeConfig.values.TAX_RATE, 0.13),
    purchaseHeaders: purchaseHeaders,
    eventHeaders: eventHeaders,
    correctionHeaders: correctionHeaders,
    purchaseRows: readAnalyticsRowsThrough_(
      purchases,
      purchases.getLastRow()
    ),
    eventRows: readAnalyticsRowsThrough_(events, events.getLastRow()),
    correctionRows: corrections
      ? readAnalyticsRowsThrough_(corrections, corrections.getLastRow())
      : [],
    eventLastRow: events.getLastRow(),
    correctionLastRow: corrections ? corrections.getLastRow() : 1,
    correctionVersion: capability.version,
    correctionWritesEnabled: capability.writesEnabled,
    finishActionProductIds: applyEvidence.finishActionProductIds,
    finishProvenanceEventIds: applyEvidence.eventIds,
    finishProvenanceCorrectionIds: applyEvidence.correctionIds
  };
  const quality = newAnalyticsQuality_();
  const products = normalizeAnalyticsProducts_(snapshot, quality);
  const canonicalEvents = normalizeAnalyticsEvents_(
    snapshot,
    products,
    quality
  );
  (stagedConsumptions || []).forEach((item, index) => {
    const productUuid = text_(item.productUuid).toLowerCase();
    const legacyProductId = text_(item.legacyProductId);
    const product = (
      productUuid && products.byProductUuid[productUuid]
    ) || (
      legacyProductId && products.byProductId[legacyProductId]
    );
    // A consumption for a purchase created in this same request is projected
    // by the existing purchase plan because that product does not exist in the
    // spreadsheet snapshot yet.
    if (!product) return;
    canonicalEvents.push({
      canonicalRow: snapshot.eventLastRow + index + 1,
      eventUuid: text_(item.eventId).toLowerCase(),
      occurredAtEpochMillis: dateOrNow_(item.timestamp).getTime(),
      localDate: text_(item.localDate),
      localTime: text_(item.localTime),
      product: product,
      quantity: finiteNumberOr_(item.uses, 0),
      weightCode: text_(item.weightCode) || null,
      finished: item.isFinished === true,
      source: text_(item.source) || 'ANDROID_V2'
    });
  });
  const resolution = resolveAnalyticsCorrections_(
    snapshot,
    products,
    canonicalEvents,
    stagedCorrections || []
  );
  return {
    snapshot: snapshot,
    quality: quality,
    products: products,
    canonicalEvents: canonicalEvents,
    resolution: resolution
  };
}

function effectiveProductProjectionMap_(events, reopenedProducts, options) {
  const settings = options || {};
  const projections = {};
  (events || []).forEach(event => {
    const legacyProductId = event.product.productId;
    const projection = projections[legacyProductId] ||
      (projections[legacyProductId] = {
        eventCount: 0,
        uses: 0,
        mostRecentUse: null,
        mostRecentMillis: null,
        lastQuantity: null,
        hasFinishedEvent: false,
        finishedEventCount: 0,
        finishedAt: null,
        finishedAtMillis: null,
        lastCanonicalRow: null
      });
    projection.eventCount++;
    projection.uses += event.quantity;
    if (
      projection.mostRecentMillis == null ||
      event.occurredAtEpochMillis > projection.mostRecentMillis
    ) {
      projection.mostRecentUse = new Date(event.occurredAtEpochMillis);
      projection.mostRecentMillis = event.occurredAtEpochMillis;
      projection.lastQuantity = event.quantity;
    }
    if (event.finished) {
      projection.hasFinishedEvent = true;
      projection.finishedEventCount++;
      if (
        projection.finishedAtMillis == null ||
        event.occurredAtEpochMillis > projection.finishedAtMillis
      ) {
        projection.finishedAt = new Date(event.occurredAtEpochMillis);
        projection.finishedAtMillis = event.occurredAtEpochMillis;
      }
    }
    projection.lastCanonicalRow = event.canonicalRow;
  });
  Object.keys(reopenedProducts || {}).forEach(legacyProductId => {
    const product = settings.productsByProductId
      ? settings.productsByProductId[legacyProductId]
      : null;
    if (
      product &&
      finishActionEvidenceForProduct_(
        settings.finishActionProductIds || {},
        product
      )
    ) {
      return;
    }
    const projection = projections[legacyProductId] ||
      (projections[legacyProductId] = {
        eventCount: 0,
        uses: 0,
        mostRecentUse: null,
        mostRecentMillis: null,
        lastQuantity: null,
        hasFinishedEvent: false,
        finishedEventCount: 0,
        finishedAt: null,
        finishedAtMillis: null,
        lastCanonicalRow: null
      });
    projection.reopenRequested = true;
    projection.reopenBasisFinishedAtMillis =
      reopenedProducts[legacyProductId].basisFinishedAtEpochMillis;
  });
  return projections;
}

function reconcileConsumptionCorrections_(ss) {
  const state = effectiveConsumptionState_(ss);
  const projectionReconciliation = reconcileProductProjections_(ss, null);
  return {
    rawEvents: state.snapshot.eventRows.length,
    correctionRows: state.snapshot.correctionRows.length,
    effectiveEvents: state.resolution.effectiveEvents.length,
    revisionChains: Object.keys(state.resolution.groupsByTarget).length,
    differences: projectionReconciliation.differences
  };
}

function canonicalProductProjections_(ss) {
  if (typeof PropertiesService === 'undefined') {
    return rawCanonicalProductProjections_(ss);
  }
  const runtimeConfig = readAndAssertRuntimeConfig_(ss, environment_());
  const capability = consumptionCorrectionCapability_(
    runtimeConfig.values
  );
  if (!capability.version) return rawCanonicalProductProjections_(ss);
  const state = effectiveConsumptionState_(ss);
  return effectiveProductProjectionMap_(
    state.resolution.effectiveEvents,
    state.resolution.reopenedProducts,
    {
      finishActionProductIds: state.snapshot.finishActionProductIds,
      productsByProductId: state.products.byProductId
    }
  );
}

function rawCanonicalProductProjections_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const headers = headerMap_(sheet);
  const projections = {};
  const lastRow = sheet.getLastRow();
  const rows = lastRow < 2
    ? []
    : sheet.getRange(
      2,
      1,
      lastRow - 1,
      sheet.getLastColumn()
    ).getValues();
  rows.forEach((row, index) => {
    const legacyProductId = text_(value_(
      row,
      headers,
      'Legacy Product ID'
    ));
    if (!legacyProductId) return;
    const projection = projections[legacyProductId] ||
      (projections[legacyProductId] = {
        eventCount: 0,
        uses: 0,
        mostRecentUse: null,
        mostRecentMillis: null,
        lastQuantity: null,
        hasFinishedEvent: false,
        finishedAt: null,
        lastCanonicalRow: null
      });
    projection.eventCount++;
    projection.uses += finiteNumberOr_(
      value_(row, headers, 'Uses'),
      0
    );
    const timestamp = value_(row, headers, 'Timestamp');
    const timestampMillis = timestampMillisOrNull_(timestamp);
    if (timestampMillis != null &&
        (projection.mostRecentMillis == null ||
         timestampMillis > projection.mostRecentMillis)) {
      projection.mostRecentUse = timestamp;
      projection.mostRecentMillis = timestampMillis;
      projection.lastQuantity = finiteNumberOr_(
        value_(row, headers, 'Uses'),
        0
      );
    }
    if (truthy_(value_(row, headers, 'Finished'))) {
      projection.hasFinishedEvent = true;
      projection.finishedAt = timestamp;
    }
    projection.lastCanonicalRow = index + 2;
  });
  return projections;
}

function expectedProjectionForProduct_(
  product,
  canonicalProjection,
  options
) {
  const settings = options || {};
  const canonical = canonicalProjection || {
    eventCount: 0,
    uses: 0,
    mostRecentUse: null,
    mostRecentMillis: null,
    lastQuantity: null,
    hasFinishedEvent: false,
    finishedAt: null
  };
  let status = allowedStatusOr_(product.status, CANN.STATUS.UNOPENED);
  let finishedAt = product.finishedAt;
  const storedFinishedAtMillis = timestampMillisOrNull_(product.finishedAt);
  const replayedReopen = canonical.reopenRequested === true &&
    (
      storedFinishedAtMillis == null ||
      storedFinishedAtMillis === canonical.reopenBasisFinishedAtMillis
    );
  if (canonical.hasFinishedEvent) {
    status = CANN.STATUS.FINISHED;
    finishedAt = canonical.finishedAt;
  } else if (settings.reopenProduct === true || replayedReopen) {
    status = canonical.eventCount > 0
      ? CANN.STATUS.ACTIVE
      : CANN.STATUS.UNOPENED;
    finishedAt = null;
  } else if (canonical.eventCount > 0 && status === CANN.STATUS.UNOPENED) {
    status = CANN.STATUS.ACTIVE;
  }
  return {
    status: status,
    uses: canonical.uses,
    mostRecentUse: canonical.mostRecentUse,
    mostRecentMillis: canonical.mostRecentMillis,
    lastQuantity: canonical.lastQuantity,
    finishedAt: finishedAt
  };
}

function rebuildProductProjectionsFromCanonical_(ss, productIds) {
  const context = productContext_(ss);
  const canonical = canonicalProductProjections_(ss);
  const requests = [];
  (productIds || []).forEach(legacyProductId => {
    const product = context.byLegacyId[legacyProductId];
    if (!product) {
      throw new Error(
        'PRODUCT_PROJECTION_REBUILD_BLOCKED: missing ' + legacyProductId
      );
    }
    const expected = expectedProjectionForProduct_(
      product,
      canonical[legacyProductId]
    );
    const headers = context.headers;
    requests.push(updateCellsRequest_(
      context.purchasesSheet,
      product.rowNumber,
      headers['Finished'] + 1,
      [[expected.status]]
    ));
    requests.push(updateCellsRequest_(
      context.purchasesSheet,
      product.rowNumber,
      headers['Uses'] + 1,
      [[expected.uses]]
    ));
    requests.push(updateCellsRequest_(
      context.purchasesSheet,
      product.rowNumber,
      headers['Most recent use'] + 1,
      [[expected.mostRecentUse || '']]
    ));
    requests.push(updateCellsRequest_(
      context.purchasesSheet,
      product.rowNumber,
      headers['Finished At'] + 1,
      [[expected.finishedAt || '']]
    ));
    requests.push(updateCellsRequest_(
      context.purchasesSheet,
      product.rowNumber,
      headers['Last quantity'] + 1,
      [[expected.lastQuantity == null ? '' : expected.lastQuantity]]
    ));
  });
  sheetsBatchUpdateInChunks_(ss, requests, 400);
  return { rebuiltProducts: (productIds || []).length };
}

/**
 * Explicit repair entrypoint. It is intentionally separate from read-only
 * reconciliation because it rewrites the five derived Purchases projection
 * cells from effective history.
 */
function rebuildEffectiveProductProjections() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    assertSpreadsheetTimeZone_(ss);
    const runtimeConfig = readAndAssertRuntimeConfig_(ss, environment_());
    const capability = consumptionCorrectionCapability_(
      runtimeConfig.values
    );
    if (capability.version !== CANN.CONSUMPTION_CORRECTION_VERSION) {
      throw new Error(
        'CORRECTION_REBUILD_BLOCKED: correction schema is not provisioned'
      );
    }
    const context = productContext_(ss);
    const projections = canonicalProductProjections_(ss);
    const productIds = Array.from(new Set(
      Object.keys(context.byLegacyId).concat(Object.keys(projections))
    ));
    bumpMutationWatermark_();
    const rebuilt = rebuildProductProjectionsFromCanonical_(
      ss,
      productIds
    );
    SpreadsheetApp.flush();
    const reconciliation = reconcileProductProjections_(ss, productIds);
    if (reconciliation.differences.length) {
      throw new Error(
        'CORRECTION_REBUILD_FAILED: ' +
        JSON.stringify(reconciliation.differences)
      );
    }
    bumpMutationWatermark_();
    return {
      rebuiltProducts: rebuilt.rebuiltProducts,
      differences: reconciliation.differences
    };
  } finally {
    lock.releaseLock();
  }
}

/**
 * Read-only maintenance entrypoint for production rollout and incident checks.
 * Passing null deliberately reconciles every product found in Purchases or
 * canonical history; it never invokes the projection rebuild helper.
 */
function reconcileProductProjections() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    assertSpreadsheetTimeZone_(ss);
    const result = reconcileProductProjections_(ss, null);
    console.log(JSON.stringify({
      type: 'product_projections_reconciled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function reconcileProductProjections_(ss, productIds) {
  const context = productContext_(ss);
  const canonical = canonicalProductProjections_(ss);
  const ids = productIds == null
    ? Array.from(new Set(
      Object.keys(context.byLegacyId).concat(Object.keys(canonical))
    ))
    : Array.from(new Set(productIds));
  const differences = [];
  ids.forEach(legacyProductId => {
    const product = context.byLegacyId[legacyProductId];
    if (!product) {
      differences.push({
        type: 'MISSING_PRODUCT',
        legacyProductId: legacyProductId
      });
      return;
    }
    const expected = expectedProjectionForProduct_(
      product,
      canonical[legacyProductId]
    );
    const actualRecentMillis = timestampMillisOrNull_(
      product.mostRecentUse
    );
    const actualFinishedMillis = timestampMillisOrNull_(
      product.finishedAt
    );
    const expectedFinishedMillis = timestampMillisOrNull_(
      expected.finishedAt
    );
    if (Math.abs(finiteNumberOr_(product.uses, 0) - expected.uses) >
          1e-9 ||
        allowedStatusOr_(product.status, CANN.STATUS.UNOPENED) !==
          expected.status ||
        actualRecentMillis !== expected.mostRecentMillis ||
        optionalFiniteNumber_(product.lastQuantity) !==
          optionalFiniteNumber_(expected.lastQuantity) ||
        actualFinishedMillis !== expectedFinishedMillis) {
      differences.push({
        type: 'PRODUCT_PROJECTION_MISMATCH',
        legacyProductId: legacyProductId,
        rowNumber: product.rowNumber,
        expected: expected,
        actual: {
          status: product.status,
          uses: product.uses,
          mostRecentMillis: actualRecentMillis,
          lastQuantity: product.lastQuantity,
          finishedAtMillis: actualFinishedMillis
        }
      });
    }
  });
  return {
    checkedProducts: ids.length,
    differences: differences
  };
}

function resolveMigrationIssuesForSourceRows_(
  ss,
  sourceSheet,
  sourceRows,
  resolution
) {
  const uniqueRows = {};
  (sourceRows || []).forEach(row => { uniqueRows[Number(row)] = true; });
  if (!Object.keys(uniqueRows).length) return 0;
  const sheet = ensureMigrationResolutionSchema_(ss);
  const headers = headerMap_(sheet);
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return 0;
  const rows = sheet.getRange(
    2,
    1,
    lastRow - 1,
    sheet.getLastColumn()
  ).getValues();
  const resolutionValues = rows.map(row => [
    value_(row, headers, 'Resolved At'),
    value_(row, headers, 'Resolution')
  ]);
  let resolved = 0;
  rows.forEach((row, index) => {
    if (text_(value_(row, headers, 'Source Sheet')) !== sourceSheet ||
        !uniqueRows[Number(value_(row, headers, 'Source Row'))] ||
        value_(row, headers, 'Resolved At')) return;
    resolutionValues[index] = [new Date(), resolution];
    resolved++;
  });
  if (resolved) {
    sheet.getRange(
      2,
      headers['Resolved At'] + 1,
      resolutionValues.length,
      2
    ).setValues(resolutionValues);
    SpreadsheetApp.flush();
  }
  return resolved;
}

function enableRecoverableSyncApply() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    assertSpreadsheetTimeZone_(ss);
    assertAdvancedSheetsService_();
    ensureCompatibilityIdentitySchema_(ss);
    ensureSheet_(ss, CANN.SHEETS.APPLY_JOURNAL, CANN.APPLY_JOURNAL_HEADERS);
    ensureConfigKey_(
      ss,
      CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY,
      0,
      'Recoverable multi-sheet apply version'
    );
    ensureConfigKey_(
      ss,
      CANN.PENDING_APPLY_KEY,
      '',
      'Apply UUID awaiting finalization'
    );
    repairRecoverableStateLocked_({ ss: ss, config: {}, sheets: {}, headers: {} });
    // Close the prepare/deploy/enable window: marker-0 writes made after the
    // first prepare receive their identities before marker 1 becomes visible.
    backfillCompatibilityIdentities_(ss);

    const configSheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
    const config = configValuesFromSheet_(configSheet, headerMap_(configSheet));
    if (!interactionSummaryReady_(config)) {
      throw new Error(
        'RECOVERABLE_APPLY_ENABLE_BLOCKED: interaction summary fast path must be enabled first'
      );
    }
    const reconciliation = reconcileRecoverableSyncApply_(ss);
    if (reconciliation.blockingDifferences.length) {
      throw new Error(
        'RECOVERABLE_APPLY_ENABLE_BLOCKED: ' +
        JSON.stringify(reconciliation.blockingDifferences.slice(0, 10))
      );
    }
    setConfigValue_(
      ss,
      CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY,
      CANN.RECOVERABLE_SYNC_APPLY_VERSION,
      'Recoverable multi-sheet apply version'
    );
    SpreadsheetApp.flush();
    const result = {
      configVersion: CANN.RECOVERABLE_SYNC_APPLY_VERSION,
      fastPathEnabled: true,
      blockingDifferences: 0
    };
    console.log(JSON.stringify({
      type: 'recoverable_sync_apply_enabled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function disableRecoverableSyncApply() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    repairRecoverableStateLocked_({ ss: ss, config: {}, sheets: {}, headers: {} });
    setConfigValue_(
      ss,
      CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY,
      0,
      'Recoverable multi-sheet apply version'
    );
    SpreadsheetApp.flush();
    const result = {
      configVersion: 0,
      fastPathEnabled: false,
      fallback: 'LEGACY_MULTI_WRITE'
    };
    console.log(JSON.stringify({
      type: 'recoverable_sync_apply_disabled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function repairRecoverableSyncApply() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const runtimeContext = {
      ss: ss,
      config: {},
      sheets: {},
      headers: {}
    };
    const pending = repairRecoverableStateLocked_(runtimeContext);
    const configSheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
    runtimeContext.config = configValuesFromSheet_(
      configSheet,
      headerMap_(configSheet)
    );
    runtimeContext.sheets = {
      purchases: requiredSheet_(ss, CANN.SHEETS.PURCHASES),
      responses: requiredSheet_(ss, CANN.SHEETS.RESPONSES),
      events: requiredSheet_(ss, CANN.SHEETS.EVENTS),
      ledger: requiredSheet_(ss, CANN.SHEETS.LEDGER),
      config: configSheet,
      migrationReport: requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT),
      applyJournal: requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL)
    };
    runtimeContext.headers = {
      purchases: headerMap_(runtimeContext.sheets.purchases),
      responses: headerMap_(runtimeContext.sheets.responses),
      events: headerMap_(runtimeContext.sheets.events),
      ledger: headerMap_(runtimeContext.sheets.ledger),
      config: headerMap_(configSheet),
      migrationReport: headerMap_(runtimeContext.sheets.migrationReport),
      applyJournal: headerMap_(runtimeContext.sheets.applyJournal)
    };
    const orphans = recoverableSyncApplyReady_(runtimeContext.config)
      ? repairOrphanFormResponsesLocked_(runtimeContext)
      : { repairedRows: 0, scannedRows: 0, issues: [] };
    const result = { pending: pending, orphanForms: orphans };
    console.log(JSON.stringify({
      type: 'recoverable_sync_apply_repaired',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function reconcileRecoverableSyncApply() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const result = reconcileRecoverableSyncApply_(ss);
    console.log(JSON.stringify({
      type: 'recoverable_sync_apply_reconciled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function ensureCompatibilityIdentitySchema_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const headers = headerMap_(sheet);
  const eventIndex = headers[CANN.COMPATIBILITY_EVENT_HEADER];
  const requestIndex = headers[CANN.COMPATIBILITY_REQUEST_HEADER];
  if ((eventIndex === undefined) !== (requestIndex === undefined)) {
    throw new Error(
      'SCHEMA_MISMATCH: incomplete compatibility identity header pair'
    );
  }
  if (eventIndex !== undefined) {
    requireCompatibilityIdentityHeaders_(headers);
    return {
      eventColumn: eventIndex + 1,
      requestColumn: requestIndex + 1,
      originalOwnedColumns: eventIndex
    };
  }

  // getLastColumn() is the last column with content, not the grid width. This
  // places the identities after the actual Form-owned columns: J/K in the
  // inspected production sheet and H/I in the inspected sandbox.
  const firstIdentityColumn = sheet.getLastColumn() + 1;
  if (sheet.getMaxColumns() < firstIdentityColumn + 1) {
    sheet.insertColumnsAfter(
      sheet.getMaxColumns(),
      firstIdentityColumn + 1 - sheet.getMaxColumns()
    );
  }
  sheet.getRange(1, firstIdentityColumn, 1, 2).setValues([[
    CANN.COMPATIBILITY_EVENT_HEADER,
    CANN.COMPATIBILITY_REQUEST_HEADER
  ]]);
  return {
    eventColumn: firstIdentityColumn,
    requestColumn: firstIdentityColumn + 1,
    originalOwnedColumns: firstIdentityColumn - 1
  };
}

function backfillCompatibilityIdentities_(ss) {
  const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const responseHeaders = headerMap_(responses);
  requireCompatibilityIdentityHeaders_(responseHeaders);
  const events = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const eventHeaders = headerMap_(events);
  requireExactHeaders_(eventHeaders, CANN.EVENT_HEADERS, CANN.SHEETS.EVENTS);
  const responseLastRow = responses.getLastRow();
  if (responseLastRow < 2) return { rowsBackfilled: 0 };

  const responseRows = responses.getRange(
    2,
    1,
    responseLastRow - 1,
    responses.getLastColumn()
  ).getValues();
  const identityValues = responseRows.map(row => [
    value_(row, responseHeaders, CANN.COMPATIBILITY_EVENT_HEADER),
    value_(row, responseHeaders, CANN.COMPATIBILITY_REQUEST_HEADER)
  ]);
  const seenSourceRows = {};
  const validationErrors = [];
  let rowsBackfilled = 0;

  readDataRows_(events).forEach(row => {
    if (text_(value_(row, eventHeaders, 'Legacy Source Sheet')) !==
        responses.getName()) return;
    const sourceRow = Number(value_(row, eventHeaders, 'Legacy Source Row'));
    const eventId = text_(value_(row, eventHeaders, 'Event UUID'));
    if (!Number.isInteger(sourceRow) || sourceRow < 2 ||
        sourceRow > responseLastRow) {
      validationErrors.push({
        type: 'INVALID_SOURCE_ROW',
        eventId: eventId,
        sourceRow: sourceRow
      });
      return;
    }
    if (seenSourceRows[sourceRow] && seenSourceRows[sourceRow] !== eventId) {
      validationErrors.push({
        type: 'DUPLICATE_SOURCE_ROW',
        sourceRow: sourceRow,
        firstEventId: seenSourceRows[sourceRow],
        eventId: eventId
      });
      return;
    }
    seenSourceRows[sourceRow] = eventId;
    const responseRow = responseRows[sourceRow - 2];
    const timestampMatches =
      timestampMillisOrNull_(value_(responseRow, responseHeaders, 'Timestamp')) ===
      timestampMillisOrNull_(value_(row, eventHeaders, 'Timestamp'));
    const productMatches =
      text_(value_(responseRow, responseHeaders, 'Product')) ===
      text_(value_(row, eventHeaders, 'Legacy Product ID'));
    const responseUses = finiteNumber_(value_(responseRow, responseHeaders, 'Uses'));
    const canonicalUses = finiteNumber_(value_(row, eventHeaders, 'Uses'));
    const usesMatches = responseUses != null && canonicalUses != null &&
      Math.abs(responseUses - canonicalUses) <= 1e-9;
    if (!timestampMatches || !productMatches || !usesMatches) {
      validationErrors.push({
        type: 'SOURCE_ROW_CONTENT_MISMATCH',
        eventId: eventId,
        sourceRow: sourceRow
      });
      return;
    }

    const requestId = text_(value_(row, eventHeaders, 'Request UUID'));
    const existingEventId = text_(identityValues[sourceRow - 2][0]);
    const existingRequestId = text_(identityValues[sourceRow - 2][1]);
    if ((existingEventId && existingEventId !== eventId) ||
        (existingRequestId && existingRequestId !== requestId)) {
      validationErrors.push({
        type: 'IDENTITY_CONFLICT',
        eventId: eventId,
        sourceRow: sourceRow
      });
      return;
    }
    if (!existingEventId && eventId) rowsBackfilled++;
    identityValues[sourceRow - 2] = [eventId, requestId];
  });

  responses.getRange(
    2,
    responseHeaders[CANN.COMPATIBILITY_EVENT_HEADER] + 1,
    identityValues.length,
    2
  ).setValues(identityValues);
  SpreadsheetApp.flush();
  if (validationErrors.length) {
    throw new Error(
      'COMPATIBILITY_IDENTITY_BACKFILL_BLOCKED: safe rows were retained; ' +
      JSON.stringify({
        rowsBackfilled: rowsBackfilled,
        validationErrors: validationErrors.slice(0, 10)
      })
    );
  }
  return { rowsBackfilled: rowsBackfilled };
}

function reconcileRecoverableSyncApply_(ss) {
  const differences = [];
  const blockingDifferences = [];
  const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const responseHeaders = headerMap_(responses);
  requireCompatibilityIdentityHeaders_(responseHeaders);
  const events = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const eventHeaders = headerMap_(events);
  requireExactHeaders_(eventHeaders, CANN.EVENT_HEADERS, CANN.SHEETS.EVENTS);
  const responseLastRow = responses.getLastRow();
  const responseRows = responseLastRow < 2
    ? []
    : responses.getRange(
      2,
      1,
      responseLastRow - 1,
      responses.getLastColumn()
    ).getValues();
  const canonicalRows = readDataRows_(events);
  const canonicalByEventId = {};
  const seenSourceRows = {};

  canonicalRows.forEach((row, index) => {
    const eventId = text_(value_(row, eventHeaders, 'Event UUID'));
    if (!eventId) {
      blockingDifferences.push({
        type: 'MALFORMED_CANONICAL_EVENT',
        canonicalRow: index + 2
      });
      return;
    }
    if (canonicalByEventId[eventId]) {
      blockingDifferences.push({
        type: 'DUPLICATE_CANONICAL_EVENT_UUID',
        eventId: eventId
      });
    }
    canonicalByEventId[eventId] = { row: row, rowNumber: index + 2 };
    const lineageSheet = text_(value_(
      row,
      eventHeaders,
      'Legacy Source Sheet'
    ));
    if (lineageSheet !== responses.getName()) {
      blockingDifferences.push({
        type: 'MISSING_OR_INVALID_COMPATIBILITY_LINEAGE',
        eventId: eventId,
        sourceSheet: lineageSheet
      });
      return;
    }

    const sourceRow = Number(value_(row, eventHeaders, 'Legacy Source Row'));
    if (!Number.isInteger(sourceRow) || sourceRow < 2 ||
        sourceRow > responseLastRow) {
      blockingDifferences.push({
        type: 'INVALID_COMPATIBILITY_LINEAGE',
        eventId: eventId,
        sourceRow: sourceRow
      });
      return;
    }
    if (seenSourceRows[sourceRow] && seenSourceRows[sourceRow] !== eventId) {
      blockingDifferences.push({
        type: 'DUPLICATE_COMPATIBILITY_LINEAGE',
        eventId: eventId,
        sourceRow: sourceRow,
        firstEventId: seenSourceRows[sourceRow]
      });
      return;
    }
    seenSourceRows[sourceRow] = eventId;
    const responseRow = responseRows[sourceRow - 2];
    const identityMatches =
      text_(value_(
        responseRow,
        responseHeaders,
        CANN.COMPATIBILITY_EVENT_HEADER
      )) === eventId &&
      text_(value_(
        responseRow,
        responseHeaders,
        CANN.COMPATIBILITY_REQUEST_HEADER
      )) === text_(value_(row, eventHeaders, 'Request UUID'));
    const dataMatches =
      timestampMillisOrNull_(value_(responseRow, responseHeaders, 'Timestamp')) ===
        timestampMillisOrNull_(value_(row, eventHeaders, 'Timestamp')) &&
      text_(value_(responseRow, responseHeaders, 'Product')) ===
        text_(value_(row, eventHeaders, 'Legacy Product ID')) &&
      Math.abs(
        finiteNumberOr_(value_(responseRow, responseHeaders, 'Uses'), NaN) -
        finiteNumberOr_(value_(row, eventHeaders, 'Uses'), NaN)
      ) <= 1e-9;
    if (!identityMatches || !dataMatches) {
      blockingDifferences.push({
        type: 'COMPATIBILITY_LINEAGE_MISMATCH',
        eventId: eventId,
        sourceRow: sourceRow
      });
    }
  });

  if (responseRows.length) {
    const seenCompatibilityIds = {};
    responseRows.forEach((row, index) => {
      const eventId = text_(value_(
        row,
        responseHeaders,
        CANN.COMPATIBILITY_EVENT_HEADER
      ));
      if (!eventId) {
        const dataBearing =
          timestampMillisOrNull_(value_(
            row,
            responseHeaders,
            'Timestamp'
          )) != null &&
          !!text_(value_(row, responseHeaders, 'Product')) &&
          finiteNumber_(value_(row, responseHeaders, 'Uses')) != null;
        if (dataBearing) {
          blockingDifferences.push({
            type: 'UNIDENTIFIED_COMPATIBILITY_ROW',
            sourceRow: index + 2
          });
        }
        return;
      }
      if (seenCompatibilityIds[eventId]) {
        blockingDifferences.push({
          type: 'DUPLICATE_COMPATIBILITY_EVENT_UUID',
          eventId: eventId,
          sourceRow: index + 2
        });
      }
      seenCompatibilityIds[eventId] = true;
      if (!canonicalByEventId[eventId]) {
        blockingDifferences.push({
          type: 'COMPATIBILITY_WITHOUT_CANONICAL',
          eventId: eventId,
          sourceRow: index + 2
        });
      }
    });
  }

  const pendingApplyId = text_(configValue_(ss, CANN.PENDING_APPLY_KEY, ''));
  const journal = requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
  const journalHeaders = headerMap_(journal);
  requireExactHeaders_(
    journalHeaders,
    CANN.APPLY_JOURNAL_HEADERS,
    CANN.SHEETS.APPLY_JOURNAL
  );
  const incompleteJournalIds = [];
  readDataRows_(journal).forEach(row => {
    const applyId = text_(value_(row, journalHeaders, 'Apply UUID'));
    const state = text_(value_(row, journalHeaders, 'State'));
    if (state !== 'CORE_COMMITTED' && state !== 'COMPLETE') {
      blockingDifferences.push({
        type: 'INVALID_JOURNAL_STATE',
        applyId: applyId,
        state: state
      });
    }
    if (state === 'CORE_COMMITTED') incompleteJournalIds.push(applyId);
  });
  if (pendingApplyId) {
    blockingDifferences.push({
      type: 'PENDING_APPLY',
      applyId: pendingApplyId
    });
  }
  incompleteJournalIds.forEach(applyId => {
    blockingDifferences.push({
      type: 'INCOMPLETE_JOURNAL',
      applyId: applyId,
      pendingPointerMatches: pendingApplyId === applyId
    });
  });
  if (pendingApplyId &&
      incompleteJournalIds.indexOf(pendingApplyId) < 0) {
    blockingDifferences.push({
      type: 'PENDING_POINTER_WITHOUT_INCOMPLETE_JOURNAL',
      applyId: pendingApplyId
    });
  }
  if (incompleteJournalIds.length > 1) {
    blockingDifferences.push({
      type: 'MULTIPLE_INCOMPLETE_JOURNALS',
      applyIds: incompleteJournalIds
    });
  }

  const summary = reconcileInteractionSummary_(ss);
  summary.differences.forEach(item => {
    blockingDifferences.push({
      type: 'INTERACTION_SUMMARY_' + item.type,
      detail: item
    });
  });
  const compatibilityCanonical = reconcileReliabilityMigration_(ss);
  compatibilityCanonical.differences.forEach(item => {
    blockingDifferences.push({
      type: 'COMPATIBILITY_CANONICAL_DRIFT',
      detail: item
    });
  });
  if (compatibilityCanonical.responseEventCount !==
      compatibilityCanonical.canonicalEventCount) {
    blockingDifferences.push({
      type: 'COMPATIBILITY_CANONICAL_COUNT_MISMATCH',
      responseEventCount: compatibilityCanonical.responseEventCount,
      canonicalEventCount: compatibilityCanonical.canonicalEventCount
    });
  }
  if (compatibilityCanonical.unresolvedRows) {
    blockingDifferences.push({
      type: 'UNRESOLVED_MIGRATION_ROWS',
      unresolvedRows: compatibilityCanonical.unresolvedRows
    });
  }

  differences.push.apply(differences, blockingDifferences);
  return {
    pendingApplyId: pendingApplyId || null,
    journalRows: Math.max(0, journal.getLastRow() - 1),
    incompleteJournalRows: incompleteJournalIds.length,
    canonicalRows: canonicalRows.length,
    interactionSummaryDifferences: summary.differences.length,
    differences: differences,
    blockingDifferences: blockingDifferences
  };
}

/**
 * doPost reads Config before waiting for the script lock. Refresh every
 * mutation gate after acquiring it so a concurrent provision, enable, disable,
 * or predecessor core commit cannot be hidden by that stale request context.
 */
function refreshRecoverableSyncApplyStateLocked_(runtimeContext) {
  const ss = runtimeContext.ss;
  const configSheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
  const configHeaders = headerMap_(configSheet);
  requireExactHeaders_(
    configHeaders,
    ['Key', 'Value', 'Description'],
    CANN.SHEETS.CONFIG
  );
  const values = configValuesFromSheet_(configSheet, configHeaders);
  if (text_(values.ENVIRONMENT) !== runtimeContext.environment) {
    throw new Error('CONFIGURATION_ERROR: Config ENVIRONMENT mismatch');
  }
  assertSupportedSchemaVersion_(values);
  const summaryReady = interactionSummaryReady_(values);
  const recoverableReady = recoverableSyncApplyReady_(values);
  const correctionCapability = consumptionCorrectionCapability_(values);
  if (correctionCapability.version && !summaryReady) {
    throw new Error(
      'SCHEMA_MISMATCH: consumption corrections require the ' +
      'interaction-summary projection'
    );
  }
  runtimeContext.config[CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY] =
    values[CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY];
  runtimeContext.config[CANN.PENDING_APPLY_KEY] =
    values[CANN.PENDING_APPLY_KEY];
  runtimeContext.config[CANN.CONSUMPTION_CORRECTION_VERSION_KEY] =
    values[CANN.CONSUMPTION_CORRECTION_VERSION_KEY];
  runtimeContext.config[CANN.CONSUMPTION_CORRECTION_WRITES_KEY] =
    values[CANN.CONSUMPTION_CORRECTION_WRITES_KEY];
  runtimeContext.config.CONSUMPTION_CORRECTION_VERSION =
    correctionCapability.version;
  runtimeContext.config.CONSUMPTION_CORRECTION_WRITES_ENABLED =
    correctionCapability.writesEnabled;
  runtimeContext.config.RECOVERABLE_SYNC_APPLY_READY =
    recoverableReady;
  if (recoverableReady) {
    const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
    runtimeContext.headers.responses = headerMap_(responses);
    requireCompatibilityIdentityHeaders_(runtimeContext.headers.responses);
    runtimeContext.sheets.applyJournal =
      requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
    runtimeContext.headers.applyJournal =
      headerMap_(runtimeContext.sheets.applyJournal);
    requireExactHeaders_(
      runtimeContext.headers.applyJournal,
      CANN.APPLY_JOURNAL_HEADERS,
      CANN.SHEETS.APPLY_JOURNAL
    );
  }
  if (correctionCapability.version) {
    runtimeContext.sheets.corrections =
      requiredSheet_(ss, CANN.SHEETS.CORRECTIONS);
    runtimeContext.headers.corrections =
      headerMap_(runtimeContext.sheets.corrections);
    requireExactHeaders_(
      runtimeContext.headers.corrections,
      CANN.CORRECTION_HEADERS,
      CANN.SHEETS.CORRECTIONS
    );
  }
}

function applyRecoverableSyncLocked_(settings) {
  const runtimeContext = settings.runtimeContext;
  const ss = runtimeContext.ss;
  // The caller already attempted repair under this same lock. Re-read the
  // durable marker rather than trusting the pre-lock request context.
  const pendingBefore = text_(configValue_(ss, CANN.PENDING_APPLY_KEY, ''));
  if (pendingBefore) {
    throw new Error(
      'RECOVERABLE_APPLY_BLOCKED: pending apply was not repaired ' +
      pendingBefore
    );
  }

  const applyId = Utilities.getUuid();
  const now = new Date();
  const stagedPurchases = settings.stagedPurchases || [];
  const stagedConsumptions = settings.stagedConsumptions || [];
  const stagedFinishActions = settings.stagedFinishActions || [];
  const stagedConsumptionCorrections =
    settings.stagedConsumptionCorrections || [];
  const correctionProjectionEffects =
    settings.correctionProjectionEffects || [];
  const purchasePlan = planRecoverablePurchaseRows_(
    settings.productContext,
    stagedPurchases,
    now,
    runtimeContext.config.TAX_RATE
  );
  const effects = calculateProductEffects_(
    settings.productContext,
    stagedConsumptions
  );
  applyEffectsToPlannedPurchaseRows_(
    purchasePlan,
    effects,
    settings.productContext.headers
  );
  applyFinishActionsToPlannedPurchaseRows_(
    purchasePlan,
    stagedFinishActions,
    settings.productContext.headers
  );

  const responseRows = settings.compatibilityExistingRow
    ? []
    : buildRecoverableCompatibilityRows_(
      runtimeContext,
      stagedConsumptions
    );
  const eventRows = buildRecoverableEventRows_(stagedConsumptions);
  const correctionRows = buildRecoverableCorrectionRows_(
    stagedConsumptionCorrections
  );
  const journalSheet = requiredSheet_(
    ss,
    CANN.SHEETS.APPLY_JOURNAL
  );
  const responseLastRowBefore =
    runtimeContext.sheets.responses.getLastRow();
  const eventFirstRow = runtimeContext.sheets.events.getLastRow() + 1;
  const journalRowNumber = journalSheet.getLastRow() + 1;
  const plan = {
    applyId: applyId,
    kind: settings.kind,
    requestId: settings.requestId || '',
    eventIds: stagedConsumptions.map(item => item.eventId),
    correctionActions: stagedConsumptionCorrections.map(item => ({
      actionId: item.correctionId,
      targetEventId: item.targetEventId,
      supersedesCorrectionId: item.supersedesCorrectionId,
      revision: item.revision,
      operation: item.operation,
      reopenProduct: item.reopenProduct,
      reopenLegacyProductId: item.reopenLegacyProductId || null,
      affectedProductIds: item.affectedProductIds || []
    })),
    finishActions: stagedFinishActions.map(item => ({
      actionId: item.actionId,
      productUuid: item.productUuid,
      legacyProductId: item.legacyProductId,
      timestamp: item.timestamp
    })),
    compatibilitySheet: runtimeContext.sheets.responses.getName(),
    formRefreshRequired: settings.formRefreshRequired === true,
    ledger: settings.ledger ? {
      requestId: settings.requestId,
      apiVersion: CANN.API_VERSION,
      receivedAtEpochMillis: now.getTime(),
      durationMsAtCoreStart: settings.ledger.durationMs,
      purchaseCount: settings.ledger.purchaseCount,
      consumptionCount: settings.ledger.consumptionCount,
      result: settings.ledger.result,
      durationMs: settings.ledger.durationMs,
      errorCode: settings.ledger.errorCode || ''
    } : null
  };

  const coreRequests = [];
  if (purchasePlan.rows.length) {
    coreRequests.push(appendCellsRequest_(
      runtimeContext.sheets.purchases,
      purchasePlan.rows
    ));
  }
  if (settings.compatibilityExistingRow) {
    const responseHeaders = runtimeContext.headers.responses;
    requireCompatibilityIdentityHeaders_(responseHeaders);
    const event = stagedConsumptions[0];
    const currentIdentity = runtimeContext.sheets.responses.getRange(
      settings.compatibilityExistingRow,
      responseHeaders[CANN.COMPATIBILITY_EVENT_HEADER] + 1,
      1,
      2
    ).getValues()[0];
    if ((text_(currentIdentity[0]) &&
         text_(currentIdentity[0]) !== event.eventId) ||
        (text_(currentIdentity[1]) &&
         text_(currentIdentity[1]) !== text_(event.requestId))) {
      throw new Error(
        'FORM_COMPATIBILITY_IDENTITY_CONFLICT: row ' +
        settings.compatibilityExistingRow
      );
    }
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.responses,
      settings.compatibilityExistingRow,
      responseHeaders[CANN.COMPATIBILITY_EVENT_HEADER] + 1,
      [[event.eventId, event.requestId || '']]
    ));
  } else if (responseRows.length) {
    coreRequests.push(appendCellsRequest_(
      runtimeContext.sheets.responses,
      responseRows
    ));
  }
  maybeInjectSandboxSyncApplyBatchFault_(
    CANN.SYNC_APPLY_FAULTS.COMPATIBILITY,
    coreRequests
  );

  if (eventRows.length) {
    coreRequests.push(appendCellsRequest_(
      runtimeContext.sheets.events,
      eventRows
    ));
  }
  if (correctionRows.length) {
    coreRequests.push(appendCellsRequest_(
      runtimeContext.sheets.corrections,
      correctionRows
    ));
  }
  maybeInjectSandboxSyncApplyBatchFault_(
    CANN.SYNC_APPLY_FAULTS.CANONICAL,
    coreRequests
  );

  effects.filter(effect => !effect.pendingAppend).forEach(effect => {
    const headers = settings.productContext.headers;
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Finished'] + 1,
      [[effect.status]]
    ));
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Uses'] + 1,
      [[effect.uses]]
    ));
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Finished At'] + 1,
      [[effect.finishedAt || '']]
    ));
  });
  maybeInjectSandboxSyncApplyBatchFault_(
    CANN.SYNC_APPLY_FAULTS.PRODUCT_EFFECTS,
    coreRequests
  );

  calculateFinishEffects_(settings.productContext, stagedFinishActions)
    .filter(effect => !effect.pendingAppend)
    .forEach(effect => {
      const headers = settings.productContext.headers;
      coreRequests.push(updateCellsRequest_(
        runtimeContext.sheets.purchases,
        effect.rowNumber,
        headers['Finished'] + 1,
        [[effect.status]]
      ));
      coreRequests.push(updateCellsRequest_(
        runtimeContext.sheets.purchases,
        effect.rowNumber,
        headers['Finished At'] + 1,
        [[effect.finishedAt]]
      ));
    });

  effects.filter(effect => !effect.pendingAppend).forEach(effect => {
    const headers = settings.productContext.headers;
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Most recent use'] + 1,
      [[effect.mostRecentUse || '']]
    ));
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Last quantity'] + 1,
      [[effect.lastQuantity == null ? '' : effect.lastQuantity]]
    ));
  });
  correctionProjectionEffects.forEach(effect => {
    const headers = settings.productContext.headers;
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Finished'] + 1,
      [[effect.status]]
    ));
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Uses'] + 1,
      [[effect.uses]]
    ));
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Most recent use'] + 1,
      [[effect.mostRecentUse || '']]
    ));
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Finished At'] + 1,
      [[effect.finishedAt || '']]
    ));
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Last quantity'] + 1,
      [[effect.lastQuantity == null ? '' : effect.lastQuantity]]
    ));
  });
  maybeInjectSandboxSyncApplyBatchFault_(
    CANN.SYNC_APPLY_FAULTS.INTERACTION_SUMMARY,
    coreRequests
  );

  coreRequests.push(appendCellsRequest_(
    journalSheet,
    [[
      applyId,
      settings.kind,
      settings.apiVersion,
      settings.requestId || '',
      'CORE_COMMITTED',
      now,
      '',
      JSON.stringify(plan),
      settings.response == null ? '' : JSON.stringify(settings.response)
    ]]
  ));
  coreRequests.push(updateCellsRequest_(
    requiredSheet_(ss, CANN.SHEETS.CONFIG),
    findConfigRowNumber_(ss, CANN.PENDING_APPLY_KEY),
    2,
    [[applyId]]
  ));

  const coreStarted = Date.now();
  bumpMutationWatermark_();
  sheetsBatchUpdate_(ss, coreRequests);
  recordBackendPhase_(settings.timing, 'recoverableCoreBatch', coreStarted);
  maybeInjectSandboxSyncApplyFault_(
    CANN.SYNC_APPLY_FAULTS.CORE_COMMITTED
  );
  const materialized = materializedRecoverableRowsAfterCore_(
    ss,
    {
      applyId: applyId,
      kind: settings.kind,
      requestId: settings.requestId || '',
      now: now,
      plan: plan,
      response: settings.response,
      eventIds: plan.eventIds,
      compatibilityExistingRow: settings.compatibilityExistingRow,
      responseLastRowBefore: responseLastRowBefore,
      compatibilityEventColumn:
        runtimeContext.headers.responses[
          CANN.COMPATIBILITY_EVENT_HEADER
        ] + 1,
      eventFirstRow: eventFirstRow,
      eventRowCount: eventRows.length,
      journalRowNumber: journalRowNumber
    }
  );

  if (settings.formRefreshRequired) {
    const formStarted = Date.now();
    try {
      // This operation is intentionally between the two atomic batches. It is
      // safe to repeat and repair reruns it before finalization.
      updateFormAndDescriptionLocked_(ss);
    } catch (error) {
      if (settings.apiVersion === 1) {
        // V1 has no stable request/action/event identity across an HTTP retry.
        // Return its already-committed success rather than invite a duplicate;
        // leave CORE_COMMITTED + pending marker for explicit/next-run repair.
        console.error(
          'V1 Form refresh deferred to recoverable repair: ' +
          conciseError_(error)
        );
        bumpMutationWatermark_();
        return {
          applyId: applyId,
          complete: false,
          pendingRepair: true,
          v1RetryBoundary: true
        };
      }
      throw error;
    }
    recordBackendPhase_(settings.timing, 'formRefresh', formStarted);
  }

  const finalStarted = Date.now();
  const result = finalizeRecoverableApplyLocked_(ss, applyId, {
    ledgerDurationMs: settings.ledger &&
      settings.ledger.startedAtEpochMillis != null
      ? Date.now() - settings.ledger.startedAtEpochMillis
      : null,
    journalRecord: materialized.journalRecord,
    canonicalRowsByEventId: materialized.canonicalRowsByEventId,
    compatibilityRowsByEventId:
      materialized.compatibilityRowsByEventId
  });
  recordBackendPhase_(settings.timing, 'recoverableFinalBatch', finalStarted);
  maybeInjectSandboxSyncApplyFault_(
    CANN.SYNC_APPLY_FAULTS.POST_COMPLETE
  );

  effects.forEach(effect => {
    const product =
      settings.productContext.byLegacyId[effect.legacyProductId];
    if (!product) return;
    product.status = effect.status;
    product.uses = effect.uses;
    product.mostRecentUse = effect.mostRecentUse;
    product.finishedAt = effect.finishedAt;
    product.lastQuantity = effect.lastQuantity;
  });
  calculateFinishEffects_(settings.productContext, stagedFinishActions)
    .forEach(effect => {
      const product = settings.productContext.byLegacyId[effect.legacyProductId];
      if (!product) return;
      product.status = effect.status;
      product.finishedAt = effect.finishedAt;
    });
  correctionProjectionEffects.forEach(effect => {
    const product =
      settings.productContext.byLegacyId[effect.legacyProductId];
    if (!product) return;
    product.status = effect.status;
    product.uses = effect.uses;
    product.mostRecentUse = effect.mostRecentUse;
    product.finishedAt = effect.finishedAt;
    product.lastQuantity = effect.lastQuantity;
  });
  bumpMutationWatermark_();
  return result;
}

function planRecoverablePurchaseRows_(context, staged, now, configuredTaxRate) {
  const taxRate = finiteNumberOr_(configuredTaxRate, 0.13);
  const rows = staged.map(item => {
    const row = newPurchaseRow_(item, now, taxRate);
    row.push('');
    item.pendingAppend = true;
    item.row = row;
    item.status = CANN.STATUS.UNOPENED;
    item.uses = 0;
    item.mostRecentUse = null;
    item.finishedAt = null;
    item.lastQuantity = null;
    context.byLegacyId[item.legacyProductId] = item;
    if (item.productUuid) context.byProductUuid[item.productUuid] = item;
    if (item.actionId && context.byActionId) {
      context.byActionId[item.actionId] = item;
    }
    return row;
  });
  const byLegacyId = {};
  staged.forEach((item, index) => {
    byLegacyId[item.legacyProductId] = rows[index];
  });
  return { rows: rows, byLegacyId: byLegacyId };
}

function applyEffectsToPlannedPurchaseRows_(purchasePlan, effects, headers) {
  effects.forEach(effect => {
    const row = purchasePlan.byLegacyId[effect.legacyProductId];
    if (!row) return;
    row[headers['Finished']] = effect.status;
    row[headers['Uses']] = effect.uses;
    row[headers['Most recent use']] = effect.mostRecentUse || '';
    row[headers['Finished At']] = effect.finishedAt || '';
    row[headers['Last quantity']] =
      effect.lastQuantity == null ? '' : effect.lastQuantity;
    effect.pendingAppend = true;
  });
}

function applyFinishActionsToPlannedPurchaseRows_(purchasePlan, finishActions, headers) {
  finishActions.forEach(action => {
    const row = purchasePlan.byLegacyId[action.legacyProductId];
    if (!row) return;
    row[headers['Finished']] = CANN.STATUS.FINISHED;
    row[headers['Finished At']] = action.timestamp;
  });
}

function buildRecoverableCompatibilityRows_(runtimeContext, staged) {
  const sheet = runtimeContext.sheets.responses;
  const headers = runtimeContext.headers.responses;
  requireHeaders_(headers, ['Timestamp', 'Product', 'Uses']);
  requireCompatibilityIdentityHeaders_(headers);
  return staged.map(item => {
    const row = Array(sheet.getLastColumn()).fill('');
    row[headers.Timestamp] = item.timestamp;
    row[headers.Product] = item.legacyProductId;
    row[headers.Uses] = item.uses;
    if (headers.Date !== undefined) row[headers.Date] = item.localDate;
    if (headers.Time !== undefined) row[headers.Time] = item.localTime;
    if (headers['Weight code'] !== undefined) {
      row[headers['Weight code']] = item.weightCode || '';
    }
    if (headers['Mark as Finished?'] !== undefined) {
      row[headers['Mark as Finished?']] = item.isFinished ? 'Yes' : '';
    }
    row[headers[CANN.COMPATIBILITY_EVENT_HEADER]] = item.eventId;
    row[headers[CANN.COMPATIBILITY_REQUEST_HEADER]] =
      item.requestId || '';
    return row;
  });
}

function buildRecoverableEventRows_(staged) {
  return staged.map(item => [
    item.eventId, item.timestamp, item.localDate, item.localTime,
    item.productUuid, item.legacyProductId, item.uses,
    item.weightCode || '', !!item.isFinished, item.source,
    item.requestId || '', '', ''
  ]);
}

function buildRecoverableCorrectionRows_(staged) {
  return (staged || []).map(item => {
    const replacement = item.replacement;
    return [
      item.correctionId,
      item.targetEventId,
      item.supersedesCorrectionId || '',
      item.revision,
      item.operation,
      replacement
        ? new Date(replacement.occurredAtEpochMillis)
        : '',
      replacement ? replacement.localDate : '',
      replacement ? replacement.localTime : '',
      replacement ? replacement.product.productUuid : '',
      replacement ? replacement.product.productId : '',
      replacement ? replacement.quantity : '',
      replacement ? (replacement.weightCode || '') : '',
      replacement ? replacement.finished : '',
      item.reopenProduct === true,
      item.reason || '',
      item.requestId,
      item.source,
      new Date(item.createdAtEpochMillis)
    ];
  });
}

function repairRecoverableStateLocked_(runtimeContext) {
  const ss = runtimeContext.ss;
  const first = repairPendingSyncApplyLocked_(runtimeContext);
  const journal = requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
  const headers = headerMap_(journal);
  const incomplete = readDataRows_(journal).filter(row =>
    text_(value_(row, headers, 'State')) === 'CORE_COMMITTED'
  ).map(row => text_(value_(row, headers, 'Apply UUID')));
  if (!incomplete.length) return first;
  if (incomplete.length > 1) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: multiple incomplete journals ' +
      JSON.stringify(incomplete)
    );
  }
  const pending = text_(configValue_(ss, CANN.PENDING_APPLY_KEY, ''));
  if (pending && pending !== incomplete[0]) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: pending pointer does not match journal'
    );
  }
  if (!pending) {
    sheetsBatchUpdate_(ss, [updateCellsRequest_(
      requiredSheet_(ss, CANN.SHEETS.CONFIG),
      findConfigRowNumber_(ss, CANN.PENDING_APPLY_KEY),
      2,
      [[incomplete[0]]]
    )]);
  }
  return repairPendingSyncApplyLocked_(runtimeContext);
}

function repairPendingSyncApplyLocked_(runtimeContext) {
  const ss = runtimeContext.ss;
  // Always reread under the acquired script lock. A predecessor may have
  // committed its core while this request was waiting.
  const applyId = text_(configValue_(ss, CANN.PENDING_APPLY_KEY, ''));
  if (!applyId) return { repaired: false, pendingApplyId: null };
  const journalRecord = readApplyJournalRecord_(ss, applyId);
  if (!journalRecord) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: pending apply has no journal row ' +
      applyId
    );
  }
  if (journalRecord.state === 'COMPLETE') {
    bumpMutationWatermark_();
    sheetsBatchUpdate_(ss, [updateCellsRequest_(
      requiredSheet_(ss, CANN.SHEETS.CONFIG),
      findConfigRowNumber_(ss, CANN.PENDING_APPLY_KEY),
      2,
      [['']]
    )]);
    bumpMutationWatermark_();
    return {
      repaired: true,
      applyId: applyId,
      previousState: 'COMPLETE'
    };
  }
  if (journalRecord.state !== 'CORE_COMMITTED') {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: invalid journal state ' +
      journalRecord.state
    );
  }
  const plan = JSON.parse(journalRecord.finalizationJson);
  bumpMutationWatermark_();
  if (plan.formRefreshRequired) updateFormAndDescriptionLocked_(ss);
  const result = finalizeRecoverableApplyLocked_(ss, applyId);
  bumpMutationWatermark_();
  return {
    repaired: true,
    applyId: applyId,
    previousState: journalRecord.state,
    result: result
  };
}

function repairOrphanFormResponsesLocked_(runtimeContext) {
  const ss = runtimeContext.ss;
  if (!recoverableSyncApplyReady_(runtimeContext.config)) {
    return { repairedRows: 0, checkedThroughRow: null, issues: [] };
  }
  const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const responseHeaders = headerMap_(responses);
  requireCompatibilityIdentityHeaders_(responseHeaders);
  runtimeContext.sheets.responses = responses;
  runtimeContext.headers.responses = responseHeaders;

  const lastRow = responses.getLastRow();
  if (lastRow < 2) {
    return {
      repairedRows: 0,
      scannedRows: 0,
      issues: []
    };
  }

  const rows = responses.getRange(
    2,
    1,
    lastRow - 1,
    responses.getLastColumn()
  ).getValues();
  const issues = [];
  let repairedRows = 0;
  for (let index = 0; index < rows.length; index++) {
    const rowNumber = index + 2;
    const row = rows[index];
    const eventIdentity = text_(value_(
      row,
      responseHeaders,
      CANN.COMPATIBILITY_EVENT_HEADER
    ));
    const requestIdentity = text_(value_(
      row,
      responseHeaders,
      CANN.COMPATIBILITY_REQUEST_HEADER
    ));
    if (eventIdentity) continue;
    if (requestIdentity) {
      issues.push({
        type: 'FORM_ORPHAN_IDENTITY_CONFLICT',
        rowNumber: rowNumber
      });
      continue;
    }
    if (!row.some(cell => cell !== '' && cell != null)) continue;

    const timestamp = dateOrNull_(
      value_(row, responseHeaders, 'Timestamp')
    );
    const legacyId = text_(value_(row, responseHeaders, 'Product'));
    const uses = finiteNumber_(value_(row, responseHeaders, 'Uses'));
    const context = productContext_(ss, {
      runtimeContext: runtimeContext
    });
    const product = context.byLegacyId[legacyId];
    if (!timestamp || !product || uses == null) {
      issues.push({
        type: 'FORM_ORPHAN_INVALID',
        rowNumber: rowNumber,
        productId: legacyId
      });
      continue;
    }

    const eventId = deterministicLegacyEventUuid_(
      ss.getId(),
      responses.getName(),
      rowNumber
    );
    if (eventContext_(ss, runtimeContext, [eventId]).eventIds.has(eventId)) {
      issues.push({
        type: 'FORM_ORPHAN_CANONICAL_WITHOUT_IDENTITY',
        rowNumber: rowNumber,
        eventId: eventId
      });
      continue;
    }
    const event = {
      eventId: eventId,
      timestamp: timestamp,
      localDate: formatDate_(timestamp),
      localTime: formatTime_(timestamp),
      productUuid: product.productUuid,
      legacyProductId: product.legacyProductId,
      uses: uses,
      weightCode: text_(value_(row, responseHeaders, 'Weight code')),
      isFinished: truthy_(value_(
        row,
        responseHeaders,
        'Mark as Finished?'
      )),
      source: 'FORM_RECOVERY',
      requestId: '',
      legacySourceSheet: responses.getName(),
      legacySourceRow: rowNumber,
      compatibilityRow: null
    };
    applyRecoverableSyncLocked_({
      runtimeContext: runtimeContext,
      productContext: context,
      stagedPurchases: [],
      stagedConsumptions: [event],
      kind: 'FORM_RECOVERY',
      apiVersion: 0,
      requestId: '',
      response: null,
      formRefreshRequired: true,
      compatibilityExistingRow: rowNumber,
      ledger: null,
      timing: null
    });
    repairedRows++;
  }

  return {
    repairedRows: repairedRows,
    scannedRows: rows.length,
    issues: issues
  };
}

function finalizeRecoverableApplyLocked_(ss, applyId, options) {
  const settings = options || {};
  const journalRecord = settings.journalRecord ||
    readApplyJournalRecord_(ss, applyId);
  if (!journalRecord) {
    throw new Error('RECOVERABLE_APPLY_CORRUPT: missing journal ' + applyId);
  }
  if (journalRecord.state === 'COMPLETE') {
    return { applyId: applyId, alreadyComplete: true };
  }
  if (journalRecord.state !== 'CORE_COMMITTED') {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: invalid journal state ' +
      journalRecord.state
    );
  }
  const plan = JSON.parse(journalRecord.finalizationJson);
  const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const responseHeaders = headerMap_(responses);
  requireCompatibilityIdentityHeaders_(responseHeaders);
  const events = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const eventHeaders = headerMap_(events);
  const finalRequests = [];

  const plannedEventIds = plan.eventIds || [];
  const canonicalRowsByEventId =
    settings.canonicalRowsByEventId ||
    resolveUniqueRowsByIds_(
      events,
      eventHeaders['Event UUID'] + 1,
      plannedEventIds
    );
  const compatibilityRowsByEventId =
    settings.compatibilityRowsByEventId ||
    resolveUniqueRowsByIds_(
      responses,
      responseHeaders[CANN.COMPATIBILITY_EVENT_HEADER] + 1,
      plannedEventIds
    );
  plannedEventIds.forEach(eventId => {
    const canonicalRow = canonicalRowsByEventId[eventId];
    const compatibilityRow = compatibilityRowsByEventId[eventId];
    if (!canonicalRow || !compatibilityRow) {
      throw new Error(
        'RECOVERABLE_APPLY_CORRUPT: missing materialized row for ' +
        eventId
      );
    }
    finalRequests.push(updateCellsRequest_(
      events,
      canonicalRow,
      eventHeaders['Legacy Source Sheet'] + 1,
      [[responses.getName(), compatibilityRow]]
    ));
  });

  if (plan.ledger) {
    const ledger = requiredSheet_(ss, CANN.SHEETS.LEDGER);
    const ledgerHeaders = headerMap_(ledger);
    const existingLedgerRow = findOptionalUniqueExactCellRow_(
      ledger,
      ledgerHeaders['Request UUID'] + 1,
      plan.ledger.requestId
    );
    const ledgerValues = [[
      plan.ledger.requestId,
      plan.ledger.apiVersion,
      new Date(plan.ledger.receivedAtEpochMillis),
      plan.ledger.purchaseCount,
      plan.ledger.consumptionCount,
      plan.ledger.result,
      settings.ledgerDurationMs != null
        ? settings.ledgerDurationMs
        : plan.ledger.durationMsAtCoreStart,
      plan.ledger.errorCode || ''
    ]];
    if (existingLedgerRow) {
      finalRequests.push(updateCellsRequest_(
        ledger,
        existingLedgerRow,
        1,
        ledgerValues
      ));
    } else {
      finalRequests.push(appendCellsRequest_(ledger, ledgerValues));
    }
    // Add an invalid request to the same final transaction. Sheets rejects the
    // whole batch, proving that lineage/ledger/state/clear cannot split.
    maybeInjectSandboxSyncApplyBatchFault_(
      CANN.SYNC_APPLY_FAULTS.LEDGER,
      finalRequests
    );
  }

  finalRequests.push(updateCellsRequest_(
    requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL),
    journalRecord.rowNumber,
    5,
    [['COMPLETE', journalRecord.coreCommittedAt, new Date()]]
  ));
  finalRequests.push(updateCellsRequest_(
    requiredSheet_(ss, CANN.SHEETS.CONFIG),
    findConfigRowNumber_(ss, CANN.PENDING_APPLY_KEY),
    2,
    [['']]
  ));
  sheetsBatchUpdate_(ss, finalRequests);
  return {
    applyId: applyId,
    eventCount: (plan.eventIds || []).length,
    ledgerFinalized: !!plan.ledger,
    complete: true
  };
}

function readApplyJournalRecord_(ss, applyId) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
  const headers = headerMap_(sheet);
  requireExactHeaders_(
    headers,
    CANN.APPLY_JOURNAL_HEADERS,
    CANN.SHEETS.APPLY_JOURNAL
  );
  const rowNumber = findOptionalUniqueExactCellRow_(
    sheet,
    headers['Apply UUID'] + 1,
    applyId
  );
  if (!rowNumber) return null;
  const row = sheet.getRange(
    rowNumber,
    1,
    1,
    sheet.getLastColumn()
  ).getValues()[0];
  return {
    rowNumber: rowNumber,
    state: text_(value_(row, headers, 'State')),
    coreCommittedAt: value_(row, headers, 'Core Committed At'),
    finalizationJson: text_(value_(row, headers, 'Finalization JSON')),
    responseJson: text_(value_(row, headers, 'Response JSON'))
  };
}

/**
 * Finish actions have no event row by design. Their immutable action IDs live
 * in the recoverable apply plan, which lets a lost response retry acknowledge
 * the completed finish without rewriting its timestamp.
 */
function finishActionContext_(ss, runtimeContext, submittedActionIds) {
  const wanted = {};
  (submittedActionIds || []).forEach(actionId => { wanted[text_(actionId)] = true; });
  const matches = {};
  if (!Object.keys(wanted).length) return matches;
  const sheet = runtimeContext && runtimeContext.sheets.applyJournal
    ? runtimeContext.sheets.applyJournal
    : requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
  const headers = runtimeContext && runtimeContext.headers.applyJournal
    ? runtimeContext.headers.applyJournal
    : headerMap_(sheet);
  readDataRows_(sheet).forEach(row => {
    const finalizationJson = text_(value_(row, headers, 'Finalization JSON'));
    if (!finalizationJson) return;
    let plan;
    try {
      plan = JSON.parse(finalizationJson);
    } catch (error) {
      throw new Error('RECOVERABLE_APPLY_CORRUPT: invalid finalization JSON');
    }
    (plan.finishActions || []).forEach(action => {
      const actionId = text_(action && action.actionId);
      if (!wanted[actionId]) return;
      if (matches[actionId]) {
        throw new Error(
          'RECOVERABLE_APPLY_CORRUPT: duplicate finish action ' + actionId
        );
      }
      matches[actionId] = action;
    });
  });
  return matches;
}

function recoverableApplyEvidence_(ss, runtimeContext) {
  const sheet = runtimeContext && runtimeContext.sheets && runtimeContext.sheets.applyJournal
    ? runtimeContext.sheets.applyJournal
    : requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
  const headers = runtimeContext && runtimeContext.headers && runtimeContext.headers.applyJournal
    ? runtimeContext.headers.applyJournal
    : headerMap_(sheet);
  const rows = readDataRows_(sheet);
  return recoverableApplyEvidenceFromValues_(rows, headers);
}

function recoverableApplyEvidenceFromValues_(rows, headers) {
  const evidence = {
    eventIds: {},
    correctionIds: {},
    finishActionProductIds: {}
  };
  const safeHeaders = headers || {};
  (rows || []).forEach(row => {
    if (!row) return;
    const cells = Array.isArray(row) ? row : row.cells;
    if (!cells || !cells.some(cell => cell !== '' && cell != null)) return;
    if (text_(value_(cells, safeHeaders, 'State')) !== 'COMPLETE') return;
    const finalizationJson = text_(value_(
      cells,
      safeHeaders,
      'Finalization JSON'
    ));
    if (!finalizationJson) return;
    if (
      finalizationJson.indexOf('"eventIds"') === -1 &&
      finalizationJson.indexOf('"correctionActions"') === -1 &&
      finalizationJson.indexOf('"finishActions"') === -1
    ) {
      return;
    }
    let plan;
    try {
      plan = JSON.parse(finalizationJson);
    } catch (error) {
      throw new Error('RECOVERABLE_APPLY_CORRUPT: invalid finalization JSON');
    }
    (plan.eventIds || []).forEach(eventId => {
      const normalized = text_(eventId).toLowerCase();
      if (normalized) evidence.eventIds[normalized] = true;
    });
    (plan.correctionActions || []).forEach(action => {
      const correctionId = text_(action && action.actionId).toLowerCase();
      if (correctionId) evidence.correctionIds[correctionId] = true;
    });
    (plan.finishActions || []).forEach(action => {
      const productUuid = text_(action && action.productUuid).toLowerCase();
      const legacyProductId = text_(action && action.legacyProductId);
      if (productUuid) {
        evidence.finishActionProductIds[productUuid] = true;
      }
      if (legacyProductId) {
        evidence.finishActionProductIds[legacyProductId] = true;
      }
    });
  });
  return evidence;
}

function findConfigRowNumber_(ss, key) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
  const row = findOptionalUniqueExactCellRow_(sheet, 1, key);
  if (!row) throw new Error('SCHEMA_MISMATCH: missing Config key ' + key);
  return row;
}

function findUniqueExactCellRow_(sheet, columnNumber, value) {
  const row = findOptionalUniqueExactCellRow_(sheet, columnNumber, value);
  if (!row) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: missing ' + value +
      ' in ' + sheet.getName()
    );
  }
  return row;
}

function resolveUniqueRowsByIds_(sheet, columnNumber, ids) {
  const submitted = Array.from(new Set((ids || []).map(text_).filter(Boolean)));
  const resolved = {};
  if (!submitted.length) return resolved;
  if (submitted.length <= CANN.EVENT_TEXT_FINDER_MAX_BATCH) {
    submitted.forEach(id => {
      resolved[id] = findUniqueExactCellRow_(sheet, columnNumber, id);
    });
    return resolved;
  }

  const wanted = {};
  submitted.forEach(id => { wanted[id] = true; });
  const lastRow = sheet.getLastRow();
  if (lastRow >= 2) {
    sheet.getRange(2, columnNumber, lastRow - 1, 1)
      .getValues()
      .forEach((row, index) => {
        const id = text_(row[0]);
        if (!wanted[id]) return;
        if (resolved[id]) {
          throw new Error(
            'RECOVERABLE_APPLY_CORRUPT: duplicate ' + id +
            ' in ' + sheet.getName()
          );
        }
        resolved[id] = index + 2;
      });
  }
  submitted.forEach(id => {
    if (!resolved[id]) {
      throw new Error(
        'RECOVERABLE_APPLY_CORRUPT: missing ' + id +
        ' in ' + sheet.getName()
      );
    }
  });
  return resolved;
}

function findOptionalUniqueExactCellRow_(sheet, columnNumber, value) {
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return null;
  const matches = sheet.getRange(2, columnNumber, lastRow - 1, 1)
    .createTextFinder(String(value))
    .matchEntireCell(true)
    .matchCase(true)
    .useRegularExpression(false)
    .findAll();
  if (matches.length > 1) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: duplicate ' + value +
      ' in ' + sheet.getName()
    );
  }
  return matches.length ? matches[0].getRow() : null;
}

function appendCellsRequest_(sheet, rows) {
  return {
    appendCells: {
      sheetId: sheet.getSheetId(),
      rows: rows.map(row => ({
        values: row.map(sheetCellData_)
      })),
      fields: 'userEnteredValue'
    }
  };
}

function updateCellsRequest_(sheet, rowNumber, columnNumber, rows) {
  return {
    updateCells: {
      start: {
        sheetId: sheet.getSheetId(),
        rowIndex: rowNumber - 1,
        columnIndex: columnNumber - 1
      },
      rows: rows.map(row => ({
        values: row.map(sheetCellData_)
      })),
      fields: 'userEnteredValue'
    }
  };
}

function sheetCellData_(value) {
  if (value == null || value === '') return {};
  if (Object.prototype.toString.call(value) === '[object Date]') {
    if (!Number.isFinite(value.getTime())) return {};
    return {
      userEnteredValue: {
        numberValue: spreadsheetLocalDateSerial_(value)
      }
    };
  }
  if (typeof value === 'boolean') {
    return { userEnteredValue: { boolValue: value } };
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return { userEnteredValue: { numberValue: value } };
  }
  return { userEnteredValue: { stringValue: String(value) } };
}

function spreadsheetLocalDateSerial_(value) {
  // Sheets serials represent spreadsheet-local wall-clock components. A raw
  // UTC epoch/86400000 value shifts visible timestamps by the UTC offset.
  const parts = Utilities.formatDate(
    new Date(value),
    CANN.TIME_ZONE,
    'yyyy,MM,dd,HH,mm,ss,SSS'
  ).split(',').map(Number);
  return Date.UTC(
    parts[0],
    parts[1] - 1,
    parts[2],
    parts[3],
    parts[4],
    parts[5],
    parts[6]
  ) / 86400000 + 25569;
}

function padBatchRowWidth_(values) {
  // The Advanced Sheets Values API omits trailing empty cells from each row,
  // while the sequential Range.getValues() path always returns a full
  // rectangular matrix padded with ''. Left unpadded, a short batch row reads
  // as undefined past its length where the sequential row reads as '', and
  // analyticsHashValue_ types those two differently (null vs string) --
  // the same dataVersion-parity problem Fix 2 exists to close, just for
  // ordinary blank trailing cells instead of dates.
  if (!values || values.length < 2) return values;
  const headerLength = (values[0] || []).length;
  for (let rowIndex = 1; rowIndex < values.length; rowIndex++) {
    const row = values[rowIndex];
    if (!row) continue;
    while (row.length < headerLength) row.push('');
  }
  return values;
}

function dateFromSpreadsheetSerial_(serial) {
  const number = Number(serial);
  if (!Number.isFinite(number)) return null;
  const wallClockMillis = Math.round((number - 25569) * 86400000);
  const wallClockText = Utilities.formatDate(
    new Date(wallClockMillis),
    'UTC',
    'yyyy-MM-dd HH:mm:ss'
  );
  return Utilities.parseDate(
    wallClockText,
    CANN.TIME_ZONE,
    'yyyy-MM-dd HH:mm:ss'
  );
}

function normalizeBatchDateColumns_(sheetName, values) {
  const dateColumns = ANALYTICS_DATE_COLUMNS_[sheetName];
  if (!dateColumns || !values || values.length < 2) return values;
  const headers = headerMapFromRow_(values[0]);
  const indexes = [];
  dateColumns.forEach(name => {
    const index = headers[name];
    if (index !== undefined) indexes.push(index);
  });
  if (!indexes.length) return values;
  for (let rowIndex = 1; rowIndex < values.length; rowIndex++) {
    const row = values[rowIndex];
    if (!row) continue;
    indexes.forEach(index => {
      const cell = row[index];
      if (typeof cell === 'number' && Number.isFinite(cell) && cell > 0) {
        const converted = dateFromSpreadsheetSerial_(cell);
        if (converted) row[index] = converted;
      }
    });
  }
  return values;
}

function sheetsBatchUpdate_(ss, requests) {
  if (!requests.length) return { replies: [] };
  assertAdvancedSheetsService_();
  return Sheets.Spreadsheets.batchUpdate(
    { requests: requests },
    ss.getId()
  );
}

function sheetsBatchUpdateInChunks_(ss, requests, requestedChunkSize) {
  const chunkSize = Math.max(
    1,
    Math.floor(finiteNumberOr_(requestedChunkSize, 400))
  );
  for (let start = 0; start < requests.length; start += chunkSize) {
    sheetsBatchUpdate_(ss, requests.slice(start, start + chunkSize));
  }
}

/**
 * The Advanced Sheets write becomes durable before SpreadsheetApp refreshes
 * its in-process read cache. Journal and canonical rows have single-writer,
 * lock-protected positions. Google Forms can append compatibility rows outside
 * that lock, so reread only the newly appended identity-column tail through the
 * Advanced service, which observes the just-committed batch.
 */
function materializedRecoverableRowsAfterCore_(ss, details) {
  const eventIds = (details.eventIds || []).map(text_);
  const eventCount = Number(details.eventRowCount || 0);
  const eventFirstRow = Number(details.eventFirstRow);
  const journalRowNumber = Number(details.journalRowNumber);
  if (eventIds.length !== eventCount ||
      !Number.isInteger(journalRowNumber) ||
      journalRowNumber < 2 ||
      (eventCount &&
       (!Number.isInteger(eventFirstRow) || eventFirstRow < 2))) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: core response row count mismatch'
    );
  }
  const canonicalRows = Array.from(
    { length: eventCount },
    (_, index) => eventFirstRow + index
  );
  const compatibilityRowsByEventId =
    details.compatibilityExistingRow
      ? rowsByStableIds_(
        eventIds,
        [Number(details.compatibilityExistingRow)]
      )
      : resolveFreshCompatibilityRowsByIds_(
        ss,
        requiredSheet_(ss, CANN.SHEETS.RESPONSES),
        details.compatibilityEventColumn,
        Number(details.responseLastRowBefore) + 1,
        eventIds
      );
  return {
    journalRecord: {
      rowNumber: journalRowNumber,
      state: 'CORE_COMMITTED',
      coreCommittedAt: details.now,
      finalizationJson: JSON.stringify(details.plan),
      responseJson: details.response == null
        ? ''
        : JSON.stringify(details.response)
    },
    canonicalRowsByEventId: rowsByStableIds_(
      eventIds,
      canonicalRows
    ),
    compatibilityRowsByEventId: compatibilityRowsByEventId
  };
}

function resolveFreshCompatibilityRowsByIds_(
  ss,
  sheet,
  columnNumber,
  firstPossibleRow,
  ids
) {
  const submitted = Array.from(
    new Set((ids || []).map(text_).filter(Boolean))
  );
  if (!submitted.length) return {};
  const column = sheetColumnLetters_(columnNumber);
  const firstRow = Math.max(2, Math.floor(firstPossibleRow));
  const sheetName = "'" +
    sheet.getName().replace(/'/g, "''") +
    "'";
  const response = Sheets.Spreadsheets.Values.get(
    ss.getId(),
    sheetName + '!' + column + firstRow + ':' + column
  );
  const wanted = {};
  submitted.forEach(id => { wanted[id] = true; });
  const resolved = {};
  (response.values || []).forEach((row, index) => {
    const id = text_(row && row[0]);
    if (!wanted[id]) return;
    if (resolved[id]) {
      throw new Error(
        'RECOVERABLE_APPLY_CORRUPT: duplicate ' + id +
        ' in ' + sheet.getName()
      );
    }
    resolved[id] = firstRow + index;
  });
  submitted.forEach(id => {
    if (!resolved[id]) {
      throw new Error(
        'RECOVERABLE_APPLY_CORRUPT: missing fresh compatibility ' +
        id
      );
    }
  });
  return resolved;
}

function rowsByStableIds_(ids, rowNumbers) {
  if (ids.length !== rowNumbers.length) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: stable ID row count mismatch'
    );
  }
  const rows = {};
  ids.forEach((id, index) => {
    if (!id || rows[id]) {
      throw new Error(
        'RECOVERABLE_APPLY_CORRUPT: invalid materialized stable ID ' +
        id
      );
    }
    rows[id] = rowNumbers[index];
  });
  return rows;
}

function sheetColumnLetters_(columnNumber) {
  let value = Number(columnNumber);
  if (!Number.isInteger(value) || value < 1) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: invalid sheet column ' +
      columnNumber
    );
  }
  let result = '';
  while (value > 0) {
    value--;
    result = String.fromCharCode(65 + value % 26) + result;
    value = Math.floor(value / 26);
  }
  return result;
}

function assertAdvancedSheetsService_() {
  if (typeof Sheets === 'undefined' ||
      !Sheets.Spreadsheets ||
      !Sheets.Spreadsheets.batchUpdate ||
      !Sheets.Spreadsheets.Values ||
      !Sheets.Spreadsheets.Values.get) {
    throw new Error(
      'CONFIGURATION_ERROR: Advanced Google Sheets service v4 is required'
    );
  }
}

function setSandboxSyncApplyFault(stage) {
  if (environment_() !== 'SANDBOX') {
    throw new Error('SANDBOX_FAULT_GUARD: ENVIRONMENT must be SANDBOX');
  }
  const allowed = Object.keys(CANN.SYNC_APPLY_FAULTS)
    .map(key => CANN.SYNC_APPLY_FAULTS[key]);
  const normalized = text_(stage).toUpperCase();
  if (allowed.indexOf(normalized) < 0) {
    throw new Error(
      'SANDBOX_FAULT_GUARD: unsupported stage ' + normalized
    );
  }
  PropertiesService.getScriptProperties().setProperty(
    CANN.SANDBOX_FAULT_PROPERTY,
    normalized
  );
  return { armed: normalized };
}

function clearSandboxSyncApplyFault() {
  if (environment_() !== 'SANDBOX') {
    throw new Error('SANDBOX_FAULT_GUARD: ENVIRONMENT must be SANDBOX');
  }
  PropertiesService.getScriptProperties().deleteProperty(
    CANN.SANDBOX_FAULT_PROPERTY
  );
  return { armed: null };
}

function armedSandboxSyncApplyFault_(stage) {
  if (environment_() !== 'SANDBOX') return false;
  const properties = PropertiesService.getScriptProperties();
  const armed = text_(
    properties.getProperty(CANN.SANDBOX_FAULT_PROPERTY)
  ).toUpperCase();
  if (armed !== stage) return false;
  properties.deleteProperty(CANN.SANDBOX_FAULT_PROPERTY);
  return true;
}

function maybeInjectSandboxSyncApplyBatchFault_(stage, requests) {
  if (!armedSandboxSyncApplyFault_(stage)) return;
  // A non-existent sheet makes the Advanced Sheets request invalid. Because it
  // is part of the same batch, every otherwise-valid request rolls back.
  requests.push({
    updateCells: {
      start: {
        sheetId: -2147483648,
        rowIndex: 0,
        columnIndex: 0
      },
      rows: [{ values: [{ userEnteredValue: { stringValue: 'FAULT' } }] }],
      fields: 'userEnteredValue'
    }
  });
}

function maybeInjectSandboxSyncApplyFault_(stage) {
  if (!armedSandboxSyncApplyFault_(stage)) return;
  throw new Error('SANDBOX_INJECTED_SYNC_APPLY_STOP: ' + stage);
}

function productContext_(ss, options) {
  const settings = options || {};
  const runtimeContext = settings.runtimeContext;
  const includeActionIds = settings.includeActionIds !== false;
  const sheet = runtimeContext ? runtimeContext.sheets.purchases : requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const headers = runtimeContext ? runtimeContext.headers.purchases : headerMap_(sheet);
  requireHeaders_(headers, ['Product ID', 'Product UUID', 'Client Action UUID', 'Type', 'Borrowed']);
  const lastRow = sheet.getLastRow();
  // Do not filter blank physical rows: the array index must remain the true
  // sheet row number for targeted writes.
  const rows = lastRow < 2 ? [] : sheet.getRange(2, 1, lastRow - 1, sheet.getLastColumn()).getValues();
  const byLegacyId = {}, byProductUuid = {}, byActionId = {};
  rows.forEach((row, index) => {
    const legacyProductId = text_(value_(row, headers, 'Product ID'));
    if (!legacyProductId) return;
    const product = {
      rowNumber: index + 2,
      legacyProductId: legacyProductId,
      productUuid: text_(value_(row, headers, 'Product UUID')),
      name: text_(value_(row, headers, 'Product name')),
      actionId: includeActionIds ? text_(value_(row, headers, 'Client Action UUID')) : '',
      type: text_(value_(row, headers, 'Type')),
      borrowed: truthy_(value_(row, headers, 'Borrowed')),
      status: allowedStatusOr_(value_(row, headers, 'Finished'), CANN.STATUS.ACTIVE),
      uses: finiteNumberOr_(value_(row, headers, 'Uses'), 0),
      mostRecentUse: value_(row, headers, 'Most recent use') || null,
      finishedAt: value_(row, headers, 'Finished At') || null,
      lastQuantity: optionalFiniteNumber_(value_(row, headers, 'Last quantity')),
      row: row
    };
    byLegacyId[legacyProductId] = product;
    if (product.productUuid) byProductUuid[product.productUuid] = product;
    if (product.actionId) byActionId[product.actionId] = product;
  });
  return { purchasesSheet: sheet, headers: headers, rows: rows, byLegacyId: byLegacyId, byProductUuid: byProductUuid, byActionId: byActionId };
}

function eventContext_(ss, runtimeContext, submittedEventIds) {
  const sheet = runtimeContext ? runtimeContext.sheets.events : requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const headers = runtimeContext ? runtimeContext.headers.events : headerMap_(sheet);
  requireHeaders_(headers, ['Event UUID']);
  const lastRow = sheet.getLastRow();
  const eventIds = new Set();
  if (lastRow < 2) return { sheet: sheet, eventIds: eventIds, lookupStrategy: 'EMPTY' };

  const uuidRange = sheet.getRange(2, headers['Event UUID'] + 1, lastRow - 1, 1);
  const submitted = submittedEventIds == null
    ? null
    : Array.from(new Set(submittedEventIds.map(text_).filter(eventId => eventId)));
  if (submitted && submitted.length <= CANN.EVENT_TEXT_FINDER_MAX_BATCH) {
    submitted.forEach(eventId => {
      const match = uuidRange.createTextFinder(eventId)
        .matchEntireCell(true)
        .useRegularExpression(false)
        .findNext();
      if (match) eventIds.add(eventId);
    });
    return { sheet: sheet, eventIds: eventIds, lookupStrategy: 'TEXT_FINDER' };
  }

  const values = uuidRange.getValues();
  values.forEach(row => {
    const eventId = text_(row[0]);
    if (eventId) eventIds.add(eventId);
  });
  return { sheet: sheet, eventIds: eventIds, lookupStrategy: 'COLUMN_SET' };
}

function stagePurchases_(items, context) {
  const accepted = [], rejected = [], byTempId = {};
  const reservedIds = Object.assign({}, context.byLegacyId);
  items.forEach(wrapper => {
    const item = wrapper.item;
    const type = text_(item.type).toUpperCase();
    const borrowed = truthy_(item.borrowed);
    if (wrapper.tempId && byTempId[wrapper.tempId]) {
      rejected.push({ item: item, code: 'INVALID_ITEM', message: 'Duplicate temporary purchase ID' });
      return;
    }
    const legacyProductId = nextDisplayId_(type, borrowed, reservedIds);
    const staged = {
      item: item,
      actionId: wrapper.actionId,
      tempId: wrapper.tempId,
      legacyProductId: legacyProductId,
      productUuid: Utilities.getUuid(),
      name: text_(item.name),
      type: type,
      borrowed: borrowed
    };
    reservedIds[legacyProductId] = staged;
    accepted.push(staged);
    if (wrapper.tempId) byTempId[wrapper.tempId] = staged;
  });
  return { accepted: accepted, rejected: rejected, byTempId: byTempId };
}

function nextDisplayId_(type, borrowed, reserved) {
  if (!type) throw new Error('Missing product type');
  const suffix = borrowed ? 'B' : '';
  const escaped = type.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pattern = new RegExp('^\\*' + escaped + '(\\d+)' + suffix + '$');
  let max = 0;
  Object.keys(reserved).forEach(id => {
    const match = id.match(pattern);
    if (match) max = Math.max(max, Number(match[1]));
  });
  let candidate;
  do { max++; candidate = '*' + type + max + suffix; } while (reserved[candidate]);
  return candidate;
}

function stageLegacyConsumption_(item, resolver, now) {
  const product = resolver[text_(item.productId)];
  return {
    eventId: Utilities.getUuid(),
    timestamp: now,
    localDate: text_(item.date) || formatDate_(now),
    localTime: text_(item.time) || formatTime_(now),
    productUuid: product.productUuid,
    legacyProductId: product.legacyProductId,
    uses: finiteNumberOr_(item.uses, 0),
    weightCode: '',
    isFinished: !!item.isFinished,
    source: 'ANDROID_V1',
    requestId: '',
    legacySourceSheet: '',
    legacySourceRow: ''
  };
}

function stageV2Consumption_(item, resolved, requestId) {
  const timestamp = parseClientDateTime_(item.date, item.time);
  return {
    eventId: text_(item.eventId),
    timestamp: timestamp,
    localDate: text_(item.date) || formatDate_(timestamp),
    localTime: text_(item.time) || formatTime_(timestamp),
    productUuid: resolved.productUuid,
    legacyProductId: resolved.legacyProductId,
    uses: finiteNumberOr_(item.uses, 0),
    weightCode: text_(item.weightCode),
    isFinished: !!item.isFinished,
    source: 'ANDROID_V2',
    requestId: requestId,
    legacySourceSheet: '',
    legacySourceRow: ''
  };
}

function stageV2FinishAction_(item, resolved) {
  return {
    actionId: text_(item.actionId),
    timestamp: parseClientDateTime_(item.date, item.time),
    productUuid: resolved.productUuid,
    legacyProductId: resolved.legacyProductId
  };
}

function stageV2ConsumptionCorrection_(
  item,
  targetState,
  products,
  requestId,
  createdAt
) {
  const operation = text_(item.operation).toUpperCase();
  let replacement = null;
  if (operation === CANN.CORRECTION_OPERATIONS.REPLACE) {
    const value = item.replacement;
    const productUuid = text_(value.productUuid).toLowerCase();
    const productId = text_(value.productId);
    const byUuid = products.byProductUuid[productUuid];
    const byId = products.byProductId[productId];
    if (!byUuid || !byId || byUuid !== byId) {
      return {
        error: itemError_(
          'UNKNOWN_PRODUCT',
          'Unknown or conflicting replacement product reference'
        )
      };
    }
    const timestamp = parseClientDateTimeStrict_(value.date, value.time);
    if (!timestamp) {
      return {
        error: itemError_(
          'INVALID_ITEM',
          'Correction replacement date or time is invalid in ' +
          CANN.TIME_ZONE
        )
      };
    }
    replacement = {
      occurredAtEpochMillis: timestamp.getTime(),
      localDate: text_(value.date),
      localTime: text_(value.time),
      product: byUuid,
      quantity: Number(value.uses),
      weightCode: text_(value.weightCode) || null,
      finished: value.finished === true
    };
    if (
      text_(replacement.weightCode) !==
      text_(targetState.original.weightCode)
    ) {
      return {
        error: itemError_(
          'IMMUTABLE_FIELD',
          'Weight code cannot be changed by a correction'
        )
      };
    }
  }
  const current = targetState.current;
  const finishBasis = targetState.reopenBasis;
  const proposed = operation === CANN.CORRECTION_OPERATIONS.REPLACE
    ? replacement
    : (
      operation === CANN.CORRECTION_OPERATIONS.RESTORE
        ? targetState.original
        : null
    );
  const removesCurrentFinish = !!(
    finishBasis &&
    finishBasis.finished &&
    (
      !proposed ||
      !proposed.finished ||
      proposed.product.productId !== finishBasis.product.productId
    )
  );
  const reopenProduct = item.reopenProduct === true;
  if (reopenProduct && !removesCurrentFinish) {
    return {
      error: itemError_(
        'REOPEN_NOT_APPLICABLE',
        'This correction does not remove the current finish marker'
      )
    };
  }
  if (reopenProduct && !targetState.reopenEligible) {
    return {
      error: itemError_(
        'REOPEN_NOT_SAFE',
        'The product finish cannot be traced only to this consumption entry'
      )
    };
  }
  const correctionId = text_(item.actionId).toLowerCase();
  const createdAtEpochMillis = createdAt.getTime();
  const record = {
    canonicalRow: Number.MAX_SAFE_INTEGER,
    correctionId: correctionId,
    targetEventId: text_(item.targetEventId).toLowerCase(),
    supersedesCorrectionId: text_(
      item.expectedCorrectionHeadId
    ).toLowerCase(),
    revision: targetState.revision + 1,
    operation: operation,
    replacement: replacement,
    reopenProduct: reopenProduct,
    reopenLegacyProductId: reopenProduct && finishBasis
      ? finishBasis.product.productId
      : null,
    reason: text_(item.reason) || null,
    requestId: requestId,
    source: 'ANDROID_V2',
    createdAtEpochMillis: createdAtEpochMillis,
    affectedProductIds: Array.from(new Set([
      current ? current.product.productId : '',
      proposed ? proposed.product.productId : '',
      reopenProduct && finishBasis ? finishBasis.product.productId : ''
    ].filter(Boolean)))
  };
  return { record: record };
}

function correctionRequestMatchesRecord_(item, record) {
  if (
    text_(item.targetEventId).toLowerCase() !== record.targetEventId ||
    text_(item.expectedCorrectionHeadId).toLowerCase() !==
      text_(record.supersedesCorrectionId).toLowerCase() ||
    text_(item.operation).toUpperCase() !== record.operation ||
    (item.reopenProduct === true) !== record.reopenProduct ||
    (text_(item.reason) || '') !== (record.reason || '')
  ) {
    return false;
  }
  if (record.operation !== CANN.CORRECTION_OPERATIONS.REPLACE) {
    return item.replacement == null;
  }
  const value = item.replacement || {};
  return text_(value.date) === record.replacement.localDate &&
    text_(value.time) === record.replacement.localTime &&
    text_(value.productUuid).toLowerCase() ===
      text_(record.replacement.product.productUuid).toLowerCase() &&
    text_(value.productId) === record.replacement.product.productId &&
    finiteNumber_(value.uses) === record.replacement.quantity &&
    text_(value.weightCode) === text_(record.replacement.weightCode) &&
    (value.finished === true) === record.replacement.finished;
}

function resolveProduct_(item, byLegacyId, byProductUuid) {
  const productUuid = text_(item.productUuid);
  const productId = text_(item.productId);
  if (productUuid && byProductUuid[productUuid]) return byProductUuid[productUuid];
  if (productId && byLegacyId[productId]) return byLegacyId[productId];
  return null;
}

function applyClientStateLocked_(ss, clientState, byLegacyId, byProductUuid) {
  const incomingUpdatedAt = clientState.loadedPenUpdatedAtEpochMillis;
  const acknowledged = {
    loadedPenUpdatedAtEpochMillis: incomingUpdatedAt,
    status: 'committed'
  };
  const productId = clientState.loadedPenProductId;
  const resolved = productId === null
    ? null
    : resolveProduct_({ productId: productId }, byLegacyId, byProductUuid);
  if (productId !== null && !resolved) {
    acknowledged.status = 'rejected';
    acknowledged.errorCode = 'UNKNOWN_PRODUCT';
    acknowledged.message = 'Unknown loaded pen product';
    return acknowledged;
  }
  const storedUpdatedAt = configValue_(ss, 'LOADED_PEN_UPDATED_AT', '');
  if (
    typeof storedUpdatedAt === 'number' &&
    Number.isFinite(storedUpdatedAt) &&
    storedUpdatedAt >= incomingUpdatedAt
  ) {
    acknowledged.status = 'stale';
    return acknowledged;
  }

  if (productId !== null) {
    setConfigValue_(ss, 'LOADED_PEN_PRODUCT_ID', resolved.legacyProductId, 'Loaded pen product ID');
    setConfigValue_(ss, 'LOADED_PEN_PRODUCT_UUID', resolved.productUuid, 'Loaded pen product UUID');
    setConfigValue_(ss, 'LOADED_PEN_PRODUCT_NAME', resolved.name, 'Loaded pen product name');
  } else {
    setConfigValue_(ss, 'LOADED_PEN_PRODUCT_ID', '', 'Loaded pen product ID');
    setConfigValue_(ss, 'LOADED_PEN_PRODUCT_UUID', '', 'Loaded pen product UUID');
    setConfigValue_(ss, 'LOADED_PEN_PRODUCT_NAME', '', 'Loaded pen product name');
  }
  setConfigValue_(
    ss,
    'LOADED_PEN_UPDATED_AT',
    incomingUpdatedAt,
    'Loaded pen state update time in epoch milliseconds'
  );
  return acknowledged;
}

function purchaseAck_(requestItem, product, status) {
  return {
    actionId: text_(requestItem.actionId),
    tempId: text_(requestItem.tempId),
    productUuid: product.productUuid,
    legacyProductId: product.legacyProductId,
    status: status
  };
}

function rejectedPurchase_(item, code, message) {
  return { actionId: text_(item.actionId) || null, errorCode: code, message: message };
}

function rejectedConsumption_(item, code, message) {
  return { eventId: text_(item.eventId) || null, errorCode: code, message: message };
}

function finishActionAck_(requestItem, product, status) {
  return {
    actionId: text_(requestItem.actionId),
    productUuid: text_(product.productUuid) || null,
    legacyProductId: text_(product.legacyProductId) || null,
    status: status
  };
}

function rejectedFinishAction_(item, code, message) {
  return {
    actionId: text_(item && item.actionId) || null,
    errorCode: code,
    message: message
  };
}

function consumptionCorrectionAck_(item, record, status) {
  return {
    actionId: text_(item.actionId).toLowerCase(),
    targetEventId: record.targetEventId,
    revision: record.revision,
    status: status
  };
}

function rejectedConsumptionCorrection_(
  item,
  code,
  message,
  currentHeadId
) {
  return {
    actionId: text_(item && item.actionId).toLowerCase() || null,
    targetEventId: text_(item && item.targetEventId).toLowerCase() || null,
    errorCode: code,
    message: message,
    currentHeadId: currentHeadId || null
  };
}

function validateLegacyPurchase_(item, index) {
  if (!item || !text_(item.tempId) || !text_(item.date) || !text_(item.type) || !text_(item.name)) return itemError_('INVALID_ITEM', 'Invalid purchase at index ' + index);
  const purchaseNumbers = [item.cost, item.thc, item.grams];
  const borrowed = truthy_(item.borrowed);
  const validNumbers = borrowed
    ? purchaseNumbers.every(isOptionalFinitePurchaseNumber_)
    : purchaseNumbers.every(isRequiredFinitePurchaseNumber_);
  if (!validNumbers) return itemError_('INVALID_ITEM', 'Invalid purchase number at index ' + index);
  return null;
}

function validateV2Purchase_(item, index) {
  const legacy = validateLegacyPurchase_(item, index);
  if (legacy) return legacy;
  if (!isUuid_(text_(item.actionId))) return itemError_('INVALID_ITEM', 'Invalid purchase actionId at index ' + index);
  return null;
}

function validateLegacyConsumption_(item, index, resolver) {
  if (!item || !text_(item.productId) || !isFiniteNumber_(item.uses) || Number(item.uses) <= 0) return itemError_('INVALID_ITEM', 'Invalid consumption at index ' + index);
  if (!resolver[text_(item.productId)]) return itemError_('UNKNOWN_PRODUCT', 'Unknown product at consumption index ' + index);
  return null;
}

function validateV2Consumption_(item, index) {
  if (!item || !isUuid_(text_(item.eventId))) return itemError_('INVALID_ITEM', 'Invalid consumption eventId at index ' + index);
  if (!text_(item.productId) && !text_(item.productUuid)) return itemError_('INVALID_ITEM', 'Missing product reference at index ' + index);
  if (!isFiniteNumber_(item.uses) || Number(item.uses) <= 0) return itemError_('INVALID_ITEM', 'Invalid uses at index ' + index);
  return null;
}

function validateV2FinishAction_(item, index) {
  if (!item || !isUuid_(text_(item.actionId))) {
    return itemError_('INVALID_ITEM', 'Invalid finish actionId at index ' + index);
  }
  if (!isClientDate_(item.date) || !isClientTime_(item.time)) {
    return itemError_('INVALID_ITEM', 'Invalid finish date or time at index ' + index);
  }
  if (!text_(item.productId)) {
    return itemError_('INVALID_ITEM', 'Missing finish productId at index ' + index);
  }
  if (text_(item.productUuid) && !isUuid_(text_(item.productUuid))) {
    return itemError_('INVALID_ITEM', 'Invalid finish productUuid at index ' + index);
  }
  return null;
}

function validateClientState_(value) {
  if (!isPlainObject_(value)) {
    return itemError_('INVALID_ITEM', 'clientState must be an object');
  }
  if (
    typeof value.loadedPenUpdatedAtEpochMillis !== 'number' ||
    !Number.isFinite(value.loadedPenUpdatedAtEpochMillis) ||
    !Number.isInteger(value.loadedPenUpdatedAtEpochMillis) ||
    value.loadedPenUpdatedAtEpochMillis <= 0
  ) {
    return itemError_(
      'INVALID_ITEM',
      'loadedPenUpdatedAtEpochMillis must be a positive integer'
    );
  }
  if (
    value.loadedPenProductId !== null &&
    typeof value.loadedPenProductId !== 'string'
  ) {
    return itemError_(
      'INVALID_ITEM',
      'loadedPenProductId must be a string or null'
    );
  }
  return null;
}

function preflightSyncRequest_(payload, apiVersion) {
  const purchases = arrayOrEmpty_(payload && payload.purchases);
  const consumptions = arrayOrEmpty_(payload && payload.consumptions);
  const finishActions = arrayOrEmpty_(payload && payload.finishActions);
  const consumptionCorrections = arrayOrEmpty_(
    payload && payload.consumptionCorrections
  );
  const hasClientState = Object.prototype.hasOwnProperty.call(
    payload || {},
    'clientState'
  );
  const clientState = hasClientState ? payload.clientState : undefined;
  if (apiVersion === 1 && hasClientState) {
    return {
      purchases: purchases,
      consumptions: consumptions,
      finishActions: finishActions,
      consumptionCorrections: consumptionCorrections,
      clientState: clientState,
      failure: itemError_('INVALID_ITEM', 'clientState requires apiVersion 2')
    };
  }
  if (hasClientState) {
    const clientStateError = validateClientState_(clientState);
    if (clientStateError) {
      return {
        purchases: purchases,
        consumptions: consumptions,
        finishActions: finishActions,
        consumptionCorrections: consumptionCorrections,
        clientState: clientState,
        failure: clientStateError
      };
    }
  }
  if (apiVersion === 1 && Object.prototype.hasOwnProperty.call(payload || {}, 'finishActions')) {
    return {
      purchases: purchases,
      consumptions: consumptions,
      finishActions: finishActions,
      consumptionCorrections: consumptionCorrections,
      clientState: clientState,
      failure: itemError_('INVALID_ITEM', 'finishActions require apiVersion 2')
    };
  }
  if (
    apiVersion === 1 &&
    Object.prototype.hasOwnProperty.call(
      payload || {},
      'consumptionCorrections'
    )
  ) {
    return {
      purchases: purchases,
      consumptions: consumptions,
      finishActions: finishActions,
      consumptionCorrections: consumptionCorrections,
      clientState: clientState,
      failure: itemError_(
        'INVALID_ITEM',
        'consumptionCorrections require apiVersion 2'
      )
    };
  }
  const sizeError = validateBatchSize_(
    purchases,
    consumptions,
    finishActions,
    consumptionCorrections
  );
  if (sizeError) {
    return {
      purchases: purchases,
      consumptions: consumptions,
      finishActions: finishActions,
      consumptionCorrections: consumptionCorrections,
      clientState: clientState,
      failure: itemError_('INVALID_ITEM', sizeError)
    };
  }
  if (apiVersion === CANN.API_VERSION) {
    if (!isUuid_(text_(payload && payload.requestId))) {
      return {
        purchases: purchases,
        consumptions: consumptions,
        finishActions: finishActions,
        consumptionCorrections: consumptionCorrections,
        clientState: clientState,
        failure: itemError_('INVALID_ITEM', 'requestId must be a UUID')
      };
    }
    const duplicateActionId = firstDuplicate_(purchases
      .concat(finishActions)
      .concat(consumptionCorrections)
      .map(item => text_(item && item.actionId))
      .filter(Boolean));
    const duplicateEventId = firstDuplicate_(consumptions.map(item => text_(item && item.eventId)).filter(Boolean));
    if (duplicateActionId || duplicateEventId) {
      return {
        purchases: purchases,
        consumptions: consumptions,
        finishActions: finishActions,
        consumptionCorrections: consumptionCorrections,
        clientState: clientState,
        failure: itemError_('INVALID_ITEM', 'Duplicate UUID inside request')
      };
    }
  }
  return {
    purchases: purchases,
    consumptions: consumptions,
    finishActions: finishActions,
    consumptionCorrections: consumptionCorrections,
    clientState: clientState,
    failure: null
  };
}

function isRequestPayloadObject_(value) {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function isPlainObject_(value) {
  return !!value && Object.prototype.toString.call(value) === '[object Object]';
}

function validateBatchSize_(
  purchases,
  consumptions,
  finishActions,
  consumptionCorrections
) {
  if (consumptionCorrections == null) consumptionCorrections = [];
  if (
    !Array.isArray(purchases) ||
    !Array.isArray(consumptions) ||
    !Array.isArray(finishActions) ||
    !Array.isArray(consumptionCorrections)
  ) {
    return 'purchases, consumptions, finishActions, and ' +
      'consumptionCorrections must be arrays';
  }
  if (
    purchases.length +
    consumptions.length +
    finishActions.length +
    consumptionCorrections.length >
      CANN.MAX_BATCH_SIZE
  ) {
    return 'Batch exceeds maximum size';
  }
  return null;
}

function validateV2ConsumptionCorrection_(item, index) {
  if (!item || !isUuid_(text_(item.actionId))) {
    return itemError_(
      'INVALID_ITEM',
      'Invalid correction actionId at index ' + index
    );
  }
  if (!isUuid_(text_(item.targetEventId))) {
    return itemError_(
      'INVALID_ITEM',
      'Invalid correction targetEventId at index ' + index
    );
  }
  const expectedHead = text_(item.expectedCorrectionHeadId);
  if (expectedHead && !isUuid_(expectedHead)) {
    return itemError_(
      'INVALID_ITEM',
      'Invalid expected correction head at index ' + index
    );
  }
  const operation = text_(item.operation).toUpperCase();
  if (!Object.prototype.hasOwnProperty.call(
    CANN.CORRECTION_OPERATIONS,
    operation
  )) {
    return itemError_(
      'INVALID_ITEM',
      'Invalid correction operation at index ' + index
    );
  }
  if (
    item.reason != null &&
    typeof item.reason !== 'string'
  ) {
    return itemError_(
      'INVALID_ITEM',
      'Correction reason must be text at index ' + index
    );
  }
  if (text_(item.reason).length > CANN.MAX_CORRECTION_REASON_LENGTH) {
    return itemError_(
      'INVALID_ITEM',
      'Correction reason exceeds ' +
      CANN.MAX_CORRECTION_REASON_LENGTH + ' characters'
    );
  }
  if (
    item.reopenProduct != null &&
    typeof item.reopenProduct !== 'boolean'
  ) {
    return itemError_(
      'INVALID_ITEM',
      'Correction reopenProduct must be true or false at index ' + index
    );
  }
  if (operation !== CANN.CORRECTION_OPERATIONS.REPLACE) {
    if (item.replacement != null) {
      return itemError_(
        'INVALID_ITEM',
        operation + ' correction must not include replacement values'
      );
    }
    return null;
  }
  const replacement = item.replacement;
  if (!replacement || typeof replacement !== 'object' ||
      Array.isArray(replacement)) {
    return itemError_(
      'INVALID_ITEM',
      'REPLACE correction requires a replacement snapshot at index ' + index
    );
  }
  if (
    !isClientDate_(replacement.date) ||
    !isClientTime_(replacement.time) ||
    !isUuid_(text_(replacement.productUuid)) ||
    !text_(replacement.productId) ||
    !isFiniteNumber_(replacement.uses) ||
    Number(replacement.uses) <= 0 ||
    typeof replacement.finished !== 'boolean' ||
    typeof replacement.weightCode !== 'string'
  ) {
    return itemError_(
      'INVALID_ITEM',
      'Invalid REPLACE snapshot at index ' + index
    );
  }
  return null;
}

function itemError_(code, message) { return { code: code, message: message }; }

