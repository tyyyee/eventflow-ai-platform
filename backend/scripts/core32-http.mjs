import assert from 'node:assert/strict';
import {createHmac,randomBytes,randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync,mkdirSync,copyFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const project=fileURLToPath(new URL('../..',import.meta.url));
const local=path.join(project,'.local/core32'),evidence=path.join(project,'docs/verification/2026-10-29-rocketmq');
const secrets=JSON.parse(readFileSync(path.join(project,'.local/integration/credentials.json')));
const base='http://127.0.0.1:38193',sleep=ms=>new Promise(r=>setTimeout(r,ms));
const result={startedAt:new Date().toISOString(),base,checks:[],capacityClaim:false};mkdirSync(evidence,{recursive:true});
function save(){writeFileSync(path.join(evidence,'http-result.json'),JSON.stringify(result,null,2)+'\n');}
function pass(name,details={}){result.checks.push({name,passed:true,...details});save();console.log('PASS '+name);}
function sql(query,node='source') {return execFileSync('F:/software/Mysql/mysql-8.0.31-winx64/bin/mysql.exe',['--defaults-extra-file=root.cnf','--batch','--raw','--skip-column-names',node==='source'?'qupiao_core_catalog':'qupiao_core32'],{cwd:path.join(project,node==='source'?'.local/sharding/source':'.local/core32/'+node),input:query,encoding:'utf8',timeout:15000}).trim();}
function service(script,action,...args){execFileSync('pwsh',['-NoProfile','-File',path.join(project,'backend/scripts/'+script),action,...args],{cwd:project,stdio:'ignore',timeout:180000});}
function restart(){service('core32-local.ps1','halt');service('core32-local.ps1','run','-EnableFaults');}
function token(){const p=`9000001:admin:${Math.floor(Date.now()/1000)+7200}`;return p+'.'+createHmac('sha256',secrets.authSecret).update(p).digest('base64url');}
async function req(route,{session,admin=false,method='GET',body,csrf=true}={}){
  const headers={'Content-Type':'application/json'};if(admin)headers.Authorization='Bearer '+token();if(session){headers.Cookie=session.cookie;if(csrf)headers['X-CSRF-Token']=session.csrfToken;}
  const r=await fetch(base+route,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});return{status:r.status,data:await r.json().catch(()=>null),cookie:r.headers.get('set-cookie')?.split(';')[0]};
}
async function ok(route,options={},expected=200){const r=await req(route,options);assert.equal(r.status,expected,route+': '+JSON.stringify(r.data));return r.data;}
async function until(f,timeout=90000,label='condition'){const end=Date.now()+timeout;while(Date.now()<end){const r=await f();if(r)return r;await sleep(400);}throw Error('Timed out: '+label);}
async function fixture(id,stock=20){await ok(`/v2/admin/fixtures/${id}?stock=${stock}`,{admin:true,method:'POST'});}
async function buy(session,activity,key='core_'+randomUUID()){const r=await ok('/v2/requests',{session,method:'POST',body:{requestId:key,activityId:activity}},202);return{id:r.id,key,activity};}
async function order(session,id){return until(async()=>{const r=await req(`/v2/requests/${id}/order`,{session});return r.status===200?r.data:false;},90000,'RocketMQ order');}
async function cancel(session,id){return ok(`/v2/requests/${id}/cancel`,{session,method:'POST'});}
async function drained(){return until(async()=>{const s=await ok('/v2/admin/core32',{admin:true});return Object.values(s.queues).every(x=>x===0)?s:false;},90000,'durable queues drain');}
async function crash(point,trigger){writeFileSync(path.join(local,'faults',point),'1');try{await trigger();}catch{}await until(()=>{try{const pid=Number(readFileSync(path.join(local,'backend.pid')));process.kill(pid,0);return false;}catch{return true;}},30000,'fault stops JVM');copyFileSync(path.join(local,'backend.stdout.log'),path.join(local,point+'.log'));restart();}
try{
  await ok('/actuator/health/readiness');const password=randomBytes(20).toString('hex'),run=Date.now().toString(36),sessions=[];
  assert.equal((await req('/v2/account/order-history')).status,401);
  for(let i=0;i<24;i++){const username=`cx_${run}_${i}`;const r=await req('/v2/auth/register',{method:'POST',body:{username,password,displayName:'分片联调'+i}});assert.equal(r.status,201,JSON.stringify(r.data));sessions.push({...r.data,cookie:r.cookie,username});}
  writeFileSync(path.join(local,'http-sessions.json'),JSON.stringify(sessions));
  const login=await req('/v2/auth/login',{method:'POST',body:{username:sessions[0].username,password}});assert.equal(login.status,200);sessions[0]={...login.data,cookie:login.cookie,username:sessions[0].username};
  const a=sessions[0],b=sessions[1],c=sessions[2];assert.equal((await req('/v2/admin/core32',{session:a})).status,403);
  assert.equal((await req('/v2/requests',{session:a,method:'POST',csrf:false,body:{activityId:1,requestId:'missing_csrf'}})).status,403);
  const nodes=[...new Set(sessions.map(s=>Number(BigInt(s.user.id)%32n)/8|0))];assert.equal(nodes.length,4,'HTTP-created users should exercise four actual databases');
  pass('real registration/login, Snowflake string IDs, four database routes, auth and CSRF',{users:sessions.length,nodes});
  const activity=Date.now();await fixture(activity,24);const purchases=[];
  for(const s of sessions){const p=await buy(s,activity);p.order=await order(s,p.id);assert.equal(p.order.state,'PENDING_CONFIRM');purchases.push(p);}
  const timeoutPayment=await ok('/v2/account/payments',{session:c,method:'POST',body:{requestId:purchases[2].id}});
  const pay=await ok('/v2/account/payments',{session:a,method:'POST',body:{requestId:purchases[0].id}}),channel='cx_'+randomUUID();
  for(let i=0;i<2;i++)assert.equal((await ok(`/v2/account/payments/${pay.id}/sandbox-receipt`,{session:a,method:'POST',body:{channelId:channel,amountCents:pay.amount_cents}})).outcome,'APPLIED');
  for(let i=1;i<sessions.length;i++)if(i!==2)await cancel(sessions[i],purchases[i].id);
  assert.equal((await ok('/v2/requests',{session:a,method:'POST',body:{requestId:purchases[0].key,activityId:activity}})).id,purchases[0].id);
  assert.equal((await req(`/v2/requests/${purchases[0].id}/order`,{session:b})).status,404);
  assert.equal(Number(sql(`SELECT COUNT(*) FROM ux_order WHERE activity_id=${activity}`)),0);
  for(let i=0;i<sessions.length;i++){const h=await ok('/v2/account/order-history',{session:sessions[i]});assert.equal(h.consistency,'primary');assert.equal(h.items[0].id,purchases[i].order.id);}
  await until(async()=>{const r=await req('/v2/admin/core32/orders/'+purchases[0].order.id,{admin:true});return r.status===200&&r.data.id===purchases[0].order.id;});
  pass('RocketMQ primary-shard orders, binding joins, source has no primary orders, payment replay, owner isolation and route directory',{orders:24,activity});
  // Waitlist remains an activity-wide central queue, promotion creates a primary-shard request.
  const wa=activity+1;await fixture(wa,1);const first=await buy(a,wa);await order(a,first.id);const wait=await ok('/v2/waitlists',{session:b,method:'POST',body:{activityId:wa}});await cancel(a,first.id);
  const promoted=await until(async()=>{const list=await ok('/v2/account/waitlists',{session:b});return list.items.find(x=>x.id===wait.id&&x.state==='PROMOTED'&&x.requestId);});await order(b,promoted.requestId);await cancel(b,promoted.requestId);
  pass('central waitlist promotes into user-sharded primary order without legacy foreign-key conflict');
  const ca=activity+2;await fixture(ca,3);let interrupted;
  await crash('core-after-stock-hold',async()=>{interrupted=await buy(a,ca);await sleep(6000);});
  const recovered=await order(a,interrupted.id);assert.equal(recovered.state,'PENDING_CONFIRM');assert.equal(Number(sql(`SELECT COUNT(*) FROM ux_core_stock_hold WHERE activity_id=${ca}`)),1);await cancel(a,interrupted.id);
  pass('actual JVM crash after source stock commit before shard commit recovers one grant and one order');
  const pa=activity+3;await fixture(pa,3);const p=await buy(b,pa);await order(b,p.id);const payment=await ok('/v2/account/payments',{session:b,method:'POST',body:{requestId:p.id}}),ch='cx_'+randomUUID();
  await crash('core-channel-before-receipt',()=>ok(`/v2/account/payments/${payment.id}/sandbox-receipt`,{session:b,method:'POST',body:{channelId:ch,amountCents:payment.amount_cents}}));
  await until(async()=>(await order(b,p.id)).state==='CONFIRMED');assert.equal(Number(sql(`SELECT COUNT(*) FROM ux_core_channel_claim WHERE channel_id='${ch}' AND processed_at IS NOT NULL`)),1);
  pass('actual JVM crash after channel claim recovers payment without repeated callback');
  const ka=activity+4;await fixture(ka,3);service('integration-local.ps1','stop-rocketmq');const queued=await buy(a,ka);await sleep(2500);assert.equal((await ok('/v2/requests/'+queued.id,{session:a})).state,'ACCEPTED');service('integration-local.ps1','start-rocketmq');await order(a,queued.id);await cancel(a,queued.id);
  pass('RocketMQ outage preserves accepted request and Outbox retries after restart');
  // Existing sessions are SQL-backed. Reads remain available while new reservations fail closed.
  const ra=activity+5;await fixture(ra,3);service('integration-local.ps1','stop-redis');
  try{assert.equal((await req('/v2/requests',{session:b,method:'POST',body:{requestId:'redis_'+randomUUID(),activityId:ra}})).status,503);assert.equal((await req('/v2/account/order-history',{session:a})).status,200);}finally{service('integration-local.ps1','start-redis');}
  await until(async()=>{try{return (await req(`/v2/admin/fixtures/${ra}/reservation`,{admin:true})).status===200;}catch{return false;}});const afterRedis=await buy(b,ra);await order(b,afterRedis.id);await cancel(b,afterRedis.id);
  pass('Redis outage rejects new reservation and preserves primary SQL history, then resumes');
  const da=activity+6;await fixture(da,3);const affected=sessions.find(s=>BigInt(s.user.id)%32n<8n),healthy=sessions.find(s=>BigInt(s.user.id)%32n>=24n);
  execFileSync('F:/software/Mysql/mysql-8.0.31-winx64/bin/mysqladmin.exe',['--defaults-extra-file=root.cnf','shutdown'],{cwd:path.join(local,'db0'),stdio:'ignore',timeout:20000});
  let isolated;
  try{assert.equal((await req('/v2/account/order-history',{session:affected})).status,503);assert.equal((await req('/v2/account/order-history',{session:healthy})).status,200);isolated=await buy(healthy,da);}finally{service('core32-local.ps1','start');}
  await order(healthy,isolated.id);await cancel(healthy,isolated.id);await ok('/v2/account/order-history',{session:affected});
  pass('one primary database outage affects its users, another prepared shard accepts orders, restoration resumes reads');
  // Natural five-minute close, not a manually shortened deadline.
  await until(async()=>(await order(c,purchases[2].id)).state==='EXPIRED',360000,'natural five-minute close');const late='cx_late_'+randomUUID();
  await ok(`/v2/account/payments/${timeoutPayment.id}/sandbox-receipt`,{session:c,method:'POST',body:{channelId:late,amountCents:timeoutPayment.amount_cents}});
  await until(async()=>{const p=await ok('/v2/account/payments/'+timeoutPayment.id,{session:c});return p.receipts.some(r=>r.channel_id===late&&r.outcome==='REFUNDED');});
  assert.equal(Number(sql(`SELECT COUNT(*) FROM ux_core_provider_refund WHERE channel_id='${late}'`)),1);
  pass('natural five-minute close and late payment refunded exactly once');
  const status=await drained();assert.equal(status.topology.misplaced,0);assert.equal(status.invariants.stockViolations,0);assert.equal(status.invariants.stateViolations,0);
  result.final=status;result.distribution=[];
  for(let db=0;db<4;db++)for(let table=0;table<8;table++)result.distribution.push({db,table,orders:Number(sql(`SELECT COUNT(*) FROM cx_order_${table}`,'db'+db))});
  const references=await Promise.all(sessions.map(session=>ok('/v2/account/order-history',{session}))),samples=[];let errors=0,mismatches=0;const began=performance.now();
  await Promise.all(Array.from({length:8},(_,worker)=>(async()=>{for(let n=0;n<30;n++){const index=(worker+n*8)%sessions.length,start=performance.now();try{const r=await req('/v2/account/order-history',{session:sessions[index]});if(r.status!==200)errors++;else if(JSON.stringify(r.data)!==JSON.stringify(references[index]))mismatches++;}catch{errors++;}samples.push(performance.now()-start);await sleep(30);}})()));
  samples.sort((a,b)=>a-b);result.httpSample={workers:8,requests:samples.length,pacingMs:30,elapsedMs:performance.now()-began,p95Ms:samples[Math.ceil(samples.length*.95)-1],errors,mismatches,scope:'small functional dataset, no capacity or before/after improvement claim'};assert.equal(errors,0);assert.equal(mismatches,0);
  result.connections=['source','db0','db1','db2','db3'].map(node=>({node,snapshot:sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Max_used_connections');",node),serverMaxConnections:Number(sql('SELECT @@max_connections',node))}));
  pass('eight concurrent HTTP readers return consistent per-user primary history',{httpSample:result.httpSample});
  assert.equal(Number(sql('SELECT COUNT(*) FROM ux_order')),0);result.completedAt=new Date().toISOString();pass('final queues drained, inventory and request/order invariants zero, no misplaced rows, all four primary databases written');
}catch(error){result.failedAt=new Date().toISOString();result.error=error.stack;save();console.error(error);process.exitCode=1;}
