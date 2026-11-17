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

