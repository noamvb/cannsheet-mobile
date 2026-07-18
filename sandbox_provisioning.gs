/** Sandbox-only editor utilities. Never deploy this file to production. */
const SANDBOX_FIXTURE = Object.freeze({
  requestId: '40000000-0000-4000-8000-000000000001',
  purchases: Object.freeze([
    ['2026-01-01', 'P', 'SANDBOX Flower', 30, 24, 3.5, 0, 0, '*P1', 1.5, false, 33.90, '2026-01-10 10:05', '10000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', '2026-01-01 12:00', ''],
    ['2026-01-02', 'E', 'SANDBOX Edible', 12, 10, 1, 0, 2, '*E1', 0, true, 12, '', '10000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000002', '2026-01-02 12:00', ''],
    ['2026-01-03', 'J', 'SANDBOX Pre-Roll', 8, 20, 1, 0, 1, '*J1', 0, false, 9.04, '', '10000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000003', '2026-01-03 12:00', '2026-01-03 12:00'],
    ['2026-01-04', 'F', 'SANDBOX Borrowed Flower', 0, 18, 1, 1, 0, '*F1B', 0.25, true, 0, '2026-01-11 12:00', '10000000-0000-4000-8000-000000000004', '20000000-0000-4000-8000-000000000004', '2026-01-04 12:00', ''],
    ['2026-01-05', 'S', 'SANDBOX Softgel', 20, 5, 2, 0, 1, '*S1', 2, false, 22.60, '2026-01-12 18:30', '10000000-0000-4000-8000-000000000005', '20000000-0000-4000-8000-000000000005', '2026-01-05 12:00', '2026-01-12 18:30'],
    ['2026-01-06', 'K', 'SANDBOX Cartridge', 40, 80, 1, 0, 0, '*K1', 1.5, true, 40, '2026-01-13 20:15', '10000000-0000-4000-8000-000000000006', '20000000-0000-4000-8000-000000000006', '2026-01-06 12:00', '']
  ]),
  events: Object.freeze([
    ['30000000-0000-4000-8000-000000000001', '2026-01-10 10:00', '2026-01-10', '10:00', '10000000-0000-4000-8000-000000000001', '*P1', 1, 'A', false],
    ['30000000-0000-4000-8000-000000000002', '2026-01-10 10:05', '2026-01-10', '10:05', '10000000-0000-4000-8000-000000000001', '*P1', 0.5, 'B', false],
    ['30000000-0000-4000-8000-000000000003', '2026-01-11 12:00', '2026-01-11', '12:00', '10000000-0000-4000-8000-000000000004', '*F1B', 0.25, '', false],
    ['30000000-0000-4000-8000-000000000004', '2026-01-12 18:30', '2026-01-12', '18:30', '10000000-0000-4000-8000-000000000005', '*S1', 2, 'C', true],
    ['30000000-0000-4000-8000-000000000005', '2026-01-13 20:15', '2026-01-13', '20:15', '10000000-0000-4000-8000-000000000006', '*K1', 1.5, 'D', false]
  ])
});

function sandboxGuard_() {
  if (environment_() !== 'SANDBOX') throw new Error('SANDBOX_GUARD: ENVIRONMENT must be SANDBOX');
  const configured = spreadsheet_();
  const active = SpreadsheetApp.getActiveSpreadsheet();
  const bound = SpreadsheetApp.getActive();
  if (!active || !bound || active.getId() !== configured.getId() || bound.getId() !== configured.getId()) {
    throw new Error('SANDBOX_GUARD: active, bound, and configured spreadsheets must match');
  }
  const formId = requiredScriptProperty_('FORM_ID');
  const form = FormApp.openById(formId);
  if (form.getDestinationId() !== configured.getId()) throw new Error('SANDBOX_GUARD: Form destination mismatch');
  assertConfigEnvironment_(configured);
  return { ss: configured, form: form };
}

function provisionSandbox() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    if (environment_() !== 'SANDBOX' || SpreadsheetApp.getActive().getId() !== ss.getId()) {
      throw new Error('SANDBOX_GUARD: refusing to provision this project');
    }
    let purchases = ss.getSheetByName(CANN.SHEETS.PURCHASES);
    if (!purchases) purchases = ss.insertSheet(CANN.SHEETS.PURCHASES);
    ensureHeaders_(purchases, CANN.PURCHASE_HEADERS);
    ensureCoreSchema_(ss);
    const config = requiredSheet_(ss, CANN.SHEETS.CONFIG);
    const marker = configValue_(ss, 'ENVIRONMENT', '');
    if (marker && marker !== 'SANDBOX') throw new Error('SANDBOX_GUARD: Config ENVIRONMENT mismatch');
    if (!marker) config.getRange(config.getLastRow() + 1, 1, 1, 3).setValues([['ENVIRONMENT', 'SANDBOX', 'Runtime environment marker']]);
    sandboxGuard_();
    seedSandboxFixture_(ss);
    applySheetSafety_(ss);
    updateFormAndDescriptionLocked_(ss);
    return sandboxMetrics_(ss);
  } finally {
    lock.releaseLock();
  }
}

function resetSandboxData() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const guarded = sandboxGuard_();
    guarded.form.deleteAllResponses();
    seedSandboxFixture_(guarded.ss);
    applySheetSafety_(guarded.ss);
    updateFormAndDescriptionLocked_(guarded.ss);
    return sandboxMetrics_(guarded.ss);
  } finally {
    lock.releaseLock();
  }
}

function seedSandboxFixture_(ss) {
  [
    CANN.SHEETS.PURCHASES,
    CANN.SHEETS.RESPONSES,
    CANN.SHEETS.EVENTS,
    CANN.SHEETS.LEDGER,
    CANN.SHEETS.MIGRATION_REPORT,
    CANN.SHEETS.APPLY_JOURNAL
  ].forEach(name => {
    const sheet = ss.getSheetByName(name);
    if (sheet) clearSandboxDataRows_(sheet);
  });
  if (configValue_(ss, CANN.PENDING_APPLY_KEY, null) != null) {
    setConfigValue_(
      ss,
      CANN.PENDING_APPLY_KEY,
      '',
      'Apply UUID awaiting finalization'
    );
  }
  PropertiesService.getScriptProperties().deleteProperty(
    CANN.SANDBOX_FAULT_PROPERTY
  );

  const purchaseRows = SANDBOX_FIXTURE.purchases.map(row => {
    if (row.length > CANN.PURCHASE_HEADERS.length) {
      throw new Error('SANDBOX_FIXTURE_PURCHASE_WIDTH_MISMATCH');
    }
    const padded = row.slice();
    while (padded.length < CANN.PURCHASE_HEADERS.length) padded.push('');
    return padded;
  });
  requiredSheet_(ss, CANN.SHEETS.PURCHASES)
    .getRange(2, 1, purchaseRows.length, CANN.PURCHASE_HEADERS.length)
    .setValues(purchaseRows);

  const eventRows = SANDBOX_FIXTURE.events.map((event, index) => event.concat([
    'SANDBOX_SEED',
    SANDBOX_FIXTURE.requestId,
    CANN.SHEETS.RESPONSES,
    index + 2
  ]));
  requiredSheet_(ss, CANN.SHEETS.EVENTS).getRange(2, 1, eventRows.length, CANN.EVENT_HEADERS.length).setValues(eventRows);
  const responseSheet = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const headers = headerMap_(responseSheet);
  requireHeaders_(headers, ['Timestamp', 'Product', 'Uses']);
  const width = responseSheet.getLastColumn();
  const responseRows = SANDBOX_FIXTURE.events.map(event => {
    const row = new Array(width).fill('');
    row[headers.Timestamp] = event[1];
    if (headers.Date !== undefined) row[headers.Date] = event[2];
    if (headers.Time !== undefined) row[headers.Time] = event[3];
    row[headers.Product] = event[5];
    row[headers.Uses] = event[6];
    if (headers['Weight code'] !== undefined) row[headers['Weight code']] = event[7];
    if (headers['Mark as Finished?'] !== undefined) row[headers['Mark as Finished?']] = event[8] ? 'Yes' : 'No';
    if (headers[CANN.COMPATIBILITY_EVENT_HEADER] !== undefined) {
      row[headers[CANN.COMPATIBILITY_EVENT_HEADER]] = event[0];
      row[headers[CANN.COMPATIBILITY_REQUEST_HEADER]] =
        SANDBOX_FIXTURE.requestId;
    }
    return row;
  });
  responseSheet.getRange(2, 1, responseRows.length, width).setValues(responseRows);

  if (CANN.PURCHASE_HEADERS.indexOf('Last quantity') >= 0) {
    const rebuild = rebuildInteractionSummaryLocked_(ss, true);
    if (!rebuild.fastPathEnabled || rebuild.reconciliationDifferences !== 0) {
      throw new Error('SANDBOX_INTERACTION_SUMMARY_REBUILD_FAILED');
    }
  }
}

function clearSandboxDataRows_(sheet) {
  if (sheet.getLastRow() > 1) sheet.getRange(2, 1, sheet.getLastRow() - 1, sheet.getMaxColumns()).clearContent();
}

function sandboxMetrics_(ss) {
  const purchases = readDataRows_(requiredSheet_(ss, CANN.SHEETS.PURCHASES));
  const events = readDataRows_(requiredSheet_(ss, CANN.SHEETS.EVENTS));
  const uses = events.reduce((sum, row) => sum + finiteNumberOr_(row[6], 0), 0);
  const statuses = purchases.reduce((out, row) => { const value = String(row[7]); out[value] = (out[value] || 0) + 1; return out; }, {});
  const result = { purchases: purchases.length, responses: Math.max(0, requiredSheet_(ss, CANN.SHEETS.RESPONSES).getLastRow() - 1), events: events.length, uniqueEvents: new Set(events.map(row => row[0])).size, unresolved: 0, ledgerRows: 0, migrationRows: 0, totalUses: uses, active: statuses['0'] || 0, finished: statuses['1'] || 0, unopened: statuses['2'] || 0 };
  if (JSON.stringify(result) !== JSON.stringify({ purchases: 6, responses: 5, events: 5, uniqueEvents: 5, unresolved: 0, ledgerRows: 0, migrationRows: 0, totalUses: 5.25, active: 3, finished: 2, unopened: 1 })) throw new Error('SANDBOX_FIXTURE_MISMATCH: ' + JSON.stringify(result));
  if (CANN.PURCHASE_HEADERS.indexOf('Last quantity') >= 0) {
    const reconciliation = reconcileInteractionSummary_(ss);
    if (reconciliation.differences.length) {
      throw new Error('SANDBOX_INTERACTION_SUMMARY_MISMATCH: ' + JSON.stringify(reconciliation.differences));
    }
  }
  return result;
}
