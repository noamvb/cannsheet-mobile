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

