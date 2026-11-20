import {spawn,fork,execFileSync} from 'node:child_process';
import {mkdirSync,openSync,closeSync,readFileSync,writeFileSync} from 'node:fs';
import {createHash,createHmac,randomBytes} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import net from 'node:net';
import {performance} from 'node:perf_hooks';
import {redisCommand,infoValues} from './cache-million-redis.mjs';

const backend=fileURLToPath(new URL('..',import.meta.url)),project=path.dirname(backend),state=path.join(project,'.local/cache-million');
const secrets=JSON.parse(readFileSync(path.join(state,'credentials.json')));
const smoke=process.argv.includes('--smoke'),baseId=1000000,shopRows=1000000,ports=[18193,18194];
const stamp=new Date().toISOString().replace(/\D/g,'').slice(0,14),dir=path.join(backend,'evidence',`cache-million-${stamp}-${smoke?'smoke':'matrix'}`);
mkdirSync(dir,{recursive:true});writeFileSync(path.join(state,'latest-evidence.txt'),dir);
const save=(file,data)=>writeFileSync(path.join(dir,file),JSON.stringify(data,null,2)+'\n');
let stage='prepare';
process.on('exit',code=>save('client-exit.json',{code,stage,recordedAt:new Date().toISOString()}));
process.on('uncaughtExceptionMonitor',error=>save('client-uncaught.json',{message:error.message,stage}));
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const mysql=process.env.CACHE_BENCH_MYSQL??'F:/software/Mysql/mysql-8.0.31-winx64/bin/mysql.exe';
function sql(query){return execFileSync(mysql,['--defaults-extra-file=bench.cnf','--batch','--raw','--skip-column-names','--default-character-set=utf8mb4','qupiao_cache_bench'],{cwd:state,input:query,encoding:'utf8',timeout:180000,windowsHide:true}).trim();}
function adminToken(){const payload=`900:admin:${Math.floor(Date.now()/1000)+14400}`;return 'Bearer '+payload+'.'+createHmac('sha256',secrets.authSecret).update(payload).digest('base64url');}
const controlAgent=new http.Agent({keepAlive:true,maxSockets:16});
function api(index,route,{method='GET',body,cookie}={}){return new Promise((resolve,reject)=>{
    const payload=body===undefined?undefined:JSON.stringify(body);
    const request=http.request({host:'127.0.0.1',port:ports[index],path:route,method,agent:controlAgent,headers:{...(cookie?{Cookie:cookie}:{Authorization:adminToken()}),'Content-Type':'application/json',...(payload?{'Content-Length':Buffer.byteLength(payload)}:{})}},response=>{
        const buffers=[];response.on('data',value=>buffers.push(value));response.on('error',reject);
        response.on('end',()=>{try{const text=Buffer.concat(buffers).toString('utf8');resolve({status:response.statusCode,data:text?JSON.parse(text):null});}catch(error){reject(error);}});
    });
    const timeout=setTimeout(()=>request.destroy(new Error('Control API timeout')),10000);
    request.on('close',()=>clearTimeout(timeout));request.on('error',reject);request.end(payload);
});}
function portOpen(port){return new Promise(resolve=>{const socket=net.createConnection({host:'127.0.0.1',port});let done=false;const finish=value=>{if(!done){done=true;socket.destroy();resolve(value);}};socket.setTimeout(500,()=>finish(false));socket.on('connect',()=>finish(true));socket.on('error',()=>finish(false));});}
async function checked(index,route,options){const result=await api(index,route,options);if(result.status!==200)throw new Error(`Unexpected response for ${route}: ${result.status}, ${result.data?.error??'no error code'}`);return result.data;}
const children=[];
async function stop(child){if(!child||child.exitCode!==null||child.signalCode!==null)return;const ended=new Promise(resolve=>child.once('exit',resolve));child.kill('SIGTERM');const timer=setTimeout(()=>child.kill('SIGKILL'),15000);await ended;clearTimeout(timer);}
async function launch(index){
    if(await portOpen(ports[index]))throw new Error(`Benchmark port ${ports[index]} is occupied`);
    const fd=openSync(path.join(dir,`server-${index}.log`),'a',0o600);
    const java=path.join(process.env.JAVA_HOME,'bin/java.exe');
    const child=spawn(java,['-Xms384m','-Xmx384m','-XX:+UseG1GC','-Dfile.encoding=UTF-8','-jar','target/life-choice-backend-1.0.0.jar',`--server.port=${ports[index]}`,'--upgrade.benchmark=true','--upgrade.jobs=false','--upgrade.demo-data=false','--upgrade.core32.enabled=false','--upgrade.history.replica-enabled=false','--upgrade.planning.mode=disabled','--upgrade.rocketmq.create-topics=false'],{cwd:backend,env:{...process.env,DATABASE_URL:'jdbc:mysql://127.0.0.1:33507/qupiao_cache_bench?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=5000',DATABASE_USERNAME:'cachebench',DATABASE_PASSWORD:secrets.dbPassword,REDIS_HOST:'127.0.0.1',REDIS_PORT:'36480',REDIS_PASSWORD:secrets.redisPassword,SPRING_DATA_REDIS_DATABASE:'0',SPRING_DATA_REDIS_CONNECT_TIMEOUT:'5s',SPRING_DATA_REDIS_TIMEOUT:'1s',APP_AUTH_SECRET:secrets.authSecret,ROCKETMQ_NAMESERVER:'127.0.0.1:39876'},stdio:['ignore',fd,fd],windowsHide:true});closeSync(fd);children.push(child);
    for(let n=0;n<240;n++){if(child.exitCode!==null)throw new Error(`Benchmark JVM ${index} exited; inspect its private log`);try{await checked(index,'/v2/admin/status');return child;}catch{}await sleep(250);}throw new Error(`Benchmark JVM ${index} startup timed out`);
}
function dataset(){
    const exists=Number(sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ux_shop'"));
    if(!exists)return null;
    return JSON.parse(sql("SELECT JSON_OBJECT('rows',COUNT(*),'minId',MIN(id),'maxId',MAX(id),'descriptionBytes',SUM(OCTET_LENGTH(description))) FROM ux_shop"));
}
async function prepare(){
    const initial=dataset();
    if(!initial){const bootstrap=await launch(0);await stop(bootstrap);}
    let data=initial??dataset();
    if(data.rows!==shopRows||data.minId!==baseId||data.maxId!==baseId+shopRows-1){
        if(data.rows>0&&!(data.minId>=baseId&&data.maxId<baseId+shopRows))throw new Error('Refusing to replace an unexpected dataset');
        sql('CREATE TABLE IF NOT EXISTS cache_bench_digits(n INT PRIMARY KEY); INSERT IGNORE INTO cache_bench_digits VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9);');
        for(let batch=0;batch<100;batch++){
            const start=baseId+batch*10000;
            sql(`INSERT IGNORE INTO ux_shop(id,name,description,revision) SELECT id,CONCAT('Mock Campus Venue ',id),RPAD(CONCAT('Synthetic campus venue ',id,'; no real merchant data; '),512,'x'),1 FROM (SELECT ${start}+a.n+10*b.n+100*c.n+1000*d.n AS id FROM cache_bench_digits a CROSS JOIN cache_bench_digits b CROSS JOIN cache_bench_digits c CROSS JOIN cache_bench_digits d) seed_rows`);
            if(batch%10===9)console.log(`Mock shops prepared: ${(batch+1)*10000}/${shopRows}`);
        }
        sql('ANALYZE TABLE ux_shop');data=dataset();
    }
    if(data.rows!==shopRows||data.descriptionBytes!==512*shopRows)throw new Error('Million-row dataset validation failed');
    const cookies=[],values=[];
    for(let n=0;n<256;n++){
        const id=9000000+n,token=randomBytes(32).toString('hex'),csrf=randomBytes(32).toString('hex');cookies.push('life_session='+token);
        values.push(`INSERT IGNORE INTO ux_account(id,username,display_name,password_hash,created_at) VALUES(${id},'cache_user_${n}','Mock user ${n}','session-fixture-only-no-password-login',CURRENT_TIMESTAMP(3));INSERT INTO ux_session(token_hash,user_id,csrf_token,created_at,expires_at) VALUES('${sha(token)}',${id},'${csrf}',CURRENT_TIMESTAMP(3),DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 8 HOUR));`);
    }
    sql(values.join('\n'));writeFileSync(path.join(state,'sessions.json'),JSON.stringify({cookies}),{mode:0o600});
    save('dataset.json',{...data,source:'deterministic synthetic campus merchant/venue basic information',descriptionBytesPerRow:512,users:256,auth:'SQL-seeded mock Cookie sessions; actual Accounts.authenticate and user limiter enabled; no Bearer load traffic',mysql:JSON.parse(sql("SELECT JSON_OBJECT('version',VERSION(),'port',@@port,'bufferPoolBytes',@@innodb_buffer_pool_size,'maxConnections',@@max_connections)")),storage:JSON.parse(sql("SELECT JSON_OBJECT('dataBytes',data_length,'indexBytes',index_length,'estimatedRows',table_rows) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ux_shop'"))});
    return cookies;
}
async function clearMode(mode){let cursor='0';do{const result=await redisCommand(secrets.redisPassword,'SCAN',cursor,'MATCH',mode==='ttl'?'ux:baseline:shop:*':'ux:shop:{*','COUNT','2000');cursor=result[0];for(let n=0;n<result[1].length;n+=500)await redisCommand(secrets.redisPassword,'DEL',...result[1].slice(n,n+500));}while(cursor!=='0');}
async function snapshot(){return {apps:await Promise.all(ports.map((_,i)=>checked(i,'/v2/admin/cache'))),runtime:await Promise.all(ports.map((_,i)=>checked(i,'/v2/admin/pipeline'))),redis:infoValues(await redisCommand(secrets.redisPassword,'INFO','all')),sql:sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Com_select','Threads_connected','Threads_running','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests')")};}
function hostCpu(){return os.cpus().map(cpu=>({...cpu.times}));}
async function load(config,mode,cookies,seconds,seed){
    stage=`${config.id}/${mode}/${seconds}s`;save('progress.json',{stage,startedAt:new Date().toISOString()});
    const workers=4,startAt=Date.now()+1500,cpu=process.cpuUsage(),cpuBefore=hostCpu(),freeMemoryBefore=os.freemem();
    const rows=await Promise.all(Array.from({length:workers},(_,worker)=>new Promise((resolve,reject)=>{
        const child=fork(new URL('./cache-million-worker.mjs',import.meta.url),[],{serialization:'advanced',stdio:['ignore','ignore','pipe','ipc'],windowsHide:true});
        let row,diagnostic='';child.stderr.on('data',data=>{diagnostic=(diagnostic+data.toString()).slice(-2000);});
        child.once('message',value=>{row=value;});child.once('error',reject);
        child.once('exit',code=>{if(code===0&&row)resolve(row);else reject(new Error(`Load process exited ${code}: ${diagnostic}`));});
        child.send({worker,workers,concurrency:config.concurrency/workers,seconds,mode,shops:config.shops,distribution:config.distribution,seed,cookies,baseId,ports,startAt});
    })));
    const cpuAfter=hostCpu();let total=0,idle=0;cpuBefore.forEach((before,i)=>{for(const key of Object.keys(before))total+=cpuAfter[i][key]-before[key];idle+=cpuAfter[i].idle-before.idle;});
    const clientCpuMicros=process.cpuUsage(cpu);for(const row of rows)for(const field of ['user','system'])clientCpuMicros[field]+=row.cpuMicros?.[field]??0;
    return {rows,elapsedSeconds:(Math.max(...rows.map(r=>r.finishedEpoch))-Math.min(...rows.map(r=>r.startedEpoch)))/1000,workerStartSpreadMs:Math.max(...rows.map(r=>r.startedEpoch))-Math.min(...rows.map(r=>r.startedEpoch)),clientCpuMicros,hostCpuBusyRatio:1-idle/total,freeMemoryBefore,freeMemoryAfter:os.freemem()};
}
const cases=smoke?[{id:'smoke-hot1000-c8',shops:1000,distribution:'hot80',concurrency:8,rounds:1,seconds:3,warmup:2}]:[
    {id:'hot1000-c32',shops:1000,distribution:'hot80',concurrency:32,rounds:3,seconds:30,warmup:10,primary:true},
    {id:'uniform10000-c32',shops:10000,distribution:'uniform',concurrency:32,rounds:3,seconds:30,warmup:10},
    {id:'hot1000-c8',shops:1000,distribution:'hot80',concurrency:8,rounds:2,seconds:30,warmup:10},
    {id:'hot1000-c64',shops:1000,distribution:'hot80',concurrency:64,rounds:2,seconds:30,warmup:10},
    {id:'diagnostic-hot20-c32',shops:20,distribution:'hot80',concurrency:32,rounds:2,seconds:30,warmup:10,diagnostic:'Added after an interrupted initial round showed a regression at 1000 active shops; secondary only, never replaces the primary case'}
];
const results=[];
try{
    save('plan.json',{recordedBeforeRuns:new Date().toISOString(),node:process.version,nodeExecutable:process.execPath,cases,modes:['ttl','optimized'],modeOrder:'AB then BA, alternating by round',shopRows,baseId,workers:4,loadDriver:'Four independent Node child processes; runs interrupted by native Windows Node 24.15 client crashes are excluded. Durations/rounds fixed before this complete run.',instances:2,heapPerInstanceMiB:384,sameHost:true,rawFormat:'little-endian Float64 milliseconds; positive=validated success, negative=failure; timing starts after worker barrier',scope:'Redis single TTL baseline vs existing whole multilevel-cache policy; steady-state authenticated basic merchant/venue query, not full detail page, order TPS or isolated Caffeine attribution'});
    const cookies=await prepare();console.log('Validated 1,000,000 synthetic shops; starting two benchmark JVMs.');
    // Rebuilds share an insertion guard. Start serially so the second scan cannot
    // exhaust a JDBC socket deadline while waiting for the first snapshot lock.
    for(let i=0;i<2;i++){
        await launch(i);let ready=false;
        for(let poll=0;poll<130;poll++){const cache=await checked(i,'/v2/admin/cache');save(`preflight-cache-${i}.json`,cache);if(cache.bloom.ready===1&&cache.bloom.entries===shopRows){ready=true;break;}await sleep(1000);}
        if(!ready)throw new Error(`Bloom snapshot ${i} is not ready for all million rows`);
    }
    const env=JSON.parse(readFileSync(path.join(state,'environment.json')));
    save('environment.json',{...env,node:process.version,platform:os.platform(),cpu:os.cpus()[0]?.model,cpuCount:os.cpus().length,memoryBytes:os.totalmem(),jarSha256:sha(readFileSync(path.join(backend,'target/life-choice-backend-1.0.0.jar'))),cacheSourceSha256:sha(readFileSync(path.join(backend,'src/main/java/com/hmdp/upgrade/ShopCache.java'))),sourceChanges:'Bloom batch rebuild uses an isolated 120s maintenance transaction after million-row preflight reproduced its former 5s transaction timeout; trading transactions remain 5s. ShopCache query policies unchanged. Both arms run the same rebuilt jar.',application:{instances:2,heapMiB:384,cacheSoftMs:5000,cacheHardMs:15000,localMaximumSize:10000,localBusinessLifetimeMs:1000,jdbcPoolMaxPerInstance:12},initial:await snapshot()});
    // Prime both code paths before the predeclared paired measurements.
    await load(cases[0],'ttl',cookies,smoke?1:10,123);await load(cases[0],'optimized',cookies,smoke?1:10,123);
    for(const config of cases)for(let round=1;round<=config.rounds;round++)for(const mode of round%2?['ttl','optimized']:['optimized','ttl']){
        await clearMode(mode);const seed=round*1234567;
        const warm=await load(config,mode,cookies,config.warmup,seed+1);if(warm.rows.every(r=>r.successes===0))throw new Error('All warmup requests failed');
        const before=await snapshot();const measured=await load(config,mode,cookies,config.seconds,seed);await sleep(500);const after=await snapshot();
        const count=measured.rows.reduce((n,r)=>n+r.total,0),success=measured.rows.reduce((n,r)=>n+r.successes,0),latencies=new Float64Array(success),visited=new Uint8Array(config.shops);let at=0;
        const statuses={},errors={},raw=[];
        for(const row of measured.rows){
            for(const [key,value] of Object.entries(row.statuses))statuses[key]=(statuses[key]??0)+value;
            for(const [key,value] of Object.entries(row.errors))errors[key]=(errors[key]??0)+value;
            new Uint8Array(row.visited).forEach((value,index)=>{visited[index]|=value;});
            const buffers=row.chunks.map(chunk=>Buffer.from(chunk));const bytes=Buffer.concat(buffers),name=`${config.id}-r${round}-${mode}-w${row.worker}.f64`;
            writeFileSync(path.join(dir,name),bytes);raw.push({file:name,sha256:sha(bytes),requests:row.total,successes:row.successes,eventLoopUtilization:row.eventLoopUtilization});
            for(const buffer of row.chunks)for(const value of new Float64Array(buffer))if(value>0)latencies[at++]=value;
        }
        if(at!==success)throw new Error('Raw latency counts disagree');latencies.sort();
        const delta=key=>after.apps.reduce((n,app,i)=>n+app.cache[key]-before.apps[i].cache[key],0);
        const result={case:config.id,round,mode,requests:count,successes:success,elapsedSeconds:measured.elapsedSeconds,successfulQps:success/measured.elapsedSeconds,errorRate:(count-success)/count,statuses,errors,successP50Ms:latencies[Math.ceil(success*.5)-1],successP95Ms:latencies[Math.ceil(success*.95)-1],successP99Ms:latencies[Math.ceil(success*.99)-1],databaseReads:delta('databaseReads'),localHits:delta('localHits'),redisHits:delta(mode==='ttl'?'ttlHits':'hits'),staleHits:delta('staleHits'),fallbacks:delta('fallbacks'),rebuildRejected:delta('rebuildRejected'),distinctRequestedShops:visited.reduce((n,x)=>n+x,0),observedHotFraction:measured.rows.reduce((n,r)=>n+r.hotRequests,0)/count,workerStartSpreadMs:measured.workerStartSpreadMs,clientCpuMicros:measured.clientCpuMicros,hostCpuBusyRatio:measured.hostCpuBusyRatio,freeMemoryBefore:measured.freeMemoryBefore,freeMemoryAfter:measured.freeMemoryAfter,raw,before,after};
        results.push(result);save('results.json',results);console.log(JSON.stringify({case:config.id,round,mode,qps:result.successfulQps,p95Ms:result.successP95Ms,errors:count-success,databaseReads:result.databaseReads,localHits:result.localHits,clientMaxElu:Math.max(...raw.map(r=>r.eventLoopUtilization))}));
    }
    // Correctness requests are excluded from throughput and latency measurements.
    const probeBefore=await snapshot(),negativeStatuses={};
    for(let start=0;start<2000;start+=16)await Promise.all(Array.from({length:Math.min(16,2000-start)},async(_,n)=>{
        const k=start+n,response=await api(k%2,`/v2/shops/${5000000+k}`,{cookie:cookies[k%cookies.length]});
        negativeStatuses[response.status]=(negativeStatuses[response.status]??0)+1;
    }));
    const probeAfter=await snapshot();
    const bloomBlocked=probeAfter.apps.reduce((n,app,i)=>n+app.bloom.blocked-probeBefore.apps[i].bloom.blocked,0);
    save('functional-probes.json',{invalidUniqueIds:2000,statuses:negativeStatuses,bloomBlocked,merchantDatabaseReadAttempts:probeAfter.apps.reduce((n,app,i)=>n+app.cache.databaseReads-probeBefore.apps[i].cache.databaseReads,0),before:probeBefore.apps,after:probeAfter.apps,excludedFromMeasurements:true});
    if(negativeStatuses[404]!==2000||bloomBlocked<1900)throw new Error('Invalid-ID Bloom probe failed');
    save('completion.json',{completedAt:new Date().toISOString(),runs:results.length,requests:results.reduce((n,r)=>n+r.requests,0)});console.log('Evidence: '+dir);
}catch(error){save('failure.json',{message:error.message,completedRuns:results.length});console.error(error.message);process.exitCode=1;}
finally{controlAgent.destroy();for(const child of children.reverse())await stop(child);}
