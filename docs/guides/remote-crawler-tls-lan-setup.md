# Remote Crawler TLS Setup (LAN + Self-Signed)

Use this guide to run remote crawler over HTTPS on a trusted LAN, including self-signed/private-CA certificates.

## Goals

- HTTPS as default transport for remote crawler
- Keep temporary HTTP compatibility during migration
- Make certificate trust explicit so Java clients do not fail with PKIX errors

## 1) Pick a TLS Topology

### Option A (recommended): TLS at reverse proxy

- Run Spring Boot on HTTP internally (for example `localhost:8082`)
- Terminate TLS at Nginx/Caddy/Traefik on `443`
- Point crawler to `https://<server-hostname>`

Pros:
- easier certificate rotation
- cleaner standard ports (`443`)
- no app-level keystore changes required

### Option B: TLS directly in Spring Boot

Use this when you do not want a reverse proxy.

## 2) Generate a Certificate

For LAN and self-managed trust, either:
- use `mkcert` (best developer UX)
- or use OpenSSL and import trust manually

### 2.1 mkcert example

```bash
mkcert -install
mkcert tempo.local 192.168.1.100
openssl pkcs12 -export \
  -in tempo.local+1.pem \
  -inkey tempo.local+1-key.pem \
  -out tempo.p12 \
  -name tempo \
  -passout pass:changeit
```

### 2.2 OpenSSL self-signed example

```bash
openssl req -x509 -newkey rsa:4096 -sha256 -days 825 -nodes \
  -keyout tempo.key -out tempo.crt \
  -subj "/CN=tempo.local" \
  -addext "subjectAltName=DNS:tempo.local,IP:192.168.1.100"

openssl pkcs12 -export \
  -in tempo.crt -inkey tempo.key \
  -out tempo.p12 -name tempo \
  -passout pass:changeit
```

## 3) Configure Spring Boot HTTPS (Option B)

Place `tempo.p12` in a secure location (or in resources for dev only), then configure:

```yaml
server:
  port: 8443
  ssl:
    enabled: true
    key-store: file:/opt/work/springboot/spring-search-tempo/certs/tempo.p12
    key-store-password: changeit
    key-store-type: PKCS12
    key-alias: tempo
```

Notes:
- keep HTTP disabled in production unless you explicitly need migration support
- if you keep HTTP temporarily, make sure crawler uses HTTPS URL

## 4) Trust the Certificate on Crawler Hosts

`remote-crawler` uses Java TLS validation. Self-signed/private CA must be in a truststore.

### 4.1 Build a dedicated truststore

```bash
keytool -importcert -noprompt \
  -alias tempo-server \
  -file tempo.crt \
  -keystore tempo-truststore.jks \
  -storepass changeit
```

### 4.2 Windows scheduled-task runner

Use `scripts/windows/run-remote-crawler.ps1` with either config or env vars:

- `trustStorePath` / `trustStorePassword` in `config.json`
- or `TEMPO_TRUSTSTORE_PATH` / `TEMPO_TRUSTSTORE_PASSWORD`

The script now injects:
- `-Djavax.net.ssl.trustStore=...`
- `-Djavax.net.ssl.trustStorePassword=...`

## 5) Validate End-to-End

### 5.1 Server certificate probe

```bash
openssl s_client -connect <server-hostname>:443 -servername <server-hostname> </dev/null
```

### 5.2 Remote crawler connectivity/auth probe

```bash
java -jar remote-crawler-<version>.jar -s https://<server-hostname> -u <user> -p <pass> test
```

Expected:
- `Connectivity: OK`
- `Authentication: OK`
- no HTTP insecure warning

## 6) HTTP Migration Window

The CLI now does HTTPS-first negotiation and can fall back to HTTP.

When HTTP is used, it prints a loud warning banner. Treat this as temporary.

Recommended migration:
1. Enable HTTPS endpoint and truststore
2. Confirm `test` command works with `https://...`
3. Update all crawler configs/scripts to HTTPS URLs
4. Remove HTTP fallback routes/listeners when stable

## 7) Common Failure Modes

- `SSL error (possible HTTPS-to-HTTP mismatch on this port)`
  - HTTPS client is hitting a plain HTTP port
  - fix URL/port or proxy routing

- `SSL error (certificate validation failed)`
  - cert is not trusted by Java
  - import cert/CA into truststore used by crawler runtime

- `Hostname mismatch`
  - cert SAN/CN does not match URL host
  - regenerate cert with proper DNS/IP SAN entries
