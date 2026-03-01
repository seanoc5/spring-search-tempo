# Windows Deployment with jpackage

This guide packages Spring Search Tempo as a native Windows installer (`.msi` or `.exe`) with a bundled Java runtime.

## Why this approach

- No Java installation required on user machines.
- Better fit for non-technical users than running Docker Desktop.
- Keeps filesystem behavior native to Windows (important for crawl paths and profile auto-detection).

## Prerequisites (build machine)

- Windows 11
- JDK 21+ (`jpackage` must be in `PATH`)
- WiX Toolset (required by `jpackage` for Windows installer creation)
- This repository checked out

## Build installer

From repo root on Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\build-jpackage.ps1 `
  -InstallerType msi `
  -InstallMode per-user `
  -DbHost minti9 `
  -DbPort 5432 `
  -DbName tempo `
  -DbUser tempo `
  -DbPassword "password"
```

Output goes to:

`build\jpackage\dist\`

Common alternatives:

```powershell
# Build EXE instead of MSI
powershell -ExecutionPolicy Bypass -File .\scripts\windows\build-jpackage.ps1 -InstallerType exe

# Per-machine install package (admin install model)
powershell -ExecutionPolicy Bypass -File .\scripts\windows\build-jpackage.ps1 -InstallMode per-machine

# Override app version used by installer metadata
powershell -ExecutionPolicy Bypass -File .\scripts\windows\build-jpackage.ps1 -AppVersion 0.1.10
```

## What the packaged app is configured with

The launcher is baked with JVM system properties:

- `JDBC_DATABASE_URL=jdbc:postgresql://<DbHost>:<DbPort>/<DbName>`
- `JDBC_DATABASE_USERNAME=<DbUser>`
- `JDBC_DATABASE_PASSWORD=<DbPassword>`
- `spring.profiles.active=windows`

This means no PostgreSQL install is needed on Windows clients when using your LAN host (`minti9`).

## Install on target Windows machine

### Interactive install

1. Copy installer from `build\jpackage\dist\`.
2. Double-click installer.
3. Launch `SpringSearchTempo` from Start Menu.

### Silent install (MSI)

```powershell
msiexec /i "SpringSearchTempo-0.1.9.msi" /qn /norestart
```

## Upgrade strategy

- Build a new installer for each release.
- Keep `--win-upgrade-uuid` unchanged (already fixed in script) so installed copies upgrade in place.
- Users can install the new package directly over the old one.

## Operational notes

- This packaging creates a desktop app launcher, not a Windows service.
- If you need auto-start/background behavior, add a Scheduled Task or service wrapper in a follow-up step.
- Database credentials are stored in launcher config on the client machine; treat endpoints accordingly.

## Troubleshooting

- `jpackage is not recognized`: install JDK 21+ and ensure `%JAVA_HOME%\bin` is on `PATH`.
- MSI build fails quickly on Windows: verify WiX Toolset is installed and available in `PATH`.
- App starts but cannot connect DB: verify client can resolve and reach `minti9:5432` and credentials are valid.
