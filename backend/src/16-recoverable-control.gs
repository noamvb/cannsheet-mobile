function enableRecoverableSyncApply() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    assertSpreadsheetTimeZone_(ss);
    assertAdvancedSheetsService_();
    ensureCompatibilityIdentitySchema_(ss);
    ensureSheet_(ss, CANN.SHEETS.APPLY_JOURNAL, CANN.APPLY_JOURNAL_HEADERS);
    ensureConfigKey_(
      ss,
      CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY,
      0,
      'Recoverable multi-sheet apply version'
    );
    ensureConfigKey_(
      ss,
      CANN.PENDING_APPLY_KEY,
      '',
      'Apply UUID awaiting finalization'
    );
    repairRecoverableStateLocked_({ ss: ss, config: {}, sheets: {}, headers: {} });
    // Close the prepare/deploy/enable window: marker-0 writes made after the
    // first prepare receive their identities before marker 1 becomes visible.
    backfillCompatibilityIdentities_(ss);

    const configSheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
    const config = configValuesFromSheet_(configSheet, headerMap_(configSheet));
    if (!interactionSummaryReady_(config)) {
      throw new Error(
        'RECOVERABLE_APPLY_ENABLE_BLOCKED: interaction summary fast path must be enabled first'
      );
    }
    const reconciliation = reconcileRecoverableSyncApply_(ss);
    if (reconciliation.blockingDifferences.length) {
      throw new Error(
        'RECOVERABLE_APPLY_ENABLE_BLOCKED: ' +
        JSON.stringify(reconciliation.blockingDifferences.slice(0, 10))
      );
    }
    setConfigValue_(
      ss,
      CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY,
      CANN.RECOVERABLE_SYNC_APPLY_VERSION,
      'Recoverable multi-sheet apply version'
    );
    SpreadsheetApp.flush();
    const result = {
      configVersion: CANN.RECOVERABLE_SYNC_APPLY_VERSION,
      fastPathEnabled: true,
      blockingDifferences: 0
    };
    console.log(JSON.stringify({
      type: 'recoverable_sync_apply_enabled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function disableRecoverableSyncApply() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    repairRecoverableStateLocked_({ ss: ss, config: {}, sheets: {}, headers: {} });
    setConfigValue_(
      ss,
      CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY,
      0,
      'Recoverable multi-sheet apply version'
    );
    SpreadsheetApp.flush();
    const result = {
      configVersion: 0,
      fastPathEnabled: false,
      fallback: 'LEGACY_MULTI_WRITE'
    };
    console.log(JSON.stringify({
      type: 'recoverable_sync_apply_disabled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function repairRecoverableSyncApply() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const runtimeContext = {
      ss: ss,
      config: {},
      sheets: {},
      headers: {}
    };
    const pending = repairRecoverableStateLocked_(runtimeContext);
    const configSheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
    runtimeContext.config = configValuesFromSheet_(
      configSheet,
      headerMap_(configSheet)
    );
    runtimeContext.sheets = {
      purchases: requiredSheet_(ss, CANN.SHEETS.PURCHASES),
      responses: requiredSheet_(ss, CANN.SHEETS.RESPONSES),
      events: requiredSheet_(ss, CANN.SHEETS.EVENTS),
      ledger: requiredSheet_(ss, CANN.SHEETS.LEDGER),
      config: configSheet,
      migrationReport: requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT),
      applyJournal: requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL)
    };
    runtimeContext.headers = {
      purchases: headerMap_(runtimeContext.sheets.purchases),
      responses: headerMap_(runtimeContext.sheets.responses),
      events: headerMap_(runtimeContext.sheets.events),
      ledger: headerMap_(runtimeContext.sheets.ledger),
      config: headerMap_(configSheet),
      migrationReport: headerMap_(runtimeContext.sheets.migrationReport),
      applyJournal: headerMap_(runtimeContext.sheets.applyJournal)
    };
    const orphans = recoverableSyncApplyReady_(runtimeContext.config)
      ? repairOrphanFormResponsesLocked_(runtimeContext)
      : { repairedRows: 0, scannedRows: 0, issues: [] };
    const result = { pending: pending, orphanForms: orphans };
    console.log(JSON.stringify({
      type: 'recoverable_sync_apply_repaired',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function reconcileRecoverableSyncApply() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const result = reconcileRecoverableSyncApply_(ss);
    console.log(JSON.stringify({
      type: 'recoverable_sync_apply_reconciled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function ensureCompatibilityIdentitySchema_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const headers = headerMap_(sheet);
  const eventIndex = headers[CANN.COMPATIBILITY_EVENT_HEADER];
  const requestIndex = headers[CANN.COMPATIBILITY_REQUEST_HEADER];
  if ((eventIndex === undefined) !== (requestIndex === undefined)) {
    throw new Error(
      'SCHEMA_MISMATCH: incomplete compatibility identity header pair'
    );
  }
  if (eventIndex !== undefined) {
    requireCompatibilityIdentityHeaders_(headers);
    return {
      eventColumn: eventIndex + 1,
      requestColumn: requestIndex + 1,
      originalOwnedColumns: eventIndex
    };
  }

  // getLastColumn() is the last column with content, not the grid width. This
  // places the identities after the actual Form-owned columns: J/K in the
  // inspected production sheet and H/I in the inspected sandbox.
  const firstIdentityColumn = sheet.getLastColumn() + 1;
  if (sheet.getMaxColumns() < firstIdentityColumn + 1) {
    sheet.insertColumnsAfter(
      sheet.getMaxColumns(),
      firstIdentityColumn + 1 - sheet.getMaxColumns()
    );
  }
  sheet.getRange(1, firstIdentityColumn, 1, 2).setValues([[
    CANN.COMPATIBILITY_EVENT_HEADER,
    CANN.COMPATIBILITY_REQUEST_HEADER
  ]]);
  return {
    eventColumn: firstIdentityColumn,
    requestColumn: firstIdentityColumn + 1,
    originalOwnedColumns: firstIdentityColumn - 1
  };
}

function backfillCompatibilityIdentities_(ss) {
  const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const responseHeaders = headerMap_(responses);
  requireCompatibilityIdentityHeaders_(responseHeaders);
  const events = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const eventHeaders = headerMap_(events);
  requireExactHeaders_(eventHeaders, CANN.EVENT_HEADERS, CANN.SHEETS.EVENTS);
  const responseLastRow = responses.getLastRow();
  if (responseLastRow < 2) return { rowsBackfilled: 0 };

  const responseRows = responses.getRange(
    2,
    1,
    responseLastRow - 1,
    responses.getLastColumn()
  ).getValues();
  const identityValues = responseRows.map(row => [
    value_(row, responseHeaders, CANN.COMPATIBILITY_EVENT_HEADER),
    value_(row, responseHeaders, CANN.COMPATIBILITY_REQUEST_HEADER)
  ]);
  const seenSourceRows = {};
  const validationErrors = [];
  let rowsBackfilled = 0;

  readDataRows_(events).forEach(row => {
    if (text_(value_(row, eventHeaders, 'Legacy Source Sheet')) !==
        responses.getName()) return;
    const sourceRow = Number(value_(row, eventHeaders, 'Legacy Source Row'));
    const eventId = text_(value_(row, eventHeaders, 'Event UUID'));
    if (!Number.isInteger(sourceRow) || sourceRow < 2 ||
        sourceRow > responseLastRow) {
      validationErrors.push({
        type: 'INVALID_SOURCE_ROW',
        eventId: eventId,
        sourceRow: sourceRow
      });
      return;
    }
    if (seenSourceRows[sourceRow] && seenSourceRows[sourceRow] !== eventId) {
      validationErrors.push({
        type: 'DUPLICATE_SOURCE_ROW',
        sourceRow: sourceRow,
        firstEventId: seenSourceRows[sourceRow],
        eventId: eventId
      });
      return;
    }
    seenSourceRows[sourceRow] = eventId;
    const responseRow = responseRows[sourceRow - 2];
    const timestampMatches =
      timestampMillisOrNull_(value_(responseRow, responseHeaders, 'Timestamp')) ===
      timestampMillisOrNull_(value_(row, eventHeaders, 'Timestamp'));
    const productMatches =
      text_(value_(responseRow, responseHeaders, 'Product')) ===
      text_(value_(row, eventHeaders, 'Legacy Product ID'));
    const responseUses = finiteNumber_(value_(responseRow, responseHeaders, 'Uses'));
    const canonicalUses = finiteNumber_(value_(row, eventHeaders, 'Uses'));
    const usesMatches = responseUses != null && canonicalUses != null &&
      Math.abs(responseUses - canonicalUses) <= 1e-9;
    if (!timestampMatches || !productMatches || !usesMatches) {
      validationErrors.push({
        type: 'SOURCE_ROW_CONTENT_MISMATCH',
        eventId: eventId,
        sourceRow: sourceRow
      });
      return;
    }

    const requestId = text_(value_(row, eventHeaders, 'Request UUID'));
    const existingEventId = text_(identityValues[sourceRow - 2][0]);
    const existingRequestId = text_(identityValues[sourceRow - 2][1]);
    if ((existingEventId && existingEventId !== eventId) ||
        (existingRequestId && existingRequestId !== requestId)) {
      validationErrors.push({
        type: 'IDENTITY_CONFLICT',
        eventId: eventId,
        sourceRow: sourceRow
      });
      return;
    }
    if (!existingEventId && eventId) rowsBackfilled++;
    identityValues[sourceRow - 2] = [eventId, requestId];
  });

  responses.getRange(
    2,
    responseHeaders[CANN.COMPATIBILITY_EVENT_HEADER] + 1,
    identityValues.length,
    2
  ).setValues(identityValues);
  SpreadsheetApp.flush();
  if (validationErrors.length) {
    throw new Error(
      'COMPATIBILITY_IDENTITY_BACKFILL_BLOCKED: safe rows were retained; ' +
      JSON.stringify({
        rowsBackfilled: rowsBackfilled,
        validationErrors: validationErrors.slice(0, 10)
      })
    );
  }
  return { rowsBackfilled: rowsBackfilled };
}

function reconcileRecoverableSyncApply_(ss) {
  const differences = [];
  const blockingDifferences = [];
  const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const responseHeaders = headerMap_(responses);
  requireCompatibilityIdentityHeaders_(responseHeaders);
  const events = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const eventHeaders = headerMap_(events);
  requireExactHeaders_(eventHeaders, CANN.EVENT_HEADERS, CANN.SHEETS.EVENTS);
  const responseLastRow = responses.getLastRow();
  const responseRows = responseLastRow < 2
    ? []
    : responses.getRange(
      2,
      1,
      responseLastRow - 1,
      responses.getLastColumn()
    ).getValues();
  const canonicalRows = readDataRows_(events);
  const canonicalByEventId = {};
  const seenSourceRows = {};

  canonicalRows.forEach((row, index) => {
    const eventId = text_(value_(row, eventHeaders, 'Event UUID'));
    if (!eventId) {
      blockingDifferences.push({
        type: 'MALFORMED_CANONICAL_EVENT',
        canonicalRow: index + 2
      });
      return;
    }
    if (canonicalByEventId[eventId]) {
      blockingDifferences.push({
        type: 'DUPLICATE_CANONICAL_EVENT_UUID',
        eventId: eventId
      });
    }
    canonicalByEventId[eventId] = { row: row, rowNumber: index + 2 };
    const lineageSheet = text_(value_(
      row,
      eventHeaders,
      'Legacy Source Sheet'
    ));
    if (lineageSheet !== responses.getName()) {
      blockingDifferences.push({
        type: 'MISSING_OR_INVALID_COMPATIBILITY_LINEAGE',
        eventId: eventId,
        sourceSheet: lineageSheet
      });
      return;
    }

    const sourceRow = Number(value_(row, eventHeaders, 'Legacy Source Row'));
    if (!Number.isInteger(sourceRow) || sourceRow < 2 ||
        sourceRow > responseLastRow) {
      blockingDifferences.push({
        type: 'INVALID_COMPATIBILITY_LINEAGE',
        eventId: eventId,
        sourceRow: sourceRow
      });
      return;
    }
    if (seenSourceRows[sourceRow] && seenSourceRows[sourceRow] !== eventId) {
      blockingDifferences.push({
        type: 'DUPLICATE_COMPATIBILITY_LINEAGE',
        eventId: eventId,
        sourceRow: sourceRow,
        firstEventId: seenSourceRows[sourceRow]
      });
      return;
    }
    seenSourceRows[sourceRow] = eventId;
    const responseRow = responseRows[sourceRow - 2];
    const identityMatches =
      text_(value_(
        responseRow,
        responseHeaders,
        CANN.COMPATIBILITY_EVENT_HEADER
      )) === eventId &&
      text_(value_(
        responseRow,
        responseHeaders,
        CANN.COMPATIBILITY_REQUEST_HEADER
      )) === text_(value_(row, eventHeaders, 'Request UUID'));
    const dataMatches =
      timestampMillisOrNull_(value_(responseRow, responseHeaders, 'Timestamp')) ===
        timestampMillisOrNull_(value_(row, eventHeaders, 'Timestamp')) &&
      text_(value_(responseRow, responseHeaders, 'Product')) ===
        text_(value_(row, eventHeaders, 'Legacy Product ID')) &&
      Math.abs(
        finiteNumberOr_(value_(responseRow, responseHeaders, 'Uses'), NaN) -
        finiteNumberOr_(value_(row, eventHeaders, 'Uses'), NaN)
      ) <= 1e-9;
    if (!identityMatches || !dataMatches) {
      blockingDifferences.push({
        type: 'COMPATIBILITY_LINEAGE_MISMATCH',
        eventId: eventId,
        sourceRow: sourceRow
      });
    }
  });

  if (responseRows.length) {
    const seenCompatibilityIds = {};
    responseRows.forEach((row, index) => {
      const eventId = text_(value_(
        row,
        responseHeaders,
        CANN.COMPATIBILITY_EVENT_HEADER
      ));
      if (!eventId) {
        const dataBearing =
          timestampMillisOrNull_(value_(
            row,
            responseHeaders,
            'Timestamp'
          )) != null &&
          !!text_(value_(row, responseHeaders, 'Product')) &&
          finiteNumber_(value_(row, responseHeaders, 'Uses')) != null;
        if (dataBearing) {
          blockingDifferences.push({
            type: 'UNIDENTIFIED_COMPATIBILITY_ROW',
            sourceRow: index + 2
          });
        }
        return;
      }
      if (seenCompatibilityIds[eventId]) {
        blockingDifferences.push({
          type: 'DUPLICATE_COMPATIBILITY_EVENT_UUID',
          eventId: eventId,
          sourceRow: index + 2
        });
      }
      seenCompatibilityIds[eventId] = true;
      if (!canonicalByEventId[eventId]) {
        blockingDifferences.push({
          type: 'COMPATIBILITY_WITHOUT_CANONICAL',
          eventId: eventId,
          sourceRow: index + 2
        });
      }
    });
  }

  const pendingApplyId = text_(configValue_(ss, CANN.PENDING_APPLY_KEY, ''));
  const journal = requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
  const journalHeaders = headerMap_(journal);
  requireExactHeaders_(
    journalHeaders,
    CANN.APPLY_JOURNAL_HEADERS,
    CANN.SHEETS.APPLY_JOURNAL
  );
  const incompleteJournalIds = [];
  readDataRows_(journal).forEach(row => {
    const applyId = text_(value_(row, journalHeaders, 'Apply UUID'));
    const state = text_(value_(row, journalHeaders, 'State'));
    if (state !== 'CORE_COMMITTED' && state !== 'COMPLETE') {
      blockingDifferences.push({
        type: 'INVALID_JOURNAL_STATE',
        applyId: applyId,
        state: state
      });
    }
    if (state === 'CORE_COMMITTED') incompleteJournalIds.push(applyId);
  });
  if (pendingApplyId) {
    blockingDifferences.push({
      type: 'PENDING_APPLY',
      applyId: pendingApplyId
    });
  }
  incompleteJournalIds.forEach(applyId => {
    blockingDifferences.push({
      type: 'INCOMPLETE_JOURNAL',
      applyId: applyId,
      pendingPointerMatches: pendingApplyId === applyId
    });
  });
  if (pendingApplyId &&
      incompleteJournalIds.indexOf(pendingApplyId) < 0) {
    blockingDifferences.push({
      type: 'PENDING_POINTER_WITHOUT_INCOMPLETE_JOURNAL',
      applyId: pendingApplyId
    });
  }
  if (incompleteJournalIds.length > 1) {
    blockingDifferences.push({
      type: 'MULTIPLE_INCOMPLETE_JOURNALS',
      applyIds: incompleteJournalIds
    });
  }

  const summary = reconcileInteractionSummary_(ss);
  summary.differences.forEach(item => {
    blockingDifferences.push({
      type: 'INTERACTION_SUMMARY_' + item.type,
      detail: item
    });
  });
  const compatibilityCanonical = reconcileReliabilityMigration_(ss);
  compatibilityCanonical.differences.forEach(item => {
    blockingDifferences.push({
      type: 'COMPATIBILITY_CANONICAL_DRIFT',
      detail: item
    });
  });
  if (compatibilityCanonical.responseEventCount !==
      compatibilityCanonical.canonicalEventCount) {
    blockingDifferences.push({
      type: 'COMPATIBILITY_CANONICAL_COUNT_MISMATCH',
      responseEventCount: compatibilityCanonical.responseEventCount,
      canonicalEventCount: compatibilityCanonical.canonicalEventCount
    });
  }
  if (compatibilityCanonical.unresolvedRows) {
    blockingDifferences.push({
      type: 'UNRESOLVED_MIGRATION_ROWS',
      unresolvedRows: compatibilityCanonical.unresolvedRows
    });
  }

  differences.push.apply(differences, blockingDifferences);
  return {
    pendingApplyId: pendingApplyId || null,
    journalRows: Math.max(0, journal.getLastRow() - 1),
    incompleteJournalRows: incompleteJournalIds.length,
    canonicalRows: canonicalRows.length,
    interactionSummaryDifferences: summary.differences.length,
    differences: differences,
    blockingDifferences: blockingDifferences
  };
}

/**
 * doPost reads Config before waiting for the script lock. Refresh every
 * mutation gate after acquiring it so a concurrent provision, enable, disable,
 * or predecessor core commit cannot be hidden by that stale request context.
 */
function refreshRecoverableSyncApplyStateLocked_(runtimeContext) {
  const ss = runtimeContext.ss;
  const configSheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
  const configHeaders = headerMap_(configSheet);
  requireExactHeaders_(
    configHeaders,
    ['Key', 'Value', 'Description'],
    CANN.SHEETS.CONFIG
  );
  const values = configValuesFromSheet_(configSheet, configHeaders);
  if (text_(values.ENVIRONMENT) !== runtimeContext.environment) {
    throw new Error('CONFIGURATION_ERROR: Config ENVIRONMENT mismatch');
  }
  assertSupportedSchemaVersion_(values);
  const summaryReady = interactionSummaryReady_(values);
  const recoverableReady = recoverableSyncApplyReady_(values);
  const correctionCapability = consumptionCorrectionCapability_(values);
  if (correctionCapability.version && !summaryReady) {
    throw new Error(
      'SCHEMA_MISMATCH: consumption corrections require the ' +
      'interaction-summary projection'
    );
  }
  runtimeContext.config[CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY] =
    values[CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY];
  runtimeContext.config[CANN.PENDING_APPLY_KEY] =
    values[CANN.PENDING_APPLY_KEY];
  runtimeContext.config[CANN.CONSUMPTION_CORRECTION_VERSION_KEY] =
    values[CANN.CONSUMPTION_CORRECTION_VERSION_KEY];
  runtimeContext.config[CANN.CONSUMPTION_CORRECTION_WRITES_KEY] =
    values[CANN.CONSUMPTION_CORRECTION_WRITES_KEY];
  runtimeContext.config.CONSUMPTION_CORRECTION_VERSION =
    correctionCapability.version;
  runtimeContext.config.CONSUMPTION_CORRECTION_WRITES_ENABLED =
    correctionCapability.writesEnabled;
  runtimeContext.config.RECOVERABLE_SYNC_APPLY_READY =
    recoverableReady;
  if (recoverableReady) {
    const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
    runtimeContext.headers.responses = headerMap_(responses);
    requireCompatibilityIdentityHeaders_(runtimeContext.headers.responses);
    runtimeContext.sheets.applyJournal =
      requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
    runtimeContext.headers.applyJournal =
      headerMap_(runtimeContext.sheets.applyJournal);
    requireExactHeaders_(
      runtimeContext.headers.applyJournal,
      CANN.APPLY_JOURNAL_HEADERS,
      CANN.SHEETS.APPLY_JOURNAL
    );
  }
  if (correctionCapability.version) {
    runtimeContext.sheets.corrections =
      requiredSheet_(ss, CANN.SHEETS.CORRECTIONS);
    runtimeContext.headers.corrections =
      headerMap_(runtimeContext.sheets.corrections);
    requireExactHeaders_(
      runtimeContext.headers.corrections,
      CANN.CORRECTION_HEADERS,
      CANN.SHEETS.CORRECTIONS
    );
  }
}

