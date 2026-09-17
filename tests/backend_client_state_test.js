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

const PRODUCT_UUID_114 = '114cd8751-ed77-4979-9392-c1c0b5b8792b';
const PRODUCT_UUID_115 = '70cd8751-ed77-4979-9392-c1c0b5b8792b';
const LOADED_AT = 1789616287000;

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

function purchase(productId, productUuid, name) {
  return {
    Date: '2026-09-01',
    Type: 'P',
    'Product name': name,
    'Pre-tax cost': 10,
    'THC%': 20,
    Grams: 3.5,
    Borrowed: 0,
    Finished: 0,
    'Product ID': productId,
    Uses: 0,
    'Post-tax': false,
    'Final cost': 11.3,
    'Most recent use': '',
    'Product UUID': productUuid,
    'Client Action UUID': deterministicUuid(productId === '*P114' ? 114 : 115),
    'Created At': new Date('2026-09-01T12:00:00Z'),
    'Finished At': '',
    'Last quantity': '',
  };
}

function buildRuntime() {
  const runtime = createAppsScriptRuntime({
    environment: 'SANDBOX',
    spreadsheetId: 'client-state-sheet',
    formId: 'client-state-form',
    timeZone: 'America/New_York',
    now: '2026-09-17T12:00:00-04:00',
    sheets: {
      Purchases: {
        rows: makeSheetRows(PURCHASE_HEADERS, [
          purchase('*P114', PRODUCT_UUID_114, 'BH Blueberry'),
          purchase('*P115', PRODUCT_UUID_115, 'BH Grape Smuggler'),
        ]),
        maxColumns: PURCHASE_HEADERS.length,
      },
      'Form Responses 1': { rows: [RESPONSE_HEADERS], maxColumns: RESPONSE_HEADERS.length },
      ConsumptionEvents: { rows: [EVENT_HEADERS], maxColumns: EVENT_HEADERS.length },
      SyncLedger: { rows: [LEDGER_HEADERS], maxColumns: LEDGER_HEADERS.length },
      SyncApplyJournal: { rows: [JOURNAL_HEADERS], maxColumns: JOURNAL_HEADERS.length },
      Config: {
        rows: buildConfigRows({
          environment: 'SANDBOX',
          schemaVersion: 2,
          interactionSummaryVersion: 1,
          recoverableSyncApplyVersion: 1,
          pendingApplyKey: '',
          taxRate: 0.13,
        }),
        maxColumns: 3,
      },
      MigrationReport: { rows: [REPORT_HEADERS], maxColumns: REPORT_HEADERS.length },
    },
    form: {
      id: 'client-state-form',
      destinationId: 'client-state-sheet',
      items: [{ title: 'Product', type: 'MULTIPLE_CHOICE', choices: ['*P114', '*P115'] }],
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

function get(runtime, parameters) {
  const parameter = Object.fromEntries(
    Object.entries(parameters).map(([key, value]) => [key, String(value)]),
  );
  const parametersMap = Object.fromEntries(
    Object.entries(parameter).map(([key, value]) => [key, [value]]),
  );
  return runtime.parseTextOutput(runtime.context.doGet({ parameter, parameters: parametersMap }));
}

function statePayload(requestOrdinal, productId, updatedAt) {
  return {
    apiVersion: 2,
    requestId: deterministicUuid(9000 + requestOrdinal),
    environment: 'SANDBOX',
    purchases: [],
    consumptions: [],
    finishActions: [],
    clientState: {
      loadedPenProductId: productId,
      loadedPenUpdatedAtEpochMillis: updatedAt,
    },
  };
}

function configRows(runtime) {
  return runtime.peekSheet('Config').snapshot().rows;
}

function configValue(runtime, key) {
  const rows = configRows(runtime);
  const keyColumn = rows[0].indexOf('Key');
  const valueColumn = rows[0].indexOf('Value');
  const row = rows.slice(1).find(item => item[keyColumn] === key);
  assert.ok(row, `missing Config key ${key}`);
  return row[valueColumn];
}

function configValueCells(runtime) {
  const rows = configRows(runtime);
  const valueColumn = rows[0].indexOf('Value');
  return rows.slice(1).map(row => row[valueColumn]);
}

// 1. A clientState-only request commits Config without a core ledger/journal row.
{
  const runtime = buildRuntime();
  const response = post(runtime, statePayload(1, '*P115', LOADED_AT));
  assert.equal(response.success, true);
  assert.deepEqual(response.acknowledgedClientState, {
    loadedPenUpdatedAtEpochMillis: LOADED_AT,
    status: 'committed',
  });
  assert.equal(configValue(runtime, 'LOADED_PEN_PRODUCT_ID'), '*P115');
  assert.equal(configValue(runtime, 'LOADED_PEN_PRODUCT_UUID'), PRODUCT_UUID_115);
  assert.equal(configValue(runtime, 'LOADED_PEN_PRODUCT_NAME'), 'BH Grape Smuggler');
  assert.equal(configValue(runtime, 'LOADED_PEN_UPDATED_AT'), LOADED_AT);
  assert.equal(runtime.peekSheet('SyncLedger').snapshot().rows.length, 1);
  assert.equal(runtime.peekSheet('SyncApplyJournal').snapshot().rows.length, 1);
}

// 2. Repeating the state is stale and leaves the already-populated Value cells byte-equal.
{
  const runtime = buildRuntime();
  const payload = statePayload(2, '*P115', LOADED_AT);
  assert.equal(post(runtime, payload).acknowledgedClientState.status, 'committed');
  const before = JSON.stringify(configValueCells(runtime));
  assert.equal(post(runtime, payload).acknowledgedClientState.status, 'stale');
  assert.equal(JSON.stringify(configValueCells(runtime)), before);
}

// 3. A newer state replaces the stored product and timestamp.
{
  const runtime = buildRuntime();
  assert.equal(post(runtime, statePayload(3, '*P114', LOADED_AT)).acknowledgedClientState.status, 'committed');
  const response = post(runtime, statePayload(4, '*P115', LOADED_AT + 1));
  assert.equal(response.acknowledgedClientState.status, 'committed');
  assert.equal(configValue(runtime, 'LOADED_PEN_PRODUCT_ID'), '*P115');
  assert.equal(configValue(runtime, 'LOADED_PEN_PRODUCT_UUID'), PRODUCT_UUID_115);
  assert.equal(configValue(runtime, 'LOADED_PEN_PRODUCT_NAME'), 'BH Grape Smuggler');
  assert.equal(configValue(runtime, 'LOADED_PEN_UPDATED_AT'), LOADED_AT + 1);
}

// 4. An older state is stale and cannot replace the committed newer state.
{
  const runtime = buildRuntime();
  assert.equal(post(runtime, statePayload(5, '*P115', LOADED_AT)).acknowledgedClientState.status, 'committed');
  const response = post(runtime, statePayload(6, '*P114', LOADED_AT - 1));
  assert.equal(response.acknowledgedClientState.status, 'stale');
  assert.equal(configValue(runtime, 'LOADED_PEN_PRODUCT_ID'), '*P115');
  assert.equal(configValue(runtime, 'LOADED_PEN_UPDATED_AT'), LOADED_AT);
}

// 5. Unknown product is rejected without changing a previously committed state.
{
  const runtime = buildRuntime();
  assert.equal(post(runtime, statePayload(7, '*P115', LOADED_AT)).acknowledgedClientState.status, 'committed');
  const before = JSON.stringify(configValueCells(runtime));
  const response = post(runtime, statePayload(8, '*P999', LOADED_AT + 1));
  assert.equal(response.acknowledgedClientState.status, 'rejected');
  assert.equal(response.acknowledgedClientState.errorCode, 'UNKNOWN_PRODUCT');
  assert.equal(JSON.stringify(configValueCells(runtime)), before);
}

// 6. A newer null state clears all product keys and the read resource reports null.
{
  const runtime = buildRuntime();
  assert.equal(post(runtime, statePayload(9, '*P115', LOADED_AT)).acknowledgedClientState.status, 'committed');
  const response = post(runtime, statePayload(10, null, LOADED_AT + 1));
  assert.equal(response.acknowledgedClientState.status, 'committed');
  assert.equal(configValue(runtime, 'LOADED_PEN_PRODUCT_ID'), '');
  assert.equal(configValue(runtime, 'LOADED_PEN_PRODUCT_UUID'), '');
  assert.equal(configValue(runtime, 'LOADED_PEN_PRODUCT_NAME'), '');
  assert.equal(configValue(runtime, 'LOADED_PEN_UPDATED_AT'), LOADED_AT + 1);
  assert.equal(get(runtime, {
    resource: 'clientState', analyticsVersion: 1, environment: 'SANDBOX',
  }).loadedPen, null);
}

// 7. V1 rejects the new top-level state during preflight.
{
  const runtime = buildRuntime();
  const response = post(runtime, {
    apiVersion: 1,
    environment: 'SANDBOX',
    purchases: [],
    consumptions: [],
    clientState: {
      loadedPenProductId: '*P115',
      loadedPenUpdatedAtEpochMillis: LOADED_AT,
    },
  });
  assert.equal(response.success, false);
  assert.match(response.message, /clientState requires apiVersion 2/);
}

// 8. A non-numeric update time fails pure V2 preflight.
{
  const runtime = buildRuntime();
  const response = post(runtime, Object.assign(
    statePayload(11, '*P115', LOADED_AT),
    { clientState: { loadedPenProductId: '*P115', loadedPenUpdatedAtEpochMillis: 'abc' } },
  ));
  assert.equal(response.success, false);
  assert.equal(response.errorCode, 'INVALID_ITEM');
  assert.match(response.message, /loadedPenUpdatedAtEpochMillis/);
}

// 9. The resource returns the committed shape and shares history's environment guard.
{
  const runtime = buildRuntime();
  assert.equal(post(runtime, statePayload(12, '*P114', LOADED_AT)).acknowledgedClientState.status, 'committed');
  assert.equal(post(runtime, statePayload(13, '*P115', LOADED_AT + 1)).acknowledgedClientState.status, 'committed');
  const response = get(runtime, {
    resource: 'clientState', analyticsVersion: 1, environment: 'SANDBOX',
  });
  assert.deepEqual({
    success: response.success,
    apiVersion: response.apiVersion,
    analyticsVersion: response.analyticsVersion,
    resource: response.resource,
    environment: response.environment,
    loadedPen: response.loadedPen,
  }, {
    success: true,
    apiVersion: 2,
    analyticsVersion: 1,
    resource: 'clientState',
    environment: 'SANDBOX',
    loadedPen: {
      productId: '*P115',
      productUuid: PRODUCT_UUID_115,
      productName: 'BH Grape Smuggler',
      updatedAtEpochMillis: LOADED_AT + 1,
    },
  });
  const historyMismatch = get(runtime, {
    resource: 'history', analyticsVersion: 1, environment: 'PRODUCTION',
  });
  const clientStateMismatch = get(runtime, {
    resource: 'clientState', analyticsVersion: 1, environment: 'PRODUCTION',
  });
  assert.equal(clientStateMismatch.errorCode, historyMismatch.errorCode);
  assert.equal(clientStateMismatch.errorCode, 'ENVIRONMENT_MISMATCH');
}

console.log('backend client state tests passed');
