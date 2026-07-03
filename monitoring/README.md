# Grafana Cloud Free Tier — Portfolio Platform Monitoring

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

## Key Metrics Available

Once scraped, you'll have out-of-the-box:

- **HTTP**: `http_server_requests_seconds_*` (latency, count, by status/method/uri)
- **JVM**: heap usage, GC pauses, thread count, class loading
- **Hikari**: connection pool active/idle/pending
- **Kafka**: consumer lag, poll rate, commit latency
- **System**: CPU usage, uptime, disk space
- **Custom**: any `@Timed` or `Counter` you add to your code
