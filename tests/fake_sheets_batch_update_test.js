'use strict';

const assert = require('node:assert/strict');
const {
  createAppsScriptRuntime,
} = require('./fake_apps_script_runtime');

const SHEETS_EPOCH_MS = Date.UTC(1899, 11, 30);
const DAY_MS = 24 * 60 * 60 * 1000;

function sheetsSerial(year, month, day, hour = 0, minute = 0, second = 0) {
  return (
    Date.UTC(year, month - 1, day, hour, minute, second) - SHEETS_EPOCH_MS
  ) / DAY_MS;
}

const runtime = createAppsScriptRuntime({
  environment: 'SANDBOX',
  spreadsheetId: 'atomic-sheet',
  timeZone: 'America/New_York',
  sheets: {
    Alpha: {
      rows: [['Name', 'When', 'Blank', 'Flag']],
      maxColumns: 4,
    },
    Beta: {
      rows: [
        ['Value', 'Other'],
        ['old', 'keep'],
      ],
      maxColumns: 2,
    },
  },
});

const spreadsheet = runtime.spreadsheet;
const alpha = runtime.peekSheet('Alpha');
const beta = runtime.peekSheet('Beta');
const alphaId = alpha.getSheetId();
const betaId = beta.getSheetId();

assert.equal(spreadsheet.getSheetById(alphaId), alpha);
assert.equal(spreadsheet.getSheetById(betaId), beta);
assert.equal(spreadsheet.getSheetById(999999), null);

const firstDateSerial = sheetsSerial(2025, 7, 17, 14, 30, 0);
const appendAlpha = {
  appendCells: {
    sheetId: alphaId,
    rows: [{
      values: [
        { userEnteredValue: { stringValue: 'added' } },
        {
          userEnteredValue: { numberValue: firstDateSerial },
          userEnteredFormat: {
            numberFormat: {
              type: 'DATE_TIME',
              pattern: 'yyyy-mm-dd hh:mm:ss',
            },
          },
        },
        {},
        { userEnteredValue: { boolValue: true } },
      ],
    }],
    fields: 'userEnteredValue,userEnteredFormat.numberFormat',
  },
};
const updateBeta = {
  updateCells: {
    range: {
      sheetId: betaId,
      startRowIndex: 1,
      endRowIndex: 2,
      startColumnIndex: 0,
      endColumnIndex: 2,
    },
    rows: [{
      values: [
        {},
        { userEnteredValue: { stringValue: 'changed' } },
      ],
    }],
    fields: 'userEnteredValue',
  },
};
const invalidUpdate = {
  updateCells: {
    start: {
      sheetId: 999999,
      rowIndex: 0,
      columnIndex: 0,
    },
    rows: [{
      values: [{ userEnteredValue: { stringValue: 'must not commit' } }],
    }],
    fields: 'userEnteredValue',
  },
};

runtime.resetAudit();
const beforeFailedBatch = JSON.stringify(runtime.snapshot());
assert.throws(
  () => runtime.context.Sheets.Spreadsheets.batchUpdate({
    requests: [appendAlpha, updateBeta, invalidUpdate],
  }, spreadsheet.getId()),
  /sheet not found/,
);
assert.equal(
  JSON.stringify(runtime.snapshot()),
  beforeFailedBatch,
  'A bad request must roll back every earlier request in the same batch',
);
assert.equal(runtime.peekSheet('Alpha'), alpha, 'Rollback must preserve sheet object identity');
assert.equal(runtime.peekSheet('Beta'), beta, 'Rollback must preserve sheet object identity');
assert.equal(runtime.audit.batches.length, 1);
assert.equal(runtime.audit.batches[0].committed, false);
assert.equal(runtime.audit.batches[0].attemptedEffects.length, 2);
assert.equal(runtime.audit.writes.length, 0);

runtime.resetAudit();
const batchResult = runtime.context.Sheets.Spreadsheets.batchUpdate({
  requests: [appendAlpha, updateBeta],
}, spreadsheet.getId());
assert.equal(batchResult.spreadsheetId, spreadsheet.getId());
assert.equal(batchResult.replies.length, 2);
assert.equal(runtime.peekSheet('Alpha'), alpha, 'Commit must preserve sheet object identity');
assert.equal(runtime.peekSheet('Beta'), beta, 'Commit must preserve sheet object identity');

const alphaRows = alpha.snapshot().rows;
assert.equal(alphaRows.length, 2);
assert.equal(alphaRows[1][0], 'added');
assert.equal(alphaRows[1][1] instanceof Date, true);
assert.equal(alphaRows[1][1].toISOString(), '2025-07-17T18:30:00.000Z');
assert.equal(alphaRows[1][2], '', 'Empty CellData must clear/write a blank value');
assert.equal(alphaRows[1][3], true);

const betaRows = beta.snapshot().rows;
assert.deepEqual(betaRows[1], ['', 'changed']);
assert.equal(runtime.audit.batches.length, 1);
assert.equal(runtime.audit.batches[0].committed, true);
assert.equal(runtime.audit.batches[0].requestCount, 2);
assert.deepEqual(
  runtime.audit.batches[0].effects.map(effect => effect.operation),
  ['appendCells', 'updateCells'],
);
assert.equal(runtime.audit.writes.length, 0);

// An update that changes only userEnteredValue must retain the existing date
// number format. That mirrors Sheets converting the new serial to a Date when
// SpreadsheetApp reads the cell on a later retry.
const secondDateSerial = sheetsSerial(2025, 7, 18, 9, 15, 0);
runtime.context.Sheets.Spreadsheets.batchUpdate({
  requests: [{
    updateCells: {
      start: {
        sheetId: alphaId,
        rowIndex: 1,
        columnIndex: 1,
      },
      rows: [{
        values: [{ userEnteredValue: { numberValue: secondDateSerial } }],
      }],
      fields: 'userEnteredValue',
    },
  }],
}, spreadsheet.getId());
const updatedDate = alpha.snapshot().rows[1][1];
assert.equal(updatedDate instanceof Date, true);
assert.equal(updatedDate.toISOString(), '2025-07-18T13:15:00.000Z');

console.log('fake Advanced Sheets batchUpdate transaction tests passed');
