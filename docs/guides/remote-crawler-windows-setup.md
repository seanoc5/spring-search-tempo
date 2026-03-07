# Remote Crawler - Windows Setup Guide

Deploy the remote crawler as a scheduled task on Windows 10/11 to automatically sync files to your Spring Search Tempo server.

## Prerequisites

- Windows 10 or 11
- Java 21+ installed and in PATH
- Network access to your Spring Search Tempo server
- PowerShell (included with Windows)

## Quick Start

### 1. Create the installation folder

```powershell
New-Item -Path "C:\Tempo\remote-crawler" -ItemType Directory -Force
```

### 2. Download the files

**Option A: From GitHub Release**
```powershell
# Requires GitHub CLI (gh)
gh release download remote-crawler-v0.2.1 -R seanoc5/spring-search-tempo -D "C:\Tempo\remote-crawler"
```

**Option B: Manual download**
- Download `remote-crawler-0.2.1.jar` from [GitHub Releases](https://github.com/seanoc5/spring-search-tempo/releases)
- Copy to `C:\Tempo\remote-crawler\`

### 3. Copy the PowerShell scripts

Copy these files from `scripts/windows/` in the repository to `C:\Tempo\remote-crawler\`:
- `run-remote-crawler.ps1`
- `install-remote-crawler-task.ps1`

### 4. Configure credentials

**Option A: Config file (recommended)**

Create `C:\Tempo\remote-crawler\config.json`:

```json
{
    "serverUrl": "http://your-server:8082",
    "username": "admin",
    "password": "your-password"
}
```

**Option B: Environment variables**

```powershell
# Set permanently for your user
[Environment]::SetEnvironmentVariable("TEMPO_SERVER_URL", "http://your-server:8082", "User")
[Environment]::SetEnvironmentVariable("TEMPO_CRAWLER_USERNAME", "admin", "User")
[Environment]::SetEnvironmentVariable("TEMPO_CRAWLER_PASSWORD", "your-password", "User")
```

**Priority order**: Command-line parameters > Environment variables > Config file > Defaults

### 5. Test connectivity

```powershell
cd C:\Tempo\remote-crawler
java -jar remote-crawler-0.2.1.jar -s http://your-server:8082 -u admin -p password status
```

You should see your assigned crawl configurations.

### 6. Install the scheduled task

Run PowerShell **as Administrator**:

```powershell
cd C:\Tempo\remote-crawler
.\install-remote-crawler-task.ps1
```

This creates a task that runs every 4 hours by default.

## Configuration Options

### install-remote-crawler-task.ps1

| Parameter | Default | Description |
|-----------|---------|-------------|
| `-Trigger` | `HOURLY` | Schedule type: `HOURLY`, `DAILY`, or `ONLOGON` |
| `-IntervalHours` | `4` | Hours between runs (HOURLY trigger only) |
| `-StartTime` | `02:00` | First run time (HH:mm format) |
| `-TaskName` | `RemoteCrawlerCrawl` | Name shown in Task Scheduler |
| `-RunnerScriptPath` | `C:\Tempo\remote-crawler\run-remote-crawler.ps1` | Path to runner script |
| `-RunAsUser` | (current user) | Optional: run as specific user |

**Examples:**

```powershell
# Every 4 hours starting at midnight (default)
.\install-remote-crawler-task.ps1

# Every 2 hours starting at 6 AM
.\install-remote-crawler-task.ps1 -IntervalHours 2 -StartTime "06:00"

# Once daily at 3 AM
.\install-remote-crawler-task.ps1 -Trigger DAILY -StartTime "03:00"

# On user logon
.\install-remote-crawler-task.ps1 -Trigger ONLOGON
```

### run-remote-crawler.ps1

| Parameter | Default | Description |
|-----------|---------|-------------|
| `-Mode` | `crawl` | Operation: `crawl`, `status`, or `onboard` |
| `-JarPath` | `C:\Tempo\remote-crawler\remote-crawler-0.2.1.jar` | Path to JAR file |
| `-ServerUrl` | (from config) | Spring Search Tempo server URL |
| `-Username` | (from config) | Authentication username |
| `-Password` | (from config) | Authentication password |
| `-HostName` | (auto-detected) | Override hostname sent to server |
| `-LogDir` | `C:\Tempo\remote-crawler\logs` | Directory for log files |
| `-ConfigFile` | `config.json` (same folder as JAR) | Path to JSON config file |

**Examples:**

```powershell
# Run crawl with custom server
.\run-remote-crawler.ps1 -ServerUrl "http://192.168.1.100:8082"

# Check status only
.\run-remote-crawler.ps1 -Mode status

# Run onboard (initial discovery)
.\run-remote-crawler.ps1 -Mode onboard
```

## Managing the Task

### View task status
```powershell
schtasks /Query /TN "RemoteCrawlerCrawl" /V /FO LIST
```

### Run immediately
```powershell
schtasks /Run /TN "RemoteCrawlerCrawl"
```

### Disable temporarily
```powershell
schtasks /Change /TN "RemoteCrawlerCrawl" /DISABLE
```

### Enable again
```powershell
schtasks /Change /TN "RemoteCrawlerCrawl" /ENABLE
```

### Remove completely
```powershell
schtasks /Delete /TN "RemoteCrawlerCrawl" /F
```

## Viewing Logs

Logs are stored in `C:\Tempo\remote-crawler\logs\` with timestamps:

```powershell
# View recent logs
Get-ChildItem "C:\Tempo\remote-crawler\logs" | Sort-Object LastWriteTime -Descending | Select-Object -First 5

# Tail the latest log
Get-Content (Get-ChildItem "C:\Tempo\remote-crawler\logs\*.log" | Sort-Object LastWriteTime -Descending | Select-Object -First 1) -Tail 50
```

## Troubleshooting

### "java is not available in PATH"

Install Java 21+ and add to PATH:
```powershell
# Check if java is available
java -version

# If not, add to PATH (adjust path as needed)
$env:PATH += ";C:\Program Files\Java\jdk-21\bin"
```

### Task runs but no files indexed

1. Check that crawl configs are assigned to this host in the server UI
2. Verify connectivity: `.\run-remote-crawler.ps1 -Mode status`
3. Check logs for errors

### Authentication failures

1. Verify credentials work: `.\run-remote-crawler.ps1 -Mode status`
2. Check `TEMPO_CRAWLER_PASSWORD` environment variable is set correctly
3. Ensure the user exists on the server with appropriate permissions

### Task doesn't start

1. Open Task Scheduler and check the task's "History" tab
2. Verify the task is enabled
3. Check that the paths in the task action are correct

## File Structure

After setup, your installation should look like:

```
C:\Tempo\remote-crawler\
├── remote-crawler-0.2.1.jar         # The crawler application
├── run-remote-crawler.ps1           # Runner script (called by task)
├── install-remote-crawler-task.ps1  # Installer (run once)
├── config.json                      # Credentials and server URL
└── logs\                            # Created automatically
    ├── remote-crawler-crawl-20240115-140000.log
    └── ...
```

## Updating

To update to a new version:

1. Download the new JAR file
2. Update the `-JarPath` default in `run-remote-crawler.ps1` or pass it explicitly
3. No need to reinstall the scheduled task unless script paths change

```powershell
# Download new version
gh release download remote-crawler-v0.3.0 -R seanoc5/spring-search-tempo -D "C:\Tempo\remote-crawler"

# Update script default (or edit run-remote-crawler.ps1)
# The task will use the new JAR on next run
```

## Appendix: Installing GitHub CLI (gh)

The GitHub CLI makes downloading releases easy. Here are several ways to install it on Windows 11:

### Option 1: WinGet (Recommended)

WinGet is built into Windows 11. Open PowerShell or Command Prompt:

```powershell
winget install GitHub.cli
```

Restart your terminal after installation.

### Option 2: Scoop

If you use [Scoop](https://scoop.sh/):

```powershell
scoop install gh
```

### Option 3: Chocolatey

If you use [Chocolatey](https://chocolatey.org/):

```powershell
choco install gh
```

### Option 4: MSI Installer

1. Go to [GitHub CLI Releases](https://github.com/cli/cli/releases/latest)
2. Download `gh_X.X.X_windows_amd64.msi`
3. Run the installer

### Authenticate with GitHub

After installation, authenticate to access releases:

```powershell
gh auth login
```

Follow the prompts to authenticate via browser or token.

### Verify Installation

```powershell
gh --version
gh release list -R seanoc5/spring-search-tempo
```
