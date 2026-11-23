import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { parse, stringify } from 'yaml';
import { parseRegistry, publicCatalog } from './registry.mjs';
import { renderAlloy } from './generate.mjs';
import { reconcileDashboard } from './sync-dashboard.mjs';

const source = readFileSync(new URL('./services.yaml', import.meta.url), 'utf8');
const approvals = JSON.parse(readFileSync(new URL('./access-approvals.json', import.meta.url)));
const registry = parseRegistry(source, approvals);
const changed = fn => { const data = parse(source); fn(data); return stringify(data); };
test('current registry preserves 13 services and exact sampling budget', () => {
  assert.equal(registry.services.length, 13); assert.equal(registry.requestsPerDay, 16560);
  assert.equal(registry.services.filter(s => s.access === 'cloud-run-iam').length, 4);
});
test('six-field entry generates collector and public metadata without code changes', () => {
  const r = parseRegistry(changed(d => d.services.push({ id:'new-service', name:'New Service', monitoring:'health', endpoint:'https://new-service-123.us-central1.run.app/health', intervalSeconds:120, public:true })), approvals);
  assert.equal(publicCatalog(r)['new-service'].monitoring, 'probe');
  const alloy = renderAlloy(r);
  assert.match(alloy, /prometheus.exporter.blackbox "new_service"/);
  assert.match(alloy, /replacement = "new-service"/);
  assert.equal((alloy.match(/prometheus.scrape /g) || []).length,15);
  assert.match(alloy, /portfolio_monitoring_registry_info/);
});
test('private entries require separate approval and cannot be downgraded', () => {
  assert.throws(() => parseRegistry(changed(d => d.services[0].access = 'cloud-run-iam'), approvals), /approval/);
  assert.throws(() => parseRegistry(changed(d => d.services.find(s => s.access).access = 'public'), approvals), /downgrade/);
  assert.throws(() => parseRegistry(changed(d => d.services.find(s => s.access).endpoint = 'https://other.run.app/actuator/prometheus'), approvals), /redirect/);
  const output = renderAlloy(registry);
  assert.equal((output.match(/credentials_file/g) || []).length, 4);
});
test('public false hides display metadata but does not disable authorized collection', () => {
  const r = parseRegistry(changed(d => d.services[0].public = false), approvals);
  assert.equal(Object.keys(publicCatalog(r)).length,12);
  assert.match(renderAlloy(r), /job" = "portfolio-admin-service"/);
  assert.doesNotMatch(JSON.stringify(publicCatalog(r)), /"(?:endpoint|access|credentials)":|run\.app/);
});
test('reject malformed/duplicate IDs, unknown templates, injection, unsafe URLs and excessive work', () => {
  const mutations = [d=>delete d.services[0].id, d=>d.services[0].id='constructor', d=>d.services[0].id='x"}', d=>d.services.push(d.services[0]), d=>d.services[0].monitoring='redis', d=>d.services[0].intervalSeconds=5, d=>d.services[0].public='true', d=>d.services[0].endpoint='http://localhost/health', d=>d.services[0].endpoint='https://bad.run.app/actuator/prometheus?token=x', d=>d.services[0].unknown=true, d=>d.services[0].healthProfile='ok', d=>{ for(let i=0;i<20;i++)d.services.push({ ...d.services[0], id:`extra-${i}`, endpoint:`https://extra-${i}.run.app/actuator/prometheus` }); }];
  for (const mutate of mutations) assert.throws(() => parseRegistry(changed(mutate), approvals));
  assert.throws(() => parseRegistry('version: 1\nversion: 2\nservices: []', approvals));
});
test('dashboard reconcile is idempotent, keeps custom panels and scopes legacy aggregates', () => {
  const custom = {id:900,type:'timeseries',gridPos:{x:0,y:30,w:24,h:5},targets:[{expr:'up{job=~"custom.*"}'}]};
  const d = {uid:'spring_boot_21',version:10,templating:{list:[]},panels:[custom,{id:58,type:'gauge',gridPos:{x:0,y:0,w:6,h:4},targets:[{expr:'sum(jvm_memory_used_bytes{area="heap"})'}]}]};
  const result = reconcileDashboard(d,registry);
  assert.deepEqual(result.panels[0],custom);
  assert.equal(result.panels.length,15);
  assert.equal(result.version,10);
  assert.match(result.panels[1].targets[0].expr,/job=~/);
  assert.deepEqual(reconcileDashboard(result,registry),result);
  const ids = result.panels.flatMap(p=>[p.id,...(p.panels || []).map(c=>c.id)]);
  assert.equal(new Set(ids).size,ids.length);
  const hidden = {...registry,services:registry.services.map((s,i)=>i ? s : {...s,public:false})};
  const removed = reconcileDashboard(result,hidden);
  assert.equal(removed.panels.length,14);
  assert.doesNotMatch(JSON.stringify(removed), /portfolio-admin-service/);
  const node = result.panels.find(p=>p.description==='portfolio-registry:portfolio-voice-relay');
  assert.doesNotMatch(JSON.stringify(node),/jvm_|heap/);
});
