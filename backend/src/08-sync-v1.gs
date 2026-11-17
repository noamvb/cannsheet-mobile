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
    bumpMutationWatermark_();
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

