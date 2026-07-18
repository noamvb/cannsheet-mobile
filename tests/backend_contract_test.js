const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

let uuidCounter = 0;
const context = {
  console,
  Utilities: {
    getUuid() {
      uuidCounter += 1;
      return `00000000-0000-4000-8000-${String(uuidCounter).padStart(12, '0')}`;
    },
    DigestAlgorithm: { SHA_256: 'SHA_256' },
    Charset: { UTF_8: 'UTF_8' },
    computeDigest(_algorithm, value) {
      return Array.from(require('node:crypto').createHash('sha256').update(value).digest());
    },
  },
};

vm.createContext(context);
const source = fs.readFileSync('backend_additions.gs', 'utf8');
vm.runInContext(
  source + `\nthis.testApi = {
    deterministicLegacyEventUuid_, firstDuplicate_, isUuid_,
    validateV2Consumption_, stagePurchases_, validateRequestEnvironment_,
    newBackendTiming_, recordBackendPhase_, backendTimingRecord_, addServerTimingFields_,
    preflightSyncRequest_, calculateProductEffects_, timestampMillisOrNull_,
    productContext_, appendPurchaseRows_, applyProductEffects_,
    canonicalInteractionSummary_, interactionSummaryReady_,
    interactionStateMatches_, compareInteractionSummaryMaps_,
    CANN
  };`,
  context,
);

const api = context.testApi;

const legacyEventId = api.deterministicLegacyEventUuid_('sheet-id', 'Form Responses 1', 42);
assert.match(legacyEventId, /^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-8[0-9a-f]{3}-[0-9a-f]{12}$/);
assert.equal(legacyEventId, api.deterministicLegacyEventUuid_('sheet-id', 'Form Responses 1', 42));
assert.notEqual(legacyEventId, api.deterministicLegacyEventUuid_('sheet-id', 'Form Responses 1', 43));

assert.equal(api.firstDuplicate_(['a', 'b', 'a']), 'a');
assert.equal(api.firstDuplicate_(['a', 'b']), '');
assert.equal(api.isUuid_('b0f88bb2-5d82-46e1-92a7-0188e95ba3a6'), true);
assert.equal(api.isUuid_('temp_12345678'), false);

const invalidConsumption = api.validateV2Consumption_({
  eventId: 'not-a-uuid',
  productId: '*P1',
  uses: 1,
}, 0);
assert.equal(invalidConsumption.code, 'INVALID_ITEM');

const existing = {
  byLegacyId: {
    '*P1': { legacyProductId: '*P1' },
    '*P2': { legacyProductId: '*P2' },
    '*P1B': { legacyProductId: '*P1B' },
  },
};
const staged = api.stagePurchases_([
  { item: { type: 'P', borrowed: 0 }, actionId: 'a1', tempId: 't1' },
  { item: { type: 'P', borrowed: 0 }, actionId: 'a2', tempId: 't2' },
  { item: { type: 'P', borrowed: 1 }, actionId: 'a3', tempId: 't3' },
], existing);
assert.deepEqual(
  Array.from(staged.accepted, item => item.legacyProductId),
  ['*P3', '*P4', '*P2B'],
);
assert.equal(staged.rejected.length, 0);

assert.equal(api.validateRequestEnvironment_('PRODUCTION', 'PRODUCTION'), '');
assert.equal(api.validateRequestEnvironment_('PRODUCTION', ''), '');
assert.equal(api.validateRequestEnvironment_('SANDBOX', 'SANDBOX'), '');
assert.match(api.validateRequestEnvironment_('SANDBOX', ''), /must include/);
assert.match(api.validateRequestEnvironment_('PRODUCTION', 'SANDBOX'), /do not match/);
assert.match(api.validateRequestEnvironment_('SANDBOX', 'PRODUCTION'), /do not match/);

const timing = api.newBackendTiming_('POST', 100);
api.recordBackendPhase_(timing, 'runtimeSchemaAssertion', 110, 125);
api.recordBackendPhase_(timing, 'runtimeSchemaAssertion', 130, 135);
api.recordBackendPhase_(timing, 'ledgerUpdate', 135, 142);
timing.serverDurationMs = 45;
const timingRecord = api.backendTimingRecord_(timing, 'success', { apiVersion: 2 }, 150);
assert.equal(timingRecord.recordType, 'cannsheet_backend_timing');
assert.equal(timingRecord.handler, 'POST');
assert.equal(timingRecord.phasesMs.runtimeSchemaAssertion, 20);
assert.equal(timingRecord.phasesMs.ledgerUpdate, 7);
assert.equal(timingRecord.serverDurationMs, 45);
assert.equal(timingRecord.totalHandlerMs, 50);
assert.equal(timingRecord.apiVersion, 2);

const sandboxTiming = api.newBackendTiming_('GET');
api.recordBackendPhase_(sandboxTiming, 'purchaseContext', Date.now(), Date.now());
const sandboxResponse = api.addServerTimingFields_({}, sandboxTiming, 'SANDBOX');
assert.equal(typeof sandboxResponse.serverDurationMs, 'number');
assert.deepEqual(
  Object.fromEntries(Object.entries(sandboxResponse.serverTimings)),
  { purchaseContext: sandboxTiming.phasesMs.purchaseContext },
);
const productionResponse = api.addServerTimingFields_({}, sandboxTiming, 'PRODUCTION');
assert.equal('serverTimings' in productionResponse, false);

const validEmptyPreflight = api.preflightSyncRequest_({
  requestId: 'b0f88bb2-5d82-46e1-92a7-0188e95ba3a6',
  purchases: [],
  consumptions: [],
}, 2);
assert.equal(validEmptyPreflight.failure, null);
assert.equal(validEmptyPreflight.purchases.length, 0);
assert.equal(validEmptyPreflight.consumptions.length, 0);

const invalidRequestPreflight = api.preflightSyncRequest_({ purchases: [], consumptions: [] }, 2);
assert.equal(invalidRequestPreflight.failure.code, 'INVALID_ITEM');
assert.match(invalidRequestPreflight.failure.message, /requestId must be a UUID/);

const duplicateActionPreflight = api.preflightSyncRequest_({
  requestId: 'b0f88bb2-5d82-46e1-92a7-0188e95ba3a6',
  purchases: [{ actionId: 'same' }, { actionId: 'same' }],
  consumptions: [],
}, 2);
assert.equal(duplicateActionPreflight.failure.code, 'INVALID_ITEM');
assert.match(duplicateActionPreflight.failure.message, /Duplicate UUID inside request/);

const oversizedPreflight = api.preflightSyncRequest_({
  requestId: 'b0f88bb2-5d82-46e1-92a7-0188e95ba3a6',
  purchases: Array.from({ length: 101 }, () => ({})),
  consumptions: [],
}, 2);
assert.equal(oversizedPreflight.failure.code, 'INVALID_ITEM');
assert.match(oversizedPreflight.failure.message, /maximum size/);

const effectProduct = {
  rowNumber: 7,
  legacyProductId: '*P7',
  status: 2,
  uses: 4,
  mostRecentUse: new Date('2025-01-01T12:00:00Z'),
  lastQuantity: 0.4,
  finishedAt: null,
};
const effectContext = { byLegacyId: { '*P7': effectProduct } };
const calculatedEffects = api.calculateProductEffects_(effectContext, [
  { legacyProductId: '*P7', uses: 0.5, timestamp: new Date('2025-01-03T12:00:00Z'), isFinished: false },
  { legacyProductId: '*P7', uses: 1, timestamp: new Date('2025-01-04T12:00:00Z'), isFinished: true },
  { legacyProductId: '*P7', uses: 0.25, timestamp: new Date('2025-01-02T12:00:00Z'), isFinished: true },
]);
assert.equal(calculatedEffects.length, 1);
assert.equal(calculatedEffects[0].rowNumber, 7);
assert.equal(calculatedEffects[0].uses, 5.75);
assert.equal(calculatedEffects[0].status, 1);
assert.equal(Number(calculatedEffects[0].mostRecentUse), Date.parse('2025-01-04T12:00:00Z'));
assert.equal(
  calculatedEffects[0].lastQuantity,
  1,
  'Last quantity must belong to the exact event that supplied Most recent use',
);
assert.equal(
  Number(calculatedEffects[0].finishedAt),
  Date.parse('2025-01-02T12:00:00Z'),
  'Finished At must preserve the deployed last-appended-finishing-event rule',
);

const equalTimestampEffects = api.calculateProductEffects_({
  byLegacyId: {
    '*E1': {
      rowNumber: 3,
      legacyProductId: '*E1',
      status: 0,
      uses: 1,
      mostRecentUse: new Date('2025-02-01T10:00:00Z'),
      lastQuantity: 0.75,
      finishedAt: null,
    },
  },
}, [{
  legacyProductId: '*E1',
  uses: 2,
  timestamp: new Date('2025-02-01T10:00:00Z'),
  isFinished: false,
}]);
assert.equal(Number(equalTimestampEffects[0].mostRecentUse), Date.parse('2025-02-01T10:00:00Z'));
assert.equal(
  equalTimestampEffects[0].lastQuantity,
  0.75,
  'An equal timestamp must preserve the earlier canonical quantity',
);
assert.equal(equalTimestampEffects[0].uses, 3);

assert.equal(api.interactionSummaryReady_({}), false);
assert.equal(api.interactionSummaryReady_({ INTERACTION_SUMMARY_VERSION: '' }), false);
assert.equal(api.interactionSummaryReady_({ INTERACTION_SUMMARY_VERSION: 0 }), false);
assert.equal(api.interactionSummaryReady_({ INTERACTION_SUMMARY_VERSION: '1' }), true);
assert.throws(
  () => api.interactionSummaryReady_({ INTERACTION_SUMMARY_VERSION: 2 }),
  /unsupported Config INTERACTION_SUMMARY_VERSION 2/,
);

const canonicalHeaders = Array.from(api.CANN.EVENT_HEADERS);
const canonicalRows = [
  ['event-1', new Date('2025-04-01T10:00:00Z'), '', '', '', '*C1', 0.25],
  ['event-2', new Date('2025-04-01T10:00:00Z'), '', '', '', '*C1', 0.75],
  ['event-3', new Date('2025-03-31T10:00:00Z'), '', '', '', '*C1', 1.25],
  ['event-4', new Date('2025-04-02T10:00:00Z'), '', '', '', '*C2', 0.5],
  ['event-5', new Date('2025-04-02T10:00:00Z'), '', '', '', '*C2', 1.5],
].map(row => row.concat(Array(canonicalHeaders.length - row.length).fill('')));
const canonicalSheet = {
  getLastRow: () => canonicalRows.length + 1,
  getLastColumn: () => canonicalHeaders.length,
  getRange(row, column, rowCount, columnCount) {
    if (row === 1) {
      assert.deepEqual([column, rowCount, columnCount], [1, 1, canonicalHeaders.length]);
      return { getValues: () => [canonicalHeaders] };
    }
    assert.deepEqual(
      [row, column, rowCount, columnCount],
      [2, 1, canonicalRows.length, canonicalHeaders.length],
    );
    return { getValues: () => canonicalRows };
  },
};
const canonicalSummary = api.canonicalInteractionSummary_({
  getSheetByName(name) {
    assert.equal(name, 'ConsumptionEvents');
    return canonicalSheet;
  },
});
assert.equal(canonicalSummary.eventRows, 5);
assert.equal(canonicalSummary.validEvents, 5);
assert.equal(canonicalSummary.invalidEvents, 0);
assert.equal(
  canonicalSummary.interactions['*C1'].lastQuantity,
  0.25,
  'Canonical rebuild must keep the earlier row when timestamps tie',
);
assert.equal(canonicalSummary.interactions['*C1'].canonicalRow, 2);
assert.equal(
  canonicalSummary.interactions['*C2'].lastQuantity,
  0.5,
  'Canonical rebuild must also preserve the first of equal latest timestamps',
);

assert.equal(
  api.interactionStateMatches_(
    Date.parse('2025-04-01T10:00:00Z'),
    0.25,
    canonicalSummary.interactions['*C1'],
  ),
  true,
);
const comparisonDifferences = api.compareInteractionSummaryMaps_(
  {
    '*C1': {
      lastLoggedAtEpochMillis: Date.parse('2025-04-01T10:00:00Z'),
      lastQuantity: 9,
    },
  },
  { '*C1': canonicalSummary.interactions['*C1'] },
);
assert.equal(comparisonDifferences.length, 1);
assert.equal(comparisonDifferences[0].legacyProductId, '*C1');

const purchaseHeaders = Object.fromEntries([
  'Date', 'Type', 'Product name', 'Pre-tax cost', 'THC%', 'Grams',
  'Borrowed', 'Finished', 'Product ID', 'Uses', 'Post-tax', 'Final cost',
  'Most recent use', 'Product UUID', 'Client Action UUID', 'Created At', 'Finished At',
].map((name, index) => [name, index]));
const physicalRows = [
  ['', 'P', 'First', 1, 1, 1, 0, 0, '*P1', 2, false, 1.13, '', 'uuid-1', 'action-1', '', ''],
  Array(17).fill(''),
  ['', 'P', 'Third', 1, 1, 1, 0, 0, '*P3', 3, false, 1.13, '', 'uuid-3', 'action-3', '', ''],
];
const physicalSheet = {
  getLastRow: () => 4,
  getLastColumn: () => 17,
  getRange(row, column, rowCount, columnCount) {
    assert.deepEqual([row, column, rowCount, columnCount], [2, 1, 3, 17]);
    return { getValues: () => physicalRows };
  },
};
const physicalContext = api.productContext_(null, {
  includeActionIds: true,
  runtimeContext: {
    sheets: { purchases: physicalSheet },
    headers: { purchases: purchaseHeaders },
  },
});
assert.equal(physicalContext.byLegacyId['*P1'].rowNumber, 2);
assert.equal(physicalContext.byLegacyId['*P3'].rowNumber, 4);

let appendedPurchaseWrite = null;
const appendSheet = {
  getLastRow: () => 4,
  getRange(row, column, rowCount, columnCount) {
    return {
      setValues(values) {
        appendedPurchaseWrite = { row, column, rowCount, columnCount, values };
      },
    };
  },
};
const appendContext = {
  purchasesSheet: appendSheet,
  byLegacyId: {},
  byProductUuid: {},
  byActionId: {},
};
const appended = [{
  item: { date: '2025-03-01', type: 'P', name: 'New', cost: 10, thc: 20, grams: 3.5 },
  legacyProductId: '*P9',
  productUuid: 'product-uuid',
  actionId: 'action-uuid',
  tempId: 'temp-9',
}];
api.appendPurchaseRows_(appendContext, appended, new Date('2025-03-01T12:00:00Z'), 0.13);
assert.equal(appendedPurchaseWrite.row, 5);
assert.equal(appendedPurchaseWrite.rowCount, 1);
assert.equal(appendedPurchaseWrite.columnCount, 17);
assert.equal(appended[0].rowNumber, 5);
assert.equal(appendContext.byLegacyId['*P9'], appended[0]);
assert.equal(appendContext.byProductUuid['product-uuid'], appended[0]);

function productEffectWriteCount(headers) {
  const writes = [];
  const product = {
    rowNumber: 9,
    legacyProductId: '*W1',
    status: 0,
    uses: 2,
    mostRecentUse: new Date('2025-05-01T10:00:00Z'),
    lastQuantity: 0.25,
    finishedAt: null,
  };
  api.applyProductEffects_({
    purchasesSheet: {
      getRange(row, column) {
        return {
          setValue(value) {
            writes.push({ row, column, value });
          },
        };
      },
    },
    headers,
    byLegacyId: { '*W1': product },
  }, [{
    legacyProductId: '*W1',
    uses: 0.5,
    timestamp: new Date('2025-05-02T10:00:00Z'),
    isFinished: false,
  }]);
  return { writes, product };
}

const readyEffectWrites = productEffectWriteCount({
  Finished: 7,
  Uses: 9,
  'Most recent use': 12,
  'Finished At': 16,
  'Last quantity': 17,
});
assert.equal(readyEffectWrites.writes.length, 5);
assert.deepEqual(
  Array.from(readyEffectWrites.writes, write => write.column),
  [8, 10, 13, 17, 18],
);
assert.equal(readyEffectWrites.writes[4].value, 0.5);
assert.equal(readyEffectWrites.product.lastQuantity, 0.5);

const fallbackEffectWrites = productEffectWriteCount({
  Finished: 7,
  Uses: 9,
  'Most recent use': 12,
  'Finished At': 16,
});
assert.equal(
  fallbackEffectWrites.writes.length,
  4,
  'A not-yet-migrated sheet must retain the four-write Phase 3 behavior',
);
assert.deepEqual(
  Array.from(fallbackEffectWrites.writes, write => write.column),
  [8, 10, 13, 17],
);

assert.equal(api.CANN.PURCHASE_HEADERS.at(-1), 'Last quantity');

const doGetSource = source.slice(source.indexOf('function doGet('), source.indexOf('function doPost('));
assert.match(doGetSource, /const summaryReady = interactionSummaryReady_\(runtimeConfig\.values\)/);
assert.match(doGetSource, /const legacyInteractions = summaryReady \? null : latestInteractions_\(ss\)/);
assert.match(
  doGetSource,
  /const rawLastQuantity = summaryReady\s*\?\s*value_\(row, headers, 'Last quantity'\)/,
);
assert.match(doGetSource, /const lastQuantity = summaryReady\s*\?\s*optionalFiniteNumber_\(rawLastQuantity\)/);
assert.match(doGetSource, /INTERACTION_SUMMARY_INVALID/);
assert.equal((doGetSource.match(/latestInteractions_\(/g) || []).length, 1);
assert.doesNotMatch(doGetSource, /canonicalInteractionSummary_|reconcileInteractionSummary_|writeInteractionSummary_/);
assert.doesNotMatch(doGetSource, /CANN\.SHEETS\.(EVENTS|RESPONSES|LEDGER)/);

const doPostSource = source.slice(source.indexOf('function doPost('), source.indexOf('function handleSync('));
assert.equal((doPostSource.match(/spreadsheet_\(\)/g) || []).length, 1);
assert.doesNotMatch(doPostSource, /ensureInteractionSummarySchema_|writeInteractionSummary_|setConfigValue_/);
const legacyHandlerSource = source.slice(source.indexOf('function handleLegacySyncLocked_('), source.indexOf('function legacyFailure_('));
const v2HandlerSource = source.slice(source.indexOf('function handleV2SyncLocked_('), source.indexOf('function v2RequestFailure_('));
assert.doesNotMatch(legacyHandlerSource, /ensureCoreSchema_\(/);
assert.doesNotMatch(v2HandlerSource, /ensureCoreSchema_\(/);
assert.match(
  v2HandlerSource,
  /const hasCoreMutation\s*=\s*staged\.accepted\.length > 0 \|\|\s*stagedConsumptions\.length > 0/,
);
assert.match(v2HandlerSource, /if \(recoverableReady && hasCoreMutation\)/);
assert.match(v2HandlerSource, /if \(!recoverableReady\)/);

const runtimeAssertionSource = source.slice(source.indexOf('function assertRuntimeSchema_('), source.indexOf('function requireExactHeaders_('));
assert.doesNotMatch(runtimeAssertionSource, /insertSheet|insertColumns|setFrozenRows|setValue|setValues|setBackground|setDataValidation/);

const eventContextSource = source.slice(source.indexOf('function eventContext_('), source.indexOf('function stagePurchases_('));
assert.match(eventContextSource, /getRange\(2, headers\['Event UUID'\] \+ 1, lastRow - 1, 1\)/);
assert.doesNotMatch(eventContextSource, /readDataRows_/);

const ledgerSource = source.slice(source.indexOf('function upsertLedger_('), source.indexOf('function emptyProductContext_('));
assert.match(ledgerSource, /sheet\.getMaxRows\(\) - 1/);
assert.match(ledgerSource, /createTextFinder\(requestId\)/);
assert.match(ledgerSource, /\.matchEntireCell\(true\)/);
assert.match(ledgerSource, /\.matchCase\(true\)/);
assert.match(ledgerSource, /\.useRegularExpression\(false\)/);
assert.match(ledgerSource, /sheet\.appendRow\(values\[0\]\)/);
assert.match(ledgerSource, /SpreadsheetApp\.flush\(\)/);
assert.doesNotMatch(ledgerSource, /readDataRows_/);
assert.doesNotMatch(ledgerSource, /\.getValues\(\)/);
assert.doesNotMatch(ledgerSource, /\.getLastRow\(\)/);

const productEffectsSource = source.slice(source.indexOf('function applyProductEffects_('), source.indexOf('function calculateProductEffects_('));
assert.equal((productEffectsSource.match(/\.setValue\(/g) || []).length, 5);
assert.match(productEffectsSource, /if \(headers\['Last quantity'\] !== undefined\)/);
assert.doesNotMatch(productEffectsSource, /setValues\(/);

const migrationEntrypointSource = source.slice(
  source.indexOf('function runInteractionSummaryMigration('),
  source.indexOf('/** Explicit maintenance entrypoint. Rebuilds only from canonical history. */'),
);
assert.match(migrationEntrypointSource, /LockService\.getScriptLock\(\)/);
assert.match(migrationEntrypointSource, /rebuildInteractionSummaryLocked_\(ss, false\)/);

const enableEntrypointStart = source.indexOf('function enableInteractionSummaryFastPath(');
assert.ok(enableEntrypointStart >= 0, 'The fast path requires a separate explicit enable entrypoint');
const rebuildEntrypointSource = source.slice(
  source.indexOf('function rebuildInteractionSummary('),
  enableEntrypointStart,
);
assert.match(rebuildEntrypointSource, /rebuildInteractionSummaryLocked_\(ss, false\)/);

const enableEntrypointSource = source.slice(
  enableEntrypointStart,
  source.indexOf('function disableInteractionSummaryFastPath('),
);
assert.match(enableEntrypointSource, /LockService\.getScriptLock\(\)/);
assert.match(enableEntrypointSource, /rebuildInteractionSummaryLocked_\(ss, true\)/);
assert.match(enableEntrypointSource, /finally\s*{\s*lock\.releaseLock\(\);\s*}/);

const reconciliationEntrypointSource = source.slice(
  source.indexOf('function reconcileInteractionSummary('),
  source.indexOf('function rebuildInteractionSummaryLocked_('),
);
assert.match(reconciliationEntrypointSource, /reconcileInteractionSummary_\(ss\)/);
assert.doesNotMatch(reconciliationEntrypointSource, /rebuildInteractionSummaryLocked_|writeInteractionSummary_/);

const productProjectionEntrypointStart = source.indexOf(
  'function reconcileProductProjections()',
);
assert.ok(
  productProjectionEntrypointStart >= 0,
  'All-product projection reconciliation must be a public editor entrypoint',
);
const productProjectionEntrypointSource = source.slice(
  productProjectionEntrypointStart,
  source.indexOf(
    'function reconcileProductProjections_(',
    productProjectionEntrypointStart,
  ),
);
assert.match(productProjectionEntrypointSource, /LockService\.getScriptLock\(\)/);
assert.match(productProjectionEntrypointSource, /assertConfigEnvironment_\(ss\)/);
assert.match(productProjectionEntrypointSource, /assertSpreadsheetTimeZone_\(ss\)/);
assert.match(
  productProjectionEntrypointSource,
  /reconcileProductProjections_\(ss, null\)/,
);
assert.match(
  productProjectionEntrypointSource,
  /type: 'product_projections_reconciled'/,
);
assert.match(
  productProjectionEntrypointSource,
  /finally\s*{\s*lock\.releaseLock\(\);\s*}/,
);
assert.doesNotMatch(
  productProjectionEntrypointSource,
  /setValue|setValues|appendRow|batchUpdate|rebuildProductProjections/,
);

const rebuildSource = source.slice(
  source.indexOf('function rebuildInteractionSummaryLocked_('),
  source.indexOf('function ensureInteractionSummarySchema_('),
);
const notReadyMarker = rebuildSource.search(
  /setConfigValue_\(\s*ss,\s*'INTERACTION_SUMMARY_VERSION',\s*0,/,
);
const legacyComparison = rebuildSource.indexOf('compareInteractionSummaryMaps_(');
const projectionWrite = rebuildSource.indexOf('writeInteractionSummary_(');
const reconciliation = rebuildSource.indexOf('reconcileInteractionSummary_(ss)');
const readyMarker = rebuildSource.search(
  /setConfigValue_\(\s*ss,\s*'INTERACTION_SUMMARY_VERSION',\s*CANN\.INTERACTION_SUMMARY_VERSION,/,
);
assert.ok(notReadyMarker >= 0);
assert.ok(legacyComparison > notReadyMarker);
assert.ok(projectionWrite > legacyComparison);
assert.ok(reconciliation > projectionWrite);
assert.ok(readyMarker > reconciliation);
assert.match(rebuildSource, /const fastPathEnabled = enableFastPath === true/);
assert.match(
  rebuildSource,
  /if \(fastPathEnabled\)\s*{\s*setConfigValue_\(\s*ss,\s*'INTERACTION_SUMMARY_VERSION',\s*CANN\.INTERACTION_SUMMARY_VERSION,/,
);

const reliabilityMigrationSource = source.slice(
  source.indexOf('function runReliabilityMigration('),
  source.indexOf('function runInteractionSummaryMigration('),
);
assert.match(reliabilityMigrationSource, /rebuildInteractionSummaryLocked_\(ss, false\)/);

const summaryRebuildCalls = source.match(/rebuildInteractionSummaryLocked_\(ss, (?:true|false)\)/g) || [];
assert.deepEqual(
  Array.from(summaryRebuildCalls),
  [
    'rebuildInteractionSummaryLocked_(ss, false)',
    'rebuildInteractionSummaryLocked_(ss, false)',
    'rebuildInteractionSummaryLocked_(ss, false)',
    'rebuildInteractionSummaryLocked_(ss, true)',
  ],
  'Only the explicit enable entrypoint may request marker version 1',
);

const runtimeSchemaSource = source.slice(
  source.indexOf('function assertRuntimeSchema_('),
  source.indexOf('function assertSupportedSchemaVersion_('),
);
assert.match(
  runtimeSchemaSource,
  /summaryReady \? CANN\.PURCHASE_HEADERS : CANN\.PURCHASE_HEADERS\.slice\(0, -1\)/,
);

console.log('backend contract tests passed');
