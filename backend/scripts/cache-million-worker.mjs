import {once} from 'node:events';
import http from 'node:http';
import {performance} from 'node:perf_hooks';
const [workerData]=await once(process,'message');

const {worker,workers,concurrency,seconds,mode,shops,distribution,seed,cookies,baseId,ports}=workerData;
const agent=new http.Agent({keepAlive:true,maxSockets:concurrency,maxFreeSockets:concurrency});
const chunks=[];let chunk=new Float64Array(16384),filled=0,total=0,successes=0,hotRequests=0;
const statuses={},errors={},visited=new Uint8Array(shops);
function sample(value){if(filled===chunk.length){chunks.push(chunk.buffer);chunk=new Float64Array(16384);filled=0;}chunk[filled++]=value;total++;}
function mix(n){let x=(n+seed)>>>0;x=Math.imul(x^(x>>>16),0x7feb352d);x=Math.imul(x^(x>>>15),0x846ca68b);return (x^(x>>>16))>>>0;}
const description=id=>`Synthetic campus venue ${id}; no real merchant data; `.padEnd(512,'x');
function get(port,path,cookie){return new Promise(resolve=>{
    let finished=false;const done=result=>{if(!finished){finished=true;resolve(result);}};
    const req=http.get({host:'127.0.0.1',port,path,agent,headers:{Cookie:cookie,Accept:'application/json'}},res=>{
        const body=[];let bytes=0;res.on('data',value=>{bytes+=value.length;if(bytes>65536){req.destroy();done({status:res.statusCode,error:'BODY_TOO_LARGE'});}else body.push(value);});
        res.on('end',()=>{try{done({status:res.statusCode,body:JSON.parse(Buffer.concat(body).toString('utf8'))});}catch{done({status:res.statusCode,error:'INVALID_JSON'});}});
        res.on('error',error=>done({status:0,error:error.code??'RESPONSE_ERROR'}));
    });
    const timeout=setTimeout(()=>req.destroy(new Error('CLIENT_TIMEOUT')),5000);
    req.on('close',()=>clearTimeout(timeout));req.on('error',error=>done({status:0,error:error.code??error.message}));
});}
await new Promise(resolve=>setTimeout(resolve,Math.max(0,workerData.startAt-Date.now())));
let sequence=0;const started=performance.now(),startedEpoch=performance.timeOrigin+started,eluBefore=performance.eventLoopUtilization(),cpuBefore=process.cpuUsage();
await Promise.all(Array.from({length:concurrency},async()=>{
    while(performance.now()-started<seconds*1000){
        const local=sequence++,index=local*workers+worker,hash=mix(index);
        const hot=Math.max(1,Math.floor(shops*.2));
        const isHot=distribution==='hot80'&&index%5!==4;
        const offset=distribution==='hot80'?(isHot?hash%hot:hot+hash%(shops-hot)):hash%shops;
        if(isHot)hotRequests++;visited[offset]=1;
        const id=baseId+offset,port=ports[(worker+local)%ports.length];
        const path=mode==='optimized'?`/v2/shops/${id}`:`/v2/benchmark/ttl/${id}`;
        const sent=performance.now();const result=await get(port,path,cookies[index%cookies.length]);
        const latency=performance.now()-sent;
        const valid=result.status===200&&result.body?.id===id&&result.body?.name===`Mock Campus Venue ${id}`&&result.body?.description===description(id)&&result.body?.revision===1;
        statuses[result.status]=(statuses[result.status]??0)+1;
        if(valid){successes++;sample(latency);}else{const reason=result.body?.error??result.error??'INVALID_BODY';errors[reason]=(errors[reason]??0)+1;sample(-Math.max(Number.EPSILON,latency));}
    }
}));
const elapsedSeconds=(performance.now()-started)/1000;
if(filled)chunks.push(chunk.buffer.slice(0,filled*8));
agent.destroy();
process.send({worker,total,successes,statuses,errors,elapsedSeconds,startedEpoch,finishedEpoch:startedEpoch+elapsedSeconds*1000,eventLoopUtilization:performance.eventLoopUtilization(eluBefore).utilization,cpuMicros:process.cpuUsage(cpuBefore),hotRequests,visited:visited.buffer,chunks},()=>process.disconnect());
