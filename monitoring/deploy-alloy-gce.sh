#!/usr/bin/env bash
set -euo pipefail

PROJECT_ID="${GCP_PROJECT_ID:-portfolio-notify-prod}"
ZONE="${GCP_ZONE:-us-central1-a}"
REGION="${GCP_REGION:-us-central1}"
INSTANCE_NAME="${ALLOY_INSTANCE_NAME:-portfolio-metrics-scraper}"
SERVICE_ACCOUNT_NAME="portfolio-metrics-scraper"
SERVICE_ACCOUNT="${SERVICE_ACCOUNT_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
TOKEN_SECRET="${GRAFANA_TOKEN_SECRET:-GRAFANA_CLOUD_WRITE_TOKEN}"
IMAGE="${ALLOY_IMAGE:-gcr.io/${PROJECT_ID}/portfolio-metrics-scraper:latest}"
CUSTOM_AUDIENCE="${CLOUD_RUN_METRICS_AUDIENCE:-https://portfolio-metrics.internal}"
FIREWALL_TAG="portfolio-metrics-no-ingress"
FIREWALL_RULE="portfolio-metrics-scraper-deny-ingress"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

retry() {
  local attempts=12
  local delay_seconds=5
  local attempt=1
  until "$@"; do
    if (( attempt >= attempts )); then
      return 1
    fi
    sleep "${delay_seconds}"
    attempt=$((attempt + 1))
  done
}

gcloud services enable compute.googleapis.com --project="${PROJECT_ID}"

if ! gcloud iam service-accounts describe "${SERVICE_ACCOUNT}" \
  --project="${PROJECT_ID}" >/dev/null 2>&1; then
  gcloud iam service-accounts create "${SERVICE_ACCOUNT_NAME}" \
    --project="${PROJECT_ID}" \
    --display-name="Portfolio metrics scraper"
fi

retry gcloud secrets add-iam-policy-binding "${TOKEN_SECRET}" \
  --project="${PROJECT_ID}" \
  --member="serviceAccount:${SERVICE_ACCOUNT}" \
  --role="roles/secretmanager.secretAccessor" >/dev/null

retry gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
  --member="serviceAccount:${SERVICE_ACCOUNT}" \
  --role="roles/storage.objectViewer" >/dev/null

retry gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
  --member="serviceAccount:${SERVICE_ACCOUNT}" \
  --role="roles/artifactregistry.reader" >/dev/null

retry gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
  --member="serviceAccount:${SERVICE_ACCOUNT}" \
  --role="roles/logging.logWriter" >/dev/null

for service in portfolio-search-indexer portfolio-rag-indexer; do
  if ! gcloud run services describe "${service}" \
    --project="${PROJECT_ID}" \
    --region="${REGION}" \
    --format=json | grep -Fq "\"${CUSTOM_AUDIENCE}\""; then
    gcloud run services update "${service}" \
      --project="${PROJECT_ID}" \
      --region="${REGION}" \
      --add-custom-audiences="${CUSTOM_AUDIENCE}" >/dev/null
  fi
  gcloud run services add-iam-policy-binding "${service}" \
    --project="${PROJECT_ID}" \
    --region="${REGION}" \
    --member="serviceAccount:${SERVICE_ACCOUNT}" \
    --role="roles/run.invoker" >/dev/null
done

gcloud builds submit "${SCRIPT_DIR}/alloy" \
  --project="${PROJECT_ID}" \
  --tag="${IMAGE}" \
  --quiet

if ! gcloud compute firewall-rules describe "${FIREWALL_RULE}" \
  --project="${PROJECT_ID}" >/dev/null 2>&1; then
  gcloud compute firewall-rules create "${FIREWALL_RULE}" \
    --project="${PROJECT_ID}" \
    --network="default" \
    --direction="INGRESS" \
    --priority="900" \
    --action="DENY" \
    --rules="all" \
    --source-ranges="0.0.0.0/0" \
    --target-tags="${FIREWALL_TAG}" >/dev/null
fi

if gcloud compute instances describe "${INSTANCE_NAME}" \
  --project="${PROJECT_ID}" --zone="${ZONE}" >/dev/null 2>&1; then
  gcloud compute instances add-tags "${INSTANCE_NAME}" \
    --project="${PROJECT_ID}" \
    --zone="${ZONE}" \
    --tags="${FIREWALL_TAG}" >/dev/null
  gcloud compute instances add-metadata "${INSTANCE_NAME}" \
    --project="${PROJECT_ID}" \
    --zone="${ZONE}" \
    --metadata="project-id=${PROJECT_ID},container-image=${IMAGE},grafana-token-secret=${TOKEN_SECRET},cloud-run-audience=${CUSTOM_AUDIENCE},grafana-prometheus-url=https://prometheus-prod-67-prod-us-west-0.grafana.net/api/prom/push,grafana-prometheus-user=3348056,grafana-loki-url=https://logs-prod-021.grafana.net/loki/api/v1/push,grafana-loki-user=1669703" \
    --metadata-from-file="startup-script=${SCRIPT_DIR}/alloy-gce-startup.sh" >/dev/null
  gcloud compute instances reset "${INSTANCE_NAME}" \
    --project="${PROJECT_ID}" \
    --zone="${ZONE}" \
    --quiet >/dev/null
  echo "Updated and restarted ${INSTANCE_NAME} in ${ZONE}."
  exit 0
fi

gcloud compute instances create "${INSTANCE_NAME}" \
  --project="${PROJECT_ID}" \
  --zone="${ZONE}" \
  --machine-type="e2-micro" \
  --image-family="debian-12" \
  --image-project="debian-cloud" \
  --boot-disk-type="pd-standard" \
  --boot-disk-size="10GB" \
  --service-account="${SERVICE_ACCOUNT}" \
  --scopes="cloud-platform" \
  --shielded-secure-boot \
  --tags="${FIREWALL_TAG}" \
  --labels="service=portfolio-metrics-scraper,environment=production" \
  --metadata="project-id=${PROJECT_ID},container-image=${IMAGE},grafana-token-secret=${TOKEN_SECRET},cloud-run-audience=${CUSTOM_AUDIENCE},grafana-prometheus-url=https://prometheus-prod-67-prod-us-west-0.grafana.net/api/prom/push,grafana-prometheus-user=3348056,grafana-loki-url=https://logs-prod-021.grafana.net/loki/api/v1/push,grafana-loki-user=1669703" \
  --metadata-from-file="startup-script=${SCRIPT_DIR}/alloy-gce-startup.sh"

echo "Created ${INSTANCE_NAME}. Check serial output and Grafana after the first scrape interval."
