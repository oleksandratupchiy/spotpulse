// Newman runner: owned localhost backend only; evidence is an allowlist, never a raw Newman export.
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const { spawn } = require('node:child_process');
const { createHash } = require('node:crypto');
const origin = 'http://localhost:18091';
const root = path.resolve(__dirname, '../..');
const output = path.join(root, 'spotpulse-backend/target/qa1');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));

function validateCollection(collection) {
  if (collection.variable.find(v => v.key === 'baseUrl')?.value !== origin) throw new Error('Only the fixed disposable QA1 origin is allowed');
  function visit(items) {
    for (const item of items) {
      if (item.item) visit(item.item);
      else {
        const url = typeof item.request.url === 'string' ? item.request.url : item.request.url.raw;
        if (!url.startsWith('{{baseUrl}}/api/') && !url.startsWith('{{baseUrl}}/__e2e/')
          && url !== '{{baseUrl}}/health' && url !== '{{baseUrl}}{{nextLink}}') throw new Error('Unsafe collection request URL');
        if (item.request.header?.some(h => /^(cookie|authorization|x-csrf-token)$/i.test(h.key))) throw new Error('Persisted authentication state is forbidden');
      }
    }
  }
  visit(collection.item);
}
function evidence(summary, durationMs, httpRequests, hash, primaryResponses = new Map()) {
  const statistics = summary.run.stats;
  // Newman appends the same mutable per-item object for scripted HTTP requests too.
  const executions = [...new Map(summary.run.executions.map(e => [e.cursor?.ref || e, e])).values()];
  return {
    assignment: 'QA1', timestamp: new Date().toISOString(), collectionSha256: hash,
    runtime: 'owned disposable loopback backend', iterations: statistics.iterations.total,
    collectionRequests: statistics.items?.total ?? executions.length, observedHttpRequests: httpRequests,
    assertions: statistics.assertions.total, passedAssertions: statistics.assertions.total - statistics.assertions.failed,
    failedAssertions: statistics.assertions.failed, failures: summary.run.failures.length, durationMs: Math.round(durationMs),
    requests: executions.map(e => ({ name: e.item.name, iteration: (e.cursor?.iteration || 0) + 1,
      method: primaryResponses.get(e.cursor?.ref)?.method || e.request.method,
      status: primaryResponses.get(e.cursor?.ref)?.status ?? e.response?.code ?? null,
      assertions: (e.assertions || []).map(a => ({ name: a.assertion, passed: !a.error })) })),
  };
}
function html(report) {
  const escape = text => String(text).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  return `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>SpotPulse QA1 evidence</title><style>body{font:16px system-ui;max-width:1100px;margin:2rem auto;padding:1rem}table{border-collapse:collapse;width:100%}td,th{border:1px solid #aaa;padding:.4rem;text-align:left}</style><h1>SpotPulse QA1 — Newman API verification</h1><p>${escape(report.timestamp)} · SHA256 ${escape(report.collectionSha256)}</p><p>${report.collectionRequests} collection requests; ${report.observedHttpRequests} observed HTTP requests including setup; ${report.passedAssertions}/${report.assertions} assertions passed; ${report.failures} failures; ${(report.durationMs/1000).toFixed(2)} seconds. ${report.iterations} iterations.</p><p>Disposable local fixtures only. This report contains no headers, cookies, tokens, response bodies or authentication state.</p><table><thead><tr><th>Request</th><th>Method</th><th>Status</th><th>Assertions</th></tr></thead><tbody>${report.requests.map(r=>`<tr><td>${escape(r.name)}</td><td>${escape(r.method)}</td><td>${r.status}</td><td>${r.assertions.filter(a=>a.passed).length}/${r.assertions.length}</td></tr>`).join('')}</tbody></table></html>`;
}
async function main() {
  if (process.argv.length !== 2) throw new Error('No target URL or extra arguments are accepted');
  // Requests to localhost must never be routed through an inherited network proxy.
  for (const key of Object.keys(process.env)) if (/^(http_proxy|https_proxy|all_proxy|node_use_env_proxy)$/i.test(key)) delete process.env[key];
  for (const key of Object.keys(process.env)) if (/^(SPRING_|SPOTPULSE_|GOOGLE_|CLOUDINARY_|TELEGRAM_|VITE_)/i.test(key)
    || ['DATABASE_URL','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'].includes(key.toUpperCase())) delete process.env[key];
  process.env.NO_PROXY = process.env.no_proxy = 'localhost,127.0.0.1,::1';
  const collectionPath = path.join(root, 'postman_collection.json');
  const text = fs.readFileSync(collectionPath, 'utf8');
  const collection = JSON.parse(text); validateCollection(collection);
  fs.mkdirSync(output, { recursive: true });
  const active = path.join(output, 'active'), stop = path.join(output, 'stop-requested');
  if (fs.existsSync(active)) throw new Error('An isolated QA1 runtime is already active; do not reuse it');
  for (const name of ['summary.json','report.html']) { const file=path.join(output,name); if (fs.existsSync(file)) fs.unlinkSync(file); }
  await new Promise((resolve, reject) => { const probe = net.createServer(); probe.once('error', reject); probe.listen(18091, '127.0.0.1', () => probe.close(resolve)); });
  const environment = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(SPRING_|SPOTPULSE_|GOOGLE_|CLOUDINARY_|TELEGRAM_|VITE_)/i.test(key)
    && !['DATABASE_URL','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'].includes(key.toUpperCase())));
  Object.assign(environment, { SPOTPULSE_E2E_PORT: '18091', SPOTPULSE_E2E_SUITE: 'qa1' });
  const backend = spawn('python', [path.join(root, 'spotpulse-backend/tools/run_e2e.py')], { env: environment, cwd: __dirname, stdio: 'ignore', windowsHide: true });
  let spawnError; backend.on('error', error => { spawnError = error; });
  const shutdown = () => { if (fs.existsSync(active)) fs.writeFileSync(stop, 'stop owned QA1 runtime'); };
  let interrupted = false;
  const interrupt = () => { interrupted = true; shutdown(); process.exitCode = 1; };
  process.on('SIGINT', interrupt); process.on('SIGTERM', interrupt);
  let report;
  try {
    const deadline = performance.now() + 300_000;
    let ready = false;
    while (performance.now() < deadline) {
      if (interrupted) throw new Error('QA1 verification interrupted');
      if (spawnError || backend.exitCode !== null) throw new Error('Isolated backend failed to start; inspect target/qa1/backend.log');
      try { const r = await fetch(origin + '/health', { redirect: 'error', signal: AbortSignal.timeout(1500) }); ready = r.ok && (await r.json()).status === 'UP'; } catch {}
      if (ready) break;
      await pause(250);
    }
    if (!ready) throw new Error('Timed out waiting for isolated health');
    let httpRequests = 0;
    const lastResponses = new Map(), primaryResponses = new Map();
    const started = performance.now();
    const summary = await new Promise((resolve, reject) => {
      const run = require('newman').run({ collection, iterationCount: 2, reporters: [],
        ignoreRedirects: true, timeoutRequest: 10_000, timeoutScript: 30_000 }, (error, summary) => error ? reject(error) : resolve(summary));
      run.on('request', (_error, args) => {
        httpRequests++;
        lastResponses.set(args.cursor.ref, { method: args.request.method, status: args.response?.code ?? null });
      });
      // Freeze the main response before post-response scripts can send more HTTP requests (e.g. logout verification).
      run.on('beforeTest', (_error, args) => primaryResponses.set(args.cursor.ref, lastResponses.get(args.cursor.ref)));
    });
    if (primaryResponses.size !== summary.run.stats.items.total) throw new Error('Cannot reliably identify primary collection responses');
    report = evidence(summary, performance.now() - started, httpRequests, createHash('sha256').update(text).digest('hex'), primaryResponses);
    if (report.failures) {
      for (const failure of summary.run.failures) console.error('FAIL:', failure.source?.name || 'script', failure.error?.test || failure.error?.name || 'request');
      process.exitCode = 1;
    }
  } finally {
    shutdown();
    const deadline = performance.now() + 35_000;
    while ((fs.existsSync(active) || (backend.exitCode === null && !spawnError)) && performance.now() < deadline) { shutdown(); await pause(100); }
    if (fs.existsSync(active) || (backend.exitCode === null && !spawnError)) throw new Error('Owned QA1 backend cleanup did not complete');
    process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt);
  }
  report.cleanupCompleted = true;
  fs.writeFileSync(path.join(output, 'summary.json'), JSON.stringify(report, null, 2) + '\n');
  fs.writeFileSync(path.join(output, 'report.html'), html(report));
  console.log(JSON.stringify({ ...report, requests: undefined }, null, 2));
}
module.exports = { validateCollection, evidence, html };
if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
