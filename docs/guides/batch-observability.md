# Batch Observability (Actuator + Prometheus + Grafana)

This project now exposes batch/job-run metrics suitable for Prometheus + Grafana.

## Metrics added

Custom metrics (Prometheus names):

- `tempo_batch_jobrun_started_total{job_name=...}`
- `tempo_batch_jobrun_completed_total{job_name=...,status=...}`
- `tempo_batch_jobrun_duration_seconds_*{job_name=...,status=...}`
- `tempo_batch_jobrun_status{status=...}`
- `tempo_batch_jobrun_total`
- `tempo_batch_jobrun_stale_running`

The `/batch` dashboard now includes an **Operations Snapshot** panel (auto-refresh every 30s)
showing running/stale counts and recent 15m outcomes, plus quick links to Prometheus/Grafana.
It also performs an automatic lightweight **reality check** on full page load (max once per 30s)
to reconcile stale entries that still appear as running.

Notes:

- `job_name` is normalized for high-cardinality job patterns:
  - `emailQuickSyncJob-<id>` -> `emailQuickSyncJob`
  - `oneDriveSync_<id>` -> `oneDriveSync`

## Actuator endpoint

Prometheus scrape endpoint:

- `GET /actuator/prometheus`

Security:

- `/actuator/**` is admin-only by default in this app.
- For local dev, the monitoring compose file assumes basic auth `admin/admin`.

## Run Prometheus + Grafana locally

From repo root:

```bash
docker compose -f ops/monitoring/docker-compose.monitoring.yml up -d
```

Open:

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)

Provisioned dashboard:

- `Spring Search Tempo / Spring Search Tempo - Batch Overview`

## Scrape target

Prometheus scrapes:

- `host.docker.internal:8082/actuator/prometheus`

If your app runs on a different host/port, update:

- `ops/monitoring/prometheus/prometheus.yml`

## Suggested alerts

- stale jobs present: `tempo_batch_jobrun_stale_running > 0`
- elevated failures: `sum(rate(tempo_batch_jobrun_completed_total{status="FAILED"}[15m])) > 0`
- no completions during expected windows for critical jobs.
