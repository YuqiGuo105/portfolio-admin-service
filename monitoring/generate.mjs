import { readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { parseRegistry, publicCatalog } from './registry.mjs';

const q = JSON.stringify;
const forward = 'forward_to = [prometheus.remote_write.grafana_cloud.receiver]';
const auth = 'authorization {\n    type = "Bearer"\n    credentials_file = "/var/run/cloud-run-identity/token"\n  }';
const profiles = {
  ok: ['"ok"\\s*:\\s*true'],
  status: ['"status"\\s*:\\s*"(?:ok|UP)"'],
  mcp: ['"status"\\s*:\\s*"ok"', '"server"\\s*:\\s*"yuqi-portfolio-mcp"'],
};

export function renderAlloy(registry) {
  const blocks = [`// Generated from monitoring/services.yaml. Do not edit by hand.
prometheus.remote_write "grafana_cloud" {
  endpoint {
    url = sys.env("GRAFANA_PROMETHEUS_URL")
    basic_auth {
      username = sys.env("GRAFANA_PROMETHEUS_USER")
      password = sys.env("GRAFANA_API_KEY")
    }
  }
}`];
  for (const service of registry.services) {
    const label = service.id.replaceAll('-', '_');
    const url = new URL(service.endpoint);
    if (service.monitoring === 'jvm') {
      blocks.push(`prometheus.scrape "${label}" {
  targets = [{"__address__" = ${q(url.host)}, "job" = ${q(service.id)}}]
  scheme = "https"
  metrics_path = ${q(url.pathname)}
  scrape_interval = "${service.intervalSeconds}s"
  scrape_timeout = "15s"
  follow_redirects = false
${service.access === 'cloud-run-iam' ? `  ${auth}\n` : ''}  ${forward}
}`);
    } else {
      blocks.push(`prometheus.exporter.blackbox "${label}" {
  config = \`modules:
  health:
    prober: http
    timeout: 8s
    http:
      valid_status_codes: [200]
      follow_redirects: false
      preferred_ip_protocol: ip4
      fail_if_body_not_matches_regexp: ${q(profiles[service.healthProfile])}
\`
  target {
    name = ${q(service.id)}
    address = ${q(service.endpoint)}
    module = "health"
  }
}
prometheus.scrape "${label}" {
  targets = prometheus.exporter.blackbox.${label}.targets
  scrape_interval = "${service.intervalSeconds}s"
  scrape_timeout = "10s"
  forward_to = [prometheus.relabel.${label}.receiver]
}
prometheus.relabel "${label}" {
  ${forward}
  rule {
    source_labels = ["job"]
    regex = "integrations/blackbox/${service.id}"
    target_label = "job"
    replacement = ${q(service.id)}
  }
  rule {
    source_labels = ["__name__"]
    regex = "up|probe_success|probe_duration_seconds|probe_http_status_code"
    action = "keep"
  }
}`);
    }
  }
  // One local heartbeat proves the new configuration is running, rather than old samples passing CI.
  blocks.push(`prometheus.exporter.self "registry" {}
prometheus.scrape "registry" {
  targets = prometheus.exporter.self.registry.targets
  scrape_interval = "120s"
  forward_to = [prometheus.relabel.registry.receiver]
}
prometheus.relabel "registry" {
  ${forward}
  rule {
    source_labels = ["__name__"]
    regex = "up"
    action = "keep"
  }
  rule {
    target_label = "__name__"
    replacement = "portfolio_monitoring_registry_info"
  }
  rule {
    target_label = "job"
    replacement = "portfolio-monitoring-registry"
  }
  rule {
    target_label = "instance"
    replacement = "collector"
  }
  rule {
    target_label = "revision"
    replacement = ${q(registry.revision)}
  }
}`);
  blocks.push(`loki.write "grafana_cloud" {
  endpoint {
    url = sys.env("GRAFANA_LOKI_URL")
    basic_auth {
      username = sys.env("GRAFANA_LOKI_USER")
      password = sys.env("GRAFANA_API_KEY")
    }
  }
}`);
  return `${blocks.join('\n\n')}\n`;
}

export async function loadRegistry() {
  return parseRegistry(await readFile(new URL('./services.yaml', import.meta.url), 'utf8'), JSON.parse(await readFile(new URL('./access-approvals.json', import.meta.url), 'utf8')));
}

async function main() {
  const registry = await loadRegistry();
  const outputs = [['./alloy/config.alloy', renderAlloy(registry)], ['./public-catalog.json', `${JSON.stringify(publicCatalog(registry), null, 2)}\n`]];
  for (const [path, content] of outputs) {
    if (process.argv.includes('--check')) {
      if (await readFile(new URL(path, import.meta.url), 'utf8') !== content) throw new Error(`${path} is out of sync; run npm run generate`);
    } else await writeFile(new URL(path, import.meta.url), content);
  }
  console.log(JSON.stringify({ services: registry.services.length, public: Object.keys(publicCatalog(registry)).length, checksPerDay: registry.requestsPerDay }));
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main().catch(error => { console.error(error.message); process.exitCode = 1; });
