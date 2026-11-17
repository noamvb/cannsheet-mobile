function readAnalyticsSnapshot_(resource, expectedEnvironment, cursor) {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.ANALYTICS_READ_LOCK_TIMEOUT_MS)) {
    throw analyticsError_('BACKEND_BUSY', 'The backend is busy; retry this request');
  }
  let rawData;
  const ss = spreadsheet_();
  try {
    rawData = fetchAnalyticsRawData_(ss, resource, expectedEnvironment);
  } finally {
    lock.releaseLock();
  }

  const runtimeConfig = rawData.runtimeConfig;
  const correctionCapability = rawData.correctionCapability;

  const currentPurchaseLastRow = rawData.purchaseLastRow;
  const currentEventLastRow = rawData.eventLastRow;
  const currentCorrectionLastRow = rawData.correctionLastRow;
  const purchaseLastRow = cursor ? cursor.purchaseLastRow : currentPurchaseLastRow;
  const eventLastRow = cursor ? cursor.eventLastRow : currentEventLastRow;
  if (
    cursor &&
    correctionCapability.version &&
    cursor.correctionLastRow == null
  ) {
    throw analyticsError_(
      'CURSOR_STALE',
      'The captured history snapshot predates correction support'
    );
  }
  if (
    cursor &&
    correctionCapability.version &&
    cursor.correctionLastRow !== currentCorrectionLastRow
  ) {
    throw analyticsError_(
      'CURSOR_STALE',
      'The captured history snapshot changed after a correction'
    );
  }
  const correctionLastRow = cursor && cursor.correctionLastRow != null
    ? cursor.correctionLastRow
    : currentCorrectionLastRow;
  if (
    currentPurchaseLastRow < purchaseLastRow ||
    currentEventLastRow < eventLastRow ||
    currentCorrectionLastRow < correctionLastRow
  ) {
    throw analyticsError_(
      'CURSOR_STALE',
      'The captured history snapshot is no longer available'
    );
  }

  const applyEvidence = rawData.applyEvidence || {
    eventIds: {},
    correctionIds: {},
    finishActionProductIds: {}
  };

  return {
    environment: expectedEnvironment,
    taxRate: finiteNumberOr_(runtimeConfig.values.TAX_RATE, 0.13),
    purchaseHeaders: rawData.purchaseHeaders,
    eventHeaders: rawData.eventHeaders,
    correctionHeaders: rawData.correctionHeaders,
    ledgerHeaders: rawData.ledgerHeaders,
    purchaseRows: buildAnalyticsRowsFromValues_(
      rawData.purchasesValues,
      purchaseLastRow
    ),
    eventRows: buildAnalyticsRowsFromValues_(
      rawData.eventsValues,
      eventLastRow
    ),
    correctionRows: correctionCapability.version
      ? buildAnalyticsRowsFromValues_(
        rawData.correctionsValues,
        correctionLastRow
      )
      : [],
    ledgerRows: resource === 'insights'
      ? buildAnalyticsRowsFromValues_(
        rawData.ledgerValues,
        rawData.ledgerLastRow
      )
      : [],
    purchaseLastRow: purchaseLastRow,
    eventLastRow: eventLastRow,
    correctionLastRow: correctionLastRow,
    ledgerLastRow: rawData.ledgerLastRow || 1,
    correctionVersion: correctionCapability.version,
    correctionWritesEnabled: correctionCapability.writesEnabled,
    finishActionProductIds: applyEvidence.finishActionProductIds,
    finishProvenanceEventIds: applyEvidence.eventIds,
    finishProvenanceCorrectionIds: applyEvidence.correctionIds
  };
}

function fetchAnalyticsRawData_(ss, resource, expectedEnvironment) {
  const runtimeConfig = readAndAssertRuntimeConfig_(ss, expectedEnvironment);
  assertSupportedSchemaVersion_(runtimeConfig.values);
  const correctionCapability = consumptionCorrectionCapability_(
    runtimeConfig.values
  );
  if (!recoverableSyncApplyReady_(runtimeConfig.values)) {
    throw analyticsError_(
      'SCHEMA_MISMATCH',
      'Recoverable sync apply must be enabled before analytics reads'
    );
  }
  if (text_(runtimeConfig.values[CANN.PENDING_APPLY_KEY])) {
    throw analyticsError_(
      'BACKEND_BUSY',
      'A recoverable sync apply is pending; retry this request'
    );
  }
  if (ss.getSpreadsheetTimeZone() !== CANN.TIME_ZONE) {
    throw analyticsError_(
      'CONFIGURATION_ERROR',
      'Spreadsheet time zone must be ' + CANN.TIME_ZONE
    );
  }

  if (
    typeof Sheets !== 'undefined' &&
    Sheets &&
    Sheets.Spreadsheets &&
    Sheets.Spreadsheets.Values &&
    typeof Sheets.Spreadsheets.Values.batchGet === 'function'
  ) {
    try {
      return fetchAnalyticsDataSheetsBatch_(
        ss,
        resource,
        expectedEnvironment,
        runtimeConfig,
        correctionCapability
      );
    } catch (error) {
      console.warn(
        'Advanced Sheets batchGet failed; falling back to sequential read: ' +
        conciseError_(error)
      );
    }
  }
  return fetchAnalyticsDataSheetsSequential_(
    ss,
    resource,
    expectedEnvironment,
    runtimeConfig,
    correctionCapability
  );
}

function fetchAnalyticsDataSheetsBatch_(
  ss,
  resource,
  expectedEnvironment,
  runtimeConfig,
  correctionCapability
) {
  const existingSheets = ss.getSheets();
  const existingSheetNames = {};
  existingSheets.forEach(sheet => {
    existingSheetNames[sheet.getName()] = true;
  });

  if (
    !existingSheetNames[CANN.SHEETS.PURCHASES] ||
    !existingSheetNames[CANN.SHEETS.EVENTS]
  ) {
    throw new Error('SCHEMA_MISMATCH: missing core sheets');
  }

  const requestedRanges = [
    "'" + CANN.SHEETS.PURCHASES + "'!A1:Z",
    "'" + CANN.SHEETS.EVENTS + "'!A1:Z"
  ];
  if (
    correctionCapability.version &&
    existingSheetNames[CANN.SHEETS.CORRECTIONS]
  ) {
    requestedRanges.push("'" + CANN.SHEETS.CORRECTIONS + "'!A1:Z");
  }
  if (resource === 'insights' && existingSheetNames[CANN.SHEETS.LEDGER]) {
    requestedRanges.push("'" + CANN.SHEETS.LEDGER + "'!A1:Z");
  }
  if (
    correctionCapability.version &&
    existingSheetNames[CANN.SHEETS.APPLY_JOURNAL]
  ) {
    requestedRanges.push("'" + CANN.SHEETS.APPLY_JOURNAL + "'!A1:Z");
  }

  const batchResponse = Sheets.Spreadsheets.Values.batchGet(ss.getId(), {
    ranges: requestedRanges,
    valueRenderOption: 'UNFORMATTED_VALUE',
    dateTimeRenderOption: 'SERIAL_NUMBER'
  });
  const valueRanges = (batchResponse && batchResponse.valueRanges) || [];
  const rangeMap = {};
  valueRanges.forEach(vr => {
    if (!vr || !vr.range) return;
    const sheetName = vr.range.split('!')[0].replace(/^'|'$/g, '');
    rangeMap[sheetName] = normalizeBatchDateColumns_(
      sheetName,
      padBatchRowWidth_(vr.values || [])
    );
  });

  const purchasesValues = rangeMap[CANN.SHEETS.PURCHASES] || [];
  const eventsValues = rangeMap[CANN.SHEETS.EVENTS] || [];
  const correctionsValues = rangeMap[CANN.SHEETS.CORRECTIONS] || [];
  const ledgerValues = rangeMap[CANN.SHEETS.LEDGER] || [];
  const applyJournalValues = rangeMap[CANN.SHEETS.APPLY_JOURNAL] || [];

  const purchaseHeaders = headerMapFromRow_(purchasesValues[0]);
  const eventHeaders = headerMapFromRow_(eventsValues[0]);
  const correctionHeaders = correctionsValues.length
    ? headerMapFromRow_(correctionsValues[0])
    : {};
  const ledgerHeaders = ledgerValues.length
    ? headerMapFromRow_(ledgerValues[0])
    : {};
  const applyJournalHeaders = applyJournalValues.length
    ? headerMapFromRow_(applyJournalValues[0])
    : {};

  requireExactHeaders_(
    purchaseHeaders,
    CANN.PURCHASE_HEADERS,
    CANN.SHEETS.PURCHASES
  );
  requireExactHeaders_(
    eventHeaders,
    CANN.EVENT_HEADERS,
    CANN.SHEETS.EVENTS
  );
  if (correctionCapability.version && correctionsValues.length > 0) {
    requireExactHeaders_(
      correctionHeaders,
      CANN.CORRECTION_HEADERS,
      CANN.SHEETS.CORRECTIONS
    );
  }
  if (resource === 'insights' && ledgerValues.length > 0) {
    requireExactHeaders_(
      ledgerHeaders,
      CANN.LEDGER_HEADERS,
      CANN.SHEETS.LEDGER
    );
  }

  const purchaseLastRow = purchasesValues.length;
  const eventLastRow = eventsValues.length;
  const correctionLastRow = correctionsValues.length || 1;
  const ledgerLastRow = ledgerValues.length || 1;

  const applyEvidence = correctionCapability.version
    ? recoverableApplyEvidenceFromValues_(
      applyJournalValues.slice(1),
      applyJournalHeaders
    )
    : { eventIds: {}, correctionIds: {}, finishActionProductIds: {} };

  return {
    spreadsheetTimeZone: ss.getSpreadsheetTimeZone(),
    runtimeConfig: runtimeConfig,
    correctionCapability: correctionCapability,
    purchaseHeaders: purchaseHeaders,
    eventHeaders: eventHeaders,
    correctionHeaders: correctionHeaders,
    ledgerHeaders: ledgerHeaders,
    purchasesValues: purchasesValues,
    eventsValues: eventsValues,
    correctionsValues: correctionsValues,
    ledgerValues: ledgerValues,
    purchaseLastRow: purchaseLastRow,
    eventLastRow: eventLastRow,
    correctionLastRow: correctionLastRow,
    ledgerLastRow: ledgerLastRow,
    applyEvidence: applyEvidence
  };
}

function fetchAnalyticsDataSheetsSequential_(
  ss,
  resource,
  expectedEnvironment,
  runtimeConfig,
  correctionCapability
) {
  const purchases = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const events = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const corrections = correctionCapability.version
    ? requiredSheet_(ss, CANN.SHEETS.CORRECTIONS)
    : null;
  const purchaseHeaders = headerMap_(purchases);
  const eventHeaders = headerMap_(events);
  const correctionHeaders = corrections ? headerMap_(corrections) : {};
  requireExactHeaders_(
    purchaseHeaders,
    CANN.PURCHASE_HEADERS,
    CANN.SHEETS.PURCHASES
  );
  requireExactHeaders_(
    eventHeaders,
    CANN.EVENT_HEADERS,
    CANN.SHEETS.EVENTS
  );
  if (corrections) {
    requireExactHeaders_(
      correctionHeaders,
      CANN.CORRECTION_HEADERS,
      CANN.SHEETS.CORRECTIONS
    );
  }

  const purchaseLastRow = purchases.getLastRow();
  const eventLastRow = events.getLastRow();
  const correctionLastRow = corrections
    ? corrections.getLastRow()
    : 1;

  let ledger = null;
  let ledgerHeaders = {};
  let ledgerLastRow = 1;
  if (resource === 'insights') {
    ledger = requiredSheet_(ss, CANN.SHEETS.LEDGER);
    ledgerHeaders = headerMap_(ledger);
    requireExactHeaders_(
      ledgerHeaders,
      CANN.LEDGER_HEADERS,
      CANN.SHEETS.LEDGER
    );
    ledgerLastRow = ledger.getLastRow();
  }

  const applyEvidence = correctionCapability.version
    ? recoverableApplyEvidence_(ss)
    : {
      eventIds: {},
      correctionIds: {},
      finishActionProductIds: {}
    };

  const purchasesValues = purchaseLastRow > 0
    ? purchases.getRange(1, 1, purchaseLastRow, purchases.getLastColumn()).getValues()
    : [];
  const eventsValues = eventLastRow > 0
    ? events.getRange(1, 1, eventLastRow, events.getLastColumn()).getValues()
    : [];
  const correctionsValues = corrections && correctionLastRow > 0
    ? corrections.getRange(1, 1, correctionLastRow, corrections.getLastColumn()).getValues()
    : [];
  const ledgerValues = ledger && ledgerLastRow > 0
    ? ledger.getRange(1, 1, ledgerLastRow, ledger.getLastColumn()).getValues()
    : [];

  return {
    spreadsheetTimeZone: ss.getSpreadsheetTimeZone(),
    runtimeConfig: runtimeConfig,
    correctionCapability: correctionCapability,
    purchaseHeaders: purchaseHeaders,
    eventHeaders: eventHeaders,
    correctionHeaders: correctionHeaders,
    ledgerHeaders: ledgerHeaders,
    purchasesValues: purchasesValues,
    eventsValues: eventsValues,
    correctionsValues: correctionsValues,
    ledgerValues: ledgerValues,
    purchaseLastRow: purchaseLastRow,
    eventLastRow: eventLastRow,
    correctionLastRow: correctionLastRow,
    ledgerLastRow: ledgerLastRow,
    applyEvidence: applyEvidence
  };
}

function buildAnalyticsRowsFromValues_(values, lastRow) {
  if (!values || values.length < 2) return [];
  const limit = lastRow ? Math.min(lastRow, values.length) : values.length;
  const result = [];
  for (let i = 1; i < limit; i++) {
    result.push({
      canonicalRow: i + 1,
      cells: values[i] || []
    });
  }
  return result;
}

function readAnalyticsRowsThrough_(sheet, lastRow) {
  if (!sheet || lastRow < 2) return [];
  return sheet.getRange(
    2,
    1,
    lastRow - 1,
    sheet.getLastColumn()
  ).getValues().map((cells, index) => ({
    canonicalRow: index + 2,
    cells: cells
  }));
}

function headerMapFromRow_(headerRow) {
  const result = {};
  (headerRow || []).forEach((header, index) => {
    const name = text_(header);
    if (name && result[name] === undefined) result[name] = index;
  });
  return result;
}

function configValuesFromRows_(rows, headers) {
  const result = {};
  (rows || []).forEach(row => {
    const key = text_(value_(row, headers, 'Key'));
    if (key) result[key] = value_(row, headers, 'Value');
  });
  return result;
}

function newAnalyticsQuality_() {
  return {
    estimatedPurchaseDateCount: 0,
    unknownPurchaseDateCount: 0,
    unknownPersonalCostCount: 0,
    unknownBorrowedCostCount: 0,
    ambiguousThcCount: 0,
    invalidThcCount: 0,
    invalidGramsCount: 0,
    unknownStatusCount: 0,
    unknownBorrowedFlagCount: 0,
    localDateMismatchCount: 0,
    localTimeMismatchCount: 0,
    unknownSourceCount: 0,
    invalidUnreferencedPurchaseRowCount: 0
  };
}

function analyticsQualityResponse_(quality) {
  const warnings = {};
  Object.keys(newAnalyticsQuality_()).forEach(name => {
    warnings[name] = Number(quality[name]) || 0;
  });
  return {
    complete: Object.keys(warnings).every(name => warnings[name] === 0),
    warnings: warnings
  };
}

