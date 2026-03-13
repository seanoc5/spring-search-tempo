param(
    [string]$InstallDir = "C:\Tempo\remote-crawler",
    [string]$Repo = "seanoc5/spring-search-tempo",
    [switch]$Force,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-SemanticVersion {
    param([string]$VersionString)
    # Extract version like "0.5.3" from strings like "remote-crawler-0.5.3.jar" or "remote-crawler-v0.5.3"
    if ($VersionString -match '(\d+\.\d+\.\d+)') {
        return [version]$Matches[1]
    }
    return $null
}

function Get-InstalledVersion {
    param([string]$InstallDir)

    # Look for existing JAR files
    $jars = Get-ChildItem -Path $InstallDir -Filter "remote-crawler-*.jar" -ErrorAction SilentlyContinue
    if (-not $jars) {
        return $null
    }

    # Find highest version among installed JARs
    $highestVersion = $null
    $highestJar = $null
    foreach ($jar in $jars) {
        $version = Get-SemanticVersion $jar.Name
        if ($version -and (-not $highestVersion -or $version -gt $highestVersion)) {
            $highestVersion = $version
            $highestJar = $jar
        }
    }

    return @{
        Version = $highestVersion
        File = $highestJar
    }
}

function Get-LatestRelease {
    param([string]$Repo)

    # Get releases matching remote-crawler pattern
    $releases = gh release list -R $Repo --limit 20 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to fetch releases: $releases"
    }

    # Find latest remote-crawler release
    foreach ($line in $releases -split "`n") {
        if ($line -match 'remote-crawler-v(\d+\.\d+\.\d+)') {
            $tag = "remote-crawler-v$($Matches[1])"
            $version = [version]$Matches[1]
            return @{
                Tag = $tag
                Version = $version
            }
        }
    }

    return $null
}

function Update-RunnerScript {
    param(
        [string]$ScriptPath,
        [string]$NewJarName
    )

    if (-not (Test-Path $ScriptPath)) {
        Write-Warning "Runner script not found: $ScriptPath"
        return $false
    }

    $content = Get-Content $ScriptPath -Raw

    # Update the default JarPath
    $updated = $content -replace 'remote-crawler-\d+\.\d+\.\d+\.jar', $NewJarName

    if ($updated -ne $content) {
        Set-Content -Path $ScriptPath -Value $updated -NoNewline
        return $true
    }

    return $false
}

# Check for gh CLI
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Host "GitHub CLI (gh) is not installed." -ForegroundColor Red
    Write-Host ""
    Write-Host "Install with: winget install GitHub.cli"
    Write-Host "Then run: gh auth login"
    exit 1
}

# Check gh auth status
$authStatus = gh auth status 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "GitHub CLI is not authenticated." -ForegroundColor Red
    Write-Host ""
    Write-Host "Run: gh auth login"
    exit 1
}

Write-Host "Checking for updates..." -ForegroundColor Cyan
Write-Host ""

# Get installed version
$installed = Get-InstalledVersion -InstallDir $InstallDir
if ($installed.Version) {
    Write-Host ("Installed version: {0}" -f $installed.Version)
    Write-Host ("  File: {0}" -f $installed.File.Name)
} else {
    Write-Host "No existing installation found in $InstallDir"
}

# Get latest release
$latest = Get-LatestRelease -Repo $Repo
if (-not $latest) {
    Write-Host "No remote-crawler releases found in $Repo" -ForegroundColor Red
    exit 1
}

Write-Host ("Latest version:    {0}" -f $latest.Version)
Write-Host ("  Tag: {0}" -f $latest.Tag)
Write-Host ""

# Compare versions
$needsUpdate = $false
if (-not $installed.Version) {
    $needsUpdate = $true
    Write-Host "No installation found - will install." -ForegroundColor Yellow
} elseif ($latest.Version -gt $installed.Version) {
    $needsUpdate = $true
    Write-Host ("Update available: {0} -> {1}" -f $installed.Version, $latest.Version) -ForegroundColor Green
} elseif ($Force) {
    $needsUpdate = $true
    Write-Host "Forcing reinstall of current version." -ForegroundColor Yellow
} else {
    Write-Host "Already up to date." -ForegroundColor Green
    exit 0
}

if ($DryRun) {
    Write-Host ""
    Write-Host "[DRY RUN] Would download and install version $($latest.Version)" -ForegroundColor Cyan
    exit 0
}

# Create install directory if needed
if (-not (Test-Path $InstallDir)) {
    Write-Host "Creating directory: $InstallDir"
    New-Item -Path $InstallDir -ItemType Directory -Force | Out-Null
}

# Download new version
Write-Host ""
Write-Host "Downloading $($latest.Tag)..." -ForegroundColor Cyan

$downloadArgs = @(
    "release", "download", $latest.Tag,
    "-R", $Repo,
    "-D", $InstallDir,
    "--pattern", "remote-crawler-*.jar",
    "--pattern", "remote-crawler-*.jar.sha256",
    "--clobber"
)

& gh @downloadArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host "Failed to download release" -ForegroundColor Red
    exit 1
}

# Verify download
$newJarName = "remote-crawler-$($latest.Version).jar"
$newJarPath = Join-Path $InstallDir $newJarName
if (-not (Test-Path $newJarPath)) {
    Write-Host "Downloaded JAR not found: $newJarPath" -ForegroundColor Red
    exit 1
}

Write-Host "Downloaded: $newJarName" -ForegroundColor Green

# Verify checksum if available
$checksumFile = Join-Path $InstallDir "$newJarName.sha256"
if (Test-Path $checksumFile) {
    Write-Host "Verifying checksum..."
    $expectedHash = (Get-Content $checksumFile -Raw).Trim().Split()[0].ToUpper()
    $actualHash = (Get-FileHash $newJarPath -Algorithm SHA256).Hash.ToUpper()

    if ($expectedHash -eq $actualHash) {
        Write-Host "Checksum verified." -ForegroundColor Green
    } else {
        Write-Host "Checksum mismatch!" -ForegroundColor Red
        Write-Host "  Expected: $expectedHash"
        Write-Host "  Actual:   $actualHash"
        Remove-Item $newJarPath -Force
        exit 1
    }
}

# Update runner script
$runnerScript = Join-Path $InstallDir "run-remote-crawler.ps1"
if (Update-RunnerScript -ScriptPath $runnerScript -NewJarName $newJarName) {
    Write-Host "Updated run-remote-crawler.ps1 with new JAR path." -ForegroundColor Green
}

# Clean up old JARs (keep only the new one)
if ($installed.File -and $installed.File.Name -ne $newJarName) {
    $oldJars = Get-ChildItem -Path $InstallDir -Filter "remote-crawler-*.jar" |
               Where-Object { $_.Name -ne $newJarName -and $_.Name -notmatch '\.sha256$' }

    if ($oldJars) {
        Write-Host ""
        Write-Host "Cleaning up old versions:"
        foreach ($oldJar in $oldJars) {
            Write-Host "  Removing: $($oldJar.Name)"
            Remove-Item $oldJar.FullName -Force
            # Also remove old checksum file
            $oldChecksum = "$($oldJar.FullName).sha256"
            if (Test-Path $oldChecksum) {
                Remove-Item $oldChecksum -Force
            }
        }
    }
}

Write-Host ""
Write-Host "Update complete! Version $($latest.Version) is now installed." -ForegroundColor Green
Write-Host ""
Write-Host "Test with: .\run-remote-crawler.ps1 -Mode status"
