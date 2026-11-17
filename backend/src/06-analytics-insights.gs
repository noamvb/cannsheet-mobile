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

