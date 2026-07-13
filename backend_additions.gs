/**
 * Cannsheet Backend v2
 *
 * Backward-compatible Apps Script backend for the Android client and Google Form.
 * Shared mutations are serialized, v2 records are idempotent by UUID, and the
 * legacy GET/POST fields remain available during rollout.
 */

// -----------------------------------------------------------------------------
// Config
// -----------------------------------------------------------------------------

const CANN = Object.freeze({
  API_VERSION: 2,
  SCHEMA_VERSION: 2,
  TIME_ZONE: 'America/New_York',
  LOCK_TIMEOUT_MS: 30000,
  MAX_BATCH_SIZE: 100,
  FORM_PRODUCT_QUESTION: 'Product',
  SHEETS: Object.freeze({
    PURCHASES: 'Purchases',
    RESPONSES: 'Form Responses 1',
    EVENTS: 'ConsumptionEvents',
    LEDGER: 'SyncLedger',
    CONFIG: 'Config',
    MIGRATION_REPORT: 'MigrationReport'
  }),
  PURCHASE_HEADERS: Object.freeze([
    'Date', 'Type', 'Product name', 'Pre-tax cost', 'THC%', 'Grams',
    'Borrowed', 'Finished', 'Product ID', 'Uses', 'Post-tax', 'Final cost',
    'Most recent use', 'Product UUID', 'Client Action UUID', 'Created At',
    'Finished At'
  ]),
  EVENT_HEADERS: Object.freeze([
    'Event UUID', 'Timestamp', 'Local Date', 'Local Time', 'Product UUID',
    'Legacy Product ID', 'Uses', 'Weight Code', 'Finished', 'Source',
    'Request UUID', 'Legacy Source Sheet', 'Legacy Source Row'
  ]),
  LEDGER_HEADERS: Object.freeze([
    'Request UUID', 'API Version', 'Received At', 'Purchase Count',
    'Consumption Count', 'Result', 'Duration Ms', 'Error Code'
  ]),
  REPORT_HEADERS: Object.freeze([
    'Type', 'Source Sheet', 'Source Row', 'Product ID', 'Detail', 'Recorded At'
  ]),
  STATUS: Object.freeze({ ACTIVE: 0, FINISHED: 1, UNOPENED: 2 })
});

// -----------------------------------------------------------------------------
// HTTP API
// -----------------------------------------------------------------------------

function doGet() {
  let environment = '';
  try {
    environment = environment_();
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const purchases = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
    const headers = headerMap_(purchases);
    requireHeaders_(headers, ['Type', 'Product name', 'Pre-tax cost', 'THC%', 'Grams', 'Finished', 'Product ID']);
    const rows = readDataRows_(purchases);
    const interactions = latestInteractions_(ss);

    const products = rows
      .filter(row => text_(value_(row, headers, 'Product ID')) && text_(value_(row, headers, 'Product name')))
      .map(row => {
        const legacyId = text_(value_(row, headers, 'Product ID'));
        const recent = interactions[legacyId];
        const product = {
          id: legacyId,
          name: text_(value_(row, headers, 'Product name')),
          type: text_(value_(row, headers, 'Type')),
          cost: finiteNumberOr_(value_(row, headers, 'Pre-tax cost'), 0),
          thc: finiteNumberOr_(value_(row, headers, 'THC%'), 0),
          grams: finiteNumberOr_(value_(row, headers, 'Grams'), 0),
          status: allowedStatusOr_(value_(row, headers, 'Finished'), CANN.STATUS.ACTIVE)
        };
        if (headers['Product UUID'] !== undefined) {
          const productUuid = text_(value_(row, headers, 'Product UUID'));
          if (productUuid) product.productUuid = productUuid;
        }
        if (recent) {
          product.lastLoggedAtEpochMillis = recent.lastLoggedAtEpochMillis;
          product.lastQuantity = recent.lastQuantity;
        }
        return product;
      });

    return jsonOutput_({ products: products, apiVersion: CANN.API_VERSION, environment: environment });
  } catch (error) {
    console.error('GET failed: ' + conciseError_(error));
    return jsonOutput_({ error: conciseError_(error), errorCode: 'INTERNAL_ERROR', environment: environment || undefined });
  }
}

function doPost(e) {
  const started = Date.now();
  let payload;
  try {
    if (!e || !e.postData || typeof e.postData.contents !== 'string') {
      return requestFailure_('INVALID_JSON', 'Missing JSON request body');
    }
    payload = JSON.parse(e.postData.contents);
  } catch (error) {
    return requestFailure_('INVALID_JSON', 'Malformed JSON request body');
  }

  const apiVersion = payload.apiVersion == null ? 1 : Number(payload.apiVersion);
  if (apiVersion !== 1 && apiVersion !== CANN.API_VERSION) {
    return requestFailure_('UNSUPPORTED_API_VERSION', 'Unsupported apiVersion');
  }

  let environment;
  try {
    environment = environment_();
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const mismatch = validateRequestEnvironment_(environment, payload.environment);
    if (mismatch) return requestFailure_('ENVIRONMENT_MISMATCH', mismatch, environment);
  } catch (error) {
    return requestFailure_('CONFIGURATION_ERROR', conciseError_(error), environment);
  }

  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) {
    return requestFailure_('LOCK_TIMEOUT', 'The backend is busy; retry this request');
  }

  try {
    const response = apiVersion === 1
      ? handleLegacySyncLocked_(payload)
      : handleV2SyncLocked_(payload, started);
    response.environment = environment;
    return jsonOutput_(response);
  } catch (error) {
    console.error('POST failed: ' + conciseError_(error));
    return requestFailure_('INTERNAL_ERROR', conciseError_(error), environment);
  } finally {
    lock.releaseLock();
  }
}

function handleSync(payload) {
  // Retained for compatibility with manual callers and old trigger references.
  const environment = environment_();
  const ss = spreadsheet_();
  assertConfigEnvironment_(ss);
  const mismatch = validateRequestEnvironment_(environment, (payload || {}).environment);
  if (mismatch) return requestFailure_('ENVIRONMENT_MISMATCH', mismatch, environment);
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) {
    return requestFailure_('LOCK_TIMEOUT', 'The backend is busy; retry this request');
  }
  try {
    const response = handleLegacySyncLocked_(payload || {});
    response.environment = environment;
    return jsonOutput_(response);
  } finally {
    lock.releaseLock();
  }
}

// -----------------------------------------------------------------------------
// Version 1 sync (all-or-nothing response semantics)
// -----------------------------------------------------------------------------

function handleLegacySyncLocked_(payload) {
  const purchases = arrayOrEmpty_(payload.purchases);
  const consumptions = arrayOrEmpty_(payload.consumptions);
  const sizeError = validateBatchSize_(purchases, consumptions);
  if (sizeError) return legacyFailure_(sizeError);

  const ss = spreadsheet_();
  assertConfigEnvironment_(ss);
  ensureCoreSchema_(ss);
  const context = productContext_(ss);
  const purchaseErrors = purchases.map((item, index) => validateLegacyPurchase_(item, index)).filter(Boolean);
  if (purchaseErrors.length) return legacyFailure_(purchaseErrors[0].message);

  const staged = stagePurchases_(purchases.map((item, index) => ({
    item: item,
    actionId: Utilities.getUuid(),
    tempId: text_(item.tempId),
    sourceIndex: index,
    legacy: true
  })), context);
  if (staged.rejected.length) return legacyFailure_(staged.rejected[0].message);

  const resolver = Object.assign({}, context.byLegacyId, staged.byTempId);
  const consumptionErrors = consumptions
    .map((item, index) => validateLegacyConsumption_(item, index, resolver))
    .filter(Boolean);
  if (consumptionErrors.length) return legacyFailure_(consumptionErrors[0].message);

  const now = new Date();
  appendPurchaseRows_(context.purchasesSheet, staged.accepted, now);
  const stagedConsumptions = consumptions.map(item => stageLegacyConsumption_(item, resolver, now));
  appendConsumptionRows_(ss, stagedConsumptions);
  applyProductEffects_(context.purchasesSheet, stagedConsumptions);
  updateFormAndDescriptionLocked_(ss);

  const productIdMap = {};
  staged.accepted.forEach(item => { productIdMap[item.tempId] = item.legacyProductId; });
  return { success: true, message: 'Sync complete', productIdMap: productIdMap };
}

function legacyFailure_(message) {
  return { success: false, message: message, productIdMap: {} };
}

// -----------------------------------------------------------------------------
// Version 2 sync (record-level idempotency and acknowledgements)
// -----------------------------------------------------------------------------

function handleV2SyncLocked_(payload, started) {
  const requestId = text_(payload.requestId);
  const purchases = arrayOrEmpty_(payload.purchases);
  const consumptions = arrayOrEmpty_(payload.consumptions);
  if (!isUuid_(requestId)) {
    return v2RequestFailure_(requestId, 'INVALID_ITEM', 'requestId must be a UUID');
  }
  const sizeError = validateBatchSize_(purchases, consumptions);
  if (sizeError) return v2RequestFailure_(requestId, 'INVALID_ITEM', sizeError);

  const duplicateActionId = firstDuplicate_(purchases.map(item => text_(item.actionId)).filter(Boolean));
  const duplicateEventId = firstDuplicate_(consumptions.map(item => text_(item.eventId)).filter(Boolean));
  if (duplicateActionId || duplicateEventId) {
    return v2RequestFailure_(requestId, 'INVALID_ITEM', 'Duplicate UUID inside request');
  }

  const ss = spreadsheet_();
  assertConfigEnvironment_(ss);
  ensureCoreSchema_(ss);
  const context = productContext_(ss);
  const existingEvents = eventContext_(ss);
  const acceptedPurchases = [];
  const rejectedPurchases = [];
  const newPurchases = [];

  purchases.forEach((item, index) => {
    const error = validateV2Purchase_(item, index);
    if (error) {
      rejectedPurchases.push(rejectedPurchase_(item, error.code, error.message));
      return;
    }
    const actionId = text_(item.actionId);
    const existing = context.byActionId[actionId];
    if (existing) {
      acceptedPurchases.push(purchaseAck_(item, existing, 'duplicate'));
      return;
    }
    newPurchases.push({ item: item, actionId: actionId, tempId: text_(item.tempId), sourceIndex: index, legacy: false });
  });

  const staged = stagePurchases_(newPurchases, context);
  staged.rejected.forEach(item => rejectedPurchases.push(rejectedPurchase_(item.item, item.code, item.message)));
  staged.accepted.forEach(item => acceptedPurchases.push(purchaseAck_(item.item, item, 'committed')));

  const resolver = Object.assign({}, context.byLegacyId, staged.byTempId);
  const acceptedConsumptions = [];
  const rejectedConsumptions = [];
  const stagedConsumptions = [];

  consumptions.forEach((item, index) => {
    const error = validateV2Consumption_(item, index);
    if (error) {
      rejectedConsumptions.push(rejectedConsumption_(item, error.code, error.message));
      return;
    }
    const eventId = text_(item.eventId);
    if (existingEvents.byEventId[eventId]) {
      acceptedConsumptions.push({ eventId: eventId, status: 'duplicate' });
      return;
    }
    const resolved = resolveProduct_(item, resolver, context.byProductUuid);
    if (!resolved) {
      rejectedConsumptions.push(rejectedConsumption_(item, 'UNKNOWN_PRODUCT', 'Unknown product reference'));
      return;
    }
    const stagedItem = stageV2Consumption_(item, resolved, requestId);
    stagedConsumptions.push(stagedItem);
    acceptedConsumptions.push({ eventId: eventId, status: 'committed' });
  });

  const now = new Date();
  appendPurchaseRows_(context.purchasesSheet, staged.accepted, now);
  appendConsumptionRows_(ss, stagedConsumptions);
  applyProductEffects_(context.purchasesSheet, stagedConsumptions);
  if (staged.accepted.length) updateFormAndDescriptionLocked_(ss);

  const allAccepted = rejectedPurchases.length === 0 && rejectedConsumptions.length === 0;
  const productIdMap = {};
  acceptedPurchases.forEach(item => {
    if (item.tempId && item.legacyProductId) productIdMap[item.tempId] = item.legacyProductId;
  });
  const response = {
    apiVersion: CANN.API_VERSION,
    requestId: requestId,
    success: true,
    allAccepted: allAccepted,
    message: allAccepted ? 'Sync complete' : 'Some items were rejected',
    productIdMap: productIdMap,
    acknowledgedPurchases: acceptedPurchases,
    rejectedPurchases: rejectedPurchases,
    acknowledgedConsumptions: acceptedConsumptions,
    rejectedConsumptions: rejectedConsumptions
  };
  upsertLedger_(ss, requestId, purchases.length, consumptions.length, allAccepted ? 'ACCEPTED' : 'PARTIAL', Date.now() - started, '');
  return response;
}

function v2RequestFailure_(requestId, code, message) {
  return {
    apiVersion: CANN.API_VERSION,
    requestId: requestId || null,
    success: false,
    allAccepted: false,
    message: message,
    errorCode: code,
    productIdMap: {},
    acknowledgedPurchases: [],
    rejectedPurchases: [],
    acknowledgedConsumptions: [],
    rejectedConsumptions: []
  };
}

// -----------------------------------------------------------------------------
// Google Form integration
// -----------------------------------------------------------------------------

function updateFormAndDescription() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    updateFormAndDescriptionLocked_(spreadsheet_());
  } finally {
    lock.releaseLock();
  }
}

function updateFormAndDescriptionLocked_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const headers = headerMap_(sheet);
  requireHeaders_(headers, ['Product name', 'Finished', 'Product ID', 'Uses', 'Most recent use']);
  const available = readDataRows_(sheet).filter(row =>
    allowedStatusOr_(value_(row, headers, 'Finished'), CANN.STATUS.ACTIVE) === CANN.STATUS.ACTIVE &&
    text_(value_(row, headers, 'Product ID'))
  );
  const choices = available.map(row => text_(value_(row, headers, 'Product ID')));
  const description = available.map(row => {
    const id = text_(value_(row, headers, 'Product ID'));
    const name = text_(value_(row, headers, 'Product name'));
    const uses = finiteNumberOr_(value_(row, headers, 'Uses'), 0);
    const recent = value_(row, headers, 'Most recent use');
    return id + ' - ' + name + ' (Uses: ' + uses + ')' + (recent ? ' Last: ' + recent : '');
  }).join('\n');
  assertConfigEnvironment_(ss);
  const form = FormApp.openById(requiredScriptProperty_('FORM_ID'));
  const item = form.getItems().find(formItem => formItem.getTitle() === CANN.FORM_PRODUCT_QUESTION);
  if (!item) throw new Error('Form question not found: ' + CANN.FORM_PRODUCT_QUESTION);
  item.setHelpText(description || 'No active products to list.');
  if (choices.length) item.asMultipleChoiceItem().setChoiceValues(choices);
}

function clearFormQuestion() {
  const ss = spreadsheet_();
  assertConfigEnvironment_(ss);
  const form = FormApp.openById(requiredScriptProperty_('FORM_ID'));
  const item = form.getItems().find(formItem => formItem.getTitle() === CANN.FORM_PRODUCT_QUESTION);
  if (item) item.setHelpText('No active products to list.');
}

function onFormSubmit(e) {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    if (!e || !e.range) throw new Error('Missing form-submit event range');
    const ss = e.range.getSheet().getParent();
    assertConfiguredSpreadsheet_(ss);
    assertConfigEnvironment_(ss);
    const responseSheet = e.range.getSheet();
    if (responseSheet.getName() !== CANN.SHEETS.RESPONSES) return;
    ensureCoreSchema_(ss);
    const rowNumber = e.range.getRow();
    const headers = headerMap_(responseSheet);
    const values = responseSheet.getRange(rowNumber, 1, 1, responseSheet.getLastColumn()).getValues()[0];
    const legacyId = text_(value_(values, headers, 'Product'));
    const context = productContext_(ss);
    const product = context.byLegacyId[legacyId];
    if (!product) {
      recordMigrationIssue_(ss, 'UNKNOWN_PRODUCT', responseSheet.getName(), rowNumber, legacyId, 'Form submission was preserved but could not be canonicalized');
      return;
    }
    const timestamp = dateOrNow_(value_(values, headers, 'Timestamp'));
    const eventId = deterministicLegacyEventUuid_(ss.getId(), responseSheet.getName(), rowNumber);
    if (eventContext_(ss).byEventId[eventId]) return;
    const event = {
      eventId: eventId,
      timestamp: timestamp,
      localDate: formatDate_(timestamp),
      localTime: formatTime_(timestamp),
      productUuid: product.productUuid,
      legacyProductId: product.legacyProductId,
      uses: finiteNumberOr_(value_(values, headers, 'Uses'), 0),
      weightCode: text_(value_(values, headers, 'Weight code')),
      isFinished: truthy_(value_(values, headers, 'Mark as Finished?')),
      source: 'FORM',
      requestId: '',
      legacySourceSheet: responseSheet.getName(),
      legacySourceRow: rowNumber,
      compatibilityRow: null
    };
    appendConsumptionRows_(ss, [event], true);
    applyProductEffects_(context.purchasesSheet, [event]);
    updateFormAndDescriptionLocked_(ss);
  } finally {
    lock.releaseLock();
  }
}

function onInventoryEdit(e) {
  if (!e || !e.range) return;
  const range = e.range;
  if (range.getSheet().getName() !== CANN.SHEETS.PURCHASES || range.getRow() === 1) return;
  const relevantColumns = [2, 3, 7, 8, 9];
  const first = range.getColumn();
  const last = range.getLastColumn();
  if (!relevantColumns.some(column => column >= first && column <= last)) return;
  const ss = range.getSheet().getParent();
  assertConfiguredSpreadsheet_(ss);
  assertConfigEnvironment_(ss);
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try { updateFormAndDescriptionLocked_(ss); } finally { lock.releaseLock(); }
}

// -----------------------------------------------------------------------------
// Migration and reconciliation
// -----------------------------------------------------------------------------

function runReliabilityMigration() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    ensureCoreSchema_(ss);
    const purchaseResult = backfillPurchases_(ss);
    const eventResult = backfillConsumptionEvents_(ss);
    applySheetSafety_(ss);
    updateFormAndDescriptionLocked_(ss);
    const reconciliation = reconcileReliabilityMigration_(ss);
    const result = {
      purchaseBackfill: purchaseResult,
      eventBackfill: eventResult,
      reconciliation: reconciliation
    };
    console.log(JSON.stringify(result));
    return result;
  } finally {
    lock.releaseLock();
  }
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
  const existing = eventContext_(ss).byEventId;
  const append = [];
  let unresolved = 0;

  rows.forEach((row, index) => {
    const rowNumber = index + 2;
    if (!row.some(cell => cell !== '' && cell != null)) return;
    const eventId = deterministicLegacyEventUuid_(ss.getId(), responses.getName(), rowNumber);
    if (existing[eventId]) return;
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
  const unresolvedRows = Math.max(0, reportSheet.getLastRow() - 1);
  return {
    purchaseCount: Object.keys(purchases.byLegacyId).length,
    responseEventCount: Object.keys(responses).reduce((sum, key) => sum + responses[key].count, 0),
    canonicalEventCount: Object.keys(events).reduce((sum, key) => sum + events[key].count, 0),
    unresolvedRows: unresolvedRows,
    differences: differences
  };
}

// -----------------------------------------------------------------------------
// Schema and sheet operations
// -----------------------------------------------------------------------------

function ensureCoreSchema_(ss) {
  const purchases = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  ensureHeaders_(purchases, CANN.PURCHASE_HEADERS);
  ensureSheet_(ss, CANN.SHEETS.EVENTS, CANN.EVENT_HEADERS);
  ensureSheet_(ss, CANN.SHEETS.LEDGER, CANN.LEDGER_HEADERS);
  ensureSheet_(ss, CANN.SHEETS.MIGRATION_REPORT, CANN.REPORT_HEADERS);
  const config = ensureSheet_(ss, CANN.SHEETS.CONFIG, ['Key', 'Value', 'Description']);
  if (config.getLastRow() < 2) {
    config.getRange(2, 1, 6, 3).setValues([
      ['ENVIRONMENT', environment_(), 'Runtime environment marker'],
      ['TAX_RATE', 0.13, 'Tax rate used for final-cost values'],
      ['TIME_ZONE', CANN.TIME_ZONE, 'Canonical local timezone'],
      ['SCHEMA_VERSION', CANN.SCHEMA_VERSION, 'Spreadsheet schema version'],
      ['MAX_BATCH_SIZE', CANN.MAX_BATCH_SIZE, 'Maximum v2 items per request'],
      ['LOCK_TIMEOUT_MS', CANN.LOCK_TIMEOUT_MS, 'Shared mutation lock timeout']
    ]);
  }
}

function ensureSheet_(ss, name, headers) {
  const sheet = ss.getSheetByName(name) || ss.insertSheet(name);
  ensureHeaders_(sheet, headers);
  sheet.setFrozenRows(1);
  return sheet;
}

function ensureHeaders_(sheet, expected) {
  if (sheet.getMaxColumns() < expected.length) {
    sheet.insertColumnsAfter(sheet.getMaxColumns(), expected.length - sheet.getMaxColumns());
  }
  const current = sheet.getRange(1, 1, 1, expected.length).getValues()[0];
  expected.forEach((header, index) => {
    if (!text_(current[index])) sheet.getRange(1, index + 1).setValue(header);
    else if (text_(current[index]) !== header) {
      throw new Error('SCHEMA_MISMATCH: ' + sheet.getName() + ' column ' + (index + 1) + ' expected ' + header + ' but found ' + current[index]);
    }
  });
}

function applySheetSafety_(ss) {
  const purchases = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const headers = headerMap_(purchases);
  const lastValidationRow = Math.max(purchases.getMaxRows(), 1000);
  const typeValues = readDataRows_(purchases).map(row => text_(value_(row, headers, 'Type'))).filter(Boolean);
  const uniqueTypes = Array.from(new Set(typeValues)).sort();
  if (uniqueTypes.length) {
    const typeRule = SpreadsheetApp.newDataValidation().requireValueInList(uniqueTypes, true).setAllowInvalid(false).build();
    purchases.getRange(2, headers['Type'] + 1, lastValidationRow - 1, 1).setDataValidation(typeRule);
  }
  const statusRule = SpreadsheetApp.newDataValidation().requireValueInList(['0', '1', '2'], true).setAllowInvalid(false).build();
  purchases.getRange(2, headers['Finished'] + 1, lastValidationRow - 1, 1).setDataValidation(statusRule);
  const binaryRule = SpreadsheetApp.newDataValidation().requireValueInList(['0', '1'], true).setAllowInvalid(false).build();
  purchases.getRange(2, headers['Borrowed'] + 1, lastValidationRow - 1, 1).setDataValidation(binaryRule);
  // The existing Purchases table already owns the Post-tax typed checkbox
  // column. Reapplying checkbox validation is rejected for typed columns.

  [purchases, requiredSheet_(ss, CANN.SHEETS.EVENTS), requiredSheet_(ss, CANN.SHEETS.LEDGER), requiredSheet_(ss, CANN.SHEETS.CONFIG), requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT)].forEach(sheet => {
    sheet.getRange(1, 1, 1, sheet.getLastColumn())
      .setBackground('#eeeeee')
      .setFontColor('#000000')
      .setFontWeight('bold');
    sheet.setFrozenRows(1);
  });
  addWarningProtection_(purchases.getRange(1, 1, 1, purchases.getLastColumn()), 'Cannsheet reliability headers');
  addWarningProtection_(purchases.getRange(2, headers['Product ID'] + 1, Math.max(1, purchases.getMaxRows() - 1), 1), 'Cannsheet display identities');
  addWarningProtection_(purchases.getRange(2, headers['Product UUID'] + 1, Math.max(1, purchases.getMaxRows() - 1), 3), 'Cannsheet immutable identities');
}

function addWarningProtection_(range, description) {
  const existing = range.getSheet().getProtections(SpreadsheetApp.ProtectionType.RANGE)
    .find(protection => protection.getDescription() === description);
  const protection = existing || range.protect().setDescription(description);
  protection.setWarningOnly(true);
}

// -----------------------------------------------------------------------------
// Batch write helpers
// -----------------------------------------------------------------------------

function appendPurchaseRows_(sheet, staged, now) {
  if (!staged.length) return;
  const ss = sheet.getParent();
  const taxRate = taxRate_(ss);
  const rows = staged.map(item => {
    const p = item.item;
    const cost = finiteNumberOr_(p.cost, 0);
    const postTax = truthy_(p.postTax);
    return [
      text_(p.date), text_(p.type), text_(p.name), cost,
      finiteNumberOr_(p.thc, 0), finiteNumberOr_(p.grams, 0),
      truthy_(p.borrowed) ? 1 : 0, CANN.STATUS.UNOPENED,
      item.legacyProductId, 0, postTax, postTax ? cost : cost * (1 + taxRate),
      '', item.productUuid, item.actionId, now, ''
    ];
  });
  sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, CANN.PURCHASE_HEADERS.length).setValues(rows);
}

function appendConsumptionRows_(ss, staged, skipCompatibility) {
  if (!staged.length) return;
  if (!skipCompatibility) {
    const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
    const responseHeaders = headerMap_(responses);
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
  appendEventRows_(requiredSheet_(ss, CANN.SHEETS.EVENTS), staged);
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

function applyProductEffects_(sheet, consumptions) {
  if (!consumptions.length || sheet.getLastRow() < 2) return;
  const headers = headerMap_(sheet);
  const rows = readDataRows_(sheet);
  const byId = {};
  rows.forEach((row, index) => {
    const id = text_(value_(row, headers, 'Product ID'));
    if (id) byId[id] = { index: index, row: row };
  });
  consumptions.forEach(item => {
    const target = byId[item.legacyProductId];
    if (!target) return;
    const row = target.row;
    const statusIndex = headers['Finished'];
    const usesIndex = headers['Uses'];
    const latestIndex = headers['Most recent use'];
    const finishedAtIndex = headers['Finished At'];
    const currentStatus = allowedStatusOr_(row[statusIndex], CANN.STATUS.ACTIVE);
    row[usesIndex] = finiteNumberOr_(row[usesIndex], 0) + item.uses;
    row[latestIndex] = item.timestamp;
    if (item.isFinished) {
      row[statusIndex] = CANN.STATUS.FINISHED;
      row[finishedAtIndex] = item.timestamp;
    } else if (currentStatus === CANN.STATUS.UNOPENED) {
      row[statusIndex] = CANN.STATUS.ACTIVE;
    }
  });
  sheet.getRange(2, headers['Finished'] + 1, rows.length, 1).setValues(rows.map(row => [row[headers['Finished']]]));
  sheet.getRange(2, headers['Uses'] + 1, rows.length, 1).setValues(rows.map(row => [row[headers['Uses']]]));
  sheet.getRange(2, headers['Most recent use'] + 1, rows.length, 1).setValues(rows.map(row => [row[headers['Most recent use']]]));
  sheet.getRange(2, headers['Finished At'] + 1, rows.length, 1).setValues(rows.map(row => [row[headers['Finished At']]]));
}

function upsertLedger_(ss, requestId, purchaseCount, consumptionCount, result, durationMs, errorCode) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.LEDGER);
  const rows = readDataRows_(sheet);
  let rowNumber = 0;
  rows.some((row, index) => {
    if (text_(row[0]) === requestId) { rowNumber = index + 2; return true; }
    return false;
  });
  const values = [[requestId, CANN.API_VERSION, new Date(), purchaseCount, consumptionCount, result, durationMs, errorCode || '']];
  if (rowNumber) sheet.getRange(rowNumber, 1, 1, values[0].length).setValues(values);
  else sheet.getRange(sheet.getLastRow() + 1, 1, 1, values[0].length).setValues(values);
}

// -----------------------------------------------------------------------------
// Context, staging, validation, and response helpers
// -----------------------------------------------------------------------------

function productContext_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const headers = headerMap_(sheet);
  requireHeaders_(headers, ['Product ID', 'Product UUID', 'Client Action UUID', 'Type', 'Borrowed']);
  const byLegacyId = {}, byProductUuid = {}, byActionId = {}, rows = readDataRows_(sheet);
  rows.forEach((row, index) => {
    const legacyProductId = text_(value_(row, headers, 'Product ID'));
    if (!legacyProductId) return;
    const product = {
      rowNumber: index + 2,
      legacyProductId: legacyProductId,
      productUuid: text_(value_(row, headers, 'Product UUID')),
      actionId: text_(value_(row, headers, 'Client Action UUID')),
      type: text_(value_(row, headers, 'Type')),
      borrowed: truthy_(value_(row, headers, 'Borrowed'))
    };
    byLegacyId[legacyProductId] = product;
    if (product.productUuid) byProductUuid[product.productUuid] = product;
    if (product.actionId) byActionId[product.actionId] = product;
  });
  return { purchasesSheet: sheet, headers: headers, rows: rows, byLegacyId: byLegacyId, byProductUuid: byProductUuid, byActionId: byActionId };
}

function eventContext_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const headers = headerMap_(sheet);
  const byEventId = {};
  readDataRows_(sheet).forEach((row, index) => {
    const eventId = text_(value_(row, headers, 'Event UUID'));
    if (eventId) byEventId[eventId] = { rowNumber: index + 2 };
  });
  return { sheet: sheet, byEventId: byEventId };
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

function resolveProduct_(item, byLegacyId, byProductUuid) {
  const productUuid = text_(item.productUuid);
  const productId = text_(item.productId);
  if (productUuid && byProductUuid[productUuid]) return byProductUuid[productUuid];
  if (productId && byLegacyId[productId]) return byLegacyId[productId];
  return null;
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

function validateLegacyPurchase_(item, index) {
  if (!item || !text_(item.tempId) || !text_(item.date) || !text_(item.type) || !text_(item.name)) return itemError_('INVALID_ITEM', 'Invalid purchase at index ' + index);
  if (![item.cost, item.thc, item.grams].every(isFiniteNumber_)) return itemError_('INVALID_ITEM', 'Invalid purchase number at index ' + index);
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

function validateBatchSize_(purchases, consumptions) {
  if (!Array.isArray(purchases) || !Array.isArray(consumptions)) return 'purchases and consumptions must be arrays';
  if (purchases.length + consumptions.length > CANN.MAX_BATCH_SIZE) return 'Batch exceeds maximum size';
  return null;
}

function itemError_(code, message) { return { code: code, message: message }; }

// -----------------------------------------------------------------------------
// Reconciliation metrics
// -----------------------------------------------------------------------------

function responseMetrics_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const headers = headerMap_(sheet);
  const result = {};
  readDataRows_(sheet).forEach(row => {
    const id = text_(value_(row, headers, 'Product'));
    if (!id) return;
    const timestamp = value_(row, headers, 'Timestamp');
    const metrics = result[id] || (result[id] = { uses: 0, count: 0, earliest: '', latest: '' });
    metrics.uses += finiteNumberOr_(value_(row, headers, 'Uses'), 0);
    metrics.count++;
    if (timestamp && (!metrics.earliest || timestamp < metrics.earliest)) metrics.earliest = timestamp;
    if (timestamp && (!metrics.latest || timestamp > metrics.latest)) metrics.latest = timestamp;
  });
  return result;
}

function canonicalMetrics_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const headers = headerMap_(sheet);
  const result = {};
  readDataRows_(sheet).forEach(row => {
    const id = text_(value_(row, headers, 'Legacy Product ID'));
    if (!id) return;
    const timestamp = value_(row, headers, 'Timestamp');
    const metrics = result[id] || (result[id] = { uses: 0, count: 0, earliest: '', latest: '' });
    metrics.uses += finiteNumberOr_(value_(row, headers, 'Uses'), 0);
    metrics.count++;
    if (timestamp && (!metrics.earliest || timestamp < metrics.earliest)) metrics.earliest = timestamp;
    if (timestamp && (!metrics.latest || timestamp > metrics.latest)) metrics.latest = timestamp;
  });
  return result;
}

function latestInteractions_(ss) {
  const result = {};
  const canonical = ss.getSheetByName(CANN.SHEETS.EVENTS);
  if (canonical && canonical.getLastRow() >= 2) {
    const headers = headerMap_(canonical);
    readDataRows_(canonical).forEach(row => {
      const id = text_(value_(row, headers, 'Legacy Product ID'));
      const timestamp = value_(row, headers, 'Timestamp');
      const uses = finiteNumber_(value_(row, headers, 'Uses'));
      if (!id || !timestamp || uses == null) return;
      const epoch = new Date(timestamp).getTime();
      if (!result[id] || epoch > result[id].lastLoggedAtEpochMillis) result[id] = { lastLoggedAtEpochMillis: epoch, lastQuantity: uses };
    });
    return result;
  }
  const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const headers = headerMap_(responses);
  readDataRows_(responses).forEach(row => {
    const id = text_(value_(row, headers, 'Product'));
    const timestamp = value_(row, headers, 'Timestamp');
    const uses = finiteNumber_(value_(row, headers, 'Uses'));
    if (!id || !timestamp || uses == null) return;
    const epoch = new Date(timestamp).getTime();
    if (!result[id] || epoch > result[id].lastLoggedAtEpochMillis) result[id] = { lastLoggedAtEpochMillis: epoch, lastQuantity: uses };
  });
  return result;
}

// -----------------------------------------------------------------------------
// General helpers
// -----------------------------------------------------------------------------

function requiredSheet_(ss, name) {
  const sheet = ss.getSheetByName(name);
  if (!sheet) throw new Error('SCHEMA_MISMATCH: Missing sheet ' + name);
  return sheet;
}

function headerMap_(sheet) {
  const lastColumn = Math.max(1, sheet.getLastColumn());
  const headers = sheet.getRange(1, 1, 1, lastColumn).getValues()[0];
  const result = {};
  headers.forEach((header, index) => {
    const name = text_(header);
    if (name && result[name] === undefined) result[name] = index;
  });
  return result;
}

function requireHeaders_(headers, required) {
  const missing = required.filter(name => headers[name] === undefined);
  if (missing.length) throw new Error('SCHEMA_MISMATCH: Missing headers ' + missing.join(', '));
}

function readDataRows_(sheet) {
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return [];
  return sheet.getRange(2, 1, lastRow - 1, sheet.getLastColumn()).getValues()
    .filter(row => row.some(cell => cell !== '' && cell != null));
}

function value_(row, headers, name) {
  const index = headers[name];
  return index === undefined ? '' : row[index];
}

function recordMigrationIssue_(ss, type, sourceSheet, sourceRow, productId, detail) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT);
  sheet.getRange(sheet.getLastRow() + 1, 1, 1, CANN.REPORT_HEADERS.length)
    .setValues([[type, sourceSheet, sourceRow, productId, detail, new Date()]]);
}

function configValue_(ss, key, fallback) {
  const sheet = ss.getSheetByName(CANN.SHEETS.CONFIG);
  if (!sheet || sheet.getLastRow() < 2) return fallback;
  const rows = sheet.getRange(2, 1, sheet.getLastRow() - 1, 2).getValues();
  const match = rows.find(row => text_(row[0]) === key);
  return match && match[1] !== '' ? match[1] : fallback;
}

function requiredScriptProperty_(name) {
  const value = text_(PropertiesService.getScriptProperties().getProperty(name));
  if (!value) throw new Error('CONFIGURATION_ERROR: missing Script Property ' + name);
  return value;
}

function environment_() {
  const value = requiredScriptProperty_('ENVIRONMENT');
  if (value !== 'PRODUCTION' && value !== 'SANDBOX') {
    throw new Error('CONFIGURATION_ERROR: ENVIRONMENT must be PRODUCTION or SANDBOX');
  }
  return value;
}

function spreadsheet_() {
  return SpreadsheetApp.openById(requiredScriptProperty_('SPREADSHEET_ID'));
}

function assertConfiguredSpreadsheet_(ss) {
  if (!ss || ss.getId() !== requiredScriptProperty_('SPREADSHEET_ID')) {
    throw new Error('CONFIGURATION_ERROR: spreadsheet does not match SPREADSHEET_ID');
  }
}

function assertConfigEnvironment_(ss) {
  assertConfiguredSpreadsheet_(ss);
  const marker = text_(configValue_(ss, 'ENVIRONMENT', ''));
  if (marker !== environment_()) throw new Error('CONFIGURATION_ERROR: Config ENVIRONMENT mismatch');
}

function validateRequestEnvironment_(serverEnvironment, requestEnvironment) {
  const supplied = text_(requestEnvironment);
  if (supplied && supplied !== serverEnvironment) return 'Client and server environments do not match';
  if (!supplied && serverEnvironment === 'SANDBOX') return 'Sandbox requests must include environment SANDBOX';
  return '';
}

function taxRate_(ss) { return finiteNumberOr_(configValue_(ss, 'TAX_RATE', 0.13), 0.13); }
function formatDate_(date) { return Utilities.formatDate(new Date(date), CANN.TIME_ZONE, 'yyyy-MM-dd'); }
function formatTime_(date) { return Utilities.formatDate(new Date(date), CANN.TIME_ZONE, 'HH:mm:ss'); }

function parseClientDateTime_(dateText, timeText) {
  const combined = text_(dateText) + (text_(timeText) ? 'T' + text_(timeText) : 'T00:00:00');
  const parsed = new Date(combined);
  return isNaN(parsed.getTime()) ? new Date() : parsed;
}

function deterministicLegacyEventUuid_(spreadsheetId, sheetName, rowNumber) {
  const bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, spreadsheetId + ':' + sheetName + ':' + rowNumber, Utilities.Charset.UTF_8);
  let hex = bytes.map(value => ('0' + ((value + 256) % 256).toString(16)).slice(-2)).join('').slice(0, 32);
  hex = hex.slice(0, 12) + '5' + hex.slice(13, 16) + '8' + hex.slice(17);
  return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
}

function requestFailure_(code, message, environment) {
  return jsonOutput_({ success: false, message: message, errorCode: code, productIdMap: {}, environment: environment });
}

function jsonOutput_(value) {
  return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON);
}

function conciseError_(error) { return error && error.message ? String(error.message).slice(0, 500) : String(error).slice(0, 500); }
function text_(value) { return value == null ? '' : String(value).trim(); }
function arrayOrEmpty_(value) { return value == null ? [] : value; }
function finiteNumber_(value) { const number = Number(value); return Number.isFinite(number) ? number : null; }
function finiteNumberOr_(value, fallback) { const number = finiteNumber_(value); return number == null ? fallback : number; }
function isFiniteNumber_(value) { return finiteNumber_(value) != null; }
function allowedStatusOr_(value, fallback) { const status = Number(value); return [0, 1, 2].indexOf(status) >= 0 ? status : fallback; }
function truthy_(value) { return value === true || value === 1 || text_(value).toLowerCase() === 'true' || text_(value).toLowerCase() === 'yes'; }
function dateOrNow_(value) { const date = new Date(value); return isNaN(date.getTime()) ? new Date() : date; }
function dateOrNull_(value) { const date = new Date(value); return isNaN(date.getTime()) ? null : date; }
function isUuid_(value) { return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value); }
function firstDuplicate_(values) { const seen = {}; for (let i = 0; i < values.length; i++) { if (seen[values[i]]) return values[i]; seen[values[i]] = true; } return ''; }
