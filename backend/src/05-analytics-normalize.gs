function normalizeAnalyticsProducts_(snapshot, quality) {
  const products = [];
  const byProductUuid = {};
  const byProductId = {};
  const seenProductUuids = {};
  const seenProductIds = {};
  snapshot.purchaseRows.forEach(raw => {
    const row = raw.cells;
    if (!row.some(cell => cell !== '' && cell != null)) return;
    const headers = snapshot.purchaseHeaders;
    const productId = text_(value_(row, headers, 'Product ID'));
    const name = text_(value_(row, headers, 'Product name'));
    if (productId && seenProductIds[productId]) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        'Duplicate Product ID: ' + productId
      );
    }
    if (productId) seenProductIds[productId] = true;
    const rawUuid = text_(value_(row, headers, 'Product UUID'));
    if (rawUuid && !isUuid_(rawUuid)) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        'Invalid Product UUID for ' + productId
      );
    }
    const productUuid = rawUuid ? rawUuid.toLowerCase() : null;
    if (productUuid && seenProductUuids[productUuid]) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        'Duplicate Product UUID: ' + productUuid
      );
    }
    if (productUuid) seenProductUuids[productUuid] = true;
    if (!productId || !name) {
      quality.invalidUnreferencedPurchaseRowCount++;
      return;
    }

    const borrowed = strictBorrowed_(value_(row, headers, 'Borrowed'));
    if (!borrowed.known) quality.unknownBorrowedFlagCount++;
    const status = strictAnalyticsStatus_(value_(row, headers, 'Finished'));
    if (!status.known) quality.unknownStatusCount++;
    const purchaseDate = effectivePurchaseDate_(
      value_(row, headers, 'Date'),
      value_(row, headers, 'Created At')
    );
    if (purchaseDate.source === 'CREATED_AT_FALLBACK') {
      quality.estimatedPurchaseDateCount++;
    } else if (purchaseDate.source === 'UNKNOWN') {
      quality.unknownPurchaseDateCount++;
    }
    const cost = analyticsCost_(
      value_(row, headers, 'Pre-tax cost'),
      value_(row, headers, 'Post-tax'),
      value_(row, headers, 'Final cost'),
      snapshot.taxRate
    );
    if (cost.finalCostCents == null && borrowed.known) {
      if (borrowed.value) quality.unknownBorrowedCostCount++;
      else quality.unknownPersonalCostCount++;
    }
    const thc = normalizeThc_(value_(row, headers, 'THC%'));
    const thcRaw = thc.value;
    const thcQuality = thc.quality;
    if (thcQuality === 'AMBIGUOUS_SCALE') quality.ambiguousThcCount++;
    if (thcQuality === 'INVALID') quality.invalidThcCount++;
    const grams = optionalFiniteNumber_(value_(row, headers, 'Grams'));
    const validGrams = grams != null && grams > 0 ? grams : null;
    if (validGrams == null) quality.invalidGramsCount++;

    const product = {
      canonicalRow: raw.canonicalRow,
      productUuid: productUuid,
      productId: productId,
      name: name,
      type: text_(value_(row, headers, 'Type')).toUpperCase() || 'UNKNOWN',
      status: status.label,
      statusCode: status.value,
      borrowed: borrowed.known ? borrowed.value : null,
      purchaseDate: purchaseDate.date,
      purchaseDateSource: purchaseDate.source,
      preTaxCostCents: cost.preTaxCostCents,
      finalCostCents: cost.finalCostCents,
      costKnown: cost.finalCostCents != null,
      grams: validGrams,
      thcRaw: thcRaw,
      thcQuality: thcQuality,
      createdAtEpochMillis: timestampMillisOrNull_(
        value_(row, headers, 'Created At')
      ),
      purchaseFinishedAtEpochMillis: timestampMillisOrNull_(
        value_(row, headers, 'Finished At')
      )
    };
    products.push(product);
    byProductId[productId] = product;
    if (productUuid) byProductUuid[productUuid] = product;
  });
  products.byProductId = byProductId;
  products.byProductUuid = byProductUuid;
  return products;
}

function normalizeAnalyticsEvents_(snapshot, products, quality) {
  const events = [];
  const eventIds = {};
  snapshot.eventRows.forEach(raw => {
    const row = raw.cells;
    if (!row.some(cell => cell !== '' && cell != null)) return;
    const headers = snapshot.eventHeaders;
    const eventUuid = text_(value_(row, headers, 'Event UUID'));
    if (!eventUuid || !isUuid_(eventUuid)) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        'Canonical event row has an invalid Event UUID'
      );
    }
    const normalizedEventUuid = eventUuid.toLowerCase();
    if (eventIds[normalizedEventUuid]) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        'Duplicate Event UUID: ' + eventUuid
      );
    }
    eventIds[normalizedEventUuid] = true;
    const occurredAtEpochMillis = timestampMillisOrNull_(
      value_(row, headers, 'Timestamp')
    );
    if (occurredAtEpochMillis == null) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        'Canonical event row has an invalid timestamp'
      );
    }
    const quantity = optionalFiniteNumber_(value_(row, headers, 'Uses'));
    if (quantity == null || quantity <= 0) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        'Canonical event row has a nonpositive or invalid quantity'
      );
    }
    const rawProductUuid = text_(value_(row, headers, 'Product UUID'));
    if (rawProductUuid && !isUuid_(rawProductUuid)) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        'Canonical event row has an invalid Product UUID'
      );
    }
    const productUuid = rawProductUuid ? rawProductUuid.toLowerCase() : '';
    const productId = text_(value_(row, headers, 'Legacy Product ID'));
    const byUuid = productUuid ? products.byProductUuid[productUuid] : null;
    const byId = productId ? products.byProductId[productId] : null;
    if (byUuid && byId && byUuid !== byId) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        'Canonical event product identities conflict'
      );
    }
    const product = byUuid || byId;
    if (!product) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        'Canonical event references an unknown product'
      );
    }

    const localDate = Utilities.formatDate(
      new Date(occurredAtEpochMillis),
      CANN.TIME_ZONE,
      'yyyy-MM-dd'
    );
    const localTime = Utilities.formatDate(
      new Date(occurredAtEpochMillis),
      CANN.TIME_ZONE,
      'HH:mm:ss'
    );
    const storedLocalDate = text_(value_(row, headers, 'Local Date'));
    const storedLocalTime = text_(value_(row, headers, 'Local Time'));
    if (storedLocalDate && storedLocalDate !== localDate) {
      quality.localDateMismatchCount++;
    }
    if (storedLocalTime && storedLocalTime !== localTime) {
      quality.localTimeMismatchCount++;
    }
    const source = normalizeAnalyticsSource_(
      value_(row, headers, 'Source')
    );
    if (source === 'UNKNOWN') quality.unknownSourceCount++;
    events.push({
      canonicalRow: raw.canonicalRow,
      eventUuid: normalizedEventUuid,
      occurredAtEpochMillis: occurredAtEpochMillis,
      localDate: localDate,
      localTime: localTime,
      product: product,
      quantity: quantity,
      weightCode: text_(value_(row, headers, 'Weight Code')) || null,
      finished: truthy_(value_(row, headers, 'Finished')),
      source: source,
      finishProvenanceKind: 'EVENT',
      finishProvenanceId: normalizedEventUuid
    });
  });
  return events;
}

function resolveAnalyticsCorrections_(
  snapshot,
  products,
  canonicalEvents,
  extraCorrections
) {
  const correctionRecords = normalizeAnalyticsCorrectionRows_(
    snapshot,
    products
  ).concat(extraCorrections || []);
  const canonicalById = {};
  canonicalEvents.forEach(event => {
    canonicalById[event.eventUuid] = event;
  });
  const recordsById = {};
  const groupsByTarget = {};
  const reopenedProducts = {};
  correctionRecords.forEach(record => {
    const correctionId = text_(record.correctionId).toLowerCase();
    const targetEventId = text_(record.targetEventId).toLowerCase();
    if (!isUuid_(correctionId) || recordsById[correctionId]) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        'Duplicate or invalid Correction UUID: ' + correctionId
      );
    }
    const original = canonicalById[targetEventId];
    if (!original) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        'Correction references an unknown target event: ' + targetEventId
      );
    }
    if (
      record.operation === CANN.CORRECTION_OPERATIONS.REPLACE &&
      text_(record.replacement && record.replacement.weightCode) !==
        text_(original.weightCode)
    ) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        'Correction replacement changed immutable weight code'
      );
    }
    recordsById[correctionId] = record;
    const group = groupsByTarget[targetEventId] ||
      (groupsByTarget[targetEventId] = []);
    const expectedRevision = group.length + 1;
    const expectedHead = group.length
      ? group[group.length - 1].correctionId
      : '';
    if (
      record.revision !== expectedRevision ||
      text_(record.supersedesCorrectionId).toLowerCase() !== expectedHead
    ) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        'Correction chain is not a complete linear revision sequence for ' +
        targetEventId
      );
    }
    group.push(record);
  });

  const effectiveEvents = [];
  const historyEvents = [];
  const historyById = {};
  const stateByTarget = {};
  canonicalEvents.forEach(original => {
    const group = groupsByTarget[original.eventUuid] || [];
    let current = cloneAnalyticsEvent_(original);
    let pendingReopenBasis = null;
    const finishDispositions = {};
    let lifecycleState = 'ORIGINAL';
    group.forEach(record => {
      const before = current;
      if (record.operation === CANN.CORRECTION_OPERATIONS.REPLACE) {
        current = correctionReplacementEvent_(original, record);
        lifecycleState = 'CORRECTED';
      } else if (record.operation === CANN.CORRECTION_OPERATIONS.VOID) {
        current = null;
        lifecycleState = 'VOIDED';
      } else if (record.operation === CANN.CORRECTION_OPERATIONS.RESTORE) {
        current = cloneAnalyticsEvent_(original);
        if (current.finished) {
          current.finishProvenanceKind = 'CORRECTION';
          current.finishProvenanceId = record.correctionId;
        }
        lifecycleState = 'ORIGINAL';
      } else {
        throw analyticsError_(
          'DATA_INTEGRITY_ERROR',
          'Unsupported correction operation'
        );
      }
      const removesFinish = !!(
        before &&
        before.finished &&
        (
          !current ||
          !current.finished ||
          current.product.productId !== before.product.productId
        )
      );
      if (removesFinish) {
        finishDispositions[before.product.productId] = {
          targetEventId: original.eventUuid,
          correctionId: record.correctionId,
          reopenProduct: record.reopenProduct,
          decidedAtEpochMillis: record.createdAtEpochMillis,
          basisFinishedAtEpochMillis: before.occurredAtEpochMillis
        };
        pendingReopenBasis = record.reopenProduct ? null : before;
      }
      if (current && current.finished) {
        delete finishDispositions[current.product.productId];
        if (
          pendingReopenBasis &&
          current.product.productId ===
            pendingReopenBasis.product.productId
        ) {
          pendingReopenBasis = null;
        }
      }
    });
    const head = group.length ? group[group.length - 1] : null;
    const auditRevisions = group.map(correctionAuditRevision_);
    const historyEvent = current
      ? cloneAnalyticsEvent_(current)
      : cloneAnalyticsEvent_(original);
    historyEvent.lifecycleState = lifecycleState;
    historyEvent.correctionHeadId = head ? head.correctionId : null;
    historyEvent.revision = head ? head.revision : 0;
    historyEvent.auditRevisions = auditRevisions;
    historyEvent.reopenEligible = false;
    if (current) {
      current.lifecycleState = lifecycleState;
      current.correctionHeadId = historyEvent.correctionHeadId;
      current.revision = historyEvent.revision;
      current.auditRevisions = auditRevisions;
      current.reopenEligible = false;
      effectiveEvents.push(current);
    }
    historyEvents.push(historyEvent);
    historyById[historyEvent.eventUuid] = historyEvent;
    stateByTarget[original.eventUuid] = {
      original: original,
      current: current,
      lifecycleState: lifecycleState,
      headId: historyEvent.correctionHeadId,
      revision: historyEvent.revision,
      records: group,
      finishDispositions: finishDispositions,
      reopenBasis: current && current.finished
        ? (
          pendingReopenBasis &&
          pendingReopenBasis.product.productId !== current.product.productId
            ? null
            : current
        )
        : pendingReopenBasis,
      reopenEligible: false
    };
  });

  const finishDispositionsByProduct = {};
  Object.keys(stateByTarget).forEach(targetEventId => {
    const state = stateByTarget[targetEventId];
    Object.keys(state.finishDispositions).forEach(productId => {
      const dispositions = finishDispositionsByProduct[productId] ||
        (finishDispositionsByProduct[productId] = []);
      dispositions.push(state.finishDispositions[productId]);
    });
  });
  Object.keys(finishDispositionsByProduct).forEach(productId => {
    const dispositions = finishDispositionsByProduct[productId];
    if (
      !dispositions.length ||
      dispositions.some(disposition =>
        disposition.reopenProduct !== true
      )
    ) {
      return;
    }
    const latest = dispositions.slice().sort((left, right) =>
      left.decidedAtEpochMillis - right.decidedAtEpochMillis
    )[dispositions.length - 1];
    reopenedProducts[productId] = {
      correctionId: latest.correctionId,
      reopenedAtEpochMillis: latest.decidedAtEpochMillis,
      basisFinishedAtEpochMillis: latest.basisFinishedAtEpochMillis
    };
  });

  const finishedCounts = {};
  effectiveEvents.forEach(event => {
    if (!event.finished) return;
    const productId = event.product.productId;
    finishedCounts[productId] = (finishedCounts[productId] || 0) + 1;
  });
  Object.keys(stateByTarget).forEach(targetEventId => {
    const state = stateByTarget[targetEventId];
    const basis = state.reopenBasis;
    if (!basis || !basis.finished) return;
    const currentFinishedOnBasisProduct = !!(
      state.current &&
      state.current.finished &&
      state.current.product.productId === basis.product.productId
    );
    const otherFinishedCount =
      (finishedCounts[basis.product.productId] || 0) -
      (currentFinishedOnBasisProduct ? 1 : 0);
    const retainedFinishElsewhere = (
      finishDispositionsByProduct[basis.product.productId] || []
    ).some(disposition =>
      disposition.targetEventId !== targetEventId &&
      disposition.reopenProduct !== true
    );
    const finishProvenanceProved =
      (
        basis.finishProvenanceKind === 'EVENT' &&
        snapshot.finishProvenanceEventIds &&
        snapshot.finishProvenanceEventIds[basis.finishProvenanceId]
      ) ||
      (
        basis.finishProvenanceKind === 'CORRECTION' &&
        snapshot.finishProvenanceCorrectionIds &&
        snapshot.finishProvenanceCorrectionIds[basis.finishProvenanceId]
      );
    const eligible =
      basis.product.status === 'FINISHED' &&
      basis.product.purchaseFinishedAtEpochMillis ===
        basis.occurredAtEpochMillis &&
      otherFinishedCount === 0 &&
      !retainedFinishElsewhere &&
      finishProvenanceProved === true &&
      !finishActionEvidenceForProduct_(
        snapshot.finishActionProductIds || {},
        basis.product
      );
    state.reopenEligible = eligible;
    if (state.current) state.current.reopenEligible = eligible;
    const historyEvent = historyById[targetEventId];
    if (historyEvent) historyEvent.reopenEligible = eligible;
  });

  return {
    correctionRecords: correctionRecords,
    recordsById: recordsById,
    groupsByTarget: groupsByTarget,
    stateByTarget: stateByTarget,
    reopenedProducts: reopenedProducts,
    effectiveEvents: effectiveEvents,
    historyEvents: historyEvents
  };
}

function normalizeAnalyticsCorrectionRows_(snapshot, products) {
  if (!snapshot.correctionVersion) return [];
  const records = [];
  snapshot.correctionRows.forEach(raw => {
    const row = raw.cells;
    if (!row.some(cell => cell !== '' && cell != null)) return;
    const headers = snapshot.correctionHeaders;
    const correctionId = text_(
      value_(row, headers, 'Correction UUID')
    ).toLowerCase();
    const targetEventId = text_(
      value_(row, headers, 'Target Event UUID')
    ).toLowerCase();
    const supersedesCorrectionId = text_(
      value_(row, headers, 'Supersedes Correction UUID')
    ).toLowerCase();
    const revision = Number(value_(row, headers, 'Revision'));
    const operation = text_(
      value_(row, headers, 'Operation')
    ).toUpperCase();
    const reason = text_(value_(row, headers, 'Reason'));
    const requestId = text_(value_(row, headers, 'Request UUID'));
    const source = text_(value_(row, headers, 'Source'));
    const createdAtEpochMillis = timestampMillisOrNull_(
      value_(row, headers, 'Created At')
    );
    if (
      !isUuid_(correctionId) ||
      !isUuid_(targetEventId) ||
      (supersedesCorrectionId && !isUuid_(supersedesCorrectionId)) ||
      !Number.isInteger(revision) ||
      revision < 1 ||
      !Object.prototype.hasOwnProperty.call(
        CANN.CORRECTION_OPERATIONS,
        operation
      ) ||
      reason.length > CANN.MAX_CORRECTION_REASON_LENGTH ||
      !isUuid_(requestId) ||
      source !== 'ANDROID_V2' ||
      createdAtEpochMillis == null
    ) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        'Correction row has invalid immutable metadata'
      );
    }
    const replacementFields = [
      'Replacement Timestamp',
      'Replacement Local Date',
      'Replacement Local Time',
      'Replacement Product UUID',
      'Replacement Legacy Product ID',
      'Replacement Uses',
      'Replacement Weight Code',
      'Replacement Finished'
    ];
    const hasReplacementValues = replacementFields.some(name => {
      const rawValue = value_(row, headers, name);
      return rawValue !== '' && rawValue != null;
    });
    let replacement = null;
    if (operation === CANN.CORRECTION_OPERATIONS.REPLACE) {
      replacement = normalizeCorrectionReplacement_(
        row,
        headers,
        products
      );
    } else if (hasReplacementValues) {
      throw analyticsError_(
        'DATA_INTEGRITY_ERROR',
        operation + ' correction must not contain replacement values'
      );
    }
    records.push({
      canonicalRow: raw.canonicalRow,
      correctionId: correctionId,
      targetEventId: targetEventId,
      supersedesCorrectionId: supersedesCorrectionId,
      revision: revision,
      operation: operation,
      replacement: replacement,
      reopenProduct: truthy_(value_(row, headers, 'Reopen Product')),
      reason: reason || null,
      requestId: requestId.toLowerCase(),
      source: source,
      createdAtEpochMillis: createdAtEpochMillis
    });
  });
  return records;
}

function normalizeCorrectionReplacement_(row, headers, products) {
  const occurredAtEpochMillis = timestampMillisOrNull_(
    value_(row, headers, 'Replacement Timestamp')
  );
  const localDate = text_(value_(
    row,
    headers,
    'Replacement Local Date'
  ));
  const localTime = text_(value_(
    row,
    headers,
    'Replacement Local Time'
  ));
  const rawProductUuid = text_(value_(
    row,
    headers,
    'Replacement Product UUID'
  )).toLowerCase();
  const productId = text_(value_(
    row,
    headers,
    'Replacement Legacy Product ID'
  ));
  const quantity = optionalFiniteNumber_(value_(
    row,
    headers,
    'Replacement Uses'
  ));
  const finishedRaw = value_(row, headers, 'Replacement Finished');
  const byUuid = rawProductUuid
    ? products.byProductUuid[rawProductUuid]
    : null;
  const byId = productId ? products.byProductId[productId] : null;
  if (
    occurredAtEpochMillis == null ||
    !strictLocalDate_(localDate) ||
    !isClientTime_(localTime) ||
    !isUuid_(rawProductUuid) ||
    !productId ||
    !byUuid ||
    !byId ||
    byUuid !== byId ||
    quantity == null ||
    quantity <= 0 ||
    (finishedRaw === '' || finishedRaw == null)
  ) {
    throw analyticsError_(
      'DATA_INTEGRITY_ERROR',
      'REPLACE correction has an invalid replacement snapshot'
    );
  }
  const expectedDate = Utilities.formatDate(
    new Date(occurredAtEpochMillis),
    CANN.TIME_ZONE,
    'yyyy-MM-dd'
  );
  const expectedTime = Utilities.formatDate(
    new Date(occurredAtEpochMillis),
    CANN.TIME_ZONE,
    'HH:mm:ss'
  );
  const comparableLocalTime = /^\d{2}:\d{2}$/.test(localTime)
    ? localTime + ':00'
    : localTime;
  if (localDate !== expectedDate || comparableLocalTime !== expectedTime) {
    throw analyticsError_(
      'DATA_INTEGRITY_ERROR',
      'Correction replacement local date/time does not match its timestamp'
    );
  }
  return {
    occurredAtEpochMillis: occurredAtEpochMillis,
    localDate: localDate,
    localTime: localTime,
    product: byUuid,
    quantity: quantity,
    weightCode: text_(value_(
      row,
      headers,
      'Replacement Weight Code'
    )) || null,
    finished: truthy_(finishedRaw)
  };
}

function cloneAnalyticsEvent_(event) {
  return {
    canonicalRow: event.canonicalRow,
    eventUuid: event.eventUuid,
    occurredAtEpochMillis: event.occurredAtEpochMillis,
    localDate: event.localDate,
    localTime: event.localTime,
    product: event.product,
    quantity: event.quantity,
    weightCode: event.weightCode,
    finished: event.finished,
    source: event.source,
    finishProvenanceKind: event.finishProvenanceKind || null,
    finishProvenanceId: event.finishProvenanceId || null
  };
}

function correctionReplacementEvent_(original, record) {
  const replacement = record.replacement;
  return {
    canonicalRow: original.canonicalRow,
    eventUuid: original.eventUuid,
    occurredAtEpochMillis: replacement.occurredAtEpochMillis,
    localDate: replacement.localDate,
    localTime: replacement.localTime,
    product: replacement.product,
    quantity: replacement.quantity,
    weightCode: replacement.weightCode,
    finished: replacement.finished,
    source: original.source,
    finishProvenanceKind: replacement.finished
      ? 'CORRECTION'
      : null,
    finishProvenanceId: replacement.finished
      ? record.correctionId
      : null
  };
}

function correctionAuditRevision_(record) {
  const revision = {
    correctionId: record.correctionId,
    supersedesCorrectionId: record.supersedesCorrectionId || null,
    revision: record.revision,
    operation: record.operation,
    reopenProduct: record.reopenProduct,
    reason: record.reason || null,
    createdAtEpochMillis: record.createdAtEpochMillis
  };
  if (record.replacement) {
    revision.replacement = {
      occurredAtEpochMillis: record.replacement.occurredAtEpochMillis,
      localDate: record.replacement.localDate,
      localTime: record.replacement.localTime,
      productUuid: record.replacement.product.productUuid,
      productId: record.replacement.product.productId,
      productName: record.replacement.product.name,
      productType: record.replacement.product.type,
      quantity: record.replacement.quantity,
      weightCode: record.replacement.weightCode,
      finished: record.replacement.finished
    };
  }
  return revision;
}

function finishActionEvidenceForProduct_(evidence, product) {
  return !!(
    evidence[text_(product.productUuid).toLowerCase()] ||
    evidence[text_(product.productId)]
  );
}

function normalizeLedgerRows_(snapshot) {
  if (!snapshot.ledgerHeaders || !Object.keys(snapshot.ledgerHeaders).length) {
    return [];
  }
  const rows = [];
  snapshot.ledgerRows.forEach(raw => {
    const row = raw.cells;
    if (!row.some(cell => cell !== '' && cell != null)) return;
    const receivedAtEpochMillis = timestampMillisOrNull_(
      value_(row, snapshot.ledgerHeaders, 'Received At')
    );
    if (receivedAtEpochMillis == null) return;
    const duration = optionalFiniteNumber_(
      value_(row, snapshot.ledgerHeaders, 'Duration Ms')
    );
    rows.push({
      receivedAtEpochMillis: receivedAtEpochMillis,
      result: text_(value_(row, snapshot.ledgerHeaders, 'Result')).toUpperCase(),
      durationMs: duration != null && duration >= 0 ? duration : null
    });
  });
  return rows;
}

function strictBorrowed_(value) {
  const normalized = text_(value).toLowerCase();
  if (
    value === true ||
    value === 1 ||
    normalized === '1' ||
    normalized === 'yes'
  ) {
    return { known: true, value: true };
  }
  if (
    value === false ||
    value === 0 ||
    normalized === '0' ||
    normalized === 'no'
  ) {
    return { known: true, value: false };
  }
  return { known: false, value: null };
}

function strictAnalyticsStatus_(value) {
  if (
    typeof value !== 'boolean' &&
    value !== '' &&
    value != null &&
    /^[012]$/.test(text_(value))
  ) {
    const numeric = Number(value);
    return {
      known: true,
      value: numeric,
      label: numeric === CANN.STATUS.ACTIVE
        ? 'ACTIVE'
        : (numeric === CANN.STATUS.FINISHED ? 'FINISHED' : 'UNOPENED')
    };
  }
  return { known: false, value: null, label: 'UNKNOWN' };
}

function moneyToCents_(value) {
  if (value == null || value === '') return null;
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0) return null;
  return Math.round(number * 100);
}

function analyticsCost_(preTaxValue, postTaxValue, finalValue, taxRate) {
  const preTaxCostCents = moneyToCents_(preTaxValue);
  const recordedFinal = moneyToCents_(finalValue);
  if (recordedFinal != null) {
    return {
      preTaxCostCents: preTaxCostCents,
      finalCostCents: recordedFinal
    };
  }
  if (preTaxCostCents == null) {
    return { preTaxCostCents: null, finalCostCents: null };
  }
  const postTax = strictPostTax_(postTaxValue);
  if (!postTax.known) {
    return {
      preTaxCostCents: preTaxCostCents,
      finalCostCents: null
    };
  }
  const numericPreTax = Number(preTaxValue);
  return {
    preTaxCostCents: preTaxCostCents,
    finalCostCents: postTax.value
      ? preTaxCostCents
      : Math.round(numericPreTax * (1 + taxRate) * 100)
  };
}

function strictPostTax_(value) {
  if (
    value === true ||
    value === 1 ||
    text_(value).toLowerCase() === 'true' ||
    text_(value).toLowerCase() === 'yes'
  ) {
    return { known: true, value: true };
  }
  if (
    value === false ||
    value === 0 ||
    text_(value).toLowerCase() === 'false' ||
    text_(value).toLowerCase() === 'no'
  ) {
    return { known: true, value: false };
  }
  return { known: false, value: null };
}

function normalizeThc_(value) {
  if (value == null || value === '') {
    return { value: null, quality: 'UNKNOWN' };
  }
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0 || number > 100) {
    return { value: null, quality: 'INVALID' };
  }
  if (number === 0) return { value: null, quality: 'UNKNOWN' };
  // Google Sheets stores a percentage-formatted 75% cell as the number 0.75.
  // Normalize that storage representation before returning analytics to the app.
  if (number <= 1) {
    return { value: analyticsRounded_(number * 100), quality: 'RECORDED_PERCENT' };
  }
  return { value: number, quality: 'RECORDED_PERCENT' };
}

function effectivePurchaseDate_(recordedValue, createdAtValue) {
  const recordedDate = strictSheetDate_(recordedValue);
  if (recordedDate) {
    return { date: recordedDate, source: 'RECORDED' };
  }
  const createdAt = timestampMillisOrNull_(createdAtValue);
  if (createdAt != null) {
    return {
      date: Utilities.formatDate(
        new Date(createdAt),
        CANN.TIME_ZONE,
        'yyyy-MM-dd'
      ),
      source: 'CREATED_AT_FALLBACK'
    };
  }
  return { date: null, source: 'UNKNOWN' };
}

function strictSheetDate_(value) {
  if (
    value &&
    Object.prototype.toString.call(value) === '[object Date]' &&
    Number.isFinite(value.getTime())
  ) {
    return Utilities.formatDate(value, CANN.TIME_ZONE, 'yyyy-MM-dd');
  }
  return typeof value === 'string' ? strictLocalDate_(value.trim()) : null;
}

function strictLocalDate_(value) {
  const text = String(value || '');
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  ) {
    return null;
  }
  return text;
}

function localToday_() {
  return Utilities.formatDate(new Date(), CANN.TIME_ZONE, 'yyyy-MM-dd');
}

function localDateOrdinal_(dateText) {
  const parts = dateText.split('-').map(Number);
  return Math.floor(Date.UTC(parts[0], parts[1] - 1, parts[2]) / 86400000);
}

function localDateFromOrdinal_(ordinal) {
  const date = new Date(ordinal * 86400000);
  const year = date.getUTCFullYear();
  const month = String(date.getUTCMonth() + 1).padStart(2, '0');
  const day = String(date.getUTCDate()).padStart(2, '0');
  return year + '-' + month + '-' + day;
}

function shiftLocalDate_(dateText, days) {
  return localDateFromOrdinal_(localDateOrdinal_(dateText) + Number(days));
}

function localDateDayCount_(from, to) {
  return localDateOrdinal_(to) - localDateOrdinal_(from) + 1;
}

function dateInRange_(dateText, from, to) {
  return !!dateText && (!from || dateText >= from) && (!to || dateText <= to);
}

function normalizeAnalyticsSource_(value) {
  const source = text_(value).toUpperCase();
  if (source.indexOf('ANDROID_') === 0 || source === 'ANDROID') return 'ANDROID';
  if (source === 'FORM' || source === 'FORM_LEGACY') return 'FORM';
  if (source.indexOf('RECOVERY') >= 0 || source.indexOf('REPAIR') >= 0) {
    return 'RECOVERY';
  }
  return 'UNKNOWN';
}

function analyticsError_(code, message) {
  const error = new Error(message);
  error.analyticsCode = code;
  return error;
}

function analyticsErrorCode_(error) {
  if (error && error.analyticsCode) return String(error.analyticsCode);
  const message = conciseError_(error);
  const known = [
    'INVALID_QUERY', 'UNSUPPORTED_RESOURCE', 'UNSUPPORTED_ANALYTICS_VERSION',
    'ENVIRONMENT_MISMATCH', 'INVALID_CURSOR', 'CURSOR_STALE', 'BACKEND_BUSY',
    'DATA_INTEGRITY_ERROR', 'RANGE_TOO_LARGE', 'SCHEMA_MISMATCH',
    'CONFIGURATION_ERROR'
  ];
  const match = known.find(code => message.indexOf(code + ':') === 0);
  return match || 'INTERNAL_ERROR';
}

function analyticsErrorMessage_(error) {
  const message = conciseError_(error);
  return message.replace(/^[A-Z_]+:\s*/, '');
}

function analyticsFailure_(
  resource,
  environment,
  code,
  message,
  timing,
  analyticsVersion
) {
  const response = {
    success: false,
    apiVersion: CANN.API_VERSION,
    analyticsVersion: analyticsVersion || CANN.ANALYTICS_VERSION,
    resource: resource || undefined,
    environment: environment,
    errorCode: code,
    message: message
  };
  return finalizeAnalyticsResponse_(response, timing);
}

