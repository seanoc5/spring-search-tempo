package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.domain.DiscoveryRuleGroup
import com.oconeco.spring_search_tempo.base.domain.DiscoveryRuleOperation
import com.oconeco.spring_search_tempo.base.domain.SuggestedStatus
import com.oconeco.spring_search_tempo.base.repos.DiscoveryClassificationRuleRepository
import kotlin.math.roundToInt
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Applies opinionated onboarding templates for discovery/classification:
 * 1) OS baseline rules
 * 2) Profile overlay (PROGRAMMER, MANAGER, POWER_USER)
 * 3) Parent status inheritance unless child overrides
 *
 * Rule values are loaded dynamically from DB (`discovery_classification_rule`) and merged
 * against built-in defaults. DB rules can ADD or REMOVE tokens from each rule group.
 */
@Component
class DiscoveryTemplateClassifier(
    private val discoveryRuleRepository: DiscoveryClassificationRuleRepository? = null
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun buildPlan(
        osType: String,
        rootPaths: List<String>,
        folders: List<TemplateFolderInput>,
        forcedProfile: DiscoveryUserProfile? = null
    ): DiscoveryTemplatePlan {
        val rules = loadEffectiveRules()

        val normalizedFolders = folders
            .filter { it.path.isNotBlank() }
            .map {
                val normalizedPath = normalizePath(it.path)
                val derivedName = it.name.ifBlank { pathSegments(normalizedPath).lastOrNull().orEmpty() }
                NormalizedFolder(
                    originalPath = it.path,
                    normalizedPath = normalizedPath,
                    segments = pathSegments(normalizedPath),
                    normalizedName = derivedName.trim().lowercase(),
                    depth = it.depth
                )
            }
            .sortedWith(compareBy<NormalizedFolder> { it.depth }.thenBy { it.normalizedPath.length })

        val guess = guessProfile(normalizedFolders, rules)
        val profile = forcedProfile ?: guess.profile
        val reason = if (forcedProfile != null) "Manually selected profile: ${forcedProfile.name}" else guess.reason
        val confidencePercent = if (forcedProfile != null) 100 else guess.confidencePercent

        val statusByNormalizedPath = linkedMapOf<String, SuggestedStatus>()
        val statusByOriginalPath = linkedMapOf<String, SuggestedStatus>()

        normalizedFolders.forEach { folder ->
            val inherited = parentPath(folder.normalizedPath)?.let { statusByNormalizedPath[it] }
            val explicit = explicitStatus(folder, osType, rootPaths, profile, rules)
            val status = explicit ?: inherited ?: SuggestedStatus.LOCATE

            statusByNormalizedPath[folder.normalizedPath] = status
            statusByOriginalPath[folder.originalPath] = status
        }

        val counts = mutableMapOf<SuggestedStatus, Int>()
        statusByOriginalPath.values.forEach { status ->
            counts[status] = (counts[status] ?: 0) + 1
        }

        return DiscoveryTemplatePlan(
            profile = profile,
            confidencePercent = confidencePercent,
            reason = reason,
            statusByPath = statusByOriginalPath,
            counts = counts
        )
    }

    private fun explicitStatus(
        folder: NormalizedFolder,
        osType: String,
        rootPaths: List<String>,
        profile: DiscoveryUserProfile,
        rules: EffectiveRules
    ): SuggestedStatus? {
        val os = canonicalOsType(osType)

        // 1. SKIP: Only truly disposable/temp content (recycle bin, temp, caches)
        if (isOsSkip(folder, os, rules)) return SuggestedStatus.SKIP
        if (isProfileSkip(folder, profile, rules)) return SuggestedStatus.SKIP

        // 2. Explicit LOCATE: System folders, 3rd party code repos (protects against parent INDEX override)
        if (isThirdPartyCodeRepo(folder, os)) return SuggestedStatus.LOCATE
        if (isOsSystemFolder(folder, os, rules)) return SuggestedStatus.LOCATE
        if (isOsSystemRoot(folder, os, rootPaths, rules)) return SuggestedStatus.LOCATE

        // 3. INDEX: User content that should have text extracted
        if (isOfficeFolder(folder, rules)) return SuggestedStatus.INDEX
        if (isMediaFolder(folder, rules)) return SuggestedStatus.LOCATE  // Media = metadata only
        if (isConfigOrLogsFolder(folder, rules)) return SuggestedStatus.INDEX

        // 4. ANALYZE: Source code and documentation worth NLP processing
        if (isManualHelpFolder(folder, rules)) return SuggestedStatus.ANALYZE
        if (isProfileAnalyze(folder, profile, rules)) return SuggestedStatus.ANALYZE

        // 5. Profile-specific LOCATE overrides
        if (isProfileLocate(folder, profile, rules)) return SuggestedStatus.LOCATE

        // Default: null means inherit from parent, ultimate fallback is LOCATE
        return null
    }

    /**
     * Third-party code repositories that should stay LOCATE even if parent is INDEX/ANALYZE.
     * These contain downloaded/installed code, not user-created content.
     */
    private fun isThirdPartyCodeRepo(folder: NormalizedFolder, os: CanonicalOsType): Boolean {
        val p = folder.normalizedPath
        val name = folder.normalizedName

        // Common package managers and their lib directories
        if (name == "node_modules") return true
        if (name == "site-packages" || name == "dist-packages") return true
        if (name == "vendor" && p.contains("/composer/")) return true
        if (name == ".cargo" || name == ".rustup") return true
        if (name == ".m2" || name == ".gradle") return true
        if (name == ".nuget" || name == "packages" && p.contains("/nuget/")) return true

        // Python installations
        if (p.contains("/python") && (p.contains("/lib/") || p.contains("/libs/"))) return true
        if (p.matches(Regex(".*/python\\d+/lib.*"))) return true

        // Windows-specific
        if (os == CanonicalOsType.WINDOWS) {
            if (p.contains("/program files/nodejs/node_modules")) return true
            if (p.contains("/program files/python")) return true
            if (p.matches(Regex(".*/python\\d+/lib.*"))) return true
        }

        // Ruby gems, Go modules, etc.
        if (name == "gems" && p.contains("/ruby/")) return true
        if (name == "pkg" && p.contains("/go/")) return true

        return false
    }

    /**
     * OS system folders that should be LOCATE (not SKIP).
     * These contain system files but might have useful metadata.
     */
    private fun isOsSystemFolder(folder: NormalizedFolder, os: CanonicalOsType, rules: EffectiveRules): Boolean {
        val p = folder.normalizedPath
        val name = folder.normalizedName

        if (os == CanonicalOsType.WINDOWS) {
            // Windows system folders - LOCATE not SKIP
            if (name.startsWith("\$") && name != "\$recycle.bin") return true  // $SysReset, $Windows.~WS, etc.
            if (name == "intel") return true
            if (name == "perflogs") return true
            if (name == "recovery") return true
            if (p.contains("/windows/system32") && !p.contains("/temp")) return true
            if (p.contains("/windows/syswow64")) return true
            // Hibernation and system files location
            if (name == "system volume information") return true
        }

        if (os == CanonicalOsType.MACOS) {
            if (p.startsWith("/system") && !p.contains("/caches")) return true
            if (p.startsWith("/private/var") && !p.contains("/tmp") && !p.contains("/folders")) return true
        }

        if (os == CanonicalOsType.LINUX) {
            if (p.startsWith("/boot")) return true
            if (p.startsWith("/lib") || p.startsWith("/lib64")) return true
            if (p.startsWith("/sbin") || p.startsWith("/bin")) return true
        }

        return false
    }

    private fun guessProfile(folders: List<NormalizedFolder>, rules: EffectiveRules): ProfileGuess {
        var programmer = 0
        var manager = 0
        var powerUser = 1 // small baseline to avoid zero-confidence fallback

        folders.forEach { folder ->
            val segments = folder.segments
            if (segments.any { it in rules.programmerHints }) programmer += 2
            if (segments.any { it in rules.programmerArtifacts }) programmer += 3
            if (segments.any { it in rules.managerHints }) manager += 2
            if (segments.any { it in rules.officeHints }) manager += 1
            if (segments.any { it in rules.powerUserHints }) powerUser += 1
        }

        val profile = when {
            programmer >= (manager + 3) && programmer >= 4 -> DiscoveryUserProfile.PROGRAMMER
            manager >= 3 && programmer <= 2 -> DiscoveryUserProfile.MANAGER
            else -> DiscoveryUserProfile.POWER_USER
        }

        val winning = when (profile) {
            DiscoveryUserProfile.PROGRAMMER -> programmer
            DiscoveryUserProfile.MANAGER -> manager
            DiscoveryUserProfile.POWER_USER -> powerUser
        }
        val total = (programmer + manager + powerUser).coerceAtLeast(1)
        val confidence = ((winning.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(55, 99)

        val reason = "Signals: programmer=$programmer, manager=$manager, power_user=$powerUser"
        return ProfileGuess(profile = profile, confidencePercent = confidence, reason = reason)
    }

    /**
     * SKIP: Only truly disposable content - recycle bins, temp files, caches.
     * System folders should be LOCATE (handled by isOsSystemFolder), not SKIP.
     */
    private fun isOsSkip(folder: NormalizedFolder, os: CanonicalOsType, rules: EffectiveRules): Boolean {
        return when (os) {
            CanonicalOsType.WINDOWS -> {
                val p = folder.normalizedPath
                folder.normalizedName in rules.windowsSkip ||
                    hasSegment(folder, "\$recycle.bin") ||
                    // Temp and cache folders only
                    p.contains("/windows/temp") ||
                    p.contains("/windows/prefetch") ||
                    p.contains("/appdata/local/temp") ||
                    p.contains("/appdata/local/packages/") && p.contains("/tempstate") ||
                    p.contains("/appdata/local/microsoft/windows/temporary") ||
                    // Browser caches
                    p.contains("/cache2/entries") ||
                    p.contains("/code cache/") ||
                    p.contains("/gpucache/") ||
                    p.contains("/shadercache/")
            }
            CanonicalOsType.MACOS -> {
                val p = folder.normalizedPath
                folder.normalizedName in rules.macosSkip ||
                    hasSegment(folder, ".trashes") ||
                    hasSegment(folder, ".spotlight-v100") ||
                    // Temp and cache only
                    p.startsWith("/private/tmp") ||
                    p.startsWith("/private/var/folders") ||  // Per-user temp
                    p.startsWith("/private/var/vm") ||       // Virtual memory
                    p.contains("/library/caches") ||
                    p.contains("/caches/")
            }
            CanonicalOsType.LINUX -> {
                val p = folder.normalizedPath
                folder.normalizedName in rules.linuxSkip ||
                    // Virtual filesystems
                    p.startsWith("/proc") ||
                    p.startsWith("/sys") ||
                    p.startsWith("/dev") ||
                    p.startsWith("/run") ||
                    // Temp and cache
                    p.startsWith("/tmp") ||
                    p.startsWith("/var/tmp") ||
                    p.startsWith("/var/cache") ||
                    p.startsWith("/lost+found") ||
                    hasSegment(folder, ".cache")
            }
        }
    }

    private fun isProfileSkip(folder: NormalizedFolder, profile: DiscoveryUserProfile, rules: EffectiveRules): Boolean {
        val skipSet = when (profile) {
            DiscoveryUserProfile.PROGRAMMER -> rules.programmerArtifacts
            DiscoveryUserProfile.MANAGER -> rules.managerSkip
            DiscoveryUserProfile.POWER_USER -> rules.powerUserSkip
        }
        return folder.segments.any { it in skipSet }
    }

    private fun isProfileAnalyze(folder: NormalizedFolder, profile: DiscoveryUserProfile, rules: EffectiveRules): Boolean {
        val analyzeSet = when (profile) {
            DiscoveryUserProfile.PROGRAMMER -> rules.programmerAnalyze
            DiscoveryUserProfile.MANAGER -> rules.managerAnalyze
            DiscoveryUserProfile.POWER_USER -> rules.powerUserAnalyze
        }
        return folder.normalizedName in analyzeSet
    }

    private fun isProfileLocate(folder: NormalizedFolder, profile: DiscoveryUserProfile, rules: EffectiveRules): Boolean {
        val locateSet = when (profile) {
            DiscoveryUserProfile.PROGRAMMER -> rules.programmerLocate
            DiscoveryUserProfile.MANAGER -> rules.managerLocate
            DiscoveryUserProfile.POWER_USER -> rules.powerUserLocate
        }
        return folder.normalizedName in locateSet
    }

    private fun isOfficeFolder(folder: NormalizedFolder, rules: EffectiveRules): Boolean =
        folder.normalizedName in rules.officeHints

    private fun isMediaFolder(folder: NormalizedFolder, rules: EffectiveRules): Boolean =
        folder.normalizedName in rules.mediaHints

    private fun isConfigOrLogsFolder(folder: NormalizedFolder, rules: EffectiveRules): Boolean =
        folder.normalizedName in rules.configOrLogHints

    private fun isManualHelpFolder(folder: NormalizedFolder, rules: EffectiveRules): Boolean =
        folder.normalizedName in rules.manualHelpHints

    private fun isOsSystemRoot(
        folder: NormalizedFolder,
        os: CanonicalOsType,
        rootPaths: List<String>,
        rules: EffectiveRules
    ): Boolean {
        val p = folder.normalizedPath
        if (os == CanonicalOsType.WINDOWS) {
            if (p.contains("/program files") || p.contains("/programdata")) return true
            if (folder.normalizedName in rules.windowsSystemRoots) return true
        }
        if (os == CanonicalOsType.MACOS) {
            if (p.startsWith("/library") || p.startsWith("/applications")) return true
            if (folder.normalizedName in rules.macosSystemRoots) return true
        }
        if (os == CanonicalOsType.LINUX) {
            if (p.startsWith("/usr") || p.startsWith("/var") || p.startsWith("/opt")) return true
            if (folder.normalizedName in rules.linuxSystemRoots) return true
        }

        // If this is exactly one of the declared roots, leave at LOCATE unless a rule escalates it.
        val normalizedRoots = rootPaths.map { normalizePath(it) }
        return normalizedRoots.any { it == p }
    }

    private fun hasSegment(folder: NormalizedFolder, segment: String): Boolean =
        folder.segments.any { it == segment.lowercase() }

    private fun normalizePath(path: String): String {
        val unified = path.replace('\\', '/').trim()
        return unified.replace(Regex("/+"), "/").trimEnd('/').lowercase()
    }

    private fun pathSegments(path: String): List<String> =
        path.split('/').map { it.trim() }.filter { it.isNotBlank() }

    private fun parentPath(path: String): String? {
        val idx = path.lastIndexOf('/')
        if (idx <= 0) return null
        return path.substring(0, idx)
    }

    private fun canonicalOsType(osType: String): CanonicalOsType {
        val os = osType.trim().lowercase()
        return when {
            os.contains("win") -> CanonicalOsType.WINDOWS
            os.contains("mac") || os.contains("osx") || os.contains("darwin") -> CanonicalOsType.MACOS
            else -> CanonicalOsType.LINUX
        }
    }

    private fun loadEffectiveRules(): EffectiveRules {
        val defaults = defaultRules()
        return runCatching {
            val dbRules = discoveryRuleRepository?.findByEnabledTrueOrderByRuleGroupAscIdAsc().orEmpty()
            if (dbRules.isEmpty()) {
                defaults
            } else {
                EffectiveRules(
                    officeHints = applyOperations(defaults.officeHints, dbRules, DiscoveryRuleGroup.OFFICE_HINT),
                    mediaHints = applyOperations(defaults.mediaHints, dbRules, DiscoveryRuleGroup.MEDIA_HINT),
                    manualHelpHints = applyOperations(defaults.manualHelpHints, dbRules, DiscoveryRuleGroup.MANUAL_HELP_HINT),
                    configOrLogHints = applyOperations(defaults.configOrLogHints, dbRules, DiscoveryRuleGroup.CONFIG_OR_LOG_HINT),
                    programmerHints = applyOperations(defaults.programmerHints, dbRules, DiscoveryRuleGroup.PROGRAMMER_HINT),
                    managerHints = applyOperations(defaults.managerHints, dbRules, DiscoveryRuleGroup.MANAGER_HINT),
                    powerUserHints = applyOperations(defaults.powerUserHints, dbRules, DiscoveryRuleGroup.POWER_USER_HINT),
                    programmerArtifacts = applyOperations(defaults.programmerArtifacts, dbRules, DiscoveryRuleGroup.PROGRAMMER_ARTIFACT),
                    programmerAnalyze = applyOperations(defaults.programmerAnalyze, dbRules, DiscoveryRuleGroup.PROGRAMMER_ANALYZE),
                    programmerLocate = applyOperations(defaults.programmerLocate, dbRules, DiscoveryRuleGroup.PROGRAMMER_LOCATE),
                    managerAnalyze = applyOperations(defaults.managerAnalyze, dbRules, DiscoveryRuleGroup.MANAGER_ANALYZE),
                    managerSkip = applyOperations(defaults.managerSkip, dbRules, DiscoveryRuleGroup.MANAGER_SKIP),
                    managerLocate = applyOperations(defaults.managerLocate, dbRules, DiscoveryRuleGroup.MANAGER_LOCATE),
                    powerUserAnalyze = applyOperations(defaults.powerUserAnalyze, dbRules, DiscoveryRuleGroup.POWER_USER_ANALYZE),
                    powerUserSkip = applyOperations(defaults.powerUserSkip, dbRules, DiscoveryRuleGroup.POWER_USER_SKIP),
                    powerUserLocate = applyOperations(defaults.powerUserLocate, dbRules, DiscoveryRuleGroup.POWER_USER_LOCATE),
                    windowsSkip = applyOperations(defaults.windowsSkip, dbRules, DiscoveryRuleGroup.WINDOWS_SKIP),
                    macosSkip = applyOperations(defaults.macosSkip, dbRules, DiscoveryRuleGroup.MACOS_SKIP),
                    linuxSkip = applyOperations(defaults.linuxSkip, dbRules, DiscoveryRuleGroup.LINUX_SKIP),
                    windowsSystemRoots = applyOperations(defaults.windowsSystemRoots, dbRules, DiscoveryRuleGroup.WINDOWS_SYSTEM_ROOT),
                    macosSystemRoots = applyOperations(defaults.macosSystemRoots, dbRules, DiscoveryRuleGroup.MACOS_SYSTEM_ROOT),
                    linuxSystemRoots = applyOperations(defaults.linuxSystemRoots, dbRules, DiscoveryRuleGroup.LINUX_SYSTEM_ROOT)
                )
            }
        }.getOrElse { ex ->
            log.warn("Falling back to built-in discovery rules: {}", ex.message)
            defaults
        }
    }

    private fun applyOperations(
        defaultValues: Set<String>,
        dbRules: List<com.oconeco.spring_search_tempo.base.domain.DiscoveryClassificationRule>,
        group: DiscoveryRuleGroup
    ): Set<String> {
        val values = defaultValues.toMutableSet()
        dbRules.asSequence()
            .filter { it.ruleGroup == group }
            .forEach { rule ->
                val token = rule.matchValue.trim().lowercase()
                if (token.isBlank()) return@forEach
                if (rule.operation == DiscoveryRuleOperation.REMOVE) {
                    values.remove(token)
                } else {
                    values.add(token)
                }
            }
        return values
    }

    private fun defaultRules(): EffectiveRules =
        EffectiveRules(
            officeHints = setOf(
                "documents", "desktop", "onedrive", "one drive", "work", "reports", "contracts",
                "invoices", "meetings", "presentations", "spreadsheets", "notes"
            ),
            mediaHints = setOf(
                "pictures", "photos", "images", "videos", "movies", "music", "audio", "podcasts"
            ),
            manualHelpHints = setOf(
                "man", "manual", "manuals", "help", "docs", "documentation", "knowledge", "knowledgebase", "readme", "tutorials"
            ),
            configOrLogHints = setOf(
                "etc", "config", "configs", "settings", "preferences", "log", "logs", "journal", ".config"
            ),
            programmerHints = setOf(
                "src", "source", "code", "repo", "repos", "project", "projects", "workspace", "workspaces",
                "scripts", "dev", "development"
            ),
            managerHints = setOf(
                "finance", "legal", "hr", "sales", "marketing", "operations", "contracts", "invoices", "reports"
            ),
            powerUserHints = setOf(
                "automation", "scripts", "homelab", "selfhosted", "media", "backups", "archive", "archives"
            ),
            programmerArtifacts = setOf(
                "node_modules", "vendor", ".venv", "venv", "__pycache__", ".mypy_cache", ".pytest_cache",
                "target", "build", "dist", "out", ".gradle", ".m2", ".npm", ".pnpm-store", ".yarn",
                ".idea", ".vscode", ".terraform", ".next", ".nuxt", ".tox", ".nuget", ".cargo", ".rustup",
                "obj", "bin", "packages"
            ),
            programmerAnalyze = setOf(
                "src", "source", "code", "repo", "repos", "project", "projects", "workspace", "workspaces", "scripts"
            ),
            programmerLocate = setOf("downloads", "archives", "backups"),
            managerAnalyze = setOf(
                "documents", "desktop", "onedrive", "one drive", "reports", "contracts", "invoices", "presentations"
            ),
            managerSkip = setOf(
                "node_modules", "vendor", ".venv", "venv", "target", "build", "dist", "out", ".gradle",
                ".m2", ".cache", "tmp", "temp"
            ),
            managerLocate = setOf("downloads", "archives", "backups"),
            powerUserAnalyze = setOf(
                "documents", "desktop", "notes", "projects", "configs", "help", "manuals", "knowledge"
            ),
            powerUserSkip = setOf(
                "node_modules", "vendor", ".venv", "venv", "__pycache__", "target", "build", "dist", "out", ".cache"
            ),
            powerUserLocate = setOf("downloads", "archives", "backups"),
            windowsSkip = setOf("temp", "prefetch", "winsxs"),
            macosSkip = setOf(".trashes", ".spotlight-v100", "caches"),
            linuxSkip = setOf("tmp", "cache"),
            windowsSystemRoots = setOf("windows", "program files", "programdata"),
            macosSystemRoots = setOf("system", "library", "applications"),
            linuxSystemRoots = setOf("usr", "var", "opt")
        )

    private data class ProfileGuess(
        val profile: DiscoveryUserProfile,
        val confidencePercent: Int,
        val reason: String
    )

    private data class NormalizedFolder(
        val originalPath: String,
        val normalizedPath: String,
        val segments: List<String>,
        val normalizedName: String,
        val depth: Int
    )

    private data class EffectiveRules(
        val officeHints: Set<String>,
        val mediaHints: Set<String>,
        val manualHelpHints: Set<String>,
        val configOrLogHints: Set<String>,
        val programmerHints: Set<String>,
        val managerHints: Set<String>,
        val powerUserHints: Set<String>,
        val programmerArtifacts: Set<String>,
        val programmerAnalyze: Set<String>,
        val programmerLocate: Set<String>,
        val managerAnalyze: Set<String>,
        val managerSkip: Set<String>,
        val managerLocate: Set<String>,
        val powerUserAnalyze: Set<String>,
        val powerUserSkip: Set<String>,
        val powerUserLocate: Set<String>,
        val windowsSkip: Set<String>,
        val macosSkip: Set<String>,
        val linuxSkip: Set<String>,
        val windowsSystemRoots: Set<String>,
        val macosSystemRoots: Set<String>,
        val linuxSystemRoots: Set<String>
    )

    private enum class CanonicalOsType {
        WINDOWS,
        MACOS,
        LINUX
    }
}

enum class DiscoveryUserProfile {
    PROGRAMMER,
    MANAGER,
    POWER_USER
}

data class TemplateFolderInput(
    val path: String,
    val name: String,
    val depth: Int
)

data class DiscoveryTemplatePlan(
    val profile: DiscoveryUserProfile,
    val confidencePercent: Int,
    val reason: String,
    val statusByPath: Map<String, SuggestedStatus>,
    val counts: Map<SuggestedStatus, Int>
)
