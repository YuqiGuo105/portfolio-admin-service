import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';

const project = process.env.GCP_PROJECT_ID || 'portfolio-notify-prod';
const region = process.env.GCP_REGION || 'us-central1';
const tag = process.env.MEDIA_IMAGE_TAG;
assert.match(tag || '', /^[a-zA-Z0-9._-]+$/);
const mode = process.argv[2];
assert.ok(['admin', 'worker', 'enable', 'scheduler'].includes(mode));
const base = `https://${region}-run.googleapis.com/apis/serving.knative.dev/v1/namespaces/${project}/services`;
const run = (args) => execFileSync('gcloud', [...args, '--project', project], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
async function request(url, method = 'GET', body) {
  const token = run(['auth', 'print-access-token']);
  const response = await fetch(url, { method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new Error(`Cloud API ${response.status}: ${problem.error?.status || 'request failed'}`);
  }
  return response.json();
}
const envValue = (name, value) => ({ name, value });
const secret = (name, key = name) => ({ name, valueFrom: { secretKeyRef: { name: key, key: 'latest' } } });
const mediaEnv = [
  envValue('MEDIA_S3_ENDPOINT', 'https://storage.googleapis.com'), envValue('MEDIA_S3_REGION', 'auto'),
  envValue('MEDIA_ORIGINALS_BUCKET', `${project}-media-originals`), envValue('MEDIA_PUBLIC_BUCKET', `${project}-media-public`),
  secret('MEDIA_S3_ACCESS_KEY'), secret('MEDIA_S3_SECRET_KEY'),
];
function mergeEnv(container, additions) {
  container.env = [...new Map([...(container.env || []), ...additions].map(item => [item.name, item])).values()];
}
async function save(service, exists) {
  const name = service.metadata.name;
  const metadata = { name, namespace: project, annotations: service.metadata.annotations || {} };
  if (exists) metadata.resourceVersion = service.metadata.resourceVersion;
  delete service.spec.template.metadata.name;
  const saved = await request(exists ? `${base}/${name}` : base, exists ? 'PUT' : 'POST', { apiVersion: 'serving.knative.dev/v1', kind: 'Service', metadata, spec: service.spec });
  for (let attempt = 0; attempt < 90; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 4000));
    const current = await request(`${base}/${name}`);
    const ready = current.status?.conditions?.find(c => c.type === 'Ready');
    if (current.status?.observedGeneration >= saved.metadata.generation && ready?.status === 'True') {
      console.log(JSON.stringify({ service: name, revision: current.status.latestReadyRevisionName, url: current.status.url }));
      return current;
    }
    if (ready?.status === 'False' && current.status?.observedGeneration >= saved.metadata.generation) throw new Error(`${name} failed readiness: ${ready.reason}`);
  }
  throw new Error(`${name} readiness timed out`);
}
const admin = await request(`${base}/portfolio-admin-service`);
if (mode === 'admin' || mode === 'enable') {
  const container = admin.spec.template.spec.containers[0];
  container.image = `${region}-docker.pkg.dev/${project}/portfolio/portfolio-admin-service:${tag}`;
  admin.spec.template.spec.timeoutSeconds = 900;
  mergeEnv(container, [...mediaEnv, envValue('MEDIA_PIPELINE_ENABLED', mode === 'enable' ? 'true' : 'false'),
    envValue('MEDIA_UPLOAD_MAX_VIDEO_BYTES', '268435456'), secret('MEDIA_RECOVERY_TOKEN'), secret('MEDIA_SERVICE_INTERNAL_TOKEN', 'MEDIA_INTERNAL_TOKEN')]);
  if (mode === 'enable') {
    const worker = await request(`${base}/portfolio-media-service`);
    mergeEnv(container, [envValue('MEDIA_SERVICE_BASE_URL', worker.status.url), envValue('MEDIA_SERVICE_IDENTITY_TOKEN', 'true')]);
  }
  await save(admin, true);
} else if (mode === 'worker') {
  const env = admin.spec.template.spec.containers[0].env.filter(item => item.name.startsWith('KAFKA_') || item.name.startsWith('SPRING_DATASOURCE_'));
  const service = { metadata: { name: 'portfolio-media-service', annotations: { 'run.googleapis.com/ingress': 'all' } }, spec: {
    template: { metadata: { annotations: { 'autoscaling.knative.dev/minScale': '0', 'autoscaling.knative.dev/maxScale': '1', 'run.googleapis.com/cpu-throttling': 'true' } }, spec: {
      serviceAccountName: `portfolio-media-runtime@${project}.iam.gserviceaccount.com`, containerConcurrency: 2, timeoutSeconds: 900,
      containers: [{ image: `${region}-docker.pkg.dev/${project}/portfolio/portfolio-media-service:${tag}`, ports: [{ containerPort: 8084 }],
        resources: { limits: { cpu: '2', memory: '2Gi' } }, env: [...env, ...mediaEnv, secret('INTERNAL_API_TOKEN', 'MEDIA_INTERNAL_TOKEN'),
          envValue('DB_POOL_MAX', '3'), envValue('MEDIA_WORKER_CONCURRENCY', '1'), envValue('MEDIA_SOURCE_CACHE_BYTES', '268435456'),
          envValue('MEDIA_MAX_VIDEO_SECONDS', '600'), envValue('MEDIA_JOB_TIMEOUT_SECONDS', '600'), envValue('MEDIA_HTTP_INTAKE_ENABLED', 'false')],
      }],
    } }, traffic: [{ latestRevision: true, percent: 100 }],
  } };
  let existing;
  try { existing = await request(`${base}/portfolio-media-service`); } catch (error) { if (!error.message.includes('404')) throw error; }
  if (existing) service.metadata.resourceVersion = existing.metadata.resourceVersion;
  await save(service, Boolean(existing));
} else {
  const parent = `https://cloudscheduler.googleapis.com/v1/projects/${project}/locations/${region}`;
  const name = `projects/${project}/locations/${region}/jobs/portfolio-media-recovery`;
  const token = run(['secrets', 'versions', 'access', 'latest', '--secret', 'MEDIA_RECOVERY_TOKEN']);
  const body = { name, description: 'Recover durable media delivery and wake scale-to-zero worker', schedule: '*/15 * * * *', timeZone: 'Etc/UTC', attemptDeadline: '900s',
    retryConfig: { retryCount: 2, minBackoffDuration: '60s', maxBackoffDuration: '300s' },
    httpTarget: { uri: `${admin.status.url}/api/internal/media/recover`, httpMethod: 'POST', headers: { 'X-Media-Recovery-Token': token } } };
  let exists = true;
  try { await request(`${parent}/jobs/portfolio-media-recovery`); } catch (error) { if (!error.message.includes('404')) throw error; exists = false; }
  await request(exists ? `${parent}/jobs/portfolio-media-recovery` : `${parent}/jobs`, exists ? 'PATCH' : 'POST', body);
  console.log('Media recovery schedule configured: every 15 minutes');
}
