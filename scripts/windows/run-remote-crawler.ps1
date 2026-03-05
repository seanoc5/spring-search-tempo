param(
    [ValidateSet("crawl", "status", "onboard")]
    [string]$Mode = "crawl",

    [string]$JarPath = "C:\Tempo\remote-crawler\remote-crawler-0.1.99.jar",
    [string]$ServerUrl = "http://minti9:8082",
    [string]$Username = "admin",
    [string]$Password = "",
    [string]$HostName = "",
    [string]$OnboardPaths = "",
    [int]$OnboardMaxDepth = 15,
    [string]$LogDir = "C:\Tempo\remote-crawler\logs"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not (Test-Path $JarPath)) {
    throw "JAR not found: $JarPath"
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "java is not available in PATH."
}

if ([string]::IsNullOrWhiteSpace($HostName)) {
    $HostName = $env:COMPUTERNAME.ToLowerInvariant()
}

if ([string]::IsNullOrWhiteSpace($Password)) {
    if (-not [string]::IsNullOrWhiteSpace($env:TEMPO_CRAWLER_PASSWORD)) {
        $Password = $env:TEMPO_CRAWLER_PASSWORD
    } else {
        $Password = "password"
    }
}

New-Item -Path $LogDir -ItemType Directory -Force | Out-Null

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
Write-Host ("Logging to {0}" -f $logFile)

& java @args *>&1 | Tee-Object -FilePath $logFile
$exitCode = $LASTEXITCODE

Write-Host ("Exit code: {0}" -f $exitCode)
exit $exitCode
