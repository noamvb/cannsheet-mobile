function buildHistoryResponse_(
  snapshot,
  products,
  events,
  query,
  cursor,
  quality,
  timing
) {
  const filterHash = normalizedFilterHash_(query);
  const snapshotHash = analyticsHistorySnapshotHash_(snapshot);
  if (cursor) {
    if (cursor.filterHash !== filterHash) {
      throw analyticsError_(
        'INVALID_CURSOR',
        'Cursor filters do not match this request'
      );
    }
    if (cursor.snapshotHash !== snapshotHash) {
      throw analyticsError_(
        'CURSOR_STALE',
        'The captured history snapshot has changed'
      );
    }
  }

  let filtered = events.filter(event => historyEventMatches_(event, query));
  filtered.sort((left, right) => (
    right.occurredAtEpochMillis - left.occurredAtEpochMillis ||
    right.canonicalRow - left.canonicalRow
  ));
  if (cursor) {
    filtered = filtered.filter(event => (
      event.occurredAtEpochMillis < cursor.lastTimestampMillis ||
      (
        event.occurredAtEpochMillis === cursor.lastTimestampMillis &&
        event.canonicalRow < cursor.lastCanonicalRow
      )
    ));
  }
  const hasMore = filtered.length > query.limit;
  const pageEvents = filtered.slice(0, query.limit);
  let nextCursor = null;
  if (hasMore && pageEvents.length) {
    const last = pageEvents[pageEvents.length - 1];
    nextCursor = encodeHistoryCursor_({
      v: 2,
      resource: 'history',
      eventLastRow: snapshot.eventLastRow,
      purchaseLastRow: snapshot.purchaseLastRow,
      correctionLastRow: snapshot.correctionLastRow,
      snapshotHash: snapshotHash,
      filterHash: filterHash,
      lastTimestampMillis: last.occurredAtEpochMillis,
      lastCanonicalRow: last.canonicalRow
    });
  }

  const response = {
    success: true,
    apiVersion: CANN.API_VERSION,
    analyticsVersion: query.analyticsVersion,
    resource: 'history',
    environment: snapshot.environment,
    correctionVersion: snapshot.correctionVersion,
    correctionWritesEnabled: snapshot.correctionWritesEnabled,
    timeZone: CANN.TIME_ZONE,
    filters: historyFiltersResponse_(query),
    sort: 'TIMESTAMP_DESC_CANONICAL_ROW_DESC',
    events: pageEvents.map(event =>
      historyEventResponse_(event, query)
    ),
    page: {
      limit: query.limit,
      hasMore: hasMore,
      nextCursor: nextCursor
    },
    dataQuality: analyticsQualityResponse_(quality),
    sourceRevision: {
      dataVersion: snapshotHash,
      purchaseRowCount: products.length,
      eventRowCount: events.filter(
        event => event.lifecycleState !== 'VOIDED'
      ).length,
      rawEventRowCount: snapshot.eventRows.length,
      correctionRowCount: snapshot.correctionRows.length,
      effectiveEventCount: events.filter(
        event => event.lifecycleState !== 'VOIDED'
      ).length
    },
    generatedAtEpochMillis: Date.now(),
    serverDurationMs: 0
  };
  return finalizeAnalyticsResponse_(response, timing);
}

function historyEventMatches_(event, query) {
  if (!dateInRange_(event.localDate, query.from, query.to)) return false;
  if (
    query.productUuid &&
    event.product.productUuid !== query.productUuid
  ) {
    return false;
  }
  if (query.productId && event.product.productId !== query.productId) {
    return false;
  }
  if (query.type && event.product.type !== query.type) return false;
  if (query.q) {
    const searchable = (
      event.product.name + '\n' + event.product.productId
    ).toLowerCase();
    if (searchable.indexOf(query.q) < 0) return false;
  }
  return true;
}

function historyFiltersResponse_(query) {
  return {
    from: query.from,
    to: query.to,
    productUuid: query.productUuid || null,
    productId: query.productId || null,
    type: query.type || null,
    q: query.qDisplay || null,
    includeAudit: query.includeAudit === true
  };
}

function historyHasFilters_(query) {
  return !!(
    query.from ||
    query.to ||
    query.productUuid ||
    query.productId ||
    query.type ||
    query.q ||
    query.includeAudit
  );
}

function normalizedFilterHash_(query) {
  return sha256Hex_(JSON.stringify({
    from: query.from || null,
    to: query.to || null,
    productUuid: query.productUuid || null,
    productId: query.productId || null,
    type: query.type || null,
    q: query.q || null,
    includeAudit: query.includeAudit === true,
    analyticsVersion: query.analyticsVersion
  }));
}

function encodeHistoryCursor_(value) {
  const json = JSON.stringify(value);
  return Utilities.base64EncodeWebSafe(
    json,
    Utilities.Charset.UTF_8
  );
}

function decodeHistoryCursor_(cursorText) {
  try {
    if (
      !cursorText ||
      cursorText.length > CANN.HISTORY_MAX_CURSOR_LENGTH ||
      !/^[A-Za-z0-9_-]+={0,2}$/.test(cursorText)
    ) {
      throw new Error('Malformed cursor encoding');
    }
    const bytes = Utilities.base64DecodeWebSafe(cursorText);
    const json = bytes.map(byte => (
      String.fromCharCode((Number(byte) + 256) % 256)
    )).join('');
    const value = JSON.parse(json);
    const expectedFieldsV1 = [
      'v', 'resource', 'eventLastRow', 'purchaseLastRow', 'snapshotHash',
      'filterHash', 'lastTimestampMillis', 'lastCanonicalRow'
    ];
    const expectedFieldsV2 = expectedFieldsV1.concat([
      'correctionLastRow'
    ]);
    const expectedFields = value && value.v === 2
      ? expectedFieldsV2
      : expectedFieldsV1;
    if (
      !value ||
      typeof value !== 'object' ||
      Array.isArray(value) ||
      Object.keys(value).length !== expectedFields.length ||
      expectedFields.some(name => !Object.prototype.hasOwnProperty.call(value, name)) ||
      (value.v !== 1 && value.v !== 2) ||
      value.resource !== 'history' ||
      !Number.isInteger(value.eventLastRow) ||
      value.eventLastRow < 1 ||
      !Number.isInteger(value.purchaseLastRow) ||
      value.purchaseLastRow < 1 ||
      (
        value.v === 2 &&
        (
          !Number.isInteger(value.correctionLastRow) ||
          value.correctionLastRow < 1
        )
      ) ||
      !/^[0-9a-f]{64}$/.test(value.snapshotHash) ||
      !/^[0-9a-f]{64}$/.test(value.filterHash) ||
      !Number.isFinite(value.lastTimestampMillis) ||
      !Number.isInteger(value.lastCanonicalRow) ||
      value.lastCanonicalRow < 2
    ) {
      throw new Error('Malformed cursor payload');
    }
    return value;
  } catch (error) {
    throw analyticsError_('INVALID_CURSOR', 'Cursor is invalid');
  }
}

function analyticsHistorySnapshotHash_(snapshot) {
  if (!snapshot) return '';
  if (snapshot._memoHistorySnapshotHash) {
    return snapshot._memoHistorySnapshotHash;
  }
  const result = sha256Hex_(JSON.stringify({
    purchases: analyticsHashRows_(
      snapshot.purchaseRows,
      snapshot.purchaseHeaders,
      [
        'Product UUID', 'Product ID', 'Product name', 'Type',
        'Finished', 'Finished At'
      ]
    ),
    events: analyticsHashRows_(
      snapshot.eventRows,
      snapshot.eventHeaders,
      [
        'Event UUID', 'Timestamp', 'Local Date', 'Local Time', 'Product UUID',
        'Legacy Product ID', 'Uses', 'Weight Code', 'Finished', 'Source'
      ]
    ),
    corrections: analyticsHashRows_(
      snapshot.correctionRows,
      snapshot.correctionHeaders,
      snapshot.correctionVersion ? CANN.CORRECTION_HEADERS : []
    )
  }));
  snapshot._memoHistorySnapshotHash = result;
  return result;
}

function analyticsDataVersion_(snapshot, includeLedger) {
  if (!snapshot) return '';
  const key = includeLedger
    ? '_memoDataVersionWithLedger'
    : '_memoDataVersionWithoutLedger';
  if (snapshot[key]) return snapshot[key];

  const value = {
    purchases: analyticsHashRows_(
      snapshot.purchaseRows,
      snapshot.purchaseHeaders,
      CANN.PURCHASE_HEADERS
    ),
    events: analyticsHashRows_(
      snapshot.eventRows,
      snapshot.eventHeaders,
      CANN.EVENT_HEADERS
    ),
    corrections: analyticsHashRows_(
      snapshot.correctionRows,
      snapshot.correctionHeaders,
      snapshot.correctionVersion ? CANN.CORRECTION_HEADERS : []
    )
  };
  if (includeLedger) {
    value.ledger = analyticsHashRows_(
      snapshot.ledgerRows,
      snapshot.ledgerHeaders,
      CANN.LEDGER_HEADERS
    );
  }
  const result = sha256Hex_(JSON.stringify(value));
  snapshot[key] = result;
  return result;
}

function analyticsHashRows_(rawRows, headers, fields) {
  const safeHeaders = headers || {};
  const indices = (fields || []).map(field => {
    const idx = safeHeaders[field];
    return idx === undefined ? -1 : idx;
  });
  const numFields = indices.length;
  const rows = rawRows || [];
  const len = rows.length;
  const result = new Array(len);
  for (let i = 0; i < len; i++) {
    const raw = rows[i];
    const cells = (raw && raw.cells) || [];
    const vals = new Array(numFields);
    for (let j = 0; j < numFields; j++) {
      const col = indices[j];
      const cell = col >= 0 ? cells[col] : '';
      vals[j] = analyticsHashValue_(cell);
    }
    result[i] = {
      row: raw ? raw.canonicalRow : i + 2,
      values: vals
    };
  }
  return result;
}

function analyticsHashValue_(value) {
  if (
    value &&
    Object.prototype.toString.call(value) === '[object Date]' &&
    Number.isFinite(value.getTime())
  ) {
    return { type: 'date', value: value.getTime() };
  }
  if (value == null) return { type: 'null', value: null };
  if (typeof value === 'number') return { type: 'number', value: value };
  if (typeof value === 'boolean') return { type: 'boolean', value: value };
  return { type: 'string', value: String(value) };
}

function sha256Hex_(value) {
  return Utilities.computeDigest(
    Utilities.DigestAlgorithm.SHA_256,
    String(value),
    Utilities.Charset.UTF_8
  ).map(byte => (
    ('0' + ((Number(byte) + 256) % 256).toString(16)).slice(-2)
  )).join('');
}

function isoDayForLocalDate_(dateText) {
  const day = new Date(
    localDateOrdinal_(dateText) * 86400000
  ).getUTCDay();
  return day === 0 ? 7 : day;
}

function daysSinceEpochMillis_(epochMillis) {
  const localDate = Utilities.formatDate(
    new Date(epochMillis),
    CANN.TIME_ZONE,
    'yyyy-MM-dd'
  );
  return Math.max(
    0,
    localDateOrdinal_(localToday_()) - localDateOrdinal_(localDate)
  );
}

function analyticsRounded_(value) {
  return Math.round(Number(value) * 1000000) / 1000000;
}

function compareAnalyticsProducts_(left, right) {
  const leftName = left.name.toLowerCase();
  const rightName = right.name.toLowerCase();
  if (leftName < rightName) return -1;
  if (leftName > rightName) return 1;
  return left.productId < right.productId
    ? -1
    : (left.productId > right.productId ? 1 : 0);
}

function finalizeAnalyticsResponse_(response, timing) {
  const duration = Math.max(0, Date.now() - timing.startedAt);
  timing.serverDurationMs = duration;
  response.serverDurationMs = duration;
  return response;
}

function doPost(e) {
  const started = Date.now();
  const timing = newBackendTiming_('POST', started);
  let payload;
  try {
    if (!e || !e.postData || typeof e.postData.contents !== 'string') {
      recordBackendPhase_(timing, 'requestParse', started);
      return timedRequestFailure_(timing, 'INVALID_JSON', 'Missing JSON request body');
    }
    payload = JSON.parse(e.postData.contents);
  } catch (error) {
    recordBackendPhase_(timing, 'requestParse', started);
    return timedRequestFailure_(timing, 'INVALID_JSON', 'Malformed JSON request body');
  }
  recordBackendPhase_(timing, 'requestParse', started);
  if (!isRequestPayloadObject_(payload)) {
    return timedRequestFailure_(timing, 'INVALID_JSON', 'JSON request body must be an object');
  }

  const apiVersion = payload.apiVersion == null ? 1 : Number(payload.apiVersion);
  if (apiVersion !== 1 && apiVersion !== CANN.API_VERSION) {
    return timedRequestFailure_(timing, 'UNSUPPORTED_API_VERSION', 'Unsupported apiVersion', undefined, {
      apiVersion: apiVersion
    });
  }

  // Pure request checks happen before the lock and before any history is read.
  // We still validate the target environment before returning the failure so a
  // request can never bypass the production/sandbox isolation guard.
  const preflightStarted = Date.now();
  const preflight = preflightSyncRequest_(payload, apiVersion);
  recordBackendPhase_(timing, 'stagingValidation', preflightStarted);

  let environment;
  let requestContext;
  const environmentConfigStarted = Date.now();
  try {
    environment = environment_();
    const ss = spreadsheet_();
    const runtimeConfig = readAndAssertRuntimeConfig_(ss, environment);
    const mismatch = validateRequestEnvironment_(environment, payload.environment);
    recordBackendPhase_(timing, 'environmentConfig', environmentConfigStarted);
    if (mismatch) {
      return timedRequestFailure_(timing, 'ENVIRONMENT_MISMATCH', mismatch, environment, {
        apiVersion: apiVersion,
        requestId: text_(payload.requestId) || undefined
      });
    }
    if (preflight.failure) {
      return preflightFailureOutput_(payload, apiVersion, preflight.failure, environment, timing);
    }

    const runtimeSchemaStarted = Date.now();
    const runtime = assertRuntimeSchema_(ss, environment, runtimeConfig);
    recordBackendPhase_(timing, 'runtimeSchemaAssertion', runtimeSchemaStarted);
    requestContext = {
      apiVersion: apiVersion,
      payload: payload,
      purchases: preflight.purchases,
      consumptions: preflight.consumptions,
      finishActions: preflight.finishActions,
      consumptionCorrections: preflight.consumptionCorrections,
      clientState: preflight.clientState,
      environment: environment,
      ss: ss,
      sheets: runtime.sheets,
      headers: runtime.headers,
      config: runtime.config
    };
  } catch (error) {
    recordBackendPhase_(timing, 'environmentConfig', environmentConfigStarted);
    return timedRequestFailure_(timing, 'CONFIGURATION_ERROR', conciseError_(error), environment, {
      apiVersion: apiVersion,
      requestId: text_(payload.requestId) || undefined
    });
  }

  const lock = LockService.getScriptLock();
  const lockWaitStarted = Date.now();
  const lockAcquired = lock.tryLock(CANN.LOCK_TIMEOUT_MS);
  recordBackendPhase_(timing, 'lockWait', lockWaitStarted);
  if (!lockAcquired) {
    return timedRequestFailure_(timing, 'LOCK_TIMEOUT', 'The backend is busy; retry this request', environment, {
      apiVersion: apiVersion,
      requestId: text_(payload.requestId) || undefined
    });
  }

  let output;
  let outcome = 'success';
  let timingDetails;
  try {
    const response = apiVersion === 1
      ? handleLegacySyncLocked_(requestContext, timing)
      : handleV2SyncLocked_(requestContext, started, timing);
    response.environment = environment;
    if (apiVersion === CANN.API_VERSION) addServerTimingFields_(response, timing, environment);
    const responseRoutingStarted = Date.now();
    output = jsonOutput_(response);
    recordBackendPhase_(timing, 'responseRouting', responseRoutingStarted);
    outcome = response.success === false ? 'rejected' : 'success';
    timingDetails = {
      apiVersion: apiVersion,
      environment: environment,
      requestId: text_(payload.requestId) || undefined,
      purchaseCount: Array.isArray(payload.purchases) ? payload.purchases.length : undefined,
      consumptionCount: Array.isArray(payload.consumptions) ? payload.consumptions.length : undefined,
      finishActionCount: Array.isArray(payload.finishActions) ? payload.finishActions.length : undefined,
      correctionCount: Array.isArray(payload.consumptionCorrections)
        ? payload.consumptionCorrections.length
        : undefined,
      allAccepted: response.allAccepted
    };
  } catch (error) {
    console.error('POST failed: ' + conciseError_(error));
    if (apiVersion === CANN.API_VERSION) {
      const response = v2RequestFailure_(
        text_(payload.requestId),
        'INTERNAL_ERROR',
        conciseError_(error)
      );
      response.environment = environment;
      addServerTimingFields_(response, timing, environment);
      const responseRoutingStarted = Date.now();
      output = jsonOutput_(response);
      recordBackendPhase_(timing, 'responseRouting', responseRoutingStarted);
    } else {
      output = requestFailure_('INTERNAL_ERROR', conciseError_(error), environment, timing);
    }
    outcome = 'error';
    timingDetails = {
      apiVersion: apiVersion,
      environment: environment,
      requestId: text_(payload.requestId) || undefined,
      errorCode: 'INTERNAL_ERROR'
    };
  } finally {
    const lockReleaseStarted = Date.now();
    lock.releaseLock();
    recordBackendPhase_(timing, 'lockRelease', lockReleaseStarted);
  }
  logBackendTiming_(timing, outcome, timingDetails);
  return output;
}

function handleSync(payload) {
  // Retained for compatibility with manual callers and old trigger references.
  const environment = environment_();
  const ss = spreadsheet_();
  const runtimeConfig = readAndAssertRuntimeConfig_(ss, environment);
  const mismatch = validateRequestEnvironment_(environment, (payload || {}).environment);
  if (mismatch) return requestFailure_('ENVIRONMENT_MISMATCH', mismatch, environment);
  const preflight = preflightSyncRequest_(payload || {}, 1);
  if (preflight.failure) return jsonOutput_(legacyFailure_(preflight.failure.message));
  const runtime = assertRuntimeSchema_(ss, environment, runtimeConfig);
  const requestContext = {
    apiVersion: 1,
    payload: payload || {},
    purchases: preflight.purchases,
    consumptions: preflight.consumptions,
    finishActions: preflight.finishActions,
    consumptionCorrections: preflight.consumptionCorrections,
    clientState: preflight.clientState,
    environment: environment,
    ss: ss,
    sheets: runtime.sheets,
    headers: runtime.headers,
    config: runtime.config
  };
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) {
    return requestFailure_('LOCK_TIMEOUT', 'The backend is busy; retry this request');
  }
  try {
    const response = handleLegacySyncLocked_(requestContext);
    response.environment = environment;
    return jsonOutput_(response);
  } finally {
    lock.releaseLock();
  }
}

