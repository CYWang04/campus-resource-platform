import fs from 'node:fs';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';

const config = Object.fromEntries(fs.readFileSync('.env', 'utf8').trim().split(/\r?\n/).map(line => {
  const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)];
}));
const base = 'http://127.0.0.1:8080/api/v1';
const composeArgs = ['compose', '--env-file', '.env', '-f', 'infra/compose.yml'];
function compose(...args) {
  const result = spawnSync('docker', [...composeArgs, ...args], { encoding: 'utf8', timeout: 180000 });
  if (result.status !== 0) throw new Error(`Compose action failed: ${args.join(' ')}\n${result.stderr}`);
  return result.stdout;
}
async function api(path, token, body, status = 200) {
  const response = await fetch(base + path, {
    method: body ? 'POST' : 'GET', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(15000),
  });
  const value = await response.json(); assert.equal(response.status, status, value.code); return value.data;
}
async function login(username) { return (await api('/auth/login', '', { username, password: config.DEMO_PASSWORD })).token; }
async function wait(id, token, state) {
  for (let count = 0; count < 75; count++) {
    const order = await api('/work-orders/' + id, token);
    if (order.state === state) return order;
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  throw new Error('State did not converge: ' + state);
}
const student = await login('student'), tech = await login('technician'), manager = await login('manager');
const room = (await api('/rooms', student)).items[0].id;
const order = await api('/work-orders', student, { roomId: room, title: '依赖恢复验收', description: '模拟资源服务暂时停止' });
const worker = (await api('/directory/maintenance-workers', manager))[0];
await api('/work-orders/' + order.id + '/assign', manager, { repairerId: worker.id, repairerName: worker.displayName, repairerEmail: worker.email });
compose('stop', 'm1');
try {
  const pending = await api('/work-orders/' + order.id + '/start', tech, {});
  assert.equal(pending.state, 'START_PENDING');
  await new Promise(resolve => setTimeout(resolve, 8000));
  const actual = await api('/work-orders/' + order.id, student);
  assert.equal(actual.state, 'START_PENDING');
  assert.ok(actual.operations[0].attempts >= 1);
} finally { compose('up', '-d', '--wait', '--no-deps', 'm1'); }
await wait(order.id, student, 'REPAIRING');
await api('/work-orders/' + order.id + '/complete', tech, {});
await api('/work-orders/' + order.id + '/accept', manager, {});
await wait(order.id, student, 'CLOSED');
compose('stop', 'm2');
try { await api('/rooms', student, undefined, 503); }
finally { compose('up', '-d', '--wait', '--no-deps', 'm2'); }
// Docker can briefly retain the previous health status during a restart.
// Wait for a real authenticated request, preserving the original session.
let restored = false;
for (let count = 0; count < 45; count++) {
  const response = await fetch(base + '/rooms', { headers: { Authorization: 'Bearer ' + student } });
  if (response.status === 200) { restored = true; break; }
  assert.equal(response.status, 503);
  await new Promise(resolve => setTimeout(resolve, 1000));
}
assert.ok(restored, 'Identity service did not recover');

const source = randomUUID();
function effect(body, expected = 200) {
  const payload = JSON.stringify(body);
  const input = `url = "http://localhost:8080/internal/v1/resource-effects/M3_MAINTENANCE/${source}"\nrequest = "PUT"\nheader = "Content-Type: application/json"\nheader = "X-Service-Key: ${config.SERVICE_KEY}"\ndata = "${payload.replaceAll('"', '\\"')}"\nwrite-out = "\\n%{http_code}"\n`;
  const result = spawnSync('docker', [...composeArgs, 'exec', '-T', 'm1', 'curl', '-sS', '-K', '-'], { input, encoding: 'utf8', timeout: 15000 });
  assert.equal(result.status, 0); const parts = result.stdout.trim().split('\n');
  assert.equal(Number(parts.pop()), expected); return JSON.parse(parts.join('\n')).data;
}
const active = { operationId: randomUUID(), sourceVersion: 1, roomId: room, desiredState: 'ACTIVE', reason: '乱序验收' };
effect(active);
effect({ ...active, operationId: randomUUID(), sourceVersion: 2, desiredState: 'RELEASED' });
assert.equal(effect(active).currentSourceState, 'RELEASED');
effect({ ...active, operationId: randomUUID() }, 409);
assert.equal((await api('/rooms/' + room + '/availability', student)).available, true);

const denied = spawnSync('docker', [...composeArgs, 'exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$M1_DB_PASSWORD" mysql -um1 -e "SELECT COUNT(*) FROM campus_m2.users"'], { encoding: 'utf8', timeout: 15000 });
assert.notEqual(denied.status, 0); assert.match(denied.stderr, /denied/i);
let messages;
for (let count = 0; count < 20; count++) {
  messages = await (await fetch('http://127.0.0.1:8025/api/v1/messages')).json();
  if (messages.total > 0) break;
  await new Promise(resolve => setTimeout(resolve, 1000));
}
assert.ok(messages.total > 0);
console.log('PASS: dependency recovery, fail-closed identity, restart persistence, historical/current source state, stale-version rejection, database account isolation, captured email.');
