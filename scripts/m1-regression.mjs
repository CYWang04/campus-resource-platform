import assert from 'node:assert/strict';
import fs from 'node:fs';
import {randomUUID} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {testSchedule} from './test-slots.mjs';

const env=Object.fromEntries(fs.readFileSync('.env','utf8').trim().split(/\r?\n/).map(line=>{const index=line.indexOf('=');return[line.slice(0,index),line.slice(index+1)];}));
const base=(process.env.BASE_URL??'http://127.0.0.1:8080')+'/api/v1';
const composeArgs=['compose','--env-file','.env','-f','infra/compose.yml'];
let checks=0,student,manager,m2Stopped=false,m3Stopped=false,originalPolicy,failure;
const rooms=[];
async function api(path,{token=manager,method='GET',body,key=randomUUID(),status=200}={}){
  const response=await fetch(base+path,{method,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json','Idempotency-Key':key},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
  const value=await response.json();assert.equal(response.status,status,`${method} ${path}: ${value.code??response.status}`);assert.ok(value.requestId);checks++;
  return status===200?value.data:value;
}
function compose(...args){const result=spawnSync('docker',[...composeArgs,...args],{encoding:'utf8',timeout:180000});assert.equal(result.status,0,`Compose ${args[0]} failed`);return result.stdout;}
function sql(module,statement){
  assert.ok(['m1','m2'].includes(module));
  const command=`MYSQL_PWD="$${module.toUpperCase()}_DB_PASSWORD" exec mysql --default-character-set=utf8mb4 -u${module} campus_${module} -N -e "$1"`;
  const result=spawnSync('docker',[...composeArgs,'exec','-T','mysql','sh','-c',command,'m1-regression',statement],{encoding:'utf8',timeout:20000});
  assert.equal(result.status,0,'Fixture/query failed');return result.stdout.trim();
}
function internal(service,method,path,body,status=200){
  const input=`url = "http://localhost:8080${path}"\nrequest = "${method}"\nheader = "Content-Type: application/json"\nheader = "X-Service-Key: ${env.SERVICE_KEY}"\ndata = ${JSON.stringify(JSON.stringify(body))}\nwrite-out = "\\n%{http_code}"\n`;
  const result=spawnSync('docker',[...composeArgs,'exec','-T',service,'curl','-sS','-K','-'],{input,encoding:'utf8',timeout:20000});
  assert.equal(result.status,0,'Internal request failed');const lines=result.stdout.trim().split('\n');const actual=Number(lines.pop());const value=JSON.parse(lines.join('\n'));
  assert.equal(actual,status,`${method} ${path}: ${value.code??actual}`);checks++;
  return status===200?value.data:value;
}
async function eventually(read,predicate,message){
  for(let i=0;i<60;i++){const value=await read();if(predicate(value))return value;await new Promise(resolve=>setTimeout(resolve,1000));}
  throw new Error(message);
}
async function resource(name,departmentCode=''){
  const room=await api('/admin/rooms',{method:'POST',body:{name:'M1验收-'+name+'-'+randomUUID().slice(0,6),building:'回归测试楼',departmentCode,openTime:'07:00:00',closeTime:'23:00:00',active:true}});
  rooms.push(room);
  const seats=[];
  for(const powerType of ['FIXED','RAIL'])seats.push(await api('/admin/seats',{method:'POST',body:{roomId:room.id,code:'R-'+randomUUID().slice(0,28),powerType,active:true}}));
  return{room,seats};
}
function effect(source,body,status=200,type='M3_MAINTENANCE'){return internal('m1','PUT',`/internal/v1/resource-effects/${type}/${source}`,body,status);}
async function book(seat,startAt,endAt,extra={}){return api('/reservations',{token:student,method:'POST',body:{seatId:seat.id,startAt,endAt,purpose:'M1真实回归'},...extra});}
async function cancel(booking){await api(`/reservations/${booking.id}/cancel`,{token:student,method:'POST',body:{}});}
async function roomInput(room,extra={},status=200){return api('/admin/rooms/'+room.id,{method:'PUT',body:{name:room.name,building:room.building,departmentCode:room.departmentCode??'',openTime:'07:00:00',closeTime:'23:00:00',active:true,...extra},status});}
const {at}=testSchedule();
try{
  student=(await api('/auth/login',{token:'',method:'POST',body:{username:'student',password:env.DEMO_PASSWORD}})).token;
  manager=(await api('/auth/login',{token:'',method:'POST',body:{username:'manager',password:env.DEMO_PASSWORD}})).token;
  originalPolicy=await api('/reservation-policy',{token:student});
  await api('/admin/reservation-policy',{token:student,method:'PUT',body:originalPolicy,status:403});
  const changed=await api('/admin/reservation-policy',{method:'PUT',body:{...originalPolicy,maxDurationHours:6,maxAdvanceDays:7}});
  assert.equal(changed.maxDurationHours,6);
  await api('/admin/reservation-policy',{method:'PUT',body:{...originalPolicy,maxDurationHours:2},status:409});
  await api('/admin/reservation-policy',{method:'PUT',body:{...changed,maxDurationHours:0},status:400});
  const publicResource=await resource('公开资源');
  assert.equal(publicResource.room.departmentCode,null);
  assert.ok((await api('/rooms?building='+encodeURIComponent('回归测试楼')+'&powerType=FIXED',{token:student})).items.some(r=>r.id===publicResource.room.id));
  const filtered=await api(`/rooms/${publicResource.room.id}/seats?startAt=${encodeURIComponent(at(0))}&endAt=${encodeURIComponent(at(1))}&powerType=RAIL`,{token:student});
  assert.equal(filtered.length,1);assert.equal(filtered[0].powerType,'RAIL');
  const longBooking=await book(publicResource.seats[0],at(0),at(6));await cancel(longBooking);
  await api('/rooms/'+publicResource.room.id+'/check-in-code',{token:student,status:403});
  assert.match((await api('/rooms/'+publicResource.room.id+'/check-in-code')).code,/^\d{6}$/);
  const guarded=await book(publicResource.seats[0],at(0),at(1));
  assert.equal((await roomInput(publicResource.room,{active:false},409)).code,'ACTIVE_RESERVATIONS');
  await api('/admin/rooms/'+publicResource.room.id,{method:'PUT',body:{name:publicResource.room.name,building:'回归测试楼',departmentCode:'',openTime:'09:00:00',closeTime:'23:00:00',active:true},status:409});
  await api('/admin/seats/'+publicResource.seats[0].id,{method:'PUT',body:{roomId:publicResource.room.id,code:publicResource.seats[0].code,powerType:'FIXED',active:false},status:409});
  const other=await resource('院系资源','MATH');
  assert.ok(!(await api('/rooms',{token:student})).items.some(r=>r.id===other.room.id));
  await api('/rooms/'+other.room.id+'/seats',{token:student,status:403});
  await api('/rooms/'+other.room.id+'/availability',{token:student,status:403});
  await api('/admin/seats/'+publicResource.seats[0].id,{method:'PUT',body:{roomId:other.room.id,code:publicResource.seats[0].code,powerType:'FIXED',active:true},status:409});
  await cancel(guarded);
  // A persisted reminder that failed delivery must not be sent after cancellation.
  const reminderBooking=await book(publicResource.seats[0],at(0),at(1));
  const reminderTask=randomUUID(),reminderMessage='M1-stale-reminder-'+randomUUID();
  compose('stop','m3');m3Stopped=true;
  try{
    sql('m1',`INSERT INTO reservation_notification_tasks(id,event_key,reservation_id,user_id,email,message) SELECT '${reminderTask}',CONCAT('PRE_REMINDER:',id),id,user_id,user_email,'${reminderMessage}' FROM reservations WHERE id='${reminderBooking.id}'`);
    await eventually(async()=>Number(sql('m1',`SELECT attempts FROM reservation_notification_tasks WHERE id='${reminderTask}'`)),n=>n>=1,'Reminder delivery was not retried');
    await cancel(reminderBooking);
    await eventually(async()=>sql('m1',`SELECT status FROM reservation_notification_tasks WHERE id='${reminderTask}'`),status=>status==='SKIPPED','Cancelled reminder was not skipped');
  }finally{compose('start','--wait','m3');m3Stopped=false;}
  assert.ok(!(await api('/notifications',{token:student})).some(n=>n.message===reminderMessage));checks++;
  const closed=await api(`/rooms/${publicResource.room.id}/seats?startAt=${encodeURIComponent(at(-2))}&endAt=${encodeURIComponent(at(-1))}`,{token:student});
  assert.equal(closed.length,2);assert.ok(closed.every(s=>!s.available&&s.unavailableReason==='OUTSIDE_OPEN_HOURS'));
  await roomInput(publicResource.room,{active:false});
  const inactive=await api(`/rooms/${publicResource.room.id}/seats?startAt=${encodeURIComponent(at(0))}&endAt=${encodeURIComponent(at(1))}`,{token:student});
  assert.ok(inactive.every(s=>!s.available&&s.unavailableReason==='RESOURCE_INACTIVE'));
  await book(publicResource.seats[0],at(0),at(1),{status:409});
  await roomInput(publicResource.room);
  const concurrency=await resource('并发');
  const race=await Promise.all(Array.from({length:4},async()=>{
    const response=await fetch(base+'/reservations',{method:'POST',headers:{Authorization:'Bearer '+student,'Content-Type':'application/json','Idempotency-Key':randomUUID()},body:JSON.stringify({seatId:concurrency.seats[0].id,startAt:at(0),endAt:at(1),purpose:'并发回归'})});
    return{status:response.status,data:(await response.json()).data};
  }));
  assert.equal(race.filter(r=>r.status===200).length,1);assert.ok(race.every(r=>r.status===200||r.status===409));checks++;
  await cancel(race.find(r=>r.status===200).data);

  const joint=await resource('跨模块');
  const version=(await api(`/rooms/${joint.room.id}/availability`,{token:student})).availabilityVersion;
  const application=await api('/room-applications',{token:student,method:'POST',body:{roomId:joint.room.id,roomName:joint.room.name,startAt:at(0),endAt:at(2),attendeeCount:12,purpose:'取消终态回归',expectedAvailabilityVersion:version}});
  for(let i=0;i<2;i++)await api(`/room-applications/${application.id}/review`,{method:'POST',body:{decision:'APPROVE',comment:'回归验收'}});
  const approved=await eventually(()=>api(`/room-applications/${application.id}`,{token:student}),v=>v.state==='APPROVED','Approval did not converge');
  const op=approved.operations[0].id;
  const originalEffect={operationId:op,sourceVersion:1,roomId:joint.room.id,desiredState:'ACTIVE',startAt:at(0),endAt:at(2),expectedAvailabilityVersion:version,reason:'整教室申请 '+application.id};
  const before=(await api(`/rooms/${joint.room.id}/availability`,{token:student})).availabilityVersion;
  assert.equal(effect(application.id,originalEffect,200,'M2_ROOM_APPLICATION').currentSourceState,'ACTIVE');
  assert.equal((await api(`/rooms/${joint.room.id}/availability`,{token:student})).availabilityVersion,before);
  await api('/admin/rooms/'+joint.room.id,{method:'PUT',body:{name:joint.room.name,building:'回归测试楼',departmentCode:'',openTime:'07:00:00',closeTime:'23:00:00',active:false},status:409});
  const checked=await book(joint.seats[0],at(3),at(4));
  sql('m1',`UPDATE reservations SET start_at=UTC_TIMESTAMP(6),end_at=UTC_TIMESTAMP(6)+INTERVAL 1 HOUR WHERE id='${checked.id}'`);
  const dailyCode=(await api(`/rooms/${joint.room.id}/check-in-code`)).code;
  await api(`/reservations/${checked.id}/check-in`,{token:student,method:'POST',body:{code:dailyCode}});
  const future=await book(joint.seats[1],at(4),at(5));
  const sourceA=randomUUID(),sourceB=randomUUID();
  const activeA={operationId:randomUUID(),sourceVersion:1,roomId:joint.room.id,desiredState:'ACTIVE',reason:'跨模块故障回归'};
  const activeB={...activeA,operationId:randomUUID()};
  compose('stop','m2');m2Stopped=true;
  try{
    effect(sourceA,activeA);effect(sourceB,activeB);
    assert.equal(sql('m1',`SELECT state FROM resource_effects WHERE source_type='M2_ROOM_APPLICATION' AND source_id='${application.id}'`),'CANCELLED');
    assert.equal(sql('m1',`SELECT COUNT(*) FROM reservations WHERE id IN ('${checked.id}','${future.id}') AND status='CANCELLED_MAINTENANCE'`),'2');
    assert.equal(sql('m1',`SELECT COUNT(*) FROM violation_records WHERE reservation_id IN ('${checked.id}','${future.id}')`),'0');
    assert.equal(effect(application.id,originalEffect,200,'M2_ROOM_APPLICATION').currentSourceState,'CANCELLED');
    await eventually(async()=>Number(sql('m1',`SELECT attempts FROM resource_cancellation_tasks WHERE application_id='${application.id}'`)),n=>n>=1,'Cancellation was not persisted/retried');
    effect(sourceA,{...activeA,operationId:randomUUID(),sourceVersion:2,desiredState:'RELEASED'});
    assert.equal(sql('m1',`SELECT COUNT(*) FROM resource_effects WHERE room_id='${joint.room.id}' AND state='ACTIVE'`),'1');
  }finally{compose('start','--wait','m2');m2Stopped=false;}
  await eventually(()=>api(`/room-applications/${application.id}`,{token:student}),v=>v.state==='CANCELLED_MAINTENANCE','Maintenance cancellation did not converge');
  await eventually(()=>api('/admin/resource-sync-tasks'),rows=>rows.some(t=>t.applicationId===application.id&&t.status==='SENT'),'Cancellation callback not acknowledged');
  const taskRow=sql('m1',`SELECT id,source_version,maintenance_source_id FROM resource_cancellation_tasks WHERE application_id='${application.id}'`).split('\t');
  const event={eventId:taskRow[0],roomId:joint.room.id,sourceVersion:Number(taskRow[1]),maintenanceSourceId:taskRow[2]};
  assert.equal(internal('m2','POST',`/internal/v1/room-applications/${application.id}/maintenance-cancellations`,event).state,'CANCELLED_MAINTENANCE');
  internal('m2','POST',`/internal/v1/room-applications/${application.id}/maintenance-cancellations`,{...event,maintenanceSourceId:randomUUID()},409);
  const blocked=await api(`/rooms/${joint.room.id}/seats?startAt=${encodeURIComponent(at(0))}&endAt=${encodeURIComponent(at(1))}`,{token:student});
  assert.ok(blocked.every(s=>!s.available));
  effect(sourceB,{...activeB,operationId:randomUUID(),sourceVersion:2,desiredState:'RELEASED'});
  const available=await api(`/rooms/${joint.room.id}/seats?startAt=${encodeURIComponent(at(0))}&endAt=${encodeURIComponent(at(1))}`,{token:student});
  assert.equal(available.length,2);assert.ok(available.every(s=>s.available));
  const current=(await api(`/rooms/${joint.room.id}/availability`,{token:student})).availabilityVersion;
  const revived=effect(application.id,{...originalEffect,operationId:randomUUID(),sourceVersion:2,expectedAvailabilityVersion:current},409,'M2_ROOM_APPLICATION');assert.equal(revived.code,'SOURCE_TERMINAL');
  // Restore a stale pending task to emulate recovery from an older outbox snapshot.
  sql('m2',`UPDATE application_sync_tasks SET status='PENDING',next_attempt=CURRENT_TIMESTAMP WHERE id='${op}'`);
  await eventually(()=>api(`/room-applications/${application.id}`,{token:student}),v=>v.state==='CANCELLED_MAINTENANCE'&&v.operations[0].status==='CANCELLED','Late success resurrected application or left task pending');
  const messages=await eventually(()=>api('/notifications',{token:student}),rows=>rows.some(n=>n.message.includes('整教室申请因紧急维修')),'Cancellation notification not delivered');
  assert.ok(messages.some(n=>n.message.includes('重新申请')));
  const history=await api('/reservations?page=1&pageSize=1',{token:student});assert.equal(history.items.length,1);assert.ok(history.total>=3);
  assert.ok((await api('/admin/resource-audit-events')).some(e=>e.action==='MAINTENANCE_CANCELLED'));
  await api('/admin/resource-audit-events',{token:student,status:403});

}catch(error){failure=error;}
finally{
  if(m2Stopped)compose('start','--wait','m2');
  if(m3Stopped)compose('start','--wait','m3');
  if(originalPolicy&&manager){try{const current=await api('/reservation-policy');await api('/admin/reservation-policy',{method:'PUT',body:{...originalPolicy,version:current.version}});}catch(error){failure??=error;}}
  for(const room of rooms){
    try{
      const source=randomUUID(),active={operationId:randomUUID(),sourceVersion:1,roomId:room.id,desiredState:'ACTIVE',reason:'回归结束清理有效占用'};
      effect(source,active);effect(source,{...active,operationId:randomUUID(),sourceVersion:2,desiredState:'RELEASED'});
      await roomInput(room,{active:false});
    }catch(error){failure??=error;}
  }
}
if(failure)throw failure;
console.log(`PASS: ${checks} M1 HTTP/fault checks; configurable rules, public/department access, filters, resource protection, concurrency, replay, stale-reminder recovery, persisted cancellation, multi-source isolation, terminal-state recovery and audit.`);
