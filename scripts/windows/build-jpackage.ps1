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

    [string]$JavaHome = "",

    [switch]$WinConsole,

    [switch]$GitHubRelease,
    [string]$ReleaseNotes = "",
    [switch]$PreRelease,
    [switch]$Draft
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

if ($PSVersionTable.PSEdition -eq "Core" -and -not $IsWindows) {
    throw "This script must run on Windows because jpackage can only build Windows installers on Windows."
}

function Get-JavaVersionLine {
    param([string]$JavaExePath)
    # java -version writes to stderr; redirect at cmd.exe level to avoid PS 5.1 ErrorAction issues
    $output = cmd /c "`"$JavaExePath`" -version 2>&1"
    return $output | Where-Object { $_ -match 'version' } | Select-Object -First 1
}

function Resolve-Jdk21Home {
    param([string]$ExplicitHome)

    # 1. Explicit parameter
    if (-not [string]::IsNullOrWhiteSpace($ExplicitHome)) {
        if (Test-Path (Join-Path $ExplicitHome "bin\jpackage.exe")) {
            return $ExplicitHome
        }
        throw "Specified JavaHome '$ExplicitHome' does not contain bin\jpackage.exe"
    }

    # 2. JAVA_HOME environment variable (if JDK 21+)
    $envHome = $env:JAVA_HOME
    if (-not [string]::IsNullOrWhiteSpace($envHome)) {
        $javaExe = Join-Path $envHome "bin\java.exe"
        if (Test-Path $javaExe) {
            $verLine = Get-JavaVersionLine -JavaExePath $javaExe
            if ($verLine -match '"(\d+)') {
                $major = [int]$Matches[1]
                if ($major -ge 21 -and (Test-Path (Join-Path $envHome "bin\jpackage.exe"))) {
                    return $envHome
                }
            }
        }
    }

    # 3. Gradle auto-provisioned JDK 21
    $gradleJdksDir = Join-Path $env:USERPROFILE ".gradle\jdks"
    if (Test-Path $gradleJdksDir) {
        $candidates = Get-ChildItem -Path $gradleJdksDir -Directory |
            Where-Object { $_.Name -match '21' } |
            Sort-Object LastWriteTime -Descending
        foreach ($dir in $candidates) {
            $jpackageExe = Join-Path $dir.FullName "bin\jpackage.exe"
            if (Test-Path $jpackageExe) {
                return $dir.FullName
            }
        }
    }

    # 4. jpackage on PATH (if JDK 21+)
    $pathJpackage = Get-Command jpackage -ErrorAction SilentlyContinue
    if ($pathJpackage) {
        $pathJava = Join-Path (Split-Path $pathJpackage.Source) "java.exe"
        if (Test-Path $pathJava) {
            $verLine = Get-JavaVersionLine -JavaExePath $pathJava
            if ($verLine -match '"(\d+)') {
                $major = [int]$Matches[1]
                if ($major -ge 21) {
                    return (Split-Path (Split-Path $pathJpackage.Source))
                }
            }
        }
    }

    throw @"
JDK 21+ with jpackage is required but was not found.
Searched: -JavaHome parameter, JAVA_HOME env var, Gradle JDK cache (~/.gradle/jdks), PATH.
Install JDK 21 or pass -JavaHome 'C:\path\to\jdk-21'.
"@
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Push-Location $repoRoot
try {
    $jdk21Home = Resolve-Jdk21Home -ExplicitHome $JavaHome
    $jpackageExe = Join-Path $jdk21Home "bin\jpackage.exe"
    $javaExe = Join-Path $jdk21Home "bin\java.exe"
    $javaVersion = Get-JavaVersionLine -JavaExePath $javaExe
    Write-Host "Using JDK: $jdk21Home"
    Write-Host "  Version: $javaVersion"

    # Set JAVA_HOME so gradlew.bat uses this JDK (system JDK may be unsupported by Gradle)
    $prevJavaHome = $env:JAVA_HOME
    $env:JAVA_HOME = $jdk21Home

    Write-Host "Staging boot jar for jpackage..."
    & .\gradlew.bat stageWindowsJpackage
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Gradle daemon build failed, retrying with --no-daemon..."
        & .\gradlew.bat --no-daemon stageWindowsJpackage
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle task 'stageWindowsJpackage' failed."
        }
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
        "--java-options", "-Xms128m",
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
    & $jpackageExe @jpackageArgs
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed."
    }

    $installerFile = Get-ChildItem -Path $distDir -Filter "*.$InstallerType" |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1

    Write-Host ""
    Write-Host "Installer created in: $distDir"
    Get-ChildItem -Path $distDir | Sort-Object LastWriteTime -Descending | Select-Object -First 5 | ForEach-Object {
        Write-Host ("  - {0}" -f $_.FullName)
    }

    # GitHub Release
    if ($GitHubRelease) {
        $ghCmd = Get-Command gh -ErrorAction SilentlyContinue
        if (-not $ghCmd) {
            throw "GitHub CLI (gh) is required for -GitHubRelease. Install from https://cli.github.com"
        }

        if (-not $installerFile) {
            throw "No .$InstallerType file found in $distDir to upload."
        }

        $tag = "v$AppVersion"
        Write-Host ""
        Write-Host "Creating GitHub release $tag..."

        $ghArgs = @("release", "create", $tag, $installerFile.FullName, "--title", "v$AppVersion")

        if (-not [string]::IsNullOrWhiteSpace($ReleaseNotes)) {
            $ghArgs += @("--notes", $ReleaseNotes)
        } else {
            $ghArgs += "--generate-notes"
        }

        if ($PreRelease) { $ghArgs += "--prerelease" }
        if ($Draft)      { $ghArgs += "--draft" }

        & gh @ghArgs
        if ($LASTEXITCODE -ne 0) {
            throw "GitHub release creation failed."
        }

        Write-Host "Release $tag published successfully."
    }
}
finally {
    $env:JAVA_HOME = $prevJavaHome
    Pop-Location
}
