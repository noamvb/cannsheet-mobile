'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const {
  buildConfigRows,
  createAppsScriptRuntime,
  deterministicUuid,
  makeSheetRows,
} = require('./fake_apps_script_runtime');

const source = fs.readFileSync('backend_additions.gs', 'utf8');

const LEGACY_PURCHASE_HEADERS = [
  'Date', 'Type', 'Product name', 'Pre-tax cost', 'THC%', 'Grams',
  'Borrowed', 'Finished', 'Product ID', 'Uses', 'Post-tax', 'Final cost',
  'Most recent use', 'Product UUID', 'Client Action UUID', 'Created At',
  'Finished At',
];
const PURCHASE_HEADERS = LEGACY_PURCHASE_HEADERS.concat(['Last quantity']);
const RESPONSE_HEADERS = [
  'Timestamp', 'Product', 'Uses', 'Date', 'Time', 'Weight code',
  'Mark as Finished?',
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
const REPORT_HEADERS = [
  'Type', 'Source Sheet', 'Source Row', 'Product ID', 'Detail', 'Recorded At',
];

function purchaseRecords() {
  return [
    {
      Date: '2025-01-01', Type: 'P', 'Product name': 'Product one',
      'Pre-tax cost': 10, 'THC%': 20, Grams: 3.5, Borrowed: 0,
      Finished: 0, 'Product ID': '*P1', Uses: 2, 'Post-tax': false,
      'Final cost': 11.3, 'Most recent use': new Date('2025-01-01T12:00:00Z'),
      'Product UUID': deterministicUuid(101), 'Client Action UUID': deterministicUuid(201),
      'Created At': new Date('2025-01-01T09:00:00Z'), 'Finished At': '',
      'Last quantity': 0.5,
    },
    {
      Date: '2025-01-02', Type: 'E', 'Product name': 'Product two',
      'Pre-tax cost': 20, 'THC%': 10, Grams: 1, Borrowed: 0,
      Finished: 2, 'Product ID': '*E1', Uses: 0, 'Post-tax': true,
      'Final cost': 20, 'Most recent use': '',
      'Product UUID': deterministicUuid(102), 'Client Action UUID': deterministicUuid(202),
      'Created At': new Date('2025-01-02T09:00:00Z'), 'Finished At': '',
      'Last quantity': '',
    },
  ];
}

function eventRecords(count = 0) {
  return Array.from({ length: count }, (_, index) => ({
    'Event UUID': deterministicUuid(1000 + index),
    Timestamp: new Date(2025, 0, 1, 8, index % 60, 0),
    'Local Date': '2025-01-01',
    'Local Time': `08:${String(index % 60).padStart(2, '0')}:00`,
    'Product UUID': deterministicUuid(index % 2 ? 102 : 101),
    'Legacy Product ID': index % 2 ? '*E1' : '*P1',
    Uses: 0.25,
    'Weight Code': '',
    Finished: false,
    Source: 'TEST',
    'Request UUID': '',
    'Legacy Source Sheet': 'Form Responses 1',
    'Legacy Source Row': index + 2,
  }));
}

function interactionEvent({
  ordinal,
  productId,
  productUuid,
  timestamp,
  uses,
}) {
  return {
    'Event UUID': deterministicUuid(300000 + ordinal),
    Timestamp: timestamp,
    'Local Date': timestamp.toISOString().slice(0, 10),
    'Local Time': timestamp.toISOString().slice(11, 19),
    'Product UUID': productUuid,
    'Legacy Product ID': productId,
    Uses: uses,
    'Weight Code': '',
    Finished: false,
    Source: 'TEST',
    'Request UUID': deterministicUuid(400000 + ordinal),
    'Legacy Source Sheet': 'Form Responses 1',
    'Legacy Source Row': ordinal + 2,
  };
}

function productionSizedInteractionFixture(productCount = 400, eventCount = 3600) {
  const purchases = Array.from({ length: productCount }, (_, index) => ({
    Date: '2025-01-01',
    Type: 'P',
    'Product name': `Performance product ${index + 1}`,
    'Pre-tax cost': 10 + (index % 10),
    'THC%': 10 + (index % 20),
    Grams: 3.5,
    Borrowed: 0,
    Finished: 0,
    'Product ID': `*P${index + 1}`,
    Uses: 9,
    'Post-tax': false,
    'Final cost': 11.3 + (index % 10),
    'Most recent use': '',
    'Product UUID': deterministicUuid(100000 + index),
    'Client Action UUID': deterministicUuid(200000 + index),
    'Created At': new Date('2025-01-01T09:00:00Z'),
    'Finished At': '',
    'Last quantity': '',
  }));
  const latest = Array(productCount).fill(null);
  const events = Array.from({ length: eventCount }, (_, index) => {
    const productIndex = index % productCount;
    const round = Math.floor(index / productCount);
    const timestamp = new Date(Date.UTC(2025, 0, 1 + round, 12, productIndex % 60, 0));
    const uses = round === 8 && productIndex % 37 === 0
      ? 0
      : ((productIndex + round) % 8) / 4;
    latest[productIndex] = { timestamp, uses };
    return interactionEvent({
      ordinal: index,
      productId: purchases[productIndex]['Product ID'],
      productUuid: purchases[productIndex]['Product UUID'],
      timestamp,
      uses,
    });
  });
  purchases.forEach((purchase, index) => {
    purchase['Most recent use'] = latest[index].timestamp;
    purchase['Last quantity'] = latest[index].uses;
  });
  return { purchases, events };
}

function buildRuntime(options = {}) {
  const eventCount = options.eventCount || 0;
  const summaryReady = options.summaryReady === true;
  const purchaseHeaders = summaryReady ? PURCHASE_HEADERS : LEGACY_PURCHASE_HEADERS;
  const purchases = options.purchases || purchaseRecords();
  const events = options.events || eventRecords(eventCount);
  const runtime = createAppsScriptRuntime({
    environment: 'SANDBOX',
    spreadsheetId: 'sandbox-sheet',
    formId: 'sandbox-form',
    sheets: {
      Purchases: {
        rows: makeSheetRows(purchaseHeaders, purchases),
        maxColumns: options.purchaseMaxColumns,
      },
      'Form Responses 1': { rows: [RESPONSE_HEADERS] },
      ConsumptionEvents: { rows: makeSheetRows(EVENT_HEADERS, events) },
      SyncLedger: { rows: [LEDGER_HEADERS] },
      Config: {
        rows: buildConfigRows({
          environment: 'SANDBOX',
          schemaVersion: options.schemaVersion ?? 2,
          interactionSummaryVersion: summaryReady ? 1 : options.interactionSummaryVersion,
        }),
      },
      MigrationReport: { rows: [REPORT_HEADERS] },
    },
  });
  runtime.loadSource(source, { filename: 'backend_additions.gs' });
  runtime.resetAudit();
  return runtime;
}

function post(runtime, payload) {
  return runtime.parseTextOutput(runtime.context.doPost({
    postData: { contents: typeof payload === 'string' ? payload : JSON.stringify(payload) },
  }));
}

function get(runtime) {
  return runtime.parseTextOutput(runtime.context.doGet());
}

function v2Payload(requestOrdinal, overrides = {}) {
  return Object.assign({
    apiVersion: 2,
    requestId: deterministicUuid(50000 + requestOrdinal),
    environment: 'SANDBOX',
    purchases: [],
    consumptions: [],
  }, overrides);
}

function sheetRows(runtime, name) {
  return runtime.peekSheet(name).snapshot().rows;
}

function headerIndex(headers, name) {
  const index = headers.indexOf(name);
  assert.notEqual(index, -1, `missing test header ${name}`);
  return index;
}

function populatedDataRows(runtime, name) {
  return sheetRows(runtime, name).slice(1).filter(row => row.some(value => value !== '' && value != null));
}

function productRow(runtime, productId) {
  const rows = sheetRows(runtime, 'Purchases');
  const idColumn = headerIndex(rows[0], 'Product ID');
  return rows.slice(1).find(row => String(row[idColumn]).trim() === productId);
}

function configValue(runtime, key) {
  const match = populatedDataRows(runtime, 'Config').find(row => row[0] === key);
  return match ? match[1] : undefined;
}

function dataReads(runtime, sheetName) {
  return runtime.audit.reads.filter(entry => entry.sheet === sheetName && entry.row >= 2);
}

function writes(runtime, sheetName) {
  return runtime.audit.writes.filter(entry => entry.sheet === sheetName);
}

// A production-sized event table proves that the normal POST lookup is limited
// to the single Event UUID column and that only the affected product cells move.
const normalRuntime = buildRuntime({ eventCount: 3600 });
const normalEventId = deterministicUuid(90001);
const normalPayload = v2Payload(1, {
  consumptions: [{
    eventId: normalEventId,
    date: '2025-04-01',
    time: '10:30:00',
    productId: '*P1',
    productUuid: deterministicUuid(101),
    uses: 1,
    isFinished: false,
    weightCode: 'test',
  }],
});
const normalResponse = post(normalRuntime, normalPayload);
assert.equal(normalResponse.success, true);
assert.equal(normalResponse.allAccepted, true);
assert.deepEqual(normalResponse.acknowledgedConsumptions, [{ eventId: normalEventId, status: 'committed' }]);
assert.equal(populatedDataRows(normalRuntime, 'Form Responses 1').length, 1);
assert.equal(populatedDataRows(normalRuntime, 'ConsumptionEvents').length, 3601);
assert.equal(populatedDataRows(normalRuntime, 'SyncLedger').length, 1);
const normalProduct = productRow(normalRuntime, '*P1');
assert.equal(normalProduct[headerIndex(PURCHASE_HEADERS, 'Uses')], 3);
assert.equal(normalProduct[headerIndex(PURCHASE_HEADERS, 'Finished')], 0);
assert.equal(
  Number(normalProduct[headerIndex(PURCHASE_HEADERS, 'Most recent use')]),
  Date.parse('2025-04-01T10:30:00'),
);

const eventDataReads = dataReads(normalRuntime, 'ConsumptionEvents');
assert.equal(eventDataReads.length, 0);
const eventFinders = normalRuntime.audit.finders.filter(entry => entry.sheet === 'ConsumptionEvents');
assert.equal(eventFinders.length, 1);
assert.equal(eventFinders[0].column, 1);
assert.equal(eventFinders[0].numColumns, 1);
assert.equal(eventFinders[0].numRows, 3600);
assert.equal(eventFinders[0].query, normalEventId);
assert.equal(eventFinders[0].matchEntireCell, true);
assert.equal(eventFinders[0].useRegularExpression, false);
assert.equal(dataReads(normalRuntime, 'SyncLedger').length, 0);
const ledgerFinders = normalRuntime.audit.finders.filter(entry => entry.sheet === 'SyncLedger');
assert.equal(ledgerFinders.length, 1);
assert.equal(ledgerFinders[0].row, 2);
assert.equal(ledgerFinders[0].column, 1);
assert.equal(ledgerFinders[0].numColumns, 1);
assert.equal(ledgerFinders[0].query, normalPayload.requestId);
assert.equal(ledgerFinders[0].matchEntireCell, true);
assert.equal(ledgerFinders[0].matchCase, true);
assert.equal(ledgerFinders[0].useRegularExpression, false);
assert.equal(
  normalRuntime.audit.services.filter(entry => entry.service === 'SpreadsheetApp' && entry.method === 'flush').length,
  1,
);
const purchaseWrites = writes(normalRuntime, 'Purchases');
assert.deepEqual(purchaseWrites.map(entry => entry.row), [2, 2, 2, 2]);
assert.deepEqual(purchaseWrites.map(entry => entry.column), [8, 10, 13, 17]);
assert.equal(purchaseWrites.every(entry => entry.numRows === 1 && entry.numColumns === 1), true);
assert.equal(normalRuntime.audit.structural.length, 0);
assert.equal(
  normalRuntime.audit.services.filter(entry => entry.service === 'SpreadsheetApp' && entry.method === 'openById').length,
  1,
);

// Identical and conflicting-content retries preserve the deployed UUID-only
// duplicate contract: no new history and no second Uses increment.
normalRuntime.resetAudit();
const beforeRetryCounts = {
  responses: populatedDataRows(normalRuntime, 'Form Responses 1').length,
  events: populatedDataRows(normalRuntime, 'ConsumptionEvents').length,
  ledgers: populatedDataRows(normalRuntime, 'SyncLedger').length,
};
const retryResponse = post(normalRuntime, normalPayload);
assert.deepEqual(retryResponse.acknowledgedConsumptions, [{ eventId: normalEventId, status: 'duplicate' }]);
assert.equal(populatedDataRows(normalRuntime, 'Form Responses 1').length, beforeRetryCounts.responses);
assert.equal(populatedDataRows(normalRuntime, 'ConsumptionEvents').length, beforeRetryCounts.events);
assert.equal(populatedDataRows(normalRuntime, 'SyncLedger').length, beforeRetryCounts.ledgers);
const retryLedgerFinders = normalRuntime.audit.finders.filter(entry => entry.sheet === 'SyncLedger');
assert.equal(retryLedgerFinders.length, 1);
assert.equal(retryLedgerFinders[0].query, normalPayload.requestId);
assert.equal(writes(normalRuntime, 'SyncLedger').length, 1);
assert.equal(writes(normalRuntime, 'SyncLedger')[0].row, 2);
assert.equal(
  normalRuntime.audit.services.filter(entry => entry.service === 'SpreadsheetApp' && entry.method === 'flush').length,
  1,
);
assert.equal(productRow(normalRuntime, '*P1')[headerIndex(PURCHASE_HEADERS, 'Uses')], 3);
assert.equal(writes(normalRuntime, 'Purchases').length, 0);

normalRuntime.resetAudit();
const conflictingPayload = v2Payload(2, {
  consumptions: [{
    eventId: normalEventId,
    date: '2035-12-31',
    time: '23:59:59',
    productId: '*E1',
    uses: 99,
    isFinished: true,
  }],
});
const conflictingResponse = post(normalRuntime, conflictingPayload);
assert.deepEqual(conflictingResponse.acknowledgedConsumptions, [{ eventId: normalEventId, status: 'duplicate' }]);
assert.equal(populatedDataRows(normalRuntime, 'Form Responses 1').length, beforeRetryCounts.responses);
assert.equal(populatedDataRows(normalRuntime, 'ConsumptionEvents').length, beforeRetryCounts.events);
assert.equal(populatedDataRows(normalRuntime, 'SyncLedger').length, beforeRetryCounts.ledgers + 1);
assert.equal(productRow(normalRuntime, '*E1')[headerIndex(PURCHASE_HEADERS, 'Uses')], 0);

// Empty v2 changes only the ledger after the header/config assertion.
const emptyRuntime = buildRuntime({ eventCount: 20 });
const emptyResponse = post(emptyRuntime, v2Payload(3));
assert.equal(emptyResponse.success, true);
assert.equal(populatedDataRows(emptyRuntime, 'SyncLedger').length, 1);
assert.equal(dataReads(emptyRuntime, 'Purchases').length, 0);
assert.equal(dataReads(emptyRuntime, 'ConsumptionEvents').length, 0);
assert.equal(dataReads(emptyRuntime, 'Form Responses 1').length, 0);
assert.equal(writes(emptyRuntime, 'Purchases').length, 0);
assert.equal(writes(emptyRuntime, 'ConsumptionEvents').length, 0);
assert.equal(writes(emptyRuntime, 'Form Responses 1').length, 0);
assert.equal(emptyRuntime.audit.structural.length, 0);

// The measured adaptive branch switches to one UUID-column Set read for larger
// batches, avoiding one TextFinder service operation per submitted item.
const largeBatchRuntime = buildRuntime({ eventCount: 20 });
const largeBatchConsumptions = Array.from({ length: 10 }, (_, index) => ({
  eventId: deterministicUuid(93000 + index),
  date: '2025-04-01',
  time: `12:${String(index).padStart(2, '0')}:00`,
  productId: '*P1',
  uses: 1,
  isFinished: false,
}));
const largeBatchResponse = post(largeBatchRuntime, v2Payload(30, {
  consumptions: largeBatchConsumptions,
}));
assert.equal(largeBatchResponse.acknowledgedConsumptions.length, 10);
assert.equal(largeBatchResponse.acknowledgedConsumptions.every(item => item.status === 'committed'), true);
const largeBatchEventReads = dataReads(largeBatchRuntime, 'ConsumptionEvents');
assert.equal(largeBatchEventReads.length, 1);
assert.equal(largeBatchEventReads[0].column, 1);
assert.equal(largeBatchEventReads[0].numColumns, 1);
assert.equal(largeBatchRuntime.audit.finders.filter(entry => entry.sheet === 'ConsumptionEvents').length, 0);
assert.equal(productRow(largeBatchRuntime, '*P1')[headerIndex(PURCHASE_HEADERS, 'Uses')], 12);
assert.equal(writes(largeBatchRuntime, 'Purchases').length, 4);

// Purchase-only does not read or search Event UUID history.
const purchaseRuntime = buildRuntime({ eventCount: 20 });
const purchaseActionId = deterministicUuid(91001);
const purchaseResponse = post(purchaseRuntime, v2Payload(4, {
  purchases: [{
    actionId: purchaseActionId,
    tempId: 'temp-purchase-only',
    date: '2025-04-02',
    type: 'P',
    name: 'Purchase only',
    cost: 12,
    thc: 18,
    grams: 3.5,
    borrowed: 0,
    postTax: false,
  }],
}));
assert.equal(purchaseResponse.acknowledgedPurchases[0].status, 'committed');
assert.equal(populatedDataRows(purchaseRuntime, 'Purchases').length, 3);
assert.equal(
  productRow(purchaseRuntime, purchaseResponse.acknowledgedPurchases[0].legacyProductId)[
    headerIndex(PURCHASE_HEADERS, 'Final cost')
  ],
  12 * 1.13,
  'a supplied cost retains the configured pre-tax calculation',
);
assert.equal(populatedDataRows(purchaseRuntime, 'ConsumptionEvents').length, 20);
assert.equal(populatedDataRows(purchaseRuntime, 'Form Responses 1').length, 0);
assert.equal(dataReads(purchaseRuntime, 'ConsumptionEvents').length, 0);
assert.equal(purchaseRuntime.audit.finders.filter(entry => entry.sheet === 'ConsumptionEvents').length, 0);

// A purchase can be consumed by its temporary ID in the same request. The
// appended physical row becomes the exact target for the four effect cells.
const mixedRuntime = buildRuntime();
const mixedActionId = deterministicUuid(92001);
const mixedEventId = deterministicUuid(92002);
const mixedResponse = post(mixedRuntime, v2Payload(5, {
  purchases: [{
    actionId: mixedActionId,
    tempId: 'temp-mixed',
    date: '2025-04-03',
    type: 'P',
    name: 'Mixed product',
    cost: 15,
    thc: 22,
    grams: 7,
    borrowed: 0,
    postTax: false,
  }],
  consumptions: [{
    eventId: mixedEventId,
    date: '2025-04-03',
    time: '11:00:00',
    productId: 'temp-mixed',
    uses: 1.5,
    isFinished: false,
  }],
}));
assert.equal(mixedResponse.acknowledgedPurchases[0].status, 'committed');
assert.equal(mixedResponse.acknowledgedConsumptions[0].status, 'committed');
const assignedMixedId = mixedResponse.productIdMap['temp-mixed'];
assert.ok(assignedMixedId);
const mixedProduct = productRow(mixedRuntime, assignedMixedId);
assert.equal(mixedProduct[headerIndex(PURCHASE_HEADERS, 'Uses')], 1.5);
assert.equal(mixedProduct[headerIndex(PURCHASE_HEADERS, 'Finished')], 0);
assert.deepEqual(
  writes(mixedRuntime, 'Purchases').filter(entry => entry.operation === 'setValue').map(entry => entry.row),
  [4, 4, 4, 4],
);

// Borrowed purchases may omit all optional measurements. In the ordinary
// append path, blanks stay blank, same-request temporary-ID consumption links,
// and the active B-suffixed product remains usable on later requests.
const borrowedRuntime = buildRuntime();
const borrowedActionId = deterministicUuid(93001);
const borrowedEventId = deterministicUuid(93002);
const borrowedPayload = v2Payload(51, {
  purchases: [{
    actionId: borrowedActionId,
    tempId: 'temp-borrowed',
    date: '2025-04-04',
    type: 'P',
    name: 'Borrowed quick log',
    cost: '  ',
    thc: null,
    grams: '',
    borrowed: 1,
    postTax: false,
  }],
  consumptions: [{
    eventId: borrowedEventId,
    date: '2025-04-04',
    time: '12:00:00',
    productId: 'temp-borrowed',
    uses: 0.5,
    isFinished: false,
  }],
});
const borrowedResponse = post(borrowedRuntime, borrowedPayload);
assert.equal(borrowedResponse.acknowledgedPurchases[0].status, 'committed');
assert.equal(borrowedResponse.acknowledgedConsumptions[0].status, 'committed');
const borrowedId = borrowedResponse.productIdMap['temp-borrowed'];
assert.match(borrowedId, /^\*P\d+B$/);
const borrowedProduct = productRow(borrowedRuntime, borrowedId);
assert.equal(borrowedProduct[headerIndex(PURCHASE_HEADERS, 'Pre-tax cost')], '');
assert.equal(borrowedProduct[headerIndex(PURCHASE_HEADERS, 'THC%')], '');
assert.equal(borrowedProduct[headerIndex(PURCHASE_HEADERS, 'Grams')], '');
assert.equal(borrowedProduct[headerIndex(PURCHASE_HEADERS, 'Final cost')], '');
assert.equal(borrowedProduct[headerIndex(PURCHASE_HEADERS, 'Finished')], 0);
assert.equal(
  populatedDataRows(borrowedRuntime, 'ConsumptionEvents').at(-1)[headerIndex(EVENT_HEADERS, 'Legacy Product ID')],
  borrowedId,
);
const borrowedRetry = post(borrowedRuntime, borrowedPayload);
assert.equal(borrowedRetry.acknowledgedPurchases[0].status, 'duplicate');
assert.deepEqual(borrowedRetry.acknowledgedConsumptions, [{ eventId: borrowedEventId, status: 'duplicate' }]);
assert.equal(populatedDataRows(borrowedRuntime, 'Purchases').length, 3);
const borrowedReuseResponse = post(borrowedRuntime, v2Payload(52, {
  consumptions: [{
    eventId: deterministicUuid(93003),
    date: '2025-04-05',
    time: '12:00:00',
    productId: borrowedId,
    uses: 0.25,
    isFinished: false,
  }],
}));
assert.deepEqual(borrowedReuseResponse.acknowledgedConsumptions, [{
  eventId: deterministicUuid(93003), status: 'committed',
}]);
assert.equal(productRow(borrowedRuntime, borrowedId)[headerIndex(PURCHASE_HEADERS, 'Finished')], 0);

// Parse/API/environment/schema failures occur before the lock and never write.
const rejectionRuntime = buildRuntime();
let rejection = post(rejectionRuntime, '{not json');
assert.equal(rejection.errorCode, 'INVALID_JSON');
assert.equal(rejectionRuntime.audit.writes.length, 0);
assert.equal(rejectionRuntime.audit.locks.length, 0);
assert.equal(
  rejectionRuntime.audit.services.filter(entry => entry.service === 'SpreadsheetApp' && entry.method === 'openById').length,
  0,
);

rejectionRuntime.resetAudit();
rejection = post(rejectionRuntime, { apiVersion: 99 });
assert.equal(rejection.errorCode, 'UNSUPPORTED_API_VERSION');
assert.equal(rejectionRuntime.audit.writes.length, 0);
assert.equal(rejectionRuntime.audit.locks.length, 0);

rejectionRuntime.resetAudit();
rejection = post(rejectionRuntime, v2Payload(6, { environment: 'PRODUCTION' }));
assert.equal(rejection.errorCode, 'ENVIRONMENT_MISMATCH');
assert.equal(rejectionRuntime.audit.writes.length, 0);
assert.equal(rejectionRuntime.audit.locks.length, 0);

const wrongSchemaRuntime = buildRuntime({ schemaVersion: 99 });
rejection = post(wrongSchemaRuntime, v2Payload(7));
assert.equal(rejection.errorCode, 'CONFIGURATION_ERROR');
assert.match(rejection.message, /SCHEMA_MISMATCH/);
assert.equal(wrongSchemaRuntime.audit.writes.length, 0);
assert.equal(wrongSchemaRuntime.audit.structural.length, 0);
assert.equal(wrongSchemaRuntime.audit.locks.length, 0);

// Legacy v1 keeps its response shape and uses the same non-provisioning path.
const legacyRuntime = buildRuntime();
const legacyResponse = post(legacyRuntime, {
  apiVersion: 1,
  environment: 'SANDBOX',
  purchases: [],
  consumptions: [{ productId: '*P1', uses: 0.5, isFinished: false }],
});
assert.equal(legacyResponse.success, true);
assert.equal(legacyResponse.message, 'Sync complete');
assert.deepEqual(legacyResponse.productIdMap, {});
assert.equal(productRow(legacyRuntime, '*P1')[headerIndex(PURCHASE_HEADERS, 'Uses')], 2.5);
assert.equal(legacyRuntime.audit.structural.length, 0);

// Before migration, GET uses the exact legacy canonical-history calculation.
// The projection migration must reproduce that response byte-for-byte at the
// product level, including strict-greater tie handling and a zero quantity.
const projectionTimestamp = new Date('2025-05-03T16:00:00Z');
const migrationEvents = [
  interactionEvent({
    ordinal: 1,
    productId: '*P1',
    productUuid: deterministicUuid(101),
    timestamp: projectionTimestamp,
    uses: 1.25,
  }),
  interactionEvent({
    ordinal: 2,
    productId: '*P1',
    productUuid: deterministicUuid(101),
    timestamp: new Date(projectionTimestamp),
    uses: 9,
  }),
  interactionEvent({
    ordinal: 3,
    productId: '*P1',
    productUuid: deterministicUuid(101),
    timestamp: new Date('2025-04-01T16:00:00Z'),
    uses: 7,
  }),
  interactionEvent({
    ordinal: 4,
    productId: '*E1',
    productUuid: deterministicUuid(102),
    timestamp: new Date('2025-05-04T16:00:00Z'),
    uses: 0,
  }),
];
const migrationRuntime = buildRuntime({
  events: migrationEvents,
  purchaseMaxColumns: LEGACY_PURCHASE_HEADERS.length,
});
const fallbackGet = get(migrationRuntime);
assert.equal(fallbackGet.products.length, 2);
assert.equal(fallbackGet.products[0].lastLoggedAtEpochMillis, projectionTimestamp.getTime());
assert.equal(fallbackGet.products[0].lastQuantity, 1.25);
assert.equal(fallbackGet.products[1].lastQuantity, 0);
const fallbackEventReads = dataReads(migrationRuntime, 'ConsumptionEvents');
assert.equal(fallbackEventReads.length, 1);
assert.equal(fallbackEventReads[0].numRows, migrationEvents.length);
assert.equal(fallbackEventReads[0].numColumns, EVENT_HEADERS.length);

migrationRuntime.resetAudit();
const firstMigration = migrationRuntime.context.runInteractionSummaryMigration();
assert.equal(firstMigration.preparedSummaryVersion, 1);
assert.equal(firstMigration.configSummaryVersion, 0);
assert.equal(firstMigration.readyToEnable, true);
assert.equal(firstMigration.fastPathEnabled, false);
assert.equal(firstMigration.canonicalEventRows, 4);
assert.equal(firstMigration.validCanonicalEvents, 4);
assert.equal(firstMigration.invalidCanonicalEvents, 0);
assert.equal(firstMigration.summarizedProducts, 2);
assert.equal(firstMigration.purchaseRows, 2);
assert.equal(firstMigration.populatedSummaries, 2);
assert.equal(firstMigration.legacyComparisonDifferences, 0);
assert.equal(firstMigration.reconciliationDifferences, 0);
assert.equal(sheetRows(migrationRuntime, 'Purchases')[0][17], 'Last quantity');
assert.equal(
  migrationRuntime.audit.structural.filter(entry => entry.operation === 'insertColumnsAfter').length,
  1,
);
assert.equal(
  Number(productRow(migrationRuntime, '*P1')[headerIndex(PURCHASE_HEADERS, 'Most recent use')]),
  projectionTimestamp.getTime(),
);
assert.equal(productRow(migrationRuntime, '*P1')[headerIndex(PURCHASE_HEADERS, 'Last quantity')], 1.25);
assert.equal(productRow(migrationRuntime, '*E1')[headerIndex(PURCHASE_HEADERS, 'Last quantity')], 0);
assert.equal(configValue(migrationRuntime, 'INTERACTION_SUMMARY_VERSION'), 0);

// Preparation deliberately keeps the old GET path active. This closes the
// deployment window in which old source could see marker 1 before new source is
// live: the fallback still scans canonical history and returns identical data.
migrationRuntime.resetAudit();
const postPreparationFallbackGet = get(migrationRuntime);
assert.deepEqual(postPreparationFallbackGet.products, fallbackGet.products);
assert.equal(postPreparationFallbackGet.products[1].lastQuantity, 0);
assert.equal(dataReads(migrationRuntime, 'ConsumptionEvents').length, 1);

// Running the migration again leaves all durable cells identical and does not
// add another column. Explicit reconciliation is read-only and catches drift.
const stableProjectionSnapshot = JSON.stringify(migrationRuntime.snapshot());
migrationRuntime.resetAudit();
const secondMigration = migrationRuntime.context.runInteractionSummaryMigration();
assert.equal(secondMigration.preparedSummaryVersion, 1);
assert.equal(secondMigration.configSummaryVersion, 0);
assert.equal(secondMigration.readyToEnable, true);
assert.equal(secondMigration.fastPathEnabled, false);
assert.equal(secondMigration.reconciliationDifferences, 0);
assert.equal(JSON.stringify(migrationRuntime.snapshot()), stableProjectionSnapshot);
assert.equal(configValue(migrationRuntime, 'INTERACTION_SUMMARY_VERSION'), 0);
assert.equal(
  migrationRuntime.audit.structural.filter(entry => entry.operation === 'insertColumnsAfter').length,
  0,
);

// Enabling is a separate, explicit action. It rebuilds once more under the lock
// and only then changes the marker to 1. From this point normal GET must not
// open or read canonical event history.
migrationRuntime.resetAudit();
const firstEnable = migrationRuntime.context.enableInteractionSummaryFastPath();
assert.equal(firstEnable.preparedSummaryVersion, 1);
assert.equal(firstEnable.configSummaryVersion, 1);
assert.equal(firstEnable.readyToEnable, true);
assert.equal(firstEnable.fastPathEnabled, true);
assert.equal(firstEnable.reconciliationDifferences, 0);
assert.equal(configValue(migrationRuntime, 'INTERACTION_SUMMARY_VERSION'), 1);

migrationRuntime.resetAudit();
const projectedGet = get(migrationRuntime);
assert.deepEqual(projectedGet.products, fallbackGet.products);
assert.equal(projectedGet.products[1].lastQuantity, 0);
assert.equal(dataReads(migrationRuntime, 'ConsumptionEvents').length, 0);
assert.equal(
  migrationRuntime.audit.services.filter(entry => entry.sheet === 'ConsumptionEvents').length,
  0,
);

const stableEnabledSnapshot = JSON.stringify(migrationRuntime.snapshot());
migrationRuntime.resetAudit();
const secondEnable = migrationRuntime.context.enableInteractionSummaryFastPath();
assert.equal(secondEnable.configSummaryVersion, 1);
assert.equal(secondEnable.fastPathEnabled, true);
assert.equal(secondEnable.reconciliationDifferences, 0);
assert.equal(JSON.stringify(migrationRuntime.snapshot()), stableEnabledSnapshot);
assert.equal(
  migrationRuntime.audit.structural.filter(entry => entry.operation === 'insertColumnsAfter').length,
  0,
);

migrationRuntime.resetAudit();
const cleanReconciliation = migrationRuntime.context.reconcileInteractionSummary();
assert.equal(cleanReconciliation.differences.length, 0);
assert.equal(cleanReconciliation.canonicalEventRows, 4);
assert.equal(cleanReconciliation.validCanonicalEvents, 4);
assert.equal(cleanReconciliation.summarizedProducts, 2);
assert.equal(migrationRuntime.audit.writes.length, 0);
assert.equal(migrationRuntime.audit.structural.length, 0);

const migratedPurchaseSheet = migrationRuntime.peekSheet('Purchases');
migratedPurchaseSheet.getRange(2, headerIndex(PURCHASE_HEADERS, 'Last quantity') + 1).setValue(99);
migrationRuntime.resetAudit();
const driftSnapshot = JSON.stringify(migrationRuntime.snapshot());
const driftReconciliation = migrationRuntime.context.reconcileInteractionSummary();
assert.equal(driftReconciliation.differences.length, 1);
assert.equal(driftReconciliation.differences[0].type, 'STATE_MISMATCH');
assert.equal(driftReconciliation.differences[0].legacyProductId, '*P1');
assert.equal(driftReconciliation.differences[0].actual.lastQuantity, 99);
assert.equal(driftReconciliation.differences[0].expected.lastQuantity, 1.25);
assert.equal(JSON.stringify(migrationRuntime.snapshot()), driftSnapshot);
assert.equal(migrationRuntime.audit.writes.length, 0);

migrationRuntime.resetAudit();
const repairedMigration = migrationRuntime.context.rebuildInteractionSummary();
assert.equal(repairedMigration.preparedSummaryVersion, 1);
assert.equal(repairedMigration.configSummaryVersion, 0);
assert.equal(repairedMigration.readyToEnable, true);
assert.equal(repairedMigration.fastPathEnabled, false);
assert.equal(repairedMigration.reconciliationDifferences, 0);
assert.equal(configValue(migrationRuntime, 'INTERACTION_SUMMARY_VERSION'), 0);
assert.equal(productRow(migrationRuntime, '*P1')[headerIndex(PURCHASE_HEADERS, 'Last quantity')], 1.25);

migrationRuntime.resetAudit();
const repairedFallbackGet = get(migrationRuntime);
assert.deepEqual(repairedFallbackGet.products, fallbackGet.products);
assert.equal(dataReads(migrationRuntime, 'ConsumptionEvents').length, 1);

migrationRuntime.resetAudit();
const repairedEnable = migrationRuntime.context.enableInteractionSummaryFastPath();
assert.equal(repairedEnable.configSummaryVersion, 1);
assert.equal(repairedEnable.fastPathEnabled, true);
assert.equal(configValue(migrationRuntime, 'INTERACTION_SUMMARY_VERSION'), 1);

// POST maintains the projection as a fifth exact product-cell write. Equal and
// older timestamps add to total Uses but do not replace the latest quantity.
// The migration cases above separately prove that a canonical zero quantity is
// a real projected value, not an absent summary.
const postProjectionPurchases = purchaseRecords();
const preservedTimestamp = new Date(2025, 4, 3, 12, 0, 0);
postProjectionPurchases[0]['Most recent use'] = preservedTimestamp;
postProjectionPurchases[0]['Last quantity'] = 1.25;
const postProjectionRuntime = buildRuntime({
  summaryReady: true,
  purchases: postProjectionPurchases,
});

function postProjectedConsumption(ordinal, date, time, uses) {
  postProjectionRuntime.resetAudit();
  const eventId = deterministicUuid(500000 + ordinal);
  const response = post(postProjectionRuntime, v2Payload(100 + ordinal, {
    consumptions: [{
      eventId,
      date,
      time,
      productId: '*P1',
      productUuid: deterministicUuid(101),
      uses,
      isFinished: false,
      weightCode: 'projection-test',
    }],
  }));
  assert.deepEqual(response.acknowledgedConsumptions, [{ eventId, status: 'committed' }]);
  const exactWrites = writes(postProjectionRuntime, 'Purchases');
  assert.deepEqual(exactWrites.map(entry => entry.row), [2, 2, 2, 2, 2]);
  assert.deepEqual(exactWrites.map(entry => entry.column), [8, 10, 13, 17, 18]);
  assert.equal(exactWrites.every(entry => entry.numRows === 1 && entry.numColumns === 1), true);
  return productRow(postProjectionRuntime, '*P1');
}

let projectedProductRow = postProjectedConsumption(1, '2025-05-03', '12:00:00', 9);
assert.equal(projectedProductRow[headerIndex(PURCHASE_HEADERS, 'Uses')], 11);
assert.equal(
  Number(projectedProductRow[headerIndex(PURCHASE_HEADERS, 'Most recent use')]),
  preservedTimestamp.getTime(),
);
assert.equal(projectedProductRow[headerIndex(PURCHASE_HEADERS, 'Last quantity')], 1.25);

projectedProductRow = postProjectedConsumption(2, '2025-04-01', '12:00:00', 7);
assert.equal(projectedProductRow[headerIndex(PURCHASE_HEADERS, 'Uses')], 18);
assert.equal(
  Number(projectedProductRow[headerIndex(PURCHASE_HEADERS, 'Most recent use')]),
  preservedTimestamp.getTime(),
);
assert.equal(projectedProductRow[headerIndex(PURCHASE_HEADERS, 'Last quantity')], 1.25);

projectedProductRow = postProjectedConsumption(3, '2025-05-04', '12:00:00', 0.25);
assert.equal(projectedProductRow[headerIndex(PURCHASE_HEADERS, 'Uses')], 18.25);
assert.equal(
  Number(projectedProductRow[headerIndex(PURCHASE_HEADERS, 'Most recent use')]),
  new Date(2025, 4, 4, 12, 0, 0).getTime(),
);
assert.equal(projectedProductRow[headerIndex(PURCHASE_HEADERS, 'Last quantity')], 0.25);

postProjectionRuntime.resetAudit();
const latestProjectionGet = get(postProjectionRuntime);
assert.equal(latestProjectionGet.products[0].lastQuantity, 0.25);
assert.equal(
  latestProjectionGet.products[0].lastLoggedAtEpochMillis,
  new Date(2025, 4, 4, 12, 0, 0).getTime(),
);
assert.equal(dataReads(postProjectionRuntime, 'ConsumptionEvents').length, 0);

// At the measured sandbox size, a ready GET reads 400 Purchases rows and never
// touches the 3,600-row event table. Maintenance reconciliation deliberately
// scans that table and proves every projected product matches canonical history.
const productionFixture = productionSizedInteractionFixture();
const productionRuntime = buildRuntime({
  summaryReady: true,
  purchases: productionFixture.purchases,
  events: productionFixture.events,
});
const productionGet = get(productionRuntime);
assert.equal(productionGet.products.length, 400);
assert.equal(productionGet.products[0].id, '*P1');
assert.equal(productionGet.products[399].id, '*P400');
assert.equal(dataReads(productionRuntime, 'ConsumptionEvents').length, 0);
assert.equal(
  productionRuntime.audit.services.filter(entry => entry.sheet === 'ConsumptionEvents').length,
  0,
);
const productionPurchaseReads = dataReads(productionRuntime, 'Purchases');
assert.equal(productionPurchaseReads.length, 1);
assert.equal(productionPurchaseReads[0].numRows, 400);
assert.equal(productionPurchaseReads[0].numColumns, PURCHASE_HEADERS.length);

productionRuntime.resetAudit();
const productionReconciliation = productionRuntime.context.reconcileInteractionSummary();
assert.equal(productionReconciliation.canonicalEventRows, 3600);
assert.equal(productionReconciliation.validCanonicalEvents, 3600);
assert.equal(productionReconciliation.invalidCanonicalEvents, 0);
assert.equal(productionReconciliation.purchaseRows, 400);
assert.equal(productionReconciliation.summarizedProducts, 400);
assert.equal(productionReconciliation.differences.length, 0);
const productionEventReads = dataReads(productionRuntime, 'ConsumptionEvents');
assert.equal(productionEventReads.length, 1);
assert.equal(productionEventReads[0].numRows, 3600);
assert.equal(productionEventReads[0].numColumns, EVENT_HEADERS.length);
assert.equal(productionRuntime.audit.writes.length, 0);
assert.equal(productionRuntime.audit.structural.length, 0);

console.log('backend spreadsheet integration tests passed');
