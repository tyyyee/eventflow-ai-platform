import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
const backend=fileURLToPath(new URL('..',import.meta.url));
const project=path.resolve(backend,'..');
const evidence=path.join(project,'docs/verification/2026-10-27-sharding');
const data=JSON.parse(fs.readFileSync(path.join(evidence,'benchmark.json'),'utf8'));
assert.equal(data.rounds.length,36,'All 36 rounds must be present.');
const groups=new Map();
for(const row of data.rounds) {
  const samplePath=path.resolve(backend,row.sampleFile);
  assert.ok(samplePath.startsWith(path.join(project,'.local')+path.sep),'Samples must stay inside this project.');
  const values=fs.readFileSync(samplePath,'utf8').trim().split(/\r?\n/).slice(1).map(Number).sort((a,b)=>a-b);
  assert.equal(values.length,row.completed);
  assert.equal(values[Math.ceil(values.length*.95)-1]/1e6,row.p95Ms);
  assert.ok(Math.abs(values.reduce((a,b)=>a+b,0)/values.length/1e6-row.meanMs)<1e-7);
  assert.equal(row.failures,0);
  assert.ok(Math.abs(row.completed/row.elapsedSeconds-row.operationsPerSecond)<1e-7);
  const key=[row.rows,row.workload,row.mode].join('/');
  if(!groups.has(key))groups.set(key,[]);
  groups.get(key).push(row);
}
const median=values=>values.slice().sort((a,b)=>a-b)[Math.floor(values.length/2)];
const summary=[...groups.entries()].map(([key,rows])=>{
  assert.equal(rows.length,3);
  return {rows:rows[0].rows,workload:rows[0].workload,mode:rows[0].mode,
    operationsPerSecondMedian:median(rows.map(r=>r.operationsPerSecond)),p95MsMedian:median(rows.map(r=>r.p95Ms)),
    operationsPerSecondMin:Math.min(...rows.map(r=>r.operationsPerSecond)),
    operationsPerSecondMax:Math.max(...rows.map(r=>r.operationsPerSecond)),
    completed:rows.reduce((n,r)=>n+r.completed,0),failures:0};
});
const output={scope:data.scope,rounds:36,rawSampleRecalculation:'passed',groups:summary};
fs.writeFileSync(path.join(evidence,'benchmark-summary.json'),JSON.stringify(output,null,2)+'\n');
let markdown='| 合成订单数 | 负载 | 布局 | 操作/秒（三轮中位数） | P95 ms（三轮中位数） |\n|---|---|---|---:|---:|\n';
for(const row of summary)markdown+=`| ${row.rows.toLocaleString('en-US')} | ${row.workload} | ${row.mode} | ${row.operationsPerSecondMedian.toFixed(1)} | ${row.p95MsMedian.toFixed(3)} |\n`;
fs.writeFileSync(path.join(evidence,'benchmark-table.md'),markdown);
console.log(JSON.stringify({rounds:36,samples:data.rounds.reduce((n,r)=>n+r.completed,0),recalculation:'passed',summary:path.join(evidence,'benchmark-summary.json')}));
