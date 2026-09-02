/**
 * Cannsheet Backend v2
 *
 * Backward-compatible Apps Script backend for the Android client and Google Form.
 * Shared mutations are serialized, v2 records are idempotent by UUID, and the
 * legacy GET/POST fields remain available during rollout.
 */

// -----------------------------------------------------------------------------
// Config
// -----------------------------------------------------------------------------

const CANN = Object.freeze({
  API_VERSION: 2,
  ANALYTICS_VERSION: 1,
  ANALYTICS_VERSIONS: Object.freeze([1, 2]),
  ANALYTICS_DEFAULT_RANGE_DAYS: 180,
  ANALYTICS_MAX_RANGE_DAYS: 3660,
  ANALYTICS_READ_LOCK_TIMEOUT_MS: 5000,
  HISTORY_DEFAULT_LIMIT: 50,
  HISTORY_MAX_LIMIT: 200,
  HISTORY_MAX_QUERY_LENGTH: 80,
  HISTORY_MAX_CURSOR_LENGTH: 1024,
  CACHE_DEFAULT_TTL_SECONDS: 21600,
  CACHE_CHUNK_SIZE: 90000,
  CACHE_MAX_CHUNKS: 10,
  MUTATION_WATERMARK_PROPERTY: 'MUTATION_WATERMARK',
  // The additive Purchases projection has its own readiness version so the
  // existing v2 deployment remains usable while the migration runs.
  SCHEMA_VERSION: 2,
  INTERACTION_SUMMARY_VERSION: 1,
  RECOVERABLE_SYNC_APPLY_VERSION: 1,
  RECOVERABLE_SYNC_APPLY_CONFIG_KEY: 'RECOVERABLE_SYNC_APPLY_VERSION',
  CONSUMPTION_CORRECTION_VERSION: 1,
  CONSUMPTION_CORRECTION_VERSION_KEY:
    'CONSUMPTION_CORRECTION_SCHEMA_VERSION',
  CONSUMPTION_CORRECTION_WRITES_KEY:
    'CONSUMPTION_CORRECTION_WRITES_ENABLED',
  PENDING_APPLY_KEY: 'PENDING_APPLY_KEY',
  COMPATIBILITY_EVENT_HEADER: 'Cannsheet Event UUID',
  COMPATIBILITY_REQUEST_HEADER: 'Cannsheet Request UUID',
  SANDBOX_FAULT_PROPERTY: 'SANDBOX_SYNC_APPLY_FAULT',
  SYNC_APPLY_FAULTS: Object.freeze({
    COMPATIBILITY: 'AFTER_COMPATIBILITY',
    CANONICAL: 'AFTER_CANONICAL',
    PRODUCT_EFFECTS: 'AFTER_PRODUCT_EFFECTS',
    INTERACTION_SUMMARY: 'AFTER_INTERACTION_SUMMARY',
    CORE_COMMITTED: 'AFTER_CORE_COMMIT',
    LEDGER: 'BEFORE_FINAL_LEDGER',
    POST_COMPLETE: 'AFTER_COMPLETE'
  }),
  TIME_ZONE: 'America/New_York',
  LOCK_TIMEOUT_MS: 30000,
  MAX_BATCH_SIZE: 100,
  MAX_CORRECTION_REASON_LENGTH: 200,
  EVENT_TEXT_FINDER_MAX_BATCH: 5,
  FORM_PRODUCT_QUESTION: 'Product',
  SHEETS: Object.freeze({
    PURCHASES: 'Purchases',
    RESPONSES: 'Form Responses 1',
    EVENTS: 'ConsumptionEvents',
    CORRECTIONS: 'ConsumptionEventCorrections',
    LEDGER: 'SyncLedger',
    APPLY_JOURNAL: 'SyncApplyJournal',
    CONFIG: 'Config',
    MIGRATION_REPORT: 'MigrationReport'
  }),
  PURCHASE_HEADERS: Object.freeze([
    'Date', 'Type', 'Product name', 'Pre-tax cost', 'THC%', 'Grams',
    'Borrowed', 'Finished', 'Product ID', 'Uses', 'Post-tax', 'Final cost',
    'Most recent use', 'Product UUID', 'Client Action UUID', 'Created At',
    'Finished At', 'Last quantity'
  ]),
  EVENT_HEADERS: Object.freeze([
    'Event UUID', 'Timestamp', 'Local Date', 'Local Time', 'Product UUID',
    'Legacy Product ID', 'Uses', 'Weight Code', 'Finished', 'Source',
    'Request UUID', 'Legacy Source Sheet', 'Legacy Source Row'
  ]),
  CORRECTION_HEADERS: Object.freeze([
    'Correction UUID', 'Target Event UUID', 'Supersedes Correction UUID',
    'Revision', 'Operation', 'Replacement Timestamp',
    'Replacement Local Date', 'Replacement Local Time',
    'Replacement Product UUID', 'Replacement Legacy Product ID',
    'Replacement Uses', 'Replacement Weight Code', 'Replacement Finished',
    'Reopen Product', 'Reason', 'Request UUID', 'Source', 'Created At'
  ]),
  LEDGER_HEADERS: Object.freeze([
    'Request UUID', 'API Version', 'Received At', 'Purchase Count',
    'Consumption Count', 'Result', 'Duration Ms', 'Error Code'
  ]),
  REPORT_HEADERS: Object.freeze([
    'Type', 'Source Sheet', 'Source Row', 'Product ID', 'Detail', 'Recorded At'
  ]),
  MIGRATION_RESOLUTION_HEADERS: Object.freeze([
    'Resolved At', 'Resolution'
  ]),
  APPLY_JOURNAL_HEADERS: Object.freeze([
    'Apply UUID', 'Kind', 'API Version', 'Request UUID', 'State',
    'Core Committed At', 'Completed At', 'Finalization JSON', 'Response JSON'
  ]),
  CORRECTION_OPERATIONS: Object.freeze({
    REPLACE: 'REPLACE',
    VOID: 'VOID',
    RESTORE: 'RESTORE'
  }),
  STATUS: Object.freeze({ ACTIVE: 0, FINISHED: 1, UNOPENED: 2 })
});

// Columns the sequential Range.getValues() path returns as Date objects. The
// Advanced Sheets Values API returns them as serial numbers, so the batch path
// converts them back to preserve cell-type parity — analyticsHashValue_ types
// a Date and a string differently, which would change sourceRevision.dataVersion.
const ANALYTICS_DATE_COLUMNS_ = Object.freeze({
  [CANN.SHEETS.PURCHASES]: Object.freeze([
    'Date', 'Most recent use', 'Created At', 'Finished At'
  ]),
  [CANN.SHEETS.EVENTS]: Object.freeze(['Timestamp']),
  [CANN.SHEETS.CORRECTIONS]: Object.freeze([
    'Replacement Timestamp', 'Created At'
  ]),
  [CANN.SHEETS.LEDGER]: Object.freeze(['Received At']),
  [CANN.SHEETS.APPLY_JOURNAL]: Object.freeze([
    'Core Committed At', 'Completed At'
  ])
});

// -----------------------------------------------------------------------------
// HTTP API
// -----------------------------------------------------------------------------

function doGet(e) {
  if (analyticsResourceWasSupplied_(e)) return handleReadResource_(e);
  const timing = newBackendTiming_('GET');
  let environment = '';
  try {
    const environmentConfigStarted = Date.now();
    environment = environment_();
    const ss = spreadsheet_();
    const runtimeConfig = readAndAssertRuntimeConfig_(ss, environment);
    assertSupportedSchemaVersion_(runtimeConfig.values);
    const summaryReady = interactionSummaryReady_(runtimeConfig.values);
    const correctionCapability = consumptionCorrectionCapability_(
      runtimeConfig.values
    );
    if (correctionCapability.version && !summaryReady) {
      throw new Error(
        'SCHEMA_MISMATCH: consumption corrections require the ' +
        'interaction-summary projection'
      );
    }
    recordBackendPhase_(timing, 'environmentConfig', environmentConfigStarted);

    const purchaseContextStarted = Date.now();
    const purchases = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
    const headers = headerMap_(purchases);
    requireExactHeaders_(
      headers,
      summaryReady ? CANN.PURCHASE_HEADERS : CANN.PURCHASE_HEADERS.slice(0, -1),
      CANN.SHEETS.PURCHASES
    );
    const rows = readDataRows_(purchases);
    recordBackendPhase_(timing, 'purchaseContext', purchaseContextStarted);

    const interactionLookupStarted = Date.now();
    // The history scan is a migration/rollback fallback only. Once the explicit
    // migration marks version 1 ready, normal GET never opens event history.
    const legacyInteractions = summaryReady ? null : latestInteractions_(ss);
    recordBackendPhase_(timing, 'interactionLookup', interactionLookupStarted);

    const responseConstructionStarted = Date.now();
    const products = rows
      .filter(row => text_(value_(row, headers, 'Product ID')) && text_(value_(row, headers, 'Product name')))
      .map(row => {
        const legacyId = text_(value_(row, headers, 'Product ID'));
        const fallback = legacyInteractions && legacyInteractions[legacyId];
        const rawRecent = summaryReady
          ? value_(row, headers, 'Most recent use')
          : '';
        const rawLastQuantity = summaryReady
          ? value_(row, headers, 'Last quantity')
          : '';
        const rawTotalUses = value_(row, headers, 'Uses');
        const totalUses = text_(rawTotalUses) === ''
          ? 0
          : optionalFiniteNumber_(rawTotalUses);
        const recentMillis = summaryReady
          ? timestampMillisOrNull_(rawRecent)
          : (fallback ? fallback.lastLoggedAtEpochMillis : null);
        const lastQuantity = summaryReady
          ? optionalFiniteNumber_(rawLastQuantity)
          : (fallback ? optionalFiniteNumber_(fallback.lastQuantity) : null);
        if (summaryReady && (
          (rawRecent !== '' && rawRecent != null && recentMillis == null) ||
          (rawLastQuantity !== '' && rawLastQuantity != null && lastQuantity == null) ||
          ((recentMillis == null) !== (lastQuantity == null))
        )) {
          throw new Error(
            'INTERACTION_SUMMARY_INVALID: timestamp and quantity must be a valid pair for ' +
            legacyId
          );
        }
        if (totalUses == null || totalUses < 0) {
          throw new Error(
            'INTERACTION_SUMMARY_INVALID: Uses must be a finite nonnegative number for ' +
            legacyId
          );
        }
        const product = {
          id: legacyId,
          name: text_(value_(row, headers, 'Product name')),
          type: text_(value_(row, headers, 'Type')),
          cost: finiteNumberOr_(value_(row, headers, 'Pre-tax cost'), 0),
          postTax: truthy_(value_(row, headers, 'Post-tax')),
          thc: finiteNumberOr_(value_(row, headers, 'THC%'), 0),
          grams: finiteNumberOr_(value_(row, headers, 'Grams'), 0),
          status: allowedStatusOr_(value_(row, headers, 'Finished'), CANN.STATUS.ACTIVE),
          totalUses: analyticsRounded_(totalUses)
        };
        if (headers['Product UUID'] !== undefined) {
          const productUuid = text_(value_(row, headers, 'Product UUID'));
          if (productUuid) product.productUuid = productUuid;
        }
        if (recentMillis != null && lastQuantity != null) {
          product.lastLoggedAtEpochMillis = recentMillis;
          product.lastQuantity = lastQuantity;
        }
        return product;
      });

    const response = {
      products: products,
      apiVersion: CANN.API_VERSION,
      environment: environment,
      correctionVersion: correctionCapability.version,
      correctionWritesEnabled: correctionCapability.writesEnabled,
      taxRate: taxRate_(ss)
    };
    recordBackendPhase_(timing, 'responseConstruction', responseConstructionStarted);
    addServerTimingFields_(response, timing, environment);
    const responseRoutingStarted = Date.now();
    const output = jsonOutput_(response);
    recordBackendPhase_(timing, 'responseRouting', responseRoutingStarted);
    logBackendTiming_(timing, 'success', {
      environment: environment,
      productCount: products.length,
      interactionProjection: summaryReady ? 'PURCHASES' : 'LEGACY_HISTORY'
    });
    return output;
  } catch (error) {
    console.error('GET failed: ' + conciseError_(error));
    const responseConstructionStarted = Date.now();
    const response = { error: conciseError_(error), errorCode: 'INTERNAL_ERROR', environment: environment || undefined };
    recordBackendPhase_(timing, 'responseConstruction', responseConstructionStarted);
    addServerTimingFields_(response, timing, environment);
    const responseRoutingStarted = Date.now();
    const output = jsonOutput_(response);
    recordBackendPhase_(timing, 'responseRouting', responseRoutingStarted);
    logBackendTiming_(timing, 'error', {
      environment: environment || undefined,
      errorCode: 'INTERNAL_ERROR'
    });
    return output;
  }
}

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
    if (resource !== 'insights' && resource !== 'history') {
      throw analyticsError_('UNSUPPORTED_RESOURCE', 'Unsupported analytics resource');
    }

    requestedAnalyticsVersion = validateAnalyticsCommonQuery_(query.values);
    const recognized = resource === 'insights'
      ? ['resource', 'analyticsVersion', 'environment', 'from', 'to', 'scope']
      : [
        'resource', 'analyticsVersion', 'environment', 'from', 'to',
        'productUuid', 'productId', 'type', 'q', 'limit', 'cursor'
      ];
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

// -----------------------------------------------------------------------------
// Version 1 sync (all-or-nothing response semantics)
// -----------------------------------------------------------------------------

function handleLegacySyncLocked_(requestContext, timing) {
  let phaseStarted = Date.now();
  const payload = requestContext.payload;
  const purchases = requestContext.purchases;
  const consumptions = requestContext.consumptions;
  const ss = requestContext.ss;

  refreshRecoverableSyncApplyStateLocked_(requestContext);
  if (recoverableSyncApplyReady_(requestContext.config)) {
    const repairStarted = Date.now();
    repairPendingSyncApplyLocked_(requestContext);
    recordBackendPhase_(timing, 'recoveryRepair', repairStarted);
  }

  phaseStarted = Date.now();
  const context = productContext_(ss, {
    includeActionIds: false,
    runtimeContext: requestContext
  });
  recordBackendPhase_(timing, 'purchaseContext', phaseStarted);

  phaseStarted = Date.now();
  const purchaseErrors = purchases.map((item, index) => validateLegacyPurchase_(item, index)).filter(Boolean);
  if (purchaseErrors.length) {
    recordBackendPhase_(timing, 'stagingValidation', phaseStarted);
    return legacyFailure_(purchaseErrors[0].message);
  }

  const staged = stagePurchases_(purchases.map((item, index) => ({
    item: item,
    actionId: Utilities.getUuid(),
    tempId: text_(item.tempId),
    sourceIndex: index,
    legacy: true
  })), context);
  if (staged.rejected.length) {
    recordBackendPhase_(timing, 'stagingValidation', phaseStarted);
    return legacyFailure_(staged.rejected[0].message);
  }

  const resolver = Object.assign({}, context.byLegacyId, staged.byTempId);
  const consumptionErrors = consumptions
    .map((item, index) => validateLegacyConsumption_(item, index, resolver))
    .filter(Boolean);
  if (consumptionErrors.length) {
    recordBackendPhase_(timing, 'stagingValidation', phaseStarted);
    return legacyFailure_(consumptionErrors[0].message);
  }

  const now = new Date();
  phaseStarted = Date.now();
  const stagedConsumptions = consumptions.map(item => stageLegacyConsumption_(item, resolver, now));
  recordBackendPhase_(timing, 'stagingValidation', phaseStarted);
  const productIdMap = {};
  staged.accepted.forEach(item => { productIdMap[item.tempId] = item.legacyProductId; });
  const response = { success: true, message: 'Sync complete', productIdMap: productIdMap };

  if (recoverableSyncApplyReady_(requestContext.config)) {
    applyRecoverableSyncLocked_({
      runtimeContext: requestContext,
      productContext: context,
      stagedPurchases: staged.accepted,
      stagedConsumptions: stagedConsumptions,
      kind: 'ANDROID_V1',
      apiVersion: 1,
      requestId: '',
      response: response,
      formRefreshRequired: true,
      ledger: null,
      timing: timing
    });
  } else {
    phaseStarted = Date.now();
    appendPurchaseRows_(context, staged.accepted, now, requestContext.config.TAX_RATE);
    recordBackendPhase_(timing, 'purchaseAppend', phaseStarted);
    appendConsumptionRows_(ss, stagedConsumptions, false, timing, requestContext);
    phaseStarted = Date.now();
    applyProductEffects_(context, stagedConsumptions);
    recordBackendPhase_(timing, 'productEffects', phaseStarted);
    phaseStarted = Date.now();
    updateFormAndDescriptionLocked_(ss, requestContext);
    recordBackendPhase_(timing, 'formRefresh', phaseStarted);
  }

  phaseStarted = Date.now();
  recordBackendPhase_(timing, 'responseConstruction', phaseStarted);
  bumpMutationWatermark_();
  return response;
}

function legacyFailure_(message) {
  return { success: false, message: message, productIdMap: {} };
}

// -----------------------------------------------------------------------------
// Version 2 sync (record-level idempotency and acknowledgements)
// -----------------------------------------------------------------------------

function handleV2SyncLocked_(requestContext, started, timing) {
  let phaseStarted = Date.now();
  const payload = requestContext.payload;
  const requestId = text_(payload.requestId);
  const purchases = requestContext.purchases;
  const consumptions = requestContext.consumptions;
  const finishActions = requestContext.finishActions;
  const consumptionCorrections = requestContext.consumptionCorrections;
  const ss = requestContext.ss;

  refreshRecoverableSyncApplyStateLocked_(requestContext);
  const recoverableReady = recoverableSyncApplyReady_(requestContext.config);
  if (recoverableReady) {
    const repairStarted = Date.now();
    repairPendingSyncApplyLocked_(requestContext);
    recordBackendPhase_(timing, 'recoveryRepair', repairStarted);
  }

  phaseStarted = Date.now();
  const context = purchases.length || consumptions.length ||
      finishActions.length || consumptionCorrections.length
    ? productContext_(ss, {
      includeActionIds: purchases.length > 0,
      runtimeContext: requestContext
    })
    : emptyProductContext_(requestContext);
  recordBackendPhase_(timing, 'purchaseContext', phaseStarted);

  phaseStarted = Date.now();
  const existingEvents = consumptions.length
    ? eventContext_(ss, requestContext, consumptions
      .map(item => text_(item && item.eventId))
      .filter(eventId => isUuid_(eventId)))
    : { sheet: requestContext.sheets.events, eventIds: new Set() };
  recordBackendPhase_(timing, 'eventDuplicateLookup', phaseStarted);

  phaseStarted = Date.now();
  const acceptedPurchases = [];
  const rejectedPurchases = [];
  const newPurchases = [];
  const duplicatePurchasesByTempId = {};

  purchases.forEach((item, index) => {
    const error = validateV2Purchase_(item, index);
    if (error) {
      rejectedPurchases.push(rejectedPurchase_(item, error.code, error.message));
      return;
    }
    const actionId = text_(item.actionId);
    const existing = context.byActionId[actionId];
    if (existing) {
      acceptedPurchases.push(purchaseAck_(item, existing, 'duplicate'));
      const duplicateTempId = text_(item.tempId);
      if (duplicateTempId) duplicatePurchasesByTempId[duplicateTempId] = existing;
      return;
    }
    newPurchases.push({ item: item, actionId: actionId, tempId: text_(item.tempId), sourceIndex: index, legacy: false });
  });

  const staged = stagePurchases_(newPurchases, context);
  staged.rejected.forEach(item => rejectedPurchases.push(rejectedPurchase_(item.item, item.code, item.message)));
  staged.accepted.forEach(item => acceptedPurchases.push(purchaseAck_(item.item, item, 'committed')));

  const resolver = Object.assign(
    {},
    context.byLegacyId,
    duplicatePurchasesByTempId,
    staged.byTempId
  );
  const acceptedConsumptions = [];
  const rejectedConsumptions = [];
  const stagedConsumptions = [];

  consumptions.forEach((item, index) => {
    const error = validateV2Consumption_(item, index);
    if (error) {
      rejectedConsumptions.push(rejectedConsumption_(item, error.code, error.message));
      return;
    }
    const eventId = text_(item.eventId);
    if (existingEvents.eventIds.has(eventId)) {
      acceptedConsumptions.push({ eventId: eventId, status: 'duplicate' });
      return;
    }
    const resolved = resolveProduct_(item, resolver, context.byProductUuid);
    if (!resolved) {
      rejectedConsumptions.push(rejectedConsumption_(item, 'UNKNOWN_PRODUCT', 'Unknown product reference'));
      return;
    }
    const stagedItem = stageV2Consumption_(item, resolved, requestId);
    stagedConsumptions.push(stagedItem);
    acceptedConsumptions.push({ eventId: eventId, status: 'committed' });
  });

  const existingFinishActions = recoverableReady && finishActions.length
    ? finishActionContext_(ss, requestContext, finishActions
      .map(item => text_(item && item.actionId))
      .filter(actionId => isUuid_(actionId)))
    : {};
  const acceptedFinishActions = [];
  const rejectedFinishActions = [];
  const stagedFinishActions = [];
  const finishResolverByProductUuid = Object.assign({}, context.byProductUuid);
  staged.accepted.forEach(item => {
    if (item.productUuid) finishResolverByProductUuid[item.productUuid] = item;
  });

  finishActions.forEach((item, index) => {
    const error = validateV2FinishAction_(item, index);
    if (error) {
      rejectedFinishActions.push(rejectedFinishAction_(item, error.code, error.message));
      return;
    }
    if (!recoverableReady) {
      rejectedFinishActions.push(rejectedFinishAction_(
        item,
        'BACKEND_UPDATE_REQUIRED',
        'Finish actions require recoverable sync apply; update the backend before retrying'
      ));
      return;
    }
    const actionId = text_(item.actionId);
    const duplicate = existingFinishActions[actionId];
    if (duplicate) {
      acceptedFinishActions.push(finishActionAck_(item, duplicate, 'duplicate'));
      return;
    }
    const resolved = resolveProduct_(item, resolver, finishResolverByProductUuid);
    if (!resolved) {
      rejectedFinishActions.push(rejectedFinishAction_(item, 'UNKNOWN_PRODUCT', 'Unknown product reference'));
      return;
    }
    const stagedItem = stageV2FinishAction_(item, resolved);
    stagedFinishActions.push(stagedItem);
    acceptedFinishActions.push(finishActionAck_(item, resolved, 'committed'));
  });

  const correctionCapability = consumptionCorrectionCapability_(
    requestContext.config
  );
  const acceptedConsumptionCorrections = [];
  const rejectedConsumptionCorrections = [];
  const stagedConsumptionCorrections = [];
  const correctionTargetsInRequest = {};
  const currentCorrectionState = correctionCapability.version &&
      consumptionCorrections.length
    ? effectiveConsumptionState_(ss, requestContext)
    : null;
  consumptionCorrections.forEach((item, index) => {
    const error = validateV2ConsumptionCorrection_(item, index);
    if (error) {
      rejectedConsumptionCorrections.push(
        rejectedConsumptionCorrection_(
          item,
          error.code,
          error.message
        )
      );
      return;
    }
    if (!correctionCapability.version || !currentCorrectionState) {
      rejectedConsumptionCorrections.push(
        rejectedConsumptionCorrection_(
          item,
          'BACKEND_UPDATE_REQUIRED',
          'Consumption corrections are not provisioned on this backend'
        )
      );
      return;
    }
    const actionId = text_(item.actionId).toLowerCase();
    const existing = currentCorrectionState.resolution.recordsById[actionId];
    if (existing) {
      if (!correctionRequestMatchesRecord_(item, existing)) {
        rejectedConsumptionCorrections.push(
          rejectedConsumptionCorrection_(
            item,
            'CORRECTION_ID_CONFLICT',
            'Correction actionId is already used by different content',
            existing.correctionId
          )
        );
        return;
      }
      acceptedConsumptionCorrections.push(
        consumptionCorrectionAck_(item, existing, 'duplicate')
      );
      return;
    }
    if (!correctionCapability.writesEnabled) {
      rejectedConsumptionCorrections.push(
        rejectedConsumptionCorrection_(
          item,
          'CORRECTION_WRITES_DISABLED',
          'Consumption correction writes are temporarily disabled'
        )
      );
      return;
    }
    if (!recoverableReady) {
      rejectedConsumptionCorrections.push(
        rejectedConsumptionCorrection_(
          item,
          'BACKEND_UPDATE_REQUIRED',
          'Consumption corrections require recoverable sync apply'
        )
      );
      return;
    }
    const targetEventId = text_(item.targetEventId).toLowerCase();
    if (correctionTargetsInRequest[targetEventId]) {
      rejectedConsumptionCorrections.push(
        rejectedConsumptionCorrection_(
          item,
          'INVALID_ITEM',
          'Only one correction per target is allowed in a request'
        )
      );
      return;
    }
    const targetState =
      currentCorrectionState.resolution.stateByTarget[targetEventId];
    if (!targetState) {
      rejectedConsumptionCorrections.push(
        rejectedConsumptionCorrection_(
          item,
          'UNKNOWN_EVENT',
          'Unknown consumption event'
        )
      );
      return;
    }
    const expectedHead = text_(
      item.expectedCorrectionHeadId
    ).toLowerCase();
    if (expectedHead !== text_(targetState.headId).toLowerCase()) {
      rejectedConsumptionCorrections.push(
        rejectedConsumptionCorrection_(
          item,
          'CORRECTION_CONFLICT',
          'The consumption entry has a newer correction revision',
          targetState.headId
        )
      );
      return;
    }
    const staged = stageV2ConsumptionCorrection_(
      item,
      targetState,
      currentCorrectionState.products,
      requestId,
      new Date()
    );
    if (staged.error) {
      rejectedConsumptionCorrections.push(
        rejectedConsumptionCorrection_(
          item,
          staged.error.code,
          staged.error.message,
          targetState.headId
        )
      );
      return;
    }
    if (
      stagedFinishActions.some(action =>
        staged.record.affectedProductIds.indexOf(
          action.legacyProductId
        ) >= 0
      )
    ) {
      rejectedConsumptionCorrections.push(
        rejectedConsumptionCorrection_(
          item,
          'CONFLICTING_ACTION',
          'A finish action for an affected product must sync separately',
          targetState.headId
        )
      );
      return;
    }
    if (
      staged.record.reopenLegacyProductId &&
      (
        stagedConsumptions.some(consumption =>
          consumption.legacyProductId ===
            staged.record.reopenLegacyProductId &&
          consumption.isFinished
        )
      )
    ) {
      rejectedConsumptionCorrections.push(
        rejectedConsumptionCorrection_(
          item,
          'REOPEN_NOT_SAFE',
          'Another queued action finishes this product',
          targetState.headId
        )
      );
      return;
    }
    correctionTargetsInRequest[targetEventId] = true;
    stagedConsumptionCorrections.push(staged.record);
    acceptedConsumptionCorrections.push(
      consumptionCorrectionAck_(item, staged.record, 'committed')
    );
  });
  let correctionProjectionEffects = [];
  if (stagedConsumptionCorrections.length) {
    const projectedState = effectiveConsumptionState_(
      ss,
      requestContext,
      stagedConsumptions,
      stagedConsumptionCorrections
    );
    correctionProjectionEffects =
      calculateCorrectionProjectionEffects_(
        context,
        projectedState,
        stagedConsumptionCorrections
      );
  }
  recordBackendPhase_(timing, 'stagingValidation', phaseStarted);

  phaseStarted = Date.now();
  const allAccepted = rejectedPurchases.length === 0 &&
    rejectedConsumptions.length === 0 &&
    rejectedFinishActions.length === 0 &&
    rejectedConsumptionCorrections.length === 0;
  const productIdMap = {};
  acceptedPurchases.forEach(item => {
    if (item.tempId && item.legacyProductId) productIdMap[item.tempId] = item.legacyProductId;
  });
  const response = {
    apiVersion: CANN.API_VERSION,
    requestId: requestId,
    success: true,
    correctionVersion: correctionCapability.version,
    correctionWritesEnabled: correctionCapability.writesEnabled,
    allAccepted: allAccepted,
    message: allAccepted ? 'Sync complete' : 'Some items were rejected',
    productIdMap: productIdMap,
    acknowledgedPurchases: acceptedPurchases,
    rejectedPurchases: rejectedPurchases,
    acknowledgedConsumptions: acceptedConsumptions,
    rejectedConsumptions: rejectedConsumptions,
    acknowledgedFinishActions: acceptedFinishActions,
    rejectedFinishActions: rejectedFinishActions,
    acknowledgedConsumptionCorrections: acceptedConsumptionCorrections,
    rejectedConsumptionCorrections: rejectedConsumptionCorrections
  };
  recordBackendPhase_(timing, 'responseConstruction', phaseStarted);

  const now = new Date();
  const hasCoreMutation =
    staged.accepted.length > 0 || stagedConsumptions.length > 0 ||
    stagedFinishActions.length > 0 ||
    stagedConsumptionCorrections.length > 0;
  if (recoverableReady && hasCoreMutation) {
    applyRecoverableSyncLocked_({
      runtimeContext: requestContext,
      productContext: context,
      stagedPurchases: staged.accepted,
      stagedConsumptions: stagedConsumptions,
      stagedFinishActions: stagedFinishActions,
      stagedConsumptionCorrections: stagedConsumptionCorrections,
      correctionProjectionEffects: correctionProjectionEffects,
      kind: 'ANDROID_V2',
      apiVersion: CANN.API_VERSION,
      requestId: requestId,
      response: response,
      formRefreshRequired: staged.accepted.length > 0 ||
        stagedFinishActions.length > 0 ||
        stagedConsumptionCorrections.length > 0,
      ledger: {
        startedAtEpochMillis: started,
        purchaseCount: purchases.length,
        consumptionCount: consumptions.length,
        result: allAccepted ? 'ACCEPTED' : 'PARTIAL',
        durationMs: Date.now() - started,
        errorCode: ''
      },
      timing: timing
    });
  } else {
    if (!recoverableReady) {
      phaseStarted = Date.now();
      appendPurchaseRows_(context, staged.accepted, now, requestContext.config.TAX_RATE);
      recordBackendPhase_(timing, 'purchaseAppend', phaseStarted);
      appendConsumptionRows_(ss, stagedConsumptions, false, timing, requestContext);
      phaseStarted = Date.now();
      applyProductEffects_(context, stagedConsumptions);
      applyFinishActions_(context, stagedFinishActions);
      recordBackendPhase_(timing, 'productEffects', phaseStarted);
      if (staged.accepted.length || stagedFinishActions.length) {
        phaseStarted = Date.now();
        updateFormAndDescriptionLocked_(ss, requestContext);
        recordBackendPhase_(timing, 'formRefresh', phaseStarted);
      }
    }

    // Empty, rejected-only, and exact-duplicate requests have no multi-sheet
    // core mutation to protect. Their only durable change is the idempotent
    // ledger upsert, so a journal and two Advanced Sheets batches would add
    // latency without improving recovery. A retry overwrites the same ledger
    // row, while pending work from a predecessor was already repaired above.
    const ledgerStarted = Date.now();
    upsertLedger_(ss, requestId, purchases.length, consumptions.length, allAccepted ? 'ACCEPTED' : 'PARTIAL', ledgerStarted - started, '', requestContext);
    recordBackendPhase_(timing, 'ledgerUpdate', ledgerStarted);
  }
  bumpMutationWatermark_();
  return response;
}

function v2RequestFailure_(requestId, code, message) {
  return {
    apiVersion: CANN.API_VERSION,
    requestId: requestId || null,
    success: false,
    allAccepted: false,
    message: message,
    errorCode: code,
    productIdMap: {},
    acknowledgedPurchases: [],
    rejectedPurchases: [],
    acknowledgedConsumptions: [],
    rejectedConsumptions: [],
    acknowledgedFinishActions: [],
    rejectedFinishActions: [],
    acknowledgedConsumptionCorrections: [],
    rejectedConsumptionCorrections: []
  };
}

function preflightFailureOutput_(payload, apiVersion, failure, environment, timing) {
  const responseConstructionStarted = Date.now();
  const response = apiVersion === 1
    ? legacyFailure_(failure.message)
    : v2RequestFailure_(text_(payload.requestId), failure.code, failure.message);
  response.environment = environment;
  recordBackendPhase_(timing, 'responseConstruction', responseConstructionStarted);
  if (apiVersion === CANN.API_VERSION) addServerTimingFields_(response, timing, environment);
  const responseRoutingStarted = Date.now();
  const output = jsonOutput_(response);
  recordBackendPhase_(timing, 'responseRouting', responseRoutingStarted);
  logBackendTiming_(timing, 'rejected', {
    apiVersion: apiVersion,
    environment: environment,
    requestId: text_(payload.requestId) || undefined,
    errorCode: failure.code
  });
  return output;
}

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
    updateFormAndDescriptionLocked_(ss);
    bumpMutationWatermark_();
  } finally { lock.releaseLock(); }
}

// -----------------------------------------------------------------------------
// Migration and reconciliation
// -----------------------------------------------------------------------------

function runReliabilityMigration() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    ensureCoreSchema_(ss);
    const purchaseResult = backfillPurchases_(ss);
    const eventResult = backfillConsumptionEvents_(ss);
    const interactionSummary = rebuildInteractionSummaryLocked_(ss, false);
    applySheetSafety_(ss);
    updateFormAndDescriptionLocked_(ss);
    const reconciliation = reconcileReliabilityMigration_(ss);
    const result = {
      purchaseBackfill: purchaseResult,
      eventBackfill: eventResult,
      interactionSummary: interactionSummary,
      reconciliation: reconciliation
    };
    bumpMutationWatermark_();
    console.log(JSON.stringify(result));
    return result;
  } finally {
    lock.releaseLock();
  }
}

/**
 * Explicit additive migration for the fast GET interaction projection.
 *
 * Run this editor function while the prior deployment remains active. It leaves
 * the Config marker at 0, so both the prior deployment and this source keep using
 * the legacy GET calculation. After this returns with zero differences, deploy
 * the Phase 4 source, then run enableInteractionSummaryFastPath().
 */
function runInteractionSummaryMigration() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const result = rebuildInteractionSummaryLocked_(ss, false);
    console.log(JSON.stringify({
      type: 'interaction_summary_prepared',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

/**
 * Explicit maintenance entrypoint. Rebuilds only from canonical history and
 * deliberately leaves the fast path disabled until the separate enable step.
 */
function rebuildInteractionSummary() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const result = rebuildInteractionSummaryLocked_(ss, false);
    console.log(JSON.stringify({
      type: 'interaction_summary_rebuilt',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

/**
 * Final rollout step. Run only after this Phase 4 source is active on the web
 * deployment. It rebuilds once more under the lock, covering any requests that
 * arrived between preparation and deployment, then enables the fast GET path.
 */
function enableInteractionSummaryFastPath() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const result = rebuildInteractionSummaryLocked_(ss, true);
    console.log(JSON.stringify({
      type: 'interaction_summary_fast_path_enabled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

/** Immediate rollback switch. Leaves the additive column and data in place. */
function disableInteractionSummaryFastPath() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    setConfigValue_(
      ss,
      'INTERACTION_SUMMARY_VERSION',
      0,
      'Purchases interaction-summary version'
    );
    SpreadsheetApp.flush();
    const result = {
      summaryVersion: 0,
      fastPathEnabled: false,
      fallback: 'LEGACY_HISTORY'
    };
    console.log(JSON.stringify({
      type: 'interaction_summary_fast_path_disabled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

/** Read-only maintenance entrypoint. It does not repair or mark readiness. */
function reconcileInteractionSummary() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const result = reconcileInteractionSummary_(ss);
    console.log(JSON.stringify({
      type: 'interaction_summary_reconciled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function rebuildInteractionSummaryLocked_(ss, enableFastPath) {
  // Fail over to the legacy read path before any projection cells move. This
  // marker is ignored by the previous deployment and understood by this one.
  setConfigValue_(
    ss,
    'INTERACTION_SUMMARY_VERSION',
    0,
    'Purchases interaction-summary version'
  );
  SpreadsheetApp.flush();

  const purchases = ensureInteractionSummarySchema_(ss);
  const headers = headerMap_(purchases);
  requireExactHeaders_(headers, CANN.PURCHASE_HEADERS, CANN.SHEETS.PURCHASES);

  // This is the exact legacy GET calculation retained only for migration and
  // explicit reconciliation. It is never called by the normal GET path.
  const legacy = latestInteractions_(ss);
  const canonicalResult = canonicalInteractionSummary_(ss);
  if (canonicalResult.invalidEvents) {
    throw new Error(
      'INTERACTION_SUMMARY_MIGRATION_BLOCKED: canonical history contains ' +
      canonicalResult.invalidEvents + ' invalid event row(s)'
    );
  }
  const legacyComparison = compareInteractionSummaryMaps_(
    legacy,
    canonicalResult.interactions
  );
  if (legacyComparison.length) {
    throw new Error(
      'INTERACTION_SUMMARY_MIGRATION_BLOCKED: canonical state differs from legacy GET: ' +
      JSON.stringify(legacyComparison.slice(0, 10))
    );
  }

  const writeResult = writeInteractionSummary_(
    purchases,
    headers,
    canonicalResult.interactions
  );
  SpreadsheetApp.flush();

  const reconciliation = reconcileInteractionSummary_(ss);
  if (reconciliation.differences.length) {
    throw new Error(
      'INTERACTION_SUMMARY_MIGRATION_BLOCKED: rebuilt summary did not reconcile: ' +
      JSON.stringify(reconciliation.differences.slice(0, 10))
    );
  }

  const fastPathEnabled = enableFastPath === true;
  if (fastPathEnabled) {
    setConfigValue_(
      ss,
      'INTERACTION_SUMMARY_VERSION',
      CANN.INTERACTION_SUMMARY_VERSION,
      'Purchases interaction-summary version'
    );
    SpreadsheetApp.flush();
  }

  return {
    preparedSummaryVersion: CANN.INTERACTION_SUMMARY_VERSION,
    configSummaryVersion: fastPathEnabled
      ? CANN.INTERACTION_SUMMARY_VERSION
      : 0,
    canonicalEventRows: canonicalResult.eventRows,
    validCanonicalEvents: canonicalResult.validEvents,
    invalidCanonicalEvents: canonicalResult.invalidEvents,
    summarizedProducts: Object.keys(canonicalResult.interactions).length,
    purchaseRows: writeResult.purchaseRows,
    populatedSummaries: writeResult.populatedSummaries,
    legacyComparisonDifferences: legacyComparison.length,
    reconciliationDifferences: reconciliation.differences.length,
    readyToEnable: true,
    fastPathEnabled: fastPathEnabled
  };
  bumpMutationWatermark_();
  return result;
}

function ensureInteractionSummarySchema_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const summaryColumn = CANN.PURCHASE_HEADERS.length;
  const expectedPrefix = CANN.PURCHASE_HEADERS.slice(0, summaryColumn - 1);
  const currentHeaders = headerMap_(sheet);
  requireExactHeaders_(currentHeaders, expectedPrefix, CANN.SHEETS.PURCHASES);
  if (sheet.getMaxColumns() < summaryColumn) {
    sheet.insertColumnsAfter(
      sheet.getMaxColumns(),
      summaryColumn - sheet.getMaxColumns()
    );
  }
  const range = sheet.getRange(1, summaryColumn);
  const existing = text_(range.getValue());
  const expected = CANN.PURCHASE_HEADERS[summaryColumn - 1];
  if (existing && existing !== expected) {
    throw new Error(
      'SCHEMA_MISMATCH: ' + CANN.SHEETS.PURCHASES + ' column ' +
      summaryColumn + ' expected ' + expected + ' but found ' + existing
    );
  }
  if (!existing) range.setValue(expected);
  return sheet;
}

function canonicalInteractionSummary_(ss) {
  if (typeof PropertiesService === 'undefined') {
    return rawCanonicalInteractionSummary_(ss);
  }
  const runtimeConfig = readAndAssertRuntimeConfig_(ss, environment_());
  const capability = consumptionCorrectionCapability_(
    runtimeConfig.values
  );
  if (!capability.version) return rawCanonicalInteractionSummary_(ss);
  const state = effectiveConsumptionState_(ss);
  const interactions = {};
  state.resolution.effectiveEvents.forEach(event => {
    const legacyProductId = event.product.productId;
    const existing = interactions[legacyProductId];
    if (
      !existing ||
      event.occurredAtEpochMillis > existing.lastLoggedAtEpochMillis
    ) {
      interactions[legacyProductId] = {
        lastLoggedAtEpochMillis: event.occurredAtEpochMillis,
        lastQuantity: event.quantity,
        timestamp: new Date(event.occurredAtEpochMillis),
        canonicalRow: event.canonicalRow
      };
    }
  });
  return {
    interactions: interactions,
    eventRows: state.snapshot.eventRows.length,
    correctionRows: state.snapshot.correctionRows.length,
    validEvents: state.resolution.effectiveEvents.length,
    invalidEvents: 0
  };
}

function rawCanonicalInteractionSummary_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const headers = headerMap_(sheet);
  requireExactHeaders_(headers, CANN.EVENT_HEADERS, CANN.SHEETS.EVENTS);
  const lastRow = sheet.getLastRow();
  const rows = lastRow < 2
    ? []
    : sheet.getRange(2, 1, lastRow - 1, sheet.getLastColumn()).getValues();
  const interactions = {};
  let validEvents = 0;
  let invalidEvents = 0;

  rows.forEach((row, index) => {
    if (!row.some(cell => cell !== '' && cell != null)) return;
    const legacyProductId = text_(value_(row, headers, 'Legacy Product ID'));
    const timestamp = value_(row, headers, 'Timestamp');
    const timestampMillis = timestampMillisOrNull_(timestamp);
    const lastQuantity = optionalFiniteNumber_(value_(row, headers, 'Uses'));
    if (!legacyProductId || timestampMillis == null || lastQuantity == null) {
      invalidEvents++;
      return;
    }
    validEvents++;
    const existing = interactions[legacyProductId];
    // Strictly greater preserves the deployed rule: for equal timestamps, the
    // earlier physical canonical row keeps its quantity.
    if (!existing || timestampMillis > existing.lastLoggedAtEpochMillis) {
      interactions[legacyProductId] = {
        lastLoggedAtEpochMillis: timestampMillis,
        lastQuantity: lastQuantity,
        timestamp: new Date(timestampMillis),
        canonicalRow: index + 2
      };
    }
  });

  return {
    interactions: interactions,
    eventRows: rows.length,
    validEvents: validEvents,
    invalidEvents: invalidEvents
  };
}

function writeInteractionSummary_(sheet, headers, interactions) {
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return { purchaseRows: 0, populatedSummaries: 0 };
  const rows = sheet.getRange(
    2,
    1,
    lastRow - 1,
    sheet.getLastColumn()
  ).getValues();
  const latestValues = [];
  const quantityValues = [];
  let purchaseRows = 0;
  let populatedSummaries = 0;

  rows.forEach(row => {
    const legacyProductId = text_(value_(row, headers, 'Product ID'));
    const interaction = legacyProductId ? interactions[legacyProductId] : null;
    if (legacyProductId) purchaseRows++;
    if (interaction) populatedSummaries++;
    latestValues.push([interaction ? interaction.timestamp : '']);
    quantityValues.push([
      interaction ? interaction.lastQuantity : ''
    ]);
  });

  sheet.getRange(
    2,
    headers['Most recent use'] + 1,
    latestValues.length,
    1
  ).setValues(latestValues);
  sheet.getRange(
    2,
    headers['Last quantity'] + 1,
    quantityValues.length,
    1
  ).setValues(quantityValues);
  return {
    purchaseRows: purchaseRows,
    populatedSummaries: populatedSummaries
  };
}

function reconcileInteractionSummary_(ss) {
  const canonicalResult = canonicalInteractionSummary_(ss);
  const expected = canonicalResult.interactions;
  const sheet = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const headers = headerMap_(sheet);
  requireExactHeaders_(headers, CANN.PURCHASE_HEADERS, CANN.SHEETS.PURCHASES);
  const lastRow = sheet.getLastRow();
  const rows = lastRow < 2
    ? []
    : sheet.getRange(2, 1, lastRow - 1, sheet.getLastColumn()).getValues();
  const differences = [];
  const seen = {};
  let purchaseRows = 0;

  rows.forEach((row, index) => {
    const legacyProductId = text_(value_(row, headers, 'Product ID'));
    if (!legacyProductId) return;
    purchaseRows++;
    if (seen[legacyProductId]) {
      differences.push({
        type: 'DUPLICATE_PRODUCT_ID',
        legacyProductId: legacyProductId,
        rowNumber: index + 2,
        firstRowNumber: seen[legacyProductId]
      });
      return;
    }
    seen[legacyProductId] = index + 2;
    const actualMillis = timestampMillisOrNull_(
      value_(row, headers, 'Most recent use')
    );
    const actualQuantity = optionalFiniteNumber_(
      value_(row, headers, 'Last quantity')
    );
    const expectedState = expected[legacyProductId] || null;
    if (!interactionStateMatches_(
      actualMillis,
      actualQuantity,
      expectedState
    )) {
      differences.push({
        type: 'STATE_MISMATCH',
        legacyProductId: legacyProductId,
        rowNumber: index + 2,
        expected: interactionStateForReport_(expectedState),
        actual: {
          lastLoggedAtEpochMillis: actualMillis,
          lastQuantity: actualQuantity
        }
      });
    }
  });

  Object.keys(expected).forEach(legacyProductId => {
    if (!seen[legacyProductId]) {
      differences.push({
        type: 'MISSING_PRODUCT',
        legacyProductId: legacyProductId,
        canonicalRow: expected[legacyProductId].canonicalRow
      });
    }
  });

  return {
    canonicalEventRows: canonicalResult.eventRows,
    validCanonicalEvents: canonicalResult.validEvents,
    invalidCanonicalEvents: canonicalResult.invalidEvents,
    purchaseRows: purchaseRows,
    summarizedProducts: Object.keys(expected).length,
    differences: differences
  };
}

function compareInteractionSummaryMaps_(left, right) {
  const differences = [];
  const keys = {};
  Object.keys(left || {}).forEach(key => { keys[key] = true; });
  Object.keys(right || {}).forEach(key => { keys[key] = true; });
  Object.keys(keys).sort().forEach(legacyProductId => {
    const leftState = (left || {})[legacyProductId] || null;
    const rightState = (right || {})[legacyProductId] || null;
    const leftMillis = leftState
      ? timestampMillisOrNull_(leftState.lastLoggedAtEpochMillis)
      : null;
    const leftQuantity = leftState
      ? optionalFiniteNumber_(leftState.lastQuantity)
      : null;
    if (!interactionStateMatches_(leftMillis, leftQuantity, rightState)) {
      differences.push({
        legacyProductId: legacyProductId,
        legacy: interactionStateForReport_(leftState),
        canonical: interactionStateForReport_(rightState)
      });
    }
  });
  return differences;
}

function interactionStateMatches_(actualMillis, actualQuantity, expectedState) {
  if (!expectedState) return actualMillis == null && actualQuantity == null;
  const expectedMillis = timestampMillisOrNull_(
    expectedState.lastLoggedAtEpochMillis
  );
  const expectedQuantity = optionalFiniteNumber_(expectedState.lastQuantity);
  return actualMillis === expectedMillis &&
    actualQuantity != null &&
    expectedQuantity != null &&
    Math.abs(actualQuantity - expectedQuantity) <= 1e-9;
}

function interactionStateForReport_(state) {
  if (!state) return null;
  return {
    lastLoggedAtEpochMillis: timestampMillisOrNull_(
      state.lastLoggedAtEpochMillis
    ),
    lastQuantity: optionalFiniteNumber_(state.lastQuantity)
  };
}

function backfillPurchases_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const headers = headerMap_(sheet);
  const lastRow = sheet.getLastRow();
  const rows = lastRow < 2 ? [] : sheet.getRange(2, 1, lastRow - 1, sheet.getLastColumn()).getValues();
  const responseTotals = responseMetrics_(ss);
  const ids = {};
  let backfilled = 0;
  let purchaseRows = 0;
  const idValues = [], usesValues = [], finalValues = [], latestValues = [], identityValues = [];

  rows.forEach((row, index) => {
    const rowNumber = index + 2;
    const name = text_(value_(row, headers, 'Product name'));
    const legacyId = text_(value_(row, headers, 'Product ID'));
    const preservedIdentity = [
      value_(row, headers, 'Product UUID'),
      value_(row, headers, 'Client Action UUID'),
      value_(row, headers, 'Created At'),
      value_(row, headers, 'Finished At')
    ];
    if (!name) {
      idValues.push([value_(row, headers, 'Product ID')]);
      usesValues.push([value_(row, headers, 'Uses')]);
      finalValues.push([value_(row, headers, 'Final cost')]);
      latestValues.push([value_(row, headers, 'Most recent use')]);
      identityValues.push(preservedIdentity);
      return;
    }
    purchaseRows++;
    if (!legacyId) {
      recordMigrationIssue_(ss, 'BLANK_PRODUCT_ID', sheet.getName(), rowNumber, '', 'Purchase row retained without guessing an ID');
      idValues.push([value_(row, headers, 'Product ID')]);
      usesValues.push([value_(row, headers, 'Uses')]);
      finalValues.push([value_(row, headers, 'Final cost')]);
      latestValues.push([value_(row, headers, 'Most recent use')]);
      identityValues.push(preservedIdentity);
      return;
    }
    if (ids[legacyId]) {
      recordMigrationIssue_(ss, 'DUPLICATE_PRODUCT_ID', sheet.getName(), rowNumber, legacyId, 'Duplicate display ID');
      idValues.push([value_(row, headers, 'Product ID')]);
      usesValues.push([value_(row, headers, 'Uses')]);
      finalValues.push([value_(row, headers, 'Final cost')]);
      latestValues.push([value_(row, headers, 'Most recent use')]);
      identityValues.push(preservedIdentity);
      return;
    }
    ids[legacyId] = true;
    const productUuid = text_(value_(row, headers, 'Product UUID')) || Utilities.getUuid();
    const actionUuid = text_(value_(row, headers, 'Client Action UUID')) || Utilities.getUuid();
    const createdAt = value_(row, headers, 'Created At') || value_(row, headers, 'Date') || new Date();
    const metrics = responseTotals[legacyId] || { uses: 0, latest: '' };
    const cost = finiteNumber_(value_(row, headers, 'Pre-tax cost'));
    const postTax = truthy_(value_(row, headers, 'Post-tax'));
    const finalCost = cost == null ? '' : (postTax ? cost : cost * (1 + taxRate_(ss)));
    idValues.push([legacyId]);
    usesValues.push([metrics.uses]);
    finalValues.push([finalCost]);
    latestValues.push([metrics.latest]);
    identityValues.push([productUuid, actionUuid, createdAt, value_(row, headers, 'Finished At') || '']);
    if (!text_(value_(row, headers, 'Product UUID')) || !text_(value_(row, headers, 'Client Action UUID'))) backfilled++;
  });

  if (rows.length) {
    sheet.getRange(2, headers['Product ID'] + 1, idValues.length, 1).setValues(idValues);
    sheet.getRange(2, headers['Uses'] + 1, usesValues.length, 1).setValues(usesValues);
    sheet.getRange(2, headers['Final cost'] + 1, finalValues.length, 1).setValues(finalValues);
    sheet.getRange(2, headers['Most recent use'] + 1, latestValues.length, 1).setValues(latestValues);
    sheet.getRange(2, headers['Product UUID'] + 1, identityValues.length, 4).setValues(identityValues);
  }
  return { purchaseRows: purchaseRows, identitiesBackfilled: backfilled };
}

function backfillConsumptionEvents_(ss) {
  const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const headers = headerMap_(responses);
  requireHeaders_(headers, ['Timestamp', 'Product', 'Uses']);
  const cutoffRow = responses.getLastRow();
  const rows = cutoffRow < 2 ? [] : responses.getRange(2, 1, cutoffRow - 1, responses.getLastColumn()).getValues();
  const products = productContext_(ss);
  const eventSheet = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const existing = eventContext_(ss).eventIds;
  const append = [];
  let unresolved = 0;

  rows.forEach((row, index) => {
    const rowNumber = index + 2;
    if (!row.some(cell => cell !== '' && cell != null)) return;
    const eventId = deterministicLegacyEventUuid_(ss.getId(), responses.getName(), rowNumber);
    if (existing.has(eventId)) return;
    const legacyId = text_(value_(row, headers, 'Product'));
    const product = products.byLegacyId[legacyId];
    if (!product) {
      unresolved++;
      recordMigrationIssue_(ss, 'UNKNOWN_PRODUCT', responses.getName(), rowNumber, legacyId, 'Historical row preserved; canonical relationship unresolved');
      return;
    }
    const rawTimestamp = value_(row, headers, 'Timestamp');
    const timestamp = dateOrNull_(rawTimestamp);
    if (!timestamp) {
      unresolved++;
      recordMigrationIssue_(ss, 'INVALID_TIMESTAMP', responses.getName(), rowNumber, legacyId, 'Historical row preserved; timestamp is missing or invalid');
      return;
    }
    append.push({
      eventId: eventId,
      timestamp: timestamp,
      localDate: formatDate_(timestamp),
      localTime: formatTime_(timestamp),
      productUuid: product.productUuid,
      legacyProductId: legacyId,
      uses: finiteNumberOr_(value_(row, headers, 'Uses'), 0),
      weightCode: text_(value_(row, headers, 'Weight code')),
      isFinished: truthy_(value_(row, headers, 'Mark as Finished?')),
      source: 'FORM_LEGACY',
      requestId: '',
      legacySourceSheet: responses.getName(),
      legacySourceRow: rowNumber,
      compatibilityRow: null
    });
  });
  appendEventRows_(eventSheet, append);
  return { cutoffRow: cutoffRow, eventsBackfilled: append.length, unresolvedRows: unresolved };
}

function reconcileReliabilityMigration_(ss) {
  const purchases = productContext_(ss);
  const responses = responseMetrics_(ss);
  const events = canonicalMetrics_(ss);
  const differences = [];
  Object.keys(purchases.byLegacyId).forEach(legacyId => {
    const response = responses[legacyId] || { uses: 0, count: 0, earliest: '', latest: '' };
    const event = events[legacyId] || { uses: 0, count: 0, earliest: '', latest: '' };
    if (Math.abs(response.uses - event.uses) > 1e-9 || response.count !== event.count || String(response.earliest) !== String(event.earliest) || String(response.latest) !== String(event.latest)) {
      differences.push({ legacyProductId: legacyId, response: response, canonical: event });
    }
  });
  const reportSheet = requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT);
  const reportHeaders = headerMap_(reportSheet);
  const unresolvedRows = readDataRows_(reportSheet).filter(row =>
    reportHeaders['Resolved At'] === undefined ||
    !value_(row, reportHeaders, 'Resolved At')
  ).length;
  return {
    purchaseCount: Object.keys(purchases.byLegacyId).length,
    responseEventCount: Object.keys(responses).reduce((sum, key) => sum + responses[key].count, 0),
    canonicalEventCount: Object.keys(events).reduce((sum, key) => sum + events[key].count, 0),
    unresolvedRows: unresolvedRows,
    differences: differences
  };
}

// -----------------------------------------------------------------------------
// Schema and sheet operations
// -----------------------------------------------------------------------------

function ensureCoreSchema_(ss) {
  const purchases = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  ensureHeaders_(purchases, CANN.PURCHASE_HEADERS);
  ensureSheet_(ss, CANN.SHEETS.EVENTS, CANN.EVENT_HEADERS);
  ensureSheet_(ss, CANN.SHEETS.LEDGER, CANN.LEDGER_HEADERS);
  ensureSheet_(ss, CANN.SHEETS.APPLY_JOURNAL, CANN.APPLY_JOURNAL_HEADERS);
  ensureSheet_(ss, CANN.SHEETS.MIGRATION_REPORT, CANN.REPORT_HEADERS);
  const config = ensureSheet_(ss, CANN.SHEETS.CONFIG, ['Key', 'Value', 'Description']);
  if (config.getLastRow() < 2) {
    config.getRange(2, 1, 11, 3).setValues([
      ['ENVIRONMENT', environment_(), 'Runtime environment marker'],
      ['TAX_RATE', 0.13, 'Tax rate used for final-cost values'],
      ['TIME_ZONE', CANN.TIME_ZONE, 'Canonical local timezone'],
      ['SCHEMA_VERSION', CANN.SCHEMA_VERSION, 'Spreadsheet schema version'],
      ['INTERACTION_SUMMARY_VERSION', 0, 'Purchases interaction-summary version'],
      [CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY, 0, 'Recoverable multi-sheet apply version'],
      [CANN.PENDING_APPLY_KEY, '', 'Apply UUID awaiting finalization'],
      [
        CANN.CONSUMPTION_CORRECTION_VERSION_KEY,
        0,
        'Append-only consumption correction schema version'
      ],
      [
        CANN.CONSUMPTION_CORRECTION_WRITES_KEY,
        false,
        'Whether new consumption correction writes are accepted'
      ],
      ['MAX_BATCH_SIZE', CANN.MAX_BATCH_SIZE, 'Maximum v2 items per request'],
      ['LOCK_TIMEOUT_MS', CANN.LOCK_TIMEOUT_MS, 'Shared mutation lock timeout']
    ]);
  } else {
    ensureConfigKey_(ss, CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY, 0, 'Recoverable multi-sheet apply version');
    ensureConfigKey_(ss, CANN.PENDING_APPLY_KEY, '', 'Apply UUID awaiting finalization');
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
  }
}

function historyEventResponse_(event, query) {
  const response = {
    eventUuid: event.eventUuid,
    occurredAtEpochMillis: event.occurredAtEpochMillis,
    localDate: event.localDate,
    localTime: event.localTime,
    productUuid: event.product.productUuid,
    productId: event.product.productId,
    productName: event.product.name,
    productType: event.product.type,
    quantity: event.quantity,
    weightCode: event.weightCode,
    finished: event.finished,
    source: event.source
  };
  if (query.analyticsVersion >= 2) {
    response.lifecycleState = event.lifecycleState || 'ORIGINAL';
    response.correctionHeadId = event.correctionHeadId || null;
    response.revision = Number(event.revision) || 0;
    response.reopenEligible = event.reopenEligible === true;
    if (query.includeAudit) {
      response.auditRevisions = event.auditRevisions || [];
    }
  }
  return response;
}

function ensureMigrationResolutionSchema_(ss) {
  const expected = CANN.REPORT_HEADERS.concat(
    CANN.MIGRATION_RESOLUTION_HEADERS
  );
  const sheet = requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT);
  ensureHeaders_(sheet, expected);
  return sheet;
}

function assertSpreadsheetTimeZone_(ss, config) {
  const spreadsheetTimeZone = text_(ss.getSpreadsheetTimeZone());
  const configTimeZone = text_(
    config && config.TIME_ZONE !== undefined
      ? config.TIME_ZONE
      : configValue_(ss, 'TIME_ZONE', '')
  );
  if (spreadsheetTimeZone !== CANN.TIME_ZONE ||
      configTimeZone !== CANN.TIME_ZONE) {
    throw new Error(
      'CONFIGURATION_ERROR: spreadsheet and Config TIME_ZONE must both be ' +
      CANN.TIME_ZONE + ' (spreadsheet=' + spreadsheetTimeZone +
      ', config=' + configTimeZone + ')'
    );
  }
}

function provisionRecoverableDateFormats_(ss) {
  const specifications = [
    [CANN.SHEETS.PURCHASES, ['Most recent use', 'Created At', 'Finished At']],
    [CANN.SHEETS.RESPONSES, ['Timestamp']],
    [CANN.SHEETS.EVENTS, ['Timestamp']],
    [CANN.SHEETS.LEDGER, ['Received At']],
    [CANN.SHEETS.APPLY_JOURNAL, ['Core Committed At', 'Completed At']]
  ];
  const corrections = ss.getSheetByName(CANN.SHEETS.CORRECTIONS);
  if (corrections) {
    specifications.push([
      CANN.SHEETS.CORRECTIONS,
      ['Replacement Timestamp', 'Created At']
    ]);
  }
  specifications.forEach(specification => {
    const sheet = requiredSheet_(ss, specification[0]);
    const headers = headerMap_(sheet);
    specification[1].forEach(header => {
      if (headers[header] === undefined) {
        throw new Error(
          'SCHEMA_MISMATCH: missing date header ' + header +
          ' in ' + sheet.getName()
        );
      }
      sheet.getRange(
        2,
        headers[header] + 1,
        Math.max(1, sheet.getMaxRows() - 1),
        1
      ).setNumberFormat('yyyy-mm-dd hh:mm:ss');
    });
  });
}

/**
 * Read-only schema assertion for HTTP execution. Provisioning and migrations
 * remain responsible for creating sheets, adding columns, and formatting.
 */
function readAndAssertRuntimeConfig_(ss, expectedEnvironment) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
  const headers = headerMap_(sheet);
  requireExactHeaders_(headers, ['Key', 'Value', 'Description'], CANN.SHEETS.CONFIG);
  const values = configValuesFromSheet_(sheet, headers);
  if (text_(values.ENVIRONMENT) !== expectedEnvironment) {
    throw new Error('CONFIGURATION_ERROR: Config ENVIRONMENT mismatch');
  }
  return { sheet: sheet, headers: headers, values: values };
}

function assertRuntimeSchema_(ss, expectedEnvironment, validatedConfig) {
  const runtimeConfig = validatedConfig || readAndAssertRuntimeConfig_(ss, expectedEnvironment);
  const sheets = {
    purchases: requiredSheet_(ss, CANN.SHEETS.PURCHASES),
    responses: requiredSheet_(ss, CANN.SHEETS.RESPONSES),
    events: requiredSheet_(ss, CANN.SHEETS.EVENTS),
    ledger: requiredSheet_(ss, CANN.SHEETS.LEDGER),
    config: runtimeConfig.sheet,
    migrationReport: requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT)
  };
  const headers = {
    purchases: headerMap_(sheets.purchases),
    responses: headerMap_(sheets.responses),
    events: headerMap_(sheets.events),
    ledger: headerMap_(sheets.ledger),
    config: runtimeConfig.headers,
    migrationReport: headerMap_(sheets.migrationReport)
  };

  const config = runtimeConfig.values;
  assertSupportedSchemaVersion_(config);
  const summaryReady = interactionSummaryReady_(config);
  const recoverableApplyReady = recoverableSyncApplyReady_(config);
  const correctionCapability = consumptionCorrectionCapability_(config);
  if (correctionCapability.version && !summaryReady) {
    throw new Error(
      'SCHEMA_MISMATCH: consumption corrections require the ' +
      'interaction-summary projection'
    );
  }
  if (recoverableApplyReady) assertSpreadsheetTimeZone_(ss, config);
  requireExactHeaders_(
    headers.purchases,
    summaryReady ? CANN.PURCHASE_HEADERS : CANN.PURCHASE_HEADERS.slice(0, -1),
    CANN.SHEETS.PURCHASES
  );
  requireHeaders_(headers.responses, ['Timestamp', 'Product', 'Uses']);
  if (recoverableApplyReady) {
    requireCompatibilityIdentityHeaders_(headers.responses);
    sheets.applyJournal = requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
    headers.applyJournal = headerMap_(sheets.applyJournal);
    requireExactHeaders_(headers.applyJournal, CANN.APPLY_JOURNAL_HEADERS, CANN.SHEETS.APPLY_JOURNAL);
  }
  requireExactHeaders_(headers.events, CANN.EVENT_HEADERS, CANN.SHEETS.EVENTS);
  if (correctionCapability.version === CANN.CONSUMPTION_CORRECTION_VERSION) {
    sheets.corrections = requiredSheet_(ss, CANN.SHEETS.CORRECTIONS);
    headers.corrections = headerMap_(sheets.corrections);
    requireExactHeaders_(
      headers.corrections,
      CANN.CORRECTION_HEADERS,
      CANN.SHEETS.CORRECTIONS
    );
  }
  requireExactHeaders_(headers.ledger, CANN.LEDGER_HEADERS, CANN.SHEETS.LEDGER);
  requireExactHeaders_(headers.config, ['Key', 'Value', 'Description'], CANN.SHEETS.CONFIG);
  requireExactHeaders_(headers.migrationReport, CANN.REPORT_HEADERS, CANN.SHEETS.MIGRATION_REPORT);

  const configuredEnvironment = text_(config.ENVIRONMENT);
  if (expectedEnvironment && configuredEnvironment !== expectedEnvironment) {
    throw new Error('CONFIGURATION_ERROR: Config ENVIRONMENT mismatch');
  }
  config.INTERACTION_SUMMARY_READY = summaryReady;
  config.RECOVERABLE_SYNC_APPLY_READY = recoverableApplyReady;
  config.CONSUMPTION_CORRECTION_VERSION = correctionCapability.version;
  config.CONSUMPTION_CORRECTION_WRITES_ENABLED =
    correctionCapability.writesEnabled;
  config.TAX_RATE = finiteNumberOr_(config.TAX_RATE, 0.13);
  return { sheets: sheets, headers: headers, config: config };
}

function assertSupportedSchemaVersion_(config) {
  if (Number((config || {}).SCHEMA_VERSION) !== CANN.SCHEMA_VERSION) {
    throw new Error('SCHEMA_MISMATCH: Config SCHEMA_VERSION must be ' + CANN.SCHEMA_VERSION);
  }
}

function interactionSummaryReady_(config) {
  const raw = (config || {}).INTERACTION_SUMMARY_VERSION;
  if (raw == null || raw === '' || Number(raw) === 0) return false;
  if (Number(raw) !== CANN.INTERACTION_SUMMARY_VERSION) {
    throw new Error(
      'SCHEMA_MISMATCH: unsupported Config INTERACTION_SUMMARY_VERSION ' + raw
    );
  }
  return true;
}

function recoverableSyncApplyReady_(config) {
  const raw = (config || {})[CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY];
  if (raw == null || raw === '' || Number(raw) === 0) return false;
  if (Number(raw) !== CANN.RECOVERABLE_SYNC_APPLY_VERSION) {
    throw new Error(
      'SCHEMA_MISMATCH: unsupported Config ' +
      CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY + ' ' + raw
    );
  }
  return true;
}

function consumptionCorrectionCapability_(config) {
  const values = config || {};
  const rawVersion = values[CANN.CONSUMPTION_CORRECTION_VERSION_KEY];
  const version = rawVersion == null || rawVersion === ''
    ? 0
    : Number(rawVersion);
  if (
    version !== 0 &&
    version !== CANN.CONSUMPTION_CORRECTION_VERSION
  ) {
    throw new Error(
      'SCHEMA_MISMATCH: unsupported Config ' +
      CANN.CONSUMPTION_CORRECTION_VERSION_KEY + ' ' + rawVersion
    );
  }
  const rawWrites = values[CANN.CONSUMPTION_CORRECTION_WRITES_KEY];
  const writesText = text_(rawWrites).toLowerCase();
  if (
    rawWrites != null &&
    rawWrites !== '' &&
    ['true', 'false', '1', '0'].indexOf(writesText) < 0
  ) {
    throw new Error(
      'SCHEMA_MISMATCH: Config ' +
      CANN.CONSUMPTION_CORRECTION_WRITES_KEY +
      ' must be true or false'
    );
  }
  const writesEnabled = truthy_(rawWrites);
  if (writesEnabled && version !== CANN.CONSUMPTION_CORRECTION_VERSION) {
    throw new Error(
      'SCHEMA_MISMATCH: correction writes require schema version ' +
      CANN.CONSUMPTION_CORRECTION_VERSION
    );
  }
  return {
    version: version,
    writesEnabled: writesEnabled
  };
}

function requireCompatibilityIdentityHeaders_(headers) {
  requireHeaders_(headers, [
    CANN.COMPATIBILITY_EVENT_HEADER,
    CANN.COMPATIBILITY_REQUEST_HEADER
  ]);
  if (headers[CANN.COMPATIBILITY_REQUEST_HEADER] !==
      headers[CANN.COMPATIBILITY_EVENT_HEADER] + 1) {
    throw new Error('SCHEMA_MISMATCH: compatibility identity columns must be adjacent');
  }
}

function requireExactHeaders_(headers, expected, sheetName) {
  expected.forEach((name, index) => {
    if (headers[name] !== index) {
      throw new Error('SCHEMA_MISMATCH: ' + sheetName + ' column ' + (index + 1) + ' must be ' + name);
    }
  });
}

function configValuesFromSheet_(sheet, headers) {
  const result = {};
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return result;
  sheet.getRange(2, 1, lastRow - 1, sheet.getLastColumn()).getValues().forEach(row => {
    const key = text_(value_(row, headers, 'Key'));
    if (key) result[key] = value_(row, headers, 'Value');
  });
  return result;
}

function ensureSheet_(ss, name, headers) {
  const sheet = ss.getSheetByName(name) || ss.insertSheet(name);
  ensureHeaders_(sheet, headers);
  sheet.setFrozenRows(1);
  return sheet;
}

function ensureHeaders_(sheet, expected) {
  if (sheet.getMaxColumns() < expected.length) {
    sheet.insertColumnsAfter(sheet.getMaxColumns(), expected.length - sheet.getMaxColumns());
  }
  const current = sheet.getRange(1, 1, 1, expected.length).getValues()[0];
  expected.forEach((header, index) => {
    if (!text_(current[index])) sheet.getRange(1, index + 1).setValue(header);
    else if (text_(current[index]) !== header) {
      throw new Error('SCHEMA_MISMATCH: ' + sheet.getName() + ' column ' + (index + 1) + ' expected ' + header + ' but found ' + current[index]);
    }
  });
}

function applySheetHeaderCosmetics_(sheet) {
  try {
    sheet.getRange(1, 1, 1, sheet.getLastColumn())
      .setBackground('#eeeeee')
      .setFontColor('#000000')
      .setFontWeight('bold');
    if (sheet.getFrozenRows() < 1) sheet.setFrozenRows(1);
  } catch (error) {
    const message = String(error && error.message || error)
      .replace(/^\s*(?:Exception:\s*)?/, '')
      .trim();
    if (message !== 'This operation is not allowed on cells in typed columns.') {
      throw error;
    }
    console.warn(JSON.stringify({
      type: 'sheet_header_cosmetics_skipped',
      sheet: sheet.getName(),
      reason: 'TYPED_COLUMN_RESTRICTION'
    }));
  }
}

function applySheetSafety_(ss) {
  const purchases = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const headers = headerMap_(purchases);
  const lastValidationRow = Math.max(purchases.getMaxRows(), 1000);
  const typeValues = readDataRows_(purchases).map(row => text_(value_(row, headers, 'Type'))).filter(Boolean);
  const uniqueTypes = Array.from(new Set(typeValues)).sort();
  if (uniqueTypes.length) {
    const typeRule = SpreadsheetApp.newDataValidation().requireValueInList(uniqueTypes, true).setAllowInvalid(false).build();
    purchases.getRange(2, headers['Type'] + 1, lastValidationRow - 1, 1).setDataValidation(typeRule);
  }
  const statusRule = SpreadsheetApp.newDataValidation().requireValueInList(['0', '1', '2'], true).setAllowInvalid(false).build();
  purchases.getRange(2, headers['Finished'] + 1, lastValidationRow - 1, 1).setDataValidation(statusRule);
  const binaryRule = SpreadsheetApp.newDataValidation().requireValueInList(['0', '1'], true).setAllowInvalid(false).build();
  purchases.getRange(2, headers['Borrowed'] + 1, lastValidationRow - 1, 1).setDataValidation(binaryRule);
  // The existing Purchases table already owns the Post-tax typed checkbox
  // column. Reapplying checkbox validation is rejected for typed columns.

  const protectedSheets = [
    purchases,
    requiredSheet_(ss, CANN.SHEETS.EVENTS),
    requiredSheet_(ss, CANN.SHEETS.LEDGER),
    requiredSheet_(ss, CANN.SHEETS.CONFIG),
    requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT)
  ];
  const corrections = ss.getSheetByName(CANN.SHEETS.CORRECTIONS);
  if (corrections) protectedSheets.push(corrections);
  protectedSheets.forEach(applySheetHeaderCosmetics_);
  addWarningProtection_(purchases.getRange(1, 1, 1, purchases.getLastColumn()), 'Cannsheet reliability headers');
  addWarningProtection_(purchases.getRange(2, headers['Product ID'] + 1, Math.max(1, purchases.getMaxRows() - 1), 1), 'Cannsheet display identities');
  addWarningProtection_(purchases.getRange(2, headers['Product UUID'] + 1, Math.max(1, purchases.getMaxRows() - 1), 3), 'Cannsheet immutable identities');
  if (corrections) {
    addWarningProtection_(
      corrections.getRange(1, 1, 1, corrections.getLastColumn()),
      'Cannsheet correction headers'
    );
    addWarningProtection_(
      corrections.getRange(
        2,
        1,
        Math.max(1, corrections.getMaxRows() - 1),
        corrections.getLastColumn()
      ),
      'Cannsheet append-only correction records'
    );
  }
}

function addWarningProtection_(range, description) {
  const existing = range.getSheet().getProtections(SpreadsheetApp.ProtectionType.RANGE)
    .find(protection => protection.getDescription() === description);
  const protection = existing || range.protect().setDescription(description);
  protection.setWarningOnly(true);
}

// -----------------------------------------------------------------------------
// Batch write helpers
// -----------------------------------------------------------------------------

function newPurchaseRow_(item, now, taxRate) {
  const p = item.item;
  const cost = purchaseNumberOrBlank_(p.cost);
  const thc = purchaseNumberOrBlank_(p.thc);
  const grams = purchaseNumberOrBlank_(p.grams);
  const postTax = truthy_(p.postTax);
  const finalCost = cost === '' ? '' : (postTax ? cost : cost * (1 + taxRate));
  return [
    text_(p.date), text_(p.type), text_(p.name), cost,
    thc, grams, truthy_(p.borrowed) ? 1 : 0, CANN.STATUS.UNOPENED,
    item.legacyProductId, 0, postTax, finalCost,
    '', item.productUuid, item.actionId, now, ''
  ];
}

function appendPurchaseRows_(context, staged, now, configuredTaxRate) {
  if (!staged.length) return;
  const sheet = context.purchasesSheet;
  const taxRate = finiteNumberOr_(configuredTaxRate, 0.13);
  const rows = staged.map(item => {
    const row = newPurchaseRow_(item, now, taxRate);
    if (context.headers && context.headers['Last quantity'] !== undefined) row.push('');
    return row;
  });
  const firstRow = sheet.getLastRow() + 1;
  sheet.getRange(firstRow, 1, rows.length, rows[0].length).setValues(rows);
  staged.forEach((item, index) => {
    item.rowNumber = firstRow + index;
    item.row = rows[index];
    item.status = CANN.STATUS.UNOPENED;
    item.uses = 0;
    item.mostRecentUse = null;
    item.finishedAt = null;
    item.lastQuantity = null;
    context.byLegacyId[item.legacyProductId] = item;
    if (item.productUuid) context.byProductUuid[item.productUuid] = item;
    if (item.actionId && context.byActionId) context.byActionId[item.actionId] = item;
  });
}

function appendConsumptionRows_(ss, staged, skipCompatibility, timing, runtimeContext) {
  let phaseStarted = Date.now();
  if (!staged.length) {
    recordBackendPhase_(timing, 'compatibilityAppend', phaseStarted);
    phaseStarted = Date.now();
    recordBackendPhase_(timing, 'canonicalAppend', phaseStarted);
    return;
  }
  if (!skipCompatibility) {
    const responses = runtimeContext ? runtimeContext.sheets.responses : requiredSheet_(ss, CANN.SHEETS.RESPONSES);
    const responseHeaders = runtimeContext ? runtimeContext.headers.responses : headerMap_(responses);
    requireHeaders_(responseHeaders, ['Timestamp', 'Product', 'Uses']);
    const compatibilityRows = staged.map(item => {
      const row = Array(responses.getLastColumn()).fill('');
      row[responseHeaders['Timestamp']] = item.timestamp;
      row[responseHeaders['Product']] = item.legacyProductId;
      row[responseHeaders['Uses']] = item.uses;
      if (responseHeaders['Date'] !== undefined) row[responseHeaders['Date']] = item.localDate;
      if (responseHeaders['Time'] !== undefined) row[responseHeaders['Time']] = item.localTime;
      if (responseHeaders['Weight code'] !== undefined) row[responseHeaders['Weight code']] = item.weightCode || '';
      if (responseHeaders['Mark as Finished?'] !== undefined) row[responseHeaders['Mark as Finished?']] = item.isFinished ? 'Yes' : '';
      return row;
    });
    responses.getRange(responses.getLastRow() + 1, 1, compatibilityRows.length, responses.getLastColumn()).setValues(compatibilityRows);
    const firstRow = responses.getLastRow() - compatibilityRows.length + 1;
    staged.forEach((item, index) => {
      item.legacySourceSheet = responses.getName();
      item.legacySourceRow = firstRow + index;
    });
  }
  recordBackendPhase_(timing, 'compatibilityAppend', phaseStarted);

  phaseStarted = Date.now();
  appendEventRows_(runtimeContext ? runtimeContext.sheets.events : requiredSheet_(ss, CANN.SHEETS.EVENTS), staged);
  recordBackendPhase_(timing, 'canonicalAppend', phaseStarted);
}

function appendEventRows_(sheet, staged) {
  if (!staged.length) return;
  const rows = staged.map(item => [
    item.eventId, item.timestamp, item.localDate, item.localTime,
    item.productUuid, item.legacyProductId, item.uses, item.weightCode || '',
    !!item.isFinished, item.source, item.requestId || '',
    item.legacySourceSheet || '', item.legacySourceRow || ''
  ]);
  sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, CANN.EVENT_HEADERS.length).setValues(rows);
}

function applyProductEffects_(context, consumptions) {
  if (!consumptions.length) return;
  const effects = calculateProductEffects_(context, consumptions);
  const sheet = context.purchasesSheet;
  const headers = context.headers;
  effects.forEach(effect => {
    // These are intentionally up to five exact cells on affected rows only. This
    // avoids rewriting formulas, typed cells, blank physical rows, or products
    // unrelated to the accepted events.
    sheet.getRange(effect.rowNumber, headers['Finished'] + 1).setValue(effect.status);
    sheet.getRange(effect.rowNumber, headers['Uses'] + 1).setValue(effect.uses);
    sheet.getRange(effect.rowNumber, headers['Most recent use'] + 1).setValue(effect.mostRecentUse || '');
    sheet.getRange(effect.rowNumber, headers['Finished At'] + 1).setValue(effect.finishedAt || '');
    if (headers['Last quantity'] !== undefined) {
      sheet.getRange(effect.rowNumber, headers['Last quantity'] + 1).setValue(
        effect.lastQuantity == null ? '' : effect.lastQuantity
      );
    }

    const product = context.byLegacyId[effect.legacyProductId];
    product.status = effect.status;
    product.uses = effect.uses;
    product.mostRecentUse = effect.mostRecentUse;
    product.finishedAt = effect.finishedAt;
    product.lastQuantity = effect.lastQuantity;
  });
}

/**
 * Finish-only actions deliberately update only the two finish projection cells.
 * They are not consumption events, so Uses, Most recent use, and Last quantity
 * must remain untouched.
 */
function applyFinishActions_(context, finishActions) {
  if (!finishActions.length) return;
  const effects = calculateFinishEffects_(context, finishActions);
  const sheet = context.purchasesSheet;
  const headers = context.headers;
  effects.forEach(effect => {
    if (!effect.rowNumber) return;
    sheet.getRange(effect.rowNumber, headers['Finished'] + 1)
      .setValue(effect.status);
    sheet.getRange(effect.rowNumber, headers['Finished At'] + 1)
      .setValue(effect.finishedAt);
    const product = context.byLegacyId[effect.legacyProductId];
    if (!product) return;
    product.status = effect.status;
    product.finishedAt = effect.finishedAt;
  });
}

function calculateFinishEffects_(context, finishActions) {
  const effectsByProduct = {};
  finishActions.forEach(action => {
    const product = context.byLegacyId[action.legacyProductId];
    if (!product || (!product.rowNumber && !product.pendingAppend)) return;
    // Preserve append order inside the immutable request snapshot: a later
    // accepted finish action for the same product supplies the final timestamp.
    effectsByProduct[action.legacyProductId] = {
      legacyProductId: action.legacyProductId,
      rowNumber: product.rowNumber,
      pendingAppend: product.pendingAppend === true,
      status: CANN.STATUS.FINISHED,
      finishedAt: action.timestamp
    };
  });
  return Object.keys(effectsByProduct).map(id => effectsByProduct[id]);
}

function calculateProductEffects_(context, consumptions) {
  const grouped = {};
  consumptions.forEach(item => {
    const id = item.legacyProductId;
    if (!grouped[id]) grouped[id] = [];
    grouped[id].push(item);
  });

  return Object.keys(grouped).map(legacyProductId => {
    const product = context.byLegacyId[legacyProductId];
    if (!product || (!product.rowNumber && !product.pendingAppend)) return null;
    let status = allowedStatusOr_(product.status, CANN.STATUS.ACTIVE);
    let uses = finiteNumberOr_(product.uses, 0);
    let mostRecentUse = product.mostRecentUse || null;
    let latestMillis = timestampMillisOrNull_(mostRecentUse);
    let lastQuantity = optionalFiniteNumber_(product.lastQuantity);
    let finishedAt = product.finishedAt || null;

    grouped[legacyProductId].forEach(item => {
      uses += finiteNumberOr_(item.uses, 0);
      const eventMillis = timestampMillisOrNull_(item.timestamp);
      if (eventMillis != null && (latestMillis == null || eventMillis > latestMillis)) {
        mostRecentUse = item.timestamp;
        latestMillis = eventMillis;
        lastQuantity = finiteNumberOr_(item.uses, 0);
      }
      if (item.isFinished) {
        status = CANN.STATUS.FINISHED;
        // Preserve the deployed append-order rule: the last accepted finishing
        // event in this request wins, even when its client timestamp is older.
        finishedAt = item.timestamp;
      } else if (status === CANN.STATUS.UNOPENED) {
        status = CANN.STATUS.ACTIVE;
      }
    });

    return {
      legacyProductId: legacyProductId,
      rowNumber: product.rowNumber,
      pendingAppend: product.pendingAppend === true,
      status: status,
      uses: uses,
      mostRecentUse: mostRecentUse,
      finishedAt: finishedAt,
      lastQuantity: lastQuantity
    };
  }).filter(Boolean);
}

function calculateCorrectionProjectionEffects_(
  productContext,
  projectedState,
  corrections
) {
  const affected = {};
  const reopenProducts = {};
  (corrections || []).forEach(correction => {
    (correction.affectedProductIds || []).forEach(productId => {
      affected[productId] = true;
    });
    if (correction.reopenLegacyProductId) {
      reopenProducts[correction.reopenLegacyProductId] = true;
    }
  });
  const projections = effectiveProductProjectionMap_(
    projectedState.resolution.effectiveEvents,
    projectedState.resolution.reopenedProducts,
    {
      finishActionProductIds:
        projectedState.snapshot.finishActionProductIds,
      productsByProductId: projectedState.products.byProductId
    }
  );
  return Object.keys(affected).map(legacyProductId => {
    const product = productContext.byLegacyId[legacyProductId];
    if (!product) {
      throw new Error(
        'CORRECTION_PROJECTION_BLOCKED: missing product ' +
        legacyProductId
      );
    }
    const expected = expectedProjectionForProduct_(
      product,
      projections[legacyProductId],
      { reopenProduct: reopenProducts[legacyProductId] === true }
    );
    return {
      legacyProductId: legacyProductId,
      rowNumber: product.rowNumber,
      status: expected.status,
      uses: expected.uses,
      mostRecentUse: expected.mostRecentUse,
      lastQuantity: expected.lastQuantity,
      finishedAt: expected.finishedAt
    };
  });
}

function upsertLedger_(ss, requestId, purchaseCount, consumptionCount, result, durationMs, errorCode, runtimeContext) {
  const sheet = runtimeContext ? runtimeContext.sheets.ledger : requiredSheet_(ss, CANN.SHEETS.LEDGER);
  const headers = runtimeContext ? runtimeContext.headers.ledger : headerMap_(sheet);
  requireHeaders_(headers, ['Request UUID']);
  // The sheet object is intentionally created before the lock so the request can
  // reuse one spreadsheet context. Do not use its cached getLastRow() value for
  // the final ledger decision: a prior execution may have appended while this
  // request waited for the lock. Search the durable Request UUID column across
  // the current grid and use appendRow() for the no-match case.
  const requestIdRange = sheet.getRange(
    2,
    headers['Request UUID'] + 1,
    Math.max(1, sheet.getMaxRows() - 1),
    1
  );
  const match = requestIdRange.createTextFinder(requestId)
    .matchEntireCell(true)
    .matchCase(true)
    .useRegularExpression(false)
    .findNext();
  const values = [[requestId, CANN.API_VERSION, new Date(), purchaseCount, consumptionCount, result, durationMs, errorCode || '']];
  if (match) sheet.getRange(match.getRow(), 1, 1, values[0].length).setValues(values);
  else sheet.appendRow(values[0]);

  // The lock must not be released while the final ledger write is still queued.
  // Otherwise a waiting execution can miss it and append a duplicate row.
  SpreadsheetApp.flush();
}

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

// -----------------------------------------------------------------------------
// Recoverable multi-sheet apply: explicit rollout and reconciliation
// -----------------------------------------------------------------------------

/**
 * Additive, idempotent preparation. Marker 0 deliberately keeps the deployed
 * SpreadsheetApp write path active until enableRecoverableSyncApply() is run.
 */
function prepareRecoverableSyncApply() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    ensureCoreSchema_(ss);
    ensureMigrationResolutionSchema_(ss);
    assertSpreadsheetTimeZone_(ss);
    provisionRecoverableDateFormats_(ss);
    repairRecoverableStateLocked_({
      ss: ss,
      config: {},
      sheets: {},
      headers: {}
    });
    setConfigValue_(
      ss,
      CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY,
      0,
      'Recoverable multi-sheet apply version'
    );
    setConfigValue_(
      ss,
      CANN.PENDING_APPLY_KEY,
      '',
      'Apply UUID awaiting finalization'
    );
    const compatibility = ensureCompatibilityIdentitySchema_(ss);
    const backfill = backfillCompatibilityIdentities_(ss);
    SpreadsheetApp.flush();
    const reconciliation = reconcileRecoverableSyncApply_(ss);
    const result = {
      preparedVersion: CANN.RECOVERABLE_SYNC_APPLY_VERSION,
      configVersion: 0,
      fastPathEnabled: false,
      compatibilityIdentityColumns: compatibility,
      identityRowsBackfilled: backfill.rowsBackfilled,
      blockingDifferences: reconciliation.blockingDifferences.length
    };
    console.log(JSON.stringify({
      type: 'recoverable_sync_apply_prepared',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

/**
 * Marker-0 maintenance repair for rows that preparation cannot safely map.
 *
 * First, a blank compatibility identity is relinked only when timestamp,
 * product, quantity, weight, and finished flag select exactly one canonical
 * event and that Event UUID is not already owned by another response row.
 * If no canonical event exists, canonicalization is allowed only when the
 * Purchases Uses value still equals the canonical sum, which proves this
 * response quantity has not already been applied. Anything else is reported
 * and left untouched for human review.
 */
function repairPreparedCompatibilityRows() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    assertSpreadsheetTimeZone_(ss);
    assertAdvancedSheetsService_();
    ensureCompatibilityIdentitySchema_(ss);
    const configSheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
    const config = configValuesFromSheet_(
      configSheet,
      headerMap_(configSheet)
    );
    if (recoverableSyncApplyReady_(config)) {
      throw new Error(
        'PREPARED_COMPATIBILITY_REPAIR_BLOCKED: disable marker 1 first'
      );
    }
    if (!interactionSummaryReady_(config)) {
      throw new Error(
        'PREPARED_COMPATIBILITY_REPAIR_BLOCKED: interaction summary must be ready'
      );
    }
    repairRecoverableStateLocked_({
      ss: ss,
      config: config,
      sheets: {},
      headers: {}
    });

    const purchases = requiredSheet_(ss, CANN.SHEETS.PURCHASES);
    const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
    const events = requiredSheet_(ss, CANN.SHEETS.EVENTS);
    const ledger = requiredSheet_(ss, CANN.SHEETS.LEDGER);
    const migrationReport =
      requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT);
    const applyJournal =
      requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
    const runtimeContext = {
      ss: ss,
      environment: environment_(),
      config: config,
      sheets: {
        purchases: purchases,
        responses: responses,
        events: events,
        ledger: ledger,
        config: configSheet,
        migrationReport: migrationReport,
        applyJournal: applyJournal
      },
      headers: {
        purchases: headerMap_(purchases),
        responses: headerMap_(responses),
        events: headerMap_(events),
        ledger: headerMap_(ledger),
        config: headerMap_(configSheet),
        migrationReport: headerMap_(migrationReport),
        applyJournal: headerMap_(applyJournal)
      }
    };
    const responseHeaders = runtimeContext.headers.responses;
    const eventHeaders = runtimeContext.headers.events;
    const responseRows = responses.getLastRow() < 2
      ? []
      : responses.getRange(
        2,
        1,
        responses.getLastRow() - 1,
        responses.getLastColumn()
      ).getValues();
    const canonicalRows = events.getLastRow() < 2
      ? []
      : events.getRange(
        2,
        1,
        events.getLastRow() - 1,
        events.getLastColumn()
      ).getValues();
    const identifiedByEventId = {};
    responseRows.forEach((row, index) => {
      const eventId = text_(value_(
        row,
        responseHeaders,
        CANN.COMPATIBILITY_EVENT_HEADER
      ));
      if (eventId) identifiedByEventId[eventId] = index + 2;
    });
    const canonicalUsesByProduct = {};
    const canonicalByFingerprint = {};
    canonicalRows.forEach((row, canonicalIndex) => {
      const legacyId = text_(value_(
        row,
        eventHeaders,
        'Legacy Product ID'
      ));
      if (!legacyId) return;
      canonicalUsesByProduct[legacyId] =
        finiteNumberOr_(canonicalUsesByProduct[legacyId], 0) +
        finiteNumberOr_(value_(row, eventHeaders, 'Uses'), 0);
      const fingerprint = canonicalCompatibilityFingerprint_(
        value_(row, eventHeaders, 'Timestamp'),
        legacyId,
        value_(row, eventHeaders, 'Uses'),
        value_(row, eventHeaders, 'Weight Code'),
        value_(row, eventHeaders, 'Finished')
      );
      if (!canonicalByFingerprint[fingerprint]) {
        canonicalByFingerprint[fingerprint] = [];
      }
      canonicalByFingerprint[fingerprint].push({
        row: row,
        rowNumber: canonicalIndex + 2
      });
    });

    let relinkedRows = 0;
    let canonicalizedRows = 0;
    const unresolved = [];
    const relinkRequests = [];
    const repairedSourceRows = [];
    const affectedProductIds = {};
    const maintenanceProductContext = productContext_(ss, {
      runtimeContext: runtimeContext
    });
    responseRows.forEach((row, index) => {
      const sourceRow = index + 2;
      if (text_(value_(
        row,
        responseHeaders,
        CANN.COMPATIBILITY_EVENT_HEADER
      ))) return;
      const timestamp = dateOrNull_(
        value_(row, responseHeaders, 'Timestamp')
      );
      const legacyId = text_(value_(row, responseHeaders, 'Product'));
      const uses = finiteNumber_(value_(row, responseHeaders, 'Uses'));
      if (!timestamp || !legacyId || uses == null) return;
      const weightCode = text_(
        value_(row, responseHeaders, 'Weight code')
      );
      const finished = truthy_(
        value_(row, responseHeaders, 'Mark as Finished?')
      );
      const fingerprint = canonicalCompatibilityFingerprint_(
        timestamp,
        legacyId,
        uses,
        weightCode,
        finished
      );
      const allCandidates = canonicalByFingerprint[fingerprint] || [];
      const candidates = allCandidates.filter(candidate => {
        const candidateEventId = text_(value_(
          candidate.row,
          eventHeaders,
          'Event UUID'
        ));
        return candidateEventId && !identifiedByEventId[candidateEventId];
      });

      if (candidates.length === 1) {
        const candidate = candidates[0];
        const eventId = text_(value_(
          candidate.row,
          eventHeaders,
          'Event UUID'
        ));
        const requestId = text_(value_(
          candidate.row,
          eventHeaders,
          'Request UUID'
        ));
        relinkRequests.push(updateCellsRequest_(
          responses,
          sourceRow,
          responseHeaders[CANN.COMPATIBILITY_EVENT_HEADER] + 1,
          [[eventId, requestId]]
        ));
        relinkRequests.push(updateCellsRequest_(
          events,
          candidate.rowNumber,
          eventHeaders['Legacy Source Sheet'] + 1,
          [[responses.getName(), sourceRow]]
        ));
        identifiedByEventId[eventId] = sourceRow;
        repairedSourceRows.push(sourceRow);
        affectedProductIds[legacyId] = true;
        relinkedRows++;
        return;
      }
      if (candidates.length > 1) {
        unresolved.push({
          type: 'AMBIGUOUS_CANONICAL_MATCH',
          sourceRow: sourceRow,
          candidateRows: candidates.map(candidate => candidate.rowNumber)
        });
        return;
      }
      if (allCandidates.length) {
        unresolved.push({
          type: 'CANONICAL_MATCH_ALREADY_IDENTIFIED',
          sourceRow: sourceRow,
          candidateRows: allCandidates.map(candidate => candidate.rowNumber)
        });
        return;
      }

      const context = maintenanceProductContext;
      const product = context.byLegacyId[legacyId];
      const canonicalUses =
        finiteNumberOr_(canonicalUsesByProduct[legacyId], 0);
      if (!product ||
          Math.abs(finiteNumberOr_(product.uses, 0) - canonicalUses) >
            1e-9) {
        unresolved.push({
          type: 'UNMATCHED_COMPATIBILITY_ROW',
          sourceRow: sourceRow,
          productId: legacyId,
          currentProductUses: product
            ? finiteNumberOr_(product.uses, 0)
            : null,
          canonicalUses: canonicalUses
        });
        return;
      }
      const eventId = deterministicLegacyEventUuid_(
        ss.getId(),
        responses.getName(),
        sourceRow
      );
      if (eventContext_(
        ss,
        runtimeContext,
        [eventId]
      ).eventIds.has(eventId)) {
        unresolved.push({
          type: 'DETERMINISTIC_EVENT_ID_CONFLICT',
          sourceRow: sourceRow,
          eventId: eventId
        });
        return;
      }
      const event = {
        eventId: eventId,
        timestamp: timestamp,
        localDate: formatDate_(timestamp),
        localTime: formatTime_(timestamp),
        productUuid: product.productUuid,
        legacyProductId: legacyId,
        uses: uses,
        weightCode: weightCode,
        isFinished: finished,
        source: 'PREPARED_COMPATIBILITY_REPAIR',
        requestId: '',
        legacySourceSheet: responses.getName(),
        legacySourceRow: sourceRow,
        compatibilityRow: null
      };
      applyRecoverableSyncLocked_({
        runtimeContext: runtimeContext,
        productContext: context,
        stagedPurchases: [],
        stagedConsumptions: [event],
        kind: 'PREPARED_COMPATIBILITY_REPAIR',
        apiVersion: 0,
        requestId: '',
        response: null,
        formRefreshRequired: true,
        compatibilityExistingRow: sourceRow,
        ledger: null,
        timing: null
      });
      canonicalUsesByProduct[legacyId] = canonicalUses + uses;
      const appendedCanonical = buildRecoverableEventRows_([event])[0];
      canonicalRows.push(appendedCanonical);
      const appendedFingerprint = canonicalCompatibilityFingerprint_(
        event.timestamp,
        event.legacyProductId,
        event.uses,
        event.weightCode,
        event.isFinished
      );
      if (!canonicalByFingerprint[appendedFingerprint]) {
        canonicalByFingerprint[appendedFingerprint] = [];
      }
      canonicalByFingerprint[appendedFingerprint].push({
        row: appendedCanonical,
        rowNumber: events.getLastRow()
      });
      identifiedByEventId[eventId] = sourceRow;
      repairedSourceRows.push(sourceRow);
      affectedProductIds[legacyId] = true;
      canonicalizedRows++;
    });

    sheetsBatchUpdateInChunks_(ss, relinkRequests, 400);
    const affectedProducts = Object.keys(affectedProductIds);
    if (affectedProducts.length) {
      rebuildProductProjectionsFromCanonical_(ss, affectedProducts);
    }
    const productReconciliation =
      reconcileProductProjections_(ss, affectedProducts);
    const summaryReconciliation = reconcileInteractionSummary_(ss);
    const compatibilityReconciliation =
      reconcileReliabilityMigration_(ss);
    const projectionsClean =
      productReconciliation.differences.length === 0 &&
      summaryReconciliation.differences.length === 0 &&
      compatibilityReconciliation.differences.length === 0 &&
      compatibilityReconciliation.responseEventCount ===
        compatibilityReconciliation.canonicalEventCount;
    let resolvedMigrationRows = 0;
    if (!unresolved.length && projectionsClean) {
      resolvedMigrationRows = resolveMigrationIssuesForSourceRows_(
        ss,
        responses.getName(),
        repairedSourceRows,
        'Resolved by recoverable compatibility repair'
      );
    }

    const result = {
      relinkedRows: relinkedRows,
      canonicalizedRows: canonicalizedRows,
      rebuiltProducts: affectedProducts.length,
      productProjectionDifferences:
        productReconciliation.differences.length,
      resolvedMigrationRows: resolvedMigrationRows,
      unresolvedRows: unresolved.length,
      unresolved: unresolved
    };
    console.log(JSON.stringify({
      type: 'prepared_compatibility_rows_repaired',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function canonicalCompatibilityFingerprint_(
  timestamp,
  legacyProductId,
  uses,
  weightCode,
  finished
) {
  return JSON.stringify([
    timestampMillisOrNull_(timestamp),
    text_(legacyProductId),
    finiteNumber_(uses),
    text_(weightCode),
    truthy_(finished)
  ]);
}

function effectiveConsumptionState_(
  ss,
  runtimeContext,
  stagedConsumptions,
  stagedCorrections
) {
  const runtimeConfig = runtimeContext
    ? { values: runtimeContext.config }
    : readAndAssertRuntimeConfig_(ss, environment_());
  const capability = consumptionCorrectionCapability_(
    runtimeConfig.values
  );
  const purchases = runtimeContext
    ? runtimeContext.sheets.purchases
    : requiredSheet_(ss, CANN.SHEETS.PURCHASES);
  const events = runtimeContext
    ? runtimeContext.sheets.events
    : requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const corrections = capability.version
    ? (
      runtimeContext
        ? runtimeContext.sheets.corrections
        : requiredSheet_(ss, CANN.SHEETS.CORRECTIONS)
    )
    : null;
  const purchaseHeaders = runtimeContext
    ? runtimeContext.headers.purchases
    : headerMap_(purchases);
  const eventHeaders = runtimeContext
    ? runtimeContext.headers.events
    : headerMap_(events);
  const correctionHeaders = corrections
    ? (
      runtimeContext
        ? runtimeContext.headers.corrections
        : headerMap_(corrections)
    )
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
  if (corrections) {
    requireExactHeaders_(
      correctionHeaders,
      CANN.CORRECTION_HEADERS,
      CANN.SHEETS.CORRECTIONS
    );
  }
  const applyEvidence = capability.version
    ? recoverableApplyEvidence_(ss, runtimeContext)
    : {
      eventIds: {},
      correctionIds: {},
      finishActionProductIds: {}
    };
  const snapshot = {
    environment: runtimeContext
      ? runtimeContext.environment
      : environment_(),
    taxRate: finiteNumberOr_(runtimeConfig.values.TAX_RATE, 0.13),
    purchaseHeaders: purchaseHeaders,
    eventHeaders: eventHeaders,
    correctionHeaders: correctionHeaders,
    purchaseRows: readAnalyticsRowsThrough_(
      purchases,
      purchases.getLastRow()
    ),
    eventRows: readAnalyticsRowsThrough_(events, events.getLastRow()),
    correctionRows: corrections
      ? readAnalyticsRowsThrough_(corrections, corrections.getLastRow())
      : [],
    eventLastRow: events.getLastRow(),
    correctionLastRow: corrections ? corrections.getLastRow() : 1,
    correctionVersion: capability.version,
    correctionWritesEnabled: capability.writesEnabled,
    finishActionProductIds: applyEvidence.finishActionProductIds,
    finishProvenanceEventIds: applyEvidence.eventIds,
    finishProvenanceCorrectionIds: applyEvidence.correctionIds
  };
  const quality = newAnalyticsQuality_();
  const products = normalizeAnalyticsProducts_(snapshot, quality);
  const canonicalEvents = normalizeAnalyticsEvents_(
    snapshot,
    products,
    quality
  );
  (stagedConsumptions || []).forEach((item, index) => {
    const productUuid = text_(item.productUuid).toLowerCase();
    const legacyProductId = text_(item.legacyProductId);
    const product = (
      productUuid && products.byProductUuid[productUuid]
    ) || (
      legacyProductId && products.byProductId[legacyProductId]
    );
    // A consumption for a purchase created in this same request is projected
    // by the existing purchase plan because that product does not exist in the
    // spreadsheet snapshot yet.
    if (!product) return;
    canonicalEvents.push({
      canonicalRow: snapshot.eventLastRow + index + 1,
      eventUuid: text_(item.eventId).toLowerCase(),
      occurredAtEpochMillis: dateOrNow_(item.timestamp).getTime(),
      localDate: text_(item.localDate),
      localTime: text_(item.localTime),
      product: product,
      quantity: finiteNumberOr_(item.uses, 0),
      weightCode: text_(item.weightCode) || null,
      finished: item.isFinished === true,
      source: text_(item.source) || 'ANDROID_V2'
    });
  });
  const resolution = resolveAnalyticsCorrections_(
    snapshot,
    products,
    canonicalEvents,
    stagedCorrections || []
  );
  return {
    snapshot: snapshot,
    quality: quality,
    products: products,
    canonicalEvents: canonicalEvents,
    resolution: resolution
  };
}

function effectiveProductProjectionMap_(events, reopenedProducts, options) {
  const settings = options || {};
  const projections = {};
  (events || []).forEach(event => {
    const legacyProductId = event.product.productId;
    const projection = projections[legacyProductId] ||
      (projections[legacyProductId] = {
        eventCount: 0,
        uses: 0,
        mostRecentUse: null,
        mostRecentMillis: null,
        lastQuantity: null,
        hasFinishedEvent: false,
        finishedEventCount: 0,
        finishedAt: null,
        finishedAtMillis: null,
        lastCanonicalRow: null
      });
    projection.eventCount++;
    projection.uses += event.quantity;
    if (
      projection.mostRecentMillis == null ||
      event.occurredAtEpochMillis > projection.mostRecentMillis
    ) {
      projection.mostRecentUse = new Date(event.occurredAtEpochMillis);
      projection.mostRecentMillis = event.occurredAtEpochMillis;
      projection.lastQuantity = event.quantity;
    }
    if (event.finished) {
      projection.hasFinishedEvent = true;
      projection.finishedEventCount++;
      if (
        projection.finishedAtMillis == null ||
        event.occurredAtEpochMillis > projection.finishedAtMillis
      ) {
        projection.finishedAt = new Date(event.occurredAtEpochMillis);
        projection.finishedAtMillis = event.occurredAtEpochMillis;
      }
    }
    projection.lastCanonicalRow = event.canonicalRow;
  });
  Object.keys(reopenedProducts || {}).forEach(legacyProductId => {
    const product = settings.productsByProductId
      ? settings.productsByProductId[legacyProductId]
      : null;
    if (
      product &&
      finishActionEvidenceForProduct_(
        settings.finishActionProductIds || {},
        product
      )
    ) {
      return;
    }
    const projection = projections[legacyProductId] ||
      (projections[legacyProductId] = {
        eventCount: 0,
        uses: 0,
        mostRecentUse: null,
        mostRecentMillis: null,
        lastQuantity: null,
        hasFinishedEvent: false,
        finishedEventCount: 0,
        finishedAt: null,
        finishedAtMillis: null,
        lastCanonicalRow: null
      });
    projection.reopenRequested = true;
    projection.reopenBasisFinishedAtMillis =
      reopenedProducts[legacyProductId].basisFinishedAtEpochMillis;
  });
  return projections;
}

function reconcileConsumptionCorrections_(ss) {
  const state = effectiveConsumptionState_(ss);
  const projectionReconciliation = reconcileProductProjections_(ss, null);
  return {
    rawEvents: state.snapshot.eventRows.length,
    correctionRows: state.snapshot.correctionRows.length,
    effectiveEvents: state.resolution.effectiveEvents.length,
    revisionChains: Object.keys(state.resolution.groupsByTarget).length,
    differences: projectionReconciliation.differences
  };
}

function canonicalProductProjections_(ss) {
  if (typeof PropertiesService === 'undefined') {
    return rawCanonicalProductProjections_(ss);
  }
  const runtimeConfig = readAndAssertRuntimeConfig_(ss, environment_());
  const capability = consumptionCorrectionCapability_(
    runtimeConfig.values
  );
  if (!capability.version) return rawCanonicalProductProjections_(ss);
  const state = effectiveConsumptionState_(ss);
  return effectiveProductProjectionMap_(
    state.resolution.effectiveEvents,
    state.resolution.reopenedProducts,
    {
      finishActionProductIds: state.snapshot.finishActionProductIds,
      productsByProductId: state.products.byProductId
    }
  );
}

function rawCanonicalProductProjections_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const headers = headerMap_(sheet);
  const projections = {};
  const lastRow = sheet.getLastRow();
  const rows = lastRow < 2
    ? []
    : sheet.getRange(
      2,
      1,
      lastRow - 1,
      sheet.getLastColumn()
    ).getValues();
  rows.forEach((row, index) => {
    const legacyProductId = text_(value_(
      row,
      headers,
      'Legacy Product ID'
    ));
    if (!legacyProductId) return;
    const projection = projections[legacyProductId] ||
      (projections[legacyProductId] = {
        eventCount: 0,
        uses: 0,
        mostRecentUse: null,
        mostRecentMillis: null,
        lastQuantity: null,
        hasFinishedEvent: false,
        finishedAt: null,
        lastCanonicalRow: null
      });
    projection.eventCount++;
    projection.uses += finiteNumberOr_(
      value_(row, headers, 'Uses'),
      0
    );
    const timestamp = value_(row, headers, 'Timestamp');
    const timestampMillis = timestampMillisOrNull_(timestamp);
    if (timestampMillis != null &&
        (projection.mostRecentMillis == null ||
         timestampMillis > projection.mostRecentMillis)) {
      projection.mostRecentUse = timestamp;
      projection.mostRecentMillis = timestampMillis;
      projection.lastQuantity = finiteNumberOr_(
        value_(row, headers, 'Uses'),
        0
      );
    }
    if (truthy_(value_(row, headers, 'Finished'))) {
      projection.hasFinishedEvent = true;
      projection.finishedAt = timestamp;
    }
    projection.lastCanonicalRow = index + 2;
  });
  return projections;
}

function expectedProjectionForProduct_(
  product,
  canonicalProjection,
  options
) {
  const settings = options || {};
  const canonical = canonicalProjection || {
    eventCount: 0,
    uses: 0,
    mostRecentUse: null,
    mostRecentMillis: null,
    lastQuantity: null,
    hasFinishedEvent: false,
    finishedAt: null
  };
  let status = allowedStatusOr_(product.status, CANN.STATUS.UNOPENED);
  let finishedAt = product.finishedAt;
  const storedFinishedAtMillis = timestampMillisOrNull_(product.finishedAt);
  const replayedReopen = canonical.reopenRequested === true &&
    (
      storedFinishedAtMillis == null ||
      storedFinishedAtMillis === canonical.reopenBasisFinishedAtMillis
    );
  if (canonical.hasFinishedEvent) {
    status = CANN.STATUS.FINISHED;
    finishedAt = canonical.finishedAt;
  } else if (settings.reopenProduct === true || replayedReopen) {
    status = canonical.eventCount > 0
      ? CANN.STATUS.ACTIVE
      : CANN.STATUS.UNOPENED;
    finishedAt = null;
  } else if (canonical.eventCount > 0 && status === CANN.STATUS.UNOPENED) {
    status = CANN.STATUS.ACTIVE;
  }
  return {
    status: status,
    uses: canonical.uses,
    mostRecentUse: canonical.mostRecentUse,
    mostRecentMillis: canonical.mostRecentMillis,
    lastQuantity: canonical.lastQuantity,
    finishedAt: finishedAt
  };
}

function rebuildProductProjectionsFromCanonical_(ss, productIds) {
  const context = productContext_(ss);
  const canonical = canonicalProductProjections_(ss);
  const requests = [];
  (productIds || []).forEach(legacyProductId => {
    const product = context.byLegacyId[legacyProductId];
    if (!product) {
      throw new Error(
        'PRODUCT_PROJECTION_REBUILD_BLOCKED: missing ' + legacyProductId
      );
    }
    const expected = expectedProjectionForProduct_(
      product,
      canonical[legacyProductId]
    );
    const headers = context.headers;
    requests.push(updateCellsRequest_(
      context.purchasesSheet,
      product.rowNumber,
      headers['Finished'] + 1,
      [[expected.status]]
    ));
    requests.push(updateCellsRequest_(
      context.purchasesSheet,
      product.rowNumber,
      headers['Uses'] + 1,
      [[expected.uses]]
    ));
    requests.push(updateCellsRequest_(
      context.purchasesSheet,
      product.rowNumber,
      headers['Most recent use'] + 1,
      [[expected.mostRecentUse || '']]
    ));
    requests.push(updateCellsRequest_(
      context.purchasesSheet,
      product.rowNumber,
      headers['Finished At'] + 1,
      [[expected.finishedAt || '']]
    ));
    requests.push(updateCellsRequest_(
      context.purchasesSheet,
      product.rowNumber,
      headers['Last quantity'] + 1,
      [[expected.lastQuantity == null ? '' : expected.lastQuantity]]
    ));
  });
  sheetsBatchUpdateInChunks_(ss, requests, 400);
  return { rebuiltProducts: (productIds || []).length };
}

/**
 * Explicit repair entrypoint. It is intentionally separate from read-only
 * reconciliation because it rewrites the five derived Purchases projection
 * cells from effective history.
 */
function rebuildEffectiveProductProjections() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    assertSpreadsheetTimeZone_(ss);
    const runtimeConfig = readAndAssertRuntimeConfig_(ss, environment_());
    const capability = consumptionCorrectionCapability_(
      runtimeConfig.values
    );
    if (capability.version !== CANN.CONSUMPTION_CORRECTION_VERSION) {
      throw new Error(
        'CORRECTION_REBUILD_BLOCKED: correction schema is not provisioned'
      );
    }
    const context = productContext_(ss);
    const projections = canonicalProductProjections_(ss);
    const productIds = Array.from(new Set(
      Object.keys(context.byLegacyId).concat(Object.keys(projections))
    ));
    const rebuilt = rebuildProductProjectionsFromCanonical_(
      ss,
      productIds
    );
    SpreadsheetApp.flush();
    const reconciliation = reconcileProductProjections_(ss, productIds);
    if (reconciliation.differences.length) {
      throw new Error(
        'CORRECTION_REBUILD_FAILED: ' +
        JSON.stringify(reconciliation.differences)
      );
    }
    bumpMutationWatermark_();
    return {
      rebuiltProducts: rebuilt.rebuiltProducts,
      differences: reconciliation.differences
    };
  } finally {
    lock.releaseLock();
  }
}

/**
 * Read-only maintenance entrypoint for production rollout and incident checks.
 * Passing null deliberately reconciles every product found in Purchases or
 * canonical history; it never invokes the projection rebuild helper.
 */
function reconcileProductProjections() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    assertSpreadsheetTimeZone_(ss);
    const result = reconcileProductProjections_(ss, null);
    console.log(JSON.stringify({
      type: 'product_projections_reconciled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function reconcileProductProjections_(ss, productIds) {
  const context = productContext_(ss);
  const canonical = canonicalProductProjections_(ss);
  const ids = productIds == null
    ? Array.from(new Set(
      Object.keys(context.byLegacyId).concat(Object.keys(canonical))
    ))
    : Array.from(new Set(productIds));
  const differences = [];
  ids.forEach(legacyProductId => {
    const product = context.byLegacyId[legacyProductId];
    if (!product) {
      differences.push({
        type: 'MISSING_PRODUCT',
        legacyProductId: legacyProductId
      });
      return;
    }
    const expected = expectedProjectionForProduct_(
      product,
      canonical[legacyProductId]
    );
    const actualRecentMillis = timestampMillisOrNull_(
      product.mostRecentUse
    );
    const actualFinishedMillis = timestampMillisOrNull_(
      product.finishedAt
    );
    const expectedFinishedMillis = timestampMillisOrNull_(
      expected.finishedAt
    );
    if (Math.abs(finiteNumberOr_(product.uses, 0) - expected.uses) >
          1e-9 ||
        allowedStatusOr_(product.status, CANN.STATUS.UNOPENED) !==
          expected.status ||
        actualRecentMillis !== expected.mostRecentMillis ||
        optionalFiniteNumber_(product.lastQuantity) !==
          optionalFiniteNumber_(expected.lastQuantity) ||
        actualFinishedMillis !== expectedFinishedMillis) {
      differences.push({
        type: 'PRODUCT_PROJECTION_MISMATCH',
        legacyProductId: legacyProductId,
        rowNumber: product.rowNumber,
        expected: expected,
        actual: {
          status: product.status,
          uses: product.uses,
          mostRecentMillis: actualRecentMillis,
          lastQuantity: product.lastQuantity,
          finishedAtMillis: actualFinishedMillis
        }
      });
    }
  });
  return {
    checkedProducts: ids.length,
    differences: differences
  };
}

function resolveMigrationIssuesForSourceRows_(
  ss,
  sourceSheet,
  sourceRows,
  resolution
) {
  const uniqueRows = {};
  (sourceRows || []).forEach(row => { uniqueRows[Number(row)] = true; });
  if (!Object.keys(uniqueRows).length) return 0;
  const sheet = ensureMigrationResolutionSchema_(ss);
  const headers = headerMap_(sheet);
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return 0;
  const rows = sheet.getRange(
    2,
    1,
    lastRow - 1,
    sheet.getLastColumn()
  ).getValues();
  const resolutionValues = rows.map(row => [
    value_(row, headers, 'Resolved At'),
    value_(row, headers, 'Resolution')
  ]);
  let resolved = 0;
  rows.forEach((row, index) => {
    if (text_(value_(row, headers, 'Source Sheet')) !== sourceSheet ||
        !uniqueRows[Number(value_(row, headers, 'Source Row'))] ||
        value_(row, headers, 'Resolved At')) return;
    resolutionValues[index] = [new Date(), resolution];
    resolved++;
  });
  if (resolved) {
    sheet.getRange(
      2,
      headers['Resolved At'] + 1,
      resolutionValues.length,
      2
    ).setValues(resolutionValues);
    SpreadsheetApp.flush();
  }
  return resolved;
}

function enableRecoverableSyncApply() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    assertSpreadsheetTimeZone_(ss);
    assertAdvancedSheetsService_();
    ensureCompatibilityIdentitySchema_(ss);
    ensureSheet_(ss, CANN.SHEETS.APPLY_JOURNAL, CANN.APPLY_JOURNAL_HEADERS);
    ensureConfigKey_(
      ss,
      CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY,
      0,
      'Recoverable multi-sheet apply version'
    );
    ensureConfigKey_(
      ss,
      CANN.PENDING_APPLY_KEY,
      '',
      'Apply UUID awaiting finalization'
    );
    repairRecoverableStateLocked_({ ss: ss, config: {}, sheets: {}, headers: {} });
    // Close the prepare/deploy/enable window: marker-0 writes made after the
    // first prepare receive their identities before marker 1 becomes visible.
    backfillCompatibilityIdentities_(ss);

    const configSheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
    const config = configValuesFromSheet_(configSheet, headerMap_(configSheet));
    if (!interactionSummaryReady_(config)) {
      throw new Error(
        'RECOVERABLE_APPLY_ENABLE_BLOCKED: interaction summary fast path must be enabled first'
      );
    }
    const reconciliation = reconcileRecoverableSyncApply_(ss);
    if (reconciliation.blockingDifferences.length) {
      throw new Error(
        'RECOVERABLE_APPLY_ENABLE_BLOCKED: ' +
        JSON.stringify(reconciliation.blockingDifferences.slice(0, 10))
      );
    }
    setConfigValue_(
      ss,
      CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY,
      CANN.RECOVERABLE_SYNC_APPLY_VERSION,
      'Recoverable multi-sheet apply version'
    );
    SpreadsheetApp.flush();
    const result = {
      configVersion: CANN.RECOVERABLE_SYNC_APPLY_VERSION,
      fastPathEnabled: true,
      blockingDifferences: 0
    };
    console.log(JSON.stringify({
      type: 'recoverable_sync_apply_enabled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function disableRecoverableSyncApply() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    repairRecoverableStateLocked_({ ss: ss, config: {}, sheets: {}, headers: {} });
    setConfigValue_(
      ss,
      CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY,
      0,
      'Recoverable multi-sheet apply version'
    );
    SpreadsheetApp.flush();
    const result = {
      configVersion: 0,
      fastPathEnabled: false,
      fallback: 'LEGACY_MULTI_WRITE'
    };
    console.log(JSON.stringify({
      type: 'recoverable_sync_apply_disabled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function repairRecoverableSyncApply() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const runtimeContext = {
      ss: ss,
      config: {},
      sheets: {},
      headers: {}
    };
    const pending = repairRecoverableStateLocked_(runtimeContext);
    const configSheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
    runtimeContext.config = configValuesFromSheet_(
      configSheet,
      headerMap_(configSheet)
    );
    runtimeContext.sheets = {
      purchases: requiredSheet_(ss, CANN.SHEETS.PURCHASES),
      responses: requiredSheet_(ss, CANN.SHEETS.RESPONSES),
      events: requiredSheet_(ss, CANN.SHEETS.EVENTS),
      ledger: requiredSheet_(ss, CANN.SHEETS.LEDGER),
      config: configSheet,
      migrationReport: requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT),
      applyJournal: requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL)
    };
    runtimeContext.headers = {
      purchases: headerMap_(runtimeContext.sheets.purchases),
      responses: headerMap_(runtimeContext.sheets.responses),
      events: headerMap_(runtimeContext.sheets.events),
      ledger: headerMap_(runtimeContext.sheets.ledger),
      config: headerMap_(configSheet),
      migrationReport: headerMap_(runtimeContext.sheets.migrationReport),
      applyJournal: headerMap_(runtimeContext.sheets.applyJournal)
    };
    const orphans = recoverableSyncApplyReady_(runtimeContext.config)
      ? repairOrphanFormResponsesLocked_(runtimeContext)
      : { repairedRows: 0, scannedRows: 0, issues: [] };
    const result = { pending: pending, orphanForms: orphans };
    console.log(JSON.stringify({
      type: 'recoverable_sync_apply_repaired',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function reconcileRecoverableSyncApply() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(CANN.LOCK_TIMEOUT_MS)) throw new Error('LOCK_TIMEOUT');
  try {
    const ss = spreadsheet_();
    assertConfigEnvironment_(ss);
    const result = reconcileRecoverableSyncApply_(ss);
    console.log(JSON.stringify({
      type: 'recoverable_sync_apply_reconciled',
      result: result
    }));
    return result;
  } finally {
    lock.releaseLock();
  }
}

function ensureCompatibilityIdentitySchema_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const headers = headerMap_(sheet);
  const eventIndex = headers[CANN.COMPATIBILITY_EVENT_HEADER];
  const requestIndex = headers[CANN.COMPATIBILITY_REQUEST_HEADER];
  if ((eventIndex === undefined) !== (requestIndex === undefined)) {
    throw new Error(
      'SCHEMA_MISMATCH: incomplete compatibility identity header pair'
    );
  }
  if (eventIndex !== undefined) {
    requireCompatibilityIdentityHeaders_(headers);
    return {
      eventColumn: eventIndex + 1,
      requestColumn: requestIndex + 1,
      originalOwnedColumns: eventIndex
    };
  }

  // getLastColumn() is the last column with content, not the grid width. This
  // places the identities after the actual Form-owned columns: J/K in the
  // inspected production sheet and H/I in the inspected sandbox.
  const firstIdentityColumn = sheet.getLastColumn() + 1;
  if (sheet.getMaxColumns() < firstIdentityColumn + 1) {
    sheet.insertColumnsAfter(
      sheet.getMaxColumns(),
      firstIdentityColumn + 1 - sheet.getMaxColumns()
    );
  }
  sheet.getRange(1, firstIdentityColumn, 1, 2).setValues([[
    CANN.COMPATIBILITY_EVENT_HEADER,
    CANN.COMPATIBILITY_REQUEST_HEADER
  ]]);
  return {
    eventColumn: firstIdentityColumn,
    requestColumn: firstIdentityColumn + 1,
    originalOwnedColumns: firstIdentityColumn - 1
  };
}

function backfillCompatibilityIdentities_(ss) {
  const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const responseHeaders = headerMap_(responses);
  requireCompatibilityIdentityHeaders_(responseHeaders);
  const events = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const eventHeaders = headerMap_(events);
  requireExactHeaders_(eventHeaders, CANN.EVENT_HEADERS, CANN.SHEETS.EVENTS);
  const responseLastRow = responses.getLastRow();
  if (responseLastRow < 2) return { rowsBackfilled: 0 };

  const responseRows = responses.getRange(
    2,
    1,
    responseLastRow - 1,
    responses.getLastColumn()
  ).getValues();
  const identityValues = responseRows.map(row => [
    value_(row, responseHeaders, CANN.COMPATIBILITY_EVENT_HEADER),
    value_(row, responseHeaders, CANN.COMPATIBILITY_REQUEST_HEADER)
  ]);
  const seenSourceRows = {};
  const validationErrors = [];
  let rowsBackfilled = 0;

  readDataRows_(events).forEach(row => {
    if (text_(value_(row, eventHeaders, 'Legacy Source Sheet')) !==
        responses.getName()) return;
    const sourceRow = Number(value_(row, eventHeaders, 'Legacy Source Row'));
    const eventId = text_(value_(row, eventHeaders, 'Event UUID'));
    if (!Number.isInteger(sourceRow) || sourceRow < 2 ||
        sourceRow > responseLastRow) {
      validationErrors.push({
        type: 'INVALID_SOURCE_ROW',
        eventId: eventId,
        sourceRow: sourceRow
      });
      return;
    }
    if (seenSourceRows[sourceRow] && seenSourceRows[sourceRow] !== eventId) {
      validationErrors.push({
        type: 'DUPLICATE_SOURCE_ROW',
        sourceRow: sourceRow,
        firstEventId: seenSourceRows[sourceRow],
        eventId: eventId
      });
      return;
    }
    seenSourceRows[sourceRow] = eventId;
    const responseRow = responseRows[sourceRow - 2];
    const timestampMatches =
      timestampMillisOrNull_(value_(responseRow, responseHeaders, 'Timestamp')) ===
      timestampMillisOrNull_(value_(row, eventHeaders, 'Timestamp'));
    const productMatches =
      text_(value_(responseRow, responseHeaders, 'Product')) ===
      text_(value_(row, eventHeaders, 'Legacy Product ID'));
    const responseUses = finiteNumber_(value_(responseRow, responseHeaders, 'Uses'));
    const canonicalUses = finiteNumber_(value_(row, eventHeaders, 'Uses'));
    const usesMatches = responseUses != null && canonicalUses != null &&
      Math.abs(responseUses - canonicalUses) <= 1e-9;
    if (!timestampMatches || !productMatches || !usesMatches) {
      validationErrors.push({
        type: 'SOURCE_ROW_CONTENT_MISMATCH',
        eventId: eventId,
        sourceRow: sourceRow
      });
      return;
    }

    const requestId = text_(value_(row, eventHeaders, 'Request UUID'));
    const existingEventId = text_(identityValues[sourceRow - 2][0]);
    const existingRequestId = text_(identityValues[sourceRow - 2][1]);
    if ((existingEventId && existingEventId !== eventId) ||
        (existingRequestId && existingRequestId !== requestId)) {
      validationErrors.push({
        type: 'IDENTITY_CONFLICT',
        eventId: eventId,
        sourceRow: sourceRow
      });
      return;
    }
    if (!existingEventId && eventId) rowsBackfilled++;
    identityValues[sourceRow - 2] = [eventId, requestId];
  });

  responses.getRange(
    2,
    responseHeaders[CANN.COMPATIBILITY_EVENT_HEADER] + 1,
    identityValues.length,
    2
  ).setValues(identityValues);
  SpreadsheetApp.flush();
  if (validationErrors.length) {
    throw new Error(
      'COMPATIBILITY_IDENTITY_BACKFILL_BLOCKED: safe rows were retained; ' +
      JSON.stringify({
        rowsBackfilled: rowsBackfilled,
        validationErrors: validationErrors.slice(0, 10)
      })
    );
  }
  return { rowsBackfilled: rowsBackfilled };
}

function reconcileRecoverableSyncApply_(ss) {
  const differences = [];
  const blockingDifferences = [];
  const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const responseHeaders = headerMap_(responses);
  requireCompatibilityIdentityHeaders_(responseHeaders);
  const events = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const eventHeaders = headerMap_(events);
  requireExactHeaders_(eventHeaders, CANN.EVENT_HEADERS, CANN.SHEETS.EVENTS);
  const responseLastRow = responses.getLastRow();
  const responseRows = responseLastRow < 2
    ? []
    : responses.getRange(
      2,
      1,
      responseLastRow - 1,
      responses.getLastColumn()
    ).getValues();
  const canonicalRows = readDataRows_(events);
  const canonicalByEventId = {};
  const seenSourceRows = {};

  canonicalRows.forEach((row, index) => {
    const eventId = text_(value_(row, eventHeaders, 'Event UUID'));
    if (!eventId) {
      blockingDifferences.push({
        type: 'MALFORMED_CANONICAL_EVENT',
        canonicalRow: index + 2
      });
      return;
    }
    if (canonicalByEventId[eventId]) {
      blockingDifferences.push({
        type: 'DUPLICATE_CANONICAL_EVENT_UUID',
        eventId: eventId
      });
    }
    canonicalByEventId[eventId] = { row: row, rowNumber: index + 2 };
    const lineageSheet = text_(value_(
      row,
      eventHeaders,
      'Legacy Source Sheet'
    ));
    if (lineageSheet !== responses.getName()) {
      blockingDifferences.push({
        type: 'MISSING_OR_INVALID_COMPATIBILITY_LINEAGE',
        eventId: eventId,
        sourceSheet: lineageSheet
      });
      return;
    }

    const sourceRow = Number(value_(row, eventHeaders, 'Legacy Source Row'));
    if (!Number.isInteger(sourceRow) || sourceRow < 2 ||
        sourceRow > responseLastRow) {
      blockingDifferences.push({
        type: 'INVALID_COMPATIBILITY_LINEAGE',
        eventId: eventId,
        sourceRow: sourceRow
      });
      return;
    }
    if (seenSourceRows[sourceRow] && seenSourceRows[sourceRow] !== eventId) {
      blockingDifferences.push({
        type: 'DUPLICATE_COMPATIBILITY_LINEAGE',
        eventId: eventId,
        sourceRow: sourceRow,
        firstEventId: seenSourceRows[sourceRow]
      });
      return;
    }
    seenSourceRows[sourceRow] = eventId;
    const responseRow = responseRows[sourceRow - 2];
    const identityMatches =
      text_(value_(
        responseRow,
        responseHeaders,
        CANN.COMPATIBILITY_EVENT_HEADER
      )) === eventId &&
      text_(value_(
        responseRow,
        responseHeaders,
        CANN.COMPATIBILITY_REQUEST_HEADER
      )) === text_(value_(row, eventHeaders, 'Request UUID'));
    const dataMatches =
      timestampMillisOrNull_(value_(responseRow, responseHeaders, 'Timestamp')) ===
        timestampMillisOrNull_(value_(row, eventHeaders, 'Timestamp')) &&
      text_(value_(responseRow, responseHeaders, 'Product')) ===
        text_(value_(row, eventHeaders, 'Legacy Product ID')) &&
      Math.abs(
        finiteNumberOr_(value_(responseRow, responseHeaders, 'Uses'), NaN) -
        finiteNumberOr_(value_(row, eventHeaders, 'Uses'), NaN)
      ) <= 1e-9;
    if (!identityMatches || !dataMatches) {
      blockingDifferences.push({
        type: 'COMPATIBILITY_LINEAGE_MISMATCH',
        eventId: eventId,
        sourceRow: sourceRow
      });
    }
  });

  if (responseRows.length) {
    const seenCompatibilityIds = {};
    responseRows.forEach((row, index) => {
      const eventId = text_(value_(
        row,
        responseHeaders,
        CANN.COMPATIBILITY_EVENT_HEADER
      ));
      if (!eventId) {
        const dataBearing =
          timestampMillisOrNull_(value_(
            row,
            responseHeaders,
            'Timestamp'
          )) != null &&
          !!text_(value_(row, responseHeaders, 'Product')) &&
          finiteNumber_(value_(row, responseHeaders, 'Uses')) != null;
        if (dataBearing) {
          blockingDifferences.push({
            type: 'UNIDENTIFIED_COMPATIBILITY_ROW',
            sourceRow: index + 2
          });
        }
        return;
      }
      if (seenCompatibilityIds[eventId]) {
        blockingDifferences.push({
          type: 'DUPLICATE_COMPATIBILITY_EVENT_UUID',
          eventId: eventId,
          sourceRow: index + 2
        });
      }
      seenCompatibilityIds[eventId] = true;
      if (!canonicalByEventId[eventId]) {
        blockingDifferences.push({
          type: 'COMPATIBILITY_WITHOUT_CANONICAL',
          eventId: eventId,
          sourceRow: index + 2
        });
      }
    });
  }

  const pendingApplyId = text_(configValue_(ss, CANN.PENDING_APPLY_KEY, ''));
  const journal = requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
  const journalHeaders = headerMap_(journal);
  requireExactHeaders_(
    journalHeaders,
    CANN.APPLY_JOURNAL_HEADERS,
    CANN.SHEETS.APPLY_JOURNAL
  );
  const incompleteJournalIds = [];
  readDataRows_(journal).forEach(row => {
    const applyId = text_(value_(row, journalHeaders, 'Apply UUID'));
    const state = text_(value_(row, journalHeaders, 'State'));
    if (state !== 'CORE_COMMITTED' && state !== 'COMPLETE') {
      blockingDifferences.push({
        type: 'INVALID_JOURNAL_STATE',
        applyId: applyId,
        state: state
      });
    }
    if (state === 'CORE_COMMITTED') incompleteJournalIds.push(applyId);
  });
  if (pendingApplyId) {
    blockingDifferences.push({
      type: 'PENDING_APPLY',
      applyId: pendingApplyId
    });
  }
  incompleteJournalIds.forEach(applyId => {
    blockingDifferences.push({
      type: 'INCOMPLETE_JOURNAL',
      applyId: applyId,
      pendingPointerMatches: pendingApplyId === applyId
    });
  });
  if (pendingApplyId &&
      incompleteJournalIds.indexOf(pendingApplyId) < 0) {
    blockingDifferences.push({
      type: 'PENDING_POINTER_WITHOUT_INCOMPLETE_JOURNAL',
      applyId: pendingApplyId
    });
  }
  if (incompleteJournalIds.length > 1) {
    blockingDifferences.push({
      type: 'MULTIPLE_INCOMPLETE_JOURNALS',
      applyIds: incompleteJournalIds
    });
  }

  const summary = reconcileInteractionSummary_(ss);
  summary.differences.forEach(item => {
    blockingDifferences.push({
      type: 'INTERACTION_SUMMARY_' + item.type,
      detail: item
    });
  });
  const compatibilityCanonical = reconcileReliabilityMigration_(ss);
  compatibilityCanonical.differences.forEach(item => {
    blockingDifferences.push({
      type: 'COMPATIBILITY_CANONICAL_DRIFT',
      detail: item
    });
  });
  if (compatibilityCanonical.responseEventCount !==
      compatibilityCanonical.canonicalEventCount) {
    blockingDifferences.push({
      type: 'COMPATIBILITY_CANONICAL_COUNT_MISMATCH',
      responseEventCount: compatibilityCanonical.responseEventCount,
      canonicalEventCount: compatibilityCanonical.canonicalEventCount
    });
  }
  if (compatibilityCanonical.unresolvedRows) {
    blockingDifferences.push({
      type: 'UNRESOLVED_MIGRATION_ROWS',
      unresolvedRows: compatibilityCanonical.unresolvedRows
    });
  }

  differences.push.apply(differences, blockingDifferences);
  return {
    pendingApplyId: pendingApplyId || null,
    journalRows: Math.max(0, journal.getLastRow() - 1),
    incompleteJournalRows: incompleteJournalIds.length,
    canonicalRows: canonicalRows.length,
    interactionSummaryDifferences: summary.differences.length,
    differences: differences,
    blockingDifferences: blockingDifferences
  };
}

/**
 * doPost reads Config before waiting for the script lock. Refresh every
 * mutation gate after acquiring it so a concurrent provision, enable, disable,
 * or predecessor core commit cannot be hidden by that stale request context.
 */
function refreshRecoverableSyncApplyStateLocked_(runtimeContext) {
  const ss = runtimeContext.ss;
  const configSheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
  const configHeaders = headerMap_(configSheet);
  requireExactHeaders_(
    configHeaders,
    ['Key', 'Value', 'Description'],
    CANN.SHEETS.CONFIG
  );
  const values = configValuesFromSheet_(configSheet, configHeaders);
  if (text_(values.ENVIRONMENT) !== runtimeContext.environment) {
    throw new Error('CONFIGURATION_ERROR: Config ENVIRONMENT mismatch');
  }
  assertSupportedSchemaVersion_(values);
  const summaryReady = interactionSummaryReady_(values);
  const recoverableReady = recoverableSyncApplyReady_(values);
  const correctionCapability = consumptionCorrectionCapability_(values);
  if (correctionCapability.version && !summaryReady) {
    throw new Error(
      'SCHEMA_MISMATCH: consumption corrections require the ' +
      'interaction-summary projection'
    );
  }
  runtimeContext.config[CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY] =
    values[CANN.RECOVERABLE_SYNC_APPLY_CONFIG_KEY];
  runtimeContext.config[CANN.PENDING_APPLY_KEY] =
    values[CANN.PENDING_APPLY_KEY];
  runtimeContext.config[CANN.CONSUMPTION_CORRECTION_VERSION_KEY] =
    values[CANN.CONSUMPTION_CORRECTION_VERSION_KEY];
  runtimeContext.config[CANN.CONSUMPTION_CORRECTION_WRITES_KEY] =
    values[CANN.CONSUMPTION_CORRECTION_WRITES_KEY];
  runtimeContext.config.CONSUMPTION_CORRECTION_VERSION =
    correctionCapability.version;
  runtimeContext.config.CONSUMPTION_CORRECTION_WRITES_ENABLED =
    correctionCapability.writesEnabled;
  runtimeContext.config.RECOVERABLE_SYNC_APPLY_READY =
    recoverableReady;
  if (recoverableReady) {
    const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
    runtimeContext.headers.responses = headerMap_(responses);
    requireCompatibilityIdentityHeaders_(runtimeContext.headers.responses);
    runtimeContext.sheets.applyJournal =
      requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
    runtimeContext.headers.applyJournal =
      headerMap_(runtimeContext.sheets.applyJournal);
    requireExactHeaders_(
      runtimeContext.headers.applyJournal,
      CANN.APPLY_JOURNAL_HEADERS,
      CANN.SHEETS.APPLY_JOURNAL
    );
  }
  if (correctionCapability.version) {
    runtimeContext.sheets.corrections =
      requiredSheet_(ss, CANN.SHEETS.CORRECTIONS);
    runtimeContext.headers.corrections =
      headerMap_(runtimeContext.sheets.corrections);
    requireExactHeaders_(
      runtimeContext.headers.corrections,
      CANN.CORRECTION_HEADERS,
      CANN.SHEETS.CORRECTIONS
    );
  }
}

function applyRecoverableSyncLocked_(settings) {
  const runtimeContext = settings.runtimeContext;
  const ss = runtimeContext.ss;
  // The caller already attempted repair under this same lock. Re-read the
  // durable marker rather than trusting the pre-lock request context.
  const pendingBefore = text_(configValue_(ss, CANN.PENDING_APPLY_KEY, ''));
  if (pendingBefore) {
    throw new Error(
      'RECOVERABLE_APPLY_BLOCKED: pending apply was not repaired ' +
      pendingBefore
    );
  }

  const applyId = Utilities.getUuid();
  const now = new Date();
  const stagedPurchases = settings.stagedPurchases || [];
  const stagedConsumptions = settings.stagedConsumptions || [];
  const stagedFinishActions = settings.stagedFinishActions || [];
  const stagedConsumptionCorrections =
    settings.stagedConsumptionCorrections || [];
  const correctionProjectionEffects =
    settings.correctionProjectionEffects || [];
  const purchasePlan = planRecoverablePurchaseRows_(
    settings.productContext,
    stagedPurchases,
    now,
    runtimeContext.config.TAX_RATE
  );
  const effects = calculateProductEffects_(
    settings.productContext,
    stagedConsumptions
  );
  applyEffectsToPlannedPurchaseRows_(
    purchasePlan,
    effects,
    settings.productContext.headers
  );
  applyFinishActionsToPlannedPurchaseRows_(
    purchasePlan,
    stagedFinishActions,
    settings.productContext.headers
  );

  const responseRows = settings.compatibilityExistingRow
    ? []
    : buildRecoverableCompatibilityRows_(
      runtimeContext,
      stagedConsumptions
    );
  const eventRows = buildRecoverableEventRows_(stagedConsumptions);
  const correctionRows = buildRecoverableCorrectionRows_(
    stagedConsumptionCorrections
  );
  const journalSheet = requiredSheet_(
    ss,
    CANN.SHEETS.APPLY_JOURNAL
  );
  const responseLastRowBefore =
    runtimeContext.sheets.responses.getLastRow();
  const eventFirstRow = runtimeContext.sheets.events.getLastRow() + 1;
  const journalRowNumber = journalSheet.getLastRow() + 1;
  const plan = {
    applyId: applyId,
    kind: settings.kind,
    requestId: settings.requestId || '',
    eventIds: stagedConsumptions.map(item => item.eventId),
    correctionActions: stagedConsumptionCorrections.map(item => ({
      actionId: item.correctionId,
      targetEventId: item.targetEventId,
      supersedesCorrectionId: item.supersedesCorrectionId,
      revision: item.revision,
      operation: item.operation,
      reopenProduct: item.reopenProduct,
      reopenLegacyProductId: item.reopenLegacyProductId || null,
      affectedProductIds: item.affectedProductIds || []
    })),
    finishActions: stagedFinishActions.map(item => ({
      actionId: item.actionId,
      productUuid: item.productUuid,
      legacyProductId: item.legacyProductId,
      timestamp: item.timestamp
    })),
    compatibilitySheet: runtimeContext.sheets.responses.getName(),
    formRefreshRequired: settings.formRefreshRequired === true,
    ledger: settings.ledger ? {
      requestId: settings.requestId,
      apiVersion: CANN.API_VERSION,
      receivedAtEpochMillis: now.getTime(),
      durationMsAtCoreStart: settings.ledger.durationMs,
      purchaseCount: settings.ledger.purchaseCount,
      consumptionCount: settings.ledger.consumptionCount,
      result: settings.ledger.result,
      durationMs: settings.ledger.durationMs,
      errorCode: settings.ledger.errorCode || ''
    } : null
  };

  const coreRequests = [];
  if (purchasePlan.rows.length) {
    coreRequests.push(appendCellsRequest_(
      runtimeContext.sheets.purchases,
      purchasePlan.rows
    ));
  }
  if (settings.compatibilityExistingRow) {
    const responseHeaders = runtimeContext.headers.responses;
    requireCompatibilityIdentityHeaders_(responseHeaders);
    const event = stagedConsumptions[0];
    const currentIdentity = runtimeContext.sheets.responses.getRange(
      settings.compatibilityExistingRow,
      responseHeaders[CANN.COMPATIBILITY_EVENT_HEADER] + 1,
      1,
      2
    ).getValues()[0];
    if ((text_(currentIdentity[0]) &&
         text_(currentIdentity[0]) !== event.eventId) ||
        (text_(currentIdentity[1]) &&
         text_(currentIdentity[1]) !== text_(event.requestId))) {
      throw new Error(
        'FORM_COMPATIBILITY_IDENTITY_CONFLICT: row ' +
        settings.compatibilityExistingRow
      );
    }
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.responses,
      settings.compatibilityExistingRow,
      responseHeaders[CANN.COMPATIBILITY_EVENT_HEADER] + 1,
      [[event.eventId, event.requestId || '']]
    ));
  } else if (responseRows.length) {
    coreRequests.push(appendCellsRequest_(
      runtimeContext.sheets.responses,
      responseRows
    ));
  }
  maybeInjectSandboxSyncApplyBatchFault_(
    CANN.SYNC_APPLY_FAULTS.COMPATIBILITY,
    coreRequests
  );

  if (eventRows.length) {
    coreRequests.push(appendCellsRequest_(
      runtimeContext.sheets.events,
      eventRows
    ));
  }
  if (correctionRows.length) {
    coreRequests.push(appendCellsRequest_(
      runtimeContext.sheets.corrections,
      correctionRows
    ));
  }
  maybeInjectSandboxSyncApplyBatchFault_(
    CANN.SYNC_APPLY_FAULTS.CANONICAL,
    coreRequests
  );

  effects.filter(effect => !effect.pendingAppend).forEach(effect => {
    const headers = settings.productContext.headers;
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Finished'] + 1,
      [[effect.status]]
    ));
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Uses'] + 1,
      [[effect.uses]]
    ));
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Finished At'] + 1,
      [[effect.finishedAt || '']]
    ));
  });
  maybeInjectSandboxSyncApplyBatchFault_(
    CANN.SYNC_APPLY_FAULTS.PRODUCT_EFFECTS,
    coreRequests
  );

  calculateFinishEffects_(settings.productContext, stagedFinishActions)
    .filter(effect => !effect.pendingAppend)
    .forEach(effect => {
      const headers = settings.productContext.headers;
      coreRequests.push(updateCellsRequest_(
        runtimeContext.sheets.purchases,
        effect.rowNumber,
        headers['Finished'] + 1,
        [[effect.status]]
      ));
      coreRequests.push(updateCellsRequest_(
        runtimeContext.sheets.purchases,
        effect.rowNumber,
        headers['Finished At'] + 1,
        [[effect.finishedAt]]
      ));
    });

  effects.filter(effect => !effect.pendingAppend).forEach(effect => {
    const headers = settings.productContext.headers;
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Most recent use'] + 1,
      [[effect.mostRecentUse || '']]
    ));
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Last quantity'] + 1,
      [[effect.lastQuantity == null ? '' : effect.lastQuantity]]
    ));
  });
  correctionProjectionEffects.forEach(effect => {
    const headers = settings.productContext.headers;
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Finished'] + 1,
      [[effect.status]]
    ));
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Uses'] + 1,
      [[effect.uses]]
    ));
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Most recent use'] + 1,
      [[effect.mostRecentUse || '']]
    ));
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Finished At'] + 1,
      [[effect.finishedAt || '']]
    ));
    coreRequests.push(updateCellsRequest_(
      runtimeContext.sheets.purchases,
      effect.rowNumber,
      headers['Last quantity'] + 1,
      [[effect.lastQuantity == null ? '' : effect.lastQuantity]]
    ));
  });
  maybeInjectSandboxSyncApplyBatchFault_(
    CANN.SYNC_APPLY_FAULTS.INTERACTION_SUMMARY,
    coreRequests
  );

  coreRequests.push(appendCellsRequest_(
    journalSheet,
    [[
      applyId,
      settings.kind,
      settings.apiVersion,
      settings.requestId || '',
      'CORE_COMMITTED',
      now,
      '',
      JSON.stringify(plan),
      settings.response == null ? '' : JSON.stringify(settings.response)
    ]]
  ));
  coreRequests.push(updateCellsRequest_(
    requiredSheet_(ss, CANN.SHEETS.CONFIG),
    findConfigRowNumber_(ss, CANN.PENDING_APPLY_KEY),
    2,
    [[applyId]]
  ));

  const coreStarted = Date.now();
  sheetsBatchUpdate_(ss, coreRequests);
  recordBackendPhase_(settings.timing, 'recoverableCoreBatch', coreStarted);
  maybeInjectSandboxSyncApplyFault_(
    CANN.SYNC_APPLY_FAULTS.CORE_COMMITTED
  );
  const materialized = materializedRecoverableRowsAfterCore_(
    ss,
    {
      applyId: applyId,
      kind: settings.kind,
      requestId: settings.requestId || '',
      now: now,
      plan: plan,
      response: settings.response,
      eventIds: plan.eventIds,
      compatibilityExistingRow: settings.compatibilityExistingRow,
      responseLastRowBefore: responseLastRowBefore,
      compatibilityEventColumn:
        runtimeContext.headers.responses[
          CANN.COMPATIBILITY_EVENT_HEADER
        ] + 1,
      eventFirstRow: eventFirstRow,
      eventRowCount: eventRows.length,
      journalRowNumber: journalRowNumber
    }
  );

  if (settings.formRefreshRequired) {
    const formStarted = Date.now();
    try {
      // This operation is intentionally between the two atomic batches. It is
      // safe to repeat and repair reruns it before finalization.
      updateFormAndDescriptionLocked_(ss);
    } catch (error) {
      if (settings.apiVersion === 1) {
        // V1 has no stable request/action/event identity across an HTTP retry.
        // Return its already-committed success rather than invite a duplicate;
        // leave CORE_COMMITTED + pending marker for explicit/next-run repair.
        console.error(
          'V1 Form refresh deferred to recoverable repair: ' +
          conciseError_(error)
        );
        bumpMutationWatermark_();
        return {
          applyId: applyId,
          complete: false,
          pendingRepair: true,
          v1RetryBoundary: true
        };
      }
      throw error;
    }
    recordBackendPhase_(settings.timing, 'formRefresh', formStarted);
  }

  const finalStarted = Date.now();
  const result = finalizeRecoverableApplyLocked_(ss, applyId, {
    ledgerDurationMs: settings.ledger &&
      settings.ledger.startedAtEpochMillis != null
      ? Date.now() - settings.ledger.startedAtEpochMillis
      : null,
    journalRecord: materialized.journalRecord,
    canonicalRowsByEventId: materialized.canonicalRowsByEventId,
    compatibilityRowsByEventId:
      materialized.compatibilityRowsByEventId
  });
  recordBackendPhase_(settings.timing, 'recoverableFinalBatch', finalStarted);
  maybeInjectSandboxSyncApplyFault_(
    CANN.SYNC_APPLY_FAULTS.POST_COMPLETE
  );

  effects.forEach(effect => {
    const product =
      settings.productContext.byLegacyId[effect.legacyProductId];
    if (!product) return;
    product.status = effect.status;
    product.uses = effect.uses;
    product.mostRecentUse = effect.mostRecentUse;
    product.finishedAt = effect.finishedAt;
    product.lastQuantity = effect.lastQuantity;
  });
  calculateFinishEffects_(settings.productContext, stagedFinishActions)
    .forEach(effect => {
      const product = settings.productContext.byLegacyId[effect.legacyProductId];
      if (!product) return;
      product.status = effect.status;
      product.finishedAt = effect.finishedAt;
    });
  correctionProjectionEffects.forEach(effect => {
    const product =
      settings.productContext.byLegacyId[effect.legacyProductId];
    if (!product) return;
    product.status = effect.status;
    product.uses = effect.uses;
    product.mostRecentUse = effect.mostRecentUse;
    product.finishedAt = effect.finishedAt;
    product.lastQuantity = effect.lastQuantity;
  });
  bumpMutationWatermark_();
  return result;
}

function planRecoverablePurchaseRows_(context, staged, now, configuredTaxRate) {
  const taxRate = finiteNumberOr_(configuredTaxRate, 0.13);
  const rows = staged.map(item => {
    const row = newPurchaseRow_(item, now, taxRate);
    row.push('');
    item.pendingAppend = true;
    item.row = row;
    item.status = CANN.STATUS.UNOPENED;
    item.uses = 0;
    item.mostRecentUse = null;
    item.finishedAt = null;
    item.lastQuantity = null;
    context.byLegacyId[item.legacyProductId] = item;
    if (item.productUuid) context.byProductUuid[item.productUuid] = item;
    if (item.actionId && context.byActionId) {
      context.byActionId[item.actionId] = item;
    }
    return row;
  });
  const byLegacyId = {};
  staged.forEach((item, index) => {
    byLegacyId[item.legacyProductId] = rows[index];
  });
  return { rows: rows, byLegacyId: byLegacyId };
}

function applyEffectsToPlannedPurchaseRows_(purchasePlan, effects, headers) {
  effects.forEach(effect => {
    const row = purchasePlan.byLegacyId[effect.legacyProductId];
    if (!row) return;
    row[headers['Finished']] = effect.status;
    row[headers['Uses']] = effect.uses;
    row[headers['Most recent use']] = effect.mostRecentUse || '';
    row[headers['Finished At']] = effect.finishedAt || '';
    row[headers['Last quantity']] =
      effect.lastQuantity == null ? '' : effect.lastQuantity;
    effect.pendingAppend = true;
  });
}

function applyFinishActionsToPlannedPurchaseRows_(purchasePlan, finishActions, headers) {
  finishActions.forEach(action => {
    const row = purchasePlan.byLegacyId[action.legacyProductId];
    if (!row) return;
    row[headers['Finished']] = CANN.STATUS.FINISHED;
    row[headers['Finished At']] = action.timestamp;
  });
}

function buildRecoverableCompatibilityRows_(runtimeContext, staged) {
  const sheet = runtimeContext.sheets.responses;
  const headers = runtimeContext.headers.responses;
  requireHeaders_(headers, ['Timestamp', 'Product', 'Uses']);
  requireCompatibilityIdentityHeaders_(headers);
  return staged.map(item => {
    const row = Array(sheet.getLastColumn()).fill('');
    row[headers.Timestamp] = item.timestamp;
    row[headers.Product] = item.legacyProductId;
    row[headers.Uses] = item.uses;
    if (headers.Date !== undefined) row[headers.Date] = item.localDate;
    if (headers.Time !== undefined) row[headers.Time] = item.localTime;
    if (headers['Weight code'] !== undefined) {
      row[headers['Weight code']] = item.weightCode || '';
    }
    if (headers['Mark as Finished?'] !== undefined) {
      row[headers['Mark as Finished?']] = item.isFinished ? 'Yes' : '';
    }
    row[headers[CANN.COMPATIBILITY_EVENT_HEADER]] = item.eventId;
    row[headers[CANN.COMPATIBILITY_REQUEST_HEADER]] =
      item.requestId || '';
    return row;
  });
}

function buildRecoverableEventRows_(staged) {
  return staged.map(item => [
    item.eventId, item.timestamp, item.localDate, item.localTime,
    item.productUuid, item.legacyProductId, item.uses,
    item.weightCode || '', !!item.isFinished, item.source,
    item.requestId || '', '', ''
  ]);
}

function buildRecoverableCorrectionRows_(staged) {
  return (staged || []).map(item => {
    const replacement = item.replacement;
    return [
      item.correctionId,
      item.targetEventId,
      item.supersedesCorrectionId || '',
      item.revision,
      item.operation,
      replacement
        ? new Date(replacement.occurredAtEpochMillis)
        : '',
      replacement ? replacement.localDate : '',
      replacement ? replacement.localTime : '',
      replacement ? replacement.product.productUuid : '',
      replacement ? replacement.product.productId : '',
      replacement ? replacement.quantity : '',
      replacement ? (replacement.weightCode || '') : '',
      replacement ? replacement.finished : '',
      item.reopenProduct === true,
      item.reason || '',
      item.requestId,
      item.source,
      new Date(item.createdAtEpochMillis)
    ];
  });
}

function repairRecoverableStateLocked_(runtimeContext) {
  const ss = runtimeContext.ss;
  const first = repairPendingSyncApplyLocked_(runtimeContext);
  const journal = requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
  const headers = headerMap_(journal);
  const incomplete = readDataRows_(journal).filter(row =>
    text_(value_(row, headers, 'State')) === 'CORE_COMMITTED'
  ).map(row => text_(value_(row, headers, 'Apply UUID')));
  if (!incomplete.length) return first;
  if (incomplete.length > 1) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: multiple incomplete journals ' +
      JSON.stringify(incomplete)
    );
  }
  const pending = text_(configValue_(ss, CANN.PENDING_APPLY_KEY, ''));
  if (pending && pending !== incomplete[0]) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: pending pointer does not match journal'
    );
  }
  if (!pending) {
    sheetsBatchUpdate_(ss, [updateCellsRequest_(
      requiredSheet_(ss, CANN.SHEETS.CONFIG),
      findConfigRowNumber_(ss, CANN.PENDING_APPLY_KEY),
      2,
      [[incomplete[0]]]
    )]);
  }
  return repairPendingSyncApplyLocked_(runtimeContext);
}

function repairPendingSyncApplyLocked_(runtimeContext) {
  const ss = runtimeContext.ss;
  // Always reread under the acquired script lock. A predecessor may have
  // committed its core while this request was waiting.
  const applyId = text_(configValue_(ss, CANN.PENDING_APPLY_KEY, ''));
  if (!applyId) return { repaired: false, pendingApplyId: null };
  const journalRecord = readApplyJournalRecord_(ss, applyId);
  if (!journalRecord) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: pending apply has no journal row ' +
      applyId
    );
  }
  if (journalRecord.state === 'COMPLETE') {
    sheetsBatchUpdate_(ss, [updateCellsRequest_(
      requiredSheet_(ss, CANN.SHEETS.CONFIG),
      findConfigRowNumber_(ss, CANN.PENDING_APPLY_KEY),
      2,
      [['']]
    )]);
    bumpMutationWatermark_();
    return {
      repaired: true,
      applyId: applyId,
      previousState: 'COMPLETE'
    };
  }
  if (journalRecord.state !== 'CORE_COMMITTED') {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: invalid journal state ' +
      journalRecord.state
    );
  }
  const plan = JSON.parse(journalRecord.finalizationJson);
  if (plan.formRefreshRequired) updateFormAndDescriptionLocked_(ss);
  const result = finalizeRecoverableApplyLocked_(ss, applyId);
  bumpMutationWatermark_();
  return {
    repaired: true,
    applyId: applyId,
    previousState: journalRecord.state,
    result: result
  };
}

function repairOrphanFormResponsesLocked_(runtimeContext) {
  const ss = runtimeContext.ss;
  if (!recoverableSyncApplyReady_(runtimeContext.config)) {
    return { repairedRows: 0, checkedThroughRow: null, issues: [] };
  }
  const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const responseHeaders = headerMap_(responses);
  requireCompatibilityIdentityHeaders_(responseHeaders);
  runtimeContext.sheets.responses = responses;
  runtimeContext.headers.responses = responseHeaders;

  const lastRow = responses.getLastRow();
  if (lastRow < 2) {
    return {
      repairedRows: 0,
      scannedRows: 0,
      issues: []
    };
  }

  const rows = responses.getRange(
    2,
    1,
    lastRow - 1,
    responses.getLastColumn()
  ).getValues();
  const issues = [];
  let repairedRows = 0;
  for (let index = 0; index < rows.length; index++) {
    const rowNumber = index + 2;
    const row = rows[index];
    const eventIdentity = text_(value_(
      row,
      responseHeaders,
      CANN.COMPATIBILITY_EVENT_HEADER
    ));
    const requestIdentity = text_(value_(
      row,
      responseHeaders,
      CANN.COMPATIBILITY_REQUEST_HEADER
    ));
    if (eventIdentity) continue;
    if (requestIdentity) {
      issues.push({
        type: 'FORM_ORPHAN_IDENTITY_CONFLICT',
        rowNumber: rowNumber
      });
      continue;
    }
    if (!row.some(cell => cell !== '' && cell != null)) continue;

    const timestamp = dateOrNull_(
      value_(row, responseHeaders, 'Timestamp')
    );
    const legacyId = text_(value_(row, responseHeaders, 'Product'));
    const uses = finiteNumber_(value_(row, responseHeaders, 'Uses'));
    const context = productContext_(ss, {
      runtimeContext: runtimeContext
    });
    const product = context.byLegacyId[legacyId];
    if (!timestamp || !product || uses == null) {
      issues.push({
        type: 'FORM_ORPHAN_INVALID',
        rowNumber: rowNumber,
        productId: legacyId
      });
      continue;
    }

    const eventId = deterministicLegacyEventUuid_(
      ss.getId(),
      responses.getName(),
      rowNumber
    );
    if (eventContext_(ss, runtimeContext, [eventId]).eventIds.has(eventId)) {
      issues.push({
        type: 'FORM_ORPHAN_CANONICAL_WITHOUT_IDENTITY',
        rowNumber: rowNumber,
        eventId: eventId
      });
      continue;
    }
    const event = {
      eventId: eventId,
      timestamp: timestamp,
      localDate: formatDate_(timestamp),
      localTime: formatTime_(timestamp),
      productUuid: product.productUuid,
      legacyProductId: product.legacyProductId,
      uses: uses,
      weightCode: text_(value_(row, responseHeaders, 'Weight code')),
      isFinished: truthy_(value_(
        row,
        responseHeaders,
        'Mark as Finished?'
      )),
      source: 'FORM_RECOVERY',
      requestId: '',
      legacySourceSheet: responses.getName(),
      legacySourceRow: rowNumber,
      compatibilityRow: null
    };
    applyRecoverableSyncLocked_({
      runtimeContext: runtimeContext,
      productContext: context,
      stagedPurchases: [],
      stagedConsumptions: [event],
      kind: 'FORM_RECOVERY',
      apiVersion: 0,
      requestId: '',
      response: null,
      formRefreshRequired: true,
      compatibilityExistingRow: rowNumber,
      ledger: null,
      timing: null
    });
    repairedRows++;
  }

  return {
    repairedRows: repairedRows,
    scannedRows: rows.length,
    issues: issues
  };
}

function finalizeRecoverableApplyLocked_(ss, applyId, options) {
  const settings = options || {};
  const journalRecord = settings.journalRecord ||
    readApplyJournalRecord_(ss, applyId);
  if (!journalRecord) {
    throw new Error('RECOVERABLE_APPLY_CORRUPT: missing journal ' + applyId);
  }
  if (journalRecord.state === 'COMPLETE') {
    return { applyId: applyId, alreadyComplete: true };
  }
  if (journalRecord.state !== 'CORE_COMMITTED') {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: invalid journal state ' +
      journalRecord.state
    );
  }
  const plan = JSON.parse(journalRecord.finalizationJson);
  const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const responseHeaders = headerMap_(responses);
  requireCompatibilityIdentityHeaders_(responseHeaders);
  const events = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const eventHeaders = headerMap_(events);
  const finalRequests = [];

  const plannedEventIds = plan.eventIds || [];
  const canonicalRowsByEventId =
    settings.canonicalRowsByEventId ||
    resolveUniqueRowsByIds_(
      events,
      eventHeaders['Event UUID'] + 1,
      plannedEventIds
    );
  const compatibilityRowsByEventId =
    settings.compatibilityRowsByEventId ||
    resolveUniqueRowsByIds_(
      responses,
      responseHeaders[CANN.COMPATIBILITY_EVENT_HEADER] + 1,
      plannedEventIds
    );
  plannedEventIds.forEach(eventId => {
    const canonicalRow = canonicalRowsByEventId[eventId];
    const compatibilityRow = compatibilityRowsByEventId[eventId];
    if (!canonicalRow || !compatibilityRow) {
      throw new Error(
        'RECOVERABLE_APPLY_CORRUPT: missing materialized row for ' +
        eventId
      );
    }
    finalRequests.push(updateCellsRequest_(
      events,
      canonicalRow,
      eventHeaders['Legacy Source Sheet'] + 1,
      [[responses.getName(), compatibilityRow]]
    ));
  });

  if (plan.ledger) {
    const ledger = requiredSheet_(ss, CANN.SHEETS.LEDGER);
    const ledgerHeaders = headerMap_(ledger);
    const existingLedgerRow = findOptionalUniqueExactCellRow_(
      ledger,
      ledgerHeaders['Request UUID'] + 1,
      plan.ledger.requestId
    );
    const ledgerValues = [[
      plan.ledger.requestId,
      plan.ledger.apiVersion,
      new Date(plan.ledger.receivedAtEpochMillis),
      plan.ledger.purchaseCount,
      plan.ledger.consumptionCount,
      plan.ledger.result,
      settings.ledgerDurationMs != null
        ? settings.ledgerDurationMs
        : plan.ledger.durationMsAtCoreStart,
      plan.ledger.errorCode || ''
    ]];
    if (existingLedgerRow) {
      finalRequests.push(updateCellsRequest_(
        ledger,
        existingLedgerRow,
        1,
        ledgerValues
      ));
    } else {
      finalRequests.push(appendCellsRequest_(ledger, ledgerValues));
    }
    // Add an invalid request to the same final transaction. Sheets rejects the
    // whole batch, proving that lineage/ledger/state/clear cannot split.
    maybeInjectSandboxSyncApplyBatchFault_(
      CANN.SYNC_APPLY_FAULTS.LEDGER,
      finalRequests
    );
  }

  finalRequests.push(updateCellsRequest_(
    requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL),
    journalRecord.rowNumber,
    5,
    [['COMPLETE', journalRecord.coreCommittedAt, new Date()]]
  ));
  finalRequests.push(updateCellsRequest_(
    requiredSheet_(ss, CANN.SHEETS.CONFIG),
    findConfigRowNumber_(ss, CANN.PENDING_APPLY_KEY),
    2,
    [['']]
  ));
  sheetsBatchUpdate_(ss, finalRequests);
  return {
    applyId: applyId,
    eventCount: (plan.eventIds || []).length,
    ledgerFinalized: !!plan.ledger,
    complete: true
  };
}

function readApplyJournalRecord_(ss, applyId) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
  const headers = headerMap_(sheet);
  requireExactHeaders_(
    headers,
    CANN.APPLY_JOURNAL_HEADERS,
    CANN.SHEETS.APPLY_JOURNAL
  );
  const rowNumber = findOptionalUniqueExactCellRow_(
    sheet,
    headers['Apply UUID'] + 1,
    applyId
  );
  if (!rowNumber) return null;
  const row = sheet.getRange(
    rowNumber,
    1,
    1,
    sheet.getLastColumn()
  ).getValues()[0];
  return {
    rowNumber: rowNumber,
    state: text_(value_(row, headers, 'State')),
    coreCommittedAt: value_(row, headers, 'Core Committed At'),
    finalizationJson: text_(value_(row, headers, 'Finalization JSON')),
    responseJson: text_(value_(row, headers, 'Response JSON'))
  };
}

/**
 * Finish actions have no event row by design. Their immutable action IDs live
 * in the recoverable apply plan, which lets a lost response retry acknowledge
 * the completed finish without rewriting its timestamp.
 */
function finishActionContext_(ss, runtimeContext, submittedActionIds) {
  const wanted = {};
  (submittedActionIds || []).forEach(actionId => { wanted[text_(actionId)] = true; });
  const matches = {};
  if (!Object.keys(wanted).length) return matches;
  const sheet = runtimeContext && runtimeContext.sheets.applyJournal
    ? runtimeContext.sheets.applyJournal
    : requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
  const headers = runtimeContext && runtimeContext.headers.applyJournal
    ? runtimeContext.headers.applyJournal
    : headerMap_(sheet);
  readDataRows_(sheet).forEach(row => {
    const finalizationJson = text_(value_(row, headers, 'Finalization JSON'));
    if (!finalizationJson) return;
    let plan;
    try {
      plan = JSON.parse(finalizationJson);
    } catch (error) {
      throw new Error('RECOVERABLE_APPLY_CORRUPT: invalid finalization JSON');
    }
    (plan.finishActions || []).forEach(action => {
      const actionId = text_(action && action.actionId);
      if (!wanted[actionId]) return;
      if (matches[actionId]) {
        throw new Error(
          'RECOVERABLE_APPLY_CORRUPT: duplicate finish action ' + actionId
        );
      }
      matches[actionId] = action;
    });
  });
  return matches;
}

function recoverableApplyEvidence_(ss, runtimeContext) {
  const sheet = runtimeContext && runtimeContext.sheets && runtimeContext.sheets.applyJournal
    ? runtimeContext.sheets.applyJournal
    : requiredSheet_(ss, CANN.SHEETS.APPLY_JOURNAL);
  const headers = runtimeContext && runtimeContext.headers && runtimeContext.headers.applyJournal
    ? runtimeContext.headers.applyJournal
    : headerMap_(sheet);
  const rows = readDataRows_(sheet);
  return recoverableApplyEvidenceFromValues_(rows, headers);
}

function recoverableApplyEvidenceFromValues_(rows, headers) {
  const evidence = {
    eventIds: {},
    correctionIds: {},
    finishActionProductIds: {}
  };
  const safeHeaders = headers || {};
  (rows || []).forEach(row => {
    if (!row) return;
    const cells = Array.isArray(row) ? row : row.cells;
    if (!cells || !cells.some(cell => cell !== '' && cell != null)) return;
    if (text_(value_(cells, safeHeaders, 'State')) !== 'COMPLETE') return;
    const finalizationJson = text_(value_(
      cells,
      safeHeaders,
      'Finalization JSON'
    ));
    if (!finalizationJson) return;
    if (
      finalizationJson.indexOf('"eventIds"') === -1 &&
      finalizationJson.indexOf('"correctionActions"') === -1 &&
      finalizationJson.indexOf('"finishActions"') === -1
    ) {
      return;
    }
    let plan;
    try {
      plan = JSON.parse(finalizationJson);
    } catch (error) {
      throw new Error('RECOVERABLE_APPLY_CORRUPT: invalid finalization JSON');
    }
    (plan.eventIds || []).forEach(eventId => {
      const normalized = text_(eventId).toLowerCase();
      if (normalized) evidence.eventIds[normalized] = true;
    });
    (plan.correctionActions || []).forEach(action => {
      const correctionId = text_(action && action.actionId).toLowerCase();
      if (correctionId) evidence.correctionIds[correctionId] = true;
    });
    (plan.finishActions || []).forEach(action => {
      const productUuid = text_(action && action.productUuid).toLowerCase();
      const legacyProductId = text_(action && action.legacyProductId);
      if (productUuid) {
        evidence.finishActionProductIds[productUuid] = true;
      }
      if (legacyProductId) {
        evidence.finishActionProductIds[legacyProductId] = true;
      }
    });
  });
  return evidence;
}

function findConfigRowNumber_(ss, key) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
  const row = findOptionalUniqueExactCellRow_(sheet, 1, key);
  if (!row) throw new Error('SCHEMA_MISMATCH: missing Config key ' + key);
  return row;
}

function findUniqueExactCellRow_(sheet, columnNumber, value) {
  const row = findOptionalUniqueExactCellRow_(sheet, columnNumber, value);
  if (!row) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: missing ' + value +
      ' in ' + sheet.getName()
    );
  }
  return row;
}

function resolveUniqueRowsByIds_(sheet, columnNumber, ids) {
  const submitted = Array.from(new Set((ids || []).map(text_).filter(Boolean)));
  const resolved = {};
  if (!submitted.length) return resolved;
  if (submitted.length <= CANN.EVENT_TEXT_FINDER_MAX_BATCH) {
    submitted.forEach(id => {
      resolved[id] = findUniqueExactCellRow_(sheet, columnNumber, id);
    });
    return resolved;
  }

  const wanted = {};
  submitted.forEach(id => { wanted[id] = true; });
  const lastRow = sheet.getLastRow();
  if (lastRow >= 2) {
    sheet.getRange(2, columnNumber, lastRow - 1, 1)
      .getValues()
      .forEach((row, index) => {
        const id = text_(row[0]);
        if (!wanted[id]) return;
        if (resolved[id]) {
          throw new Error(
            'RECOVERABLE_APPLY_CORRUPT: duplicate ' + id +
            ' in ' + sheet.getName()
          );
        }
        resolved[id] = index + 2;
      });
  }
  submitted.forEach(id => {
    if (!resolved[id]) {
      throw new Error(
        'RECOVERABLE_APPLY_CORRUPT: missing ' + id +
        ' in ' + sheet.getName()
      );
    }
  });
  return resolved;
}

function findOptionalUniqueExactCellRow_(sheet, columnNumber, value) {
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return null;
  const matches = sheet.getRange(2, columnNumber, lastRow - 1, 1)
    .createTextFinder(String(value))
    .matchEntireCell(true)
    .matchCase(true)
    .useRegularExpression(false)
    .findAll();
  if (matches.length > 1) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: duplicate ' + value +
      ' in ' + sheet.getName()
    );
  }
  return matches.length ? matches[0].getRow() : null;
}

function appendCellsRequest_(sheet, rows) {
  return {
    appendCells: {
      sheetId: sheet.getSheetId(),
      rows: rows.map(row => ({
        values: row.map(sheetCellData_)
      })),
      fields: 'userEnteredValue'
    }
  };
}

function updateCellsRequest_(sheet, rowNumber, columnNumber, rows) {
  return {
    updateCells: {
      start: {
        sheetId: sheet.getSheetId(),
        rowIndex: rowNumber - 1,
        columnIndex: columnNumber - 1
      },
      rows: rows.map(row => ({
        values: row.map(sheetCellData_)
      })),
      fields: 'userEnteredValue'
    }
  };
}

function sheetCellData_(value) {
  if (value == null || value === '') return {};
  if (Object.prototype.toString.call(value) === '[object Date]') {
    if (!Number.isFinite(value.getTime())) return {};
    return {
      userEnteredValue: {
        numberValue: spreadsheetLocalDateSerial_(value)
      }
    };
  }
  if (typeof value === 'boolean') {
    return { userEnteredValue: { boolValue: value } };
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return { userEnteredValue: { numberValue: value } };
  }
  return { userEnteredValue: { stringValue: String(value) } };
}

function spreadsheetLocalDateSerial_(value) {
  // Sheets serials represent spreadsheet-local wall-clock components. A raw
  // UTC epoch/86400000 value shifts visible timestamps by the UTC offset.
  const parts = Utilities.formatDate(
    new Date(value),
    CANN.TIME_ZONE,
    'yyyy,MM,dd,HH,mm,ss,SSS'
  ).split(',').map(Number);
  return Date.UTC(
    parts[0],
    parts[1] - 1,
    parts[2],
    parts[3],
    parts[4],
    parts[5],
    parts[6]
  ) / 86400000 + 25569;
}

function padBatchRowWidth_(values) {
  // The Advanced Sheets Values API omits trailing empty cells from each row,
  // while the sequential Range.getValues() path always returns a full
  // rectangular matrix padded with ''. Left unpadded, a short batch row reads
  // as undefined past its length where the sequential row reads as '', and
  // analyticsHashValue_ types those two differently (null vs string) --
  // the same dataVersion-parity problem Fix 2 exists to close, just for
  // ordinary blank trailing cells instead of dates.
  if (!values || values.length < 2) return values;
  const headerLength = (values[0] || []).length;
  for (let rowIndex = 1; rowIndex < values.length; rowIndex++) {
    const row = values[rowIndex];
    if (!row) continue;
    while (row.length < headerLength) row.push('');
  }
  return values;
}

function dateFromSpreadsheetSerial_(serial) {
  const number = Number(serial);
  if (!Number.isFinite(number)) return null;
  const wallClockMillis = Math.round((number - 25569) * 86400000);
  const wallClockText = Utilities.formatDate(
    new Date(wallClockMillis),
    'UTC',
    'yyyy-MM-dd HH:mm:ss'
  );
  return Utilities.parseDate(
    wallClockText,
    CANN.TIME_ZONE,
    'yyyy-MM-dd HH:mm:ss'
  );
}

function normalizeBatchDateColumns_(sheetName, values) {
  const dateColumns = ANALYTICS_DATE_COLUMNS_[sheetName];
  if (!dateColumns || !values || values.length < 2) return values;
  const headers = headerMapFromRow_(values[0]);
  const indexes = [];
  dateColumns.forEach(name => {
    const index = headers[name];
    if (index !== undefined) indexes.push(index);
  });
  if (!indexes.length) return values;
  for (let rowIndex = 1; rowIndex < values.length; rowIndex++) {
    const row = values[rowIndex];
    if (!row) continue;
    indexes.forEach(index => {
      const cell = row[index];
      if (typeof cell === 'number' && Number.isFinite(cell) && cell > 0) {
        const converted = dateFromSpreadsheetSerial_(cell);
        if (converted) row[index] = converted;
      }
    });
  }
  return values;
}

function sheetsBatchUpdate_(ss, requests) {
  if (!requests.length) return { replies: [] };
  assertAdvancedSheetsService_();
  return Sheets.Spreadsheets.batchUpdate(
    { requests: requests },
    ss.getId()
  );
}

function sheetsBatchUpdateInChunks_(ss, requests, requestedChunkSize) {
  const chunkSize = Math.max(
    1,
    Math.floor(finiteNumberOr_(requestedChunkSize, 400))
  );
  for (let start = 0; start < requests.length; start += chunkSize) {
    sheetsBatchUpdate_(ss, requests.slice(start, start + chunkSize));
  }
}

/**
 * The Advanced Sheets write becomes durable before SpreadsheetApp refreshes
 * its in-process read cache. Journal and canonical rows have single-writer,
 * lock-protected positions. Google Forms can append compatibility rows outside
 * that lock, so reread only the newly appended identity-column tail through the
 * Advanced service, which observes the just-committed batch.
 */
function materializedRecoverableRowsAfterCore_(ss, details) {
  const eventIds = (details.eventIds || []).map(text_);
  const eventCount = Number(details.eventRowCount || 0);
  const eventFirstRow = Number(details.eventFirstRow);
  const journalRowNumber = Number(details.journalRowNumber);
  if (eventIds.length !== eventCount ||
      !Number.isInteger(journalRowNumber) ||
      journalRowNumber < 2 ||
      (eventCount &&
       (!Number.isInteger(eventFirstRow) || eventFirstRow < 2))) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: core response row count mismatch'
    );
  }
  const canonicalRows = Array.from(
    { length: eventCount },
    (_, index) => eventFirstRow + index
  );
  const compatibilityRowsByEventId =
    details.compatibilityExistingRow
      ? rowsByStableIds_(
        eventIds,
        [Number(details.compatibilityExistingRow)]
      )
      : resolveFreshCompatibilityRowsByIds_(
        ss,
        requiredSheet_(ss, CANN.SHEETS.RESPONSES),
        details.compatibilityEventColumn,
        Number(details.responseLastRowBefore) + 1,
        eventIds
      );
  return {
    journalRecord: {
      rowNumber: journalRowNumber,
      state: 'CORE_COMMITTED',
      coreCommittedAt: details.now,
      finalizationJson: JSON.stringify(details.plan),
      responseJson: details.response == null
        ? ''
        : JSON.stringify(details.response)
    },
    canonicalRowsByEventId: rowsByStableIds_(
      eventIds,
      canonicalRows
    ),
    compatibilityRowsByEventId: compatibilityRowsByEventId
  };
}

function resolveFreshCompatibilityRowsByIds_(
  ss,
  sheet,
  columnNumber,
  firstPossibleRow,
  ids
) {
  const submitted = Array.from(
    new Set((ids || []).map(text_).filter(Boolean))
  );
  if (!submitted.length) return {};
  const column = sheetColumnLetters_(columnNumber);
  const firstRow = Math.max(2, Math.floor(firstPossibleRow));
  const sheetName = "'" +
    sheet.getName().replace(/'/g, "''") +
    "'";
  const response = Sheets.Spreadsheets.Values.get(
    ss.getId(),
    sheetName + '!' + column + firstRow + ':' + column
  );
  const wanted = {};
  submitted.forEach(id => { wanted[id] = true; });
  const resolved = {};
  (response.values || []).forEach((row, index) => {
    const id = text_(row && row[0]);
    if (!wanted[id]) return;
    if (resolved[id]) {
      throw new Error(
        'RECOVERABLE_APPLY_CORRUPT: duplicate ' + id +
        ' in ' + sheet.getName()
      );
    }
    resolved[id] = firstRow + index;
  });
  submitted.forEach(id => {
    if (!resolved[id]) {
      throw new Error(
        'RECOVERABLE_APPLY_CORRUPT: missing fresh compatibility ' +
        id
      );
    }
  });
  return resolved;
}

function rowsByStableIds_(ids, rowNumbers) {
  if (ids.length !== rowNumbers.length) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: stable ID row count mismatch'
    );
  }
  const rows = {};
  ids.forEach((id, index) => {
    if (!id || rows[id]) {
      throw new Error(
        'RECOVERABLE_APPLY_CORRUPT: invalid materialized stable ID ' +
        id
      );
    }
    rows[id] = rowNumbers[index];
  });
  return rows;
}

function sheetColumnLetters_(columnNumber) {
  let value = Number(columnNumber);
  if (!Number.isInteger(value) || value < 1) {
    throw new Error(
      'RECOVERABLE_APPLY_CORRUPT: invalid sheet column ' +
      columnNumber
    );
  }
  let result = '';
  while (value > 0) {
    value--;
    result = String.fromCharCode(65 + value % 26) + result;
    value = Math.floor(value / 26);
  }
  return result;
}

function assertAdvancedSheetsService_() {
  if (typeof Sheets === 'undefined' ||
      !Sheets.Spreadsheets ||
      !Sheets.Spreadsheets.batchUpdate ||
      !Sheets.Spreadsheets.Values ||
      !Sheets.Spreadsheets.Values.get) {
    throw new Error(
      'CONFIGURATION_ERROR: Advanced Google Sheets service v4 is required'
    );
  }
}

function setSandboxSyncApplyFault(stage) {
  if (environment_() !== 'SANDBOX') {
    throw new Error('SANDBOX_FAULT_GUARD: ENVIRONMENT must be SANDBOX');
  }
  const allowed = Object.keys(CANN.SYNC_APPLY_FAULTS)
    .map(key => CANN.SYNC_APPLY_FAULTS[key]);
  const normalized = text_(stage).toUpperCase();
  if (allowed.indexOf(normalized) < 0) {
    throw new Error(
      'SANDBOX_FAULT_GUARD: unsupported stage ' + normalized
    );
  }
  PropertiesService.getScriptProperties().setProperty(
    CANN.SANDBOX_FAULT_PROPERTY,
    normalized
  );
  return { armed: normalized };
}

function clearSandboxSyncApplyFault() {
  if (environment_() !== 'SANDBOX') {
    throw new Error('SANDBOX_FAULT_GUARD: ENVIRONMENT must be SANDBOX');
  }
  PropertiesService.getScriptProperties().deleteProperty(
    CANN.SANDBOX_FAULT_PROPERTY
  );
  return { armed: null };
}

function armedSandboxSyncApplyFault_(stage) {
  if (environment_() !== 'SANDBOX') return false;
  const properties = PropertiesService.getScriptProperties();
  const armed = text_(
    properties.getProperty(CANN.SANDBOX_FAULT_PROPERTY)
  ).toUpperCase();
  if (armed !== stage) return false;
  properties.deleteProperty(CANN.SANDBOX_FAULT_PROPERTY);
  return true;
}

function maybeInjectSandboxSyncApplyBatchFault_(stage, requests) {
  if (!armedSandboxSyncApplyFault_(stage)) return;
  // A non-existent sheet makes the Advanced Sheets request invalid. Because it
  // is part of the same batch, every otherwise-valid request rolls back.
  requests.push({
    updateCells: {
      start: {
        sheetId: -2147483648,
        rowIndex: 0,
        columnIndex: 0
      },
      rows: [{ values: [{ userEnteredValue: { stringValue: 'FAULT' } }] }],
      fields: 'userEnteredValue'
    }
  });
}

function maybeInjectSandboxSyncApplyFault_(stage) {
  if (!armedSandboxSyncApplyFault_(stage)) return;
  throw new Error('SANDBOX_INJECTED_SYNC_APPLY_STOP: ' + stage);
}

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

function preflightSyncRequest_(payload, apiVersion) {
  const purchases = arrayOrEmpty_(payload && payload.purchases);
  const consumptions = arrayOrEmpty_(payload && payload.consumptions);
  const finishActions = arrayOrEmpty_(payload && payload.finishActions);
  const consumptionCorrections = arrayOrEmpty_(
    payload && payload.consumptionCorrections
  );
  if (apiVersion === 1 && Object.prototype.hasOwnProperty.call(payload || {}, 'finishActions')) {
    return {
      purchases: purchases,
      consumptions: consumptions,
      finishActions: finishActions,
      consumptionCorrections: consumptionCorrections,
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
        failure: itemError_('INVALID_ITEM', 'Duplicate UUID inside request')
      };
    }
  }
  return {
    purchases: purchases,
    consumptions: consumptions,
    finishActions: finishActions,
    consumptionCorrections: consumptionCorrections,
    failure: null
  };
}

function isRequestPayloadObject_(value) {
  return !!value && typeof value === 'object' && !Array.isArray(value);
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

// -----------------------------------------------------------------------------
// Reconciliation metrics
// -----------------------------------------------------------------------------

function responseMetrics_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const headers = headerMap_(sheet);
  const result = {};
  readDataRows_(sheet).forEach(row => {
    const id = text_(value_(row, headers, 'Product'));
    if (!id) return;
    const timestamp = value_(row, headers, 'Timestamp');
    const metrics = result[id] || (result[id] = { uses: 0, count: 0, earliest: '', latest: '' });
    metrics.uses += finiteNumberOr_(value_(row, headers, 'Uses'), 0);
    metrics.count++;
    if (timestamp && (!metrics.earliest || timestamp < metrics.earliest)) metrics.earliest = timestamp;
    if (timestamp && (!metrics.latest || timestamp > metrics.latest)) metrics.latest = timestamp;
  });
  return result;
}

function canonicalMetrics_(ss) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.EVENTS);
  const headers = headerMap_(sheet);
  const result = {};
  readDataRows_(sheet).forEach(row => {
    const id = text_(value_(row, headers, 'Legacy Product ID'));
    if (!id) return;
    const timestamp = value_(row, headers, 'Timestamp');
    const metrics = result[id] || (result[id] = { uses: 0, count: 0, earliest: '', latest: '' });
    metrics.uses += finiteNumberOr_(value_(row, headers, 'Uses'), 0);
    metrics.count++;
    if (timestamp && (!metrics.earliest || timestamp < metrics.earliest)) metrics.earliest = timestamp;
    if (timestamp && (!metrics.latest || timestamp > metrics.latest)) metrics.latest = timestamp;
  });
  return result;
}

function latestInteractions_(ss) {
  const result = {};
  const canonical = ss.getSheetByName(CANN.SHEETS.EVENTS);
  if (canonical && canonical.getLastRow() >= 2) {
    const headers = headerMap_(canonical);
    readDataRows_(canonical).forEach(row => {
      const id = text_(value_(row, headers, 'Legacy Product ID'));
      const timestamp = value_(row, headers, 'Timestamp');
      const uses = finiteNumber_(value_(row, headers, 'Uses'));
      if (!id || !timestamp || uses == null) return;
      const epoch = new Date(timestamp).getTime();
      if (!result[id] || epoch > result[id].lastLoggedAtEpochMillis) result[id] = { lastLoggedAtEpochMillis: epoch, lastQuantity: uses };
    });
    return result;
  }
  const responses = requiredSheet_(ss, CANN.SHEETS.RESPONSES);
  const headers = headerMap_(responses);
  readDataRows_(responses).forEach(row => {
    const id = text_(value_(row, headers, 'Product'));
    const timestamp = value_(row, headers, 'Timestamp');
    const uses = finiteNumber_(value_(row, headers, 'Uses'));
    if (!id || !timestamp || uses == null) return;
    const epoch = new Date(timestamp).getTime();
    if (!result[id] || epoch > result[id].lastLoggedAtEpochMillis) result[id] = { lastLoggedAtEpochMillis: epoch, lastQuantity: uses };
  });
  return result;
}

// -----------------------------------------------------------------------------
// General helpers
// -----------------------------------------------------------------------------

function newBackendTiming_(handler, startedAt) {
  const started = startedAt == null ? Date.now() : Number(startedAt);
  return { handler: handler, startedAt: started, phasesMs: {} };
}

function recordBackendPhase_(timing, phase, startedAt, endedAt) {
  if (!timing) return;
  const ended = endedAt == null ? Date.now() : Number(endedAt);
  const duration = Math.max(0, ended - Number(startedAt));
  timing.phasesMs[phase] = finiteNumberOr_(timing.phasesMs[phase], 0) + duration;
}

function backendTimingRecord_(timing, outcome, details, endedAt) {
  const ended = endedAt == null ? Date.now() : Number(endedAt);
  const total = Math.max(0, ended - timing.startedAt);
  const record = {
    recordType: 'cannsheet_backend_timing',
    handler: timing.handler,
    outcome: outcome,
    phasesMs: Object.assign({}, timing.phasesMs),
    serverDurationMs: timing.serverDurationMs == null ? total : timing.serverDurationMs,
    totalHandlerMs: total
  };
  Object.keys(details || {}).forEach(key => {
    if (details[key] !== undefined) record[key] = details[key];
  });
  return record;
}

function logBackendTiming_(timing, outcome, details) {
  if (!timing) return;
  try {
    console.log(JSON.stringify(backendTimingRecord_(timing, outcome, details)));
  } catch (ignored) {
    // Timing must never change request behavior if logging is unavailable.
  }
}

function addServerTimingFields_(response, timing, environment) {
  if (!response || !timing) return response;
  timing.serverDurationMs = Math.max(0, Date.now() - timing.startedAt);
  response.serverDurationMs = timing.serverDurationMs;
  if (environment === 'SANDBOX') {
    response.serverTimings = Object.assign({}, timing.phasesMs);
  }
  return response;
}

function timedRequestFailure_(timing, code, message, environment, details) {
  let output;
  if (details && details.apiVersion === CANN.API_VERSION) {
    const response = v2RequestFailure_(details.requestId, code, message);
    response.environment = environment;
    addServerTimingFields_(response, timing, environment);
    const responseRoutingStarted = Date.now();
    output = jsonOutput_(response);
    recordBackendPhase_(timing, 'responseRouting', responseRoutingStarted);
  } else {
    output = requestFailure_(code, message, environment, timing);
  }
  const timingDetails = Object.assign({ errorCode: code }, details || {});
  if (environment) timingDetails.environment = environment;
  logBackendTiming_(timing, 'rejected', timingDetails);
  return output;
}

function requiredSheet_(ss, name) {
  const sheet = ss.getSheetByName(name);
  if (!sheet) throw new Error('SCHEMA_MISMATCH: Missing sheet ' + name);
  return sheet;
}

function headerMap_(sheet) {
  const lastColumn = Math.max(1, sheet.getLastColumn());
  const headers = sheet.getRange(1, 1, 1, lastColumn).getValues()[0];
  const result = {};
  headers.forEach((header, index) => {
    const name = text_(header);
    if (name && result[name] === undefined) result[name] = index;
  });
  return result;
}

function requireHeaders_(headers, required) {
  const missing = required.filter(name => headers[name] === undefined);
  if (missing.length) throw new Error('SCHEMA_MISMATCH: Missing headers ' + missing.join(', '));
}

function readDataRows_(sheet) {
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return [];
  return sheet.getRange(2, 1, lastRow - 1, sheet.getLastColumn()).getValues()
    .filter(row => row.some(cell => cell !== '' && cell != null));
}

function value_(row, headers, name) {
  const index = headers[name];
  return index === undefined ? '' : row[index];
}

function recordMigrationIssue_(ss, type, sourceSheet, sourceRow, productId, detail) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.MIGRATION_REPORT);
  sheet.getRange(sheet.getLastRow() + 1, 1, 1, CANN.REPORT_HEADERS.length)
    .setValues([[type, sourceSheet, sourceRow, productId, detail, new Date()]]);
}

function configValue_(ss, key, fallback) {
  const sheet = ss.getSheetByName(CANN.SHEETS.CONFIG);
  if (!sheet || sheet.getLastRow() < 2) return fallback;
  const rows = sheet.getRange(2, 1, sheet.getLastRow() - 1, 2).getValues();
  const match = rows.find(row => text_(row[0]) === key);
  return match && match[1] !== '' ? match[1] : fallback;
}

function setConfigValue_(ss, key, value, description) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
  const headers = headerMap_(sheet);
  requireExactHeaders_(
    headers,
    ['Key', 'Value', 'Description'],
    CANN.SHEETS.CONFIG
  );
  const lastRow = sheet.getLastRow();
  const rows = lastRow < 2
    ? []
    : sheet.getRange(2, 1, lastRow - 1, 3).getValues();
  const matches = [];
  rows.forEach((row, index) => {
    if (text_(row[0]) === key) matches.push(index + 2);
  });
  if (matches.length > 1) {
    throw new Error('SCHEMA_MISMATCH: duplicate Config key ' + key);
  }
  const rowNumber = matches.length ? matches[0] : lastRow + 1;
  sheet.getRange(rowNumber, 1, 1, 3).setValues([[
    key,
    value,
    description || ''
  ]]);
}

function ensureConfigKey_(ss, key, defaultValue, description) {
  const sheet = requiredSheet_(ss, CANN.SHEETS.CONFIG);
  const lastRow = sheet.getLastRow();
  const rows = lastRow < 2 ? [] : sheet.getRange(2, 1, lastRow - 1, 1).getValues();
  const matches = [];
  rows.forEach((row, index) => {
    if (text_(row[0]) === key) matches.push(index + 2);
  });
  if (matches.length > 1) throw new Error('SCHEMA_MISMATCH: duplicate Config key ' + key);
  if (!matches.length) setConfigValue_(ss, key, defaultValue, description);
  return matches.length ? matches[0] : sheet.getLastRow();
}

function requiredScriptProperty_(name) {
  const value = text_(PropertiesService.getScriptProperties().getProperty(name));
  if (!value) throw new Error('CONFIGURATION_ERROR: missing Script Property ' + name);
  return value;
}

function environment_() {
  const value = requiredScriptProperty_('ENVIRONMENT');
  if (value !== 'PRODUCTION' && value !== 'SANDBOX') {
    throw new Error('CONFIGURATION_ERROR: ENVIRONMENT must be PRODUCTION or SANDBOX');
  }
  return value;
}

function spreadsheet_() {
  return SpreadsheetApp.openById(requiredScriptProperty_('SPREADSHEET_ID'));
}

function assertConfiguredSpreadsheet_(ss) {
  if (!ss || ss.getId() !== requiredScriptProperty_('SPREADSHEET_ID')) {
    throw new Error('CONFIGURATION_ERROR: spreadsheet does not match SPREADSHEET_ID');
  }
}

function assertConfigEnvironment_(ss) {
  assertConfiguredSpreadsheet_(ss);
  const marker = text_(configValue_(ss, 'ENVIRONMENT', ''));
  if (marker !== environment_()) throw new Error('CONFIGURATION_ERROR: Config ENVIRONMENT mismatch');
}

function validateRequestEnvironment_(serverEnvironment, requestEnvironment) {
  const supplied = text_(requestEnvironment);
  if (supplied && supplied !== serverEnvironment) return 'Client and server environments do not match';
  if (!supplied && serverEnvironment === 'SANDBOX') return 'Sandbox requests must include environment SANDBOX';
  return '';
}

function taxRate_(ss) { return finiteNumberOr_(configValue_(ss, 'TAX_RATE', 0.13), 0.13); }
function formatDate_(date) { return Utilities.formatDate(new Date(date), CANN.TIME_ZONE, 'yyyy-MM-dd'); }
function formatTime_(date) { return Utilities.formatDate(new Date(date), CANN.TIME_ZONE, 'HH:mm:ss'); }

function parseClientDateTime_(dateText, timeText) {
  return parseClientDateTimeStrict_(dateText, timeText) || new Date();
}

function parseClientDateTimeStrict_(dateText, timeText) {
  const date = text_(dateText);
  const suppliedTime = text_(timeText);
  const time = suppliedTime || '00:00:00';
  const format = time.length === 5
    ? 'yyyy-MM-dd HH:mm'
    : 'yyyy-MM-dd HH:mm:ss';
  const combined = date + ' ' + time;
  if (
    !isClientDate_(date) ||
    (suppliedTime && !isClientTime_(suppliedTime))
  ) {
    return null;
  }
  try {
    const parsed = Utilities.parseDate(combined, CANN.TIME_ZONE, format);
    if (
      !parsed ||
      isNaN(parsed.getTime()) ||
      Utilities.formatDate(parsed, CANN.TIME_ZONE, format) !== combined
    ) {
      return null;
    }
    return parsed;
  } catch (error) {
    return null;
  }
}

function isClientDate_(value) {
  const text = text_(value);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return false;
  const parsed = new Date(text + 'T00:00:00');
  return !isNaN(parsed.getTime()) &&
    parsed.getFullYear() === Number(text.slice(0, 4)) &&
    parsed.getMonth() + 1 === Number(text.slice(5, 7)) &&
    parsed.getDate() === Number(text.slice(8, 10));
}

function isClientTime_(value) {
  return /^([01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/.test(text_(value));
}

function deterministicLegacyEventUuid_(spreadsheetId, sheetName, rowNumber) {
  const bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, spreadsheetId + ':' + sheetName + ':' + rowNumber, Utilities.Charset.UTF_8);
  let hex = bytes.map(value => ('0' + ((value + 256) % 256).toString(16)).slice(-2)).join('').slice(0, 32);
  hex = hex.slice(0, 12) + '5' + hex.slice(13, 16) + '8' + hex.slice(17);
  return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
}

function requestFailure_(code, message, environment, timing) {
  const responseConstructionStarted = Date.now();
  const response = { success: false, message: message, errorCode: code, productIdMap: {}, environment: environment };
  recordBackendPhase_(timing, 'responseConstruction', responseConstructionStarted);
  addServerTimingFields_(response, timing, environment);
  const responseRoutingStarted = Date.now();
  const output = jsonOutput_(response);
  recordBackendPhase_(timing, 'responseRouting', responseRoutingStarted);
  return output;
}

function jsonOutput_(value) {
  return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON);
}

function conciseError_(error) { return error && error.message ? String(error.message).slice(0, 500) : String(error).slice(0, 500); }
function text_(value) { return value == null ? '' : String(value).trim(); }
function arrayOrEmpty_(value) { return value == null ? [] : value; }
function finiteNumber_(value) { const number = Number(value); return Number.isFinite(number) ? number : null; }
function optionalFiniteNumber_(value) {
  return value == null || value === '' ? null : finiteNumber_(value);
}
function isMissingPurchaseNumber_(value) { return value == null || text_(value) === ''; }
function isOptionalFinitePurchaseNumber_(value) {
  return isMissingPurchaseNumber_(value) || isFiniteNumber_(value);
}
function isRequiredFinitePurchaseNumber_(value) {
  return !isMissingPurchaseNumber_(value) && isFiniteNumber_(value);
}
function purchaseNumberOrBlank_(value) {
  return isMissingPurchaseNumber_(value) ? '' : finiteNumber_(value);
}
function finiteNumberOr_(value, fallback) { const number = finiteNumber_(value); return number == null ? fallback : number; }
function isFiniteNumber_(value) { return finiteNumber_(value) != null; }
function allowedStatusOr_(value, fallback) { const status = Number(value); return [0, 1, 2].indexOf(status) >= 0 ? status : fallback; }
function truthy_(value) { return value === true || value === 1 || text_(value).toLowerCase() === 'true' || text_(value).toLowerCase() === 'yes'; }
function dateOrNow_(value) { const date = new Date(value); return isNaN(date.getTime()) ? new Date() : date; }
function dateOrNull_(value) { const date = new Date(value); return isNaN(date.getTime()) ? null : date; }
function timestampMillisOrNull_(value) {
  if (value == null || value === '') return null;
  const millis = new Date(value).getTime();
  return Number.isFinite(millis) ? millis : null;
}
function isUuid_(value) { return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value); }
function firstDuplicate_(values) { const seen = {}; for (let i = 0; i < values.length; i++) { if (seen[values[i]]) return values[i]; seen[values[i]] = true; } return ''; }
