import { writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { loadRegistry } from './generate.mjs';

const uid = 'spring_boot_21';
const base = 'https://loyalcaravan951.grafana.net';
const datasource = { type: 'prometheus', uid: 'grafanacloud-prom' };
const prefix = 'portfolio-registry:';
const legacy = { 97: 'portfolio-application-copilot', 105: 'portfolio-media-service', 113: 'portfolio-voice-relay', 118: 'portfolio-mcp-server' };
const aggregatePanels = new Set([52,58,60,66,56,95,96,85,88,68,82,83,78,74,76,44,36,46,38,42,40,4,2,6,10,14,16,20]);

export function reconcileDashboard(dashboard, registry) {
  if (dashboard.uid !== uid || !Array.isArray(dashboard.panels)) throw new Error('Unexpected dashboard');
  const next = structuredClone(dashboard);
  const managed = new Map();
  const preserved = next.panels.filter(row => {
    let job = typeof row.description === 'string' && row.description.startsWith(prefix) ? row.description.slice(prefix.length) : null;
    // Adopt only the four previously published service rows, never similarly named user panels.
    if (!job && legacy[row.id] && row.type === 'row' && row.panels?.length && row.panels.every(p => p.targets?.every(t => t.expr?.includes(`job="${legacy[row.id]}"`)))) job = legacy[row.id];
    if (!job) return true;
    if (row.type !== 'row' || managed.has(job)) throw new Error('Invalid managed row');
    managed.set(job, row);
    return false;
  });
  const flatten = panels => panels.flatMap(p => [p, ...flatten(p.panels || [])]);
  let id = Math.max(0, ...flatten(next.panels).map(p => p.id || 0)) + 1;
  let y = Math.max(0, ...preserved.map(p => p.gridPos.y + p.gridPos.h));
  const publicServices = registry.services.filter(s => s.public);
  const rows = publicServices.map(s => {
    const existing = managed.get(s.id);
    const selector = `job="${s.id}"`;
    const freshness = ` and (time() - max(timestamp(${s.monitoring === 'health' ? 'probe_success' : 'up'}{${selector}})) <= ${Math.max(180, s.intervalSeconds * 2)})`;
    const specs = s.monitoring === 'health' ? [
      ['Health check', `min(probe_success{${selector}})${freshness}`, 'none'],
      ['Response time', `max(probe_duration_seconds{${selector}})*1000${freshness}`, 'ms'],
      ['HTTP status', `max(probe_http_status_code{${selector}})${freshness}`, 'none'],
      ['Response time history', `max(probe_duration_seconds{${selector}})*1000`, 'ms', true],
    ] : [
      ['Metrics check', `min(up{${selector}})${freshness}`, 'none'],
      ['JVM Heap Used', `sum(jvm_memory_used_bytes{${selector},area="heap"})/sum(jvm_memory_max_bytes{${selector},area="heap"}>0)*100${freshness}`, 'percent'],
      ['Uptime', `max(process_uptime_seconds{${selector}})${freshness}`, 's'],
      ['Live Threads', `sum(jvm_threads_live_threads{${selector}})${freshness}`, 'none'],
      ['CPU Usage', `avg(process_cpu_usage{${selector}} >= 0)*100`, 'percent', true],
      ['HTTP Requests / sec', `sum(rate(http_server_requests_seconds_count{${selector}}[5m]))`, 'reqps', true],
      ['HTTP 5xx / sec', `sum(rate(http_server_requests_seconds_count{${selector},status=~"5.."}[5m]))`, 'reqps', true],
    ];
    const row = { id: existing?.id || id++, title: s.name, description: `${prefix}${s.id}`, type: 'row', collapsed: true, gridPos: { x: 0, y: y++, w: 24, h: 1 }, panels: [] };
    row.panels = specs.map(([title, expr, unit, history], index) => ({
      id: existing?.panels?.[index]?.id || id++, title, type: history ? 'timeseries' : 'stat', datasource,
      description: `Sampled every ${s.intervalSeconds}s. ${s.monitoring === 'health' ? 'Health endpoint only; no AI/tool calls.' : 'Metrics scrape, not an end-to-end availability test.'}`,
      gridPos: { x: index % 4 * 6, y: row.gridPos.y + 1 + Math.floor(index / 4) * 6, w: 6, h: 6 },
      targets: [{ refId: 'A', datasource, expr, instant: !history, range: Boolean(history), legendFormat: title }],
      fieldConfig: { defaults: { unit, noValue: 'NO DATA', decimals: ['ms', 'percent', 'reqps'].includes(unit) ? 1 : 0,
        mappings: index === 0 ? [{ type: 'value', options: { '0': { text: 'CHECK FAILED', color: 'red' }, '1': { text: 'UP', color: 'green' } } }] : [],
        thresholds: { mode: 'absolute', steps: [{ color: 'green', value: null }] } }, overrides: [] },
      options: history ? { legend: { displayMode: 'list', placement: 'bottom' }, tooltip: { mode: 'single' } } : { colorMode: 'value', graphMode: 'none', reduceOptions: { calcs: ['lastNotNull'], fields: '', values: false } },
    }));
    return row;
  });
  next.panels = [...preserved, ...rows];
  // Legacy aggregate panels must not reveal services marked public:false.
  const jobs = publicServices.map(s => s.id).join('|') || '__no_public_services__';
  const scope = expr => expr.replace(/\{([^{}]*)\}/g, (_match, selector) => `{${selector}${selector.trim() ? ',' : ''}job=~"${jobs}"}`);
  for (const panel of flatten(preserved)) {
    for (const target of panel.targets || []) {
      if (aggregatePanels.has(panel.id) && target.expr && !/\bjob\s*(?:=|!)/.test(target.expr)) {
        target.expr = scope(target.expr);
        target.portfolioRegistryScope = true;
      } else if (target.portfolioRegistryScope && target.expr?.includes('job=~"')) {
        target.expr = target.expr.replace(/job=~"[^"]*"/g, `job=~"${jobs}"`);
      }
    }
  }
  return next;
}

async function main() {
  const token = process.env.GRAFANA_RENDER_TOKEN;
  if (!token) throw new Error('GRAFANA_RENDER_TOKEN is required');
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const response = await fetch(`${base}/api/dashboards/uid/${uid}`, { headers, redirect: 'error', signal: AbortSignal.timeout(15000) });
  if (!response.ok) throw new Error(`Dashboard read failed: ${response.status}`);
  const current = await response.json();
  const next = reconcileDashboard(current.dashboard, await loadRegistry());
  if (JSON.stringify(next) === JSON.stringify(current.dashboard)) { console.log('Dashboard already in sync'); return; }
  console.log(JSON.stringify({ uid, version: current.dashboard.version, managedRows: next.panels.filter(p => p.description?.startsWith(prefix)).length, apply: process.argv.includes('--apply') }));
  if (!process.argv.includes('--apply')) return;
  const backup = process.env.GRAFANA_DASHBOARD_BACKUP;
  if (!backup) throw new Error('Set GRAFANA_DASHBOARD_BACKUP before applying');
  await writeFile(backup, JSON.stringify(current), { flag: 'wx', mode: 0o600 });
  const saved = await fetch(`${base}/api/dashboards/db`, { method: 'POST', headers, redirect: 'error', signal: AbortSignal.timeout(15000), body: JSON.stringify({ dashboard: next, folderUid: current.meta.folderUid, overwrite: false, message: 'Sync validated monitoring service registry' }) });
  if (!saved.ok) throw new Error(`Dashboard save failed: ${saved.status}`);
  const result = await saved.json();
  console.log(JSON.stringify({ status: result.status, version: result.version, uid: result.uid }));
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main().catch(error => { console.error(error.message); process.exitCode = 1; });
