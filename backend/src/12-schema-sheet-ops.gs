// -----------------------------------------------------------------------------
// Schema and sheet operations
// -----------------------------------------------------------------------------

function ensureCoreSchema_(ss) {
  const purchases = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  ensureHeaders_(purchases, CANN.PURCHASE_HEADERS);
  ensureSheet_(ss, CANN.SHEETS.EVENTS, CANN.EVENT_HEADERS);
  ensureSheet_(ss, CANN.SHEETS.LEDGER, CANN.LEDGER_HEADERS);
  ensureSheet_(ss, CANN.SHEETS.APPLY_JOURNAL, CANN.APPLY_JOURNAL_HEADERS);
  ensureSheet_(ss, CANN.SHEETS.MIGRATION_REPORT, CANN.REPORT_HEADERS);
  const config = ensureSheet_(ss, CANN.SHEETS.CONFIG, ['Key', 'Value', 'Description']);
  if (config.getLastRow() < 2) {
    config.getRange(2, 1, 11, 3).setValues([
      ['ENVIRONMENT', environment_(), 'Runtime environment marker'],
      ['TAX_RATE', 0.13, 'Tax rate used for final-cost values'],
      ['TIME_ZONE', CANN.TIME_ZONE, 'Canonical local timezone'],
      ['SCHEMA_VERSION', CANN.SCHEMA_VERSION, 'Spreadsheet schema version'],
      ['INTERACTION_SUMMARY_VERSION', 0, 'Purchases interaction-summary version'],
      [CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY, 0, 'Recoverable multi-sheet apply version'],
      [CANN.PENDING_APPLY_KEY, '', 'Apply UUID awaiting finalization'],
      [
        CANN.CONSUMPTION_CORRECTION_VERSION_KEY,
        0,
        'Append-only consumption correction schema version'
      ],
      [
        CANN.CONSUMPTION_CORRECTION_WRITES_KEY,
        false,
        'Whether new consumption correction writes are accepted'
      ],
      ['MAX_BATCH_SIZE', CANN.MAX_BATCH_SIZE, 'Maximum v2 items per request'],
      ['LOCK_TIMEOUT_MS', CANN.LOCK_TIMEOUT_MS, 'Shared mutation lock timeout']
    ]);
  } else {
    ensureConfigKey_(ss, CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY, 0, 'Recoverable multi-sheet apply version');
    ensureConfigKey_(ss, CANN.PENDING_APPLY_KEY, '', 'Apply UUID awaiting finalization');
    ensureConfigKey_(
      ss,
      CANN.CONSUMPTION_CORRECTION_VERSION_KEY,
      0,
      'Append-only consumption correction schema version'
    );
    ensureConfigKey_(
      ss,
      CANN.CONSUMPTION_CORRECTION_WRITES_KEY,
      false,
      'Whether new consumption correction writes are accepted'
    );
  }
}

function historyEventResponse_(event, query) {
  const response = {
    eventUuid: event.eventUuid,
    occurredAtEpochMillis: event.occurredAtEpochMillis,
    localDate: event.localDate,
    localTime: event.localTime,
    productUuid: event.product.productUuid,
    productId: event.product.productId,
    productName: event.product.name,
    productType: event.product.type,
    quantity: event.quantity,
    weightCode: event.weightCode,
    finished: event.finished,
    source: event.source
  };
  if (query.analyticsVersion >= 2) {
    response.lifecycleState = event.lifecycleState || 'ORIGINAL';
    response.correctionHeadId = event.correctionHeadId || null;
    response.revision = Number(event.revision) || 0;
    response.reopenEligible = event.reopenEligible === true;
    if (query.includeAudit) {
      response.auditRevisions = event.auditRevisions || [];
    }
  }
  return response;
}

function ensureMigrationResolutionSchema_(ss) {
  const expected = CANN.REPORT_HEADERS.concat(
    CANN.MIGRATION_RESOLUTION_HEADERS
  );
  const sheet = requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT);
  ensureHeaders_(sheet, expected);
  return sheet;
}

function assertSpreadsheetTimeZone_(ss, config) {
  const spreadsheetTimeZone = text_(ss.getSpreadsheetTimeZone());
  const configTimeZone = text_(
    config && config.TIME_ZONE !== undefined
      ? config.TIME_ZONE
      : configValue_(ss, 'TIME_ZONE', '')
  );
  if (spreadsheetTimeZone !== CANN.TIME_ZONE ||
      configTimeZone !== CANN.TIME_ZONE) {
    throw new Error(
      'CONFIGURATION_ERROR: spreadsheet and Config TIME_ZONE must both be ' +
      CANN.TIME_ZONE + ' (spreadsheet=' + spreadsheetTimeZone +
      ', config=' + configTimeZone + ')'
    );
  }
}

function provisionRecoverableDateFormats_(ss) {
  const specifications = [
    [CANN.SHEETS.PURCHASES, ['Most recent use', 'Created At', 'Finished At']],
    [CANN.SHEETS.RESPONSES, ['Timestamp']],
    [CANN.SHEETS.EVENTS, ['Timestamp']],
    [CANN.SHEETS.LEDGER, ['Received At']],
    [CANN.SHEETS.APPLY_JOURNAL, ['Core Committed At', 'Completed At']]
  ];
  const corrections = ss.getSheetByName(CANN.SHEETS.CORRECTIONS);
  if (corrections) {
    specifications.push([
      CANN.SHEETS.CORRECTIONS,
      ['Replacement Timestamp', 'Created At']
    ]);
  }
  specifications.forEach(specification => {
    const sheet = requiredSheet_(ss, specification[0]);
    const headers = headerMap_(sheet);
    specification[1].forEach(header => {
      if (headers[header] === undefined) {
        throw new Error(
          'SCHEMA_MISMATCH: missing date header ' + header +
          ' in ' + sheet.getName()
        );
      }
      sheet.getRange(
        2,
        headers[header] + 1,
        Math.max(1, sheet.getMaxRows() - 1),
        1
      ).setNumberFormat('yyyy-mm-dd hh:mm:ss');
    });
  });
}

/**
 * Read-only schema assertion for HTTP execution. Provisioning and migrations
 * remain responsible for creating sheets, adding columns, and formatting.
 */
function readAndAssertRuntimeConfig_(ss, expectedEnvironment) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
  const headers = headerMap_(sheet);
  requireExactHeaders_(headers, ['Key', 'Value', 'Description'], CANN.SHEETS.CONFIG);
  const values = configValuesFromSheet_(sheet, headers);
  if (text_(values.ENVIRONMENT) !== expectedEnvironment) {
    throw new Error('CONFIGURATION_ERROR: Config ENVIRONMENT mismatch');
  }
  return { sheet: sheet, headers: headers, values: values };
}

function assertRuntimeSchema_(ss, expectedEnvironment, validatedConfig) {
  const runtimeConfig = validatedConfig || readAndAssertRuntimeConfig_(ss, expectedEnvironment);
  const sheets = {
    purchases: requiredSheet_(ss, CANN.SHEETS.PURCHASES),
    responses: requiredSheet_(ss, CANN.SHEETS.RESPONSES),
    events: requiredSheet_(ss, CANN.SHEETS.EVENTS),
    ledger: requiredSheet_(ss, CANN.SHEETS.LEDGER),
    config: runtimeConfig.sheet,
    migrationReport: requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT)
  };
  const headers = {
    purchases: headerMap_(sheets.purchases),
    responses: headerMap_(sheets.responses),
    events: headerMap_(sheets.events),
    ledger: headerMap_(sheets.ledger),
    config: runtimeConfig.headers,
    migrationReport: headerMap_(sheets.migrationReport)
  };

  const config = runtimeConfig.values;
  assertSupportedSchemaVersion_(config);
  const summaryReady = interactionSummaryReady_(config);
  const recoverableApplyReady = recoverableSyncApplyReady_(config);
  const correctionCapability = consumptionCorrectionCapability_(config);
  if (correctionCapability.version && !summaryReady) {
    throw new Error(
      'SCHEMA_MISMATCH: consumption corrections require the ' +
      'interaction-summary projection'
    );
  }
  if (recoverableApplyReady) assertSpreadsheetTimeZone_(ss, config);
  requireExactHeaders_(
    headers.purchases,
    summaryReady ? CANN.PURCHASE_HEADERS : CANN.PURCHASE_HEADERS.slice(0, -1),
    CANN.SHEETS.PURCHASES
  );
  requireHeaders_(headers.responses, ['Timestamp', 'Product', 'Uses']);
  if (recoverableApplyReady) {
    requireCompatibilityIdentityHeaders_(headers.responses);
    sheets.applyJournal = requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
    headers.applyJournal = headerMap_(sheets.applyJournal);
    requireExactHeaders_(headers.applyJournal, CANN.APPLY_JOURNAL_HEADERS, CANN.SHEETS.APPLY_JOURNAL);
  }
  requireExactHeaders_(headers.events, CANN.EVENT_HEADERS, CANN.SHEETS.EVENTS);
  if (correctionCapability.version === CANN.CONSUMPTION_CORRECTION_VERSION) {
    sheets.corrections = requiredSheet_(ss, CANN.SHEETS.CORRECTIONS);
    headers.corrections = headerMap_(sheets.corrections);
    requireExactHeaders_(
      headers.corrections,
      CANN.CORRECTION_HEADERS,
      CANN.SHEETS.CORRECTIONS
    );
  }
  requireExactHeaders_(headers.ledger, CANN.LEDGER_HEADERS, CANN.SHEETS.LEDGER);
  requireExactHeaders_(headers.config, ['Key', 'Value', 'Description'], CANN.SHEETS.CONFIG);
  requireExactHeaders_(headers.migrationReport, CANN.REPORT_HEADERS, CANN.SHEETS.MIGRATION_REPORT);

  const configuredEnvironment = text_(config.ENVIRONMENT);
  if (expectedEnvironment && configuredEnvironment !== expectedEnvironment) {
    throw new Error('CONFIGURATION_ERROR: Config ENVIRONMENT mismatch');
  }
  config.INTERACTION_SUMMARY_READY = summaryReady;
  config.RECOVERABLE_SYNC_APPLY_READY = recoverableApplyReady;
  config.CONSUMPTION_CORRECTION_VERSION = correctionCapability.version;
  config.CONSUMPTION_CORRECTION_WRITES_ENABLED =
    correctionCapability.writesEnabled;
  config.TAX_RATE = finiteNumberOr_(config.TAX_RATE, 0.13);
  return { sheets: sheets, headers: headers, config: config };
}

function assertSupportedSchemaVersion_(config) {
  if (Number((config || {}).SCHEMA_VERSION) !== CANN.SCHEMA_VERSION) {
    throw new Error('SCHEMA_MISMATCH: Config SCHEMA_VERSION must be ' + CANN.SCHEMA_VERSION);
  }
}

function interactionSummaryReady_(config) {
  const raw = (config || {}).INTERACTION_SUMMARY_VERSION;
  if (raw == null || raw === '' || Number(raw) === 0) return false;
  if (Number(raw) !== CANN.INTERACTION_SUMMARY_VERSION) {
    throw new Error(
      'SCHEMA_MISMATCH: unsupported Config INTERACTION_SUMMARY_VERSION ' + raw
    );
  }
  return true;
}

function recoverableSyncApplyReady_(config) {
  const raw = (config || {})[CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY];
  if (raw == null || raw === '' || Number(raw) === 0) return false;
  if (Number(raw) !== CANN.RECOVERABLE_SYNC_APPLY_VERSION) {
    throw new Error(
      'SCHEMA_MISMATCH: unsupported Config ' +
      CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY + ' ' + raw
    );
  }
  return true;
}

function consumptionCorrectionCapability_(config) {
  const values = config || {};
  const rawVersion = values[CANN.CONSUMPTION_CORRECTION_VERSION_KEY];
  const version = rawVersion == null || rawVersion === ''
    ? 0
    : Number(rawVersion);
  if (
    version !== 0 &&
    version !== CANN.CONSUMPTION_CORRECTION_VERSION
  ) {
    throw new Error(
      'SCHEMA_MISMATCH: unsupported Config ' +
      CANN.CONSUMPTION_CORRECTION_VERSION_KEY + ' ' + rawVersion
    );
  }
  const rawWrites = values[CANN.CONSUMPTION_CORRECTION_WRITES_KEY];
  const writesText = text_(rawWrites).toLowerCase();
  if (
    rawWrites != null &&
    rawWrites !== '' &&
    ['true', 'false', '1', '0'].indexOf(writesText) < 0
  ) {
    throw new Error(
      'SCHEMA_MISMATCH: Config ' +
      CANN.CONSUMPTION_CORRECTION_WRITES_KEY +
      ' must be true or false'
    );
  }
  const writesEnabled = truthy_(rawWrites);
  if (writesEnabled && version !== CANN.CONSUMPTION_CORRECTION_VERSION) {
    throw new Error(
      'SCHEMA_MISMATCH: correction writes require schema version ' +
      CANN.CONSUMPTION_CORRECTION_VERSION
    );
  }
  return {
    version: version,
    writesEnabled: writesEnabled
  };
}

function requireCompatibilityIdentityHeaders_(headers) {
  requireHeaders_(headers, [
    CANN.COMPATIBILITY_EVENT_HEADER,
    CANN.COMPATIBILITY_REQUEST_HEADER
  ]);
  if (headers[CANN.COMPATIBILITY_REQUEST_HEADER] !==
      headers[CANN.COMPATIBILITY_EVENT_HEADER] + 1) {
    throw new Error('SCHEMA_MISMATCH: compatibility identity columns must be adjacent');
  }
}

function requireExactHeaders_(headers, expected, sheetName) {
  expected.forEach((name, index) => {
    if (headers[name] !== index) {
      throw new Error('SCHEMA_MISMATCH: ' + sheetName + ' column ' + (index + 1) + ' must be ' + name);
    }
  });
}

function configValuesFromSheet_(sheet, headers) {
  const result = {};
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return result;
  sheet.getRange(2, 1, lastRow - 1, sheet.getLastColumn()).getValues().forEach(row => {
    const key = text_(value_(row, headers, 'Key'));
    if (key) result[key] = value_(row, headers, 'Value');
  });
  return result;
}

function ensureSheet_(ss, name, headers) {
  const sheet = ss.getSheetByName(name) || ss.insertSheet(name);
  ensureHeaders_(sheet, headers);
  sheet.setFrozenRows(1);
  return sheet;
}

function ensureHeaders_(sheet, expected) {
  if (sheet.getMaxColumns() < expected.length) {
    sheet.insertColumnsAfter(sheet.getMaxColumns(), expected.length - sheet.getMaxColumns());
  }
  const current = sheet.getRange(1, 1, 1, expected.length).getValues()[0];
  expected.forEach((header, index) => {
    if (!text_(current[index])) sheet.getRange(1, index + 1).setValue(header);
    else if (text_(current[index]) !== header) {
      throw new Error('SCHEMA_MISMATCH: ' + sheet.getName() + ' column ' + (index + 1) + ' expected ' + header + ' but found ' + current[index]);
    }
  });
}

function applySheetHeaderCosmetics_(sheet) {
  try {
    sheet.getRange(1, 1, 1, sheet.getLastColumn())
      .setBackground('#eeeeee')
      .setFontColor('#000000')
      .setFontWeight('bold');
    if (sheet.getFrozenRows() < 1) sheet.setFrozenRows(1);
  } catch (error) {
    const message = String(error && error.message || error)
      .replace(/^\s*(?:Exception:\s*)?/, '')
      .trim();
    if (message !== 'This operation is not allowed on cells in typed columns.') {
      throw error;
    }
    console.warn(JSON.stringify({
      type: 'sheet_header_cosmetics_skipped',
      sheet: sheet.getName(),
      reason: 'TYPED_COLUMN_RESTRICTION'
    }));
  }
}

function applySheetSafety_(ss) {
  const purchases = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const headers = headerMap_(purchases);
  const lastValidationRow = Math.max(purchases.getMaxRows(), 1000);
  const typeValues = readDataRows_(purchases).map(row => text_(value_(row, headers, 'Type'))).filter(Boolean);
  const uniqueTypes = Array.from(new Set(typeValues)).sort();
  if (uniqueTypes.length) {
    const typeRule = SpreadsheetApp.newDataValidation().requireValueInList(uniqueTypes, true).setAllowInvalid(false).build();
    purchases.getRange(2, headers['Type'] + 1, lastValidationRow - 1, 1).setDataValidation(typeRule);
  }
  const statusRule = SpreadsheetApp.newDataValidation().requireValueInList(['0', '1', '2'], true).setAllowInvalid(false).build();
  purchases.getRange(2, headers['Finished'] + 1, lastValidationRow - 1, 1).setDataValidation(statusRule);
  const binaryRule = SpreadsheetApp.newDataValidation().requireValueInList(['0', '1'], true).setAllowInvalid(false).build();
  purchases.getRange(2, headers['Borrowed'] + 1, lastValidationRow - 1, 1).setDataValidation(binaryRule);
  // The existing Purchases table already owns the Post-tax typed checkbox
  // column. Reapplying checkbox validation is rejected for typed columns.

  const protectedSheets = [
    purchases,
    requiredSheet_(ss, CANN.SHEETS.EVENTS),
    requiredSheet_(ss, CANN.SHEETS.LEDGER),
    requiredSheet_(ss, CANN.SHEETS.CONFIG),
    requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT)
  ];
  const corrections = ss.getSheetByName(CANN.SHEETS.CORRECTIONS);
  if (corrections) protectedSheets.push(corrections);
  protectedSheets.forEach(applySheetHeaderCosmetics_);
  addWarningProtection_(purchases.getRange(1, 1, 1, purchases.getLastColumn()), 'Cannsheet reliability headers');
  addWarningProtection_(purchases.getRange(2, headers['Product ID'] + 1, Math.max(1, purchases.getMaxRows() - 1), 1), 'Cannsheet display identities');
  addWarningProtection_(purchases.getRange(2, headers['Product UUID'] + 1, Math.max(1, purchases.getMaxRows() - 1), 3), 'Cannsheet immutable identities');
  if (corrections) {
    addWarningProtection_(
      corrections.getRange(1, 1, 1, corrections.getLastColumn()),
      'Cannsheet correction headers'
    );
    addWarningProtection_(
      corrections.getRange(
        2,
        1,
        Math.max(1, corrections.getMaxRows() - 1),
        corrections.getLastColumn()
      ),
      'Cannsheet append-only correction records'
    );
  }
}

function addWarningProtection_(range, description) {
  const existing = range.getSheet().getProtections(SpreadsheetApp.ProtectionType.RANGE)
    .find(protection => protection.getDescription() === description);
  const protection = existing || range.protect().setDescription(description);
  protection.setWarningOnly(true);
}

