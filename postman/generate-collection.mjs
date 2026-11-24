// Builds card-issuance.postman_collection.json. Run from the repo root:  node postman/generate-collection.mjs
// Edit this file rather than the generated JSON, so the shared polling and request helpers stay in one place.
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const CARD = { cardType: 'VIRTUAL', productCode: 'VIRTUAL_STANDARD', customerRef: 'customer-1', accountRef: 'account-1', spendLimitMinor: 50000 };
const REISSUE = { reason: 'STOLEN', productCode: 'VIRTUAL_STANDARD', customerRef: 'customer-1', accountRef: 'account-1', spendLimitMinor: 50000 };

const JSON_HEADER = { key: 'Content-Type', value: 'application/json' };
const tenantHeader = (tenant = '{{tenant}}') => ({ key: 'X-Tenant-Id', value: tenant });
const keyHeader = (key) => ({ key: 'Idempotency-Key', value: key });
const key = (id) => `{{run}}-${id}`;

const lines = (text) => text.trim().split('\n');

function item(name, method, url, { headers = [], body, pre = '', test = '', description } = {}) {
  const request = { method, header: headers, url };
  if (body !== undefined) {
    request.body = { mode: 'raw', raw: JSON.stringify(body, null, 2), options: { raw: { language: 'json' } } };
  }
  if (description) request.description = description;
  const event = [];
  if (pre) event.push({ listen: 'prerequest', script: { type: 'text/javascript', exec: lines(pre) } });
  if (test) event.push({ listen: 'test', script: { type: 'text/javascript', exec: lines(test) } });
  return { name, event, request };
}

const folder = (name, description, items) => ({ name, description, item: items });

const status = (code) => `pm.test('HTTP ${code}', () => pm.response.to.have.status(${code}));`;
const errorCode = (code) => `pm.test('code is ${code}', () => pm.expect(pm.response.json().code).to.eql('${code}'));`;

// ---------- request builders ----------

const resetMocks = (name) => item(name, 'POST', '{{mockUrl}}/mock-admin/reset', { test: status(204) });

const setFailure = (name, path, rule) => item(name, 'PUT', `{{mockUrl}}/mock-admin/failures/${path}`, {
  headers: [JSON_HEADER], body: rule, test: status(204),
});

const setCallbacks = (name, mode, delayMs) => item(name, 'PUT', '{{mockUrl}}/mock-admin/callbacks/{{tenant}}', {
  headers: [JSON_HEADER], body: delayMs ? { mode, delayMs } : { mode }, test: status(204),
});

const createCard = (name, id, { changes = {}, tenant = '{{tenant}}', expect = 201, extra = '' } = {}) =>
  item(name, 'POST', '{{baseUrl}}/v1/card-requests', {
    headers: [JSON_HEADER, tenantHeader(tenant), keyHeader(key(id))],
    body: { ...CARD, ...changes },
    test: `${status(expect)}\n${extra}`,
  });

/**
 * Repeats a GET once a second until `ready` holds, then runs the assertions. Repeating uses
 * setNextRequest, which only works in the Collection Runner. With a single Send it checks once.
 */
function poll(name, url, ready, assertions, { tenant = '{{tenant}}', maxTries = 45, description } = {}) {
  const pre = `
if (Number(pm.collectionVariables.get('tries') || 0) > 0) {
  setTimeout(() => {}, 1000);
}`;
  const test = `
const next = (n) => (pm.execution && pm.execution.setNextRequest) ? pm.execution.setNextRequest(n) : postman.setNextRequest(n);
const code = pm.response.code;
let b = {};
try { b = pm.response.json(); } catch (e) { }
const step = (n) => (b.steps || []).find((s) => s.name === n) || {};
const tries = Number(pm.collectionVariables.get('tries') || 0);
const ready = ${ready};
if (!ready && tries < ${maxTries}) {
  pm.collectionVariables.set('tries', tries + 1);
  next(pm.info.requestName);
} else {
  pm.collectionVariables.set('tries', 0);
  pm.test('reached the expected state within ${maxTries} seconds', () => pm.expect(ready).to.be.true);
${assertions.trim().split('\n').map((l) => '  ' + l).join('\n')}
}`;
  return item(name, 'GET', url, { headers: [tenantHeader(tenant)], pre, test, description });
}

const FINISHED = `code === 200 && b.finished === true`;
const PARKED = `code === 200 && b.pendingReview != null`;

const progress = (name, id, ready, assertions, options = {}) =>
  poll(name, `{{baseUrl}}/v1/card-requests/${key(id)}`, ready, assertions, options);

const expectFinal = (status, reason) => `
pm.test('status is ${status}', () => pm.expect(b.status).to.eql('${status}'));
${reason === undefined ? '' : `pm.test('reasonCode is ${reason}', () => pm.expect(b.reasonCode).to.eql(${reason === null ? 'null' : `'${reason}'`}));`}`;

const expectParked = (kind) => `
pm.test('status is UNDER_REVIEW', () => pm.expect(b.status).to.eql('UNDER_REVIEW'));
pm.test('pending review is ${kind}', () => pm.expect(b.pendingReview.kind).to.eql('${kind}'));`;

const review = (name, id, action, expect, extra = '', updateKey) => item(name, 'POST', `{{baseUrl}}/v1/card-requests/${key(id)}/approvals`, {
  headers: updateKey ? [JSON_HEADER, tenantHeader(), keyHeader(key(updateKey))] : [JSON_HEADER, tenantHeader()], body: { action, reviewer: 'ops-1' }, test: `${status(expect)}\n${extra}`,
});

const cancel = (name, id, expect, extra = '') => item(name, 'POST', `{{baseUrl}}/v1/card-requests/${key(id)}/cancel`, {
  headers: [JSON_HEADER, tenantHeader()], body: { reason: 'customer-request' }, test: `${status(expect)}\n${extra}`,
});

const mockCards = (name, assertions) => item(name, 'GET', '{{mockUrl}}/mock-admin/cards/{{tenant}}', {
  test: `${status(200)}\nconst cards = pm.response.json();\n${assertions}`,
});

const mockCounters = (name, assertions) => item(name, 'GET', '{{mockUrl}}/mock-admin/counters/{{tenant}}', {
  test: `${status(200)}\nconst n = pm.response.json();\n${assertions}`,
});

const orphan = (name, saveAs) => item(name, 'POST', '{{mockUrl}}/mock-admin/cards/{{tenant}}/orphan', {
  test: `${status(201)}\n${saveAs ? `pm.collectionVariables.set('${saveAs}', pm.response.json().cardId);` : ''}`,
});

// ---------- collection ----------

const setup = folder('0 Setup', 'Starts a new run. Every request key includes the run ID, so the collection can be run repeatedly.', [
  item('Start run and reset mocks', 'POST', '{{mockUrl}}/mock-admin/reset', {
    pre: `pm.collectionVariables.set('run', 'pm' + Date.now().toString(36));\npm.collectionVariables.set('tries', 0);`,
    test: `${status(204)}\nconsole.log('Run ID: ' + pm.collectionVariables.get('run'));`,
  }),
]);

const milestone1 = folder('1 Milestone 1', 'The sample workflow.', [
  item('M1 Hello workflow', 'POST', '{{baseUrl}}/v1/hello', {
    headers: [JSON_HEADER], body: { requestId: key('hello'), name: 'Ada' },
    test: `${status(200)}\npm.test('greeting', () => pm.expect(pm.response.json().greeting).to.eql('Hello, Ada!'));`,
  }),
]);

const milestone2 = folder('2 Milestone 2', 'Virtual card issuance, retries, idempotency and compensation.', [
  createCard('tc01 Create card', 'tc01', { extra: `pm.test('status REQUESTED', () => pm.expect(pm.response.json().status).to.eql('REQUESTED'));` }),
  progress('tc01 Progress', 'tc01', FINISHED, `
${expectFinal('ACTIVE', null)}
pm.test('all 7 steps DONE', () => pm.expect((b.steps || []).filter((s) => s.state === 'DONE').length).to.eql(7));
pm.test('card has ID and token', () => { pm.expect(b.card.cardId).to.be.a('string'); pm.expect(b.card.token).to.be.a('string'); });
pm.collectionVariables.set('tc01CardId', b.card ? b.card.cardId : '');`),
  mockCards('tc01 Mock card is active with limit', `
const card = cards.find((c) => c.cardId === pm.collectionVariables.get('tc01CardId'));
pm.test('card ACTIVE', () => pm.expect(card.status).to.eql('ACTIVE'));
pm.test('limit 50000', () => pm.expect(card.limitMinor).to.eql(50000));`),
  createCard('tc02 Duplicate request returns existing', 'tc01', { expect: 200, extra: `pm.test('existing request', () => pm.expect(pm.response.json().status).to.eql('ACTIVE'));` }),
  item('tc03 Other tenant gets 404', 'GET', `{{baseUrl}}/v1/card-requests/${key('tc01')}`, {
    headers: [tenantHeader('{{otherTenant}}')], test: `${status(404)}\n${errorCode('NOT_FOUND')}`,
  }),
  createCard('tc04 Card number as input is refused', 'tc04', { changes: { customerRef: '4111111111111111' }, expect: 400, extra: errorCode('INVALID_REQUEST') }),
  createCard('tc05 Create with unknown product', 'tc05', { changes: { productCode: 'GOLD' } }),
  progress('tc05 Progress', 'tc05', FINISHED, expectFinal('REJECTED', 'INVALID_PRODUCT')),
  createCard('tc06 Create with limit above policy', 'tc06', { changes: { spendLimitMinor: 2000000 } }),
  progress('tc06 Progress', 'tc06', FINISHED, expectFinal('REJECTED', 'LIMIT_EXCEEDS_POLICY')),

  resetMocks('tc07 Reset mocks'),
  createCard('tc07 Create, KYC rejects', 'tc07', { changes: { customerRef: 'reject-1' } }),
  progress('tc07 Progress', 'tc07', FINISHED, `${expectFinal('REJECTED', 'KYC_REJECTED')}\npm.test('createCard never ran', () => pm.expect(step('createCard').state).to.eql('PENDING'));`),
  mockCards('tc07 No card created', `pm.test('no cards', () => pm.expect(cards.length).to.eql(0));`),

  resetMocks('tc09 Reset mocks'),
  setFailure('tc09 Processor fails the next 2 calls', '{{tenant}}/processor/createCard', { mode: 'SERVER_ERROR', times: 2 }),
  createCard('tc09 Create card', 'tc09'),
  progress('tc09 Progress', 'tc09', FINISHED, expectFinal('ACTIVE', null)),
  mockCounters('tc09 Three calls, one card', `
pm.test('3 createCard calls', () => pm.expect(n.calls['processor/createCard']).to.eql(3));
pm.test('1 side effect', () => pm.expect(n.sideEffects['processor/createCard']).to.eql(1));`),

  resetMocks('tc10 Reset mocks'),
  setFailure('tc10 Processor drops the next response', '{{tenant}}/processor/createCard', { mode: 'DROP_RESPONSE', times: 1 }),
  createCard('tc10 Create card', 'tc10'),
  progress('tc10 Progress', 'tc10', FINISHED, expectFinal('ACTIVE', null)),
  mockCounters('tc10 Retried, still one card', `
pm.test('at least 2 createCard calls', () => pm.expect(n.calls['processor/createCard']).to.be.at.least(2));
pm.test('1 side effect', () => pm.expect(n.sideEffects['processor/createCard']).to.eql(1));`),

  resetMocks('tc11 Reset mocks'),
  setFailure('tc11 Processor declines activation', '{{tenant}}/processor/activateCard', { mode: 'BUSINESS_ERROR', times: -1, code: 'ACTIVATION_DECLINED' }),
  createCard('tc11 Create card', 'tc11'),
  progress('tc11 Progress', 'tc11', FINISHED, `
${expectFinal('REJECTED', 'ACTIVATION_DECLINED')}
pm.test('activateCard FAILED', () => pm.expect(step('activateCard').state).to.eql('FAILED'));
['deactivateCard', 'resetLimits', 'cancelCard'].forEach((s) => pm.test(s + ' COMPENSATED', () => pm.expect(step(s).state).to.eql('COMPENSATED')));
const order = (b.steps || []).filter((s) => s.state === 'COMPENSATED').map((s) => s.name).join(',');
pm.test('compensated in reverse order', () => pm.expect(order).to.eql('deactivateCard,resetLimits,cancelCard'));`),
  mockCards('tc11 Card cancelled and limit reset', `
pm.test('card CANCELLED', () => pm.expect(cards[0].status).to.eql('CANCELLED'));
pm.test('limit 0', () => pm.expect(cards[0].limitMinor).to.eql(0));`),

  resetMocks('tc12 Reset mocks'),
  setFailure('tc12 Notification service offline', '{{tenant}}/notification/send', { mode: 'OFFLINE', times: -1 }),
  createCard('tc12 Create card', 'tc12'),
  progress('tc12 Progress', 'tc12', FINISHED, `
${expectFinal('ACTIVE', null)}
pm.test('sendNotification FAILED', () => pm.expect(step('sendNotification').state).to.eql('FAILED'));
pm.test('nothing compensated', () => pm.expect((b.steps || []).filter((s) => s.state === 'COMPENSATED').length).to.eql(0));`),
]);

const milestone3 = folder('3 Milestone 3', 'Signals, timers, updates, parking, cancellation, reissue, reconciliation and schedules. The confirmation timer is 10 seconds.', [
  resetMocks('M3-02 Reset mocks'),
  setCallbacks('M3-02 Processor callbacks arrive late', 'DELAY', 15000),
  createCard('M3-02 Create card', 'm3-02'),
  progress('M3-02 Parked after the timer fires', 'm3-02', PARKED, expectParked('CONFIRMATION_TIMEOUT')),
  progress('M3-02 Late callback clears it', 'm3-02', FINISHED, expectFinal('ACTIVE', null)),

  resetMocks('M3-03 Reset mocks'),
  setCallbacks('M3-03 Processor never calls back', 'NEVER'),
  createCard('M3-03 Create card', 'm3-03'),
  progress('M3-03 Parked', 'm3-03', PARKED, expectParked('CONFIRMATION_TIMEOUT')),
  review('M3-03 Wrong action is refused', 'm3-03', 'RETRY', 409, errorCode('ACTION_NOT_ALLOWED')),
  review('M3-03 Operator approves', 'm3-03', 'APPROVE', 200, `pm.test('approved', () => pm.expect(pm.response.json().action).to.eql('APPROVE'));`, 'm3-03-decision'),
  review('M3-03 Retried approval returns the same result', 'm3-03', 'APPROVE', 200, `pm.test('same result', () => pm.expect(pm.response.json().action).to.eql('APPROVE'));`, 'm3-03-decision'),
  progress('M3-03 Progress', 'm3-03', FINISHED, expectFinal('ACTIVE', null)),

  resetMocks('M3-04 Reset mocks'),
  setCallbacks('M3-04 Processor never calls back', 'NEVER'),
  createCard('M3-04 Create card', 'm3-04'),
  progress('M3-04 Parked', 'm3-04', PARKED, expectParked('CONFIRMATION_TIMEOUT')),
  review('M3-04 Operator rejects', 'm3-04', 'REJECT', 200),
  progress('M3-04 Progress', 'm3-04', FINISHED, `${expectFinal('REJECTED', 'REJECTED_BY_REVIEWER')}\npm.test('cancelCard COMPENSATED', () => pm.expect(step('cancelCard').state).to.eql('COMPENSATED'));`),

  resetMocks('M3-05 Reset mocks'),
  createCard('M3-05 Create, KYC needs review', 'm3-05', { changes: { customerRef: 'review-2' } }),
  progress('M3-05 Parked for KYC review', 'm3-05', PARKED, expectParked('KYC_REVIEW')),
  review('M3-05 Reviewer approves', 'm3-05', 'APPROVE', 200),
  progress('M3-05 Progress', 'm3-05', FINISHED, expectFinal('ACTIVE', null)),

  resetMocks('M3-06 Reset mocks'),
  setFailure('M3-06 Processor offline for bank A', '{{tenant}}/processor/createCard', { mode: 'OFFLINE', times: -1 }),
  createCard('M3-06 Create card (bank A)', 'm3-06'),
  createCard('M3-07 Create card (bank A)', 'm3-07'),
  createCard('M3-06 Create card (bank B)', 'm3-06b', { tenant: '{{otherTenant}}' }),
  poll('M3-06 Bank B unaffected', `{{baseUrl}}/v1/card-requests/${key('m3-06b')}`, FINISHED, expectFinal('ACTIVE', null), { tenant: '{{otherTenant}}' }),
  progress('M3-06 Bank A parked after retries run out (about 30 s)', 'm3-06', PARKED, `${expectParked('RETRIES_EXHAUSTED')}\npm.test('parked at createCard', () => pm.expect(b.pendingReview.step).to.eql('createCard'));`, { maxTries: 75 }),
  setFailure('M3-06 Processor back online', '{{tenant}}/processor/createCard', { mode: 'OFFLINE', times: 0 }),
  review('M3-06 Operator retries', 'm3-06', 'RETRY', 200),
  progress('M3-06 Progress', 'm3-06', FINISHED, expectFinal('ACTIVE', null)),
  progress('M3-07 Parked', 'm3-07', PARKED, expectParked('RETRIES_EXHAUSTED'), { maxTries: 75 }),
  review('M3-07 Operator cancels', 'm3-07', 'CANCEL', 200),
  progress('M3-07 Progress', 'm3-07', FINISHED, `${expectFinal('CANCELLED', 'CANCELLED_BY_OPERATOR')}\npm.test('cancelCard COMPENSATED', () => pm.expect(step('cancelCard').state).to.eql('COMPENSATED'));`),

  resetMocks('M3-08 Reset mocks'),
  setCallbacks('M3-08 Processor never calls back', 'NEVER'),
  createCard('M3-08 Create card', 'm3-08'),
  progress('M3-08 Waiting for callback', 'm3-08', `code === 200 && step('awaitConfirmation').state === 'WAITING'`,
    `pm.test('awaitConfirmation WAITING', () => pm.expect(step('awaitConfirmation').state).to.eql('WAITING'));`),
  cancel('M3-08 Cancel', 'm3-08', 200, `pm.test('accepted', () => pm.expect(pm.response.json().accepted).to.be.true);`),
  progress('M3-08 Progress', 'm3-08', FINISHED, expectFinal('CANCELLED', 'CANCELLED_BY_REQUEST')),
  mockCards('M3-08 Card cancelled', `pm.test('card CANCELLED', () => pm.expect(cards[0].status).to.eql('CANCELLED'));`),

  item('M3-09 Card number in a cancel reason is refused', 'POST', `{{baseUrl}}/v1/card-requests/${key('tc01')}/cancel`, {
    headers: [JSON_HEADER, tenantHeader()], body: { reason: 'card 4111 1111 1111 1111 lost' }, test: `${status(400)}
${errorCode('INVALID_REQUEST')}`,
  }),
  cancel('M3-09 Cancel a finished card is refused', 'tc01', 409, errorCode('ALREADY_FINISHED')),
  review('M3-09 Review a finished card is refused', 'tc01', 'APPROVE', 409, errorCode('ALREADY_FINISHED')),

  resetMocks('M3-10 Reset mocks'),
  createCard('M3-10 Create the card to replace', 'm3-10'),
  progress('M3-10 Card active', 'm3-10', FINISHED, `${expectFinal('ACTIVE', null)}\npm.collectionVariables.set('oldCardId', b.card ? b.card.cardId : '');`),
  item('M3-10 Reissue as stolen', 'POST', '{{baseUrl}}/v1/cards/{{oldCardId}}/reissue', {
    headers: [JSON_HEADER, tenantHeader(), keyHeader(key('m3-10r'))], body: REISSUE,
    test: `${status(201)}\npm.test('replacement request named', () => pm.expect(pm.response.json().newCardRequestId).to.eql(pm.collectionVariables.get('run') + '-m3-10r-new'));`,
  }),
  poll('M3-10 Reissue progress', `{{baseUrl}}/v1/reissues/${key('m3-10r')}`, FINISHED, `
${expectFinal('ACTIVE', null)}
['blockOldCard', 'issueNewCard', 'linkCards'].forEach((s) => pm.test(s + ' DONE', () => pm.expect(step(s).state).to.eql('DONE')));
pm.collectionVariables.set('newCardId', b.card ? b.card.cardId : '');`),
  mockCards('M3-10 Old card blocked and linked', `
const oldCard = cards.find((c) => c.cardId === pm.collectionVariables.get('oldCardId'));
const newCard = cards.find((c) => c.cardId === pm.collectionVariables.get('newCardId'));
pm.test('old card BLOCKED', () => pm.expect(oldCard.status).to.eql('BLOCKED'));
pm.test('old card linked to new', () => pm.expect(oldCard.replacedBy).to.eql(pm.collectionVariables.get('newCardId')));
pm.test('new card ACTIVE', () => pm.expect(newCard.status).to.eql('ACTIVE'));`),

  item('M3-11 Reissue an unknown card', 'POST', '{{baseUrl}}/v1/cards/card_NOSUCHCARD/reissue', {
    headers: [JSON_HEADER, tenantHeader(), keyHeader(key('m3-11'))], body: { ...REISSUE, reason: 'LOST' }, test: status(201),
  }),
  poll('M3-11 Reissue progress', `{{baseUrl}}/v1/reissues/${key('m3-11')}`, FINISHED, expectFinal('REJECTED', 'CARD_NOT_FOUND')),

  resetMocks('M3-12 Reset mocks'),
  orphan('M3-12 Processor card the platform never issued', 'orphanCardId'),
  item('M3-12 Start reconciliation', 'POST', '{{baseUrl}}/v1/reconciliations', {
    headers: [JSON_HEADER, tenantHeader(), keyHeader(key('m3-12'))], body: { pageSize: 50 }, test: status(201),
  }),
  poll('M3-12 Reconciliation report', `{{baseUrl}}/v1/reconciliations/${key('m3-12')}`, `code === 200 && b.status !== 'RUNNING'`, `
pm.test('COMPLETED', () => pm.expect(b.status).to.eql('COMPLETED'));
const found = (b.report.discrepancies || []).find((d) => d.cardId === pm.collectionVariables.get('orphanCardId'));
pm.test('orphan reported as UNKNOWN_TO_PLATFORM', () => pm.expect(found && found.type).to.eql('UNKNOWN_TO_PLATFORM'));
pm.test('processor has 1 card', () => pm.expect(b.report.processorCards).to.eql(1));`),

  item('M3-14 Trigger the nightly schedule', 'POST', '{{baseUrl}}/v1/reconciliations/schedule/trigger', {
    headers: [tenantHeader()], test: `${status(202)}\npm.test('triggered', () => pm.expect(pm.response.json().result).to.eql('TRIGGERED'));`,
  }),
]);

const manual = folder('9 Manual (deselect in the Runner)',
  'These need you to stop and restart the worker, so run them one at a time with Send. Each request description says what to do.', [
  resetMocks('Manual Reset mocks'),
  createCard('tc15 Create card while the worker is stopped', 'tc15'),
  item('tc15 Progress', 'GET', `{{baseUrl}}/v1/card-requests/${key('tc15')}`, {
    headers: [tenantHeader()],
    description: 'Stop the worker (Ctrl+C) before "Create card". Send this: expect 503 PROGRESS_UNAVAILABLE. Restart the worker, wait a few seconds, send again: expect ACTIVE.',
    test: `pm.test('503 while the worker is down, or ACTIVE after restart', () => pm.expect([503, 200]).to.include(pm.response.code));`,
  }),
  setFailure('M3-13 Slow processor listing (1.5 s per page)', '{{tenant}}/processor/listCards', { mode: 'LATENCY', times: -1, latencyMs: 1500 }),
  orphan('M3-13 Orphan card 1'), orphan('M3-13 Orphan card 2'), orphan('M3-13 Orphan card 3'),
  orphan('M3-13 Orphan card 4'), orphan('M3-13 Orphan card 5'), orphan('M3-13 Orphan card 6'),
  item('M3-13 Start reconciliation, one card per page', 'POST', '{{baseUrl}}/v1/reconciliations', {
    headers: [JSON_HEADER, tenantHeader(), keyHeader(key('m3-13'))], body: { pageSize: 1 },
    description: 'About 5 seconds after sending, stop the worker (Ctrl+C), then start it again.',
    test: status(201),
  }),
  item('M3-13 Reconciliation report', 'GET', `{{baseUrl}}/v1/reconciliations/${key('m3-13')}`, {
    headers: [tenantHeader()],
    description: 'Send until COMPLETED. Then in the Web UI, open reconcile-bank-a-<run>-m3-13: the Reconcile activity completed on attempt 2.',
    test: `${status(200)}\nconst b = pm.response.json();\npm.test('COMPLETED with 6 processor cards', () => { pm.expect(b.status).to.eql('COMPLETED'); pm.expect(b.report.processorCards).to.eql(6); });`,
  }),
  mockCounters('M3-13 Fewer than 12 page reads', `pm.test('resumed, not restarted', () => pm.expect(n.calls['processor/listCards']).to.be.below(12));`),
]);

const collection = {
  info: {
    name: 'Temporal Card Issuance',
    description: 'Milestones 1 to 3. Start Temporal, mocks, worker and API, then run this collection in the Collection Runner, deselecting folder 9. Polling requests repeat once a second until the workflow is ready. A full run takes about 2 minutes.',
    schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
  },
  variable: [
    { key: 'baseUrl', value: 'http://localhost:8080' },
    { key: 'mockUrl', value: 'http://localhost:8091' },
    { key: 'tenant', value: 'bank-a' },
    { key: 'otherTenant', value: 'bank-b' },
    { key: 'run', value: '' },
    { key: 'tries', value: '0' },
  ],
  item: [setup, milestone1, milestone2, milestone3, manual],
};

const out = join(dirname(fileURLToPath(import.meta.url)), 'card-issuance.postman_collection.json');
writeFileSync(out, JSON.stringify(collection, null, 2) + '\n');
const count = collection.item.reduce((sum, f) => sum + f.item.length, 0);
console.log(`Wrote ${out} with ${count} requests`);
