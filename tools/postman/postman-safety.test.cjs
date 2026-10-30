const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { validateCollection, evidence, html } = require('./verify-postman.cjs');
const collection = JSON.parse(fs.readFileSync(require('node:path').resolve(__dirname, '../../postman_collection.json')));
test('current collection is restricted to the owned QA1 target', () => assert.doesNotThrow(() => validateCollection(collection)));
for (const base of ['https://spotpulse.onrender.com','http://localhost:8080','http://localhost:18091@evil.example.test']) {
  test('rejects unsafe target ' + base, () => { const c=structuredClone(collection); c.variable[0].value=base; assert.throws(()=>validateCollection(c)); });
}
test('rejects absolute request URLs and persisted cookies', () => {
  const c=structuredClone(collection); c.item[0].item[0].request.url='https://spotpulse.onrender.com/api/spaces'; assert.throws(()=>validateCollection(c));
  c.item[0].item[0].request.url='{{baseUrl}}/health'; c.item[0].item[0].request.header=[{key:'Cookie',value:'synthetic-test'}]; assert.throws(()=>validateCollection(c));
});
test('evidence drops bodies, headers, cookies and raw failures', () => {
  const secret='DO-NOT-EXPORT-SYNTHETIC-SESSION';
  const summary={run:{stats:{iterations:{total:2},assertions:{total:2,failed:1}},failures:[{secret}],executions:[{
    item:{name:'Safe test'},request:{method:'GET',headers:{Cookie:secret}},response:{code:403,body:secret},assertions:[{assertion:'Status',error:{message:secret}}]}]}};
  const report=evidence(summary,123,4,'hash'); assert.equal(report.failedAssertions,1); assert.equal(report.failures,1);
  assert.ok(!JSON.stringify(report).includes(secret)); assert.ok(!html(report).includes(secret));
});
test('HTML escapes request and assertion-derived text', () => {
  const report={timestamp:'now',collectionSha256:'hash',collectionRequests:1,observedHttpRequests:1,passedAssertions:0,assertions:0,failures:0,durationMs:1,iterations:1,
    requests:[{name:'<script>bad</script>',method:'GET',status:200,assertions:[]}]};
  assert.ok(!html(report).includes('<script>')); assert.ok(html(report).includes('&lt;script&gt;'));
});
test('deduplicates Newman helper aliases and freezes the primary logout response', () => {
  const shared={cursor:{ref:'one',iteration:0},item:{name:'Logout'},request:{method:'GET'},response:{code:200},assertions:[]};
  const summary={run:{stats:{items:{total:1},iterations:{total:1},assertions:{total:0,failed:0}},failures:[],executions:[shared,shared]}};
  const report=evidence(summary,1,2,'hash',new Map([['one',{method:'POST',status:204}]]));
  assert.equal(report.collectionRequests,1); assert.equal(report.requests.length,1); assert.equal(report.requests[0].status,204); assert.equal(report.requests[0].method,'POST');
});
test('actual Newman runtime skips an unsafe target before any HTTP request', async () => {
  const c={info:collection.info,variable:[{key:'baseUrl',value:'http://127.0.0.1:1'}],
    event:collection.event.filter(e=>e.listen==='prerequest'),item:[{name:'Guard probe',request:{method:'GET',url:'{{baseUrl}}/health'}}]};
  let requests=0;
  const result=await new Promise((resolve,reject)=>{
    const run=require('newman').run({collection:c,reporters:[],timeoutRequest:1000},(error,summary)=>error?reject(error):resolve(summary));
    run.on('request',()=>requests++);
  });
  assert.equal(requests,0);
  assert.equal(result.run.stats.assertions.failed,1); // expected guard rejection, not an API-suite failure
});
