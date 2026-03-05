param(
    [string]$TaskName = "RemoteCrawlerCrawl",
    [string]$RunnerScriptPath = "C:\Tempo\remote-crawler\run-remote-crawler.ps1",
    [ValidateSet("DAILY", "ONLOGON")]
    [string]$Trigger = "DAILY",
    [string]$StartTime = "02:00",
    [string]$RunAsUser = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not (Test-Path $RunnerScriptPath)) {
    throw "Runner script not found: $RunnerScriptPath"
}

$runnerCommand = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$RunnerScriptPath`""

$args = @(
    "/Create",
    "/F",
    "/TN", $TaskName,
    "/TR", $runnerCommand,
    "/RL", "LIMITED"
)

if (-not [string]::IsNullOrWhiteSpace($RunAsUser)) {
    $args += @("/RU", $RunAsUser)
}

if ($Trigger -eq "DAILY") {
    $args += @("/SC", "DAILY", "/ST", $StartTime)
} else {
    $args += @("/SC", "ONLOGON")
}

if ([string]::IsNullOrWhiteSpace($RunAsUser)) {
    Write-Host ("Creating/updating task '{0}' for current user context..." -f $TaskName)
} else {
    Write-Host ("Creating/updating task '{0}' for user '{1}'..." -f $TaskName, $RunAsUser)
}
& schtasks.exe @args
if ($LASTEXITCODE -ne 0) {
    throw "schtasks failed with exit code $LASTEXITCODE"
}

Write-Host ""
Write-Host "Task created/updated."
Write-Host ("Check: schtasks /Query /TN `"{0}`" /V /FO LIST" -f $TaskName)
Write-Host ("Run now: schtasks /Run /TN `"{0}`"" -f $TaskName)
