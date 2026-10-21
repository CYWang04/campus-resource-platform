import assert from 'node:assert/strict';
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';

// Local-only demo credentials are read without writing them to output.
const env = Object.fromEntries(fs.readFileSync(new URL('../.env', import.meta.url), 'utf8').trim().split(/\r?\n/).map(line => {
  const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)];
}));
const base = process.env.BASE_URL ?? 'http://127.0.0.1:8080';
let checks = 0;
async function request(path, token, method = 'GET', body, key, expected = 200) {
  const response = await fetch(base + '/api/v1' + path, {
    method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}), ...(key ? { 'Idempotency-Key': key } : {}) },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(15000),
  });
  const result = await response.json();
  assert.equal(response.status, expected, `${method} ${path}: ${result.code ?? response.status}`);
  assert.ok(result.requestId); checks++;
  return result.data;
}
async function login(username) { return (await request('/auth/login', null, 'POST', { username, password: env.DEMO_PASSWORD })).token; }
async function waitState(id, token, expected) {
  for (let i = 0; i < 40; i++) {
    const order = await request('/work-orders/' + id, token);
    if (order.state === expected) return order;
    assert.ok(!order.operations.some(op => op.status === 'FAILED'), 'Resource synchronization failed');
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  throw new Error('Timed out waiting for ' + expected);
}
await request('/rooms', null, 'GET', undefined, undefined, 401);
await request('/auth/login', null, 'POST', { username: 'student', password: 'wrong' }, undefined, 401);
const student = await login('student'), technician = await login('technician'), manager = await login('manager');
for (const service of ['m1', 'm2', 'm3']) {
  const version = await request('/system/' + service + '/version', student);
  assert.equal(version.service, service); assert.equal(version.version, '0.1.0-SNAPSHOT'); checks += 2;
}
const room = (await request('/rooms', student)).items[0];
assert.ok(room); const roomId = room.id;
assert.ok((await request('/rooms/' + roomId + '/seats', student)).length > 0);
await request('/admin/audit-events', student, 'GET', undefined, undefined, 403);
const report = { roomId, title: '自动验收 ' + randomUUID().slice(0, 8), description: '用于验证维修来源隔离' };
const createKey = randomUUID();
const first = await request('/work-orders', student, 'POST', report, createKey);
const duplicate = await request('/work-orders', student, 'POST', report, createKey);
assert.equal(first.id, duplicate.id); checks++;
await request('/work-orders', student, 'POST', { ...report, title: '不同内容' }, createKey, 409);
await request('/work-orders/' + first.id + '/start', student, 'POST', {}, randomUUID(), 403);
const second = await request('/work-orders', student, 'POST', { ...report, title: report.title + ' 第二来源' }, randomUUID());
const startKey = randomUUID();
await request('/work-orders/' + first.id + '/start', technician, 'POST', {}, startKey);
await request('/work-orders/' + first.id + '/start', technician, 'POST', {}, startKey);
await request('/work-orders/' + second.id + '/start', technician, 'POST', {}, randomUUID());
await waitState(first.id, student, 'REPAIRING'); await waitState(second.id, student, 'REPAIRING');
let availability = await request('/rooms/' + roomId + '/availability', student);
assert.equal(availability.available, false); assert.equal(availability.effects.length, 2); checks += 2;
for (const [index, order] of [first, second].entries()) {
  await request('/work-orders/' + order.id + '/complete', technician, 'POST', {}, randomUUID());
  await request('/work-orders/' + order.id + '/accept', technician, 'POST', {}, randomUUID(), 403);
  await request('/work-orders/' + order.id + '/accept', manager, 'POST', {}, randomUUID());
  await waitState(order.id, student, 'CLOSED');
  availability = await request('/rooms/' + roomId + '/availability', student);
  assert.equal(availability.available, index === 1); assert.equal(availability.effects.length, 1 - index); checks += 2;
}
assert.ok((await request('/notifications', student)).length >= 4);
assert.ok((await request('/admin/audit-events', manager)).length >= 6);
await request('/auth/logout', student, 'POST');
await request('/rooms', student, 'GET', undefined, undefined, 401);
const internal = await fetch(base + '/internal/v1/auth/introspect', { method: 'POST' });
assert.equal(internal.status, 404); checks++;
console.log(`PASS: ${checks} assertions/HTTP checks; real login, permissions, idempotency, two maintenance sources, acceptance, notifications, audit, logout, gateway isolation.`);
