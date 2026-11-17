// -----------------------------------------------------------------------------
// General helpers
// -----------------------------------------------------------------------------

function newBackendTiming_(handler, startedAt) {
  const started = startedAt == null ? Date.now() : Number(startedAt);
  return { handler: handler, startedAt: started, phasesMs: {} };
}

function recordBackendPhase_(timing, phase, startedAt, endedAt) {
  if (!timing) return;
  const ended = endedAt == null ? Date.now() : Number(endedAt);
  const duration = Math.max(0, ended - Number(startedAt));
  timing.phasesMs[phase] = finiteNumberOr_(timing.phasesMs[phase], 0) + duration;
}

function backendTimingRecord_(timing, outcome, details, endedAt) {
  const ended = endedAt == null ? Date.now() : Number(endedAt);
  const total = Math.max(0, ended - timing.startedAt);
  const record = {
    recordType: 'cannsheet_backend_timing',
    handler: timing.handler,
    outcome: outcome,
    phasesMs: Object.assign({}, timing.phasesMs),
    serverDurationMs: timing.serverDurationMs == null ? total : timing.serverDurationMs,
    totalHandlerMs: total
  };
  Object.keys(details || {}).forEach(key => {
    if (details[key] !== undefined) record[key] = details[key];
  });
  return record;
}

function logBackendTiming_(timing, outcome, details) {
  if (!timing) return;
  try {
    console.log(JSON.stringify(backendTimingRecord_(timing, outcome, details)));
  } catch (ignored) {
    // Timing must never change request behavior if logging is unavailable.
  }
}

function addServerTimingFields_(response, timing, environment) {
  if (!response || !timing) return response;
  timing.serverDurationMs = Math.max(0, Date.now() - timing.startedAt);
  response.serverDurationMs = timing.serverDurationMs;
  if (environment === 'SANDBOX') {
    response.serverTimings = Object.assign({}, timing.phasesMs);
  }
  return response;
}

function timedRequestFailure_(timing, code, message, environment, details) {
  let output;
  if (details && details.apiVersion === CANN.API_VERSION) {
    const response = v2RequestFailure_(details.requestId, code, message);
    response.environment = environment;
    addServerTimingFields_(response, timing, environment);
    const responseRoutingStarted = Date.now();
    output = jsonOutput_(response);
    recordBackendPhase_(timing, 'responseRouting', responseRoutingStarted);
  } else {
    output = requestFailure_(code, message, environment, timing);
  }
  const timingDetails = Object.assign({ errorCode: code }, details || {});
  if (environment) timingDetails.environment = environment;
  logBackendTiming_(timing, 'rejected', timingDetails);
  return output;
}

function requiredSheet_(ss, name) {
  const sheet = ss.getSheetByName(name);
  if (!sheet) throw new Error('SCHEMA_MISMATCH: Missing sheet ' + name);
  return sheet;
}

function headerMap_(sheet) {
  const lastColumn = Math.max(1, sheet.getLastColumn());
  const headers = sheet.getRange(1, 1, 1, lastColumn).getValues()[0];
  const result = {};
  headers.forEach((header, index) => {
    const name = text_(header);
    if (name && result[name] === undefined) result[name] = index;
  });
  return result;
}

function requireHeaders_(headers, required) {
  const missing = required.filter(name => headers[name] === undefined);
  if (missing.length) throw new Error('SCHEMA_MISMATCH: Missing headers ' + missing.join(', '));
}

function readDataRows_(sheet) {
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return [];
  return sheet.getRange(2, 1, lastRow - 1, sheet.getLastColumn()).getValues()
    .filter(row => row.some(cell => cell !== '' && cell != null));
}

function value_(row, headers, name) {
  const index = headers[name];
  return index === undefined ? '' : row[index];
}

function recordMigrationIssue_(ss, type, sourceSheet, sourceRow, productId, detail) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT);
  sheet.getRange(sheet.getLastRow() + 1, 1, 1, CANN.REPORT_HEADERS.length)
    .setValues([[type, sourceSheet, sourceRow, productId, detail, new Date()]]);
}

function configValue_(ss, key, fallback) {
  const sheet = ss.getSheetByName(CANN.SHEETS.CONFIG);
  if (!sheet || sheet.getLastRow() < 2) return fallback;
  const rows = sheet.getRange(2, 1, sheet.getLastRow() - 1, 2).getValues();
  const match = rows.find(row => text_(row[0]) === key);
  return match && match[1] !== '' ? match[1] : fallback;
}

function setConfigValue_(ss, key, value, description) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
  const headers = headerMap_(sheet);
  requireExactHeaders_(
    headers,
    ['Key', 'Value', 'Description'],
    CANN.SHEETS.CONFIG
  );
  const lastRow = sheet.getLastRow();
  const rows = lastRow < 2
    ? []
    : sheet.getRange(2, 1, lastRow - 1, 3).getValues();
  const matches = [];
  rows.forEach((row, index) => {
    if (text_(row[0]) === key) matches.push(index + 2);
  });
  if (matches.length > 1) {
    throw new Error('SCHEMA_MISMATCH: duplicate Config key ' + key);
  }
  const rowNumber = matches.length ? matches[0] : lastRow + 1;
  sheet.getRange(rowNumber, 1, 1, 3).setValues([[
    key,
    value,
    description || ''
  ]]);
}

function ensureConfigKey_(ss, key, defaultValue, description) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
  const lastRow = sheet.getLastRow();
  const rows = lastRow < 2 ? [] : sheet.getRange(2, 1, lastRow - 1, 1).getValues();
  const matches = [];
  rows.forEach((row, index) => {
    if (text_(row[0]) === key) matches.push(index + 2);
  });
  if (matches.length > 1) throw new Error('SCHEMA_MISMATCH: duplicate Config key ' + key);
  if (!matches.length) setConfigValue_(ss, key, defaultValue, description);
  return matches.length ? matches[0] : sheet.getLastRow();
}

function requiredScriptProperty_(name) {
  const value = text_(PropertiesService.getScriptProperties().getProperty(name));
  if (!value) throw new Error('CONFIGURATION_ERROR: missing Script Property ' + name);
  return value;
}

function environment_() {
  const value = requiredScriptProperty_('ENVIRONMENT');
  if (value !== 'PRODUCTION' && value !== 'SANDBOX') {
    throw new Error('CONFIGURATION_ERROR: ENVIRONMENT must be PRODUCTION or SANDBOX');
  }
  return value;
}

function spreadsheet_() {
  return SpreadsheetApp.openById(requiredScriptProperty_('SPREADSHEET_ID'));
}

function assertConfiguredSpreadsheet_(ss) {
  if (!ss || ss.getId() !== requiredScriptProperty_('SPREADSHEET_ID')) {
    throw new Error('CONFIGURATION_ERROR: spreadsheet does not match SPREADSHEET_ID');
  }
}

function assertConfigEnvironment_(ss) {
  assertConfiguredSpreadsheet_(ss);
  const marker = text_(configValue_(ss, 'ENVIRONMENT', ''));
  if (marker !== environment_()) throw new Error('CONFIGURATION_ERROR: Config ENVIRONMENT mismatch');
}

function validateRequestEnvironment_(serverEnvironment, requestEnvironment) {
  const supplied = text_(requestEnvironment);
  if (supplied && supplied !== serverEnvironment) return 'Client and server environments do not match';
  if (!supplied && serverEnvironment === 'SANDBOX') return 'Sandbox requests must include environment SANDBOX';
  return '';
}

function taxRate_(ss) { return finiteNumberOr_(configValue_(ss, 'TAX_RATE', 0.13), 0.13); }
function formatDate_(date) { return Utilities.formatDate(new Date(date), CANN.TIME_ZONE, 'yyyy-MM-dd'); }
function formatTime_(date) { return Utilities.formatDate(new Date(date), CANN.TIME_ZONE, 'HH:mm:ss'); }

function parseClientDateTime_(dateText, timeText) {
  return parseClientDateTimeStrict_(dateText, timeText) || new Date();
}

function parseClientDateTimeStrict_(dateText, timeText) {
  const date = text_(dateText);
  const suppliedTime = text_(timeText);
  const time = suppliedTime || '00:00:00';
  const format = time.length === 5
    ? 'yyyy-MM-dd HH:mm'
    : 'yyyy-MM-dd HH:mm:ss';
  const combined = date + ' ' + time;
  if (
    !isClientDate_(date) ||
    (suppliedTime && !isClientTime_(suppliedTime))
  ) {
    return null;
  }
  try {
    const parsed = Utilities.parseDate(combined, CANN.TIME_ZONE, format);
    if (
      !parsed ||
      isNaN(parsed.getTime()) ||
      Utilities.formatDate(parsed, CANN.TIME_ZONE, format) !== combined
    ) {
      return null;
    }
    return parsed;
  } catch (error) {
    return null;
  }
}

function isClientDate_(value) {
  const text = text_(value);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return false;
  const parsed = new Date(text + 'T00:00:00');
  return !isNaN(parsed.getTime()) &&
    parsed.getFullYear() === Number(text.slice(0, 4)) &&
    parsed.getMonth() + 1 === Number(text.slice(5, 7)) &&
    parsed.getDate() === Number(text.slice(8, 10));
}

function isClientTime_(value) {
  return /^([01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/.test(text_(value));
}

function deterministicLegacyEventUuid_(spreadsheetId, sheetName, rowNumber) {
  const bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, spreadsheetId + ':' + sheetName + ':' + rowNumber, Utilities.Charset.UTF_8);
  let hex = bytes.map(value => ('0' + ((value + 256) % 256).toString(16)).slice(-2)).join('').slice(0, 32);
  hex = hex.slice(0, 12) + '5' + hex.slice(13, 16) + '8' + hex.slice(17);
  return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
}

function requestFailure_(code, message, environment, timing) {
  const responseConstructionStarted = Date.now();
  const response = { success: false, message: message, errorCode: code, productIdMap: {}, environment: environment };
  recordBackendPhase_(timing, 'responseConstruction', responseConstructionStarted);
  addServerTimingFields_(response, timing, environment);
  const responseRoutingStarted = Date.now();
  const output = jsonOutput_(response);
  recordBackendPhase_(timing, 'responseRouting', responseRoutingStarted);
  return output;
}

function jsonOutput_(value) {
  return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON);
}

function conciseError_(error) { return error && error.message ? String(error.message).slice(0, 500) : String(error).slice(0, 500); }
function text_(value) { return value == null ? '' : String(value).trim(); }
function arrayOrEmpty_(value) { return value == null ? [] : value; }
function finiteNumber_(value) { const number = Number(value); return Number.isFinite(number) ? number : null; }
function optionalFiniteNumber_(value) {
  return value == null || value === '' ? null : finiteNumber_(value);
}
function isMissingPurchaseNumber_(value) { return value == null || text_(value) === ''; }
function isOptionalFinitePurchaseNumber_(value) {
  return isMissingPurchaseNumber_(value) || isFiniteNumber_(value);
}
function isRequiredFinitePurchaseNumber_(value) {
  return !isMissingPurchaseNumber_(value) && isFiniteNumber_(value);
}
function purchaseNumberOrBlank_(value) {
  return isMissingPurchaseNumber_(value) ? '' : finiteNumber_(value);
}
function finiteNumberOr_(value, fallback) { const number = finiteNumber_(value); return number == null ? fallback : number; }
function isFiniteNumber_(value) { return finiteNumber_(value) != null; }
function allowedStatusOr_(value, fallback) { const status = Number(value); return [0, 1, 2].indexOf(status) >= 0 ? status : fallback; }
function truthy_(value) { return value === true || value === 1 || text_(value).toLowerCase() === 'true' || text_(value).toLowerCase() === 'yes'; }
function dateOrNow_(value) { const date = new Date(value); return isNaN(date.getTime()) ? new Date() : date; }
function dateOrNull_(value) { const date = new Date(value); return isNaN(date.getTime()) ? null : date; }
function timestampMillisOrNull_(value) {
  if (value == null || value === '') return null;
  const millis = new Date(value).getTime();
  return Number.isFinite(millis) ? millis : null;
}
function isUuid_(value) { return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value); }
function firstDuplicate_(values) { const seen = {}; for (let i = 0; i < values.length; i++) { if (seen[values[i]]) return values[i]; seen[values[i]] = true; } return ''; }
