# Spring Search Tempo Monitoring

This guide covers monitoring and observability for Spring Search Tempo.

## Metrics Overview

The application exposes Prometheus metrics at `/actuator/prometheus`.

### Search Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `tempo_search_duration_seconds` | Histogram | Search operation latency (p50, p95, p99) |
| `tempo_search_requests_total` | Counter | Total search requests by type/operation/success |
| `tempo_search_errors_total` | Counter | Search errors by type/operation/exception |
| `tempo_search_files_total` | Gauge | Total indexed files |
| `tempo_search_chunks_total` | Gauge | Total content chunks |
| `tempo_search_chunks_with_embedding` | Gauge | Chunks with vector embeddings |
| `tempo_search_chunks_with_nlp` | Gauge | Chunks with NLP processing |
| `tempo_search_chunks_with_entities` | Gauge | Chunks with named entities |
| `tempo_search_embedding_available` | Gauge | Embedding service availability (1/0) |

### Search Types

The `tempo_search_duration_seconds` histogram includes these type/operation tags:

| Type | Operation | Description |
|------|-----------|-------------|
| `fts` | `all` | Full-text search across all content |
| `fts` | `files` | Full-text search files only |
| `fts` | `chunks` | Full-text search chunks only |
| `fts` | `filtered` | Full-text search with filters |
| `semantic` | `query` | Semantic similarity search |
| `semantic` | `similar` | Find similar chunks by ID |
| `hybrid` | `query` | Hybrid (FTS + semantic) search |
| `suggest` | `query` | Autocomplete suggestions |

## Health Indicators

The application includes custom health indicators accessible at `/actuator/health`:

### Embedding Service Health

Check embedding service status at `/actuator/health/embeddingService`:

```json
{
  "status": "UP",
  "details": {
    "model": "mxbai-embed-large",
    "gpuMode": "GPU",
    "chunksWithEmbedding": 12500,
    "chunksPending": 500,
    "coveragePercent": "96.2%"
  }
}
```

When embedding is unavailable:

```json
{
  "status": "DOWN",
  "details": {
    "reason": "Ollama embedding service unavailable",
    "hint": "Start Ollama with: ollama serve"
  }
}
```

## Grafana Dashboard

A pre-built Grafana dashboard is included at `docs/monitoring/grafana-dashboard.json`.

### Importing the Dashboard

1. Open Grafana
2. Navigate to **Dashboards** > **Import**
3. Upload `grafana-dashboard.json` or paste its contents
4. Select your Prometheus datasource
5. Click **Import**

### Dashboard Panels

The dashboard includes:

**Search Overview Row:**
- Total Files (stat)
- Total Chunks (stat)
- Embedding Coverage (gauge)
- Embedding Service Status (UP/DOWN)
- NLP Processed Chunks (stat)
- Chunks with Entities (stat)

**Search Latency Row:**
- Full-Text Search Latency (p50, p95, p99)
- Semantic & Hybrid Search Latency (p50, p95)

**Request Volume & Errors Row:**
- Search Requests/sec by type
- Search Errors/sec by exception type

**JVM & Application Health Row:**
- JVM Heap Memory usage
- JVM Thread counts

**Batch Jobs Row:**
- Batch Job Executions by job name and status
- Batch Job Average Duration

## Prometheus Configuration

Add to your `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'spring-search-tempo'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8082']
    scrape_interval: 15s
```

## Alerting (Optional)

Example Prometheus alerting rules:

```yaml
groups:
  - name: spring-search-tempo
    rules:
      - alert: EmbeddingServiceDown
        expr: tempo_search_embedding_available == 0
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Embedding service is down"
          description: "Ollama embedding service has been unavailable for 5 minutes"

      - alert: HighSearchLatency
        expr: histogram_quantile(0.95, rate(tempo_search_duration_seconds_bucket{type="fts"}[5m])) > 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High search latency detected"
          description: "95th percentile FTS latency is above 1 second"

      - alert: SearchErrorRate
        expr: rate(tempo_search_errors_total[5m]) > 0.1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "High search error rate"
          description: "More than 0.1 search errors per second"
```

## Application Configuration

### Enabling Metrics

Metrics are enabled by default. To customize, add to `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: spring-search-tempo
```

### Customizing Health Indicators

```yaml
management:
  health:
    embeddingService:
      enabled: true
  endpoint:
    health:
      show-details: always  # or when_authorized
```

## Quick Start

1. **Start Prometheus** (with the config above)
2. **Start Grafana** (typically at http://localhost:3000)
3. **Add Prometheus datasource** in Grafana
4. **Import dashboard** from `grafana-dashboard.json`
5. **Start the application** with `./gradlew bootRun`

Metrics will begin appearing within the scrape interval (15s default).

## Useful PromQL Queries

```promql
# Search request rate by type
sum(rate(tempo_search_requests_total[5m])) by (type)

# Error rate percentage
100 * sum(rate(tempo_search_errors_total[5m])) / sum(rate(tempo_search_requests_total[5m]))

# Average search latency by type
histogram_quantile(0.5, rate(tempo_search_duration_seconds_bucket[5m]))

# Embedding coverage percentage
100 * tempo_search_chunks_with_embedding / tempo_search_chunks_total

# Chunks pending embedding
tempo_search_chunks_total - tempo_search_chunks_with_embedding
```

## Troubleshooting

**No metrics appearing?**
- Check `/actuator/prometheus` is accessible
- Verify Prometheus scrape config target is correct
- Check firewall/network connectivity

**Health status not updating?**
- Health indicators cache for 10 seconds by default
- Add `?refresh` to force refresh: `/actuator/health?refresh`

**Embedding service showing DOWN?**
- Ensure Ollama is running: `ollama serve`
- Check Ollama model is available: `ollama list`
- Verify network connectivity to Ollama (default: localhost:11434)
