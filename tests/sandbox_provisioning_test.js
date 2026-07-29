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
const CORRECTION_HEADERS = [
  'Correction UUID', 'Target Event UUID', 'Supersedes Correction UUID',
  'Revision', 'Operation', 'Replacement Timestamp',
  'Replacement Local Date', 'Replacement Local Time',
  'Replacement Product UUID', 'Replacement Legacy Product ID',
  'Replacement Uses', 'Replacement Weight Code', 'Replacement Finished',
  'Reopen Product', 'Reason', 'Request UUID', 'Source', 'Created At',
];
const LEDGER_HEADERS = [
  'Request UUID', 'API Version', 'Received At', 'Purchase Count',
  'Consumption Count', 'Result', 'Duration Ms', 'Error Code',
];
const APPLY_JOURNAL_HEADERS = [
  'Apply UUID', 'Kind', 'API Version', 'Request UUID', 'State',
  'Core Committed At', 'Completed At', 'Finalization JSON', 'Response JSON',
];
const REPORT_HEADERS = [
  'Type', 'Source Sheet', 'Source Row', 'Product ID', 'Detail', 'Recorded At',
];

// A sandbox-bound script can still be pointed at the wrong spreadsheet. An
// existing production marker must stop provisioning before any mutation.
{
  const protectedRuntime = createAppsScriptRuntime({
    environment: 'SANDBOX',
    spreadsheetId: 'production-marked-sheet',
    formId: 'production-marked-form',
    sheets: {
      Purchases: { rows: [['Sentinel'], ['must remain untouched']] },
      Config: {
        rows: buildConfigRows({
          environment: 'PRODUCTION',
          schemaVersion: 2,
        }),
      },
    },
    form: {
      destinationId: 'production-marked-sheet',
      items: [{ title: 'Sentinel question', type: 'MULTIPLE_CHOICE' }],
    },
  });
  protectedRuntime.loadSource(fs.readFileSync('backend_additions.gs', 'utf8'), {
    filename: 'backend_additions.gs',
  });
  const protectedApi = protectedRuntime.loadSource(
    fs.readFileSync('sandbox_provisioning.gs', 'utf8'),
    { filename: 'sandbox_provisioning.gs', exports: ['provisionSandbox'] },
  );
  protectedRuntime.resetAudit();
  const before = protectedRuntime.snapshot();
  const beforeSheetNames = Object.keys(before.spreadsheet.sheets).sort();
  const beforeConfigRows = protectedRuntime.peekSheet('Config').snapshot().rows;

  assert.throws(
    () => protectedApi.provisionSandbox(),
    /SANDBOX_GUARD: Config ENVIRONMENT mismatch/,
  );

  const after = protectedRuntime.snapshot();
  assert.deepEqual(after, before, 'a production marker must leave all state unchanged');
  assert.deepEqual(
    Object.keys(after.spreadsheet.sheets).sort(),
    beforeSheetNames,
    'a production marker must not create correction or core sheets',
  );
  assert.deepEqual(
    protectedRuntime.peekSheet('Config').snapshot().rows,
    beforeConfigRows,
    'a production marker must not add or change Config keys',
  );
  assert.equal(protectedRuntime.peekSheet('ConsumptionEventCorrections'), null);
  assert.equal(protectedRuntime.audit.writes.length, 0, 'guard failure must not write cells');
  assert.equal(protectedRuntime.audit.structural.length, 0, 'guard failure must not change sheet structure');
  assert.equal(protectedRuntime.audit.batches.length, 0, 'guard failure must not batch-write cells');
  assert.equal(protectedRuntime.audit.form.length, 0, 'guard failure must not alter form state');
  assert.equal(protectedRuntime.lock.locked, false, 'guard failure must release the script lock');
}

const runtime = createAppsScriptRuntime({
  environment: 'SANDBOX',
  spreadsheetId: 'sandbox-reset-sheet',
  formId: 'sandbox-reset-form',
  sheets: {
    Purchases: { rows: [PURCHASE_HEADERS, ['stale purchase']] },
    'Form Responses 1': { rows: [RESPONSE_HEADERS, ['stale response']] },
    ConsumptionEvents: { rows: [EVENT_HEADERS, ['stale event']] },
    SyncLedger: { rows: [LEDGER_HEADERS, ['stale ledger']] },
    SyncApplyJournal: { rows: [APPLY_JOURNAL_HEADERS] },
    Config: {
      rows: buildConfigRows({
        environment: 'SANDBOX',
        schemaVersion: 2,
        interactionSummaryVersion: 0,
        correctionSchemaVersion: 0,
        correctionWritesEnabled: false,
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
    'provisionSandbox',
    'resetSandboxData',
    'sandboxMetrics_',
  ],
});
runtime.resetAudit();

const expectedMetrics = {
  purchases: 6,
  responses: 5,
  events: 5,
  correctionRows: 0,
  uniqueEvents: 5,
  unresolved: 0,
  ledgerRows: 0,
  migrationRows: 0,
  totalUses: 5.25,
  active: 3,
  finished: 2,
  unopened: 1,
};
const provisionMetrics = JSON.parse(JSON.stringify(api.provisionSandbox()));
assert.deepEqual(provisionMetrics, expectedMetrics);

const correctionRows = runtime.peekSheet('ConsumptionEventCorrections').snapshot().rows;
assert.deepEqual(
  correctionRows,
  [CORRECTION_HEADERS],
  'provision must create the empty correction sheet with its exact header contract',
);
const configValues = Object.fromEntries(
  runtime.peekSheet('Config').snapshot().rows.slice(1).map(row => [row[0], row[1]]),
);
assert.equal(configValues.CONSUMPTION_CORRECTION_SCHEMA_VERSION, 1);
assert.equal(configValues.CONSUMPTION_CORRECTION_WRITES_ENABLED, true);
const seededJournalRows = runtime.peekSheet('SyncApplyJournal').snapshot().rows;
assert.equal(seededJournalRows.length, 2);
assert.equal(
  seededJournalRows[1][APPLY_JOURNAL_HEADERS.indexOf('State')],
  'COMPLETE',
);
assert.deepEqual(
  JSON.parse(
    seededJournalRows[1][
      APPLY_JOURNAL_HEADERS.indexOf('Finalization JSON')
    ],
  ).eventIds,
  runtime.peekSheet('ConsumptionEvents').snapshot().rows
    .slice(1)
    .map(row => row[0]),
  'seeded events need durable finish provenance for safe Reopen testing',
);

runtime.peekSheet('ConsumptionEventCorrections')
  .getRange(2, 1, 1, CORRECTION_HEADERS.length)
  .setValues([new Array(CORRECTION_HEADERS.length).fill('stale correction')]);
const metrics = JSON.parse(JSON.stringify(api.resetSandboxData()));
assert.deepEqual(metrics, expectedMetrics, 'reset must clear stale correction rows');
assert.deepEqual(
  runtime.peekSheet('ConsumptionEventCorrections').snapshot().rows,
  [CORRECTION_HEADERS],
  'reset must leave no correction fixture rows',
);
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
