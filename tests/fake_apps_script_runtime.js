'use strict';

const crypto = require('node:crypto');
const vm = require('node:vm');

const AUDIT_BUCKETS = Object.freeze([
  'reads',
  'finders',
  'writes',
  'structural',
  'services',
  'batches',
  'locks',
  'form',
]);

const SHEETS_SERIAL_EPOCH_MS = Date.UTC(1899, 11, 30);
const MILLISECONDS_PER_DAY = 24 * 60 * 60 * 1000;

function cloneValue(value) {
  if (value instanceof Date) return new Date(value.getTime());
  if (Array.isArray(value)) return value.map(cloneValue);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, cloneValue(item)]));
  }
  return value;
}

function cloneMatrix(rows) {
  return (rows || []).map(row => (row || []).map(cloneValue));
}

function isPopulated(value) {
  return value !== '' && value !== null && value !== undefined;
}

function cellKey(row, column) {
  return `${row}:${column}`;
}

function cloneNumberFormats(formats) {
  return new Map(Array.from(formats || [], ([key, value]) => [key, cloneValue(value)]));
}

function numberFormatType(format) {
  return String(format && format.type || '').toUpperCase();
}

function isDateNumberFormat(format) {
  return ['DATE', 'DATE_TIME', 'TIME'].includes(numberFormatType(format));
}

function numberFormatFromPattern(pattern) {
  const normalized = String(pattern || '').toLowerCase();
  const hasDate = /[yd]/.test(normalized);
  const hasTime = /[hs]/.test(normalized);
  let type = 'NUMBER';
  if (hasDate && hasTime) type = 'DATE_TIME';
  else if (hasDate) type = 'DATE';
  else if (hasTime) type = 'TIME';
  return { type, pattern: String(pattern || '') };
}

function datePartsInTimeZone(dateValue, timeZone) {
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: timeZone || 'UTC',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  });
  return Object.fromEntries(
    formatter.formatToParts(new Date(dateValue))
      .filter(part => part.type !== 'literal')
      .map(part => [part.type, Number(part.value)]),
  );
}

function localDateTimeToDate(parts, timeZone) {
  const targetWallClockMs = Date.UTC(
    parts.year,
    parts.month - 1,
    parts.day,
    parts.hour,
    parts.minute,
    parts.second,
    parts.millisecond || 0,
  );
  let candidateMs = targetWallClockMs;
  for (let attempt = 0; attempt < 4; attempt += 1) {
    const observed = datePartsInTimeZone(candidateMs, timeZone);
    const observedWallClockMs = Date.UTC(
      observed.year,
      observed.month - 1,
      observed.day,
      observed.hour,
      observed.minute,
      observed.second,
      parts.millisecond || 0,
    );
    const correction = targetWallClockMs - observedWallClockMs;
    candidateMs += correction;
    if (correction === 0) break;
  }
  return new Date(candidateMs);
}

function sheetsSerialToDate(serialValue, timeZone) {
  const serial = Number(serialValue);
  if (!Number.isFinite(serial)) throw new Error('Sheets serial date must be a finite number');
  const wallClock = new Date(SHEETS_SERIAL_EPOCH_MS + Math.round(serial * MILLISECONDS_PER_DAY));
  return localDateTimeToDate({
    year: wallClock.getUTCFullYear(),
    month: wallClock.getUTCMonth() + 1,
    day: wallClock.getUTCDate(),
    hour: wallClock.getUTCHours(),
    minute: wallClock.getUTCMinutes(),
    second: wallClock.getUTCSeconds(),
    millisecond: wallClock.getUTCMilliseconds(),
  }, timeZone);
}

function columnToLetters(column) {
  let value = Number(column);
  if (!Number.isInteger(value) || value < 1) throw new Error('Column must be a positive integer');
  let result = '';
  while (value > 0) {
    value -= 1;
    result = String.fromCharCode(65 + (value % 26)) + result;
    value = Math.floor(value / 26);
  }
  return result;
}

function lettersToColumn(letters) {
  const normalized = String(letters || '').toUpperCase();
  if (!/^[A-Z]+$/.test(normalized)) throw new Error('Invalid A1 column: ' + letters);
  let result = 0;
  for (const character of normalized) result = result * 26 + character.charCodeAt(0) - 64;
  return result;
}

function rangeA1(row, column, numRows, numColumns) {
  const first = columnToLetters(column) + row;
  const last = columnToLetters(column + numColumns - 1) + (row + numRows - 1);
  return first === last ? first : first + ':' + last;
}

function parseA1(a1, maxRows, maxColumns) {
  const normalized = String(a1 || '').trim().replace(/\$/g, '').toUpperCase();
  const match = normalized.match(/^([A-Z]+)(\d*)?(?::([A-Z]+)(\d*)?)?$/);
  if (!match) throw new Error('Unsupported A1 notation: ' + a1);

  const startColumn = lettersToColumn(match[1]);
  const hasColon = normalized.includes(':');
  const startRow = match[2] ? Number(match[2]) : 1;
  const endColumn = match[3] ? lettersToColumn(match[3]) : startColumn;
  let endRow;
  if (!hasColon) endRow = startRow;
  else if (match[4]) endRow = Number(match[4]);
  else endRow = maxRows;

  if (!match[2] && hasColon) endRow = match[4] ? Number(match[4]) : maxRows;
  if (endColumn < startColumn || endRow < startRow) throw new Error('Invalid A1 range: ' + a1);
  if (endColumn > maxColumns) {
    // Apps Script accepts ranges up to the sheet's current maximum columns only.
    throw new Error('A1 range exceeds max columns: ' + a1);
  }
  return {
    row: startRow,
    column: startColumn,
    numRows: endRow - startRow + 1,
    numColumns: endColumn - startColumn + 1,
  };
}

function makeRow(headers, values) {
  const source = values || {};
  return headers.map(header => cloneValue(source[header] === undefined ? '' : source[header]));
}

function makeSheetRows(headers, records) {
  return [headers.slice()].concat((records || []).map(record => makeRow(headers, record)));
}

function deterministicUuid(index) {
  const value = String(Number(index)).padStart(12, '0');
  return `00000000-0000-4000-8000-${value}`;
}

function buildConfigRows(options = {}) {
  const environment = options.environment || 'SANDBOX';
  const schemaVersion = options.schemaVersion === undefined ? 2 : options.schemaVersion;
  const rows = [
    ['Key', 'Value', 'Description'],
    ['ENVIRONMENT', environment, 'Runtime environment marker'],
    ['TAX_RATE', options.taxRate === undefined ? 0.13 : options.taxRate, 'Tax rate'],
    ['TIME_ZONE', options.timeZone || 'America/New_York', 'Canonical local timezone'],
    ['SCHEMA_VERSION', schemaVersion, 'Spreadsheet schema version'],
    ['MAX_BATCH_SIZE', options.maxBatchSize === undefined ? 100 : options.maxBatchSize, 'Maximum batch size'],
    ['LOCK_TIMEOUT_MS', options.lockTimeoutMs === undefined ? 30000 : options.lockTimeoutMs, 'Lock timeout'],
  ];
  if (options.interactionSummaryVersion !== undefined) {
    rows.splice(5, 0, [
      'INTERACTION_SUMMARY_VERSION',
      options.interactionSummaryVersion,
      'Purchases interaction-summary version',
    ]);
  }
  if (options.recoverableSyncApplyVersion !== undefined) {
    rows.push([
      'RECOVERABLE_SYNC_APPLY_VERSION',
      options.recoverableSyncApplyVersion,
      'Recoverable sync apply version',
    ]);
  }
  if (options.pendingApplyKey !== undefined) {
    rows.push([
      'PENDING_APPLY_KEY',
      options.pendingApplyKey,
      'Pending recoverable apply pointer',
    ]);
  }
  return rows;
}

class AuditRecorder {
  constructor() {
    this.sequence = 0;
    this.reset();
  }

  reset() {
    this.sequence = 0;
    for (const bucket of AUDIT_BUCKETS) this[bucket] = [];
  }

  record(bucket, entry) {
    if (!AUDIT_BUCKETS.includes(bucket)) throw new Error('Unknown audit bucket: ' + bucket);
    const recorded = Object.assign({ sequence: ++this.sequence }, cloneValue(entry));
    this[bucket].push(recorded);
    return recorded;
  }

  timeline() {
    return AUDIT_BUCKETS
      .flatMap(bucket => this[bucket].map(entry => Object.assign({ bucket }, cloneValue(entry))))
      .sort((left, right) => left.sequence - right.sequence);
  }
}

class FakeRange {
  constructor(sheet, row, column, numRows = 1, numColumns = 1) {
    for (const [name, value] of Object.entries({ row, column, numRows, numColumns })) {
      if (!Number.isInteger(value) || value < 1) throw new Error(`${name} must be a positive integer`);
    }
    this.sheet = sheet;
    this.row = row;
    this.column = column;
    this.numRows = numRows;
    this.numColumns = numColumns;
  }

  _descriptor(operation) {
    return {
      operation,
      sheet: this.sheet.getName(),
      row: this.row,
      column: this.column,
      numRows: this.numRows,
      numColumns: this.numColumns,
      cellCount: this.numRows * this.numColumns,
      a1: this.getA1Notation(),
    };
  }

  _readRaw() {
    const result = [];
    for (let rowOffset = 0; rowOffset < this.numRows; rowOffset += 1) {
      const row = [];
      for (let columnOffset = 0; columnOffset < this.numColumns; columnOffset += 1) {
        row.push(cloneValue(this.sheet._getCell(this.row + rowOffset, this.column + columnOffset)));
      }
      result.push(row);
    }
    return result;
  }

  _writeRaw(values, operation) {
    if (!Array.isArray(values) || values.length !== this.numRows) {
      throw new Error(`setValues row count mismatch for ${this.getA1Notation()}`);
    }
    values.forEach(row => {
      if (!Array.isArray(row) || row.length !== this.numColumns) {
        throw new Error(`setValues column count mismatch for ${this.getA1Notation()}`);
      }
    });
    const before = this._readRaw();
    this.sheet._ensureCapacity(this.row + this.numRows - 1, this.column + this.numColumns - 1);
    for (let rowOffset = 0; rowOffset < this.numRows; rowOffset += 1) {
      for (let columnOffset = 0; columnOffset < this.numColumns; columnOffset += 1) {
        this.sheet._setCell(
          this.row + rowOffset,
          this.column + columnOffset,
          cloneValue(values[rowOffset][columnOffset]),
        );
      }
    }
    const after = this._readRaw();
    this.sheet.runtime.audit.record('writes', Object.assign(this._descriptor(operation), { before, after }));
    return this;
  }

  getValues() {
    const values = this._readRaw();
    this.sheet.runtime.audit.record('reads', Object.assign(this._descriptor('getValues'), { values }));
    return values;
  }

  getValue() {
    const value = cloneValue(this.sheet._getCell(this.row, this.column));
    this.sheet.runtime.audit.record('reads', Object.assign(this._descriptor('getValue'), { value }));
    return value;
  }

  getDisplayValues() {
    const values = this._readRaw().map(row => row.map(value => {
      if (value instanceof Date) return value.toISOString();
      return value === null || value === undefined ? '' : String(value);
    }));
    this.sheet.runtime.audit.record('reads', Object.assign(this._descriptor('getDisplayValues'), { values }));
    return values;
  }

  getDisplayValue() {
    return this.getDisplayValues()[0][0];
  }

  setValues(values) {
    return this._writeRaw(values, 'setValues');
  }

  setValue(value) {
    if (this.numRows !== 1 || this.numColumns !== 1) {
      const values = Array.from({ length: this.numRows }, () => Array(this.numColumns).fill(value));
      return this._writeRaw(values, 'setValue');
    }
    return this._writeRaw([[value]], 'setValue');
  }

  clearContent() {
    const values = Array.from({ length: this.numRows }, () => Array(this.numColumns).fill(''));
    return this._writeRaw(values, 'clearContent');
  }

  clearContents() {
    return this.clearContent();
  }

  getFormulas() {
    const values = this._readRaw().map(row => row.map(value => (
      typeof value === 'string' && value.startsWith('=') ? value : ''
    )));
    this.sheet.runtime.audit.record('reads', Object.assign(this._descriptor('getFormulas'), { values }));
    return values;
  }

  setFormula(formula) {
    return this.setValue(formula);
  }

  setFormulas(values) {
    return this._writeRaw(values, 'setFormulas');
  }

  getRow() { return this.row; }
  getColumn() { return this.column; }
  getNumRows() { return this.numRows; }
  getNumColumns() { return this.numColumns; }
  getLastRow() { return this.row + this.numRows - 1; }
  getLastColumn() { return this.column + this.numColumns - 1; }
  getSheet() { return this.sheet; }
  getA1Notation() { return rangeA1(this.row, this.column, this.numRows, this.numColumns); }

  getCell(relativeRow, relativeColumn) {
    if (relativeRow < 1 || relativeRow > this.numRows || relativeColumn < 1 || relativeColumn > this.numColumns) {
      throw new Error('Cell lies outside range ' + this.getA1Notation());
    }
    return new FakeRange(this.sheet, this.row + relativeRow - 1, this.column + relativeColumn - 1, 1, 1);
  }

  offset(rowOffset, columnOffset, numRows = this.numRows, numColumns = this.numColumns) {
    return new FakeRange(this.sheet, this.row + rowOffset, this.column + columnOffset, numRows, numColumns);
  }

  isBlank() {
    const blank = this._readRaw().every(row => row.every(value => !isPopulated(value)));
    this.sheet.runtime.audit.record('reads', Object.assign(this._descriptor('isBlank'), { value: blank }));
    return blank;
  }

  createTextFinder(query) {
    return new FakeTextFinder(this, query);
  }

  _recordStructural(operation, details = {}) {
    this.sheet.runtime.audit.record('structural', Object.assign(this._descriptor(operation), details));
    return this;
  }

  setDataValidation(rule) { return this._recordStructural('setDataValidation', { rule }); }
  setDataValidations(rules) { return this._recordStructural('setDataValidations', { rules }); }
  setBackground(value) { return this._recordStructural('setBackground', { value }); }
  setBackgrounds(values) { return this._recordStructural('setBackgrounds', { values }); }
  setFontColor(value) { return this._recordStructural('setFontColor', { value }); }
  setFontWeight(value) { return this._recordStructural('setFontWeight', { value }); }
  setNumberFormat(value) {
    for (let rowOffset = 0; rowOffset < this.numRows; rowOffset += 1) {
      for (let columnOffset = 0; columnOffset < this.numColumns; columnOffset += 1) {
        this.sheet._setNumberFormat(
          this.row + rowOffset,
          this.column + columnOffset,
          numberFormatFromPattern(value),
        );
      }
    }
    return this._recordStructural('setNumberFormat', { value });
  }
  setNote(value) { return this._recordStructural('setNote', { value }); }

  protect() {
    const protection = new FakeProtection(this.sheet, this);
    this.sheet.protections.push(protection);
    this.sheet.runtime.audit.record('structural', Object.assign(this._descriptor('protect')));
    return protection;
  }
}

class FakeTextFinder {
  constructor(range, query) {
    this.range = range;
    this.query = String(query);
    this.entireCell = false;
    this.caseSensitive = false;
    this.regularExpression = false;
    this.cursor = 0;
  }

  matchEntireCell(value) { this.entireCell = !!value; return this; }
  matchCase(value) { this.caseSensitive = !!value; return this; }
  useRegularExpression(value) { this.regularExpression = !!value; return this; }
  ignoreDiacritics() { return this; }

  _matches(value) {
    const candidate = value === null || value === undefined ? '' : String(value);
    if (this.regularExpression) {
      const flags = this.caseSensitive ? '' : 'i';
      const expression = new RegExp(this.query, flags);
      return this.entireCell ? expression.test(candidate) && candidate.match(expression)?.[0] === candidate : expression.test(candidate);
    }
    const expected = this.caseSensitive ? this.query : this.query.toLowerCase();
    const actual = this.caseSensitive ? candidate : candidate.toLowerCase();
    return this.entireCell ? actual === expected : actual.includes(expected);
  }

  _allMatches() {
    const matches = [];
    for (let rowOffset = 0; rowOffset < this.range.numRows; rowOffset += 1) {
      for (let columnOffset = 0; columnOffset < this.range.numColumns; columnOffset += 1) {
        const row = this.range.row + rowOffset;
        const column = this.range.column + columnOffset;
        if (this._matches(this.range.sheet._getCell(row, column))) {
          matches.push(new FakeRange(this.range.sheet, row, column, 1, 1));
        }
      }
    }
    return matches;
  }

  _record(operation, matches) {
    this.range.sheet.runtime.audit.record('finders', Object.assign(this.range._descriptor(operation), {
      query: this.query,
      matchEntireCell: this.entireCell,
      matchCase: this.caseSensitive,
      useRegularExpression: this.regularExpression,
      matches: matches.map(match => match.getA1Notation()),
    }));
  }

  findNext() {
    const matches = this._allMatches();
    const result = matches[this.cursor] || null;
    if (result) this.cursor += 1;
    this._record('findNext', result ? [result] : []);
    return result;
  }

  findAll() {
    const matches = this._allMatches();
    this._record('findAll', matches);
    return matches;
  }
}

class FakeRangeList {
  constructor(ranges) {
    this.ranges = ranges;
  }

  getRanges() { return this.ranges.slice(); }
  setValue(value) { this.ranges.forEach(range => range.setValue(value)); return this; }
  clearContent() { this.ranges.forEach(range => range.clearContent()); return this; }
  clearContents() { return this.clearContent(); }
  setBackground(value) { this.ranges.forEach(range => range.setBackground(value)); return this; }
  setFontColor(value) { this.ranges.forEach(range => range.setFontColor(value)); return this; }
  setFontWeight(value) { this.ranges.forEach(range => range.setFontWeight(value)); return this; }
  setNumberFormat(value) { this.ranges.forEach(range => range.setNumberFormat(value)); return this; }
}

class FakeProtection {
  constructor(sheet, range) {
    this.sheet = sheet;
    this.range = range;
    this.description = '';
    this.warningOnly = false;
  }

  getDescription() { return this.description; }
  getRange() { return this.range; }
  setDescription(description) {
    this.description = String(description);
    this.sheet.runtime.audit.record('structural', Object.assign(this.range._descriptor('setProtectionDescription'), {
      description: this.description,
    }));
    return this;
  }
  setWarningOnly(value) {
    this.warningOnly = !!value;
    this.sheet.runtime.audit.record('structural', Object.assign(this.range._descriptor('setProtectionWarningOnly'), {
      value: this.warningOnly,
    }));
    return this;
  }
}

class FakeSheet {
  constructor(runtime, parent, name, rows = [], options = {}) {
    this.runtime = runtime;
    this.parent = parent;
    this.name = String(name);
    this.rows = cloneMatrix(rows);
    this.numberFormats = new Map();
    this.rows.forEach((row, rowIndex) => {
      row.forEach((value, columnIndex) => {
        if (value instanceof Date) {
          this.numberFormats.set(cellKey(rowIndex + 1, columnIndex + 1), { type: 'DATE_TIME' });
        }
      });
    });
    Object.entries(options.numberFormats || {}).forEach(([key, value]) => {
      this.numberFormats.set(String(key), cloneValue(value));
    });
    const populatedWidth = this.rows.reduce((max, row) => Math.max(max, row.length), 0);
    this.maxRows = Math.max(Number(options.maxRows) || 1000, this.rows.length, 1);
    this.maxColumns = Math.max(Number(options.maxColumns) || 26, populatedWidth, 1);
    this.frozenRows = Number(options.frozenRows) || 0;
    this.protections = [];
  }

  _service(method, details = {}) {
    this.runtime.audit.record('services', Object.assign({
      service: 'Sheet',
      method,
      sheet: this.name,
    }, details));
  }

  _ensureCapacity(row, column) {
    this.maxRows = Math.max(this.maxRows, row);
    this.maxColumns = Math.max(this.maxColumns, column);
    while (this.rows.length < row) this.rows.push([]);
    for (const existingRow of this.rows) {
      while (existingRow.length < column) existingRow.push('');
    }
  }

  _getCell(row, column) {
    const existingRow = this.rows[row - 1];
    return existingRow && existingRow[column - 1] !== undefined ? existingRow[column - 1] : '';
  }

  _setCell(row, column, value) {
    this._ensureCapacity(row, column);
    this.rows[row - 1][column - 1] = cloneValue(value);
    if (value instanceof Date && !this.numberFormats.has(cellKey(row, column))) {
      this.numberFormats.set(cellKey(row, column), { type: 'DATE_TIME' });
    }
  }

  _getNumberFormat(row, column) {
    return cloneValue(this.numberFormats.get(cellKey(row, column)) || null);
  }

  _setNumberFormat(row, column, format) {
    this._ensureCapacity(row, column);
    const key = cellKey(row, column);
    if (format == null) this.numberFormats.delete(key);
    else this.numberFormats.set(key, cloneValue(format));
  }

  _lastRowRaw() {
    for (let index = this.rows.length - 1; index >= 0; index -= 1) {
      if (this.rows[index].some(isPopulated)) return index + 1;
    }
    return 0;
  }

  _lastColumnRaw() {
    let result = 0;
    for (const row of this.rows) {
      for (let index = row.length - 1; index >= 0; index -= 1) {
        if (isPopulated(row[index])) {
          result = Math.max(result, index + 1);
          break;
        }
      }
    }
    return result;
  }

  getName() { return this.name; }
  getParent() { return this.parent; }
  getSheetId() { return this.parent._sheetIds.get(this.name); }

  getLastRow() {
    const value = this._lastRowRaw();
    this._service('getLastRow', { value });
    return value;
  }

  getLastColumn() {
    const value = this._lastColumnRaw();
    this._service('getLastColumn', { value });
    return value;
  }

  getMaxRows() { this._service('getMaxRows', { value: this.maxRows }); return this.maxRows; }
  getMaxColumns() { this._service('getMaxColumns', { value: this.maxColumns }); return this.maxColumns; }

  getRange(rowOrA1, column, numRows = 1, numColumns = 1) {
    let coordinates;
    if (typeof rowOrA1 === 'string') coordinates = parseA1(rowOrA1, this.maxRows, this.maxColumns);
    else coordinates = { row: rowOrA1, column, numRows, numColumns };
    this._service('getRange', Object.assign({}, coordinates));
    return new FakeRange(this, coordinates.row, coordinates.column, coordinates.numRows, coordinates.numColumns);
  }

  getRangeList(a1Notations) {
    if (!Array.isArray(a1Notations)) throw new Error('getRangeList expects an array');
    this._service('getRangeList', { ranges: a1Notations.slice() });
    return new FakeRangeList(a1Notations.map(a1 => this.getRange(a1)));
  }

  getDataRange() {
    const lastRow = Math.max(1, this._lastRowRaw());
    const lastColumn = Math.max(1, this._lastColumnRaw());
    this._service('getDataRange', { lastRow, lastColumn });
    return new FakeRange(this, 1, 1, lastRow, lastColumn);
  }

  createTextFinder(query) {
    this._service('createTextFinder', { query: String(query) });
    return this.getDataRange().createTextFinder(query);
  }

  appendRow(values) {
    if (!Array.isArray(values)) throw new Error('appendRow expects an array');
    const row = this._lastRowRaw() + 1;
    this.getRange(row, 1, 1, values.length).setValues([values]);
    return this;
  }

  insertColumnsAfter(afterPosition, howMany) {
    if (!Number.isInteger(afterPosition) || afterPosition < 1 || !Number.isInteger(howMany) || howMany < 1) {
      throw new Error('Invalid insertColumnsAfter arguments');
    }
    this.rows.forEach(row => row.splice(afterPosition, 0, ...Array(howMany).fill('')));
    this.numberFormats = new Map(Array.from(this.numberFormats, ([key, value]) => {
      const [row, column] = key.split(':').map(Number);
      const shiftedColumn = column > afterPosition ? column + howMany : column;
      return [cellKey(row, shiftedColumn), value];
    }));
    this.maxColumns += howMany;
    this.runtime.audit.record('structural', {
      operation: 'insertColumnsAfter',
      sheet: this.name,
      afterPosition,
      howMany,
    });
    return this;
  }

  deleteRows(startRow, howMany) {
    if (!Number.isInteger(startRow) || startRow < 1 || !Number.isInteger(howMany) || howMany < 1) {
      throw new Error('Invalid deleteRows arguments');
    }
    const before = cloneMatrix(this.rows.slice(startRow - 1, startRow - 1 + howMany));
    this.rows.splice(startRow - 1, howMany);
    const endRow = startRow + howMany - 1;
    this.numberFormats = new Map(Array.from(this.numberFormats)
      .map(([key, value]) => {
        const [row, column] = key.split(':').map(Number);
        if (row >= startRow && row <= endRow) return null;
        return [cellKey(row > endRow ? row - howMany : row, column), value];
      })
      .filter(Boolean));
    this.runtime.audit.record('structural', {
      operation: 'deleteRows',
      sheet: this.name,
      startRow,
      howMany,
      before,
    });
    return this;
  }

  setFrozenRows(count) {
    this.frozenRows = Number(count);
    this.runtime.audit.record('structural', { operation: 'setFrozenRows', sheet: this.name, count: this.frozenRows });
    return this;
  }

  getFrozenRows() { return this.frozenRows; }

  getProtections(type) {
    this._service('getProtections', { type });
    return this.protections.slice();
  }

  snapshot() {
    const lastRow = this._lastRowRaw();
    const lastColumn = this._lastColumnRaw();
    return {
      name: this.name,
      rows: cloneMatrix(this.rows.slice(0, lastRow).map(row => row.slice(0, lastColumn))),
      maxRows: this.maxRows,
      maxColumns: this.maxColumns,
      frozenRows: this.frozenRows,
      numberFormats: Object.fromEntries(
        Array.from(this.numberFormats.entries()).sort(([left], [right]) => left.localeCompare(right)),
      ),
    };
  }
}

class FakeSpreadsheet {
  constructor(runtime, id, title = 'Fake Spreadsheet', options = {}) {
    this.runtime = runtime;
    this.id = String(id);
    this.title = String(title);
    this.timeZone = String(options.timeZone || 'America/New_York');
    this.sheets = new Map();
    this._sheetIds = new Map();
    this._nextSheetId = 1;
  }

  _service(method, details = {}) {
    this.runtime.audit.record('services', Object.assign({ service: 'Spreadsheet', method, spreadsheetId: this.id }, details));
  }

  getId() { this._service('getId'); return this.id; }
  getName() { return this.title; }
  getSpreadsheetTimeZone() {
    this._service('getSpreadsheetTimeZone', { value: this.timeZone });
    return this.timeZone;
  }

  getSheetByName(name) {
    this._service('getSheetByName', { sheet: String(name) });
    return this.sheets.get(String(name)) || null;
  }

  getSheets() {
    this._service('getSheets');
    return Array.from(this.sheets.values());
  }

  _sheetById(sheetId) {
    const normalized = Number(sheetId);
    for (const [name, id] of this._sheetIds.entries()) {
      if (id === normalized) return this.sheets.get(name) || null;
    }
    return null;
  }

  getSheetById(sheetId) {
    this._service('getSheetById', { sheetId: Number(sheetId) });
    return this._sheetById(sheetId);
  }

  insertSheet(name) {
    const normalized = String(name || `Sheet${this.sheets.size + 1}`);
    if (this.sheets.has(normalized)) throw new Error('Sheet already exists: ' + normalized);
    const sheet = new FakeSheet(this.runtime, this, normalized);
    this.sheets.set(normalized, sheet);
    this._sheetIds.set(normalized, this._nextSheetId++);
    this.runtime.audit.record('structural', { operation: 'insertSheet', spreadsheetId: this.id, sheet: normalized });
    return sheet;
  }

  deleteSheet(sheet) {
    const name = sheet && sheet.getName ? sheet.getName() : String(sheet);
    if (!this.sheets.has(name)) throw new Error('Sheet not found: ' + name);
    this.sheets.delete(name);
    this._sheetIds.delete(name);
    this.runtime.audit.record('structural', { operation: 'deleteSheet', spreadsheetId: this.id, sheet: name });
  }

  seedSheet(name, rows = [], options = {}) {
    const normalized = String(name);
    const sheet = new FakeSheet(this.runtime, this, normalized, rows, options);
    this.sheets.set(normalized, sheet);
    if (!this._sheetIds.has(normalized)) this._sheetIds.set(normalized, this._nextSheetId++);
    return sheet;
  }

  snapshot() {
    return {
      id: this.id,
      title: this.title,
      timeZone: this.timeZone,
      sheets: Object.fromEntries(Array.from(this.sheets.entries()).map(([name, sheet]) => [name, sheet.snapshot()])),
    };
  }
}

function fieldsSelect(fields, path) {
  const selected = String(fields || '')
    .split(',')
    .map(value => value.trim())
    .filter(Boolean);
  if (!selected.length) throw new Error('Advanced Sheets request fields must not be empty');
  return selected.includes('*') || selected.includes(path) || selected.some(value => path.startsWith(`${value}.`));
}

function decodeUserEnteredValue(userEnteredValue, numberFormat, timeZone) {
  if (userEnteredValue == null) return '';
  if (!userEnteredValue || typeof userEnteredValue !== 'object' || Array.isArray(userEnteredValue)) {
    throw new Error('userEnteredValue must be an object');
  }
  const supportedKeys = ['stringValue', 'numberValue', 'boolValue', 'formulaValue'];
  const presentKeys = supportedKeys.filter(key => Object.prototype.hasOwnProperty.call(userEnteredValue, key));
  const unsupportedKeys = Object.keys(userEnteredValue).filter(key => !supportedKeys.includes(key));
  if (unsupportedKeys.length) {
    throw new Error('Unsupported userEnteredValue field: ' + unsupportedKeys[0]);
  }
  if (presentKeys.length > 1) throw new Error('userEnteredValue must contain exactly one value field');
  if (!presentKeys.length) return '';

  const key = presentKeys[0];
  const raw = userEnteredValue[key];
  if (key === 'stringValue' || key === 'formulaValue') return String(raw == null ? '' : raw);
  if (key === 'boolValue') return !!raw;

  const number = Number(raw);
  if (!Number.isFinite(number)) throw new Error('numberValue must be a finite number');
  return isDateNumberFormat(numberFormat)
    ? sheetsSerialToDate(number, timeZone)
    : number;
}

function createSpreadsheetShadow(spreadsheet) {
  const byId = new Map();
  for (const [name, sheet] of spreadsheet.sheets.entries()) {
    const sheetId = spreadsheet._sheetIds.get(name);
    byId.set(sheetId, {
      sheet,
      sheetId,
      name,
      rows: cloneMatrix(sheet.rows),
      maxRows: sheet.maxRows,
      maxColumns: sheet.maxColumns,
      numberFormats: cloneNumberFormats(sheet.numberFormats),
    });
  }
  return { spreadsheet, byId };
}

function shadowEnsureCapacity(state, row, column) {
  state.maxRows = Math.max(state.maxRows, row);
  state.maxColumns = Math.max(state.maxColumns, column);
  while (state.rows.length < row) state.rows.push([]);
  for (const existingRow of state.rows) {
    while (existingRow.length < column) existingRow.push('');
  }
}

function shadowLastRow(state) {
  for (let index = state.rows.length - 1; index >= 0; index -= 1) {
    if (state.rows[index].some(isPopulated)) return index + 1;
  }
  return 0;
}

function shadowNumberFormat(state, row, column, inferColumnFormat = false) {
  const exact = state.numberFormats.get(cellKey(row, column));
  if (exact) return cloneValue(exact);
  if (!inferColumnFormat) return null;
  for (const [key, format] of state.numberFormats.entries()) {
    const existingColumn = Number(key.split(':')[1]);
    if (existingColumn === column) return cloneValue(format);
  }
  return null;
}

function shadowApplyCellData(state, row, column, cellData, fields, timeZone, inferColumnFormat) {
  if (cellData != null && (typeof cellData !== 'object' || Array.isArray(cellData))) {
    throw new Error('CellData must be an object');
  }
  const normalizedCellData = cellData || {};
  shadowEnsureCapacity(state, row, column);
  const key = cellKey(row, column);

  if (fieldsSelect(fields, 'userEnteredFormat.numberFormat')) {
    const enteredFormat = normalizedCellData.userEnteredFormat;
    if (enteredFormat && Object.prototype.hasOwnProperty.call(enteredFormat, 'numberFormat')) {
      const format = enteredFormat.numberFormat;
      if (format == null) state.numberFormats.delete(key);
      else if (typeof format !== 'object' || Array.isArray(format)) {
        throw new Error('numberFormat must be an object');
      } else {
        state.numberFormats.set(key, cloneValue(format));
      }
    } else {
      state.numberFormats.delete(key);
    }
  }

  if (fieldsSelect(fields, 'userEnteredValue')) {
    const exactFormat = shadowNumberFormat(state, row, column, false);
    const effectiveFormat = exactFormat ||
      shadowNumberFormat(state, row, column, inferColumnFormat);
    if (!exactFormat && inferColumnFormat && effectiveFormat) {
      // Appended cells inherit a column's existing format in the live sheets
      // used by these tests. Remember that inherited format so a later
      // updateCells request decodes another serial consistently.
      state.numberFormats.set(key, cloneValue(effectiveFormat));
    }
    const enteredValue = Object.prototype.hasOwnProperty.call(normalizedCellData, 'userEnteredValue')
      ? normalizedCellData.userEnteredValue
      : null;
    state.rows[row - 1][column - 1] = decodeUserEnteredValue(
      enteredValue,
      effectiveFormat,
      timeZone,
    );
  }
}

function requireShadowSheet(shadow, sheetId) {
  const normalized = Number(sheetId);
  if (!Number.isInteger(normalized) || normalized < 0) {
    throw new Error('Advanced Sheets sheetId must be a non-negative integer');
  }
  const state = shadow.byId.get(normalized);
  if (!state) throw new Error('Advanced Sheets sheet not found: ' + sheetId);
  return state;
}

function applyAppendCellsRequest(shadow, request, timeZone) {
  const state = requireShadowSheet(shadow, request.sheetId);
  if (!Array.isArray(request.rows)) throw new Error('appendCells.rows must be an array');
  const fields = String(request.fields || '');
  const startRow = shadowLastRow(state) + 1;
  request.rows.forEach((rowData, rowOffset) => {
    if (!rowData || typeof rowData !== 'object' || Array.isArray(rowData)) {
      throw new Error('appendCells RowData must be an object');
    }
    const values = rowData.values == null ? [] : rowData.values;
    if (!Array.isArray(values)) throw new Error('appendCells RowData.values must be an array');
    values.forEach((cellData, columnOffset) => {
      shadowApplyCellData(
        state,
        startRow + rowOffset,
        columnOffset + 1,
        cellData,
        fields,
        timeZone,
        true,
      );
    });
  });
  return {
    operation: 'appendCells',
    sheet: state.name,
    sheetId: state.sheetId,
    startRowIndex: startRow - 1,
    rowCount: request.rows.length,
  };
}

function updateCellsCoordinates(request) {
  const grid = request.start || request.range;
  if (!grid || typeof grid !== 'object' || Array.isArray(grid)) {
    throw new Error('updateCells requires start or range coordinates');
  }
  const rowIndex = grid.rowIndex == null ? grid.startRowIndex : grid.rowIndex;
  const columnIndex = grid.columnIndex == null ? grid.startColumnIndex : grid.columnIndex;
  const normalizedRow = rowIndex == null ? 0 : Number(rowIndex);
  const normalizedColumn = columnIndex == null ? 0 : Number(columnIndex);
  if (!Number.isInteger(normalizedRow) || normalizedRow < 0 ||
      !Number.isInteger(normalizedColumn) || normalizedColumn < 0) {
    throw new Error('updateCells coordinates must be non-negative integers');
  }
  return {
    sheetId: grid.sheetId,
    rowIndex: normalizedRow,
    columnIndex: normalizedColumn,
    endRowIndex: grid.endRowIndex == null ? null : Number(grid.endRowIndex),
    endColumnIndex: grid.endColumnIndex == null ? null : Number(grid.endColumnIndex),
  };
}

function applyUpdateCellsRequest(shadow, request, timeZone) {
  const coordinates = updateCellsCoordinates(request);
  const state = requireShadowSheet(shadow, coordinates.sheetId);
  if (!Array.isArray(request.rows)) throw new Error('updateCells.rows must be an array');
  const fields = String(request.fields || '');
  request.rows.forEach((rowData, rowOffset) => {
    if (!rowData || typeof rowData !== 'object' || Array.isArray(rowData)) {
      throw new Error('updateCells RowData must be an object');
    }
    const values = rowData.values == null ? [] : rowData.values;
    if (!Array.isArray(values)) throw new Error('updateCells RowData.values must be an array');
    values.forEach((cellData, columnOffset) => {
      const zeroBasedRow = coordinates.rowIndex + rowOffset;
      const zeroBasedColumn = coordinates.columnIndex + columnOffset;
      if (coordinates.endRowIndex != null && zeroBasedRow >= coordinates.endRowIndex) {
        throw new Error('updateCells rows exceed GridRange');
      }
      if (coordinates.endColumnIndex != null && zeroBasedColumn >= coordinates.endColumnIndex) {
        throw new Error('updateCells columns exceed GridRange');
      }
      shadowApplyCellData(
        state,
        zeroBasedRow + 1,
        zeroBasedColumn + 1,
        cellData,
        fields,
        timeZone,
        false,
      );
    });
  });
  return {
    operation: 'updateCells',
    sheet: state.name,
    sheetId: state.sheetId,
    startRowIndex: coordinates.rowIndex,
    startColumnIndex: coordinates.columnIndex,
    rowCount: request.rows.length,
  };
}

function applySheetsRequest(shadow, request, timeZone) {
  if (!request || typeof request !== 'object' || Array.isArray(request)) {
    throw new Error('Advanced Sheets request must be an object');
  }
  const operations = ['appendCells', 'updateCells']
    .filter(operation => Object.prototype.hasOwnProperty.call(request, operation));
  if (operations.length !== 1) {
    throw new Error('Advanced Sheets request must contain exactly one supported operation');
  }
  if (operations[0] === 'appendCells') {
    return applyAppendCellsRequest(shadow, request.appendCells, timeZone);
  }
  return applyUpdateCellsRequest(shadow, request.updateCells, timeZone);
}

function commitSpreadsheetShadow(shadow) {
  for (const state of shadow.byId.values()) {
    state.sheet.rows = state.rows;
    state.sheet.maxRows = state.maxRows;
    state.sheet.maxColumns = state.maxColumns;
    state.sheet.numberFormats = state.numberFormats;
  }
}

function sheetsBatchUpdate(runtime, body, spreadsheetId) {
  const normalizedSpreadsheetId = String(spreadsheetId);
  const requestBody = cloneValue(body);
  runtime.audit.record('services', {
    service: 'Sheets',
    method: 'Spreadsheets.batchUpdate',
    spreadsheetId: normalizedSpreadsheetId,
  });
  const attemptedEffects = [];
  try {
    const spreadsheet = runtime.spreadsheets.get(normalizedSpreadsheetId);
    if (!spreadsheet) throw new Error('Spreadsheet not found: ' + normalizedSpreadsheetId);
    if (!body || typeof body !== 'object' || Array.isArray(body)) {
      throw new Error('Sheets batchUpdate body must be an object');
    }
    if (!Array.isArray(body.requests)) throw new Error('Sheets batchUpdate requests must be an array');
    const shadow = createSpreadsheetShadow(spreadsheet);
    body.requests.forEach(request => {
      attemptedEffects.push(applySheetsRequest(shadow, request, spreadsheet.timeZone));
    });
    commitSpreadsheetShadow(shadow);
    runtime.audit.record('batches', {
      service: 'Sheets',
      method: 'Spreadsheets.batchUpdate',
      spreadsheetId: normalizedSpreadsheetId,
      committed: true,
      requestCount: body.requests.length,
      requests: requestBody.requests,
      effects: attemptedEffects,
    });
    return {
      spreadsheetId: normalizedSpreadsheetId,
      replies: body.requests.map(() => ({})),
    };
  } catch (error) {
    runtime.audit.record('batches', {
      service: 'Sheets',
      method: 'Spreadsheets.batchUpdate',
      spreadsheetId: normalizedSpreadsheetId,
      committed: false,
      requestCount: body && Array.isArray(body.requests) ? body.requests.length : 0,
      requests: requestBody && requestBody.requests,
      attemptedEffects,
      error: error && error.message ? String(error.message) : String(error),
    });
    throw error;
  }
}

function splitSheetRange(range) {
  const source = String(range || '');
  const separator = source.lastIndexOf('!');
  if (separator < 1) throw new Error('Advanced Sheets range must name a sheet');
  let sheetName = source.slice(0, separator);
  if (sheetName.startsWith("'") && sheetName.endsWith("'")) {
    sheetName = sheetName.slice(1, -1).replace(/''/g, "'");
  }
  return {
    sheetName,
    a1: source.slice(separator + 1),
  };
}

function sheetsValuesGet(runtime, spreadsheetId, range) {
  const normalizedSpreadsheetId = String(spreadsheetId);
  runtime.audit.record('services', {
    service: 'Sheets',
    method: 'Spreadsheets.Values.get',
    spreadsheetId: normalizedSpreadsheetId,
    range: String(range),
  });
  const spreadsheet = runtime.spreadsheets.get(normalizedSpreadsheetId);
  if (!spreadsheet) throw new Error('Spreadsheet not found: ' + normalizedSpreadsheetId);
  const parsed = splitSheetRange(range);
  const sheet = spreadsheet.getSheetByName(parsed.sheetName);
  if (!sheet) throw new Error('Advanced Sheets sheet not found: ' + parsed.sheetName);
  const values = sheet.getRange(parsed.a1).getValues();
  while (values.length && values.at(-1).every(value => value === '' || value == null)) {
    values.pop();
  }
  const trimmed = values.map(row => {
    const output = row.slice();
    while (output.length && (output.at(-1) === '' || output.at(-1) == null)) {
      output.pop();
    }
    return output;
  });
  return {
    range: String(range),
    majorDimension: 'ROWS',
    values: trimmed,
  };
}

class FakeTextOutput {
  constructor(runtime, content) {
    this.runtime = runtime;
    this.content = String(content);
    this.mimeType = '';
  }

  setMimeType(mimeType) {
    this.mimeType = mimeType;
    this.runtime.audit.record('services', { service: 'ContentService', method: 'setMimeType', mimeType });
    return this;
  }

  getContent() { return this.content; }
  getMimeType() { return this.mimeType; }
  toString() { return this.content; }
}

class FakeScriptProperties {
  constructor(runtime, values) {
    this.runtime = runtime;
    this.values = Object.assign({}, values);
  }

  getProperty(name) {
    const value = Object.prototype.hasOwnProperty.call(this.values, name) ? this.values[name] : null;
    this.runtime.audit.record('services', { service: 'ScriptProperties', method: 'getProperty', name, value });
    return value;
  }

  getProperties() {
    this.runtime.audit.record('services', { service: 'ScriptProperties', method: 'getProperties' });
    return Object.assign({}, this.values);
  }

  setProperty(name, value) {
    this.values[name] = String(value);
    this.runtime.audit.record('structural', { operation: 'setScriptProperty', name, value: String(value) });
    return this;
  }

  setProperties(values, deleteAllOthers = false) {
    if (deleteAllOthers) this.values = {};
    Object.entries(values || {}).forEach(([name, value]) => { this.values[name] = String(value); });
    this.runtime.audit.record('structural', {
      operation: 'setScriptProperties',
      values: Object.assign({}, values),
      deleteAllOthers: !!deleteAllOthers,
    });
    return this;
  }

  deleteProperty(name) {
    delete this.values[name];
    this.runtime.audit.record('structural', { operation: 'deleteScriptProperty', name });
    return this;
  }
}

class FakeScriptLock {
  constructor(runtime) {
    this.runtime = runtime;
    this.locked = false;
  }

  tryLock(timeoutMs) {
    const acquired = !this.locked;
    if (acquired) this.locked = true;
    this.runtime.audit.record('locks', { operation: 'tryLock', timeoutMs: Number(timeoutMs), acquired });
    return acquired;
  }

  waitLock(timeoutMs) {
    const acquired = !this.locked;
    this.runtime.audit.record('locks', { operation: 'waitLock', timeoutMs: Number(timeoutMs), acquired });
    if (!acquired) throw new Error('Lock timeout');
    this.locked = true;
  }

  hasLock() {
    this.runtime.audit.record('locks', { operation: 'hasLock', value: this.locked });
    return this.locked;
  }

  releaseLock() {
    const wasLocked = this.locked;
    this.locked = false;
    this.runtime.audit.record('locks', { operation: 'releaseLock', wasLocked });
  }

  forceHeld(value = true) {
    this.locked = !!value;
  }
}

class FakeFormChoice {
  constructor(value) { this.value = String(value); }
  getValue() { return this.value; }
}

class FakeFormItem {
  constructor(runtime, options = {}) {
    this.runtime = runtime;
    this.title = String(options.title || 'Product');
    this.type = options.type || 'MULTIPLE_CHOICE';
    this.helpText = String(options.helpText || '');
    this.choiceValues = (options.choices || []).map(String);
  }

  getTitle() { return this.title; }
  getType() { return this.type; }
  getHelpText() { return this.helpText; }
  getChoices() { return this.choiceValues.map(value => new FakeFormChoice(value)); }
  asMultipleChoiceItem() { return this; }

  setHelpText(value) {
    this.helpText = String(value);
    this.runtime.audit.record('form', { operation: 'setHelpText', title: this.title, value: this.helpText });
    return this;
  }

  setChoiceValues(values) {
    this.choiceValues = (values || []).map(String);
    this.runtime.audit.record('form', {
      operation: 'setChoiceValues',
      title: this.title,
      values: this.choiceValues.slice(),
    });
    return this;
  }

  snapshot() {
    return {
      title: this.title,
      type: this.type,
      helpText: this.helpText,
      choices: this.choiceValues.slice(),
    };
  }
}

class FakeForm {
  constructor(runtime, options = {}) {
    this.runtime = runtime;
    this.id = String(options.id || 'fake-form');
    this.destinationId = String(options.destinationId || '');
    this.description = String(options.description || '');
    this.items = (options.items || [{ title: 'Product', type: 'MULTIPLE_CHOICE' }])
      .map(item => item instanceof FakeFormItem ? item : new FakeFormItem(runtime, item));
  }

  getId() { return this.id; }
  getDestinationId() { return this.destinationId; }
  setDestinationId(value) { this.destinationId = String(value); return this; }
  getItems() { return this.items.slice(); }
  getDescription() { return this.description; }

  setDescription(value) {
    this.description = String(value);
    this.runtime.audit.record('form', { operation: 'setDescription', value: this.description });
    return this;
  }

  addMultipleChoiceItem() {
    const item = new FakeFormItem(this.runtime, { title: '', type: 'MULTIPLE_CHOICE' });
    this.items.push(item);
    this.runtime.audit.record('form', { operation: 'addMultipleChoiceItem' });
    return item;
  }

  snapshot() {
    return {
      id: this.id,
      destinationId: this.destinationId,
      description: this.description,
      items: this.items.map(item => item.snapshot()),
    };
  }
}

class FakeDataValidationBuilder {
  constructor(runtime) {
    this.runtime = runtime;
    this.rule = {};
  }

  requireValueInList(values, showDropdown) {
    this.rule.kind = 'VALUE_IN_LIST';
    this.rule.values = (values || []).map(cloneValue);
    this.rule.showDropdown = !!showDropdown;
    return this;
  }

  setAllowInvalid(value) { this.rule.allowInvalid = !!value; return this; }
  build() { return cloneValue(this.rule); }
}

function createClock(initialValue) {
  let nowMs = new Date(initialValue === undefined ? '2026-07-14T16:00:00-04:00' : initialValue).getTime();
  if (!Number.isFinite(nowMs)) throw new Error('Invalid initial clock value');
  return {
    now: () => nowMs,
    set(value) {
      const parsed = new Date(value).getTime();
      if (!Number.isFinite(parsed)) throw new Error('Invalid clock value');
      nowMs = parsed;
      return nowMs;
    },
    advance(milliseconds) {
      nowMs += Number(milliseconds);
      return nowMs;
    },
  };
}

function createFakeDate(clock) {
  return class FakeDate extends Date {
    constructor(...arguments_) {
      if (arguments_.length === 0) super(clock.now());
      else super(...arguments_);
    }

    static now() { return clock.now(); }
  };
}

function formatDateInTimeZone(dateValue, timeZone, format) {
  const date = new Date(dateValue);
  if (Number.isNaN(date.getTime())) return 'Invalid Date';
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: timeZone || 'UTC',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  });
  const parts = Object.fromEntries(formatter.formatToParts(date).map(part => [part.type, part.value]));
  return String(format)
    .replace(/yyyy/g, parts.year)
    .replace(/MM/g, parts.month)
    .replace(/dd/g, parts.day)
    .replace(/HH/g, parts.hour)
    .replace(/mm/g, parts.minute)
    .replace(/ss/g, parts.second)
    .replace(/SSS/g, String(date.getMilliseconds()).padStart(3, '0'));
}

function normalizeSheetSeed(value) {
  if (Array.isArray(value)) return { rows: value, options: {} };
  if (!value || typeof value !== 'object') return { rows: [], options: {} };
  const options = Object.assign({}, value);
  delete options.rows;
  return { rows: value.rows || [], options };
}

function createAppsScriptRuntime(options = {}) {
  const audit = new AuditRecorder();
  const runtime = { audit };
  const environment = options.environment || 'SANDBOX';
  const spreadsheetId = options.spreadsheetId || 'fake-spreadsheet';
  const formId = options.formId || 'fake-form';
  const clock = createClock(options.now);
  const FakeDate = createFakeDate(clock);
  const logs = [];

  const quietConsole = {
    log: (...args) => { logs.push({ level: 'log', args: cloneValue(args) }); if (options.passthroughConsole) console.log(...args); },
    error: (...args) => { logs.push({ level: 'error', args: cloneValue(args) }); if (options.passthroughConsole) console.error(...args); },
    warn: (...args) => { logs.push({ level: 'warn', args: cloneValue(args) }); if (options.passthroughConsole) console.warn(...args); },
  };

  const spreadsheet = new FakeSpreadsheet(
    runtime,
    spreadsheetId,
    options.spreadsheetTitle || 'Fake Spreadsheet',
    { timeZone: options.timeZone || 'America/New_York' },
  );
  runtime.spreadsheet = spreadsheet;
  const sheets = options.sheets || {};
  Object.entries(sheets).forEach(([name, value]) => {
    const seed = normalizeSheetSeed(value);
    spreadsheet.seedSheet(name, seed.rows, seed.options);
  });

  const properties = new FakeScriptProperties(runtime, Object.assign({
    ENVIRONMENT: environment,
    SPREADSHEET_ID: spreadsheetId,
    FORM_ID: formId,
  }, options.properties || {}));
  const lock = new FakeScriptLock(runtime);
  const forms = new Map();
  const form = new FakeForm(runtime, Object.assign({
    id: formId,
    destinationId: spreadsheetId,
  }, options.form || {}));
  forms.set(form.getId(), form);

  let uuidCounter = Number(options.uuidStart) || 0;
  const uuidQueue = (options.uuids || []).map(String);
  const spreadsheets = new Map([[spreadsheetId, spreadsheet]]);
  let activeSpreadsheet = spreadsheet;

  const context = {
    console: options.console || quietConsole,
    Date: FakeDate,
    Utilities: {
      DigestAlgorithm: { SHA_256: 'SHA_256' },
      Charset: { UTF_8: 'UTF_8' },
      getUuid() {
        const value = uuidQueue.length ? uuidQueue.shift() : deterministicUuid(++uuidCounter);
        audit.record('services', { service: 'Utilities', method: 'getUuid', value });
        return value;
      },
      computeDigest(algorithm, value) {
        if (algorithm !== 'SHA_256') throw new Error('Unsupported digest algorithm: ' + algorithm);
        const bytes = Array.from(crypto.createHash('sha256').update(String(value)).digest(), byte => (
          byte > 127 ? byte - 256 : byte
        ));
        audit.record('services', { service: 'Utilities', method: 'computeDigest', algorithm });
        return bytes;
      },
      base64EncodeWebSafe(value) {
        const encoded = Buffer.from(String(value), 'utf8')
          .toString('base64')
          .replace(/\+/g, '-')
          .replace(/\//g, '_');
        audit.record('services', { service: 'Utilities', method: 'base64EncodeWebSafe' });
        return encoded;
      },
      base64DecodeWebSafe(value) {
        const normalized = String(value)
          .replace(/-/g, '+')
          .replace(/_/g, '/');
        const bytes = Array.from(Buffer.from(normalized, 'base64'), byte => (
          byte > 127 ? byte - 256 : byte
        ));
        audit.record('services', { service: 'Utilities', method: 'base64DecodeWebSafe' });
        return bytes;
      },
      formatDate(date, timeZone, format) {
        const value = formatDateInTimeZone(date, timeZone, format);
        audit.record('services', { service: 'Utilities', method: 'formatDate', timeZone, format, value });
        return value;
      },
    },
    PropertiesService: {
      getScriptProperties() {
        audit.record('services', { service: 'PropertiesService', method: 'getScriptProperties' });
        return properties;
      },
    },
    SpreadsheetApp: {
      ProtectionType: { RANGE: 'RANGE' },
      openById(id) {
        audit.record('services', { service: 'SpreadsheetApp', method: 'openById', spreadsheetId: String(id) });
        const result = spreadsheets.get(String(id));
        if (!result) throw new Error('Spreadsheet not found: ' + id);
        return result;
      },
      getActiveSpreadsheet() {
        audit.record('services', { service: 'SpreadsheetApp', method: 'getActiveSpreadsheet' });
        return activeSpreadsheet;
      },
      getActive() {
        audit.record('services', { service: 'SpreadsheetApp', method: 'getActive' });
        return activeSpreadsheet;
      },
      newDataValidation() {
        audit.record('services', { service: 'SpreadsheetApp', method: 'newDataValidation' });
        return new FakeDataValidationBuilder(runtime);
      },
      flush() {
        audit.record('services', { service: 'SpreadsheetApp', method: 'flush' });
      },
    },
    Sheets: {
      Spreadsheets: {
        batchUpdate(body, id) {
          return sheetsBatchUpdate(runtime, body, id);
        },
        Values: {
          get(id, range) {
            return sheetsValuesGet(runtime, id, range);
          },
        },
      },
    },
    LockService: {
      getScriptLock() {
        audit.record('locks', { operation: 'getScriptLock' });
        return lock;
      },
      getDocumentLock() {
        audit.record('locks', { operation: 'getDocumentLock' });
        return lock;
      },
      getUserLock() {
        audit.record('locks', { operation: 'getUserLock' });
        return lock;
      },
    },
    ContentService: {
      MimeType: { JSON: 'application/json', TEXT: 'text/plain' },
      createTextOutput(content) {
        audit.record('services', { service: 'ContentService', method: 'createTextOutput' });
        return new FakeTextOutput(runtime, content);
      },
    },
    FormApp: {
      ItemType: { MULTIPLE_CHOICE: 'MULTIPLE_CHOICE' },
      openById(id) {
        audit.record('services', { service: 'FormApp', method: 'openById', formId: String(id) });
        const result = forms.get(String(id));
        if (!result) throw new Error('Form not found: ' + id);
        return result;
      },
    },
  };

  vm.createContext(context);

  Object.assign(runtime, {
    context,
    clock,
    logs,
    properties,
    lock,
    form,
    forms,
    spreadsheets,
    resetAudit() { audit.reset(); },
    auditTimeline() { return audit.timeline(); },
    getSheet(name) { return spreadsheet.getSheetByName(name); },
    peekSheet(name) { return spreadsheet.sheets.get(String(name)) || null; },
    seedSheet(name, rows, sheetOptions = {}) { return spreadsheet.seedSheet(name, rows, sheetOptions); },
    seedRecords(name, headers, records, sheetOptions = {}) {
      return spreadsheet.seedSheet(name, makeSheetRows(headers, records), sheetOptions);
    },
    seedConfig(configOptions = {}) {
      return spreadsheet.seedSheet('Config', buildConfigRows(Object.assign({ environment }, configOptions)));
    },
    addSpreadsheet(id, title = 'Fake Spreadsheet', spreadsheetOptions = {}) {
      const added = new FakeSpreadsheet(runtime, id, title, Object.assign({
        timeZone: spreadsheet.timeZone,
      }, spreadsheetOptions));
      spreadsheets.set(String(id), added);
      return added;
    },
    setActiveSpreadsheet(value) {
      activeSpreadsheet = value;
    },
    addForm(formOptions = {}) {
      const added = new FakeForm(runtime, formOptions);
      forms.set(added.getId(), added);
      return added;
    },
    queueUuids(...values) { uuidQueue.push(...values.flat().map(String)); },
    snapshot() {
      return {
        properties: Object.assign({}, properties.values),
        spreadsheet: spreadsheet.snapshot(),
        form: form.snapshot(),
      };
    },
    parseTextOutput(output) {
      const content = output && typeof output.getContent === 'function' ? output.getContent() : String(output);
      return JSON.parse(content);
    },
    evaluate(source, filename = 'apps-script.gs') {
      return vm.runInContext(String(source), context, { filename });
    },
    loadSource(source, loadOptions = {}) {
      const exportNames = loadOptions.exports || [];
      const suffix = exportNames.length
        ? `\nthis.__testApi = { ${exportNames.join(', ')} };`
        : '';
      vm.runInContext(String(source) + suffix, context, { filename: loadOptions.filename || 'apps-script.gs' });
      return exportNames.length ? context.__testApi : undefined;
    },
    installOneShotAfterCallFault(functionName, predicate = () => true, errorFactory) {
      const original = context[functionName];
      if (typeof original !== 'function') throw new Error('Global function not found: ' + functionName);
      let armed = true;
      const replacement = function (...args) {
        const result = original.apply(this, args);
        if (armed && predicate(args, result)) {
          armed = false;
          throw typeof errorFactory === 'function'
            ? errorFactory(args, result)
            : new Error(`INJECTED_FAULT_AFTER_${functionName}`);
        }
        return result;
      };
      context[functionName] = replacement;
      return () => { context[functionName] = original; };
    },
  });

  return runtime;
}

module.exports = {
  AUDIT_BUCKETS,
  AuditRecorder,
  FakeDataValidationBuilder,
  FakeForm,
  FakeFormItem,
  FakeProtection,
  FakeRange,
  FakeRangeList,
  FakeScriptLock,
  FakeSheet,
  FakeSpreadsheet,
  FakeTextFinder,
  FakeTextOutput,
  buildConfigRows,
  cloneMatrix,
  cloneValue,
  columnToLetters,
  createAppsScriptRuntime,
  deterministicUuid,
  lettersToColumn,
  makeRow,
  makeSheetRows,
  parseA1,
  rangeA1,
  sheetsSerialToDate,
};
