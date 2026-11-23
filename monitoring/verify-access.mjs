import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { loadRegistry } from './generate.mjs';

// Read-only preflight. Granting a new invoker/audience is a separate approved operation.
const approvals = JSON.parse(await readFile(new URL('./access-approvals.json', import.meta.url)));
for (const service of (await loadRegistry()).services.filter(s => s.access === 'cloud-run-iam')) {
  const args = ['--project', approvals.project, '--region', approvals.region, '--format=json'];
  const describe = JSON.parse(execFileSync('gcloud', ['run', 'services', 'describe', service.id, ...args], { encoding: 'utf8' }));
  const audiences = JSON.parse(describe.metadata?.annotations?.['run.googleapis.com/custom-audiences'] || '[]');
  if (!audiences.includes(approvals.audience)) throw new Error(`Metrics audience not authorized: ${service.id}`);
  const policy = JSON.parse(execFileSync('gcloud', ['run', 'services', 'get-iam-policy', service.id, ...args], { encoding: 'utf8' }));
  if (!policy.bindings?.some(b => !b.condition && b.role === 'roles/run.invoker' && b.members?.includes(`serviceAccount:${approvals.collector}`))) throw new Error(`Collector invoker permission not authorized: ${service.id}`);
  console.log(`Existing private metrics permission verified: ${service.id}`);
}
