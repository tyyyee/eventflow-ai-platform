import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,readdirSync,statSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const project=fileURLToPath(new URL('../..',import.meta.url));
const folder=path.join(project,'docs/verification/2026-10-27-full-integration');
const result=JSON.parse(readFileSync(path.join(folder,'result.json')));
assert.equal(result.passed,true);assert.equal(result.benchmark.length,6);
const verified=[];
for(const round of result.benchmark) {
  const file=path.join(project,`.local/integration/http-${round.mode}-${round.round}.csv`);
  const raw=readFileSync(file);const samples=raw.toString().trim().split(/\r?\n/).slice(1).map(Number).sort((a,b)=>a-b);
  assert.equal(samples.length,round.operations);assert.ok(samples.every(x=>Number.isFinite(x)&&x>=0));
  const p95=samples[Math.ceil(samples.length*.95)-1];const qps=samples.length*1000/round.elapsedMs;
  assert.equal(p95,round.p95Ms);assert.equal(qps,round.qps);assert.equal(round.errors,0);assert.equal(round.mismatches,0);
  verified.push({mode:round.mode,round:round.round,operations:samples.length,p95Ms:p95,qps,rawSha256:createHash('sha256').update(raw).digest('hex')});
}
const median=values=>values.sort((a,b)=>a-b)[Math.floor(values.length/2)];
const summary={verified:true,operations:verified.reduce((sum,row)=>sum+row.operations,0),errors:0,mismatches:0,modes:['LOCAL','SHARDED'].map(mode=>({mode,medianQps:median(verified.filter(x=>x.mode===mode).map(x=>x.qps)),medianP95Ms:median(verified.filter(x=>x.mode===mode).map(x=>x.p95Ms))})),rounds:verified};
writeFileSync(path.join(folder,'http-summary.json'),JSON.stringify(summary,null,2)+'\n');
const lines=['# 登录后历史订单 HTTP 实测','','8 个工作线程；17 个真实注册会话；每次请求后等待 25ms；每模式三轮，每轮 10 秒。LOCAL / SHARDED 顺序交替。每个响应核对完整列表、状态、总数和用户隔离。数据为本轮少量真实 HTTP 建单记录，不能解释为百万数据接口或最大吞吐。','','| 模式 | 轮次 | 请求数 | 完成请求/秒 | P95（ms） |','|---|---:|---:|---:|---:|',...verified.map(r=>`| ${r.mode} | ${r.round} | ${r.operations} | ${r.qps.toFixed(1)} | ${r.p95Ms.toFixed(2)} |`),'',`共 ${summary.operations} 次请求，HTTP 错误 0，响应核对差异 0。原始 CSV 的样本数、P95、请求/秒已由独立脚本复算；摘要保存 SHA-256。原始样本留在忽略目录 .local/integration。`,'','请求/秒包含刻意等待，不是饱和压测结果；P95 包含 HTTP、Cookie 数据库会话鉴权、入口模式读取、SQL、JSON 及客户端响应核对。使用共享 Windows 主机，不能据此判断线上容量。',''];
writeFileSync(path.join(folder,'http-table.md'),lines.join('\n'));
console.log(JSON.stringify(summary.modes));

const environment=JSON.parse(readFileSync(path.join(project,'.local/integration/regression-environment.json')));
const buildLog=readFileSync(path.join(project,'backend/integration-regression.log'),'utf8');
assert.ok(buildLog.includes('BUILD SUCCESS'),'Run the complete regression successfully before producing the final report.');
const reports=path.join(project,'backend/target/surefire-reports');
const classes=[];
for(const file of readdirSync(reports).filter(x=>x.startsWith('TEST-')&&x.endsWith('.xml'))) {
  const full=path.join(reports,file);if(statSync(full).mtimeMs<Date.parse(environment.startedAt))continue;
  const header=readFileSync(full,'utf8').match(/<testsuite\s[^>]*>/)?.[0];assert.ok(header);
  const attribute=name=>header.match(new RegExp(`\\b${name}="([^"]*)"`))?.[1];
  classes.push({name:attribute('name'),tests:Number(attribute('tests')),failures:Number(attribute('failures')),errors:Number(attribute('errors')),skipped:Number(attribute('skipped'))});
}
assert.ok(classes.length>0);
const tests={environment,buildSuccess:true,total:0,passed:0,failures:0,errors:0,skipped:0,classes};
for(const item of classes){tests.total+=item.tests;tests.failures+=item.failures;tests.errors+=item.errors;tests.skipped+=item.skipped;}
tests.passed=tests.total-tests.failures-tests.errors-tests.skipped;
assert.equal(tests.failures,0);assert.equal(tests.errors,0);
for(const name of ['CustomerHttpTest','MiddlewareTest','ReliabilityIntegrationTest','AuthSecretConfigurationTest']) {
  const item=classes.find(x=>x.name.endsWith('.'+name));assert.ok(item);assert.equal(item.skipped,0);
}
writeFileSync(path.join(folder,'test-summary.json'),JSON.stringify(tests,null,2)+'\n');
console.log(`Tests: ${tests.passed} passed, ${tests.skipped} skipped, ${tests.failures+tests.errors} failed.`);
