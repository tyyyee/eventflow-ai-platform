import { randomUUID } from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

// Run only against the dedicated verification environment. No credentials are written to the report.
const base=process.env.LOCAL_LIFE_API || 'http://127.0.0.1:8293/v2'
if(process.env.LOCAL_LIFE_VERIFY!=='true')throw new Error('Explicit LOCAL_LIFE_VERIFY=true is required for test writes')
const seconds=Number(process.env.LOAD_SECONDS||60),concurrency=20
const registration=await fetch(base+'/auth/register',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({username:'perf_'+randomUUID().replaceAll('-','').slice(0,12),password:randomUUID(),displayName:'性能验收账号'})})
if(!registration.ok)throw new Error('Register failed: '+registration.status)
const cookie=registration.headers.get('set-cookie').split(';')[0],session=await registration.json()
const headers={'Cookie':cookie,'X-CSRF-Token':session.csrfToken,'X-Account-ID':session.user.id}
async function phase(name,paths,authenticated=false){
  const samples=[],statuses={};let bytes=0,index=0
  const started=performance.now(),end=started+seconds*1000,pace=authenticated?667:0
  await Promise.all(Array.from({length:concurrency},async()=>{while(performance.now()<end){const item=paths[index++%paths.length],began=performance.now();let status='network-error';try{const r=await fetch(base+item.path,{method:item.method||'GET',headers:authenticated?headers:{},signal:AbortSignal.timeout(5000)});bytes+=(await r.arrayBuffer()).byteLength;status=String(r.status)}catch{}statuses[status]=(statuses[status]||0)+1;samples.push(performance.now()-began);if(pace)await new Promise(resolve=>setTimeout(resolve,Math.max(0,pace-(performance.now()-began))))}}))
  const elapsed=(performance.now()-started)/1000; samples.sort((a,b)=>a-b)
  const quantile=q=>Math.round(samples[Math.min(samples.length-1,Math.ceil(samples.length*q)-1)]*100)/100
  return {name,concurrency,perWorkerIntervalMs:pace,seconds:Math.round(elapsed*100)/100,requests:samples.length,statuses,p50Ms:quantile(.5),p95Ms:quantile(.95),p99Ms:quantile(.99),successfulRequestsPerSecond:Math.round(Object.entries(statuses).filter(([s])=>s.startsWith('2')).reduce((sum,[,n])=>sum+n,0)/elapsed*100)/100,responseBytes:bytes,paths}
}
const results=[]
if(process.env.LOAD_WRITES_ONLY==='true'){const old=JSON.parse(await fs.readFile(path.resolve(import.meta.dirname,'../../docs/verification/2026-11-19-local-life/load-results.json'),'utf8'));results.push(old.results[0])}else results.push(await phase('public shop list and detail',[{path:'/local/shops'},{path:'/local/shops/32001'}]))
results.push(await phase('authenticated idempotent sign and favorite writes',[{path:'/account/signs/today',method:'PUT'},{path:'/account/favorites/SHOP/32001',method:'PUT'}],true))
const report={date:new Date().toISOString(),environment:{os:os.type()+' '+os.release(),cpu:os.cpus()[0]?.model,logicalCpus:os.cpus().length,memoryGiB:Math.round(os.totalmem()/2**30),node:process.version,database:'isolated qulife_local_verify',transport:'HTTP directly to Spring Boot; same machine',sqlQueryCount:'not measured'},limitations:'Small fictional dataset; sequential phases; no external model, no production capacity claim; repeated writes test idempotent paths, not distinct-user coupon issuance.',results}
await fs.writeFile(path.resolve(import.meta.dirname,'../../docs/verification/2026-11-19-local-life/load-results.json'),JSON.stringify(report,null,2)+'\n')
console.log(JSON.stringify(results,null,2))
