function validateLegacyPurchase_(item, index) {
  if (!item || !text_(item.tempId) || !text_(item.date) || !text_(item.type) || !text_(item.name)) return itemError_('INVALID_ITEM', 'Invalid purchase at index ' + index);
  const purchaseNumbers = [item.cost, item.thc, item.grams];
  const borrowed = truthy_(item.borrowed);
  const validNumbers = borrowed
    ? purchaseNumbers.every(isOptionalFinitePurchaseNumber_)
    : purchaseNumbers.every(isRequiredFinitePurchaseNumber_);
  if (!validNumbers) return itemError_('INVALID_ITEM', 'Invalid purchase number at index ' + index);
  return null;
}

function validateV2Purchase_(item, index) {
  const legacy = validateLegacyPurchase_(item, index);
  if (legacy) return legacy;
  if (!isUuid_(text_(item.actionId))) return itemError_('INVALID_ITEM', 'Invalid purchase actionId at index ' + index);
  return null;
}

function validateLegacyConsumption_(item, index, resolver) {
  if (!item || !text_(item.productId) || !isFiniteNumber_(item.uses) || Number(item.uses) <= 0) return itemError_('INVALID_ITEM', 'Invalid consumption at index ' + index);
  if (!resolver[text_(item.productId)]) return itemError_('UNKNOWN_PRODUCT', 'Unknown product at consumption index ' + index);
  return null;
}

function validateV2Consumption_(item, index) {
  if (!item || !isUuid_(text_(item.eventId))) return itemError_('INVALID_ITEM', 'Invalid consumption eventId at index ' + index);
  if (!text_(item.productId) && !text_(item.productUuid)) return itemError_('INVALID_ITEM', 'Missing product reference at index ' + index);
  if (!isFiniteNumber_(item.uses) || Number(item.uses) <= 0) return itemError_('INVALID_ITEM', 'Invalid uses at index ' + index);
  return null;
}

function validateV2FinishAction_(item, index) {
  if (!item || !isUuid_(text_(item.actionId))) {
    return itemError_('INVALID_ITEM', 'Invalid finish actionId at index ' + index);
  }
  if (!isClientDate_(item.date) || !isClientTime_(item.time)) {
    return itemError_('INVALID_ITEM', 'Invalid finish date or time at index ' + index);
  }
  if (!text_(item.productId)) {
    return itemError_('INVALID_ITEM', 'Missing finish productId at index ' + index);
  }
  if (text_(item.productUuid) && !isUuid_(text_(item.productUuid))) {
    return itemError_('INVALID_ITEM', 'Invalid finish productUuid at index ' + index);
  }
  return null;
}

function validateClientState_(value) {
  if (!isPlainObject_(value)) {
    return itemError_('INVALID_ITEM', 'clientState must be an object');
  }
  if (
    typeof value.loadedPenUpdatedAtEpochMillis !== 'number' ||
    !Number.isFinite(value.loadedPenUpdatedAtEpochMillis) ||
    !Number.isInteger(value.loadedPenUpdatedAtEpochMillis) ||
    value.loadedPenUpdatedAtEpochMillis <= 0
  ) {
    return itemError_(
      'INVALID_ITEM',
      'loadedPenUpdatedAtEpochMillis must be a positive integer'
    );
  }
  if (
    value.loadedPenProductId !== null &&
    typeof value.loadedPenProductId !== 'string'
  ) {
    return itemError_(
      'INVALID_ITEM',
      'loadedPenProductId must be a string or null'
    );
  }
  return null;
}

function preflightSyncRequest_(payload, apiVersion) {
  const purchases = arrayOrEmpty_(payload && payload.purchases);
  const consumptions = arrayOrEmpty_(payload && payload.consumptions);
  const finishActions = arrayOrEmpty_(payload && payload.finishActions);
  const consumptionCorrections = arrayOrEmpty_(
    payload && payload.consumptionCorrections
  );
  const hasClientState = Object.prototype.hasOwnProperty.call(
    payload || {},
    'clientState'
  );
  const clientState = hasClientState ? payload.clientState : undefined;
  if (apiVersion === 1 && hasClientState) {
    return {
      purchases: purchases,
      consumptions: consumptions,
      finishActions: finishActions,
      consumptionCorrections: consumptionCorrections,
      clientState: clientState,
      failure: itemError_('INVALID_ITEM', 'clientState requires apiVersion 2')
    };
  }
  if (hasClientState) {
    const clientStateError = validateClientState_(clientState);
    if (clientStateError) {
      return {
        purchases: purchases,
        consumptions: consumptions,
        finishActions: finishActions,
        consumptionCorrections: consumptionCorrections,
        clientState: clientState,
        failure: clientStateError
      };
    }
  }
  if (apiVersion === 1 && Object.prototype.hasOwnProperty.call(payload || {}, 'finishActions')) {
    return {
      purchases: purchases,
      consumptions: consumptions,
      finishActions: finishActions,
      consumptionCorrections: consumptionCorrections,
      clientState: clientState,
      failure: itemError_('INVALID_ITEM', 'finishActions require apiVersion 2')
    };
  }
  if (
    apiVersion === 1 &&
    Object.prototype.hasOwnProperty.call(
      payload || {},
      'consumptionCorrections'
    )
  ) {
    return {
      purchases: purchases,
      consumptions: consumptions,
      finishActions: finishActions,
      consumptionCorrections: consumptionCorrections,
      clientState: clientState,
      failure: itemError_(
        'INVALID_ITEM',
        'consumptionCorrections require apiVersion 2'
      )
    };
  }
  const sizeError = validateBatchSize_(
    purchases,
    consumptions,
    finishActions,
    consumptionCorrections
  );
  if (sizeError) {
    return {
      purchases: purchases,
      consumptions: consumptions,
      finishActions: finishActions,
      consumptionCorrections: consumptionCorrections,
      clientState: clientState,
      failure: itemError_('INVALID_ITEM', sizeError)
    };
  }
  if (apiVersion === CANN.API_VERSION) {
    if (!isUuid_(text_(payload && payload.requestId))) {
      return {
        purchases: purchases,
        consumptions: consumptions,
        finishActions: finishActions,
        consumptionCorrections: consumptionCorrections,
        clientState: clientState,
        failure: itemError_('INVALID_ITEM', 'requestId must be a UUID')
      };
    }
    const duplicateActionId = firstDuplicate_(purchases
      .concat(finishActions)
      .concat(consumptionCorrections)
      .map(item => text_(item && item.actionId))
      .filter(Boolean));
    const duplicateEventId = firstDuplicate_(consumptions.map(item => text_(item && item.eventId)).filter(Boolean));
    if (duplicateActionId || duplicateEventId) {
      return {
        purchases: purchases,
        consumptions: consumptions,
        finishActions: finishActions,
        consumptionCorrections: consumptionCorrections,
        clientState: clientState,
        failure: itemError_('INVALID_ITEM', 'Duplicate UUID inside request')
      };
    }
  }
  return {
    purchases: purchases,
    consumptions: consumptions,
    finishActions: finishActions,
    consumptionCorrections: consumptionCorrections,
    clientState: clientState,
    failure: null
  };
}

function isRequestPayloadObject_(value) {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function isPlainObject_(value) {
  return !!value && Object.prototype.toString.call(value) === '[object Object]';
}

function validateBatchSize_(
  purchases,
  consumptions,
  finishActions,
  consumptionCorrections
) {
  if (consumptionCorrections == null) consumptionCorrections = [];
  if (
    !Array.isArray(purchases) ||
    !Array.isArray(consumptions) ||
    !Array.isArray(finishActions) ||
    !Array.isArray(consumptionCorrections)
  ) {
    return 'purchases, consumptions, finishActions, and ' +
      'consumptionCorrections must be arrays';
  }
  if (
    purchases.length +
    consumptions.length +
    finishActions.length +
    consumptionCorrections.length >
      CANN.MAX_BATCH_SIZE
  ) {
    return 'Batch exceeds maximum size';
  }
  return null;
}

function validateV2ConsumptionCorrection_(item, index) {
  if (!item || !isUuid_(text_(item.actionId))) {
    return itemError_(
      'INVALID_ITEM',
      'Invalid correction actionId at index ' + index
    );
  }
  if (!isUuid_(text_(item.targetEventId))) {
    return itemError_(
      'INVALID_ITEM',
      'Invalid correction targetEventId at index ' + index
    );
  }
  const expectedHead = text_(item.expectedCorrectionHeadId);
  if (expectedHead && !isUuid_(expectedHead)) {
    return itemError_(
      'INVALID_ITEM',
      'Invalid expected correction head at index ' + index
    );
  }
  const operation = text_(item.operation).toUpperCase();
  if (!Object.prototype.hasOwnProperty.call(
    CANN.CORRECTION_OPERATIONS,
    operation
  )) {
    return itemError_(
      'INVALID_ITEM',
      'Invalid correction operation at index ' + index
    );
  }
  if (
    item.reason != null &&
    typeof item.reason !== 'string'
  ) {
    return itemError_(
      'INVALID_ITEM',
      'Correction reason must be text at index ' + index
    );
  }
  if (text_(item.reason).length > CANN.MAX_CORRECTION_REASON_LENGTH) {
    return itemError_(
      'INVALID_ITEM',
      'Correction reason exceeds ' +
      CANN.MAX_CORRECTION_REASON_LENGTH + ' characters'
    );
  }
  if (
    item.reopenProduct != null &&
    typeof item.reopenProduct !== 'boolean'
  ) {
    return itemError_(
      'INVALID_ITEM',
      'Correction reopenProduct must be true or false at index ' + index
    );
  }
  if (operation !== CANN.CORRECTION_OPERATIONS.REPLACE) {
    if (item.replacement != null) {
      return itemError_(
        'INVALID_ITEM',
        operation + ' correction must not include replacement values'
      );
    }
    return null;
  }
  const replacement = item.replacement;
  if (!replacement || typeof replacement !== 'object' ||
      Array.isArray(replacement)) {
    return itemError_(
      'INVALID_ITEM',
      'REPLACE correction requires a replacement snapshot at index ' + index
    );
  }
  if (
    !isClientDate_(replacement.date) ||
    !isClientTime_(replacement.time) ||
    !isUuid_(text_(replacement.productUuid)) ||
    !text_(replacement.productId) ||
    !isFiniteNumber_(replacement.uses) ||
    Number(replacement.uses) <= 0 ||
    typeof replacement.finished !== 'boolean' ||
    typeof replacement.weightCode !== 'string'
  ) {
    return itemError_(
      'INVALID_ITEM',
      'Invalid REPLACE snapshot at index ' + index
    );
  }
  return null;
}

function itemError_(code, message) { return { code: code, message: message }; }

