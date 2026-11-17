// -----------------------------------------------------------------------------
// Batch write helpers
// -----------------------------------------------------------------------------

function newPurchaseRow_(item, now, taxRate) {
  const p = item.item;
  const cost = purchaseNumberOrBlank_(p.cost);
  const thc = purchaseNumberOrBlank_(p.thc);
  const grams = purchaseNumberOrBlank_(p.grams);
  const postTax = truthy_(p.postTax);
  const finalCost = cost === '' ? '' : (postTax ? cost : cost * (1 + taxRate));
  return [
    text_(p.date), text_(p.type), text_(p.name), cost,
    thc, grams, truthy_(p.borrowed) ? 1 : 0, CANN.STATUS.UNOPENED,
    item.legacyProductId, 0, postTax, finalCost,
    '', item.productUuid, item.actionId, now, ''
  ];
}

function appendPurchaseRows_(context, staged, now, configuredTaxRate) {
  if (!staged.length) return;
  const sheet = context.purchasesSheet;
  const taxRate = finiteNumberOr_(configuredTaxRate, 0.13);
  const rows = staged.map(item => {
    const row = newPurchaseRow_(item, now, taxRate);
    if (context.headers && context.headers['Last quantity'] !== undefined) row.push('');
    return row;
  });
  const firstRow = sheet.getLastRow() + 1;
  sheet.getRange(firstRow, 1, rows.length, rows[0].length).setValues(rows);
  staged.forEach((item, index) => {
    item.rowNumber = firstRow + index;
    item.row = rows[index];
    item.status = CANN.STATUS.UNOPENED;
    item.uses = 0;
    item.mostRecentUse = null;
    item.finishedAt = null;
    item.lastQuantity = null;
    context.byLegacyId[item.legacyProductId] = item;
    if (item.productUuid) context.byProductUuid[item.productUuid] = item;
    if (item.actionId && context.byActionId) context.byActionId[item.actionId] = item;
  });
}

function appendConsumptionRows_(ss, staged, skipCompatibility, timing, runtimeContext) {
  let phaseStarted = Date.now();
  if (!staged.length) {
    recordBackendPhase_(timing, 'compatibilityAppend', phaseStarted);
    phaseStarted = Date.now();
    recordBackendPhase_(timing, 'canonicalAppend', phaseStarted);
    return;
  }
  if (!skipCompatibility) {
    const responses = runtimeContext ? runtimeContext.sheets.responses : requiredSheet_(ss, CANN.SHEETS.RESPONSES);
    const responseHeaders = runtimeContext ? runtimeContext.headers.responses : headerMap_(responses);
    requireHeaders_(responseHeaders, ['Timestamp', 'Product', 'Uses']);
    const compatibilityRows = staged.map(item => {
      const row = Array(responses.getLastColumn()).fill('');
      row[responseHeaders['Timestamp']] = item.timestamp;
      row[responseHeaders['Product']] = item.legacyProductId;
      row[responseHeaders['Uses']] = item.uses;
      if (responseHeaders['Date'] !== undefined) row[responseHeaders['Date']] = item.localDate;
      if (responseHeaders['Time'] !== undefined) row[responseHeaders['Time']] = item.localTime;
      if (responseHeaders['Weight code'] !== undefined) row[responseHeaders['Weight code']] = item.weightCode || '';
      if (responseHeaders['Mark as Finished?'] !== undefined) row[responseHeaders['Mark as Finished?']] = item.isFinished ? 'Yes' : '';
      return row;
    });
    responses.getRange(responses.getLastRow() + 1, 1, compatibilityRows.length, responses.getLastColumn()).setValues(compatibilityRows);
    const firstRow = responses.getLastRow() - compatibilityRows.length + 1;
    staged.forEach((item, index) => {
      item.legacySourceSheet = responses.getName();
      item.legacySourceRow = firstRow + index;
    });
  }
  recordBackendPhase_(timing, 'compatibilityAppend', phaseStarted);

  phaseStarted = Date.now();
  appendEventRows_(runtimeContext ? runtimeContext.sheets.events : requiredSheet_(ss, CANN.SHEETS.EVENTS), staged);
  recordBackendPhase_(timing, 'canonicalAppend', phaseStarted);
}

function appendEventRows_(sheet, staged) {
  if (!staged.length) return;
  const rows = staged.map(item => [
    item.eventId, item.timestamp, item.localDate, item.localTime,
    item.productUuid, item.legacyProductId, item.uses, item.weightCode || '',
    !!item.isFinished, item.source, item.requestId || '',
    item.legacySourceSheet || '', item.legacySourceRow || ''
  ]);
  sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, CANN.EVENT_HEADERS.length).setValues(rows);
}

function applyProductEffects_(context, consumptions) {
  if (!consumptions.length) return;
  const effects = calculateProductEffects_(context, consumptions);
  const sheet = context.purchasesSheet;
  const headers = context.headers;
  effects.forEach(effect => {
    // These are intentionally up to five exact cells on affected rows only. This
    // avoids rewriting formulas, typed cells, blank physical rows, or products
    // unrelated to the accepted events.
    sheet.getRange(effect.rowNumber, headers['Finished'] + 1).setValue(effect.status);
    sheet.getRange(effect.rowNumber, headers['Uses'] + 1).setValue(effect.uses);
    sheet.getRange(effect.rowNumber, headers['Most recent use'] + 1).setValue(effect.mostRecentUse || '');
    sheet.getRange(effect.rowNumber, headers['Finished At'] + 1).setValue(effect.finishedAt || '');
    if (headers['Last quantity'] !== undefined) {
      sheet.getRange(effect.rowNumber, headers['Last quantity'] + 1).setValue(
        effect.lastQuantity == null ? '' : effect.lastQuantity
      );
    }

    const product = context.byLegacyId[effect.legacyProductId];
    product.status = effect.status;
    product.uses = effect.uses;
    product.mostRecentUse = effect.mostRecentUse;
    product.finishedAt = effect.finishedAt;
    product.lastQuantity = effect.lastQuantity;
  });
}

/**
 * Finish-only actions deliberately update only the two finish projection cells.
 * They are not consumption events, so Uses, Most recent use, and Last quantity
 * must remain untouched.
 */
function applyFinishActions_(context, finishActions) {
  if (!finishActions.length) return;
  const effects = calculateFinishEffects_(context, finishActions);
  const sheet = context.purchasesSheet;
  const headers = context.headers;
  effects.forEach(effect => {
    if (!effect.rowNumber) return;
    sheet.getRange(effect.rowNumber, headers['Finished'] + 1)
      .setValue(effect.status);
    sheet.getRange(effect.rowNumber, headers['Finished At'] + 1)
      .setValue(effect.finishedAt);
    const product = context.byLegacyId[effect.legacyProductId];
    if (!product) return;
    product.status = effect.status;
    product.finishedAt = effect.finishedAt;
  });
}

function calculateFinishEffects_(context, finishActions) {
  const effectsByProduct = {};
  finishActions.forEach(action => {
    const product = context.byLegacyId[action.legacyProductId];
    if (!product || (!product.rowNumber && !product.pendingAppend)) return;
    // Preserve append order inside the immutable request snapshot: a later
    // accepted finish action for the same product supplies the final timestamp.
    effectsByProduct[action.legacyProductId] = {
      legacyProductId: action.legacyProductId,
      rowNumber: product.rowNumber,
      pendingAppend: product.pendingAppend === true,
      status: CANN.STATUS.FINISHED,
      finishedAt: action.timestamp
    };
  });
  return Object.keys(effectsByProduct).map(id => effectsByProduct[id]);
}

function calculateProductEffects_(context, consumptions) {
  const grouped = {};
  consumptions.forEach(item => {
    const id = item.legacyProductId;
    if (!grouped[id]) grouped[id] = [];
    grouped[id].push(item);
  });

  return Object.keys(grouped).map(legacyProductId => {
    const product = context.byLegacyId[legacyProductId];
    if (!product || (!product.rowNumber && !product.pendingAppend)) return null;
    let status = allowedStatusOr_(product.status, CANN.STATUS.ACTIVE);
    let uses = finiteNumberOr_(product.uses, 0);
    let mostRecentUse = product.mostRecentUse || null;
    let latestMillis = timestampMillisOrNull_(mostRecentUse);
    let lastQuantity = optionalFiniteNumber_(product.lastQuantity);
    let finishedAt = product.finishedAt || null;

    grouped[legacyProductId].forEach(item => {
      uses += finiteNumberOr_(item.uses, 0);
      const eventMillis = timestampMillisOrNull_(item.timestamp);
      if (eventMillis != null && (latestMillis == null || eventMillis > latestMillis)) {
        mostRecentUse = item.timestamp;
        latestMillis = eventMillis;
        lastQuantity = finiteNumberOr_(item.uses, 0);
      }
      if (item.isFinished) {
        status = CANN.STATUS.FINISHED;
        // Preserve the deployed append-order rule: the last accepted finishing
        // event in this request wins, even when its client timestamp is older.
        finishedAt = item.timestamp;
      } else if (status === CANN.STATUS.UNOPENED) {
        status = CANN.STATUS.ACTIVE;
      }
    });

    return {
      legacyProductId: legacyProductId,
      rowNumber: product.rowNumber,
      pendingAppend: product.pendingAppend === true,
      status: status,
      uses: uses,
      mostRecentUse: mostRecentUse,
      finishedAt: finishedAt,
      lastQuantity: lastQuantity
    };
  }).filter(Boolean);
}

function calculateCorrectionProjectionEffects_(
  productContext,
  projectedState,
  corrections
) {
  const affected = {};
  const reopenProducts = {};
  (corrections || []).forEach(correction => {
    (correction.affectedProductIds || []).forEach(productId => {
      affected[productId] = true;
    });
    if (correction.reopenLegacyProductId) {
      reopenProducts[correction.reopenLegacyProductId] = true;
    }
  });
  const projections = effectiveProductProjectionMap_(
    projectedState.resolution.effectiveEvents,
    projectedState.resolution.reopenedProducts,
    {
      finishActionProductIds:
        projectedState.snapshot.finishActionProductIds,
      productsByProductId: projectedState.products.byProductId
    }
  );
  return Object.keys(affected).map(legacyProductId => {
    const product = productContext.byLegacyId[legacyProductId];
    if (!product) {
      throw new Error(
        'CORRECTION_PROJECTION_BLOCKED: missing product ' +
        legacyProductId
      );
    }
    const expected = expectedProjectionForProduct_(
      product,
      projections[legacyProductId],
      { reopenProduct: reopenProducts[legacyProductId] === true }
    );
    return {
      legacyProductId: legacyProductId,
      rowNumber: product.rowNumber,
      status: expected.status,
      uses: expected.uses,
      mostRecentUse: expected.mostRecentUse,
      lastQuantity: expected.lastQuantity,
      finishedAt: expected.finishedAt
    };
  });
}

function upsertLedger_(ss, requestId, purchaseCount, consumptionCount, result, durationMs, errorCode, runtimeContext) {
  const sheet = runtimeContext ? runtimeContext.sheets.ledger : requiredSheet_(ss, CANN.SHEETS.LEDGER);
  const headers = runtimeContext ? runtimeContext.headers.ledger : headerMap_(sheet);
  requireHeaders_(headers, ['Request UUID']);
  // The sheet object is intentionally created before the lock so the request can
  // reuse one spreadsheet context. Do not use its cached getLastRow() value for
  // the final ledger decision: a prior execution may have appended while this
  // request waited for the lock. Search the durable Request UUID column across
  // the current grid and use appendRow() for the no-match case.
  const requestIdRange = sheet.getRange(
    2,
    headers['Request UUID'] + 1,
    Math.max(1, sheet.getMaxRows() - 1),
    1
  );
  const match = requestIdRange.createTextFinder(requestId)
    .matchEntireCell(true)
    .matchCase(true)
    .useRegularExpression(false)
    .findNext();
  const values = [[requestId, CANN.API_VERSION, new Date(), purchaseCount, consumptionCount, result, durationMs, errorCode || '']];
  if (match) sheet.getRange(match.getRow(), 1, 1, values[0].length).setValues(values);
  else sheet.appendRow(values[0]);

  // The lock must not be released while the final ledger write is still queued.
  // Otherwise a waiting execution can miss it and append a duplicate row.
  SpreadsheetApp.flush();
}

