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
      Config: { rows: configRows },
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

function bodyReads(runtime, sheetName) {
  return runtime.audit.reads.filter(entry => entry.sheet === sheetName && entry.row >= 2);
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
    [history(runtime, { analyticsVersion: 2 }), 'UNSUPPORTED_ANALYTICS_VERSION'],
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

// Production-shaped local acceptance: one contiguous read per source. The
// exact raw sizes are reported here; the stricter 300 KB / 150 KB promotion
// gates are applied to the deployed sandbox responses.
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

console.log('backend analytics tests passed');
