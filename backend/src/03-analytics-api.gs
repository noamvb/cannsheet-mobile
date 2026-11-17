// -----------------------------------------------------------------------------
// Read-only analytics API
// -----------------------------------------------------------------------------

function analyticsResourceWasSupplied_(e) {
  return !!(
    e && (
      (e.parameter && Object.prototype.hasOwnProperty.call(e.parameter, 'resource')) ||
      (e.parameters && Object.prototype.hasOwnProperty.call(e.parameters, 'resource'))
    )
  );
}

function handleReadResource_(e) {
  const timing = newBackendTiming_('GET_ANALYTICS');
  let resource = '';
  let environment = '';
  let requestedAnalyticsVersion = CANN.ANALYTICS_VERSION;
  try {
    const query = analyticsQuery_(e);
    resource = text_(query.values.resource);
    if (
      resource !== 'insights' &&
      resource !== 'history' &&
      resource !== 'clientState'
    ) {
      throw analyticsError_('UNSUPPORTED_RESOURCE', 'Unsupported analytics resource');
    }

    requestedAnalyticsVersion = validateAnalyticsCommonQuery_(query.values);
    const recognized = resource === 'insights'
      ? ['resource', 'analyticsVersion', 'environment', 'from', 'to', 'scope']
      : [
        'resource', 'analyticsVersion', 'environment', 'from', 'to',
        'productUuid', 'productId', 'type', 'q', 'limit', 'cursor'
      ];
    if (resource === 'clientState') {
      recognized.splice(
        0,
        recognized.length,
        'resource',
        'analyticsVersion',
        'environment'
      );
    }
    if (resource === 'history' && requestedAnalyticsVersion >= 2) {
      recognized.push('includeAudit');
    }
    validateAnalyticsParameterNames_(query, recognized);

    environment = environment_();
    if (query.values.environment !== environment) {
      throw analyticsError_(
        'ENVIRONMENT_MISMATCH',
        'Client and server environments do not match'
      );
    }

    if (resource === 'clientState') {
      const response = buildClientStateResponse_(
        spreadsheet_(),
        environment,
        requestedAnalyticsVersion,
        timing
      );
      logBackendTiming_(timing, 'success', {
        environment: environment,
        resource: resource
      });
      return jsonOutput_(response);
    }

    const parsed = resource === 'insights'
      ? parseInsightsQuery_(query.values)
      : parseHistoryQuery_(query.values, requestedAnalyticsVersion);
    parsed.analyticsVersion = requestedAnalyticsVersion;

    const watermark = getMutationWatermark_();
    const cacheKey = buildAnalyticsCacheKey_(
      resource,
      environment,
      watermark,
      parsed,
      requestedAnalyticsVersion
    );

    if (cacheKey) {
      const cachedJson = getChunkedScriptCache_(cacheKey);
      if (cachedJson) {
        try {
          const cachedResponse = JSON.parse(cachedJson);
          cachedResponse.serverDurationMs = Math.max(
            0,
            Date.now() - timing.startedAt
          );
          logBackendTiming_(timing, 'cache_hit', {
            environment: environment,
            resource: resource,
            cacheKey: cacheKey
          });
          return jsonOutput_(cachedResponse);
        } catch (parseErr) {
          console.warn(
            'Failed to parse cached JSON: ' + conciseError_(parseErr)
          );
        }
      }
    }

    const cursor = resource === 'history' && parsed.cursor
      ? decodeHistoryCursor_(parsed.cursor)
      : null;
    const snapshot = readAnalyticsSnapshot_(
      resource,
      environment,
      cursor
    );
    const quality = newAnalyticsQuality_();
    const products = normalizeAnalyticsProducts_(snapshot, quality);
    const canonicalEvents = normalizeAnalyticsEvents_(
      snapshot,
      products,
      quality
    );
    const correctionResolution = resolveAnalyticsCorrections_(
      snapshot,
      products,
      canonicalEvents
    );
    const effectiveEvents = correctionResolution.effectiveEvents;
    const response = resource === 'insights'
      ? buildInsightsResponse_(
        snapshot,
        products,
        effectiveEvents,
        normalizeLedgerRows_(snapshot),
        parsed,
        quality,
        timing
      )
      : buildHistoryResponse_(
        snapshot,
        products,
        parsed.includeAudit
          ? correctionResolution.historyEvents
          : effectiveEvents,
        parsed,
        cursor,
        quality,
        timing
      );

    if (cacheKey && response && response.status !== 'error') {
      putChunkedScriptCache_(
        cacheKey,
        JSON.stringify(response),
        CANN.CACHE_DEFAULT_TTL_SECONDS
      );
    }

    logBackendTiming_(timing, 'success', {
      environment: environment,
      resource: resource,
      purchaseRows: snapshot.purchaseRows.length,
      eventRows: snapshot.eventRows.length,
      ledgerRows: snapshot.ledgerRows.length,
      rangeDays: response.range ? response.range.dayCount : undefined,
      pageSize: response.events ? response.events.length : undefined,
      hasFilters: resource === 'history' ? historyHasFilters_(parsed) : undefined
    });
    return jsonOutput_(response);
  } catch (error) {
    const code = analyticsErrorCode_(error);
    const message = analyticsErrorMessage_(error);
    const response = analyticsFailure_(
      resource || text_(
        e && e.parameter && e.parameter.resource
      ),
      environment || undefined,
      code,
      message,
      timing,
      requestedAnalyticsVersion
    );
    logBackendTiming_(timing, 'error', {
      environment: environment || undefined,
      resource: resource || undefined,
      errorCode: code
    });
    return jsonOutput_(response);
  }
}

function buildClientStateResponse_(ss, environment, analyticsVersion, timing) {
  const productId = text_(configValue_(ss, 'LOADED_PEN_PRODUCT_ID', ''));
  const productUuid = text_(configValue_(ss, 'LOADED_PEN_PRODUCT_UUID', ''));
  const productName = text_(configValue_(ss, 'LOADED_PEN_PRODUCT_NAME', ''));
  const updatedAt = optionalFiniteNumber_(
    configValue_(ss, 'LOADED_PEN_UPDATED_AT', '')
  );
  const loadedPen = productId && updatedAt != null
    ? {
      productId: productId,
      productUuid: productUuid,
      productName: productName,
      updatedAtEpochMillis: updatedAt
    }
    : null;
  return finalizeAnalyticsResponse_({
    success: true,
    apiVersion: CANN.API_VERSION,
    analyticsVersion: analyticsVersion,
    resource: 'clientState',
    environment: environment,
    loadedPen: loadedPen
  }, timing);
}

function analyticsQuery_(e) {
  const single = e && e.parameter && typeof e.parameter === 'object'
    ? e.parameter
    : {};
  const multi = e && e.parameters && typeof e.parameters === 'object'
    ? e.parameters
    : {};
  const names = {};
  Object.keys(single).forEach(name => { names[name] = true; });
  Object.keys(multi).forEach(name => { names[name] = true; });
  const values = {};
  const counts = {};
  Object.keys(names).forEach(name => {
    let supplied;
    if (Object.prototype.hasOwnProperty.call(multi, name)) {
      supplied = Array.isArray(multi[name]) ? multi[name] : [multi[name]];
    } else {
      supplied = [single[name]];
    }
    counts[name] = supplied.length;
    values[name] = text_(supplied.length ? supplied[0] : '');
  });
  return { values: values, counts: counts };
}

function validateAnalyticsParameterNames_(query, recognized) {
  const allowed = {};
  recognized.forEach(name => { allowed[name] = true; });
  Object.keys(query.values).forEach(name => {
    if (!allowed[name]) {
      throw analyticsError_('INVALID_QUERY', 'Unrecognized query parameter: ' + name);
    }
    if (query.counts[name] !== 1) {
      throw analyticsError_('INVALID_QUERY', 'Duplicate query parameter: ' + name);
    }
  });
}

function validateAnalyticsCommonQuery_(values) {
  const version = Number(values.analyticsVersion);
  if (
    !Number.isInteger(version) ||
    CANN.ANALYTICS_VERSIONS.indexOf(version) < 0
  ) {
    throw analyticsError_(
      'UNSUPPORTED_ANALYTICS_VERSION',
      'analyticsVersion must be one of ' +
      CANN.ANALYTICS_VERSIONS.join(', ')
    );
  }
  if (values.environment !== 'PRODUCTION' && values.environment !== 'SANDBOX') {
    throw analyticsError_(
      'INVALID_QUERY',
      'environment must be PRODUCTION or SANDBOX'
    );
  }
  return version;
}

function parseInsightsQuery_(values) {
  const fromText = text_(values.from);
  const toText = text_(values.to);
  const scopeText = text_(values.scope);
  if (!!fromText !== !!toText) {
    throw analyticsError_('INVALID_QUERY', 'from and to must be supplied together');
  }
  if (scopeText && scopeText !== 'all') {
    throw analyticsError_('INVALID_QUERY', 'scope must be all');
  }
  if (scopeText && (fromText || toText)) {
    throw analyticsError_('INVALID_QUERY', 'scope cannot be combined with from or to');
  }
  if (scopeText === 'all') {
    return {
      scope: 'ALL',
      from: null,
      to: localToday_()
    };
  }
  if (fromText) {
    const from = strictLocalDate_(fromText);
    const to = strictLocalDate_(toText);
    if (!from || !to) {
      throw analyticsError_('INVALID_QUERY', 'from and to must use YYYY-MM-DD');
    }
    const dayCount = localDateDayCount_(from, to);
    if (dayCount < 1) {
      throw analyticsError_('INVALID_QUERY', 'from must not be after to');
    }
    if (dayCount > CANN.ANALYTICS_MAX_RANGE_DAYS) {
      throw analyticsError_('RANGE_TOO_LARGE', 'Requested range is too large');
    }
    return { scope: 'CUSTOM', from: from, to: to };
  }
  const to = localToday_();
  return {
    scope: 'DEFAULT',
    from: shiftLocalDate_(to, -(CANN.ANALYTICS_DEFAULT_RANGE_DAYS - 1)),
    to: to
  };
}

function parseHistoryQuery_(values, analyticsVersion) {
  const from = text_(values.from);
  const to = text_(values.to);
  const parsedFrom = from ? strictLocalDate_(from) : null;
  const parsedTo = to ? strictLocalDate_(to) : null;
  if ((from && !parsedFrom) || (to && !parsedTo)) {
    throw analyticsError_('INVALID_QUERY', 'from and to must use YYYY-MM-DD');
  }
  if (parsedFrom && parsedTo) {
    const dayCount = localDateDayCount_(parsedFrom, parsedTo);
    if (dayCount < 1) {
      throw analyticsError_('INVALID_QUERY', 'from must not be after to');
    }
    if (dayCount > CANN.ANALYTICS_MAX_RANGE_DAYS) {
      throw analyticsError_('RANGE_TOO_LARGE', 'Requested range is too large');
    }
  }

  const productUuid = text_(values.productUuid);
  const productId = text_(values.productId);
  if (productUuid && productId) {
    throw analyticsError_(
      'INVALID_QUERY',
      'productUuid and productId are mutually exclusive'
    );
  }
  if (productUuid && !isUuid_(productUuid)) {
    throw analyticsError_('INVALID_QUERY', 'productUuid must be a UUID');
  }
  const queryText = text_(values.q);
  if (queryText.length > CANN.HISTORY_MAX_QUERY_LENGTH) {
    throw analyticsError_('INVALID_QUERY', 'q is too long');
  }
  const rawLimit = text_(values.limit);
  if (rawLimit && !/^\d+$/.test(rawLimit)) {
    throw analyticsError_('INVALID_QUERY', 'limit must be an integer');
  }
  const limit = rawLimit ? Number(rawLimit) : CANN.HISTORY_DEFAULT_LIMIT;
  if (
    !Number.isInteger(limit) ||
    limit < 1 ||
    limit > CANN.HISTORY_MAX_LIMIT
  ) {
    throw analyticsError_(
      'INVALID_QUERY',
      'limit must be between 1 and ' + CANN.HISTORY_MAX_LIMIT
    );
  }
  const cursor = text_(values.cursor);
  if (cursor.length > CANN.HISTORY_MAX_CURSOR_LENGTH) {
    throw analyticsError_('INVALID_CURSOR', 'cursor is too long');
  }
  const includeAuditText = text_(values.includeAudit).toLowerCase();
  if (
    includeAuditText &&
    includeAuditText !== 'true' &&
    includeAuditText !== 'false'
  ) {
    throw analyticsError_(
      'INVALID_QUERY',
      'includeAudit must be true or false'
    );
  }
  if (includeAuditText && analyticsVersion < 2) {
    throw analyticsError_(
      'INVALID_QUERY',
      'includeAudit requires analyticsVersion 2'
    );
  }
  return {
    from: parsedFrom,
    to: parsedTo,
    productUuid: productUuid ? productUuid.toLowerCase() : '',
    productId: productId,
    type: text_(values.type).toUpperCase(),
    q: queryText.toLowerCase(),
    qDisplay: queryText,
    limit: limit,
    cursor: cursor,
    includeAudit: includeAuditText === 'true'
  };
}

function getMutationWatermark_() {
  try {
    if (typeof CacheService !== 'undefined' && CacheService.getScriptCache) {
      const cache = CacheService.getScriptCache();
      if (cache) {
        const cached = cache.get(CANN.MUTATION_WATERMARK_PROPERTY);
        if (cached) return cached;
      }
    }
    if (
      typeof PropertiesService === 'undefined' ||
      !PropertiesService.getScriptProperties
    ) {
      return '0';
    }
    const props = PropertiesService.getScriptProperties();
    const watermark = props.getProperty(CANN.MUTATION_WATERMARK_PROPERTY);
    if (watermark) {
      if (typeof CacheService !== 'undefined' && CacheService.getScriptCache) {
        const cache = CacheService.getScriptCache();
        if (cache) cache.put(CANN.MUTATION_WATERMARK_PROPERTY, watermark, 21600);
      }
      return watermark;
    }
    return '0';
  } catch (error) {
    console.warn('Failed to read MUTATION_WATERMARK: ' + conciseError_(error));
    return '0';
  }
}

function bumpMutationWatermark_() {
  const newWatermark = typeof Utilities !== 'undefined' && Utilities.getUuid
    ? Utilities.getUuid()
    : String(Date.now());
  let persisted = false;
  // The durable write comes first. CacheService entries expire after at most
  // six hours and may be evicted sooner, and getMutationWatermark_ falls back
  // to this property; without it a lost cache entry silently reverts the
  // watermark to '0' and resurrects pre-mutation cached responses.
  try {
    if (
      typeof PropertiesService !== 'undefined' &&
      PropertiesService.getScriptProperties
    ) {
      const props = PropertiesService.getScriptProperties();
      if (props) {
        props.setProperty(CANN.MUTATION_WATERMARK_PROPERTY, newWatermark);
        persisted = true;
      }
    }
  } catch (error) {
    console.warn(
      'Failed to persist MUTATION_WATERMARK: ' + conciseError_(error)
    );
  }
  try {
    if (typeof CacheService !== 'undefined' && CacheService.getScriptCache) {
      const cache = CacheService.getScriptCache();
      if (cache) {
        cache.put(CANN.MUTATION_WATERMARK_PROPERTY, newWatermark, 21600);
        persisted = true;
      }
    }
  } catch (error) {
    console.warn('Failed to cache MUTATION_WATERMARK: ' + conciseError_(error));
  }
  return persisted ? newWatermark : null;
}

function buildAnalyticsCacheKey_(resource, env, watermark, query, version) {
  const envPrefix = text_(env).slice(0, 4).toLowerCase();
  const wm = text_(watermark) || '0';
  if (resource === 'insights') {
    const scope = query.scope || 'DEFAULT';
    const from = query.from || '_';
    const to = query.to || localToday_();
    return `cann:v${version}:${envPrefix}:w:${wm}:ins:${scope}:${from}:${to}`;
  }
  if (resource === 'history') {
    const filterSummary = [
      query.from || '',
      query.to || '',
      query.productUuid || '',
      query.productId || '',
      query.type || '',
      query.q || '',
      query.includeAudit ? '1' : '0'
    ].join('|');
    const fh = sha256Hex_(filterSummary).slice(0, 16);
    const curHash = query.cursor ? sha256Hex_(query.cursor).slice(0, 16) : 'p1';
    return `cann:v${version}:${envPrefix}:w:${wm}:hist:${fh}:${query.limit}:${curHash}`;
  }
  return null;
}

function putChunkedScriptCache_(baseKey, jsonText, ttlSeconds) {
  try {
    if (!jsonText || typeof jsonText !== 'string') return false;
    if (typeof CacheService === 'undefined' || !CacheService.getScriptCache) {
      return false;
    }
    const cache = CacheService.getScriptCache();
    if (!cache) return false;

    const ttl = ttlSeconds || CANN.CACHE_DEFAULT_TTL_SECONDS;
    const chunkSize = CANN.CACHE_CHUNK_SIZE;
    const len = jsonText.length;
    const chunkCount = Math.ceil(len / chunkSize);

    if (chunkCount > CANN.CACHE_MAX_CHUNKS) {
      return false;
    }

    const batch = {};
    batch[baseKey + ':m'] = JSON.stringify({
      v: 1,
      chunks: chunkCount,
      len: len,
      ts: Date.now()
    });

    for (let i = 0; i < chunkCount; i++) {
      batch[baseKey + ':c:' + i] = jsonText.substring(
        i * chunkSize,
        (i + 1) * chunkSize
      );
    }

    cache.putAll(batch, ttl);
    return true;
  } catch (error) {
    console.warn('Cache write failed: ' + conciseError_(error));
    return false;
  }
}

function getChunkedScriptCache_(baseKey) {
  try {
    if (typeof CacheService === 'undefined' || !CacheService.getScriptCache) {
      return null;
    }
    const cache = CacheService.getScriptCache();
    if (!cache) return null;

    const manifestKey = baseKey + ':m';
    const manifestJson = cache.get(manifestKey);
    if (!manifestJson) return null;

    let manifest;
    try {
      manifest = JSON.parse(manifestJson);
    } catch (e) {
      return null;
    }
    if (
      !manifest ||
      typeof manifest.chunks !== 'number' ||
      manifest.chunks < 1
    ) {
      return null;
    }

    const chunkKeys = [];
    for (let i = 0; i < manifest.chunks; i++) {
      chunkKeys.push(baseKey + ':c:' + i);
    }

    const chunks = cache.getAll(chunkKeys);
    if (!chunks) return null;

    let reassembled = '';
    for (let i = 0; i < manifest.chunks; i++) {
      const part = chunks[baseKey + ':c:' + i];
      if (part == null) {
        return null;
      }
      reassembled += part;
    }

    if (reassembled.length !== manifest.len) {
      return null;
    }

    return reassembled;
  } catch (error) {
    console.warn('Cache read failed: ' + conciseError_(error));
    return null;
  }
}

function readAnalyticsSnapshot_(resource, expectedEnvironment, cursor) {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.ANALYTICS_READ_LOCK_TIMEOUT_MS)) {
    throw analyticsError_('BACKEND_BUSY', 'The backend is busy; retry this request');
  }
  let rawData;
  const ss = spreadsheet_();
  try {
    rawData = fetchAnalyticsRawData_(ss, resource, expectedEnvironment);
  } finally {
    lock.releaseLock();
  }

  const runtimeConfig = rawData.runtimeConfig;
  const correctionCapability = rawData.correctionCapability;

  const currentPurchaseLastRow = rawData.purchaseLastRow;
  const currentEventLastRow = rawData.eventLastRow;
  const currentCorrectionLastRow = rawData.correctionLastRow;
  const purchaseLastRow = cursor ? cursor.purchaseLastRow : currentPurchaseLastRow;
  const eventLastRow = cursor ? cursor.eventLastRow : currentEventLastRow;
  if (
    cursor &&
    correctionCapability.version &&
    cursor.correctionLastRow == null
  ) {
    throw analyticsError_(
      'CURSOR_STALE',
      'The captured history snapshot predates correction support'
    );
  }
  if (
    cursor &&
    correctionCapability.version &&
    cursor.correctionLastRow !== currentCorrectionLastRow
  ) {
    throw analyticsError_(
      'CURSOR_STALE',
      'The captured history snapshot changed after a correction'
    );
  }
  const correctionLastRow = cursor && cursor.correctionLastRow != null
    ? cursor.correctionLastRow
    : currentCorrectionLastRow;
  if (
    currentPurchaseLastRow < purchaseLastRow ||
    currentEventLastRow < eventLastRow ||
    currentCorrectionLastRow < correctionLastRow
  ) {
    throw analyticsError_(
      'CURSOR_STALE',
      'The captured history snapshot is no longer available'
    );
  }

  const applyEvidence = rawData.applyEvidence || {
    eventIds: {},
    correctionIds: {},
    finishActionProductIds: {}
  };

  return {
    environment: expectedEnvironment,
    taxRate: finiteNumberOr_(runtimeConfig.values.TAX_RATE, 0.13),
    purchaseHeaders: rawData.purchaseHeaders,
    eventHeaders: rawData.eventHeaders,
    correctionHeaders: rawData.correctionHeaders,
    ledgerHeaders: rawData.ledgerHeaders,
    purchaseRows: buildAnalyticsRowsFromValues_(
      rawData.purchasesValues,
      purchaseLastRow
    ),
    eventRows: buildAnalyticsRowsFromValues_(
      rawData.eventsValues,
      eventLastRow
    ),
    correctionRows: correctionCapability.version
      ? buildAnalyticsRowsFromValues_(
        rawData.correctionsValues,
        correctionLastRow
      )
      : [],
    ledgerRows: resource === 'insights'
      ? buildAnalyticsRowsFromValues_(
        rawData.ledgerValues,
        rawData.ledgerLastRow
      )
      : [],
    purchaseLastRow: purchaseLastRow,
    eventLastRow: eventLastRow,
    correctionLastRow: correctionLastRow,
    ledgerLastRow: rawData.ledgerLastRow || 1,
    correctionVersion: correctionCapability.version,
    correctionWritesEnabled: correctionCapability.writesEnabled,
    finishActionProductIds: applyEvidence.finishActionProductIds,
    finishProvenanceEventIds: applyEvidence.eventIds,
    finishProvenanceCorrectionIds: applyEvidence.correctionIds
  };
}

function fetchAnalyticsRawData_(ss, resource, expectedEnvironment) {
  const runtimeConfig = readAndAssertRuntimeConfig_(ss, expectedEnvironment);
  assertSupportedSchemaVersion_(runtimeConfig.values);
  const correctionCapability = consumptionCorrectionCapability_(
    runtimeConfig.values
  );
  if (!recoverableSyncApplyReady_(runtimeConfig.values)) {
    throw analyticsError_(
      'SCHEMA_MISMATCH',
      'Recoverable sync apply must be enabled before analytics reads'
    );
  }
  if (text_(runtimeConfig.values[CANN.PENDING_APPLY_KEY])) {
    throw analyticsError_(
      'BACKEND_BUSY',
      'A recoverable sync apply is pending; retry this request'
    );
  }
  if (ss.getSpreadsheetTimeZone() !== CANN.TIME_ZONE) {
    throw analyticsError_(
      'CONFIGURATION_ERROR',
      'Spreadsheet time zone must be ' + CANN.TIME_ZONE
    );
  }

  if (
    typeof Sheets !== 'undefined' &&
    Sheets &&
    Sheets.Spreadsheets &&
    Sheets.Spreadsheets.Values &&
    typeof Sheets.Spreadsheets.Values.batchGet === 'function'
  ) {
    try {
      return fetchAnalyticsDataSheetsBatch_(
        ss,
        resource,
        expectedEnvironment,
        runtimeConfig,
        correctionCapability
      );
    } catch (error) {
      console.warn(
        'Advanced Sheets batchGet failed; falling back to sequential read: ' +
        conciseError_(error)
      );
    }
  }
  return fetchAnalyticsDataSheetsSequential_(
    ss,
    resource,
    expectedEnvironment,
    runtimeConfig,
    correctionCapability
  );
}

function fetchAnalyticsDataSheetsBatch_(
  ss,
  resource,
  expectedEnvironment,
  runtimeConfig,
  correctionCapability
) {
  const existingSheets = ss.getSheets();
  const existingSheetNames = {};
  existingSheets.forEach(sheet => {
    existingSheetNames[sheet.getName()] = true;
  });

  if (
    !existingSheetNames[CANN.SHEETS.PURCHASES] ||
    !existingSheetNames[CANN.SHEETS.EVENTS]
  ) {
    throw new Error('SCHEMA_MISMATCH: missing core sheets');
  }

  const requestedRanges = [
    "'" + CANN.SHEETS.PURCHASES + "'!A1:Z",
    "'" + CANN.SHEETS.EVENTS + "'!A1:Z"
  ];
  if (
    correctionCapability.version &&
    existingSheetNames[CANN.SHEETS.CORRECTIONS]
  ) {
    requestedRanges.push("'" + CANN.SHEETS.CORRECTIONS + "'!A1:Z");
  }
  if (resource === 'insights' && existingSheetNames[CANN.SHEETS.LEDGER]) {
    requestedRanges.push("'" + CANN.SHEETS.LEDGER + "'!A1:Z");
  }
  if (
    correctionCapability.version &&
    existingSheetNames[CANN.SHEETS.APPLY_JOURNAL]
  ) {
    requestedRanges.push("'" + CANN.SHEETS.APPLY_JOURNAL + "'!A1:Z");
  }

  const batchResponse = Sheets.Spreadsheets.Values.batchGet(ss.getId(), {
    ranges: requestedRanges,
    valueRenderOption: 'UNFORMATTED_VALUE',
    dateTimeRenderOption: 'SERIAL_NUMBER'
  });
  const valueRanges = (batchResponse && batchResponse.valueRanges) || [];
  const rangeMap = {};
  valueRanges.forEach(vr => {
    if (!vr || !vr.range) return;
    const sheetName = vr.range.split('!')[0].replace(/^'|'$/g, '');
    rangeMap[sheetName] = normalizeBatchDateColumns_(
      sheetName,
      padBatchRowWidth_(vr.values || [])
    );
  });

  const purchasesValues = rangeMap[CANN.SHEETS.PURCHASES] || [];
  const eventsValues = rangeMap[CANN.SHEETS.EVENTS] || [];
  const correctionsValues = rangeMap[CANN.SHEETS.CORRECTIONS] || [];
  const ledgerValues = rangeMap[CANN.SHEETS.LEDGER] || [];
  const applyJournalValues = rangeMap[CANN.SHEETS.APPLY_JOURNAL] || [];

  const purchaseHeaders = headerMapFromRow_(purchasesValues[0]);
  const eventHeaders = headerMapFromRow_(eventsValues[0]);
  const correctionHeaders = correctionsValues.length
    ? headerMapFromRow_(correctionsValues[0])
    : {};
  const ledgerHeaders = ledgerValues.length
    ? headerMapFromRow_(ledgerValues[0])
    : {};
  const applyJournalHeaders = applyJournalValues.length
    ? headerMapFromRow_(applyJournalValues[0])
    : {};

  requireExactHeaders_(
    purchaseHeaders,
    CANN.PURCHASE_HEADERS,
    CANN.SHEETS.PURCHASES
  );
  requireExactHeaders_(
    eventHeaders,
    CANN.EVENT_HEADERS,
    CANN.SHEETS.EVENTS
  );
  if (correctionCapability.version && correctionsValues.length > 0) {
    requireExactHeaders_(
      correctionHeaders,
      CANN.CORRECTION_HEADERS,
      CANN.SHEETS.CORRECTIONS
    );
  }
  if (resource === 'insights' && ledgerValues.length > 0) {
    requireExactHeaders_(
      ledgerHeaders,
      CANN.LEDGER_HEADERS,
      CANN.SHEETS.LEDGER
    );
  }

  const purchaseLastRow = purchasesValues.length;
  const eventLastRow = eventsValues.length;
  const correctionLastRow = correctionsValues.length || 1;
  const ledgerLastRow = ledgerValues.length || 1;

  const applyEvidence = correctionCapability.version
    ? recoverableApplyEvidenceFromValues_(
      applyJournalValues.slice(1),
      applyJournalHeaders
    )
    : { eventIds: {}, correctionIds: {}, finishActionProductIds: {} };

  return {
    spreadsheetTimeZone: ss.getSpreadsheetTimeZone(),
    runtimeConfig: runtimeConfig,
    correctionCapability: correctionCapability,
    purchaseHeaders: purchaseHeaders,
    eventHeaders: eventHeaders,
    correctionHeaders: correctionHeaders,
    ledgerHeaders: ledgerHeaders,
    purchasesValues: purchasesValues,
    eventsValues: eventsValues,
    correctionsValues: correctionsValues,
    ledgerValues: ledgerValues,
    purchaseLastRow: purchaseLastRow,
    eventLastRow: eventLastRow,
    correctionLastRow: correctionLastRow,
    ledgerLastRow: ledgerLastRow,
    applyEvidence: applyEvidence
  };
}

function fetchAnalyticsDataSheetsSequential_(
  ss,
  resource,
  expectedEnvironment,
  runtimeConfig,
  correctionCapability
) {
  const purchases = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const events = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const corrections = correctionCapability.version
    ? requiredSheet_(ss, CANN.SHEETS.CORRECTIONS)
    : null;
  const purchaseHeaders = headerMap_(purchases);
  const eventHeaders = headerMap_(events);
  const correctionHeaders = corrections ? headerMap_(corrections) : {};
  requireExactHeaders_(
    purchaseHeaders,
    CANN.PURCHASE_HEADERS,
    CANN.SHEETS.PURCHASES
  );
  requireExactHeaders_(
    eventHeaders,
    CANN.EVENT_HEADERS,
    CANN.SHEETS.EVENTS
  );
  if (corrections) {
    requireExactHeaders_(
      correctionHeaders,
      CANN.CORRECTION_HEADERS,
      CANN.SHEETS.CORRECTIONS
    );
  }

  const purchaseLastRow = purchases.getLastRow();
  const eventLastRow = events.getLastRow();
  const correctionLastRow = corrections
    ? corrections.getLastRow()
    : 1;

  let ledger = null;
  let ledgerHeaders = {};
  let ledgerLastRow = 1;
  if (resource === 'insights') {
    ledger = requiredSheet_(ss, CANN.SHEETS.LEDGER);
    ledgerHeaders = headerMap_(ledger);
    requireExactHeaders_(
      ledgerHeaders,
      CANN.LEDGER_HEADERS,
      CANN.SHEETS.LEDGER
    );
    ledgerLastRow = ledger.getLastRow();
  }

  const applyEvidence = correctionCapability.version
    ? recoverableApplyEvidence_(ss)
    : {
      eventIds: {},
      correctionIds: {},
      finishActionProductIds: {}
    };

  const purchasesValues = purchaseLastRow > 0
    ? purchases.getRange(1, 1, purchaseLastRow, purchases.getLastColumn()).getValues()
    : [];
  const eventsValues = eventLastRow > 0
    ? events.getRange(1, 1, eventLastRow, events.getLastColumn()).getValues()
    : [];
  const correctionsValues = corrections && correctionLastRow > 0
    ? corrections.getRange(1, 1, correctionLastRow, corrections.getLastColumn()).getValues()
    : [];
  const ledgerValues = ledger && ledgerLastRow > 0
    ? ledger.getRange(1, 1, ledgerLastRow, ledger.getLastColumn()).getValues()
    : [];

  return {
    spreadsheetTimeZone: ss.getSpreadsheetTimeZone(),
    runtimeConfig: runtimeConfig,
    correctionCapability: correctionCapability,
    purchaseHeaders: purchaseHeaders,
    eventHeaders: eventHeaders,
    correctionHeaders: correctionHeaders,
    ledgerHeaders: ledgerHeaders,
    purchasesValues: purchasesValues,
    eventsValues: eventsValues,
    correctionsValues: correctionsValues,
    ledgerValues: ledgerValues,
    purchaseLastRow: purchaseLastRow,
    eventLastRow: eventLastRow,
    correctionLastRow: correctionLastRow,
    ledgerLastRow: ledgerLastRow,
    applyEvidence: applyEvidence
  };
}

function buildAnalyticsRowsFromValues_(values, lastRow) {
  if (!values || values.length < 2) return [];
  const limit = lastRow ? Math.min(lastRow, values.length) : values.length;
  const result = [];
  for (let i = 1; i < limit; i++) {
    result.push({
      canonicalRow: i + 1,
      cells: values[i] || []
    });
  }
  return result;
}

function readAnalyticsRowsThrough_(sheet, lastRow) {
  if (!sheet || lastRow < 2) return [];
  return sheet.getRange(
    2,
    1,
    lastRow - 1,
    sheet.getLastColumn()
  ).getValues().map((cells, index) => ({
    canonicalRow: index + 2,
    cells: cells
  }));
}

function headerMapFromRow_(headerRow) {
  const result = {};
  (headerRow || []).forEach((header, index) => {
    const name = text_(header);
    if (name && result[name] === undefined) result[name] = index;
  });
  return result;
}

function configValuesFromRows_(rows, headers) {
  const result = {};
  (rows || []).forEach(row => {
    const key = text_(value_(row, headers, 'Key'));
    if (key) result[key] = value_(row, headers, 'Value');
  });
  return result;
}

function newAnalyticsQuality_() {
  return {
    estimatedPurchaseDateCount: 0,
    unknownPurchaseDateCount: 0,
    unknownPersonalCostCount: 0,
    unknownBorrowedCostCount: 0,
    ambiguousThcCount: 0,
    invalidThcCount: 0,
    invalidGramsCount: 0,
    unknownStatusCount: 0,
    unknownBorrowedFlagCount: 0,
    localDateMismatchCount: 0,
    localTimeMismatchCount: 0,
    unknownSourceCount: 0,
    invalidUnreferencedPurchaseRowCount: 0
  };
}

function analyticsQualityResponse_(quality) {
  const warnings = {};
  Object.keys(newAnalyticsQuality_()).forEach(name => {
    warnings[name] = Number(quality[name]) || 0;
  });
  return {
    complete: Object.keys(warnings).every(name => warnings[name] === 0),
    warnings: warnings
  };
}

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

function buildInsightsResponse_(
  snapshot,
  products,
  events,
  ledgerRows,
  query,
  quality,
  timing
) {
  const range = resolveInsightsRange_(query, products, events);
  const productStats = {};
  products.forEach(product => {
    productStats[product.productId] = {
      allLogCount: 0,
      allQuantity: 0,
      allDays: {},
      allFirst: null,
      allLast: null,
      allLastQuantity: null,
      rangeLogCount: 0,
      rangeQuantity: 0,
      rangeDays: {},
      latestFinished: null
    };
  });

  const daily = {};
  for (
    let ordinal = localDateOrdinal_(range.from);
    ordinal <= localDateOrdinal_(range.to);
    ordinal++
  ) {
    const date = localDateFromOrdinal_(ordinal);
    daily[date] = { logCount: 0, products: {} };
  }
  const weekdayCounts = Array(7).fill(0);
  const hourCounts = Array(24).fill(0);
  const overviewProducts = {};
  let overviewFirst = null;
  let overviewLast = null;
  let overviewLogCount = 0;

  events.forEach(event => {
    const stats = productStats[event.product.productId];
    stats.allLogCount++;
    stats.allQuantity += event.quantity;
    stats.allDays[event.localDate] = true;
    if (
      stats.allFirst == null ||
      event.occurredAtEpochMillis < stats.allFirst
    ) {
      stats.allFirst = event.occurredAtEpochMillis;
    }
    if (
      stats.allLast == null ||
      event.occurredAtEpochMillis > stats.allLast
    ) {
      stats.allLast = event.occurredAtEpochMillis;
      stats.allLastQuantity = event.quantity;
    }
    if (
      event.finished &&
      (
        stats.latestFinished == null ||
        event.occurredAtEpochMillis > stats.latestFinished
      )
    ) {
      stats.latestFinished = event.occurredAtEpochMillis;
    }
    if (!dateInRange_(event.localDate, range.from, range.to)) return;

    stats.rangeLogCount++;
    stats.rangeQuantity += event.quantity;
    stats.rangeDays[event.localDate] = true;
    daily[event.localDate].logCount++;
    daily[event.localDate].products[event.product.productId] = true;
    const isoDay = isoDayForLocalDate_(event.localDate);
    weekdayCounts[isoDay - 1]++;
    hourCounts[Number(event.localTime.slice(0, 2))]++;
    overviewProducts[event.product.productId] = true;
    overviewLogCount++;
    if (
      overviewFirst == null ||
      event.occurredAtEpochMillis < overviewFirst
    ) {
      overviewFirst = event.occurredAtEpochMillis;
    }
    if (
      overviewLast == null ||
      event.occurredAtEpochMillis > overviewLast
    ) {
      overviewLast = event.occurredAtEpochMillis;
    }
  });

  const spending = buildSpending_(products, range);
  const inventory = buildInventory_(products);
  const byType = buildTypeBreakdown_(products, productStats, range);
  const productResponses = products.map(product => {
    const stats = productStats[product.productId];
    const eligible = (
      product.status === 'FINISHED' &&
      product.borrowed === false &&
      product.costKnown &&
      stats.allLogCount > 0 &&
      stats.allQuantity > 0
    );
    return {
      productUuid: product.productUuid,
      productId: product.productId,
      name: product.name,
      type: product.type,
      status: product.status,
      borrowed: product.borrowed,
      purchaseDate: product.purchaseDate,
      purchaseDateSource: product.purchaseDateSource,
      preTaxCostCents: product.preTaxCostCents,
      finalCostCents: product.finalCostCents,
      grams: product.grams,
      thcRaw: product.thcRaw,
      thcQuality: product.thcQuality,
      latestFinishedLogAtEpochMillis: stats.latestFinished,
      daysSinceLastLog: stats.allLast == null
        ? null
        : daysSinceEpochMillis_(stats.allLast),
      allTime: {
        logCount: stats.allLogCount,
        quantity: analyticsRounded_(stats.allQuantity),
        activeDayCount: Object.keys(stats.allDays).length,
        firstLogAtEpochMillis: stats.allFirst,
        lastLogAtEpochMillis: stats.allLast,
        lastQuantity: stats.allLastQuantity
      },
      range: {
        logCount: stats.rangeLogCount,
        quantity: analyticsRounded_(stats.rangeQuantity),
        activeDayCount: Object.keys(stats.rangeDays).length
      },
      costPerLogToDateCents: (
        product.borrowed === false &&
        product.costKnown &&
        stats.allLogCount > 0
      ) ? Math.round(product.finalCostCents / stats.allLogCount) : null,
      costPerRecordedUnitToDateCents: (
        product.borrowed === false &&
        product.costKnown &&
        stats.allQuantity > 0
      ) ? Math.round(product.finalCostCents / stats.allQuantity) : null,
      completedValueComparisonEligible: eligible
    };
  }).sort(compareAnalyticsProducts_);

  const response = {
    success: true,
    apiVersion: CANN.API_VERSION,
    analyticsVersion: query.analyticsVersion,
    resource: 'insights',
    environment: snapshot.environment,
    correctionVersion: snapshot.correctionVersion,
    correctionWritesEnabled: snapshot.correctionWritesEnabled,
    timeZone: CANN.TIME_ZONE,
    range: range,
    overview: {
      logCount: overviewLogCount,
      activeDayCount: Object.keys(daily)
        .filter(date => daily[date].logCount > 0).length,
      distinctProductCount: Object.keys(overviewProducts).length,
      firstLogAtEpochMillis: overviewFirst,
      lastLogAtEpochMillis: overviewLast,
      daysSinceLastLog: overviewLast == null
        ? null
        : daysSinceEpochMillis_(overviewLast)
    },
    dailyActivity: Object.keys(daily).sort().map(date => ({
      date: date,
      logCount: daily[date].logCount,
      distinctProductCount: Object.keys(daily[date].products).length
    })),
    byWeekday: weekdayCounts.map((logCount, index) => ({
      isoDay: index + 1,
      logCount: logCount
    })),
    byHour: hourCounts.map((logCount, hour) => ({
      hour: hour,
      logCount: logCount
    })),
    inventory: inventory,
    byType: byType,
    products: productResponses,
    spending: spending,
    syncHealth: buildSyncHealth_(ledgerRows),
    dataQuality: analyticsQualityResponse_(quality),
    sourceRevision: {
      dataVersion: analyticsDataVersion_(snapshot, true),
      purchaseRowCount: products.length,
      eventRowCount: events.length,
      rawEventRowCount: snapshot.eventRows.length,
      correctionRowCount: snapshot.correctionRows.length,
      effectiveEventCount: events.length,
      ledgerRowCount: ledgerRows.length
    },
    generatedAtEpochMillis: Date.now(),
    serverDurationMs: 0
  };
  return finalizeAnalyticsResponse_(response, timing);
}

function resolveInsightsRange_(query, products, events) {
  let from = query.from;
  const to = query.to;
  if (query.scope === 'ALL') {
    const candidates = [];
    products.forEach(product => {
      if (product.purchaseDate && product.purchaseDate <= to) {
        candidates.push(product.purchaseDate);
      }
    });
    events.forEach(event => {
      if (event.localDate <= to) candidates.push(event.localDate);
    });
    from = candidates.length ? candidates.sort()[0] : to;
  }
  return {
    scope: query.scope,
    from: from,
    to: to,
    dayCount: localDateDayCount_(from, to)
  };
}

function buildInventory_(products) {
  const result = {
    activeCount: 0,
    unopenedCount: 0,
    finishedCount: 0,
    unknownStatusCount: 0,
    currentPersonalOriginalCostCents: 0,
    currentBorrowedRecordedValueCents: 0,
    unknownCurrentCostCount: 0
  };
  products.forEach(product => {
    if (product.status === 'ACTIVE') result.activeCount++;
    else if (product.status === 'UNOPENED') result.unopenedCount++;
    else if (product.status === 'FINISHED') result.finishedCount++;
    else result.unknownStatusCount++;

    if (product.status !== 'ACTIVE' && product.status !== 'UNOPENED') return;
    if (!product.costKnown) {
      result.unknownCurrentCostCount++;
      return;
    }
    if (product.borrowed === false) {
      result.currentPersonalOriginalCostCents += product.finalCostCents;
    } else if (product.borrowed === true) {
      result.currentBorrowedRecordedValueCents += product.finalCostCents;
    }
  });
  return result;
}

function buildTypeBreakdown_(products, statsByProduct, range) {
  const byType = {};
  products.forEach(product => {
    const type = product.type;
    if (!byType[type]) {
      byType[type] = {
        type: type,
        rangeLogCount: 0,
        rangeDistinctProductCount: 0,
        activeCount: 0,
        unopenedCount: 0,
        finishedCount: 0,
        unknownStatusCount: 0,
        personalSpendCents: 0,
        personalPurchaseCount: 0,
        borrowedRecordedValueCents: 0,
        borrowedPurchaseCount: 0,
        unknownCostCount: 0
      };
    }
    const bucket = byType[type];
    const stats = statsByProduct[product.productId];
    bucket.rangeLogCount += stats.rangeLogCount;
    if (stats.rangeLogCount > 0) bucket.rangeDistinctProductCount++;
    if (product.status === 'ACTIVE') bucket.activeCount++;
    else if (product.status === 'UNOPENED') bucket.unopenedCount++;
    else if (product.status === 'FINISHED') bucket.finishedCount++;
    else bucket.unknownStatusCount++;
    if (!dateInRange_(product.purchaseDate, range.from, range.to)) return;
    if (product.borrowed === false) {
      bucket.personalPurchaseCount++;
      if (product.costKnown) bucket.personalSpendCents += product.finalCostCents;
      else bucket.unknownCostCount++;
    } else if (product.borrowed === true) {
      bucket.borrowedPurchaseCount++;
      if (product.costKnown) {
        bucket.borrowedRecordedValueCents += product.finalCostCents;
      } else {
        bucket.unknownCostCount++;
      }
    } else if (!product.costKnown) {
      bucket.unknownCostCount++;
    }
  });
  return Object.keys(byType).sort().map(type => byType[type]);
}

function emptySpendBucket_() {
  return {
    personalSpendCents: 0,
    personalPurchaseCount: 0,
    borrowedRecordedValueCents: 0,
    borrowedPurchaseCount: 0,
    unknownPersonalCostCount: 0,
    unknownBorrowedCostCount: 0,
    estimatedDateCount: 0,
    unknownDateCount: 0
  };
}

function addProductToSpendBucket_(bucket, product) {
  if (product.purchaseDateSource === 'CREATED_AT_FALLBACK') {
    bucket.estimatedDateCount++;
  }
  if (product.purchaseDateSource === 'UNKNOWN') bucket.unknownDateCount++;
  if (product.borrowed === false) {
    bucket.personalPurchaseCount++;
    if (product.costKnown) bucket.personalSpendCents += product.finalCostCents;
    else bucket.unknownPersonalCostCount++;
  } else if (product.borrowed === true) {
    bucket.borrowedPurchaseCount++;
    if (product.costKnown) {
      bucket.borrowedRecordedValueCents += product.finalCostCents;
    } else {
      bucket.unknownBorrowedCostCount++;
    }
  }
}

function buildSpending_(products, range) {
  const allTime = emptySpendBucket_();
  const selectedRange = emptySpendBucket_();
  const months = monthKeysBetween_(range.from, range.to);
  const byMonth = {};
  months.forEach(month => { byMonth[month] = emptySpendBucket_(); });
  products.forEach(product => {
    addProductToSpendBucket_(allTime, product);
    if (!dateInRange_(product.purchaseDate, range.from, range.to)) return;
    addProductToSpendBucket_(selectedRange, product);
    addProductToSpendBucket_(byMonth[product.purchaseDate.slice(0, 7)], product);
  });
  return {
    allTime: allTime,
    range: selectedRange,
    byMonth: months.map(month => Object.assign({ month: month }, byMonth[month]))
  };
}

function monthKeysBetween_(from, to) {
  const fromParts = from.split('-').map(Number);
  const toParts = to.split('-').map(Number);
  let year = fromParts[0];
  let month = fromParts[1];
  const result = [];
  while (year < toParts[0] || (year === toParts[0] && month <= toParts[1])) {
    result.push(year + '-' + String(month).padStart(2, '0'));
    month++;
    if (month === 13) {
      month = 1;
      year++;
    }
  }
  return result;
}

function buildSyncHealth_(ledgerRows) {
  const sorted = ledgerRows.slice().sort((left, right) => (
    left.receivedAtEpochMillis - right.receivedAtEpochMillis
  ));
  const latest = sorted.length ? sorted[sorted.length - 1] : null;
  const cutoff = Date.now() - 30 * 86400000;
  const recent = sorted.filter(row => row.receivedAtEpochMillis >= cutoff);
  const durations = recent
    .map(row => row.durationMs)
    .filter(value => value != null)
    .sort((left, right) => left - right);
  return {
    coverage: 'SERVER_ACKNOWLEDGED_REQUESTS_ONLY',
    lastAcknowledgedAtEpochMillis: latest
      ? latest.receivedAtEpochMillis
      : null,
    lastResult: latest ? latest.result || null : null,
    lastDurationMs: latest ? latest.durationMs : null,
    acknowledgedRequestCount30d: recent.length,
    partialRequestCount30d: recent.filter(row => row.result === 'PARTIAL').length,
    medianDurationMs30d: analyticsPercentile_(durations, 0.5),
    p95DurationMs30d: analyticsPercentile_(durations, 0.95)
  };
}

function analyticsPercentile_(sortedValues, percentile) {
  if (!sortedValues.length) return null;
  if (percentile === 0.5 && sortedValues.length % 2 === 0) {
    const right = sortedValues.length / 2;
    return Math.round((sortedValues[right - 1] + sortedValues[right]) / 2);
  }
  const index = Math.max(0, Math.ceil(percentile * sortedValues.length) - 1);
  return Math.round(sortedValues[index]);
}

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

