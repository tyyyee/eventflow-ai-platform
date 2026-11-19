import assert from 'node:assert/strict';
import {createHmac,randomBytes,randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const project=fileURLToPath(new URL('../..',import.meta.url));
const local=path.join(project,'.local/integration');
const evidence=path.join(project,'docs/verification/2026-10-27-full-integration');
const base='http://127.0.0.1:38093';
const mysql=process.env.INTEGRATION_MYSQL??'F:/software/Mysql/mysql-8.0.31-winx64/bin/mysql.exe';
const secrets=JSON.parse(readFileSync(path.join(local,'credentials.json')));
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
mkdirSync(evidence,{recursive:true});
const result={startedAt:new Date().toISOString(),base,checks:[],benchmark:[],boundaries:{http:true,customerAuthentication:'registered Cookie session plus CSRF for mutations',adminAuthentication:'existing laboratory HMAC, isolated environment only',data:'actual HTTP-created test orders, small functional dataset',capacityClaim:false}};
function save(){writeFileSync(path.join(evidence,'result.json'),JSON.stringify(result,null,2)+'\n');}
function passed(name,details={}){result.checks.push({name,passed:true,...details});save();console.log(`PASS ${name}`);}
function sql(statement,name='source',schema=name==='source'?'qupiao_sharding_source':'qupiao_history') {
  return execFileSync(mysql,['--defaults-extra-file=root.cnf','--batch','--raw','--skip-column-names','--default-character-set=utf8mb4',schema],{cwd:path.join(project,'.local/sharding',name),input:statement,encoding:'utf8',timeout:15000}).trim();
}
function service(action) {
  console.log(`SERVICE ${action}`);
  // On Windows a detached Java child can keep its parent's pipe handles open.
  // Discard service output here; each component already writes dedicated local logs.
  execFileSync('pwsh',['-NoProfile','-File',path.join(project,'backend/scripts/integration-local.ps1'),action],{cwd:project,stdio:'ignore',timeout:180000});
}
function adminToken(){const payload=`9000001:admin:${Math.floor(Date.now()/1000)+7200}`;return `${payload}.${createHmac('sha256',secrets.authSecret).update(payload).digest('base64url')}`;}
async function request(route,{session,admin=false,method='GET',body,csrf=true}={}) {
  const headers={'Content-Type':'application/json'};
  if(admin) headers.Authorization=`Bearer ${adminToken()}`;
  if(session){headers.Cookie=session.cookie;if(csrf)headers['X-CSRF-Token']=session.csrfToken;}
  const response=await fetch(base+route,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(12000)});
  const data=await response.json().catch(()=>null);
  return {status:response.status,data,cookie:response.headers.get('set-cookie')?.split(';')[0]};
}
async function ok(route,options={},expected=200) {
  const response=await request(route,options);
  assert.equal(response.status,expected,`${route}: ${JSON.stringify(response.data)}`);
  return response.data;
}
async function until(check,timeout=30000,label='condition') {
  const end=Date.now()+timeout;let last;
  while(Date.now()<end){last=await check();if(last)return last;await sleep(300);}
  throw new Error(`Timed out: ${label}; last=${JSON.stringify(last)}`);
}
async function ready(){await until(async()=>{try{return (await request('/actuator/health/readiness')).status===200;}catch{return false;}},45000,'backend readiness');}
async function fixture(id,stock=30){await ok(`/v2/admin/fixtures/${id}?stock=${stock}`,{admin:true,method:'POST'});}
async function buy(session,activityId,key=`http_${randomUUID()}`){
  const start=performance.now();
  const accepted=await ok('/v2/requests',{session,method:'POST',body:{requestId:key,activityId}},202);
  return {id:accepted.id,key,activityId,acceptedAt:Date.now(),acceptMs:performance.now()-start};
}
async function order(session,requestId){
  return until(async()=>{const r=await request(`/v2/requests/${requestId}/order`,{session});return r.status===200?r.data:false;},90000,'asynchronous order creation');
}
async function pendingZero(){await until(()=>Number(sql('SELECT COUNT(*) FROM ux_order_projection WHERE replica_pending=1 OR applied_revision<revision'))===0,90000,'both projections drain');}
async function verify(){
  let batch;
  do{batch=await ok('/v2/admin/order-sharding/backfill?limit=100',{admin:true,method:'POST'});}while(!batch.done);
  await pendingZero();
  do{batch=await ok('/v2/admin/order-sharding/verify?limit=100',{admin:true,method:'POST'});}while(!batch.done);
  assert.equal(batch.passed,true,JSON.stringify(batch));return batch;
}
async function mode(value){return ok('/v2/admin/order-sharding/read-mode',{admin:true,method:'POST',body:{mode:value}});}
async function histories(sessions){return Promise.all(sessions.map(session=>ok('/v2/account/order-history',{session})));}
function equalHistories(a,b){assert.deepEqual(a,b);}
async function benchmark(sessions) {
  await pendingZero();
  const reference=await histories(sessions);
  const rounds=[];
  for(let round=1;round<=3;round++) for(const selected of (round%2?['LOCAL','SHARDED']:['SHARDED','LOCAL'])) {
    await mode(selected);
    for(let i=0;i<100;i++) await ok('/v2/account/order-history',{session:sessions[i%sessions.length]});
    // Eight fixed workers, rotating real sessions; pacing avoids the intentional 100/s per-user limiter.
    const samples=[];const durationMs=10000;let operations=0,errors=0,mismatches=0;
    const began=performance.now(),end=began+durationMs;
    await Promise.all(Array.from({length:8},(_,worker)=>(async()=>{
      let iteration=worker;
      while(performance.now()<end){
        const user=iteration%sessions.length;iteration+=8;const start=performance.now();
        try{
          const r=await request('/v2/account/order-history',{session:sessions[user]});
          if(r.status!==200) errors++;
          else {try{equalHistories(r.data,reference[user]);}catch{mismatches++;}}
        }catch{errors++;}
        samples.push(performance.now()-start);operations++;
        await sleep(25);
      }
    })()));
    const elapsedMs=performance.now()-began;samples.sort((a,b)=>a-b);
    const item={mode:selected,round,workers:8,pacingMs:25,users:sessions.length,operations,errors,mismatches,elapsedMs,qps:operations*1000/elapsedMs,p50Ms:samples[Math.ceil(samples.length*.5)-1],p95Ms:samples[Math.ceil(samples.length*.95)-1],maxMs:samples.at(-1)};
    rounds.push(item);result.benchmark=rounds;save();
    writeFileSync(path.join(local,`http-${selected}-${round}.csv`),'latency_ms\n'+samples.join('\n')+'\n');
    console.log(`BENCH ${selected} round=${round} qps=${item.qps.toFixed(1)} p95=${item.p95Ms.toFixed(2)}ms errors=${errors}`);
    assert.equal(errors,0);assert.equal(mismatches,0);
  }
  await mode('LOCAL');
}

try {
  await ready();await mode('LOCAL');
  const runId=Date.now().toString(36);const sessions=[];const password=randomBytes(20).toString('hex');
  assert.equal((await request('/v2/account/order-history')).status,401);
  for(let i=0;i<18;i++) {
    const username=`it_${runId}_${i}`;
    const registered=await request('/v2/auth/register',{method:'POST',body:{username,password,displayName:`联调用户${i}`}});
    assert.equal(registered.status,201,JSON.stringify(registered.data));
    sessions.push({...registered.data,cookie:registered.cookie,username});
  }
  const login=await request('/v2/auth/login',{method:'POST',body:{username:sessions[0].username,password}});
  assert.equal(login.status,200);sessions[0]={...login.data,cookie:login.cookie,username:sessions[0].username};
  writeFileSync(path.join(local,'http-sessions.json'),JSON.stringify(sessions));
  const a=sessions[0],b=sessions[1],c=sessions[2];
  assert.equal((await request('/v2/admin/order-sharding',{session:a})).status,403);
  assert.equal((await request('/v2/requests',{session:a,method:'POST',body:{requestId:'csrf_reject_test',activityId:1},csrf:false})).status,403);
  passed('real registration/login, anonymous denial, admin denial and CSRF rejection',{users:sessions.length,userIds:sessions.map(s=>s.user.id)});
  const activity=Date.now();await fixture(activity);
  const purchases=[];
  for(const session of sessions) {
    const purchase=await buy(session,activity);const created=await order(session,purchase.id);
    assert.equal(created.state,'PENDING_CONFIRM');purchases.push({...purchase,orderId:created.id,userId:session.user.id});
    if(session!==a && session!==c) await ok(`/v2/requests/${purchase.id}/cancel`,{session,method:'POST'});
  }
  const payment=await ok('/v2/account/payments',{session:a,method:'POST',body:{requestId:purchases[0].id}});
  const channel=`receipt_${randomUUID()}`;
  for(let i=0;i<2;i++) assert.equal((await ok(`/v2/account/payments/${payment.id}/sandbox-receipt`,{session:a,method:'POST',body:{channelId:channel,amountCents:payment.amount_cents}})).outcome,'APPLIED');
  assert.equal((await order(a,purchases[0].id)).state,'CONFIRMED');
  const replay=await ok('/v2/requests',{session:a,method:'POST',body:{requestId:purchases[0].key,activityId:activity}});
  assert.equal(replay.id,purchases[0].id);
  assert.equal((await request(`/v2/requests/${purchases[0].id}/order`,{session:b})).status,404);
  assert.equal(Number(sql(`SELECT COUNT(*) FROM ux_order WHERE activity_id=${activity}`)),18);
  passed('RocketMQ asynchronous order creation, payment receipt replay, cancellation, request idempotence and owner isolation',{activityId:activity,orders:purchases.length});
  const timeoutPayment=await ok('/v2/account/payments',{session:c,method:'POST',body:{requestId:purchases[2].id}});
  await pendingZero();const validated=await verify();
  const localHistory=await histories(sessions);await mode('SHARDED');const shardedHistory=await histories(sessions);
  equalHistories(localHistory,shardedHistory);
  for(let i=0;i<sessions.length;i++) {
    assert.equal(shardedHistory[i].total,1);assert.equal(shardedHistory[i].items[0].id,purchases[i].orderId);
    equalHistories(await ok(`/v2/account/order-history?userId=${sessions[(i+1)%sessions.length].user.id}`,{session:sessions[i]}),shardedHistory[i]);
  }
  passed('backfill, complete field verification, guarded cutover and login-based shard isolation',{verification:validated});
  // Stop both owned history instances, exercise the real HTTP error and source-only rollback.
  service('stop-history');
  const unavailable=await request('/v2/account/order-history',{session:a});assert.equal(unavailable.status,503);
  await mode('LOCAL');equalHistories(await ok('/v2/account/order-history',{session:a}),localHistory[0]);
  const outageActivity=activity+1;await fixture(outageActivity,5);
  const outagePurchase=await buy(b,outageActivity);await order(b,outagePurchase.id);
  await ok(`/v2/requests/${outagePurchase.id}/cancel`,{session:b,method:'POST'});
  assert.ok(Number(sql('SELECT COUNT(*) FROM ux_order_projection WHERE replica_pending=1'))>0);
  const unsafeSwitch=await request('/v2/admin/order-sharding/read-mode',{admin:true,method:'POST',body:{mode:'SHARDED'}});assert.equal(unsafeSwitch.status,409);
  service('start-history');await pendingZero();await verify();await mode('SHARDED');
  const bRecovered=await ok('/v2/account/order-history',{session:b});assert.equal(bRecovered.total,2);
  assert.equal(bRecovered.items.find(x=>x.requestId===outagePurchase.id).state,'CANCELLED');
  passed('history outage: HTTP 503, source-only LOCAL rollback, continuing trade, unsafe cutover blocked and durable retry recovery');
  await mode('LOCAL');
  const restartActivity=activity+2;await fixture(restartActivity,5);
  service('stop-rocketmq');
  const durable=await buy(a,restartActivity);
  assert.equal(Number(sql(`SELECT COUNT(*) FROM ux_outbox WHERE request_id='${durable.id}' AND sent_at IS NULL`)),1);
  service('stop-backend');service('start-rocketmq');service('start-backend');await ready();
  await order(a,durable.id);
  await ok(`/v2/requests/${durable.id}/cancel`,{session:a,method:'POST'});
  await until(()=>Number(sql(`SELECT COUNT(*) FROM ux_outbox WHERE request_id='${durable.id}' AND sent_at IS NULL`))===0,90000,'RocketMQ relay after restart');
  assert.equal(Number(sql(`SELECT COUNT(*) FROM ux_order WHERE request_id='${durable.id}'`)),1);
  passed('RocketMQ interruption plus backend restart: accepted outbox recovered and exactly one authoritative order');
  const freshnessActivity=activity+3;await fixture(freshnessActivity,5);await fixture(activity+4,5);
  service('stop-redis');
  // Use an existing prepared activity: Redis failure must reject a new reservation without source writes.
  const failKey=`redis_${randomUUID()}`;
  const rejected=await request('/v2/requests',{session:b,method:'POST',body:{requestId:failKey,activityId:restartActivity}});
  assert.equal(rejected.status,503);
  assert.equal(Number(sql(`SELECT COUNT(*) FROM ux_request WHERE request_key='${failKey}'`)),0);
  assert.equal((await request('/v2/account/order-history',{session:a})).status,200);
  service('start-redis');
  await until(async()=>{const r=await request('/actuator/health');return r.status===200;},30000,'Redis reconnect');
  const recoveryKey=`recover_${randomUUID()}`;let recovery503s=0;const reconnectStarted=Date.now();
  const recovered=await until(async()=>{
    const r=await request('/v2/requests',{session:b,method:'POST',body:{requestId:recoveryKey,activityId:freshnessActivity}});
    if(r.status===503){recovery503s++;return false;}
    assert.ok([200,202].includes(r.status),JSON.stringify(r));return r.data;
  },30000,'Redis reservation reconnect with same idempotency key');
  result.redisRecovery={transient503s:recovery503s,observedMs:Date.now()-reconnectStarted};
  passed('Redis interruption: reservations fail closed, history remains usable and same-key retry recovers',result.redisRecovery);
  await order(b,recovered.id);await ok(`/v2/requests/${recovered.id}/cancel`,{session:b,method:'POST'});
  await pendingZero();await verify();await mode('SHARDED');
  const freshness=await buy(b,activity+4);await order(b,freshness.id);
  const createdMs=Number(sql(`SELECT UNIX_TIMESTAMP(created_at)*1000 FROM ux_order WHERE request_id='${freshness.id}'`));
  await until(async()=>((await ok('/v2/account/order-history',{session:b})).items.some(x=>x.requestId===freshness.id)),30000,'new order reaches selected shard');
  const createVisibilityMs=Date.now()-createdMs;
  await ok(`/v2/requests/${freshness.id}/cancel`,{session:b,method:'POST'});
  const changedMs=Number(sql(`SELECT UNIX_TIMESTAMP(p.changed_at)*1000 FROM ux_order_projection p JOIN ux_order o ON o.id=p.order_id WHERE o.request_id='${freshness.id}'`));
  await until(async()=>((await ok('/v2/account/order-history',{session:b})).items.find(x=>x.requestId===freshness.id)?.state==='CANCELLED'),30000,'cancellation reaches selected shard');
  result.freshness={createVisibilityMs,cancelVisibilityMs:Date.now()-changedMs,pollIntervalMs:300,basis:'source timestamp to first successful authenticated HTTP observation; includes polling and SQL observation overhead'};
  passed('live create/cancel visible through sharded authenticated HTTP',{...result.freshness});
  await pendingZero();await verify();
  // Benchmark only stable orders; exclude naturally expiring user C until the close test finishes.
  await benchmark(sessions.filter(session=>session!==c));
  const deadline=Date.parse((await order(c,purchases[2].id)).confirm_until);
  console.log(`WAIT natural five-minute expiry, remaining=${Math.max(0,Math.ceil((deadline-Date.now())/1000))}s`);
  let nextProgress=0;
  await until(()=>{
    const state=sql(`SELECT state FROM ux_order WHERE request_id='${purchases[2].id}'`);
    if(Date.now()>nextProgress){console.log(`EXPIRY status=${state} remaining=${Math.max(0,Math.ceil((deadline-Date.now())/1000))}s`);nextProgress=Date.now()+30000;}
    return state==='EXPIRED';
  },Math.max(30000,deadline-Date.now()+30000),'durable delayed close at original five-minute deadline');
  assert.equal(Number(sql(`SELECT available FROM ux_activity WHERE id=${activity}`)),29);
  await until(async()=>{const r=await ok(`/v2/admin/fixtures/${activity}/reservation`,{admin:true});return r.remaining===29;},30000,'Redis stock restore exactly once');
  const lateChannel=`late_${randomUUID()}`;
  await ok(`/v2/account/payments/${timeoutPayment.id}/sandbox-receipt`,{session:c,method:'POST',body:{channelId:lateChannel,amountCents:timeoutPayment.amount_cents}});
  await until(async()=>((await ok(`/v2/account/payments/${timeoutPayment.id}`,{session:c})).state==='REFUNDED'),30000,'late-payment refund');
  assert.equal(Number(sql(`SELECT COUNT(*) FROM ux_sandbox_refund WHERE channel_id='${lateChannel}'`)),1);
  await pendingZero();await verify();await mode('SHARDED');
  const timeoutHistory=await ok('/v2/account/order-history',{session:c});assert.equal(timeoutHistory.items[0].state,'EXPIRED');
  passed('unchanged five-minute delayed close, one stock restoration, late-payment refund and sharded EXPIRED visibility',{confirmDeadline:new Date(deadline).toISOString()});
  await mode('LOCAL');
  const status=await ok('/v2/admin/status',{admin:true});assert.equal(status.invariants.stockViolations,0);assert.equal(status.invariants.stateViolations,0);
  const pipeline=await ok('/v2/admin/pipeline',{admin:true});
  result.final={readMode:'LOCAL',invariants:status.invariants,history:await ok('/v2/admin/order-sharding',{admin:true}),queues:pipeline.queues};
  const loggedOut=await ok('/v2/auth/logout',{session:sessions.at(-1),method:'POST'},204);
  assert.equal((await request('/v2/account/order-history',{session:sessions.at(-1)})).status,401);
  passed('session logout invalidation and final inventory/order invariants');
  result.completedAt=new Date().toISOString();result.passed=true;save();console.log('ALL CHECKS PASSED');
} catch(error) {
  result.passed=false;result.failure={message:error.message,at:new Date().toISOString()};save();
  console.error(error);
  for(const action of ['start-history','start-rocketmq','start-redis','start-backend'])try{service(action);}catch{}
  try{await mode('LOCAL');}catch{}
  process.exitCode=1;
}
