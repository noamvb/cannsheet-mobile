'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const {
  buildConfigRows,
  createAppsScriptRuntime,
} = require('./fake_apps_script_runtime');

const PURCHASE_HEADERS = [
  'Date', 'Type', 'Product name', 'Pre-tax cost', 'THC%', 'Grams',
  'Borrowed', 'Finished', 'Product ID', 'Uses', 'Post-tax', 'Final cost',
  'Most recent use', 'Product UUID', 'Client Action UUID', 'Created At',
  'Finished At', 'Last quantity',
];
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

const runtime = createAppsScriptRuntime({
  environment: 'SANDBOX',
  spreadsheetId: 'sandbox-reset-sheet',
  formId: 'sandbox-reset-form',
  sheets: {
    Purchases: { rows: [PURCHASE_HEADERS, ['stale purchase']] },
    'Form Responses 1': { rows: [RESPONSE_HEADERS, ['stale response']] },
    ConsumptionEvents: { rows: [EVENT_HEADERS, ['stale event']] },
    SyncLedger: { rows: [LEDGER_HEADERS, ['stale ledger']] },
    Config: {
      rows: buildConfigRows({
        environment: 'SANDBOX',
        schemaVersion: 2,
        interactionSummaryVersion: 0,
      }),
    },
    MigrationReport: { rows: [REPORT_HEADERS, ['stale report']] },
  },
  form: {
    destinationId: 'sandbox-reset-sheet',
    items: [{ title: 'Product', type: 'MULTIPLE_CHOICE' }],
  },
});

let deletedFormResponses = 0;
runtime.form.deleteAllResponses = () => {
  deletedFormResponses += 1;
  runtime.audit.record('form', { operation: 'deleteAllResponses' });
  return runtime.form;
};

runtime.loadSource(fs.readFileSync('backend_additions.gs', 'utf8'), {
  filename: 'backend_additions.gs',
});
const api = runtime.loadSource(fs.readFileSync('sandbox_provisioning.gs', 'utf8'), {
  filename: 'sandbox_provisioning.gs',
  exports: [
    'SANDBOX_FIXTURE',
    'resetSandboxData',
    'sandboxMetrics_',
  ],
});
runtime.resetAudit();

const metrics = JSON.parse(JSON.stringify(api.resetSandboxData()));
assert.deepEqual(metrics, {
  purchases: 6,
  responses: 5,
  events: 5,
  uniqueEvents: 5,
  unresolved: 0,
  ledgerRows: 0,
  migrationRows: 0,
  totalUses: 5.25,
  active: 3,
  finished: 2,
  unopened: 1,
});
assert.equal(deletedFormResponses, 1);
assert.equal(runtime.lock.locked, false, 'reset must release the script lock');

const purchaseRows = runtime.peekSheet('Purchases').snapshot().rows;
assert.deepEqual(purchaseRows[0], PURCHASE_HEADERS);
assert.equal(
  Array.from(api.SANDBOX_FIXTURE.purchases).every(row => row.length === 17),
  true,
  'the legacy fixture intentionally contains 17 values per purchase',
);
assert.equal(
  purchaseRows.slice(1).every(row => row.length === 18),
  true,
  'reset must pad every fixture purchase to the 18-column Phase 4 schema',
);
assert.equal(
  runtime.audit.writes.some(entry => (
    entry.sheet === 'Purchases'
    && entry.operation === 'setValues'
    && entry.row === 2
    && entry.column === 1
    && entry.numRows === 6
    && entry.numColumns === 18
  )),
  true,
  'reset must write the six padded purchases as one 18-column batch',
);

const headerIndex = name => {
  const index = purchaseRows[0].indexOf(name);
  assert.notEqual(index, -1, `missing Purchases header ${name}`);
  return index;
};
const productRow = productId => {
  const idColumn = headerIndex('Product ID');
  const row = purchaseRows.slice(1).find(candidate => candidate[idColumn] === productId);
  assert.ok(row, `missing fixture product ${productId}`);
  return row;
};
const lastQuantityColumn = headerIndex('Last quantity');
const mostRecentUseColumn = headerIndex('Most recent use');

assert.equal(productRow('*P1')[lastQuantityColumn], 0.5);
assert.equal(productRow('*F1B')[lastQuantityColumn], 0.25);
assert.equal(productRow('*S1')[lastQuantityColumn], 2);
assert.equal(productRow('*K1')[lastQuantityColumn], 1.5);
assert.equal(productRow('*E1')[lastQuantityColumn], '');
assert.equal(productRow('*J1')[lastQuantityColumn], '');
assert.notEqual(productRow('*P1')[mostRecentUseColumn], '');
assert.equal(productRow('*E1')[mostRecentUseColumn], '');

const configRows = runtime.peekSheet('Config').snapshot().rows;
const summaryConfig = configRows.find(row => row[0] === 'INTERACTION_SUMMARY_VERSION');
assert.ok(summaryConfig, 'reset must retain the interaction-summary Config marker');
assert.equal(summaryConfig[1], 1, 'reset must enable the rebuilt Phase 4 fast path');
assert.equal(
  runtime.audit.services.filter(entry => (
    entry.service === 'SpreadsheetApp' && entry.method === 'flush'
  )).length >= 3,
  true,
  'rebuild must flush before projection writes, after projection writes, and after enabling',
);

assert.deepEqual(runtime.form.snapshot().items[0].choices, ['*P1', '*F1B', '*K1']);

runtime.peekSheet('Purchases').getRange(2, lastQuantityColumn + 1).setValue(999);
assert.throws(
  () => api.sandboxMetrics_(runtime.spreadsheet),
  /SANDBOX_INTERACTION_SUMMARY_MISMATCH/,
  'sandbox metrics must reject a reset whose projection no longer matches canonical events',
);

console.log('sandbox provisioning tests passed');
