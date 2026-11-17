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
  const hasClientState = Object.prototype.hasOwnProperty.call(
    payload,
    'clientState'
  );
  const isClientStateOnly = hasClientState &&
    purchases.length === 0 &&
    consumptions.length === 0 &&
    finishActions.length === 0 &&
    consumptionCorrections.length === 0;

  refreshRecoverableSyncApplyStateLocked_(requestContext);
  const recoverableReady = recoverableSyncApplyReady_(requestContext.config);
  if (recoverableReady) {
    const repairStarted = Date.now();
    repairPendingSyncApplyLocked_(requestContext);
    recordBackendPhase_(timing, 'recoveryRepair', repairStarted);
  }

  phaseStarted = Date.now();
  const context = purchases.length || consumptions.length ||
      finishActions.length || consumptionCorrections.length || hasClientState
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
  staged.accepted.forEach(item => {
    resolver[item.legacyProductId] = item;
  });
  const clientStateByProductUuid = Object.assign({}, context.byProductUuid);
  staged.accepted.forEach(item => {
    if (item.productUuid) clientStateByProductUuid[item.productUuid] = item;
  });
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
      // Invalidate before the first write, not only after it: an execution that
      // dies mid-write would otherwise leave the sheet changed, the watermark
      // unchanged, and the pre-mutation analytics response served from cache
      // for up to CANN.CACHE_DEFAULT_TTL_SECONDS. Empty, rejected-only and
      // duplicate requests write nothing here, so they must not invalidate.
      if (hasCoreMutation) bumpMutationWatermark_();
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
    if (!isClientStateOnly) {
      const ledgerStarted = Date.now();
      upsertLedger_(ss, requestId, purchases.length, consumptions.length, allAccepted ? 'ACCEPTED' : 'PARTIAL', ledgerStarted - started, '', requestContext);
      recordBackendPhase_(timing, 'ledgerUpdate', ledgerStarted);
    }
  }
  if (hasClientState) {
    response.acknowledgedClientState = applyClientStateLocked_(
      ss,
      requestContext.clientState,
      resolver,
      clientStateByProductUuid
    );
  }
  if (!isClientStateOnly) bumpMutationWatermark_();
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

