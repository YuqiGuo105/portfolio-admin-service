// Explicit operator step after approval. Secret bytes only travel through pipes to Secret Manager.
import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
const project = 'portfolio-notify-prod';
const runtime = `portfolio-media-runtime@${project}.iam.gserviceaccount.com`;
const admin = `admin-platform-runtime@${project}.iam.gserviceaccount.com`;
function command(args, input) {
  const r = spawnSync('gcloud', [...args, `--project=${project}`, '--quiet'], { input, encoding: 'utf8', maxBuffer: 4e6 });
  if (r.status !== 0) throw new Error(`gcloud ${args.slice(0, 3).join(' ')} failed (${r.status})`);
  return r.stdout;
}
function exists(name) {
  return spawnSync('gcloud', ['secrets', 'describe', name, `--project=${project}`, '--format=value(name)'], { stdio: 'pipe' }).status === 0;
}
function secret(name, value, readers) {
  if (!exists(name)) command(['secrets', 'create', name, '--replication-policy=automatic']);
  command(['secrets', 'versions', 'add', name, '--data-file=-'], value);
  for (const reader of readers) command(['secrets', 'add-iam-policy-binding', name, `--member=serviceAccount:${reader}`, '--role=roles/secretmanager.secretAccessor']);
  console.log(`${name}: stored; scoped readers configured`);
}
if (exists('MEDIA_S3_SECRET_KEY')) throw new Error('Media key already exists; refusing to create duplicate credentials');
const created = JSON.parse(command(['storage', 'hmac', 'create', runtime, '--format=json']));
const accessId = created.accessId || created.metadata?.accessId;
if (!accessId || !created.secret) throw new Error('Unexpected HMAC response; inspect metadata without printing secrets');
secret('MEDIA_S3_ACCESS_KEY', accessId, [runtime, admin]);
secret('MEDIA_S3_SECRET_KEY', created.secret, [runtime, admin]);
secret('MEDIA_INTERNAL_TOKEN', randomBytes(32).toString('hex'), [runtime, admin]);
secret('MEDIA_RECOVERY_TOKEN', randomBytes(32).toString('hex'), [admin]);
for (const name of ['SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD', 'KAFKA_CA_CERT']) {
  command(['secrets', 'add-iam-policy-binding', name, `--member=serviceAccount:${runtime}`, '--role=roles/secretmanager.secretAccessor']);
}
console.log('Media runtime access configured. No credentials were written to disk.');
