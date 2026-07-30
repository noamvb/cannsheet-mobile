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
const REPORT_HEADERS = [
  'Type', 'Source Sheet', 'Source Row', 'Product ID', 'Detail', 'Recorded At',
];
const CORRECTION_HEADERS = [
  'Correction UUID', 'Target Event UUID', 'Supersedes Correction UUID',
  'Revision', 'Operation', 'Replacement Timestamp',
  'Replacement Local Date', 'Replacement Local Time',
  'Replacement Product UUID', 'Replacement Legacy Product ID',
  'Replacement Uses', 'Replacement Weight Code', 'Replacement Finished',
  'Reopen Product', 'Reason', 'Request UUID', 'Source', 'Created At',
];

const P1_UUID = deterministicUuid(101);
const P2_UUID = deterministicUuid(102);
const EVENT_ID = deterministicUuid(1001);
const ORIGINAL_TIMESTAMP = new Date('2026-07-10T14:15:00Z');
const FAULTS = {
  PRE_CORE: 'AFTER_CANONICAL',
  CORE_COMMITTED: 'AFTER_CORE_COMMIT',
  FINALIZATION: 'BEFORE_FINAL_LEDGER',
  POST_COMPLETE: 'AFTER_COMPLETE',
};

function configRows(options = {}) {
  const writesEnabled = options.writesEnabled !== false;
  const correctionVersion = options.correctionVersion === undefined
    ? 1
    : options.correctionVersion;
  const recoverableApplyVersion = options.recoverableApplyVersion === undefined
    ? 1
    : options.recoverableApplyVersion;
  return [
    ['Key', 'Value', 'Description'],
    ['ENVIRONMENT', 'SANDBOX', 'Runtime environment marker'],
    ['TAX_RATE', 0.13, 'Tax rate'],
    ['TIME_ZONE', 'America/New_York', 'Canonical local timezone'],
    ['SCHEMA_VERSION', 2, 'Spreadsheet schema version'],
    ['INTERACTION_SUMMARY_VERSION', 1, 'Purchases interaction-summary version'],
    ['RECOVERABLE_SYNC_APPLY_VERSION', recoverableApplyVersion, 'Recoverable multi-sheet apply version'],
    ['PENDING_APPLY_KEY', '', 'Apply UUID awaiting finalization'],
    ['CONSUMPTION_CORRECTION_SCHEMA_VERSION', correctionVersion, 'Append-only correction schema'],
    ['CONSUMPTION_CORRECTION_WRITES_ENABLED', writesEnabled, 'Accept correction writes'],
    ['MAX_BATCH_SIZE', 100, 'Maximum batch size'],
    ['LOCK_TIMEOUT_MS', 30000, 'Lock timeout'],
  ];
}

function purchase(productId, productUuid, name, finished, uses, recent, quantity) {
  return {
    Date: '2026-07-01', Type: 'P', 'Product name': name,
    'Pre-tax cost': 10, 'THC%': 20, Grams: 3.5, Borrowed: 0,
    Finished: finished, 'Product ID': productId, Uses: uses,
    'Post-tax': false, 'Final cost': 11.3, 'Most recent use': recent,
    'Product UUID': productUuid, 'Client Action UUID': deterministicUuid(productId === '*P1' ? 201 : 202),
    'Created At': new Date('2026-07-01T12:00:00Z'),
    'Finished At': finished === 1 ? recent : '', 'Last quantity': quantity,
  };
}

function dateFormats(columns, lastRow = 30) {
  const formats = {};
  for (let row = 2; row <= lastRow; row += 1) {
    columns.forEach(column => {
      formats[`${row}:${column}`] = { type: 'DATE_TIME', pattern: 'yyyy-mm-dd hh:mm:ss' };
    });
  }
  return formats;
}

function baseEvent() {
  return {
    'Event UUID': EVENT_ID, Timestamp: ORIGINAL_TIMESTAMP,
    'Local Date': '2026-07-10', 'Local Time': '10:15:00',
    'Product UUID': P1_UUID, 'Legacy Product ID': '*P1', Uses: 1,
    'Weight Code': 'A', Finished: true, Source: 'ANDROID_V2',
    'Request UUID': deterministicUuid(2001),
    'Legacy Source Sheet': 'Form Responses 1', 'Legacy Source Row': 2,
  };
}

function baseCompatibilityResponse() {
  return {
    Timestamp: ORIGINAL_TIMESTAMP, Product: '*P1', Uses: 1,
    Date: '2026-07-10', Time: '10:15:00', 'Weight code': 'A',
    'Mark as Finished?': true, 'Cannsheet Event UUID': EVENT_ID,
    'Cannsheet Request UUID': deterministicUuid(2001),
  };
}

function baseEventProvenanceJournal() {
  return {
    'Apply UUID': deterministicUuid(3001),
    Kind: 'ANDROID_V2',
    'API Version': 2,
    'Request UUID': deterministicUuid(2001),
    State: 'COMPLETE',
    'Core Committed At': new Date('2026-07-10T14:15:00Z'),
    'Completed At': new Date('2026-07-10T14:15:01Z'),
    'Finalization JSON': JSON.stringify({
      eventIds: [EVENT_ID],
      correctionActions: [],
      finishActions: [],
    }),
    'Response JSON': '{}',
  };
}

function buildRuntime(options = {}) {
  const journalRecords = options.eventProvenance === false
    ? []
    : [baseEventProvenanceJournal()];
  const runtime = createAppsScriptRuntime({
    environment: 'SANDBOX', spreadsheetId: 'correction-sheet', formId: 'correction-form',
    timeZone: 'America/New_York', now: '2026-07-20T12:00:00-04:00',
    sheets: {
      Purchases: { rows: makeSheetRows(PURCHASE_HEADERS, [
        purchase('*P1', P1_UUID, 'Alpha', 1, 1, ORIGINAL_TIMESTAMP, 1),
        purchase('*P2', P2_UUID, 'Beta', 2, 0, '', ''),
      ]), maxColumns: PURCHASE_HEADERS.length, numberFormats: dateFormats([13, 16, 17]),
      frozenRows: options.purchasesFrozenRows,
      typedColumns: options.purchasesTypedColumns ? PURCHASE_HEADERS.map((_, index) => index + 1) : [],
      tables: options.purchasesTables || [] },
      'Form Responses 1': {
        rows: makeSheetRows(RESPONSE_HEADERS, [baseCompatibilityResponse()]),
        maxColumns: RESPONSE_HEADERS.length, numberFormats: dateFormats([1]),
      },
      ConsumptionEvents: { rows: makeSheetRows(EVENT_HEADERS, [baseEvent()]), maxColumns: EVENT_HEADERS.length, numberFormats: dateFormats([2]) },
      SyncLedger: { rows: [LEDGER_HEADERS], maxColumns: LEDGER_HEADERS.length, numberFormats: dateFormats([3]) },
      SyncApplyJournal: {
        rows: makeSheetRows(JOURNAL_HEADERS, journalRecords),
        maxColumns: JOURNAL_HEADERS.length,
        numberFormats: dateFormats([6, 7]),
      },
      Config: { rows: configRows(options) },
      MigrationReport: { rows: [REPORT_HEADERS], maxColumns: REPORT_HEADERS.length },
      ConsumptionEventCorrections: {
        rows: [CORRECTION_HEADERS], maxColumns: CORRECTION_HEADERS.length,
        numberFormats: dateFormats([6, 18]),
      },
    },
    form: { id: 'correction-form', destinationId: 'correction-sheet', items: [{
      title: 'Product', type: 'MULTIPLE_CHOICE', choices: ['*P1'],
    }] },
    sheetsGetAvailable: options.sheetsGetAvailable,
    sheetsGetError: options.sheetsGetError,
    sheetsMetadata: options.sheetsMetadata,
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

function get(runtime, parameters) {
  const parameter = Object.fromEntries(Object.entries(parameters).map(([key, value]) => [key, String(value)]));
  return runtime.parseTextOutput(runtime.context.doGet({
    parameter, parameters: Object.fromEntries(Object.entries(parameter).map(([key, value]) => [key, [value]])),
  }));
}

function withHostTimeZone(timeZone, callback) {
  const previous = process.env.TZ;
  process.env.TZ = timeZone;
  try {
    return callback();
  } finally {
    if (previous === undefined) delete process.env.TZ;
    else process.env.TZ = previous;
  }
}

function correction(actionOrdinal, operation, overrides = {}) {
  const replace = operation === 'REPLACE';
  return Object.assign({
    actionId: deterministicUuid(10000 + actionOrdinal),
    targetEventId: EVENT_ID,
    expectedCorrectionHeadId: '',
    operation,
    reopenProduct: false,
    reason: 'test correction',
  }, replace ? {
    replacement: {
      date: '2026-07-11', time: '11:30:00', productUuid: P2_UUID,
      productId: '*P2', uses: 2.5, weightCode: 'A', finished: false,
    },
  } : {}, overrides);
}

function payload(requestOrdinal, corrections, overrides = {}) {
  return Object.assign({
    apiVersion: 2, requestId: deterministicUuid(20000 + requestOrdinal),
    environment: 'SANDBOX', purchases: [], consumptions: [], finishActions: [],
    consumptionCorrections: corrections,
  }, overrides);
}

function consumption(eventOrdinal, overrides = {}) {
  return Object.assign({
    eventId: deterministicUuid(30000 + eventOrdinal),
    productId: '*P2',
    productUuid: P2_UUID,
    date: '2026-07-12',
    time: '12:00:00',
    uses: 0.5,
    weightCode: 'A',
    isFinished: false,
  }, overrides);
}

function rows(runtime, name) {
  return runtime.peekSheet(name).snapshot().rows;
}

function dataRows(runtime, name) {
  return rows(runtime, name).slice(1).filter(row => row.some(value => value !== '' && value != null));
}

function headerIndex(sheetRows, name) {
  const index = sheetRows[0].indexOf(name);
  assert.notEqual(index, -1, `missing ${name} header`);
  return index;
}

function productRow(runtime, productId) {
  const sheetRows = rows(runtime, 'Purchases');
  const id = headerIndex(sheetRows, 'Product ID');
  return sheetRows.slice(1).find(row => row[id] === productId);
}

function productValue(runtime, productId, name) {
  const sheetRows = rows(runtime, 'Purchases');
  return productRow(runtime, productId)[headerIndex(sheetRows, name)];
}

function configValue(runtime, key) {
  const sheetRows = rows(runtime, 'Config');
  const keyIndex = headerIndex(sheetRows, 'Key');
  const valueIndex = headerIndex(sheetRows, 'Value');
  const row = sheetRows.slice(1).find(item => item[keyIndex] === key);
  assert.ok(row, `missing Config key ${key}`);
  return row[valueIndex];
}

function setConfigValue(runtime, key, value) {
  const sheetRows = rows(runtime, 'Config');
  const keyIndex = headerIndex(sheetRows, 'Key');
  const valueIndex = headerIndex(sheetRows, 'Value');
  const rowNumber = sheetRows.findIndex(row => row[keyIndex] === key) + 1;
  assert.ok(rowNumber >= 2, `missing Config key ${key}`);
  runtime.peekSheet('Config').getRange(rowNumber, valueIndex + 1).setValue(value);
}

function setProductValue(runtime, productId, name, value) {
  const sheetRows = rows(runtime, 'Purchases');
  const rowNumber = sheetRows.findIndex(
    row => row[headerIndex(sheetRows, 'Product ID')] === productId
  ) + 1;
  assert.ok(rowNumber >= 2, `missing product ${productId}`);
  runtime.peekSheet('Purchases')
    .getRange(rowNumber, headerIndex(sheetRows, name) + 1)
    .setValue(value);
}

function assertInternalFailure(response) {
  assert.equal(response.success, false, JSON.stringify(response));
  assert.equal(response.errorCode, 'INTERNAL_ERROR', JSON.stringify(response));
}

function assertReadOnlyGet(runtime, parameters) {
  runtime.resetAudit();
  const response = get(runtime, parameters);
  assert.equal(runtime.audit.writes.length, 0, 'GET must not write cells');
  assert.equal(runtime.audit.structural.length, 0, 'GET must not change sheet structure');
  assert.equal(runtime.audit.batches.length, 0, 'GET must not batch-write cells');
  assert.equal(runtime.audit.form.length, 0, 'GET must not alter form state');
  return response;
}

function assertCorrectionProjection(runtime) {
  assert.equal(dataRows(runtime, 'ConsumptionEventCorrections').length, 1);
  assert.equal(productValue(runtime, '*P1', 'Finished'), 2);
  assert.equal(productValue(runtime, '*P1', 'Uses'), 0);
  assert.equal(productValue(runtime, '*P2', 'Uses'), 2.5);
  assert.equal(productValue(runtime, '*P2', 'Finished'), 0);
  assert.equal(
    runtime.context.reconcileConsumptionCorrections().differences.length,
    0
  );
}

// A REPLACE is append-only, idempotent, does not rewrite either raw event source,
// and updates both affected product projections from effective history.
{
  const runtime = buildRuntime();
  const catalog = get(runtime, {});
  assert.equal(catalog.correctionVersion, 1);
  assert.equal(catalog.correctionWritesEnabled, true);
  const originalCanonical = JSON.stringify(rows(runtime, 'ConsumptionEvents'));
  const originalCompatibility = JSON.stringify(rows(runtime, 'Form Responses 1'));
  const replace = correction(1, 'REPLACE', { reopenProduct: true, reason: 'move to Beta' });
  const firstPayload = payload(1, [replace]);
  const first = post(runtime, firstPayload);
  assert.equal(first.allAccepted, true);
  assert.deepEqual(first.rejectedConsumptionCorrections, []);
  assert.deepEqual(first.acknowledgedConsumptionCorrections, [{
    actionId: replace.actionId, targetEventId: EVENT_ID, revision: 1, status: 'committed',
  }]);
  assert.equal(dataRows(runtime, 'ConsumptionEventCorrections').length, 1);
  assert.equal(JSON.stringify(rows(runtime, 'ConsumptionEvents')), originalCanonical);
  assert.equal(JSON.stringify(rows(runtime, 'Form Responses 1')), originalCompatibility);
  assert.equal(productValue(runtime, '*P1', 'Finished'), 2);
  assert.equal(productValue(runtime, '*P1', 'Uses'), 0);
  assert.equal(productValue(runtime, '*P2', 'Uses'), 2.5);
  assert.equal(productValue(runtime, '*P2', 'Finished'), 0);

  const sameRequestRetry = post(runtime, firstPayload);
  assert.equal(
    sameRequestRetry.success,
    true,
    JSON.stringify(sameRequestRetry),
  );
  assert.equal(
    Array.isArray(sameRequestRetry.acknowledgedConsumptionCorrections),
    true,
    JSON.stringify(sameRequestRetry),
  );
  assert.equal(
    sameRequestRetry.acknowledgedConsumptionCorrections.length,
    1,
    JSON.stringify(sameRequestRetry),
  );
  assert.equal(
    sameRequestRetry.acknowledgedConsumptionCorrections[0].status,
    'duplicate',
    JSON.stringify(sameRequestRetry),
  );
  assert.equal(dataRows(runtime, 'ConsumptionEventCorrections').length, 1);

  const retry = post(runtime, payload(2, [replace]));
  assert.equal(retry.rejectedConsumptionCorrections.length, 0, JSON.stringify(retry));
  assert.equal(retry.acknowledgedConsumptionCorrections.length, 1, JSON.stringify(retry));
  assert.equal(retry.acknowledgedConsumptionCorrections[0].status, 'duplicate');
  assert.equal(dataRows(runtime, 'ConsumptionEventCorrections').length, 1);

  const conflicting = JSON.parse(JSON.stringify(replace));
  conflicting.replacement.uses = 2.75;
  const conflictResponse = post(runtime, payload(3, [conflicting]));
  assert.equal(
    conflictResponse.rejectedConsumptionCorrections[0].errorCode,
    'CORRECTION_ID_CONFLICT'
  );
  assert.equal(dataRows(runtime, 'ConsumptionEventCorrections').length, 1);

  const history = get(runtime, { resource: 'history', analyticsVersion: 2, environment: 'SANDBOX', limit: 10 });
  assert.equal(history.success, true);
  assert.equal(history.events.length, 1);
  assert.equal(history.events[0].productId, '*P2');
  assert.equal(history.events[0].quantity, 2.5);
  assert.equal(history.events[0].lifecycleState, 'CORRECTED');
  assert.equal(history.events[0].revision, 1);
  assert.equal(history.events[0].correctionHeadId, replace.actionId);

  const audit = assertReadOnlyGet(runtime, {
    resource: 'history', analyticsVersion: 2, includeAudit: true,
    environment: 'SANDBOX', limit: 10,
  });
  assert.equal(audit.events[0].auditRevisions.length, 1);
  assert.equal(audit.events[0].auditRevisions[0].operation, 'REPLACE');
  const legacyHistory = get(runtime, { resource: 'history', analyticsVersion: 1, environment: 'SANDBOX', limit: 10 });
  assert.equal(legacyHistory.events.length, 1);
  assert.equal(legacyHistory.events[0].productId, '*P2');
  assert.equal(legacyHistory.events[0].quantity, 2.5);
  assert.equal(
    Object.prototype.hasOwnProperty.call(
      legacyHistory.events[0],
      'lifecycleState'
    ),
    false
  );
  const insights = get(runtime, { resource: 'insights', analyticsVersion: 2, environment: 'SANDBOX', from: '2026-07-01', to: '2026-07-20' });
  assert.equal(insights.overview.logCount, 1);
  assert.equal(insights.products.find(product => product.productId === '*P2').allTime.quantity, 2.5);
  assertReadOnlyGet(runtime, {
    resource: 'insights', analyticsVersion: 2, environment: 'SANDBOX',
    from: '2026-07-01', to: '2026-07-20',
  });
}

// Correction timestamps are canonical New York wall-clock values, independent
// of the Node host timezone. Both commit and exact same-request retry must
// therefore resolve to one durable revision with a matching acknowledgement.
[
  { hostTimeZone: 'UTC', ordinal: 90 },
  { hostTimeZone: 'America/New_York', ordinal: 91 },
].forEach(testCase => withHostTimeZone(testCase.hostTimeZone, () => {
  const runtime = buildRuntime();
  const action = correction(testCase.ordinal, 'REPLACE', {
    reason: `host timezone ${testCase.hostTimeZone}`,
  });
  const request = payload(testCase.ordinal, [action]);
  const committed = post(runtime, request);
  assert.deepEqual(
    committed.acknowledgedConsumptionCorrections,
    [{
      actionId: action.actionId,
      targetEventId: EVENT_ID,
      revision: 1,
      status: 'committed',
    }],
    JSON.stringify(committed),
  );
  assert.equal(
    runtime.audit.services.some(entry => (
      entry.service === 'Utilities'
      && entry.method === 'parseDate'
      && entry.timeZone === 'America/New_York'
      && entry.format === 'yyyy-MM-dd HH:mm:ss'
    )),
    true,
    `missing canonical Utilities.parseDate call for ${testCase.hostTimeZone}`,
  );

  const retry = post(runtime, request);
  assert.equal(retry.success, true, JSON.stringify(retry));
  assert.deepEqual(
    retry.acknowledgedConsumptionCorrections,
    [{
      actionId: action.actionId,
      targetEventId: EVENT_ID,
      revision: 1,
      status: 'duplicate',
    }],
    JSON.stringify(retry),
  );
  assert.equal(dataRows(runtime, 'ConsumptionEventCorrections').length, 1);

  const history = get(runtime, {
    resource: 'history',
    analyticsVersion: 2,
    environment: 'SANDBOX',
    limit: 10,
  });
  assert.equal(history.success, true, JSON.stringify(history));
  assert.equal(
    history.events[0].occurredAtEpochMillis,
    Date.parse('2026-07-11T15:30:00.000Z'),
    JSON.stringify(history),
  );
}));

// A syntactically valid wall time can still be nonexistent in the canonical
// timezone. The spring-forward gap must be item-rejected, never replaced with
// the server clock, and its retry must remain free of domain/history mutation.
// The SyncLedger request-audit row is the intentional durable exception.
{
  const runtime = buildRuntime();
  const action = correction(93, 'REPLACE');
  action.replacement.date = '2026-03-08';
  action.replacement.time = '02:30:00';
  const request = payload(93, [action]);
  const before = {
    purchases: JSON.stringify(rows(runtime, 'Purchases')),
    compatibility: JSON.stringify(rows(runtime, 'Form Responses 1')),
    canonical: JSON.stringify(rows(runtime, 'ConsumptionEvents')),
    corrections: JSON.stringify(rows(runtime, 'ConsumptionEventCorrections')),
    journal: JSON.stringify(rows(runtime, 'SyncApplyJournal')),
  };
  assert.equal(dataRows(runtime, 'SyncLedger').length, 0);

  const response = post(runtime, request);
  assert.equal(response.success, true, JSON.stringify(response));
  assert.equal(response.allAccepted, false, JSON.stringify(response));
  assert.deepEqual(response.acknowledgedConsumptionCorrections, []);
  assert.deepEqual(response.rejectedConsumptionCorrections, [{
    actionId: action.actionId,
    targetEventId: EVENT_ID,
    errorCode: 'INVALID_ITEM',
    message: 'Correction replacement date or time is invalid in America/New_York',
    currentHeadId: null,
  }]);
  assert.equal(JSON.stringify(rows(runtime, 'Purchases')), before.purchases);
  assert.equal(JSON.stringify(rows(runtime, 'Form Responses 1')), before.compatibility);
  assert.equal(JSON.stringify(rows(runtime, 'ConsumptionEvents')), before.canonical);
  assert.equal(
    JSON.stringify(rows(runtime, 'ConsumptionEventCorrections')),
    before.corrections,
  );
  assert.equal(JSON.stringify(rows(runtime, 'SyncApplyJournal')), before.journal);
  const firstLedgerRows = rows(runtime, 'SyncLedger');
  assert.equal(dataRows(runtime, 'SyncLedger').length, 1);
  assert.equal(
    firstLedgerRows[1][headerIndex(firstLedgerRows, 'Request UUID')],
    request.requestId,
  );
  assert.equal(firstLedgerRows[1][headerIndex(firstLedgerRows, 'API Version')], 2);
  assert.equal(firstLedgerRows[1][headerIndex(firstLedgerRows, 'Result')], 'PARTIAL');
  const ledgerRowNumber = 2;

  runtime.resetAudit();
  const retry = post(runtime, request);
  assert.equal(retry.success, true, JSON.stringify(retry));
  assert.deepEqual(
    retry.rejectedConsumptionCorrections,
    response.rejectedConsumptionCorrections,
    JSON.stringify(retry),
  );
  assert.deepEqual(retry.acknowledgedConsumptionCorrections, []);
  assert.equal(JSON.stringify(rows(runtime, 'Purchases')), before.purchases);
  assert.equal(JSON.stringify(rows(runtime, 'ConsumptionEvents')), before.canonical);
  assert.equal(
    JSON.stringify(rows(runtime, 'ConsumptionEventCorrections')),
    before.corrections,
  );
  assert.equal(JSON.stringify(rows(runtime, 'SyncApplyJournal')), before.journal);
  const retryLedgerRows = rows(runtime, 'SyncLedger');
  assert.equal(
    dataRows(runtime, 'SyncLedger').length,
    1,
    'retry must reuse the existing request-audit row',
  );
  assert.equal(
    retryLedgerRows[1][headerIndex(retryLedgerRows, 'Request UUID')],
    request.requestId,
  );
  assert.equal(retryLedgerRows[1][headerIndex(retryLedgerRows, 'Result')], 'PARTIAL');
  assert.equal(
    runtime.audit.writes.some(entry => (
      entry.sheet === 'SyncLedger'
      && entry.row === ledgerRowNumber
      && entry.operation === 'setValues'
    )),
    true,
    'retry must update the existing SyncLedger row',
  );
}

// Revisions form a linear append-only chain. VOID removes the effective row and
// RESTORE returns the immutable original snapshot.
{
  const runtime = buildRuntime();
  const replace = correction(10, 'REPLACE');
  const first = post(runtime, payload(10, [replace]));
  assert.equal(first.acknowledgedConsumptionCorrections[0].status, 'committed');
  const voidAction = correction(11, 'VOID', { expectedCorrectionHeadId: replace.actionId, reason: 'void it' });
  const voidResponse = post(runtime, payload(11, [voidAction]));
  assert.equal(voidResponse.acknowledgedConsumptionCorrections[0].revision, 2);
  const voidHistory = get(runtime, { resource: 'history', analyticsVersion: 2, environment: 'SANDBOX', limit: 10 });
  assert.equal(voidHistory.events.length, 0);
  const restore = correction(12, 'RESTORE', { expectedCorrectionHeadId: voidAction.actionId, reason: 'restore original' });
  const restoreResponse = post(runtime, payload(12, [restore]));
  assert.equal(restoreResponse.acknowledgedConsumptionCorrections[0].revision, 3);
  const restored = get(runtime, { resource: 'history', analyticsVersion: 2, includeAudit: true, environment: 'SANDBOX', limit: 10 });
  assert.equal(restored.events.length, 1);
  assert.equal(restored.events[0].productId, '*P1');
  assert.equal(restored.events[0].lifecycleState, 'ORIGINAL');
  assert.equal(restored.events[0].revision, 3);
  assert.deepEqual(restored.events[0].auditRevisions.map(item => item.operation), ['REPLACE', 'VOID', 'RESTORE']);
  assert.equal(dataRows(runtime, 'ConsumptionEventCorrections').length, 3);
}

// Clients cannot overwrite a newer head, malformed notes are rejected, and the
// server-wide write switch leaves the append-only log untouched.
{
  const runtime = buildRuntime();
  const replace = correction(20, 'REPLACE');
  post(runtime, payload(20, [replace]));
  const stale = correction(21, 'VOID');
  const staleResponse = post(runtime, payload(21, [stale]));
  assert.equal(staleResponse.rejectedConsumptionCorrections[0].errorCode, 'CORRECTION_CONFLICT');
  assert.equal(staleResponse.rejectedConsumptionCorrections[0].currentHeadId, replace.actionId);
  const tooLong = correction(22, 'VOID', { expectedCorrectionHeadId: replace.actionId, reason: 'x'.repeat(201) });
  const tooLongResponse = post(runtime, payload(22, [tooLong]));
  assert.equal(tooLongResponse.rejectedConsumptionCorrections[0].errorCode, 'INVALID_ITEM');
  assert.match(tooLongResponse.rejectedConsumptionCorrections[0].message, /200/);

  const disabled = buildRuntime({ writesEnabled: false });
  const disabledResponse = post(disabled, payload(23, [correction(23, 'VOID')]));
  assert.equal(disabledResponse.rejectedConsumptionCorrections[0].errorCode, 'CORRECTION_WRITES_DISABLED');
  assert.equal(dataRows(disabled, 'ConsumptionEventCorrections').length, 0);
}

// A request may read an enabled capability before waiting for the script lock.
// If disable completes first, the lock-held request must refresh that state and
// reject the correction rather than committing from its stale pre-lock read.
{
  const runtime = buildRuntime();
  const action = correction(92, 'REPLACE');
  const request = payload(92, [action]);
  const originalCanonical = JSON.stringify(rows(runtime, 'ConsumptionEvents'));
  const originalCompatibility = JSON.stringify(rows(runtime, 'Form Responses 1'));
  assert.equal(configValue(runtime, 'CONSUMPTION_CORRECTION_WRITES_ENABLED'), true);

  runtime.lock.beforeNextTryLock(() => {
    const disabled = runtime.context.disableConsumptionCorrectionWrites();
    assert.equal(disabled.writesEnabled, false);
  });
  const response = post(runtime, request);

  assert.equal(response.success, true, JSON.stringify(response));
  assert.equal(response.allAccepted, false, JSON.stringify(response));
  assert.equal(response.correctionVersion, 1, JSON.stringify(response));
  assert.equal(response.correctionWritesEnabled, false, JSON.stringify(response));
  assert.deepEqual(response.acknowledgedConsumptionCorrections, []);
  assert.deepEqual(
    response.rejectedConsumptionCorrections.map(item => ({
      actionId: item.actionId,
      targetEventId: item.targetEventId,
      errorCode: item.errorCode,
      currentHeadId: item.currentHeadId,
    })),
    [{
      actionId: action.actionId,
      targetEventId: EVENT_ID,
      errorCode: 'CORRECTION_WRITES_DISABLED',
      currentHeadId: null,
    }],
    JSON.stringify(response),
  );
  assert.equal(dataRows(runtime, 'ConsumptionEventCorrections').length, 0);
  assert.equal(JSON.stringify(rows(runtime, 'ConsumptionEvents')), originalCanonical);
  assert.equal(JSON.stringify(rows(runtime, 'Form Responses 1')), originalCompatibility);
  assert.equal(configValue(runtime, 'CONSUMPTION_CORRECTION_WRITES_ENABLED'), false);
  assert.deepEqual(
    runtime.audit.locks
      .filter(entry => entry.operation === 'tryLock')
      .map(entry => entry.acquired),
    [true, true],
    'disable must acquire/release before the request acquires the same lock',
  );
  assert.equal(
    runtime.audit.locks.filter(entry => entry.operation === 'releaseLock').length,
    2,
  );
}

// A mixed request reports precisely the independently accepted ordinary event
// and the rejected correction. Independent correction targets may share a
// request, but two revisions for one target may not.
{
  const runtime = buildRuntime();
  const ordinary = consumption(24);
  const unknownTarget = deterministicUuid(9999);
  const unknownCorrection = correction(24, 'VOID', {
    targetEventId: unknownTarget,
  });
  const mixed = post(runtime, payload(24, [unknownCorrection], {
    consumptions: [ordinary],
  }));
  assert.equal(mixed.allAccepted, false);
  assert.deepEqual(mixed.acknowledgedConsumptions, [{
    eventId: ordinary.eventId, status: 'committed',
  }]);
  assert.deepEqual(mixed.rejectedConsumptions, []);
  assert.deepEqual(mixed.acknowledgedConsumptionCorrections, []);
  assert.deepEqual(
    mixed.rejectedConsumptionCorrections.map(item => ({
      actionId: item.actionId,
      targetEventId: item.targetEventId,
      errorCode: item.errorCode,
      currentHeadId: item.currentHeadId,
    })),
    [{
      actionId: unknownCorrection.actionId,
      targetEventId: unknownTarget,
      errorCode: 'UNKNOWN_EVENT',
      currentHeadId: null,
    }],
  );

  const secondEventId = deterministicUuid(1004);
  const secondEvent = baseEvent();
  secondEvent['Event UUID'] = secondEventId;
  secondEvent.Timestamp = new Date('2026-07-12T14:15:00Z');
  secondEvent['Local Date'] = '2026-07-12';
  secondEvent.Finished = false;
  runtimeRowsAppend(runtime, 'ConsumptionEvents', EVENT_HEADERS, secondEvent);
  const firstTarget = correction(25, 'REPLACE');
  const secondTarget = correction(26, 'REPLACE', {
    targetEventId: secondEventId,
  });
  const independent = post(runtime, payload(25, [firstTarget, secondTarget]));
  assert.equal(independent.allAccepted, true, JSON.stringify(independent));
  assert.deepEqual(
    independent.acknowledgedConsumptionCorrections,
    [firstTarget, secondTarget].map(item => ({
      actionId: item.actionId, targetEventId: item.targetEventId,
      revision: 1, status: 'committed',
    })),
  );
  assert.deepEqual(independent.rejectedConsumptionCorrections, []);

  const duplicateTargetRuntime = buildRuntime();
  const firstForTarget = correction(27, 'REPLACE');
  const secondForTarget = correction(28, 'VOID');
  const duplicateTarget = post(
    duplicateTargetRuntime,
    payload(26, [firstForTarget, secondForTarget]),
  );
  assert.equal(duplicateTarget.allAccepted, false);
  assert.deepEqual(duplicateTarget.acknowledgedConsumptionCorrections, [{
    actionId: firstForTarget.actionId,
    targetEventId: EVENT_ID,
    revision: 1,
    status: 'committed',
  }]);
  assert.deepEqual(
    duplicateTarget.rejectedConsumptionCorrections.map(item => ({
      actionId: item.actionId,
      targetEventId: item.targetEventId,
      errorCode: item.errorCode,
    })),
    [{
      actionId: secondForTarget.actionId,
      targetEventId: EVENT_ID,
      errorCode: 'INVALID_ITEM',
    }],
  );
}

// Provisioning creates a disabled capability. A clean reconciliation and no
// pending apply are both required to enable it; emergency disable still returns
// duplicate acknowledgement for an already durable correction.
{
  const runtime = buildRuntime({
    correctionVersion: 0,
    writesEnabled: false,
    purchasesTypedColumns: true,
    purchasesFrozenRows: 1,
    purchasesTables: [{ tableId: 'typed-purchases' }],
  });
  const action = correction(29, 'REPLACE');
  const beforeProvision = post(runtime, payload(27, [action]));
  assert.equal(
    beforeProvision.rejectedConsumptionCorrections[0].errorCode,
    'BACKEND_UPDATE_REQUIRED',
  );

  const provisioned = runtime.context.provisionConsumptionCorrections();
  assert.equal(provisioned.correctionVersion, 1);
  assert.equal(provisioned.writesEnabled, false);
  assert.equal(provisioned.differences.length, 0);
  assert.equal(
    runtime.audit.structural.some(entry => (
      entry.sheet === 'Purchases' &&
      ['setBackground', 'setBackgrounds', 'setFontColor', 'setFontWeight']
        .includes(entry.operation)
    )),
    false,
    'provision must not format the typed Purchases table header',
  );
  assert.equal(
    runtime.audit.structural.some(entry => (
      entry.sheet === 'Purchases' && entry.operation === 'setFrozenRows'
    )),
    false,
    'provision must not reset frozen rows owned by the typed Purchases table',
  );
  assert.equal(
    runtime.peekSheet('Purchases').getFrozenRows(),
    1,
    'provision must preserve the typed Purchases table frozen-row state',
  );
  assert.equal(
    runtime.audit.structural.filter(entry => (
      entry.sheet === 'Purchases' && entry.operation === 'setDataValidation'
    )).length,
    3,
    'provision must retain Purchases data validations',
  );
  assert.deepEqual(
    runtime.peekSheet('Purchases').protections.map(protection => protection.getDescription()).sort(),
    [
      'Cannsheet display identities',
      'Cannsheet immutable identities',
      'Cannsheet reliability headers',
    ],
    'provision must retain Purchases warning protections',
  );
  assert.equal(
    runtime.peekSheet('Purchases').protections.every(protection => protection.warningOnly),
    true,
    'Purchases protections must remain warning-only',
  );
  assert.equal(
    runtime.audit.structural.some(entry => (
      entry.sheet === 'ConsumptionEvents' &&
      entry.operation === 'setBackground'
    )),
    true,
    'provision must retain header safety formatting on ordinary existing sheets',
  );
  assert.equal(
    runtime.audit.structural.some(entry => (
      entry.sheet === 'ConsumptionEvents' && entry.operation === 'setFrozenRows'
    )),
    true,
    'provision must retain frozen headers on ordinary existing sheets',
  );
  assert.equal(
    runtime.peekSheet('ConsumptionEvents').getFrozenRows(),
    1,
    'provision must freeze ordinary existing sheets',
  );
  const provisionedButDisabled = post(runtime, payload(28, [action]));
  assert.equal(
    provisionedButDisabled.rejectedConsumptionCorrections[0].errorCode,
    'CORRECTION_WRITES_DISABLED',
  );

  setConfigValue(runtime, 'PENDING_APPLY_KEY', deterministicUuid(50000));
  assert.throws(
    () => runtime.context.enableConsumptionCorrectionWrites(),
    /CORRECTION_ENABLE_BLOCKED: recoverable apply is pending/,
  );
  setConfigValue(runtime, 'PENDING_APPLY_KEY', '');
  const journalRows = rows(runtime, 'SyncApplyJournal');
  const journalStateIndex = headerIndex(journalRows, 'State');
  runtime.peekSheet('SyncApplyJournal')
    .getRange(2, journalStateIndex + 1)
    .setValue('CORE_COMMITTED');
  assert.throws(
    () => runtime.context.enableConsumptionCorrectionWrites(),
    /CORRECTION_ENABLE_BLOCKED: recoverable sync apply reconciliation must be clean;.*INCOMPLETE_JOURNAL/,
  );
  assert.equal(configValue(runtime, 'CONSUMPTION_CORRECTION_WRITES_ENABLED'), false);
  runtime.peekSheet('SyncApplyJournal')
    .getRange(2, journalStateIndex + 1)
    .setValue('COMPLETE');
  const enabled = runtime.context.enableConsumptionCorrectionWrites();
  assert.equal(enabled.writesEnabled, true);
  const committed = post(runtime, payload(29, [action]));
  assert.deepEqual(committed.acknowledgedConsumptionCorrections, [{
    actionId: action.actionId, targetEventId: EVENT_ID, revision: 1,
    status: 'committed',
  }]);

  const disabled = runtime.context.disableConsumptionCorrectionWrites();
  assert.equal(disabled.writesEnabled, false);
  const duplicateWhileDisabled = post(runtime, payload(30, [action]));
  assert.deepEqual(duplicateWhileDisabled.acknowledgedConsumptionCorrections, [{
    actionId: action.actionId, targetEventId: EVENT_ID, revision: 1,
    status: 'duplicate',
  }]);
  assert.deepEqual(duplicateWhileDisabled.rejectedConsumptionCorrections, []);

  const noRecovery = buildRuntime({ recoverableApplyVersion: 0 });
  const recoveryUnavailable = post(
    noRecovery,
    payload(31, [correction(31, 'VOID')]),
  );
  assert.equal(
    recoveryUnavailable.rejectedConsumptionCorrections[0].errorCode,
    'BACKEND_UPDATE_REQUIRED',
  );
}

// A table remains untouched even when it is unfrozen: table ownership, rather
// than frozen-row count, determines whether header operations are safe.
{
  const runtime = buildRuntime({
    correctionVersion: 0,
    writesEnabled: false,
    purchasesFrozenRows: 0,
    purchasesTables: [{ tableId: 'unfrozen-purchases-table' }],
  });
  const provisioned = runtime.context.provisionConsumptionCorrections();
  assert.equal(provisioned.writesEnabled, false);
  assert.equal(runtime.peekSheet('Purchases').getFrozenRows(), 0);
  assert.equal(
    runtime.audit.structural.some(entry => (
      entry.sheet === 'Purchases' &&
      ['setBackground', 'setFontColor', 'setFontWeight', 'setFrozenRows']
        .includes(entry.operation)
    )),
    false,
    'provision must not rewrite table-owned Purchases header operations',
  );
}

// Non-table Purchases sheets retain the ordinary header safety setup.
{
  const runtime = buildRuntime({
    correctionVersion: 0,
    writesEnabled: false,
    purchasesFrozenRows: 0,
    sheetsMetadata: [{ properties: { sheetId: 1 } }],
  });
  const provisioned = runtime.context.provisionConsumptionCorrections();
  assert.equal(provisioned.writesEnabled, false);
  assert.equal(
    runtime.audit.structural.some(entry => (
      entry.sheet === 'Purchases' && entry.operation === 'setFrozenRows'
    )),
    true,
    'provision must freeze an ordinary unfrozen Purchases sheet',
  );
  assert.equal(runtime.peekSheet('Purchases').getFrozenRows(), 1);
  assert.equal(
    runtime.audit.structural.some(entry => (
      entry.sheet === 'Purchases' && entry.operation === 'setBackground'
    )),
    true,
    'provision must style an ordinary Purchases header',
  );
}

// Missing table metadata is fail-closed before additive correction schema,
// configuration, formatting, validation, or protection changes can occur.
[
  { options: { sheetsGetAvailable: false }, label: 'missing Advanced Sheets get' },
  { options: { sheetsGetError: 'metadata API failed' }, label: 'metadata API failure' },
  { options: { sheetsMetadata: [] }, label: 'missing Purchases metadata' },
  {
    options: {
      sheetsMetadata: [
        { properties: { sheetId: 1 }, tables: [] },
        { properties: { sheetId: 1 }, tables: [] },
      ],
    },
    label: 'duplicate Purchases metadata',
  },
].forEach(({ options, label }) => {
  const runtime = buildRuntime(options);
  assert.throws(
    () => runtime.context.provisionConsumptionCorrections(),
    /PURCHASES_TABLE_METADATA_UNAVAILABLE/,
    label,
  );
  assert.equal(
    runtime.audit.structural.length,
    0,
    `${label} must not mutate correction provisioning or sheet safety state`,
  );
});

// Table metadata is fetched with the narrow field mask needed for the exact
// Purchases sheet identity and table presence.
{
  const runtime = buildRuntime({
    correctionVersion: 0,
    writesEnabled: false,
    purchasesTables: [{ tableId: 'metadata-audit' }],
  });
  runtime.context.provisionConsumptionCorrections();
  const metadataRequest = runtime.audit.services.find(entry => (
    entry.service === 'Sheets' && entry.method === 'Spreadsheets.get'
  ));
  assert.equal(metadataRequest.service, 'Sheets');
  assert.equal(metadataRequest.method, 'Spreadsheets.get');
  assert.equal(metadataRequest.spreadsheetId, 'correction-sheet');
  assert.equal(metadataRequest.fields, 'sheets(properties(sheetId),tables(tableId))');
}

// A correction changes the history snapshot hash, so a page cursor captured
// before the revision is deliberately stale rather than mixing revisions.
{
  const runtime = buildRuntime();
  const laterEvent = baseEvent();
  laterEvent['Event UUID'] = deterministicUuid(1003);
  laterEvent.Timestamp = new Date('2026-07-12T14:15:00Z');
  laterEvent['Local Date'] = '2026-07-12';
  laterEvent.Finished = false;
  runtimeRowsAppend(runtime, 'ConsumptionEvents', EVENT_HEADERS, laterEvent);
  const firstPage = get(runtime, { resource: 'history', analyticsVersion: 2, environment: 'SANDBOX', limit: 1 });
  assert.ok(firstPage.page.nextCursor);
  post(runtime, payload(30, [correction(30, 'REPLACE')]));
  const stalePage = get(runtime, { resource: 'history', analyticsVersion: 2, environment: 'SANDBOX', limit: 10, cursor: firstPage.page.nextCursor });
  assert.equal(stalePage.success, false);
  assert.equal(stalePage.errorCode, 'CURSOR_STALE');

  const voidRuntime = buildRuntime();
  const laterVoidEvent = baseEvent();
  laterVoidEvent['Event UUID'] = deterministicUuid(1005);
  laterVoidEvent.Timestamp = new Date('2026-07-12T14:15:00Z');
  laterVoidEvent['Local Date'] = '2026-07-12';
  laterVoidEvent.Finished = false;
  runtimeRowsAppend(voidRuntime, 'ConsumptionEvents', EVENT_HEADERS, laterVoidEvent);
  const voidPage = get(voidRuntime, {
    resource: 'history', analyticsVersion: 2, environment: 'SANDBOX', limit: 1,
  });
  assert.ok(voidPage.page.nextCursor);
  post(voidRuntime, payload(32, [correction(32, 'VOID')]));
  const staleAfterVoid = get(voidRuntime, {
    resource: 'history', analyticsVersion: 2, environment: 'SANDBOX', limit: 10,
    cursor: voidPage.page.nextCursor,
  });
  assert.equal(staleAfterVoid.success, false);
  assert.equal(staleAfterVoid.errorCode, 'CURSOR_STALE');
}

// Reopen is accepted only when the source proves that the target event is the
// sole finish evidence; otherwise it must be rejected without a revision row.
{
  const safeRuntime = buildRuntime();
  const safe = correction(40, 'VOID', { reopenProduct: true, reason: 'reopen safely' });
  const safeResponse = post(safeRuntime, payload(40, [safe]));
  assert.equal(safeResponse.acknowledgedConsumptionCorrections[0].status, 'committed');
  assert.equal(productValue(safeRuntime, '*P1', 'Finished'), 2);

  const unsafeRuntime = buildRuntime();
  const extra = baseEvent();
  extra['Event UUID'] = deterministicUuid(1002);
  extra.Timestamp = new Date('2026-07-12T14:15:00Z');
  extra['Local Date'] = '2026-07-12';
  runtimeRowsAppend(unsafeRuntime, 'ConsumptionEvents', EVENT_HEADERS, extra);
  const unsafe = correction(41, 'VOID', { reopenProduct: true, reason: 'must remain finished' });
  const unsafeResponse = post(unsafeRuntime, payload(41, [unsafe]));
  assert.equal(unsafeResponse.rejectedConsumptionCorrections[0].errorCode, 'REOPEN_NOT_SAFE');
  assert.equal(dataRows(unsafeRuntime, 'ConsumptionEventCorrections').length, 0);

  const finishActionRuntime = buildRuntime();
  runtimeRowsAppend(
    finishActionRuntime,
    'SyncApplyJournal',
    JOURNAL_HEADERS,
    {
      'Apply UUID': deterministicUuid(50001),
      Kind: 'ANDROID_V2',
      'API Version': 2,
      'Request UUID': deterministicUuid(50002),
      State: 'COMPLETE',
      'Core Committed At': new Date('2026-07-15T14:00:00Z'),
      'Completed At': new Date('2026-07-15T14:00:01Z'),
      'Finalization JSON': JSON.stringify({
        finishActions: [{
          actionId: deterministicUuid(50003),
          productUuid: P1_UUID,
          legacyProductId: '*P1',
          timestamp: '2026-07-15T14:00:00.000Z',
        }],
      }),
      'Response JSON': '{}',
    }
  );
  const finishActionBlocked = post(
    finishActionRuntime,
    payload(42, [correction(42, 'VOID', { reopenProduct: true })])
  );
  assert.equal(
    finishActionBlocked.rejectedConsumptionCorrections[0].errorCode,
    'REOPEN_NOT_SAFE'
  );

  const manualRuntime = buildRuntime();
  setProductValue(
    manualRuntime,
    '*P1',
    'Finished At',
    new Date('2026-07-19T14:15:00Z')
  );
  const manualBlocked = post(
    manualRuntime,
    payload(43, [correction(43, 'VOID', { reopenProduct: true })])
  );
  assert.equal(
    manualBlocked.rejectedConsumptionCorrections[0].errorCode,
    'REOPEN_NOT_SAFE'
  );

  const ambiguousRuntime = buildRuntime({ eventProvenance: false });
  const ambiguousBlocked = post(
    ambiguousRuntime,
    payload(44, [correction(44, 'VOID', { reopenProduct: true })])
  );
  assert.equal(
    ambiguousBlocked.rejectedConsumptionCorrections[0].errorCode,
    'REOPEN_NOT_SAFE'
  );
}

// Only the final finish disposition in a revision chain may affect projections.
// Restoring a finish clears an older Reopen, and a later explicit false choice
// must keep the product finished.
{
  const runtime = buildRuntime();
  const firstVoid = correction(50, 'VOID', { reopenProduct: true });
  post(runtime, payload(50, [firstVoid]));
  const restore = correction(51, 'RESTORE', {
    expectedCorrectionHeadId: firstVoid.actionId,
  });
  post(runtime, payload(51, [restore]));
  const finalReplace = correction(52, 'REPLACE', {
    expectedCorrectionHeadId: restore.actionId,
    reopenProduct: false,
    reason: 'keep product finished',
  });
  const response = post(runtime, payload(52, [finalReplace]));
  assert.equal(response.acknowledgedConsumptionCorrections[0].revision, 3);
  assert.equal(productValue(runtime, '*P1', 'Finished'), 1);
  assert.equal(productValue(runtime, '*P1', 'Uses'), 0);
  assert.equal(
    runtime.context.reconcileConsumptionCorrections().differences.length,
    0
  );
}

// A Reopen on one entry cannot undo an earlier explicit choice to retain the
// same product's finish after removing a different entry.
{
  const runtime = buildRuntime();
  const secondEventId = deterministicUuid(1051);
  const secondFinish = baseEvent();
  secondFinish['Event UUID'] = secondEventId;
  secondFinish.Timestamp = new Date('2026-07-12T14:15:00Z');
  secondFinish['Local Date'] = '2026-07-12';
  runtimeRowsAppend(runtime, 'ConsumptionEvents', EVENT_HEADERS, secondFinish);
  runtimeRowsAppend(runtime, 'SyncApplyJournal', JOURNAL_HEADERS, {
    'Apply UUID': deterministicUuid(5051),
    Kind: 'ANDROID_V2',
    'API Version': 2,
    'Request UUID': deterministicUuid(5052),
    State: 'COMPLETE',
    'Core Committed At': new Date('2026-07-12T14:15:00Z'),
    'Completed At': new Date('2026-07-12T14:15:01Z'),
    'Finalization JSON': JSON.stringify({
      eventIds: [secondEventId],
      correctionActions: [],
      finishActions: [],
    }),
    'Response JSON': '{}',
  });

  const retainFirstFinish = correction(53, 'VOID', {
    reopenProduct: false,
    reason: 'keep finished after first void',
  });
  const retained = post(runtime, payload(53, [retainFirstFinish]));
  assert.equal(
    retained.acknowledgedConsumptionCorrections[0].status,
    'committed'
  );
  const reopenSecondFinish = correction(54, 'VOID', {
    targetEventId: secondEventId,
    reopenProduct: true,
    reason: 'must not override retained finish',
  });
  const blocked = post(runtime, payload(54, [reopenSecondFinish]));
  assert.equal(
    blocked.rejectedConsumptionCorrections[0].errorCode,
    'REOPEN_NOT_SAFE'
  );
  assert.equal(productValue(runtime, '*P1', 'Finished'), 1);
  assert.equal(dataRows(runtime, 'ConsumptionEventCorrections').length, 1);
}

// Correction append, product projections, journal marker, and pending pointer
// share the recoverable core transaction. Every crash boundary therefore
// retries to exactly one revision and one final ledger row.
[
  { stage: FAULTS.PRE_CORE, ordinal: 60, durableAtFailure: false },
  { stage: FAULTS.CORE_COMMITTED, ordinal: 61, durableAtFailure: true },
  { stage: FAULTS.FINALIZATION, ordinal: 62, durableAtFailure: true },
  { stage: FAULTS.POST_COMPLETE, ordinal: 63, durableAtFailure: true },
].forEach(testCase => {
  const runtime = buildRuntime();
  const action = correction(testCase.ordinal, 'REPLACE', {
    reopenProduct: true,
    reason: `fault ${testCase.stage}`,
  });
  const request = payload(testCase.ordinal, [action]);
  runtime.context.setSandboxSyncApplyFault(testCase.stage);
  assertInternalFailure(post(runtime, request));

  const correctionsAtFailure = dataRows(
    runtime,
    'ConsumptionEventCorrections'
  ).length;
  assert.equal(
    correctionsAtFailure,
    testCase.durableAtFailure ? 1 : 0,
    testCase.stage
  );
  if (testCase.durableAtFailure) {
    assert.equal(productValue(runtime, '*P1', 'Uses'), 0, testCase.stage);
    assert.equal(productValue(runtime, '*P2', 'Uses'), 2.5, testCase.stage);
  } else {
    assert.equal(productValue(runtime, '*P1', 'Uses'), 1, testCase.stage);
    assert.equal(productValue(runtime, '*P2', 'Uses'), 0, testCase.stage);
  }

  const retry = post(runtime, request);
  assert.equal(retry.success, true, JSON.stringify(retry));
  assert.equal(
    retry.acknowledgedConsumptionCorrections[0].status,
    testCase.durableAtFailure ? 'duplicate' : 'committed',
    testCase.stage
  );
  assertCorrectionProjection(runtime);
  assert.equal(dataRows(runtime, 'SyncApplyJournal').length, 2);
  assert.equal(dataRows(runtime, 'SyncLedger').length, 1);
  assert.equal(configValue(runtime, 'PENDING_APPLY_KEY'), '');
  const journalRows = rows(runtime, 'SyncApplyJournal');
  assert.equal(
    journalRows[1][headerIndex(journalRows, 'State')],
    'COMPLETE',
    testCase.stage
  );
});

// Production-shaped local acceptance for the correction resolver. The live
// sandbox remains the authoritative latency gate; this fixture guards against
// accidental superlinear work before a deployment is considered.
{
  const runtime = buildRuntime();
  const eventCount = 3600;
  const correctionCount = 600;
  for (let index = 1; index < eventCount; index += 1) {
    const timestamp = new Date(
      Date.UTC(2026, 6, 1, 12, 0, 0) + index * 60 * 1000
    );
    runtimeRowsAppend(runtime, 'ConsumptionEvents', EVENT_HEADERS, {
      'Event UUID': deterministicUuid(600000 + index),
      Timestamp: timestamp,
      'Local Date': '',
      'Local Time': '',
      'Product UUID': P1_UUID,
      'Legacy Product ID': '*P1',
      Uses: 0.25,
      'Weight Code': 'A',
      Finished: false,
      Source: 'ANDROID_V2',
      'Request UUID': deterministicUuid(700000 + index),
      'Legacy Source Sheet': '',
      'Legacy Source Row': '',
    });
  }
  for (let index = 1; index <= correctionCount; index += 1) {
    runtimeRowsAppend(
      runtime,
      'ConsumptionEventCorrections',
      CORRECTION_HEADERS,
      {
        'Correction UUID': deterministicUuid(800000 + index),
        'Target Event UUID': deterministicUuid(600000 + index),
        'Supersedes Correction UUID': '',
        Revision: 1,
        Operation: 'REPLACE',
        'Replacement Timestamp': new Date('2026-07-05T12:00:00Z'),
        'Replacement Local Date': '2026-07-05',
        'Replacement Local Time': '08:00:00',
        'Replacement Product UUID': P1_UUID,
        'Replacement Legacy Product ID': '*P1',
        'Replacement Uses': 0.5,
        'Replacement Weight Code': 'A',
        'Replacement Finished': false,
        'Reopen Product': false,
        Reason: '',
        'Request UUID': deterministicUuid(900000 + index),
        Source: 'ANDROID_V2',
        'Created At': new Date(
          Date.UTC(2026, 6, 20, 12, 0, 0) + index * 1000
        ),
      }
    );
  }
  runtime.resetAudit();
  const insightStarted = performance.now();
  const insightResponse = get(runtime, {
    resource: 'insights',
    analyticsVersion: 2,
    environment: 'SANDBOX',
    from: '2026-07-01',
    to: '2026-07-20',
  });
  const insightMs = performance.now() - insightStarted;
  assert.equal(insightResponse.success, true, JSON.stringify(insightResponse));
  assert.equal(insightResponse.overview.logCount, eventCount);
  assert.equal(
    insightResponse.products.find(
      product => product.productId === '*P1'
    ).allTime.quantity,
    1050.75
  );

  runtime.resetAudit();
  const historyStarted = performance.now();
  const historyResponse = get(runtime, {
    resource: 'history',
    analyticsVersion: 2,
    includeAudit: true,
    environment: 'SANDBOX',
    limit: 200,
  });
  const historyMs = performance.now() - historyStarted;
  assert.equal(historyResponse.success, true, JSON.stringify(historyResponse));
  assert.equal(historyResponse.events.length, 200);
  console.log(JSON.stringify({
    type: 'backend_corrections_local_scale',
    events: eventCount,
    corrections: correctionCount,
    insightsMs: Math.round(insightMs),
    historyMs: Math.round(historyMs),
  }));
}

function runtimeRowsAppend(runtime, sheetName, headers, record) {
  runtime.peekSheet(sheetName).appendRow(headers.map(header => record[header] === undefined ? '' : record[header]));
}

console.log('backend correction tests passed');
