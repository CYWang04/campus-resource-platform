import assert from 'node:assert/strict';
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';

// Local-only demo credentials are read without writing them to output.
const env = Object.fromEntries(fs.readFileSync(new URL('../.env', import.meta.url), 'utf8').trim().split(/\r?\n/).map(line => {
  const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)];
}));
const base = process.env.BASE_URL ?? 'http://127.0.0.1:8080';
let checks = 0;
async function request(path, token, method = 'GET', body, key, expected = 200, expectedCode) {
  const response = await fetch(base + '/api/v1' + path, {
    method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}), ...(key ? { 'Idempotency-Key': key } : {}) },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(15000),
  });
  const result = await response.json();
  assert.equal(response.status, expected, `${method} ${path}: ${result.code ?? response.status}`);
  if (expectedCode) assert.equal(result.code, expectedCode, `${method} ${path}: unexpected business error`);
  assert.ok(result.requestId); checks++;
  return result.data;
}
async function login(username) { return (await request('/auth/login', null, 'POST', { username, password: env.DEMO_PASSWORD })).token; }
function m1sql(statement) {
  const result = spawnSync('docker', ['compose','--env-file','.env','-f','infra/compose.yml','exec','-T','mysql','sh','-c','MYSQL_PWD="$M1_DB_PASSWORD" exec mysql -um1 campus_m1 -N -e "$1"','mysql-smoke',statement], { encoding:'utf8', timeout:30000 });
  assert.equal(result.status,0,result.stderr); return result.stdout.trim();
}
async function waitState(id, token, expected) {
  for (let i = 0; i < 40; i++) {
    const order = await request('/work-orders/' + id, token);
    if (order.state === expected) return order;
    assert.ok(!order.operations.some(op => op.status === 'FAILED'), 'Resource synchronization failed');
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  throw new Error('Timed out waiting for ' + expected);
}
async function waitApplication(id, token, expected) {
  for (let i = 0; i < 40; i++) {
    const item = await request('/room-applications/' + id, token);
    if (item.state === expected) return item;
    if (item.state === 'CONFLICT' || item.operations?.some(op => op.status === 'FAILED')) throw new Error('Application synchronization failed: ' + item.state);
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  throw new Error('Timed out waiting for application ' + expected);
}
await request('/rooms', null, 'GET', undefined, undefined, 401);
await request('/auth/login', null, 'POST', { username: 'student', password: 'wrong' }, undefined, 401);
const student = await login('student'), technician = await login('technician'), manager = await login('manager');
const savedPolicy=await request('/reservation-policy',manager);
await request('/admin/reservation-policy',manager,'PUT',{...savedPolicy,maxDurationHours:4,maxAdvanceDays:7},randomUUID());
try {
for (const service of ['m1', 'm2', 'm3']) {
  const version = await request('/system/' + service + '/version', student);
  assert.equal(version.service, service); assert.equal(version.version, '0.1.0-SNAPSHOT'); checks += 2;
}
const room = (await request('/rooms', student)).items.find(item => item.id === '10000000-0000-0000-0000-000000000001');
assert.ok(room); const roomId = room.id;
assert.equal(room.openTime, '07:00:00');
assert.equal(room.closeTime, '23:00:00');
const roomSeats = await request('/rooms/' + roomId + '/seats', student);
assert.ok(roomSeats.length > 1);
await request('/admin/audit-events', student, 'GET', undefined, undefined, 403);

const hour = 60 * 60 * 1000;
function shanghaiTomorrowAt(hourOfDay) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en', {
      timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    }).formatToParts(new Date()).filter(part => part.type !== 'literal').map(part => [part.type, part.value]),
  );
  const shanghaiToday = new Date(`${parts.year}-${parts.month}-${parts.day}T00:00:00+08:00`);
  return new Date(shanghaiToday.getTime() + 24 * hour + hourOfDay * hour);
}
const nextHour = shanghaiTomorrowAt(8);
const at = (offset) => new Date(nextHour.getTime() + offset * hour).toISOString();
const reservation = { seatId: roomSeats[0].id, startAt: at(0), endAt: at(1), purpose: '自动验收预约' };
await request('/reservations', technician, 'POST', reservation, randomUUID(), 403);
await request('/reservations', student, 'POST', { ...reservation, startAt: new Date(nextHour.getTime() + 30 * 60 * 1000).toISOString(), endAt: new Date(nextHour.getTime() + 90 * 60 * 1000).toISOString() }, randomUUID(), 400, 'WHOLE_HOUR_REQUIRED');
await request('/reservations', student, 'POST', { ...reservation, startAt: new Date(Math.floor(Date.now() / hour) * hour - hour).toISOString(), endAt: new Date(Math.floor(Date.now() / hour) * hour).toISOString() }, randomUUID(), 409, 'START_IN_PAST');
await request('/reservations', student, 'POST', { ...reservation, endAt: at(5) }, randomUUID(), 400, 'INVALID_DURATION');
await request('/reservations', student, 'POST', { ...reservation, startAt: new Date(Math.ceil(Date.now() / hour) * hour + 8 * 24 * hour).toISOString(), endAt: new Date(Math.ceil(Date.now() / hour) * hour + 8 * 24 * hour + hour).toISOString() }, randomUUID(), 409, 'START_TOO_LATE');
const reservationKey = randomUUID();
const booked = await request('/reservations', student, 'POST', reservation, reservationKey);
const replayedBooking = await request('/reservations', student, 'POST', reservation, reservationKey);
assert.equal(booked.id, replayedBooking.id); checks++;
await request('/reservations', student, 'POST', { ...reservation, purpose: '不同用途' }, reservationKey, 409);
assert.equal((await request('/reservations/' + booked.id, student)).id, booked.id); checks++;
assert.ok((await request('/reservations?page=1&pageSize=100', student)).items.some(item => item.id === booked.id)); checks++;
assert.equal((await request(`/rooms/${roomId}/seats?startAt=${encodeURIComponent(at(0))}&endAt=${encodeURIComponent(at(1))}`, student)).find(seat => seat.id === reservation.seatId).available, false); checks++;
const adjacent = await request('/reservations', student, 'POST', { ...reservation, startAt: at(1), endAt: at(2), purpose: '相邻时段' }, randomUUID());

const concurrentBody = { seatId: roomSeats[1].id, startAt: at(3), endAt: at(4), purpose: '并发预约' };
async function concurrentReservation(key) {
  const response = await fetch(base + '/api/v1/reservations', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + student, 'Content-Type': 'application/json', 'Idempotency-Key': key },
    body: JSON.stringify(concurrentBody), signal: AbortSignal.timeout(15000),
  });
  const result = await response.json();
  assert.ok(result.requestId); checks++;
  return { status: response.status, data: result.data };
}
const concurrent = await Promise.all([concurrentReservation(randomUUID()), concurrentReservation(randomUUID())]);
assert.deepEqual(concurrent.map(value => value.status).sort(), [200, 409]); checks++;
const concurrentWinner = concurrent.find(value => value.status === 200).data;
await request('/reservations/' + booked.id + '/cancel', technician, 'POST', {}, randomUUID(), 403);
const cancelKey = randomUUID();
const cancelled = await request('/reservations/' + booked.id + '/cancel', student, 'POST', {}, cancelKey);
assert.equal(cancelled.status, 'CANCELLED'); checks++;
assert.equal((await request('/reservations/' + booked.id + '/cancel', student, 'POST', {}, cancelKey)).status, 'CANCELLED'); checks++;
await request('/reservations/' + adjacent.id + '/cancel', student, 'POST', {}, cancelKey, 409);
const replacement = await request('/reservations', student, 'POST', { ...reservation, purpose: '取消后重新预约' }, randomUUID());
for (const item of [adjacent, concurrentWinner, replacement])
  await request('/reservations/' + item.id + '/cancel', student, 'POST', {}, randomUUID());

const applicationRoom = (await request('/rooms', student)).items.find(item => item.id === '10000000-0000-0000-0000-000000000002');
const applicationBody = { roomId: applicationRoom.id, roomName: applicationRoom.name, startAt: at(6), endAt: at(8), attendeeCount: 30, purpose: '自动验收整教室申请', expectedAvailabilityVersion: applicationRoom.availabilityVersion };
const application = await request('/room-applications', student, 'POST', applicationBody, randomUUID());
assert.equal(application.state, 'DEPARTMENT_PENDING'); checks++;
await request('/room-applications/' + application.id + '/review', student, 'POST', { decision: 'APPROVE', comment: '越权' }, randomUUID(), 403);
await request('/room-applications/' + application.id + '/review', manager, 'POST', { decision: 'APPROVE', comment: '院系同意' }, randomUUID());
await request('/room-applications/' + application.id + '/review', manager, 'POST', { decision: 'APPROVE', comment: '资源同意' }, randomUUID());
await waitApplication(application.id, manager, 'APPROVED');
assert.equal((await request(`/rooms/${applicationRoom.id}/seats?startAt=${encodeURIComponent(at(6))}&endAt=${encodeURIComponent(at(8))}`, student))[0].available, false); checks++;
await request('/room-applications/' + application.id + '/withdraw', manager, 'POST', {}, randomUUID(), 403);
await request('/room-applications/' + application.id + '/withdraw', student, 'POST', {}, randomUUID());
await waitApplication(application.id, student, 'WITHDRAWN');
assert.equal((await request(`/rooms/${applicationRoom.id}/seats?startAt=${encodeURIComponent(at(6))}&endAt=${encodeURIComponent(at(8))}`, student))[0].available, true); checks++;
const conflictBooking = await request('/reservations', student, 'POST', { seatId: roomSeats[0].id, startAt: at(9), endAt: at(10), purpose: '整教室审批冲突验证' }, randomUUID());
const currentRoom = (await request('/rooms', student)).items.find(item => item.id === roomId);
const conflictKey = randomUUID();
const conflictApplicationBody = { roomId, roomName: currentRoom.name, startAt: at(9), endAt: at(10), attendeeCount: 12, purpose: '应被个人预约阻止', expectedAvailabilityVersion: currentRoom.availabilityVersion };
const conflictApplication = await request('/room-applications', student, 'POST', conflictApplicationBody, conflictKey);
assert.equal((await request('/room-applications', student, 'POST', conflictApplicationBody, conflictKey)).id, conflictApplication.id); checks++;
await request('/room-applications', student, 'POST', { ...conflictApplicationBody, purpose: '不同申请内容' }, conflictKey, 409);
await request('/room-applications/' + conflictApplication.id + '/review', manager, 'POST', { decision: 'APPROVE', comment: '院系同意' }, randomUUID());
await request('/room-applications/' + conflictApplication.id + '/review', manager, 'POST', { decision: 'APPROVE', comment: '资源核验' }, randomUUID());
for (let i = 0; i < 20; i++) { const value = await request('/room-applications/' + conflictApplication.id, manager); if (value.state === 'CONFLICT') { checks++; break; } await new Promise(resolve => setTimeout(resolve, 500)); }
assert.equal((await request('/room-applications/' + conflictApplication.id, manager)).state, 'CONFLICT'); checks++;
await request('/reservations/' + conflictBooking.id + '/cancel', student, 'POST', {}, randomUUID());
const rejectedApplication = await request('/room-applications', student, 'POST', { ...applicationBody, startAt: at(10), endAt: at(11), purpose: '驳回流程验证', expectedAvailabilityVersion: (await request('/rooms', student)).items.find(item => item.id === applicationRoom.id).availabilityVersion }, randomUUID());
assert.equal((await request('/room-applications/' + rejectedApplication.id + '/review', manager, 'POST', { decision: 'REJECT', comment: '材料不完整' }, randomUUID())).state, 'REJECTED'); checks++;

const report = { roomId, title: '自动验收 ' + randomUUID().slice(0, 8), description: '用于验证维修来源隔离' };
const createKey = randomUUID();
const first = await request('/work-orders', student, 'POST', report, createKey);
const duplicate = await request('/work-orders', student, 'POST', report, createKey);
assert.equal(first.id, duplicate.id); checks++;
await request('/work-orders', student, 'POST', { ...report, title: '不同内容' }, createKey, 409);
await request('/work-orders/' + first.id + '/start', student, 'POST', {}, randomUUID(), 403);
const second = await request('/work-orders', student, 'POST', { ...report, title: report.title + ' 第二来源' }, randomUUID());
const cancelledOrder = await request('/work-orders', student, 'POST', { ...report, title: report.title + ' 取消流程' }, randomUUID());
assert.equal((await request('/work-orders/' + cancelledOrder.id + '/cancel', student, 'POST', {}, randomUUID())).state, 'CANCELLED'); checks++;
const worker = (await request('/directory/maintenance-workers', manager))[0];
assert.ok(worker); checks++;
await request('/work-orders/' + first.id + '/assign', manager, 'POST', { repairerId: worker.id, repairerName: worker.displayName, repairerEmail: worker.email }, randomUUID());
await request('/work-orders/' + second.id + '/assign', manager, 'POST', { repairerId: worker.id, repairerName: worker.displayName, repairerEmail: worker.email }, randomUUID());
const startKey = randomUUID();
await request('/work-orders/' + first.id + '/start', technician, 'POST', {}, startKey);
await request('/work-orders/' + first.id + '/start', technician, 'POST', {}, startKey);
await request('/work-orders/' + second.id + '/start', technician, 'POST', {}, randomUUID());
await waitState(first.id, student, 'REPAIRING'); await waitState(second.id, student, 'REPAIRING');
await request('/reservations', student, 'POST', { ...reservation, startAt: at(12), endAt: at(13), purpose: '维修限制验证' }, randomUUID(), 409, 'ROOM_UNAVAILABLE');
let availability = await request('/rooms/' + roomId + '/availability', student);
for(let i=0;i<10&&availability.available;i++){await new Promise(resolve=>setTimeout(resolve,500));availability=await request('/rooms/' + roomId + '/availability', student);}
assert.equal(availability.available, false); assert.equal(availability.effects.length, 2); checks += 2;
for (const [index, order] of [first, second].entries()) {
  await request('/work-orders/' + order.id + '/complete', technician, 'POST', {}, randomUUID());
  await request('/work-orders/' + order.id + '/accept', technician, 'POST', {}, randomUUID(), 403);
  await request('/work-orders/' + order.id + '/accept', manager, 'POST', {}, randomUUID());
  await waitState(order.id, student, 'CLOSED');
  availability = await request('/rooms/' + roomId + '/availability', student);
  assert.equal(availability.effects.length, 1 - index); checks++;
  const freed = await request(`/rooms/${roomId}/seats?startAt=${encodeURIComponent(at(0))}&endAt=${encodeURIComponent(at(1))}`, student);
  assert.equal(freed.every(s => s.available), index === 1); checks++;
}
await request('/work-orders/' + first.id + '/rating', manager, 'POST', { score: 5, comment: '越权' }, randomUUID(), 403);
const rated = await request('/work-orders/' + first.id + '/rating', student, 'POST', { score: 5, comment: '维修及时' }, randomUUID());
assert.equal(rated.rating, 5); checks++;
const historyBase = { seatId: roomSeats[0].id, startAt: at(0), endAt: at(1), purpose: '历史复用验收' };
const history = await request('/reservations', student, 'POST', historyBase, randomUUID());
await request('/reservations/' + history.id + '/cancel', student, 'POST', {}, randomUUID());
const repeated = await request('/reservations/' + history.id + '/repeat', student, 'POST', { startAt: at(1), endAt: at(2) }, randomUUID());
assert.equal(repeated.seatId, history.seatId); assert.equal(repeated.seatId, historyBase.seatId); assert.equal(repeated.purpose, history.purpose); checks += 2;
await request('/reservations/' + repeated.id + '/cancel', student, 'POST', {}, randomUUID());
const checkinReservation = await request('/reservations', student, 'POST', { ...historyBase, purpose:'签到验收' }, randomUUID());
m1sql(`UPDATE reservations SET start_at=UTC_TIMESTAMP()-INTERVAL 5 MINUTE,end_at=UTC_TIMESTAMP()+INTERVAL 55 MINUTE WHERE id='${checkinReservation.id}'`);
await request('/rooms/' + roomId + '/check-in-code', student, 'GET', undefined, undefined, 403);
const dailyCode = await request('/rooms/' + roomId + '/check-in-code', manager);
await request('/reservations/' + checkinReservation.id + '/check-in', student, 'POST', { code:'000000' }, randomUUID(), 409);
const checkedIn = await request('/reservations/' + checkinReservation.id + '/check-in', student, 'POST', { code:dailyCode.code }, randomUUID());
assert.equal(checkedIn.status,'CHECKED_IN'); checks++;
const noShowReservation = await request('/reservations', student, 'POST', { seatId:roomSeats[1].id,startAt:at(0),endAt:at(1),purpose:'违约验收' }, randomUUID());
m1sql(`UPDATE reservations SET start_at=UTC_TIMESTAMP()-INTERVAL 16 MINUTE,end_at=UTC_TIMESTAMP()+INTERVAL 44 MINUTE WHERE id='${noShowReservation.id}'`);
const preReminder = await request('/reservations', student, 'POST', { seatId:roomSeats[0].id,startAt:at(2),endAt:at(3),purpose:'开始前提醒验收' }, randomUUID());
const lateReminder = await request('/reservations', student, 'POST', { seatId:roomSeats[1].id,startAt:at(2),endAt:at(3),purpose:'迟到提醒验收' }, randomUUID());
m1sql(`UPDATE reservations SET start_at=UTC_TIMESTAMP()+INTERVAL 14 MINUTE,end_at=UTC_TIMESTAMP()+INTERVAL 74 MINUTE WHERE id='${preReminder.id}'; UPDATE reservations SET start_at=UTC_TIMESTAMP()-INTERVAL 11 MINUTE,end_at=UTC_TIMESTAMP()+INTERVAL 49 MINUTE WHERE id='${lateReminder.id}'`);
for(let i=0;i<40;i++){const value=await request('/reservations/'+noShowReservation.id,student);if(value.status==='NO_SHOW')break;await new Promise(resolve=>setTimeout(resolve,1000));}
assert.equal((await request('/reservations/'+noShowReservation.id,student)).status,'NO_SHOW');checks++;
assert.ok((await request('/reservations/violations',student)).some(v=>v.reservationId===noShowReservation.id));checks++;
let reminderMessages=[];for(let i=0;i<15;i++){reminderMessages=(await request('/notifications',student)).map(n=>n.message);if(reminderMessages.some(m=>m.includes('15分钟内开始'))&&reminderMessages.some(m=>m.includes('已开始10分钟')))break;await new Promise(resolve=>setTimeout(resolve,1000));}
assert.ok(reminderMessages.some(m=>m.includes('15分钟内开始')));assert.ok(reminderMessages.some(m=>m.includes('已开始10分钟')));checks+=2;
const statistics=await request('/admin/reservation-statistics',manager);assert.ok(statistics.byStatus.length>0);assert.ok(statistics.violations>0);checks+=2;
await request('/admin/reservation-statistics',student,'GET',undefined,undefined,403);
const managedRoom=await request('/admin/rooms',manager,'POST',{name:'自动验收教室 '+randomUUID().slice(0,6),building:'测试楼',departmentCode:'CS',openTime:'07:00:00',closeTime:'23:00:00',active:true});
const managedSeat=await request('/admin/seats',manager,'POST',{roomId:managedRoom.id,code:'AUTO-'+randomUUID().slice(0,8),powerType:'RAIL',active:true});
assert.equal(managedSeat.powerType,'RAIL');checks++;
assert.ok((await request('/rooms?building='+encodeURIComponent('测试楼')+'&powerType=RAIL',student)).items.some(r=>r.id===managedRoom.id));checks++;
assert.ok((await request('/notifications', student)).length >= 4);
assert.ok((await request('/admin/audit-events', manager)).length >= 6);
await request('/auth/logout', student, 'POST');
await request('/rooms', student, 'GET', undefined, undefined, 401);
const internal = await fetch(base + '/internal/v1/auth/introspect', { method: 'POST' });
assert.equal(internal.status, 404); checks++;
} finally {
  const current=await request('/reservation-policy',manager);
  await request('/admin/reservation-policy',manager,'PUT',{...savedPolicy,version:current.version},randomUUID());
}
console.log(`PASS: ${checks} assertions/HTTP checks; M1 resource management, filtering, reservations, repeat booking, daily-code check-in, no-show violations and statistics; M2 approvals; M3 work orders; notifications, audit and gateway isolation.`);
