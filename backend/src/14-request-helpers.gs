// -----------------------------------------------------------------------------
// Context, staging, validation, and response helpers
// -----------------------------------------------------------------------------

function emptyProductContext_(runtimeContext) {
  return {
    purchasesSheet: runtimeContext.sheets.purchases,
    headers: runtimeContext.headers.purchases,
    rows: [],
    byLegacyId: {},
    byProductUuid: {},
    byActionId: {}
  };
}

/**
 * Additive rollout entrypoint. Correction-aware source must already be
 * deployed. New writes remain disabled until the separate enable function
 * succeeds.
 */
function provisionConsumptionCorrections() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const runtimeConfig = readAndAssertRuntimeConfig_(ss, environment_());
    assertSupportedSchemaVersion_(runtimeConfig.values);
    if (
      !recoverableSyncApplyReady_(runtimeConfig.values) ||
      !interactionSummaryReady_(runtimeConfig.values)
    ) {
      throw new Error(
        'CORRECTION_PROVISION_BLOCKED: recoverable sync apply and the ' +
        'interaction-summary projection are required'
      );
    }
    if (text_(runtimeConfig.values[CANN.PENDING_APPLY_KEY])) {
      throw new Error(
        'CORRECTION_PROVISION_BLOCKED: recoverable apply is pending'
      );
    }
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
    ensureSheet_(ss, CANN.SHEETS.CORRECTIONS, CANN.CORRECTION_HEADERS);
    setConfigValue_(
      ss,
      CANN.CONSUMPTION_CORRECTION_WRITES_KEY,
      false,
      'Whether new consumption correction writes are accepted'
    );
    setConfigValue_(
      ss,
      CANN.CONSUMPTION_CORRECTION_VERSION_KEY,
      CANN.CONSUMPTION_CORRECTION_VERSION,
      'Append-only consumption correction schema version'
    );
    provisionRecoverableDateFormats_(ss);
    applySheetSafety_(ss);
    SpreadsheetApp.flush();
    const reconciliation = reconcileConsumptionCorrections_(ss);
    if (reconciliation.differences.length) {
      throw new Error(
        'CORRECTION_PROVISION_BLOCKED: ' +
        JSON.stringify(reconciliation.differences)
      );
    }
    return {
      correctionVersion: CANN.CONSUMPTION_CORRECTION_VERSION,
      writesEnabled: false,
      correctionRows: reconciliation.correctionRows,
      effectiveEvents: reconciliation.effectiveEvents,
      differences: reconciliation.differences
    };
  } finally {
    lock.releaseLock();
  }
}

function enableConsumptionCorrectionWrites() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const runtimeConfig = readAndAssertRuntimeConfig_(ss, environment_());
    const capability = consumptionCorrectionCapability_(
      runtimeConfig.values
    );
    if (
      capability.version !== CANN.CONSUMPTION_CORRECTION_VERSION ||
      !recoverableSyncApplyReady_(runtimeConfig.values)
    ) {
      throw new Error(
        'CORRECTION_ENABLE_BLOCKED: correction schema and recoverable apply ' +
        'must be ready'
      );
    }
    if (text_(runtimeConfig.values[CANN.PENDING_APPLY_KEY])) {
      throw new Error('CORRECTION_ENABLE_BLOCKED: recoverable apply is pending');
    }
    const recoverableReconciliation = reconcileRecoverableSyncApply_(ss);
    if (
      recoverableReconciliation.pendingApplyId ||
      recoverableReconciliation.incompleteJournalRows ||
      recoverableReconciliation.differences.length ||
      recoverableReconciliation.blockingDifferences.length
    ) {
      throw new Error(
        'CORRECTION_ENABLE_BLOCKED: recoverable sync apply reconciliation ' +
        'must be clean; ' + JSON.stringify(recoverableReconciliation)
      );
    }
    const reconciliation = reconcileConsumptionCorrections_(ss);
    if (reconciliation.differences.length) {
      throw new Error(
        'CORRECTION_ENABLE_BLOCKED: ' +
        JSON.stringify(reconciliation.differences)
      );
    }
    setConfigValue_(
      ss,
      CANN.CONSUMPTION_CORRECTION_WRITES_KEY,
      true,
      'Whether new consumption correction writes are accepted'
    );
    SpreadsheetApp.flush();
    return {
      correctionVersion: capability.version,
      writesEnabled: true,
      correctionRows: reconciliation.correctionRows,
      effectiveEvents: reconciliation.effectiveEvents
    };
  } finally {
    lock.releaseLock();
  }
}

function disableConsumptionCorrectionWrites() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    ensureConfigKey_(
      ss,
      CANN.CONSUMPTION_CORRECTION_WRITES_KEY,
      false,
      'Whether new consumption correction writes are accepted'
    );
    setConfigValue_(
      ss,
      CANN.CONSUMPTION_CORRECTION_WRITES_KEY,
      false,
      'Whether new consumption correction writes are accepted'
    );
    SpreadsheetApp.flush();
    return {
      correctionVersion: consumptionCorrectionCapability_(
        readAndAssertRuntimeConfig_(ss, environment_()).values
      ).version,
      writesEnabled: false
    };
  } finally {
    lock.releaseLock();
  }
}

function reconcileConsumptionCorrections() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    return reconcileConsumptionCorrections_(ss);
  } finally {
    lock.releaseLock();
  }
}

