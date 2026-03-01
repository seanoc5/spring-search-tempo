param(
    [ValidateSet("msi", "exe")]
    [string]$InstallerType = "msi",

    [ValidateSet("per-user", "per-machine")]
    [string]$InstallMode = "per-user",

    [string]$DbHost = "minti9",
    [int]$DbPort = 5432,
    [string]$DbName = "tempo",
    [string]$DbUser = "tempo",
    [string]$DbPassword = "password",

    [string]$AppVersion = "",
    [string]$Vendor = "Oconeco",
    [string]$InstallDir = "SpringSearchTempo",

    [switch]$WinConsole
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-ProjectVersion {
    param([string]$BuildFilePath)

    if (-not (Test-Path $BuildFilePath)) {
        return "0.1.9"
    }

    $match = Select-String -Path $BuildFilePath -Pattern '^\s*version\s*=\s*"([^"]+)"' | Select-Object -First 1
    if (-not $match) {
        return "0.1.9"
    }

    return $match.Matches[0].Groups[1].Value
}

function Normalize-WindowsAppVersion {
    param([string]$RawVersion)

    $m = [regex]::Match($RawVersion, '\d+(\.\d+){0,2}')
    if (-not $m.Success) {
        return "0.1.9"
    }

    $parts = [System.Collections.Generic.List[string]]::new()
    $m.Value.Split(".") | ForEach-Object { $parts.Add($_) }
    while ($parts.Count -lt 3) {
        $parts.Add("0")
    }

    return ($parts[0..2] -join ".")
}

if (-not $IsWindows) {
    throw "This script must run on Windows because jpackage can only build Windows installers on Windows."
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Push-Location $repoRoot
try {
    foreach ($cmd in @("jpackage", "java")) {
        if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
            throw "Required command '$cmd' is not available in PATH."
        }
    }

    Write-Host "Staging boot jar for jpackage..."
    & .\gradlew.bat stageWindowsJpackage
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle task 'stageWindowsJpackage' failed."
    }

    $inputDir = Join-Path $repoRoot "build\jpackage\input"
    $distDir = Join-Path $repoRoot "build\jpackage\dist"
    New-Item -Path $distDir -ItemType Directory -Force | Out-Null

    $jarPath = Join-Path $inputDir "spring-search-tempo.jar"
    if (-not (Test-Path $jarPath)) {
        throw "Expected staged jar at '$jarPath' but it was not found."
    }

    if ([string]::IsNullOrWhiteSpace($AppVersion)) {
        $rawProjectVersion = Resolve-ProjectVersion -BuildFilePath (Join-Path $repoRoot "build.gradle.kts")
        $AppVersion = Normalize-WindowsAppVersion -RawVersion $rawProjectVersion
    } else {
        $AppVersion = Normalize-WindowsAppVersion -RawVersion $AppVersion
    }

    $dbUrl = "jdbc:postgresql://{0}:{1}/{2}" -f $DbHost, $DbPort, $DbName

    $jpackageArgs = @(
        "--type", $InstallerType,
        "--name", "SpringSearchTempo",
        "--dest", $distDir,
        "--input", $inputDir,
        "--main-jar", "spring-search-tempo.jar",
        "--app-version", $AppVersion,
        "--vendor", $Vendor,
        "--description", "Spring Search Tempo",
        "--install-dir", $InstallDir,
        "--win-menu",
        "--win-shortcut",
        "--win-dir-chooser",
        "--win-menu-group", "Spring Search Tempo",
        "--win-upgrade-uuid", "5e95fc38-0955-4a8d-b83b-b2d919b9b08a",
        "--java-options", "-Xms512m",
        "--java-options", "-Xmx2g",
        "--java-options", "-Dspring.profiles.active=windows",
        "--java-options", "-DJDBC_DATABASE_URL=$dbUrl",
        "--java-options", "-DJDBC_DATABASE_USERNAME=$DbUser",
        "--java-options", "-DJDBC_DATABASE_PASSWORD=$DbPassword",
        "--java-options", "-Dspring.output.ansi.enabled=never"
    )

    if ($InstallMode -eq "per-user") {
        $jpackageArgs += "--win-per-user-install"
    }

    if ($WinConsole) {
        $jpackageArgs += "--win-console"
    }

    Write-Host "Building $InstallerType installer (version $AppVersion)..."
    & jpackage @jpackageArgs
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed."
    }

    Write-Host ""
    Write-Host "Installer created in: $distDir"
    Get-ChildItem -Path $distDir | Sort-Object LastWriteTime -Descending | Select-Object -First 5 | ForEach-Object {
        Write-Host ("  - {0}" -f $_.FullName)
    }
}
finally {
    Pop-Location
}
