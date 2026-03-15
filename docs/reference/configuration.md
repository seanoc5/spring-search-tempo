# Configuration Reference

Quick reference for Spring Search Tempo configuration options.

## Application Properties

### Server

```yaml
server:
  port: 8082                    # Web server port
  servlet:
    context-path: /             # Base URL path
```

### Database

```yaml
spring:
  datasource:
    url: jdbc:postgresql://minti9:5432/tempo
    username: tempo
    password: password
  jpa:
    hibernate:
      ddl-auto: update          # Auto-update schema on startup
    show-sql: false             # Log SQL (debug only)
```

**Notes:**
- Use `ddl-auto: update` for development
- PostgreSQL-specific features require running `docs/sql/essential-postgres-features.sql`

### Batch Jobs

```yaml
spring:
  batch:
    job:
      enabled: true             # Run jobs on startup
      name: fsCrawlJob          # Specific job to run (optional)
    jdbc:
      initialize-schema: always # Create batch tables
```

**Job Names:**
- `fsCrawlJob` - File system crawling
- `nlpProcessingJob` - NLP processing
- `emailQuickSyncJob` - Email synchronization
- `chunkingJob` - Content chunking

### NLP Processing

```yaml
app:
  nlp:
    auto-trigger: true          # Run NLP after file crawl
    batch-size: 100             # Chunks per batch
```

### Crawl Configuration

See [Crawl Configuration Guide](../guides/crawl-configuration.md) for detailed options.

```yaml
app:
  crawl:
    defaults:
      max-depth: 10
      follow-links: false
      parallel: false
    crawls:
      - name: "MY_CRAWL"
        label: "My Documents"
        enabled: true
        start-paths:
          - "/home/user/Documents"
```

### Scheduling

```yaml
app:
  scheduling:
    crawl:
      enabled: false              # Enable scheduled file crawling
      cron: "0 0 1 * * *"        # Daily at 01:00
      zone: "America/New_York"    # Timezone for cron
      run-missed-on-startup: true # Catch up if missed
      startup-grace-minutes: 5    # Skip if within grace period
    email:
      enabled: false              # Enable scheduled email sync
      cron: "0 0 */4 * * *"      # Every 4 hours
      zone: "America/New_York"
      run-missed-on-startup: true
      startup-grace-minutes: 5
      trigger-after-crawl: false  # Chain email sync after file crawl
```

### Email Configuration

```yaml
app:
  email:
    enabled: true
    quick-sync-folders:
      - "INBOX"
      - "Sent"
    accounts:
      - name: "personal-gmail"
        email: "${GMAIL_EMAIL:}"
        provider: "GMAIL"           # GMAIL, WORKMAIL, or GENERIC_IMAP
        credential-env-var: "GMAIL_APP_PASSWORD"
        enabled: true
      - name: "work-email"
        email: "${WORKMAIL_EMAIL:}"
        provider: "WORKMAIL"
        imap-host: "imap.mail.us-east-1.awsapps.com"
        imap-port: 993
        credential-env-var: "WORKMAIL_PASSWORD"
        enabled: true
```

**Environment variables for email:**
```bash
GMAIL_EMAIL=your.email@gmail.com
GMAIL_APP_PASSWORD=your-app-password
WORKMAIL_EMAIL=your.email@company.com
WORKMAIL_PASSWORD=your-password
```

### Security

```yaml
spring:
  security:
    user:
      name: user                # Default username
      password: password        # Default password
```

**Production**: Configure proper authentication via `SecurityConfig.kt`.

### Logging

```yaml
logging:
  level:
    root: INFO
    com.oconeco.spring_search_tempo: DEBUG
    org.springframework.batch: INFO
    org.hibernate.SQL: DEBUG    # Log SQL queries
```

### Actuator

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,batch,top
  endpoint:
    health:
      show-details: always
```

**Endpoints:**
- `/actuator/health` - Health check
- `/actuator/metrics` - Application metrics
- `/actuator/batch` - Batch job info
- `/actuator/top` - Running processes (custom)

## Environment Variables

Override any property via environment variables:

```bash
# Database
SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/db
SPRING_DATASOURCE_USERNAME=user
SPRING_DATASOURCE_PASSWORD=secret

# Server
SERVER_PORT=9090

# Profiles
SPRING_PROFILES_ACTIVE=production

# NLP
APP_NLP_AUTO_TRIGGER=false
```

## Profiles

| Profile | Config File | Description |
|---------|-------------|-------------|
| `local` | `application-local.yml` | Local development overrides |
| `linux` | `application-linux.yml` | Linux-specific paths (auto-detected) |
| `windows` | `application-windows.yml` | Windows-specific paths (auto-detected) |

**Auto-detection**: OS profile is activated automatically on startup.

**Manual override**:
```bash
./gradlew bootRun --args='--spring.profiles.active=windows'
```

## Common Configurations

### Development (Local)

Create `src/main/resources/application-local.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/tempo
  jpa:
    show-sql: true

logging:
  level:
    com.oconeco.spring_search_tempo: DEBUG

app:
  nlp:
    auto-trigger: false         # Disable for faster iteration
```

### Production

```yaml
spring:
  datasource:
    url: ${JDBC_DATABASE_URL}
    username: ${JDBC_DATABASE_USERNAME}
    password: ${JDBC_DATABASE_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate        # Don't auto-modify schema
    show-sql: false

logging:
  level:
    root: WARN
    com.oconeco.spring_search_tempo: INFO
```

### Disable All Jobs

```yaml
spring:
  batch:
    job:
      enabled: false
```

### Run Specific Job Only

```bash
./gradlew bootRun --args='--spring.batch.job.name=nlpProcessingJob'
```

## See Also

- [Crawl Configuration Guide](../guides/crawl-configuration.md)
- [NLP Processing Guide](../guides/nlp-processing.md)
- [Commands Reference](commands.md)
- [Troubleshooting](troubleshooting.md)
