#!/usr/bin/env bash
# deploy-alloy-cloudrun.sh — Deploy Grafana Alloy as a Cloud Run service
# This scraper runs alongside your portfolio services and pushes metrics
# to Grafana Cloud. No local Docker needed — Cloud Build builds it remotely.
#
# Prerequisites:
#   - gcloud CLI authenticated
#   - .env.monitoring filled with Grafana Cloud credentials
#
# Usage:
#   ./monitoring/deploy-alloy-cloudrun.sh
set -euo pipefail

PROJECT_ID="${GCP_PROJECT_ID:-portfolio-notify-prod}"
REGION="us-central1"
SERVICE_NAME="portfolio-metrics-scraper"
IMAGE="gcr.io/${PROJECT_ID}/${SERVICE_NAME}"

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  Deploying Grafana Alloy → Cloud Run (${REGION})            ║"
echo "╚══════════════════════════════════════════════════════════════╝"

# ─── Load credentials from .env.monitoring ─────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/../.env.monitoring"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "❌ .env.monitoring not found. Run setup first."
  exit 1
fi
source "$ENV_FILE"

echo ""
echo "📦 Building container via Cloud Build..."
cd "${SCRIPT_DIR}/alloy"
gcloud builds submit --tag "${IMAGE}" --project "${PROJECT_ID}" --quiet

echo ""
echo "🚀 Deploying to Cloud Run..."
gcloud run deploy "${SERVICE_NAME}" \
  --image "${IMAGE}" \
  --project "${PROJECT_ID}" \
  --region "${REGION}" \
  --platform managed \
  --no-allow-unauthenticated \
  --memory 256Mi \
  --cpu 1 \
  --min-instances 0 \
  --max-instances 1 \
  --cpu-throttling \
  --set-env-vars "\
GRAFANA_PROMETHEUS_URL=${GRAFANA_PROMETHEUS_URL},\
GRAFANA_PROMETHEUS_USER=${GRAFANA_PROMETHEUS_USER},\
GRAFANA_API_KEY=${GRAFANA_API_KEY},\
GRAFANA_LOKI_URL=${GRAFANA_LOKI_URL},\
GRAFANA_LOKI_USER=${GRAFANA_LOKI_USER},\
NOTIFICATION_SERVICE_URL=portfolio-notification-service-702193211434.us-central1.run.app,\
ADMIN_SERVICE_URL=portfolio-admin-service-702193211434.us-central1.run.app,\
SEARCH_INDEXER_URL=portfolio-search-indexer-702193211434.us-central1.run.app,\
RAG_INDEXER_URL=portfolio-rag-indexer-702193211434.us-central1.run.app,\
AGENT_SERVICE_URL=portfolio-agent-service-702193211434.us-central1.run.app,\
MCP_GATEWAY_URL=portfolio-mcp-gateway-702193211434.us-central1.run.app,\
AGGREGATOR_SERVICE_URL=portfolio-analytics-aggregator-702193211434.us-central1.run.app,\
ALERTS_SERVICE_URL=portfolio-analytics-alerts-702193211434.us-central1.run.app"

echo ""
echo "✅ Deployed! Alloy is now scraping all 8 services → Grafana Cloud"
echo ""
echo "Dashboard: https://loyalcaravan951.grafana.net"
echo "Import Spring Boot dashboard: ID 19004"
