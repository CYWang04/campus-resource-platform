import assert from 'node:assert/strict';
import fs from 'node:fs';
import {randomUUID} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {testSchedule} from './test-slots.mjs';

// Only operate on an explicitly selected, isolated PR5 verification project.
assert.match(process.env.COMPOSE_PROJECT_NAME??'',/^campus-pr5-/,'Select an isolated campus-pr5-* project');
const stage=process.argv[2];assert.ok(['prepare','verify'].includes(stage));
const evidence='.work/pr5-compat-2026-10-31/upgrade-snapshot.json';
const env=Object.fromEntries(fs.readFileSync('.env','utf8').trim().split(/\r?\n/).map(line=>{const n=line.indexOf('=');return[line.slice(0,n),line.slice(n+1)];}));
const compose=['compose','--env-file','.env','-f','infra/compose.yml'];
const base=process.env.BASE_URL??'http://127.0.0.1:8080';
async function api(path,token,body,key=randomUUID()){
  const r=await fetch(base+'/api/v1'+path,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json','Idempotency-Key':key},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
  assert.equal(r.status,200,path+' status');const value=await r.json();assert.ok(value.requestId);return value.data;
}
function sql(module,statement){
  assert.ok(['m1','m2'].includes(module));
  const command=`MYSQL_PWD="$${module.toUpperCase()}_DB_PASSWORD" exec mysql --default-character-set=utf8mb4 -u${module} campus_${module} -N -e "$1"`;
  const r=spawnSync('docker',[...compose,'exec','-T','mysql','sh','-c',command,'upgrade-check',statement],{encoding:'utf8',timeout:20000});
  assert.equal(r.status,0,'Database check failed');return r.stdout.trim();
}
function internal(service,method,path,body){
  const input=`url = "http://localhost:8080${path}"\nrequest = "${method}"\nheader = "Content-Type: application/json"\nheader = "X-Service-Key: ${env.SERVICE_KEY}"\ndata = ${JSON.stringify(JSON.stringify(body))}\nwrite-out = "\\n%{http_code}"\n`;
  const r=spawnSync('docker',[...compose,'exec','-T',service,'curl','-sS','-K','-'],{input,encoding:'utf8',timeout:20000});
  assert.equal(r.status,0,'Internal request failed');const lines=r.stdout.trim().split('\n');assert.equal(Number(lines.pop()),200);return JSON.parse(lines.join('\n')).data;
}
async function wait(id,token,state){
  for(let n=0;n<60;n++){const value=await api('/room-applications/'+id,token);if(value.state===state)return value;await new Promise(resolve=>setTimeout(resolve,1000));}
  throw new Error('Application did not converge to '+state);
}
function history(module){return sql(module,'SELECT version,checksum FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank');}
const student=(await api('/auth/login','',{username:'student',password:env.DEMO_PASSWORD})).token;
const manager=(await api('/auth/login','',{username:'manager',password:env.DEMO_PASSWORD})).token;
try{
  if(stage==='prepare'){
    assert.ok(!fs.existsSync(evidence),'Preserve the existing upgrade evidence; do not overwrite');
    assert.equal(sql('m2',"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='campus_m2' AND table_name='application_notification_tasks' AND column_name='application_id'"),'1');
    const {at}=testSchedule();const applications=[];
    for(const [roomId,offset] of [['10000000-0000-0000-0000-000000000001',0],['10000000-0000-0000-0000-000000000002',6]]){
      const version=(await api(`/rooms/${roomId}/availability`,student)).availabilityVersion;
      const app=await api('/room-applications',student,{roomId,roomName:'主干升级验收',startAt:at(offset),endAt:at(offset+1),attendeeCount:10,purpose:'main-to-PR5 upgrade fixture',expectedAvailabilityVersion:version});
      for(let n=0;n<2;n++)await api(`/room-applications/${app.id}/review`,manager,{decision:'APPROVE',comment:'upgrade verification'});
      await wait(app.id,student,'APPROVED');applications.push({id:app.id,roomId,startAt:at(offset),endAt:at(offset+1)});
    }
    const event={eventId:randomUUID(),roomId:applications[1].roomId,sourceVersion:1,maintenanceSourceId:randomUUID(),reason:'主干升级取消验收'};
    internal('m2','POST',`/internal/v1/room-applications/${applications[1].id}/maintenance-cancellation`,event);
    const row=sql('m2',`SELECT id,event_key,application_id,message FROM application_notification_tasks WHERE application_id='${applications[1].id}'`);
    assert.ok(row.includes('M2_MAINTENANCE_CANCELLED:'));
    const snapshot={project:process.env.COMPOSE_PROJECT_NAME,applications,event,notice:row,m1:history('m1'),m2:history('m2'),withdrawKey:randomUUID(),releaseOperation:randomUUID()};
    fs.mkdirSync('.work/pr5-compat-2026-10-31',{recursive:true});
    fs.writeFileSync(evidence,JSON.stringify(snapshot,null,2)+'\n',{mode:0o600});
    console.log('PASS: main baseline has real approved/cancelled applications, a cancellation receipt and notice; migration/data snapshot saved.');
  }else{
    const snapshot=JSON.parse(fs.readFileSync(evidence,'utf8'));assert.equal(snapshot.project,process.env.COMPOSE_PROJECT_NAME);
    assert.equal(history('m2'),snapshot.m2,'Main M2 migration history changed');
    const migrated=history('m1');assert.ok(migrated.startsWith(snapshot.m1+'\n5\t'),'Main M1 history was not preserved before V5');
    assert.equal(sql('m1',"SELECT COUNT(*) FROM flyway_schema_history WHERE version='5' AND success=1"),'1');
    assert.equal((await api('/room-applications/'+snapshot.applications[0].id,student)).state,snapshot.verified?'WITHDRAWN':'APPROVED');
    assert.equal((await api('/room-applications/'+snapshot.applications[1].id,student)).state,'CANCELLED_MAINTENANCE');
    assert.equal(sql('m2',`SELECT id,event_key,application_id,message FROM application_notification_tasks WHERE application_id='${snapshot.applications[1].id}'`),snapshot.notice);
    internal('m2','POST',`/internal/v1/room-applications/${snapshot.applications[1].id}/maintenance-cancellation`,snapshot.event);
    assert.equal(sql('m2',`SELECT COUNT(*) FROM application_cancellation_events WHERE event_id='${snapshot.event.eventId}'`),'1');
    assert.equal(sql('m2',`SELECT COUNT(*) FROM application_notification_tasks WHERE application_id='${snapshot.applications[1].id}'`),'1');
    assert.ok((await api('/reservation-policy',student)).maxDurationHours>=1);
    await api(`/room-applications/${snapshot.applications[0].id}/withdraw`,student,{},snapshot.withdrawKey);
    await wait(snapshot.applications[0].id,student,'WITHDRAWN');
    // The baseline callback fixture deliberately simulated M2 receipt; release its M1 test source.
    const app=snapshot.applications[1];
    internal('m1','PUT',`/internal/v1/resource-effects/M2_ROOM_APPLICATION/${app.id}`,{operationId:snapshot.releaseOperation,sourceVersion:2,roomId:app.roomId,desiredState:'RELEASED',startAt:app.startAt,endAt:app.endAt,reason:'完成隔离升级验收'});
    snapshot.verified=true;fs.writeFileSync(evidence,JSON.stringify(snapshot,null,2)+'\n',{mode:0o600});
    console.log('PASS: main-to-integrated upgrade/restart preserves migrations, applications, receipt and one notice; new policy, callback replay and normal withdrawal work.');
  }
}finally{
  await api('/auth/logout',student,{});await api('/auth/logout',manager,{});
}
