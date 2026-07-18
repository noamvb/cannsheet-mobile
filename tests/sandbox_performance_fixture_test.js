const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const runtime = {
  properties: {
    ENVIRONMENT: 'SANDBOX',
    SPREADSHEET_ID: 'synthetic-sandbox-sheet',
    FORM_ID: 'synthetic-sandbox-form',
  },
  configEnvironment: 'SANDBOX',
  activeSpreadsheetId: 'synthetic-sandbox-sheet',
  boundSpreadsheetId: 'synthetic-sandbox-sheet',
  formDestinationId: 'synthetic-sandbox-sheet',
  eventUuids: [],
  uuidColumnReadCount: 0,
  textFinderCount: 0,
};
const eventSheet = {
  getLastRow: () => runtime.eventUuids.length + 1,
  getRange: (row, column, count, width) => {
    assert.equal(row, 2, 'benchmark range must exclude the header');
    assert.equal(column, 1, 'benchmark must target Event UUID');
    assert.equal(count, runtime.eventUuids.length);
    assert.equal(width, 1, 'benchmark must read exactly one column');
    return {
      getValues: () => {
        runtime.uuidColumnReadCount++;
        return runtime.eventUuids.map(uuid => [uuid]);
      },
      createTextFinder: uuid => {
        runtime.textFinderCount++;
        let wholeCell = false;
        let regularExpression = true;
        const finder = {
          matchEntireCell: value => {
            wholeCell = value;
            return finder;
          },
          useRegularExpression: value => {
            regularExpression = value;
            return finder;
          },
          findNext: () => {
            assert.equal(wholeCell, true, 'TextFinder must require a whole-cell match');
            assert.equal(regularExpression, false, 'TextFinder must use literal matching');
            return runtime.eventUuids.includes(uuid) ? { row: 2 } : null;
          },
        };
        return finder;
      },
    };
  },
};
const spreadsheet = id => ({ getId: () => id });
const form = {
  getId: () => runtime.properties.FORM_ID,
  getDestinationId: () => runtime.formDestinationId,
};

const context = {
  console,
  CANN: {
    STATUS: { ACTIVE: 0, FINISHED: 1, UNOPENED: 2 },
    SHEETS: { RESPONSES: 'Form Responses 1', EVENTS: 'ConsumptionEvents' },
    PURCHASE_HEADERS: new Array(18).fill('purchase'),
    EVENT_HEADERS: new Array(13).fill('event'),
  },
  requiredScriptProperty_: name => runtime.properties[name],
  SpreadsheetApp: {
    openById: id => spreadsheet(id),
    getActiveSpreadsheet: () => spreadsheet(runtime.activeSpreadsheetId),
    getActive: () => spreadsheet(runtime.boundSpreadsheetId),
  },
  FormApp: { openById: () => form },
  requiredSheet_: (_ss, name) => {
    assert.equal(name, 'ConsumptionEvents');
    return eventSheet;
  },
  headerMap_: () => ({ 'Event UUID': 0 }),
  requireHeaders_: (_headers, required) => assert.deepEqual(Array.from(required), ['Event UUID']),
  configValue_: () => runtime.configEnvironment,
  text_: value => String(value == null ? '' : value).trim(),
  resetSandboxData() {},
};

vm.createContext(context);
const source = fs.readFileSync('sandbox_performance_fixture.gs', 'utf8');
vm.runInContext(
  source + `\nthis.testApi = {
    build: sandboxPerformanceBuildFixture_,
    responseRows: sandboxPerformanceBuildResponseRows_,
    assertGenerated: sandboxPerformanceAssertGeneratedFixture_,
    guard: sandboxPerformanceGuard_,
    benchmarkUuidLookup: benchmarkSandboxEventUuidLookup,
    buildUuidLookupBatch: sandboxPerformanceBuildUuidLookupBatch_,
    lookupChecksum: sandboxPerformanceLookupChecksum_,
    summarizeDurations: sandboxPerformanceSummarizeDurations_
  };`,
  context,
);

const first = context.testApi.build();
const second = context.testApi.build();
runtime.eventUuids = first.events.map(row => row[0]);

assert.equal(JSON.stringify(first), JSON.stringify(second), 'fixture must be deterministic');
assert.equal(first.purchases.length, 400);
assert.equal(first.events.length, 3600);
assert.equal(first.expected.responses, 3600);
assert.equal(first.expected.totalUses, 2700);
assert.equal(first.expected.interactionSummaryRows, 360);
assert.equal(first.expected.interactionSummaryMismatches, 0);
assert.equal(first.expected.active, 24);
assert.equal(first.expected.finished, 336);
assert.equal(first.expected.unopened, 40);
assert.equal(first.expected.finishedEvents, 336);
assert.equal(first.expected.lineageRows, 3600);
assert.equal(first.expected.uniqueLineage, 3600);
assert.equal(first.expected.contiguousLineage, true);

assert.equal(new Set(first.purchases.map(row => row[8])).size, 400);
assert.equal(new Set(first.purchases.map(row => row[13])).size, 400);
assert.equal(new Set(first.events.map(row => row[0])).size, 3600);
assert.equal(first.purchases.every(row => row.length === 18), true);
assert.equal(first.events.every(row => row.length === 13), true);
assert.equal(first.events.every(row => /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-8[0-9a-f]{3}-[0-9]{12}$/i.test(row[0])), true);

assert.equal(first.events[0][0], '53000000-0000-4000-8000-000000000001');
assert.equal(first.events[3599][0], '53000000-0000-4000-8000-000000003600');
assert.equal(first.events[0][1], '2025-01-01 08:00');
assert.equal(first.events[3599][1], '2025-02-02 19:47');
assert.equal(first.events[0][11], 'Form Responses 1');
assert.equal(first.events[0][12], 2);
assert.equal(first.events[3599][12], 3601);
assert.equal(new Set(first.events.map(row => `${row[11]}:${row[12]}`)).size, 3600);
assert.equal(first.events.every((row, index) => row[12] === index + 2), true);
assert.equal(first.purchases.slice(0, 360).every(row => row[9] === 7.5), true);
assert.equal(first.purchases.slice(0, 360).every(row => row[17] !== ''), true);
assert.equal(first.purchases.slice(360).every(row => row[9] === 0 && row[12] === '' && row[17] === ''), true);

assert.doesNotThrow(() => context.testApi.assertGenerated(first));
const brokenLineage = context.testApi.build();
brokenLineage.events[100][12] = 999;
assert.throws(() => context.testApi.assertGenerated(brokenLineage), /lineage must be unique and contiguous/);

assert.equal(context.testApi.guard().ss.getId(), 'synthetic-sandbox-sheet');
runtime.configEnvironment = 'PRODUCTION';
assert.throws(() => context.testApi.guard(), /Config ENVIRONMENT marker mismatch/);
runtime.configEnvironment = 'SANDBOX';
runtime.formDestinationId = 'different-sheet';
assert.throws(() => context.testApi.guard(), /Form destination mismatch/);
runtime.formDestinationId = 'synthetic-sandbox-sheet';
runtime.activeSpreadsheetId = 'different-sheet';
assert.throws(() => context.testApi.guard(), /active spreadsheet mismatch/);
runtime.activeSpreadsheetId = 'synthetic-sandbox-sheet';
runtime.properties.ENVIRONMENT = 'PRODUCTION';
assert.throws(() => context.testApi.guard(), /ENVIRONMENT must be SANDBOX/);
assert.throws(() => context.testApi.benchmarkUuidLookup(), /ENVIRONMENT must be SANDBOX/);
runtime.properties.ENVIRONMENT = 'SANDBOX';

const fiveUuidBatch = context.testApi.buildUuidLookupBatch(
  runtime.eventUuids,
  new Set(runtime.eventUuids),
  5,
);
assert.deepEqual(Array.from(fiveUuidBatch.expectedFound), [true, false, true, false, true]);
assert.equal(new Set(fiveUuidBatch.submittedUuids).size, 5);
assert.equal(fiveUuidBatch.submittedUuids[0], runtime.eventUuids[5]);
assert.equal(runtime.eventUuids.includes(fiveUuidBatch.submittedUuids[1]), false);
assert.equal(
  context.testApi.lookupChecksum(fiveUuidBatch.submittedUuids, fiveUuidBatch.expectedFound),
  context.testApi.lookupChecksum(fiveUuidBatch.submittedUuids, fiveUuidBatch.expectedFound),
);
assert.notEqual(
  context.testApi.lookupChecksum(fiveUuidBatch.submittedUuids, fiveUuidBatch.expectedFound),
  context.testApi.lookupChecksum(fiveUuidBatch.submittedUuids, [false, false, true, false, true]),
);

const durationSummary = JSON.parse(JSON.stringify(context.testApi.summarizeDurations([9, 3, 7, 5])));
assert.deepEqual(durationSummary, { rawMs: [9, 3, 7, 5], minMs: 3, medianMs: 6, maxMs: 9 });

runtime.uuidColumnReadCount = 0;
runtime.textFinderCount = 0;
const uuidBenchmark = JSON.parse(JSON.stringify(context.testApi.benchmarkUuidLookup()));
assert.equal(uuidBenchmark.type, 'sandbox_event_uuid_lookup_benchmark');
assert.equal(uuidBenchmark.schemaVersion, 1);
assert.equal(uuidBenchmark.environment, 'SANDBOX');
assert.equal(uuidBenchmark.readOnly, true);
assert.equal(uuidBenchmark.eventRows, 3600);
assert.deepEqual(uuidBenchmark.batchSizes, [1, 5, 10, 20]);
assert.equal(uuidBenchmark.runsPerBatch, 6);
assert.equal(uuidBenchmark.cases.length, 4);
assert.equal(uuidBenchmark.checks.passed, true);
assert.equal(uuidBenchmark.checks.allStrategiesEqual, true);
assert.equal(uuidBenchmark.checks.allResultsMatchExpected, true);
assert.equal(uuidBenchmark.checks.eventUuidColumnUnchanged, true);
assert.equal(uuidBenchmark.checks.eventRowCountUnchanged, true);
assert.equal(uuidBenchmark.cases.every(item => item.runs[0].label === 'first-measured'), true);
assert.equal(uuidBenchmark.cases.every(item => item.runs.slice(1).every(run => /^warm-[1-5]$/.test(run.label))), true);
assert.equal(uuidBenchmark.cases.every(item => item.summary.columnReadSetMs.rawMs.length === 6), true);
assert.equal(uuidBenchmark.cases.every(item => item.summary.exactTextFinderMs.rawMs.length === 6), true);
assert.equal(runtime.uuidColumnReadCount, 26, 'setup/final reads plus one Set read per measured run');
assert.equal(runtime.textFinderCount, (1 + 5 + 10 + 20) * 6);

const uuidBenchmarkSource = source.slice(
  source.indexOf('function benchmarkSandboxEventUuidLookup()'),
  source.indexOf('/** Manual SANDBOX-only helper used around live Form trigger tests. */'),
);
assert.match(uuidBenchmarkSource, /getRange\(2, eventUuidColumn, eventRowCount, 1\)/);
assert.match(uuidBenchmarkSource, /createTextFinder\(uuid\)/);
assert.match(uuidBenchmarkSource, /matchEntireCell\(true\)/);
assert.match(uuidBenchmarkSource, /useRegularExpression\(false\)/);
assert.doesNotMatch(
  uuidBenchmarkSource,
  /\.setValues\(|\.setValue\(|\.appendRow\(|\.clear(?:Content)?\(|\.insertRows|\.deleteRows/,
  'UUID benchmark and its helpers must contain no sheet mutation calls',
);

const minimalHeaders = {
  Timestamp: 0,
  Date: 1,
  Time: 2,
  Product: 3,
  Uses: 4,
  'Weight code': 5,
  'Mark as Finished?': 6,
};
const responseRows = context.testApi.responseRows(first.events.slice(0, 1), minimalHeaders, 7);
assert.equal(responseRows.length, 1);
assert.equal(responseRows[0].length, 7);
assert.equal(responseRows[0][0], first.events[0][1]);
assert.equal(responseRows[0][3], first.events[0][5]);
assert.equal(responseRows[0][4], first.events[0][6]);
assert.equal(responseRows[0][6], 'No');

console.log('sandbox performance fixture tests passed');
