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

