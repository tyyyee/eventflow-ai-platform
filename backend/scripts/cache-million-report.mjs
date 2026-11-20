import {readFileSync,writeFileSync,mkdirSync,copyFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const project=fileURLToPath(new URL('../..',import.meta.url));
const dir=process.argv[2]??readFileSync(path.join(project,'.local/cache-million/latest-evidence.txt'),'utf8').trim();
const read=file=>JSON.parse(readFileSync(path.join(dir,file),'utf8'));
const plan=read('plan.json'),results=read('results.json'),completion=read('completion.json'),dataset=read('dataset.json'),env=read('environment.json'),probes=read('functional-probes.json');
const expected=plan.cases.reduce((n,c)=>n+c.rounds*2,0);
if(results.length!==expected||completion.runs!==expected)throw new Error('Incomplete planned measurements');
const median=values=>{const sorted=[...values].sort((a,b)=>a-b),n=sorted.length;return n%2?sorted[n>>1]:(sorted[n/2-1]+sorted[n/2])/2;};
const quantile=(sorted,p)=>sorted[Math.ceil(sorted.length*p)-1];
for(const row of results){
 let successes=0,failures=0,total=0;const values=new Float64Array(row.successes);let sum=0;
 for(const raw of row.raw){const bytes=readFileSync(path.join(dir,raw.file));if(createHash('sha256').update(bytes).digest('hex')!==raw.sha256)throw new Error('Raw checksum mismatch');
  if(bytes.length%8)throw new Error('Invalid raw byte count');let workerSuccess=0;
  for(let offset=0;offset<bytes.length;offset+=8){const value=bytes.readDoubleLE(offset);if(!Number.isFinite(value)||value===0)throw new Error('Invalid latency');total++;if(value>0){values[successes++]=value;sum+=value;workerSuccess++;}else failures++;}
  if(workerSuccess!==raw.successes||bytes.length/8!==raw.requests)throw new Error('Worker counts mismatch');
 }
 values.sort();if(total!==row.requests||successes!==row.successes||failures!==total-successes)throw new Error('Run counts mismatch');
 for(const [key,p] of [['successP50Ms',.5],['successP95Ms',.95],['successP99Ms',.99]])if(Math.abs(quantile(values,p)-row[key])>1e-8)throw new Error('Latency percentile mismatch');
 if(Math.abs(successes/row.elapsedSeconds-row.successfulQps)>1e-8)throw new Error('QPS mismatch');
 row.successMeanMs=sum/successes;
}
const summaries=plan.cases.map(config=>{
 const modes=Object.fromEntries(plan.modes.map(mode=>{const rows=results.filter(r=>r.case===config.id&&r.mode===mode);if(rows.length!==config.rounds)throw new Error('Missing arm');
 return [mode,{rounds:rows.length,requests:rows.reduce((n,r)=>n+r.requests,0),successes:rows.reduce((n,r)=>n+r.successes,0),errors:rows.reduce((n,r)=>n+r.requests-r.successes,0),medianQps:median(rows.map(r=>r.successfulQps)),minQps:Math.min(...rows.map(r=>r.successfulQps)),maxQps:Math.max(...rows.map(r=>r.successfulQps)),medianP95Ms:median(rows.map(r=>r.successP95Ms)),medianP99Ms:median(rows.map(r=>r.successP99Ms)),medianMeanMs:median(rows.map(r=>r.successMeanMs)),merchantDatabaseReadAttempts:rows.reduce((n,r)=>n+r.databaseReads,0),localHits:rows.reduce((n,r)=>n+r.localHits,0),maxClientElu:Math.max(...rows.flatMap(r=>r.raw.map(w=>w.eventLoopUtilization))),meanHostCpuBusyRatio:rows.reduce((n,r)=>n+r.hostCpuBusyRatio,0)/rows.length,distinctShops:rows.map(r=>r.distinctRequestedShops),perRound:rows.map(r=>({round:r.round,qps:r.successfulQps,p95Ms:r.successP95Ms,meanMs:r.successMeanMs,errors:r.requests-r.successes}))}];}));
 const paired=Array.from({length:config.rounds},(_,i)=>{const a=results.find(r=>r.case===config.id&&r.round===i+1&&r.mode==='ttl'),b=results.find(r=>r.case===config.id&&r.round===i+1&&r.mode==='optimized');return {round:i+1,qpsChangePercent:(b.successfulQps/a.successfulQps-1)*100,p95ReductionPercent:(1-b.successP95Ms/a.successP95Ms)*100};});
 return {config,modes,qpsChangePercent:(modes.optimized.medianQps/modes.ttl.medianQps-1)*100,p95ReductionPercent:(1-modes.optimized.medianP95Ms/modes.ttl.medianP95Ms)*100,paired};
});
const report={completedAt:completion.completedAt,evidenceDirectory:dir,verification:'Every raw latency checksum, request count, success count, QPS and success percentile independently recomputed',dataset,environment:env,probes,aggregation:'Median of per-round QPS/P95; not a pooled percentile and not the best round',requests:completion.requests,cases:summaries};
const out=process.argv[3]??dir;mkdirSync(out,{recursive:true});writeFileSync(path.join(out,'summary.json'),JSON.stringify(report,null,2)+'\n');
const digest=file=>createHash('sha256').update(readFileSync(path.join(project,file))).digest('hex');
const sources=['backend/src/main/java/com/hmdp/upgrade/ShopCache.java','backend/src/main/java/com/hmdp/upgrade/ShopBloom.java','backend/src/main/java/com/hmdp/upgrade/Transactions.java','backend/scripts/cache-million-local.ps1','backend/scripts/cache-million-benchmark.mjs','backend/scripts/cache-million-worker.mjs','backend/scripts/cache-million-redis.mjs','backend/scripts/cache-million-report.mjs'];
const buildLog=readFileSync(path.join(project,'backend/cache-million-build.log'),'utf8');
const totals=[...buildLog.matchAll(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/g)].at(-1);
const manifest={recordedAt:new Date().toISOString(),testedJarSha256:env.jarSha256,currentJarSha256:digest('backend/target/life-choice-backend-1.0.0.jar'),sources:Object.fromEntries(sources.map(file=>[file,digest(file)])),regressionBuild:totals?{total:+totals[1],failures:+totals[2],errors:+totals[3],skipped:+totals[4],buildSuccess:buildLog.includes('BUILD SUCCESS')}:null};
if(manifest.currentJarSha256!==manifest.testedJarSha256)throw new Error('Current jar differs from measured jar');
writeFileSync(path.join(out,'build-manifest.json'),JSON.stringify(manifest,null,2)+'\n');
const f=(x,n=2)=>Number(x).toFixed(n),primary=summaries.find(s=>s.config.primary)??summaries[0];
const lines=['# 百万条模拟商家数据：多级缓存对照压测','',`完成时间：${completion.completedAt}。共 ${completion.requests.toLocaleString('en-US')} 次计量请求，${expected} 个计量阶段。`,'','## 测试范围','',
'- 独立 MySQL、Redis，100 万条纯模拟商家/场地基础信息，每条描述 512 字节。数据总量与热点访问范围分别记录。',
'- 同一台电脑、同一应用版本、两个 384 MiB JVM、相同连接池和鉴权流程。256 个模拟 Cookie 会话，请求实际执行数据库会话鉴权。',
'- 对照：Redis 单级缓存（15 秒 TTL）；优化：现有 Caffeine + Redis 整套缓存策略，含逻辑过期、互斥重建和 Bloom Filter。无法将全部差异单独归因于 Caffeine。',
'- 每轮先预热，再计量；按 AB、BA 顺序交替，使用相同随机种子。表中指标取多轮中位数。P95 指 95% 的成功请求在该时间内完成。',
'- 这是本机闭环 HTTP 查询压测，包含鉴权与网络开销；不是生产容量、最大 QPS、完整商家详情页或订单 TPS。',
'- 模拟的是业务数据和访问分布，QPS/响应时间来自真实请求。原始逐请求延迟已保留，并逐文件校验摘要、数量和分位数。','',
'## 全部场景','',
'| 场景 | 并发 | 每组轮数 | 单轮计量 | 成功 QPS：前 → 后 | P95 ms：前 → 后 | QPS 变化 | P95 降幅 | 错误数：前 / 后 |',
'| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |'];
for(const s of summaries){const a=s.modes.ttl,b=s.modes.optimized;lines.push(`| ${s.config.distribution==='hot80'?`${s.config.shops} 个热点（80% 请求访问前 20%）`:`${s.config.shops} 个商家均匀访问`} | ${s.config.concurrency} | ${s.config.rounds} | ${s.config.seconds}s | ${f(a.medianQps,0)} → ${f(b.medianQps,0)} | ${f(a.medianP95Ms)} → ${f(b.medianP95Ms)} | ${f(s.qpsChangePercent,1)}% | ${f(s.p95ReductionPercent,1)}% | ${a.errors} / ${b.errors} |`);}
lines.push('','## 波动与压测资源','',
'| 场景 | QPS 范围：Redis 单级 | QPS 范围：多级缓存 | 客户端单线程最高活跃率：前 / 后 | 整机平均 CPU 忙碌率：前 / 后 |',
'| --- | ---: | ---: | ---: | ---: |');
for(const s of summaries){const a=s.modes.ttl,b=s.modes.optimized;lines.push(`| ${s.config.id} | ${f(a.minQps,0)}–${f(a.maxQps,0)} | ${f(b.minQps,0)}–${f(b.maxQps,0)} | ${f(a.maxClientElu*100,1)}% / ${f(b.maxClientElu*100,1)}% | ${f(a.meanHostCpuBusyRatio*100,1)}% / ${f(b.meanHostCpuBusyRatio*100,1)}% |`);}
if(summaries.some(s=>Object.values(s.modes).some(m=>m.maxClientElu>=.9)))lines.push('','部分阶段客户端线程活跃率达到 90% 以上，且客户端与服务端在同一台电脑上争用资源。这些数字只能表示当前本机配置下观察到的吞吐与延迟，不能当成服务端最大承载能力。');
lines.push('','20 个热点场景属于初轮出现反向变化后追加的诊断场景，不能用它替换预先确定的 1000 个热点主场景。');
lines.push('','## 主场景与简历口径','',`预先确定的主场景：百万条模拟数据，${primary.config.shops} 个活跃商家，80% 请求集中在最热的 20%，${primary.config.concurrency} 并发，两实例，${primary.config.rounds} 轮对照。`,
`成功 QPS 从 ${f(primary.modes.ttl.medianQps,0)} 到 ${f(primary.modes.optimized.medianQps,0)}；P95 从 ${f(primary.modes.ttl.medianP95Ms)}ms 到 ${f(primary.modes.optimized.medianP95Ms)}ms。`,
'','简历中应保留“百万条模拟数据、32 并发热点查询压测”等测试条件。数据量大不等于每条数据均被高频访问，不能写成百万热点或生产流量。若分散访问场景退化，也需在面试中说明本地缓存的收益依赖命中率。','',
'## 每轮明细','', '| 场景 | 轮次 | 方案 | 成功 QPS | 平均 ms | P95 ms | 错误数 | 商家数据库读取尝试 | 本地命中 |','| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |');
for(const r of results)lines.push(`| ${r.case} | ${r.round} | ${r.mode} | ${f(r.successfulQps,0)} | ${f(r.successMeanMs)} | ${f(r.successP95Ms)} | ${r.requests-r.successes} | ${r.databaseReads} | ${r.localHits} |`);
lines.push('','数据库读取计数仅统计商家信息查询尝试，包含异步刷新；不包含每次请求的会话鉴权 SQL。优化组逻辑刷新周期为 5 秒，对照 TTL 为 15 秒，数据库读取次数应按实际记录解释，不能直接当作本地缓存的独立效果。','',
`独立功能探测：2000 个不同的无效 ID 中，Bloom Filter 直接拦截 ${probes.bloomBlocked} 个；HTTP 状态统计 ${JSON.stringify(probes.statuses)}。这部分不计入上面的 QPS 与延迟。`,'',
'初始化修正：百万条预检查复现了 Bloom 扫描超过 5 秒交易事务限制的问题。现为该批量维护单独设置 120 秒超时并增加失败日志；普通交易事务仍为 5 秒。两组使用同一修正版本，缓存查询策略和 TTL 未调整。该初始化仍持有原有索引保护锁，大数据重建需安排维护窗口。','',
`测试启动时依次确认两个实例的 Bloom 就绪，避免同时重建时争用保护锁。Node 24.15 的线程与独立进程客户端均曾原生退出；正式结果全部来自 ${env.node} 的独立进程客户端，控制连接使用原生 HTTP。中断样本未合并，未确定旧运行时原生退出的底层原因。`,
`构建回归：${manifest.regressionBuild?.total} 个测试，失败 ${manifest.regressionBuild?.failures}，错误 ${manifest.regressionBuild?.errors}，条件跳过 ${manifest.regressionBuild?.skipped}；测试 JAR 与报告生成时的 JAR 校验一致。`,'',
'## 复现与证据','',
'运行 `backend/scripts/cache-million-local.ps1` 执行完整矩阵；加 `-Smoke` 仅做预检查。运行 `node backend/scripts/cache-million-report.mjs <证据目录> <报告目录>` 重新校验和汇总。',
`本次 Node 可执行文件：${plan.nodeExecutable}。可用脚本的 -NodeExecutable 参数指定客户端运行时。`,
`原始证据目录：${dir}`,
'本目录 JSON 包含计划、环境、数据规模、逐轮结果及原始文件 SHA256。Cookie、数据库密码未写入报告。测试结束后保留模拟数据库文件，关闭测试实例。','');
writeFileSync(path.join(out,'README.md'),lines.join('\n'));
if(path.resolve(out)!==path.resolve(dir))for(const file of ['plan.json','dataset.json','environment.json','results.json','completion.json','functional-probes.json'])copyFileSync(path.join(dir,file),path.join(out,file));
console.log(JSON.stringify({output:out,requests:report.requests,primary:{case:primary.config.id,before:primary.modes.ttl,after:primary.modes.optimized,qpsChangePercent:primary.qpsChangePercent,p95ReductionPercent:primary.p95ReductionPercent}},null,2));
