// -----------------------------------------------------------------------------
// Google Form integration
// -----------------------------------------------------------------------------

function updateFormAndDescription() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    updateFormAndDescriptionLocked_(spreadsheet_());
  } finally {
    lock.releaseLock();
  }
}

function updateFormAndDescriptionLocked_(ss, runtimeContext) {
  const sheet = runtimeContext ? runtimeContext.sheets.purchases : requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const headers = runtimeContext ? runtimeContext.headers.purchases : headerMap_(sheet);
  requireHeaders_(headers, ['Product name', 'Finished', 'Product ID', 'Uses', 'Most recent use']);
  const available = readDataRows_(sheet).filter(row =>
    allowedStatusOr_(value_(row, headers, 'Finished'), CANN.STATUS.ACTIVE) === CANN.STATUS.ACTIVE &&
    text_(value_(row, headers, 'Product ID'))
  );
  const choices = available.map(row => text_(value_(row, headers, 'Product ID')));
  const description = available.map(row => {
    const id = text_(value_(row, headers, 'Product ID'));
    const name = text_(value_(row, headers, 'Product name'));
    const uses = finiteNumberOr_(value_(row, headers, 'Uses'), 0);
    const recent = value_(row, headers, 'Most recent use');
    return id + ' - ' + name + ' (Uses: ' + uses + ')' + (recent ? ' Last: ' + recent : '');
  }).join('\n');
  if (!runtimeContext) assertConfigEnvironment_(ss);
  const form = FormApp.openById(requiredScriptProperty_('FORM_ID'));
  const item = form.getItems().find(formItem => formItem.getTitle() === CANN.FORM_PRODUCT_QUESTION);
  if (!item) throw new Error('Form question not found: ' + CANN.FORM_PRODUCT_QUESTION);
  item.setHelpText(description || 'No active products to list.');
  if (choices.length) item.asMultipleChoiceItem().setChoiceValues(choices);
}

function clearFormQuestion() {
  const ss = spreadsheet_();
  assertConfigEnvironment_(ss);
  const form = FormApp.openById(requiredScriptProperty_('FORM_ID'));
  const item = form.getItems().find(formItem => formItem.getTitle() === CANN.FORM_PRODUCT_QUESTION);
  if (item) item.setHelpText('No active products to list.');
}

function onFormSubmit(e) {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    if (!e || !e.range) throw new Error('Missing form-submit event range');
    const ss = e.range.getSheet().getParent();
    assertConfiguredSpreadsheet_(ss);
    const environment = environment_();
    const runtimeConfig = readAndAssertRuntimeConfig_(ss, environment);
    const runtime = assertRuntimeSchema_(ss, environment, runtimeConfig);
    const runtimeContext = {
      environment: environment,
      ss: ss,
      sheets: runtime.sheets,
      headers: runtime.headers,
      config: runtime.config
    };
    refreshRecoverableSyncApplyStateLocked_(runtimeContext);
    if (recoverableSyncApplyReady_(runtimeContext.config)) {
      repairPendingSyncApplyLocked_(runtimeContext);
    }
    const responseSheet = e.range.getSheet();
    if (responseSheet.getName() !== CANN.SHEETS.RESPONSES) return;
    const rowNumber = e.range.getRow();
    const headers = runtimeContext.headers.responses;
    const values = responseSheet.getRange(rowNumber, 1, 1, responseSheet.getLastColumn()).getValues()[0];
    const legacyId = text_(value_(values, headers, 'Product'));
    const context = productContext_(ss, { runtimeContext: runtimeContext });
    const product = context.byLegacyId[legacyId];
    if (!product) {
      bumpMutationWatermark_();
      recordMigrationIssue_(ss, 'UNKNOWN_PRODUCT', responseSheet.getName(), rowNumber, legacyId, 'Form submission was preserved but could not be canonicalized');
      return;
    }
    const timestamp = dateOrNow_(value_(values, headers, 'Timestamp'));
    const expectedEventId = deterministicLegacyEventUuid_(
      ss.getId(),
      responseSheet.getName(),
      rowNumber
    );
    let eventId = expectedEventId;
    const recoveryReady =
      recoverableSyncApplyReady_(runtimeContext.config);
    if (recoveryReady) {
      const storedEventId = text_(value_(
        values,
        headers,
        CANN.COMPATIBILITY_EVENT_HEADER
      ));
      const storedRequestId = text_(value_(
        values,
        headers,
        CANN.COMPATIBILITY_REQUEST_HEADER
      ));
      if (storedRequestId) {
        throw new Error(
          'FORM_COMPATIBILITY_IDENTITY_CONFLICT: Request UUID must be blank'
        );
      }
      if (storedEventId) {
        if (!isUuid_(storedEventId) || storedEventId !== expectedEventId) {
          throw new Error(
            'FORM_COMPATIBILITY_IDENTITY_CONFLICT: unexpected Event UUID'
          );
        }
        eventId = storedEventId;
      }
    }
    if (eventContext_(ss, runtimeContext, [eventId]).eventIds.has(eventId)) {
      if (recoveryReady) {
        assertExistingFormCanonical_(
          runtimeContext,
          eventId,
          responseSheet,
          rowNumber,
          values
        );
      }
      return;
    }
    const event = {
      eventId: eventId,
      timestamp: timestamp,
      localDate: formatDate_(timestamp),
      localTime: formatTime_(timestamp),
      productUuid: product.productUuid,
      legacyProductId: product.legacyProductId,
      uses: finiteNumberOr_(value_(values, headers, 'Uses'), 0),
      weightCode: text_(value_(values, headers, 'Weight code')),
      isFinished: truthy_(value_(values, headers, 'Mark as Finished?')),
      source: 'FORM',
      requestId: '',
      legacySourceSheet: responseSheet.getName(),
      legacySourceRow: rowNumber,
      compatibilityRow: null
    };
    if (recoveryReady) {
      applyRecoverableSyncLocked_({
        runtimeContext: runtimeContext,
        productContext: context,
        stagedPurchases: [],
        stagedConsumptions: [event],
        kind: 'FORM',
        apiVersion: 0,
        requestId: '',
        response: null,
        formRefreshRequired: true,
        compatibilityExistingRow: rowNumber,
        ledger: null,
        timing: null
      });
    } else {
      bumpMutationWatermark_();
      appendConsumptionRows_(ss, [event], true, null, runtimeContext);
      applyProductEffects_(context, [event]);
      updateFormAndDescriptionLocked_(ss, runtimeContext);
      bumpMutationWatermark_();
      SpreadsheetApp.flush();
    }
  } finally {
    lock.releaseLock();
  }
}

function assertExistingFormCanonical_(
  runtimeContext,
  eventId,
  responseSheet,
  responseRowNumber,
  responseValues
) {
  const eventSheet = runtimeContext.sheets.events;
  const eventHeaders = runtimeContext.headers.events;
  const eventRowNumber = findUniqueExactCellRow_(
    eventSheet,
    eventHeaders['Event UUID'] + 1,
    eventId
  );
  const eventRow = eventSheet.getRange(
    eventRowNumber,
    1,
    1,
    eventSheet.getLastColumn()
  ).getValues()[0];
  const responseHeaders = runtimeContext.headers.responses;
  const matches =
    text_(value_(eventRow, eventHeaders, 'Legacy Source Sheet')) ===
      responseSheet.getName() &&
    Number(value_(eventRow, eventHeaders, 'Legacy Source Row')) ===
      responseRowNumber &&
    !text_(value_(eventRow, eventHeaders, 'Request UUID')) &&
    timestampMillisOrNull_(value_(eventRow, eventHeaders, 'Timestamp')) ===
      timestampMillisOrNull_(
        value_(responseValues, responseHeaders, 'Timestamp')
      ) &&
    text_(value_(eventRow, eventHeaders, 'Legacy Product ID')) ===
      text_(value_(responseValues, responseHeaders, 'Product')) &&
    Math.abs(
      finiteNumberOr_(value_(eventRow, eventHeaders, 'Uses'), NaN) -
      finiteNumberOr_(value_(responseValues, responseHeaders, 'Uses'), NaN)
    ) <= 1e-9;
  if (!matches) {
    throw new Error(
      'FORM_COMPATIBILITY_IDENTITY_CONFLICT: canonical lineage/data mismatch'
    );
  }
}

function onInventoryEdit(e) {
  if (!e || !e.range) return;
  const range = e.range;
  if (range.getSheet().getName() !== CANN.SHEETS.PURCHASES || range.getRow() === 1) return;
  const relevantColumns = [2, 3, 7, 8, 9];
  const first = range.getColumn();
  const last = range.getLastColumn();
  if (!relevantColumns.some(column => column >= first && column <= last)) return;
  const ss = range.getSheet().getParent();
  assertConfiguredSpreadsheet_(ss);
  assertConfigEnvironment_(ss);
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    bumpMutationWatermark_();
    updateFormAndDescriptionLocked_(ss);
    bumpMutationWatermark_();
  } finally { lock.releaseLock(); }
}

