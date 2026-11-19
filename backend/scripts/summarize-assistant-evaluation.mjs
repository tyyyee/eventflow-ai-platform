import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const folder=new URL('../../docs/verification/2026-11-09-assistant-evaluation/',import.meta.url);
const read=async name=>JSON.parse((await readFile(new URL(name,folder),'utf8')).replace(/^\uFEFF/,''));
const [local,before,after,heldout]=await Promise.all(['deterministic-after-fixes.json','live-initial.json','live-after-fixes.json','live-heldout.json'].map(read));
assert.equal(local.casesRecorded,40);assert.equal(local.passed,40);assert.equal(local.providerCalls,0);
assert.equal(before.casesRecorded,20);assert.equal(before.passed,17);
assert.equal(after.casesRecorded,20);assert.equal(after.passed,20);
assert.equal(heldout.casesRecorded,8);assert.equal(heldout.passed,8);
assert.equal(before.caseSourceSha256,after.caseSourceSha256,'The original business assertions must be identical before and after fixes');
assert.equal(after.runtimeSourceSha256,heldout.runtimeSourceSha256,'Held-out checks must use unchanged runtime prompt');
assert.deepEqual(before.cases.map(c=>c.id),after.cases.map(c=>c.id));
assert(![before,after,heldout].some(r=>r.usageUnknown||r.notRunBudget));
const paid=[before,after,heldout];
const summary={
  createdAt:new Date().toISOString(),suiteVersion:after.suiteVersion,
  deterministic:{cases:40,passed:40,report:'deterministic-after-fixes.json'},
  liveRegressionBefore:{cases:20,passed:17,failed:3,report:'live-initial.json'},
  liveRegressionAfter:{cases:20,passed:20,failed:0,report:'live-after-fixes.json'},
  liveHeldOut:{cases:8,passed:8,failed:0,report:'live-heldout.json',note:'Newly authored after fixes, not used to tune the prompt; synthetic fixtures and a small hand-written sample.'},
  assertionsUnchangedAcrossRegression:true,
  heldOutRuntimeUnchanged:true,
  heldOutSourceSha256:createHash('sha256').update(await readFile(new URL('../src/test/java/com/hmdp/upgrade/assistant/AssistantHeldOutEvaluationTest.java',import.meta.url))).digest('hex'),
  fixes:[{caseId:'A06',problem:'No-match category was silently cleared',fix:'Pin first search criteria per turn and require explicit user changes in a new turn'},
    {caseId:'A09',problem:'Order details overwrote earlier order list',fix:'Merge same-turn order facts by request ID'},
    {caseId:'B09',problem:'Supplementary rules hid replacement activity cards',fix:'Keep same-turn activity cards alongside rules; report oldest visible evidence time'}],
  providerCalls:paid.reduce((n,r)=>n+r.providerCalls,0),
  reportedTotalTokens:paid.reduce((n,r)=>n+r.reportedTotalTokens,0),
  conservativeReportedTokenCostYuan:Number(paid.reduce((n,r)=>n+r.conservativeReportedTokenCostYuan,0).toFixed(6)),
  costNote:'All reported tokens multiplied by 8 CNY/million. Not an actual invoice; includes before, after and held-out batches, excludes earlier five-turn smoke.',
  rawBalanceObservations:await Promise.all(['live-initial-balance.json','live-after-fixes-balance.json','live-heldout-balance.json'].map(read)),
  limits:['No population-wide accuracy claim','Regression cases participated in debugging','No real payment or production data','Live model tests use isolated H2 fixtures; separate MySQL/Redis/MQ integration covers real middleware','No LLM-as-Judge paid calls']
};
const verified=[];
for(const name of ['AssistantEvaluationTest','AssistantBusinessTest','AssistantHttpTest','DeepSeekAssistantModelTest','AssistantIntegrationTest']){
  const xml=await readFile(new URL(`../target/surefire-reports/TEST-com.hmdp.upgrade.assistant.${name}.xml`,import.meta.url),'utf8');
  const tag=xml.match(/<testsuite\b[^>]*>/)[0];
  const count=key=>Number(tag.match(new RegExp(`\\b${key}="(\\d+)"`))[1]);
  const entry={name,tests:count('tests'),failures:count('failures'),errors:count('errors'),skipped:count('skipped')};
  assert.equal(entry.failures+entry.errors+entry.skipped,0);verified.push(entry);
}
summary.junitVerification=verified;
summary.localRegressionTotal=verified.filter(t=>t.name!=='AssistantIntegrationTest').reduce((n,t)=>n+t.tests,0);
assert.equal(summary.localRegressionTotal,71);
const browser=await read('browser/results.json');assert(browser.passed);assert.equal(browser.checks.length,9);
summary.browser={passed:true,scenarios:9,mode:browser.mode,report:'browser/results.json'};
summary.finalBalanceObservation=await read('final-balance.json');
await writeFile(new URL('summary.json',folder),JSON.stringify(summary,null,2));
console.log(JSON.stringify({local:'40/40',before:'17/20',after:'20/20',heldout:'8/8',providerCalls:summary.providerCalls,totalTokens:summary.reportedTotalTokens,conservativeCostYuan:summary.conservativeReportedTokenCostYuan},null,2));
