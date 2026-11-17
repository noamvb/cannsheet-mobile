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
          thc: finiteNumberOr_(value_(row, headers, 'THC%'), 0),
          grams: finiteNumberOr_(value_(row, headers, 'Grams'), 0),
          status: allowedStatusOr_(value_(row, headers, 'Finished'), CANN.STATUS.ACTIVE),
          totalUses: analyticsRounded_(totalUses)
        };
        // A blank or unrecognised Post-tax cell means the basis was never recorded,
        // which is not the same as pre-tax. Leave the field absent so the client
        // reads it as unknown and says so, rather than silently assuming pre-tax
        // and letting tax be added to an amount that already included it. This is
        // the same distinction analyticsCost_ makes when it refuses a final cost.
        const productPostTax = strictPostTax_(value_(row, headers, 'Post-tax'));
        if (productPostTax.known) product.postTax = productPostTax.value;
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

