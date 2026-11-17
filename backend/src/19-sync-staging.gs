function productContext_(ss, options) {
  const settings = options || {};
  const runtimeContext = settings.runtimeContext;
  const includeActionIds = settings.includeActionIds !== false;
  const sheet = runtimeContext ? runtimeContext.sheets.purchases : requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const headers = runtimeContext ? runtimeContext.headers.purchases : headerMap_(sheet);
  requireHeaders_(headers, ['Product ID', 'Product UUID', 'Client Action UUID', 'Type', 'Borrowed']);
  const lastRow = sheet.getLastRow();
  // Do not filter blank physical rows: the array index must remain the true
  // sheet row number for targeted writes.
  const rows = lastRow < 2 ? [] : sheet.getRange(2, 1, lastRow - 1, sheet.getLastColumn()).getValues();
  const byLegacyId = {}, byProductUuid = {}, byActionId = {};
  rows.forEach((row, index) => {
    const legacyProductId = text_(value_(row, headers, 'Product ID'));
    if (!legacyProductId) return;
    const product = {
      rowNumber: index + 2,
      legacyProductId: legacyProductId,
      productUuid: text_(value_(row, headers, 'Product UUID')),
      name: text_(value_(row, headers, 'Product name')),
      actionId: includeActionIds ? text_(value_(row, headers, 'Client Action UUID')) : '',
      type: text_(value_(row, headers, 'Type')),
      borrowed: truthy_(value_(row, headers, 'Borrowed')),
      status: allowedStatusOr_(value_(row, headers, 'Finished'), CANN.STATUS.ACTIVE),
      uses: finiteNumberOr_(value_(row, headers, 'Uses'), 0),
      mostRecentUse: value_(row, headers, 'Most recent use') || null,
      finishedAt: value_(row, headers, 'Finished At') || null,
      lastQuantity: optionalFiniteNumber_(value_(row, headers, 'Last quantity')),
      row: row
    };
    byLegacyId[legacyProductId] = product;
    if (product.productUuid) byProductUuid[product.productUuid] = product;
    if (product.actionId) byActionId[product.actionId] = product;
  });
  return { purchasesSheet: sheet, headers: headers, rows: rows, byLegacyId: byLegacyId, byProductUuid: byProductUuid, byActionId: byActionId };
}

function eventContext_(ss, runtimeContext, submittedEventIds) {
  const sheet = runtimeContext ? runtimeContext.sheets.events : requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const headers = runtimeContext ? runtimeContext.headers.events : headerMap_(sheet);
  requireHeaders_(headers, ['Event UUID']);
  const lastRow = sheet.getLastRow();
  const eventIds = new Set();
  if (lastRow < 2) return { sheet: sheet, eventIds: eventIds, lookupStrategy: 'EMPTY' };

  const uuidRange = sheet.getRange(2, headers['Event UUID'] + 1, lastRow - 1, 1);
  const submitted = submittedEventIds == null
    ? null
    : Array.from(new Set(submittedEventIds.map(text_).filter(eventId => eventId)));
  if (submitted && submitted.length <= CANN.EVENT_TEXT_FINDER_MAX_BATCH) {
    submitted.forEach(eventId => {
      const match = uuidRange.createTextFinder(eventId)
        .matchEntireCell(true)
        .useRegularExpression(false)
        .findNext();
      if (match) eventIds.add(eventId);
    });
    return { sheet: sheet, eventIds: eventIds, lookupStrategy: 'TEXT_FINDER' };
  }

  const values = uuidRange.getValues();
  values.forEach(row => {
    const eventId = text_(row[0]);
    if (eventId) eventIds.add(eventId);
  });
  return { sheet: sheet, eventIds: eventIds, lookupStrategy: 'COLUMN_SET' };
}

function stagePurchases_(items, context) {
  const accepted = [], rejected = [], byTempId = {};
  const reservedIds = Object.assign({}, context.byLegacyId);
  items.forEach(wrapper => {
    const item = wrapper.item;
    const type = text_(item.type).toUpperCase();
    const borrowed = truthy_(item.borrowed);
    if (wrapper.tempId && byTempId[wrapper.tempId]) {
      rejected.push({ item: item, code: 'INVALID_ITEM', message: 'Duplicate temporary purchase ID' });
      return;
    }
    const legacyProductId = nextDisplayId_(type, borrowed, reservedIds);
    const staged = {
      item: item,
      actionId: wrapper.actionId,
      tempId: wrapper.tempId,
      legacyProductId: legacyProductId,
      productUuid: Utilities.getUuid(),
      name: text_(item.name),
      type: type,
      borrowed: borrowed
    };
    reservedIds[legacyProductId] = staged;
    accepted.push(staged);
    if (wrapper.tempId) byTempId[wrapper.tempId] = staged;
  });
  return { accepted: accepted, rejected: rejected, byTempId: byTempId };
}

function nextDisplayId_(type, borrowed, reserved) {
  if (!type) throw new Error('Missing product type');
  const suffix = borrowed ? 'B' : '';
  const escaped = type.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pattern = new RegExp('^\\*' + escaped + '(\\d+)' + suffix + '$');
  let max = 0;
  Object.keys(reserved).forEach(id => {
    const match = id.match(pattern);
    if (match) max = Math.max(max, Number(match[1]));
  });
  let candidate;
  do { max++; candidate = '*' + type + max + suffix; } while (reserved[candidate]);
  return candidate;
}

function stageLegacyConsumption_(item, resolver, now) {
  const product = resolver[text_(item.productId)];
  return {
    eventId: Utilities.getUuid(),
    timestamp: now,
    localDate: text_(item.date) || formatDate_(now),
    localTime: text_(item.time) || formatTime_(now),
    productUuid: product.productUuid,
    legacyProductId: product.legacyProductId,
    uses: finiteNumberOr_(item.uses, 0),
    weightCode: '',
    isFinished: !!item.isFinished,
    source: 'ANDROID_V1',
    requestId: '',
    legacySourceSheet: '',
    legacySourceRow: ''
  };
}

function stageV2Consumption_(item, resolved, requestId) {
  const timestamp = parseClientDateTime_(item.date, item.time);
  return {
    eventId: text_(item.eventId),
    timestamp: timestamp,
    localDate: text_(item.date) || formatDate_(timestamp),
    localTime: text_(item.time) || formatTime_(timestamp),
    productUuid: resolved.productUuid,
    legacyProductId: resolved.legacyProductId,
    uses: finiteNumberOr_(item.uses, 0),
    weightCode: text_(item.weightCode),
    isFinished: !!item.isFinished,
    source: 'ANDROID_V2',
    requestId: requestId,
    legacySourceSheet: '',
    legacySourceRow: ''
  };
}

function stageV2FinishAction_(item, resolved) {
  return {
    actionId: text_(item.actionId),
    timestamp: parseClientDateTime_(item.date, item.time),
    productUuid: resolved.productUuid,
    legacyProductId: resolved.legacyProductId
  };
}

function stageV2ConsumptionCorrection_(
  item,
  targetState,
  products,
  requestId,
  createdAt
) {
  const operation = text_(item.operation).toUpperCase();
  let replacement = null;
  if (operation === CANN.CORRECTION_OPERATIONS.REPLACE) {
    const value = item.replacement;
    const productUuid = text_(value.productUuid).toLowerCase();
    const productId = text_(value.productId);
    const byUuid = products.byProductUuid[productUuid];
    const byId = products.byProductId[productId];
    if (!byUuid || !byId || byUuid !== byId) {
      return {
        error: itemError_(
          'UNKNOWN_PRODUCT',
          'Unknown or conflicting replacement product reference'
        )
      };
    }
    const timestamp = parseClientDateTimeStrict_(value.date, value.time);
    if (!timestamp) {
      return {
        error: itemError_(
          'INVALID_ITEM',
          'Correction replacement date or time is invalid in ' +
          CANN.TIME_ZONE
        )
      };
    }
    replacement = {
      occurredAtEpochMillis: timestamp.getTime(),
      localDate: text_(value.date),
      localTime: text_(value.time),
      product: byUuid,
      quantity: Number(value.uses),
      weightCode: text_(value.weightCode) || null,
      finished: value.finished === true
    };
    if (
      text_(replacement.weightCode) !==
      text_(targetState.original.weightCode)
    ) {
      return {
        error: itemError_(
          'IMMUTABLE_FIELD',
          'Weight code cannot be changed by a correction'
        )
      };
    }
  }
  const current = targetState.current;
  const finishBasis = targetState.reopenBasis;
  const proposed = operation === CANN.CORRECTION_OPERATIONS.REPLACE
    ? replacement
    : (
      operation === CANN.CORRECTION_OPERATIONS.RESTORE
        ? targetState.original
        : null
    );
  const removesCurrentFinish = !!(
    finishBasis &&
    finishBasis.finished &&
    (
      !proposed ||
      !proposed.finished ||
      proposed.product.productId !== finishBasis.product.productId
    )
  );
  const reopenProduct = item.reopenProduct === true;
  if (reopenProduct && !removesCurrentFinish) {
    return {
      error: itemError_(
        'REOPEN_NOT_APPLICABLE',
        'This correction does not remove the current finish marker'
      )
    };
  }
  if (reopenProduct && !targetState.reopenEligible) {
    return {
      error: itemError_(
        'REOPEN_NOT_SAFE',
        'The product finish cannot be traced only to this consumption entry'
      )
    };
  }
  const correctionId = text_(item.actionId).toLowerCase();
  const createdAtEpochMillis = createdAt.getTime();
  const record = {
    canonicalRow: Number.MAX_SAFE_INTEGER,
    correctionId: correctionId,
    targetEventId: text_(item.targetEventId).toLowerCase(),
    supersedesCorrectionId: text_(
      item.expectedCorrectionHeadId
    ).toLowerCase(),
    revision: targetState.revision + 1,
    operation: operation,
    replacement: replacement,
    reopenProduct: reopenProduct,
    reopenLegacyProductId: reopenProduct && finishBasis
      ? finishBasis.product.productId
      : null,
    reason: text_(item.reason) || null,
    requestId: requestId,
    source: 'ANDROID_V2',
    createdAtEpochMillis: createdAtEpochMillis,
    affectedProductIds: Array.from(new Set([
      current ? current.product.productId : '',
      proposed ? proposed.product.productId : '',
      reopenProduct && finishBasis ? finishBasis.product.productId : ''
    ].filter(Boolean)))
  };
  return { record: record };
}

function correctionRequestMatchesRecord_(item, record) {
  if (
    text_(item.targetEventId).toLowerCase() !== record.targetEventId ||
    text_(item.expectedCorrectionHeadId).toLowerCase() !==
      text_(record.supersedesCorrectionId).toLowerCase() ||
    text_(item.operation).toUpperCase() !== record.operation ||
    (item.reopenProduct === true) !== record.reopenProduct ||
    (text_(item.reason) || '') !== (record.reason || '')
  ) {
    return false;
  }
  if (record.operation !== CANN.CORRECTION_OPERATIONS.REPLACE) {
    return item.replacement == null;
  }
  const value = item.replacement || {};
  return text_(value.date) === record.replacement.localDate &&
    text_(value.time) === record.replacement.localTime &&
    text_(value.productUuid).toLowerCase() ===
      text_(record.replacement.product.productUuid).toLowerCase() &&
    text_(value.productId) === record.replacement.product.productId &&
    finiteNumber_(value.uses) === record.replacement.quantity &&
    text_(value.weightCode) === text_(record.replacement.weightCode) &&
    (value.finished === true) === record.replacement.finished;
}

function resolveProduct_(item, byLegacyId, byProductUuid) {
  const productUuid = text_(item.productUuid);
  const productId = text_(item.productId);
  if (productUuid && byProductUuid[productUuid]) return byProductUuid[productUuid];
  if (productId && byLegacyId[productId]) return byLegacyId[productId];
  return null;
}

function applyClientStateLocked_(ss, clientState, byLegacyId, byProductUuid) {
  const incomingUpdatedAt = clientState.loadedPenUpdatedAtEpochMillis;
  const acknowledged = {
    loadedPenUpdatedAtEpochMillis: incomingUpdatedAt,
    status: 'committed'
  };
  const productId = clientState.loadedPenProductId;
  const resolved = productId === null
    ? null
    : resolveProduct_({ productId: productId }, byLegacyId, byProductUuid);
  if (productId !== null && !resolved) {
    acknowledged.status = 'rejected';
    acknowledged.errorCode = 'UNKNOWN_PRODUCT';
    acknowledged.message = 'Unknown loaded pen product';
    return acknowledged;
  }
  const storedUpdatedAt = configValue_(ss, 'LOADED_PEN_UPDATED_AT', '');
  if (
    typeof storedUpdatedAt === 'number' &&
    Number.isFinite(storedUpdatedAt) &&
    storedUpdatedAt >= incomingUpdatedAt
  ) {
    acknowledged.status = 'stale';
    return acknowledged;
  }

  if (productId !== null) {
    setConfigValue_(ss, 'LOADED_PEN_PRODUCT_ID', resolved.legacyProductId, 'Loaded pen product ID');
    setConfigValue_(ss, 'LOADED_PEN_PRODUCT_UUID', resolved.productUuid, 'Loaded pen product UUID');
    setConfigValue_(ss, 'LOADED_PEN_PRODUCT_NAME', resolved.name, 'Loaded pen product name');
  } else {
    setConfigValue_(ss, 'LOADED_PEN_PRODUCT_ID', '', 'Loaded pen product ID');
    setConfigValue_(ss, 'LOADED_PEN_PRODUCT_UUID', '', 'Loaded pen product UUID');
    setConfigValue_(ss, 'LOADED_PEN_PRODUCT_NAME', '', 'Loaded pen product name');
  }
  setConfigValue_(
    ss,
    'LOADED_PEN_UPDATED_AT',
    incomingUpdatedAt,
    'Loaded pen state update time in epoch milliseconds'
  );
  return acknowledged;
}

function purchaseAck_(requestItem, product, status) {
  return {
    actionId: text_(requestItem.actionId),
    tempId: text_(requestItem.tempId),
    productUuid: product.productUuid,
    legacyProductId: product.legacyProductId,
    status: status
  };
}

function rejectedPurchase_(item, code, message) {
  return { actionId: text_(item.actionId) || null, errorCode: code, message: message };
}

function rejectedConsumption_(item, code, message) {
  return { eventId: text_(item.eventId) || null, errorCode: code, message: message };
}

function finishActionAck_(requestItem, product, status) {
  return {
    actionId: text_(requestItem.actionId),
    productUuid: text_(product.productUuid) || null,
    legacyProductId: text_(product.legacyProductId) || null,
    status: status
  };
}

function rejectedFinishAction_(item, code, message) {
  return {
    actionId: text_(item && item.actionId) || null,
    errorCode: code,
    message: message
  };
}

function consumptionCorrectionAck_(item, record, status) {
  return {
    actionId: text_(item.actionId).toLowerCase(),
    targetEventId: record.targetEventId,
    revision: record.revision,
    status: status
  };
}

function rejectedConsumptionCorrection_(
  item,
  code,
  message,
  currentHeadId
) {
  return {
    actionId: text_(item && item.actionId).toLowerCase() || null,
    targetEventId: text_(item && item.targetEventId).toLowerCase() || null,
    errorCode: code,
    message: message,
    currentHeadId: currentHeadId || null
  };
}

