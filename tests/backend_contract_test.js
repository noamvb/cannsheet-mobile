const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

let uuidCounter = 0;
const context = {
  console,
  Utilities: {
    getUuid() {
      uuidCounter += 1;
      return `00000000-0000-4000-8000-${String(uuidCounter).padStart(12, '0')}`;
    },
    DigestAlgorithm: { SHA_256: 'SHA_256' },
    Charset: { UTF_8: 'UTF_8' },
    computeDigest(_algorithm, value) {
      return Array.from(require('node:crypto').createHash('sha256').update(value).digest());
    },
  },
};

vm.createContext(context);
const source = fs.readFileSync('backend_additions.gs', 'utf8');
vm.runInContext(
  source + `\nthis.testApi = {
    deterministicLegacyEventUuid_, firstDuplicate_, isUuid_,
    validateV2Consumption_, stagePurchases_, validateRequestEnvironment_
  };`,
  context,
);

const api = context.testApi;

const legacyEventId = api.deterministicLegacyEventUuid_('sheet-id', 'Form Responses 1', 42);
assert.match(legacyEventId, /^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-8[0-9a-f]{3}-[0-9a-f]{12}$/);
assert.equal(legacyEventId, api.deterministicLegacyEventUuid_('sheet-id', 'Form Responses 1', 42));
assert.notEqual(legacyEventId, api.deterministicLegacyEventUuid_('sheet-id', 'Form Responses 1', 43));

assert.equal(api.firstDuplicate_(['a', 'b', 'a']), 'a');
assert.equal(api.firstDuplicate_(['a', 'b']), '');
assert.equal(api.isUuid_('b0f88bb2-5d82-46e1-92a7-0188e95ba3a6'), true);
assert.equal(api.isUuid_('temp_12345678'), false);

const invalidConsumption = api.validateV2Consumption_({
  eventId: 'not-a-uuid',
  productId: '*P1',
  uses: 1,
}, 0);
assert.equal(invalidConsumption.code, 'INVALID_ITEM');

const existing = {
  byLegacyId: {
    '*P1': { legacyProductId: '*P1' },
    '*P2': { legacyProductId: '*P2' },
    '*P1B': { legacyProductId: '*P1B' },
  },
};
const staged = api.stagePurchases_([
  { item: { type: 'P', borrowed: 0 }, actionId: 'a1', tempId: 't1' },
  { item: { type: 'P', borrowed: 0 }, actionId: 'a2', tempId: 't2' },
  { item: { type: 'P', borrowed: 1 }, actionId: 'a3', tempId: 't3' },
], existing);
assert.deepEqual(
  Array.from(staged.accepted, item => item.legacyProductId),
  ['*P3', '*P4', '*P2B'],
);
assert.equal(staged.rejected.length, 0);

assert.equal(api.validateRequestEnvironment_('PRODUCTION', 'PRODUCTION'), '');
assert.equal(api.validateRequestEnvironment_('PRODUCTION', ''), '');
assert.equal(api.validateRequestEnvironment_('SANDBOX', 'SANDBOX'), '');
assert.match(api.validateRequestEnvironment_('SANDBOX', ''), /must include/);
assert.match(api.validateRequestEnvironment_('PRODUCTION', 'SANDBOX'), /do not match/);
assert.match(api.validateRequestEnvironment_('SANDBOX', 'PRODUCTION'), /do not match/);

console.log('backend contract tests passed');
