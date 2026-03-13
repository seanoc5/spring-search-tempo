param(
    [ValidateSet("crawl", "status", "onboard")]
    [string]$Mode = "crawl",

    [string]$JarPath = "C:\Tempo\remote-crawler\remote-crawler-0.2.2.jar",
    [string]$ServerUrl = "",
    [string]$Username = "",
    [string]$Password = "",
    [string]$TrustStorePath = "",
    [string]$TrustStorePassword = "",
    [string]$HostName = "",
    [string]$OnboardPaths = "",
    [int]$OnboardMaxDepth = 15,
    [string]$LogDir = "C:\Tempo\remote-crawler\logs",
    [string]$ConfigFile = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Determine config file path (default: config.json in same folder as JAR)
if ([string]::IsNullOrWhiteSpace($ConfigFile)) {
    $ConfigFile = Join-Path (Split-Path $JarPath -Parent) "config.json"
}

# Load config file if it exists
$config = @{}
if (Test-Path $ConfigFile) {
    try {
        $config = Get-Content $ConfigFile -Raw | ConvertFrom-Json
        Write-Host ("Loaded config from {0}" -f $ConfigFile)
    } catch {
        Write-Warning ("Failed to parse config file {0}: {1}" -f $ConfigFile, $_.Exception.Message)
    }
}

# Priority: CLI param > env var > config file > default
# ServerUrl
if ([string]::IsNullOrWhiteSpace($ServerUrl)) {
    if (-not [string]::IsNullOrWhiteSpace($env:TEMPO_SERVER_URL)) {
        $ServerUrl = $env:TEMPO_SERVER_URL
    } elseif ($config.serverUrl) {
        $ServerUrl = $config.serverUrl
    } else {
        $ServerUrl = "https://minti9"
    }
}

# Username
if ([string]::IsNullOrWhiteSpace($Username)) {
    if (-not [string]::IsNullOrWhiteSpace($env:TEMPO_CRAWLER_USERNAME)) {
        $Username = $env:TEMPO_CRAWLER_USERNAME
    } elseif ($config.username) {
        $Username = $config.username
    } else {
        $Username = "admin"
    }
}

# Password
if ([string]::IsNullOrWhiteSpace($Password)) {
    if (-not [string]::IsNullOrWhiteSpace($env:TEMPO_CRAWLER_PASSWORD)) {
        $Password = $env:TEMPO_CRAWLER_PASSWORD
    } elseif ($config.password) {
        $Password = $config.password
    } else {
        $Password = "password"
    }
}

# TrustStorePath (optional, useful for self-signed TLS)
if ([string]::IsNullOrWhiteSpace($TrustStorePath)) {
    if (-not [string]::IsNullOrWhiteSpace($env:TEMPO_TRUSTSTORE_PATH)) {
        $TrustStorePath = $env:TEMPO_TRUSTSTORE_PATH
    } elseif ($config.trustStorePath) {
        $TrustStorePath = $config.trustStorePath
    }
}

# TrustStorePassword (optional, defaults to 'changeit' if truststore path is set)
if ([string]::IsNullOrWhiteSpace($TrustStorePassword)) {
    if (-not [string]::IsNullOrWhiteSpace($env:TEMPO_TRUSTSTORE_PASSWORD)) {
        $TrustStorePassword = $env:TEMPO_TRUSTSTORE_PASSWORD
    } elseif ($config.trustStorePassword) {
        $TrustStorePassword = $config.trustStorePassword
    } elseif (-not [string]::IsNullOrWhiteSpace($TrustStorePath)) {
        $TrustStorePassword = "changeit"
    }
}

if (-not (Test-Path $JarPath)) {
    throw "JAR not found: $JarPath"
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "java is not available in PATH."
}

if ([string]::IsNullOrWhiteSpace($HostName)) {
    $HostName = $env:COMPUTERNAME.ToLowerInvariant()
}

New-Item -Path $LogDir -ItemType Directory -Force | Out-Null

$javaArgs = @()
if (-not [string]::IsNullOrWhiteSpace($TrustStorePath)) {
    $javaArgs += "-Djavax.net.ssl.trustStore=$TrustStorePath"
    if (-not [string]::IsNullOrWhiteSpace($TrustStorePassword)) {
        $javaArgs += "-Djavax.net.ssl.trustStorePassword=$TrustStorePassword"
    }
}

$args = @(
    "-jar", $JarPath,
    "-s", $ServerUrl,
    "-u", $Username,
    "-p", $Password,
    "-H", $HostName
)

switch ($Mode) {
    "crawl" {
        $args += "crawl"
    }
    "status" {
        $args += "status"
    }
    "onboard" {
        $args += "onboard"
        $args += "--yes"
        $args += "--max-depth"
        $args += "$OnboardMaxDepth"
        if (-not [string]::IsNullOrWhiteSpace($OnboardPaths)) {
            $args += "-d"
            $args += $OnboardPaths
        }
    }
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logFile = Join-Path $LogDir ("remote-crawler-{0}-{1}.log" -f $Mode, $timestamp)

Write-Host ("Running mode={0} host={1} server={2}" -f $Mode, $HostName, $ServerUrl)
if (-not [string]::IsNullOrWhiteSpace($TrustStorePath)) {
    Write-Host ("Using truststore: {0}" -f $TrustStorePath)
}
Write-Host ("Logging to {0}" -f $logFile)

& java @javaArgs @args *>&1 | Tee-Object -FilePath $logFile
$exitCode = $LASTEXITCODE

Write-Host ("Exit code: {0}" -f $exitCode)
exit $exitCode
