#!/usr/bin/env bash
set -euo pipefail

METADATA="http://metadata.google.internal/computeMetadata/v1"
HEADER="Metadata-Flavor: Google"

metadata() {
  curl -fsS -H "${HEADER}" "${METADATA}/instance/attributes/$1"
}

access_token() {
  curl -fsS -H "${HEADER}" \
    "${METADATA}/instance/service-accounts/default/token" | jq -r '.access_token'
}

if ! command -v docker >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    ca-certificates curl docker.io jq
fi
systemctl enable --now docker

PROJECT_ID="$(metadata project-id)"
IMAGE="$(metadata container-image)"
SECRET_NAME="$(metadata grafana-token-secret)"
TOKEN="$(access_token)"

install -d -m 0700 /etc/portfolio-alloy
curl -fsS \
  -H "Authorization: Bearer ${TOKEN}" \
  "https://secretmanager.googleapis.com/v1/projects/${PROJECT_ID}/secrets/${SECRET_NAME}/versions/latest:access" \
  | jq -r '.payload.data' | tr '_-' '/+' | base64 -d \
  > /etc/portfolio-alloy/grafana-token
chmod 0600 /etc/portfolio-alloy/grafana-token

cat > /etc/portfolio-alloy/runtime.env <<EOF
GRAFANA_PROMETHEUS_URL=$(metadata grafana-prometheus-url)
GRAFANA_PROMETHEUS_USER=$(metadata grafana-prometheus-user)
GRAFANA_API_KEY=$(cat /etc/portfolio-alloy/grafana-token)
GRAFANA_LOKI_URL=$(metadata grafana-loki-url)
GRAFANA_LOKI_USER=$(metadata grafana-loki-user)
EOF
chmod 0600 /etc/portfolio-alloy/runtime.env

cat > /usr/local/sbin/refresh-cloud-run-identity-token <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
METADATA="http://metadata.google.internal/computeMetadata/v1"
HEADER="Metadata-Flavor: Google"
AUDIENCE="$(curl -fsS -H "${HEADER}" "${METADATA}/instance/attributes/cloud-run-audience")"
install -d -m 0755 /var/run/cloud-run-identity
curl -fsS -G -H "${HEADER}" \
  --data-urlencode "audience=${AUDIENCE}" \
  --data-urlencode "format=full" \
  "${METADATA}/instance/service-accounts/default/identity" \
  > /var/run/cloud-run-identity/token.tmp
chmod 0644 /var/run/cloud-run-identity/token.tmp
mv /var/run/cloud-run-identity/token.tmp /var/run/cloud-run-identity/token
EOF
chmod 0755 /usr/local/sbin/refresh-cloud-run-identity-token

cat > /etc/systemd/system/cloud-run-identity-token.service <<'EOF'
[Unit]
Description=Refresh Cloud Run identity token for Grafana Alloy

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/refresh-cloud-run-identity-token
EOF

cat > /etc/systemd/system/cloud-run-identity-token.timer <<'EOF'
[Unit]
Description=Refresh Cloud Run identity token every 30 minutes

[Timer]
OnBootSec=1min
OnUnitActiveSec=30min
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
/usr/local/sbin/refresh-cloud-run-identity-token
systemctl enable --now cloud-run-identity-token.timer

cat > /usr/local/sbin/update-portfolio-metrics-scraper <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

METADATA="http://metadata.google.internal/computeMetadata/v1"
HEADER="Metadata-Flavor: Google"
IMAGE="$(curl -fsS -H "${HEADER}" "${METADATA}/instance/attributes/container-image")"
REGISTRY="${IMAGE%%/*}"
TOKEN="$(curl -fsS -H "${HEADER}" \
  "${METADATA}/instance/service-accounts/default/token" | jq -r '.access_token')"

echo "${TOKEN}" | docker login -u oauth2accesstoken --password-stdin "https://${REGISTRY}"
docker pull "${IMAGE}"
docker logout "${REGISTRY}" >/dev/null

TARGET_IMAGE_ID="$(docker image inspect "${IMAGE}" --format '{{.Id}}')"
CURRENT_IMAGE_ID="$(docker inspect portfolio-metrics-scraper \
  --format '{{.Image}}' 2>/dev/null || true)"
if [[ "${TARGET_IMAGE_ID}" == "${CURRENT_IMAGE_ID}" ]]; then
  exit 0
fi

docker rm -f portfolio-metrics-scraper 2>/dev/null || true
docker run -d \
  --name portfolio-metrics-scraper \
  --restart=always \
  --env-file /etc/portfolio-alloy/runtime.env \
  --volume /var/run/cloud-run-identity:/var/run/cloud-run-identity:ro \
  "${IMAGE}"
docker image prune -f >/dev/null
EOF
chmod 0755 /usr/local/sbin/update-portfolio-metrics-scraper

cat > /etc/systemd/system/portfolio-metrics-scraper-update.service <<'EOF'
[Unit]
Description=Pull and atomically update the Portfolio Grafana Alloy scraper
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/update-portfolio-metrics-scraper
EOF

cat > /etc/systemd/system/portfolio-metrics-scraper-update.timer <<'EOF'
[Unit]
Description=Check for Portfolio Grafana Alloy updates every five minutes

[Timer]
OnBootSec=30s
OnUnitActiveSec=5min
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
/usr/local/sbin/update-portfolio-metrics-scraper
systemctl enable --now portfolio-metrics-scraper-update.timer
