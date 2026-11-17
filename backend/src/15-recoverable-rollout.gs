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

