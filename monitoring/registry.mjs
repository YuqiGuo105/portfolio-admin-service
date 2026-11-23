import { parseDocument } from 'yaml';
import { createHash } from 'node:crypto';

const ID = /^[a-z][a-z0-9-]{0,62}$/;
const fields = new Set(['id', 'name', 'monitoring', 'endpoint', 'intervalSeconds', 'public', 'access', 'healthProfile', 'repo', 'module', 'purpose', 'stack']);
const text = (value, max) => typeof value === 'string' && value.trim() && value.length <= max && !/[\x00-\x1f]/.test(value);

export function parseRegistry(source, approvals) {
  if (Buffer.byteLength(source) > 65536) throw new Error('Registry exceeds 64 KiB');
  const doc = parseDocument(source, { uniqueKeys: true });
  if (doc.errors.length) throw new Error(`Invalid YAML: ${doc.errors[0].message}`);
  const input = doc.toJS({ maxAliasCount: 0 });
  if (input?.version !== 1 || !Array.isArray(input.services) || !input.services.length || input.services.length > 50 || Object.keys(input).some(k => !['version', 'services'].includes(k))) throw new Error('Invalid registry envelope');
  const ids = new Set(), endpoints = new Set();
  const services = input.services.map(service => {
    if (!service || typeof service !== 'object' || Object.keys(service).some(k => !fields.has(k))) throw new Error('Unknown registry field');
    const { id } = service;
    if (typeof id !== 'string' || !ID.test(id) || ['constructor', 'prototype'].includes(id) || ids.has(id)) throw new Error(`Invalid or duplicate service id: ${id}`);
    ids.add(id);
    if (!text(service.name, 80) || !['jvm', 'health'].includes(service.monitoring) || typeof service.public !== 'boolean') throw new Error(`Invalid display/template: ${id}`);
    if (!Number.isInteger(service.intervalSeconds) || service.intervalSeconds < 60 || service.intervalSeconds > 900) throw new Error(`Unsafe interval: ${id}`);
    const url = new URL(service.endpoint);
    const path = service.monitoring === 'jvm' ? '/actuator/prometheus' : '/health';
    if (url.protocol !== 'https:' || !/^[a-z0-9-]+(?:\.[a-z0-9-]+)*\.run\.app$/.test(url.hostname) || url.port || url.username || url.password || url.search || url.hash || url.pathname !== path || url.href !== service.endpoint || endpoints.has(url.href)) throw new Error(`Unsafe or duplicate endpoint: ${id}`);
    endpoints.add(url.href);
    const access = service.access || 'public';
    if (!['public', 'cloud-run-iam'].includes(access)) throw new Error(`Unknown access mode: ${id}`);
    const approvedEndpoint = Object.hasOwn(approvals?.services || {}, id) ? approvals.services[id] : undefined;
    if (approvedEndpoint && (access !== 'cloud-run-iam' || approvedEndpoint !== service.endpoint)) throw new Error(`Cannot downgrade or redirect approved private service: ${id}`);
    if (access === 'cloud-run-iam' && (approvedEndpoint !== service.endpoint || service.monitoring !== 'jvm')) throw new Error(`Separate private-service approval required: ${id}`);
    if (service.healthProfile && (service.monitoring !== 'health' || !['ok', 'status', 'mcp'].includes(service.healthProfile))) throw new Error(`Unknown health profile: ${id}`);
    if (service.repo != null && !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/.test(service.repo)) throw new Error(`Invalid repository: ${id}`);
    if (service.module != null && (!/^[A-Za-z0-9_/-]{1,120}$/.test(service.module) || service.module.includes('..'))) throw new Error(`Invalid module: ${id}`);
    if (service.purpose != null && !text(service.purpose, 300)) throw new Error(`Invalid purpose: ${id}`);
    if (service.stack != null && (!Array.isArray(service.stack) || service.stack.length > 8 || service.stack.some(s => !text(s, 40)))) throw new Error(`Invalid stack: ${id}`);
    return { ...service, access, healthProfile: service.monitoring === 'health' ? service.healthProfile || 'status' : undefined };
  });
  const requestsPerDay = services.reduce((sum, s) => sum + 86400 / s.intervalSeconds, 0);
  if (requestsPerDay > 24000) throw new Error('Collector exceeds 24000 checks/day; review cost or lengthen intervals');
  return { version: 1, services, requestsPerDay, revision: createHash('sha256').update(source).digest('hex').slice(0,16) };
}

export function publicCatalog(registry) {
  return Object.fromEntries(registry.services.filter(s => s.public).map(s => [s.id, {
    name: s.name, monitoring: s.monitoring === 'health' ? 'probe' : 'jvm', intervalSeconds: s.intervalSeconds,
    purpose: s.purpose || '', stack: s.stack || [], ...(s.repo ? { repo: s.repo } : {}), ...(s.module ? { module: s.module } : {}),
  }]));
}
