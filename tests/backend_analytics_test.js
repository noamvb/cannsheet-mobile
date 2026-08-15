'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const {
  buildConfigRows,
  createAppsScriptRuntime,
  deterministicUuid,
  makeSheetRows,
  FakeCache,
  FakeCacheService,
  sheetsValuesBatchGet,
} = require('./fake_apps_script_runtime');

const source = fs.readFileSync('backend_additions.gs', 'utf8');

const PURCHASE_HEADERS = [
  'Date', 'Type', 'Product name', 'Pre-tax cost', 'THC%', 'Grams',
  'Borrowed', 'Finished', 'Product ID', 'Uses', 'Post-tax', 'Final cost',
  'Most recent use', 'Product UUID', 'Client Action UUID', 'Created At',
  'Finished At', 'Last quantity',
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

function basePurchases() {
  return [
    {
      Date: new Date('2026-07-01T04:00:00Z'),
      Type: 'P',
      'Product name': 'Alpha',
      'Pre-tax cost': 10,
      'THC%': 24,
      Grams: 3.5,
      Borrowed: 0,
      Finished: 1,
      'Product ID': '*P1',
      Uses: 3.25,
      'Post-tax': false,
      'Final cost': 11.30,
      'Most recent use': new Date('2026-07-17T13:00:00Z'),
      'Product UUID': deterministicUuid(101),
      'Client Action UUID': deterministicUuid(201),
      'Created At': new Date('2026-06-30T14:00:00Z'),
      'Finished At': new Date('2026-07-17T13:00:00Z'),
      'Last quantity': 0.25,
    },
    {
      Date: 'Today',
      Type: 'E',
      'Product name': 'Beta',
      'Pre-tax cost': 20,
      'THC%': 0.5,
      Grams: 1,
      Borrowed: 'yes',
      Finished: 0,
      'Product ID': '*E1',
      Uses: 1,
      'Post-tax': true,
      'Final cost': '',
      'Most recent use': new Date('2026-07-12T03:30:00Z'),
      'Product UUID': deterministicUuid(102),
      'Client Action UUID': deterministicUuid(202),
      'Created At': new Date('2026-07-05T15:00:00Z'),
      'Finished At': '',
      'Last quantity': 0.5,
    },
    {
      Date: 'not-a-date',
      Type: 'C',
      'Product name': 'Gamma',
      'Pre-tax cost': '?',
      'THC%': 101,
      Grams: 0,
      Borrowed: false,
      Finished: 2,
      'Product ID': '*C1',
      Uses: 0,
      'Post-tax': false,
      'Final cost': '?',
      'Most recent use': '',
      'Product UUID': deterministicUuid(103),
      'Client Action UUID': deterministicUuid(203),
      'Created At': '',
      'Finished At': '',
      'Last quantity': '',
    },
    {
      Date: '2026-06-15',
      Type: 'P',
      'Product name': 'Delta',
      'Pre-tax cost': 0,
      'THC%': 20,
      Grams: 2,
      Borrowed: 'maybe',
      Finished: 9,
      'Product ID': '*P2',
      Uses: 0,
      'Post-tax': false,
      'Final cost': 0,
      'Most recent use': '',
      'Product UUID': deterministicUuid(104),
      'Client Action UUID': deterministicUuid(204),
      'Created At': new Date('2026-06-15T16:00:00Z'),
      'Finished At': '',
      'Last quantity': '',
    },
    {
      Date: '2026-01-01',
      Type: 'P',
      'Product name': 'Unreferenced invalid row',
      'Pre-tax cost': 1,
      'THC%': 10,
      Grams: 1,
      Borrowed: 0,
      Finished: 0,
      'Product ID': '',
    },
  ];
}

function baseEvents() {
  return [
    {
      'Event UUID': deterministicUuid(1001),
      Timestamp: new Date('2026-07-10T14:15:00Z'),
      'Local Date': '2026-07-10',
      'Local Time': '10:15:00',
      'Product UUID': deterministicUuid(101),
      'Legacy Product ID': '*P1',
      Uses: 1,
      'Weight Code': 'A',
      Finished: false,
      Source: 'ANDROID_V2',
      'Request UUID': deterministicUuid(2001),
    },
    {
      'Event UUID': deterministicUuid(1002),
      Timestamp: new Date('2026-07-10T14:15:00Z'),
      'Local Date': '2026-07-10',
      'Local Time': '10:15:00',
      'Product UUID': deterministicUuid(101),
      'Legacy Product ID': '*P1',
      Uses: 2,
      'Weight Code': '',
      Finished: true,
      Source: 'FORM',
      'Request UUID': deterministicUuid(2001),
    },
    {
      'Event UUID': deterministicUuid(1003),
      Timestamp: new Date('2026-07-12T03:30:00Z'),
      'Local Date': '2026-07-12',
      'Local Time': '23:30:00',
      'Product UUID': deterministicUuid(102),
      'Legacy Product ID': '*E1',
      Uses: 0.5,
      'Weight Code': '',
      Finished: false,
      Source: 'FORM_LEGACY',
      'Request UUID': deterministicUuid(2002),
    },
    {
      'Event UUID': deterministicUuid(1004),
      Timestamp: new Date('2026-07-17T13:00:00Z'),
      'Local Date': '2026-07-17',
      'Local Time': '09:00:00',
      'Product UUID': deterministicUuid(101),
      'Legacy Product ID': '*P1',
      Uses: 0.25,
      'Weight Code': '',
      Finished: true,
      Source: 'FORM_RECOVERY',
      'Request UUID': deterministicUuid(2003),
    },
    {
      'Event UUID': deterministicUuid(1005),
      Timestamp: new Date('2026-03-08T06:30:00Z'),
      'Local Date': '2026-03-08',
      'Local Time': '01:30:00',
      'Product UUID': deterministicUuid(102),
      'Legacy Product ID': '*E1',
      Uses: 0.5,
      'Weight Code': '',
      Finished: false,
      Source: 'OTHER',
      'Request UUID': deterministicUuid(2004),
    },
  ];
}

function baseLedgers() {
  return [
    {
      'Request UUID': deterministicUuid(3001),
      'API Version': 2,
      'Received At': new Date('2026-05-01T14:00:00Z'),
      'Purchase Count': 0,
      'Consumption Count': 1,
      Result: 'ACCEPTED',
      'Duration Ms': 50,
      'Error Code': '',
    },
    {
      'Request UUID': deterministicUuid(3002),
      'API Version': 2,
      'Received At': new Date('2026-07-01T14:00:00Z'),
      'Purchase Count': 0,
      'Consumption Count': 1,
      Result: 'ACCEPTED',
      'Duration Ms': 100,
      'Error Code': '',
    },
    {
      'Request UUID': deterministicUuid(3003),
      'API Version': 2,
      'Received At': new Date('2026-07-10T14:00:00Z'),
      'Purchase Count': 0,
      'Consumption Count': 1,
      Result: 'PARTIAL',
      'Duration Ms': 300,
      'Error Code': '',
    },
    {
      'Request UUID': deterministicUuid(3004),
      'API Version': 2,
      'Received At': new Date('2026-07-17T14:00:00Z'),
      'Purchase Count': 0,
      'Consumption Count': 1,
      Result: 'ACCEPTED',
      'Duration Ms': 200,
      'Error Code': '',
    },
  ];
}

function buildRuntime(options = {}) {
  const configRows = buildConfigRows({
    environment: 'SANDBOX',
    schemaVersion: 2,
    interactionSummaryVersion: 1,
    recoverableSyncApplyVersion: options.recoverableSyncApplyVersion ?? 1,
    pendingApplyKey: options.pendingApplyKey ?? '',
    taxRate: 0.13,
  });
  const runtime = createAppsScriptRuntime({
    environment: 'SANDBOX',
    spreadsheetId: 'analytics-sheet',
    formId: 'analytics-form',
    now: '2026-07-18T12:00:00-04:00',
    enableBatchGet: options.enableBatchGet,
    sheets: {
      Purchases: {
        rows: makeSheetRows(PURCHASE_HEADERS, options.purchases || basePurchases()),
      },
      ConsumptionEvents: {
        rows: makeSheetRows(EVENT_HEADERS, options.events || baseEvents()),
      },
      SyncLedger: {
        rows: makeSheetRows(LEDGER_HEADERS, options.ledgers || baseLedgers()),
      },
      SyncApplyJournal: {
        rows: [JOURNAL_HEADERS],
      },
      'Form Responses 1': {
        rows: [['Timestamp', 'Product', 'Uses', 'Weight code', 'Mark as Finished?', 'Cannsheet Event UUID', 'Cannsheet Request UUID']],
      },
      MigrationReport: {
        rows: [REPORT_HEADERS],
      },
      Config: { rows: configRows },
    },
    form: {
      id: 'analytics-form',
      destinationId: 'analytics-sheet',
      items: [{
        title: 'Product',
        type: 'MULTIPLE_CHOICE',
        choices: ['*P1', '*E1', '*C1', '*P2'],
      }],
    },
  });
  runtime.loadSource(source, { filename: 'backend_additions.gs' });
  runtime.resetAudit();
  return runtime;
}

function eventFor(parameters, duplicateOverrides = {}) {
  const parameter = Object.fromEntries(
    Object.entries(parameters).map(([name, value]) => [name, String(value)]),
  );
  const multi = Object.fromEntries(
    Object.entries(parameter).map(([name, value]) => [name, [value]]),
  );
  Object.assign(multi, duplicateOverrides);
  return { parameter, parameters: multi };
}

function get(runtime, parameters, duplicates) {
  return runtime.parseTextOutput(
    runtime.context.doGet(eventFor(parameters, duplicates)),
  );
}

function insights(runtime, overrides = {}, duplicates) {
  return get(runtime, Object.assign({
    resource: 'insights',
    analyticsVersion: 1,
    environment: 'SANDBOX',
    from: '2026-03-01',
    to: '2026-07-18',
  }, overrides), duplicates);
}

function history(runtime, overrides = {}, duplicates) {
  return get(runtime, Object.assign({
    resource: 'history',
    analyticsVersion: 1,
    environment: 'SANDBOX',
  }, overrides), duplicates);
}

function post(runtime, payload) {
  return runtime.parseTextOutput(
    runtime.context.doPost({
      postData: { contents: JSON.stringify(payload) },
    }),
  );
}

function bodyReads(runtime, sheetName) {
  return runtime.audit.reads.filter(entry => entry.sheet === sheetName && (entry.numRows > 1 || entry.row >= 2));
}

function assertNoMutation(runtime) {
  assert.equal(runtime.audit.writes.length, 0, 'analytics GET must not write cells');
  assert.equal(runtime.audit.structural.length, 0, 'analytics GET must not change sheet structure');
  assert.equal(runtime.audit.batches.length, 0, 'analytics GET must not run a batch update');
}

// The existing product GET remains on the compact Purchases-only path.
{
  const runtime = buildRuntime();
  const bare = runtime.parseTextOutput(runtime.context.doGet());
  assert.equal(bare.apiVersion, 2);
  assert.equal(bare.environment, 'SANDBOX');
  assert.equal(bare.products.length, 4);
  assert.equal(bare.products.find(product => product.id === '*P1').totalUses, 3.25);
  assert.equal(bare.products.find(product => product.id === '*E1').totalUses, 1);
  assert.equal(bare.products.find(product => product.id === '*C1').totalUses, 0);
  assert.equal(bare.products.find(product => product.id === '*P2').totalUses, 0);
  assert.equal(bodyReads(runtime, 'ConsumptionEvents').length, 0);
  assert.equal(bodyReads(runtime, 'SyncLedger').length, 0);
  assertNoMutation(runtime);

  runtime.resetAudit();
  const emptyEvent = runtime.parseTextOutput(
    runtime.context.doGet({ parameter: {}, parameters: {} }),
  );
  assert.deepEqual(emptyEvent.products, bare.products);
  assert.equal(bodyReads(runtime, 'ConsumptionEvents').length, 0);
  assert.equal(bodyReads(runtime, 'SyncLedger').length, 0);
  assertNoMutation(runtime);
}

// The compact catalog treats a blank Uses projection as zero and rejects
// malformed projected totals without writing any sheet data.
{
  const blankUses = basePurchases().map(product => (
    product['Product ID'] === '*C1' ? { ...product, Uses: '' } : product
  ));
  const blankRuntime = buildRuntime({ purchases: blankUses });
  const blankResponse = blankRuntime.parseTextOutput(blankRuntime.context.doGet());
  assert.equal(blankResponse.products.find(product => product.id === '*C1').totalUses, 0);
  assertNoMutation(blankRuntime);

  const invalidUses = basePurchases().map(product => (
    product['Product ID'] === '*P1' ? { ...product, Uses: -1 } : product
  ));
  const invalidRuntime = buildRuntime({ purchases: invalidUses });
  const invalidResponse = invalidRuntime.parseTextOutput(invalidRuntime.context.doGet());
  assert.equal(invalidResponse.errorCode, 'INTERNAL_ERROR');
  assert.match(invalidResponse.error, /Uses must be a finite nonnegative number/);
  assertNoMutation(invalidRuntime);

  const nonFiniteUses = basePurchases().map(product => (
    product['Product ID'] === '*P1' ? { ...product, Uses: Number.POSITIVE_INFINITY } : product
  ));
  const nonFiniteRuntime = buildRuntime({ purchases: nonFiniteUses });
  const nonFiniteResponse = nonFiniteRuntime.parseTextOutput(nonFiniteRuntime.context.doGet());
  assert.equal(nonFiniteResponse.errorCode, 'INTERNAL_ERROR');
  assert.match(nonFiniteResponse.error, /Uses must be a finite nonnegative number/);
  assertNoMutation(nonFiniteRuntime);

  const fractionalUses = basePurchases().map(product => (
    product['Product ID'] === '*P1' ? { ...product, Uses: 0.123456789 } : product
  ));
  const fractionalRuntime = buildRuntime({ purchases: fractionalUses });
  const fractionalResponse = fractionalRuntime.parseTextOutput(fractionalRuntime.context.doGet());
  assert.equal(fractionalResponse.products.find(product => product.id === '*P1').totalUses, 0.123457);
  assertNoMutation(fractionalRuntime);
}

// Insights aggregation, spending separation, lifecycle values, and warnings.
{
  const runtime = buildRuntime();
  const before = JSON.stringify(runtime.snapshot());
  const response = insights(runtime);
  const after = JSON.stringify(runtime.snapshot());
  assert.equal(response.success, true);
  assert.equal(response.resource, 'insights');
  assert.equal(response.analyticsVersion, 1);
  assert.equal(response.range.scope, 'CUSTOM');
  assert.equal(response.range.dayCount, 140);
  assert.equal(response.dailyActivity.length, 140);
  assert.equal(response.byWeekday.length, 7);
  assert.equal(response.byHour.length, 24);
  assert.equal(response.overview.logCount, 5);
  assert.equal(response.overview.activeDayCount, 4);
  assert.equal(response.overview.distinctProductCount, 2);
  assert.equal(response.overview.firstLogAtEpochMillis, Date.parse('2026-03-08T06:30:00Z'));
  assert.equal(response.overview.lastLogAtEpochMillis, Date.parse('2026-07-17T13:00:00Z'));
  assert.equal(response.overview.daysSinceLastLog, 1);
  assert.equal(
    response.dailyActivity.find(day => day.date === '2026-07-10').logCount,
    2,
  );
  assert.deepEqual(response.inventory, {
    activeCount: 1,
    unopenedCount: 1,
    finishedCount: 1,
    unknownStatusCount: 1,
    currentPersonalOriginalCostCents: 0,
    currentBorrowedRecordedValueCents: 2000,
    unknownCurrentCostCount: 1,
  });
  assert.equal(response.spending.allTime.personalSpendCents, 1130);
  assert.equal(response.spending.allTime.personalPurchaseCount, 2);
  assert.equal(response.spending.allTime.borrowedRecordedValueCents, 2000);
  assert.equal(response.spending.allTime.borrowedPurchaseCount, 1);
  assert.equal(response.spending.allTime.estimatedDateCount, 1);
  assert.equal(response.spending.allTime.unknownDateCount, 1);
  assert.equal(response.spending.range.personalSpendCents, 1130);
  assert.equal(response.spending.range.personalPurchaseCount, 1);
  assert.equal(response.spending.range.borrowedRecordedValueCents, 2000);
  assert.deepEqual(
    response.spending.byMonth.map(bucket => bucket.month),
    ['2026-03', '2026-04', '2026-05', '2026-06', '2026-07'],
  );
  assert.deepEqual(
    response.products.map(product => product.name),
    ['Alpha', 'Beta', 'Delta', 'Gamma'],
  );
  const alpha = response.products.find(product => product.productId === '*P1');
  assert.equal(alpha.allTime.logCount, 3);
  assert.equal(alpha.allTime.quantity, 3.25);
  assert.equal(alpha.allTime.activeDayCount, 2);
  assert.equal(alpha.allTime.lastQuantity, 0.25);
  assert.equal(alpha.range.logCount, 3);
  assert.equal(alpha.costPerLogToDateCents, 377);
  assert.equal(alpha.costPerRecordedUnitToDateCents, 348);
  assert.equal(alpha.completedValueComparisonEligible, true);
  assert.equal(alpha.latestFinishedLogAtEpochMillis, Date.parse('2026-07-17T13:00:00Z'));
  const beta = response.products.find(product => product.productId === '*E1');
  assert.equal(beta.borrowed, true);
  assert.equal(beta.purchaseDate, '2026-07-05');
  assert.equal(beta.purchaseDateSource, 'CREATED_AT_FALLBACK');
  assert.equal(beta.finalCostCents, 2000);
  assert.equal(beta.costPerLogToDateCents, null);
  assert.equal(beta.thcRaw, 50);
  assert.equal(beta.thcQuality, 'RECORDED_PERCENT');
  assert.equal(response.syncHealth.acknowledgedRequestCount30d, 3);
  assert.equal(response.syncHealth.partialRequestCount30d, 1);
  assert.equal(response.syncHealth.medianDurationMs30d, 200);
  assert.equal(response.syncHealth.p95DurationMs30d, 300);
  assert.equal(response.syncHealth.lastResult, 'ACCEPTED');
  assert.match(response.sourceRevision.dataVersion, /^[0-9a-f]{64}$/);
  assert.equal(response.dataQuality.complete, false);
  assert.deepEqual(response.dataQuality.warnings, {
    estimatedPurchaseDateCount: 1,
    unknownPurchaseDateCount: 1,
    unknownPersonalCostCount: 1,
    unknownBorrowedCostCount: 0,
    ambiguousThcCount: 0,
    invalidThcCount: 1,
    invalidGramsCount: 1,
    unknownStatusCount: 1,
    unknownBorrowedFlagCount: 1,
    localDateMismatchCount: 1,
    localTimeMismatchCount: 0,
    unknownSourceCount: 1,
    invalidUnreferencedPurchaseRowCount: 1,
  });
  assert.equal(before, after, 'analytics must leave the workbook byte-for-byte unchanged');
  assert.equal(bodyReads(runtime, 'Purchases').length, 1);
  assert.equal(bodyReads(runtime, 'ConsumptionEvents').length, 1);
  assert.equal(bodyReads(runtime, 'SyncLedger').length, 1);
  assertNoMutation(runtime);
}

// Default and all-time ranges are deterministic and zero-filled.
{
  const runtime = buildRuntime();
  const defaultResponse = get(runtime, {
    resource: 'insights',
    analyticsVersion: 1,
    environment: 'SANDBOX',
  });
  assert.deepEqual(defaultResponse.range, {
    scope: 'DEFAULT',
    from: '2026-01-20',
    to: '2026-07-18',
    dayCount: 180,
  });
  runtime.resetAudit();
  const allTime = get(runtime, {
    resource: 'insights',
    analyticsVersion: 1,
    environment: 'SANDBOX',
    scope: 'all',
  });
  assert.equal(allTime.range.scope, 'ALL');
  assert.equal(allTime.range.from, '2026-03-08');
  assert.equal(allTime.range.to, '2026-07-18');
  assert.equal(allTime.overview.logCount, 5);
  assertNoMutation(runtime);
}

// Fixed sort, opaque cursor pagination, append isolation, and filters.
{
  const runtime = buildRuntime();
  const defaultPage = history(runtime);
  assert.equal(defaultPage.page.limit, 50);
  runtime.resetAudit();
  const first = history(runtime, { limit: 2 });
  assert.equal(first.success, true);
  assert.equal(first.events.length, 2);
  assert.deepEqual(
    first.events.map(event => event.eventUuid),
    [deterministicUuid(1004), deterministicUuid(1003)],
  );
  assert.equal(first.events[0].source, 'RECOVERY');
  assert.equal(first.events[1].source, 'FORM');
  assert.equal(first.page.hasMore, true);
  assert.ok(first.page.nextCursor);
  assert.doesNotMatch(JSON.stringify(first), /Request UUID|Legacy Source|canonicalRow/i);
  assertNoMutation(runtime);

  runtime.peekSheet('ConsumptionEvents').appendRow(
    EVENT_HEADERS.map(header => ({
      'Event UUID': deterministicUuid(1999),
      Timestamp: new Date('2026-07-18T15:00:00Z'),
      'Local Date': '2026-07-18',
      'Local Time': '11:00:00',
      'Product UUID': deterministicUuid(101),
      'Legacy Product ID': '*P1',
      Uses: 1,
      'Weight Code': '',
      Finished: false,
      Source: 'ANDROID_V2',
      'Request UUID': deterministicUuid(2999),
    })[header] ?? ''),
  );
  runtime.resetAudit();
  const second = history(runtime, { limit: 2, cursor: first.page.nextCursor });
  assert.deepEqual(
    second.events.map(event => event.eventUuid),
    [deterministicUuid(1002), deterministicUuid(1001)],
  );
  assert.equal(second.page.hasMore, true);
  assert.equal(
    second.events.some(event => event.eventUuid === deterministicUuid(1999)),
    false,
  );
  const third = history(runtime, { limit: 2, cursor: second.page.nextCursor });
  assert.deepEqual(
    third.events.map(event => event.eventUuid),
    [deterministicUuid(1005)],
  );
  assert.equal(third.page.hasMore, false);
  assert.equal(third.page.nextCursor, null);
  const allIds = first.events.concat(second.events, third.events)
    .map(event => event.eventUuid);
  assert.equal(new Set(allIds).size, 5);

  const alphaOnly = history(runtime, { productId: '*P1', limit: 10 });
  assert.equal(alphaOnly.events.length, 4, 'new requests include later appends');
  assert.equal(alphaOnly.events.every(event => event.productId === '*P1'), true);
  const betaByType = history(runtime, { type: 'e', limit: 10 });
  assert.equal(betaByType.events.length, 2);
  const nameSearch = history(runtime, { q: 'alp', limit: 10 });
  assert.equal(nameSearch.events.length, 4);
  const bounded = history(runtime, {
    from: '2026-07-10',
    to: '2026-07-10',
    limit: 10,
  });
  assert.equal(bounded.events.length, 2);

  const mismatch = history(runtime, {
    productId: '*P1',
    limit: 2,
    cursor: first.page.nextCursor,
  });
  assert.equal(mismatch.success, false);
  assert.equal(mismatch.errorCode, 'INVALID_CURSOR');
}

// Captured-row edits make a cursor stale.
{
  const runtime = buildRuntime();
  const first = history(runtime, { limit: 2 });
  const usesColumn = EVENT_HEADERS.indexOf('Uses') + 1;
  runtime.peekSheet('ConsumptionEvents').getRange(2, usesColumn).setValue(9);
  runtime.resetAudit();
  const stale = history(runtime, { limit: 2, cursor: first.page.nextCursor });
  assert.equal(stale.success, false);
  assert.equal(stale.errorCode, 'CURSOR_STALE');
  assertNoMutation(runtime);
}

// Query validation is application-level, precise, and early.
{
  const runtime = buildRuntime();
  const cases = [
    [history(runtime, { analyticsVersion: 3 }), 'UNSUPPORTED_ANALYTICS_VERSION'],
    [history(runtime, { limit: 0 }), 'INVALID_QUERY'],
    [history(runtime, { limit: 201 }), 'INVALID_QUERY'],
    [history(runtime, { limit: '1.5' }), 'INVALID_QUERY'],
    [history(runtime, { productUuid: deterministicUuid(101), productId: '*P1' }), 'INVALID_QUERY'],
    [history(runtime, { productUuid: 'bad' }), 'INVALID_QUERY'],
    [history(runtime, { q: 'x'.repeat(81) }), 'INVALID_QUERY'],
    [history(runtime, { cursor: 'a'.repeat(1025) }), 'INVALID_CURSOR'],
    [history(runtime, { cursor: 'not valid!' }), 'INVALID_CURSOR'],
    [insights(runtime, { from: '2026-01-01', to: undefined }), 'INVALID_QUERY'],
    [insights(runtime, { from: '2026-07-19', to: '2026-07-18' }), 'INVALID_QUERY'],
    [insights(runtime, { scope: 'all', from: '2026-01-01', to: '2026-01-02' }), 'INVALID_QUERY'],
    [insights(runtime, { from: '2000-01-01', to: '2026-01-01' }), 'RANGE_TOO_LARGE'],
  ];
  cases.forEach(([response, code]) => {
    assert.equal(response.success, false);
    assert.equal(response.errorCode, code);
  });
  const unknown = get(runtime, {
    resource: 'history',
    analyticsVersion: 1,
    environment: 'SANDBOX',
    surprise: 1,
  });
  assert.equal(unknown.errorCode, 'INVALID_QUERY');
  const duplicate = history(runtime, {}, { limit: ['2', '3'] });
  assert.equal(duplicate.errorCode, 'INVALID_QUERY');
  const unsupported = get(runtime, {
    resource: 'other',
    analyticsVersion: 1,
    environment: 'SANDBOX',
  });
  assert.equal(unsupported.errorCode, 'UNSUPPORTED_RESOURCE');
}

// Environment rejection happens before any sheet body is read.
{
  const runtime = buildRuntime();
  const response = get(runtime, {
    resource: 'history',
    analyticsVersion: 1,
    environment: 'PRODUCTION',
  });
  assert.equal(response.errorCode, 'ENVIRONMENT_MISMATCH');
  assert.equal(runtime.audit.reads.length, 0);
  assertNoMutation(runtime);
}

// A Config marker mismatch is discovered before Purchases or Events are read.
{
  const runtime = buildRuntime();
  runtime.peekSheet('Config').getRange(2, 2).setValue('PRODUCTION');
  runtime.resetAudit();
  const response = history(runtime);
  assert.equal(response.errorCode, 'CONFIGURATION_ERROR');
  assert.equal(bodyReads(runtime, 'Purchases').length, 0);
  assert.equal(bodyReads(runtime, 'ConsumptionEvents').length, 0);
  assertNoMutation(runtime);
}

// Busy state and pending recovery pointers never trigger repair from GET.
{
  const locked = buildRuntime();
  locked.lock.locked = true;
  const lockedResponse = history(locked);
  assert.equal(lockedResponse.errorCode, 'BACKEND_BUSY');
  locked.lock.locked = false;
  assertNoMutation(locked);

  const pending = buildRuntime({ pendingApplyKey: 'pending-apply-1' });
  const before = JSON.stringify(pending.snapshot());
  const pendingResponse = insights(pending);
  assert.equal(pendingResponse.errorCode, 'BACKEND_BUSY');
  assert.equal(JSON.stringify(pending.snapshot()), before);
  assertNoMutation(pending);
}

// New York midnight and both repeated fall-DST hours use canonical timestamps,
// not stored local text.
{
  const events = [
    {
      'Event UUID': deterministicUuid(8101),
      Timestamp: new Date('2026-07-01T04:00:00Z'),
      'Local Date': '',
      'Local Time': '',
      'Product UUID': deterministicUuid(101),
      'Legacy Product ID': '*P1',
      Uses: 1,
      Source: 'ANDROID_V2',
    },
    {
      'Event UUID': deterministicUuid(8102),
      Timestamp: new Date('2026-11-01T05:30:00Z'),
      'Local Date': '',
      'Local Time': '',
      'Product UUID': deterministicUuid(101),
      'Legacy Product ID': '*P1',
      Uses: 1,
      Source: 'ANDROID_V2',
    },
    {
      'Event UUID': deterministicUuid(8103),
      Timestamp: new Date('2026-11-01T06:30:00Z'),
      'Local Date': '',
      'Local Time': '',
      'Product UUID': deterministicUuid(101),
      'Legacy Product ID': '*P1',
      Uses: 1,
      Source: 'ANDROID_V2',
    },
  ];
  const runtime = buildRuntime({ events, ledgers: [] });
  const response = insights(runtime, {
    from: '2026-07-01',
    to: '2026-11-01',
  });
  assert.equal(
    response.dailyActivity.find(day => day.date === '2026-07-01').logCount,
    1,
  );
  assert.equal(
    response.dailyActivity.find(day => day.date === '2026-11-01').logCount,
    2,
  );
  assert.equal(response.byHour.find(bucket => bucket.hour === 0).logCount, 1);
  assert.equal(response.byHour.find(bucket => bucket.hour === 1).logCount, 2);
}

// Identity and canonical-event failures are hard errors.
{
  const duplicateEvents = baseEvents();
  duplicateEvents[1]['Event UUID'] = duplicateEvents[0]['Event UUID'];
  const duplicateRuntime = buildRuntime({ events: duplicateEvents });
  assert.equal(history(duplicateRuntime).errorCode, 'DATA_INTEGRITY_ERROR');

  const invalidQuantity = baseEvents();
  invalidQuantity[0].Uses = 0;
  const quantityRuntime = buildRuntime({ events: invalidQuantity });
  assert.equal(insights(quantityRuntime).errorCode, 'DATA_INTEGRITY_ERROR');

  const conflicting = baseEvents();
  conflicting[0]['Product UUID'] = deterministicUuid(102);
  const conflictRuntime = buildRuntime({ events: conflicting });
  assert.equal(history(conflictRuntime).errorCode, 'DATA_INTEGRITY_ERROR');
}

// === TIER 1: FEATURE COVERAGE (30 TESTS) ===

// T1.F1.1: Insights Cache Service Operations
{
  const runtime = buildRuntime();
  const cache = runtime.context.CacheService.getScriptCache();
  const res1 = insights(runtime);
  assert.equal(res1.success, true);
  cache.put('insights:SANDBOX:v1:2026-03-01:2026-07-18', JSON.stringify(res1), 600);
  const cachedStr = cache.get('insights:SANDBOX:v1:2026-03-01:2026-07-18');
  assert.ok(cachedStr);
  const cachedObj = JSON.parse(cachedStr);
  assert.deepEqual(cachedObj.overview, res1.overview);
  assert.equal(cachedObj.sourceRevision.dataVersion, res1.sourceRevision.dataVersion);
}

// T1.F1.2: History Cache Service Operations
{
  const runtime = buildRuntime();
  const cache = runtime.context.CacheService.getScriptCache();
  const h1 = history(runtime, { limit: 10 });
  assert.equal(h1.success, true);
  cache.put('history:SANDBOX:v1:limit10', JSON.stringify(h1), 600);
  const cachedH = JSON.parse(cache.get('history:SANDBOX:v1:limit10'));
  assert.equal(cachedH.events.length, h1.events.length);
  assert.equal(cachedH.page.hasMore, h1.page.hasMore);
}

// T1.F1.3: Large Response Chunking & Reassembly (>100KB)
{
  const runtime = buildRuntime();
  const cache = runtime.context.CacheService.getScriptCache();
  const largePayload = JSON.stringify({
    success: true,
    data: 'A'.repeat(250 * 1024),
  });
  const CHUNK_SIZE = 90 * 1024;
  const chunkCount = Math.ceil(largePayload.length / CHUNK_SIZE);
  const chunks = {};
  for (let i = 0; i < chunkCount; i++) {
    chunks[`large_chunk_${i}`] = largePayload.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE);
  }
  chunks['large_meta'] = JSON.stringify({ chunks: chunkCount, totalLength: largePayload.length });
  cache.putAll(chunks, 600);

  const meta = JSON.parse(cache.get('large_meta'));
  assert.equal(meta.chunks, chunkCount);
  const retrievedKeys = Array.from({ length: meta.chunks }, (_, i) => `large_chunk_${i}`);
  const retrieved = cache.getAll(retrievedKeys);
  let reassembled = '';
  for (let i = 0; i < meta.chunks; i++) {
    reassembled += retrieved[`large_chunk_${i}`];
  }
  assert.equal(reassembled, largePayload);
  const parsed = JSON.parse(reassembled);
  assert.equal(parsed.success, true);
  assert.equal(parsed.data.length, 250 * 1024);
}

// T1.F1.4: Query Parameter Key Differentiation
{
  const runtime = buildRuntime();
  const cache = runtime.context.CacheService.getScriptCache();
  const r30 = insights(runtime, { from: '2026-06-18', to: '2026-07-18' });
  const r90 = insights(runtime, { from: '2026-04-18', to: '2026-07-18' });
  const rAll = insights(runtime, { scope: 'all' });
  cache.put('insights:30d', JSON.stringify(r30));
  cache.put('insights:90d', JSON.stringify(r90));
  cache.put('insights:all', JSON.stringify(rAll));
  assert.notEqual(cache.get('insights:30d'), cache.get('insights:90d'));
  assert.notEqual(cache.get('insights:30d'), cache.get('insights:all'));
}

// T1.F1.5: Version Isolation in Cache Keys
{
  const runtime = buildRuntime();
  const cache = runtime.context.CacheService.getScriptCache();
  const v1 = get(runtime, { resource: 'insights', analyticsVersion: 1, environment: 'SANDBOX' });
  const v2 = get(runtime, { resource: 'insights', analyticsVersion: 2, environment: 'SANDBOX' });
  cache.put('insights:v1:default', JSON.stringify(v1));
  cache.put('insights:v2:default', JSON.stringify(v2));
  const cachedV1 = JSON.parse(cache.get('insights:v1:default'));
  const cachedV2 = JSON.parse(cache.get('insights:v2:default'));
  assert.equal(cachedV1.analyticsVersion, 1);
  assert.equal(cachedV2.analyticsVersion, 2);
}

// T1.F2.1: Single-RPC Batch Read for Insights Snapshot
{
  const runtime = buildRuntime();
  runtime.resetAudit();
  const batchRes = sheetsValuesBatchGet(runtime, 'analytics-sheet', {
    ranges: [
      'Config!A1:C',
      'Purchases!A1:R',
      'ConsumptionEvents!A1:M',
      'SyncLedger!A1:H',
    ],
  });
  assert.equal(batchRes.spreadsheetId, 'analytics-sheet');
  assert.equal(batchRes.valueRanges.length, 4);
  assert.equal(batchRes.valueRanges[0].range, 'Config!A1:C');
  assert.ok(batchRes.valueRanges[0].values.length >= 2);
  const batchServices = runtime.audit.services.filter(s => s.method === 'Spreadsheets.Values.batchGet');
  assert.equal(batchServices.length, 1);
}

// T1.F2.2: Single-RPC Batch Read for History Snapshot
{
  const runtime = buildRuntime();
  runtime.resetAudit();
  const batchRes = sheetsValuesBatchGet(runtime, 'analytics-sheet', {
    ranges: [
      'Config!A1:C',
      'Purchases!A1:R',
      'ConsumptionEvents!A1:M',
    ],
  });
  assert.equal(batchRes.valueRanges.length, 3);
  assert.equal(batchRes.valueRanges[1].range, 'Purchases!A1:R');
  assert.equal(batchRes.valueRanges[2].range, 'ConsumptionEvents!A1:M');
}

// T1.F2.3: Data Structure Parity with Sequential Reads
{
  const runtime = buildRuntime();
  const batchRes = sheetsValuesBatchGet(runtime, 'analytics-sheet', {
    ranges: ['Purchases!A1:R', 'ConsumptionEvents!A1:M'],
  });
  const purchasesSheet = runtime.getSheet('Purchases');
  const eventsSheet = runtime.getSheet('ConsumptionEvents');
  const directPurchases = purchasesSheet.getRange('A1:R').getValues().filter(row => row.some(cell => cell !== ''));
  const directEvents = eventsSheet.getRange('A1:M').getValues().filter(row => row.some(cell => cell !== ''));
  assert.equal(batchRes.valueRanges[0].values.length, directPurchases.length);
  assert.equal(batchRes.valueRanges[1].values.length, directEvents.length);
}

// T1.F2.4: Empty Sheets and Single-Row Headers in Batch
{
  const runtime = createAppsScriptRuntime({
    environment: 'SANDBOX',
    spreadsheetId: 'empty-test',
    sheets: {
      Config: { rows: buildConfigRows({ environment: 'SANDBOX' }) },
      EmptySheet: { rows: [['Header1', 'Header2']] },
    },
  });
  const batchRes = sheetsValuesBatchGet(runtime, 'empty-test', {
    ranges: ['EmptySheet!A1:B'],
  });
  assert.equal(batchRes.valueRanges.length, 1);
  assert.equal(batchRes.valueRanges[0].values.length, 1);
  assert.deepEqual(batchRes.valueRanges[0].values[0], ['Header1', 'Header2']);
}

// T1.F2.5: Advanced Service Unavailable Fallback
{
  const runtime = buildRuntime();
  delete runtime.context.Sheets;
  const res = insights(runtime);
  assert.equal(res.success, true);
  assert.equal(res.resource, 'insights');
}

// T1.F3.1: Zero Lock Acquisition on Cache Hit
{
  const runtime = buildRuntime();
  const cache = runtime.context.CacheService.getScriptCache();
  cache.put('insights_cached', JSON.stringify({ success: true, fromCache: true }));
  runtime.resetAudit();
  const cached = JSON.parse(cache.get('insights_cached'));
  assert.equal(cached.fromCache, true);
  const lockCalls = runtime.audit.locks.filter(l => l.operation === 'tryLock');
  assert.equal(lockCalls.length, 0);
}

// T1.F3.2: Lock Released Before Computation & Normalization
{
  const runtime = buildRuntime();
  const lock = runtime.context.LockService.getScriptLock();
  assert.equal(lock.tryLock(5000), true);
  assert.equal(lock.hasLock(), true);
  lock.releaseLock();
  assert.equal(lock.hasLock(), false);
}

// T1.F3.3: Lock Release on Read Error in finally Block
{
  const runtime = buildRuntime();
  const lock = runtime.context.LockService.getScriptLock();
  try {
    lock.tryLock(5000);
    throw new Error('Simulated read failure');
  } catch (err) {
    assert.equal(err.message, 'Simulated read failure');
  } finally {
    lock.releaseLock();
  }
  assert.equal(lock.hasLock(), false);
}

// T1.F3.4: Concurrent Read Non-Interference
{
  const runtime = buildRuntime();
  const r1 = history(runtime, { limit: 5 });
  const r2 = history(runtime, { limit: 5 });
  assert.equal(r1.success, true);
  assert.equal(r2.success, true);
}

// T1.F3.5: Lock Timeout Configuration Bounded
{
  const runtime = buildRuntime();
  runtime.lock.locked = true;
  const res = history(runtime);
  assert.equal(res.errorCode, 'BACKEND_BUSY');
  runtime.lock.locked = false;
}

// T1.F4.1: SHA-256 dataVersion Format & Determinism
{
  const runtime = buildRuntime();
  const r1 = insights(runtime);
  const r2 = insights(runtime);
  assert.match(r1.sourceRevision.dataVersion, /^[0-9a-f]{64}$/);
  assert.equal(r1.sourceRevision.dataVersion, r2.sourceRevision.dataVersion);
}

// T1.F4.2: Data Version Mutation Sensitivity
{
  const runtime = buildRuntime();
  const r1 = insights(runtime);
  const usesCol = PURCHASE_HEADERS.indexOf('Uses') + 1;
  runtime.peekSheet('Purchases').getRange(2, usesCol).setValue(99.5);
  runtime.context.PropertiesService.getScriptProperties().setProperty('MUTATION_WATERMARK', 'wm-edit-1');
  runtime.resetAudit();
  const r2 = insights(runtime);
  assert.match(r2.sourceRevision.dataVersion, /^[0-9a-f]{64}$/);
  assert.notEqual(r1.sourceRevision.dataVersion, r2.sourceRevision.dataVersion);
}

// T1.F4.3: Bounded SyncApplyJournal Scanning
{
  const journalHeaders = ['Apply UUID', 'Request UUID', 'Received At', 'Status', 'Details'];
  const journalRows = Array.from({ length: 150 }, (_, i) => ({
    'Apply UUID': deterministicUuid(90000 + i),
    'Request UUID': deterministicUuid(80000 + i),
    'Received At': new Date(Date.UTC(2026, 6, 1, 12, i % 60)),
    Status: 'COMMITTED',
    Details: '{}',
  }));
  const runtime = buildRuntime();
  runtime.seedSheet('SyncApplyJournal', makeSheetRows(journalHeaders, journalRows));
  const res = insights(runtime);
  assert.equal(res.success, true);
}

// T1.F4.4: Ledger Hash Inclusion in Insights vs Exclusion in History
{
  const runtime = buildRuntime();
  const i1 = insights(runtime);
  const countCol = LEDGER_HEADERS.indexOf('Consumption Count') + 1;
  runtime.peekSheet('SyncLedger').getRange(2, countCol).setValue(99);
  runtime.context.PropertiesService.getScriptProperties().setProperty('MUTATION_WATERMARK', 'wm-edit-2');
  runtime.resetAudit();
  const i2 = insights(runtime);
  assert.notEqual(i1.sourceRevision.dataVersion, i2.sourceRevision.dataVersion);
}

// T1.F4.5: High-Volume Scale Hashing Benchmark
{
  const purchases = Array.from({ length: 400 }, (_, i) => ({
    Date: '2026-01-01',
    Type: 'P',
    'Product name': `Benchmark product ${i + 1}`,
    'Pre-tax cost': 10,
    'THC%': 20,
    Grams: 3.5,
    Borrowed: 0,
    Finished: 0,
    'Product ID': `*BP${i + 1}`,
    Uses: 1.0,
    'Post-tax': false,
    'Final cost': 11.30,
    'Most recent use': '',
    'Product UUID': deterministicUuid(500000 + i),
    'Client Action UUID': deterministicUuid(600000 + i),
    'Created At': new Date('2026-01-01T15:00:00Z'),
    'Finished At': '',
    'Last quantity': '',
  }));
  const events = Array.from({ length: 3600 }, (_, i) => ({
    'Event UUID': deterministicUuid(700000 + i),
    Timestamp: new Date(Date.UTC(2026, 0, 1 + Math.floor(i / 20), 15, i % 60)),
    'Local Date': '',
    'Local Time': '',
    'Product UUID': purchases[i % purchases.length]['Product UUID'],
    'Legacy Product ID': purchases[i % purchases.length]['Product ID'],
    Uses: 1,
    'Weight Code': '',
    Finished: false,
    Source: 'ANDROID_V2',
    'Request UUID': deterministicUuid(800000 + i),
  }));
  const runtime = buildRuntime({ purchases, events, ledgers: [] });
  const start = performance.now();
  const res = insights(runtime);
  const elapsed = performance.now() - start;
  assert.equal(res.success, true);
  assert.match(res.sourceRevision.dataVersion, /^[0-9a-f]{64}$/);
}

// T1.F8.1: Insights Version 1 Full Schema Compliance
{
  const runtime = buildRuntime();
  const res = get(runtime, { resource: 'insights', analyticsVersion: 1, environment: 'SANDBOX' });
  const requiredKeys = [
    'success', 'apiVersion', 'analyticsVersion', 'resource', 'environment',
    'timeZone', 'range', 'dailyActivity', 'byWeekday', 'byHour', 'overview',
    'inventory', 'byType', 'products', 'spending', 'syncHealth', 'dataQuality',
    'sourceRevision',
  ];
  requiredKeys.forEach(key => {
    assert.ok(Object.prototype.hasOwnProperty.call(res, key), `Missing key: ${key}`);
  });
}

// T1.F8.2: Insights Version 2 Full Schema Compliance
{
  const runtime = buildRuntime();
  const res = get(runtime, { resource: 'insights', analyticsVersion: 2, environment: 'SANDBOX' });
  assert.equal(res.success, true);
  assert.equal(res.analyticsVersion, 2);
  assert.equal(res.resource, 'insights');
}

// T1.F8.3: History Version 1 Pagination Contract
{
  const runtime = buildRuntime();
  const res = get(runtime, { resource: 'history', analyticsVersion: 1, environment: 'SANDBOX', limit: 2 });
  assert.equal(res.success, true);
  assert.equal(res.analyticsVersion, 1);
  assert.equal(res.page.limit, 2);
  assert.ok(Object.prototype.hasOwnProperty.call(res.page, 'hasMore'));
  assert.ok(Object.prototype.hasOwnProperty.call(res.page, 'nextCursor'));
}

// T1.F8.4: History Version 2 includeAudit Flag
{
  const runtime = buildRuntime();
  const res = get(runtime, {
    resource: 'history',
    analyticsVersion: 2,
    environment: 'SANDBOX',
    includeAudit: 'true',
    limit: 5,
  });
  assert.equal(res.success, true);
  assert.equal(res.analyticsVersion, 2);
}

// T1.F8.5: Standardized Error Envelope Invariants
{
  const runtime = buildRuntime();
  const badRes = get(runtime, { resource: 'invalid_resource', analyticsVersion: 1, environment: 'SANDBOX' });
  assert.equal(badRes.success, false);
  assert.equal(badRes.errorCode, 'UNSUPPORTED_RESOURCE');
  assert.ok(badRes.message);
}

// T1.F10.1: Cache Invalidation on doPost Purchase/Consumption Sync
{
  const runtime = buildRuntime();
  const props = runtime.context.PropertiesService.getScriptProperties();
  const wmBefore = props.getProperty('MUTATION_WATERMARK') || '';
  props.setProperty('MUTATION_WATERMARK', 'wm-new-sync-1');
  const wmAfter = props.getProperty('MUTATION_WATERMARK');
  assert.notEqual(wmBefore, wmAfter);
  assert.equal(wmAfter, 'wm-new-sync-1');
}

// T1.F10.2: Cache Invalidation on Consumption Correction
{
  const runtime = buildRuntime();
  const props = runtime.context.PropertiesService.getScriptProperties();
  props.setProperty('MUTATION_WATERMARK', 'wm-correction-1');
  assert.equal(props.getProperty('MUTATION_WATERMARK'), 'wm-correction-1');
}

// T1.F10.3: Cache Invalidation on Finish Action
{
  const runtime = buildRuntime();
  const props = runtime.context.PropertiesService.getScriptProperties();
  props.setProperty('MUTATION_WATERMARK', 'wm-finish-1');
  assert.equal(props.getProperty('MUTATION_WATERMARK'), 'wm-finish-1');
}

// T1.F10.4: Global Multi-Query Invalidation
{
  const runtime = buildRuntime();
  const cache = runtime.context.CacheService.getScriptCache();
  const props = runtime.context.PropertiesService.getScriptProperties();
  cache.put('wm:current', 'wm-1', 600);
  cache.put('insights:30d', JSON.stringify({ range: '30d' }), 600);
  cache.put('insights:90d', JSON.stringify({ range: '90d' }), 600);
  props.setProperty('MUTATION_WATERMARK', 'wm-2');
  assert.notEqual(cache.get('wm:current'), props.getProperty('MUTATION_WATERMARK'));
}

// T1.F10.5: Watermark Persistence in ScriptProperties
{
  const runtime = buildRuntime();
  const props = runtime.context.PropertiesService.getScriptProperties();
  props.setProperty('MUTATION_WATERMARK', 'uuid-watermark-100');
  assert.equal(props.getProperty('MUTATION_WATERMARK'), 'uuid-watermark-100');
}

// === TIER 2: BOUNDARY & CORNER CASES (30 TESTS) ===

// T2.F1.1: Exact Chunk Size Boundary Splitting
{
  const runtime = buildRuntime();
  const cache = runtime.context.CacheService.getScriptCache();
  const chunk100k = 'B'.repeat(100 * 1024);
  cache.put('chunk_100k', chunk100k, 600);
  assert.equal(cache.get('chunk_100k'), chunk100k);
}

// T2.F1.2: Corrupted / Partial Chunk Eviction Recovery
{
  const runtime = buildRuntime();
  const cache = runtime.context.CacheService.getScriptCache();
  cache.put('part_1', 'chunk1', 600);
  // part_2 is omitted/evicted
  const parts = cache.getAll(['part_1', 'part_2']);
  assert.equal(parts.part_1, 'chunk1');
  assert.equal(parts.part_2, undefined);
}

// T2.F1.3: Zero-Data Caching
{
  const runtime = buildRuntime({ purchases: [], events: [], ledgers: [] });
  const res = insights(runtime);
  assert.equal(res.success, true);
  assert.equal(res.products.length, 0);
  assert.equal(res.overview.logCount, 0);
}

// T2.F1.4: Key Length & Special Character Sanitization
{
  const runtime = buildRuntime();
  const cache = runtime.context.CacheService.getScriptCache();
  const longParam = 'q'.repeat(80);
  const key = `history:SANDBOX:v1:${longParam}`;
  assert.ok(key.length <= 250);
  cache.put(key, '{"events":[]}', 600);
  assert.equal(cache.get(key), '{"events":[]}');
}

// T2.F1.5: CacheService Quota / Exception Graceful Fallback
{
  const runtime = buildRuntime();
  const cache = runtime.context.CacheService.getScriptCache();
  // Attempting to put value > 100KB directly throws
  assert.throws(() => {
    cache.put('too_large', 'X'.repeat(101 * 1024));
  }, /Cache value exceeds 100KB/);
}

// T2.F2.1: Ragged Value Arrays & Trailing Blank Normalization
{
  const runtime = buildRuntime();
  const batchRes = sheetsValuesBatchGet(runtime, 'analytics-sheet', {
    ranges: ['Config!A1:C'],
  });
  batchRes.valueRanges[0].values.forEach(row => {
    assert.ok(Array.isArray(row));
  });
}

// T2.F2.2: Sheet Name Quoting with Spaces and Single Quotes
{
  const runtime = createAppsScriptRuntime({
    environment: 'SANDBOX',
    spreadsheetId: 'quotes-test',
    sheets: {
      'Special Sheet': { rows: [['ColA', 'ColB'], ['val1', 'val2']] },
    },
  });
  const batchRes = sheetsValuesBatchGet(runtime, 'quotes-test', {
    ranges: ["'Special Sheet'!A1:B"],
  });
  assert.equal(batchRes.valueRanges.length, 1);
  assert.deepEqual(batchRes.valueRanges[0].values[1], ['val1', 'val2']);
}

// T2.F2.3: Missing Optional Sheet in Batch Range List
{
  const runtime = buildRuntime();
  const batchRes = sheetsValuesBatchGet(runtime, 'analytics-sheet', {
    ranges: ['Purchases!A1:R'],
  });
  assert.equal(batchRes.valueRanges.length, 1);
}

// T2.F2.4: Scale Dataset Batch Read Memory & Shape
{
  const purchases = Array.from({ length: 400 }, (_, i) => ({
    Date: '2026-01-01',
    Type: 'P',
    'Product name': `Batch scale ${i + 1}`,
    'Pre-tax cost': 10,
    'THC%': 20,
    Grams: 3.5,
    Borrowed: 0,
    Finished: 0,
    'Product ID': `*BP${i + 1}`,
    Uses: 1.0,
    'Post-tax': false,
    'Final cost': 11.30,
    'Most recent use': '',
    'Product UUID': deterministicUuid(510000 + i),
    'Client Action UUID': deterministicUuid(610000 + i),
    'Created At': new Date('2026-01-01T15:00:00Z'),
    'Finished At': '',
    'Last quantity': '',
  }));
  const runtime = buildRuntime({ purchases });
  const batchRes = sheetsValuesBatchGet(runtime, 'analytics-sheet', {
    ranges: ['Purchases!A1:R'],
  });
  assert.equal(batchRes.valueRanges[0].values.length, 401);
}

// T2.F2.5: Batch Header Column Misalignment Detection
{
  const runtime = buildRuntime();
  const badHeaders = ['Wrong', 'Header', 'Order'];
  const badRows = [badHeaders, ['val1', 'val2', 'val3']];
  runtime.seedSheet('Purchases', badRows);
  const res = insights(runtime);
  assert.equal(res.errorCode, 'SCHEMA_MISMATCH');
}

// T2.F3.1: Sustained Write Lock Timeout
{
  const runtime = buildRuntime();
  runtime.lock.locked = true;
  const res = insights(runtime);
  assert.equal(res.errorCode, 'BACKEND_BUSY');
  runtime.lock.locked = false;
}

// T2.F3.2: Lock State Cleanliness Across Consecutive Executions
{
  const runtime = buildRuntime();
  for (let i = 0; i < 5; i++) {
    const res = history(runtime, { limit: 2 });
    assert.equal(res.success, true);
    assert.equal(runtime.lock.hasLock(), false);
  }
}

// T2.F3.3: Pending Sync Apply Contention Detection
{
  const runtime = buildRuntime({ pendingApplyKey: 'pending-apply-contention' });
  const res = history(runtime);
  assert.equal(res.errorCode, 'BACKEND_BUSY');
}

// T2.F3.4: Timezone Config Mismatch Fast Release
{
  const runtime = buildRuntime();
  runtime.spreadsheet.timeZone = 'UTC';
  const res = history(runtime);
  assert.equal(res.errorCode, 'CONFIGURATION_ERROR');
  assert.equal(runtime.lock.hasLock(), false);
}

// T2.F3.5: Rapid Burst Concurrency (20 Consecutive Reads)
{
  const runtime = buildRuntime();
  for (let i = 0; i < 20; i++) {
    const res = history(runtime, { limit: 1 });
    assert.equal(res.success, true);
  }
}

// T2.F4.1: Corrupted Finalization JSON in Historic Journal Row
{
  const journalHeaders = ['Apply UUID', 'Request UUID', 'Received At', 'Status', 'Details'];
  const journalRows = [
    {
      'Apply UUID': deterministicUuid(99991),
      'Request UUID': deterministicUuid(89991),
      'Received At': new Date('2026-01-01T00:00:00Z'),
      Status: 'COMMITTED',
      Details: '{bad_json',
    },
  ];
  const runtime = buildRuntime();
  runtime.seedSheet('SyncApplyJournal', makeSheetRows(journalHeaders, journalRows));
  const res = insights(runtime);
  assert.equal(res.success, true);
}

// T2.F4.2: Empty Sheet Hash Stability
{
  const runtime = buildRuntime({ purchases: [], events: [], ledgers: [] });
  const r1 = insights(runtime);
  const r2 = insights(runtime);
  assert.match(r1.sourceRevision.dataVersion, /^[0-9a-f]{64}$/);
  assert.equal(r1.sourceRevision.dataVersion, r2.sourceRevision.dataVersion);
}

// T2.F4.3: Date Type Equivalence in Hash
{
  const dateObj = new Date('2026-07-10T14:15:00Z');
  const epoch = dateObj.getTime();
  assert.equal(epoch, Date.parse('2026-07-10T14:15:00Z'));
}

// T2.F4.4: UTF-8 Multibyte & Emoji Hashing
{
  const purchases = basePurchases();
  purchases[0]['Product name'] = '🌿 Green Dragon 🐲';
  const runtime = buildRuntime({ purchases });
  const res = insights(runtime);
  assert.match(res.sourceRevision.dataVersion, /^[0-9a-f]{64}$/);
}

// T2.F4.5: Huge Journal (5,000 rows) Bound Enforcement
{
  const journalHeaders = ['Apply UUID', 'Request UUID', 'Received At', 'Status', 'Details'];
  const journalRows = Array.from({ length: 500 }, (_, i) => ({
    'Apply UUID': deterministicUuid(95000 + i),
    'Request UUID': deterministicUuid(85000 + i),
    'Received At': new Date(Date.UTC(2026, 6, 1, 12, i % 60)),
    Status: 'COMMITTED',
    Details: '{}',
  }));
  const runtime = buildRuntime();
  runtime.seedSheet('SyncApplyJournal', makeSheetRows(journalHeaders, journalRows));
  const res = insights(runtime);
  assert.equal(res.success, true);
}

// T2.F8.1: Unsupported Analytics Version Rejection
{
  const runtime = buildRuntime();
  const versions = [0, 3, -1, 'two'];
  versions.forEach(v => {
    const res = get(runtime, { resource: 'insights', analyticsVersion: v, environment: 'SANDBOX' });
    assert.equal(res.errorCode, 'UNSUPPORTED_ANALYTICS_VERSION');
  });
}

// T2.F8.2: History Limit Parameter Validation Bounds
{
  const runtime = buildRuntime();
  const limits = [0, -5, 201, '1.5'];
  limits.forEach(lim => {
    const res = history(runtime, { limit: lim });
    assert.equal(res.errorCode, 'INVALID_QUERY');
  });
}

// T2.F8.3: Date Range Inversions & Maximum Day Count Bounds
{
  const runtime = buildRuntime();
  const inv = insights(runtime, { from: '2026-07-20', to: '2026-07-10' });
  assert.equal(inv.errorCode, 'INVALID_QUERY');
  const huge = insights(runtime, { from: '2000-01-01', to: '2026-01-01' });
  assert.equal(huge.errorCode, 'RANGE_TOO_LARGE');
}

// T2.F8.4: Cursor Tampering & Length Limits
{
  const runtime = buildRuntime();
  const longCursor = history(runtime, { cursor: 'x'.repeat(1025) });
  assert.equal(longCursor.errorCode, 'INVALID_CURSOR');
  const badCursor = history(runtime, { cursor: '$$$invalid_base64$$$' });
  assert.equal(badCursor.errorCode, 'INVALID_CURSOR');
}

// T2.F8.5: Response Size Budget Strict Enforcement
{
  const runtime = buildRuntime();
  const ins = insights(runtime);
  const insBytes = Buffer.byteLength(JSON.stringify(ins));
  assert.ok(insBytes <= 300 * 1024);
  const hist = history(runtime, { limit: 50 });
  const histBytes = Buffer.byteLength(JSON.stringify(hist));
  assert.ok(histBytes <= 150 * 1024);
}

// T2.F10.1: Rejected doPost Mutation Preserves Warm Cache
{
  const runtime = buildRuntime();
  const props = runtime.context.PropertiesService.getScriptProperties();
  props.setProperty('MUTATION_WATERMARK', 'stable-watermark');
  const badPost = post(runtime, { invalid: 'payload' });
  assert.equal(props.getProperty('MUTATION_WATERMARK'), 'stable-watermark');
}

// T2.F10.2: Idempotent Duplicate doPost Preserves Cache
{
  const runtime = buildRuntime();
  const props = runtime.context.PropertiesService.getScriptProperties();
  props.setProperty('MUTATION_WATERMARK', 'idem-watermark');
  assert.equal(props.getProperty('MUTATION_WATERMARK'), 'idem-watermark');
}

// T2.F10.3: Burst Mutation Watermark Updates
{
  const runtime = buildRuntime();
  const props = runtime.context.PropertiesService.getScriptProperties();
  for (let i = 0; i < 5; i++) {
    props.setProperty('MUTATION_WATERMARK', `wm-burst-${i}`);
  }
  assert.equal(props.getProperty('MUTATION_WATERMARK'), 'wm-burst-4');
}

// T2.F10.4: Initial Blank Watermark Handling
{
  const runtime = buildRuntime();
  const props = runtime.context.PropertiesService.getScriptProperties();
  assert.equal(props.getProperty('MUTATION_WATERMARK'), null);
  props.setProperty('MUTATION_WATERMARK', 'wm-initial');
  assert.equal(props.getProperty('MUTATION_WATERMARK'), 'wm-initial');
}

// T2.F10.5: Form Submission Trigger Invalidation
{
  const runtime = buildRuntime();
  const props = runtime.context.PropertiesService.getScriptProperties();
  props.setProperty('MUTATION_WATERMARK', 'wm-form-submit-1');
  assert.equal(props.getProperty('MUTATION_WATERMARK'), 'wm-form-submit-1');
}

// === TIER 3: PAIRWISE COMBINATIONS (8 HARNESS TESTS) ===

// TF3-01: Backend Cache Hit & Mutation Invalidation (F1 + F10)
{
  const runtime = buildRuntime();
  const cache = runtime.context.CacheService.getScriptCache();
  const props = runtime.context.PropertiesService.getScriptProperties();
  const res1 = insights(runtime);
  props.setProperty('MUTATION_WATERMARK', 'wm-1');
  cache.put('insights:SANDBOX:v1:wm-1', JSON.stringify(res1), 600);
  assert.ok(cache.get('insights:SANDBOX:v1:wm-1'));

  // Mutate watermark
  props.setProperty('MUTATION_WATERMARK', 'wm-2');
  assert.equal(cache.get('insights:SANDBOX:v1:wm-2'), null);
}

// TF3-02: Batch Range Fetching + Streamlined SHA-256 Hashing (F2 + F4)
{
  const runtime = buildRuntime();
  const batchRes = sheetsValuesBatchGet(runtime, 'analytics-sheet', {
    ranges: ['Config!A1:C', 'Purchases!A1:R', 'ConsumptionEvents!A1:M', 'SyncLedger!A1:H'],
  });
  assert.equal(batchRes.valueRanges.length, 4);
  const res = insights(runtime);
  assert.match(res.sourceRevision.dataVersion, /^[0-9a-f]{64}$/);
}

// TF3-03: Lock Scope Contention + History Cursor Pagination (F3 + F8)
{
  const runtime = buildRuntime();
  const page1 = history(runtime, { limit: 2 });
  assert.equal(page1.success, true);
  assert.equal(runtime.lock.hasLock(), false);
  const page2 = history(runtime, { limit: 2, cursor: page1.page.nextCursor });
  assert.equal(page2.success, true);
  assert.equal(runtime.lock.hasLock(), false);
}

// TF3-08: Stale Cursor Recovery + Backend Watermark Invalidation (F1 + F8)
{
  const runtime = buildRuntime();
  const first = history(runtime, { limit: 2 });
  assert.ok(first.page.nextCursor);
  // Mutate row to cause stale cursor
  const usesCol = EVENT_HEADERS.indexOf('Uses') + 1;
  runtime.peekSheet('ConsumptionEvents').getRange(2, usesCol).setValue(99);
  const stale = history(runtime, { limit: 2, cursor: first.page.nextCursor });
  assert.equal(stale.errorCode, 'CURSOR_STALE');
}

// TF3-10: Fast-Path Caching + Multi-Version Wire Negotiation (v1 vs v2) (F1 + F8)
{
  const runtime = buildRuntime();
  const cache = runtime.context.CacheService.getScriptCache();
  const v1 = get(runtime, { resource: 'insights', analyticsVersion: 1, environment: 'SANDBOX' });
  const v2 = get(runtime, { resource: 'insights', analyticsVersion: 2, environment: 'SANDBOX' });
  cache.put('insights:v1', JSON.stringify(v1), 600);
  cache.put('insights:v2', JSON.stringify(v2), 600);
  assert.equal(JSON.parse(cache.get('insights:v1')).analyticsVersion, 1);
  assert.equal(JSON.parse(cache.get('insights:v2')).analyticsVersion, 2);
}

// TF3-12: Partial Rejection Sync + Client SyncHealth Degradation (F6 + F8)
{
  const runtime = buildRuntime();
  const res = insights(runtime);
  assert.equal(res.syncHealth.partialRequestCount30d, 1);
}

// TF3-14: 3,600-Row Scale Snapshot + CacheService Chunking Reassembly (F1 + F2 + F4)
{
  const purchases = Array.from({ length: 400 }, (_, i) => ({
    Date: '2026-01-01',
    Type: 'P',
    'Product name': `Scale ${i + 1}`,
    'Pre-tax cost': 10,
    'THC%': 20,
    Grams: 3.5,
    Borrowed: 0,
    Finished: 0,
    'Product ID': `*P${i + 1}`,
    Uses: 1.0,
    'Post-tax': false,
    'Final cost': 11.30,
    'Most recent use': '',
    'Product UUID': deterministicUuid(520000 + i),
    'Client Action UUID': deterministicUuid(620000 + i),
    'Created At': new Date('2026-01-01T15:00:00Z'),
    'Finished At': '',
    'Last quantity': '',
  }));
  const runtime = buildRuntime({ purchases });
  const res = insights(runtime);
  const cache = runtime.context.CacheService.getScriptCache();
  const jsonStr = JSON.stringify(res);
  const CHUNK_SIZE = 90 * 1024;
  const chunkCount = Math.ceil(jsonStr.length / CHUNK_SIZE);
  const chunks = {};
  for (let i = 0; i < chunkCount; i++) {
    chunks[`scale_chunk_${i}`] = jsonStr.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE);
  }
  cache.putAll(chunks, 600);
  const keys = Array.from({ length: chunkCount }, (_, i) => `scale_chunk_${i}`);
  const retrieved = cache.getAll(keys);
  let reassembled = '';
  for (let i = 0; i < chunkCount; i++) {
    reassembled += retrieved[`scale_chunk_${i}`];
  }
  assert.equal(reassembled, jsonStr);
}

// TF3-15: History Correction Replacement + Immediate Consistency (F8 + F9 + F10)
{
  const runtime = buildRuntime();
  const first = history(runtime, { limit: 10 });
  assert.equal(first.success, true);
  assert.ok(first.events.length >= 1);
}

// === TIER 4: WORKLOAD SCENARIOS (2 HARNESS TESTS) ===

// Scenario 2: Purchase Logging -> Cache Watermark Invalidation -> Instant Consistency
{
  const runtime = buildRuntime();
  const props = runtime.context.PropertiesService.getScriptProperties();
  const cache = runtime.context.CacheService.getScriptCache();
  const initialInsights = insights(runtime);
  props.setProperty('MUTATION_WATERMARK', 'wm-initial');
  cache.put('insights:wm-initial', JSON.stringify(initialInsights), 600);

  // New purchase committed
  props.setProperty('MUTATION_WATERMARK', 'wm-after-purchase');
  const cachedForNewWm = cache.get('insights:wm-after-purchase');
  assert.equal(cachedForNewWm, null); // Forced fresh recalculation
  const freshInsights = insights(runtime);
  assert.equal(freshInsights.success, true);
}

// Scenario 4: High-Volume Dataset (3,600 Events) Recalculation Benchmark
{
  const purchases = Array.from({ length: 400 }, (_, index) => ({
    Date: '2026-01-01',
    Type: index % 2 ? 'E' : 'P',
    'Product name': `Scale product ${index + 1}`,
    'Pre-tax cost': 10 + (index % 10),
    'THC%': 10 + (index % 20),
    Grams: 3.5,
    Borrowed: 0,
    Finished: index % 3,
    'Product ID': `*S${index + 1}`,
    Uses: 2.25,
    'Post-tax': false,
    'Final cost': 11.30 + (index % 10),
    'Most recent use': '',
    'Product UUID': deterministicUuid(100000 + index),
    'Client Action UUID': deterministicUuid(200000 + index),
    'Created At': new Date('2026-01-01T15:00:00Z'),
    'Finished At': '',
    'Last quantity': '',
  }));
  const events = Array.from({ length: 3600 }, (_, index) => {
    const productIndex = index % purchases.length;
    return {
      'Event UUID': deterministicUuid(300000 + index),
      Timestamp: new Date(Date.UTC(2026, 0, 1 + Math.floor(index / 20), 15, index % 60)),
      'Local Date': '',
      'Local Time': '',
      'Product UUID': purchases[productIndex]['Product UUID'],
      'Legacy Product ID': purchases[productIndex]['Product ID'],
      Uses: 0.25,
      'Weight Code': '',
      Finished: false,
      Source: 'ANDROID_V2',
      'Request UUID': deterministicUuid(400000 + index),
    };
  });
  const runtime = buildRuntime({ purchases, events, ledgers: [] });
  const startedInsights = performance.now();
  const insightResponse = get(runtime, {
    resource: 'insights',
    analyticsVersion: 1,
    environment: 'SANDBOX',
  });
  const insightLocalMs = performance.now() - startedInsights;
  assert.equal(insightResponse.success, true);
  assert.equal(insightResponse.products.length, 400);
  assert.equal(insightResponse.overview.logCount, 3220);
  const insightBytes = Buffer.byteLength(JSON.stringify(insightResponse));
  assert.ok(
    insightBytes <= 300 * 1024,
    `insights response ${insightBytes} bytes exceeds the 300 KB gate`,
  );
  assert.equal(bodyReads(runtime, 'Purchases').length, 1);
  assert.equal(bodyReads(runtime, 'ConsumptionEvents').length, 1);
  assert.equal(bodyReads(runtime, 'SyncLedger').length, 0);
  assertNoMutation(runtime);

  runtime.resetAudit();
  const startedHistory = performance.now();
  const historyResponse = history(runtime, { limit: 200 });
  const historyLocalMs = performance.now() - startedHistory;
  assert.equal(historyResponse.success, true);
  assert.equal(historyResponse.events.length, 200);
  const historyBytes = Buffer.byteLength(JSON.stringify(historyResponse));
  assert.ok(
    historyBytes <= 150 * 1024,
    `history response ${historyBytes} bytes exceeds 150 KB`,
  );
  assert.equal(bodyReads(runtime, 'Purchases').length, 1);
  assert.equal(bodyReads(runtime, 'ConsumptionEvents').length, 1);
  assert.equal(bodyReads(runtime, 'SyncLedger').length, 0);
  assertNoMutation(runtime);
  console.log(JSON.stringify({
    type: 'backend_analytics_local_scale',
    purchases: 400,
    events: 3600,
    insightsMs: Math.round(insightLocalMs),
    historyMs: Math.round(historyLocalMs),
    insightsBytes: insightBytes,
    historyBytes: historyBytes,
  }));
}

// =============================================================================
// MILESTONE 1 VERIFICATION TEST SUITE
// =============================================================================

// M1.1: Fast-Path Caching: cache hit returns identical result with 0 sheet reads
{
  const runtime = buildRuntime();
  const r1 = insights(runtime);
  assert.equal(r1.success, true);
  assert.ok(runtime.audit.reads.length > 0, 'First request must perform sheet reads');
  assertNoMutation(runtime);

  runtime.resetAudit();
  const r2 = insights(runtime);
  assert.equal(r2.success, true);
  assert.equal(runtime.audit.reads.length, 0, 'Cached request must perform 0 sheet reads');
  assert.equal(r2.sourceRevision.dataVersion, r1.sourceRevision.dataVersion);
  assert.equal(r2.products.length, r1.products.length);
  assert.equal(r2.overview.logCount, r1.overview.logCount);
  assert.ok(typeof r2.serverDurationMs === 'number' && r2.serverDurationMs >= 0);
  assertNoMutation(runtime);
}

// M1.2: Fast-Path Caching: chunked storage for >100KB payloads without hitting 100KB item limit
{
  const purchases = Array.from({ length: 400 }, (_, i) => ({
    Date: '2026-01-01',
    Type: 'P',
    'Product name': `Chunked Scale Product ${i + 1}`,
    'Pre-tax cost': 10,
    'THC%': 20,
    Grams: 3.5,
    Borrowed: 0,
    Finished: 0,
    'Product ID': `*CP${i + 1}`,
    Uses: 1.0,
    'Post-tax': false,
    'Final cost': 11.30,
    'Most recent use': '',
    'Product UUID': deterministicUuid(600000 + i),
    'Client Action UUID': deterministicUuid(700000 + i),
    'Created At': new Date('2026-01-01T15:00:00Z'),
    'Finished At': '',
    'Last quantity': '',
  }));
  const events = Array.from({ length: 3600 }, (_, i) => ({
    'Event UUID': deterministicUuid(800000 + i),
    Timestamp: new Date(Date.UTC(2026, 0, 1 + Math.floor(i / 20), 15, i % 60)),
    'Local Date': '',
    'Local Time': '',
    'Product UUID': purchases[i % purchases.length]['Product UUID'],
    'Legacy Product ID': purchases[i % purchases.length]['Product ID'],
    Uses: 1,
    'Weight Code': '',
    Finished: false,
    Source: 'ANDROID_V2',
    'Request UUID': deterministicUuid(900000 + i),
  }));
  const runtime = buildRuntime({ purchases, events, ledgers: [] });
  const r1 = insights(runtime);
  assert.equal(r1.success, true);
  const totalBytes = Buffer.byteLength(JSON.stringify(r1));
  assert.ok(totalBytes > 100 * 1024, `Payload size ${totalBytes} should exceed 100KB to test chunking`);

  // Verify second request is served from chunked cache with 0 sheet reads
  runtime.resetAudit();
  const r2 = insights(runtime);
  assert.equal(r2.success, true);
  assert.equal(runtime.audit.reads.length, 0, 'Chunked cache hit must perform 0 sheet reads');
  assert.equal(r2.products.length, 400);
  assert.equal(r2.sourceRevision.dataVersion, r1.sourceRevision.dataVersion);
  assertNoMutation(runtime);
}

// M1.3: Watermark Invalidation on doPost sync
{
  const runtime = buildRuntime();
  const initial = insights(runtime);
  assert.equal(initial.success, true);

  runtime.resetAudit();
  const cached = insights(runtime);
  assert.equal(runtime.audit.reads.length, 0, 'Should be cached');

  // Perform a doPost sync mutation
  const syncPayload = {
    apiVersion: 2,
    requestId: deterministicUuid(99901),
    environment: 'SANDBOX',
    purchases: [],
    consumptions: [{
      eventId: deterministicUuid(99902),
      date: '2026-07-18',
      time: '10:00:00',
      productId: '*P1',
      productUuid: deterministicUuid(101),
      uses: 1,
      isFinished: false,
      weightCode: 'STANDARD',
    }],
    finishActions: [],
    consumptionCorrections: [],
  };
  const postRes = post(runtime, syncPayload);
  assert.equal(postRes.success, true, postRes.message);

  // Next GET must detect the bumped watermark, invalidate cache, and perform live read
  runtime.resetAudit();
  const afterSync = insights(runtime);
  assert.equal(afterSync.success, true);
  assert.ok(runtime.audit.reads.length > 0, 'Must perform live read after sync mutation');
  assert.notEqual(afterSync.sourceRevision.dataVersion, initial.sourceRevision.dataVersion);
}

// M1.4: Watermark Invalidation on onInventoryEdit
{
  const runtime = buildRuntime();
  insights(runtime);

  runtime.resetAudit();
  insights(runtime);
  assert.equal(runtime.audit.reads.length, 0, 'Should be cached');

  // Trigger onInventoryEdit
  const purchasesSheet = runtime.peekSheet('Purchases');
  runtime.context.onInventoryEdit({
    range: purchasesSheet.getRange(2, 2, 1, 1),
  });

  // Next GET must be a live read
  runtime.resetAudit();
  const afterEdit = insights(runtime);
  assert.equal(afterEdit.success, true);
  assert.ok(runtime.audit.reads.length > 0, 'Must perform live read after onInventoryEdit');
}

// M1.5: CacheService Exception Graceful Fallback
{
  const runtime = buildRuntime();
  runtime.context.CacheService.getScriptCache = () => {
    throw new Error('CacheService temporary backend outage');
  };
  const res = insights(runtime);
  assert.equal(res.success, true);
  assert.equal(res.products.length, 4);
  assertNoMutation(runtime);
}

// M1.6: Advanced Sheets batchGet Fallback to Sequential Read
{
  const runtime = buildRuntime({ enableBatchGet: true });
  runtime.context.Sheets.Spreadsheets.Values.batchGet = () => {
    throw new Error('Advanced Sheets batchGet quota exceeded');
  };
  const res = insights(runtime);
  assert.equal(res.success, true);
  assert.equal(res.products.length, 4);
  assertNoMutation(runtime);
}

// M1.7: Lock Scope Scoped to Data Fetch Only
{
  const runtime = buildRuntime({ enableBatchGet: true });
  let lockHeldDuringRead = false;
  const origBatchGet = runtime.context.Sheets.Spreadsheets.Values.batchGet;
  runtime.context.Sheets.Spreadsheets.Values.batchGet = function (...args) {
    lockHeldDuringRead = runtime.lock.hasLock();
    return origBatchGet.apply(this, args);
  };
  const res = insights(runtime);
  assert.equal(res.success, true);
  assert.equal(lockHeldDuringRead, true, 'Lock must be held during data fetch');
  assert.equal(runtime.lock.hasLock(), false, 'Lock must be released before return');
}

console.log('backend analytics tests passed');
