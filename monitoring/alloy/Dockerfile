FROM grafana/alloy:v1.22.0
COPY config.alloy /etc/alloy/config.alloy
RUN GRAFANA_PROMETHEUS_URL=https://example.invalid/api/prom/push \
    GRAFANA_LOKI_URL=https://example.invalid/loki/api/v1/push \
    /bin/alloy validate /etc/alloy/config.alloy
# Cloud Run requires the container to listen on $PORT (default 8080).
# Alloy's built-in HTTP server (for UI/health) uses --server.http.listen-addr.
ENTRYPOINT ["/bin/alloy", "run", "--server.http.listen-addr=0.0.0.0:8080", "/etc/alloy/config.alloy"]
