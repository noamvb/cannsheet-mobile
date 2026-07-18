'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const {
  createAppsScriptRuntime,
  deterministicUuid,
  makeSheetRows,
} = require('./fake_apps_script_runtime');

const source = fs.readFileSync('backend_additions.gs', 'utf8');

const PURCHASE_HEADERS = [
  'Date', 'Type', 'Product name', 'Pre-tax cost', 'THC%', 'Grams',
  'Borrowed', 'Finished', 'Product ID', 'Uses', 'Post-tax', 'Final cost',
  'Most recent use', 'Product UUID', 'Client Action UUID', 'Created At',
  'Finished At', 'Last quantity',
];
const RESPONSE_HEADERS = [
  'Timestamp', 'Product', 'Uses', 'Date', 'Time', 'Weight code',
  'Mark as Finished?', 'Cannsheet Event UUID', 'Cannsheet Request UUID',
];
const EVENT_HEADERS = [
  'Event UUID', 'Timestamp', 'Local Date', 'Local Time', 'Product UUID',
  'Legacy Product ID', 'Uses', 'Weight Code', 'Finished', 'Source',
  'Request UUID', 'Legacy Source Sheet', 'Legacy Source Row',
];
const LEDGER_HEADERS = [
  'Request UUID', 'API Version', 'Received At', 'Purchase Count',
  'Consumption Count', 'Result', 'Duration Ms', 'Error Code',
];
const JOURNAL_HEADERS = [
  'Apply UUID', 'Kind', 'API Version', 'Request UUID', 'State',
  'Core Committed At', 'Completed At', 'Finalization JSON', 'Response JSON',
];
const CONFIG_HEADERS = ['Key', 'Value', 'Description'];
const REPORT_HEADERS = [
  'Type', 'Source Sheet', 'Source Row', 'Product ID', 'Detail', 'Recorded At',
];
const FAULTS = {
  COMPATIBILITY: 'AFTER_COMPATIBILITY',
  CANONICAL: 'AFTER_CANONICAL',
  PRODUCT_EFFECTS: 'AFTER_PRODUCT_EFFECTS',
  INTERACTION_SUMMARY: 'AFTER_INTERACTION_SUMMARY',
  CORE_COMMITTED: 'AFTER_CORE_COMMIT',
  LEDGER: 'BEFORE_FINAL_LEDGER',
  POST_COMPLETE: 'AFTER_COMPLETE',
};

const BASE_PRODUCT_UUID = deterministicUuid(101);
const BASE_ACTION_UUID = deterministicUuid(201);
const BASE_RECENT = new Date('2025-05-01T12:00:00-04:00');

function dateFormats(columns, firstRow = 2, lastRow = 30) {
  const result = {};
  for (let row = firstRow; row <= lastRow; row += 1) {
    columns.forEach(column => {
      result[`${row}:${column}`] = {
        type: 'DATE_TIME',
        pattern: 'yyyy-mm-dd hh:mm:ss',
      };
    });
  }
  return result;
}

function configRows() {
  return [
    CONFIG_HEADERS,
    ['ENVIRONMENT', 'SANDBOX', 'Runtime environment marker'],
    ['TAX_RATE', 0.13, 'Tax rate'],
    ['TIME_ZONE', 'America/New_York', 'Canonical local timezone'],
    ['SCHEMA_VERSION', 2, 'Spreadsheet schema version'],
    ['INTERACTION_SUMMARY_VERSION', 1, 'Purchases interaction-summary version'],
    ['RECOVERABLE_SYNC_APPLY_VERSION', 1, 'Recoverable multi-sheet apply version'],
    ['PENDING_APPLY_KEY', '', 'Apply UUID awaiting finalization'],
    ['MAX_BATCH_SIZE', 100, 'Maximum batch size'],
    ['LOCK_TIMEOUT_MS', 30000, 'Lock timeout'],
  ];
}

function basePurchase(options = {}) {
  const recent = Object.prototype.hasOwnProperty.call(options, 'initialRecent')
    ? options.initialRecent
    : BASE_RECENT;
  const lastQuantity = Object.prototype.hasOwnProperty.call(options, 'initialQuantity')
    ? options.initialQuantity
    : 0.5;
  return {
    Date: '2025-01-01',
    Type: 'P',
    'Product name': 'Recovery product',
    'Pre-tax cost': 10,
    'THC%': 20,
    Grams: 3.5,
    Borrowed: 0,
    Finished: 0,
    'Product ID': '*P1',
    Uses: options.initialUses ?? 2,
    'Post-tax': false,
    'Final cost': 11.3,
    'Most recent use': recent == null ? '' : recent,
    'Product UUID': BASE_PRODUCT_UUID,
    'Client Action UUID': BASE_ACTION_UUID,
    'Created At': new Date('2025-01-01T09:00:00-05:00'),
    'Finished At': '',
    'Last quantity': lastQuantity == null ? '' : lastQuantity,
  };
}

function compatibilityRow(event) {
  return [
    event.timestamp,
    event.productId || '*P1',
    event.uses,
    event.localDate || '2025-05-01',
    event.localTime || '12:00:00',
    event.weightCode || '',
    event.isFinished ? 'Yes' : '',
    event.eventId || '',
    event.requestId || '',
  ];
}

function canonicalRow(event, responseRowNumber) {
  return [
    event.eventId,
    event.timestamp,
    event.localDate || '2025-05-01',
    event.localTime || '12:00:00',
    event.productUuid || BASE_PRODUCT_UUID,
    event.productId || '*P1',
    event.uses,
    event.weightCode || '',
    !!event.isFinished,
    event.source || 'ANDROID_V2',
    event.requestId || '',
    'Form Responses 1',
    responseRowNumber,
  ];
}

function buildRuntime(options = {}) {
  const existingEvents = options.existingEvents || [];
  const responseRows = existingEvents.map(compatibilityRow);
  if (options.formOrphan) responseRows.push(compatibilityRow(options.formOrphan));
  const eventRows = existingEvents.map((event, index) => canonicalRow(event, index + 2));
  const purchase = basePurchase(options);
  const runtime = createAppsScriptRuntime({
    environment: 'SANDBOX',
    spreadsheetId: 'recovery-sheet',
    formId: 'recovery-form',
    timeZone: 'America/New_York',
    now: options.now || '2026-07-14T16:00:00-04:00',
    sheets: {
      Purchases: {
        rows: makeSheetRows(PURCHASE_HEADERS, [purchase]),
        maxColumns: PURCHASE_HEADERS.length,
        numberFormats: dateFormats([13, 16, 17]),
      },
      'Form Responses 1': {
        rows: [RESPONSE_HEADERS].concat(responseRows),
        maxColumns: RESPONSE_HEADERS.length,
        numberFormats: dateFormats([1]),
      },
      ConsumptionEvents: {
        rows: [EVENT_HEADERS].concat(eventRows),
        maxColumns: EVENT_HEADERS.length,
        numberFormats: dateFormats([2]),
      },
      SyncLedger: {
        rows: [LEDGER_HEADERS],
        maxColumns: LEDGER_HEADERS.length,
        numberFormats: dateFormats([3]),
      },
      SyncApplyJournal: {
        rows: [JOURNAL_HEADERS],
        maxColumns: JOURNAL_HEADERS.length,
        numberFormats: dateFormats([6, 7]),
      },
      Config: {
        rows: configRows(),
        maxColumns: CONFIG_HEADERS.length,
      },
      MigrationReport: {
        rows: [REPORT_HEADERS],
        maxColumns: REPORT_HEADERS.length,
        numberFormats: dateFormats([6]),
      },
    },
    form: {
      id: 'recovery-form',
      destinationId: 'recovery-sheet',
      items: [{
        title: 'Product',
        type: 'MULTIPLE_CHOICE',
        choices: ['*P1'],
      }],
    },
  });
  runtime.loadSource(source, { filename: 'backend_additions.gs' });
  runtime.resetAudit();
  return runtime;
}

function post(runtime, payload) {
  return runtime.parseTextOutput(runtime.context.doPost({
    postData: { contents: JSON.stringify(payload) },
  }));
}

function payload(ordinal, overrides = {}) {
  const requestId = overrides.requestId || deterministicUuid(50000 + ordinal);
  const eventId = overrides.eventId || deterministicUuid(60000 + ordinal);
  const consumption = Object.assign({
    eventId,
    date: '2025-05-04',
    time: '12:00:00',
    productId: '*P1',
    productUuid: BASE_PRODUCT_UUID,
    uses: 1,
    isFinished: false,
    weightCode: 'recovery-test',
  }, overrides.consumption || {});
  return {
    apiVersion: 2,
    requestId,
    environment: 'SANDBOX',
    purchases: overrides.purchases || [],
    consumptions: overrides.consumptions || [consumption],
  };
}

function dataEntries(runtime, sheetName) {
  return runtime.peekSheet(sheetName).snapshot().rows
    .slice(1)
    .map((row, index) => ({ row, rowNumber: index + 2 }))
    .filter(entry => entry.row.some(value => value !== '' && value != null));
}

function headerMap(runtime, sheetName) {
  const headers = runtime.peekSheet(sheetName).snapshot().rows[0];
  return Object.fromEntries(headers.map((header, index) => [header, index]));
}

function cell(runtime, sheetName, entry, header) {
  return entry.row[headerMap(runtime, sheetName)[header]];
}

function entryBy(runtime, sheetName, header, expected) {
  const result = dataEntries(runtime, sheetName)
    .find(entry => String(cell(runtime, sheetName, entry, header)) === String(expected));
  assert.ok(result, `Missing ${sheetName} row where ${header}=${expected}`);
  return result;
}

function productEntry(runtime, productId = '*P1') {
  return entryBy(runtime, 'Purchases', 'Product ID', productId);
}

function productState(runtime, productId = '*P1') {
  const entry = productEntry(runtime, productId);
  const recent = cell(runtime, 'Purchases', entry, 'Most recent use');
  const finishedAt = cell(runtime, 'Purchases', entry, 'Finished At');
  return {
    status: Number(cell(runtime, 'Purchases', entry, 'Finished')),
    uses: Number(cell(runtime, 'Purchases', entry, 'Uses')),
    recentMillis: recent === '' || recent == null ? null : new Date(recent).getTime(),
    lastQuantity: cell(runtime, 'Purchases', entry, 'Last quantity'),
    finishedMillis: finishedAt === '' || finishedAt == null
      ? null
      : new Date(finishedAt).getTime(),
  };
}

function configValue(runtime, key) {
  const entry = entryBy(runtime, 'Config', 'Key', key);
  return cell(runtime, 'Config', entry, 'Value');
}

function durableSnapshot(runtime) {
  return JSON.stringify({
    spreadsheet: runtime.spreadsheet.snapshot(),
    form: runtime.form.snapshot(),
  });
}

function assertCounts(runtime, expected) {
  assert.equal(dataEntries(runtime, 'Form Responses 1').length, expected.responses);
  assert.equal(dataEntries(runtime, 'ConsumptionEvents').length, expected.events);
  assert.equal(dataEntries(runtime, 'SyncLedger').length, expected.ledgers);
  assert.equal(dataEntries(runtime, 'SyncApplyJournal').length, expected.journals);
}

function eventMillis(requestPayload) {
  const item = requestPayload.consumptions.find(consumption =>
    consumption.eventId === requestPayload.consumptions.at(-1).eventId);
  return new Date(`${item.date}T${item.time || '00:00:00'}`).getTime();
}

function assertNoCoreMutation(runtime) {
  assertCounts(runtime, {
    responses: 0,
    events: 0,
    ledgers: 0,
    journals: 0,
  });
  assert.equal(configValue(runtime, 'PENDING_APPLY_KEY'), '');
  assert.deepEqual(productState(runtime), {
    status: 0,
    uses: 2,
    recentMillis: BASE_RECENT.getTime(),
    lastQuantity: 0.5,
    finishedMillis: null,
  });
}

function assertInternalFailure(response) {
  assert.equal(response.success, false);
  assert.equal(response.errorCode, 'INTERNAL_ERROR');
}

function assertEventCommitted(runtime, requestPayload, options = {}) {
  const event = requestPayload.consumptions.at(-1);
  const expectedUses = options.expectedUses ?? 3;
  const expectedLastQuantity = options.expectedLastQuantity ?? event.uses;
  const expectedRecentMillis = options.expectedRecentMillis ?? eventMillis(requestPayload);
  assertCounts(runtime, {
    responses: options.responseCount ?? 1,
    events: options.eventCount ?? 1,
    ledgers: options.ledgerCount ?? 1,
    journals: options.journalCount ?? 1,
  });
  assert.equal(configValue(runtime, 'PENDING_APPLY_KEY'), '');

  const responseEntry = entryBy(
    runtime,
    'Form Responses 1',
    'Cannsheet Event UUID',
    event.eventId,
  );
  assert.equal(
    cell(runtime, 'Form Responses 1', responseEntry, 'Cannsheet Request UUID'),
    requestPayload.requestId,
  );
  const canonicalEntry = entryBy(runtime, 'ConsumptionEvents', 'Event UUID', event.eventId);
  assert.equal(
    cell(runtime, 'ConsumptionEvents', canonicalEntry, 'Legacy Source Sheet'),
    'Form Responses 1',
  );
  assert.equal(
    Number(cell(runtime, 'ConsumptionEvents', canonicalEntry, 'Legacy Source Row')),
    responseEntry.rowNumber,
  );
  const state = productState(runtime, options.productId || '*P1');
  assert.equal(state.uses, expectedUses);
  assert.equal(state.recentMillis, expectedRecentMillis);
  assert.equal(state.lastQuantity, expectedLastQuantity);

  dataEntries(runtime, 'SyncApplyJournal').forEach(entry => {
    assert.equal(cell(runtime, 'SyncApplyJournal', entry, 'State'), 'COMPLETE');
  });
  if ((options.ledgerCount ?? 1) > 0) {
    const ledger = entryBy(runtime, 'SyncLedger', 'Request UUID', requestPayload.requestId);
    assert.equal(cell(runtime, 'SyncLedger', ledger, 'Request UUID'), requestPayload.requestId);
  }
}

function assertCorePending(runtime, requestPayload) {
  const event = requestPayload.consumptions[0];
  assertCounts(runtime, {
    responses: 1,
    events: 1,
    ledgers: 0,
    journals: 1,
  });
  const journal = dataEntries(runtime, 'SyncApplyJournal')[0];
  const applyId = cell(runtime, 'SyncApplyJournal', journal, 'Apply UUID');
  assert.equal(cell(runtime, 'SyncApplyJournal', journal, 'State'), 'CORE_COMMITTED');
  assert.equal(configValue(runtime, 'PENDING_APPLY_KEY'), applyId);

  const responseEntry = entryBy(
    runtime,
    'Form Responses 1',
    'Cannsheet Event UUID',
    event.eventId,
  );
  assert.equal(
    cell(runtime, 'Form Responses 1', responseEntry, 'Cannsheet Request UUID'),
    requestPayload.requestId,
  );
  const canonical = entryBy(runtime, 'ConsumptionEvents', 'Event UUID', event.eventId);
  assert.equal(cell(runtime, 'ConsumptionEvents', canonical, 'Legacy Source Sheet') || '', '');
  assert.equal(cell(runtime, 'ConsumptionEvents', canonical, 'Legacy Source Row') || '', '');
  assert.deepEqual(productState(runtime), {
    status: 0,
    uses: 3,
    recentMillis: eventMillis(requestPayload),
    lastQuantity: 1,
    finishedMillis: null,
  });
  return applyId;
}

function assertReconciliationClean(runtime) {
  const result = runtime.context.reconcileRecoverableSyncApply();
  assert.equal(result.blockingDifferences.length, 0);
  assert.equal(result.differences.length, 0);
}

// Faults inserted inside the atomic core transaction must roll back every
// earlier logical stage. The same request then commits once, with one use.
[
  FAULTS.COMPATIBILITY,
  FAULTS.CANONICAL,
  FAULTS.PRODUCT_EFFECTS,
  FAULTS.INTERACTION_SUMMARY,
].forEach((stage, index) => {
  const runtime = buildRuntime();
  const requestPayload = payload(10 + index);
  runtime.context.setSandboxSyncApplyFault(stage);
  runtime.resetAudit();
  const before = durableSnapshot(runtime);
  const failed = post(runtime, requestPayload);
  assertInternalFailure(failed);
  assert.equal(durableSnapshot(runtime), before, `${stage} must roll back the whole core batch`);
  assertNoCoreMutation(runtime);
  assert.equal(runtime.audit.batches.length, 1);
  assert.equal(runtime.audit.batches[0].committed, false);

  const retry = post(runtime, requestPayload);
  assert.deepEqual(
    retry.acknowledgedConsumptions,
    [{ eventId: requestPayload.consumptions[0].eventId, status: 'committed' }],
  );
  assertEventCommitted(runtime, requestPayload);
  assertReconciliationClean(runtime);
});

function exercisePendingFault(stage, ordinal) {
  const runtime = buildRuntime();
  const requestPayload = payload(ordinal);
  runtime.context.setSandboxSyncApplyFault(stage);
  runtime.resetAudit();
  const failed = post(runtime, requestPayload);
  assertInternalFailure(failed);
  const applyId = assertCorePending(runtime, requestPayload);
  if (stage === FAULTS.CORE_COMMITTED) {
    assert.equal(runtime.audit.batches.filter(batch => batch.committed).length, 1);
    assert.equal(runtime.audit.batches.filter(batch => !batch.committed).length, 0);
  } else {
    assert.equal(runtime.audit.batches.filter(batch => batch.committed).length, 1);
    assert.equal(runtime.audit.batches.filter(batch => !batch.committed).length, 1);
  }

  const repaired = runtime.context.repairRecoverableSyncApply();
  assert.equal(repaired.pending.repaired, true);
  assert.equal(repaired.pending.applyId, applyId);
  assert.equal(repaired.pending.previousState, 'CORE_COMMITTED');
  assert.equal(repaired.orphanForms.repairedRows, 0);
  assertEventCommitted(runtime, requestPayload);

  runtime.resetAudit();
  const retry = post(runtime, requestPayload);
  assert.deepEqual(
    retry.acknowledgedConsumptions,
    [{ eventId: requestPayload.consumptions[0].eventId, status: 'duplicate' }],
  );
  assertEventCommitted(runtime, requestPayload, { journalCount: 1 });
  assert.equal(
    runtime.audit.batches.length,
    0,
    'a repaired duplicate must not create another journal transaction',
  );

  const beforeSecondRepair = durableSnapshot(runtime);
  const secondRepair = runtime.context.repairRecoverableSyncApply();
  assert.equal(secondRepair.pending.repaired, false);
  assert.equal(secondRepair.orphanForms.repairedRows, 0);
  assert.equal(durableSnapshot(runtime), beforeSecondRepair);
  assertReconciliationClean(runtime);
}

exercisePendingFault(FAULTS.CORE_COMMITTED, 30);
exercisePendingFault(FAULTS.LEDGER, 31);

// A stop after the final atomic transaction is a lost-response boundary:
// durable state is already complete, and retry remains a duplicate.
{
  const runtime = buildRuntime();
  const requestPayload = payload(40);
  runtime.context.setSandboxSyncApplyFault(FAULTS.POST_COMPLETE);
  const failed = post(runtime, requestPayload);
  assertInternalFailure(failed);
  assertEventCommitted(runtime, requestPayload);

  const retry = post(runtime, requestPayload);
  assert.deepEqual(
    retry.acknowledgedConsumptions,
    [{ eventId: requestPayload.consumptions[0].eventId, status: 'duplicate' }],
  );
  assertEventCommitted(runtime, requestPayload, { journalCount: 1 });
  const repaired = runtime.context.repairRecoverableSyncApply();
  assert.equal(repaired.pending.repaired, false);
  assert.equal(repaired.orphanForms.repairedRows, 0);
  assertReconciliationClean(runtime);
}

// Requests with no newly accepted purchase or event have only one durable
// mutation: their idempotent ledger row. They must skip the recovery journal
// and both Advanced Sheets batches while preserving acknowledgements.
{
  const runtime = buildRuntime();
  const emptyPayload = payload(41, { consumptions: [] });
  const emptyResponse = post(runtime, emptyPayload);
  assert.equal(emptyResponse.success, true);
  assert.equal(emptyResponse.allAccepted, true);
  assertCounts(runtime, {
    responses: 0,
    events: 0,
    ledgers: 1,
    journals: 0,
  });
  assert.equal(runtime.audit.batches.length, 0);
  assert.equal(configValue(runtime, 'PENDING_APPLY_KEY'), '');
  assert.equal(typeof emptyResponse.serverTimings.ledgerUpdate, 'number');
  assert.equal(
    Object.prototype.hasOwnProperty.call(
      emptyResponse.serverTimings,
      'recoverableCoreBatch',
    ),
    false,
  );
  assert.equal(
    Object.prototype.hasOwnProperty.call(
      emptyResponse.serverTimings,
      'recoverableFinalBatch',
    ),
    false,
  );
  assert.deepEqual(productState(runtime), {
    status: 0,
    uses: 2,
    recentMillis: BASE_RECENT.getTime(),
    lastQuantity: 0.5,
    finishedMillis: null,
  });
}

{
  const runtime = buildRuntime();
  const requestPayload = payload(42);
  const committed = post(runtime, requestPayload);
  assert.equal(committed.acknowledgedConsumptions[0].status, 'committed');
  runtime.resetAudit();

  const duplicate = post(runtime, requestPayload);
  assert.deepEqual(
    duplicate.acknowledgedConsumptions,
    [{ eventId: requestPayload.consumptions[0].eventId, status: 'duplicate' }],
  );
  assertEventCommitted(runtime, requestPayload, { journalCount: 1 });
  assert.equal(runtime.audit.batches.length, 0);
  assert.equal(typeof duplicate.serverTimings.ledgerUpdate, 'number');
  assert.equal(
    Object.prototype.hasOwnProperty.call(
      duplicate.serverTimings,
      'recoverableCoreBatch',
    ),
    false,
  );
  assert.equal(
    Object.prototype.hasOwnProperty.call(
      duplicate.serverTimings,
      'recoverableFinalBatch',
    ),
    false,
  );
  assert.equal(
    runtime.audit.writes.filter(entry =>
      ['Purchases', 'Form Responses 1', 'ConsumptionEvents', 'SyncApplyJournal']
        .includes(entry.sheet)
    ).length,
    0,
  );
}

{
  const runtime = buildRuntime();
  const rejectedPayload = payload(43, {
    consumption: {
      productId: '*UNKNOWN',
      productUuid: deterministicUuid(999999),
    },
  });
  const rejected = post(runtime, rejectedPayload);
  assert.equal(rejected.success, true);
  assert.equal(rejected.allAccepted, false);
  assert.equal(rejected.acknowledgedConsumptions.length, 0);
  assert.equal(rejected.rejectedConsumptions[0].errorCode, 'UNKNOWN_PRODUCT');
  assertCounts(runtime, {
    responses: 0,
    events: 0,
    ledgers: 1,
    journals: 0,
  });
  assert.equal(runtime.audit.batches.length, 0);
  assert.equal(configValue(runtime, 'PENDING_APPLY_KEY'), '');
}

// The no-core shortcut must never jump ahead of a predecessor whose atomic
// core is durable but whose ledger/lineage finalization is still pending.
{
  const runtime = buildRuntime();
  const pendingPayload = payload(44);
  runtime.context.setSandboxSyncApplyFault(FAULTS.CORE_COMMITTED);
  assertInternalFailure(post(runtime, pendingPayload));
  assertCorePending(runtime, pendingPayload);

  runtime.resetAudit();
  const emptyPayload = payload(45, { consumptions: [] });
  const emptyResponse = post(runtime, emptyPayload);
  assert.equal(emptyResponse.success, true);
  assert.equal(emptyResponse.allAccepted, true);
  assertCounts(runtime, {
    responses: 1,
    events: 1,
    ledgers: 2,
    journals: 1,
  });
  assert.equal(
    runtime.audit.batches.length,
    1,
    'only the predecessor finalization batch should run',
  );
  assert.equal(configValue(runtime, 'PENDING_APPLY_KEY'), '');
  assertEventCommitted(runtime, pendingPayload, {
    ledgerCount: 2,
    journalCount: 1,
  });

  runtime.resetAudit();
  const emptyRetry = post(runtime, emptyPayload);
  assert.equal(emptyRetry.success, true);
  assertCounts(runtime, {
    responses: 1,
    events: 1,
    ledgers: 2,
    journals: 1,
  });
  assert.equal(runtime.audit.batches.length, 0);
  const emptyLedger = entryBy(
    runtime,
    'SyncLedger',
    'Request UUID',
    emptyPayload.requestId,
  );
  assert.equal(cell(runtime, 'SyncLedger', emptyLedger, 'Purchase Count'), 0);
  assert.equal(cell(runtime, 'SyncLedger', emptyLedger, 'Consumption Count'), 0);
  assert.equal(cell(runtime, 'SyncLedger', emptyLedger, 'Result'), 'ACCEPTED');
}

// A Google Form row is written by Forms before the trigger starts. If the
// identity update is rolled back, the explicit scanner must find that orphan,
// canonicalize it once, and make later trigger delivery a no-op.
{
  const formTimestamp = new Date('2025-05-06T11:45:00-04:00');
  const formOrphan = {
    timestamp: formTimestamp,
    productId: '*P1',
    uses: 0.75,
    localDate: '2025-05-06',
    localTime: '11:45:00',
    weightCode: 'form-test',
    isFinished: false,
    eventId: '',
    requestId: '',
  };
  const runtime = buildRuntime({ formOrphan });
  const responseSheet = runtime.peekSheet('Form Responses 1');
  const formRange = responseSheet.getRange(2, 1, 1, responseSheet.getLastColumn());
  runtime.context.setSandboxSyncApplyFault(FAULTS.COMPATIBILITY);
  const before = durableSnapshot(runtime);
  assert.throws(
    () => runtime.context.onFormSubmit({ range: formRange }),
    /Advanced Sheets sheetId must be a non-negative integer/,
  );
  assert.equal(durableSnapshot(runtime), before);
  const orphanBefore = dataEntries(runtime, 'Form Responses 1')[0];
  assert.equal(cell(runtime, 'Form Responses 1', orphanBefore, 'Cannsheet Event UUID') || '', '');
  assertCounts(runtime, {
    responses: 1,
    events: 0,
    ledgers: 0,
    journals: 0,
  });
  assert.equal(productState(runtime).uses, 2);

  const repaired = runtime.context.repairRecoverableSyncApply();
  assert.equal(repaired.pending.repaired, false);
  assert.equal(repaired.orphanForms.repairedRows, 1);
  assert.equal(repaired.orphanForms.issues.length, 0);
  const expectedEventId = runtime.context.deterministicLegacyEventUuid_(
    'recovery-sheet',
    'Form Responses 1',
    2,
  );
  const responseEntry = entryBy(
    runtime,
    'Form Responses 1',
    'Cannsheet Event UUID',
    expectedEventId,
  );
  assert.equal(cell(runtime, 'Form Responses 1', responseEntry, 'Cannsheet Request UUID') || '', '');
  const canonical = entryBy(runtime, 'ConsumptionEvents', 'Event UUID', expectedEventId);
  assert.equal(cell(runtime, 'ConsumptionEvents', canonical, 'Source'), 'FORM_RECOVERY');
  assert.equal(cell(runtime, 'ConsumptionEvents', canonical, 'Legacy Source Sheet'), 'Form Responses 1');
  assert.equal(Number(cell(runtime, 'ConsumptionEvents', canonical, 'Legacy Source Row')), 2);
  assertCounts(runtime, {
    responses: 1,
    events: 1,
    ledgers: 0,
    journals: 1,
  });
  assert.deepEqual(productState(runtime), {
    status: 0,
    uses: 2.75,
    recentMillis: formTimestamp.getTime(),
    lastQuantity: 0.75,
    finishedMillis: null,
  });
  assert.equal(configValue(runtime, 'PENDING_APPLY_KEY'), '');
  assert.equal(
    cell(runtime, 'SyncApplyJournal', dataEntries(runtime, 'SyncApplyJournal')[0], 'State'),
    'COMPLETE',
  );

  const beforeSecondRepair = durableSnapshot(runtime);
  const secondRepair = runtime.context.repairRecoverableSyncApply();
  assert.equal(secondRepair.pending.repaired, false);
  assert.equal(secondRepair.orphanForms.repairedRows, 0);
  assert.equal(durableSnapshot(runtime), beforeSecondRepair);
  runtime.context.onFormSubmit({ range: formRange });
  assert.equal(durableSnapshot(runtime), beforeSecondRepair);
  assertReconciliationClean(runtime);
}

// Request UUID is a ledger identity, not an immutable transaction identity.
// The same Request UUID can retain event A and later add event B.
{
  const runtime = buildRuntime();
  const requestId = deterministicUuid(70001);
  const eventA = deterministicUuid(71001);
  const eventB = deterministicUuid(71002);
  const first = payload(50, {
    requestId,
    eventId: eventA,
    consumption: {
      date: '2025-05-07',
      time: '10:00:00',
      uses: 1,
    },
  });
  const firstResponse = post(runtime, first);
  assert.equal(firstResponse.acknowledgedConsumptions[0].status, 'committed');

  const second = payload(51, {
    requestId,
    consumptions: [
      first.consumptions[0],
      {
        eventId: eventB,
        date: '2025-05-08',
        time: '10:00:00',
        productId: '*P1',
        productUuid: BASE_PRODUCT_UUID,
        uses: 2,
        isFinished: false,
      },
    ],
  });
  const secondResponse = post(runtime, second);
  assert.deepEqual(secondResponse.acknowledgedConsumptions, [
    { eventId: eventA, status: 'duplicate' },
    { eventId: eventB, status: 'committed' },
  ]);
  assertCounts(runtime, {
    responses: 2,
    events: 2,
    ledgers: 1,
    journals: 2,
  });
  assert.equal(productState(runtime).uses, 5);
  assert.equal(productState(runtime).lastQuantity, 2);
  assert.equal(
    productState(runtime).recentMillis,
    new Date('2025-05-08T10:00:00').getTime(),
  );
  [eventA, eventB].forEach(eventId => {
    const responseEntry = entryBy(
      runtime,
      'Form Responses 1',
      'Cannsheet Event UUID',
      eventId,
    );
    assert.equal(cell(runtime, 'Form Responses 1', responseEntry, 'Cannsheet Request UUID'), requestId);
    const canonical = entryBy(runtime, 'ConsumptionEvents', 'Event UUID', eventId);
    assert.equal(
      Number(cell(runtime, 'ConsumptionEvents', canonical, 'Legacy Source Row')),
      responseEntry.rowNumber,
    );
  });
  assert.equal(dataEntries(runtime, 'SyncLedger').length, 1);
  assertReconciliationClean(runtime);
}

// A duplicate purchase in a reused request still resolves its temporary ID for
// a newly queued consumption. It must not append the purchase twice.
{
  const runtime = buildRuntime({
    initialRecent: null,
    initialQuantity: null,
  });
  const requestId = deterministicUuid(72001);
  const actionId = deterministicUuid(72002);
  const eventId = deterministicUuid(72003);
  const purchaseItem = {
    actionId,
    tempId: 'temp-reused',
    date: '2025-06-01',
    type: 'P',
    name: 'Reused request product',
    cost: 12,
    thc: 18,
    grams: 3.5,
    borrowed: 0,
    postTax: false,
  };
  const firstPayload = {
    apiVersion: 2,
    requestId,
    environment: 'SANDBOX',
    purchases: [purchaseItem],
    consumptions: [],
  };
  const first = post(runtime, firstPayload);
  assert.equal(first.acknowledgedPurchases[0].status, 'committed');
  const assignedId = first.acknowledgedPurchases[0].legacyProductId;
  assert.equal(dataEntries(runtime, 'Purchases').length, 2);

  const secondPayload = {
    apiVersion: 2,
    requestId,
    environment: 'SANDBOX',
    purchases: [purchaseItem],
    consumptions: [{
      eventId,
      date: '2025-06-02',
      time: '09:30:00',
      productId: 'temp-reused',
      uses: 0.75,
      isFinished: false,
    }],
  };
  const second = post(runtime, secondPayload);
  assert.equal(second.acknowledgedPurchases[0].status, 'duplicate');
  assert.deepEqual(second.acknowledgedConsumptions, [
    { eventId, status: 'committed' },
  ]);
  assert.equal(second.productIdMap['temp-reused'], assignedId);
  assert.equal(dataEntries(runtime, 'Purchases').length, 2);
  assert.equal(productState(runtime, assignedId).uses, 0.75);
  assert.equal(productState(runtime, assignedId).status, 0);
  assert.equal(productState(runtime, assignedId).lastQuantity, 0.75);
  assertCounts(runtime, {
    responses: 1,
    events: 1,
    ledgers: 1,
    journals: 2,
  });
  assertReconciliationClean(runtime);
}

// Equal timestamps retain the quantity from the earlier physical canonical row.
{
  const timestamp = new Date('2025-05-09T12:00:00-04:00');
  const existingEvent = {
    eventId: deterministicUuid(73001),
    timestamp,
    productId: '*P1',
    productUuid: BASE_PRODUCT_UUID,
    uses: 0.25,
    localDate: '2025-05-09',
    localTime: '12:00:00',
    requestId: deterministicUuid(73002),
    source: 'ANDROID_V2',
  };
  const runtime = buildRuntime({
    existingEvents: [existingEvent],
    initialRecent: timestamp,
    initialQuantity: 0.25,
  });
  const requestPayload = payload(60, {
    eventId: deterministicUuid(73003),
    consumption: {
      date: '2025-05-09',
      time: '12:00:00',
      uses: 9,
    },
  });
  const response = post(runtime, requestPayload);
  assert.equal(response.acknowledgedConsumptions[0].status, 'committed');
  assert.equal(productState(runtime).uses, 11);
  assert.equal(productState(runtime).recentMillis, timestamp.getTime());
  assert.equal(productState(runtime).lastQuantity, 0.25);
  assertCounts(runtime, {
    responses: 2,
    events: 2,
    ledgers: 1,
    journals: 1,
  });
  assertReconciliationClean(runtime);
}

// Marker-0 summary migration deliberately treats nonempty canonical history as
// authoritative. A newer Form-only orphan must not make that migration compare
// against the compatibility table or block the canonical projection rebuild.
// The separate recoverable-apply reconciliation must still flag the orphan.
{
  const canonicalTimestamp = new Date('2025-05-09T10:00:00-04:00');
  const orphanTimestamp = new Date('2025-05-09T11:00:00-04:00');
  const existingEvent = {
    eventId: deterministicUuid(73981),
    timestamp: canonicalTimestamp,
    productId: '*P1',
    productUuid: BASE_PRODUCT_UUID,
    uses: 1,
    localDate: '2025-05-09',
    localTime: '10:00:00',
    requestId: deterministicUuid(73982),
    source: 'ANDROID_V2',
  };
  const runtime = buildRuntime({
    existingEvents: [existingEvent],
    formOrphan: {
      timestamp: orphanTimestamp,
      productId: '*P1',
      uses: 0.5,
      localDate: '2025-05-09',
      localTime: '11:00:00',
      weightCode: 'form-only-orphan',
      eventId: '',
      requestId: '',
    },
    initialUses: 1,
    initialRecent: orphanTimestamp,
    initialQuantity: 0.5,
  });
  runtime.context.setConfigValue_(
    runtime.spreadsheet,
    'INTERACTION_SUMMARY_VERSION',
    0,
    'Purchases interaction-summary version',
  );
  runtime.context.setConfigValue_(
    runtime.spreadsheet,
    'RECOVERABLE_SYNC_APPLY_VERSION',
    0,
    'Recoverable multi-sheet apply version',
  );
  runtime.resetAudit();

  const migration = runtime.context.runInteractionSummaryMigration();

  assert.equal(migration.legacyComparisonDifferences, 0);
  assert.equal(migration.validCanonicalEvents, 1);
  assert.equal(migration.configSummaryVersion, 0);
  assert.equal(configValue(runtime, 'INTERACTION_SUMMARY_VERSION'), 0);
  assert.equal(productState(runtime).recentMillis, canonicalTimestamp.getTime());
  assert.equal(productState(runtime).lastQuantity, 1);
  assertCounts(runtime, {
    responses: 2,
    events: 1,
    ledgers: 0,
    journals: 0,
  });
  const orphanResponse = dataEntries(runtime, 'Form Responses 1')[1];
  assert.equal(
    cell(runtime, 'Form Responses 1', orphanResponse, 'Cannsheet Event UUID') || '',
    '',
  );

  const reconciliation = runtime.context.reconcileRecoverableSyncApply();
  assert.equal(reconciliation.interactionSummaryDifferences, 0);
  assert.ok(
    reconciliation.blockingDifferences.some(difference =>
      difference.type === 'UNIDENTIFIED_COMPATIBILITY_ROW' &&
      difference.sourceRow === orphanResponse.rowNumber),
  );
}

// Marker-0 maintenance must never create a second canonical event merely
// because an otherwise-identical canonical row is already linked to another
// compatibility row. That ambiguity stays blocked for human review.
{
  const firstTimestamp = new Date('2025-05-10T10:00:00-04:00');
  const secondTimestamp = new Date('2025-05-10T11:00:00-04:00');
  const firstEvent = {
    eventId: deterministicUuid(73991),
    timestamp: firstTimestamp,
    productId: '*P1',
    productUuid: BASE_PRODUCT_UUID,
    uses: 1,
    localDate: '2025-05-10',
    localTime: '10:00:00',
    requestId: deterministicUuid(73992),
    source: 'ANDROID_V2',
  };
  const secondEvent = {
    eventId: deterministicUuid(73993),
    timestamp: secondTimestamp,
    productId: '*P1',
    productUuid: BASE_PRODUCT_UUID,
    uses: 1,
    localDate: '2025-05-10',
    localTime: '11:00:00',
    requestId: deterministicUuid(73994),
    source: 'ANDROID_V2',
  };
  const runtime = buildRuntime({
    existingEvents: [firstEvent, secondEvent],
    initialUses: 2,
    initialRecent: secondTimestamp,
    initialQuantity: 1,
  });
  runtime.context.setConfigValue_(
    runtime.spreadsheet,
    'RECOVERABLE_SYNC_APPLY_VERSION',
    0,
    'Recoverable multi-sheet apply version',
  );
  const responseHeaders = headerMap(runtime, 'Form Responses 1');
  runtime.peekSheet('Form Responses 1').getRange(
    2,
    responseHeaders['Cannsheet Event UUID'] + 1,
    2,
    2,
  ).setValues([['', ''], ['', '']]);
  const eventHeaders = headerMap(runtime, 'ConsumptionEvents');
  runtime.peekSheet('ConsumptionEvents').getRange(
    3,
    eventHeaders['Legacy Source Row'] + 1,
  ).setValue(99);
  runtime.resetAudit();

  assert.throws(
    () => runtime.context.prepareRecoverableSyncApply(),
    /COMPATIBILITY_IDENTITY_BACKFILL_BLOCKED/,
  );
  const firstCompatibility = dataEntries(runtime, 'Form Responses 1')[0];
  const secondCompatibility = dataEntries(runtime, 'Form Responses 1')[1];
  assert.equal(
    cell(
      runtime,
      'Form Responses 1',
      firstCompatibility,
      'Cannsheet Event UUID',
    ),
    firstEvent.eventId,
  );
  assert.equal(
    cell(
      runtime,
      'Form Responses 1',
      secondCompatibility,
      'Cannsheet Event UUID',
    ) || '',
    '',
  );

  const repair = runtime.context.repairPreparedCompatibilityRows();
  assert.equal(repair.relinkedRows, 1);
  assert.equal(repair.canonicalizedRows, 0);
  assert.equal(repair.unresolvedRows, 0);
  assert.equal(
    cell(
      runtime,
      'Form Responses 1',
      dataEntries(runtime, 'Form Responses 1')[1],
      'Cannsheet Event UUID',
    ),
    secondEvent.eventId,
  );
  const enabled = runtime.context.enableRecoverableSyncApply();
  assert.equal(enabled.fastPathEnabled, true);
  assert.equal(configValue(runtime, 'RECOVERABLE_SYNC_APPLY_VERSION'), 1);
}

{
  const timestamp = new Date('2025-05-10T12:00:00-04:00');
  const existingEvent = {
    eventId: deterministicUuid(74001),
    timestamp,
    productId: '*P1',
    productUuid: BASE_PRODUCT_UUID,
    uses: 1,
    localDate: '2025-05-10',
    localTime: '12:00:00',
    requestId: deterministicUuid(74002),
    source: 'ANDROID_V2',
  };
  const runtime = buildRuntime({
    existingEvents: [existingEvent],
    initialUses: 1,
    initialRecent: timestamp,
    initialQuantity: 1,
  });
  runtime.context.setConfigValue_(
    runtime.spreadsheet,
    'RECOVERABLE_SYNC_APPLY_VERSION',
    0,
    'Recoverable multi-sheet apply version',
  );
  runtime.peekSheet('Form Responses 1').appendRow(compatibilityRow({
    timestamp,
    productId: '*P1',
    uses: 1,
    localDate: '2025-05-10',
    localTime: '12:00:00',
    eventId: '',
    requestId: '',
  }));
  runtime.resetAudit();

  const result = runtime.context.repairPreparedCompatibilityRows();

  assert.equal(result.relinkedRows, 0);
  assert.equal(result.canonicalizedRows, 0);
  assert.equal(result.unresolvedRows, 1);
  assert.equal(
    result.unresolved[0].type,
    'CANONICAL_MATCH_ALREADY_IDENTIFIED',
  );
  assertCounts(runtime, {
    responses: 2,
    events: 1,
    ledgers: 0,
    journals: 0,
  });
  assert.equal(productState(runtime).uses, 1);
}

// A truly unmatched marker-0 compatibility row can be canonicalized only when
// the Purchases Uses value still equals the canonical total, proving that the
// old write sequence stopped before product effects were applied.
{
  const canonicalTimestamp = new Date('2025-05-11T10:00:00-04:00');
  const orphanTimestamp = new Date('2025-05-11T11:00:00-04:00');
  const existingEvent = {
    eventId: deterministicUuid(75001),
    timestamp: canonicalTimestamp,
    productId: '*P1',
    productUuid: BASE_PRODUCT_UUID,
    uses: 1,
    localDate: '2025-05-11',
    localTime: '10:00:00',
    requestId: deterministicUuid(75002),
    source: 'ANDROID_V2',
  };
  const runtime = buildRuntime({
    existingEvents: [existingEvent],
    initialUses: 1,
    initialRecent: canonicalTimestamp,
    initialQuantity: 1,
  });
  runtime.context.setConfigValue_(
    runtime.spreadsheet,
    'RECOVERABLE_SYNC_APPLY_VERSION',
    0,
    'Recoverable multi-sheet apply version',
  );
  runtime.peekSheet('Form Responses 1').appendRow(compatibilityRow({
    timestamp: orphanTimestamp,
    productId: '*P1',
    uses: 0.5,
    localDate: '2025-05-11',
    localTime: '11:00:00',
    weightCode: 'legacy-orphan',
    eventId: '',
    requestId: '',
  }));
  runtime.resetAudit();

  const result = runtime.context.repairPreparedCompatibilityRows();

  assert.equal(result.relinkedRows, 0);
  assert.equal(result.canonicalizedRows, 1);
  assert.equal(result.unresolvedRows, 0);
  assertCounts(runtime, {
    responses: 2,
    events: 2,
    ledgers: 0,
    journals: 1,
  });
  assert.equal(productState(runtime).uses, 1.5);
  assert.equal(productState(runtime).lastQuantity, 0.5);
  assert.equal(productState(runtime).recentMillis, orphanTimestamp.getTime());
  const repairedResponse = dataEntries(runtime, 'Form Responses 1')[1];
  const repairedEventId = cell(
    runtime,
    'Form Responses 1',
    repairedResponse,
    'Cannsheet Event UUID',
  );
  assert.ok(repairedEventId);
  const repairedCanonical = entryBy(
    runtime,
    'ConsumptionEvents',
    'Event UUID',
    repairedEventId,
  );
  assert.equal(
    Number(cell(
      runtime,
      'ConsumptionEvents',
      repairedCanonical,
      'Legacy Source Row',
    )),
    repairedResponse.rowNumber,
  );
  const enabled = runtime.context.enableRecoverableSyncApply();
  assert.equal(enabled.fastPathEnabled, true);
  assert.equal(configValue(runtime, 'RECOVERABLE_SYNC_APPLY_VERSION'), 1);
}

// If Uses indicates that an unmatched row may already have affected the
// product, maintenance must stop instead of risking a second increment.
{
  const canonicalTimestamp = new Date('2025-05-12T10:00:00-04:00');
  const existingEvent = {
    eventId: deterministicUuid(76001),
    timestamp: canonicalTimestamp,
    productId: '*P1',
    productUuid: BASE_PRODUCT_UUID,
    uses: 1,
    localDate: '2025-05-12',
    localTime: '10:00:00',
    requestId: deterministicUuid(76002),
    source: 'ANDROID_V2',
  };
  const runtime = buildRuntime({
    existingEvents: [existingEvent],
    initialUses: 1.5,
    initialRecent: canonicalTimestamp,
    initialQuantity: 1,
  });
  runtime.context.setConfigValue_(
    runtime.spreadsheet,
    'RECOVERABLE_SYNC_APPLY_VERSION',
    0,
    'Recoverable multi-sheet apply version',
  );
  runtime.peekSheet('Form Responses 1').appendRow(compatibilityRow({
    timestamp: new Date('2025-05-12T11:00:00-04:00'),
    productId: '*P1',
    uses: 0.5,
    localDate: '2025-05-12',
    localTime: '11:00:00',
    eventId: '',
    requestId: '',
  }));
  runtime.resetAudit();

  const result = runtime.context.repairPreparedCompatibilityRows();

  assert.equal(result.canonicalizedRows, 0);
  assert.equal(result.unresolvedRows, 1);
  assert.equal(result.unresolved[0].type, 'UNMATCHED_COMPATIBILITY_ROW');
  assert.equal(dataEntries(runtime, 'ConsumptionEvents').length, 1);
  assert.equal(productState(runtime).uses, 1.5);
}

// Full reconciliation reads the compatibility table in bulk. It must not
// regress into one Apps Script read per canonical event.
{
  const eventCount = 100;
  const baseMillis = new Date('2025-05-13T09:00:00-04:00').getTime();
  const existingEvents = Array.from({ length: eventCount }, (_, index) => {
    const timestamp = new Date(baseMillis + index * 60_000);
    return {
      eventId: deterministicUuid(77000 + index),
      timestamp,
      productId: '*P1',
      productUuid: BASE_PRODUCT_UUID,
      uses: 1,
      localDate: '2025-05-13',
      localTime: timestamp.toISOString().slice(11, 19),
      requestId: deterministicUuid(78000 + index),
      source: 'ANDROID_V2',
    };
  });
  const runtime = buildRuntime({
    existingEvents,
    initialUses: eventCount,
    initialRecent: existingEvents.at(-1).timestamp,
    initialQuantity: 1,
  });
  runtime.resetAudit();

  const reconciliation = runtime.context.reconcileRecoverableSyncApply();

  assert.equal(reconciliation.blockingDifferences.length, 0);
  const compatibilityDataReads = runtime.audit.reads.filter(entry =>
    entry.sheet === 'Form Responses 1' && entry.row >= 2);
  assert.ok(
    compatibilityDataReads.length <= 2,
    `Expected at most two bulk compatibility reads, got ${compatibilityDataReads.length}`,
  );
  assert.ok(compatibilityDataReads.every(entry => entry.numRows === eventCount));
}

// The public rollout check reconciles every Purchases/canonical product and is
// strictly read-only in both the clean and drift-reporting cases.
{
  const runtime = buildRuntime({
    initialUses: 0,
    initialRecent: null,
    initialQuantity: null,
  });
  const purchases = runtime.peekSheet('Purchases');
  const headers = headerMap(runtime, 'Purchases');
  const secondProduct = purchases.snapshot().rows[1].slice();
  secondProduct[headers['Product name']] = 'Second reconciliation product';
  secondProduct[headers['Product ID']] = '*P2';
  secondProduct[headers['Product UUID']] = deterministicUuid(79001);
  secondProduct[headers['Client Action UUID']] = deterministicUuid(79002);
  secondProduct[headers.Uses] = 0;
  secondProduct[headers['Most recent use']] = '';
  secondProduct[headers['Last quantity']] = '';
  purchases.appendRow(secondProduct);
  runtime.resetAudit();

  const cleanBefore = durableSnapshot(runtime);
  const cleanLogCount = runtime.logs.length;
  const clean = runtime.context.reconcileProductProjections();

  assert.equal(clean.checkedProducts, 2);
  assert.deepEqual(Array.from(clean.differences), []);
  assert.equal(durableSnapshot(runtime), cleanBefore);
  assert.equal(runtime.audit.writes.length, 0);
  assert.equal(runtime.audit.structural.length, 0);
  assert.equal(runtime.audit.batches.length, 0);
  assert.equal(runtime.audit.form.length, 0);
  assert.ok(
    runtime.audit.services.every(entry =>
      entry.method !== 'flush' &&
      entry.method !== 'Spreadsheets.batchUpdate'),
  );
  assert.deepEqual(
    runtime.audit.locks.map(entry => entry.operation),
    ['getScriptLock', 'tryLock', 'releaseLock'],
  );
  const cleanLog = JSON.parse(runtime.logs[cleanLogCount].args[0]);
  assert.equal(cleanLog.type, 'product_projections_reconciled');
  assert.equal(cleanLog.result.checkedProducts, 2);
  assert.deepEqual(Array.from(cleanLog.result.differences), []);

  purchases.getRange(
    3,
    headers.Uses + 1,
  ).setValue(1);
  runtime.peekSheet('ConsumptionEvents').appendRow(canonicalRow({
    eventId: deterministicUuid(79003),
    timestamp: new Date('2025-05-14T10:00:00-04:00'),
    productId: '*P3',
    productUuid: deterministicUuid(79004),
    uses: 2,
    localDate: '2025-05-14',
    localTime: '10:00:00',
    requestId: deterministicUuid(79005),
  }, 99));
  runtime.resetAudit();
  const driftBefore = durableSnapshot(runtime);
  const drift = runtime.context.reconcileProductProjections();

  assert.equal(drift.checkedProducts, 3);
  assert.equal(drift.differences.length, 2);
  assert.equal(drift.differences[0].type, 'PRODUCT_PROJECTION_MISMATCH');
  assert.equal(drift.differences[0].legacyProductId, '*P2');
  assert.equal(drift.differences[0].actual.uses, 1);
  assert.equal(drift.differences[0].expected.uses, 0);
  assert.equal(drift.differences[1].type, 'MISSING_PRODUCT');
  assert.equal(drift.differences[1].legacyProductId, '*P3');
  assert.equal(durableSnapshot(runtime), driftBefore);
  assert.equal(runtime.audit.writes.length, 0);
  assert.equal(runtime.audit.structural.length, 0);
  assert.equal(runtime.audit.batches.length, 0);
  assert.equal(runtime.audit.form.length, 0);
}

console.log('backend recoverable multi-sheet apply tests passed');
