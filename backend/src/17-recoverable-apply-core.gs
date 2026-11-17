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

