import { existsSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

export default async function teardown() {
  // Stop through the supervising Python process before Playwright kills its webServer tree on Windows.
  const directory = resolve('../spotpulse-backend/target/qa2');
  const active = resolve(directory, 'active');
  if (!existsSync(active)) return;
  writeFileSync(resolve(directory, 'stop-requested'), 'stop owned QA2 runtime');
  const deadline = performance.now() + 35_000;
  while (existsSync(active) && performance.now() < deadline) await new Promise(resolve => setTimeout(resolve, 100));
  if (existsSync(active)) throw new Error('QA2 runtime cleanup did not complete');
}
