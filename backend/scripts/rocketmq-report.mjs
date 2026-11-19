import {readdirSync,readFileSync,writeFileSync,mkdirSync} from 'node:fs';
const label=process.argv[2]??'regression';
if(!/^[a-z-]+$/.test(label))throw new Error('Report label must use lowercase letters and hyphens');
const directory=new URL('../target/surefire-reports/',import.meta.url);
const suites=[];
for(const file of readdirSync(directory).filter(f=>/^TEST-.*\.xml$/.test(f))){
  const xml=readFileSync(new URL(file,directory),'utf8');
  const head=xml.match(/<testsuite\b([^>]*)>/s)?.[1];if(!head)continue;
  const attributes=Object.fromEntries([...head.matchAll(/\b(name|tests|errors|skipped|failures|time)="([^"]*)"/g)].map(m=>[m[1],m[1]==='name'?m[2]:Number(m[2])]));
  if(label==='core'&&!attributes.name.split('.').at(-1).startsWith('Core32'))continue;
  suites.push(attributes);
}
const totals={tests:0,errors:0,failures:0,skipped:0};for(const suite of suites)for(const key of Object.keys(totals))totals[key]+=suite[key];
totals.passed=totals.tests-totals.errors-totals.failures-totals.skipped;
const target=new URL('../../docs/verification/2026-10-29-rocketmq/',import.meta.url);mkdirSync(target,{recursive:true});
writeFileSync(new URL(label+'-summary.json',target),JSON.stringify({recordedAt:new Date().toISOString(),scope:label,totals,suites},null,2)+'\n');
console.log(JSON.stringify({scope:label,...totals}));
