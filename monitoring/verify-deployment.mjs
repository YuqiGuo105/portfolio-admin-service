import { setTimeout as sleep } from 'node:timers/promises';
import { loadRegistry } from './generate.mjs';

const registry = await loadRegistry();
const token = process.env.GRAFANA_RENDER_TOKEN;
if (!token) throw new Error('Missing monitoring deployment credential');
const query = `max(portfolio_monitoring_registry_info{revision="${registry.revision}"} == 1) and (time() - max(timestamp(portfolio_monitoring_registry_info{revision="${registry.revision}"})) < 240)`;
let ready = false;
for (let attempt = 1; attempt <= 24; attempt++) {
  try {
    const response = await fetch('https://loyalcaravan951.grafana.net/api/datasources/proxy/uid/grafanacloud-prom/api/v1/query', {
      method: 'POST', redirect: 'error', signal: AbortSignal.timeout(15000),
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({query}),
    });
    if (response.ok && (await response.json()).data?.result?.some(s => s.value?.[1] === '1')) { ready = true; break; }
  } catch { /* Retry bounded transient Grafana/network failures without exposing credentials. */ }
  console.log(`Waiting for collector registry ${registry.revision}: ${attempt}/24`);
  if (attempt < 24) await sleep(30000);
}
if (!ready) throw new Error('Collector did not report the deployed registry revision');
console.log(`Collector is running registry ${registry.revision}`);
