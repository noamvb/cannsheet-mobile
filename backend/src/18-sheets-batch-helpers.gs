function findConfigRowNumber_(ss, key) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
  const row = findOptionalUniqueExactCellRow_(sheet, 1, key);
  if (!row) throw new Error('SCHEMA_MISMATCH: missing Config key ' + key);
  return row;
}

function findUniqueExactCellRow_(sheet, columnNumber, value) {
  const row = findOptionalUniqueExactCellRow_(sheet, columnNumber, value);
  if (!row) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: missing ' + value +
      ' in ' + sheet.getName()
    );
  }
  return row;
}

function resolveUniqueRowsByIds_(sheet, columnNumber, ids) {
  const submitted = Array.from(new Set((ids || []).map(text_).filter(Boolean)));
  const resolved = {};
  if (!submitted.length) return resolved;
  if (submitted.length <= CANN.EVENT_TEXT_FINDER_MAX_BATCH) {
    submitted.forEach(id => {
      resolved[id] = findUniqueExactCellRow_(sheet, columnNumber, id);
    });
    return resolved;
  }

  const wanted = {};
  submitted.forEach(id => { wanted[id] = true; });
  const lastRow = sheet.getLastRow();
  if (lastRow >= 2) {
    sheet.getRange(2, columnNumber, lastRow - 1, 1)
      .getValues()
      .forEach((row, index) => {
        const id = text_(row[0]);
        if (!wanted[id]) return;
        if (resolved[id]) {
          throw new Error(
            'RECOVERABLE_APPLY_CORRUPT: duplicate ' + id +
            ' in ' + sheet.getName()
          );
        }
        resolved[id] = index + 2;
      });
  }
  submitted.forEach(id => {
    if (!resolved[id]) {
      throw new Error(
        'RECOVERABLE_APPLY_CORRUPT: missing ' + id +
        ' in ' + sheet.getName()
      );
    }
  });
  return resolved;
}

function findOptionalUniqueExactCellRow_(sheet, columnNumber, value) {
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return null;
  const matches = sheet.getRange(2, columnNumber, lastRow - 1, 1)
    .createTextFinder(String(value))
    .matchEntireCell(true)
    .matchCase(true)
    .useRegularExpression(false)
    .findAll();
  if (matches.length > 1) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: duplicate ' + value +
      ' in ' + sheet.getName()
    );
  }
  return matches.length ? matches[0].getRow() : null;
}

function appendCellsRequest_(sheet, rows) {
  return {
    appendCells: {
      sheetId: sheet.getSheetId(),
      rows: rows.map(row => ({
        values: row.map(sheetCellData_)
      })),
      fields: 'userEnteredValue'
    }
  };
}

function updateCellsRequest_(sheet, rowNumber, columnNumber, rows) {
  return {
    updateCells: {
      start: {
        sheetId: sheet.getSheetId(),
        rowIndex: rowNumber - 1,
        columnIndex: columnNumber - 1
      },
      rows: rows.map(row => ({
        values: row.map(sheetCellData_)
      })),
      fields: 'userEnteredValue'
    }
  };
}

function sheetCellData_(value) {
  if (value == null || value === '') return {};
  if (Object.prototype.toString.call(value) === '[object Date]') {
    if (!Number.isFinite(value.getTime())) return {};
    return {
      userEnteredValue: {
        numberValue: spreadsheetLocalDateSerial_(value)
      }
    };
  }
  if (typeof value === 'boolean') {
    return { userEnteredValue: { boolValue: value } };
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return { userEnteredValue: { numberValue: value } };
  }
  return { userEnteredValue: { stringValue: String(value) } };
}

function spreadsheetLocalDateSerial_(value) {
  // Sheets serials represent spreadsheet-local wall-clock components. A raw
  // UTC epoch/86400000 value shifts visible timestamps by the UTC offset.
  const parts = Utilities.formatDate(
    new Date(value),
    CANN.TIME_ZONE,
    'yyyy,MM,dd,HH,mm,ss,SSS'
  ).split(',').map(Number);
  return Date.UTC(
    parts[0],
    parts[1] - 1,
    parts[2],
    parts[3],
    parts[4],
    parts[5],
    parts[6]
  ) / 86400000 + 25569;
}

function padBatchRowWidth_(values) {
  // The Advanced Sheets Values API omits trailing empty cells from each row,
  // while the sequential Range.getValues() path always returns a full
  // rectangular matrix padded with ''. Left unpadded, a short batch row reads
  // as undefined past its length where the sequential row reads as '', and
  // analyticsHashValue_ types those two differently (null vs string) --
  // the same dataVersion-parity problem Fix 2 exists to close, just for
  // ordinary blank trailing cells instead of dates.
  if (!values || values.length < 2) return values;
  const headerLength = (values[0] || []).length;
  for (let rowIndex = 1; rowIndex < values.length; rowIndex++) {
    const row = values[rowIndex];
    if (!row) continue;
    while (row.length < headerLength) row.push('');
  }
  return values;
}

function dateFromSpreadsheetSerial_(serial) {
  const number = Number(serial);
  if (!Number.isFinite(number)) return null;
  const wallClockMillis = Math.round((number - 25569) * 86400000);
  const wallClockText = Utilities.formatDate(
    new Date(wallClockMillis),
    'UTC',
    'yyyy-MM-dd HH:mm:ss'
  );
  return Utilities.parseDate(
    wallClockText,
    CANN.TIME_ZONE,
    'yyyy-MM-dd HH:mm:ss'
  );
}

function normalizeBatchDateColumns_(sheetName, values) {
  const dateColumns = ANALYTICS_DATE_COLUMNS_[sheetName];
  if (!dateColumns || !values || values.length < 2) return values;
  const headers = headerMapFromRow_(values[0]);
  const indexes = [];
  dateColumns.forEach(name => {
    const index = headers[name];
    if (index !== undefined) indexes.push(index);
  });
  if (!indexes.length) return values;
  for (let rowIndex = 1; rowIndex < values.length; rowIndex++) {
    const row = values[rowIndex];
    if (!row) continue;
    indexes.forEach(index => {
      const cell = row[index];
      if (typeof cell === 'number' && Number.isFinite(cell) && cell > 0) {
        const converted = dateFromSpreadsheetSerial_(cell);
        if (converted) row[index] = converted;
      }
    });
  }
  return values;
}

function sheetsBatchUpdate_(ss, requests) {
  if (!requests.length) return { replies: [] };
  assertAdvancedSheetsService_();
  return Sheets.Spreadsheets.batchUpdate(
    { requests: requests },
    ss.getId()
  );
}

function sheetsBatchUpdateInChunks_(ss, requests, requestedChunkSize) {
  const chunkSize = Math.max(
    1,
    Math.floor(finiteNumberOr_(requestedChunkSize, 400))
  );
  for (let start = 0; start < requests.length; start += chunkSize) {
    sheetsBatchUpdate_(ss, requests.slice(start, start + chunkSize));
  }
}

/**
 * The Advanced Sheets write becomes durable before SpreadsheetApp refreshes
 * its in-process read cache. Journal and canonical rows have single-writer,
 * lock-protected positions. Google Forms can append compatibility rows outside
 * that lock, so reread only the newly appended identity-column tail through the
 * Advanced service, which observes the just-committed batch.
 */
function materializedRecoverableRowsAfterCore_(ss, details) {
  const eventIds = (details.eventIds || []).map(text_);
  const eventCount = Number(details.eventRowCount || 0);
  const eventFirstRow = Number(details.eventFirstRow);
  const journalRowNumber = Number(details.journalRowNumber);
  if (eventIds.length !== eventCount ||
      !Number.isInteger(journalRowNumber) ||
      journalRowNumber < 2 ||
      (eventCount &&
       (!Number.isInteger(eventFirstRow) || eventFirstRow < 2))) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: core response row count mismatch'
    );
  }
  const canonicalRows = Array.from(
    { length: eventCount },
    (_, index) => eventFirstRow + index
  );
  const compatibilityRowsByEventId =
    details.compatibilityExistingRow
      ? rowsByStableIds_(
        eventIds,
        [Number(details.compatibilityExistingRow)]
      )
      : resolveFreshCompatibilityRowsByIds_(
        ss,
        requiredSheet_(ss, CANN.SHEETS.RESPONSES),
        details.compatibilityEventColumn,
        Number(details.responseLastRowBefore) + 1,
        eventIds
      );
  return {
    journalRecord: {
      rowNumber: journalRowNumber,
      state: 'CORE_COMMITTED',
      coreCommittedAt: details.now,
      finalizationJson: JSON.stringify(details.plan),
      responseJson: details.response == null
        ? ''
        : JSON.stringify(details.response)
    },
    canonicalRowsByEventId: rowsByStableIds_(
      eventIds,
      canonicalRows
    ),
    compatibilityRowsByEventId: compatibilityRowsByEventId
  };
}

function resolveFreshCompatibilityRowsByIds_(
  ss,
  sheet,
  columnNumber,
  firstPossibleRow,
  ids
) {
  const submitted = Array.from(
    new Set((ids || []).map(text_).filter(Boolean))
  );
  if (!submitted.length) return {};
  const column = sheetColumnLetters_(columnNumber);
  const firstRow = Math.max(2, Math.floor(firstPossibleRow));
  const sheetName = "'" +
    sheet.getName().replace(/'/g, "''") +
    "'";
  const response = Sheets.Spreadsheets.Values.get(
    ss.getId(),
    sheetName + '!' + column + firstRow + ':' + column
  );
  const wanted = {};
  submitted.forEach(id => { wanted[id] = true; });
  const resolved = {};
  (response.values || []).forEach((row, index) => {
    const id = text_(row && row[0]);
    if (!wanted[id]) return;
    if (resolved[id]) {
      throw new Error(
        'RECOVERABLE_APPLY_CORRUPT: duplicate ' + id +
        ' in ' + sheet.getName()
      );
    }
    resolved[id] = firstRow + index;
  });
  submitted.forEach(id => {
    if (!resolved[id]) {
      throw new Error(
        'RECOVERABLE_APPLY_CORRUPT: missing fresh compatibility ' +
        id
      );
    }
  });
  return resolved;
}

function rowsByStableIds_(ids, rowNumbers) {
  if (ids.length !== rowNumbers.length) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: stable ID row count mismatch'
    );
  }
  const rows = {};
  ids.forEach((id, index) => {
    if (!id || rows[id]) {
      throw new Error(
        'RECOVERABLE_APPLY_CORRUPT: invalid materialized stable ID ' +
        id
      );
    }
    rows[id] = rowNumbers[index];
  });
  return rows;
}

function sheetColumnLetters_(columnNumber) {
  let value = Number(columnNumber);
  if (!Number.isInteger(value) || value < 1) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: invalid sheet column ' +
      columnNumber
    );
  }
  let result = '';
  while (value > 0) {
    value--;
    result = String.fromCharCode(65 + value % 26) + result;
    value = Math.floor(value / 26);
  }
  return result;
}

function assertAdvancedSheetsService_() {
  if (typeof Sheets === 'undefined' ||
      !Sheets.Spreadsheets ||
      !Sheets.Spreadsheets.batchUpdate ||
      !Sheets.Spreadsheets.Values ||
      !Sheets.Spreadsheets.Values.get) {
    throw new Error(
      'CONFIGURATION_ERROR: Advanced Google Sheets service v4 is required'
    );
  }
}

function setSandboxSyncApplyFault(stage) {
  if (environment_() !== 'SANDBOX') {
    throw new Error('SANDBOX_FAULT_GUARD: ENVIRONMENT must be SANDBOX');
  }
  const allowed = Object.keys(CANN.SYNC_APPLY_FAULTS)
    .map(key => CANN.SYNC_APPLY_FAULTS[key]);
  const normalized = text_(stage).toUpperCase();
  if (allowed.indexOf(normalized) < 0) {
    throw new Error(
      'SANDBOX_FAULT_GUARD: unsupported stage ' + normalized
    );
  }
  PropertiesService.getScriptProperties().setProperty(
    CANN.SANDBOX_FAULT_PROPERTY,
    normalized
  );
  return { armed: normalized };
}

function clearSandboxSyncApplyFault() {
  if (environment_() !== 'SANDBOX') {
    throw new Error('SANDBOX_FAULT_GUARD: ENVIRONMENT must be SANDBOX');
  }
  PropertiesService.getScriptProperties().deleteProperty(
    CANN.SANDBOX_FAULT_PROPERTY
  );
  return { armed: null };
}

function armedSandboxSyncApplyFault_(stage) {
  if (environment_() !== 'SANDBOX') return false;
  const properties = PropertiesService.getScriptProperties();
  const armed = text_(
    properties.getProperty(CANN.SANDBOX_FAULT_PROPERTY)
  ).toUpperCase();
  if (armed !== stage) return false;
  properties.deleteProperty(CANN.SANDBOX_FAULT_PROPERTY);
  return true;
}

function maybeInjectSandboxSyncApplyBatchFault_(stage, requests) {
  if (!armedSandboxSyncApplyFault_(stage)) return;
  // A non-existent sheet makes the Advanced Sheets request invalid. Because it
  // is part of the same batch, every otherwise-valid request rolls back.
  requests.push({
    updateCells: {
      start: {
        sheetId: -2147483648,
        rowIndex: 0,
        columnIndex: 0
      },
      rows: [{ values: [{ userEnteredValue: { stringValue: 'FAULT' } }] }],
      fields: 'userEnteredValue'
    }
  });
}

function maybeInjectSandboxSyncApplyFault_(stage) {
  if (!armedSandboxSyncApplyFault_(stage)) return;
  throw new Error('SANDBOX_INJECTED_SYNC_APPLY_STOP: ' + stage);
}

