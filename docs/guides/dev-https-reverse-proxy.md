# Dev HTTPS Reverse Proxy (Tomcat + Devtools)

This is the recommended setup for this project on a dev machine.

## Short Answer

- Keep Spring Boot embedded Tomcat (default in this repo).
- Keep devtools.
- Add HTTPS at a reverse proxy (Caddy or Nginx).
- Do **not** switch away from Tomcat just to get TLS.

## Why This Is Best Practice For Dev

- Spring Boot + devtools restart loop stays simple.
- TLS config/certs are isolated in one proxy layer.
- You can rotate certs without touching app runtime.
- You can expose only 443/80 publicly and keep app on `127.0.0.1:8082`.

## Confirm Current Server

This repo uses `spring-boot-starter-web` with embedded Tomcat by default:
- [build.gradle.kts](../../build.gradle.kts)

No Jetty/Undertow replacement is configured.

## Recommended Topology

```text
Browser / Remote Crawler
        |
      HTTPS :443
        |
  Caddy or Nginx (TLS terminate)
        |
     HTTP 127.0.0.1:8082
        |
 Spring Boot (Tomcat + devtools)
```

## 1) Start Spring Boot Locally

Use local-only bind for backend app:

```bash
./gradlew bootRun --args='--server.address=127.0.0.1 --server.port=8082'
```

Optional: keep this in your dev profile if desired.

## 2) Caddy (Easiest)

Ready config:
- [ops/proxy/Caddyfile](../../ops/proxy/Caddyfile)

Steps:
1. Edit hostnames/IPs in `ops/proxy/Caddyfile` (`minti9`, `192.168.1.101`, etc.).
   Current defaults in this repo are `localhost`, `minti9`, and `192.168.1.101`.
2. Run Caddy:

```bash
caddy run --config ops/proxy/Caddyfile
```

3. Trust Caddy local CA once:

```bash
sudo caddy trust
```

Notes:
- Config uses `tls internal` for LAN/dev certificates.
- HTTP is redirected to HTTPS.

## 3) Nginx (Manual But Common)

Ready config:
- [ops/proxy/nginx/spring-search-tempo.conf](../../ops/proxy/nginx/spring-search-tempo.conf)

Steps:
1. Copy file to nginx include path (for example `/etc/nginx/conf.d/`).
2. Update `server_name` and certificate paths.
3. Validate + reload:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

Notes:
- You must provision cert/key yourself (`mkcert`, OpenSSL, private CA, etc.).
- HTTP is redirected to HTTPS.

## 4) Remote Crawler With HTTPS

Remote crawler is now HTTPS-first and warns loudly when it falls back to HTTP.

Test:

```bash
java -jar remote-crawler-<version>.jar -s https://minti9 -u <user> -p <pass> test
```

If using self-signed/private CA certs, use truststore setup:
- [remote-crawler-tls-lan-setup.md](remote-crawler-tls-lan-setup.md)

## 5) Operational Guardrails

- Keep app backend private (`127.0.0.1` bind) when proxy is on same machine.
- Expose only proxy ports externally.
- Prefer HTTPS URLs in all scripts/config.
- Treat any HTTP fallback warning as migration debt, not steady state.

## 6) Do You Need To Switch App Server?

No.

Caddy/Nginx works cleanly with embedded Tomcat. Switching to Jetty/Undertow is optional and unrelated to this TLS pattern.
