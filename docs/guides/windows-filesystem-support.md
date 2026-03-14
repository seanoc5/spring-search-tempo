# Windows Filesystem Support

This guide explains how Spring Search Tempo automatically detects and adapts to different operating systems, with full support for Windows 11 filesystem crawling.

## Overview

Spring Search Tempo now includes **automatic OS detection** and **OS-specific crawl configurations**. When you run the application on Windows, Linux, or macOS, it automatically loads the appropriate crawl configuration for that platform.

## How It Works

### 1. Automatic OS Detection

The `OsProfileActivator` (EnvironmentPostProcessor) runs early in the Spring Boot lifecycle to:
- Detect the current operating system
- Activate the appropriate Spring profile (`windows`, `linux`, or `mac`)
- Log system information for debugging

**Location**: `src/main/kotlin/com/oconeco/spring_search_tempo/base/config/OsProfileActivator.kt`

### 2. OS Detection Service

The `OsDetectionService` provides runtime OS and filesystem information:

```kotlin
@Service
class OsDetectionService {
    val osType: OsType                          // WINDOWS, LINUX, MAC, UNKNOWN
    val currentFilesystemType: FilesystemType   // NTFS, EXT4, APFS, etc.

    fun isWindows(): Boolean
    fun isLinux(): Boolean
    fun isMac(): Boolean
    fun getFilesystemRoots(): List<Path>        // C:\, D:\ on Windows; / on Unix
    fun getFilesystemInfo(): Map<String, FilesystemType>
}
```

**Location**: `src/main/kotlin/com/oconeco/spring_search_tempo/base/service/OsDetectionService.kt`

### 3. Profile-Specific Configuration Files

Crawl configurations are stored in OS-specific YAML files:

| Profile | Configuration File | Description |
|---------|-------------------|-------------|
| `linux` | `application-linux.yml` | Linux/Unix filesystem paths (`/home`, `/opt`, `/var`, etc.) |
| `windows` | `application-windows.yml` | Windows paths (`C:\`, `D:\`, `%USERPROFILE%`, etc.) |
| `mac` | `application-mac.yml` | macOS paths (future implementation) |
| (none) | `application.yml` | Fallback configuration (currently Linux-based) |

## Windows-Specific Configuration

### Enabled Crawls (Default)

When running on Windows, these crawls are enabled by default:

#### User Content Directories
- **WIN_USER_DOCUMENTS**: `%USERPROFILE%\Documents`
- **WIN_USER_PICTURES**: `%USERPROFILE%\Pictures`
- **WIN_USER_VIDEOS**: `%USERPROFILE%\Videos`
- **WIN_USER_MUSIC**: `%USERPROFILE%\Music`
- **WIN_USER_DOWNLOADS**: `%USERPROFILE%\Downloads`
- **WIN_USER_DESKTOP**: `%USERPROFILE%\Desktop`
- **WIN_USER_HOME_GENERAL**: `%USERPROFILE%` (with exclusions for specific directories)

#### Development Directories
- **WIN_C_DRIVE_PROJECTS**:
  - `C:\Projects`
  - `C:\Dev`
  - `C:\Development`
  - `C:\workspace`
  - `C:\code`

#### D:\ Drive
- **WIN_D_DRIVE_ALL**: Full D:\ drive crawl (enabled by default)

### Disabled Crawls (Enable Manually)

These crawls are available but disabled by default:

- **WIN_C_DRIVE_DATA**: `C:\Data`, `C:\Temp` directories
- **WIN_ONEDRIVE**: `%USERPROFILE%\OneDrive` (cloud storage)

### Windows-Specific Skip Patterns

The Windows configuration automatically skips:

**System Directories**:
- `C:\Windows\*`
- `C:\Program Files\*`
- `C:\Program Files (x86)\*`
- `C:\ProgramData\*`
- `C:\System Volume Information\*`
- `C:\$Recycle.Bin\*`

**User Temp/Cache**:
- `%USERPROFILE%\AppData\Local\Temp\*`
- `%USERPROFILE%\AppData\Local\Microsoft\Windows\*`
- `.cache\*`
- `.thumbnails\*`

**Development Artifacts**:
- `.git\*`
- `.gradle\*`
- `.idea\*`
- `build\*`
- `node_modules\*`
- `vendor\*`

**File Types**:
- `.exe`, `.msi`, `.dll`, `.sys` (Windows executables)
- `.tmp`, `.TMP`, `~$*` (temporary files)
- `.lck`, `.bak` (lock and backup files)

### Pattern Syntax Differences

Note the different path separators in regex patterns:

**Linux** (forward slash):
```yaml
skip:
  - ".*/\\.git/.*"
  - "/var/log/.*"
```

**Windows** (double-backslash for regex escaping):
```yaml
skip:
  - ".*\\\\.git\\\\.*"
  - "C:\\\\Windows\\\\.*"
```

## Usage

### Automatic Profile Activation (Recommended)

Simply run the application - the OS will be detected automatically:

```bash
# Windows
./gradlew bootRun

# Linux/macOS
./gradlew bootRun
```

The appropriate profile will be activated based on your operating system.

### Manual Profile Override

You can explicitly set a profile if needed:

**Command Line**:
```bash
./gradlew bootRun --args='--spring.profiles.active=windows'
```

**Environment Variable**:
```bash
export SPRING_PROFILES_ACTIVE=windows
./gradlew bootRun
```

**application.yml**:
```yaml
spring:
  profiles:
    active: windows
```

### Verify Active Profile

Check the application logs on startup:

```
INFO  c.o.s.b.c.OsProfileActivator - Detected OS: WINDOWS -> Activating profile: windows
INFO  c.o.s.b.c.SystemInfoLogger - ╔══════════════════════════════════════════════════════════════╗
INFO  c.o.s.b.c.SystemInfoLogger - ║          Spring Search Tempo - System Information           ║
INFO  c.o.s.b.c.SystemInfoLogger - ╚══════════════════════════════════════════════════════════════╝
INFO  c.o.s.b.s.OsDetectionService - Detected OS: WINDOWS (from system property: windows 11)
INFO  c.o.s.b.s.OsDetectionService - Current Working Directory Filesystem: NTFS
INFO  c.o.s.b.s.OsDetectionService - === Filesystem Roots ===
INFO  c.o.s.b.s.OsDetectionService -   C:\ -> NTFS
INFO  c.o.s.b.s.OsDetectionService -   D:\ -> NTFS
INFO  c.o.s.b.c.SystemInfoLogger - Active Profiles: windows
INFO  c.o.s.b.c.SystemInfoLogger - Crawl Configuration Source: application-windows.yml
```

## Customization

### Adding New Windows Crawls

Edit `src/main/resources/application-windows.yml`:

```yaml
app:
  crawl:
    crawls:
      - name: "MY_CUSTOM_CRAWL"
        label: "My Custom Directory"
        enabled: true
        start-paths:
          - "C:\\MyCustomPath"
        max-depth: 10
        folder-patterns:
          index:
            - ".*"
        file-patterns:
          index:
            - ".*\\.txt$"
```

### Enabling/Disabling Crawls

**Option 1**: Edit the YAML file directly:
```yaml
- name: "WIN_ONEDRIVE"
  enabled: true  # Change to false to disable
```

**Option 2**: Use the CrawlOrchestrator programmatically:
```kotlin
@Service
class MyCrawlService(
    private val orchestrator: CrawlOrchestrator
) {
    fun runWindowsCrawls() {
        // Run specific crawls by name
        orchestrator.executeCrawlsByName(
            "WIN_USER_DOCUMENTS",
            "WIN_C_DRIVE_PROJECTS"
        )
    }
}
```

### Environment Variable Substitution

Both Windows and Linux configurations support environment variable substitution:

**Windows**:
```yaml
start-paths:
  - "${user.home}\\Documents"          # Resolves to C:\Users\YourName\Documents
  - "${USERPROFILE}\\OneDrive"         # Resolves to C:\Users\YourName\OneDrive
```

**Linux**:
```yaml
start-paths:
  - "${user.home}/Documents"           # Resolves to /home/username/Documents
  - "${HOME}/.config"                  # Resolves to /home/username/.config
```

## Filesystem Root Detection

The `OsDetectionService.getFilesystemRoots()` method returns all available filesystem roots:

**Windows**:
```
[C:\, D:\, E:\, ...]
```

**Linux/macOS**:
```
[/]
```

This can be useful for dynamically discovering available drives on Windows systems.

## Architecture

### Component Interaction

```
Application Startup
    ↓
OsProfileActivator (EnvironmentPostProcessor)
    ↓ (detects OS)
    ↓
Spring Profile Activated ("windows" | "linux" | "mac")
    ↓
application-{profile}.yml loaded
    ↓
CrawlConfiguration populated with OS-specific paths
    ↓
FsCrawlJob executes with appropriate configuration
    ↓
SystemInfoLogger logs OS and filesystem info
```

### Files Created/Modified

**New Files**:
- `src/main/kotlin/com/oconeco/spring_search_tempo/base/config/OsProfileActivator.kt`
- `src/main/kotlin/com/oconeco/spring_search_tempo/base/service/OsDetectionService.kt`
- `src/main/kotlin/com/oconeco/spring_search_tempo/base/config/SystemInfoLogger.kt`
- `src/main/resources/application-windows.yml`
- `src/main/resources/application-linux.yml`
- `src/main/resources/META-INF/spring.factories`
- `docs/guides/windows-filesystem-support.md` (this file)

**Modified Files**:
- `src/main/resources/application.yml` (crawl config moved to profile-specific files)
- `build.gradle.kts` (added duplicate handling for bootJar task)

## Cross-Platform Compatibility

The application uses Java NIO's `Path` and `Files` APIs, which are fully cross-platform:

- **Path Separators**: Automatically handled (`\` on Windows, `/` on Unix)
- **POSIX Attributes**: Gracefully skipped on Windows (owner, group, permissions)
- **Filesystem Types**: Detected using `FileStore.type()`
- **Symbolic Links**: Configurable via `follow-links` setting

## Testing

### Test OS Detection

You can verify OS detection by checking the logs or using the OsDetectionService directly:

```kotlin
@Service
class MyTestService(
    private val osDetectionService: OsDetectionService
) {
    fun testOsDetection() {
        println("OS: ${osDetectionService.osType}")
        println("Filesystem: ${osDetectionService.currentFilesystemType}")
        println("Roots: ${osDetectionService.getFilesystemRoots()}")
        println("Is Windows? ${osDetectionService.isWindows()}")
    }
}
```

### Run with Different Profiles

Test Windows configuration on Linux (for development):
```bash
./gradlew bootRun --args='--spring.profiles.active=windows'
```

Note: This will load Windows paths, which may not exist on Linux, but is useful for testing configuration loading.

## Troubleshooting

### Profile Not Activated

**Problem**: Application uses default configuration instead of OS-specific.

**Solution**: Check logs for "Detected OS" message. If missing, ensure:
- `META-INF/spring.factories` exists
- OsProfileActivator is registered correctly
- No explicit profile is set (which overrides auto-detection)

### Windows Paths Not Found

**Problem**: Crawl fails with "path not found" on Windows.

**Solution**:
- Verify paths exist: `%USERPROFILE%\Documents`, etc.
- Check environment variable expansion: `${user.home}`
- Enable debug logging: `logging.level.com.oconeco=DEBUG`

### D:\ Drive Not Available

**Problem**: D:\ drive crawl fails because drive doesn't exist.

**Solution**: Disable the crawl in `application-windows.yml`:
```yaml
- name: "WIN_D_DRIVE_ALL"
  enabled: false
```

### Regex Pattern Errors

**Problem**: Path patterns don't match on Windows.

**Solution**: Remember to escape backslashes in YAML regex:
- Wrong: `C:\Windows\.*`
- Correct: `C:\\\\Windows\\\\.*` (4 backslashes in YAML for 2 in regex)

## Future Enhancements

Planned improvements:
- macOS-specific configuration (`application-mac.yml`)
- Network drive support (UNC paths: `\\server\share`)
- Registry-based Windows path detection
- OneDrive/Dropbox/Google Drive auto-discovery
- WSL (Windows Subsystem for Linux) detection
- Multi-profile support (e.g., `windows,development`)

## See Also

- [Crawl Configuration Guide](crawl-configuration.md) - Detailed crawl configuration reference
- [Architecture: Module Design](../architecture/module-design.md) - Overall system architecture
- [Commands Reference](../reference/commands.md) - Command-line operations

---

**Last Updated**: 2026-03-14
**Version**: 0.2.1
**Contributors**: Claude Code, Sean
