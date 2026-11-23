# Grafana Cloud Free Tier — Portfolio Platform Monitoring

## One service registry

Edit **`monitoring/services.yaml`**. The existing `jvm` and `health` templates
generate Alloy scrapes, Grafana service rows, and public display metadata. A new
public Cloud Run service needs only this entry (the endpoint must actually exist):

```yaml
- id: new-service
  name: New Service
  monitoring: jvm # or health
  endpoint: https://new-service-PROJECT.REGION.run.app/actuator/prometheus
  intervalSeconds: 120
  public: true
```

For `health`, use `/health`; the default body requires `status: ok` or `status: UP`.
`healthProfile: ok` supports `ok: true`; `mcp` additionally checks the MCP server
identity. Optional `purpose`, `stack`, `repo`, and `module` enrich hover details.
Unknown metric types require a reviewed template, not arbitrary PromQL or URLs.

Run `npm ci --prefix monitoring`, `npm test --prefix monitoring`, and
`npm run generate --prefix monitoring`. The monitoring workflow also generates
outputs before building, so a future service addition only needs a YAML edit.
Pull requests validate the image but cannot deploy or read the Grafana secret.
Monitoring-only main pushes do not trigger the Java application release pipeline.

The frontend reads the fixed, public GitHub registry on its server, coalesces
refreshes for five minutes, and includes sanitized metadata in the existing
`/api/metrics` response. No client-side registry fetch, new poll, or per-service
summary query is added. During an upstream outage it retains the last successful
registry for at most 15 minutes; cold starts fail closed. Visibility changes are
eventually consistent with these caches. Endpoints and IAM settings are not sent
in the browser payload. This public repository must never contain credentials.

`public: false` removes the service from homepage details and registry-managed
Grafana rows/aggregate queries, but still collects its metrics. It is not an IAM
setting. `access: cloud-run-iam` requires a separately approved exact endpoint in
`access-approvals.json`, an existing audience and an existing invoker grant.
`verify-access.mjs` only reads those grants; ordinary deployments never add them.
Manual VM bootstrap is opt-in and does not grant private service access either.

`sync-dashboard.mjs` reconciles only tagged service rows and the known legacy
aggregate panels. Other panels and share settings are preserved. It saves a
backup before an optimistic-version update. The workflow supplies the existing
`GRAFANA_RENDER_TOKEN` via an Actions Secret only to monitoring verification and
dashboard reconciliation steps. To roll back,
revert the registry commit and rerun the monitoring workflow; this does not
change service permissions or Cloud Run scaling.

Current cadence is unchanged: **13 services, 16,560 checks/day**. Validation
rejects duplicate IDs/endpoints, unapproved private targets, intervals below
60 seconds, more than 50 services, or over 24,000 checks/day. These are workload
guards, not a dollar budget: additional services still add backend scrape/series
cost and can wake idle instances. No new VM, model calls, or browser polling.
One additional local collector heartbeat every 120 seconds sends only one
revision-labelled series (720 samples/day, no external service wakeup). Deployment
waits for this revision before accepting health checks; old healthy data cannot
make a failed collector update look successful.

## Media and Node services

The existing e2-micro Alloy collector also monitors these services:

| Service | Endpoint | Interval | Signals |
| --- | --- | --- | --- |
| Media Service | IAM-protected `/actuator/prometheus` | 120s | JVM, HTTP, process, pool metrics |
| Voice Relay | `/health` | 120s | JSON health result, response duration, HTTP status |
| MCP Server | `/health` | 120s | JSON health result, response duration, HTTP status |

Media accepts the existing `https://portfolio-metrics.internal` audience. The
collector identity already has Cloud Run invoker access; no bucket permissions,
raw-media access, service credentials, AI budgets, or scaling limits change.
Node probes require HTTP 200 plus the expected JSON fields, and follow no
redirects. Use `probe_success`, not exporter `up`, for target availability.
These are health checks, not end-to-end voice/tool/transcoding tests.

Cost: reuse the existing VM and Grafana workspace. Three 120-second checks add
about 2,160 HTTP requests/day. They can wake Cloud Run instances and keep them
warm; request execution and idle memory can incur charges. There are no paid
model calls, uploads, transcodes, new VMs, or new continuously allocated CPUs.
Node series are allowlisted to four metric names per service. The homepage
continues to use cached server-side queries, not direct service polling.

Validate with `alloy validate monitoring/alloy/config.alloy` (set the two Grafana
URL variables), and `node --test monitoring/service-panels.test.mjs`.
The image build also validates the config with pinned Alloy 1.22.0.
`add-service-panels.mjs` is the legacy one-time migration. Use
`sync-dashboard.mjs` for all ongoing registry updates to `spring_boot_21`.
Set `GRAFANA_RENDER_TOKEN` and run it without arguments first; `--apply` requires
`GRAFANA_DASHBOARD_BACKUP` and preserves the dashboard version for optimistic
concurrency. Existing panels and public-share settings remain unchanged.

Release checks: confirm all 13 jobs have fresh samples, the two Node services
have `probe_success=1`, Media has heap/uptime samples, and anonymous Media
requests remain denied. The homepage distinguishes failed checks from missing
data; neither missing samples nor failed scrapes are called "idle".

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Grafana Cloud (Free Tier)                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │  Prometheus  │  │     Loki     │  │    Tempo     │          │
│  │  (metrics)   │  │   (logs)     │  │  (traces)    │          │
│  └──────┬───────┘  └──────┬───────┘  └──────────────┘          │
│         │                  │                                     │
│  ┌──────┴──────────────────┴──────────────────┐                 │
│  │            Grafana Dashboards               │                 │
│  └─────────────────────────────────────────────┘                 │
└─────────────────────────────────────────────────────────────────┘
        ▲                       ▲
        │  remote_write         │  loki push
        │                       │
┌───────┴───────────────────────┴──────────┐
│           Grafana Alloy (scraper)         │
│        Cloud Run / Docker container       │
└───────────────────┬──────────────────────┘
                    │  scrapes /actuator/prometheus
        ┌───────────┼───────────────────────────────┐
        ▼           ▼           ▼                   ▼
  ┌───────────┐ ┌─────────┐ ┌─────────────┐ ┌──────────────┐
  │notification│ │  admin  │ │agent+gateway│ │analytics x2  │
  │  service   │ │+indexers│ │             │ │              │
  └───────────┘ └─────────┘ └─────────────┘ └──────────────┘
     (8 Spring Boot services on Cloud Run)
```

## Setup Steps

### 1. Create Grafana Cloud Account (Free)

1. Go to [grafana.com/products/cloud](https://grafana.com/products/cloud/)
2. Sign up → Free plan (no credit card needed)
3. Note your stack details:
   - **Prometheus** push URL + user ID (My Account → Prometheus → "Send Metrics")
   - **Loki** push URL + user ID (My Account → Loki → "Send Logs")
   - **API Key**: My Account → API Keys → "Add API Key" → role: `MetricsPublisher`

### 2. Service-Side Changes (Already Done)

All 8 services now have:
- `micrometer-registry-prometheus` dependency in `pom.xml`
- `management.endpoints.web.exposure.include: health,info,prometheus`
- `management.metrics.tags.application: <service-name>`

After redeployment, each service exposes metrics at:
```
GET /actuator/prometheus
```

### 3. Deploy Grafana Alloy (Metrics Scraper)

#### Option A: Local Dev

```bash
cp .env.monitoring.example .env.monitoring
# Edit .env.monitoring with your Grafana Cloud credentials
docker compose -f docker-compose.monitoring.yml --env-file .env.monitoring up -d
```

#### Option B: Cloud Run (Production)

```bash
# Build and push the Alloy container with config baked in
docker build -t gcr.io/YOUR_PROJECT/portfolio-alloy -f monitoring/alloy/Dockerfile .
docker push gcr.io/YOUR_PROJECT/portfolio-alloy

# Deploy to Cloud Run with env vars from Secret Manager
gcloud run deploy portfolio-metrics-scraper \
  --image gcr.io/YOUR_PROJECT/portfolio-alloy \
  --region us-central1 \
  --set-env-vars "NOTIFICATION_SERVICE_URL=portfolio-notification-service-xxxxx.a.run.app,..." \
  --set-secrets "GRAFANA_API_KEY=grafana-api-key:latest,GRAFANA_PROMETHEUS_URL=..." \
  --vpc-connector YOUR_VPC_CONNECTOR \
  --no-allow-unauthenticated
```

#### Option C: Cloud Run Sidecar (Simplest for production)

Add Alloy as a sidecar to each service's Cloud Run deployment. Each instance scrapes only itself at `localhost:PORT/actuator/prometheus`.

### 4. Grafana Dashboard

After metrics flow in, import these community dashboards:
- **Spring Boot 3.x dashboard** — ID: `19004`
- **JVM Micrometer** — ID: `4701`
- **HTTP Request Metrics** — ID: `12900`

Go to Grafana → Dashboards → Import → paste the ID.

### 5. Alerting (Free Tier Includes It)

Example alert rules to configure in Grafana Cloud:
- **Service down**: `up == 0` for > 2 minutes
- **High error rate**: `rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.1`
- **JVM heap pressure**: `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.85`

Alerts can notify via: Email, Slack, Discord, PagerDuty (all free).

## Free Tier Limits

| Resource | Free Limit | Our Estimated Usage |
|----------|-----------|---------------------|
| Metrics series | 10,000 | ~2,000 (8 services × ~250 series each) |
| Logs | 50 GB/month | ~5 GB (depends on traffic) |
| Traces | 50 GB/month | 0 (not configured yet) |
| Retention | 14 days | Sufficient for alerting |
| Users | 3 | 1 (you) |

## Service Inventory

| Service | Port | Repo | Prometheus Endpoint |
|---------|------|------|---------------------|
| portfolio-notification-service | 8080 | portfolio-notification-service | /actuator/prometheus |
| portfolio-admin-service | 8081 | portfolio-admin-service/admin-service | /actuator/prometheus |
| portfolio-search-indexer | 8082 | portfolio-admin-service/search-indexer | /actuator/prometheus |
| portfolio-rag-indexer | 8083 | portfolio-admin-service/rag-indexer | /actuator/prometheus |
| portfolio-agent-service | 8090 | portfolio-ai-platform/portfolio-agent-service | /actuator/prometheus |
| portfolio-mcp-gateway | 8091 | portfolio-ai-platform/portfolio-mcp-gateway | /actuator/prometheus |
| analytics-aggregator-service | 8093 | portfolio-analytics-platform/analytics-aggregator-service | /actuator/prometheus |
| analytics-alerts-service | 8094 | portfolio-analytics-platform/analytics-alerts-service | /actuator/prometheus |

## Career Workflow Service

`portfolio-application-copilot` exposes JVM and HTTP metrics at `/actuator/prometheus`.
The generated Alloy scrape uses a Cloud Run identity token and the same
metrics audience as the private indexers. The audience and the scraper service
account's `roles/run.invoker` grant must already exist; deployments only verify
them and never add access.
The application’s `/internal/**` token protection remains unchanged.

The Portfolio monitoring API labels this job **Career Workflow Service** and appends
it after the existing services, inside the default-collapsed “more services” list.
A successful scrape is required for **UP**; Career is not inferred to be idle from
missing samples. The 60-second scrape cadence can wake a scale-to-zero instance.

The Grafana dashboard includes a default-collapsed **Career Workflow Service** row
with availability, heap, uptime, threads, CPU, request rate, and HTTP 5xx rate. Its
legacy panel definition is in `career-grafana-row.json`; ongoing updates use
`sync-dashboard.mjs` and the registry. Verify `up{job="portfolio-application-copilot"}`
and JVM samples in Grafana after deployment.

## Key Metrics Available

Once scraped, you'll have out-of-the-box:

- **HTTP**: `http_server_requests_seconds_*` (latency, count, by status/method/uri)
- **JVM**: heap usage, GC pauses, thread count, class loading
- **Hikari**: connection pool active/idle/pending
- **Kafka**: consumer lag, poll rate, commit latency
- **System**: CPU usage, uptime, disk space
- **Custom**: any `@Timed` or `Counter` you add to your code
